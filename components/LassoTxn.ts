import { PluginNoteAPI, PluginFileAPI, PluginCommAPI, type Rect } from 'sn-plugin-lib';
import FloatingToolbarBridge from './FloatingToolbarBridge';
import { clampHostPageRect, getCurrentHostPageSize, getHostPageSize } from './HostPageCoordinates';

export type LassoRect = Rect;

let lassoTxnInFlight = false;

export function isLassoTxnBusy(): boolean {
  return lassoTxnInFlight;
}

export function beginLassoTxn(): boolean {
  if (lassoTxnInFlight) return false;
  lassoTxnInFlight = true;
  return true;
}

export function endLassoTxn(): void {
  lassoTxnInFlight = false;
}

export async function hostCall<T>(promise: Promise<T>, tag: string): Promise<T | null> {
  try {
    return await promise;
  } catch (e) {
    console.warn(`[PLT/${tag}] host request failed:`, e);
    return null;
  }
}

export async function prepareInsertedStickerSelection(): Promise<boolean> {
  if (lassoTxnInFlight) return false;
  if (!await FloatingToolbarBridge.hasPendingDocSticker()) return true;
  let pageElements: any[] = [];
  let selectedBefore: any[] = [];
  let commitStarted = false;
  lassoTxnInFlight = true;
  FloatingToolbarBridge.beginPaletteApply();
  try {
    const fp: any = await hostCall(PluginCommAPI.getCurrentFilePath() as Promise<any>, 'paste/file');
    const pg: any = await hostCall(PluginCommAPI.getCurrentPageNum() as Promise<any>, 'paste/page');
    if (!fp?.success || !pg?.success || !Number.isInteger(pg.result)) return false;
    const before: any = await hostCall(
      PluginCommAPI.getLassoElements() as Promise<any>, 'paste/selected-before');
    if (!before?.success || !Array.isArray(before.result)) return false;
    selectedBefore = before.result;

    let previewRect: LassoRect | null = null;
    const preview: any = await hostCall(
      PluginCommAPI.getLassoRect() as Promise<any>, 'paste/preview-rect',
    );
    const candidate = preview?.success ? rectFromHost(preview.result) : null;
    if (candidate && (candidate.left > 1 || candidate.top > 1)) previewRect = candidate;
    const selectedNums = new Set<number>(selectedBefore
      .filter(isPaletteInk)
      .map(el => el.numInPage)
      .filter((n: any): n is number => Number.isInteger(n)));
    if (selectedNums.size === 0) {

      console.log('[PLT/paste] no floating sticker left; clearing stale pending marker');
      FloatingToolbarBridge.clearPendingDocSticker();
      return true;
    }

    const closed = await hostCall(PluginCommAPI.setLassoBoxState(2), 'paste/commit');
    console.log(`[PLT/paste] setLassoBoxState(2) ok=${closed?.success}`);
    commitStarted = true;
    await waitForLassoCommit('paste');

    const saved: any = await hostCall(PluginNoteAPI.saveCurrentNote() as Promise<any>, 'paste/save');
    if (!saved?.success) {
      console.warn('[PLT/paste] saveCurrentNote failed, cannot read committed sticker');
      return false;
    }
    try { PluginCommAPI.clearElementCache?.(); } catch (_) {}
    const size = await getHostPageSize(fp.result, pg.result, 'paste/page-size');
    if (!size) return false;

    let inserted: any[] = [];
    for (let attempt = 0; attempt < 3; attempt++) {
      for (const el of pageElements) el?.recycle?.();
      pageElements = [];
      const fetched = await getElementsStable(pg.result, fp.result, 'paste/committed');
      if (!fetched.ok) return false;
      pageElements = fetched.els;
      inserted = pageElements.filter(el => selectedNums.has(el.numInPage) && isPaletteInk(el));
      if (inserted.length > 0) break;
    }
    if (inserted.length === 0) {
      console.warn(`[PLT/paste] committed sticker not found: want=${JSON.stringify([...selectedNums])}`);
      return false;
    }
    if (inserted.length !== selectedNums.size) {

      console.warn(`[PLT/paste] partial ${inserted.length}/${selectedNums.size}, proceeding`);
    }
    const pointRect = await committedInkRect(inserted, size);
    if (!pointRect) return false;

    const rect = previewRect ? {
      left: Math.max(0, Math.floor(Math.min(previewRect.left, pointRect.left)) - 5),
      top: Math.max(0, Math.floor(Math.min(previewRect.top, pointRect.top)) - 5),
      right: Math.min(size.width, Math.ceil(Math.max(previewRect.right, pointRect.right)) + 5),
      bottom: Math.min(size.height, Math.ceil(Math.max(previewRect.bottom, pointRect.bottom)) + 5),
    } : {
      left: Math.max(0, Math.floor(pointRect.left) - 32),
      top: Math.max(0, Math.floor(pointRect.top) - 32),
      right: Math.min(size.width, Math.ceil(pointRect.right) + 32),
      bottom: Math.min(size.height, Math.ceil(pointRect.bottom) + 32),
    };
    const expected = inserted.map(el => el.numInPage);

    const shown = await reselectLasso(rect, 'paste/ready');
    if (!shown) return false;
    FloatingToolbarBridge.parkToolbarForLasso(true);
    console.log(`[PLT/paste] committed nums=${JSON.stringify(expected)} pageRect=${JSON.stringify(rect)} selected=${shown}`);
    return shown;
  } catch (e) {
    console.warn('[PLT/paste] cannot prepare committed sticker selection:', e);
    return false;
  } finally {
    for (const el of selectedBefore) el?.recycle?.();
    for (const el of pageElements) el?.recycle?.();

    if (commitStarted) FloatingToolbarBridge.clearPendingDocSticker();
    lassoTxnInFlight = false;
    FloatingToolbarBridge.paletteApplyFinished();
  }
}

async function contourRings(el: any, scale = { x: 1, y: 1 }): Promise<{ x: number; y: number }[][]> {
  const data = el?.contoursSrc;
  const count = Array.isArray(data) ? data.length : await data?.size?.();
  if (!(count > 0)) return [];
  const rings = Array.isArray(data) ? data : await data.getRange(0, count);
  if (!Array.isArray(rings) || rings.length !== count) throw new Error('Incomplete contour data');
  return rings.map((ring: any[]) => {
    if (!Array.isArray(ring)) throw new Error('Invalid contour ring');
    return ring.map(p => {
      if (!Number.isFinite(p?.x) || !Number.isFinite(p?.y)) throw new Error('Invalid contour coordinate');
      return { x: p.x * scale.x, y: p.y * scale.y };
    });
  }).filter(ring => ring.length > 0);
}

async function committedInkRect(elements: any[], size: { width: number; height: number }) {
  let left = Infinity, top = Infinity, right = -Infinity, bottom = -Infinity;
  for (const el of elements) {
    let points: any[];
    if (el.type === 0 && el.stroke?.points) {
      const count = await el.stroke.points.size();

      points = count > 0 ? await el.stroke.points.getRange(0, count) : (await contourRings(el)).flat();
    } else if (el.type === 700 && el.geometry?.points?.length) {
      points = el.geometry.points;
    } else return null;
    if (!points.length) return null;
    for (const p of points) {
      if (!Number.isFinite(p.x) || !Number.isFinite(p.y)) return null;
      left = Math.min(left, p.x); top = Math.min(top, p.y);
      right = Math.max(right, p.x); bottom = Math.max(bottom, p.y);
    }
  }
  return rectFromHost({
    left: Math.max(0, Math.floor(left) - 5), top: Math.max(0, Math.floor(top) - 5),
    right: Math.min(size.width, Math.ceil(right) + 5), bottom: Math.min(size.height, Math.ceil(bottom) + 5),
  });
}

export async function inkBox(el: any): Promise<LassoRect | null> {
  let points: any[];
  if (el?.type === 0 && el.stroke?.points) {
    const count = await el.stroke.points.size();
    points = count > 0 ? await el.stroke.points.getRange(0, count) : (await contourRings(el)).flat();
  } else if (el?.type === 700 && el.geometry?.points?.length) {
    points = el.geometry.points;
  } else return null;
  let left = Infinity, top = Infinity, right = -Infinity, bottom = -Infinity;
  for (const p of points) {
    if (!Number.isFinite(p?.x) || !Number.isFinite(p?.y)) continue;
    left = Math.min(left, p.x); top = Math.min(top, p.y);
    right = Math.max(right, p.x); bottom = Math.max(bottom, p.y);
  }
  return Number.isFinite(left) ? { left, top, right, bottom } : null;
}

function recognizedBox(el: any): LassoRect | null {
  const r = el?.recognizeResult;
  const box = {
    left: Number(r?.up_left_point_x), top: Number(r?.up_left_point_y),
    right: Number(r?.down_right_point_x), bottom: Number(r?.down_right_point_y),
  };
  if (![box.left, box.top, box.right, box.bottom].every(Number.isFinite)) return null;
  if (box.right < box.left || box.bottom < box.top) return null;
  return box;
}

const RECOG_VERIFY_SAMPLES = 6;
const RECOG_INNER_TOLERANCE = 4;
const RECOG_OUTER_MARGIN = 60;

export async function inkBoxes(
  ink: any[],
  concurrency: number,
  tag: string,
): Promise<(LassoRect | null)[]> {
  const t0 = Date.now();
  const recog = ink.map(recognizedBox);
  const boxes: (LassoRect | null)[] = new Array(ink.length).fill(null);
  const exact = new Set<number>();

  const candidates: number[] = [];
  for (let i = 0; i < ink.length && candidates.length < RECOG_VERIFY_SAMPLES; i++) {
    if (recog[i] && ink[i]?.type === 0) candidates.push(i);
  }
  let trusted = candidates.length > 0;
  for (const i of candidates) {
    let real: LassoRect | null = null;
    try { real = await inkBox(ink[i]); } catch (_) {}
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
      console.log(`[PLT/${tag}] recognize box rejected sample=${JSON.stringify(r)} points=${JSON.stringify(real)}`);
      break;
    }
  }

  const pending: number[] = [];
  for (let i = 0; i < ink.length; i++) {
    if (exact.has(i)) continue;
    if (trusted && recog[i]) boxes[i] = recog[i];
    else pending.push(i);
  }
  let next = 0;
  const worker = async () => {
    while (next < pending.length) {
      const index = pending[next++];
      try { boxes[index] = await inkBox(ink[index]); } catch (_) {}
    }
  };
  await Promise.all(Array.from({ length: Math.min(concurrency, pending.length) }, () => worker()));
  console.log(`[PLT/${tag}] ink boxes n=${ink.length} recognize=${trusted} verified=${exact.size}`,
    `pointReads=${pending.length} +${Date.now() - t0}ms`);
  return boxes;
}

export function findClearPasteOffset(
  rect: LassoRect,
  obstacles: LassoRect[],
  page: { width: number; height: number },
  step = 24,
): { dx: number; dy: number } | null {
  if (obstacles.length === 0) return { dx: 0, dy: 0 };
  const w = rect.right - rect.left;
  const h = rect.bottom - rect.top;
  if (!(w > 0) || !(h > 0) || w > page.width || h > page.height) return null;
  const touches = (r: LassoRect, o: LassoRect) =>
    o.left < r.right && o.right > r.left && o.top < r.bottom && o.bottom > r.top;
  const swallows = (r: LassoRect, o: LassoRect) => {
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
    if (o.d > nearLimit) break;
    const r = at(o);
    if (!obstacles.some((b) => touches(r, b))) return { dx: o.dx, dy: o.dy };
  }
  for (const o of offsets) {
    const r = at(o);
    if (!obstacles.some((b) => swallows(r, b))) return { dx: o.dx, dy: o.dy };
  }
  return null;
}

async function targetInkRect(elements: any[], tag: string) {
  try {
    const size = await getCurrentHostPageSize(`${tag}/ink-rect-page-size`);
    if (!size) return null;
    const r = await committedInkRect(elements, size);
    if (!r) return null;
    return rectFromHost({
      left: Math.max(0, r.left - 32), top: Math.max(0, r.top - 32),
      right: Math.min(size.width, r.right + 32), bottom: Math.min(size.height, r.bottom + 32),
    });
  } catch (e) {
    console.warn(`[PLT/${tag}] ink rect failed (non-fatal):`, e);
    return null;
  }
}

async function getElementsStable(pageNum: number, filePath: string, tag: string): Promise<{ ok: boolean; els: any[] }> {
  for (let attempt = 0; attempt < 5; attempt++) {
    const res: any = await hostCall(
      PluginFileAPI.getElements(pageNum, filePath) as Promise<any>,
      `${tag}/elements-${attempt + 1}`,
    );
    if (!res?.success || !Array.isArray(res.result)) {
      console.warn(`[PLT/${tag}] getElements FAIL ok=${res?.success}`);
      return { ok: false, els: [] };
    }
    if (res.result.length > 0) return { ok: true, els: res.result };
    console.log(`[PLT/${tag}] getElements empty (attempt ${attempt + 1}) — page still reloading, retrying…`);
    for (const el of res.result) el?.recycle?.();

  }
  console.warn(`[PLT/${tag}] getElements still empty after retries`);
  return { ok: true, els: [] };
}

export function isPaletteInk(el: any): boolean {
  return el?.type === 0 || el?.type === 700;
}

export function rectFromHost(r: any): LassoRect | null {
  if (!r || ![r.left, r.top, r.right, r.bottom].every((n) => typeof n === 'number' && Number.isFinite(n))) return null;
  if ((r.right - r.left) < 2 || (r.bottom - r.top) < 2) return null;
  return { left: r.left, top: r.top, right: r.right, bottom: r.bottom };
}

function pickReplacementInk(
  pool: any[],
  wantCount: number,
  oldNums: number[],
  oldUuids: string[],
): any[] {
  if (wantCount <= 0) return [];
  const numSet = new Set(oldNums);
  const uuidSet = new Set(oldUuids);
  const ink = pool.filter(isPaletteInk);
  const newcomers = ink.filter((el: any) => {
    const u = el?.uuid;
    if (typeof u === 'string' && uuidSet.has(u)) return false;
    if (el?.numInPage != null && numSet.has(el.numInPage)) return false;
    return true;
  });
  if (newcomers.length === wantCount) return newcomers;
  const oldNumsGone = oldNums.every((n) => !ink.some((el: any) => el?.numInPage === n));
  if (oldNumsGone && ink.length === wantCount) return ink;
  return [];
}

type InkShape = { type: number; count: number; rings?: string; samples: { x: number; y: number }[] };

async function inkShape(el: any, contourScale = { x: 1, y: 1 }): Promise<InkShape | null> {
  try {
    let count = 0;
    let rings: string | undefined;
    let pick: (i: number) => Promise<{ x: number; y: number } | null>;
    if (el?.type === 0 && el.stroke?.points) {
      count = await el.stroke.points.size();
      pick = async (i) => {
        const r = await el.stroke.points.getRange(i, 1);
        return r?.[0] ?? null;
      };
      if (!(count > 0)) {
        const outlines = await contourRings(el, contourScale);
        const flat = outlines.flat();
        count = flat.length;
        rings = outlines.map(ring => ring.length).join(',');
        pick = async i => flat[i] ?? null;
      }
    } else if (el?.type === 700 && Array.isArray(el.geometry?.points)) {
      count = el.geometry.points.length;
      pick = async (i) => el.geometry.points[i] ?? null;
    } else return null;
    if (!(count > 0)) return null;
    const idx = [...new Set([0, count >> 2, count >> 1, (count * 3) >> 2, count - 1])];
    const samples: { x: number; y: number }[] = [];
    for (const i of idx) {
      const p = await pick(i);
      if (!p || !Number.isFinite(p.x) || !Number.isFinite(p.y)) return null;
      samples.push({ x: p.x, y: p.y });
    }
    return { type: el.type, count, rings, samples };
  } catch (_) {
    return null;
  }
}

async function matchMovedInk(pool: any[], wanted: InkShape[]): Promise<any[]> {
  if (wanted.length === 0) return [];
  const TOL = 4;
  const near = (a: number, b: number) => Math.abs(a - b) <= TOL;
  const sameShape = (a: InkShape, b: InkShape) =>
    a.type === b.type && a.count === b.count && a.rings === b.rings && a.samples.length === b.samples.length &&
    a.samples.every((p, i) =>
      near(p.x - a.samples[0].x, b.samples[i].x - b.samples[0].x) &&
      near(p.y - a.samples[0].y, b.samples[i].y - b.samples[0].y));
  const counts = new Set(wanted.map((w) => w.count));
  const cands: { el: any; shape: InkShape }[] = [];
  for (const el of pool.filter(isPaletteInk)) {
    const shape = await inkShape(el);
    if (shape && counts.has(shape.count)) cands.push({ el, shape });
  }

  const groups: any[][] = [];
  for (const first of cands.filter((c) => sameShape(wanted[0], c.shape))) {
    const dx = first.shape.samples[0].x - wanted[0].samples[0].x;
    const dy = first.shape.samples[0].y - wanted[0].samples[0].y;
    const used = new Set<any>([first.el]);
    const picked = [first.el];
    for (const w of wanted.slice(1)) {
      const hit = cands.find((c) => !used.has(c.el) && sameShape(w, c.shape) &&
        near(c.shape.samples[0].x - w.samples[0].x, dx) &&
        near(c.shape.samples[0].y - w.samples[0].y, dy));
      if (!hit) break;
      used.add(hit.el);
      picked.push(hit.el);
    }
    if (picked.length === wanted.length) groups.push(picked);
  }
  if (groups.length > 1) {
    console.warn(`[PLT] shape match ambiguous: ${groups.length} groups ` +
      JSON.stringify(groups.map((g) => g.map((el: any) => el?.numInPage))));
  }
  const minNum = (g: any[]) => Math.min(...g.map((el: any) => el?.numInPage ?? -1));
  return groups.reduce<any[]>((best, g) => (best.length === 0 || minNum(g) > minNum(best) ? g : best), []);
}

async function lassoNumsInRect(
  rect: LassoRect,
  tag: string,
): Promise<number[]> {
  let nums: number[] = [];
  try {
    const lr = await PluginCommAPI.lassoElements(rect);
    if (lr?.success && lr.result !== false) {
      const fresh: any = await PluginCommAPI.getLassoElements();
      if (fresh?.success && Array.isArray(fresh.result)) {
        nums = fresh.result
          .map((el: any) => el?.numInPage)
          .filter((n: any): n is number => Number.isInteger(n));
        for (const el of fresh.result) el?.recycle?.();
      }
    }
    await hostCall(PluginCommAPI.setLassoBoxState?.(2), `${tag}/re-lasso-close`);
  } catch (e) {
    console.warn(`[PLT/${tag}] re-lasso failed (non-fatal):`, e);
  }
  console.log(`[PLT/${tag}] re-lasso rect=${JSON.stringify(rect)} nums=${JSON.stringify(nums)}`);
  return nums;
}

async function waitForLassoCommit(tag: string, attempts = 40): Promise<boolean> {
  for (let i = 0; i < attempts; i++) {
    const res: any = await hostCall(
      PluginCommAPI.getLassoRect() as Promise<any>, `${tag}/commit-probe`);
    if (res?.success === false) {
      console.log(`[PLT/${tag}] lasso commit observed after ${i + 1} probe(s)`);
      return true;
    }
  }
  console.warn(`[PLT/${tag}] lasso commit not observed after ${attempts} probes; proceeding anyway`);
  return false;
}

export async function reloadForLasso(tag: string, callbackWaitMs = 2000): Promise<void> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    const reload = Promise.resolve().then(() => PluginCommAPI.reloadFile()).then(
      result => { console.log(`[PLT/${tag}] reload callback`, result); },
      error => { console.warn(`[PLT/${tag}] reload callback error`, error); },
    );
    await Promise.race([
      reload,
      new Promise<void>(resolve => {
        timer = setTimeout(() => {
          console.log(`[PLT/${tag}] reload callback wait elapsed; checking live selection`);
          resolve();
        }, callbackWaitMs);
      }),
    ]);
  } finally {
    if (timer !== undefined) clearTimeout(timer);
  }
}

export async function reselectLasso(
  rect: LassoRect,
  tag: string,
  attempts = 4,
  retryDelayMs = 180,
  expectedContext?: { filePath: string; pageNum: number },
): Promise<boolean> {
  console.log(`[PLT/${tag}] reselect start rect=${JSON.stringify(rect)}`);
  const size = await getCurrentHostPageSize(`${tag}/page-size`);
  const clamped = size ? clampHostPageRect(rect, size) : null;
  if (!clamped) {
    console.warn(`[PLT/${tag}] reselect skipped: degenerate rect=${JSON.stringify(clamped)}`);
    return false;
  }
  try {

    for (let attempt = 1; attempt <= attempts; attempt++) {
      if (attempt > 1) await new Promise(resolve => setTimeout(resolve, retryDelayMs));
      if (expectedContext) {
        const [file, page]: any[] = await Promise.all([
          PluginCommAPI.getCurrentFilePath(), PluginCommAPI.getCurrentPageNum(),
        ]);
        if (!file?.success || !page?.success || file.result !== expectedContext.filePath ||
            page.result !== expectedContext.pageNum) {
          console.log(`[PLT/${tag}] reselect context changed; target=${JSON.stringify(expectedContext)}`);
          return false;
        }
      }
      const lr = await PluginCommAPI.lassoElements(clamped);
      console.log(`[PLT/${tag}] reselect lassoElements attempt=${attempt} rect=${JSON.stringify(clamped)} ok=${lr?.success} result=${lr?.result}`);
      if (!lr?.success || lr.result === false || lr.result == null) continue;
      const shown = await PluginCommAPI.setLassoBoxState?.(0);
      console.log(`[PLT/${tag}] reselect setLassoBoxState(0) ok=${shown?.success}`);
      return true;
    }
    return false;
  } catch (e) {
    console.warn(`[PLT/${tag}] reselect failed (non-fatal):`, e);
    return false;
  }
}

type CommittedSelection = {

  lassoRectBefore: LassoRect | null;

  pageEls: any[];

  targets: any[];

  numsStale: boolean;
};

export async function commitSelectionAndRead(opts: {
  tag: string;
  pageNum: number;
  filePath: string;
  elementNums: number[];
  prefetchedEls?: any[] | null;
  fallbackRect?: LassoRect | null;
  accept?: (el: any) => boolean;
  t0?: number;
}): Promise<CommittedSelection | null> {
  const { tag, pageNum, filePath, elementNums } = opts;
  const accept = opts.accept ?? isPaletteInk;
  const prefetchedEls = opts.prefetchedEls ?? [];
  const t0 = opts.t0 ?? Date.now();

  let lassoRectBefore: LassoRect | null = null;
  try {
    const lrRes: any = await PluginCommAPI.getLassoRect();
    if (lrRes?.success) lassoRectBefore = rectFromHost(lrRes.result);
  } catch (e) {
    console.warn(`[PLT/${tag}] getLassoRect failed (non-fatal):`, e);
  }
  if (!lassoRectBefore && opts.fallbackRect) {
    lassoRectBefore = opts.fallbackRect;
    console.log(`[PLT/${tag}] lassoRect fallback to captured snapshot`);
  }
  console.log(`[PLT/${tag}] lassoRect before=${JSON.stringify(lassoRectBefore)}`);

  const wantShapes: InkShape[] = [];
  let contourScale = { x: 1, y: 1 };
  if (prefetchedEls.some(el => el?.contoursSrc)) {
    const pageSize = await getHostPageSize(filePath, pageNum, `${tag}/contour-size`);
    const display: any = await PluginCommAPI.getPageDisplaySize();
    if (pageSize && display?.success && display.result?.width > 0 && display.result?.height > 0) {
      contourScale = { x: pageSize.width / display.result.width, y: pageSize.height / display.result.height };
    }
  }
  for (const el of prefetchedEls.filter(isPaletteInk)) {
    const shape = await inkShape(el, contourScale);
    if (!shape) { wantShapes.length = 0; break; }
    wantShapes.push(shape);
  }
  console.log(`[PLT/${tag}] shapes captured=${wantShapes.length}`);

  const closed = await hostCall(
    PluginCommAPI.setLassoBoxState?.(2), `${tag}/close`);
  console.log(`[PLT/${tag}] close lasso box before modify ok=${closed?.success}`);

  await waitForLassoCommit(tag);

  const wantUuids: string[] = prefetchedEls
    .map((el: any) => el?.uuid)
    .filter((u: any): u is string => typeof u === 'string' && u.length > 0);
  for (const el of prefetchedEls) el?.recycle?.();

  const saved: any = await hostCall(PluginNoteAPI.saveCurrentNote() as Promise<any>, `${tag}/save`);
  console.log(`[PLT/${tag}] saveCurrentNote after lasso commit ok=${saved?.success} +${Date.now() - t0}ms`);

  try { PluginCommAPI.clearElementCache?.(); } catch (_) {}
  const fetched = await getElementsStable(pageNum, filePath, tag);
  if (!fetched.ok) return null;
  const pageEls = fetched.els;

  const numSet = new Set(elementNums);
  let targets = pageEls.filter(
    (el: any) => el?.numInPage != null && numSet.has(el.numInPage) && accept(el));

  let numsStale = false;
  if (targets.length !== elementNums.length) {
    numsStale = true;
    targets = [];

    const uuidSet = new Set(wantUuids);
    if (uuidSet.size > 0) {
      targets = pageEls.filter(
        (el: any) => typeof el?.uuid === 'string' && uuidSet.has(el.uuid) && accept(el));
      if (targets.length !== elementNums.length) targets = [];
      if (targets.length > 0) console.warn(`[PLT/${tag}] nums stale -> uuid match ${targets.length}/${wantUuids.length}`);
    }
    if (targets.length === 0 && wantShapes.length === elementNums.length) {
      targets = (await matchMovedInk(pageEls, wantShapes)).filter(accept);
      if (targets.length > 0) {
        console.warn(`[PLT/${tag}] nums stale -> shape match nums=${JSON.stringify(targets.map((el: any) => el?.numInPage))}`);
      }
    }
    if (targets.length === 0) {
      targets = pickReplacementInk(pageEls, elementNums.length, elementNums, wantUuids).filter(accept);
      if (targets.length > 0) {
        console.warn(`[PLT/${tag}] nums stale -> replacement nums=${JSON.stringify(targets.map((el: any) => el?.numInPage))}`);
      }
    }
    if (targets.length === 0 && lassoRectBefore) {
      const freshNums = new Set(await lassoNumsInRect(lassoRectBefore, tag));
      targets = pageEls.filter(
        (el: any) => el?.numInPage != null && freshNums.has(el.numInPage) && accept(el));
      if (targets.length !== elementNums.length) targets = [];
      if (targets.length > 0) {
        console.warn(`[PLT/${tag}] nums stale -> re-lasso nums=${JSON.stringify(targets.map((el: any) => el?.numInPage))}`);
      }
    }
  }
  if (targets.length === 0) {

    const pool = pageEls.map((el: any) => `${el?.numInPage}:${el?.type}`);
    console.warn(`[PLT/${tag}] no targets: want=${JSON.stringify(elementNums)} pool=${JSON.stringify(pool)}`);
  } else if (targets.length !== elementNums.length) {
    console.warn(`[PLT/${tag}] partial match ${targets.length}/${elementNums.length}, proceeding`);
  }
  return { lassoRectBefore, pageEls, targets, numsStale };
}

export async function pickReselectRect(sel: CommittedSelection, tag: string): Promise<LassoRect | null> {
  if (!sel.numsStale) return sel.lassoRectBefore;
  const inkRect = await targetInkRect(sel.targets, tag);
  if (!inkRect) return sel.lassoRectBefore;
  console.log(`[PLT/${tag}] reselect rect from moved ink=${JSON.stringify(inkRect)} (lasso said ${JSON.stringify(sel.lassoRectBefore)})`);
  return inkRect;
}
