import { log } from './common';
import { hostCtxFromState, readHostCtx } from './host';
import type { HostCtx } from './host';
import { notipalToolbar } from './native';
import type { NativeEvents } from './native';

const TAG = 'actions';

export type ToolbarAction = NativeEvents['toolbar:action'];

export type Action = (ctx: HostCtx, ev: ToolbarAction) => Promise<void>;

const actions = new Map<string, Action>();

const ACTION_CAP_MS = 150_000;

let running: { label: string } | null = null;

export function registerAction(tool: string, action: Action): void {
  actions.set(tool, action);
}

export async function dispatch(ev: ToolbarAction): Promise<void> {
  const action = actions.get(ev.tool);
  if (!action) {
    log.w(TAG, `no action registered for tool=${ev.tool} gesture=${ev.gesture}`);
    return;
  }
  const label = `${ev.tool}/${ev.gesture}`;
  if (ev.gesture === 'exit') {
    try {

      await action(hostCtxFromState(ev.host), ev);
    } catch (e) {
      log.e(TAG, `${label} failed`, e);
    }
    return;
  }
  if (running) {
    log.w(TAG, `drop ${label}: ${running.label} still running`);
    if (ev.gesture === 'tap') {
      notipalToolbar.setActive(ev.tool, false);
    }
    return;
  }
  const run = { label };
  running = run;
  const cap = setTimeout(() => {
    if (running !== run) { return; }
    running = null;
    log.w(TAG, `${label} still running after ${ACTION_CAP_MS / 1000}s: queue released`);
    notipalToolbar.setActive(ev.tool, false);
  }, ACTION_CAP_MS);
  try {
    const ctx = await readHostCtx(ev.host);
    await action(ctx, ev);
  } catch (e) {
    log.e(TAG, `${label} failed`, e);
  } finally {
    clearTimeout(cap);
    if (running === run) { running = null; }
  }
}
