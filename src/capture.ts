import { PluginCommAPI, PluginNoteAPI } from 'sn-plugin-lib';
import { registerAction } from './actions';
import { TOOL, log } from './common';
import { asResponse, dialog, insertTxn } from './host';
import { t } from './i18n';
import { hasNativeModule, notipalImage } from './native';
import type { NativeEvents } from './native';

const TAG = 'capture';

const IMAGE_AVAILABLE = hasNativeModule('NotipalImage');
if (!IMAGE_AVAILABLE) { log.e(TAG, 'NotipalImage missing, image insertion is unavailable'); }

const IMAGE_TXN = 'insert_image';

const IMAGE_TXN_TIMEOUT_MS = 30_000;

const HANDLED_KEEP = 64;
const handledInsertRequests = new Set<string>();

async function insertIntoNote(path: string, alive: () => boolean): Promise<void> {
  const prepared = await notipalImage.prepare(path);
  if (!alive()) { throw new Error('insert image abandoned after prepare'); }

  try {
    await PluginCommAPI.setLassoBoxState(2);
  } catch (e) {
    log.w(TAG, '[INSERT] setLassoBoxState(2) before insert failed', e);
  }
  if (!alive()) { throw new Error('insert image abandoned before insertImage'); }
  const result = asResponse(await PluginNoteAPI.insertImage(prepared));
  log.i(TAG, `[INSERT] insertImage resolved success=${result?.success} prepared=${prepared}`);
  if (result?.success !== true) {
    throw new Error(`insertImage failed: ${result?.error?.message ?? 'unknown'}`);
  }
}

export async function onInsertImage({ requestId, path }: NativeEvents['image:insert']): Promise<void> {
  if (!IMAGE_AVAILABLE) { return; }
  if (handledInsertRequests.has(requestId)) { return; }
  handledInsertRequests.add(requestId);
  if (handledInsertRequests.size > HANDLED_KEEP) {
    const oldest = handledInsertRequests.values().next().value;
    if (oldest !== undefined) { handledInsertRequests.delete(oldest); }
  }
  let success = false;

  const body: { run?: Promise<void> } = {};
  try {
    log.i(TAG, `[INSERT] insert request=${requestId} path=${path}`);
    await insertTxn(IMAGE_TXN, IMAGE_TXN_TIMEOUT_MS, alive => {
      body.run = insertIntoNote(path, alive);
      return body.run;
    });
    success = true;
  } catch (e) {

    log.w(TAG, `[INSERT] image insertion failed request=${requestId}`, e);
    body.run?.then(
      () => {
        log.i(TAG, `[INSERT] late success request=${requestId}`);
        notipalImage.insertFinished(requestId, true);
      },
      (late: unknown) => log.i(TAG, `[INSERT] body ended after failure request=${requestId}: ${String(late)}`),
    );
  } finally {
    notipalImage.insertFinished(requestId, success);
  }
}

function registerImage(): void {
  if (IMAGE_AVAILABLE) { return; }
  registerAction(TOOL.IMAGE_CAPTURE, async () => {
    await dialog.tip(t('feature_not_ready'));
  });
}

const CAPTURE_AVAILABLE = hasNativeModule('NotipalCapture');

export type RegionClosed = NativeEvents['capture:regionClosed'];

export const captureHooks: { regionClosed: (ev: RegionClosed) => void } = {
  regionClosed: ev => {
    if (ev.bbox) { log.w(TAG, 'region sent to AI but no relay handler is registered'); }
  },
};

export function onRegionClosed(ev: RegionClosed): void {
  log.i(TAG, `region closed fromBubble=${ev.fromBubble} bbox=${ev.bbox ? JSON.stringify(ev.bbox) : 'none'}`);
  captureHooks.regionClosed(ev);
}

function registerCapture(): void {
  if (!CAPTURE_AVAILABLE) { log.e(TAG, 'NotipalCapture missing, screenshots are unavailable'); }
}

export function register(): void {
  registerImage();
  registerCapture();
}
