import { NativeModules } from 'react-native';
import { ENABLE_DEBUG } from './FloatingToolbarBridge';

const { FloatingToolbar } = NativeModules;

const CLIP_STORE_KEY = 99;

interface ClipData {
  [slot: string]: string | null;
}

export type AiSelectionMode = 'text' | 'formula';

const AI_BUBBLE_ACTION_STORE_KEY = 93;
interface AiBubblePrefs { version: 2; selectionMode: AiSelectionMode }
let prefsPromise: Promise<AiBubblePrefs> | null = null;
let prefsWrite: Promise<void> = Promise.resolve();

function loadAiPrefs(): Promise<AiBubblePrefs> {
  if (!prefsPromise) prefsPromise = (async () => {
    let saved: any = null;
    try {
      const raw = await FloatingToolbar?.loadPreset(AI_BUBBLE_ACTION_STORE_KEY);
      saved = raw ? JSON.parse(raw) : null;
    } catch (_) {}
    return {
      version: 2 as const,

      selectionMode: ENABLE_DEBUG && saved?.selectionMode === 'formula' ? 'formula' as const : 'text' as const,
    };
  })();
  return prefsPromise;
}

function updateAiPrefs(update: (prefs: AiBubblePrefs) => void): Promise<void> {
  prefsWrite = prefsWrite.then(async () => {
    const prefs = await loadAiPrefs();
    update(prefs);
    await FloatingToolbar?.savePreset(AI_BUBBLE_ACTION_STORE_KEY, JSON.stringify(prefs));
  }).catch(e => console.warn('[ToolPresets]: save AI preferences:', e));
  return prefsWrite;
}

export async function loadAiSelectionMode(): Promise<AiSelectionMode> {
  return (await loadAiPrefs()).selectionMode;
}
export function saveAiSelectionMode(mode: AiSelectionMode): Promise<void> {
  return updateAiPrefs(prefs => { prefs.selectionMode = mode; });
}

export const CLIP_SLOT_COUNT = 8;

export async function loadClips(): Promise<ClipData> {
  const result: ClipData = {};
  try {
    const json = await FloatingToolbar?.loadPreset(CLIP_STORE_KEY);
    if (json) Object.assign(result, JSON.parse(json) as ClipData);
  } catch (e) {
    console.warn('[ToolPresets]: loadClips:', e);
  }
  for (let n = 1; n <= CLIP_SLOT_COUNT; n++) {
    if (!(String(n) in result)) result[String(n)] = null;
  }
  return result;
}

export function clipFilledFlags(clips: ClipData): boolean[] {
  return Array.from({ length: CLIP_SLOT_COUNT }, (_, i) => !!clips[String(i + 1)]);
}

export async function saveClips(clips: ClipData): Promise<void> {
  try {
    await FloatingToolbar?.savePreset(CLIP_STORE_KEY, JSON.stringify(clips));
  } catch (e) {
    console.warn('[ToolPresets]: saveClips:', e);
  }
}
