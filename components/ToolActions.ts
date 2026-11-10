import {
  PluginCommAPI, PluginDocAPI, PluginFileAPI, PluginNoteAPI, PluginManager, FileUtils, PointUtils,
} from 'sn-plugin-lib';
import HostDialog from './HostDialog';
import { NativeModules } from 'react-native';
import { loadClips, saveClips, CLIP_SLOT_COUNT } from './ToolPresets';
import { refreshTitleClips, withClipSlot } from './TitleClips';
import { t } from './i18n';
import { toggleMode, getLastMode, stopAiMode, startLocalSend } from './BackgroundService';
import { getRNFS } from './rnfs';
import FloatingToolbarBridge, { type Subscription } from './FloatingToolbarBridge';
import type { ExtractProgress } from './LassoExtractor';
import { isHostNoPageError } from './PenTools';
import FloatingBubbleBridge from './BubbleBridges';
import { layerIdOf, saveCurrentPage } from './ElementOps';
import { reloadForLasso, reselectLasso, type LassoRect } from './LassoTxn';
import {
  startOccupiedCapture, relocatedRect, relocateFloatingStickerIfOccupied, type OccupiedCapture,
} from './InsertPlacement';
import { TOOL_IDS } from './ToolIdentifiers';

const { FloatingToolbar } = NativeModules;

let _filePath: string | null = null;
let _pageNum: number | null = null;

let _clipBusy = false;

const STICKER_DIR = '/sdcard/EXPORT/Notipal/clipboard';
const LEGACY_STICKER_DIR = '/sdcard/MyStyle/Sticker';

async function ensureStickerDir(): Promise<void> {
  const RNFS = getRNFS();
  if (!RNFS) return;
  try {
    if (!(await RNFS.exists(STICKER_DIR))) {
      await RNFS.mkdir(STICKER_DIR);
      console.log('[CLIP-DBG] created', STICKER_DIR);
    }
  } catch (e) {
    console.warn('[CLIP-DBG] ensureStickerDir FAILED:', e);
  }
}

export async function purgeLegacyClips(): Promise<void> {
  const RNFS = getRNFS();
  if (!RNFS) return;
  const clips = await loadClips();
  const stale = Object.keys(clips).filter(slot => {
    const stored = clips[slot];
    return !!stored && (!stored.startsWith('/') || stored.startsWith(`${LEGACY_STICKER_DIR}/`));
  });
  if (stale.length > 0) {
    for (const slot of stale) clips[slot] = null;
    await saveClips(clips);
  }
  let deleted = 0;
  try {
    if (await RNFS.exists(LEGACY_STICKER_DIR)) {
      for (const entry of await RNFS.readDir(LEGACY_STICKER_DIR)) {
        if (!entry.isFile() || !/^(quickbar_clip_|pending_clip_)/.test(entry.name)) continue;
        try { await RNFS.unlink(entry.path); deleted++; } catch (_) {}
      }
    }
  } catch (error) {
    console.warn('[CLIP-DBG] legacy clip purge failed', error);
  }
  if (stale.length > 0 || deleted > 0) {
    console.log('[CLIP-DBG] legacy clips dropped slots=', stale.length, 'filesDeleted=', deleted);
  }
}

const OPEN_SEND_SCREEN = '__open_send_screen__';

async function ctx(): Promise<boolean> {
  try {
    const fp = await PluginCommAPI.getCurrentFilePath();
    if (fp.success && fp.result) _filePath = fp.result;
    const pg = await PluginCommAPI.getCurrentPageNum();
    if (pg.success && pg.result !== undefined) _pageNum = pg.result;
    return !!_filePath && _pageNum !== null;
  } catch { return false; }
}

export async function executeAction(action: string): Promise<string> {
  console.log('[ToolActions]:', action);

  if (action === 'insert_text') {
    const lastMode = getLastMode();
    const mode = await toggleMode(lastMode);
    return mode === 'nospacing' ? 'Text receive: no-gap ON' : mode === 'paragraph' ? 'Text receive: paragraph ON' : 'Text receive OFF';
  }

  if (action === 'text_recv_nospacing') {
    const mode = await toggleMode('nospacing');
    return mode === 'nospacing' ? 'Text receive: no-gap ON' : 'Text receive OFF';
  }
  if (action === 'text_recv_paragraph') {
    const mode = await toggleMode('paragraph');
    return mode === 'paragraph' ? 'Text receive: paragraph ON' : 'Text receive OFF';
  }

  if (action === TOOL_IDS.AI_RELAY) {

    const { startAiReceiveMode } = require('./BackgroundService');
    await startAiReceiveMode();
    return 'Opened AIRelay window';
  }

  if (action === 'lasso_ai') {
    const { requestAiSelection } = require('./BackgroundService');
    requestAiSelection()
      .catch((e: unknown) => console.error('[ToolActions]: AI selection:', e));
    return 'AI selection workflow opened';
  }

  if (action === TOOL_IDS.SMART_LASSO) {

    if (isMosaicSurface()) {
      const sent = await sendMosaicSelection();
      if (sent) return sent;
      FloatingToolbarBridge.showSmartLassoCapture();
      return 'Smart lasso capture opened';
    }

    if (FloatingToolbarBridge.isDocTextSelected()) {
      const sent = await sendDocSelectedText();
      if (sent !== 'no_text') return `Doc text send: ${sent}`;
    }
    if (!await ctx()) return 'Smart lasso: no current page';

    if (await hasLassoSelection()) {

      if (!FloatingToolbarBridge.isDocumentMode() && await lassoHasPicture()) {
        HostDialog.tip(t('smart_lasso_picture_blocked'));
        return 'Send aborted: lasso contains picture';
      }
      const { LassoExtractor, markLassoDataPrePopulated } = require('./LassoExtractor');
      const extracted = await LassoExtractor.extract();
      if (extracted.noPage) return 'Send aborted: host last-page limit';
      if (!extracted.text && extracted.imagePaths.length === 0 && extracted.linkedFiles.length === 0) {
        HostDialog.tip(t('lasso_send_ocr_failed'));
        return 'Send aborted: nothing to send';
      }
      FloatingToolbarBridge.setLassoData(
        extracted.text,
        JSON.stringify(extracted.imagePaths),
        JSON.stringify(extracted.linkedFiles),
      );
      markLassoDataPrePopulated();
      FloatingToolbarBridge.showSendPanelFromBubble();
      return 'Send panel opened';
    }
    FloatingToolbarBridge.showSmartLassoCapture();
    return 'Smart lasso capture opened';
  }

  if (action === 'lasso_send') {
    return OPEN_SEND_SCREEN;
  }

  if (action === 'screenshot_ai') {
    const { handleScreenshotAi } = require('./BackgroundService');
    await handleScreenshotAi();
    return 'Screenshot AI: delegated to native';
  }

  if (action === 'open_copilot' || action === TOOL_IDS.INK_PALETTE) {
    try {
      await NativeModules.AIRelayModule.openRelayMain();
      return 'Opened AIRelay';
    } catch (e: any) {
      console.warn('[ToolActions]: open AIRelay failed:', e);
      return `Open AIRelay failed: ${e?.message ?? e}`;
    }
  }

  if (action === 'insert_link') {
    try {
      const fpRes: any = await PluginCommAPI.getCurrentFilePath();
      const pgRes: any = await PluginCommAPI.getCurrentPageNum();
      if (fpRes?.success && fpRes.result) _filePath = fpRes.result;
      if (pgRes?.success && pgRes.result !== undefined) _pageNum = pgRes.result;
    } catch (e) {
      console.warn('[ToolActions] insert_link context refresh failed:', e);
    }

    if (_filePath && _pageNum !== null) {
      try {
        const layersRes: any = await PluginFileAPI.getLayers(_filePath, _pageNum);
        const currentLayer = Array.isArray(layersRes?.result)
          ? layersRes.result.find((layer: any) => layer.isCurrentLayer)
          : null;
        const currentLayerId = currentLayer?.layerId ?? currentLayer?.layerNum;
        if (currentLayerId !== undefined && currentLayerId !== 0) {
          console.warn('[ToolActions] insert_link blocked on non-main layer:', currentLayerId);
          HostDialog.tip(t('doc_link_main_only'));
          return 'Document links require the main layer';
        }
      } catch (e) {
        console.warn('[ToolActions] insert_link layer check failed:', e);
      }
    }

    FloatingToolbarBridge.showDocLinkPanel(_filePath);
    return 'Doc link panel opened';
  }

  if (action === 'toggle_spacing') {
    const { getActiveMode } = require('./BackgroundService');
    const cur = getActiveMode();
    const next = cur === 'nospacing' ? 'paragraph' : 'nospacing';
    const mode = await toggleMode(next);
    return mode ? `Switched to ${mode}` : 'Mode toggled off';
  }

  if (action === 'insert_doc_screenshot') {
    FloatingToolbarBridge.handleDocScreenshot();
    return 'Doc screenshot: delegated to native';
  }

  if (!action.startsWith('clip_save_') && !isMosaicSurface()) {
    if (!await ctx()) return 'No file context';
  }

  try {
    if (action.startsWith('clip_paste_')) {
      const slot = clipSlotOf(action);
      const result = await clipSmartAction(slot);
      console.log('[ToolActions]: clip result slot=', slot, 'result=', result);
      return result;
    }
    if (action.startsWith('clip_save_clear_')) {
      const slot = clipSlotOf(action);

      if (isMosaicSurface()) return await mosaicClipSave(slot, { deleteAfter: true });
      return showUseLassoMenuTip();
    }
    if (action.startsWith('clip_save_')) {
      const slot = clipSlotOf(action);
      if (isMosaicSurface()) return await mosaicClipSave(slot);
      return showUseLassoMenuTip();
    }
    if (action.startsWith('clip_clear_')) {
      const slot = clipSlotOf(action);
      return await clipClear(slot);
    }
    if (action === 'manage_clips') return await openClipManager();

    return `Unknown: ${action}`;
  } catch (e) {
    console.error('[ToolActions]:', e);
    return `Error: ${String(e)}`;
  }
}

function clipSlotOf(action: string): string {
  return action.slice(action.lastIndexOf('_') + 1);
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
    default: return t('clip_err_unsupported', { types: t('clip_skip_other') });
  }
}

function clipboardSkipKindsFromCounts(counts: any): ClipboardSkipKind[] {
  if (!counts || typeof counts !== 'object') return [];
  const kinds: ClipboardSkipKind[] = [];
  if (Number(counts.titleNum) > 0) kinds.push('title');
  if (Number(counts.bitmapNum) > 0) kinds.push('image');
  if (Number(counts.textLinkNum) + Number(counts.trailLinkNum) + Number(counts.todoLinkNum) > 0) {
    kinds.push('link');
  }
  if (
    Number(counts.normalTextBoxNum) > 0 ||
    Number(counts.digestTextBoxNum) > 0 ||
    Number(counts.digestTextBoxEditableNum) > 0
  ) {
    kinds.push('textbox');
  }
  return kinds;
}

function clipboardSkipKindForElement(element: any): ClipboardSkipKind {
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

function recycleElements(elements: any[]): void {
  for (const element of elements) {
    element?.recycle?.();
  }
}

let _paletteLassoNoPage = false;

export function paletteLassoHitNoPage(): boolean {
  return _paletteLassoNoPage;
}

let docTextSendBusy = false;

export async function sendDocSelectedText(): Promise<'opened' | 'busy' | 'no_text' | 'localsend_off' | 'failed'> {
  if (docTextSendBusy) return 'busy';
  docTextSendBusy = true;
  try {
    const selection: any = await PluginDocAPI.getLastSelectedText();
    const text = selection?.success && typeof selection.result === 'string' ? selection.result : '';
    console.log('[ToolActions] doc selected text length=', text.length);
    if (!text.trim()) return 'no_text';
    if (!await startLocalSend()) return 'localsend_off';
    FloatingToolbarBridge.showDocTextSendPanel(text);
    return 'opened';
  } catch (error) {
    console.warn('[ToolActions]: document text send failed:', error);
    return 'failed';
  } finally {
    docTextSendBusy = false;
  }
}

const PICTURE_ELEMENT_TYPE = 200;
const LASSO_SETTLE_AFTER_SEND_MS = 600;

export async function releaseLassoIfGoneAfterSend(): Promise<void> {
  if (FloatingToolbarBridge.isDocumentMode()) return;
  if (!FloatingToolbarBridge.isHostLassoMenuOpen()) return;
  await new Promise<void>(resolve => setTimeout(resolve, LASSO_SETTLE_AFTER_SEND_MS));
  if (!FloatingToolbarBridge.isHostLassoMenuOpen()) return;

  let gone = false;
  try {
    const r: any = await PluginCommAPI.getLassoRect();
    gone = r?.success === true && r.result == null;
  } catch (_) {}
  if (!gone) return;

  let elements: any[] | null = null;
  try {
    const res: any = await PluginCommAPI.getLassoElements();
    if (res?.success === true && Array.isArray(res.result)) elements = res.result;
  } catch (_) {}
  const empty = elements !== null && elements.length === 0;
  for (const el of elements ?? []) el?.recycle?.();
  if (!empty) return;
  console.log('[ToolActions] lasso gone after send but menu still marked open; releasing');
  FloatingToolbarBridge.releaseStaleLassoMenu('send closed, no lasso selection');
}

async function lassoHasPicture(): Promise<boolean> {
  let elements: any[] = [];
  try {
    const res: any = await PluginCommAPI.getLassoElements();
    if (!res?.success || !Array.isArray(res.result)) return false;
    elements = res.result;
    return elements.some(el => el?.type === PICTURE_ELEMENT_TYPE);
  } catch (_) {
    return false;
  } finally {
    for (const el of elements) el?.recycle?.();
  }
}

async function hasLassoSelection(): Promise<boolean> {
  try {
    const r = await PluginCommAPI.getLassoRect();
    console.log('[ToolActions] hasLassoSelection:', r.success, r.result);
    if (r.success && r.result != null) return true;
  } catch (_) {}
  try {
    const elements: any = await PluginCommAPI.getLassoElements();
    const selected = elements?.success === true && Array.isArray(elements.result) && elements.result.length > 0;
    console.log('[ToolActions] hasLassoSelection fallback elements=', selected ? elements.result.length : 0);
    return selected;
  } catch (_) {
    return false;
  }
}

export async function lassoSelectionSendsText(): Promise<boolean> {
  try {
    const r: any = await PluginCommAPI.getLassoElementTypeCounts();
    if (r?.success !== true || !r.result) return false;
    const count = (key: string) => Number(r.result[key]) || 0;
    const textOrLinks = count('normalTextBoxNum') + count('digestTextBoxNum') +
      count('digestTextBoxEditableNum') + count('textLinkNum');
    return textOrLinks > 0 && count('trailNum') === 0 && count('trailLinkNum') === 0;
  } catch (_) {
    return false;
  }
}

const MOSAIC_HANDOFF_DIR = '/sdcard/EXPORT/Notipal/mosaic';
const MOSAIC_LASSO_FILE = `${MOSAIC_HANDOFF_DIR}/clip_lasso.json`;
const MOSAIC_PASTE_FILE = `${MOSAIC_HANDOFF_DIR}/paste_strokes.json`;

const MOSAIC_SKETCH_LASSO_FILE = `${MOSAIC_HANDOFF_DIR}/sketch_lasso.json`;
const MOSAIC_SKETCH_PASTE_FILE = `${MOSAIC_HANDOFF_DIR}/sketch_paste.json`;

const pendingMosaicSidecars = new Map<string, Promise<void>>();

const SIDE_MARGIN = 100;

const NOTE_PRESSURE_MAX = 4095;
const NOTE_DEFAULT_THICKNESS = 1000;
const CLIP_PASTE_CONCURRENCY = 8;

const MOSAIC_THICK_FACTOR = 100;
const MOSAIC_THICK_MIN = 100;
const MOSAIC_THICK_MAX = 4000;

function isMosaicSurface(): boolean {
  return FloatingToolbarBridge.isMosaicBoardVisible();
}

function notifyStickerInserted(): void {
  try { FloatingToolbar?.notifyStickerInserted?.(); } catch (_) {}
}

const SUPPORTED_PEN_TYPES = new Set([10, 16, 15, 11]);

function sdkPenToMosaic(penType: number): number {
  switch (penType) {
    case 10:
    case 16: return 15;
    case 11: return 11;
    default: return 16;
  }
}

function rawElementThickness(element: any): number | null {
  if (typeof element?.thickness !== 'number' || !Number.isFinite(element.thickness)) return null;

  return element.thickness;
}

type ClipboardCoordinateSpace = 'mosaic-px' | 'note-page-px';
type MosaicSidecar = {
  strokes: any[];
  pageSize: { width: number; height: number } | null;
  coordinateSpace: ClipboardCoordinateSpace;
  screenShortPx: number | null;

  skippedCards: number;
};

async function readMosaicSidecar(path: string): Promise<MosaicSidecar | null> {
  try {

    let text = await FloatingToolbarBridge.readTextFile(path);
    if (text === undefined) {
      const RNFS = getRNFS();
      if (!RNFS) return null;
      text = (await RNFS.exists(path)) ? await RNFS.readFile(path, 'utf8') : null;
    }
    if (text == null) return null;
    const json = JSON.parse(text);
    const strokes = Array.isArray(json?.strokes) ? json.strokes : null;

    const skippedCards = Math.max(0, Number(json?.skippedCards) || 0);
    if (!strokes || (strokes.length === 0 && skippedCards === 0)) return null;
    const width = Number(json?.pageSize?.width);
    const height = Number(json?.pageSize?.height);
    const coordinateSpace: ClipboardCoordinateSpace = json.coordinateSpace === 'note-page-px'
      || json.coordinateSpace !== 'mosaic-px'
        && strokes.some((stroke: any) => stroke?.sdkThickness != null || stroke?.sourcePenType != null)
      ? 'note-page-px' : 'mosaic-px';
    const screenShortPx = Number(json.screenShortPx);
    return {
      strokes, pageSize: width > 0 && height > 0 ? { width, height } : null,
      coordinateSpace, screenShortPx: Number.isFinite(screenShortPx) && screenShortPx > 0 ? screenShortPx : null,
      skippedCards,
    };
  } catch (e) {
    console.warn('[MOSAIC-CLIP] read strokes failed', path, e);
    return null;
  }
}

async function restoreClipboardAngles(el: any, stroke: any): Promise<boolean> {
  const values = stroke.angles ?? stroke.tilt;
  if (values == null) return true;
  if (!Array.isArray(values) || values.length % 2 !== 0 || !values.every(Number.isInteger)) {
    throw new Error('Invalid clipboard angle sequence');
  }
  if (values.length === 0) return true;
  const angles = [];
  for (let index = 0; index < values.length; index += 2) {
    angles.push({ x: values[index], y: values[index + 1] });
  }
  return Boolean(await el.angles.setRange(0, angles.length - 1, angles));
}

async function readMosaicStrokesFile(path: string): Promise<any[] | null> {
  return (await readMosaicSidecar(path))?.strokes ?? null;
}

async function currentNotePageSize(): Promise<{ width: number; height: number } | null> {
  try {

    if (_filePath && _pageNum !== null) {
      const cachedPageSize: any = await PluginFileAPI.getPageSize(_filePath, _pageNum);
      const cachedWidth = cachedPageSize?.result?.width;
      const cachedHeight = cachedPageSize?.result?.height;
      if (Number.isInteger(cachedWidth) && Number.isInteger(cachedHeight)
        && cachedWidth > 0 && cachedHeight > 0) {
        console.log('[MOSAIC-CLIP] current note pageSize=', `${cachedWidth}x${cachedHeight}`,
          'file=', _filePath, 'page=', _pageNum, 'source=context-cache');
        return { width: cachedWidth, height: cachedHeight };
      }
    }
    let filePath = _filePath;
    let pageNum = _pageNum;
    const [fileRes, pageRes]: any[] = await Promise.all([
      PluginCommAPI.getCurrentFilePath(),
      PluginCommAPI.getCurrentPageNum(),
    ]);
    if (fileRes?.success && typeof fileRes.result === 'string' && fileRes.result) filePath = fileRes.result;
    if (pageRes?.success && Number.isInteger(pageRes.result)) pageNum = pageRes.result;
    if (filePath && pageNum !== null) {
      const psRes: any = await PluginFileAPI.getPageSize(filePath, pageNum);
      const width = psRes?.result?.width;
      const height = psRes?.result?.height;
      if (Number.isInteger(width) && Number.isInteger(height) && width > 0 && height > 0) {
        console.log('[MOSAIC-CLIP] current note pageSize=', `${width}x${height}`,
          'file=', filePath, 'page=', pageNum);
        return { width, height };
      }
    }
  } catch (e) {
    console.warn('[MOSAIC-CLIP] get current page size failed', e);
  }
  console.warn('[MOSAIC-CLIP] actual page size unavailable; coordinate conversion skipped');
  return null;
}

async function stickerTargetPageSize(
  sourcePageSize: { width: number; height: number },
): Promise<{ width: number; height: number }> {
  const cachedPageSize = await currentNotePageSize();
  if (cachedPageSize) return cachedPageSize;

  console.log('[CLIP-DBG] sticker target pageSize fallback=source',
    `${sourcePageSize.width}x${sourcePageSize.height}`);
  return sourcePageSize;
}

function resolveElementPageSize(
  reported: { width: number; height: number },
): { width: number; height: number } {

  return reported;
}

async function writeMosaicSidecar(
  stickerPath: string, strokes: any[], pageSize?: { width: number; height: number },
  coordinateSpace: ClipboardCoordinateSpace = 'note-page-px', screenShortPx?: number | null,
): Promise<void> {
  const RNFS = getRNFS();
  if (!RNFS) return;
  try {
    await RNFS.writeFile(`${stickerPath}.mosaic.json`, JSON.stringify({
      v: 3, pageSize, coordinateSpace, ...(screenShortPx ? { screenShortPx } : {}), strokes,
    }), 'utf8');
  } catch (e) {
    console.warn('[MOSAIC-CLIP] write sidecar failed', e);
  }
}

function sidecarStrokeRect(
  strokes: any[], pageSize: { width: number; height: number },
): StickerPlacementMetadata['sourceRect'] | null {
  let left = Infinity; let top = Infinity; let right = -Infinity; let bottom = -Infinity;
  for (const stroke of strokes) {
    const points = Array.isArray(stroke?.pts) ? stroke.pts : [];
    for (let i = 0; i + 1 < points.length; i += 3) {
      const x = Number(points[i]); const y = Number(points[i + 1]);
      if (!Number.isFinite(x) || !Number.isFinite(y)) continue;
      left = Math.min(left, x); top = Math.min(top, y);
      right = Math.max(right, x); bottom = Math.max(bottom, y);
    }
  }
  if (!Number.isFinite(left) || right <= left || bottom <= top) return null;
  const pad = 32;
  return {
    left: Math.max(0, Math.floor(left) - pad),
    top: Math.max(0, Math.floor(top) - pad),
    right: Math.min(pageSize.width, Math.ceil(right) + pad),
    bottom: Math.min(pageSize.height, Math.ceil(bottom) + pad),
  };
}

async function pasteNoteFromMosaicSidecar(
  stickerPath: string,
  occupied: OccupiedCapture | null,
): Promise<'selected' | 'pasted-no-selection' | 'unavailable' | 'failed'> {
  const RNFS = getRNFS();
  if (!RNFS) return 'unavailable';
  const pending = pendingMosaicSidecars.get(stickerPath);
  if (pending) await pending;

  if (await isHostLassoClip(stickerPath)) return 'unavailable';

  let payload: any;
  let metadata: StickerPlacementMetadata;
  try {
    const sidecarPath = `${stickerPath}.mosaic.json`;
    if (!(await RNFS.exists(sidecarPath))) return 'unavailable';
    payload = JSON.parse(await RNFS.readFile(sidecarPath, 'utf8'));
    const rawMeta = JSON.parse(await RNFS.readFile(`${stickerPath}.meta.json`, 'utf8'));
    const sourceWidth = Number(rawMeta?.sourcePageSize?.width ?? payload?.pageSize?.width);
    const sourceHeight = Number(rawMeta?.sourcePageSize?.height ?? payload?.pageSize?.height);
    if (!(sourceWidth > 0) || !(sourceHeight > 0) ||
        !validStickerPlacementRect(rawMeta?.sourceRect)) return 'unavailable';
    metadata = {
      v: 1,
      sourcePageSize: { width: sourceWidth, height: sourceHeight },
      sourceRect: {
        left: Number(rawMeta.sourceRect.left), top: Number(rawMeta.sourceRect.top),
        right: Number(rawMeta.sourceRect.right), bottom: Number(rawMeta.sourceRect.bottom),
      },
    };
  } catch (error) {
    console.warn('[CLIP-DBG] direct note sidecar read failed', error);
    return 'unavailable';
  }

  const strokes: any[] = Array.isArray(payload?.strokes) ? payload.strokes : [];
  if (strokes.length === 0) return 'unavailable';
  const sidecarPenTypes = new Map<number, number>();
  const sidecarRawPenTypes = new Map<number, number>();
  const sidecarMarkerColors = new Map<number, number>();
  const sidecarThickness = new Map<number, number>();
  const sidecarFactors = new Map<string, number>();
  for (const stroke of strokes) {
    const rawType = Number(stroke?.sourcePenType ?? stroke?.penType);
    if (Number.isInteger(rawType)) sidecarRawPenTypes.set(rawType, (sidecarRawPenTypes.get(rawType) ?? 0) + 1);
    const sdkType = Number(stroke?.sdkPenType);
    sidecarPenTypes.set(sdkType, (sidecarPenTypes.get(sdkType) ?? 0) + 1);
    const thickness = Number(stroke?.sdkThickness);
    if (Number.isFinite(thickness)) sidecarThickness.set(thickness, (sidecarThickness.get(thickness) ?? 0) + 1);
    const factor = Number(stroke?.sdkFactorResize);
    if (Number.isFinite(factor)) {
      const key = factor.toFixed(6);
      sidecarFactors.set(key, (sidecarFactors.get(key) ?? 0) + 1);
    }
    if (sdkType === 11) {
      const color = Number(stroke?.penColor);
      sidecarMarkerColors.set(color, (sidecarMarkerColors.get(color) ?? 0) + 1);
    }
  }
  console.log('[CLIP-DBG] direct note sidecar styles=',
    'raw=', JSON.stringify(Object.fromEntries(sidecarRawPenTypes)),
    'normalized=', JSON.stringify(Object.fromEntries(sidecarPenTypes)),
    'thickness=', JSON.stringify(Object.fromEntries(sidecarThickness)),
    'factorResize=', JSON.stringify(Object.fromEntries(sidecarFactors)),
    'markerColors=', JSON.stringify(Object.fromEntries(sidecarMarkerColors)));
  const derivedRect = sidecarStrokeRect(strokes, metadata.sourcePageSize);
  if (derivedRect) {
    metadata.sourceRect = derivedRect;
    console.log('[CLIP-DBG] direct note sidecar placement from element points=',
      JSON.stringify(derivedRect));
  }

  let filePath = _filePath;
  let pageNum = _pageNum;
  try {
    const [fp, pg]: any[] = await Promise.all([
      PluginCommAPI.getCurrentFilePath(), PluginCommAPI.getCurrentPageNum(),
    ]);
    if (fp?.success && typeof fp.result === 'string' && fp.result) filePath = fp.result;
    if (pg?.success && Number.isInteger(pg.result)) pageNum = pg.result;
  } catch (error) {
    console.warn('[CLIP-DBG] direct note sidecar current page lookup failed', error);
  }
  if (!filePath || pageNum === null) return 'failed';
  let targetMachineType: number | null = null;
  try {
    const machine: any = await PluginFileAPI.getFileMachineType?.(filePath);
    if (machine?.success && Number.isInteger(machine.result)) targetMachineType = machine.result;
  } catch (_) {}
  const targetSize = await currentNotePageSize();
  if (!targetSize) return 'failed';
  console.log('[CLIP-DBG] direct note paste target context file=', filePath,
    'page=', pageNum, 'machineType=', targetMachineType,
    'pageSize=', `${targetSize.width}x${targetSize.height}`);

  let targetLayerId = 0;
  try {
    const layersRes: any = await PluginFileAPI.getLayers(filePath, pageNum);
    const currentLayer = Array.isArray(layersRes?.result)
      ? layersRes.result.find((layer: any) => layer.isCurrentLayer)
      : null;
    const id = currentLayer ? layerIdOf(currentLayer) : undefined;
    if (Number.isInteger(id) && id >= 0 && id <= 3) targetLayerId = id;
  } catch (error) {
    console.warn('[CLIP-DBG] direct note paste current layer lookup failed', error);
  }
  console.log('[CLIP-DBG] direct note paste target layer=', targetLayerId);
  const sx = targetSize.width / metadata.sourcePageSize.width;
  const sy = targetSize.height / metadata.sourcePageSize.height;
  const sourceTargetRect = {
    left: Math.max(0, Math.min(targetSize.width, Math.round(metadata.sourceRect.left * sx))),
    top: Math.max(0, Math.min(targetSize.height, Math.round(metadata.sourceRect.top * sy))),
    right: Math.max(0, Math.min(targetSize.width, Math.round(metadata.sourceRect.right * sx))),
    bottom: Math.max(0, Math.min(targetSize.height, Math.round(metadata.sourceRect.bottom * sy))),
  };

  const relocatedTarget = occupied ? relocatedRect(sourceTargetRect, await occupied.snapshot) : null;
  const dx = relocatedTarget ? relocatedTarget.left - sourceTargetRect.left : 0;
  const dy = relocatedTarget ? relocatedTarget.top - sourceTargetRect.top : 0;
  console.log('[CLIP-DBG] direct note paste placement rect=', JSON.stringify(sourceTargetRect), 'offset=', dx, dy);
  const elements: any[] = [];
  try {

    const prepared = new Array<any>(strokes.length);
    let next = 0;
    let skipped = 0;
    const worker = async () => {
      while (true) {
        const index = next++;
        if (index >= strokes.length) return;
        try {
          const stroke = strokes[index];
          const points = Array.isArray(stroke?.pts) ? stroke.pts : [];
          if (points.length < 6) { skipped++; continue; }
          const created: any = await PluginCommAPI.createElement(0);
          if (!created?.success || !created.result?.stroke) { skipped++; continue; }
          const el: any = created.result;
          const pagePoints: { x: number; y: number }[] = [];
          const pressures: number[] = [];
          for (let i = 0; i + 2 < points.length; i += 3) {
            pagePoints.push({
              x: Math.round(Number(points[i]) * sx) + dx,
              y: Math.round(Number(points[i + 1]) * sy) + dy,
            });
            pressures.push(Math.max(1, Math.min(NOTE_PRESSURE_MAX,
              Math.round(Number(points[i + 2]) * NOTE_PRESSURE_MAX))));
          }
          if (pagePoints.length < 2) {
            el?.recycle?.();
            skipped++;
            continue;
          }
          const sourceThickness = Number.isInteger(stroke.sdkThickness) && stroke.sdkThickness > 0
            ? Number(stroke.sdkThickness)
            : Math.max(MOSAIC_THICK_MIN,
              Math.min(MOSAIC_THICK_MAX, Math.round(Number(stroke.width ?? 2) * MOSAIC_THICK_FACTOR)));
          const sourceFactor = Number(stroke.sdkFactorResize);
          const factor = Number.isFinite(sourceFactor) && sourceFactor > 0 ? sourceFactor : 1;

          el.factorResize = factor;
          el.thickness = Math.max(MOSAIC_THICK_MIN,
            Math.min(MOSAIC_THICK_MAX, Math.round(sourceThickness / factor)));
          el.layerNum = targetLayerId;
          el.stroke.penType = Number(stroke.sdkPenType);
          el.stroke.penColor = Number(stroke.penColor);
          const okPts = await el.stroke.points.setRange(0, pagePoints.length - 1, pagePoints);
          const okPrs = await el.stroke.pressures.setRange(0, pressures.length - 1, pressures);
          const okAngles = await restoreClipboardAngles(el, stroke);
          if (!okPts || !okPrs || !okAngles) {
            el?.recycle?.();
            skipped++;
            continue;
          }
          prepared[index] = el;
        } catch (_) {
          skipped++;
        }
      }
    };
    await Promise.all(Array.from(
      { length: Math.min(CLIP_PASTE_CONCURRENCY, strokes.length) }, () => worker(),
    ));
    elements.push(...prepared.filter(Boolean));
    const insertedPenTypes = new Map<number, number>();
    const insertedThickness = new Map<number, number>();
    const insertedFactors = new Map<string, number>();
    for (const element of elements) {
      const penType = Number(element?.stroke?.penType);
      if (Number.isInteger(penType)) insertedPenTypes.set(penType, (insertedPenTypes.get(penType) ?? 0) + 1);
      const thickness = Number(element?.thickness);
      if (Number.isFinite(thickness)) insertedThickness.set(thickness, (insertedThickness.get(thickness) ?? 0) + 1);
      const factor = Number(element?.factorResize);
      if (Number.isFinite(factor)) insertedFactors.set(factor.toFixed(6), (insertedFactors.get(factor.toFixed(6)) ?? 0) + 1);
    }
    console.log('[CLIP-DBG] direct note paste prepared fields=',
      'elements=', elements.length,
      'penTypes=', JSON.stringify(Object.fromEntries(insertedPenTypes)),
      'thickness=', JSON.stringify(Object.fromEntries(insertedThickness)),
      'factorResize=', JSON.stringify(Object.fromEntries(insertedFactors)));
    if (skipped > 0) {
      console.warn('[CLIP-DBG] direct note sidecar conversion skipped=', skipped,
        'of=', strokes.length);
      return 'failed';
    }
    if (elements.length === 0) return 'failed';
    const inserted: any = await PluginFileAPI.insertElements(filePath, pageNum, elements);
    console.log('[CLIP-DBG] direct note sidecar insertElements=', JSON.stringify(inserted),
      'elements=', elements.length, 'pageSize=', `${targetSize.width}x${targetSize.height}`);
    if (!inserted?.success) return 'failed';
    await reloadForLasso('clip/paste');
    const targetRect = {
      left: sourceTargetRect.left + dx, top: sourceTargetRect.top + dy,
      right: sourceTargetRect.right + dx, bottom: sourceTargetRect.bottom + dy,
    };
    const selected = await reselectLasso(targetRect, 'clip/paste', 8, 250, { filePath, pageNum });
    console.log('[CLIP-DBG] direct note sidecar lasso=', JSON.stringify(targetRect),
      'selected=', selected);
    return selected ? 'selected' : 'pasted-no-selection';
  } catch (error) {
    console.warn('[CLIP-DBG] direct note sidecar paste failed', error);
    return 'failed';
  } finally {
    recycleElements(elements);
  }
}

type StickerPlacementMetadata = {
  v: 1;
  sourcePageSize: { width: number; height: number };
  sourceRect: { left: number; top: number; right: number; bottom: number };
};

function validStickerPlacementRect(rect: any): rect is StickerPlacementMetadata['sourceRect'] {
  return !!rect && [rect.left, rect.top, rect.right, rect.bottom].every(Number.isFinite)
    && rect.right > rect.left && rect.bottom > rect.top;
}

async function writeStickerPlacementMetadata(
  stickerPath: string,
  sourcePageSize: { width: number; height: number },
  sourceRect: StickerPlacementMetadata['sourceRect'] | null,
): Promise<void> {
  if (!sourceRect) return;
  const RNFS = getRNFS();
  if (!RNFS) return;
  const metadata: StickerPlacementMetadata = { v: 1, sourcePageSize, sourceRect };
  try {
    await RNFS.writeFile(`${stickerPath}.meta.json`, JSON.stringify(metadata), 'utf8');
    console.log('[CLIP-DBG] sticker placement metadata written',
      'sourcePageSize=', `${sourcePageSize.width}x${sourcePageSize.height}`,
      'sourceRect=', JSON.stringify(sourceRect));
  } catch (error) {
    console.warn('[CLIP-DBG] sticker placement metadata write failed', error);
  }
}

async function restoreInsertedStickerPlacement(
  stickerPath: string,
  occupied: OccupiedCapture | null = null,
): Promise<boolean> {
  const parsed = await readClipMeta(stickerPath);
  const hostClip = parsed?.source === HOST_LASSO_CLIP_SOURCE;

  if (hostClip && !validStickerPlacementRect(parsed?.sourceRect)) {
    return relocateFloatingStickerIfOccupied(occupied, 'clip-paste');
  }
  if (!parsed?.sourcePageSize || !validStickerPlacementRect(parsed.sourceRect)) {
    console.warn('[CLIP-DBG] restore sticker placement skipped: metadata invalid', stickerPath);
    return false;
  }
  const sourceWidth = Number(parsed.sourcePageSize.width);
  const sourceHeight = Number(parsed.sourcePageSize.height);
  if (!(sourceWidth > 0) || !(sourceHeight > 0)) {
    console.warn('[CLIP-DBG] restore sticker placement skipped: source pageSize invalid',
      JSON.stringify(parsed.sourcePageSize));
    return false;
  }
  const metadata: StickerPlacementMetadata = {
    v: 1,
    sourcePageSize: { width: sourceWidth, height: sourceHeight },
    sourceRect: {
      left: Number(parsed.sourceRect.left), top: Number(parsed.sourceRect.top),
      right: Number(parsed.sourceRect.right), bottom: Number(parsed.sourceRect.bottom),
    },
  };

  const targetPageSize = await stickerTargetPageSize(metadata.sourcePageSize);
  const sx = targetPageSize.width / metadata.sourcePageSize.width;
  const sy = targetPageSize.height / metadata.sourcePageSize.height;
  const targetRect = {
    left: Math.max(0, Math.min(targetPageSize.width,
      Math.round(metadata.sourceRect.left * sx))),
    top: Math.max(0, Math.min(targetPageSize.height,
      Math.round(metadata.sourceRect.top * sy))),
    right: Math.max(0, Math.min(targetPageSize.width,
      Math.round(metadata.sourceRect.right * sx))),
    bottom: Math.max(0, Math.min(targetPageSize.height,
      Math.round(metadata.sourceRect.bottom * sy))),
  };
  if (targetRect.right <= targetRect.left || targetRect.bottom <= targetRect.top) {
    console.warn('[CLIP-DBG] restore sticker placement skipped: target rect invalid',
      JSON.stringify(targetRect));
    return false;
  }

  const relocated = occupied ? relocatedRect(targetRect, await occupied.snapshot) : null;
  if (relocated) {
    console.log('[CLIP-DBG] sticker placement occupied; moved', JSON.stringify(targetRect), '→', JSON.stringify(relocated));
  } else if (hostClip) {
    console.log('[CLIP-DBG] host sticker placement kept', JSON.stringify(targetRect));
    return false;
  }
  const finalRect = relocated ?? targetRect;

  try {
    const nativeResult = await FloatingToolbar.resizeInsertedSticker(finalRect);
    console.log('[CLIP-DBG] native sticker placement result=', nativeResult,
      'targetRect=', JSON.stringify(finalRect));
    return nativeResult === true;
  } catch (error) {
    console.warn('[CLIP-DBG] native sticker placement failed', error);
    return false;
  }
}

async function getLassoElementsForSidecar(): Promise<any> {
  const result: any = await PluginCommAPI.getLassoElements();
  console.log('[MOSAIC-CLIP] lasso source=SDK Android elements=',
    result?.result?.length ?? 0);
  return result;
}

function normalizeStickerElementCoordinateMetadata(
  elements: any[],
  sourcePageSize: { width: number; height: number },
): { maxX: number; maxY: number } | null {
  try {
    const maxX = PointUtils.getRealMaxX(sourcePageSize);
    const maxY = PointUtils.getRealMaxY(sourcePageSize);
    for (const element of elements) {
      if (!element || typeof element !== 'object') continue;
      element.maxX = maxX;
      element.maxY = maxY;
    }
    console.log('[CLIP-DBG] normalized sticker source coordinate metadata',
      'pageSize=', `${sourcePageSize.width}x${sourcePageSize.height}`,
      'sourceMax=', `${maxX}x${maxY}`);
    return { maxX, maxY };
  } catch (error) {
    console.warn('[CLIP-DBG] source page coordinate metadata normalization failed', error);
    return null;
  }
}

async function elementsToMosaicStrokes(
  elements: any[], pageSize: { width: number; height: number },
  sourceRect?: StickerPlacementMetadata['sourceRect'] | null,
  targetRect?: StickerPlacementMetadata['sourceRect'] | null,
): Promise<any[]> {
  const out: any[] = new Array(elements.length);
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
  let next = 0;

  const worker = async () => {
    while (true) {
      const index = next++;
      if (index >= elements.length) return;
      const el = elements[index];
      const stroke = el?.stroke;
      if (!stroke?.points) {
        missingPointAccessors++;
        if (Number.isInteger(el?.numInPage)) missingAccessorNums.push(Number(el.numInPage));
        continue;
      }
      try {
        const n = await stroke.points.size();
        if (!n || n < 2) {
          shortStrokes++;
          if (Number.isInteger(el?.numInPage)) shortStrokeNums.push(Number(el.numInPage));
          continue;
        }
        const angleCount = el.angles ? await el.angles.size() : 0;
        const [androidPts, prs, angles] = await Promise.all([
          stroke.points.getRange(0, n),
          stroke.pressures
            ? stroke.pressures.getRange(0, n).catch(() => [])
            : Promise.resolve([]),
          angleCount > 0 ? el.angles.getRange(0, angleCount) : Promise.resolve([]),
        ]);
        if (angles.length !== Math.max(0, angleCount)
          || angles.some((angle: any) => !Number.isInteger(angle?.x) || !Number.isInteger(angle?.y))) {
          throw new Error(`Incomplete clipboard angle data: expected ${angleCount}, got ${angles.length}`);
        }
        const downTilt = angles[0];
        if (androidPts.length < n) shortPointReads += 1;
        if (stroke.pressures && prs.length < n) shortPressureReads += 1;
        const pts: number[] = [];
        for (let i = 0; i < androidPts.length; i++) {
          const a = androidPts[i];
          if (!a || !Number.isFinite(a.x) || !Number.isFinite(a.y)) continue;
          const x = sourceRect && targetRect && sourceRect.right > sourceRect.left && sourceRect.bottom > sourceRect.top
            ? targetRect.left + (a.x - sourceRect.left) * (targetRect.right - targetRect.left)
              / (sourceRect.right - sourceRect.left)
            : a.x;
          const y = sourceRect && targetRect && sourceRect.right > sourceRect.left && sourceRect.bottom > sourceRect.top
            ? targetRect.top + (a.y - sourceRect.top) * (targetRect.bottom - targetRect.top)
              / (sourceRect.bottom - sourceRect.top)
            : a.y;
          const pr = typeof prs[i] === 'number' ? Math.max(0, Math.min(1, prs[i] / NOTE_PRESSURE_MAX)) : 1;
          pts.push(x, y, pr);
        }
        const sdkPenType = Number(stroke.penType);
        if (!SUPPORTED_PEN_TYPES.has(sdkPenType)) {
          unsupportedPenTypes.set(sdkPenType, (unsupportedPenTypes.get(sdkPenType) ?? 0) + 1);
          if (Number.isInteger(el?.numInPage)) unsupportedPenNums.push(Number(el.numInPage));
        }
        if (pts.length >= 6 && SUPPORTED_PEN_TYPES.has(sdkPenType)) {
          out[index] = {
            penStyle: sdkPenToMosaic(sdkPenType),
            sourcePenType: sdkPenType,
            sdkPenType,
            ...(Number.isInteger(el.layerNum) ? { layerNum: el.layerNum } : {}),
            penColor: Number(stroke.penColor),

            ...(angles.length ? { angles: angles.flatMap((angle: any) => [angle.x, angle.y]) } : {}),
            ...(downTilt && Number.isFinite(downTilt.x) && Number.isFinite(downTilt.y)
              ? { tilt: [downTilt.x, downTilt.y] } : {}),

            sdkThickness: typeof el.thickness === 'number' && Number.isFinite(el.thickness)
              ? Math.max(1, Math.round(el.thickness))
              : NOTE_DEFAULT_THICKNESS,
            ...(Number(el.factorResize) > 0 ? { sdkFactorResize: Number(el.factorResize) } : {}),
            width: Math.max(1, (typeof el.thickness === 'number'
              ? el.thickness : NOTE_DEFAULT_THICKNESS) / 100),
            pts,
          };
        }
      } catch (e) {
        readErrors++;
        if (Number.isInteger(el?.numInPage)) readErrorNums.push(Number(el.numInPage));
        if (!firstReadError) firstReadError = e instanceof Error ? e.message : String(e);
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(16, elements.length) }, () => worker()));
  const strokes = out.filter(Boolean);
  let minX = Infinity; let minY = Infinity; let maxX = -Infinity; let maxY = -Infinity;
  const thicknesses: number[] = [];
  const widths: number[] = [];
  const rawPenTypes = new Map<number, number>();
  const normalizedPenTypes = new Map<number, number>();
  const factors = new Map<string, number>();
  for (const stroke of strokes) {
    const rawType = Number(stroke?.sourcePenType);
    if (Number.isInteger(rawType)) rawPenTypes.set(rawType, (rawPenTypes.get(rawType) ?? 0) + 1);
    const normalizedType = Number(stroke?.sdkPenType);
    if (Number.isInteger(normalizedType)) normalizedPenTypes.set(normalizedType, (normalizedPenTypes.get(normalizedType) ?? 0) + 1);
    const factor = Number(stroke?.sdkFactorResize);
    if (Number.isFinite(factor)) factors.set(factor.toFixed(6), (factors.get(factor.toFixed(6)) ?? 0) + 1);
    if (Number.isFinite(stroke?.sdkThickness)) thicknesses.push(Number(stroke.sdkThickness));
    if (Number.isFinite(stroke?.width)) widths.push(Number(stroke.width));
    const points = stroke.pts as number[];
    for (let i = 0; i + 1 < points.length; i += 3) {
      minX = Math.min(minX, points[i]); maxX = Math.max(maxX, points[i]);
      minY = Math.min(minY, points[i + 1]); maxY = Math.max(maxY, points[i + 1]);
    }
  }
  console.log('[MOSAIC-CLIP] converted note strokes=', strokes.length,
    'pageSize=', `${pageSize.width}x${pageSize.height}`,
    'bbox=', Number.isFinite(minX) ? `${Math.round(minX)},${Math.round(minY)}-${Math.round(maxX)},${Math.round(maxY)}` : 'empty',
    'thickness=', thicknesses.length ? `${Math.min(...thicknesses)}..${Math.max(...thicknesses)}` : 'none',
    'width=', widths.length ? `${Math.min(...widths).toFixed(2)}..${Math.max(...widths).toFixed(2)}` : 'none',
    'rawPenTypes=', JSON.stringify(Object.fromEntries(rawPenTypes)),
    'normalizedPenTypes=', JSON.stringify(Object.fromEntries(normalizedPenTypes)),
    'factorResize=', JSON.stringify(Object.fromEntries(factors)),
    'shortPoints=', shortPointReads, 'shortPressures=', shortPressureReads,
    'missingAccessors=', missingPointAccessors, 'shortStrokes=', shortStrokes,
    'readErrors=', readErrors, 'firstReadError=', firstReadError,
    'unsupportedPenTypes=', JSON.stringify(Object.fromEntries(unsupportedPenTypes)),
    'unsupportedPenNums=', JSON.stringify(unsupportedPenNums.slice(0, 100)),
    'missingAccessorNums=', JSON.stringify(missingAccessorNums.slice(0, 100)),
    'shortStrokeNums=', JSON.stringify(shortStrokeNums.slice(0, 100)),
    'readErrorNums=', JSON.stringify(readErrorNums.slice(0, 100)));
  return strokes;
}

function requestMosaicClearSelection(deleteAfter: boolean): void {
  try { FloatingToolbar?.requestMosaicClearSelection?.(deleteAfter); }
  catch (e) { console.warn('[MOSAIC-CLIP] clear-selection broadcast failed', e); }
}

export function clearMosaicLassoSelection(): void {
  requestMosaicClearSelection(false);
}

async function mosaicStrokesToElements(
  strokes: any[], pageSize: { width: number; height: number },
  screenShortPx?: number | null,
): Promise<any[] | null> {
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const s of strokes) {
    const p = s.pts ?? [];
    for (let i = 0; i + 1 < p.length; i += 3) {
      if (p[i] < minX) minX = p[i]; if (p[i] > maxX) maxX = p[i];
      if (p[i + 1] < minY) minY = p[i + 1]; if (p[i + 1] > maxY) maxY = p[i + 1];
    }
  }
  if (!(maxX >= minX)) return [];
  const bw = Math.max(1, maxX - minX), bh = Math.max(1, maxY - minY);
  const pageScale = screenShortPx && screenShortPx > 0
    ? Math.min(pageSize.width, pageSize.height) / screenShortPx : 1;
  const fit = Math.min(1, (pageSize.width - 2 * SIDE_MARGIN) / (bw * pageScale),
    (pageSize.height - 2 * SIDE_MARGIN) / (bh * pageScale));
  const resizeFactor = pageScale > 0 ? 1 / pageScale : 1;
  console.log('[MOSAIC-CLIP] coordinate conversion mosaic-px -> note-page-px',
    'sourceSize=', `${bw}x${bh}`, 'pageScale=', pageScale, 'fit=', fit,
    'resizeFactor=', resizeFactor);

  const prepared = new Array<any>(strokes.length);
  let failed = false;
  let next = 0;
  const build = async (s: any): Promise<any | 'skip' | null> => {
    const createRes: any = await PluginCommAPI.createElement(0);
    if (!createRes?.success || !createRes.result) { console.warn('[MOSAIC-CLIP] createElement failed'); return null; }
    const el: any = createRes.result;
    const androidPoints: { x: number; y: number }[] = []; const pressures: number[] = [];
    const p = s.pts ?? [];
    for (let i = 0; i + 2 < p.length; i += 3) {
      const pageX = SIDE_MARGIN + (p[i] - minX) * pageScale * fit;
      const pageY = SIDE_MARGIN + (p[i + 1] - minY) * pageScale * fit;
      androidPoints.push({ x: Math.round(pageX), y: Math.round(pageY) });
      pressures.push(Math.max(1, Math.min(NOTE_PRESSURE_MAX, Math.round((p[i + 2] ?? 1) * NOTE_PRESSURE_MAX))));
    }
    if (androidPoints.length < 2 || !el.stroke) return 'skip';

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
    if (!okPts || !okPrs || !okAngles) { console.warn('[MOSAIC-CLIP] setRange failed'); return null; }
    return el;
  };
  const worker = async () => {
    while (!failed) {
      const index = next++;
      if (index >= strokes.length) return;
      const el = await build(strokes[index]);
      if (el === null) failed = true;
      else prepared[index] = el;
    }
  };
  await Promise.all(Array.from({ length: Math.min(CLIP_PASTE_CONCURRENCY, strokes.length) }, () => worker()));
  if (failed) return null;
  return prepared.filter((el) => el && el !== 'skip');
}

export async function extractMosaicLassoText(
  onProgress?: ExtractProgress,
): Promise<string | null> {
  if (!isMosaicSurface()) return null;
  const selection = await readMosaicSidecar(MOSAIC_LASSO_FILE);
  const strokes = selection?.strokes;
  if (!strokes || strokes.length === 0) return null;
  const canWrite = await FloatingToolbarBridge.requestFileWritePermission();
  if (!canWrite) {
    try { HostDialog.tip(t('perm_required')); } catch (_) {}
    return null;
  }
  const pageSize = await currentNotePageSize();
  if (!pageSize) {
    console.warn('[MOSAIC-OCR] actual page size unavailable');
    return null;
  }
  const elements = await mosaicStrokesToElements(strokes, pageSize, selection?.screenShortPx);
  if (!elements || elements.length === 0) return null;
  onProgress?.('recognizing');
  try {
    const res: any = await PluginCommAPI.recognizeElements(elements, pageSize);
    if (res?.success && typeof res.result === 'string' && res.result.trim()) return res.result.trim();
    console.warn('[MOSAIC-OCR] recognizeElements empty/failed:', res?.error?.message);
    return null;
  } catch (e) {
    console.warn('[MOSAIC-OCR] recognizeElements threw:', e);
    return null;
  } finally {
    onProgress?.('done');
  }
}

async function sendMosaicSelection(): Promise<string | null> {
  let selection: any = null;
  try {
    const text = await FloatingToolbarBridge.readTextFile(MOSAIC_LASSO_FILE);
    selection = text ? JSON.parse(text) : null;
  } catch (error) {
    console.warn('[MOSAIC-SEND] read selection failed', error);
  }
  const strokes: any[] = Array.isArray(selection?.strokes) ? selection.strokes : [];
  const cards: { text: string; top: number }[] = (Array.isArray(selection?.textCards) ? selection.textCards : [])
    .filter((card: any) => typeof card?.text === 'string' && card.text.trim())
    .map((card: any) => ({ text: card.text.trim(), top: Number(card.top) || 0 }));
  console.log('[MOSAIC-SEND] selection strokes=', strokes.length, 'textCards=', cards.length);
  if (strokes.length === 0 && cards.length === 0) return null;

  const parts = [...cards];
  if (strokes.length > 0) {
    const recognized = await extractMosaicLassoText();
    if (recognized) {
      let top = Infinity;
      for (const stroke of strokes) {
        const pts = Array.isArray(stroke?.pts) ? stroke.pts : [];
        for (let i = 1; i < pts.length; i += 3) if (Number.isFinite(pts[i])) top = Math.min(top, pts[i]);
      }
      parts.push({ text: recognized, top: Number.isFinite(top) ? top : 0 });
    }
  }
  if (parts.length === 0) {
    HostDialog.tip(t('lasso_send_ocr_failed'));
    return 'Send aborted: nothing to send';
  }
  parts.sort((a, b) => a.top - b.top);
  const { markLassoDataPrePopulated } = require('./LassoExtractor');
  FloatingToolbarBridge.setLassoData(parts.map(part => part.text).join('\n'), '[]', '[]');
  markLassoDataPrePopulated();
  FloatingToolbarBridge.showSendPanelFromBubble();
  return 'Send panel opened (Mosaic selection)';
}

async function convertStickerWithPreparedContours(elements: any[], context: {
  path: string; pageWidth: number; pageHeight: number;
  machineType: number; runtimeDeviceType: number;
}): Promise<any> {
  const uuids = elements.map(element => element?.uuid);
  if (uuids.some(uuid => typeof uuid !== 'string' || uuid.length === 0)) {
    throw new Error('Sticker element UUID missing');
  }
  const display: any = await PluginCommAPI.getPageDisplaySize();
  const displayWidth = Number(display?.result?.width);
  const displayHeight = Number(display?.result?.height);
  if (!display?.success || !Number.isFinite(displayWidth) || displayWidth <= 0 ||
      !Number.isFinite(displayHeight) || displayHeight <= 0) {
    throw new Error('Sticker display dimensions unavailable');
  }
  const originalAccessors = elements.map(element => element.contoursSrc);
  const prepared = await FloatingToolbar.clearClipboardContours(uuids, {
    ...context, displayWidth, displayHeight,
  });
  try {
    if (prepared?.processed !== elements.length || !Array.isArray(prepared?.clearedIndexes)) {
      throw new Error('Sticker contours preparation incomplete');
    }
    for (const index of prepared.clearedIndexes) elements[index].contoursSrc = null;
    if (prepared.contourElements > 0) {
      return await FloatingToolbar.convertClipboardSticker(elements.map(element => ({
        uuid: element.uuid, maxX: element.maxX, maxY: element.maxY, layerNum: element.layerNum,
      })), context);
    }
    return await NativeModules.NativePluginAPI.convertElement2Sticker({
      machineType: context.machineType, elements, stickerPath: context.path,
    });
  } finally {
    try {
      await FloatingToolbar.restoreClipboardContours(context.path);
    } finally {
      elements.forEach((element, index) => {
        element.contoursSrc = originalAccessors[index];
        element.contoursSrc?.clearCache?.();
      });
    }
  }
}

async function mosaicClipSave(
  slot: string, opts: { deleteAfter?: boolean; sketch?: boolean } = {},
): Promise<string> {
  const sketch = opts.sketch === true;
  if (!sketch && !isMosaicSurface()) return 'Mosaic clip deferred: Mosaic surface is not active';
  const selection = await readMosaicSidecar(sketch ? MOSAIC_SKETCH_LASSO_FILE : MOSAIC_LASSO_FILE);
  const strokes = selection?.strokes;
  if (!strokes) { HostDialog.tip(t('clip_err_read_failed')); return 'Mosaic clip empty'; }
  const skippedCards = selection?.skippedCards ?? 0;
  if (strokes.length === 0) {
    HostDialog.tip(t('clip_err_card'));
    return `Save clip ${slot} failed: only Mosaic cards selected`;
  }
  await ensureStickerDir();

  const existingClips = await loadClips();
  const oldPath = existingClips[slot];
  const path = `${STICKER_DIR}/quickbar_clip_${slot}_${Date.now()}.sticker`;

  if (oldPath && await getRNFS()?.exists(oldPath).catch(() => false)) {
    const confirmed = await HostDialog.confirm(
      t('clip_overwrite'), t('btn_cancel'), t('btn_confirm'), false,
    ).catch(() => true);
    if (!confirmed) return `Clip ${slot} overwrite cancelled`;
  }

  const pageSize = await currentNotePageSize()
    ?? (sketch ? sketchFallbackPageSize(selection?.screenShortPx) : null);
  if (!pageSize) {
    HostDialog.tip(t('clip_save_failed'));
    return `Save clip ${slot} failed (page size unavailable)`;
  }
  const runtimeDeviceType = await PluginManager.getDeviceType();
  const deviceType = stickerMachineTypeForPageSize(pageSize, runtimeDeviceType);

  const elements = await mosaicStrokesToElements(strokes, pageSize, selection?.screenShortPx);
  if (elements === null) return `Save clip ${slot} failed`;
  if (elements.length === 0) return `Save clip ${slot} failed (no strokes)`;
  console.log('[MOSAIC-CLIP] sticker target machineType=', deviceType,
    'runtimeDeviceType=', runtimeDeviceType,
    'source pageSize=', `${pageSize.width}x${pageSize.height}`);

  const convertRes: any = await convertStickerWithPreparedContours(elements, {
    path, pageWidth: pageSize.width, pageHeight: pageSize.height,
    machineType: deviceType, runtimeDeviceType,
  });
  if (!convertRes?.success || convertRes.result === false) {
    HostDialog.tip(t('clip_save_failed'));
    return `Save clip ${slot} failed`;
  }

  await writeMosaicSidecar(path, strokes, pageSize, 'mosaic-px', selection?.screenShortPx);
  if (oldPath) await deleteClipFiles(oldPath);
  const clips = await loadClips(); clips[slot] = path; await saveClips(clips);

  if (!sketch && isMosaicSurface()) requestMosaicClearSelection(opts.deleteAfter === true);
  if (skippedCards > 0) {
    HostDialog.tip(t('clip_saved_with_skipped', { skipped: clipboardSkipLabels(['card']) }));
  }
  return `Saved to clip ${slot}`;
}

async function mosaicClipPaste(slot: string, opts: { sketch?: boolean } = {}): Promise<string> {
  const sketch = opts.sketch === true;
  if (!sketch && !isMosaicSurface()) return 'Mosaic paste deferred: Mosaic surface is not active';
  const clips = await loadClips();
  const stored = clips[slot];
  if (!stored) {
    console.warn('[MOSAIC-CLIP] paste slot=', slot, 'empty clips=', JSON.stringify(clips));
    HostDialog.tip(t('clip_empty_hint'));
    return 'Clip empty';
  }
  const stickerPath = stored.startsWith('/') ? stored : `${STICKER_DIR}/${stored}`;
  const RNFS = getRNFS();
  if (!RNFS) return `Paste clip ${slot} failed (no fs)`;

  if ((await readClipMeta(stickerPath))?.hasLinks === true) {
    HostDialog.tip(t('clip_links_note_only'));
    return `Paste clip ${slot} refused: links paste into notes only`;
  }

  const pending = pendingMosaicSidecars.get(stickerPath);
  if (pending) await pending;

  const sidecarPath = `${stickerPath}.mosaic.json`;
  const sidecar = await readMosaicSidecar(sidecarPath);
  const strokes = sidecar?.strokes?.length ? sidecar.strokes : null;
  const clipCards = await readClipCards(stickerPath);
  const cards = clipCards?.cards ?? [];
  console.log('[MOSAIC-CLIP] paste slot=', slot, 'sticker=', stickerPath,
    'stickerExists=', await RNFS.exists(stickerPath).catch(() => false),
    'sidecarExists=', await RNFS.exists(sidecarPath).catch(() => false),
    'waitedPending=', !!pending, 'strokes=', strokes?.length ?? 0,
    'cards=', cards.length,
    'coordinateSpace=', sidecar?.coordinateSpace ?? null,
    'sdkPenTypes=', JSON.stringify(Object.fromEntries((strokes ?? []).reduce((m: Map<number, number>, s: any) =>
      m.set(Number(s?.sdkPenType), (m.get(Number(s?.sdkPenType)) ?? 0) + 1), new Map<number, number>()))));
  if ((!sidecar || !strokes) && cards.length === 0) {
    HostDialog.tip(t('clip_err_read_failed'));
    return `Paste clip ${slot} failed (no mosaic sidecar)`;
  }
  try {
    await RNFS.mkdir(MOSAIC_HANDOFF_DIR).catch(() => {});

    const pageSize = sidecar?.pageSize ?? clipCards?.pageSize;
    const coordinateSpace = sidecar?.coordinateSpace ?? 'note-page-px';
    if (cards.length > 0 && coordinateSpace !== 'note-page-px') {
      console.warn('[MOSAIC-CLIP] clip cards dropped: strokes are not in note page pixels', coordinateSpace);
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
    console.warn('[MOSAIC-CLIP] write paste file failed', e);
    return `Paste clip ${slot} failed`;
  }
  if (sketch) return `Pasted clip ${slot} to Mosaic`;
  if (!isMosaicSurface()) return `Paste clip ${slot} deferred: Mosaic surface is not active`;
  try { FloatingToolbar?.requestMosaicPasteStrokes?.(); } catch (e) { console.warn('[MOSAIC-CLIP] broadcast failed', e); }
  return `Pasted clip ${slot} to Mosaic`;
}

function showUseLassoMenuTip(): string {
  HostDialog.tip(t('clip_use_lasso_menu'));
  return 'Clip save moved to lasso menu';
}

async function clipSmartAction(slot: string): Promise<string> {
  if (_clipBusy) return 'Clip busy';
  _clipBusy = true;
  try {

    if (isMosaicSurface()) {
      const hasSel = (await readMosaicStrokesFile(MOSAIC_LASSO_FILE)) != null;
      console.log('[MOSAIC-CLIP] smart action slot=', slot, 'surface=', FloatingToolbarBridge.getMosaicSurface(),
        'lassoFile=', hasSel ? 'present' : 'absent', '=>', hasSel ? 'save' : 'paste');
      return hasSel ? await mosaicClipSave(slot) : await mosaicClipPaste(slot);
    }

    const hasHostLasso = await FloatingToolbar.hasHostLasso();
    if (!hasHostLasso) return await clipPasteSticker(slot);

    if (!await hasLassoSelection()) return await clipPasteSticker(slot);
    return showUseLassoMenuTip();
  } finally {
    _clipBusy = false;
  }
}

async function saveNoteClipboardSticker(elements: any[], context: {
  path: string; pageWidth: number; pageHeight: number;
  machineType: number; runtimeDeviceType: number;
}): Promise<any> {
  const range = normalizeStickerElementCoordinateMetadata(elements, {
    width: context.pageWidth, height: context.pageHeight,
  });
  if (!range) throw new Error('Sticker coordinate metadata unavailable');
  const sourceLayers = elements.map(element => element.layerNum);
  try {
    for (const element of elements) element.layerNum = 0;
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

async function deleteClipFiles(stickerPath: string): Promise<void> {
  const cards = await readClipCards(stickerPath);
  const images = (cards?.cards ?? []).flatMap(card => card.kind === 'image' ? [`${stickerPath}${card.file}`] : []);
  for (const file of [...images, ...clipFiles(stickerPath)]) {
    try { await FileUtils.deleteFile(file); } catch (_) {}
  }
}

const CLIP_CARDS_SUFFIX = '.cards.json';
type ClipCardRect = { left: number; top: number; right: number; bottom: number };
type ClipCard =
  | (ClipCardRect & { kind: 'text'; text: string; fontSize: number; bold: boolean; italic: boolean })
  | (ClipCardRect & { kind: 'image'; file: string });
type ClipCards = { pageSize: { width: number; height: number } | null; cards: ClipCard[] };

function clipCardRect(rect: any): ClipCardRect | null {
  const left = Number(rect?.left); const top = Number(rect?.top);
  const right = Number(rect?.right); const bottom = Number(rect?.bottom);
  if (![left, top, right, bottom].every(Number.isFinite) || right <= left || bottom <= top) return null;
  return { left, top, right, bottom };
}

async function readClipCards(stickerPath: string): Promise<ClipCards | null> {
  try {
    const RNFS = getRNFS();
    const path = `${stickerPath}${CLIP_CARDS_SUFFIX}`;
    if (!RNFS || !(await RNFS.exists(path))) return null;
    const json = JSON.parse(await RNFS.readFile(path, 'utf8'));
    const cards: ClipCard[] = [];
    for (const card of Array.isArray(json?.cards) ? json.cards : []) {
      const rect = clipCardRect(card);
      if (!rect) continue;
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
    console.warn('[MOSAIC-CLIP] read clip cards failed', stickerPath, error);
    return null;
  }
}

async function exportClipCards(
  stickerPath: string, elements: any[], pageSize: { width: number; height: number } | null,
): Promise<void> {
  const RNFS = getRNFS();
  if (!RNFS) return;

  const ordered: (ClipCard | null)[] = [];
  const uncopied: { index: number; rect: ClipCardRect }[] = [];
  let images = 0;
  for (const el of elements) {
    const type = Number(el?.type);
    if (type === PICTURE_ELEMENT_TYPE) {
      const source = el.picture?.picturePath;
      const rect = clipCardRect(el.picture?.rect);
      if (!rect) {
        console.warn('[MOSAIC-CLIP] clip picture skipped path=', source, 'rect=', JSON.stringify(el.picture?.rect));
        continue;
      }
      const ext = typeof source === 'string' ? /\.(png|jpe?g)$/i.exec(source)?.[0]?.toLowerCase() ?? '.png' : '.png';
      const file = `.img${images++}${ext}`;
      try {
        if (typeof source !== 'string' || !source) throw new Error('no picturePath');
        await RNFS.copyFile(source, `${stickerPath}${file}`);
        ordered.push({ ...rect, kind: 'image', file });
      } catch (error) {

        console.warn('[MOSAIC-CLIP] copy clip picture failed', source, error);
        uncopied.push({ index: ordered.length, rect });
        ordered.push(null);
      }
    } else if (type === 500 || type === 501 || type === 502) {
      const box = el.textBox;
      const label = box?.textContentFull;
      const rect = clipCardRect(box?.textRect);
      if (typeof label !== 'string' || !label.trim() || !rect) continue;

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
      if (file) ordered[u.index] = { ...u.rect, kind: 'image', file };
    });
  }
  const cards = ordered.filter((card): card is ClipCard => card !== null);
  console.log('[MOSAIC-CLIP] clip cards=', cards.length,
    'rects=', JSON.stringify(cards.map(card => [card.kind, card.left, card.top, card.right, card.bottom])),
    'pageSize=', pageSize ? `${pageSize.width}x${pageSize.height}` : null);
  if (cards.length === 0) return;
  await RNFS.writeFile(`${stickerPath}${CLIP_CARDS_SUFFIX}`, JSON.stringify({ v: 1, pageSize, cards }), 'utf8');
}

async function cropClipPictures(stickerPath: string, elements: any[], rects: ClipCardRect[]): Promise<(string | null)[]> {
  const none = rects.map(() => null);
  const crop = NativeModules.InklingImageUtil?.cropRegion;
  if (typeof crop !== 'function') return none;
  let sw = 0; let sh = 0;
  try {
    const size: any = await PluginCommAPI.getStickerSize(stickerPath);
    sw = Number(size?.result?.width); sh = Number(size?.result?.height);
  } catch (error) {
    console.warn('[MOSAIC-CLIP] sticker size for picture crop failed', error);
  }
  if (!(sw > 0) || !(sh > 0)) return none;

  const frames: { name: string; rect: ClipCardRect }[] = [];
  const boxes = elements.flatMap(el => {
    const type = Number(el?.type);
    if (type === PICTURE_ELEMENT_TYPE) return [clipCardRect(el.picture?.rect)];
    if (type === 500 || type === 501 || type === 502) return [clipCardRect(el.textBox?.textRect)];
    return [undefined];
  });
  if (boxes.length > 0 && boxes.every(Boolean)) {
    const all = boxes as ClipCardRect[];
    frames.push({ name: 'cards', rect: {
      left: Math.min(...all.map(r => r.left)), top: Math.min(...all.map(r => r.top)),
      right: Math.max(...all.map(r => r.right)), bottom: Math.max(...all.map(r => r.bottom)),
    } });
  }
  try {
    const lasso = clipCardRect((await PluginCommAPI.getLassoRect() as any)?.result);
    if (lasso) frames.push({ name: 'lasso', rect: lasso });
  } catch (_) {}
  const aspect = sw / sh;
  const frame = frames.find(f => {
    const ratio = ((f.rect.right - f.rect.left) / (f.rect.bottom - f.rect.top)) / aspect;
    return Math.abs(ratio - 1) < 0.03;
  });
  if (!frame) {
    console.warn('[MOSAIC-CLIP] clip picture crop skipped: no frame matches sticker', `${sw}x${sh}`,
      'frames=', JSON.stringify(frames));
    return none;
  }

  const render = `${stickerPath}.render.png`;
  try {
    const res: any = await PluginCommAPI.generateStickerThumbnail(stickerPath, render, { width: sw, height: sh });
    if (!res?.success || res.result === false) {
      console.warn('[MOSAIC-CLIP] clip picture crop skipped: sticker render failed', JSON.stringify(res?.error ?? null));
      return none;
    }
    const fw = frame.rect.right - frame.rect.left;
    const fh = frame.rect.bottom - frame.rect.top;
    const files: (string | null)[] = [];
    for (let i = 0; i < rects.length; i++) {
      const rect = rects[i];
      const file = `.crop${i}.png`;
      try {
        await crop(render, fw, fh, rect.left - frame.rect.left, rect.top - frame.rect.top,
          rect.right - frame.rect.left, rect.bottom - frame.rect.top, `${stickerPath}${file}`);
        files.push(file);
      } catch (error) {
        console.warn('[MOSAIC-CLIP] clip picture crop failed rect=', JSON.stringify(rect), error);
        files.push(null);
      }
    }
    console.log('[MOSAIC-CLIP] clip pictures cropped from sticker frame=', frame.name, JSON.stringify(frame.rect),
      'sticker=', `${sw}x${sh}`, 'ok=', files.filter(Boolean).length, '/', rects.length);
    return files;
  } catch (error) {
    console.warn('[MOSAIC-CLIP] clip picture crop failed', error);
    return none;
  } finally {
    try { await FileUtils.deleteFile(render); } catch (_) {}
  }
}

async function readClipMeta(stickerPath: string): Promise<any | null> {
  try {
    let text = await FloatingToolbarBridge.readTextFile(`${stickerPath}.meta.json`);
    if (text === undefined) {
      const RNFS = getRNFS();
      const metaPath = `${stickerPath}.meta.json`;
      text = RNFS && await RNFS.exists(metaPath) ? await RNFS.readFile(metaPath, 'utf8') : null;
    }
    return text ? JSON.parse(text) : null;
  } catch (_) {
    return null;
  }
}

async function isHostLassoClip(stickerPath: string): Promise<boolean> {
  return (await readClipMeta(stickerPath))?.source === HOST_LASSO_CLIP_SOURCE;
}

async function ensureClipThumbnail(stickerPath: string): Promise<string | null> {
  const thumb = `${stickerPath}.png`;
  const RNFS = getRNFS();
  try {
    if (RNFS && await RNFS.exists(thumb)) return thumb;
    if (RNFS && !(await RNFS.exists(stickerPath))) return null;
    const size: any = await PluginCommAPI.getStickerSize(stickerPath);
    const width = Number(size?.result?.width);
    const height = Number(size?.result?.height);
    if (!size?.success || !(width > 0) || !(height > 0)) return null;
    const res: any = await PluginCommAPI.generateStickerThumbnail(stickerPath, thumb, { width, height });
    return res?.success && res.result !== false ? thumb : null;
  } catch (error) {
    console.warn('[CLIP-DBG] clip thumbnail failed', stickerPath, error);
    return null;
  }
}

async function exportHostClipSidecar(stickerPath: string): Promise<void> {
  let elements: any[] = [];
  try {
    const res: any = await PluginCommAPI.getLassoElements();
    elements = res?.success && Array.isArray(res.result) ? res.result : [];
    const pageSize = await currentNotePageSize();
    await exportClipCards(stickerPath, elements, pageSize).catch(error => {
      console.warn('[MOSAIC-CLIP] clip cards export failed', error);
    });
    const strokes = elements.filter(el => Number(el?.type) === 0);
    const uuids = strokes.map(el => el?.uuid).filter((u: any) => typeof u === 'string' && u.length > 0);
    if (!pageSize || uuids.length === 0 || uuids.length !== strokes.length) {
      console.log('[MOSAIC-CLIP] host clip sidecar skipped strokes=', strokes.length, 'uuids=', uuids.length);
      return;
    }
    const result: any = await FloatingToolbar.exportClipboardSidecar(
      stickerPath, uuids, pageSize.width, pageSize.height, null, null,
    );
    if (Number(result?.count) !== strokes.length) {
      console.warn('[MOSAIC-CLIP] host clip sidecar incomplete expected=', strokes.length, 'actual=', result?.count);
      try { await FileUtils.deleteFile(`${stickerPath}.mosaic.json`); } catch (_) {}
    }
  } catch (error) {
    console.warn('[MOSAIC-CLIP] host clip sidecar failed', error);
    try { await FileUtils.deleteFile(`${stickerPath}.mosaic.json`); } catch (_) {}
  } finally {
    recycleElements(elements);
  }
}

type HostClipPlacement = {
  pageSize: { width: number; height: number };
  rect: StickerPlacementMetadata['sourceRect'];
};

async function captureHostClipPlacement(): Promise<HostClipPlacement | null> {
  try {
    const pageSize = await currentNotePageSize();
    if (!pageSize) return null;
    const lr: any = await PluginCommAPI.getLassoRect();
    const r = lr?.success ? lr.result : null;
    if (!validStickerPlacementRect(r)) return null;
    const rect = { left: Number(r.left), top: Number(r.top), right: Number(r.right), bottom: Number(r.bottom) };
    console.log('[CLIP-DBG] host clip placement', `${pageSize.width}x${pageSize.height}`, JSON.stringify(rect));
    return { pageSize, rect };
  } catch (error) {
    console.warn('[CLIP-DBG] host clip placement unavailable', error);
    return null;
  }
}

async function commitHostClip(
  slot: string, pendingPath: string,
  content: { inkOnly: boolean | null; hasLinks: boolean | null },
  placement: HostClipPlacement | null,
): Promise<boolean> {
  const RNFS = getRNFS();
  if (!RNFS) return false;
  const finalPath = `${STICKER_DIR}/quickbar_clip_${slot}_${Date.now()}.sticker`;
  await RNFS.moveFile(pendingPath, finalPath);
  if (await RNFS.exists(`${pendingPath}.mosaic.json`)) {
    await RNFS.moveFile(`${pendingPath}.mosaic.json`, `${finalPath}.mosaic.json`);
  }
  const cards = await readClipCards(pendingPath);
  if (cards) {
    for (const card of cards.cards) {
      if (card.kind === 'image') await RNFS.moveFile(`${pendingPath}${card.file}`, `${finalPath}${card.file}`);
    }
    await RNFS.moveFile(`${pendingPath}${CLIP_CARDS_SUFFIX}`, `${finalPath}${CLIP_CARDS_SUFFIX}`);
  }

  try { await FileUtils.deleteFile(`${pendingPath}.meta.json`); } catch (_) {}

  const { inkOnly, hasLinks } = content;
  await RNFS.writeFile(`${finalPath}.meta.json`, JSON.stringify({
    v: 2, source: HOST_LASSO_CLIP_SOURCE, ...(inkOnly === null ? {} : { inkOnly }),
    ...(hasLinks === null ? {} : { hasLinks }),
    ...(placement ? { sourcePageSize: placement.pageSize, sourceRect: placement.rect } : {}),
  }), 'utf8');
  const clips = await loadClips();
  const oldPath = clips[slot];
  if (oldPath && oldPath !== finalPath) await deleteClipFiles(oldPath);
  clips[slot] = finalPath;
  await saveClips(clips);
  await ensureClipThumbnail(finalPath);
  console.log('[CLIP-DBG] host lasso clip committed slot=', slot, 'path=', finalPath);
  return true;
}

export async function saveClipFromLassoMenu(): Promise<string> {
  if (lassoMenuClipBusy) return 'Clip busy';
  lassoMenuClipBusy = true;
  const pendingPath = `${STICKER_DIR}/pending_clip_${Date.now()}.sticker`;
  let pendingUsed = false;
  try {
    await ensureStickerDir();
    const startedAt = Date.now();
    let hostSaved = false;
    try {
      const res: any = await PluginCommAPI.saveStickerByLasso(pendingPath);
      hostSaved = res?.success === true && res.result === true;
      console.log('[CLIP-DBG] lasso menu saveStickerByLasso success=', res?.success, 'result=', res?.result,
        'error=', JSON.stringify(res?.error ?? null), 'elapsedMs=', Date.now() - startedAt);
    } catch (error) {
      console.warn('[CLIP-DBG] lasso menu saveStickerByLasso threw', error);
    }

    let inkOnly: boolean | null = null;
    let hasLinks: boolean | null = null;
    let placement: HostClipPlacement | null = null;
    if (hostSaved) {
      placement = await captureHostClipPlacement();
      let kinds: ClipboardSkipKind[] | null = null;
      try {
        const counts: any = await PluginCommAPI.getLassoElementTypeCounts?.();
        if (counts?.success !== false && counts?.result) kinds = clipboardSkipKindsFromCounts(counts.result);
      } catch (error) {
        console.warn('[CLIP-DBG] lasso menu type counts unavailable', error);
      }
      console.log('[CLIP-DBG] lasso menu clip kinds=', JSON.stringify(kinds));
      if (kinds?.includes('title')) {
        HostDialog.tip(clipboardSkipError('title'));
        return 'Clip save refused: titles selected';
      }
      if (kinds) {
        inkOnly = !kinds.includes('textbox') && !kinds.includes('image');
        hasLinks = kinds.includes('link');
      }
    }
    const sidecar = hostSaved ? exportHostClipSidecar(pendingPath) : Promise.resolve();

    const slot = await pickClipSlot('save');
    await sidecar;
    if (!slot) return 'Clip save cancelled';

    const result = await withClipSlot(slot, async () => {
      if (hostSaved) {
        pendingUsed = await commitHostClip(slot, pendingPath, { inkOnly, hasLinks }, placement).catch(error => {
          console.warn('[CLIP-DBG] host lasso clip commit failed', error);
          return false;
        });
        if (!pendingUsed) {
          HostDialog.tip(t('clip_save_failed'));
          return `Save clip ${slot} failed`;
        }
        try { await PluginCommAPI.setLassoBoxState(2); } catch (_) {}
        return `Saved to clip ${slot}`;
      }
      const legacy = await clipSave(slot, { confirmOverwrite: false });
      if (legacy === '__NO_LASSO__' || legacy === '__HOST_NO_PAGE__') {
        if (legacy === '__NO_LASSO__') HostDialog.tip(t('clip_save_failed'));
        return `Save clip ${slot} failed`;
      }
      return legacy;
    });
    await refreshTitleClips();
    return result;
  } finally {
    if (!pendingUsed) await deleteClipFiles(pendingPath);
    lassoMenuClipBusy = false;
  }
}

async function pickClipSlot(mode: 'save' | 'paste'): Promise<string | null> {
  const clips = await loadClips();
  const slots = [];
  for (let n = 1; n <= CLIP_SLOT_COUNT; n++) {
    const path = clips[String(n)];
    slots.push({ slot: String(n), filled: !!path, thumb: path ? await ensureClipThumbnail(path) : null });
  }
  return FloatingToolbar.showClipSlotPicker(JSON.stringify(slots), mode);
}

async function openClipManager(): Promise<string> {

  if (isMosaicSurface()) {
    const hasSel = (await readMosaicStrokesFile(MOSAIC_LASSO_FILE)) != null;
    console.log('[MOSAIC-CLIP] clip manager selection=', hasSel);
    const slot = await pickClipSlot(hasSel ? 'save' : 'paste');
    if (!slot) return hasSel ? 'Clip save cancelled' : 'Clip paste cancelled';
    return withClipSlot(slot, () => (hasSel ? mosaicClipSave(slot) : mosaicClipPaste(slot)));
  }
  const selected = await FloatingToolbar.hasHostLasso() && await hasLassoSelection();
  console.log('[ToolActions]: clip manager selection=', selected);
  if (selected) return showUseLassoMenuTip();
  const slot = await pickClipSlot('paste');
  if (!slot) return 'Clip paste cancelled';
  return withClipSlot(slot, () => clipPasteSticker(slot));
}

function sketchFallbackPageSize(screenShortPx?: number | null): { width: number; height: number } {
  return screenShortPx && screenShortPx >= 1900 ? { width: 1920, height: 2560 } : { width: 1404, height: 1872 };
}

export async function openSketchClipManager(selection: boolean): Promise<string> {
  let outcome = 'none';
  try {
    const slot = await pickClipSlot(selection ? 'save' : 'paste');
    if (slot) {
      const result = await withClipSlot(slot, () => (selection
        ? mosaicClipSave(slot, { sketch: true })
        : mosaicClipPaste(slot, { sketch: true })));
      console.log('[MOSAIC-CLIP] sketch clip manager selection=', selection, 'slot=', slot, 'result=', result);
      if (result === `Saved to clip ${slot}`) outcome = 'saved';
      else if (result === `Pasted clip ${slot} to Mosaic`) outcome = 'paste';
    }
  } catch (e) {
    console.warn('[MOSAIC-CLIP] sketch clip manager failed', e);
  } finally {
    try { FloatingToolbar?.finishMosaicSketchClipboard?.(outcome); }
    catch (e) { console.warn('[MOSAIC-CLIP] sketch clip result not sent', e); }
  }
  return outcome;
}

async function clipSave(
  slot: string, opts: { confirmOverwrite?: boolean } = {},
): Promise<string> {
  await ensureStickerDir();

  const existingClips = await loadClips();
  const oldPath = existingClips[slot];
  const name = `quickbar_clip_${slot}_${Date.now()}.sticker`;
  const path = `${STICKER_DIR}/${name}`;

  console.log('[ToolActions]: clipSave slot=', slot, 'path=', path);

  const commitSave = async (): Promise<string> => {
    if (oldPath) await deleteClipFiles(oldPath);
    await PluginCommAPI.setLassoBoxState(2);
    const clips = await loadClips();
    clips[slot] = path;
    await saveClips(clips);
    return `Saved to clip ${slot}`;
  };

  const startedAt = Date.now();
  console.log('[CLIP-DBG] ===== clipSave element conversion =====');
  let sourceLassoRect: StickerPlacementMetadata['sourceRect'] | null = null;
  try {
    const rectRes: any = await PluginCommAPI.getLassoRect();
    if (rectRes?.success && validStickerPlacementRect(rectRes.result)) {
      sourceLassoRect = {
        left: Number(rectRes.result.left), top: Number(rectRes.result.top),
        right: Number(rectRes.result.right), bottom: Number(rectRes.result.bottom),
      };
      console.log('[CLIP-DBG] source lasso rect=', JSON.stringify(sourceLassoRect));
    } else {
      console.warn('[CLIP-DBG] source lasso rect unavailable', JSON.stringify(rectRes));
    }
  } catch (error) {
    console.warn('[CLIP-DBG] source lasso rect read failed', error);
  }
  let snapshot: any = await getLassoElementsForSidecar().catch(error => {
    console.warn('[CLIP-DBG] lasso element read failed', error);
    return null;
  });
  if (isHostNoPageError(snapshot)) {

    console.warn('[CLIP-DBG] lasso read blocked by host last-page NO_PAGE');
    HostDialog.tip(t('doc_last_page_lasso'));
    return '__HOST_NO_PAGE__';
  }
  if (!snapshot?.success || !Array.isArray(snapshot.result) || snapshot.result.length === 0) {
    return '__NO_LASSO__';
  }

  const selectedElements: any[] = snapshot.result;
  let typeCounts: any = null;
  try {
    const countResponse: any = await PluginCommAPI.getLassoElementTypeCounts?.();
    if (countResponse?.success !== false) typeCounts = countResponse?.result ?? null;
  } catch (error) {
    console.warn('[CLIP-DBG] lasso type counts unavailable', error);
  }
  const skippedKinds = clipboardSkipKindsFromCounts(typeCounts);
  const convertibleElements = selectedElements.filter(element =>
    CLIP_CONVERTIBLE_TYPES.has(Number(element?.type)),
  );
  const elementKinds = selectedElements
    .filter(element => !CLIP_CONVERTIBLE_TYPES.has(Number(element?.type)))
    .map(clipboardSkipKindForElement);
  const allSkippedKinds = Array.from(new Set([...skippedKinds, ...elementKinds]));
  console.log('[CLIP-DBG] lasso type counts=', JSON.stringify(typeCounts),
    'selected=', selectedElements.length,
    'convertible=', convertibleElements.length,
    'skipped=', clipboardSkipLabels(allSkippedKinds));

  if (convertibleElements.length === 0) {
    recycleElements(selectedElements);
    const firstKind = allSkippedKinds[0] ?? 'other';
    HostDialog.tip(
      allSkippedKinds.length === 1
        ? clipboardSkipError(firstKind)
        : t('clip_err_unsupported', { types: clipboardSkipLabels(allSkippedKinds) }),
    );
    return `Save clip ${slot} failed: unsupported elements`;
  }

  if (convertibleElements.length !== selectedElements.length) {
    recycleElements(selectedElements.filter(element =>
      !CLIP_CONVERTIBLE_TYPES.has(Number(element?.type)),
    ));
    snapshot.result = convertibleElements;
    console.log('[CLIP-DBG] filtered unsupported lasso elements before sticker conversion',
      'remaining=', convertibleElements.length,
      'skipped=', selectedElements.length - convertibleElements.length);
  }

  const sourcePageSize = await currentNotePageSize();
  if (!sourcePageSize) {
    HostDialog.tip(t('clip_save_failed'));
    return `Save clip ${slot} failed (page size unavailable)`;
  }

  const runtimeDeviceType = await PluginManager.getDeviceType();
  const deviceType = stickerMachineTypeForPageSize(sourcePageSize, runtimeDeviceType);
  const saveApi = 'convertElement2Sticker';
  console.log('[CLIP-DBG] sticker target machineType=', deviceType,
    'runtimeDeviceType=', runtimeDeviceType,
    'coordinateMode=android-page',
    'elements=', snapshot.result.length,
    'sourcePageSize=', `${sourcePageSize.width}x${sourcePageSize.height}`,
    'saveApi=', saveApi);
  let nativeSaved = false;
  try {
    const converted: any = await saveNoteClipboardSticker(snapshot.result, {
      path, pageWidth: sourcePageSize.width, pageHeight: sourcePageSize.height,
      machineType: deviceType, runtimeDeviceType,
    });
    console.log('[CLIP-DBG] sticker save API=', saveApi, 'machineType=', deviceType,
      'result=', JSON.stringify(converted),
      'elapsedMs=', Date.now() - startedAt);
    nativeSaved = !!converted?.success && converted.result !== false;
  } catch (error) {
    console.warn('[CLIP-DBG] sticker save threw API=', saveApi, error);
  }
  if (!nativeSaved) {
    HostDialog.tip(t('clip_save_failed'));
    return `Save clip ${slot} failed`;
  }

  await writeStickerPlacementMetadata(path, sourcePageSize, sourceLassoRect);

  let placementRect = sourceLassoRect;
  try {
    const observed = await FloatingToolbar?.getObservedLassoPageRect?.(
      sourcePageSize.width, sourcePageSize.height,
    );
    if (validStickerPlacementRect(observed)) {
      placementRect = {
        left: observed.left, top: observed.top,
        right: observed.right, bottom: observed.bottom,
      };
      console.log('[CLIP-DBG] live resized lasso pageRect=', JSON.stringify(placementRect));
    }
  } catch (error) {
    console.warn('[CLIP-DBG] live resized lasso rect unavailable', error);
  }

  if (opts.confirmOverwrite !== false && oldPath && await getRNFS()?.exists(oldPath).catch(() => false)) {
    const confirmed = await HostDialog.confirm(
      t('clip_overwrite'), t('btn_cancel'), t('btn_confirm'), false
    ).catch(() => true);
    if (!confirmed) {
      try { await FileUtils.deleteFile(path); } catch (_) {}
      try { await FileUtils.deleteFile(path + '.meta.json'); } catch (_) {}
      return `Clip ${slot} overwrite cancelled`;
    }
  }

  const sidecarPromise = (async () => {
    if (snapshot?.success && snapshot.result?.length) {
      const reported = await currentNotePageSize();
      if (!reported) return;
      const pageSize = resolveElementPageSize(reported);
      let sourceMachineType: number | null = null;
      try {
        const machine: any = _filePath ? await PluginFileAPI.getFileMachineType(_filePath) : null;
        if (machine?.success && Number.isInteger(machine.result)) sourceMachineType = machine.result;
      } catch (_) {}
      console.log('[CLIP-DBG] sidecar source context file=', _filePath,
        'page=', _pageNum, 'machineType=', sourceMachineType,
        'pageSize=', `${pageSize.width}x${pageSize.height}`);
      const uuids = snapshot.result
        .map((element: any) => element?.uuid)
        .filter((uuid: any): uuid is string => typeof uuid === 'string' && uuid.length > 0);
      const expectedSidecarStrokes = snapshot.result.filter(
        (element: any) => Number(element?.type) === 0,
      ).length;
      const discardSidecar = async () => {
        console.warn('[MOSAIC-CLIP] sidecar discarded path=', path, 'expectedStrokes=', expectedSidecarStrokes,
          '— this clip cannot be pasted into Mosaic');
        await FileUtils.deleteFile(`${path}.mosaic.json`);

        await writeStickerPlacementMetadata(path, pageSize, placementRect);
      };
      if (uuids.length === snapshot.result.length) {
        try {
          const nativeResult = await FloatingToolbar.exportClipboardSidecar(
            path, uuids, pageSize.width, pageSize.height, sourceLassoRect, placementRect,
          );
          console.log('[CLIP-DBG] native sidecar export=', JSON.stringify(nativeResult));

          if (nativeResult?.success !== false &&
              Number(nativeResult?.count) === expectedSidecarStrokes) {
            return;
          }
          console.warn('[CLIP-DBG] native sidecar export incomplete; using JS fallback',
            'expected=', expectedSidecarStrokes, 'actual=', Number(nativeResult?.count ?? 0));
          await discardSidecar();
        } catch (error) {
          console.warn('[CLIP-DBG] native sidecar export failed; using JS fallback', error);
          await discardSidecar();
        }
      }
      const strokes = await elementsToMosaicStrokes(snapshot.result, pageSize, sourceLassoRect, placementRect);
      if (strokes.length !== expectedSidecarStrokes) {
        console.warn('[CLIP-DBG] JS sidecar export incomplete; discarding sidecar',
          'expected=', expectedSidecarStrokes, 'actual=', strokes.length);
        await discardSidecar();
        return;
      }
      if (strokes.length) {
        await writeMosaicSidecar(path, strokes, pageSize);

        const elementRect = sidecarStrokeRect(strokes, pageSize);
        if (elementRect) {
          await writeStickerPlacementMetadata(path, pageSize, elementRect);
          console.log('[CLIP-DBG] sticker placement metadata from elements=',
            JSON.stringify(elementRect));
        }
      }
    }
  })().catch(error => {
    console.warn('[MOSAIC-CLIP] note-origin sidecar failed', error);
  }).finally(() => {
    pendingMosaicSidecars.delete(path);
    console.log('[MOSAIC-CLIP] sidecar finished elapsedMs=', Date.now() - startedAt);
  });
  pendingMosaicSidecars.set(path, sidecarPromise);
  const result = await commitSave();
  console.log('[CLIP-DBG] native sticker committed elapsedMs=', Date.now() - startedAt);
  if (allSkippedKinds.length > 0) {
    HostDialog.tip(t('clip_saved_with_skipped', {
      skipped: clipboardSkipLabels(allSkippedKinds),
    }));
  }
  return result;
}

function stickerMachineTypeForPageSize(
  pageSize: { width: number; height: number }, fallback: number,
): number {
  const shortSide = Math.min(pageSize.width, pageSize.height);
  const longSide = Math.max(pageSize.width, pageSize.height);

  if (shortSide === 1404 && longSide === 1872) return 3;
  if (shortSide === 1920 && longSide === 2560) return 5;
  console.log('[CLIP-DBG] sticker machineType fallback=runtime', fallback,
    'pageSize=', `${pageSize.width}x${pageSize.height}`);
  return fallback;
}

const CLIP_PASTE_PROGRESS_DELAY_MS = 1500;

type ClipPasteOutcome = { message: string; selection: boolean };

async function clipPasteSticker(slot: string): Promise<string> {
  const clips = await loadClips();
  const stored = clips[slot];
  if (!stored) {
    HostDialog.tip(t('clip_empty_hint'));
    return 'Clip empty';
  }
  const path = stored.startsWith('/') ? stored : `${STICKER_DIR}/${stored}`;
  if (FloatingToolbarBridge.isDocumentMode()) {
    const textClip = path.endsWith('.textclip.json');
    const meta = textClip ? null : await readClipMeta(path);
    if (meta?.hasLinks === true) {
      HostDialog.tip(t('clip_links_note_only'));
      return `Paste clip ${slot} refused: links paste into notes only`;
    }

    if (textClip || meta?.inkOnly === false) {
      HostDialog.tip(t('clip_doc_ink_only'));
      return `Paste clip ${slot} refused: document accepts handwriting only`;
    }
  }

  FloatingToolbarBridge.beginPaletteApply();
  FloatingToolbarBridge.beginBusyProgress('paste', CLIP_PASTE_PROGRESS_DELAY_MS);
  let selection = false;
  try {
    const outcome = await pasteClipToPage(slot, path);
    selection = outcome.selection;
    return outcome.message;
  } finally {
    FloatingToolbarBridge.endBusyProgress();
    FloatingToolbarBridge.parkToolbarForPastedClip(selection);
    FloatingToolbarBridge.paletteApplyFinished();
  }
}

async function pasteClipToPage(slot: string, path: string): Promise<ClipPasteOutcome> {
  const failed = (message: string): ClipPasteOutcome => ({ message, selection: false });

  if (path.endsWith('.textclip.json')) {
    try {
      const RNFS = getRNFS();
      if (!RNFS) return failed(`Paste clip ${slot} failed (no fs)`);
      const content = await RNFS.readFile(path, 'utf8');
      const payload = JSON.parse(content);
      const items: any[] = Array.isArray(payload?.items) ? payload.items : [];
      if (items.length === 0) {
        return failed(`Paste clip ${slot} failed (empty textclip)`);
      }
      let okAll = true;
      let selection = false;
      let unionRect = { left: Infinity, top: Infinity, right: -Infinity, bottom: -Infinity };
      for (const tb of items) {
        console.log('[ToolActions] insertText input textRect:', JSON.stringify(tb.textRect));
        const r: any = await PluginNoteAPI.insertText?.(tb);
        console.log('[ToolActions] insertText result:', JSON.stringify(r));
        if (!r?.success) {
          okAll = false;
          console.warn('[ToolActions]: insertText failed:', r);
        } else if (tb.textRect) {
          unionRect.left = Math.min(unionRect.left, tb.textRect.left);
          unionRect.top = Math.min(unionRect.top, tb.textRect.top);
          unionRect.right = Math.max(unionRect.right, tb.textRect.right);
          unionRect.bottom = Math.max(unionRect.bottom, tb.textRect.bottom);
        }
      }

      if (unionRect.left < Infinity) {
        try {
          await new Promise(r => setTimeout(r, 500));

          await PluginCommAPI.lassoElements({ left: 0, top: 0, right: 1, bottom: 1 });
          await new Promise(r => setTimeout(r, 200));
          const pad = 5;
          const lassoTarget = {
            left: unionRect.left - pad, top: unionRect.top - pad,
            right: unionRect.right + pad, bottom: unionRect.bottom + pad,
          };
          console.log('[ToolActions] lassoElements target rect:', JSON.stringify(lassoTarget));
          const lr: any = await PluginCommAPI.lassoElements(lassoTarget);
          console.log('[ToolActions] lassoElements result:', JSON.stringify(lr));
          selection = lr?.success === true && lr.result === true;
          await PluginCommAPI.setLassoBoxState?.(0);
        } catch (e) { console.warn('[ToolActions] lassoElements after textclip paste:', e); }
      }

      return { message: okAll ? `Pasted clip ${slot}` : `Paste clip ${slot} partial`, selection };
    } catch (e) {
      console.warn('[ToolActions]: textclip paste error:', e);
      return failed(`Paste clip ${slot} failed`);
    }
  }

  const occupied = startOccupiedCapture('clip-paste');
  await occupied.ready;

  const direct = await pasteNoteFromMosaicSidecar(path, occupied);
  if (direct === 'selected' || direct === 'pasted-no-selection') {
    return { message: `Pasted clip ${slot}`, selection: direct === 'selected' };
  }
  if (direct === 'failed') {
    console.warn('[CLIP-DBG] direct note sidecar paste failed; trying sticker path');
  }

  console.log('[CLIP-DBG] clipPaste path=', path);
  try {
    const pasteSzRes = await PluginCommAPI.getStickerSize(path);
    console.log('[CLIP-DBG] paste getStickerSize before insert=', JSON.stringify(pasteSzRes));
  } catch (e) {
    console.warn('[CLIP-DBG] paste getStickerSize before insert failed:', e);
  }

  try {
    const r = await PluginCommAPI.insertSticker(path);
    console.log('[CLIP-DBG] insertSticker result:', JSON.stringify(r));
    if (r.success) {
      const restored = await restoreInsertedStickerPlacement(path, occupied);
      console.log('[CLIP-DBG] insertSticker placement restored=', restored);
      notifyStickerInserted();
      return { message: `Pasted clip ${slot}`, selection: true };
    }
  } catch (e) { console.warn('[CLIP-DBG] insertSticker error:', e); }

  try {
    const r2 = await PluginCommAPI.insertSticker(path + '.sticker');
    console.log('[CLIP-DBG] insertSticker(.sticker) result:', JSON.stringify(r2));
    if (r2.success) {
      const restored = await restoreInsertedStickerPlacement(path, occupied);
      console.log('[CLIP-DBG] insertSticker(.sticker) placement restored=', restored);
      notifyStickerInserted();
      return { message: `Pasted clip ${slot}`, selection: true };
    }
  } catch (e) { console.warn('[CLIP-DBG] insertSticker(.sticker) error:', e); }

  return failed(`Paste clip ${slot} failed`);
}

export async function clipClear(slot: string): Promise<string> {
  const clips = await loadClips();
  const oldPath = clips[slot];
  if (oldPath) await deleteClipFiles(oldPath);
  clips[slot] = null;
  await saveClips(clips);
  return `Clip ${slot} cleared`;
}

export interface PaletteLassoInfo {
  elementNums: number[];
  strokeCount: number;
  geometryCount: number;
  avgThickness: number;
  dominantPenType: number | null;
  dominantPenColor: number | null;
  elements?: any[];
}

export async function getPaletteLassoInfo(retainElements?: boolean): Promise<PaletteLassoInfo | null> {
  const t0 = Date.now();
  console.log('[PLT/stats] START');

  _paletteLassoNoPage = false;
  const elemRes = await PluginCommAPI.getLassoElements() as any;
  _paletteLassoNoPage = isHostNoPageError(elemRes);
  if (!elemRes?.success || !Array.isArray(elemRes.result) || elemRes.result.length === 0) {
    console.log(`[PLT/stats] getLassoElements empty ok=${elemRes?.success} n=${elemRes?.result?.length ?? 0} +${Date.now() - t0}ms`);
    return null;
  }
  const els: any[] = elemRes.result;
  console.log(`[PLT/stats] ${els.length} raw elements +${Date.now() - t0}ms`);
  let shouldRecycle = true;
  try {
    const strokes = els.filter((el: any) => el?.type === 0);
    const geoms   = els.filter((el: any) => el?.type === 700);
    const skipped = els.length - strokes.length - geoms.length;
    const elementNums = [...strokes, ...geoms]
      .map((el: any) => el?.numInPage)
      .filter((n: any): n is number => typeof n === 'number');
    console.log(`[PLT/stats] strokes=${strokes.length} geoms=${geoms.length} skip=${skipped} validNums=${elementNums.length}`);
    if (elementNums.length === 0) return null;

    const thicknessVals: number[] = [];
    for (const el of strokes) {
      const thickness = rawElementThickness(el);
      if (thickness != null) thicknessVals.push(thickness);
    }
    for (const el of geoms)   if (typeof el?.geometry?.penWidth === 'number') thicknessVals.push(el.geometry.penWidth);
    const avgThickness = thicknessVals.length
      ? Math.round(thicknessVals.reduce((a, b) => a + b, 0) / thicknessVals.length)
      : 100;

    const penTypeCount = new Map<number, number>();
    const penColorCount = new Map<number, number>();
    for (const el of strokes) {
      const pt = el?.stroke?.penType;
      if (typeof pt === 'number') penTypeCount.set(pt, (penTypeCount.get(pt) ?? 0) + 1);
      const pc = el?.stroke?.penColor;
      if (typeof pc === 'number') penColorCount.set(pc, (penColorCount.get(pc) ?? 0) + 1);
    }
    for (const el of geoms) {
      const pc = el?.geometry?.penColor;
      if (typeof pc === 'number') penColorCount.set(pc, (penColorCount.get(pc) ?? 0) + 1);
    }
    const dominant = (m: Map<number, number>): number | null => {
      let best: number | null = null, bestN = 0;
      for (const [k, v] of m) if (v > bestN) { best = k; bestN = v; }
      return best;
    };

    const info: PaletteLassoInfo = {
      elementNums,
      strokeCount: strokes.length,
      geometryCount: geoms.length,
      avgThickness,
      dominantPenType: dominant(penTypeCount),
      dominantPenColor: dominant(penColorCount),
    };
    if (retainElements) {
      info.elements = els;
      shouldRecycle = false;
    }
    console.log(`[PLT/stats] result: s=${info.strokeCount} g=${info.geometryCount} avgT=${info.avgThickness} domPT=${info.dominantPenType} domPC=${info.dominantPenColor} retain=${!!retainElements} +${Date.now() - t0}ms`);
    return info;
  } finally {
    if (shouldRecycle) {
      recycleElements(els);
      console.log(`[PLT/stats] recycled ${els.length} +${Date.now() - t0}ms`);
    }
  }
}

let modeExitSub: Subscription | null = null;

export function attachModeListeners(): void {
  modeExitSub?.remove();
  modeExitSub = FloatingToolbarBridge.onToolModeExit(({ toolAction }) => {
    switch (toolAction) {
      case 'insert_text':
      case 'text_recv_nospacing':
      case 'text_recv_paragraph':
        FloatingBubbleBridge.hide();
        break;
      case TOOL_IDS.AI_RELAY:
        stopAiMode();
        break;
    }
  });
}

export function detachModeListeners(): void {
  modeExitSub?.remove();
  modeExitSub = null;
}
