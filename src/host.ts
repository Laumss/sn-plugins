import { NativeUIUtils, PluginCommAPI, PluginFileAPI, PluginNoteAPI } from 'sn-plugin-lib';
import type { Element, Point, Rect } from 'sn-plugin-lib';
import { canonicalPath, log, withTimeout } from './common';
import { t } from './i18n';
import { hasNativeModule, notipalHost, notipalToolbar } from './native';
import type { HostStatePayload } from './native';

const TAG = 'host';

const HOST_CALL_TIMEOUT_MS = 5000;

export interface HostCtx {
  surface: 'note' | 'document' | 'mosaic' | 'other';

  document: boolean;

  filePath: string;

  hostFilePath: string;

  page: number;

  pageSize: { width: number; height: number };

  noPage: boolean;
  lassoMenuOpen: boolean;
  docTextSelected: boolean;
}

const INITIAL: HostStatePayload = {
  surface: 'note',
  document: false,
  inHostApp: true,
  pluginViewOpen: false,
  permissionsOk: true,
  restoring: false,
  lassoMenuOpen: false,
  docTextSelected: false,
  docLassoSelected: false,
  mosaicSurface: 'board',
  mosaicNoteRef: null,
};

let current: HostStatePayload = INITIAL;

type HostListener = (next: HostStatePayload, prev: HostStatePayload) => void;
const listeners = new Set<HostListener>();

export function hostState(): HostStatePayload {
  return current;
}

export function onHostChange(listener: HostListener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function adopt(next: HostStatePayload, reason: string): void {
  const prev = current;
  current = next;
  if (prev.surface !== next.surface) {
    log.i(TAG, `surface ${prev.surface} → ${next.surface} reason=${reason}`);
  }
  listeners.forEach(listener => {
    try {
      listener(next, prev);
    } catch (e) {
      log.e(TAG, 'host change listener failed', e);
    }
  });
}

export function onHostState(payload: HostStatePayload): void {
  adopt(payload, 'event');
}

export function initHost(): void {
  if (!hasNativeModule('NotipalHost')) {
    log.w(TAG, 'NotipalHost missing, host state stays at defaults');
    return;
  }
  try {
    adopt(notipalHost.getState(), 'init');
  } catch (e) {
    log.w(TAG, 'initial host state unavailable', e);
  }
}

export interface HostResponse {
  success?: boolean;
  result?: unknown;
  error?: { code?: number; message?: string };
}

export function asResponse(value: unknown): HostResponse | null {
  return value && typeof value === 'object' ? (value as HostResponse) : null;
}

function resultOf(response: unknown): unknown {
  const r = asResponse(response);
  return r?.success ? r.result : undefined;
}

async function hostCall(call: Promise<unknown>, label: string): Promise<unknown> {
  try {
    return resultOf(await withTimeout(call, HOST_CALL_TIMEOUT_MS, label));
  } catch (e) {
    log.w(TAG, `${label} failed`, e);
    return undefined;
  }
}

const HOST_NO_PAGE_CODE = 97;

export function isHostNoPageError(response: unknown): boolean {
  const r = asResponse(response);
  const error = asResponse(r && 'error' in r && r.error != null ? r.error : response) as
    { code?: unknown; message?: unknown } | null;
  return error?.code === HOST_NO_PAGE_CODE || /page does not exist/i.test(String(error?.message ?? ''));
}

export function hostPagePath(filePath: string): string {
  return /\.pdf$/i.test(filePath) ? `${filePath}.mark` : filePath;
}

function validDimension(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0;
}

async function readPageSize(
  path: string,
  page: number,
): Promise<{ size: { width: number; height: number } | null; noPage: boolean }> {
  try {
    const response = await withTimeout(PluginFileAPI.getPageSize(path, page), HOST_CALL_TIMEOUT_MS, 'getPageSize');
    if (isHostNoPageError(response)) { return { size: null, noPage: true }; }
    const result = resultOf(response) as { width?: unknown; height?: unknown } | undefined;
    const width = result?.width;
    const height = result?.height;
    if (!validDimension(width) || !validDimension(height)) {
      log.w(TAG, 'getPageSize failed', asResponse(response)?.error ?? response);
      return { size: null, noPage: false };
    }
    return { size: { width: Math.round(width), height: Math.round(height) }, noPage: false };
  } catch (e) {
    log.w(TAG, 'getPageSize threw', e);
    return { size: null, noPage: false };
  }
}

export async function readHostCtx(base: HostStatePayload = current): Promise<HostCtx> {
  const [file, pageNum] = await Promise.all([
    hostCall(PluginCommAPI.getCurrentFilePath(), 'getCurrentFilePath'),
    hostCall(PluginCommAPI.getCurrentPageNum(), 'getCurrentPageNum'),
  ]);
  const rawPath = typeof file === 'string' ? file : '';
  const page = typeof pageNum === 'number' && Number.isInteger(pageNum) ? pageNum : -1;
  let pageSize = { width: 0, height: 0 };
  let noPage = false;
  if (rawPath && page >= 0) {

    const first = await readPageSize(rawPath, page);
    let read = first;
    if (first.noPage && hostPagePath(rawPath) !== rawPath) {
      read = await readPageSize(hostPagePath(rawPath), page);
    }
    if (read.size) { pageSize = read.size; }

    noPage = first.noPage && !read.size;
  }
  return {
    surface: base.surface,
    document: base.document,
    filePath: canonicalPath(rawPath),
    hostFilePath: rawPath,
    page,
    pageSize,
    noPage,
    lassoMenuOpen: base.lassoMenuOpen,
    docTextSelected: base.docTextSelected,
  };
}

export function hostCtxFromState(base: HostStatePayload = current): HostCtx {
  return {
    surface: base.surface,
    document: base.document,
    filePath: '',
    hostFilePath: '',
    page: -1,
    pageSize: { width: 0, height: 0 },
    noPage: false,
    lassoMenuOpen: base.lassoMenuOpen,
    docTextSelected: base.docTextSelected,
  };
}

export interface HostPageSize {
  width: number;
  height: number;
}

function validSelectionRect(rect: unknown): rect is Rect {
  if (!rect || typeof rect !== 'object') { return false; }
  const r = rect as Record<string, unknown>;
  const values = [r.left, r.top, r.right, r.bottom];
  if (!values.every(v => typeof v === 'number' && Number.isFinite(v))) { return false; }
  const { left, top, right, bottom } = rect as Rect;
  return right > left && bottom > top;
}

function rectFromHost(r: unknown): Rect | null {
  if (!r || typeof r !== 'object') { return null; }
  const { left, top, right, bottom } = r as Record<string, unknown>;
  if (![left, top, right, bottom].every(n => typeof n === 'number' && Number.isFinite(n))) { return null; }
  const rect = { left, top, right, bottom } as Rect;
  if ((rect.right - rect.left) < 2 || (rect.bottom - rect.top) < 2) { return null; }
  return rect;
}

function getHostPageSize(ctx: HostCtx, tag = 'page-size'): HostPageSize | null {
  const { width, height } = ctx.pageSize;
  if (validDimension(width) && validDimension(height)) { return { width, height }; }
  log.w(`HostPageCoordinates/${tag}`, `page size unavailable file=${ctx.filePath} page=${ctx.page}`);
  return null;
}

function currentNotePageSize(ctx: HostCtx): HostPageSize | null {
  const { width, height } = ctx.pageSize;
  if (Number.isInteger(width) && Number.isInteger(height) && width > 0 && height > 0) {
    log.i('MOSAIC-CLIP', `current note pageSize= ${width}x${height} file= ${ctx.filePath} page= ${ctx.page}`);
    return { width, height };
  }
  log.w('MOSAIC-CLIP', 'actual page size unavailable; coordinate conversion skipped');
  return null;
}

function resolveElementPageSize(reported: HostPageSize): HostPageSize {
  return reported;
}

function clampHostPageRect(rect: Rect, size: HostPageSize): Rect | null {
  const clamped = {
    left: Math.max(0, Math.floor(rect.left)),
    top: Math.max(0, Math.floor(rect.top)),
    right: Math.min(size.width, Math.ceil(rect.right)),
    bottom: Math.min(size.height, Math.ceil(rect.bottom)),
  };
  return clamped.right - clamped.left >= 2 && clamped.bottom - clamped.top >= 2
    ? clamped
    : null;
}

const PAGE_MARGIN_RIGHT = 0.04;
const MAX_INSERT_LEFT_RATIO = 0.60;
const MIN_TEXTBOX_WIDTH_RATIO = 0.20;
const MIN_TEXTBOX_FONT_COLUMNS = 8;

export interface SafeInsertionGeometry {
  left: number;
  right: number;
  safeMaxLeft: number;
  minTextboxWidth: number;
}

function normalizeInsertionLeft(
  pageWidth: number,
  requestedLeft: number,
  fontSize: number,
): SafeInsertionGeometry {
  const width = Math.max(1, Math.round(pageWidth));
  const right = width - Math.floor(width * PAGE_MARGIN_RIGHT);
  const minTextboxWidth = Math.min(
    right,
    Math.max(fontSize * MIN_TEXTBOX_FONT_COLUMNS, Math.floor(width * MIN_TEXTBOX_WIDTH_RATIO)),
  );
  const safeMaxLeft = Math.max(
    0,
    Math.min(Math.floor(width * MAX_INSERT_LEFT_RATIO), right - minTextboxWidth),
  );
  const left = Math.max(0, Math.min(Math.round(requestedLeft), safeMaxLeft));
  return { left, right, safeMaxLeft, minTextboxWidth };
}

type PointAccessor<T> = { size(): Promise<number>; getRange(start: number, count: number): Promise<T[]> };

function isAccessor<T>(value: unknown): value is PointAccessor<T> {
  return !!value && typeof (value as PointAccessor<T>).size === 'function'
    && typeof (value as PointAccessor<T>).getRange === 'function';
}

async function contourRings(el: Element, scale = { x: 1, y: 1 }): Promise<Point[][]> {
  const data: unknown = el?.contoursSrc;
  const count = Array.isArray(data) ? data.length : isAccessor<Point[]>(data) ? await data.size() : 0;
  if (!(count > 0)) { return []; }
  const rings: unknown = Array.isArray(data) ? data : isAccessor<Point[]>(data) ? await data.getRange(0, count) : null;
  if (!Array.isArray(rings) || rings.length !== count) { throw new Error('Incomplete contour data'); }
  return rings.map((ring: unknown) => {
    if (!Array.isArray(ring)) { throw new Error('Invalid contour ring'); }
    return ring.map((p: Point) => {
      if (!Number.isFinite(p?.x) || !Number.isFinite(p?.y)) { throw new Error('Invalid contour coordinate'); }
      return { x: p.x * scale.x, y: p.y * scale.y };
    });
  }).filter(ring => ring.length > 0);
}

function isPaletteInk(el: Element | null | undefined): boolean {
  return el?.type === 0 || el?.type === 700;
}

async function inkBox(el: Element | null | undefined): Promise<Rect | null> {
  let points: Point[];
  if (el?.type === 0 && el.stroke?.points) {
    const count = await el.stroke.points.size();
    points = count > 0 ? await el.stroke.points.getRange(0, count) : (await contourRings(el)).flat();
  } else if (el?.type === 700 && el.geometry?.points?.length) {
    points = el.geometry.points;
  } else { return null; }
  let left = Infinity, top = Infinity, right = -Infinity, bottom = -Infinity;
  for (const p of points) {
    if (!Number.isFinite(p?.x) || !Number.isFinite(p?.y)) { continue; }
    left = Math.min(left, p.x); top = Math.min(top, p.y);
    right = Math.max(right, p.x); bottom = Math.max(bottom, p.y);
  }
  return Number.isFinite(left) ? { left, top, right, bottom } : null;
}

function recognizedBox(el: Element | null | undefined): Rect | null {
  const r = el?.recognizeResult;
  const box = {
    left: Number(r?.up_left_point_x), top: Number(r?.up_left_point_y),
    right: Number(r?.down_right_point_x), bottom: Number(r?.down_right_point_y),
  };
  if (![box.left, box.top, box.right, box.bottom].every(Number.isFinite)) { return null; }
  if (box.right < box.left || box.bottom < box.top) { return null; }
  return box;
}

const RECOG_VERIFY_SAMPLES = 6;
const RECOG_INNER_TOLERANCE = 4;
const RECOG_OUTER_MARGIN = 60;

async function inkBoxes(
  ink: Element[],
  concurrency: number,
  tag: string,
): Promise<(Rect | null)[]> {
  const t0 = Date.now();
  const recog = ink.map(recognizedBox);
  const boxes: (Rect | null)[] = new Array(ink.length).fill(null);
  const exact = new Set<number>();

  const candidates: number[] = [];
  for (let i = 0; i < ink.length && candidates.length < RECOG_VERIFY_SAMPLES; i++) {
    if (recog[i] && ink[i]?.type === 0) { candidates.push(i); }
  }
  let trusted = candidates.length > 0;
  for (const i of candidates) {
    let real: Rect | null = null;
    try {
      real = await inkBox(ink[i]);
    } catch (e) {

      log.w(`PLT/${tag}`, 'sample ink box failed', e);
    }
    boxes[i] = real;
    exact.add(i);
    const r = recog[i]!;
    const ok = real != null &&
      real.left >= r.left - RECOG_INNER_TOLERANCE && real.top >= r.top - RECOG_INNER_TOLERANCE &&
      real.right <= r.right + RECOG_INNER_TOLERANCE && real.bottom <= r.bottom + RECOG_INNER_TOLERANCE &&
      r.left >= real.left - RECOG_OUTER_MARGIN && r.top >= real.top - RECOG_OUTER_MARGIN &&
      r.right <= real.right + RECOG_OUTER_MARGIN && r.bottom <= real.bottom + RECOG_OUTER_MARGIN;
    if (!ok) {
      trusted = false;
      log.i(`PLT/${tag}`, `recognize box rejected sample=${JSON.stringify(r)} points=${JSON.stringify(real)}`);
      break;
    }
  }

  const pending: number[] = [];
  for (let i = 0; i < ink.length; i++) {
    if (exact.has(i)) { continue; }
    if (trusted && recog[i]) { boxes[i] = recog[i]; }
    else { pending.push(i); }
  }
  let next = 0;
  const worker = async () => {
    while (next < pending.length) {
      const index = pending[next++];
      try {
        boxes[index] = await inkBox(ink[index]);
      } catch (e) {

        log.w(`PLT/${tag}`, `ink box failed index=${index}`, e);
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(concurrency, pending.length) }, () => worker()));
  log.i(`PLT/${tag}`, `ink boxes n=${ink.length} recognize=${trusted} verified=${exact.size} ` +
    `pointReads=${pending.length} +${Date.now() - t0}ms`);
  return boxes;
}

function findClearPasteOffset(
  rect: Rect,
  obstacles: Rect[],
  page: HostPageSize,
  step = 24,
): { dx: number; dy: number } | null {
  if (obstacles.length === 0) { return { dx: 0, dy: 0 }; }
  const w = rect.right - rect.left;
  const h = rect.bottom - rect.top;
  if (!(w > 0) || !(h > 0) || w > page.width || h > page.height) { return null; }
  const touchesBox = (r: Rect, o: Rect) =>
    o.left < r.right && o.right > r.left && o.top < r.bottom && o.bottom > r.top;
  const swallows = (r: Rect, o: Rect) => {
    const cx = (o.left + o.right) / 2;
    const cy = (o.top + o.bottom) / 2;
    return cx >= r.left && cx <= r.right && cy >= r.top && cy <= r.bottom;
  };
  const offsets: { dx: number; dy: number; d: number }[] = [];
  for (let dx = -Math.floor(rect.left / step) * step; rect.right + dx <= page.width; dx += step) {
    for (let dy = -Math.floor(rect.top / step) * step; rect.bottom + dy <= page.height; dy += step) {
      offsets.push({ dx, dy, d: dx * dx + dy * dy });
    }
  }
  offsets.sort((a, b) => a.d - b.d);
  const at = (o: { dx: number; dy: number }) => ({
    left: rect.left + o.dx, top: rect.top + o.dy, right: rect.right + o.dx, bottom: rect.bottom + o.dy,
  });
  const nearLimit = (Math.max(w, h) * 1.5) ** 2;
  for (const o of offsets) {
    if (o.d > nearLimit) { break; }
    const r = at(o);
    if (!obstacles.some((b) => touchesBox(r, b))) { return { dx: o.dx, dy: o.dy }; }
  }
  for (const o of offsets) {
    const r = at(o);
    if (!obstacles.some((b) => swallows(r, b))) { return { dx: o.dx, dy: o.dy }; }
  }
  return null;
}

export const coords = {
  getHostPageSize,

  getCurrentHostPageSize: getHostPageSize,
  currentNotePageSize,
  resolveElementPageSize,
  clampHostPageRect,
  normalizeInsertionLeft,
  validSelectionRect,
  rectFromHost,
  contourRings,
  isPaletteInk,
  inkBox,
  inkBoxes,
  findClearPasteOffset,
};

const EL_TAG = 'ElementOps';

function releaseHostElements(els: Iterable<Element | null | undefined> | null | undefined): void {
  for (const el of els ?? []) {
    const uuid = el?.uuid;
    if (typeof uuid !== 'string' || uuid.length === 0) { continue; }
    try {
      PluginCommAPI.recycleElement(uuid);
    } catch (e) {

      log.w(EL_TAG, `recycleElement failed uuid=${uuid}`, e);
    }
  }
}

function layerIdOf(layer: { layerId?: number; layerNum?: number } | null | undefined): number | undefined {
  return layer?.layerId !== undefined ? layer.layerId : layer?.layerNum;
}

function rawElementThickness(el: Element | null | undefined): number | null {
  const thickness = el?.thickness;
  return typeof thickness === 'number' && Number.isFinite(thickness) ? thickness : null;
}

export const elements = {
  releaseHostElements,
  layerIdOf,
  rawElementThickness,
};

const PLACE_TAG = 'InsertPlacement';
const INK_BOX_CONCURRENCY = 8;

const LEAST_OVERLAP_STEP = 48;

export interface OccupiedSnapshot {
  filePath: string;
  pageNum: number;
  pageSize: HostPageSize;

  boxes: Rect[];
}

export interface OccupiedCapture {

  ready: Promise<void>;
  snapshot: Promise<OccupiedSnapshot | null>;
}

function touches(a: Rect, b: Rect): boolean {
  return b.left < a.right && b.right > a.left && b.top < a.bottom && b.bottom > a.top;
}

function overlapArea(a: Rect, b: Rect): number {
  const w = Math.min(a.right, b.right) - Math.max(a.left, b.left);
  const h = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
  return w > 0 && h > 0 ? w * h : 0;
}

function shifted(rect: Rect, dx: number, dy: number): Rect {
  return { left: rect.left + dx, top: rect.top + dy, right: rect.right + dx, bottom: rect.bottom + dy };
}

function startOccupiedCapture(ctx: HostCtx, tag: string): OccupiedCapture {
  if (ctx.document) {
    return { ready: Promise.resolve(), snapshot: Promise.resolve(null) };
  }
  let release: () => void = () => undefined;
  const ready = new Promise<void>(resolve => { release = resolve; });
  const snapshot = (async (): Promise<OccupiedSnapshot | null> => {
    const t0 = Date.now();
    try {
      if (!ctx.hostFilePath || ctx.page < 0) { return null; }
      try {
        const saved = asResponse(await PluginNoteAPI.saveCurrentNote());
        if (saved?.success === false) { log.w(PLACE_TAG, `${tag} saveCurrentNote failed`, saved.error); }
      } catch (e) {
        log.w(PLACE_TAG, `${tag} saveCurrentNote threw`, e);
      }
      release();
      const pageSize = getHostPageSize(ctx, tag);
      if (!pageSize) { return null; }
      try {
        PluginCommAPI.clearElementCache();
      } catch (e) {

        log.w(PLACE_TAG, `${tag} clearElementCache failed`, e);
      }
      const res = asResponse(await PluginFileAPI.getElements(ctx.page, ctx.hostFilePath));
      if (!res?.success || !Array.isArray(res.result)) {

        log.w(PLACE_TAG, `${tag} getElements failed`, res?.error ?? res);
      }
      const els: Element[] = res?.success && Array.isArray(res.result) ? (res.result as Element[]) : [];
      try {
        const boxes: Rect[] = (await inkBoxes(els.filter(isPaletteInk), INK_BOX_CONCURRENCY, tag))
          .filter((b): b is Rect => b !== null);
        for (const el of els) {
          const type = Number(el?.type);
          const r: unknown = type === 200 ? el.picture?.rect
            : type === 500 || type === 501 || type === 502 ? el.textBox?.textRect
            : null;
          if (validSelectionRect(r)) { boxes.push({ left: r.left, top: r.top, right: r.right, bottom: r.bottom }); }
        }
        log.i(PLACE_TAG, `${tag} occupied boxes=${boxes.length} elements=${els.length} +${Date.now() - t0}ms`);
        return { filePath: ctx.filePath, pageNum: ctx.page, pageSize, boxes };
      } finally {
        releaseHostElements(els);
      }
    } catch (e) {
      log.w(PLACE_TAG, `${tag} occupied capture failed`, e);
      return null;
    } finally {
      release();
    }
  })();
  return { ready, snapshot };
}

function leastOverlapOffset(rect: Rect, boxes: Rect[], page: HostPageSize): { dx: number; dy: number } | null {
  const w = rect.right - rect.left;
  const h = rect.bottom - rect.top;
  if (w > page.width || h > page.height) { return null; }
  const areaAt = (dx: number, dy: number) => {
    const r = shifted(rect, dx, dy);
    let sum = 0;
    for (const b of boxes) { sum += overlapArea(r, b); }
    return sum;
  };
  const here = areaAt(0, 0);
  let best: { dx: number; dy: number; area: number; d: number } | null = null;
  for (let dx = -rect.left; rect.right + dx <= page.width; dx += LEAST_OVERLAP_STEP) {
    for (let dy = -rect.top; rect.bottom + dy <= page.height; dy += LEAST_OVERLAP_STEP) {
      const area = areaAt(dx, dy);
      const d = dx * dx + dy * dy;
      if (!best || area < best.area || (area === best.area && d < best.d)) { best = { dx, dy, area, d }; }
    }
  }
  return best && best.area < here ? { dx: best.dx, dy: best.dy } : null;
}

function relocatedRect(rect: Rect, snapshot: OccupiedSnapshot | null): Rect | null {
  if (!snapshot || !validSelectionRect(rect)) { return null; }
  const { boxes, pageSize } = snapshot;
  if (!boxes.some(b => touches(rect, b))) { return null; }
  const clear = findClearPasteOffset(rect, boxes, pageSize);
  const offset = clear && (clear.dx !== 0 || clear.dy !== 0) ? clear : leastOverlapOffset(rect, boxes, pageSize);
  if (!offset) {
    log.i(PLACE_TAG, `occupied but no better place ${JSON.stringify(rect)}`);
    return null;
  }
  return shifted(rect, offset.dx, offset.dy);
}

async function relocateFloatingStickerIfOccupied(
  capture: OccupiedCapture | null,
  tag: string,
  move: (target: Rect, tag: string) => Promise<boolean>,
): Promise<boolean> {
  if (!capture) { return false; }
  const snapshot = await capture.snapshot;
  if (!snapshot || snapshot.boxes.length === 0) { return false; }
  let rect: Rect | null = null;
  try {
    const res = asResponse(await PluginCommAPI.getLassoRect());
    const candidate = res?.success ? rectFromHost(res.result) : null;
    if (candidate && validSelectionRect(candidate) && (candidate.left > 1 || candidate.top > 1)) { rect = candidate; }
  } catch (e) {
    log.w(PLACE_TAG, `${tag} getLassoRect failed`, e);
  }
  if (!rect) { return false; }
  const target = relocatedRect(rect, snapshot);
  if (!target) { return false; }
  log.i(PLACE_TAG, `${tag} sticker frame ${JSON.stringify(rect)} occupied → ${JSON.stringify(target)}`);
  return move(target, tag);
}

export const placement = {
  startOccupiedCapture,
  relocatedRect,
  relocateFloatingStickerIfOccupied,
};

export interface SelectionRequest {
  expectedPage?: HostCtx;
  canContinue?: () => boolean;
}

async function isSameSelectionPage(expected: HostCtx): Promise<boolean> {
  const now = await readHostCtx();

  return !!now.filePath && !now.noPage && now.pageSize.width > 0 && now.pageSize.height > 0 &&
    now.filePath === expected.filePath && now.page === expected.page &&
    now.pageSize.width === expected.pageSize.width && now.pageSize.height === expected.pageSize.height;
}

async function selectionRequestIsCurrent(request?: SelectionRequest): Promise<boolean> {
  if (request?.canContinue && !request.canContinue()) { return false; }
  if (request?.expectedPage && !await isSameSelectionPage(request.expectedPage)) { return false; }
  return !request?.canContinue || request.canContinue();
}

function showHostNoPageHint(): void {
  dialog.tip(t('doc_last_page_lasso')).catch(e => log.w(TAG, 'no-page hint failed', e));
}

async function hasSelectedContent(): Promise<boolean | 'no_page'> {
  const rect = await PluginCommAPI.getLassoRect();
  if (isHostNoPageError(rect)) { return 'no_page'; }
  if (!rect?.success || !validSelectionRect(rect.result)) { return false; }
  const els = asResponse(await PluginCommAPI.getLassoElements());
  if (isHostNoPageError(els)) { return 'no_page'; }
  if (els?.success && Array.isArray(els.result)) {
    const selected = els.result.length > 0;
    releaseHostElements(els.result as Element[]);
    if (selected) { return true; }
  }

  try {
    const links = await PluginNoteAPI.getLassoLinks();
    return links?.success === true && Array.isArray(links.result) && links.result.length > 0;
  } catch (e) {
    log.w(TAG, 'getLassoLinks failed', e);
    return false;
  }
}

export const selection = {
  showHostNoPageHint,
  isSameSelectionPage,
  selectionRequestIsCurrent,
  hasSelectedContent,
};

export const dialog = {

  async tip(text: string): Promise<void> {
    try {
      if (hasNativeModule('NotipalHost')) {
        notipalHost.tip(text);
        return;
      }
      NativeUIUtils.showErrorTipDialog(text);
    } catch (e) {
      log.w(TAG, 'tip dialog failed', e);
    }
  },

  async confirm(text: string): Promise<boolean> {
    if (hasNativeModule('NotipalHost')) { return notipalHost.confirm(text, t('btn_cancel'), t('btn_confirm')); }
    return NativeUIUtils.showRattaDialog(text, t('btn_cancel'), t('btn_confirm'), false);
  },
};

const TXN_TAG = 'InsertTxn';

let txnActive: string | null = null;

export function activeInsertTxn(): string | null {
  return txnActive;
}

const TXN_REASON_MAX = 120;

function nativeTxn(begin: boolean, name: string, timeoutMs: number, ok: boolean, reason: string): void {
  if (!hasNativeModule('NotipalToolbar')) { return; }
  try {
    if (begin) { notipalToolbar.beginTxn(name, timeoutMs); }
    else { notipalToolbar.endTxn(name, ok, reason); }
  } catch (e) {
    log.w(TXN_TAG, `${begin ? 'beginTxn' : 'endTxn'} ${name} failed`, e);
  }
}

function failureReason(e: unknown): string {
  const text = e instanceof Error ? e.message : String(e);
  return text.length > TXN_REASON_MAX ? `${text.slice(0, TXN_REASON_MAX)}…` : text;
}

export async function insertTxn<T>(
  name: string,
  timeoutMs: number,
  body: (alive: () => boolean) => Promise<T>,
): Promise<T> {
  if (txnActive) {
    log.w(TXN_TAG, `${name} refused: ${txnActive} still running`);
    throw new Error(`insert txn ${name} refused: ${txnActive} still running`);
  }
  txnActive = name;
  let settled = false;
  const alive = () => !settled;
  const t0 = Date.now();
  log.i(TXN_TAG, `${name} begin timeout=${timeoutMs}ms`);
  nativeTxn(true, name, timeoutMs, false, '');
  let ok = false;
  let reason = 'ok';

  let bodyDone = false;
  try {
    let run: Promise<T>;
    try {
      run = body(alive);
    } catch (e) {

      bodyDone = true;
      throw e;
    }
    run.then(() => { bodyDone = true; }, () => { bodyDone = true; });
    const result = await withTimeout(run, timeoutMs, `insert txn ${name}`);
    ok = true;
    return result;
  } catch (e) {
    reason = bodyDone ? failureReason(e) : 'timeout';
    throw e;
  } finally {
    settled = true;
    txnActive = null;
    nativeTxn(false, name, timeoutMs, ok, reason);
    log.i(TXN_TAG, `${name} end ok=${ok} reason=${reason} +${Date.now() - t0}ms`);
  }
}
