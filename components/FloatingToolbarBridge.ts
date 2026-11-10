import { NativeModules, NativeEventEmitter } from 'react-native';

function getFT() {
  return NativeModules.FloatingToolbar;
}

export const ENABLE_DEBUG: boolean =
  getFT()?.getConstants?.()?.ENABLE_DEBUG ??
  getFT()?.ENABLE_DEBUG ??

  Boolean(typeof globalThis !== 'undefined' && (globalThis as { __DEV__?: unknown }).__DEV__);

let pendingButtonId: number | null = null;
let appMounted = false;
let lastHostButtonEventAt = 0;
let lastHostButtonHandledAt = 0;

export function setPendingButton(id: number | null): void {
  pendingButtonId = id;
}

export function checkPendingButton(): number | null {
  const value = pendingButtonId;
  pendingButtonId = null;
  return value;
}

export function peekPendingButton(): number | null {
  return pendingButtonId;
}

export function setAppMounted(value: boolean): void {
  appMounted = value;
}

export function isAppMounted(): boolean {
  return appMounted;
}

export function markHostButtonEvent(id?: unknown): void {
  lastHostButtonEventAt = Date.now();
}

export function getLastHostButtonEventAt(): number {
  return lastHostButtonEventAt;
}

export function markHostButtonHandled(): void {
  lastHostButtonHandledAt = Date.now();
}

export function getLastHostButtonHandledAt(): number {
  return lastHostButtonHandledAt;
}

interface ToolTapEvent {
  toolId: string;
  toolAction: string;
  toolName: string;
}

export type Subscription = { remove(): void };

export interface MosaicBoardState {
  visible: boolean;
  surface?: string;
  noteRef?: string | null;
}

let _emitter: NativeEventEmitter | null = null;

function getEmitter(): NativeEventEmitter | null {
  const mod = getFT();
  if (!mod) return null;
  if (!_emitter) {
    try {
      _emitter = new NativeEventEmitter(mod);
    } catch (e) {
      if (ENABLE_DEBUG) console.warn('[FloatingToolbarBridge]: NativeEventEmitter unavailable:', e);
      return null;
    }
  }
  return _emitter;
}

const FloatingToolbarBridge = {

  get isAvailable(): boolean {
    const mod = getFT();
    return !!mod && typeof mod.toggleFromPluginButton === 'function';
  },

  showCurrent(): void {
    try {
      getFT()?.showCurrent();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showCurrent failed:', e);
    }
  },

  isSessionActiveSync(): boolean {
    return getFT()?.isSessionActiveSync?.() ?? false;
  },

  toggleFromPluginButton(mode?: 'note_notipal' | 'doc_notipal'): boolean {
    try {
      const mod = getFT();
      if (!mod || typeof mod.toggleFromPluginButton !== 'function') {
        if (ENABLE_DEBUG) console.warn('[FloatingToolbarBridge]: native module unavailable for toggleFromPluginButton');
        return false;
      }
      mod.toggleFromPluginButton(mode ?? null);
      return true;
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: toggleFromPluginButton failed:', e);
      return false;
    }
  },

  requestClosePluginView(): boolean {
    try {
      const mod = getFT();
      if (!mod || typeof mod.requestClosePluginView !== 'function') return false;
      mod.requestClosePluginView();
      return true;
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: requestClosePluginView failed:', e);
      return false;
    }
  },

  async inspectAndFlushHostEntry(): Promise<'none' | 'config' | 'flushed'> {
    try {
      const result = await getFT()?.inspectAndFlushHostEntry();
      return result === 'config' || result === 'flushed' ? result : 'none';
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: inspectAndFlushHostEntry failed:', e);
      return 'none';
    }
  },

  reportHostButtonChannel(working: boolean): void {
    try {
      getFT()?.reportHostButtonChannel(working);
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: reportHostButtonChannel failed:', e);
    }
  },

  reportHostButtonRaw(payload: string): void {
    try { getFT()?.reportHostButtonRaw?.(payload); } catch (_) {}
  },

  hide(): void {
    try {
      getFT()?.hide();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: hide failed:', e);
    }
  },

  restoreToolbar(): void {
    try {
      getFT()?.restoreToolbar();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: restoreToolbar failed:', e);
    }
  },

  beginPaletteApply(): void {
    try {
      getFT()?.beginPaletteApply?.();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: beginPaletteApply failed:', e);
    }
  },

  async hasPendingDocSticker(): Promise<boolean> {
    return (await getFT()?.hasPendingDocSticker?.()) === true;
  },

  clearPendingDocSticker(): void {
    try { getFT()?.clearPendingDocSticker?.(); } catch (_) {}
  },

  finishPaletteApply(): void {
    try {
      getFT()?.finishPaletteApply?.();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: finishPaletteApply failed:', e);
    }
  },

  paletteApplyFinished(): void {
    try {
      getFT()?.paletteApplyFinished();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: paletteApplyFinished failed:', e);
    }
  },

  parkToolbarForLasso(holdForSelection: boolean): void {
    try {
      getFT()?.parkToolbarForLasso(holdForSelection);
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: parkToolbarForLasso failed:', e);
    }
  },

  parkToolbarForPastedClip(holdForSelection: boolean): void {
    try {
      getFT()?.parkToolbarForPastedClip(holdForSelection);
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: parkToolbarForPastedClip failed:', e);
    }
  },

  isShowingSync(): boolean {
    try {
      return getFT()?.isShowingSync() ?? false;
    } catch { return false; }
  },

  checkPendingOpenMainSync(): boolean {
    try {
      return getFT()?.checkPendingOpenMainSync() ?? false;
    } catch { return false; }
  },

  ackOpenMain(): void {
    try { getFT()?.ackOpenMain(); } catch (_) {}
  },

  getPendingScreenSync(): string {
    try { return getFT()?.getPendingScreenSync() ?? ''; } catch { return ''; }
  },

  ackPendingScreen(): void {
    try { getFT()?.ackPendingScreen(); } catch (_) {}
  },

  async deleteQueueFile(path: string): Promise<boolean> {
    try { return await getFT()?.deleteQueueFile(path) ?? false; } catch { return false; }
  },

  async prepareImageForInsertion(path: string): Promise<string> {
    const prepared = await getFT()?.prepareImageForInsertion(path);
    if (!prepared) throw new Error('Image preparation returned no path');
    return prepared;
  },

  imageInsertFinished(requestId: string, path: string, success: boolean): void {
    getFT()?.imageInsertFinished(requestId, path, success);
  },

  setLocale(loc: 'zh' | 'en'): void {
    try { getFT()?.setLocale(loc); } catch (_) {}
  },

  setPluginVersion(versionName: string, versionCode: string): void {
    try { getFT()?.setPluginVersion?.(versionName, versionCode); } catch (_) {}
  },

  openPanel(screen: string): void {
    try { getFT()?.openPanel(screen); } catch (_) {}
  },

  async requestFileReadPermission(): Promise<boolean> {
    try {
      return await getFT()?.requestFileReadPermission?.() ?? false;
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: requestFileReadPermission failed:', e);
      return false;
    }
  },

  async readTextFile(path: string): Promise<string | null | undefined> {
    const read = getFT()?.readTextFile;
    if (!read) return undefined;
    return await read(path);
  },

  async requestFileWritePermission(): Promise<boolean> {
    try {
      return await getFT()?.requestFileWritePermission?.() ?? false;
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: requestFileWritePermission failed:', e);
      return false;
    }
  },

  async requestFileDeletePermission(): Promise<boolean> {
    try {
      return await getFT()?.requestFileDeletePermission?.() ?? false;
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: requestFileDeletePermission failed:', e);
      return false;
    }
  },

  async requestInternetPermission(): Promise<boolean> {
    try {
      return await getFT()?.requestInternetPermission?.() ?? false;
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: requestInternetPermission failed:', e);
      return false;
    }
  },

  setLassoData(text: string, imagePathsJson: string, linkedFilesJson?: string): void {
    try {
      getFT()?.setLassoData(text, imagePathsJson, linkedFilesJson ?? '[]');
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: setLassoData failed:', e);
    }
  },

  docLinkInsertFinished(requestId: string, selectionReady: boolean, targetFile: string, targetPage: number): void {
    getFT()?.docLinkInsertFinished(requestId, selectionReady, targetFile, targetPage);
  },

  showDocLinkPanel(currentFilePath?: string | null): void {
    try {
      getFT()?.showDocLinkPanel(currentFilePath ?? null);
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showDocLinkPanel failed:', e);
    }
  },

  showPalettePanel(infoJson: string): void {
    try {
      getFT()?.showPalettePanel(infoJson);
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showPalettePanel failed:', e);
    }
  },

  handleDocScreenshot(): void {
    try {
      getFT()?.handleDocScreenshot?.();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: handleDocScreenshot failed:', e);
    }
  },

  isMosaicBoardVisible(): boolean {
    try { return getFT()?.isMosaicBoardVisible?.() === true; } catch { return false; }
  },

  getMosaicSurface(): string {
    try { return getFT()?.getMosaicSurface() ?? ''; } catch { return ''; }
  },

  isDocumentMode(): boolean {
    try { return getFT()?.isDocumentMode() === true; } catch { return false; }
  },

  isDocTextSelected(): boolean {
    try { return getFT()?.isDocTextSelected?.() === true; } catch { return false; }
  },

  getMosaicNoteRef(): string | null {
    try {
      const value = getFT()?.getMosaicNoteRef?.();
      return typeof value === 'string' && value.length > 0 ? value : null;
    } catch { return null; }
  },

  async enqueueMosaicImage(path: string, source: string): Promise<boolean> {
    try { return (await getFT()?.enqueueMosaicImage?.(path, source)) === true; }
    catch (e) { console.warn('[FloatingToolbarBridge]: enqueueMosaicImage failed:', e); return false; }
  },

  requestMosaicTextCard(text: string, anchorScreenX: number, anchorScreenY: number): void {
    try { getFT()?.requestMosaicTextCard?.(text, Math.round(anchorScreenX), Math.round(anchorScreenY)); }
    catch (e) { console.warn('[FloatingToolbarBridge]: requestMosaicTextCard failed:', e); }
  },

  toggleScreenshotBubble(mode?: 'note_notipal' | 'doc_notipal'): void {
    try {
      getFT()?.toggleScreenshotBubble?.(mode ?? null);
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: toggleScreenshotBubble failed:', e);
    }
  },

  showDocTextSendPanel(text: string): void {
    try {
      getFT()?.showDocTextSendPanel(text);
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showDocTextSendPanel failed:', e);
    }
  },

  showSendPanelFromBubble(): void {
    try {
      getFT()?.showSendPanelFromBubble();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showSendPanelFromBubble failed:', e);
    }
  },

  openSendPanelClipboardSync(): void {
    try {
      getFT()?.openSendPanelClipboardSync();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: openSendPanelClipboardSync failed:', e);
    }
  },

  showLassoScreenshotPanelFromBubble(): void {
    try {
      getFT()?.showLassoScreenshotPanelFromBubble();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showLassoScreenshotPanelFromBubble failed:', e);
    }
  },

  isHostLassoMenuOpen(): boolean {
    try { return getFT()?.isHostLassoMenuOpen?.() === true; } catch { return false; }
  },

  releaseStaleLassoMenu(reason: string): void {
    try { getFT()?.releaseStaleLassoMenu?.(reason); } catch (e) {
      console.warn('[FloatingToolbarBridge]: releaseStaleLassoMenu failed:', e);
    }
  },

  beginBusyProgress(kind: 'paste' | 'palette', delayMs: number): void {
    try { getFT()?.beginBusyProgress?.(kind, delayMs); } catch (e) {
      console.warn('[FloatingToolbarBridge]: beginBusyProgress failed:', e);
    }
  },

  endBusyProgress(): void {
    try { getFT()?.endBusyProgress?.(); } catch (e) {
      console.warn('[FloatingToolbarBridge]: endBusyProgress failed:', e);
    }
  },

  showSmartLassoCapture(): void {
    try {
      getFT()?.showSmartLassoCapture?.();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showSmartLassoCapture failed:', e);
    }
  },

  onSmartLassoSelectionShown(callback: (e: { request: number }) => void): Subscription {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onSmartLassoSelectionShown', callback);
  },

  setSmartLassoSelectionKind(request: number, sendsText: boolean): void {
    try { getFT()?.setSmartLassoSelectionKind?.(request, sendsText); } catch (e) {
      console.warn('[FloatingToolbarBridge]: setSmartLassoSelectionKind failed:', e);
    }
  },
  onToolModeExit(
    cb: (e: { toolId: string; toolAction: string }) => void
  ): Subscription {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onToolModeExit', cb);
  },

  setActiveModes(modeIds: string[]): void {
    try {
      getFT()?.setActiveModes(JSON.stringify(modeIds));
    } catch (_) {}
  },

  onToolTap(callback: (event: ToolTapEvent) => void): Subscription {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onToolTap', callback);
  },

  onToolLongPress(callback: (event: { toolId: string; toolName: string }) => void): Subscription {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onToolLongPress', callback);
  },

  onToolbarOpenMain(callback: () => void): Subscription {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onToolbarOpenMain', () => callback());
  },

  onNativePanelClose(
    callback: (data: {
      panel: string;
      cameFromBubble: boolean;
      restoreNoteBubbles?: boolean;
      screenshotBbox?: { left: number; top: number; right: number; bottom: number };
      detailId?: string;
    }) => void
  ): Subscription {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onNativePanelClose', callback);
  },

  onNoteForegroundChanged(callback: (data: { inNote: boolean }) => void): Subscription {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onNoteForegroundChanged', callback);
  },

  onHostAppModeChanged(callback: (data: { document: boolean; reason?: string }) => void): Subscription {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onHostAppModeChanged', callback);
  },

  onMosaicBoardVisible(callback: (data: MosaicBoardState) => void): Subscription {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onMosaicBoardVisible', callback);
  },

  onFloatsClosed(callback: (data: { reason?: string; ai?: boolean }) => void): Subscription {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onFloatsClosed', callback);
  },

  onDestroyAll(callback: () => void): Subscription {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onToolbarDestroyAll', () => callback());
  },

  updateTitleClips(filled: boolean[]): void {
    try { getFT()?.updateTitleClips(JSON.stringify(filled)); } catch (_) {}
  },

  onTitleClipTap(callback: (event: { slot: string }) => void): Subscription {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onTitleClipTap', callback);
  },

  onTitleClipLongPress(callback: (event: { slot: string }) => void): Subscription {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onTitleClipLongPress', callback);
  },

  onClipSlotClear(callback: (event: { slot: string }) => void): Subscription {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onClipSlotClear', callback);
  },

  beginPenLassoSelection(requestId: string): void {
    const ft = getFT();
    if (!ft?.beginPenLassoSelection) throw new Error('Pen selection bridge unavailable');
    ft.beginPenLassoSelection(requestId);
  },

  async finishPenLassoSelection(requestId: string): Promise<boolean> {
    return (await getFT()?.finishPenLassoSelection?.(requestId)) === true;
  },

  cancelPenLassoSelection(requestId: string): void {
    getFT()?.cancelPenLassoSelection?.(requestId);
  },

  onPenLassoBbox(callback: (event: { left: number; top: number; right: number; bottom: number; requestId?: string }) => void): Subscription {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onPenLassoBbox', callback);
  },

  onPenLassoCancel(callback: (event?: { requestId?: string }) => void): Subscription {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onPenLassoCancel', callback);
  },

};

export default FloatingToolbarBridge;
