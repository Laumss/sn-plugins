import FloatingToolbarBridge from './FloatingToolbarBridge';
import { loadClips, clipFilledFlags } from './ToolPresets';

export async function refreshTitleClips(): Promise<void> {
  FloatingToolbarBridge.updateTitleClips(clipFilledFlags(await loadClips()));
}

export function clipsChangedBy(result: unknown): boolean {
  return typeof result === 'string' &&
    (result.startsWith('Saved to clip') || (result.startsWith('Clip') && result.endsWith('cleared')));
}

const slotQueues = new Map<string, Promise<unknown>>();

export function withClipSlot<T>(slot: string, fn: () => Promise<T>): Promise<T> {
  const prev = slotQueues.get(slot) ?? Promise.resolve();
  const run = prev.then(fn, fn);
  slotQueues.set(slot, run.catch(() => undefined));
  return run;
}
