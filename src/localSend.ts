import { PluginCommAPI } from 'sn-plugin-lib';
import { log } from './common';
import { asResponse } from './host';
import { lassoHooks, releaseLassoIfGoneAfterSend } from './lasso';
import type { LassoSendData } from './lasso';
import { hasNativeModule, notipalLocalSend } from './native';
import type { NativeEvents, PendingText } from './native';
import { onTextReceived, openReceiverWithText, textHooks } from './textInsert';

const TAG = 'localSend';

const NO_ACTIVE_SELECTION = 904;

function routeText(info: PendingText): void {
  log.i(TAG, `text received len=${info.text.length} file=${info.fileName}`);
  if (info.pendingId) { notipalLocalSend.ackText(info.pendingId); }
  onTextReceived(info.text);
}

export function onText(ev: NativeEvents['localsend:text']): void {
  routeText(ev);
}

export function onOpenReceiver(ev: NativeEvents['localsend:openReceiver']): void {
  openReceiverWithText(ev.text).catch(e => log.w(TAG, 'open text receiver from LocalSend prompt failed', e));
}

export async function onPrepareImage(ev: NativeEvents['localsend:prepareImage']): Promise<void> {
  let ok = false;
  try {
    const res = asResponse(await PluginCommAPI.setLassoBoxState(2));
    ok = res?.success === true || res?.error?.code === NO_ACTIVE_SELECTION;
  } catch (e) {
    log.w(TAG, '[INSERT] receive preparation: setLassoBoxState(2) failed', e);
  }
  notipalLocalSend.imagePrepared(ev.requestId, ok);
}

export function onPanelClosed(ev: NativeEvents['panel:closed']): void {
  if (ev.panel !== 'send') { return; }
  releaseLassoIfGoneAfterSend().catch(e => log.w(TAG, 'lasso check after send failed', e));
}

function setSendData(data: LassoSendData): void {
  notipalLocalSend.setSendData(data.text, data.imagePaths, data.linkedFiles);
}

async function sendDocText(text: string): Promise<'opened' | 'localsend_off' | 'failed'> {
  if (!await notipalLocalSend.start(true)) { return 'localsend_off'; }
  notipalLocalSend.openDocTextPanel(text);
  return 'opened';
}

async function flushPendingTexts(): Promise<void> {
  const texts = await notipalLocalSend.flushPendingTexts();
  if (texts.length === 0) { return; }
  log.i(TAG, `FlushPendingLocalSend count=${texts.length}`);
  for (const info of texts) { routeText(info); }
}

export function register(): void {
  if (!hasNativeModule('NotipalLocalSend')) {
    log.w(TAG, 'NotipalLocalSend not available: send panel and LocalSend stay unwired');
    return;
  }
  lassoHooks.setSendData = setSendData;
  lassoHooks.openSendPanel = () => notipalLocalSend.openLassoPanel();
  lassoHooks.sendDocText = sendDocText;
  textHooks.startLocalSend = () => notipalLocalSend.start(true);
  flushPendingTexts().catch(e => log.w(TAG, 'flushPendingTexts failed', e));
}
