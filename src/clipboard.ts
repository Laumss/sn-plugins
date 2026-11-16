import { FileUtils, PluginCommAPI, PluginFileAPI, PluginManager, PointUtils } from 'sn-plugin-lib';
import type { Element, Point, Rect } from 'sn-plugin-lib';
import { registerAction } from './actions';
import type { ToolbarAction } from './actions';
import { CLIP_SLOT_COUNT, TOOL, canonicalPath, clipFilledFlags, fs, log, presets, type ClipData } from './common';
import {
  activeInsertTxn, asResponse, coords, dialog, elements as hostElements, hostState, insertTxn, isHostNoPageError,
  placement, readHostCtx,
} from './host';
import type { HostCtx, HostPageSize, HostResponse, OccupiedCapture } from './host';
import { t } from './i18n';
import { hasLassoSelection, reloadForLasso, reselectLasso } from './lasso';
import {
  hasNativeModule, hostConvertElement2Sticker, notipalClipboard, notipalCommon, notipalMosaic, notipalToolbar,
} from './native';
import type { ClipSlotInfo, ClipboardDirs, NativeEvents, StickerContext } from './native';

const TAG = 'clipboard';

const CLIP_TAG = '[CLIP-DBG]'.slice(1, -1);
const MOSAIC_TAG = 'MOSAIC-CLIP';

const CLIP_SLOT_TOOL = 'clip';

const AVAILABLE = hasNativeModule('NotipalClipboard');
if (!AVAILABLE) { log.e(TAG, 'NotipalClipboard missing, clipboard is unavailable'); }

const DIRS: ClipboardDirs = AVAILABLE ? notipalClipboard.dirs() : { clipboardDir: '', mosaicDir: '' };

function inDir(dir: string, name: string): string {
  return AVAILABLE ? `${dir}/${name}` : '';
}

const STICKER_DIR = DIRS.clipboardDir;

const MOSAIC_HANDOFF_DIR = DIRS.mosaicDir;

export const MOSAIC_LASSO_FILE = inDir(MOSAIC_HANDOFF_DIR, 'clip_lasso.json');
const MOSAIC_PASTE_FILE = inDir(MOSAIC_HANDOFF_DIR, 'paste_strokes.json');

const MOSAIC_SKETCH_LASSO_FILE = inDir(MOSAIC_HANDOFF_DIR, 'sketch_lasso.json');
const MOSAIC_SKETCH_PASTE_FILE = inDir(MOSAIC_HANDOFF_DIR, 'sketch_paste.json');

const pendingMosaicSidecars = new Map<string, Promise<void>>();

const SIDE_MARGIN = 100;

const NOTE_PRESSURE_MAX = 4095;
const NOTE_DEFAULT_THICKNESS = 1000;
const CLIP_PASTE_CONCURRENCY = 8;

const MOSAIC_THICK_FACTOR = 100;
const MOSAIC_THICK_MIN = 100;
const MOSAIC_THICK_MAX = 4000;

const CLIP_PASTE_TXN = 'clip_paste';

const CLIP_PASTE_TIMEOUT_MS = 120_000;

type ClipElement = Element & { factorResize?: number };

type ClipboardCoordinateSpace = 'mosaic-px' | 'note-page-px';

export interface MosaicStroke {
  penStyle?: number;
  sourcePenType?: number;
  sdkPenType?: number;
  penType?: number;
  layerNum?: number;
  penColor?: number;
  angles?: unknown;
  tilt?: unknown;
  sdkThickness?: number;
  sdkFactorResize?: number;
  width?: number;
  pts?: number[];
}

type SidecarFile = {
  pageSize?: { width?: number; height?: number } | null;
  coordinateSpace?: string;
  screenShortPx?: number;
  skippedCards?: number;
  strokes?: MosaicStroke[];
};

export interface MosaicSidecar {
  strokes: MosaicStroke[];

  pageSize: HostPageSize | null;
  coordinateSpace: ClipboardCoordinateSpace;
  screenShortPx: number | null;

  skippedCards: number;
}

type StickerPlacementMetadata = {
  v: 1;
  sourcePageSize: HostPageSize;
  sourceRect: Rect;
};

type ClipMeta = {
  source?: string;
  inkOnly?: boolean;
  hasLinks?: boolean;
  sourcePageSize?: { width?: number; height?: number };
  sourceRect?: Partial<Rect>;
};

function tip(text: string): void {
  dialog.tip(text).catch(e => log.w(TAG, 'tip dialog failed', e));
}

function errorText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

function countsJson(counts: Map<number | string, number>): string {
  return JSON.stringify(Object.fromEntries(counts));
}

function bump<K>(counts: Map<K, number>, key: K): void {
  counts.set(key, (counts.get(key) ?? 0) + 1);
}

function sizeText(size: HostPageSize): string {
  return `${size.width}x${size.height}`;
}

async function ensureStickerDir(): Promise<void> {
  const RNFS = fs.get();
  if (!RNFS) { return; }
  try {
    if (!(await RNFS.exists(STICKER_DIR))) {
      await RNFS.mkdir(STICKER_DIR);
      log.i(CLIP_TAG, `created ${STICKER_DIR}`);
    }
  } catch (e) {
    log.w(CLIP_TAG, 'ensureStickerDir FAILED:', e);
  }
}

export async function refreshTitleClips(): Promise<void> {
  notipalToolbar.setClips(clipFilledFlags((await presets.load()).clips));
}

function clipsChangedBy(result: unknown): boolean {
  return typeof result === 'string' &&
    (result.startsWith('Saved to clip') || (result.startsWith('Clip') && result.endsWith('cleared')));
}

const slotQueues = new Map<string, Promise<unknown>>();

function withClipSlot<T>(slot: string, fn: () => Promise<T>): Promise<T> {
  const prev = slotQueues.get(slot) ?? Promise.resolve();
  const run = prev.then(fn, fn);
  slotQueues.set(slot, run.catch(e => {

    log.w(TAG, `clip slot ${slot} action failed`, e);
  }));
  return run;
}

async function loadClips(): Promise<{ [slot: string]: string | null }> {
  return (await presets.load()).clips;
}

async function setClip(slot: string, path: string | null): Promise<void> {
  await presets.update(p => { p.clips[slot] = path; });
}

const CLIP_CONVERTIBLE_TYPES = new Set([0, 700, 800]);
type ClipboardSkipKind = 'title' | 'image' | 'link' | 'textbox' | 'card' | 'other';

function clipboardSkipLabels(kinds: ClipboardSkipKind[]): string {
  const labels: Record<ClipboardSkipKind, string> = {
    title: t('clip_skip_title'),
    image: t('clip_skip_image'),
    link: t('clip_skip_link'),
    textbox: t('clip_skip_textbox'),
    card: t('clip_skip_card'),
    other: t('clip_skip_other'),
  };
  return Array.from(new Set(kinds)).map(kind => labels[kind]).join(t('clip_skip_separator'));
}

function clipboardSkipError(kind: ClipboardSkipKind): string {
  switch (kind) {
    case 'title': return t('clip_err_title');
    case 'image': return t('clip_err_image');
    case 'link': return t('clip_err_link');
    case 'textbox': return t('clip_err_textbox');
    case 'card': return t('clip_err_card');
    default: return t('clip_err_unsupported', t('clip_skip_other'));
  }
}

type TypeCounts = { [name: string]: unknown };

function clipboardSkipKindsFromCounts(counts: unknown): ClipboardSkipKind[] {
  if (!counts || typeof counts !== 'object') { return []; }
  const c = counts as TypeCounts;

  const n = (name: string): number => Number(c[name]) || 0;
  const kinds: ClipboardSkipKind[] = [];
  if (n('titleNum') > 0) { kinds.push('title'); }
  if (n('bitmapNum') > 0) { kinds.push('image'); }
  if (n('textLinkNum') + n('trailLinkNum') + n('todoLinkNum') > 0) {
    kinds.push('link');
  }
  if (n('normalTextBoxNum') > 0 || n('digestTextBoxNum') > 0 || n('digestTextBoxEditableNum') > 0) {
    kinds.push('textbox');
  }
  return kinds;
}

function clipboardSkipKindForElement(element: Element | null | undefined): ClipboardSkipKind {
  switch (Number(element?.type)) {
    case 100: return 'title';
    case 200: return 'image';
    case 600: return 'link';
    case 500:
    case 501:
    case 502: return 'textbox';
    default: return 'other';
  }
}

const PICTURE_ELEMENT_TYPE = 200;

function isMosaicSurface(): boolean {
  return hostState().surface === 'mosaic';
}

const SUPPORTED_PEN_TYPES = new Set([10, 16, 15, 11]);

const PRESSURE_PEN = 1;
const CALLIGRAPHY_PEN = 15;

const ERASE_COLOR = 255;

function clipboardPenType(penType: number, penColor: number): number | null {
  if (SUPPORTED_PEN_TYPES.has(penType)) { return penType; }
  if (penType === PRESSURE_PEN && penColor !== ERASE_COLOR) { return CALLIGRAPHY_PEN; }
  return null;
}

function sdkPenToMosaic(penType: number): number {
  switch (penType) {
    case 10:
    case 16: return 15;
    case 11: return 11;
    default: return 16;
  }
}

export async function readMosaicSidecar(path: string): Promise<MosaicSidecar | null> {
  if (!path) { return null; }
  try {
    const text = await notipalCommon.readTextFile(path);
    if (text == null) { return null; }
    const json = JSON.parse(text) as SidecarFile | null;
    const rawStrokes = json?.strokes;
    const strokes = Array.isArray(rawStrokes) ? rawStrokes : null;

    const skippedCards = Math.max(0, Number(json?.skippedCards) || 0);
    if (!strokes || (strokes.length === 0 && skippedCards === 0)) { return null; }
    const width = Number(json?.pageSize?.width);
    const height = Number(json?.pageSize?.height);

    const coordinateSpace: ClipboardCoordinateSpace = json?.coordinateSpace === 'note-page-px'
      || json?.coordinateSpace !== 'mosaic-px'
        && strokes.some(stroke => stroke?.sdkThickness != null || stroke?.sourcePenType != null)
      ? 'note-page-px' : 'mosaic-px';
    const screenShortPx = Number(json?.screenShortPx);
    return {
      strokes, pageSize: width > 0 && height > 0 ? { width, height } : null,
      coordinateSpace, screenShortPx: Number.isFinite(screenShortPx) && screenShortPx > 0 ? screenShortPx : null,
      skippedCards,
    };
  } catch (e) {
    log.w(MOSAIC_TAG, `read strokes failed ${path}`, e);
    return null;
  }
}

async function restoreClipboardAngles(el: Element, stroke: MosaicStroke): Promise<boolean> {
  const values: unknown = stroke.angles ?? stroke.tilt;
  if (values == null) { return true; }
  if (!Array.isArray(values) || values.length % 2 !== 0 || !values.every(Number.isInteger)) {
    throw new Error('Invalid clipboard angle sequence');
  }
  if (values.length === 0) { return true; }
  const angles: Point[] = [];
  for (let index = 0; index < values.length; index += 2) {
    angles.push({ x: values[index], y: values[index + 1] });
  }
  return Boolean(await el.angles.setRange(0, angles.length - 1, angles));
}

async function readMosaicStrokesFile(path: string): Promise<MosaicStroke[] | null> {
  return (await readMosaicSidecar(path))?.strokes ?? null;
}

function stickerTargetPageSize(ctx: HostCtx, sourcePageSize: HostPageSize): HostPageSize {
  const current = coords.currentNotePageSize(ctx);
  if (current) { return current; }

  log.i(CLIP_TAG, `sticker target pageSize fallback=source ${sizeText(sourcePageSize)}`);
  return sourcePageSize;
}

async function writeMosaicSidecar(
  stickerPath: string, strokes: MosaicStroke[], pageSize?: HostPageSize,
  coordinateSpace: ClipboardCoordinateSpace = 'note-page-px', screenShortPx?: number | null,
): Promise<void> {
  const RNFS = fs.get();
  if (!RNFS) { return; }
  try {
    await RNFS.writeFile(`${stickerPath}.mosaic.json`, JSON.stringify({
      v: 3, pageSize, coordinateSpace, ...(screenShortPx ? { screenShortPx } : {}), strokes,
    }), 'utf8');
  } catch (e) {
    log.w(MOSAIC_TAG, 'write sidecar failed', e);
  }
}

function sidecarStrokeRect(strokes: MosaicStroke[], pageSize: HostPageSize): Rect | null {
  let left = Infinity; let top = Infinity; let right = -Infinity; let bottom = -Infinity;
  for (const stroke of strokes) {
    const points = Array.isArray(stroke?.pts) ? stroke.pts : [];
    for (let i = 0; i + 1 < points.length; i += 3) {
      const x = Number(points[i]); const y = Number(points[i + 1]);
      if (!Number.isFinite(x) || !Number.isFinite(y)) { continue; }
      left = Math.min(left, x); top = Math.min(top, y);
      right = Math.max(right, x); bottom = Math.max(bottom, y);
    }
  }
  if (!Number.isFinite(left) || right <= left || bottom <= top) { return null; }
  const pad = 32;
  return {
    left: Math.max(0, Math.floor(left) - pad),
    top: Math.max(0, Math.floor(top) - pad),
    right: Math.min(pageSize.width, Math.ceil(right) + pad),
    bottom: Math.min(pageSize.height, Math.ceil(bottom) + pad),
  };
}

function scaledIntoPage(rect: Rect, sx: number, sy: number, page: HostPageSize): Rect {
  return {
    left: Math.max(0, Math.min(page.width, Math.round(rect.left * sx))),
    top: Math.max(0, Math.min(page.height, Math.round(rect.top * sy))),
    right: Math.max(0, Math.min(page.width, Math.round(rect.right * sx))),
    bottom: Math.max(0, Math.min(page.height, Math.round(rect.bottom * sy))),
  };
}

async function pasteNoteFromMosaicSidecar(
  ctx: HostCtx,
  stickerPath: string,
  occupied: OccupiedCapture | null,
  alive: () => boolean,
): Promise<'selected' | 'pasted-no-selection' | 'unavailable' | 'failed'> {
  const RNFS = fs.get();
  if (!RNFS) { return 'unavailable'; }
  const pending = pendingMosaicSidecars.get(stickerPath);
  if (pending) { await pending; }

  if (await isHostLassoClip(stickerPath)) { return 'unavailable'; }

  let payload: SidecarFile | null;
  let metadata: StickerPlacementMetadata;
  try {
    const sidecarPath = `${stickerPath}.mosaic.json`;
    if (!(await RNFS.exists(sidecarPath))) { return 'unavailable'; }
    payload = JSON.parse(await RNFS.readFile(sidecarPath, 'utf8')) as SidecarFile | null;
    const rawMeta = JSON.parse(await RNFS.readFile(`${stickerPath}.meta.json`, 'utf8')) as ClipMeta | null;
    const sourceWidth = Number(rawMeta?.sourcePageSize?.width ?? payload?.pageSize?.width);
    const sourceHeight = Number(rawMeta?.sourcePageSize?.height ?? payload?.pageSize?.height);
    const rawRect = rawMeta?.sourceRect;
    if (!(sourceWidth > 0) || !(sourceHeight > 0) || !coords.validSelectionRect(rawRect)) { return 'unavailable'; }
    metadata = {
      v: 1,
      sourcePageSize: { width: sourceWidth, height: sourceHeight },
      sourceRect: {
        left: Number(rawRect.left), top: Number(rawRect.top),
        right: Number(rawRect.right), bottom: Number(rawRect.bottom),
      },
    };
  } catch (error) {
    log.w(CLIP_TAG, 'direct note sidecar read failed', error);
    return 'unavailable';
  }

  const payloadStrokes = payload?.strokes;
  const strokes: MosaicStroke[] = Array.isArray(payloadStrokes) ? payloadStrokes : [];
  if (strokes.length === 0) { return 'unavailable'; }
  const sidecarPenTypes = new Map<number, number>();
  const sidecarRawPenTypes = new Map<number, number>();
  const sidecarMarkerColors = new Map<number, number>();
  const sidecarThickness = new Map<number, number>();
  const sidecarFactors = new Map<string, number>();
  for (const stroke of strokes) {
    const rawType = Number(stroke?.sourcePenType ?? stroke?.penType);
    if (Number.isInteger(rawType)) { bump(sidecarRawPenTypes, rawType); }
    const sdkType = Number(stroke?.sdkPenType);
    bump(sidecarPenTypes, sdkType);
    const thickness = Number(stroke?.sdkThickness);
    if (Number.isFinite(thickness)) { bump(sidecarThickness, thickness); }
    const factor = Number(stroke?.sdkFactorResize);
    if (Number.isFinite(factor)) { bump(sidecarFactors, factor.toFixed(6)); }
    if (sdkType === 11) { bump(sidecarMarkerColors, Number(stroke?.penColor)); }
  }
  log.i(CLIP_TAG, `direct note sidecar styles= raw= ${countsJson(sidecarRawPenTypes)} ` +
    `normalized= ${countsJson(sidecarPenTypes)} thickness= ${countsJson(sidecarThickness)} ` +
    `factorResize= ${countsJson(sidecarFactors)} markerColors= ${countsJson(sidecarMarkerColors)}`);
  const derivedRect = sidecarStrokeRect(strokes, metadata.sourcePageSize);
  if (derivedRect) {
    metadata.sourceRect = derivedRect;
    log.i(CLIP_TAG, `direct note sidecar placement from element points= ${JSON.stringify(derivedRect)}`);
  }

  if (!ctx.hostFilePath || ctx.page < 0) { return 'failed'; }
  let targetMachineType: number | null = null;
  try {
    const machine = asResponse(await PluginFileAPI.getFileMachineType(ctx.hostFilePath));
    if (machine?.success && Number.isInteger(machine.result)) { targetMachineType = machine.result as number; }
  } catch (e) {

    log.w(CLIP_TAG, 'direct note paste machine type unavailable', e);
  }
  const targetSize = coords.currentNotePageSize(ctx);
  if (!targetSize) { return 'failed'; }
  log.i(CLIP_TAG, `direct note paste target context file= ${ctx.hostFilePath} page= ${ctx.page} ` +
    `machineType= ${targetMachineType} pageSize= ${sizeText(targetSize)}`);

  let targetLayerId = 0;
  try {
    const layersRes = asResponse(await PluginFileAPI.getLayers(ctx.hostFilePath, ctx.page));
    const layerList = layersRes?.result;
    const layers = Array.isArray(layerList)
      ? (layerList as { isCurrentLayer?: boolean; layerId?: number; layerNum?: number }[])
      : [];
    const currentLayer = layers.find(layer => layer.isCurrentLayer) ?? null;
    const id = currentLayer ? hostElements.layerIdOf(currentLayer) : undefined;
    if (typeof id === 'number' && Number.isInteger(id) && id >= 0 && id <= 3) { targetLayerId = id; }
  } catch (error) {
    log.w(CLIP_TAG, 'direct note paste current layer lookup failed', error);
  }
  log.i(CLIP_TAG, `direct note paste target layer= ${targetLayerId}`);
  const sx = targetSize.width / metadata.sourcePageSize.width;
  const sy = targetSize.height / metadata.sourcePageSize.height;
  const sourceTargetRect = scaledIntoPage(metadata.sourceRect, sx, sy, targetSize);

  const relocatedTarget = occupied ? placement.relocatedRect(sourceTargetRect, await occupied.snapshot) : null;
  const dx = relocatedTarget ? relocatedTarget.left - sourceTargetRect.left : 0;
  const dy = relocatedTarget ? relocatedTarget.top - sourceTargetRect.top : 0;
  log.i(CLIP_TAG, `direct note paste placement rect= ${JSON.stringify(sourceTargetRect)} offset= ${dx} ${dy}`);

  const created: ClipElement[] = [];
  try {

    const prepared = new Array<ClipElement | undefined>(strokes.length);
    let next = 0;
    let skipped = 0;
    const worker = async () => {
      while (alive()) {
        const index = next++;
        if (index >= strokes.length) { return; }
        try {
          const stroke = strokes[index];
          const points = Array.isArray(stroke?.pts) ? stroke.pts : [];
          if (points.length < 6) { skipped++; continue; }
          const res = asResponse(await PluginCommAPI.createElement(0));
          const el = res?.success ? (res.result as ClipElement | null | undefined) : null;
          if (!el?.stroke) { skipped++; continue; }
          created.push(el);
          const pagePoints: Point[] = [];
          const pressures: number[] = [];
          for (let i = 0; i + 2 < points.length; i += 3) {
            pagePoints.push({
              x: Math.round(Number(points[i]) * sx) + dx,
              y: Math.round(Number(points[i + 1]) * sy) + dy,
            });
            pressures.push(Math.max(1, Math.min(NOTE_PRESSURE_MAX,
              Math.round(Number(points[i + 2]) * NOTE_PRESSURE_MAX))));
          }
          if (pagePoints.length < 2) { skipped++; continue; }
          const sdkThickness = stroke.sdkThickness;
          const sourceThickness = typeof sdkThickness === 'number' && Number.isInteger(sdkThickness) && sdkThickness > 0
            ? sdkThickness
            : Math.max(MOSAIC_THICK_MIN,
              Math.min(MOSAIC_THICK_MAX, Math.round(Number(stroke.width ?? 2) * MOSAIC_THICK_FACTOR)));
          const sourceFactor = Number(stroke.sdkFactorResize);
          const factor = Number.isFinite(sourceFactor) && sourceFactor > 0 ? sourceFactor : 1;

          el.factorResize = factor;
          el.thickness = Math.max(MOSAIC_THICK_MIN,
            Math.min(MOSAIC_THICK_MAX, Math.round(sourceThickness / factor)));
          el.layerNum = targetLayerId;

          el.stroke.penType = Number(stroke.sourcePenType) === PRESSURE_PEN
            ? PRESSURE_PEN : Number(stroke.sdkPenType);
          el.stroke.penColor = Number(stroke.penColor);
          const okPts = await el.stroke.points.setRange(0, pagePoints.length - 1, pagePoints);
          const okPrs = await el.stroke.pressures.setRange(0, pressures.length - 1, pressures);
          const okAngles = await restoreClipboardAngles(el, stroke);
          if (!okPts || !okPrs || !okAngles) { skipped++; continue; }
          prepared[index] = el;
        } catch (e) {

          log.w(CLIP_TAG, `direct note sidecar stroke ${index} failed`, e);
          skipped++;
        }
      }
    };
    await Promise.all(Array.from(
      { length: Math.min(CLIP_PASTE_CONCURRENCY, strokes.length) }, () => worker(),
    ));
    const elements = prepared.filter((el): el is ClipElement => el !== undefined);
    const insertedPenTypes = new Map<number, number>();
    const insertedThickness = new Map<number, number>();
    const insertedFactors = new Map<string, number>();
    for (const element of elements) {
      const penType = Number(element?.stroke?.penType);
      if (Number.isInteger(penType)) { bump(insertedPenTypes, penType); }
      const thickness = Number(element?.thickness);
      if (Number.isFinite(thickness)) { bump(insertedThickness, thickness); }
      const factor = Number(element?.factorResize);
      if (Number.isFinite(factor)) { bump(insertedFactors, factor.toFixed(6)); }
    }
    log.i(CLIP_TAG, `direct note paste prepared fields= elements= ${elements.length} ` +
      `penTypes= ${countsJson(insertedPenTypes)} thickness= ${countsJson(insertedThickness)} ` +
      `factorResize= ${countsJson(insertedFactors)}`);
    if (!alive()) {
      log.w(CLIP_TAG, 'direct note sidecar paste abandoned: transaction ended');
      return 'failed';
    }
    if (skipped > 0) {
      log.w(CLIP_TAG, `direct note sidecar conversion skipped= ${skipped} of= ${strokes.length}`);
      return 'failed';
    }
    if (elements.length === 0) { return 'failed'; }
    const inserted = asResponse(await PluginFileAPI.insertElements(ctx.hostFilePath, ctx.page, elements));
    log.i(CLIP_TAG, `direct note sidecar insertElements= ${JSON.stringify(inserted)} ` +
      `elements= ${elements.length} pageSize= ${sizeText(targetSize)}`);
    if (!inserted?.success) { return 'failed'; }

    await reloadForLasso('clip/paste');
    const targetRect = {
      left: sourceTargetRect.left + dx, top: sourceTargetRect.top + dy,
      right: sourceTargetRect.right + dx, bottom: sourceTargetRect.bottom + dy,
    };
    if (!alive()) { return 'pasted-no-selection'; }
    const selected = await reselectLasso(ctx, targetRect, 'clip/paste', 8, 250, true);
    log.i(CLIP_TAG, `direct note sidecar lasso= ${JSON.stringify(targetRect)} selected= ${selected}`);
    return selected ? 'selected' : 'pasted-no-selection';
  } catch (error) {
    log.w(CLIP_TAG, 'direct note sidecar paste failed', error);
    return 'failed';
  } finally {
    hostElements.releaseHostElements(created);
  }
}

async function writeStickerPlacementMetadata(
  stickerPath: string,
  sourcePageSize: HostPageSize,
  sourceRect: Rect | null,
): Promise<void> {
  if (!sourceRect) { return; }
  const RNFS = fs.get();
  if (!RNFS) { return; }
  const metadata: StickerPlacementMetadata = { v: 1, sourcePageSize, sourceRect };
  try {
    await RNFS.writeFile(`${stickerPath}.meta.json`, JSON.stringify(metadata), 'utf8');
    log.i(CLIP_TAG, `sticker placement metadata written sourcePageSize= ${sizeText(sourcePageSize)} ` +
      `sourceRect= ${JSON.stringify(sourceRect)}`);
  } catch (error) {
    log.w(CLIP_TAG, 'sticker placement metadata write failed', error);
  }
}

async function moveFloatingSelection(target: Rect, tag: string): Promise<boolean> {
  try {
    const ok = await notipalClipboard.resizeInsertedSticker(target);
    log.i('InsertPlacement', `${tag} moved floating selection ok=${ok} target=${JSON.stringify(target)}`);
    return ok === true;
  } catch (e) {
    log.w('InsertPlacement', `${tag} resizeInsertedSticker failed`, e);
    return false;
  }
}

async function restoreInsertedStickerPlacement(
  ctx: HostCtx,
  stickerPath: string,
  occupied: OccupiedCapture | null = null,
): Promise<boolean> {
  const parsed = await readClipMeta(stickerPath);
  const hostClip = parsed?.source === HOST_LASSO_CLIP_SOURCE;
  const parsedRect = parsed?.sourceRect;

  if (hostClip && !coords.validSelectionRect(parsedRect)) {
    return placement.relocateFloatingStickerIfOccupied(occupied, 'clip-paste', moveFloatingSelection);
  }
  if (!parsed?.sourcePageSize || !coords.validSelectionRect(parsedRect)) {
    log.w(CLIP_TAG, `restore sticker placement skipped: metadata invalid ${stickerPath}`);
    return false;
  }
  const sourceWidth = Number(parsed.sourcePageSize.width);
  const sourceHeight = Number(parsed.sourcePageSize.height);
  if (!(sourceWidth > 0) || !(sourceHeight > 0)) {
    log.w(CLIP_TAG, `restore sticker placement skipped: source pageSize invalid ${JSON.stringify(parsed.sourcePageSize)}`);
    return false;
  }
  const metadata: StickerPlacementMetadata = {
    v: 1,
    sourcePageSize: { width: sourceWidth, height: sourceHeight },
    sourceRect: {
      left: Number(parsedRect.left), top: Number(parsedRect.top),
      right: Number(parsedRect.right), bottom: Number(parsedRect.bottom),
    },
  };

  const targetPageSize = stickerTargetPageSize(ctx, metadata.sourcePageSize);
  const sx = targetPageSize.width / metadata.sourcePageSize.width;
  const sy = targetPageSize.height / metadata.sourcePageSize.height;
  const targetRect = scaledIntoPage(metadata.sourceRect, sx, sy, targetPageSize);
  if (targetRect.right <= targetRect.left || targetRect.bottom <= targetRect.top) {
    log.w(CLIP_TAG, `restore sticker placement skipped: target rect invalid ${JSON.stringify(targetRect)}`);
    return false;
  }

  const relocated = occupied ? placement.relocatedRect(targetRect, await occupied.snapshot) : null;
  if (relocated) {
    log.i(CLIP_TAG, `sticker placement occupied; moved ${JSON.stringify(targetRect)} → ${JSON.stringify(relocated)}`);
  } else if (hostClip) {
    log.i(CLIP_TAG, `host sticker placement kept ${JSON.stringify(targetRect)}`);
    return false;
  }
  const finalRect = relocated ?? targetRect;

  try {
    const nativeResult = await notipalClipboard.resizeInsertedSticker(finalRect);
    log.i(CLIP_TAG, `native sticker placement result= ${nativeResult} targetRect= ${JSON.stringify(finalRect)}`);
    return nativeResult === true;
  } catch (error) {
    log.w(CLIP_TAG, 'native sticker placement failed', error);
    return false;
  }
}

async function getLassoElementsForSidecar(): Promise<HostResponse | null> {
  const result = asResponse(await PluginCommAPI.getLassoElements());
  const list = result?.result;
  log.i(MOSAIC_TAG, `lasso source=SDK Android elements= ${Array.isArray(list) ? list.length : 0}`);
  return result;
}

function normalizeStickerElementCoordinateMetadata(
  elements: ClipElement[],
  sourcePageSize: HostPageSize,
): { maxX: number; maxY: number } | null {
  try {
    const maxX = PointUtils.getRealMaxX(sourcePageSize);
    const maxY = PointUtils.getRealMaxY(sourcePageSize);
    for (const element of elements) {
      if (!element || typeof element !== 'object') { continue; }
      element.maxX = maxX;
      element.maxY = maxY;
    }
    log.i(CLIP_TAG, `normalized sticker source coordinate metadata pageSize= ${sizeText(sourcePageSize)} ` +
      `sourceMax= ${maxX}x${maxY}`);
    return { maxX, maxY };
  } catch (error) {
    log.w(CLIP_TAG, 'source page coordinate metadata normalization failed', error);
    return null;
  }
}

function inRect(rect: Rect | null | undefined): rect is Rect {
  return !!rect && rect.right > rect.left && rect.bottom > rect.top;
}

async function elementsToMosaicStrokes(
  elements: ClipElement[], pageSize: HostPageSize,
  sourceRect?: Rect | null,
  targetRect?: Rect | null,
): Promise<{ strokes: MosaicStroke[]; empty: number }> {
  const out: (MosaicStroke | undefined)[] = new Array(elements.length);
  let empty = 0;
  let shortPointReads = 0;
  let shortPressureReads = 0;
  let missingPointAccessors = 0;
  let shortStrokes = 0;
  const shortStrokeNums: number[] = [];
  const missingAccessorNums: number[] = [];
  let readErrors = 0;
  const readErrorNums: number[] = [];
  let firstReadError = '';
  const unsupportedPenTypes = new Map<number, number>();
  const unsupportedPenNums: number[] = [];
  const remap = inRect(sourceRect) && targetRect ? { source: sourceRect, target: targetRect } : null;
  let next = 0;

  const worker = async () => {
    while (true) {
      const index = next++;
      if (index >= elements.length) { return; }
      const el = elements[index];
      const stroke = el?.stroke;
      if (!stroke?.points) {
        missingPointAccessors++;
        if (Number.isInteger(el?.numInPage)) { missingAccessorNums.push(Number(el.numInPage)); }
        continue;
      }
      try {
        const n = await stroke.points.size();
        if (!n || n < 2) {
          shortStrokes++;
          if (!n) { empty++; }
          if (Number.isInteger(el?.numInPage)) { shortStrokeNums.push(Number(el.numInPage)); }
          continue;
        }
        const angleCount = el.angles ? await el.angles.size() : 0;
        const [androidPts, prs, angles] = await Promise.all([
          stroke.points.getRange(0, n),
          stroke.pressures
            ? stroke.pressures.getRange(0, n).catch((): number[] => [])
            : Promise.resolve<number[]>([]),
          angleCount > 0 ? el.angles.getRange(0, angleCount) : Promise.resolve<Point[]>([]),
        ]);
        if (angles.length !== Math.max(0, angleCount)
          || angles.some(angle => !Number.isInteger(angle?.x) || !Number.isInteger(angle?.y))) {
          throw new Error(`Incomplete clipboard angle data: expected ${angleCount}, got ${angles.length}`);
        }
        const downTilt: Point | undefined = angles[0];
        if (androidPts.length < n) { shortPointReads += 1; }
        if (stroke.pressures && prs.length < n) { shortPressureReads += 1; }
        const pts: number[] = [];
        for (let i = 0; i < androidPts.length; i++) {
          const a = androidPts[i];
          if (!a || !Number.isFinite(a.x) || !Number.isFinite(a.y)) { continue; }
          const x = remap
            ? remap.target.left + (a.x - remap.source.left) * (remap.target.right - remap.target.left)
              / (remap.source.right - remap.source.left)
            : a.x;
          const y = remap
            ? remap.target.top + (a.y - remap.source.top) * (remap.target.bottom - remap.target.top)
              / (remap.source.bottom - remap.source.top)
            : a.y;
          const pr = typeof prs[i] === 'number' ? Math.max(0, Math.min(1, prs[i] / NOTE_PRESSURE_MAX)) : 1;
          pts.push(x, y, pr);
        }
        const sourcePenType = Number(stroke.penType);
        const sdkPenType = clipboardPenType(sourcePenType, Number(stroke.penColor));
        if (sdkPenType === null) {
          bump(unsupportedPenTypes, sourcePenType);
          if (Number.isInteger(el?.numInPage)) { unsupportedPenNums.push(Number(el.numInPage)); }
        }
        if (pts.length >= 6 && sdkPenType !== null) {
          const thickness = hostElements.rawElementThickness(el);
          out[index] = {
            penStyle: sdkPenToMosaic(sdkPenType),
            sourcePenType,
            sdkPenType,
            ...(Number.isInteger(el.layerNum) ? { layerNum: el.layerNum } : {}),
            penColor: Number(stroke.penColor),

            ...(angles.length ? { angles: angles.flatMap(angle => [angle.x, angle.y]) } : {}),
            ...(downTilt && Number.isFinite(downTilt.x) && Number.isFinite(downTilt.y)
              ? { tilt: [downTilt.x, downTilt.y] } : {}),

            sdkThickness: thickness != null ? Math.max(1, Math.round(thickness)) : NOTE_DEFAULT_THICKNESS,
            ...(Number(el.factorResize) > 0 ? { sdkFactorResize: Number(el.factorResize) } : {}),
            width: Math.max(1, (typeof el.thickness === 'number'
              ? el.thickness : NOTE_DEFAULT_THICKNESS) / 100),
            pts,
          };
        }
      } catch (e) {
        readErrors++;
        if (Number.isInteger(el?.numInPage)) { readErrorNums.push(Number(el.numInPage)); }
        if (!firstReadError) { firstReadError = errorText(e); }
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(16, elements.length) }, () => worker()));
  const strokes = out.filter((s): s is MosaicStroke => s !== undefined);
  let minX = Infinity; let minY = Infinity; let maxX = -Infinity; let maxY = -Infinity;
  const thicknesses: number[] = [];
  const widths: number[] = [];
  const rawPenTypes = new Map<number, number>();
  const normalizedPenTypes = new Map<number, number>();
  const factors = new Map<string, number>();
  for (const stroke of strokes) {
    const rawType = Number(stroke?.sourcePenType);
    if (Number.isInteger(rawType)) { bump(rawPenTypes, rawType); }
    const normalizedType = Number(stroke?.sdkPenType);
    if (Number.isInteger(normalizedType)) { bump(normalizedPenTypes, normalizedType); }
    const factor = Number(stroke?.sdkFactorResize);
    if (Number.isFinite(factor)) { bump(factors, factor.toFixed(6)); }
    if (Number.isFinite(stroke?.sdkThickness)) { thicknesses.push(Number(stroke.sdkThickness)); }
    if (Number.isFinite(stroke?.width)) { widths.push(Number(stroke.width)); }
    const points = stroke.pts ?? [];
    for (let i = 0; i + 1 < points.length; i += 3) {
      minX = Math.min(minX, points[i]); maxX = Math.max(maxX, points[i]);
      minY = Math.min(minY, points[i + 1]); maxY = Math.max(maxY, points[i + 1]);
    }
  }
  log.i(MOSAIC_TAG, `converted note strokes= ${strokes.length} pageSize= ${sizeText(pageSize)} ` +
    `bbox= ${Number.isFinite(minX) ? `${Math.round(minX)},${Math.round(minY)}-${Math.round(maxX)},${Math.round(maxY)}` : 'empty'} ` +
    `thickness= ${thicknesses.length ? `${Math.min(...thicknesses)}..${Math.max(...thicknesses)}` : 'none'} ` +
    `width= ${widths.length ? `${Math.min(...widths).toFixed(2)}..${Math.max(...widths).toFixed(2)}` : 'none'} ` +
    `rawPenTypes= ${countsJson(rawPenTypes)} normalizedPenTypes= ${countsJson(normalizedPenTypes)} ` +
    `factorResize= ${countsJson(factors)} shortPoints= ${shortPointReads} shortPressures= ${shortPressureReads} ` +
    `missingAccessors= ${missingPointAccessors} shortStrokes= ${shortStrokes} empty= ${empty} ` +
    `readErrors= ${readErrors} firstReadError= ${firstReadError} ` +
    `unsupportedPenTypes= ${countsJson(unsupportedPenTypes)} ` +
    `unsupportedPenNums= ${JSON.stringify(unsupportedPenNums.slice(0, 100))} ` +
    `missingAccessorNums= ${JSON.stringify(missingAccessorNums.slice(0, 100))} ` +
    `shortStrokeNums= ${JSON.stringify(shortStrokeNums.slice(0, 100))} ` +
    `readErrorNums= ${JSON.stringify(readErrorNums.slice(0, 100))}`);
  return { strokes, empty };
}

function requestMosaicClearSelection(deleteAfter: boolean): void {
  try {
    notipalMosaic.clearSelection(deleteAfter);
  } catch (e) {
    log.w(MOSAIC_TAG, 'clear-selection broadcast failed', e);
  }
}

export async function mosaicStrokesToElements(
  strokes: MosaicStroke[], pageSize: HostPageSize,
  screenShortPx?: number | null,
): Promise<Element[] | null> {
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const s of strokes) {
    const p = s.pts ?? [];
    for (let i = 0; i + 1 < p.length; i += 3) {
      if (p[i] < minX) { minX = p[i]; }
      if (p[i] > maxX) { maxX = p[i]; }
      if (p[i + 1] < minY) { minY = p[i + 1]; }
      if (p[i + 1] > maxY) { maxY = p[i + 1]; }
    }
  }
  if (!(maxX >= minX)) { return []; }
  const bw = Math.max(1, maxX - minX), bh = Math.max(1, maxY - minY);
  const pageScale = screenShortPx && screenShortPx > 0
    ? Math.min(pageSize.width, pageSize.height) / screenShortPx : 1;
  const fit = Math.min(1, (pageSize.width - 2 * SIDE_MARGIN) / (bw * pageScale),
    (pageSize.height - 2 * SIDE_MARGIN) / (bh * pageScale));
  const resizeFactor = pageScale > 0 ? 1 / pageScale : 1;
  log.i(MOSAIC_TAG, `coordinate conversion mosaic-px -> note-page-px sourceSize= ${bw}x${bh} ` +
    `pageScale= ${pageScale} fit= ${fit} resizeFactor= ${resizeFactor}`);

  const prepared = new Array<ClipElement | 'skip' | undefined>(strokes.length);
  const created: ClipElement[] = [];
  let failed = false;
  let next = 0;
  const build = async (s: MosaicStroke): Promise<ClipElement | 'skip' | null> => {
    const createRes = asResponse(await PluginCommAPI.createElement(0));
    const el = createRes?.success ? (createRes.result as ClipElement | null | undefined) : null;
    if (!el) { log.w(MOSAIC_TAG, 'createElement failed'); return null; }
    created.push(el);
    const androidPoints: Point[] = []; const pressures: number[] = [];
    const p = s.pts ?? [];
    for (let i = 0; i + 2 < p.length; i += 3) {
      const pageX = SIDE_MARGIN + (p[i] - minX) * pageScale * fit;
      const pageY = SIDE_MARGIN + (p[i + 1] - minY) * pageScale * fit;
      androidPoints.push({ x: Math.round(pageX), y: Math.round(pageY) });
      pressures.push(Math.max(1, Math.min(NOTE_PRESSURE_MAX, Math.round((p[i + 2] ?? 1) * NOTE_PRESSURE_MAX))));
    }
    if (androidPoints.length < 2 || !el.stroke) { return 'skip'; }

    const tierThickness = Math.max(MOSAIC_THICK_MIN,
      Math.min(MOSAIC_THICK_MAX, Math.round((s.width ?? 2) * MOSAIC_THICK_FACTOR)));
    el.factorResize = resizeFactor;
    el.thickness = Math.max(MOSAIC_THICK_MIN, Math.round(tierThickness / resizeFactor));
    el.stroke.penType = Number(s.sdkPenType);

    el.stroke.penColor = el.stroke.penType === 11
      ? Number(s.penColor)
      : (Number(s.penColor) === 0xFE ? 0xFE : 0);
    const [okPts, okPrs, okAngles] = await Promise.all([
      el.stroke.points.setRange(0, androidPoints.length - 1, androidPoints),
      el.stroke.pressures.setRange(0, pressures.length - 1, pressures),
      restoreClipboardAngles(el, s),
    ]);
    if (!okPts || !okPrs || !okAngles) { log.w(MOSAIC_TAG, 'setRange failed'); return null; }
    return el;
  };
  const worker = async () => {
    while (!failed) {
      const index = next++;
      if (index >= strokes.length) { return; }
      const el = await build(strokes[index]);
      if (el === null) { failed = true; }
      else { prepared[index] = el; }
    }
  };
  try {
    await Promise.all(Array.from({ length: Math.min(CLIP_PASTE_CONCURRENCY, strokes.length) }, () => worker()));
  } catch (e) {
    hostElements.releaseHostElements(created);
    throw e;
  }
  const kept = failed ? [] : prepared.filter((el): el is ClipElement => el !== undefined && el !== 'skip');

  const keep = new Set<ClipElement>(kept);
  hostElements.releaseHostElements(created.filter(el => !keep.has(el)));
  return failed ? null : kept;
}

async function convertStickerWithPreparedContours(
  elements: ClipElement[], context: StickerContext,
): Promise<HostResponse | null> {
  const uuids = elements.map(element => element?.uuid);
  if (uuids.some(uuid => typeof uuid !== 'string' || uuid.length === 0)) {
    throw new Error('Sticker element UUID missing');
  }
  const display = asResponse(await PluginCommAPI.getPageDisplaySize());
  const displaySize = display?.result as { width?: unknown; height?: unknown } | null | undefined;
  const displayWidth = Number(displaySize?.width);
  const displayHeight = Number(displaySize?.height);
  if (!display?.success || !Number.isFinite(displayWidth) || displayWidth <= 0 ||
      !Number.isFinite(displayHeight) || displayHeight <= 0) {
    throw new Error('Sticker display dimensions unavailable');
  }
  const originalAccessors = elements.map(element => element.contoursSrc);
  const prepared = await notipalClipboard.clearContours(uuids, {
    ...context, displayWidth, displayHeight,
  });
  try {
    if (prepared?.processed !== elements.length || !Array.isArray(prepared?.clearedIndexes)) {
      throw new Error('Sticker contours preparation incomplete');
    }
    for (const index of prepared.clearedIndexes) {
      (elements[index] as { contoursSrc: Element['contoursSrc'] | null }).contoursSrc = null;
    }
    if (prepared.contourElements > 0) {
      return await notipalClipboard.convertSticker(elements.map(element => ({
        uuid: element.uuid, maxX: element.maxX, maxY: element.maxY, layerNum: element.layerNum,
      })), context);
    }
    return asResponse(await hostConvertElement2Sticker({
      machineType: context.machineType, elements, stickerPath: context.path,
    }));
  } finally {
    try {
      await notipalClipboard.restoreContours(context.path);
    } finally {
      elements.forEach((element, index) => {
        element.contoursSrc = originalAccessors[index];
        element.contoursSrc?.clearCache?.();
      });
    }
  }
}

async function mosaicClipSave(
  ctx: HostCtx, slot: string, opts: { deleteAfter?: boolean; sketch?: boolean } = {},
): Promise<string> {
  const sketch = opts.sketch === true;
  if (!sketch && !isMosaicSurface()) { return 'Mosaic clip deferred: Mosaic surface is not active'; }
  const selected = await readMosaicSidecar(sketch ? MOSAIC_SKETCH_LASSO_FILE : MOSAIC_LASSO_FILE);
  const strokes = selected?.strokes;
  if (!strokes) { tip(t('clip_err_read_failed')); return 'Mosaic clip empty'; }
  const skippedCards = selected?.skippedCards ?? 0;
  if (strokes.length === 0) {
    tip(t('clip_err_card'));
    return `Save clip ${slot} failed: only Mosaic cards selected`;
  }
  await ensureStickerDir();

  const existingClips = await loadClips();
  const oldPath = existingClips[slot];
  const path = `${STICKER_DIR}/quickbar_clip_${slot}_${Date.now()}.sticker`;

  if (oldPath && await fileExists(oldPath)) {
    const confirmed = await confirmOverwrite();
    if (!confirmed) { return `Clip ${slot} overwrite cancelled`; }
  }

  const pageSize = coords.currentNotePageSize(ctx)
    ?? (sketch ? sketchFallbackPageSize(selected?.screenShortPx) : null);
  if (!pageSize) {
    tip(t('clip_save_failed'));
    return `Save clip ${slot} failed (page size unavailable)`;
  }
  const runtimeDeviceType = await PluginManager.getDeviceType();
  const deviceType = stickerMachineTypeForPageSize(pageSize, runtimeDeviceType);

  const elements = await mosaicStrokesToElements(strokes, pageSize, selected?.screenShortPx);
  if (elements === null) { return `Save clip ${slot} failed`; }
  if (elements.length === 0) { return `Save clip ${slot} failed (no strokes)`; }
  try {
    log.i(MOSAIC_TAG, `sticker target machineType= ${deviceType} runtimeDeviceType= ${runtimeDeviceType} ` +
      `source pageSize= ${sizeText(pageSize)}`);

    const convertRes = await convertStickerWithPreparedContours(elements, {
      path, pageWidth: pageSize.width, pageHeight: pageSize.height,
      machineType: deviceType, runtimeDeviceType,
    });
    if (!convertRes?.success || convertRes.result === false) {
      tip(t('clip_save_failed'));
      return `Save clip ${slot} failed`;
    }
  } finally {
    hostElements.releaseHostElements(elements);
  }

  await writeMosaicSidecar(path, strokes, pageSize, 'mosaic-px', selected?.screenShortPx);
  if (oldPath) { await deleteClipFiles(oldPath); }
  await setClip(slot, path);

  if (!sketch && isMosaicSurface()) { requestMosaicClearSelection(opts.deleteAfter === true); }
  if (skippedCards > 0) {
    tip(t('clip_saved_with_skipped', clipboardSkipLabels(['card'])));
  }
  return `Saved to clip ${slot}`;
}

async function mosaicClipPaste(slot: string, opts: { sketch?: boolean } = {}): Promise<string> {
  const sketch = opts.sketch === true;
  if (!sketch && !isMosaicSurface()) { return 'Mosaic paste deferred: Mosaic surface is not active'; }
  const clips = await loadClips();
  const stored = clips[slot];
  if (!stored) {
    log.w(MOSAIC_TAG, `paste slot= ${slot} empty clips= ${JSON.stringify(clips)}`);
    tip(t('clip_empty_hint'));
    return 'Clip empty';
  }
  const stickerPath = stored.startsWith('/') ? stored : `${STICKER_DIR}/${stored}`;
  const RNFS = fs.get();
  if (!RNFS) { return `Paste clip ${slot} failed (no fs)`; }

  const linkRefusal = clipLinkRefusal(await readClipMeta(stickerPath));
  if (linkRefusal) {
    tip(t(linkRefusal));
    return `Paste clip ${slot} refused: links paste into notes only (${linkRefusal})`;
  }

  const pending = pendingMosaicSidecars.get(stickerPath);
  if (pending) { await pending; }

  const sidecarPath = `${stickerPath}.mosaic.json`;
  const sidecar = await readMosaicSidecar(sidecarPath);
  const strokes = sidecar?.strokes?.length ? sidecar.strokes : null;
  const clipCards = await readClipCards(stickerPath);
  const cards = clipCards?.cards ?? [];
  const penTypes = new Map<number, number>();
  for (const s of strokes ?? []) { bump(penTypes, Number(s?.sdkPenType)); }
  log.i(MOSAIC_TAG, `paste slot= ${slot} sticker= ${stickerPath} ` +
    `stickerExists= ${await fileExists(stickerPath)} sidecarExists= ${await fileExists(sidecarPath)} ` +
    `waitedPending= ${!!pending} strokes= ${strokes?.length ?? 0} cards= ${cards.length} ` +
    `coordinateSpace= ${sidecar?.coordinateSpace ?? null} sdkPenTypes= ${countsJson(penTypes)}`);

  if ((!sidecar || !strokes) && cards.length === 0) {
    tip(t('clip_mosaic_unsupported'));
    return `Paste clip ${slot} failed (no mosaic sidecar)`;
  }
  try {
    await RNFS.mkdir(MOSAIC_HANDOFF_DIR).catch(e => log.w(MOSAIC_TAG, 'mkdir handoff dir failed', e));

    const pageSize = sidecar?.pageSize ?? clipCards?.pageSize;
    const coordinateSpace = sidecar?.coordinateSpace ?? 'note-page-px';
    if (cards.length > 0 && coordinateSpace !== 'note-page-px') {
      log.w(MOSAIC_TAG, `clip cards dropped: strokes are not in note page pixels ${coordinateSpace}`);
    }
    const pasteCards = coordinateSpace === 'note-page-px'
      ? cards.map(card => card.kind === 'image'
        ? { kind: 'image', left: card.left, top: card.top, right: card.right, bottom: card.bottom,
          path: `${stickerPath}${card.file}` }
        : card)
      : [];
    await RNFS.writeFile(sketch ? MOSAIC_SKETCH_PASTE_FILE : MOSAIC_PASTE_FILE, JSON.stringify({
      v: 3, ...(pageSize ? { pageSize } : {}), coordinateSpace,
      ...(sidecar?.screenShortPx ? { screenShortPx: sidecar.screenShortPx } : {}),
      strokes: strokes ?? [], cards: pasteCards,
    }), 'utf8');
  } catch (e) {
    log.w(MOSAIC_TAG, 'write paste file failed', e);
    return `Paste clip ${slot} failed`;
  }
  if (sketch) { return `Pasted clip ${slot} to Mosaic`; }
  if (!isMosaicSurface()) { return `Paste clip ${slot} deferred: Mosaic surface is not active`; }
  try {
    notipalMosaic.pasteStrokes();
  } catch (e) {
    log.w(MOSAIC_TAG, 'broadcast failed', e);
  }
  return `Pasted clip ${slot} to Mosaic`;
}

async function fileExists(path: string): Promise<boolean> {
  const RNFS = fs.get();
  if (!RNFS) { return false; }
  return RNFS.exists(path).catch(e => {
    log.w(TAG, `exists check failed ${path}`, e);
    return false;
  });
}

function confirmOverwrite(): Promise<boolean> {
  return dialog.confirm(t('clip_overwrite')).catch(e => {
    log.w(TAG, 'overwrite confirm failed, treated as confirmed', e);
    return true;
  });
}

function showUseLassoMenuTip(): string {
  tip(t('clip_use_lasso_menu'));
  return 'Clip save moved to lasso menu';
}

async function clipSmartAction(ctx: HostCtx, slot: string): Promise<string> {

  if (ctx.surface === 'mosaic') {
    const hasSel = (await readMosaicStrokesFile(MOSAIC_LASSO_FILE)) != null;
    log.i(MOSAIC_TAG, `smart action slot= ${slot} surface= ${hostState().mosaicSurface} ` +
      `lassoFile= ${hasSel ? 'present' : 'absent'} => ${hasSel ? 'save' : 'paste'}`);
    return hasSel ? await mosaicClipSave(ctx, slot) : await mosaicClipPaste(slot);
  }

  const hasHostLasso = await notipalClipboard.hasHostLasso();
  if (!hasHostLasso) { return await clipPasteSticker(ctx, slot); }

  if (!await hasLassoSelection()) { return await clipPasteSticker(ctx, slot); }
  return showUseLassoMenuTip();
}

async function saveNoteClipboardSticker(elements: ClipElement[], context: StickerContext): Promise<HostResponse | null> {
  const range = normalizeStickerElementCoordinateMetadata(elements, {
    width: context.pageWidth, height: context.pageHeight,
  });
  if (!range) { throw new Error('Sticker coordinate metadata unavailable'); }

  const sourceLayers = elements.map(element => element.layerNum);
  try {
    for (const element of elements) { element.layerNum = 0; }
    return await convertStickerWithPreparedContours(elements, context);
  } finally {
    elements.forEach((element, index) => { element.layerNum = sourceLayers[index]; });
  }
}

const HOST_LASSO_CLIP_SOURCE = 'host-lasso';
let lassoMenuClipBusy = false;

function clipFiles(stickerPath: string): string[] {
  return [stickerPath, `${stickerPath}.meta.json`, `${stickerPath}.mosaic.json`, `${stickerPath}.png`,
    `${stickerPath}${CLIP_CARDS_SUFFIX}`];
}

async function deleteFile(path: string): Promise<void> {
  try {
    await FileUtils.deleteFile(path);
  } catch (e) {

    log.d(TAG, `delete ${path} failed: ${errorText(e)}`);
  }
}

async function deleteClipFiles(stickerPath: string): Promise<void> {
  const cards = await readClipCards(stickerPath);
  const images = (cards?.cards ?? []).flatMap(card => card.kind === 'image' ? [`${stickerPath}${card.file}`] : []);
  for (const file of [...images, ...clipFiles(stickerPath)]) {
    await deleteFile(file);
  }
}

const CLIP_CARDS_SUFFIX = '.cards.json';
type ClipCard =
  | (Rect & { kind: 'text'; text: string; fontSize: number; bold: boolean; italic: boolean })
  | (Rect & { kind: 'image'; file: string });
type ClipCards = { pageSize: HostPageSize | null; cards: ClipCard[] };
type ClipCardsFile = {
  pageSize?: { width?: number; height?: number } | null;
  cards?: ({ kind?: string; text?: unknown; file?: unknown; fontSize?: unknown; bold?: unknown; italic?: unknown } & Partial<Rect>)[];
};

function clipCardRect(rect: Partial<Rect> | null | undefined): Rect | null {
  const left = Number(rect?.left); const top = Number(rect?.top);
  const right = Number(rect?.right); const bottom = Number(rect?.bottom);
  if (![left, top, right, bottom].every(Number.isFinite) || right <= left || bottom <= top) { return null; }
  return { left, top, right, bottom };
}

async function readClipCards(stickerPath: string): Promise<ClipCards | null> {
  try {
    const RNFS = fs.get();
    const path = `${stickerPath}${CLIP_CARDS_SUFFIX}`;
    if (!RNFS || !(await RNFS.exists(path))) { return null; }
    const json = JSON.parse(await RNFS.readFile(path, 'utf8')) as ClipCardsFile | null;
    const cards: ClipCard[] = [];
    const rawCards = json?.cards;
    for (const card of Array.isArray(rawCards) ? rawCards : []) {
      const rect = clipCardRect(card);
      if (!rect) { continue; }
      if (card.kind === 'text' && typeof card.text === 'string' && card.text) {
        cards.push({ ...rect, kind: 'text', text: card.text, fontSize: Number(card.fontSize) || 0,
          bold: card.bold === true, italic: card.italic === true });
      } else if (card.kind === 'image' && typeof card.file === 'string' && card.file) {
        cards.push({ ...rect, kind: 'image', file: card.file });
      }
    }
    const width = Number(json?.pageSize?.width); const height = Number(json?.pageSize?.height);
    return { pageSize: width > 0 && height > 0 ? { width, height } : null, cards };
  } catch (error) {
    log.w(MOSAIC_TAG, `read clip cards failed ${stickerPath}`, error);
    return null;
  }
}

function isTextBoxType(type: number): boolean {
  return type === 500 || type === 501 || type === 502;
}

async function exportClipCards(
  stickerPath: string, elements: Element[], pageSize: HostPageSize | null,
): Promise<void> {
  const RNFS = fs.get();
  if (!RNFS) { return; }

  const ordered: (ClipCard | null)[] = [];
  const uncopied: { index: number; rect: Rect }[] = [];
  let images = 0;
  for (const el of elements) {
    const type = Number(el?.type);
    if (type === PICTURE_ELEMENT_TYPE) {
      const source: unknown = el.picture?.picturePath;
      const rect = clipCardRect(el.picture?.rect);
      if (!rect) {
        log.w(MOSAIC_TAG, `clip picture skipped path= ${String(source)} rect= ${JSON.stringify(el.picture?.rect)}`);
        continue;
      }
      const ext = typeof source === 'string' ? /\.(png|jpe?g)$/i.exec(source)?.[0]?.toLowerCase() ?? '.png' : '.png';
      const file = `.img${images++}${ext}`;
      try {
        if (typeof source !== 'string' || !source) { throw new Error('no picturePath'); }
        await RNFS.copyFile(source, `${stickerPath}${file}`);
        ordered.push({ ...rect, kind: 'image', file });
      } catch (error) {

        log.w(MOSAIC_TAG, `copy clip picture failed ${String(source)}`, error);
        uncopied.push({ index: ordered.length, rect });
        ordered.push(null);
      }
    } else if (isTextBoxType(type)) {
      const box = el.textBox;
      const label = box?.textContentFull;
      const rect = clipCardRect(box?.textRect);
      if (!box || typeof label !== 'string' || !label.trim() || !rect) { continue; }

      const text = type === 502 ? `[ ${label} ]` : label;
      const fontSize = Number(box.fontSize);
      ordered.push({ ...rect, kind: 'text', text, fontSize: Number.isFinite(fontSize) && fontSize > 0 ? fontSize : 0,
        bold: box.textBold === 1, italic: box.textItalics === 1 });
    }
  }
  if (uncopied.length > 0) {
    const files = await cropClipPictures(stickerPath, elements, uncopied.map(u => u.rect));
    uncopied.forEach((u, i) => {
      const file = files[i];
      if (file) { ordered[u.index] = { ...u.rect, kind: 'image', file }; }
    });
  }
  const cards = ordered.filter((card): card is ClipCard => card !== null);
  log.i(MOSAIC_TAG, `clip cards= ${cards.length} ` +
    `rects= ${JSON.stringify(cards.map(card => [card.kind, card.left, card.top, card.right, card.bottom]))} ` +
    `pageSize= ${pageSize ? sizeText(pageSize) : null}`);
  if (cards.length === 0) { return; }
  await RNFS.writeFile(`${stickerPath}${CLIP_CARDS_SUFFIX}`, JSON.stringify({ v: 1, pageSize, cards }), 'utf8');
}

async function cropClipPictures(stickerPath: string, elements: Element[], rects: Rect[]): Promise<(string | null)[]> {
  const none = rects.map(() => null);
  let sw = 0; let sh = 0;
  try {
    const size = await PluginCommAPI.getStickerSize(stickerPath);
    sw = Number(size?.result?.width); sh = Number(size?.result?.height);
  } catch (error) {
    log.w(MOSAIC_TAG, 'sticker size for picture crop failed', error);
  }
  if (!(sw > 0) || !(sh > 0)) { return none; }

  const frames: { name: string; rect: Rect }[] = [];
  const boxes = elements.flatMap(el => {
    const type = Number(el?.type);
    if (type === PICTURE_ELEMENT_TYPE) { return [clipCardRect(el.picture?.rect)]; }
    if (isTextBoxType(type)) { return [clipCardRect(el.textBox?.textRect)]; }
    return [undefined];
  });
  if (boxes.length > 0 && boxes.every(Boolean)) {
    const all = boxes as Rect[];
    frames.push({ name: 'cards', rect: {
      left: Math.min(...all.map(r => r.left)), top: Math.min(...all.map(r => r.top)),
      right: Math.max(...all.map(r => r.right)), bottom: Math.max(...all.map(r => r.bottom)),
    } });
  }
  try {
    const lassoRes = asResponse(await PluginCommAPI.getLassoRect());
    const lasso = clipCardRect(lassoRes?.result as Partial<Rect> | null | undefined);
    if (lasso) { frames.push({ name: 'lasso', rect: lasso }); }
  } catch (e) {

    log.w(MOSAIC_TAG, 'lasso rect for picture crop unavailable', e);
  }
  const aspect = sw / sh;
  const frame = frames.find(f => {
    const ratio = ((f.rect.right - f.rect.left) / (f.rect.bottom - f.rect.top)) / aspect;
    return Math.abs(ratio - 1) < 0.03;
  });
  if (!frame) {
    log.w(MOSAIC_TAG, `clip picture crop skipped: no frame matches sticker ${sw}x${sh} frames= ${JSON.stringify(frames)}`);
    return none;
  }

  const render = `${stickerPath}.render.png`;
  try {
    const res = await PluginCommAPI.generateStickerThumbnail(stickerPath, render, { width: sw, height: sh });
    if (!res?.success || res.result === false) {
      log.w(MOSAIC_TAG, `clip picture crop skipped: sticker render failed ${JSON.stringify(asResponse(res)?.error ?? null)}`);
      return none;
    }
    const fw = frame.rect.right - frame.rect.left;
    const fh = frame.rect.bottom - frame.rect.top;
    const files: (string | null)[] = [];
    for (let i = 0; i < rects.length; i++) {
      const rect = rects[i];
      const file = `.crop${i}.png`;
      try {
        await notipalCommon.cropRegion(render, fw, fh, rect.left - frame.rect.left, rect.top - frame.rect.top,
          rect.right - frame.rect.left, rect.bottom - frame.rect.top, `${stickerPath}${file}`);
        files.push(file);
      } catch (error) {
        log.w(MOSAIC_TAG, `clip picture crop failed rect= ${JSON.stringify(rect)}`, error);
        files.push(null);
      }
    }
    log.i(MOSAIC_TAG, `clip pictures cropped from sticker frame= ${frame.name} ${JSON.stringify(frame.rect)} ` +
      `sticker= ${sw}x${sh} ok= ${files.filter(Boolean).length} / ${rects.length}`);
    return files;
  } catch (error) {
    log.w(MOSAIC_TAG, 'clip picture crop failed', error);
    return none;
  } finally {
    await deleteFile(render);
  }
}

async function readClipMeta(stickerPath: string): Promise<ClipMeta | null> {
  try {
    const text = await notipalCommon.readTextFile(`${stickerPath}.meta.json`);
    return text ? (JSON.parse(text) as ClipMeta | null) : null;
  } catch (e) {

    log.w(TAG, `read clip meta failed ${stickerPath}`, e);
    return null;
  }
}

function clipLinkRefusal(meta: ClipMeta | null): 'clip_links_note_only' | 'clip_links_unknown' | null {
  if (meta?.hasLinks === true) { return 'clip_links_note_only'; }
  if (meta?.source === HOST_LASSO_CLIP_SOURCE && typeof meta.hasLinks !== 'boolean') { return 'clip_links_unknown'; }
  return null;
}

async function isHostLassoClip(stickerPath: string): Promise<boolean> {
  return (await readClipMeta(stickerPath))?.source === HOST_LASSO_CLIP_SOURCE;
}

async function ensureClipThumbnail(stickerPath: string): Promise<string | null> {
  const thumb = `${stickerPath}.png`;
  const RNFS = fs.get();
  try {
    if (RNFS && await RNFS.exists(thumb)) { return thumb; }
    if (RNFS && !(await RNFS.exists(stickerPath))) { return null; }
    const size = await PluginCommAPI.getStickerSize(stickerPath);
    const width = Number(size?.result?.width);
    const height = Number(size?.result?.height);
    if (!size?.success || !(width > 0) || !(height > 0)) { return null; }
    const res = await PluginCommAPI.generateStickerThumbnail(stickerPath, thumb, { width, height });
    return res?.success && res.result !== false ? thumb : null;
  } catch (error) {
    log.w(CLIP_TAG, `clip thumbnail failed ${stickerPath}`, error);
    return null;
  }
}

type ClipElementKinds = { hasLinks: boolean; inkOnly: boolean } | null;

async function exportHostClipSidecar(ctx: HostCtx, stickerPath: string): Promise<ClipElementKinds> {
  let lassoElements: Element[] = [];
  let kinds: ClipElementKinds = null;
  try {
    const res = asResponse(await PluginCommAPI.getLassoElements());
    lassoElements = res?.success && Array.isArray(res.result) ? (res.result as Element[]) : [];
    if (lassoElements.length > 0) {
      const elementKinds = lassoElements.map(clipboardSkipKindForElement);
      kinds = {
        hasLinks: elementKinds.includes('link'),
        inkOnly: !elementKinds.includes('textbox') && !elementKinds.includes('image'),
      };
    }
    log.i(CLIP_TAG, `host clip element kinds= ${JSON.stringify(kinds)} elements= ${lassoElements.length}`);
    const pageSize = coords.currentNotePageSize(ctx);
    await exportClipCards(stickerPath, lassoElements, pageSize).catch(error => {
      log.w(MOSAIC_TAG, 'clip cards export failed', error);
    });
    const strokes = lassoElements.filter(el => Number(el?.type) === 0);
    const uuids = strokes.map(el => el?.uuid).filter((u): u is string => typeof u === 'string' && u.length > 0);
    if (!pageSize || uuids.length === 0 || uuids.length !== strokes.length) {
      log.i(MOSAIC_TAG, `host clip sidecar skipped strokes= ${strokes.length} uuids= ${uuids.length}`);
      return kinds;
    }
    const result = await notipalClipboard.exportSidecar(
      stickerPath, uuids, pageSize.width, pageSize.height, null, null,
    );

    if (Number(result?.count) + Number(result?.empty ?? 0) !== strokes.length) {
      log.w(MOSAIC_TAG, `host clip sidecar incomplete expected= ${strokes.length} actual= ${result?.count} ` +
        `empty= ${result?.empty ?? 0}`);
      await deleteFile(`${stickerPath}.mosaic.json`);
    }
  } catch (error) {
    log.w(MOSAIC_TAG, 'host clip sidecar failed', error);
    await deleteFile(`${stickerPath}.mosaic.json`);
  } finally {
    hostElements.releaseHostElements(lassoElements);
  }
  return kinds;
}

type HostClipPlacement = {
  pageSize: HostPageSize;
  rect: Rect;
};

async function captureHostClipPlacement(ctx: HostCtx): Promise<HostClipPlacement | null> {
  try {
    const pageSize = coords.currentNotePageSize(ctx);
    if (!pageSize) { return null; }
    const lr = asResponse(await PluginCommAPI.getLassoRect());
    const r = lr?.success ? lr.result : null;
    if (!coords.validSelectionRect(r)) { return null; }
    const rect = { left: Number(r.left), top: Number(r.top), right: Number(r.right), bottom: Number(r.bottom) };
    log.i(CLIP_TAG, `host clip placement ${sizeText(pageSize)} ${JSON.stringify(rect)}`);
    return { pageSize, rect };
  } catch (error) {
    log.w(CLIP_TAG, 'host clip placement unavailable', error);
    return null;
  }
}

async function commitHostClip(
  slot: string, pendingPath: string,
  content: { inkOnly: boolean | null; hasLinks: boolean | null },
  hostPlacement: HostClipPlacement | null,
): Promise<boolean> {
  const RNFS = fs.get();
  if (!RNFS) { return false; }
  const finalPath = `${STICKER_DIR}/quickbar_clip_${slot}_${Date.now()}.sticker`;
  await RNFS.moveFile(pendingPath, finalPath);
  if (await RNFS.exists(`${pendingPath}.mosaic.json`)) {
    await RNFS.moveFile(`${pendingPath}.mosaic.json`, `${finalPath}.mosaic.json`);
  }
  const cards = await readClipCards(pendingPath);
  if (cards) {
    for (const card of cards.cards) {
      if (card.kind === 'image') { await RNFS.moveFile(`${pendingPath}${card.file}`, `${finalPath}${card.file}`); }
    }
    await RNFS.moveFile(`${pendingPath}${CLIP_CARDS_SUFFIX}`, `${finalPath}${CLIP_CARDS_SUFFIX}`);
  }

  await deleteFile(`${pendingPath}.meta.json`);

  const { inkOnly, hasLinks } = content;
  await RNFS.writeFile(`${finalPath}.meta.json`, JSON.stringify({
    v: 2, source: HOST_LASSO_CLIP_SOURCE, ...(inkOnly === null ? {} : { inkOnly }),
    ...(hasLinks === null ? {} : { hasLinks }),
    ...(hostPlacement ? { sourcePageSize: hostPlacement.pageSize, sourceRect: hostPlacement.rect } : {}),
  }), 'utf8');
  const clips = await loadClips();
  const oldPath = clips[slot];
  if (oldPath && canonicalPath(oldPath) !== canonicalPath(finalPath)) { await deleteClipFiles(oldPath); }
  await setClip(slot, finalPath);
  await ensureClipThumbnail(finalPath);
  log.i(CLIP_TAG, `host lasso clip committed slot= ${slot} path= ${finalPath}`);
  return true;
}

export async function saveClipFromLassoMenu(): Promise<string> {
  if (!AVAILABLE) { return 'Clipboard unavailable'; }
  if (lassoMenuClipBusy) { return 'Clip busy'; }
  lassoMenuClipBusy = true;
  const pendingPath = `${STICKER_DIR}/pending_clip_${Date.now()}.sticker`;
  let pendingUsed = false;
  try {
    await ensureStickerDir();
    const startedAt = Date.now();
    let hostSaved = false;
    try {
      const res = await PluginCommAPI.saveStickerByLasso(pendingPath);
      hostSaved = res?.success === true && res.result === true;
      log.i(CLIP_TAG, `lasso menu saveStickerByLasso success= ${res?.success} result= ${res?.result} ` +
        `error= ${JSON.stringify(asResponse(res)?.error ?? null)} elapsedMs= ${Date.now() - startedAt}`);
    } catch (error) {
      log.w(CLIP_TAG, 'lasso menu saveStickerByLasso threw', error);
    }

    const ctx = await readHostCtx();

    let inkOnly: boolean | null = null;
    let hasLinks: boolean | null = null;
    let hostPlacement: HostClipPlacement | null = null;
    if (hostSaved) {
      hostPlacement = await captureHostClipPlacement(ctx);
      let kinds: ClipboardSkipKind[] | null = null;
      let countsRaw: unknown = null;
      try {
        const counts = asResponse(await PluginCommAPI.getLassoElementTypeCounts());
        countsRaw = counts?.result ?? null;
        if (counts?.success !== false && counts?.result) { kinds = clipboardSkipKindsFromCounts(counts.result); }
      } catch (error) {
        log.w(CLIP_TAG, 'lasso menu type counts unavailable', error);
      }
      log.i(CLIP_TAG, `lasso menu clip kinds= ${JSON.stringify(kinds)} counts= ${JSON.stringify(countsRaw)}`);
      if (kinds?.includes('title')) {
        tip(clipboardSkipError('title'));
        return 'Clip save refused: titles selected';
      }
      if (kinds) {
        inkOnly = !kinds.includes('textbox') && !kinds.includes('image');
        hasLinks = kinds.includes('link');
      }
    }
    const sidecar = hostSaved ? exportHostClipSidecar(ctx, pendingPath) : Promise.resolve(null);

    const slot = await pickClipSlot('save');
    const elementKinds = await sidecar;
    if (!slot) { return 'Clip save cancelled'; }

    if (elementKinds) {
      hasLinks = hasLinks === true || elementKinds.hasLinks;
      inkOnly = inkOnly !== false && elementKinds.inkOnly;
    }
    log.i(CLIP_TAG, `lasso menu clip content hasLinks= ${hasLinks} inkOnly= ${inkOnly}`);

    const result = await withClipSlot(slot, async () => {
      if (hostSaved) {
        pendingUsed = await commitHostClip(slot, pendingPath, { inkOnly, hasLinks }, hostPlacement).catch(error => {
          log.w(CLIP_TAG, 'host lasso clip commit failed', error);
          return false;
        });
        if (!pendingUsed) {
          tip(t('clip_save_failed'));
          return `Save clip ${slot} failed`;
        }
        try {
          await PluginCommAPI.setLassoBoxState(2);
        } catch (e) {

          log.w(CLIP_TAG, 'close lasso after host clip save failed', e);
        }
        return `Saved to clip ${slot}`;
      }
      const converted = await clipSave(ctx, slot, { confirmOverwrite: false });
      if (converted === NO_LASSO || converted === HOST_NO_PAGE) {
        if (converted === NO_LASSO) { tip(t('clip_save_failed')); }
        return `Save clip ${slot} failed`;
      }
      return converted;
    });
    await refreshTitleClips();
    return result;
  } finally {
    if (!pendingUsed) { await deleteClipFiles(pendingPath); }
    lassoMenuClipBusy = false;
  }
}

async function pickClipSlot(mode: 'save' | 'paste'): Promise<string | null> {
  const clips = await loadClips();
  const slots: ClipSlotInfo[] = [];
  for (let n = 1; n <= CLIP_SLOT_COUNT; n++) {
    const path = clips[String(n)];
    slots.push({ slot: String(n), filled: !!path, thumb: path ? await ensureClipThumbnail(path) : null });
  }
  return notipalClipboard.pickSlot(slots, mode);
}

async function openClipManager(ctx: HostCtx): Promise<string> {

  if (ctx.surface === 'mosaic') {
    const hasSel = (await readMosaicStrokesFile(MOSAIC_LASSO_FILE)) != null;
    log.i(MOSAIC_TAG, `clip manager selection= ${hasSel}`);
    const slot = await pickClipSlot(hasSel ? 'save' : 'paste');
    if (!slot) { return hasSel ? 'Clip save cancelled' : 'Clip paste cancelled'; }
    return withClipSlot(slot, () => (hasSel ? mosaicClipSave(ctx, slot) : mosaicClipPaste(slot)));
  }
  const selected = await notipalClipboard.hasHostLasso() && await hasLassoSelection();
  log.i(TAG, `clip manager selection= ${selected}`);
  if (selected) { return showUseLassoMenuTip(); }
  const slot = await pickClipSlot('paste');
  if (!slot) { return 'Clip paste cancelled'; }
  return withClipSlot(slot, () => clipPasteSticker(ctx, slot));
}

function sketchFallbackPageSize(screenShortPx?: number | null): HostPageSize {
  return screenShortPx && screenShortPx >= 1900 ? { width: 1920, height: 2560 } : { width: 1404, height: 1872 };
}

async function openSketchClipManager(selected: boolean): Promise<string> {
  let outcome = 'none';
  try {
    const ctx = await readHostCtx();
    const slot = await pickClipSlot(selected ? 'save' : 'paste');
    if (slot) {
      const result = await withClipSlot(slot, () => (selected
        ? mosaicClipSave(ctx, slot, { sketch: true })
        : mosaicClipPaste(slot, { sketch: true })));
      log.i(MOSAIC_TAG, `sketch clip manager selection= ${selected} slot= ${slot} result= ${result}`);
      if (result === `Saved to clip ${slot}`) { outcome = 'saved'; }
      else if (result === `Pasted clip ${slot} to Mosaic`) { outcome = 'paste'; }
    }
  } catch (e) {
    log.w(MOSAIC_TAG, 'sketch clip manager failed', e);
  } finally {
    try {
      notipalMosaic.finishSketchClipboard(outcome);
    } catch (e) {
      log.w(MOSAIC_TAG, 'sketch clip result not sent', e);
    }
  }
  return outcome;
}

const NO_LASSO = '__NO_LASSO__';
const HOST_NO_PAGE = '__HOST_NO_PAGE__';

async function clipSave(
  ctx: HostCtx, slot: string, opts: { confirmOverwrite?: boolean } = {},
): Promise<string> {
  await ensureStickerDir();

  const existingClips = await loadClips();
  const oldPath = existingClips[slot];
  const name = `quickbar_clip_${slot}_${Date.now()}.sticker`;
  const path = `${STICKER_DIR}/${name}`;

  log.i(TAG, `clipSave slot= ${slot} path= ${path}`);

  const commitSave = async (): Promise<string> => {
    if (oldPath) { await deleteClipFiles(oldPath); }
    await PluginCommAPI.setLassoBoxState(2);
    await setClip(slot, path);
    return `Saved to clip ${slot}`;
  };

  const startedAt = Date.now();
  log.i(CLIP_TAG, '===== clipSave element conversion =====');
  let sourceLassoRect: Rect | null = null;
  try {
    const rectRes = asResponse(await PluginCommAPI.getLassoRect());
    const r = rectRes?.success ? rectRes.result : null;
    if (coords.validSelectionRect(r)) {
      sourceLassoRect = {
        left: Number(r.left), top: Number(r.top),
        right: Number(r.right), bottom: Number(r.bottom),
      };
      log.i(CLIP_TAG, `source lasso rect= ${JSON.stringify(sourceLassoRect)}`);
    } else {
      log.w(CLIP_TAG, `source lasso rect unavailable ${JSON.stringify(rectRes)}`);
    }
  } catch (error) {
    log.w(CLIP_TAG, 'source lasso rect read failed', error);
  }
  const snapshot = await getLassoElementsForSidecar().catch(error => {
    log.w(CLIP_TAG, 'lasso element read failed', error);
    return null;
  });
  if (isHostNoPageError(snapshot)) {

    log.w(CLIP_TAG, 'lasso read blocked by host last-page NO_PAGE');
    tip(t('doc_last_page_lasso'));
    return HOST_NO_PAGE;
  }
  if (!snapshot?.success || !Array.isArray(snapshot.result) || snapshot.result.length === 0) {
    return NO_LASSO;
  }

  const selectedElements = snapshot.result as ClipElement[];
  let typeCounts: unknown = null;
  try {
    const countResponse = asResponse(await PluginCommAPI.getLassoElementTypeCounts());
    if (countResponse?.success !== false) { typeCounts = countResponse?.result ?? null; }
  } catch (error) {
    log.w(CLIP_TAG, 'lasso type counts unavailable', error);
  }
  const skippedKinds = clipboardSkipKindsFromCounts(typeCounts);
  const convertibleElements = selectedElements.filter(element =>
    CLIP_CONVERTIBLE_TYPES.has(Number(element?.type)),
  );
  const elementKinds = selectedElements
    .filter(element => !CLIP_CONVERTIBLE_TYPES.has(Number(element?.type)))
    .map(clipboardSkipKindForElement);
  const allSkippedKinds = Array.from(new Set([...skippedKinds, ...elementKinds]));
  log.i(CLIP_TAG, `lasso type counts= ${JSON.stringify(typeCounts)} selected= ${selectedElements.length} ` +
    `convertible= ${convertibleElements.length} skipped= ${clipboardSkipLabels(allSkippedKinds)}`);

  if (convertibleElements.length === 0) {
    hostElements.releaseHostElements(selectedElements);
    const firstKind = allSkippedKinds[0] ?? 'other';
    tip(
      allSkippedKinds.length === 1
        ? clipboardSkipError(firstKind)
        : t('clip_err_unsupported', clipboardSkipLabels(allSkippedKinds)),
    );
    return `Save clip ${slot} failed: unsupported elements`;
  }

  if (convertibleElements.length !== selectedElements.length) {
    hostElements.releaseHostElements(selectedElements.filter(element =>
      !CLIP_CONVERTIBLE_TYPES.has(Number(element?.type)),
    ));
    log.i(CLIP_TAG, 'filtered unsupported lasso elements before sticker conversion ' +
      `remaining= ${convertibleElements.length} skipped= ${selectedElements.length - convertibleElements.length}`);
  }

  let handedToSidecar = false;
  try {
    const sourcePageSize = coords.currentNotePageSize(ctx);
    if (!sourcePageSize) {
      tip(t('clip_save_failed'));
      return `Save clip ${slot} failed (page size unavailable)`;
    }

    const runtimeDeviceType = await PluginManager.getDeviceType();
    const deviceType = stickerMachineTypeForPageSize(sourcePageSize, runtimeDeviceType);
    const saveApi = 'convertElement2Sticker';
    log.i(CLIP_TAG, `sticker target machineType= ${deviceType} runtimeDeviceType= ${runtimeDeviceType} ` +
      `coordinateMode=android-page elements= ${convertibleElements.length} ` +
      `sourcePageSize= ${sizeText(sourcePageSize)} saveApi= ${saveApi}`);
    let nativeSaved = false;
    try {
      const converted = await saveNoteClipboardSticker(convertibleElements, {
        path, pageWidth: sourcePageSize.width, pageHeight: sourcePageSize.height,
        machineType: deviceType, runtimeDeviceType,
      });
      log.i(CLIP_TAG, `sticker save API= ${saveApi} machineType= ${deviceType} ` +
        `result= ${JSON.stringify(converted)} elapsedMs= ${Date.now() - startedAt}`);
      nativeSaved = !!converted?.success && converted.result !== false;
    } catch (error) {
      log.w(CLIP_TAG, `sticker save threw API= ${saveApi}`, error);
    }
    if (!nativeSaved) {
      tip(t('clip_save_failed'));
      return `Save clip ${slot} failed`;
    }

    await writeStickerPlacementMetadata(path, sourcePageSize, sourceLassoRect);

    let placementRect = sourceLassoRect;
    try {
      const observed = await notipalClipboard.observedLassoPageRect(sourcePageSize.width, sourcePageSize.height);
      if (coords.validSelectionRect(observed)) {
        placementRect = {
          left: observed.left, top: observed.top,
          right: observed.right, bottom: observed.bottom,
        };
        log.i(CLIP_TAG, `live resized lasso pageRect= ${JSON.stringify(placementRect)}`);
      }
    } catch (error) {
      log.w(CLIP_TAG, 'live resized lasso rect unavailable', error);
    }

    if (opts.confirmOverwrite !== false && oldPath && await fileExists(oldPath)) {
      const confirmed = await confirmOverwrite();
      if (!confirmed) {
        await deleteFile(path);
        await deleteFile(`${path}.meta.json`);
        return `Clip ${slot} overwrite cancelled`;
      }
    }

    const sidecarPromise = exportNoteSidecar(ctx, path, convertibleElements, sourceLassoRect, placementRect)
      .catch(error => {
        log.w(MOSAIC_TAG, 'note-origin sidecar failed', error);
      }).finally(() => {
        hostElements.releaseHostElements(convertibleElements);
        pendingMosaicSidecars.delete(path);
        log.i(MOSAIC_TAG, `sidecar finished elapsedMs= ${Date.now() - startedAt}`);
      });
    handedToSidecar = true;
    pendingMosaicSidecars.set(path, sidecarPromise);
    const result = await commitSave();
    log.i(CLIP_TAG, `native sticker committed elapsedMs= ${Date.now() - startedAt}`);
    if (allSkippedKinds.length > 0) {
      tip(t('clip_saved_with_skipped', clipboardSkipLabels(allSkippedKinds)));
    }
    return result;
  } finally {
    if (!handedToSidecar) { hostElements.releaseHostElements(convertibleElements); }
  }
}

async function exportNoteSidecar(
  ctx: HostCtx,
  path: string,
  savedElements: ClipElement[],
  sourceLassoRect: Rect | null,
  placementRect: Rect | null,
): Promise<void> {
  if (!savedElements.length) { return; }
  const reported = coords.currentNotePageSize(ctx);
  if (!reported) { return; }
  const pageSize = coords.resolveElementPageSize(reported);
  let sourceMachineType: number | null = null;
  try {
    const machine = ctx.hostFilePath ? asResponse(await PluginFileAPI.getFileMachineType(ctx.hostFilePath)) : null;
    if (machine?.success && Number.isInteger(machine.result)) { sourceMachineType = machine.result as number; }
  } catch (e) {

    log.w(CLIP_TAG, 'sidecar source machine type unavailable', e);
  }
  log.i(CLIP_TAG, `sidecar source context file= ${ctx.hostFilePath} page= ${ctx.page} ` +
    `machineType= ${sourceMachineType} pageSize= ${sizeText(pageSize)}`);
  const uuids = savedElements
    .map(element => element?.uuid)
    .filter((uuid): uuid is string => typeof uuid === 'string' && uuid.length > 0);
  const expectedSidecarStrokes = savedElements.filter(element => Number(element?.type) === 0).length;
  const discardSidecar = async () => {
    log.w(MOSAIC_TAG, `sidecar discarded path= ${path} expectedStrokes= ${expectedSidecarStrokes} ` +
      '— this clip cannot be pasted into Mosaic');
    await FileUtils.deleteFile(`${path}.mosaic.json`);

    await writeStickerPlacementMetadata(path, pageSize, placementRect);
  };
  if (uuids.length === savedElements.length) {
    try {
      const nativeResult = await notipalClipboard.exportSidecar(
        path, uuids, pageSize.width, pageSize.height, sourceLassoRect, placementRect,
      );
      log.i(CLIP_TAG, `native sidecar export= ${JSON.stringify(nativeResult)}`);

      if (nativeResult?.success !== false &&
          Number(nativeResult?.count) + Number(nativeResult?.empty ?? 0) === expectedSidecarStrokes) {
        return;
      }
      log.w(CLIP_TAG, 'native sidecar export incomplete; using JS fallback ' +
        `expected= ${expectedSidecarStrokes} actual= ${Number(nativeResult?.count ?? 0)} ` +
        `empty= ${Number(nativeResult?.empty ?? 0)}`);
      await discardSidecar();
    } catch (error) {
      log.w(CLIP_TAG, 'native sidecar export failed; using JS fallback', error);
      await discardSidecar();
    }
  }
  const { strokes, empty } = await elementsToMosaicStrokes(savedElements, pageSize, sourceLassoRect, placementRect);
  if (strokes.length + empty !== expectedSidecarStrokes) {
    log.w(CLIP_TAG, 'JS sidecar export incomplete; discarding sidecar ' +
      `expected= ${expectedSidecarStrokes} actual= ${strokes.length} empty= ${empty}`);
    await discardSidecar();
    return;
  }
  if (strokes.length) {
    await writeMosaicSidecar(path, strokes, pageSize);

    const elementRect = sidecarStrokeRect(strokes, pageSize);
    if (elementRect) {
      await writeStickerPlacementMetadata(path, pageSize, elementRect);
      log.i(CLIP_TAG, `sticker placement metadata from elements= ${JSON.stringify(elementRect)}`);
    }
  }
}

function stickerMachineTypeForPageSize(pageSize: HostPageSize, fallback: number): number {
  const shortSide = Math.min(pageSize.width, pageSize.height);
  const longSide = Math.max(pageSize.width, pageSize.height);

  if (shortSide === 1404 && longSide === 1872) { return 3; }
  if (shortSide === 1920 && longSide === 2560) { return 5; }
  log.i(CLIP_TAG, `sticker machineType fallback=runtime ${fallback} pageSize= ${sizeText(pageSize)}`);
  return fallback;
}

type ClipPasteOutcome = { message: string; selection: boolean };

async function clipPasteSticker(ctx: HostCtx, slot: string): Promise<string> {
  const clips = await loadClips();
  const stored = clips[slot];
  if (!stored) {
    tip(t('clip_empty_hint'));
    return 'Clip empty';
  }
  const path = stored.startsWith('/') ? stored : `${STICKER_DIR}/${stored}`;
  if (ctx.document) {
    const meta = await readClipMeta(path);
    const linkRefusal = clipLinkRefusal(meta);
    if (linkRefusal) {
      tip(t(linkRefusal));
      return `Paste clip ${slot} refused: links paste into notes only (${linkRefusal})`;
    }

    if (meta?.inkOnly === false) {
      tip(t('clip_doc_ink_only'));
      return `Paste clip ${slot} refused: document accepts handwriting only`;
    }
  }
  const busy = activeInsertTxn();
  if (busy) {
    log.w(TAG, `paste clip ${slot} skipped: ${busy} still running`);
    return 'Clip busy';
  }

  notipalClipboard.beginPaste();
  try {
    const outcome = await insertTxn(CLIP_PASTE_TXN, CLIP_PASTE_TIMEOUT_MS, async alive => {
      let selected = false;
      try {
        const pasted = await pasteClipToPage(ctx, slot, path, alive);
        selected = pasted.selection;
        return pasted;
      } finally {

        if (alive()) { notipalClipboard.finishPaste(selected); }
      }
    });
    return outcome.message;
  } catch (e) {

    log.w(TAG, `paste clip ${slot} transaction failed`, e);
    return `Paste clip ${slot} failed`;
  }
}

async function pasteClipToPage(
  ctx: HostCtx, slot: string, path: string, alive: () => boolean,
): Promise<ClipPasteOutcome> {
  const failed = (message: string): ClipPasteOutcome => ({ message, selection: false });

  const occupied = placement.startOccupiedCapture(ctx, 'clip-paste');
  await occupied.ready;
  if (!alive()) { return failed(`Paste clip ${slot} abandoned`); }

  const direct = await pasteNoteFromMosaicSidecar(ctx, path, occupied, alive);
  if (direct === 'selected' || direct === 'pasted-no-selection') {
    return { message: `Pasted clip ${slot}`, selection: direct === 'selected' };
  }
  if (direct === 'failed') {
    log.w(CLIP_TAG, 'direct note sidecar paste failed; trying sticker path');
  }
  if (!alive()) { return failed(`Paste clip ${slot} abandoned`); }

  log.i(CLIP_TAG, `clipPaste path= ${path}`);
  try {
    const pasteSzRes = await PluginCommAPI.getStickerSize(path);
    log.i(CLIP_TAG, `paste getStickerSize before insert= ${JSON.stringify(pasteSzRes)}`);
  } catch (e) {
    log.w(CLIP_TAG, 'paste getStickerSize before insert failed:', e);
  }
  if (!alive()) { return failed(`Paste clip ${slot} abandoned`); }

  try {
    const r = await PluginCommAPI.insertSticker(path);
    log.i(CLIP_TAG, `insertSticker result: ${JSON.stringify(r)}`);
    if (r.success) {

      if (!alive()) { return failed(`Paste clip ${slot} abandoned after insert`); }
      const restored = await restoreInsertedStickerPlacement(ctx, path, occupied);
      log.i(CLIP_TAG, `insertSticker placement restored= ${restored}`);
      if (!alive()) { return failed(`Paste clip ${slot} abandoned after insert`); }

      notipalClipboard.stickerInserted();
      return { message: `Pasted clip ${slot}`, selection: true };
    }
  } catch (e) {
    log.w(CLIP_TAG, 'insertSticker error:', e);
  }
  if (!alive()) { return failed(`Paste clip ${slot} abandoned`); }

  try {
    const r2 = await PluginCommAPI.insertSticker(`${path}.sticker`);
    log.i(CLIP_TAG, `insertSticker(.sticker) result: ${JSON.stringify(r2)}`);
    if (r2.success) {
      if (!alive()) { return failed(`Paste clip ${slot} abandoned after insert`); }
      const restored = await restoreInsertedStickerPlacement(ctx, path, occupied);
      log.i(CLIP_TAG, `insertSticker(.sticker) placement restored= ${restored}`);
      if (!alive()) { return failed(`Paste clip ${slot} abandoned after insert`); }
      notipalClipboard.stickerInserted();
      return { message: `Pasted clip ${slot}`, selection: true };
    }
  } catch (e) {
    log.w(CLIP_TAG, 'insertSticker(.sticker) error:', e);
  }

  return failed(`Paste clip ${slot} failed`);
}

async function clipClear(slot: string): Promise<string> {
  const clips = await loadClips();
  const oldPath = clips[slot];
  if (oldPath) { await deleteClipFiles(oldPath); }
  await setClip(slot, null);
  return `Clip ${slot} cleared`;
}

async function runClipAction(ctx: HostCtx, needsFile: boolean, action: () => Promise<string>): Promise<string> {
  if (needsFile && ctx.surface !== 'mosaic' && (!ctx.filePath || ctx.page < 0)) { return 'No file context'; }
  try {
    return await action();
  } catch (e) {
    log.e(TAG, 'clip action failed', e);
    return `Error: ${String(e)}`;
  }
}

async function onClipSlot(ctx: HostCtx, ev: ToolbarAction): Promise<void> {
  const slot = String(ev.slot ?? '');
  if (!/^\d+$/.test(slot)) {
    log.w(TAG, `clip action without slot gesture=${ev.gesture}`);
    return;
  }
  if (ev.gesture === 'tap') {
    await withClipSlot(slot, async () => {
      const result = await runClipAction(ctx, true, () => clipSmartAction(ctx, slot));
      log.i(TAG, `clip result slot= ${slot} result= ${result}`);
      if (clipsChangedBy(result)) { await refreshTitleClips(); }
    });
  } else if (ev.gesture === 'longPress') {

    await withClipSlot(slot, async () => {
      const clips = await loadClips();
      if (clips[slot]) {
        const result = await runClipAction(ctx, true, () => clipClear(slot));
        log.i(TAG, `clip long press clear slot= ${slot} result= ${result}`);
      } else {

        const result = await runClipAction(ctx, false, async () => (ctx.surface === 'mosaic'
          ? mosaicClipSave(ctx, slot, { deleteAfter: true })
          : showUseLassoMenuTip()));
        log.i(TAG, `blank clip long press result= ${result}`);
      }
      await refreshTitleClips();
    });
  }
}

async function onClipManager(ctx: HostCtx): Promise<void> {
  const result = await runClipAction(ctx, true, () => openClipManager(ctx));
  log.i(TAG, `clip manager result= ${result}`);
  if (clipsChangedBy(result)) { await refreshTitleClips(); }
}

export function register(): void {
  if (!AVAILABLE) { return; }
  registerAction(CLIP_SLOT_TOOL, onClipSlot);
  registerAction(TOOL.CLIP_MANAGER, onClipManager);
  refreshTitleClips().catch(e => log.w(TAG, 'title clips refresh failed', e));
}

export async function onLassoMenuButton(): Promise<void> {
  const result = await saveClipFromLassoMenu();
  log.i(TAG, `lasso menu clip result= ${result}`);
}

export function onClearAll(): void {
  if (!AVAILABLE) { return; }
  (async () => {
    let cleared = 0;
    for (let n = 1; n <= CLIP_SLOT_COUNT; n++) {
      const slot = String(n);
      try {
        await withClipSlot(slot, async () => {
          if ((await loadClips())[slot]) {
            await clipClear(slot);
            cleared++;
          }
        });
      } catch (e) {
        log.w(TAG, `clip slot ${slot} clear failed`, e);
      }
    }
    log.i(TAG, `clip clear all: ${cleared} slots cleared`);
    await refreshTitleClips();
  })().catch(e => log.w(TAG, 'clip clear all failed', e));
}

export function onSyncApply({ request, clips }: NativeEvents['clipboard:syncApply']): void {
  let table: ClipData;
  try {
    table = JSON.parse(clips);
  } catch (e) {
    log.w(TAG, 'clipboard sync table unreadable', e);
    notipalClipboard.syncApplied(request, false);
    return;
  }
  presets.update(p => { p.clips = table; })
    .then(() => notipalClipboard.syncApplied(request, true))
    .catch(e => {
      log.w(TAG, 'clipboard sync table not saved', e);
      notipalClipboard.syncApplied(request, false);
    });
}

export function onSketchRequest({ selection }: NativeEvents['clipboard:sketchRequest']): void {
  if (!AVAILABLE) { return; }
  openSketchClipManager(!!selection).then(async outcome => {
    log.i(TAG, `sketch clipboard outcome= ${outcome}`);
    if (outcome === 'saved') { await refreshTitleClips(); }
  }).catch(e => log.w(TAG, 'sketch clipboard failed', e));
}
