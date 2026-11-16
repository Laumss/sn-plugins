import { PluginCommAPI, PluginFileAPI, PluginNoteAPI } from 'sn-plugin-lib';
import type { Element } from 'sn-plugin-lib';
import { registerAction } from './actions';
import { TOOL, hostPath, log } from './common';
import { asResponse, dialog, elements, insertTxn, readHostCtx } from './host';
import type { HostCtx } from './host';
import { t } from './i18n';
import { hasNativeModule, notipalLinks } from './native';
import type { NativeEvents } from './native';

const TAG = 'links';

const LINKS_AVAILABLE = hasNativeModule('NotipalLinks');
if (!LINKS_AVAILABLE) { log.e(TAG, 'NotipalLinks missing, document links are unavailable'); }

const LINK_TXN = 'doc_link';

const LINK_TXN_TIMEOUT_MS = 15_000;

const HOST_NOT_MAIN_LAYER = 810;

const FONT_SIZE = 49;
const LINE_GAP = 10;
const TOP_RATIO = 0.15;
const COLLISION_GAP = 10;
const COLLISION_ITERATIONS = 50;
const BOTTOM_MARGIN = 50;
const LASSO_PAD = 5;

const NOTE_EXTS = ['note'];
const DOC_EXTS = ['epub', 'pdf', 'cbz', 'doc', 'docx', 'djvu', 'mobi', 'fb2'];

type LayerInfo = { isCurrentLayer?: boolean; layerId?: number; layerNum?: number };

async function currentLayerId(ctx: HostCtx): Promise<number | undefined> {
  const res = asResponse(await PluginFileAPI.getLayers(ctx.hostFilePath, ctx.page));
  const list = res?.result;
  const layers = Array.isArray(list) ? (list as LayerInfo[]) : [];
  const current = layers.find(layer => layer.isCurrentLayer);
  return current ? elements.layerIdOf(current) : undefined;
}

async function onToolTap(ctx: HostCtx): Promise<void> {
  if (ctx.hostFilePath && ctx.page >= 0) {
    try {
      const layer = await currentLayerId(ctx);
      if (layer !== undefined && layer !== 0) {
        log.w(TAG, `insert_link blocked on non-main layer: ${layer}`);
        await dialog.tip(t('doc_link_main_only'));
        return;
      }
    } catch (e) {
      log.w(TAG, 'insert_link layer check failed', e);
    }
  }
  notipalLinks.openPicker(ctx.hostFilePath);
}

function occupiedRows(list: Element[]): { top: number; bottom: number }[] {
  const occupied: { top: number; bottom: number }[] = [];
  for (const el of list) {
    try {
      const type = el.type;
      if (typeof type === 'number' && type >= 500 && type <= 502) {
        const rect = el.textBox?.textRect;
        if (rect && typeof rect.top === 'number' && typeof rect.bottom === 'number') {
          occupied.push({ top: rect.top, bottom: rect.bottom });
        }
      }
      if (typeof type === 'number' && type === 600) {
        const link = el.link;
        if (link && typeof link.Y === 'number' && typeof link.height === 'number') {
          occupied.push({ top: link.Y, bottom: link.Y + link.height });
        }
      }
    } finally {
      elements.releaseHostElements([el]);
    }
  }
  return occupied.sort((a, b) => a.top - b.top);
}

async function findTop(ctx: HostCtx, pageH: number, lineH: number): Promise<number> {
  const initial = Math.round(pageH * TOP_RATIO);
  let top = initial;
  try {
    const res = asResponse(await PluginFileAPI.getElements(ctx.page, ctx.hostFilePath));
    if (!res?.success || !Array.isArray(res.result)) { return top; }
    const occupied = occupiedRows(res.result as Element[]);
    for (let iter = 0; iter < COLLISION_ITERATIONS; iter++) {
      let collision = false;
      for (const range of occupied) {
        if (top < range.bottom && top + lineH > range.top) {
          log.i(TAG, `docLink collision at top=${top} with [${range.top}, ${range.bottom}] → ${range.bottom + COLLISION_GAP}`);
          top = range.bottom + COLLISION_GAP;
          collision = true;
          break;
        }
      }
      if (!collision) { break; }
    }
    if (top + lineH > pageH - BOTTOM_MARGIN) {
      log.w(TAG, 'docLink: no room on page, using original position');
      top = initial;
    }
  } catch (e) {
    log.w(TAG, 'docLink collision scan failed (non-fatal)', e);
  }
  return top;
}

type LinkOutcome =
  | { status: 'selected'; filePath: string; page: number }
  | { status: 'inserted' | 'skipped' | 'not_main_layer' | 'failed' };

async function insertLink(
  ev: NativeEvents['links:insert'],
  alive: () => boolean,
): Promise<LinkOutcome> {
  const rawName = ev.linkName;
  const dot = rawName.lastIndexOf('.');
  const linkName = dot > 0 ? rawName.substring(0, dot) : rawName;
  const ctx = await readHostCtx();

  if (ev.targetFile && (ctx.filePath !== ev.targetFile || ctx.page !== ev.targetPage)) {
    log.i(TAG, 'document link queue ended: target page changed');
    return { status: 'skipped' };
  }
  if (!ctx.hostFilePath || ctx.page < 0) {
    log.w(TAG, 'insert link skipped: current page unavailable');
    return { status: 'skipped' };
  }
  if (!alive()) { return { status: 'failed' }; }
  const layer = await currentLayerId(ctx);
  if (layer !== undefined && layer !== 0) {
    log.w(TAG, `insert link blocked on non-main layer: ${layer}`);
    return { status: 'not_main_layer' };
  }
  const pageW = ctx.pageSize.width;
  const pageH = ctx.pageSize.height;
  if (!(pageW > 0 && pageH > 0)) {
    log.w(TAG, 'insert link skipped: actual page size unavailable');
    return { status: 'skipped' };
  }
  if (!alive()) { return { status: 'failed' }; }

  try {
    await PluginCommAPI.setLassoBoxState(2);
  } catch (e) {
    log.w(TAG, 'setLassoBoxState(2) before link failed', e);
  }

  const linkW = Math.min(linkName.length * 30 + 40, pageW * 0.6);
  const left = Math.round((pageW - linkW) / 2);
  const lineH = FONT_SIZE + LINE_GAP;
  if (!alive()) { return { status: 'failed' }; }
  const top = await findTop(ctx, pageH, lineH);

  const ext = ev.path.split('.').pop()?.toLowerCase() || '';
  const linkType = NOTE_EXTS.includes(ext) ? 1 : DOC_EXTS.includes(ext) ? 2 : 0;

  const textLink = {
    destPath: hostPath(ev.path),
    destPage: -1,
    style: 0,
    linkType,
    rect: { left, top, right: left + Math.round(linkW), bottom: top + lineH },
    fontSize: FONT_SIZE,
    fullText: linkName,
    showText: linkName,
    isItalic: 0,
  };
  if (!alive()) { return { status: 'failed' }; }
  const r = asResponse(await PluginNoteAPI.insertTextLink(textLink));
  log.i(TAG, `insertTextLink result: ${JSON.stringify(r)}`);
  if (!r?.success || r.result !== 0) {
    log.w(TAG, `insertTextLink failed: ${JSON.stringify(r)}`);
    return { status: r?.error?.code === HOST_NOT_MAIN_LAYER ? 'not_main_layer' : 'failed' };
  }

  try {
    if (!alive()) { return { status: 'inserted' }; }
    await PluginCommAPI.reloadFile();
    const lassoRect = {
      left: textLink.rect.left - LASSO_PAD,
      top: textLink.rect.top - LASSO_PAD,
      right: textLink.rect.right + LASSO_PAD,
      bottom: textLink.rect.bottom + LASSO_PAD,
    };
    if (!alive()) { return { status: 'inserted' }; }
    const lr = asResponse(await PluginCommAPI.lassoElements(lassoRect));
    log.i(TAG, `lassoElements rect=${JSON.stringify(lassoRect)} page=${pageW}x${pageH} result=${JSON.stringify(lr)}`);
    if (lr?.success && lr.result !== false) {
      if (!alive()) { return { status: 'inserted' }; }
      const shown = asResponse(await PluginCommAPI.setLassoBoxState(0));
      log.i(TAG, `setLassoBoxState(0) result: ${JSON.stringify(shown)}`);
      if (shown?.success === true && shown.result !== false) {
        return { status: 'selected', filePath: ctx.filePath, page: ctx.page };
      }
    } else {
      log.w(TAG, `lassoElements did not select: success=${lr?.success} result=${String(lr?.result)}`);
    }
  } catch (e) {
    log.w(TAG, 'lassoElements after insertTextLink failed', e);
  }
  return { status: 'inserted' };
}

export async function onInsertLink(ev: NativeEvents['links:insert']): Promise<void> {
  if (!LINKS_AVAILABLE) { return; }
  log.i(TAG, `insert link request=${ev.requestId} path=${ev.path}`);
  let outcome: LinkOutcome = { status: 'failed' };
  try {
    outcome = await insertTxn(LINK_TXN, LINK_TXN_TIMEOUT_MS, alive => insertLink(ev, alive));
  } catch (e) {

    log.w(TAG, `insert link failed request=${ev.requestId}`, e);
  } finally {
    const selected = outcome.status === 'selected' ? outcome : null;
    notipalLinks.insertFinished(ev.requestId, selected !== null, selected?.filePath ?? '', selected?.page ?? -1);
  }

  if (outcome.status === 'not_main_layer') { await dialog.tip(t('doc_link_main_only')); }
}

export function register(): void {
  if (!LINKS_AVAILABLE) {
    registerAction(TOOL.INSERT_LINK, async () => {
      await dialog.tip(t('feature_not_ready'));
    });
    return;
  }
  registerAction(TOOL.INSERT_LINK, ctx => onToolTap(ctx));
}
