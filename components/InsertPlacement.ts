import { NativeModules } from 'react-native';
import { PluginCommAPI, PluginFileAPI, PluginNoteAPI, type Rect } from 'sn-plugin-lib';
import { findClearPasteOffset, inkBoxes, isPaletteInk, rectFromHost } from './LassoTxn';
import { getHostPageSize, type HostPageSize } from './HostPageCoordinates';
import FloatingToolbarBridge from './FloatingToolbarBridge';

const TAG = '[InsertPlacement]';
const INK_BOX_CONCURRENCY = 8;

const LEAST_OVERLAP_STEP = 48;

const { FloatingToolbar } = NativeModules;

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

function validRect(rect: any): rect is Rect {
  return !!rect && [rect.left, rect.top, rect.right, rect.bottom].every(Number.isFinite)
    && rect.right > rect.left && rect.bottom > rect.top;
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

export function startOccupiedCapture(tag: string): OccupiedCapture {
  if (FloatingToolbarBridge.isDocumentMode()) {
    return { ready: Promise.resolve(), snapshot: Promise.resolve(null) };
  }
  let release: () => void = () => {};
  const ready = new Promise<void>(resolve => { release = resolve; });
  const snapshot = (async () => {
    const t0 = Date.now();
    try {
      const [fp, pg]: any[] = await Promise.all([
        PluginCommAPI.getCurrentFilePath(), PluginCommAPI.getCurrentPageNum(),
      ]);
      if (!fp?.success || typeof fp.result !== 'string' || !fp.result
        || !pg?.success || !Number.isInteger(pg.result)) return null;
      const filePath: string = fp.result;
      const pageNum: number = pg.result;
      try {
        const saved: any = await PluginNoteAPI.saveCurrentNote();
        if (saved?.success === false) console.warn(TAG, tag, 'saveCurrentNote failed', saved.error);
      } catch (e) { console.warn(TAG, tag, 'saveCurrentNote threw', e); }
      release();
      const pageSize = await getHostPageSize(filePath, pageNum, tag);
      if (!pageSize) return null;
      try { PluginCommAPI.clearElementCache?.(); } catch (_) {}
      const res: any = await PluginFileAPI.getElements(pageNum, filePath);
      const els: any[] = res?.success && Array.isArray(res.result) ? res.result : [];
      try {
        const boxes: Rect[] = (await inkBoxes(els.filter(isPaletteInk), INK_BOX_CONCURRENCY, tag))
          .filter((b): b is Rect => b !== null);
        for (const el of els) {
          const type = Number(el?.type);
          const r = type === 200 ? el.rect
            : type === 500 || type === 501 || type === 502 ? el.textRect
            : null;
          if (validRect(r)) boxes.push({ left: r.left, top: r.top, right: r.right, bottom: r.bottom });
        }
        console.log(TAG, tag, `occupied boxes=${boxes.length} elements=${els.length} +${Date.now() - t0}ms`);
        return { filePath, pageNum, pageSize, boxes };
      } finally {
        for (const el of els) el?.recycle?.();
      }
    } catch (e) {
      console.warn(TAG, tag, 'occupied capture failed', e);
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
  if (w > page.width || h > page.height) return null;
  const areaAt = (dx: number, dy: number) => {
    const r = shifted(rect, dx, dy);
    let sum = 0;
    for (const b of boxes) sum += overlapArea(r, b);
    return sum;
  };
  const current = areaAt(0, 0);
  let best: { dx: number; dy: number; area: number; d: number } | null = null;
  for (let dx = -rect.left; rect.right + dx <= page.width; dx += LEAST_OVERLAP_STEP) {
    for (let dy = -rect.top; rect.bottom + dy <= page.height; dy += LEAST_OVERLAP_STEP) {
      const area = areaAt(dx, dy);
      const d = dx * dx + dy * dy;
      if (!best || area < best.area || (area === best.area && d < best.d)) best = { dx, dy, area, d };
    }
  }
  return best && best.area < current ? { dx: best.dx, dy: best.dy } : null;
}

export function relocatedRect(rect: Rect, snapshot: OccupiedSnapshot | null): Rect | null {
  if (!snapshot || !validRect(rect)) return null;
  const { boxes, pageSize } = snapshot;
  if (!boxes.some(b => touches(rect, b))) return null;
  const clear = findClearPasteOffset(rect, boxes, pageSize);
  const offset = clear && (clear.dx !== 0 || clear.dy !== 0) ? clear : leastOverlapOffset(rect, boxes, pageSize);
  if (!offset) {
    console.log(TAG, 'occupied but no better place', JSON.stringify(rect));
    return null;
  }
  return shifted(rect, offset.dx, offset.dy);
}

export async function moveFloatingSelection(target: Rect, tag: string): Promise<boolean> {
  try {
    const ok = await FloatingToolbar.resizeInsertedSticker(target);
    console.log(TAG, tag, `moved floating selection ok=${ok} target=${JSON.stringify(target)}`);
    return ok === true;
  } catch (e) {
    console.warn(TAG, tag, 'resizeInsertedSticker failed', e);
    return false;
  }
}

export async function relocateFloatingStickerIfOccupied(capture: OccupiedCapture | null, tag: string): Promise<boolean> {
  if (!capture) return false;
  const snapshot = await capture.snapshot;
  if (!snapshot || snapshot.boxes.length === 0) return false;
  let rect: Rect | null = null;
  try {
    const res: any = await PluginCommAPI.getLassoRect();
    const candidate = res?.success ? rectFromHost(res.result) : null;
    if (candidate && validRect(candidate) && (candidate.left > 1 || candidate.top > 1)) rect = candidate;
  } catch (e) {
    console.warn(TAG, tag, 'getLassoRect failed', e);
  }
  if (!rect) return false;
  const target = relocatedRect(rect, snapshot);
  if (!target) return false;
  console.log(TAG, tag, `sticker frame ${JSON.stringify(rect)} occupied → ${JSON.stringify(target)}`);
  return moveFloatingSelection(target, tag);
}
