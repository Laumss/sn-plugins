import { NativeModules, NativeEventEmitter, DeviceEventEmitter } from 'react-native';
import { PluginCommAPI, PluginDocAPI, PluginFileAPI } from 'sn-plugin-lib';
import HostDialog from './HostDialog';
import { TextInserter, InsertMode, TextSource, presetPageInfo, type InsertionAnchor, type QueueItem } from './TextInserter';
import { FileLogger } from './FileLogger';
import LocalSendBridge, { TextReceivedInfo } from './LocalSendBridge';
import { LassoExtractor, type ExtractStage } from './LassoExtractor';
import FloatingBubbleBridge, { RelayWindowBridge, PaletteBubbleBridge } from './BubbleBridges';

import { loadAiSelectionMode, saveAiSelectionMode, AiSelectionMode } from './ToolPresets';
import { PenLasso, readSelectionPage, hasSelectedContent, selectionRequestIsCurrent, showHostNoPageHint, type AiSelectionRequest } from './PenTools';
import { sendFormulaQuery, processRelayBlocks } from './FormulaService';
import { t } from './i18n';
import FloatingToolbarBridge, { ENABLE_DEBUG, type MosaicBoardState, type Subscription } from './FloatingToolbarBridge';
import { TOOL_IDS } from './ToolIdentifiers';

const { AIRelayModule } = NativeModules;

const AI_REPLY_GAP_PX = 52;
const LAST_MODE_STORE_KEY = 96;

const { FloatingToolbar } = NativeModules;

async function saveLastMode(mode: InsertMode): Promise<void> {
  try { await FloatingToolbar?.savePreset(LAST_MODE_STORE_KEY, mode); } catch (_) {}
}

async function loadLastMode(): Promise<InsertMode> {
  try {
    const v = await FloatingToolbar?.loadPreset(LAST_MODE_STORE_KEY);
    if (v === 'nospacing' || v === 'paragraph') return v;
  } catch (_) {}
  return 'nospacing';
}

let _textInserter: TextInserter | null = null;
let _broadcastTextSub: Subscription | null = null;
let _lsTextSub: Subscription | null = null;
let _initialized = false;
let _activeMode: InsertMode | null = null;
let _lastMode: InsertMode = 'nospacing';
let _aiWaiting = false;
let _aiTimeoutRef: ReturnType<typeof setTimeout> | null = null;
const AI_REPLY_TIMEOUT_MS = 90_000;

let _pendingScreenshotAnchor: { top: number; left: number } | null = null;

let _blocksSub: Subscription | null = null;
let _replaceSub: Subscription | null = null;

let _nativePanelCloseSub: Subscription | null = null;

let _noteForegroundSub: Subscription | null = null;
let _mosaicSurfaceSub: Subscription | null = null;

let _lastMosaicVisible = false;
let _hostAppModeSub: Subscription | null = null;

let _floatsClosedSub: Subscription | null = null;

let _aiSelectionMode: AiSelectionMode = 'text';
let _aiSelectionModeTouched = false;
let _aiSelectionBusy = false;
let _aiSelectionEpoch = 0;
let _aiScreenshotInFlight = false;

let _aiActive = false;
let _relayEnabled = false;

let _aiPositionLocked = false;

let _pendingCards: QueueItem[] = [];

let _mosaicTextActive = false;
let _mosaicTextBuffer: string[] = [];

let _switchVersion = 0;

let _localSendStarted = false;
let _localSendStartPromise: Promise<boolean> | null = null;
let _localSendGen = 0;

let _relayInboxSub: Subscription | null = null;

let _knownInboxIds: Set<string> | null = null;

let _lastPermissionTipAt = 0;

async function _ensurePermanentTextReceiverPermissions(): Promise<boolean> {
  const readGranted = await FloatingToolbarBridge.requestFileReadPermission();
  const writeGranted = readGranted
    ? await FloatingToolbarBridge.requestFileWritePermission()
    : false;
  const ready = readGranted && writeGranted;
  console.log('[BackgroundService]: text receiver permanent permissions',
    'read=', readGranted, 'write=', writeGranted, 'ready=', ready);
  if (!ready && Date.now() - _lastPermissionTipAt > 1500) {
    _lastPermissionTipAt = Date.now();
    HostDialog.tip(t('initial_permissions_required'));
  }
  return ready;
}

async function _startTextInserter(
  inserter: TextInserter,
  mode: InsertMode,
  preservePosition = false,
): Promise<boolean> {
  if (!await _ensureTextReceiverRuntime()) {
    _activeMode = null;
    _pushActiveModes();
    emitInsertMode(null);
    if (FloatingBubbleBridge.isAvailable) FloatingBubbleBridge.hide();
    return false;
  }
  return inserter.start(mode, preservePosition);
}

async function _ensureTextReceiverRuntime(): Promise<boolean> {
  if (!await _ensurePermanentTextReceiverPermissions()) return false;
  return startLocalSend();
}

function clearAiTimeout(): void {
  if (_aiTimeoutRef !== null) {
    clearTimeout(_aiTimeoutRef);
    _aiTimeoutRef = null;
  }
}

function _armAiReplyTimeout(logTag: string): void {
  clearAiTimeout();
  _aiTimeoutRef = setTimeout(() => {
    _aiTimeoutRef = null;
    if (!_aiWaiting) return;
    _aiWaiting = false;
    console.warn('[BackgroundService]: AI reply timeout');
    FileLogger.logEvent(logTag, 'AI reply timeout');
    RelayWindowBridge.flash(t('bubble_ai_timeout'), 2500);
  }, AI_REPLY_TIMEOUT_MS);
}

function _settleAiWait(): void {
  if (!_aiWaiting) return;
  clearAiTimeout();
  _aiWaiting = false;
  RelayWindowBridge.setStatus('');
}

function _syncSelectionMode(): void {
  RelayWindowBridge.setSelectionMode(_aiSelectionMode, ENABLE_DEBUG);
}

function _useTextMode(): void {
  _aiSelectionMode = 'text';
  void saveAiSelectionMode('text');
  _syncSelectionMode();
}

async function _webBackend(): Promise<boolean> {
  try { return await AIRelayModule.isWebBackend() === true; } catch (_) { return false; }
}

async function _ensureRelayWindow(forPage: boolean): Promise<boolean> {
  try {
    if (!_relayEnabled) {
      const enabled = await AIRelayModule.setRelayEnabled(true);
      _relayEnabled = enabled === true;
    }
  } catch (e) {
    console.warn('[BackgroundService]: AIRelay start failed:', e);
  }
  if (!_aiActive) {
    _aiActive = true;
    reviveBridge();
    _pushActiveModes();
  }
  _syncSelectionMode();
  const open = await RelayWindowBridge.open(forPage);
  if (!open) {
    FileLogger.logEvent('RelayWindow', 'not opened, AI mode off');
    stopAiMode();
  }
  return open;
}

function emitInsertMode(mode: InsertMode | null): void {
  DeviceEventEmitter.emit('insertModeChanged', { mode });
}

function _pushActiveModes(): void {
  const modes: string[] = [];
  if (_activeMode || _mosaicTextActive) modes.push('insert_text');
  if (_aiActive) {
    modes.push(TOOL_IDS.AI_RELAY);
  }
  FloatingToolbarBridge.setActiveModes(modes);
}

function reviveBridge(): void {
  if (!AIRelayModule || !_aiActive) return;

  AIRelayModule.startInboxListening();
  console.log('[BackgroundService]: bridge revived');
}

function _applyPendingScreenshotAnchor(): void {
  const pending = _pendingScreenshotAnchor;
  if (!pending) return;
  _pendingScreenshotAnchor = null;
  const anchor = setProgrammaticInsertionAnchor(pending.top, pending.left, 'screenshot');
  if (anchor) showTextBubbleAtInsertion(anchor);
}

function _attachRelayInsertListeners(): void {
  if (!AIRelayModule || _broadcastTextSub) return;
  const bbEmitter = new NativeEventEmitter(AIRelayModule);
  _broadcastTextSub = bbEmitter.addListener('onTextFromRelay', (text: string) => {
    console.log('[BackgroundService]: onTextFromRelay len=', text.length);
    FileLogger.logTextReceived('Broadcast', text);

    if (_mosaicTextEngaged() || _isMosaicVisible()) {
      _stageMosaicText(text, 'broadcast');
      return;
    }
    if (_inDocument()) {
      _rejectDocInsert('text');
      return;
    }

    if (!_textInserter) return;
    if (_activeMode) {
      _applyPendingScreenshotAnchor();
      _stagePendingCard(text, 'broadcast');
      _hintTapBubble();
      return;
    }
    (async () => {
      const inserter = _textInserter;
      if (!inserter) return;
      let ok = inserter.isRunning();
      if (inserter.isPaused()) {
        inserter.resume();
        ok = true;
      } else if (!ok) {
        ok = await _startTextInserter(inserter, 'nospacing');
      }
      if (!ok) return;

      _activeMode = inserter.getMode() ?? 'nospacing';
      _pushActiveModes();
      emitInsertMode(_activeMode);
      if (FloatingBubbleBridge.isAvailable) {
        console.log('[BUBBLE-DBG/JS] text receiver: calling bridge.show, mode=', _activeMode);
        _pushBubblePageSize(inserter);
        _aiPositionLocked = false;
        FloatingBubbleBridge.show(_getBubbleStatusText(), _activeMode);
      }
      _applyPendingScreenshotAnchor();
      _stagePendingCard(text, 'broadcast');
      _hintTapBubble();
    })().catch(e => console.error('[BackgroundService]: relay text receiver start error:', e));
  });

  _blocksSub = bbEmitter.addListener('onBlocksFromRelay',
    (payload: RelayBlocksPayload) => {
      handleRelayBlocks(payload, false).catch(e =>
        console.error('[BackgroundService]: onBlocksFromRelay error:', e));
    });

  _replaceSub = bbEmitter.addListener('onReplaceFromRelay',
    (payload: RelayBlocksPayload) => {
      handleRelayBlocks(payload, true).catch(e =>
        console.error('[BackgroundService]: onReplaceFromRelay error:', e));
    });
}

type RelayBlocksPayload = { text: string; blocks: string; savedSelection?: boolean };

async function handleRelayBlocks(
  payload: RelayBlocksPayload,
  replace: boolean,
): Promise<void> {
  console.log('[BackgroundService]: relay blocks replace=', replace, 'len=', payload.blocks?.length);
  FileLogger.logEvent('RelayBlocks', `replace=${replace} bytes=${payload.blocks?.length ?? 0}`);

  if (_mosaicTextEngaged() || _isMosaicVisible()) {
    const flat = (payload.text ?? '').trim();
    if (flat.length > 0) _stageMosaicText(flat, 'broadcast');
    return;
  }
  if (_inDocument()) {
    _rejectDocInsert(replace ? 'replace' : 'blocks');
    return;
  }

  if (!_textInserter) return;

  if (_textInserter.isPaused()) {
    _textInserter.resume();
  } else if (!_textInserter.isRunning()) {
    _textInserter.clearQueue();
    const ok = await _startTextInserter(_textInserter, 'nospacing');
    if (ok) {
      _activeMode = 'nospacing';
      _pushActiveModes();
      emitInsertMode('nospacing');
    }
  }

  try {
    const pgRes: any = await PluginCommAPI.getCurrentPageNum();
    if (pgRes?.success && typeof pgRes.result === 'number'
        && pgRes.result !== _textInserter.getTargetPage()) {
      _textInserter.relocateTo(pgRes.result);
    }
  } catch (_) {}
  _applyPendingScreenshotAnchor();

  await processRelayBlocks(payload.blocks, {
    replace,
    savedSelection: payload.savedSelection !== false,
    insertText: (text, anchorTop, anchorLeft) => {
      if (anchorTop != null && anchorLeft != null) {
        const anchor = setProgrammaticInsertionAnchor(anchorTop, anchorLeft, replace ? 'formula-replace' : 'formula-insert');
        if (anchor) showTextBubbleAtInsertion(anchor);
      }
      _textInserter?.enqueue(text, 'broadcast');
    },

    waitTextIdle: async () => {
      const ti = _textInserter;
      if (!ti) return null;
      const deadline = Date.now() + 45_000;
      while (Date.now() < deadline
          && (ti.getQueueLength() > 0 || ti.isBusy())) {
        await new Promise<void>(r => setTimeout(r, 300));
      }
      if (ti.getQueueLength() > 0 || ti.isBusy()) {
        FileLogger.logEvent('RelayBlocks', 'waitTextIdle timeout — using current nextTop');
      }
      await new Promise<void>(r => setTimeout(r, 200));
      return ti.getNextTop();
    },

    onEmbedded: (bottom, left) => {
      const anchor = setProgrammaticInsertionAnchor(bottom + 24, left, 'formula-embedded');
      if (anchor) showTextBubbleAtInsertion(anchor);
    },
    onStatus: (stage) => {
      if (stage === 'rendering') RelayWindowBridge.setStatus(t('bubble_formula_rendering'));
      else if (stage === 'inserting') RelayWindowBridge.setStatus(t('bubble_ai_sending'));
      else if (stage === 'failed') RelayWindowBridge.flash(t('bubble_formula_failed'), 2500);
      else RelayWindowBridge.setStatus('');
    },
  });
}

function _noteBubbleStatus(mode: InsertMode): string {
  const base = mode === 'nospacing'
    ? t('bubble_recv_nospacing')
    : t('bubble_recv_paragraph');

  if (_textInserter?.isWaitingPage()) {
    return base + ' ⊕';
  }

  const qLen = _textInserter?.getQueueLength() ?? 0;
  if (qLen > 0) return base + ` (${qLen})`;
  return base;
}

function _getBubbleStatusText(): string {
  if (!_activeMode) return '';
  return _noteBubbleStatus(_activeMode);
}

function setProgrammaticInsertionAnchor(top: number, left: number, source: string): InsertionAnchor | null {
  if (!_textInserter) return null;
  const anchor = _textInserter.setNextInsertionAnchor(top, left, source);
  _aiPositionLocked = true;
  return anchor;
}

function _pushBubblePageSize(inserter: TextInserter | null): void {
  const ps = inserter?.getPageSize();
  if (!ps) return;
  FloatingBubbleBridge.setPageHeight(ps.height);
  FloatingBubbleBridge.setPageWidth(ps.width);
}

function showTextBubbleAtInsertion(anchor: InsertionAnchor): void {
  if (!FloatingBubbleBridge.isAvailable || !_textInserter) return;
  _pushBubblePageSize(_textInserter);
  _aiPositionLocked = true;
  FloatingBubbleBridge.showAt(_getBubbleStatusText(), anchor.left, anchor.top, _activeMode ?? undefined);
  console.log('[BackgroundService]: show bubble at insertion origin=', JSON.stringify(anchor));
}

function _isMosaicVisible(): boolean {
  return FloatingToolbarBridge.isMosaicBoardVisible();
}

function _mosaicBubbleStatus(): string {
  return _mosaicTextBuffer.length > 0 ? t('bubble_mosaic_pending') : t('bubble_mosaic_ready');
}

function _mosaicBubbleOwned(): boolean {
  return _isMosaicVisible() && (_mosaicTextActive || _mosaicTextBuffer.length > 0);
}

function _refreshMosaicBubble(): void {
  if (!FloatingBubbleBridge.isAvailable) return;
  if (_isMosaicVisible() && (_mosaicTextActive || _mosaicTextBuffer.length > 0)) {
    FloatingBubbleBridge.setPending(_mosaicTextBuffer.length > 0);
    FloatingBubbleBridge.show(_mosaicBubbleStatus(), 'nospacing');
    return;
  }
  if (_activeMode) {
    _syncTextBubblePending();
    FloatingBubbleBridge.show(_getBubbleStatusText(), _activeMode);
  } else {
    FloatingBubbleBridge.setPending(false);
    FloatingBubbleBridge.hide();
  }
}

async function toggleMosaicTextMode(): Promise<boolean> {
  if (_mosaicTextActive) {
    stopMosaicTextMode();
    return false;
  }

  if (!await _ensureTextReceiverRuntime()) {
    return false;
  }
  _mosaicTextActive = true;
  _mosaicTextBuffer = [];
  _pushActiveModes();
  if (FloatingBubbleBridge.isAvailable) {
    FloatingBubbleBridge.setPending(false);
    FloatingBubbleBridge.show(_mosaicBubbleStatus(), 'nospacing');
  }
  FileLogger.logEvent('MosaicText', 'mode ON');
  return true;
}

function stopMosaicTextMode(): void {
  if (!_mosaicTextActive) return;
  _mosaicTextActive = false;
  _mosaicTextBuffer = [];
  _pushActiveModes();
  if (FloatingBubbleBridge.isAvailable) {
    FloatingBubbleBridge.setPending(false);
    FloatingBubbleBridge.hide();
  }
  FileLogger.logEvent('MosaicText', 'mode OFF');
}

function _mosaicTextEngaged(): boolean {
  return _mosaicTextActive && _isMosaicVisible();
}

type HostContext = 'note' | 'doc' | 'mosaic';
let _hostDocument = false;
let _lastHostContext: HostContext | null = null;

function _hostContext(): HostContext {
  if (_isMosaicVisible()) return 'mosaic';
  return _hostDocument ? 'doc' : 'note';
}

function _inDocument(): boolean {
  return _hostContext() === 'doc';
}

function _syncHostContext(reason: string): void {
  const next = _hostContext();
  const prev = _lastHostContext;
  _lastHostContext = next;
  if (prev === next) return;
  console.log('[BackgroundService]: host context', prev, '→', next, 'reason=', reason);
  FileLogger.logEvent('HostContext', `${prev ?? 'init'}→${next} reason=${reason}`);
  if (next === 'doc') _stopTextReceiveForDocument(reason);
}

function _stopTextReceiveForDocument(reason: string): void {
  const inserterLive = (_textInserter?.isRunning() || _textInserter?.isPaused()) ?? false;
  const hadText = _activeMode !== null || inserterLive || _mosaicTextActive
    || _mosaicTextBuffer.length > 0 || _pendingCards.length > 0;
  if (!hadText) return;
  ++_switchVersion;
  _mosaicTextActive = false;
  _mosaicTextBuffer = [];
  stopMode();
  FileLogger.logEvent('DocTextReceiveStop', `reason=${reason} text=${hadText}`);
  if (hadText) {
    try { HostDialog.tip(t('doc_text_receive_stop')); } catch (_) {}
  }
}

function _rejectDocInsert(kind: string): void {
  FileLogger.logEvent('DocInsertRejected', kind);
  if (_aiActive) RelayWindowBridge.flash(t('bubble_doc_insert_unsupported'), 3000);
}

function _stampTextBubble(text: string, mode: string, create: boolean): void {
  if (!FloatingBubbleBridge.isAvailable) return;
  FloatingBubbleBridge.setPending(_mosaicTextActive && _mosaicTextBuffer.length > 0);
  if (create) FloatingBubbleBridge.show(text, mode);
  else FloatingBubbleBridge.remember(text, mode);
}

function _adoptTextModeForSurface(event: MosaicBoardState): void {
  const { visible } = event;
  const surface = event.surface ?? (visible ? FloatingToolbarBridge.getMosaicSurface() : 'host');
  if (visible) {
    if (_activeMode) {
      _textInserter?.stop(true);
      _activeMode = null;
      _mosaicTextActive = true;
      _pushActiveModes();
      emitInsertMode(_lastMode);
    }
    if (_mosaicTextActive || _mosaicTextBuffer.length > 0) {

      _stampTextBubble(_mosaicBubbleStatus(), 'nospacing', true);
    }
    FileLogger.logEvent('MosaicSurface', `enter surface=${surface} text=${_mosaicTextActive} ai=${_aiActive} pending=${_mosaicTextBuffer.length}`);
    return;
  }

  if (_hostDocument) {

    FileLogger.logEvent('MosaicSurface', `leave surface=${surface} into document ai=${_aiActive}`);
    return;
  }

  if (_mosaicTextActive) {
    _mosaicTextActive = false;
    _pushActiveModes();
    const mode = _lastMode;
    _stampTextBubble(_noteBubbleStatus(mode), mode, false);
    switchToMode(mode).catch(e =>
      console.warn('[BackgroundService]: mosaic→note text mode failed:', e));
  } else if (_activeMode) {
    _stampTextBubble(_getBubbleStatusText(), _activeMode, false);
  } else if (_mosaicTextBuffer.length > 0 && FloatingBubbleBridge.isAvailable) {
    FloatingBubbleBridge.hide();
  }
  FileLogger.logEvent('MosaicSurface', `leave surface=${surface} text=${_activeMode ?? 'off'} ai=${_aiActive}`);
}

function _closeSurfaceModes(edge: string, closeAi = true): void {
  const text = _activeMode !== null || _mosaicTextActive;
  const ai = closeAi && _aiActive;
  if (text) stopTextReceive();
  if (ai) stopAiMode();
  if (text || ai) FileLogger.logEvent('MosaicSurface', `${edge}: closed text=${text} ai=${ai}`);
}

function _stageMosaicText(text: string, source: TextSource): void {
  const trimmed = text ?? '';
  if (trimmed.length === 0) return;
  _mosaicTextBuffer.push(trimmed);
  console.log('[BackgroundService]: staged mosaic text, source=', source,
    'pending=', _mosaicTextBuffer.length);
  FileLogger.logEvent('MosaicText', `staged source=${source} pending=${_mosaicTextBuffer.length}`);
  _refreshMosaicBubble();
}

function _flushMosaicTextCard(d: {
  screenX: number; screenBottomY: number; bubbleWidth: number;
}): void {
  if (_mosaicTextBuffer.length === 0) return;
  const text = _mosaicTextBuffer.join('\n\n');
  _mosaicTextBuffer = [];
  const anchorX = Math.round(d.screenX + (d.bubbleWidth ?? 0));
  const anchorY = Math.round(d.screenBottomY);
  FloatingToolbarBridge.requestMosaicTextCard(text, anchorX, anchorY);
  console.log('[BackgroundService]: mosaic text card sent anchor=', anchorX, anchorY, 'len=', text.length);
  FileLogger.logEvent('MosaicText', `flush anchor=(${anchorX},${anchorY}) len=${text.length}`);
  _refreshMosaicBubble();
}

function _hintTapBubble(): void {
  if (_aiActive) RelayWindowBridge.flash(t('bubble_tap_to_insert'), 4000);
}

function _stagePendingCard(text: string, source: TextSource): void {
  _pendingCards.push({ text, source });
  console.log('[BackgroundService]: staged text card, source=', source, 'pending=', _pendingCards.length);
  FileLogger.logEvent('StageCard', `source=${source} pending=${_pendingCards.length}`);
  _syncTextBubblePending();
}

function _routeLocalSendText(info: TextReceivedInfo): void {
  FileLogger.logTextReceived('LocalSend', info.text);
  if (info._pendingId) LocalSendBridge.ackPendingText(info._pendingId);
  if (_mosaicTextEngaged()) {
    _stageMosaicText(info.text, 'localsend');
  } else if (_inDocument()) {

    LocalSendBridge.copyTextToClipboard(info.text);
    FileLogger.logEvent('DocTextToClipboard', 'localsend');
  } else if (_activeMode) {
    _stagePendingCard(info.text, 'localsend');
  } else {
    _textInserter?.enqueue(info.text, 'localsend');
  }
}

function _syncTextBubblePending(): void {
  const pending = _pendingCards.length > 0
    || (_textInserter?.isWaitingForPageTap() ?? false);
  if (FloatingBubbleBridge.isAvailable) {
    FloatingBubbleBridge.setPending(pending);
    FloatingBubbleBridge.setPageFull(!pending && (_textInserter?.isWaitingForPageTurn() ?? false));
  }
}

async function _flushPendingCards(beforeDrain?: () => void): Promise<void> {
  if (!_textInserter) {
    beforeDrain?.();
    return;
  }
  const inserter = _textInserter;
  try {
    const pgRes: any = await PluginCommAPI.getCurrentPageNum();
    if (pgRes?.success && typeof pgRes.result === 'number'
        && pgRes.result !== inserter.getTargetPage()) {
      inserter.relocateTo(pgRes.result);
    }
  } catch (e) {
    console.warn('[BackgroundService]: bubble tap page lookup:', e);
  }

  beforeDrain?.();

  const cards = _pendingCards;
  _pendingCards = [];
  if (cards.length > 0) {
    console.log('[BackgroundService]: bubble tap → inserting', cards.length, 'staged card(s)');
    FileLogger.logEvent('FlushCards', `count=${cards.length}`);
    for (const c of cards) inserter.enqueue(c.text, c.source);
  }

  const continued = inserter.continueAfterPageTap();
  if (continued) {
    FileLogger.logEvent('ContinueAfterPageTap',
      `page=${inserter.getTargetPage()} queueLen=${inserter.getQueueLength()}`);
  }
  _syncTextBubblePending();
}

function _clearPendingCards(): void {
  if (_pendingCards.length > 0) {
    console.log('[BackgroundService]: dropping', _pendingCards.length, 'staged card(s)');
    _pendingCards = [];
  }
  _syncTextBubblePending();
}

function _clearTextReceiveQueueFromBubble(): void {
  const staged = _pendingCards.length;
  const queued = _textInserter?.getQueueLength() ?? 0;
  _pendingCards = [];
  _textInserter?.clearQueue();
  _syncTextBubblePending();
  console.log('[BackgroundService]: bubble long press cleared text queue, staged=', staged,
    'queued=', queued);
  FileLogger.logEvent('ClearTextQueue', `staged=${staged} queued=${queued}`);
}

async function prefetchPageInfo(attempt = 0): Promise<void> {
  const MAX_ATTEMPTS = 6;
  try {
    const fpRes: any = await PluginCommAPI.getCurrentFilePath();
    const notePath = fpRes?.success ? fpRes.result : null;
    if (!notePath) throw new Error('no notePath');
    const pgRes: any = await PluginCommAPI.getCurrentPageNum();
    const page = pgRes?.success && typeof pgRes.result === 'number' ? pgRes.result : 0;
    const psRes: any = await PluginFileAPI.getPageSize(notePath, page);
    if (psRes?.success && psRes.result?.width) {
      presetPageInfo(notePath, { width: psRes.result.width, height: psRes.result.height });
      return;
    }
    throw new Error('getPageSize failed: ' + JSON.stringify(psRes?.error ?? psRes));
  } catch (e) {
    if (attempt + 1 >= MAX_ATTEMPTS) {
      console.warn('[BackgroundService]: prefetchPageInfo gave up:', e);
      return;
    }
    console.log('[BackgroundService]: prefetchPageInfo retry', attempt + 1, '(', e, ')');
    setTimeout(() => prefetchPageInfo(attempt + 1), 1200);
  }
}

export function ensureInit(): void {
  if (_initialized) return;
  _initialized = true;

  console.log('[BackgroundService]: ── initializing ──');

  prefetchPageInfo();

  FloatingToolbarBridge.setActiveModes([]);

  loadLastMode().then(m => { _lastMode = m; });

  DeviceEventEmitter.addListener('onOpenTextReceiverRequested', (evt: { text: string }) => {
    console.log('[BackgroundService]: opening text receiver from LocalSend prompt, mode=', _lastMode,
      'context=', _hostContext());

    if (_inDocument()) {
      try { HostDialog.tip(t('doc_text_receive_unsupported')); } catch (_) {}
      return;
    }
    const opened = _isMosaicVisible()
      ? (_mosaicTextActive ? Promise.resolve(true) : toggleMosaicTextMode())
      : (_activeMode ? Promise.resolve(true) : startMode(_lastMode));
    opened.then(ok => {
      console.log('[BackgroundService]: LocalSend prompt text receiver result=', ok);

      if (ok) _routeLocalSendText({ text: evt.text, fileName: 'message.txt' });
    }).catch(error => {
      console.warn('[BackgroundService]: failed to open text receiver from LocalSend prompt:', error);
    });
  });

  if (AIRelayModule) {
    AIRelayModule.isRelayEnabled().then((enabled: boolean) => {
      _relayEnabled = enabled === true;
    }).catch(() => {});
    AIRelayModule.startInboxListening();

    if (!_relayInboxSub) {
      const inboxEmitter = new NativeEventEmitter(AIRelayModule);
      _relayInboxSub = inboxEmitter.addListener('onInboxFromRelay', (itemsJson: string) => {
        let parsed: { id: string; source: string }[];
        try {
          parsed = JSON.parse(itemsJson);
        } catch (e) {
          console.warn('[BackgroundService]: bad inbox payload:', e);
          return;
        }

        const known = _knownInboxIds;
        _knownInboxIds = new Set(parsed.map(it => it.id));
        if (known && parsed.some(it => it.source === 'llm' && !known.has(it.id))) _settleAiWait();
      });
    }

  }

  _textInserter = new TextInserter(
    undefined,
    (_page, nextTop, _source, isPageChange) => {
      if (FloatingBubbleBridge.isAvailable) {
        if (isPageChange) {
          _pushBubblePageSize(_textInserter);
          const left = _textInserter?.getNextLeft();
          if (left !== undefined) {
            _aiPositionLocked = true;
            FloatingBubbleBridge.showAt(_getBubbleStatusText(), left, nextTop, _activeMode ?? undefined);
            console.log('[BackgroundService]: pageChange showAt left=', left, 'top=', nextTop);
          } else {
            _aiPositionLocked = false;
            FloatingBubbleBridge.show(_getBubbleStatusText(), _activeMode ?? undefined);
            console.log('[BackgroundService]: pageChange show (bubble decides position)');
          }
        }
      }

    },

    (reason, detail) => {
      console.warn('[BackgroundService]: note context changed:', reason, detail);
      FileLogger.logEvent('NoteContextChanged', `${reason}: ${detail}`);
      const prevMode = _activeMode;
      _activeMode = null;
      _settleAiWait();
      _clearPendingCards();
      _pushActiveModes();
      emitInsertMode(null);

      if (reason === 'note_switched' && prevMode && _textInserter && !_inDocument()) {
        console.log('[BackgroundService]: note switched, restarting inserter mode=', prevMode);
        _textInserter.clearQueue();
        _startTextInserter(_textInserter, prevMode).then(ok => {
          if (ok) {
            _activeMode = prevMode;
            _pushActiveModes();
            emitInsertMode(prevMode);
            if (FloatingBubbleBridge.isAvailable) {
              FloatingBubbleBridge.show(_getBubbleStatusText(), prevMode);
            }
          }
        });
      } else if (FloatingBubbleBridge.isAvailable) {
        const msg = reason === 'note_switched'
          ? t('note_switched_stop')
          : t('pages_changed_stop');
        FloatingBubbleBridge.show(msg);
        setTimeout(() => FloatingBubbleBridge.hide(), 3500);
      }
    },
    (waiting, queueLength) => {
      console.log('[BackgroundService]: page continuation gate waiting=', waiting,
        'queue=', queueLength);
      _syncTextBubblePending();
    },
  );

  _attachRelayInsertListeners();

  if (!_lsTextSub) {
    _lsTextSub = LocalSendBridge.onTextReceived((info: TextReceivedInfo) => {
      console.log('[BackgroundService]: onTextReceived(LocalSend) len=', info.text.length);
      _routeLocalSendText(info);
    });
  }

  DeviceEventEmitter.addListener('onLocalSendStopped', () => {
    console.log('[BackgroundService]: WiFi lost – LocalSend server stopped');
    _localSendStarted = false;
  });

  if (FloatingBubbleBridge.isAvailable) {
    const applyBubblePosition = (data: {
      pageY: number; pageX: number;
      pageBottomY: number; bubbleHeight: number;
      screenY: number; screenBottomY: number;
    }, source: 'user-drag' | 'user-tap' | 'initial-layout') => {

      if (_mosaicBubbleOwned()) return;
      const bottomY = (data.pageBottomY > data.pageY) ? data.pageBottomY : data.pageY;
      const insertTop = bottomY + 4;
      const anchor = _textInserter?.setNextInsertionAnchor(insertTop, data.pageX, source);
      _aiPositionLocked = anchor !== undefined;
      console.log('[BackgroundService]: applyBubblePosition source=', source,
        'raw=', JSON.stringify(data), 'accepted=', JSON.stringify(anchor));
    };

    FloatingBubbleBridge.onTap((d) => {
      if (_mosaicBubbleOwned()) {
        console.log('[BackgroundService]: mosaic text bubble tapped, pending=', _mosaicTextBuffer.length);
        _flushMosaicTextCard(d);
        return;
      }
      console.log('[BackgroundService]: text bubble tapped, pending=', _pendingCards.length);
      _aiPositionLocked = false;
      _flushPendingCards(() => applyBubblePosition(d, 'user-tap')).catch(e =>
        console.warn('[BackgroundService]: text bubble tap:', e));
    });

    FloatingBubbleBridge.onLongPress(() => {
      if (_mosaicBubbleOwned()) {
        if (_mosaicTextBuffer.length > 0) {
          _mosaicTextBuffer = [];
          FileLogger.logEvent('MosaicText', 'buffer cleared (long press)');
          _refreshMosaicBubble();
        }
        return;
      }
      const armed = _pendingCards.length > 0
        || (_textInserter?.isWaitingForPageTap() ?? false);
      if (armed) _clearTextReceiveQueueFromBubble();
    });

    FloatingBubbleBridge.onDragEnd((d) => {
      _aiPositionLocked = false;
      applyBubblePosition(d, 'user-drag');
    });
    FloatingBubbleBridge.onLayout((d) => {
      if (_aiPositionLocked) {
        console.log('[BackgroundService]: ignored programmatic bubble layout=', JSON.stringify(d));
        return;
      }
      applyBubblePosition(d, 'initial-layout');
    });

    if (RelayWindowBridge.isAvailable) {
      RelayWindowBridge.onAction(({ actionId }) => {
        console.log('[BackgroundService]: AIRelay window action:', actionId);
        if (actionId === 'lasso_ai') {
          requestAiSelection(_aiSelectionMode)
            .catch(e => console.error('[BackgroundService]: AI selection error:', e));
        } else if (actionId === 'screenshot_ai') {
          void handleScreenshotAi();
        } else if (actionId === 'mode') {
          _aiSelectionModeTouched = true;
          _aiSelectionMode = _aiSelectionMode === 'formula' ? 'text' : 'formula';
          void saveAiSelectionMode(_aiSelectionMode);
          _syncSelectionMode();
          RelayWindowBridge.flash(t(_aiSelectionMode === 'formula' ? 'ai_mode_formula' : 'ai_mode_text'), 2000);
        } else if (actionId === 'mode_text') {
          _useTextMode();
        } else if (actionId === 'reply_failed') {

          clearAiTimeout();
          _aiWaiting = false;
        }
      });
    }

    if (!_nativePanelCloseSub) {
      _nativePanelCloseSub = FloatingToolbarBridge.onNativePanelClose(({
        panel,
        cameFromBubble,
        restoreNoteBubbles,
        screenshotBbox,
      }) => {

        if (panel === 'lassoScreenshot') _aiScreenshotInFlight = false;
        const shouldRestoreNoteBubbles = restoreNoteBubbles ?? cameFromBubble;
        console.log('[BackgroundService]: onNativePanelClose panel=', panel,
          'fromBubble=', cameFromBubble, 'restoreNoteBubbles=', shouldRestoreNoteBubbles);

        if (panel === 'airRelay' && _aiActive) stopAiMode();

        if (panel === 'lassoScreenshot' && screenshotBbox) {
          _pendingScreenshotAnchor = { top: screenshotBbox.bottom + AI_REPLY_GAP_PX, left: screenshotBbox.left };
          _aiWaiting = true;
          RelayWindowBridge.setStatus(t('bubble_ai_waiting'));
          _armAiReplyTimeout('ScreenshotAi');
          FileLogger.logEvent('ScreenshotAi', 'submitted, waiting for reply');
        }
        if (shouldRestoreNoteBubbles) setTimeout(restoreBubbleAfterLasso, 300);
      });
    }

    if (!_noteForegroundSub) {
      _noteForegroundSub = FloatingToolbarBridge.onNoteForegroundChanged(({ inNote }) => {
        console.log('[BackgroundService]: onNoteForegroundChanged inNote=', inNote);
        if (inNote) {

          _textInserter?.holdInserts(1500);
        }
      });
    }

    if (!_mosaicSurfaceSub) {
      _mosaicSurfaceSub = FloatingToolbarBridge.onMosaicBoardVisible((state) => {
        const surface = state.surface ?? (state.visible ? FloatingToolbarBridge.getMosaicSurface() : 'host');
        console.log('[BackgroundService]: onMosaicBoardVisible visible=', state.visible,
          'surface=', surface, 'noteRef=', state.noteRef ?? null);
        if (state.visible !== _lastMosaicVisible) {
          _lastMosaicVisible = state.visible;
          _closeSurfaceModes(state.visible ? 'enter' : 'leave');
        }
        _adoptTextModeForSurface({ ...state, surface });
        _syncHostContext(`mosaic surface=${state.visible ? surface : 'host'}`);
      });
    }

    if (!_floatsClosedSub) {
      _floatsClosedSub = FloatingToolbarBridge.onFloatsClosed(({ reason, ai }) => {
        console.log('[BackgroundService]: floats closed reason=', reason, 'ai=', ai);
        _closeSurfaceModes(reason ?? 'floats closed', ai !== false);
      });
    }

    if (!_hostAppModeSub) {
      _hostAppModeSub = FloatingToolbarBridge.onHostAppModeChanged(({ document, reason }) => {
        _hostDocument = document === true;
        _syncHostContext(`app mode: ${reason ?? ''}`);
      });
    }

    _hostDocument = FloatingToolbarBridge.isDocumentMode();
    const mosaicVisible = FloatingToolbarBridge.isMosaicBoardVisible();
    _lastMosaicVisible = mosaicVisible;
    _adoptTextModeForSurface({
      visible: mosaicVisible,
      surface: mosaicVisible ? FloatingToolbarBridge.getMosaicSurface() : 'host',
      noteRef: mosaicVisible ? FloatingToolbarBridge.getMosaicNoteRef() : null,
    });
    _syncHostContext('init');
  }

  void loadAiSelectionMode().then(async mode => {
    if (!_aiSelectionModeTouched) _aiSelectionMode = mode;
    if (_aiSelectionMode === 'formula' && await _webBackend()) _useTextMode();
    else _syncSelectionMode();
  });

  FileLogger.logEvent('BackgroundInit', 'ready');
}

export function getActiveMode(): InsertMode | null {
  return _activeMode;
}

export function getLastMode(): InsertMode {
  return _lastMode;
}

export async function toggleMode(target: InsertMode): Promise<InsertMode | null> {
  ensureInit();
  try {
    return await _toggleMode(target);
  } finally {

    _pushActiveModes();
  }
}

export function stopTextReceive(): void {
  ensureInit();
  ++_switchVersion;
  stopMosaicTextMode();
  stopMode();
}

async function _toggleMode(target: InsertMode): Promise<InsertMode | null> {

  if (_isMosaicVisible()) {
    return (await toggleMosaicTextMode()) ? target : null;
  }
  if (_inDocument()) {
    try { HostDialog.tip(t('doc_text_receive_unsupported')); } catch (_) {}
    return null;
  }
  if (!_textInserter) return null;

  const cur = _activeMode;

  const isActuallyRunning = _textInserter.isRunning() || _textInserter.isPaused();
  if (cur === null || !isActuallyRunning) {
    await switchToMode(target);
  } else if (cur === target) {
    stopMode();
  } else {
    await switchToMode(target);
  }

  FileLogger.logEvent('ToggleMode', `target=${target} result=${_activeMode}`);
  return _activeMode;
}

async function startMode(mode: InsertMode): Promise<boolean> {
  _lastMode = mode;
  saveLastMode(mode);
  return switchToMode(mode);
}

export function stopMode(): void {
  if (_textInserter) {
    const cur = _textInserter.getMode();
    if (cur) { _lastMode = cur; saveLastMode(cur); }
    _textInserter.clearQueue();
    _textInserter.stop(true);
  }
  _activeMode = null;
  _aiPositionLocked = false;
  _clearPendingCards();
  if (FloatingBubbleBridge.isAvailable) {
    FloatingBubbleBridge.hide();
  }
  _pushActiveModes();
  emitInsertMode(null);
}

export async function startAiReceiveMode(): Promise<void> {
  ensureInit();
  await _ensureRelayWindow(false);
}

export function stopAiMode(): void {
  ++_aiSelectionEpoch;
  PenLasso.cancel();
  _aiSelectionBusy = false;
  _aiScreenshotInFlight = false;
  _aiActive = false;
  _aiWaiting = false;
  _aiPositionLocked = false;
  _pendingScreenshotAnchor = null;
  AIRelayModule.sendFormulaSessionReset();
  clearAiTimeout();
  _relayEnabled = false;
  AIRelayModule.setRelayEnabled(false).catch(() => {});
  RelayWindowBridge.close();

  stopMode();
  _pushActiveModes();
}

export function stopAllModes(): void {
  stopAiMode();
}

async function switchToMode(mode: InsertMode): Promise<boolean> {
  ensureInit();
  if (!_textInserter) return false;
  if (_inDocument()) {
    FileLogger.logEvent('SwitchModeRejected', `mode=${mode} in document`);
    return false;
  }
  if (!await _ensureTextReceiverRuntime()) {
    stopMode();
    return false;
  }

  const myVersion = ++_switchVersion;
  const previousMode = _lastMode;
  _lastMode = mode;
  saveLastMode(mode);

  if (_textInserter.isPaused()) {
    _textInserter.resume();
  }

  if (_textInserter.isRunning()) {
    const ok = _textInserter.switchMode(mode);
    if (ok) {
      _activeMode = mode;
      _pushActiveModes();
      emitInsertMode(mode);
      return true;
    }
  }

  _textInserter.clearQueue();
  const ok = await _textInserter.start(mode, true);
  if (myVersion !== _switchVersion) return false;
  if (ok) {
    _textInserter.applyModeSwitchGap(previousMode, mode);
    _activeMode = mode;
    if (FloatingBubbleBridge.isAvailable) {
      _pushBubblePageSize(_textInserter);
    }
  } else {
    let retryOk = false;
    const retryDelays = [800, 2000];
    for (const delay of retryDelays) {
      await new Promise<void>(r => setTimeout(r, delay));
      if (myVersion !== _switchVersion) return false;
      if (_textInserter.isRunning()) { retryOk = true; break; }
      console.log('[BackgroundService]: retrying start, delay=', delay);
      FileLogger.logEvent('StartRetry', `mode=${mode} delay=${delay}`);
      retryOk = await _textInserter.start(mode, true);
      if (myVersion !== _switchVersion) return false;
      if (retryOk) break;
    }
    if (myVersion !== _switchVersion) return false;
    if (retryOk) {
      _textInserter.applyModeSwitchGap(previousMode, mode);
      _activeMode = mode;
      if (FloatingBubbleBridge.isAvailable) {
        _pushBubblePageSize(_textInserter);
      }
    } else {
      _activeMode = null;
    }
  }
  _pushActiveModes();
  emitInsertMode(_activeMode);

  if (_activeMode && FloatingBubbleBridge.isAvailable) {
    FloatingBubbleBridge.show(_getBubbleStatusText(), _activeMode);
  }

  return ok;
}

export async function requestAiSelection(mode: AiSelectionMode = _aiSelectionMode): Promise<void> {
  ensureInit();
  if (_aiSelectionBusy || _aiScreenshotInFlight || _aiWaiting) {
    console.log('[BackgroundService]: AI selection skipped, busy=', _aiSelectionBusy,
      'screenshot=', _aiScreenshotInFlight, 'waiting=', _aiWaiting);
    return;
  }
  if (!await _ensureRelayWindow(true)) return;
  if (mode === 'formula' && await _webBackend()) {
    _useTextMode();
    mode = 'text';
  }
  _aiSelectionBusy = true;
  _pendingScreenshotAnchor = null;
  const epoch = ++_aiSelectionEpoch;
  let hidden = false;
  const canContinue = () => _aiSelectionEpoch === epoch && _relayEnabled;
  try {

    if (_inDocument() && FloatingToolbarBridge.isDocTextSelected() && await _stageDocSelectedText()) return;
    if (mode === 'formula' && _inDocument()) {
      RelayWindowBridge.flash(t('bubble_doc_formula_unsupported'), 2500);
      return;
    }
    if (_isMosaicVisible()) {

      if (mode === 'text') await handleAiSend({ canContinue });
      else RelayWindowBridge.flash(t('ai_selection_retry'), 2500);
      return;
    }
    const page = await readSelectionPage();
    if (!canContinue()) return;
    if (page === 'no_page') { showHostNoPageHint(); return; }
    if (!page) { RelayWindowBridge.flash(t('ai_selection_retry'), 2500); return; }
    const selected = await hasSelectedContent();
    const request: AiSelectionRequest = { expectedPage: page, canContinue };
    if (!await selectionRequestIsCurrent(request)) return;
    if (selected === 'no_page') { showHostNoPageHint(); return; }
    if (!selected) {
      hidden = true;
      RelayWindowBridge.setAside(true);
      FloatingBubbleBridge.hide();
      PaletteBubbleBridge.hideTemporarily();
      const result = await PenLasso.arm({ page });
      if (!canContinue()) return;
      restoreBubbleAfterLasso();
      hidden = false;
      if (result.status !== 'selected') {
        if (result.status === 'no_page') showHostNoPageHint();
        else if (result.status !== 'cancelled') RelayWindowBridge.flash(t('ai_selection_retry'), 2500);
        return;
      }
    }
    if (!await selectionRequestIsCurrent(request)) return;
    if (mode === 'formula') await handleFormulaSend(request);
    else await handleAiSend(request);
  } catch (e) {
    FileLogger.logEvent('AiSelection', `request=${epoch} ${String(e)}`);
    if (canContinue()) RelayWindowBridge.flash(t('ai_selection_retry'), 2500);
  } finally {
    if (_aiSelectionEpoch === epoch) _aiSelectionBusy = false;

    if (hidden) {
      PaletteBubbleBridge.restore();
      RelayWindowBridge.setAside(false);
    }
  }
}

async function _stageDocSelectedText(): Promise<boolean> {
  let text = '';
  try {
    const selection: any = await PluginDocAPI.getLastSelectedText();
    if (selection?.success && typeof selection.result === 'string') text = selection.result;
  } catch (e) {
    console.warn('[BackgroundService]: doc selected text read failed:', e);
  }
  if (!text.trim()) return false;
  let staged = false;
  try { staged = await AIRelayModule.stageText(text); } catch (_) {}
  FileLogger.logEvent('SendToAI', staged
    ? `${text.length} doc chars staged in AIRelay window`
    : `${text.length} doc chars dropped: no AIRelay window`);
  return true;
}

async function handleAiSend(request?: AiSelectionRequest): Promise<void> {
  ensureInit();
  console.log('[BackgroundService]: ── handleAiSend ──');
  const onProgress = (stage: ExtractStage) => {
    if (stage === 'recognizing') RelayWindowBridge.setStatus(t('bubble_ai_recognizing'));
  };

  try {
    if (!await selectionRequestIsCurrent(request)) return;
    let text: string;
    const mosaicBoard = _isMosaicVisible();
    if (mosaicBoard) {

      const { extractMosaicLassoText } = require('./ToolActions');
      text = (await extractMosaicLassoText(onProgress)) ?? '';
    } else {
      text = (await LassoExtractor.extract(onProgress)).text ?? '';
    }
    RelayWindowBridge.setStatus('');
    if (!await selectionRequestIsCurrent(request)) return;
    if (!text) {
      FileLogger.logEvent('AiSendAbort', 'no text');
      RelayWindowBridge.flash(t('bubble_ai_no_lasso'), 2500);
      return;
    }

    if (mosaicBoard) {
      const { clearMosaicLassoSelection } = require('./ToolActions');
      clearMosaicLassoSelection();
    } else {
      try {
        await Promise.race([
          PluginCommAPI.setLassoBoxState(2),
          new Promise((_, reject) => setTimeout(() => reject(new Error('setLassoBoxState timeout')), 3000)),
        ]);
        console.log('[BackgroundService]: lasso selection cleared');
      } catch (e) {
        console.warn('[BackgroundService]: setLassoBoxState failed (non-fatal):', e);
      }
    }

    let staged = false;
    try { staged = await AIRelayModule.stageText(text); } catch (_) {}
    FileLogger.logEvent('SendToAI', staged
      ? `${text.length} chars staged in AIRelay window`
      : `${text.length} chars dropped: no AIRelay window`);
  } catch (e) {
    RelayWindowBridge.setStatus('');
    console.error('[BackgroundService]: handleAiSend error:', e);
    FileLogger.logEvent('AiSendError', String(e));
  }
}

async function handleFormulaSend(request?: AiSelectionRequest): Promise<void> {
  ensureInit();
  reviveBridge();

  try {
    RelayWindowBridge.setStatus(t('bubble_ai_sending'));
    if (!await selectionRequestIsCurrent(request)) {
      RelayWindowBridge.setStatus('');
      return;
    }
    const res = await sendFormulaQuery(request);
    if (res === 'ok') {
      _aiWaiting = true;
      RelayWindowBridge.setStatus(t('bubble_ai_waiting'));
      _armAiReplyTimeout('FormulaSend');
    } else if (res === 'staged') {
      RelayWindowBridge.setStatus('');
    } else if (res === 'no-lasso') {
      RelayWindowBridge.flash(t('bubble_ai_no_lasso'), 2500);
    } else if (res === 'no-page') {
      showHostNoPageHint();
      RelayWindowBridge.setStatus('');
    } else if (res === 'no-relay') {
      try { HostDialog.tip(t('formula_no_relay')); } catch (_) {}
      RelayWindowBridge.setStatus('');
    } else {
      RelayWindowBridge.flash(t('bubble_formula_failed'), 2500);
    }
  } catch (e) {
    RelayWindowBridge.setStatus('');
    console.error('[BackgroundService]: handleFormulaSend error:', e);
    FileLogger.logEvent('FormulaSend', `error: ${String(e)}`);
  }
}

async function _startLocalSendOnce(gen: number): Promise<boolean> {
  if (_localSendStarted) return true;
  try {
    const status = await LocalSendBridge.getServerStatus();
    if (status?.running) {
      if (gen !== _localSendGen) return false;
      _localSendStarted = true;
      return true;
    }
    const canWrite = await FloatingToolbarBridge.requestFileWritePermission();
    if (!canWrite) {
      HostDialog.tip(t('perm_required'));
      return false;
    }
    const canNet = await FloatingToolbarBridge.requestInternetPermission();
    if (!canNet) {
      HostDialog.tip(t('perm_required'));
      return false;
    }
    if (gen !== _localSendGen) return false;
    try {
      await LocalSendBridge.startServer({
        alias: 'Supernote', port: 53317,
        dest: '/sdcard/INBOX', pin: '',
      });
      if (gen !== _localSendGen) {
        try { await LocalSendBridge.stopServer(); } catch (_) {}
        return false;
      }
      _localSendStarted = true;
      console.log('[BackgroundService]: LocalSend server started');
      return true;
    } catch (startErr) {
      console.warn('[BackgroundService]: startLocalSend: LocalSend start failed:', startErr);
      HostDialog.tip(t('perm_required'));
      return false;
    }
  } catch (e) {
    console.warn('[BackgroundService]: startLocalSend error:', e);
    return false;
  }
}

export async function startLocalSend(showNetworkTip = true): Promise<boolean> {
  const gen = _localSendGen;
  const wifi = await LocalSendBridge.isWifiConnected().catch(() => false);
  if (gen !== _localSendGen) return false;
  if (!wifi) {
    _localSendStarted = false;
    if (showNetworkTip) HostDialog.tip(t('no_wifi'));
    return false;
  }
  if (_localSendStarted) return true;
  if (_localSendStartPromise) return _localSendStartPromise;
  const attempt = _startLocalSendOnce(gen);
  _localSendStartPromise = attempt;
  try {
    return await attempt;
  } finally {
    if (_localSendStartPromise === attempt) _localSendStartPromise = null;
  }
}

export async function stopLocalSend(): Promise<void> {
  _localSendGen += 1;
  _localSendStarted = false;
  _localSendStartPromise = null;
  try { await LocalSendBridge.stopServer(); } catch (_) {}
  console.log('[BackgroundService]: LocalSend server stopped');
}

export function isLocalSendRunning(): boolean {
  return _localSendStarted;
}

export async function flushPendingTexts(): Promise<number> {
  if (!_textInserter) return 0;
  try {
    let count = 0;
    const localSendTexts: TextReceivedInfo[] = await LocalSendBridge.flushPendingTexts().catch(() => []);
    if (localSendTexts && localSendTexts.length > 0) {
      for (const info of localSendTexts) {
        _routeLocalSendText(info);
      }
      FileLogger.logEvent('FlushPendingLocalSend', `count=${localSendTexts.length}`);
      count += localSendTexts.length;
    }
    return count;
  } catch (e) {
    console.warn('[BackgroundService]: flushPendingTexts error:', e);
    return 0;
  }
}

export async function reviveIfNeeded(): Promise<void> {
  _attachRelayInsertListeners();
  if (!_textInserter || !_activeMode || _inDocument()) return;
  if (!_textInserter.hasLiveTimers()) {
    const mode = _activeMode;
    _textInserter.clearQueue();
    const ok = await _startTextInserter(_textInserter, mode, true);
    if (ok) {
      _pushActiveModes();
      emitInsertMode(_activeMode);
    } else {

      console.warn('[BackgroundService]: reviveIfNeeded start() failed, keeping mode=', mode);
      FileLogger.logEvent('ReviveFailed', `mode=${mode}`);
    }
  }
}

export async function handleScreenshotAi(): Promise<void> {
  ensureInit();
  if (_aiSelectionBusy || _aiWaiting || _aiScreenshotInFlight) return;
  if (!await _ensureRelayWindow(true)) return;
  _aiScreenshotInFlight = true;
  console.log('[BackgroundService] handleScreenshotAi START (live page path)');

  FloatingToolbarBridge.showLassoScreenshotPanelFromBubble();
  FileLogger.logEvent('ScreenshotAi', 'native panel invoked');
}

function restoreBubbleAfterLasso(): void {
  PaletteBubbleBridge.restore();
  RelayWindowBridge.setAside(false);
  if (_activeMode && FloatingBubbleBridge.isAvailable) {
    FloatingBubbleBridge.show(_getBubbleStatusText(), _activeMode);
  }
}
