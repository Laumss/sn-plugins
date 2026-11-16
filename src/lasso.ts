import { PluginCommAPI, PluginDocAPI, PluginNoteAPI } from 'sn-plugin-lib';
import type { Element, Rect } from 'sn-plugin-lib';
import { registerAction } from './actions';
import type { ToolbarAction } from './actions';
import { MOSAIC_LASSO_FILE, mosaicStrokesToElements, readMosaicSidecar } from './clipboard';
import { TOOL, after, canonicalPath, fs, log, sleep } from './common';
import { asResponse, coords, dialog, elements, hostState, isHostNoPageError, readHostCtx, selection } from './host';
import type { HostCtx } from './host';
import { t } from './i18n';
import { notipalCommon, notipalHost, notipalLasso, notipalToolbar } from './native';
import type { NativeEvents } from './native';

const TAG = 'lasso';

export async function hasLassoSelection(): Promise<boolean> {
  try {
    const r = asResponse(await PluginCommAPI.getLassoRect());
    log.i(TAG, `hasLassoSelection: ${r?.success} ${JSON.stringify(r?.result ?? null)}`);
    if (r?.success && r.result != null) { return true; }
  } catch (e) {

    log.w(TAG, 'hasLassoSelection getLassoRect failed', e);
  }
  try {
    const els = asResponse(await PluginCommAPI.getLassoElements());
    const selected = els?.success === true && Array.isArray(els.result) && els.result.length > 0;
    log.i(TAG, `hasLassoSelection fallback elements= ${selected && Array.isArray(els?.result) ? els.result.length : 0}`);
    return selected;
  } catch (e) {
    log.w(TAG, 'hasLassoSelection getLassoElements failed', e);
    return false;
  }
}

export async function reloadForLasso(tag: string, callbackWaitMs = 2000): Promise<void> {
  let cancelWait: (() => void) | undefined;
  try {
    const reload = Promise.resolve().then(() => PluginCommAPI.reloadFile()).then(
      result => { log.i(`PLT/${tag}`, `reload callback ${JSON.stringify(result ?? null)}`); },
      error => { log.w(`PLT/${tag}`, 'reload callback error', error); },
    );
    await Promise.race([
      reload,
      new Promise<void>(resolve => {
        cancelWait = after(callbackWaitMs, () => {
          log.i(`PLT/${tag}`, 'reload callback wait elapsed; checking live selection');
          resolve();
        });
      }),
    ]);
  } finally {
    cancelWait?.();
  }
}

export async function reselectLasso(
  ctx: HostCtx,
  rect: Rect,
  tag: string,
  attempts = 4,
  retryDelayMs = 180,
  checkPage = false,
): Promise<boolean> {
  log.i(`PLT/${tag}`, `reselect start rect=${JSON.stringify(rect)}`);
  const size = coords.getCurrentHostPageSize(ctx, `${tag}/page-size`);
  const clamped = size ? coords.clampHostPageRect(rect, size) : null;
  if (!clamped) {
    log.w(`PLT/${tag}`, `reselect skipped: degenerate rect=${JSON.stringify(clamped)}`);
    return false;
  }
  try {

    for (let attempt = 1; attempt <= attempts; attempt++) {
      if (attempt > 1) { await sleep(retryDelayMs); }
      if (checkPage) {
        const [file, page] = await Promise.all([
          PluginCommAPI.getCurrentFilePath(), PluginCommAPI.getCurrentPageNum(),
        ]);
        const fileRes = asResponse(file);
        const pageRes = asResponse(page);
        if (!fileRes?.success || !pageRes?.success ||
            canonicalPath(String(fileRes.result)) !== ctx.filePath || pageRes.result !== ctx.page) {
          log.i(`PLT/${tag}`, `reselect context changed; target=${JSON.stringify({ filePath: ctx.filePath, pageNum: ctx.page })}`);
          return false;
        }
      }
      const lr = await PluginCommAPI.lassoElements(clamped);
      log.i(`PLT/${tag}`, `reselect lassoElements attempt=${attempt} rect=${JSON.stringify(clamped)} ok=${lr?.success} result=${lr?.result}`);
      if (!lr?.success || lr.result === false || lr.result == null) { continue; }
      const shown = await PluginCommAPI.setLassoBoxState(0);
      log.i(`PLT/${tag}`, `reselect setLassoBoxState(0) ok=${shown?.success}`);
      return true;
    }
    return false;
  } catch (e) {
    log.w(`PLT/${tag}`, 'reselect failed (non-fatal)', e);
    return false;
  }
}

export interface LinkedFile {
  path: string;
  linkType: number;
  label: string;
}

export interface LassoSendData {
  text: string;
  imagePaths: string[];
  linkedFiles: LinkedFile[];
}

function notAvailable(what: string): void {
  log.w(TAG, `${what}: not available in this build`);
  dialog.tip(t('feature_not_ready')).catch(e => log.w(TAG, 'not-ready tip failed', e));
}

export const lassoHooks: {
  setSendData: (data: LassoSendData) => void;
  openSendPanel: () => void;
  sendDocText: (text: string) => Promise<'opened' | 'localsend_off' | 'failed'>;
} = {
  setSendData: data => {
    log.i(TAG, `send data not staged (no send panel): text=${data.text.length}chars ` +
      `images=${data.imagePaths.length} links=${data.linkedFiles.length}`);
  },
  openSendPanel: () => notAvailable('send panel'),
  sendDocText: async () => {
    notAvailable('document text send');
    return 'failed';
  },
};

export type ExtractStage = 'recognizing' | 'done';
export type ExtractProgress = (stage: ExtractStage) => void;

export interface ExtractedContent {
  text: string;
  imagePaths: string[];
  linkedFiles: LinkedFile[];

  noPage?: boolean;

  recognizedNothing?: boolean;
  lastTextBoxRect?: Rect;
  lassoRect?: Rect;
  stats: { strokes: number; textBoxes: number; pictures: number; others: number; recognizedStrokes: number };
}

const TEXT_BOX_TYPES = new Set([500, 501, 502]);
const STROKE_TYPE = 0;
const PICTURE_TYPE = 200;

const RECOGNIZE_PEN_TYPE = 10;
const RECOGNIZABLE_PEN_TYPES = new Set([10, 1]);

export async function extractLasso(ctx: HostCtx, onProgress?: ExtractProgress): Promise<ExtractedContent> {
  const result: ExtractedContent = {
    text: '',
    imagePaths: [],
    linkedFiles: [],
    stats: { strokes: 0, textBoxes: 0, pictures: 0, others: 0, recognizedStrokes: 0 },
  };

  const res = asResponse(await PluginCommAPI.getLassoElements());
  if (!res?.success || !Array.isArray(res.result)) {
    log.i('LassoExtract', `getLassoElements failed: ${res?.error?.message ?? 'unknown'}`);
    if (isHostNoPageError(res)) {
      selection.showHostNoPageHint();
      result.noPage = true;
    }
    return result;
  }
  const els = res.result as Element[];
  log.i('LassoExtract', `found ${els.length} elements`);

  try {
    const lr = asResponse(await PluginCommAPI.getLassoRect());
    const r = lr?.success ? coords.rectFromHost(lr.result) : null;
    if (r) { result.lassoRect = r; }
  } catch (e) {
    log.i('LassoExtract', `getLassoRect failed (non-fatal): ${String(e)}`);
  }

  type SortableItem = { sortY: number; text?: string; imagePath?: string; rect?: Rect };
  const sortable: SortableItem[] = [];
  const unrecognized: { el: Element; sortY: number }[] = [];

  for (const el of els) {
    try {
      if (TEXT_BOX_TYPES.has(el.type)) {
        result.stats.textBoxes++;
        const content = el.textBox?.textContentFull ?? '';
        const rect = el.textBox?.textRect;
        if (content.trim()) { sortable.push({ sortY: rect?.top ?? 0, text: content.trim(), rect }); }
      } else if (el.type === STROKE_TYPE) {
        result.stats.strokes++;
        let sortY = 0;
        try {
          const size = await el.contoursSrc?.size?.();
          if (size && size > 0) {
            const first = await el.contoursSrc.get(0);
            if (Array.isArray(first) && first.length > 0) { sortY = first[0].y ?? 0; }
          }
        } catch (e) {

          log.w('LassoExtract', 'stroke contour read failed', e);
        }
        unrecognized.push({ el, sortY });
      } else if (el.type === PICTURE_TYPE) {
        result.stats.pictures++;
        const picture = el.picture as (Element['picture'] & { path?: string }) | null;
        const imgPath = picture?.picturePath ?? picture?.path;
        if (imgPath) {
          sortable.push({ sortY: 0, imagePath: imgPath });
          result.imagePaths.push(imgPath);
        }
      } else {
        result.stats.others++;
      }
    } catch (e) {
      log.w('LassoExtractor', `error processing element ${el?.type}`, e);
    }
  }

  if (unrecognized.length > 0) {
    onProgress?.('recognizing');
    try {
      const canWrite = await notipalHost.requestPermission('file_write');
      if (!canWrite) {
        log.i('LassoExtract', 'recognizeElements skipped: no FILE:WRITE permission');
        dialog.tip(t('perm_required')).catch(e => log.w(TAG, 'permission tip failed', e));
        throw new Error('no FILE:WRITE permission');
      }
      if (ctx.hostFilePath && ctx.page >= 0) {
        if (ctx.pageSize.width > 0 && ctx.pageSize.height > 0) {

          const forOcr = unrecognized.map(u => {
            const penType = u.el.stroke?.penType;
            if (!u.el.stroke || (penType != null && RECOGNIZABLE_PEN_TYPES.has(penType))) { return u.el; }
            return { ...u.el, stroke: { ...u.el.stroke, penType: RECOGNIZE_PEN_TYPE } };
          });
          const ocr = asResponse(await PluginCommAPI.recognizeElements(forOcr, ctx.pageSize));
          if (ocr?.success && typeof ocr.result === 'string' && ocr.result.trim()) {
            const minSortY = Math.min(...unrecognized.map(u => u.sortY));
            sortable.push({ sortY: minSortY, text: ocr.result.trim() });
            result.stats.recognizedStrokes += unrecognized.length;
            log.i('LassoExtract', `recognizeElements ok: ${unrecognized.length} strokes → ${ocr.result.length}chars`);
          } else if (ocr?.success) {
            result.recognizedNothing = true;
            log.i('LassoExtract', `recognizeElements no text: ${unrecognized.length} strokes, ` +
              `result length= ${typeof ocr.result === 'string' ? ocr.result.length : typeof ocr.result}`);
          } else {
            log.i('LassoExtract', `recognizeElements failed: code= ${ocr?.error?.code} ${ocr?.error?.message ?? 'unknown'}`);
          }
        } else {
          log.i('LassoExtract', 'getPageSize failed for OCR: unknown');
        }
      }
    } catch (e) {
      log.w('LassoExtractor', 'recognizeElements error', e);
      log.i('LassoExtract', `recognizeElements threw: ${String(e)}`);
    }
  }
  onProgress?.('done');

  sortable.sort((a, b) => a.sortY - b.sortY);
  result.text = sortable.filter(item => item.text).map(item => item.text).join('\n');
  for (const item of sortable) {
    if (item.rect && typeof item.rect.bottom === 'number') {
      if (!result.lastTextBoxRect || item.rect.bottom > result.lastTextBoxRect.bottom) {
        result.lastTextBoxRect = item.rect;
      }
    }
  }

  try {
    const links = await PluginNoteAPI.getLassoLinks();
    if (links?.success && Array.isArray(links.result)) {
      const currentFilePath = ctx.hostFilePath;
      const rnfs = fs.get();
      for (const link of links.result) {
        const lt = link.linkType;
        const dest = link.destPath ?? '';
        if (lt === 4 && dest) {
          result.linkedFiles.push({ path: dest, linkType: lt, label: dest });
        } else if (lt === 0 && currentFilePath) {
          if (!result.linkedFiles.some(f => f.path === currentFilePath)) {
            const name = currentFilePath.split('/').pop()?.replace(/\.[^.]+$/, '') ?? 'note';
            result.linkedFiles.push({ path: currentFilePath, linkType: lt, label: name });
          }
        } else if ([1, 2, 3, 6].includes(lt) && dest) {
          if (await rnfs?.exists(dest)) {
            const name = dest.split('/').pop()?.replace(/\.[^.]+$/, '') ?? 'file';
            result.linkedFiles.push({ path: dest, linkType: lt, label: name });
          } else {
            log.i('LassoExtract', `linked file not found: ${dest}`);
          }
        }
      }
      if (result.linkedFiles.length > 0) {
        log.i('LassoExtract',
          `found ${result.linkedFiles.length} linked file(s): ${result.linkedFiles.map(f => f.label).join(', ')}`);
      }
    }
  } catch (e) {
    log.i('LassoExtract', `getLassoLinks failed (non-fatal): ${String(e)}`);
  }

  elements.releaseHostElements(els);
  log.i('LassoExtract',
    `extracted: text=${result.text.length}chars images=${result.imagePaths.length} stats=${JSON.stringify(result.stats)}`);
  return result;
}

async function lassoSelectionSendsText(): Promise<boolean> {
  try {
    const r = await PluginCommAPI.getLassoElementTypeCounts();
    if (r?.success !== true || !r.result) { return false; }
    const counts = r.result as unknown as Record<string, unknown>;
    const count = (key: string) => Number(counts[key]) || 0;
    const textOrLinks = count('normalTextBoxNum') + count('digestTextBoxNum') +
      count('digestTextBoxEditableNum') + count('textLinkNum');
    return textOrLinks > 0 && count('trailNum') === 0 && count('trailLinkNum') === 0;
  } catch (e) {
    log.w(TAG, 'getLassoElementTypeCounts failed', e);
    return false;
  }
}

export function onSelectionShown({ request }: NativeEvents['lasso:selectionShown']): void {
  lassoSelectionSendsText()
    .then(sendsText => {
      log.i(TAG, `smart lasso selection request=${request} sendsText=${sendsText}`);
      notipalLasso.setSelectionKind(request, sendsText);
    })
    .catch(e => log.w(TAG, 'smart lasso selection kind failed', e));
}

async function lassoHasPicture(): Promise<boolean> {
  let els: Element[] = [];
  try {
    const res = asResponse(await PluginCommAPI.getLassoElements());
    if (!res?.success || !Array.isArray(res.result)) { return false; }
    els = res.result as Element[];
    return els.some(el => el?.type === PICTURE_TYPE);
  } catch (e) {
    log.w(TAG, 'lassoHasPicture failed', e);
    return false;
  } finally {
    elements.releaseHostElements(els);
  }
}

let docTextSendBusy = false;

async function sendDocSelectedText(): Promise<'opened' | 'busy' | 'no_text' | 'localsend_off' | 'failed'> {
  if (docTextSendBusy) { return 'busy'; }
  docTextSendBusy = true;
  try {
    const sel = asResponse(await PluginDocAPI.getLastSelectedText());
    const text = sel?.success && typeof sel.result === 'string' ? sel.result : '';
    log.i(TAG, `doc selected text length= ${text.length}`);
    if (!text.trim()) { return 'no_text'; }
    return await lassoHooks.sendDocText(text);
  } catch (e) {
    log.w(TAG, 'document text send failed', e);
    return 'failed';
  } finally {
    docTextSendBusy = false;
  }
}

export interface MosaicLassoText {
  text: string | null;
  recognizedNothing: boolean;
}

const MOSAIC_NO_TEXT: MosaicLassoText = { text: null, recognizedNothing: false };

export async function extractMosaicLassoText(ctx: HostCtx, onProgress?: ExtractProgress): Promise<MosaicLassoText> {
  if (ctx.surface !== 'mosaic') { return MOSAIC_NO_TEXT; }
  const sel = await readMosaicSidecar(MOSAIC_LASSO_FILE);
  const strokes = sel?.strokes;
  if (!strokes || strokes.length === 0) { return MOSAIC_NO_TEXT; }
  const canWrite = await notipalHost.requestPermission('file_write');
  if (!canWrite) {
    dialog.tip(t('perm_required')).catch(e => log.w(TAG, 'permission tip failed', e));
    return MOSAIC_NO_TEXT;
  }
  const pageSize = coords.currentNotePageSize(ctx);
  if (!pageSize) {
    log.w('MOSAIC-OCR', 'actual page size unavailable');
    return MOSAIC_NO_TEXT;
  }
  const els = await mosaicStrokesToElements(strokes, pageSize, sel?.screenShortPx);
  if (!els || els.length === 0) { return MOSAIC_NO_TEXT; }
  onProgress?.('recognizing');
  try {
    const res = asResponse(await PluginCommAPI.recognizeElements(els, pageSize));
    if (res?.success && typeof res.result === 'string' && res.result.trim()) {
      return { text: res.result.trim(), recognizedNothing: false };
    }
    if (res?.success) {
      log.i('MOSAIC-OCR', `recognizeElements no text: ${els.length} strokes, ` +
        `result length= ${typeof res.result === 'string' ? res.result.length : typeof res.result}`);
      return { text: null, recognizedNothing: true };
    }
    log.w('MOSAIC-OCR', `recognizeElements failed: code= ${res?.error?.code} ${res?.error?.message ?? 'unknown'}`);
    return MOSAIC_NO_TEXT;
  } catch (e) {
    log.w('MOSAIC-OCR', 'recognizeElements threw', e);
    return MOSAIC_NO_TEXT;
  } finally {
    onProgress?.('done');
    elements.releaseHostElements(els);
  }
}

type MosaicSelectionFile = {
  strokes?: unknown;
  textCards?: unknown;
};

function tipNothingToSend(recognizedNothing: boolean): void {
  const key = recognizedNothing ? 'lasso_send_no_text' : 'lasso_send_ocr_failed';
  dialog.tip(t(key)).catch(e => log.w(TAG, 'ocr tip failed', e));
}

async function sendMosaicSelection(ctx: HostCtx): Promise<string | null> {
  let sel: MosaicSelectionFile | null = null;
  try {
    const text = await notipalCommon.readTextFile(MOSAIC_LASSO_FILE);
    sel = text ? JSON.parse(text) as MosaicSelectionFile : null;
  } catch (e) {
    log.w('MOSAIC-SEND', 'read selection failed', e);
  }
  const rawStrokes = sel?.strokes;
  const strokes: unknown[] = Array.isArray(rawStrokes) ? rawStrokes : [];
  const rawCardList = sel?.textCards;
  const rawCards: unknown[] = Array.isArray(rawCardList) ? rawCardList : [];
  const cards = rawCards
    .map(card => card as { text?: unknown; top?: unknown } | null)
    .filter((card): card is { text: string; top?: unknown } => typeof card?.text === 'string' && !!card.text.trim())
    .map(card => ({ text: card.text.trim(), top: Number(card.top) || 0 }));
  log.i('MOSAIC-SEND', `selection strokes= ${strokes.length} textCards= ${cards.length}`);
  if (strokes.length === 0 && cards.length === 0) { return null; }

  const parts = [...cards];
  let recognizedNothing = false;
  if (strokes.length > 0) {
    const ocr = await extractMosaicLassoText(ctx);
    recognizedNothing = ocr.recognizedNothing;
    const recognized = ocr.text;
    if (recognized) {
      let top = Infinity;
      for (const stroke of strokes) {
        const raw = (stroke as { pts?: unknown } | null)?.pts;
        const pts: unknown[] = Array.isArray(raw) ? raw : [];
        for (let i = 1; i < pts.length; i += 3) {
          const y = pts[i];
          if (typeof y === 'number' && Number.isFinite(y)) { top = Math.min(top, y); }
        }
      }
      parts.push({ text: recognized, top: Number.isFinite(top) ? top : 0 });
    }
  }
  if (parts.length === 0) {
    tipNothingToSend(recognizedNothing);
    return 'Send aborted: nothing to send';
  }
  parts.sort((a, b) => a.top - b.top);
  lassoHooks.setSendData({ text: parts.map(part => part.text).join('\n'), imagePaths: [], linkedFiles: [] });
  lassoHooks.openSendPanel();
  return 'Send panel opened (Mosaic selection)';
}

async function runSmartLasso(ctx: HostCtx): Promise<string> {
  if (ctx.surface === 'mosaic') {
    const sent = await sendMosaicSelection(ctx);
    if (sent) { return sent; }
    notipalLasso.startCapture('smart', false);
    return 'Smart lasso capture opened';
  }
  if (ctx.docTextSelected) {
    const sent = await sendDocSelectedText();
    if (sent !== 'no_text') { return `Doc text send: ${sent}`; }
  }
  if (!ctx.filePath || ctx.page < 0) { return 'Smart lasso: no current page'; }

  if (await hasLassoSelection()) {

    if (!ctx.document && await lassoHasPicture()) {
      dialog.tip(t('smart_lasso_picture_blocked')).catch(e => log.w(TAG, 'picture tip failed', e));
      return 'Send aborted: lasso contains picture';
    }
    const extracted = await extractLasso(ctx);
    if (extracted.noPage) { return 'Send aborted: host last-page limit'; }
    if (!extracted.text && extracted.imagePaths.length === 0 && extracted.linkedFiles.length === 0) {
      tipNothingToSend(!!extracted.recognizedNothing);
      return 'Send aborted: nothing to send';
    }
    lassoHooks.setSendData({
      text: extracted.text, imagePaths: extracted.imagePaths, linkedFiles: extracted.linkedFiles,
    });
    lassoHooks.openSendPanel();
    return 'Send panel opened';
  }
  notipalLasso.startCapture('smart', false);
  return 'Smart lasso capture opened';
}

async function smartLassoAction(ctx: HostCtx, ev: ToolbarAction): Promise<void> {

  if (ev.gesture !== 'tap') {
    log.w(TAG, `smart lasso: unexpected gesture ${ev.gesture}`);
    return;
  }
  const result = await runSmartLasso(ctx);
  log.i(TAG, `smart lasso result= ${result}`);
}

const LASSO_SETTLE_AFTER_SEND_MS = 600;

export async function releaseLassoIfGoneAfterSend(): Promise<void> {
  if (hostState().document) { return; }
  if (!hostState().lassoMenuOpen) { return; }
  await sleep(LASSO_SETTLE_AFTER_SEND_MS);
  if (!hostState().lassoMenuOpen) { return; }

  let gone = false;
  try {
    const r = asResponse(await PluginCommAPI.getLassoRect());
    gone = r?.success === true && r.result == null;
  } catch (e) {
    log.w(TAG, 'getLassoRect after send failed', e);
  }
  if (!gone) { return; }

  let els: Element[] | null = null;
  try {
    const res = asResponse(await PluginCommAPI.getLassoElements());
    if (res?.success === true && Array.isArray(res.result)) { els = res.result as Element[]; }
  } catch (e) {
    log.w(TAG, 'getLassoElements after send failed', e);
  }
  const empty = els !== null && els.length === 0;
  elements.releaseHostElements(els);
  if (!empty) { return; }
  log.i(TAG, 'lasso gone after send but menu still marked open; releasing');
  notipalHost.releaseStaleLassoMenu('send closed, no lasso selection');
}

export type PenLassoResult =
  | { status: 'selected'; page: HostCtx; rect: Rect }
  | { status: 'cancelled' | 'empty' | 'stale' | 'error' | 'no_page' };

type PenRequest = {
  id: string;
  promise: Promise<PenLassoResult>;
  cancel(): void;
  onBbox(ev: NativeEvents['lasso:penBbox']): void;
  onCancel(): void;
};

let penSequence = 0;
let currentPen: PenRequest | null = null;

const BBOX_PADDING_PX = 100;

export const PenLasso = {
  arm(options: { page?: HostCtx } = {}): Promise<PenLassoResult> {
    if (currentPen) { return currentPen.promise; }
    const id = `selection-${Date.now()}-${++penSequence}`;
    let settle!: (result: PenLassoResult) => void;
    const promise = new Promise<PenLassoResult>(resolve => { settle = resolve; });
    let settled = false;
    let processing = false;
    let listening = false;

    let hostSelectionMade = false;
    let page: HostCtx | null = null;
    const alive = () => !settled && currentPen?.id === id;
    const clearHostSelection = () => {
      PluginCommAPI.setLassoBoxState(2).catch((e: unknown) =>
        log.i('PenLasso', `request=${id} orphan selection clear failed: ${String(e)}`));
    };
    const finish = (result: PenLassoResult) => {
      if (settled) { return; }
      settled = true;
      listening = false;
      if (result.status !== 'selected') {
        notipalLasso.cancelPenSelection(id);
        if (hostSelectionMade) { clearHostSelection(); }
      }
      if (currentPen?.id === id) { currentPen = null; }
      notipalToolbar.restore('pen lasso');
      settle(result);
    };
    const onBbox = (ev: NativeEvents['lasso:penBbox']) => {
      const target = page;
      if (!listening || !alive() || processing || !target) { return; }
      processing = true;
      (async () => {
        if (!await selection.isSameSelectionPage(target)) { return finish({ status: 'stale' }); }
        if (!alive()) { return; }
        if (!coords.validSelectionRect(ev)) { return finish({ status: 'empty' }); }
        const rect = {
          left: Math.max(0, Math.floor(ev.left) - BBOX_PADDING_PX),
          top: Math.max(0, Math.floor(ev.top) - BBOX_PADDING_PX),
          right: Math.min(target.pageSize.width, Math.ceil(ev.right) + BBOX_PADDING_PX),
          bottom: Math.min(target.pageSize.height, Math.ceil(ev.bottom) + BBOX_PADDING_PX),
        };
        if (!coords.validSelectionRect(rect)) { return finish({ status: 'empty' }); }
        log.i('PenLasso', `request=${id} create rect=${JSON.stringify(rect)}`);

        try {
          const synced = await notipalLasso.prepareLivePage(
            target.hostFilePath, target.page, target.pageSize.width, target.pageSize.height);
          log.i('PenLasso', `request=${id} live-page-sync=${synced === true}`);
        } catch (e) {
          log.i('PenLasso', `request=${id} live-page-sync-error=${String(e)}`);
        }
        if (!alive() || !await selection.isSameSelectionPage(target)) { return finish({ status: 'stale' }); }
        const made = await PluginCommAPI.lassoElements(rect);
        hostSelectionMade = made?.success === true && made.result !== false;
        if (!alive()) {

          if (hostSelectionMade) { clearHostSelection(); }
          return;
        }
        if (!hostSelectionMade) { return finish({ status: 'empty' }); }
        if (!await selection.isSameSelectionPage(target)) { return finish({ status: 'stale' }); }
        if (!alive()) { return; }
        const shown = await PluginCommAPI.setLassoBoxState(0);
        if (!alive()) { return; }
        if (shown?.success === false) { return finish({ status: 'error' }); }
        const hasContent = await selection.hasSelectedContent();
        if (!alive()) { return; }
        if (hasContent === 'no_page') { return finish({ status: 'no_page' }); }
        if (!hasContent) { return finish({ status: 'empty' }); }
        if (!await selection.isSameSelectionPage(target)) { return finish({ status: 'stale' }); }
        if (!alive()) { return; }

        const completed = await notipalLasso.finishPenSelection(id);
        if (!alive()) { return; }
        if (!completed) { return finish({ status: 'cancelled' }); }
        log.i('PenLasso', `request=${id} selection ready`);
        return finish({ status: 'selected', page: target, rect });
      })().catch((e: unknown) => {
        log.i('PenLasso', String(e));
        finish({ status: 'error' });
      });
    };
    currentPen = {
      id,
      promise,
      cancel: () => finish({ status: 'cancelled' }),
      onBbox,
      onCancel: () => finish({ status: 'cancelled' }),
    };
    (async () => {
      const ctx = options.page ?? await readHostCtx();
      if (!alive()) { return; }
      if (ctx.noPage) { return finish({ status: 'no_page' }); }
      if (!ctx.filePath || ctx.page < 0 || ctx.pageSize.width <= 0 || ctx.pageSize.height <= 0) {
        return finish({ status: 'error' });
      }
      page = ctx;
      listening = true;
      notipalLasso.beginPenSelection(id);
    })().catch((e: unknown) => {
      log.i('PenLasso', String(e));
      finish({ status: 'error' });
    });
    return promise;
  },
  cancel(): void {
    currentPen?.cancel();
  },
};

export function onPenBbox(ev: NativeEvents['lasso:penBbox']): void {
  if (currentPen?.id === ev.requestId) { currentPen.onBbox(ev); }
}

export function onPenCancel(ev: NativeEvents['lasso:penCancel']): void {
  if (currentPen?.id === ev.requestId) { currentPen.onCancel(); }
}

export function register(): void {
  registerAction(TOOL.SMART_LASSO, smartLassoAction);
}
