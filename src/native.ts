import { DeviceEventEmitter, NativeModules } from 'react-native';

export function nativeModule<T>(name: string): T {
  return NativeModules[name] as T;
}

export function hasNativeModule(name: string): boolean {
  return NativeModules[name] != null;
}

export type BuildFlags = { devBuild: boolean; debug: boolean };

export interface NotipalLogSpec {
  append(lines: string[]): void;
  buildFlags(): BuildFlags;
}
export const notipalLog = nativeModule<NotipalLogSpec>('NotipalLog');

export interface NotipalCommonSpec {
  delay(ms: number): Promise<void>;
  loadPresets(): Promise<string | null>;
  savePresets(json: string): Promise<void>;
  setLocale(locale: string): void;
  setPluginVersion(versionName: string, versionCode: string): void;

  readTextFile(path: string): Promise<string | null>;
  cropRegion(
    srcPath: string,
    refW: number,
    refH: number,
    left: number,
    top: number,
    right: number,
    bottom: number,
    outPath: string,
  ): Promise<string>;
}
export const notipalCommon = nativeModule<NotipalCommonSpec>('NotipalCommon');

export type HostStatePayload = {

  surface: 'note' | 'document' | 'mosaic' | 'other';

  document: boolean;

  inHostApp: boolean;
  pluginViewOpen: boolean;
  permissionsOk: boolean;
  restoring: boolean;
  lassoMenuOpen: boolean;

  docTextSelected: boolean;

  docLassoSelected: boolean;

  mosaicSurface: string;
  mosaicNoteRef: string | null;
};

export type PermissionKind = 'file_read' | 'file_write' | 'file_delete' | 'internet';

export interface HostSpec {
  getState(): HostStatePayload;
  requestPermission(kind: PermissionKind): Promise<boolean>;

  inspectAndFlushHostEntry(): Promise<'config' | 'flushed' | 'none'>;
  reportHostButtonChannel(working: boolean): void;

  releaseStaleLassoMenu(reason: string): void;

  noteForegroundElapsedMs(): number;

  tip(text: string): void;

  confirm(text: string, cancelText: string, confirmText: string): Promise<boolean>;
}
export const notipalHost = nativeModule<HostSpec>('NotipalHost');

export interface NotipalMosaicSpec {

  pasteStrokes(): void;
  clearSelection(deleteStrokes: boolean): void;
  textCard(text: string, anchorScreenX: number, anchorScreenY: number): void;

  finishSketchClipboard(outcome: string): void;
}
export const notipalMosaic = nativeModule<NotipalMosaicSpec>('NotipalMosaic');

export type ClipboardDirs = {

  clipboardDir: string;

  mosaicDir: string;
};

export type ClipSlotInfo = { slot: string; filled: boolean; thumb: string | null };

export type PageRect = { left: number; top: number; right: number; bottom: number };

export type StickerContext = {
  path: string;
  pageWidth: number;
  pageHeight: number;
  machineType: number;
  runtimeDeviceType: number;
};

export type ContourContext = StickerContext & { displayWidth: number; displayHeight: number };

export type StickerElementMeta = { uuid: string; maxX: number; maxY: number; layerNum: number };

export type ContourPrep = { processed: number; clearedIndexes: number[]; contourElements: number };

export type SidecarExport = { success: boolean; count: number; empty?: number; elapsedMs: number; rect: PageRect };

export interface NotipalClipboardSpec {

  dirs(): ClipboardDirs;

  pickSlot(slots: ClipSlotInfo[], mode: 'save' | 'paste'): Promise<string | null>;

  hasHostLasso(): Promise<boolean>;

  observedLassoPageRect(pageWidth: number, pageHeight: number): Promise<PageRect | null>;

  resizeInsertedSticker(rect: PageRect): Promise<boolean>;

  clearContours(uuids: string[], context: ContourContext): Promise<ContourPrep>;

  convertSticker(metadata: StickerElementMeta[], context: StickerContext): Promise<{ success: boolean; result: boolean }>;
  restoreContours(path: string): Promise<boolean>;

  exportSidecar(
    path: string,
    uuids: string[],
    pageWidth: number,
    pageHeight: number,
    sourceRect: PageRect | null,
    targetRect: PageRect | null,
  ): Promise<SidecarExport>;

  stickerInserted(): void;

  beginPaste(): void;

  finishPaste(selection: boolean): void;

  copyText(text: string): void;

  syncApplied(request: number, ok: boolean): void;
}
export const notipalClipboard = nativeModule<NotipalClipboardSpec>('NotipalClipboard');

export interface NotipalLassoSpec {

  startCapture(mode: 'smart' | 'ai', fromBubble: boolean): void;

  setSelectionKind(request: number, sendsText: boolean): void;

  beginPenSelection(requestId: string): void;

  finishPenSelection(requestId: string): Promise<boolean>;
  cancelPenSelection(requestId: string): void;

  prepareLivePage(filePath: string, page: number, width: number, height: number): Promise<boolean>;
}
export const notipalLasso = nativeModule<NotipalLassoSpec>('NotipalLasso');

export interface NotipalImageSpec {

  prepare(path: string): Promise<string>;

  insertFinished(requestId: string, success: boolean): void;
}
export const notipalImage = nativeModule<NotipalImageSpec>('NotipalImage');

export type ScreenBox = { left: number; top: number; right: number; bottom: number };

export interface NotipalCaptureSpec {}
export const notipalCapture = nativeModule<NotipalCaptureSpec>('NotipalCapture');

export interface NotipalLinksSpec {

  openPicker(currentFilePath: string): void;

  insertFinished(requestId: string, selectionReady: boolean, targetFile: string, targetPage: number): void;
}
export const notipalLinks = nativeModule<NotipalLinksSpec>('NotipalLinks');

export interface NotipalPaletteSpec {}
export const notipalPalette = nativeModule<NotipalPaletteSpec>('NotipalPalette');

export type OccupiedRange = { top: number; bottom: number };

export type TextLayoutParams = {
  text: string;
  mode: string;
  pageWidth: number;
  pageHeight: number;
  nextTop: number;
  occupiedRanges: OccupiedRange[];
  overrideLeft?: number;
};

export type TextLayoutResult = {
  top: number;
  boxHeight: number;
  bottom: number;
  newPage: boolean;
  maxH: number;
  left: number;
  right: number;
  charsPerLine: number;
  lines: number;
  measuredLines: number;
  topMargin: number;
  boxGap: number;
  nativeHeight: number;
  remainingHeight: number;
};

export type TextSplitOptions = { text: string; width: number; fontSize: number; includePad: boolean; availableHeight: number };

export type TextSplitResult = {
  fittingText: string;
  overflowText: string;
  fittingHeight: number;
  fittingLineCount: number;
  didSplit: boolean;
};

export type TextBubbleEvent = {
  kind: 'tap' | 'longPress' | 'drag' | 'layout';
  screenX: number;
  screenY: number;
  screenBottomY: number;
  pageX: number;
  pageY: number;
  pageBottomY: number;
  bubbleWidth: number;
  bubbleHeight: number;
};

export interface NotipalTextSpec {

  showBubble(): void;

  showBubbleAt(pageX: number, pageY: number): void;
  hideBubble(): void;

  setBubbleState(pending: boolean, pageFull: boolean): void;

  setPageSize(width: number, height: number): void;
  calculateLayout(params: TextLayoutParams): Promise<TextLayoutResult>;
  splitTextForHeight(options: TextSplitOptions): Promise<TextSplitResult>;
}
export const notipalText = nativeModule<NotipalTextSpec>('NotipalText');

export type SendLinkedFile = { path: string; linkType: number; label: string };

export type PendingText = { pendingId: string; text: string; fileName: string };

export interface NotipalLocalSendSpec {

  start(showNetworkTip: boolean): Promise<boolean>;

  setSendData(text: string, imagePaths: string[], linkedFiles: SendLinkedFile[]): void;

  openLassoPanel(): void;

  openDocTextPanel(text: string): void;

  flushPendingTexts(): Promise<PendingText[]>;
  ackText(pendingId: string): void;

  imagePrepared(requestId: string, success: boolean): void;
}
export const notipalLocalSend = nativeModule<NotipalLocalSendSpec>('NotipalLocalSend');

export type RelayAction = 'lasso_ai' | 'screenshot_ai' | 'reply_failed';

export interface NotipalRelaySpec {

  openWindow(forPage: boolean): Promise<boolean>;

  setStatus(text: string): void;

  flash(text: string, ms: number): void;

  setAside(aside: boolean): void;

  stage(text: string): Promise<boolean>;

  isActive(): boolean;
}
export const notipalRelay = nativeModule<NotipalRelaySpec>('NotipalRelay');

export function hostConvertElement2Sticker(params: {
  machineType: number;
  elements: object[];
  stickerPath: string;
}): Promise<unknown> {
  return NativeModules.NativePluginAPI.convertElement2Sticker(params);
}

export type ToolbarStatePayload = {
  state: 'hidden' | 'stowed' | 'expanded' | 'yielding';

  side: 'left' | 'right';

  session: boolean;

  windowAttached: boolean;
};

export interface ToolbarSpec {

  toggle(mode: 'note_notipal' | 'doc_notipal' | null): void;

  restore(reason: string): void;

  setActive(tool: string, active: boolean): void;

  setClips(filled: boolean[]): void;

  openSettings(): void;
  getState(): ToolbarStatePayload;

  beginTxn(name: string, timeoutMs: number): void;

  endTxn(name: string, ok: boolean, reason: string): void;
}
export const notipalToolbar = nativeModule<ToolbarSpec>('NotipalToolbar');

export type NativeEvents = {
  'host:state': HostStatePayload;

  'toolbar:action': {
    tool: string;
    gesture: 'tap' | 'longPress' | 'exit';
    slot?: number;
    host: HostStatePayload;
  };

  'toolbar:closed': Record<string, never>;

  'panel:closed': { panel: string };

  'clipboard:clearAll': Record<string, never>;

  'clipboard:sketchRequest': { selection: boolean };

  'clipboard:syncApply': { request: number; clips: string };

  'lasso:selectionShown': { request: number };

  'lasso:penBbox': { requestId: string; left: number; top: number; right: number; bottom: number };

  'lasso:penCancel': { requestId: string };

  'image:insert': { requestId: string; path: string };

  'capture:regionClosed': { fromBubble: boolean; bbox?: ScreenBox };

  'links:insert': { requestId: string; path: string; linkName: string; targetFile: string; targetPage: number };

  'text:bubble': TextBubbleEvent;

  'text:bubbleClosed': Record<string, never>;

  'localsend:text': PendingText;

  'localsend:openReceiver': { text: string };

  'localsend:prepareImage': { requestId: string };

  'relay:action': { action: RelayAction };

  'relay:insert': { text: string };

  'relay:replyArrived': Record<string, never>;

  'relay:off': Record<string, never>;
};

export function onNative<K extends keyof NativeEvents>(
  event: K,
  h: (p: NativeEvents[K]) => void,
): () => void {
  const sub = DeviceEventEmitter.addListener(event, h);
  return () => sub.remove();
}
