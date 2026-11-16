import { PluginCommAPI, PluginDocAPI } from 'sn-plugin-lib';
import { after, log, withTimeout } from './common';
import { captureHooks } from './capture';
import type { RegionClosed } from './capture';
import { asResponse, hostState, readHostCtx, selection } from './host';
import type { HostCtx, SelectionRequest } from './host';
import { t } from './i18n';
import { PenLasso, extractLasso, extractMosaicLassoText } from './lasso';
import type { ExtractStage } from './lasso';
import { hasNativeModule, notipalLasso, notipalMosaic, notipalRelay } from './native';
import type { NativeEvents } from './native';
import { placeAnchor, relayText, setBubbleAside, stopTextReceive, textState } from './textInsert';

const TAG = 'relay';
const RELAY_AVAILABLE = hasNativeModule('NotipalRelay');

const AI_REPLY_GAP_PX = 52;

const AI_REPLY_TIMEOUT_MS = 90_000;

const LASSO_CLEAR_TIMEOUT_MS = 3000;

const RESTORE_AFTER_REGION_MS = 300;

let selectionBusy = false;

let screenshotInFlight = false;

let waiting = false;

let selectionEpoch = 0;
let cancelTimeout: (() => void) | null = null;

let pendingAnchor: { top: number; left: number } | null = null;

function relayOn(): boolean {
  try {
    return notipalRelay.isActive();
  } catch (e) {
    log.w(TAG, 'isActive failed', e);
    return false;
  }
}

function setStatus(text: string): void {
  notipalRelay.setStatus(text);
}

function flash(text: string, ms: number): void {
  notipalRelay.flash(text, ms);
}

function clearAiTimeout(): void {
  if (cancelTimeout !== null) {
    cancelTimeout();
    cancelTimeout = null;
  }
}

function armReplyTimeout(logTag: string): void {
  clearAiTimeout();
  cancelTimeout = after(AI_REPLY_TIMEOUT_MS, () => {
    cancelTimeout = null;
    if (!waiting) { return; }
    waiting = false;
    log.w(TAG, `${logTag}: AI reply timeout`);
    flash(t('bubble_ai_timeout'), 2500);
  });
}

function settleWait(): void {
  if (!waiting) { return; }
  clearAiTimeout();
  waiting = false;
  setStatus('');
}

async function ensureWindow(forPage: boolean): Promise<boolean> {
  try {
    const open = await notipalRelay.openWindow(forPage);
    if (!open) { log.i(TAG, 'relay window not opened, AI mode off'); }
    return open === true;
  } catch (e) {
    log.w(TAG, 'relay window open failed', e);
    return false;
  }
}

function restoreAfterLasso(): void {
  notipalRelay.setAside(false);
  setBubbleAside(false);
}

function applyPendingAnchor(): void {
  const pending = pendingAnchor;
  if (!pending) { return; }
  pendingAnchor = null;
  placeAnchor(pending.top, pending.left, 'screenshot');
}

export async function requestAiSelection(): Promise<void> {
  if (selectionBusy || screenshotInFlight || waiting) {
    log.i(TAG, `AI selection skipped, busy=${selectionBusy} screenshot=${screenshotInFlight} waiting=${waiting}`);
    return;
  }
  if (!await ensureWindow(true)) { return; }
  selectionBusy = true;
  pendingAnchor = null;
  const epoch = ++selectionEpoch;
  let hidden = false;
  const canContinue = () => selectionEpoch === epoch && relayOn();
  try {
    const state = hostState();

    if (state.surface === 'document' && state.docTextSelected && await stageDocSelectedText()) { return; }
    if (state.surface === 'mosaic') {

      await handleAiSend({ canContinue }, await readHostCtx());
      return;
    }
    const page = await readHostCtx();
    if (!canContinue()) { return; }
    if (page.noPage) { selection.showHostNoPageHint(); return; }
    if (!page.filePath || page.page < 0 || page.pageSize.width <= 0 || page.pageSize.height <= 0) {
      flash(t('ai_selection_retry'), 2500);
      return;
    }
    const selected = await selection.hasSelectedContent();
    const request: SelectionRequest = { expectedPage: page, canContinue };
    if (!await selection.selectionRequestIsCurrent(request)) { return; }
    if (selected === 'no_page') { selection.showHostNoPageHint(); return; }
    if (!selected) {
      hidden = true;
      notipalRelay.setAside(true);
      setBubbleAside(true);
      const result = await PenLasso.arm({ page });
      if (!canContinue()) { return; }
      restoreAfterLasso();
      hidden = false;
      if (result.status !== 'selected') {
        if (result.status === 'no_page') { selection.showHostNoPageHint(); }
        else if (result.status !== 'cancelled') { flash(t('ai_selection_retry'), 2500); }
        return;
      }
    }
    if (!await selection.selectionRequestIsCurrent(request)) { return; }
    await handleAiSend(request, page);
  } catch (e) {
    log.w(TAG, `AI selection request=${epoch} failed`, e);
    if (canContinue()) { flash(t('ai_selection_retry'), 2500); }
  } finally {
    if (selectionEpoch === epoch) { selectionBusy = false; }

    if (hidden) { notipalRelay.setAside(false); }
  }
}

async function stageDocSelectedText(): Promise<boolean> {
  let text = '';
  try {
    const sel = asResponse(await PluginDocAPI.getLastSelectedText());
    if (sel?.success && typeof sel.result === 'string') { text = sel.result; }
  } catch (e) {
    log.w(TAG, 'doc selected text read failed', e);
  }
  if (!text.trim()) { return false; }
  let staged = false;
  try { staged = await notipalRelay.stage(text); } catch (e) { log.w(TAG, 'stage doc text failed', e); }
  log.i(TAG, staged ? `${text.length} doc chars staged in AIRelay window` : `${text.length} doc chars dropped: no AIRelay window`);
  return true;
}

async function handleAiSend(request: SelectionRequest, ctx: HostCtx): Promise<void> {
  const onProgress = (stage: ExtractStage) => {
    if (stage === 'recognizing') { setStatus(t('bubble_ai_recognizing')); }
  };
  try {
    if (!await selection.selectionRequestIsCurrent(request)) { return; }
    const mosaicBoard = hostState().surface === 'mosaic';
    const text = mosaicBoard
      ? (await extractMosaicLassoText(ctx, onProgress)).text ?? ''
      : (await extractLasso(ctx, onProgress)).text ?? '';
    setStatus('');
    if (!await selection.selectionRequestIsCurrent(request)) { return; }
    if (!text) {
      log.i(TAG, 'AI send aborted: no text');
      flash(t('bubble_ai_no_lasso'), 2500);
      return;
    }
    if (mosaicBoard) {
      notipalMosaic.clearSelection(false);
    } else {
      try {
        await withTimeout(PluginCommAPI.setLassoBoxState(2), LASSO_CLEAR_TIMEOUT_MS, 'setLassoBoxState');
        log.i(TAG, 'lasso selection cleared');
      } catch (e) {
        log.w(TAG, 'setLassoBoxState failed (non-fatal)', e);
      }
    }
    let staged = false;
    try { staged = await notipalRelay.stage(text); } catch (e) { log.w(TAG, 'stage text failed', e); }
    log.i(TAG, staged ? `${text.length} chars staged in AIRelay window` : `${text.length} chars dropped: no AIRelay window`);
  } catch (e) {
    setStatus('');
    log.e(TAG, 'handleAiSend failed', e);
  }
}

export async function handleScreenshotAi(): Promise<void> {
  if (selectionBusy || waiting || screenshotInFlight) { return; }
  if (!await ensureWindow(true)) { return; }
  screenshotInFlight = true;
  log.i(TAG, 'screenshot AI: region capture opened');
  notipalLasso.startCapture('ai', true);
}

function onRegionClosed(ev: RegionClosed): void {
  screenshotInFlight = false;
  if (ev.bbox) {
    pendingAnchor = { top: ev.bbox.bottom + AI_REPLY_GAP_PX, left: ev.bbox.left };
    waiting = true;
    setStatus(t('bubble_ai_waiting'));
    armReplyTimeout('ScreenshotAi');
    log.i(TAG, 'screenshot AI submitted, waiting for reply');
  }
  if (ev.fromBubble) { after(RESTORE_AFTER_REGION_MS, restoreAfterLasso); }
}

export function onAction(ev: NativeEvents['relay:action']): void {
  log.i(TAG, `window action: ${ev.action}`);
  if (ev.action === 'lasso_ai') {
    requestAiSelection().catch(e => log.e(TAG, 'AI selection failed', e));
  } else if (ev.action === 'screenshot_ai') {
    handleScreenshotAi().catch(e => log.e(TAG, 'screenshot AI failed', e));
  } else if (ev.action === 'reply_failed') {
    clearAiTimeout();
    waiting = false;
  }
}

export async function onInsert(ev: NativeEvents['relay:insert']): Promise<void> {
  log.i(TAG, `insert from relay len=${ev.text.length}`);
  const result = await relayText(ev.text);
  log.i(TAG, `insert routed: ${result}`);
  if (result === 'document') {
    if (relayOn()) { flash(t('bubble_doc_insert_unsupported'), 3000); }
    return;
  }
  if (result !== 'staged') { return; }
  applyPendingAnchor();
  if (relayOn()) { flash(t('bubble_tap_to_insert'), 4000); }
}

export function onReplyArrived(_ev: NativeEvents['relay:replyArrived']): void {
  settleWait();
}

export function onOff(_ev: NativeEvents['relay:off']): void {
  ++selectionEpoch;
  PenLasso.cancel();
  selectionBusy = false;
  screenshotInFlight = false;
  waiting = false;
  pendingAnchor = null;
  clearAiTimeout();
  const kind = textState().kind;
  if (kind === 'starting' || kind === 'receiving') { stopTextReceive('ai relay off'); }
}

export function register(): void {
  if (!RELAY_AVAILABLE) {
    log.e(TAG, 'NotipalRelay missing, AI relay is unavailable');
    return;
  }
  captureHooks.regionClosed = onRegionClosed;
}
