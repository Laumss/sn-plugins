import { BuildFlags, hasNativeModule, notipalCommon, notipalLog } from './native';

export function sleep(ms: number): Promise<void> {
  if (!hasNativeModule('NotipalCommon')) {
    return new Promise<void>(resolve => setTimeout(resolve, ms));
  }
  return notipalCommon.delay(ms).then(
    () => undefined,
    () => undefined,
  );
}

export function after(ms: number, fn: () => void): () => void {
  let cancelled = false;
  sleep(ms).then(() => {
    if (!cancelled) { fn(); }
  });
  return () => {
    cancelled = true;
  };
}

export function withTimeout<T>(p: Promise<T>, ms: number, label: string): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const cancel = after(ms, () => reject(new Error(`${label} timed out after ${ms}ms`)));
    p.then(
      v => {
        cancel();
        resolve(v);
      },
      e => {
        cancel();
        reject(e);
      },
    );
  });
}

export abstract class StateMachine<S, E> {
  private current: S;
  private queue: E[] = [];
  private dispatching = false;
  private timers: (() => void)[] = [];
  private listeners = new Set<(old: S, next: S) => void>();

  protected constructor(private readonly tag: string, initial: S) {
    this.current = initial;
  }

  get state(): S {
    return this.current;
  }

  send(event: E): void {
    this.queue.push(event);
    if (this.dispatching) { return; }
    this.dispatching = true;
    try {
      while (this.queue.length > 0) { this.step(this.queue.shift() as E); }
    } finally {
      this.dispatching = false;
    }
  }

  protected after(ms: number, fn: () => void): void {
    this.timers.push(after(ms, fn));
  }

  addListener(l: (old: S, next: S) => void): () => void {
    this.listeners.add(l);
    return () => {
      this.listeners.delete(l);
    };
  }

  protected abstract reduce(state: S, event: E): S;

  protected onEnter(_state: S, _from: S): void {}

  private step(event: E): void {
    const old = this.current;
    let next: S;
    try {
      next = this.reduce(old, event);
    } catch (e) {
      log.e(this.tag, `reduce failed: ${JSON.stringify(event)} in ${JSON.stringify(old)}`, e);
      return;
    }
    if (JSON.stringify(next) === JSON.stringify(old)) { return; }
    const timers = this.timers;
    this.timers = [];
    timers.forEach(cancel => cancel());
    this.current = next;
    log.d(this.tag, `${JSON.stringify(old)} --${JSON.stringify(event)}--> ${JSON.stringify(next)}`);
    try {
      this.onEnter(next, old);
    } catch (e) {
      log.e(this.tag, `onEnter failed: ${JSON.stringify(next)}`, e);
    }
    this.listeners.forEach(l => {
      try {
        l(old, next);
      } catch (e) {
        log.e(this.tag, 'listener failed', e);
      }
    });
  }
}

type ConsoleMethod = (...args: unknown[]) => void;

const METHODS: Array<['debug' | 'log' | 'info' | 'warn' | 'error', string]> = [
  ['debug', 'D'],
  ['log', 'I'],
  ['info', 'I'],
  ['warn', 'W'],
  ['error', 'E'],
];
const FLUSH_DELAY_MS = 1000;
const FLUSH_LINES = 200;

function text(arg: unknown): string {
  if (typeof arg === 'string') { return arg; }
  if (arg instanceof Error) {return arg.stack || `${arg.name}: ${arg.message}`;}
  if (arg !== null && typeof arg === 'object') {
    try {
      return JSON.stringify(arg) ?? String(arg);
    } catch (_) {

      return String(arg);
    }
  }
  return String(arg);
}

function readBuildFlags(): BuildFlags {
  if (!hasNativeModule('NotipalLog')) {return { devBuild: false, debug: false };}
  return notipalLog.buildFlags();
}

const flags: BuildFlags = readBuildFlags();

let captureInstalled = false;

function installLogCapture(): void {
  if (captureInstalled) { return; }
  captureInstalled = true;
  if (flags.devBuild) { return; }
  const target = console as unknown as Record<string, ConsoleMethod>;
  if (!hasNativeModule('NotipalLog')) {

    for (const [name] of METHODS) {target[name] = () => {};}
    return;
  }
  let pending: string[] = [];
  let cancelFlush: (() => void) | null = null;
  const flush = () => {
    if (cancelFlush !== null) {
      cancelFlush();
      cancelFlush = null;
    }
    const lines = pending;
    pending = [];
    notipalLog.append(lines);
  };
  for (const [name, level] of METHODS) {
    target[name] = (...args: unknown[]) => {
      pending.push(`${Date.now()}\t${level}\t${args.map(text).join(' ')}`);
      if (pending.length >= FLUSH_LINES) { flush(); }
      else if (cancelFlush === null) { cancelFlush = after(FLUSH_DELAY_MS, flush); }
    };
  }
}

installLogCapture();

export const log = {
  d(tag: string, msg: string): void {
    if (flags.debug) {console.debug(`[${tag}] ${msg}`);}
  },
  i(tag: string, msg: string): void {
    console.log(`[${tag}] ${msg}`);
  },
  w(tag: string, msg: string, err?: unknown): void {
    if (err === undefined) {console.warn(`[${tag}] ${msg}`);}
    else {console.warn(`[${tag}] ${msg}`, err);}
  },
  e(tag: string, msg: string, err?: unknown): void {
    if (err === undefined) {console.error(`[${tag}] ${msg}`);}
    else {console.error(`[${tag}] ${msg}`, err);}
  },
};

export type RNFSModule = typeof import('react-native-fs');

let rnfsCached: RNFSModule | null = null;

export const fs = {
  get(): RNFSModule | null {
    if (rnfsCached) { return rnfsCached; }
    if (!hasNativeModule('RNFSManager')) { return null; }
    try {
      const mod = require('react-native-fs');
      const rnfs = (mod?.default ?? mod) as RNFSModule | undefined;
      if (rnfs && typeof rnfs.exists === 'function') { rnfsCached = rnfs; }
    } catch (e) {
      log.w('fs', 'react-native-fs unavailable', e);
      return null;
    }
    return rnfsCached;
  },
};

export async function reportPluginVersion(): Promise<void> {
  try {
    const { PluginManager } = require('sn-plugin-lib') as typeof import('sn-plugin-lib');
    const dir = await PluginManager.getPluginDirPath();
    const rnfs = fs.get();
    if (!dir || !rnfs) { return; }
    const path = `${dir}/PluginConfig.json`;
    if (!(await rnfs.exists(path))) { return; }
    const configText = await rnfs.readFile(path, 'utf8');
    const cfg: { versionName?: unknown; versionCode?: unknown } = JSON.parse(configText.replace(/^\uFEFF/, ''));
    if (cfg && cfg.versionName) {
      notipalCommon.setPluginVersion(String(cfg.versionName), String(cfg.versionCode ?? ''));
    }
  } catch (e) {
    log.w('common', 'reportPluginVersion failed', e);
  }
}

const SDCARD = '/sdcard';
const EMULATED = '/storage/emulated/0';

export function canonicalPath(path: string): string {
  if (path === EMULATED) { return SDCARD; }
  if (path.startsWith(`${EMULATED}/`)) { return SDCARD + path.slice(EMULATED.length); }
  return path;
}

export function hostPath(path: string): string {
  if (path === SDCARD) { return EMULATED; }
  if (path.startsWith(`${SDCARD}/`)) { return EMULATED + path.slice(SDCARD.length); }
  return path;
}

export const CLIP_SLOT_COUNT = 8;

export type ClipData = { [slot: string]: string | null };

export type TextInsertMode = 'nospacing' | 'paragraph';

export type Presets = {

  clips: ClipData;

  textInsertMode: TextInsertMode;
};

function defaultPresets(): Presets {
  const clips: ClipData = {};
  for (let n = 1; n <= CLIP_SLOT_COUNT; n++) { clips[String(n)] = null; }
  return { clips, textInsertMode: 'nospacing' };
}

async function readPresets(): Promise<Presets> {
  const p = defaultPresets();
  try {
    const raw = await notipalCommon.loadPresets();
    if (!raw) { return p; }
    const saved: Partial<Presets> = JSON.parse(raw);
    if (saved.clips && typeof saved.clips === 'object') { Object.assign(p.clips, saved.clips); }
    if (saved.textInsertMode === 'paragraph' || saved.textInsertMode === 'nospacing') {
      p.textInsertMode = saved.textInsertMode;
    }
  } catch (e) {
    log.w('presets', 'load failed', e);
  }
  return p;
}

let presetsWrite: Promise<void> = Promise.resolve();

export const presets = {

  load(): Promise<Presets> {
    return presetsWrite.then(readPresets);
  },

  save(p: Presets): Promise<void> {
    const json = JSON.stringify(p);
    presetsWrite = presetsWrite
      .then(() => notipalCommon.savePresets(json))
      .catch(e => log.w('presets', 'save failed', e));
    return presetsWrite;
  },

  update(mutator: (p: Presets) => void): Promise<Presets> {
    const run = presetsWrite.then(async () => {
      const p = await readPresets();
      mutator(p);
      await notipalCommon.savePresets(JSON.stringify(p));
      return p;
    });
    presetsWrite = run.then(
      () => undefined,
      e => log.w('presets', 'update failed', e),
    );
    return run;
  },
};

export function clipFilledFlags(clips: ClipData): boolean[] {
  return Array.from({ length: CLIP_SLOT_COUNT }, (_, i) => !!clips[String(i + 1)]);
}

export const TOOL = {
  CLIP_MANAGER: 'manage_clips',
  IMAGE_CAPTURE: 'insert_doc_screenshot',
  INSERT_TEXT: 'insert_text',
  SMART_LASSO: 'smart_lasso',
  INSERT_LINK: 'insert_link',
  AI_RELAY: 'ai_relay',
  INK_PALETTE: 'ink_palette',
  COLLAPSE: 'toolbar_collapse',
  NOTE_TO_MOSAIC: 'note_to_mosaic',
  DOC_TO_MOSAIC: 'doc_to_mosaic',
  DOC_SHARE: 'doc_share',
} as const;
