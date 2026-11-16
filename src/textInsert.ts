import { PluginCommAPI, PluginFileAPI, PluginManager, PluginNoteAPI } from 'sn-plugin-lib';
import type { Element } from 'sn-plugin-lib';
import { registerAction } from './actions';
import type { ToolbarAction } from './actions';
import { StateMachine, TOOL, after, canonicalPath, log, presets, sleep, withTimeout } from './common';
import type { TextInsertMode } from './common';
import {
  activeInsertTxn,
  asResponse,
  coords,
  dialog,
  elements,
  hostState,
  insertTxn,
  onHostChange,
  readHostCtx,
} from './host';
import type { HostCtx } from './host';
import { t } from './i18n';
import {
  hasNativeModule,
  notipalClipboard,
  notipalHost,
  notipalMosaic,
  notipalText,
  notipalToolbar,
} from './native';
import type {
  HostStatePayload,
  NativeEvents,
  OccupiedRange,
  TextBubbleEvent,
  TextLayoutResult,
  TextSplitResult,
} from './native';

const TAG = 'textInsert';

const TEXT_AVAILABLE = hasNativeModule('NotipalText');
if (!TEXT_AVAILABLE) { log.e(TAG, 'NotipalText missing, text receive is unavailable'); }

export type TextSource = 'localsend' | 'broadcast' | 'unknown';

interface QueueItem {
  text: string;
  source: TextSource;
}

export type InsertionAnchor = { top: number; left: number; safeMaxLeft: number };

type PositionChangedCallback = (page: number, nextTop: number, source: TextSource, isPageChange: boolean) => void;
type NoteChangedCallback = (reason: 'note_switched', detail: string) => void;
type PageWaitChangedCallback = () => void;

type InsertResult = 'continue' | 'pause' | 'done';

const TOP_MARGIN_BASE = 150;
const BASE_PAGE_HEIGHT = 1872;
const FONT_SIZE = 36;

const NOTE_RENDER_PAD = 28;

const DEVICE_LINE_HEIGHT = 42;

const SYSTEM_FONT_WRAP_SAFETY_RATIO = 0.15;
const SYSTEM_FONT_WRAP_SAFETY_MIN_LINES = 8;
const EMOJI_PER_EXTRA_LINE = 4;
const EMOJI_REGEX = /[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE0F}\u{2190}-\u{21FF}\u{2B00}-\u{2BFF}]/gu;

const WIDTH_ADJUSTMENT = 30;
const INTERVAL_MS = 200;

const PEN_SAFE_GAP_MS = 2000;

const SCAN_RETRY_DELAY_MS = 300;

const RANGE_MATCH_TOLERANCE = 6;

const NOTE_FOREGROUND_SETTLE_MS = 1500;

const SDK_TIMEOUT_MS = 8000;

const PAGE_WAIT_POLL_MS = 800;

const TEXT_TXN = 'text_insert';

const TEXT_TXN_TIMEOUT_MS = 30_000;

interface ModeConfig {
  boxGap: number;
  threshold: number;
  lineHeightRatio: number;
  newlineGapLines: number;
}

const MODE_CONFIG: Record<TextInsertMode, ModeConfig> = {
  nospacing: { boxGap: 0, threshold: 0.87, lineHeightRatio: 1.4, newlineGapLines: 0 },
  paragraph: { boxGap: 40, threshold: 0.80, lineHeightRatio: 1.6, newlineGapLines: 0.3 },
};

function emojiExtraHeight(text: string): number {
  const count = (text.match(EMOJI_REGEX) ?? []).length;
  if (count === 0) { return 0; }
  return Math.ceil(count / EMOJI_PER_EXTRA_LINE) * DEVICE_LINE_HEIGHT;
}

function systemFontWrapExtraHeight(lineCount: number): number {
  if (lineCount < SYSTEM_FONT_WRAP_SAFETY_MIN_LINES) { return 0; }
  return Math.ceil(lineCount * SYSTEM_FONT_WRAP_SAFETY_RATIO) * DEVICE_LINE_HEIGHT;
}

function sdkCall(p: Promise<unknown>, label: string, ms: number = SDK_TIMEOUT_MS): Promise<unknown> {
  return withTimeout(p, ms, label);
}

function textBoxRange(el: Element | null | undefined): OccupiedRange | null {
  const type = el?.type;
  if (typeof type !== 'number' || type < 500 || type > 502) { return null; }
  const rect = el?.textBox?.textRect;
  if (!rect || typeof rect.top !== 'number' || typeof rect.bottom !== 'number') { return null; }
  return { top: rect.top, bottom: rect.bottom };
}

async function splitTextForHeight(
  text: string,
  width: number,
  fontSize: number,
  availableHeight: number,
): Promise<TextSplitResult | null> {
  try {
    return await notipalText.splitTextForHeight({
      text,

      width: Math.max(24, width - WIDTH_ADJUSTMENT),
      fontSize,
      includePad: true,
      availableHeight,
    });
  } catch (e) {
    log.w(TAG, 'splitTextForHeight failed', e);
    return null;
  }
}

class Inserter {
  private textQueue: QueueItem[] = [];
  private pageSize: { width: number; height: number } | null = null;
  private notePath = '';
  private currentPage = 0;
  private targetPage = 0;
  private pageNextTop = new Map<number, number>();
  private pageNextLeft = new Map<number, number>();

  private bubbleAnchorTop = -1;
  private bubbleAnchorLeft = -1;

  private waitingForPageTap = false;

  private waitingForPageTurn = false;

  private anchorMovedWhileFull = false;

  private loopActive = false;
  private activeMode: TextInsertMode | null = null;
  private inserting = false;
  private lastScheduleAt = 0;
  private lastPenUpAt = 0;
  private penUpSub: { remove: () => void } | null = null;
  private deferredMode: TextInsertMode | null = null;
  private cancelTick: (() => void) | null = null;

  private generation = 0;

  private occupiedRanges: OccupiedRange[] = [];

  private lastScan = new Map<string, OccupiedRange[]>();

  private ownInserted = new Map<string, OccupiedRange[]>();
  private occupiedPage = -1;

  private holdInsertsUntil = 0;
  private expectedTotalPages = -1;

  constructor(
    private readonly onPositionChanged: PositionChangedCallback,
    private readonly onNoteChanged: NoteChangedCallback,
    private readonly onPageWaitChanged: PageWaitChangedCallback,
  ) {}

  private topMargin(): number {
    if (!this.pageSize) { return TOP_MARGIN_BASE; }
    return Math.round(TOP_MARGIN_BASE * (this.pageSize.height / BASE_PAGE_HEIGHT));
  }

  enqueue(text: string, source: TextSource = 'unknown'): void {

    text = text.replace(/^#{1,6}\s+/gm, '');
    text = text.replace(/^(?:>\s?)+/gm, '');
    this.textQueue.push({ text, source });

    if (this.activeMode && !this.waitingForPageTap) { this.registerPenUp(); }

    if (this.activeMode && !this.inserting && !this.waitingForPageTap) {
      if (!this.loopActive) {
        log.i(TAG, 'waking up from enqueue');
        this.loopActive = true;
        this.scheduleNext(this.activeMode);
      } else {
        const stale = Date.now() - this.lastScheduleAt > INTERVAL_MS * 3;
        if (stale) {
          log.i(TAG, 'timer stale, kick-starting from enqueue');
          this.scheduleNext(this.activeMode);
        }
      }
    }
  }

  clearQueue(): void {
    this.textQueue = [];
    this.setWaitingForPageTurn(false);
    this.setWaitingForPageTap(false);
  }

  getQueueLength(): number {
    return this.textQueue.length;
  }

  isRunning(): boolean {
    return this.activeMode !== null;
  }

  getMode(): TextInsertMode | null {
    return this.activeMode;
  }

  isWaitingForPageTap(): boolean {
    return this.waitingForPageTap;
  }

  isWaitingForPageTurn(): boolean {
    return this.waitingForPageTurn;
  }

  continueAfterPageTap(): boolean {
    if (!this.waitingForPageTap) { return false; }
    this.setWaitingForPageTap(false);
    if (this.activeMode && this.textQueue.length > 0 && !this.inserting) {
      this.registerPenUp();
      this.loopActive = true;
      this.scheduleNext(this.activeMode);
    }
    return true;
  }

  holdInserts(ms: number): void {
    const until = Date.now() + ms;
    if (until > this.holdInsertsUntil) {
      this.holdInsertsUntil = until;
      log.i(TAG, `holding inserts for ${ms}ms`);
    }
  }

  private normalizeNextLeft(left: number): { left: number; safeMaxLeft: number } {
    if (!this.pageSize) {
      const normalized = Math.max(0, Math.round(left));
      return { left: normalized, safeMaxLeft: normalized };
    }
    return coords.normalizeInsertionLeft(this.pageSize.width, left, FONT_SIZE);
  }

  setNextInsertionAnchor(top: number, left: number, source = 'programmatic'): InsertionAnchor {
    const clampedTop = Math.max(this.topMargin(), Math.round(top));
    const geometry = this.normalizeNextLeft(left);
    this.pageNextTop.set(this.targetPage, clampedTop);
    this.pageNextLeft.set(this.targetPage, geometry.left);
    this.bubbleAnchorTop = clampedTop;
    this.bubbleAnchorLeft = geometry.left;
    this.anchorMovedWhileFull = true;
    log.i(TAG, `SetInsertAnchor source=${source} page=${this.targetPage} requested=(${left},${top}) ` +
      `normalized=(${geometry.left},${clampedTop}) safeMax=${geometry.safeMaxLeft}`);
    this.onPositionChanged(this.targetPage, clampedTop, 'unknown', false);
    return { top: clampedTop, left: geometry.left, safeMaxLeft: geometry.safeMaxLeft };
  }

  getTargetPage(): number {
    return this.targetPage;
  }

  relocateTo(page: number): void {
    const previousTarget = this.targetPage;
    this.targetPage = page;
    this.currentPage = page;
    if (page !== previousTarget) {
      const carriedTop = this.bubbleAnchorTop > 0 ? this.bubbleAnchorTop : this.topMargin();
      this.pageNextTop.set(page, carriedTop);
      if (this.bubbleAnchorLeft >= 0) {
        this.pageNextLeft.set(page, this.bubbleAnchorLeft);
      }
      log.i(TAG, `relocateTo page=${page} carriedAnchor=${JSON.stringify({ top: carriedTop, left: this.bubbleAnchorLeft })}`);
    }
  }

  getNextTop(): number {
    return this.pageNextTop.get(this.targetPage) ?? this.topMargin();
  }

  getNextLeft(): number | undefined {
    return this.pageNextLeft.get(this.targetPage);
  }

  getPageSize(): { width: number; height: number } | null {
    return this.pageSize;
  }

  private setWaitingForPageTurn(waiting: boolean): void {
    if (this.waitingForPageTurn === waiting) { return; }
    this.waitingForPageTurn = waiting;
    if (waiting) { this.anchorMovedWhileFull = false; }
    this.onPageWaitChanged();
  }

  private setWaitingForPageTap(waiting: boolean): void {
    if (this.waitingForPageTap === waiting) { return; }
    this.waitingForPageTap = waiting;
    this.onPageWaitChanged();
  }

  applyModeSwitchGap(fromMode: TextInsertMode, toMode: TextInsertMode): void {
    if (!this.pageSize) { return; }
    if (fromMode === toMode) { return; }
    const oldCfg = MODE_CONFIG[fromMode];
    const newCfg = MODE_CONFIG[toMode];
    const tm = this.topMargin();
    if (newCfg.boxGap > oldCfg.boxGap) {
      const currentTop = this.pageNextTop.get(this.targetPage) ?? tm;
      const gapDiff = newCfg.boxGap - oldCfg.boxGap;
      const oneLineHeight = Math.ceil(FONT_SIZE * newCfg.lineHeightRatio);
      const extraGap = gapDiff + oneLineHeight;
      this.pageNextTop.set(this.targetPage, currentTop + extraGap);
      log.i(TAG, `modeSwitchGap +${extraGap} (gapDiff=${gapDiff} lineH=${oneLineHeight}) → nextTop=${currentTop + extraGap}`);
    }
  }

  async start(mode: TextInsertMode, preservePosition: boolean, isCurrent: () => boolean): Promise<boolean> {
    this.stop(true);
    const gen = this.generation;
    const current = () => gen === this.generation;
    if (!preservePosition) {
      this.pageNextTop.clear();
      this.pageNextLeft.clear();
      this.bubbleAnchorTop = -1;
      this.bubbleAnchorLeft = -1;
    }
    this.activeMode = mode;
    this.lastPenUpAt = 0;
    this.inserting = false;
    this.setWaitingForPageTurn(false);
    this.setWaitingForPageTap(false);

    try {
      let c = await readHostCtx();
      if (!current()) { return false; }
      if (!c.hostFilePath) {
        log.w(TAG, 'notePath empty, retrying');
        await sleep(600);
        if (!current()) { return false; }
        c = await readHostCtx();
        if (!current()) { return false; }
      }
      const notePath = c.hostFilePath || this.notePath;
      let size = c.pageSize.width > 0 ? c.pageSize : null;
      if (!size) {
        log.w(TAG, 'getPageSize failed, retrying in 800ms');
        await sleep(800);
        if (!current()) { return false; }
        const again = await readHostCtx();
        if (!current()) { return false; }
        if (again.pageSize.width > 0 && canonicalPath(again.hostFilePath) === canonicalPath(notePath)) {
          size = again.pageSize;
        }
      }
      this.notePath = notePath;
      if (c.page >= 0) {
        this.currentPage = c.page;
        this.targetPage = c.page;
      }
      if (!size) {
        log.e(TAG, 'failed to get pageSize after retry');
        this.activeMode = null;
        return false;
      }
      this.pageSize = { width: size.width, height: size.height };
      log.i(TAG, `pageSize=${JSON.stringify(this.pageSize)} topMargin=${this.topMargin()}`);
    } catch (e) {
      log.e(TAG, 'failed to get page info', e);
      if (current()) { this.activeMode = null; }
      return false;
    }

    await this.refreshOccupiedRanges();
    if (!current()) { return false; }

    try {
      const tp = asResponse(await sdkCall(PluginFileAPI.getNoteTotalPageNum(this.notePath), 'getNoteTotalPageNum'));
      if (!current()) { return false; }
      if (tp?.success && typeof tp.result === 'number') {
        this.expectedTotalPages = tp.result;
        log.i(TAG, `initial totalPages=${this.expectedTotalPages}`);
      }
    } catch (e) {
      log.w(TAG, 'getNoteTotalPageNum failed (non-fatal)', e);
      if (!current()) { return false; }
    }

    if (!isCurrent() || this.activeMode !== mode) {
      log.i(TAG, 'start superseded before the loop started');
      this.activeMode = null;
      return false;
    }
    this.loopActive = true;
    log.i(TAG, `started mode=${mode} page=${this.currentPage} notePath=${this.notePath}`);
    this.scheduleNext(mode);
    return true;
  }

  stop(clearActive = false): void {
    this.loopActive = false;
    this.cancelDeferredTick();
    if (clearActive) {

      this.generation += 1;
      this.activeMode = null;
      this.setWaitingForPageTurn(false);
      this.setWaitingForPageTap(false);
      this.unregisterPenUp();
    }
  }

  private registerPenUp(): void {
    this.unregisterPenUp();
    try {
      this.penUpSub = PluginManager.registerEventListener('event_pen_up', 1, {
        onMsg: (els: unknown) => {
          this.lastPenUpAt = Date.now();
          if (Array.isArray(els)) { elements.releaseHostElements(els as Element[]); }
        },
      });
      log.d(TAG, 'pen-up listener registered');
    } catch (e) {
      log.w(TAG, 'pen-up listener registration failed', e);
    }
  }

  private unregisterPenUp(): void {
    const sub = this.penUpSub;
    if (!sub) { return; }
    this.penUpSub = null;
    try {
      sub.remove();
    } catch (e) {
      log.w(TAG, 'pen-up listener removal failed', e);
    }
  }

  private isPenIdle(): boolean {
    if (this.lastPenUpAt === 0) { return true; }
    return (Date.now() - this.lastPenUpAt) >= PEN_SAFE_GAP_MS;
  }

  private pageKey(page: number): string {
    return `${canonicalPath(this.notePath)}#${page}`;
  }

  private async scanTextBoxes(page: number): Promise<{ ranges: OccupiedRange[]; elementCount: number } | null> {
    let res;
    try {
      res = asResponse(await sdkCall(PluginFileAPI.getElements(page, this.notePath), 'getElements(scan)'));
    } catch (e) {
      log.w(TAG, 'getElements(scan) threw', e);
      return null;
    }
    if (!res?.success || !Array.isArray(res.result)) {
      log.w(TAG, `getElements(scan) failed: ${JSON.stringify(res?.error ?? null)}`);
      return null;
    }
    const list = res.result as Element[];
    const ranges: OccupiedRange[] = [];
    try {
      for (const el of list) {
        const range = textBoxRange(el);
        if (range) { ranges.push(range); }
      }
    } finally {
      elements.releaseHostElements(list);
    }
    return { ranges, elementCount: list.length };
  }

  private missingFromScan(page: number, ranges: OccupiedRange[]): OccupiedRange[] {
    const key = this.pageKey(page);
    const expected = [...(this.lastScan.get(key) ?? []), ...(this.ownInserted.get(key) ?? [])];
    return expected.filter(e => !ranges.some(r => Math.abs(r.top - e.top) <= RANGE_MATCH_TOLERANCE));
  }

  private async refreshOccupiedRanges(): Promise<void> {
    if (this.occupiedPage === this.targetPage) { return; }

    const page = this.targetPage;
    const key = this.pageKey(page);
    this.occupiedRanges = [];
    this.occupiedPage = page;

    try {
      const first = await this.scanTextBoxes(page);
      let scan = first;
      let retried = false;
      let trusted = true;

      if (!first || first.elementCount === 0 || this.missingFromScan(page, first.ranges).length > 0) {
        retried = true;
        log.w(TAG, `scan suspicious page=${page} elements=${first?.elementCount ?? 'failed'} ` +
          `textboxes=${first?.ranges.length ?? 0} missing=${JSON.stringify(this.missingFromScan(page, first?.ranges ?? []))} → rescan`);
        await sleep(SCAN_RETRY_DELAY_MS);
        const again = await this.scanTextBoxes(page);
        if (again) {
          scan = again;
        } else {

          const base = first?.ranges ?? [];
          scan = { ranges: [...base, ...this.missingFromScan(page, base)], elementCount: first?.elementCount ?? -1 };
          trusted = false;
        }
      }
      if (!scan) { return; }

      if (this.occupiedPage !== page) { return; }
      const stillMissing = trusted ? this.missingFromScan(page, scan.ranges).length : 0;
      this.occupiedRanges = [...scan.ranges].sort((a, b) => a.top - b.top);
      if (trusted) {

        this.lastScan.set(key, scan.ranges);
        this.ownInserted.delete(key);
      }
      log.i(TAG, `ScanTextBoxes page=${page} count=${this.occupiedRanges.length} elements=${scan.elementCount}` +
        ` firstCount=${first?.ranges.length ?? -1} firstElements=${first?.elementCount ?? -1}` +
        ` retried=${retried} trusted=${trusted} stillMissing=${stillMissing}`);
    } catch (e) {
      log.w(TAG, 'refreshOccupiedRanges failed', e);
    }
  }

  private addToOccupied(top: number, bottom: number): void {
    const key = this.pageKey(this.targetPage);
    const own = this.ownInserted.get(key) ?? [];
    own.push({ top, bottom });
    this.ownInserted.set(key, own);
    if (this.occupiedPage === this.targetPage) {
      this.occupiedRanges.push({ top, bottom });
      this.occupiedRanges.sort((a, b) => a.top - b.top);
    }
  }

  private async readBackActualBottom(): Promise<number | null> {
    try {

      const res = asResponse(await sdkCall(PluginFileAPI.getLastElement(), 'getLastElement'));
      const el = res?.success ? (res.result as Element | null | undefined) : null;
      if (el) {
        if (typeof el.pageNum === 'number' && el.pageNum !== this.targetPage) {
          elements.releaseHostElements([el]);
          return null;
        }
        const range = textBoxRange(el);
        elements.releaseHostElements([el]);
        if (range && range.bottom > 0) { return range.bottom; }
      }
    } catch (e) {
      log.w(TAG, 'readback failed', e);
    }
    return null;
  }

  private async insertParagraph(mode: TextInsertMode, gen: number): Promise<InsertResult> {
    if (!MODE_CONFIG[mode] || !this.pageSize) { return 'done'; }
    if (Date.now() < this.holdInsertsUntil) { return 'continue'; }

    try {
      const elapsed = notipalHost.noteForegroundElapsedMs();
      if (typeof elapsed === 'number' && elapsed < NOTE_FOREGROUND_SETTLE_MS) { return 'continue'; }
    } catch (e) {
      log.w(TAG, 'noteForegroundElapsedMs failed', e);
    }
    if (!this.isPenIdle()) { return 'continue'; }
    if (this.textQueue.length === 0) { return 'continue'; }

    if (this.waitingForPageTurn || this.currentPage < this.targetPage) {
      const waiting = await this.pageWaitCheck(gen);
      if (waiting) { return waiting; }
    }

    const busy = activeInsertTxn();
    if (busy) {
      log.d(TAG, `insert deferred: ${busy} running`);
      return 'continue';
    }
    const run: { body?: Promise<InsertResult> } = {};
    try {
      return await insertTxn(TEXT_TXN, TEXT_TXN_TIMEOUT_MS, alive => {
        run.body = this.insertOne(mode, alive, gen);
        return run.body;
      });
    } catch (e) {
      log.w(TAG, 'TextInsertTxn failed', e);

      if (run.body) {
        try {
          await run.body;
        } catch (late) {
          log.w(TAG, 'insert body failed after the txn ended', late);
        }
      }
      return 'continue';
    }
  }

  private async pageWaitCheck(gen: number): Promise<InsertResult | null> {
    const contextOk = await this.checkNoteContext();
    if (gen !== this.generation || !contextOk) { return 'done'; }
    let page: number | null = null;
    try {
      const pg = asResponse(await sdkCall(PluginCommAPI.getCurrentPageNum(), 'getCurrentPageNum(pageWait)'));
      if (pg?.success && typeof pg.result === 'number') { page = pg.result; }
    } catch (e) {
      log.w(TAG, 'page wait check failed', e);
    }
    if (gen !== this.generation) { return 'done'; }

    if (page === null) { return 'pause'; }
    this.currentPage = page;
    if (page < this.targetPage) {
      log.d(TAG, `waiting for user to navigate to target page ${page} → ${this.targetPage}`);
      return 'pause';
    }
    if (page === this.targetPage && this.waitingForPageTurn && !this.anchorMovedWhileFull) {
      log.d(TAG, `page ${page} still full, waiting for a page turn`);
      return 'pause';
    }
    return null;
  }

  private async insertOne(mode: TextInsertMode, txnAlive: () => boolean, gen: number): Promise<InsertResult> {

    const alive = () => txnAlive() && gen === this.generation;
    const contextOk = await this.checkNoteContext();
    if (gen !== this.generation) { return 'done'; }
    if (!contextOk) { return 'done'; }
    if (!alive()) { return 'continue'; }

    const newText = this.textQueue.shift();
    if (!newText) { return 'continue'; }

    const itemSource = newText.source;
    let text = newText.text;
    log.i(TAG, `dequeued text len=${text.length} source=${itemSource}`);

    const giveBack = (): InsertResult => {
      if (gen !== this.generation) {
        log.i(TAG, 'dequeued text dropped: text receive stopped meanwhile');
        return 'done';
      }
      this.textQueue.unshift({ text, source: itemSource });
      return 'continue';
    };

    text = preprocess(text);
    if (text.trim().length === 0) {
      log.i(TAG, `TextSkipEmpty source=${itemSource}`);
      return 'continue';
    }

    if (!this.activeMode || !this.pageSize) {
      this.textQueue.unshift({ text, source: itemSource });
      return 'done';
    }

    const ps = this.pageSize;
    const tm = this.topMargin();

    try {
      const pg = asResponse(await sdkCall(PluginCommAPI.getCurrentPageNum(), 'getCurrentPageNum(preLayout)'));
      if (gen !== this.generation) { return giveBack(); }
      if (pg?.success && typeof pg.result === 'number') {
        this.currentPage = pg.result;
        if (this.currentPage < this.targetPage) {
          log.i(TAG, `waiting for user to navigate to target page ${this.currentPage} → ${this.targetPage}`);
          this.textQueue.unshift({ text, source: itemSource });
          this.onPositionChanged(this.targetPage, this.getNextTop(), itemSource, false);
          return 'pause';
        } else if (this.currentPage > this.targetPage) {
          log.i(TAG, `PageAutoRelocate target=${this.targetPage} → current=${this.currentPage}`);
          this.relocateTo(this.currentPage);
          const relocTop = this.getNextTop();
          if (this.waitingForPageTurn) {
            this.textQueue.unshift({ text, source: itemSource });
            this.setWaitingForPageTurn(false);
            this.setWaitingForPageTap(true);
            log.i(TAG, `PageTurnReady page=${this.currentPage} queueLen=${this.textQueue.length}`);
            this.onPositionChanged(this.currentPage, relocTop, itemSource, false);
            return 'pause';
          }
          this.onPositionChanged(this.currentPage, relocTop, itemSource, true);
        }
      }
    } catch (e) {
      log.w(TAG, 'pre-layout page check failed', e);
    }
    if (!alive()) { return giveBack(); }

    this.occupiedPage = -1;
    await this.refreshOccupiedRanges();
    if (!alive()) { return giveBack(); }

    const nextTopVal = this.pageNextTop.get(this.targetPage) ?? tm;
    const nextLeftVal = this.pageNextLeft.get(this.targetPage);
    log.d(TAG, `pre-layout targetPage=${this.targetPage} nextTop=${nextTopVal} nextLeft=${nextLeftVal}`);

    const layout = await withTimeout(notipalText.calculateLayout({
      text,
      mode,
      pageWidth: ps.width,
      pageHeight: ps.height,
      nextTop: nextTopVal,
      occupiedRanges: this.occupiedRanges,
      ...(nextLeftVal !== undefined ? { overrideLeft: nextLeftVal } : {}),
    }), SDK_TIMEOUT_MS, 'calculateLayout');
    if (gen !== this.generation) { return giveBack(); }

    const { left, right, boxHeight: boxH, lines } = layout;
    const top = layout.top;
    const bottom = layout.bottom;
    const maxH = layout.maxH;
    const remainH = maxH - top;
    log.i(TAG, `BoxCalc mode=${mode} lines=${lines} boxH=${boxH} top=${top} maxH=${maxH} remainH=${remainH} ` +
      `gap=${layout.boxGap} fits=${boxH <= remainH} topMargin=${tm}`);

    if (layout.newPage) {
      return this.insertSplit(text, itemSource, layout, alive, gen);
    }

    if (!this.activeMode || !this.pageSize) {
      this.textQueue.unshift({ text, source: itemSource });
      return 'done';
    }
    if (!alive()) { return giveBack(); }

    const renderBottom = Math.min(ps.height, bottom);
    try {
      const res = asResponse(await sdkCall(PluginNoteAPI.insertText({
        textContentFull: text,
        textRect: { left, top, right, bottom: renderBottom },
        fontSize: FONT_SIZE,
        textAlign: 0,
        textBold: 0,
        textItalics: 0,
        textFrameWidthType: 0,
        textFrameStyle: 0,
        textEditable: 1,
      }), 'insertText'));

      if (gen !== this.generation) {
        log.i(TAG, `insert finished after text receive stopped: success=${res?.success === true}`);
        return 'done';
      }

      if (res?.success) {
        log.i(TAG, `PageAnnotation text inserted to page=${this.targetPage} currentPage=${this.currentPage} ` +
          `textLen=${text.length} rect=${JSON.stringify({ left, top, right, bottom })}`);

        const actualBottom = alive() ? await this.readBackActualBottom() : null;
        if (gen !== this.generation) { return 'done'; }
        const maxReasonableBottom = bottom * 1.5;
        const effectiveBottom = (actualBottom !== null && actualBottom > top && actualBottom <= maxReasonableBottom)
          ? Math.max(actualBottom, bottom)
          : bottom;
        const newNextTop = effectiveBottom + layout.boxGap;
        const currentNextTop = this.pageNextTop.get(this.targetPage) ?? 0;
        if (newNextTop >= currentNextTop) {
          this.pageNextTop.set(this.targetPage, newNextTop);
        } else {
          log.d(TAG, `postInsert keep nextTop=${currentNextTop} (new ${newNextTop} is higher up)`);
        }
        this.addToOccupied(top, effectiveBottom);
        log.i(TAG, `InsertPos top=${top} estimatedBottom=${bottom} actualBottom=${actualBottom} ` +
          `effectiveBottom=${effectiveBottom} nextTop=${effectiveBottom + layout.boxGap} source=${itemSource}`);
        this.onPositionChanged(this.targetPage, effectiveBottom + layout.boxGap, itemSource, false);
        return 'continue';
      }
      const errMsg = res?.error?.message || 'insertText failed';
      log.e(TAG, `TextInsertFail page=${this.targetPage} err=${errMsg} textLen=${text.length} (skipped)`);
      return 'continue';
    } catch (e) {
      log.e(TAG, `TextInsertException page=${this.targetPage}`, e);
      return 'continue';
    }
  }

  private async insertSplit(
    text: string,
    itemSource: TextSource,
    layout: TextLayoutResult,
    alive: () => boolean,
    gen: number,
  ): Promise<InsertResult> {
    const ps = this.pageSize;
    if (!ps) { return 'done'; }
    const { left, right, boxHeight: boxH, top } = layout;
    const splitAvailH = layout.remainingHeight ?? 0;
    const boxWidth = right - left;
    let fittingInserted = false;

    if (splitAvailH > FONT_SIZE * 2) {
      let splitFitting: string | null = null;
      let splitOverflow: string | null = null;
      let splitHeight = splitAvailH;
      let splitLineCount = 0;

      try {

        const measureWidth = Math.max(24, boxWidth - WIDTH_ADJUSTMENT);
        const availableLineBudget = Math.floor(Math.max(0, splitAvailH - NOTE_RENDER_PAD) / DEVICE_LINE_HEIGHT);
        const measureAvailH = Math.max(FONT_SIZE,
          splitAvailH - NOTE_RENDER_PAD - emojiExtraHeight(text) - systemFontWrapExtraHeight(availableLineBudget));
        const split = await splitTextForHeight(text, measureWidth, FONT_SIZE, measureAvailH);
        if (split && split.didSplit && split.fittingText.trim().length > 0) {
          splitFitting = split.fittingText;
          splitOverflow = split.overflowText;
          splitHeight = split.fittingHeight;
          splitLineCount = split.fittingLineCount;
          log.i(TAG, `native split: fitting=${splitFitting.length} overflow=${splitOverflow.length}`);
        }
      } catch (e) {
        log.w(TAG, 'splitTextForHeight error', e);
      }

      if (!splitFitting && layout.lines > 0 && layout.charsPerLine > 0) {
        const lineH = boxH / layout.lines;
        const fittingLines = Math.max(1, Math.floor(splitAvailH / lineH) - 1);
        if (fittingLines < layout.lines) {
          const approxSplitChar = fittingLines * layout.charsPerLine;
          let splitAt = Math.min(approxSplitChar, text.length);
          const searchStart = Math.max(0, splitAt - Math.floor(splitAt * 0.15));
          const lastNL = text.lastIndexOf('\n', splitAt);
          if (lastNL >= searchStart) {
            splitAt = lastNL + 1;
          } else {
            const sentenceEnds = ['。', '！', '？', '.', '!', '?'];
            for (let i = splitAt - 1; i >= searchStart; i--) {
              if (sentenceEnds.includes(text[i])) {
                splitAt = i + 1;
                break;
              }
            }
          }
          const fit = text.substring(0, splitAt).trimEnd();
          const over = text.substring(splitAt).trimStart();
          if (fit.length > 0 && over.length > 0) {
            splitFitting = fit;
            splitOverflow = over;
            splitHeight = Math.ceil(fittingLines * lineH);
            splitLineCount = fittingLines;
            log.i(TAG, `JS fallback split: fitting=${fit.length} overflow=${over.length} fittingLines=${fittingLines}`);
          }
        }
      }

      if (splitFitting && splitFitting.trim().length > 0 && this.activeMode && alive()) {
        const fitTop = top;

        const fitBottom = Math.min(ps.height,
          fitTop + splitHeight + NOTE_RENDER_PAD + emojiExtraHeight(splitFitting)
            + systemFontWrapExtraHeight(splitLineCount));
        try {
          const fitRes = asResponse(await sdkCall(PluginNoteAPI.insertText({
            textContentFull: splitFitting,
            textRect: { left, top: fitTop, right, bottom: fitBottom },
            fontSize: FONT_SIZE,
            textAlign: 0,
            textBold: 0,
            textItalics: 0,
            textFrameWidthType: 0,
            textFrameStyle: 0,
            textEditable: 1,
          }), 'insertText(split-fit)'));
          if (gen !== this.generation) {
            log.i(TAG, `split insert finished after text receive stopped: success=${fitRes?.success === true}`);
            return 'done';
          }
          if (fitRes?.success) {
            log.i(TAG, `SplitInsert page=${this.targetPage} fittingLen=${splitFitting.length} ` +
              `overflowLen=${splitOverflow?.length ?? 0} fitH=${splitHeight}`);
            this.addToOccupied(fitTop, fitBottom);
            fittingInserted = true;
            if (splitOverflow && splitOverflow.trim().length > 0) {
              this.textQueue.unshift({ text: splitOverflow, source: itemSource });
            }
          }
        } catch (e) {
          log.w(TAG, 'split-fit insertText failed', e);
        }
      }
    }

    if (gen !== this.generation) { return 'done'; }
    if (!fittingInserted) {
      this.textQueue.unshift({ text, source: itemSource });
    }

    if (fittingInserted && this.textQueue.length === 0) {
      const actualBottom = alive() ? await this.readBackActualBottom() : null;
      if (gen !== this.generation) { return 'done'; }
      const maxReasonableBottom = ps.height;
      const effectiveBottom = (actualBottom !== null && actualBottom > top && actualBottom <= maxReasonableBottom)
        ? actualBottom : (top + (layout.remainingHeight ?? boxH));
      const newNextTop = effectiveBottom + layout.boxGap;
      this.pageNextTop.set(this.targetPage, newNextTop);
      this.onPositionChanged(this.targetPage, newNextTop, itemSource, false);
      return 'continue';
    }

    if (!this.activeMode) { return 'done'; }
    if (!alive()) { return 'continue'; }
    log.i(TAG, `PageFull page=${this.targetPage} queueLen=${this.textQueue.length}`);

    this.anchorMovedWhileFull = false;
    this.setWaitingForPageTurn(true);
    return 'pause';
  }

  private cancelDeferredTick(): void {
    this.cancelTick?.();
    this.cancelTick = null;
    this.deferredMode = null;
  }

  private deferNextTick(mode: TextInsertMode, delay: number): void {
    this.cancelTick?.();
    this.deferredMode = mode;
    const cancel = after(delay, () => {
      if (this.cancelTick !== cancel) { return; }
      this.cancelTick = null;
      if (this.deferredMode === mode && !this.inserting) {
        this.deferredMode = null;
        this.scheduleNext(mode);
      }
    });
    this.cancelTick = cancel;
  }

  private scheduleNext(mode: TextInsertMode): void {
    if (this.waitingForPageTap || !this.loopActive || this.textQueue.length === 0) {
      this.loopActive = false;
      this.unregisterPenUp();
      log.d(TAG, `idle (queue=${this.textQueue.length})`);
      return;
    }
    if (this.inserting) { return; }
    if (!this.isPenIdle()) {
      this.deferNextTick(mode, PEN_SAFE_GAP_MS);
      return;
    }

    this.inserting = true;
    this.lastScheduleAt = Date.now();
    const generation = this.generation;

    this.insertParagraph(mode, generation).then(result => {
      if (generation !== this.generation) {
        log.i(TAG, `insert of an earlier start finished: ${result}`);
        return;
      }
      this.inserting = false;
      if (result === 'continue' && this.loopActive) {
        this.deferNextTick(mode, this.textQueue.length > 0 ? 50 : INTERVAL_MS);
      } else if (result === 'pause') {
        if (this.waitingForPageTap) {
          this.loopActive = false;
          this.unregisterPenUp();
          log.i(TAG, 'page turned, waiting for bubble tap');
        } else {
          this.deferNextTick(mode, PAGE_WAIT_POLL_MS);
        }
      } else if (result === 'done') {
        this.loopActive = false;
        this.activeMode = null;
        log.i(TAG, 'stopped');
      }
    }).catch(e => {
      log.e(TAG, 'ScheduleError', e);
      if (generation !== this.generation) { return; }
      this.inserting = false;
      if (this.loopActive) { this.deferNextTick(mode, INTERVAL_MS * 5); }
    });
  }

  private async checkNoteContext(): Promise<boolean> {
    try {

      const fgElapsed = notipalHost.noteForegroundElapsedMs();
      const notInNoteApp = typeof fgElapsed === 'number' && fgElapsed < 0;

      const fp = asResponse(await sdkCall(PluginCommAPI.getCurrentFilePath(), 'getCurrentFilePath(check)'));
      const nowPath = fp?.success && typeof fp.result === 'string' ? fp.result : '';
      if (!notInNoteApp && nowPath && this.notePath && canonicalPath(nowPath) !== canonicalPath(this.notePath)) {
        const oldPath = this.notePath;
        log.w(TAG, `NoteSwitched old=${oldPath} new=${nowPath}`);
        this.stop(true);
        this.onNoteChanged('note_switched', `${oldPath} → ${nowPath}`);
        return false;
      }

      if (this.expectedTotalPages > 0 && this.notePath) {
        const tp = asResponse(await sdkCall(PluginFileAPI.getNoteTotalPageNum(this.notePath), 'getNoteTotalPageNum(check)'));
        if (tp?.success && typeof tp.result === 'number' && tp.result !== this.expectedTotalPages) {
          log.i(TAG, `PagesUpdated expected=${this.expectedTotalPages} actual=${tp.result} targetPage=${this.targetPage}`);
          this.expectedTotalPages = tp.result;
        }
      }
    } catch (e) {
      log.w(TAG, 'checkNoteContext error', e);
    }
    return true;
  }
}

function preprocess(input: string): string {
  let text = input;
  const rawLines = text.split('\n');
  const rawLineCount = rawLines.length;
  const emptyLineCount = rawLines.filter(l => l.trim().length === 0).length;
  text = text.replace(/<t>[\s\S]*?<\/t>\s*/g, '');
  text = text.replace(/<t>[^]*$/g, '');
  text = text.replace(/^[^]*?<\/t>\s*/g, '');
  text = text.replace(/^(\d+)\./gm, '$1​.');
  text = text.replace(/\*/g, '');
  text = text.replace(/`/g, '');
  // 旧版中继的预填占位 UUID；收件箱按发来的原文保存回复。末组可能在流式中途截断，所以是 {1,12}。
  text = text.replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{1,12}/gi, '');
  // BLANK 是一个看不见的占位，熬过下面的空行过滤后变回真正的空行（对话轮次的分隔）；开头的占位丢掉，笔记不以空行开头。
  const BLANK = '⁣';
  text = text.replace(/^(?:#{1,3}\s*)?(?:H|Human)\s*[:：].*$/gim, BLANK);
  text = text.replace(/^(?:#{1,3}\s*)?(?:A|Assistant)\s*[:：][ \t]*(.*)$/gim,
    (_, rest: string) => (rest && rest.trim().length > 0 ? `${BLANK}\n${rest}` : BLANK));
  text = text.split('\n').map(line => {
    const tl = line.trim();
    if (tl.startsWith('|') && tl.endsWith('|')) {
      const inner = tl.slice(1, -1);
      if (/^[-\s|]+$/.test(inner)) { return null; }
      return inner.split('|').map(c => c.trim()).filter(c => c.length > 0).join('  ');
    }
    return line;
  }).filter((line): line is string => {
    if (line === null) { return false; }
    const tl = line.trim();
    if (tl.length === 0) { return false; }
    if (/^-{2,}$/.test(tl)) { return false; }
    return true;
  }).join('\n');
  text = text
    .replace(new RegExp(`(?:${BLANK}\\n?)+`, 'g'), `${BLANK}\n`)
    .replace(new RegExp(`^(?:${BLANK}\\n)+`), '')
    .replace(new RegExp(`(?:\\n?${BLANK}\\n?)+$`), '')
    .replace(new RegExp(BLANK, 'g'), '');
  const cleanedLineCount = text.split('\n').length;
  log.i(TAG, `TextPreprocess rawLines=${rawLineCount} emptyLines=${emptyLineCount} cleanedLines=${cleanedLineCount} textLen=${text.length}`);
  return text;
}

// =====================================================================================================================
// 模式（状态机）
// =====================================================================================================================

/**
 * 等待翻页的子状态：none 正常接收；turn 本页放不下、等用户往后翻页（胶囊角标）；tap 已翻到新页、等用户点胶囊接着放。
 */
export type PageWait = 'none' | 'turn' | 'tap';

/**
 * 文本接收的状态。
 * - off：关闭。
 * - starting：笔记里正在开（要权限、开 LocalSend、读页面）；token 区分先后两次开，旧的结果到了丢掉。
 * - receiving：笔记里接收中，mode 是无间隔（nospacing）或段落（paragraph），wait 是等待翻页子状态。
 * - mosaic：Mosaic 白板上接收，文字合成卡片。
 */
export type TextState =
  | { kind: 'off' }
  | { kind: 'starting'; mode: TextInsertMode; token: number }
  | { kind: 'receiving'; mode: TextInsertMode; wait: PageWait }
  | { kind: 'mosaic' };

/**
 * - start：开始（工具栏点按、收文字时打开、换笔记后重开）；started：启动结果，token 对不上的丢掉。
 * - pageWait：插入循环的等待翻页变了。
 * - mosaic：白板上打开。
 * - stop：关掉（再点一次按钮、打开设置、进文档、进出 Mosaic、转屏、工具栏会话结束、启动失败）。
 */
export type TextEvent =
  | { type: 'start'; mode: TextInsertMode; token: number }
  | { type: 'started'; mode: TextInsertMode; token: number; ok: boolean }
  | { type: 'pageWait'; wait: PageWait }
  | { type: 'mosaic' }
  | { type: 'stop'; reason: string };

class TextMachine extends StateMachine<TextState, TextEvent> {
  constructor() {
    super('TextReceive', { kind: 'off' });
  }

  protected reduce(state: TextState, event: TextEvent): TextState {
    switch (event.type) {
      case 'start':
        return { kind: 'starting', mode: event.mode, token: event.token };
      case 'started':
        if (state.kind !== 'starting' || state.token !== event.token) { return state; }
        return event.ok ? { kind: 'receiving', mode: event.mode, wait: currentWait() } : { kind: 'off' };
      case 'pageWait':
        return state.kind === 'receiving' ? { ...state, wait: event.wait } : state;
      case 'mosaic':
        return { kind: 'mosaic' };
      case 'stop':
        return { kind: 'off' };
    }
  }

  protected onEnter(state: TextState, from: TextState): void {
    log.i(TAG, `mode ${from.kind} → ${state.kind}`);
    switch (state.kind) {
      case 'off':
        enterOff(from);
        break;
      case 'starting':
        // 换笔记后重开时胶囊留着，等的文字丢掉（手册 1.8.2：切换笔记时等待中的文字被丢弃）。
        if (from.kind === 'mosaic') { leaveMosaic(); }
        pendingCards = [];
        syncBubble();
        break;
      case 'receiving':
        if (from.kind === 'starting') {
          pushPageSize();
          if (TEXT_AVAILABLE) { notipalText.showBubble(); }
        }
        syncBubble();
        break;
      case 'mosaic':
        if (from.kind !== 'off') { stopInserter(); }
        mosaicBuffer = [];
        syncBubble();
        if (TEXT_AVAILABLE) { notipalText.showBubble(); }
        log.i(TAG, 'MosaicText mode ON');
        break;
    }
  }
}

/** 笔记里点胶囊之前等着的文字（旧 _pendingCards）。 */
let pendingCards: QueueItem[] = [];
/** 白板上点胶囊之前等着的文字（旧 _mosaicTextBuffer）。 */
let mosaicBuffer: string[] = [];
/** 插入点是程序定的（换页、AI 回复的位置）：胶囊上屏时报来的位置不改它（旧 _aiPositionLocked）。 */
let anchorLocked = false;
/** 上次的接收方式（旧 LAST_MODE_STORE_KEY 预设，现在是预设 JSON 的 textInsertMode）。 */
let lastMode: TextInsertMode = 'nospacing';
let startToken = 0;
let lastPermissionTipAt = 0;

const inserter = new Inserter(handlePositionChanged, handleNoteChanged, handlePageWaitChanged);
const machine = new TextMachine();

function currentWait(): PageWait {
  if (inserter.isWaitingForPageTap()) { return 'tap'; }
  if (inserter.isWaitingForPageTurn()) { return 'turn'; }
  return 'none';
}

function noteActive(): boolean {
  const kind = machine.state.kind;
  return kind === 'starting' || kind === 'receiving';
}

function stopInserter(): void {
  inserter.clearQueue();
  inserter.stop(true);
  pendingCards = [];
  anchorLocked = false;
}

function leaveMosaic(): void {
  if (mosaicBuffer.length > 0) { log.i(TAG, `MosaicText dropped ${mosaicBuffer.length} staged text(s)`); }
  mosaicBuffer = [];
  log.i(TAG, 'MosaicText mode OFF');
}

function enterOff(from: TextState): void {
  if (from.kind === 'mosaic') { leaveMosaic(); }
  if (pendingCards.length > 0) { log.i(TAG, `dropping ${pendingCards.length} staged card(s)`); }
  stopInserter();
  mosaicBuffer = [];
  if (TEXT_AVAILABLE) {
    notipalText.setBubbleState(false, false);
    notipalText.hideBubble();
  }
}

/** 胶囊的样子：有文字等着放（或翻到新页等点按）时反白；本页放不下、在等翻页时有角标。 */
function syncBubble(): void {
  if (!TEXT_AVAILABLE) { return; }
  if (machine.state.kind === 'mosaic') {
    notipalText.setBubbleState(mosaicBuffer.length > 0, false);
    return;
  }
  const pending = pendingCards.length > 0 || inserter.isWaitingForPageTap();
  notipalText.setBubbleState(pending, !pending && inserter.isWaitingForPageTurn());
}

function pushPageSize(): void {
  const ps = inserter.getPageSize();
  if (ps && TEXT_AVAILABLE) { notipalText.setPageSize(ps.width, ps.height); }
}

/** 工具栏按钮的反白跟着模式走（旧 _pushActiveModes）。 */
function syncToolbar(): void {
  if (!hasNativeModule('NotipalToolbar')) { return; }
  notipalToolbar.setActive(TOOL.INSERT_TEXT, machine.state.kind !== 'off');
}

machine.addListener((old, next) => {
  if (old.kind !== next.kind) { syncToolbar(); }
});

// ---- 插入循环的回调 ----

function handlePositionChanged(_page: number, nextTop: number, _source: TextSource, isPageChange: boolean): void {
  if (!isPageChange || !TEXT_AVAILABLE) { return; }
  pushPageSize();
  const left = inserter.getNextLeft();
  if (left !== undefined) {
    anchorLocked = true;
    notipalText.showBubbleAt(left, nextTop);
    log.i(TAG, `pageChange showAt left=${left} top=${nextTop}`);
  } else {
    anchorLocked = false;
    notipalText.showBubble();
  }
}

/** 换了一本笔记：等着的文字丢掉，按原来的方式在新笔记里重开；不在笔记里了就关掉。 */
function handleNoteChanged(reason: 'note_switched', detail: string): void {
  log.w(TAG, `NoteContextChanged ${reason}: ${detail}`);
  const state = machine.state;
  pendingCards = [];
  syncBubble();
  if (state.kind !== 'receiving' || hostState().surface === 'document') {
    machine.send({ type: 'stop', reason });
    return;
  }
  inserter.clearQueue();
  startNote(state.mode, { preserve: false, saveMode: false }).catch(e => log.e(TAG, 'restart after note switch failed', e));
}

function handlePageWaitChanged(): void {
  log.i(TAG, `page continuation waitTap=${inserter.isWaitingForPageTap()} waitTurn=${inserter.isWaitingForPageTurn()} ` +
    `queue=${inserter.getQueueLength()}`);
  machine.send({ type: 'pageWait', wait: currentWait() });
  syncBubble();
}

// ---- 开与关 ----

/** 文件读写权限（先读后写）；没给时提示，1.5 秒内只提示一次。 */
async function ensurePermissions(): Promise<boolean> {
  if (!hasNativeModule('NotipalHost')) { return true; }
  const readGranted = await notipalHost.requestPermission('file_read');
  const writeGranted = readGranted ? await notipalHost.requestPermission('file_write') : false;
  const ready = readGranted && writeGranted;
  log.i(TAG, `text receiver permissions read=${readGranted} write=${writeGranted} ready=${ready}`);
  if (!ready && Date.now() - lastPermissionTipAt > 1500) {
    lastPermissionTipAt = Date.now();
    await dialog.tip(t('initial_permissions_required'));
  }
  return ready;
}

/** 由 LocalSend（Task 20）设置：开 LocalSend 服务，开不了（没 Wi-Fi、没权限）时自己提示并返回 false。 */
export const textHooks: { startLocalSend: () => Promise<boolean> } = {
  startLocalSend: async () => {
    log.w(TAG, 'LocalSend not wired yet: text receive opens without it');
    return true;
  },
};

/** 收文字要的东西：权限和 LocalSend（旧 _ensureTextReceiverRuntime）。 */
async function ensureRuntime(): Promise<boolean> {
  if (!await ensurePermissions()) { return false; }
  return textHooks.startLocalSend();
}

/**
 * 在笔记里开始接收（旧 switchToMode / _startTextInserter）。preserve：保留各页的插入点；saveMode：记成上次的方式。
 * 第一次开不起来隔 800ms、2000ms 各再试一次。途中被停掉（再点一次、进文档……）时放弃，返回 false。
 */
async function startNote(
  mode: TextInsertMode,
  opts: { preserve: boolean; saveMode: boolean },
): Promise<boolean> {
  if (hostState().surface === 'document') {
    log.i(TAG, `SwitchModeRejected mode=${mode} in document`);
    return false;
  }
  const token = ++startToken;
  machine.send({ type: 'start', mode, token });
  const isCurrent = () => {
    const s = machine.state;
    return s.kind === 'starting' && s.token === token;
  };
  if (!await ensureRuntime()) {
    if (isCurrent()) { machine.send({ type: 'stop', reason: 'runtime not ready' }); }
    return false;
  }
  if (!isCurrent()) { return false; }

  const previousMode = lastMode;
  if (opts.saveMode) {
    lastMode = mode;
    presets.update(p => { p.textInsertMode = mode; }).catch(e => log.w(TAG, 'save text mode failed', e));
  }

  inserter.clearQueue();
  let ok = await inserter.start(mode, opts.preserve, isCurrent);
  if (!isCurrent()) { return false; }
  if (!ok) {
    for (const delay of [800, 2000]) {
      await sleep(delay);
      if (!isCurrent()) { return false; }
      log.i(TAG, `StartRetry mode=${mode} delay=${delay}`);
      ok = await inserter.start(mode, opts.preserve, isCurrent);
      if (!isCurrent()) { return false; }
      if (ok) { break; }
    }
  }
  if (ok && opts.saveMode) { inserter.applyModeSwitchGap(previousMode, mode); }
  machine.send({ type: 'started', mode, token, ok });
  log.i(TAG, `ToggleMode target=${mode} result=${ok ? mode : 'off'}`);
  return ok;
}

/** 白板上开始接收（旧 toggleMosaicTextMode 的打开）。 */
async function startMosaic(): Promise<boolean> {
  if (!await ensureRuntime()) { return false; }
  if (hostState().surface !== 'mosaic') {
    log.i(TAG, 'MosaicText start dropped: board gone');
    return false;
  }
  machine.send({ type: 'mosaic' });
  return true;
}

/** 关掉文本接收（笔记和白板都停）。不调宿主 API，退出模式时在别的动作进行中也能调。 */
export function stopTextReceive(reason: string): void {
  ++startToken;
  machine.send({ type: 'stop', reason });
}

/** 工具栏“文本接收”被点（旧 toggleMode(lastMode)）：白板上开关白板模式；文档里提示不支持；笔记里开或关。 */
async function onToolTap(ctx: HostCtx): Promise<void> {
  try {
    if (ctx.surface === 'mosaic') {
      if (machine.state.kind === 'mosaic') { stopTextReceive('tool tap'); }
      else { await startMosaic(); }
      return;
    }
    if (ctx.surface === 'document') {
      await dialog.tip(t('doc_text_receive_unsupported'));
      return;
    }
    if (noteActive()) {
      stopTextReceive('tool tap');
      return;
    }
    await startNote(lastMode, { preserve: true, saveMode: true });
  } finally {
    // 原生点下时已先点亮；文档里拒绝、权限失败等路径按实际状态回写。
    syncToolbar();
  }
}

async function onToolAction(ctx: HostCtx, ev: ToolbarAction): Promise<void> {
  if (ev.gesture === 'exit') {
    // 再点一次、或打开设置：只停模式，等着的文字丢掉（旧 stopTextReceive / stopMode）。
    stopTextReceive(`tool exit (${ev.host.surface})`);
    return;
  }
  if (ev.gesture === 'tap') { await onToolTap(ctx); }
}

// ---- 宿主界面的变化 ----

function onHostChanged(next: HostStatePayload, prev: HostStatePayload): void {
  // 进出 Mosaic（白板和笔记卡片之间切换不算）：文本接收关掉（旧 _closeSurfaceModes）。
  if ((prev.surface === 'mosaic') !== (next.surface === 'mosaic')) {
    if (machine.state.kind !== 'off') {
      log.i(TAG, `MosaicSurface ${next.surface === 'mosaic' ? 'enter' : 'leave'}: closed text`);
      stopTextReceive(`mosaic ${next.surface === 'mosaic' ? 'enter' : 'leave'}`);
    }
  }
  if (next.surface === 'document' && prev.surface !== 'document') { stopForDocument(); }
  // 回到笔记或文档：宿主还在把页面画回来，1.5 秒内不插（旧 onNoteForegroundChanged）。
  if (next.inHostApp && !prev.inHostApp) { inserter.holdInserts(NOTE_FOREGROUND_SETTLE_MS); }
}

/** 进了文档：文本接收关掉并提示，之后收到的文字进系统剪贴板（旧 _stopTextReceiveForDocument）。 */
function stopForDocument(): void {
  const hadText = machine.state.kind !== 'off' || inserter.isRunning()
    || mosaicBuffer.length > 0 || pendingCards.length > 0;
  if (!hadText) { return; }
  stopTextReceive('document');
  log.i(TAG, 'DocTextReceiveStop');
  dialog.tip(t('doc_text_receive_stop')).catch(e => log.w(TAG, 'tip failed', e));
}

// ---- 胶囊 ----

/** 胶囊的位置定下一条的插入点：胶囊底边往下 4 像素、左边对齐（旧 applyBubblePosition）。白板上不用。 */
function applyBubblePosition(d: TextBubbleEvent, source: string): void {
  if (machine.state.kind === 'mosaic') { return; }
  const pageBottomY = d.pageBottomY > d.pageY ? d.pageBottomY : d.pageY;
  const insertTop = pageBottomY + 4;
  const anchor = inserter.setNextInsertionAnchor(insertTop, d.pageX, source);
  anchorLocked = true;
  log.i(TAG, `applyBubblePosition source=${source} raw=${JSON.stringify(d)} accepted=${JSON.stringify(anchor)}`);
}

/** 白板上点胶囊：等着的几段合成一张卡片，放在胶囊右下角（屏幕像素）。 */
function flushMosaicCard(d: TextBubbleEvent): void {
  if (mosaicBuffer.length === 0) { return; }
  const text = mosaicBuffer.join('\n\n');
  mosaicBuffer = [];
  const anchorX = Math.round(d.screenX + (d.bubbleWidth ?? 0));
  const anchorY = Math.round(d.screenBottomY);
  notipalMosaic.textCard(text, anchorX, anchorY);
  log.i(TAG, `MosaicText flush anchor=(${anchorX},${anchorY}) len=${text.length}`);
  syncBubble();
}

/** 笔记里点胶囊：先对上当前页，胶囊位置定插入点，等着的文字进队列；翻到新页在等点按的接着插。 */
async function flushPendingCards(d: TextBubbleEvent): Promise<void> {
  try {
    const pg = asResponse(await sdkCall(PluginCommAPI.getCurrentPageNum(), 'getCurrentPageNum(bubble)'));
    if (pg?.success && typeof pg.result === 'number' && pg.result !== inserter.getTargetPage()) {
      inserter.relocateTo(pg.result);
    }
  } catch (e) {
    log.w(TAG, 'bubble tap page lookup failed', e);
  }
  if (!noteActive()) { return; }
  applyBubblePosition(d, 'user-tap');

  const cards = pendingCards;
  pendingCards = [];
  if (cards.length > 0) {
    log.i(TAG, `FlushCards count=${cards.length}`);
    for (const c of cards) { inserter.enqueue(c.text, c.source); }
  }
  if (inserter.continueAfterPageTap()) {
    log.i(TAG, `ContinueAfterPageTap page=${inserter.getTargetPage()} queueLen=${inserter.getQueueLength()}`);
  }
  syncBubble();
}

/** `text:bubble` 的处理函数，index.js 接上。 */
export function onBubble(d: TextBubbleEvent): void {
  const kind = machine.state.kind;
  if (kind === 'off') {
    log.w(TAG, `bubble ${d.kind} while off`);
    return;
  }
  switch (d.kind) {
    case 'tap':
      if (kind === 'starting') {
        // 换笔记后正在重开：页面尺寸、插入点还是上一本的，等开好再点。
        log.i(TAG, 'text bubble tapped while starting: ignored');
        return;
      }
      if (kind === 'mosaic') {
        log.i(TAG, `mosaic text bubble tapped, pending=${mosaicBuffer.length}`);
        flushMosaicCard(d);
        return;
      }
      log.i(TAG, `text bubble tapped, pending=${pendingCards.length}`);
      anchorLocked = false;
      flushPendingCards(d).catch(e => log.w(TAG, 'text bubble tap failed', e));
      return;
    case 'longPress':
      if (kind === 'mosaic') {
        if (mosaicBuffer.length > 0) {
          mosaicBuffer = [];
          log.i(TAG, 'MosaicText buffer cleared (long press)');
          syncBubble();
        }
        return;
      }
      if (pendingCards.length > 0 || inserter.isWaitingForPageTap()) {
        const staged = pendingCards.length;
        const queued = inserter.getQueueLength();
        pendingCards = [];
        inserter.clearQueue();
        syncBubble();
        log.i(TAG, `ClearTextQueue staged=${staged} queued=${queued}`);
      }
      return;
    case 'drag':
      anchorLocked = false;
      applyBubblePosition(d, 'user-drag');
      return;
    case 'layout':
      if (anchorLocked) {
        log.d(TAG, `ignored programmatic bubble layout=${JSON.stringify(d)}`);
        return;
      }
      applyBubblePosition(d, 'initial-layout');
      return;
  }
}

/** `text:bubbleClosed` 的处理函数：转屏、进出 Mosaic、工具栏会话结束时窗口那边关了胶囊。 */
export function onBubbleClosed(_ev: NativeEvents['text:bubbleClosed']): void {
  stopTextReceive('bubble closed by window change');
}

/** `toolbar:closed` 的处理函数：工具栏会话结束，文本接收停掉（旧 onDestroyAll → stopAllModes）。 */
export function onToolbarClosed(_ev: NativeEvents['toolbar:closed']): void {
  stopTextReceive('toolbar closed');
}

// ---- 收到文字 ----

function stagePendingCard(text: string, source: TextSource): void {
  pendingCards.push({ text, source });
  log.i(TAG, `StageCard source=${source} pending=${pendingCards.length}`);
  syncBubble();
}

function stageMosaicText(text: string, source: TextSource): void {
  if (text.length === 0) { return; }
  mosaicBuffer.push(text);
  log.i(TAG, `MosaicText staged source=${source} pending=${mosaicBuffer.length}`);
  syncBubble();
}

/**
 * LocalSend 收到一段文字、而文本接收开着时（Task 20 调；旧 _routeLocalSendText）：白板上进卡片缓存，文档里进系统剪贴板，
 * 笔记里等在胶囊里。文本接收其实没开时也进系统剪贴板（旧版排进不会再插的队列，文字就丢了）。
 */
export function onTextReceived(text: string): void {
  log.i(TAG, `text received (LocalSend) len=${text.length}`);
  const kind = machine.state.kind;
  if (kind === 'mosaic') {
    stageMosaicText(text, 'localsend');
  } else if (hostState().surface === 'document') {
    notipalClipboard.copyText(text);
    log.i(TAG, 'DocTextToClipboard localsend');
  } else if (noteActive()) {
    stagePendingCard(text, 'localsend');
  } else {
    notipalClipboard.copyText(text);
    log.w(TAG, 'text arrived while text receive is off: copied to clipboard');
  }
}

/**
 * 收到文字时的提示框里点了“确认”（Task 20 调；旧 onOpenTextReceiverRequested）：按所在位置打开文本接收（白板开白板模式，
 * 文档里提示不支持），这段文字进胶囊等着放。弹框时文字已进剪贴板，这里打不开也不会丢内容。
 */
export async function openReceiverWithText(text: string): Promise<void> {
  const surface = hostState().surface;
  log.i(TAG, `opening text receiver from LocalSend prompt mode=${lastMode} surface=${surface}`);
  if (surface === 'document') {
    await dialog.tip(t('doc_text_receive_unsupported'));
    return;
  }
  let ok: boolean;
  if (surface === 'mosaic') {
    ok = machine.state.kind === 'mosaic' || await startMosaic();
  } else {
    ok = noteActive() || await startNote(lastMode, { preserve: true, saveMode: true });
  }
  syncToolbar();
  log.i(TAG, `LocalSend prompt text receiver result=${ok}`);
  if (ok) { onTextReceived(text); }
}

/**
 * AI 回复要插进页面（Task 25 调；旧 onTextFromRelay）：白板上进卡片缓存（文本接收没开时打开白板模式），文档里不插，
 * 笔记里没开就按无间隔打开，文字等在胶囊里。返回去了哪里，Task 25 据此提示。
 */
export async function relayText(text: string): Promise<'mosaic' | 'document' | 'staged' | 'off'> {
  const surface = hostState().surface;
  if (surface === 'mosaic') {
    if (machine.state.kind !== 'mosaic') { machine.send({ type: 'mosaic' }); }
    stageMosaicText(text, 'broadcast');
    return 'mosaic';
  }
  if (surface === 'document') { return 'document'; }
  if (!noteActive() && !await startNote('nospacing', { preserve: false, saveMode: false })) { return 'off'; }
  stagePendingCard(text, 'broadcast');
  return 'staged';
}

/** 把下一条的插入点定在页面坐标 (left, top) 并把胶囊挪过去（Task 25 用；旧 setProgrammaticInsertionAnchor + showTextBubbleAtInsertion）。 */
export function placeAnchor(top: number, left: number, source: string): void {
  if (!noteActive()) { return; }
  const anchor = inserter.setNextInsertionAnchor(top, left, source);
  anchorLocked = true;
  pushPageSize();
  if (TEXT_AVAILABLE) { notipalText.showBubbleAt(anchor.left, anchor.top); }
}

/**
 * AI 选区让笔圈页面时胶囊让开（aside 为真时藏起），圈完放回（笔记里在收文字时才放回）。旧 BackgroundService 的
 * FloatingBubbleBridge.hide() 与 restoreBubbleAfterLasso 里的 show。
 */
export function setBubbleAside(aside: boolean): void {
  if (!TEXT_AVAILABLE) { return; }
  if (aside) { notipalText.hideBubble(); return; }
  if (noteActive()) { notipalText.showBubble(); }
}

/** 当前状态（Task 20、25 判断用）。 */
export function textState(): TextState {
  return machine.state;
}

/** index.js 启动时调一次：登记工具栏动作，读上次的方式，接宿主界面变化；新的 JS 实例从“关闭”开始。 */
export function register(): void {
  registerAction(TOOL.INSERT_TEXT, onToolAction);
  onHostChange(onHostChanged);
  presets.load().then(p => { lastMode = p.textInsertMode; }, e => log.w(TAG, 'load text mode failed', e));
  if (TEXT_AVAILABLE) { notipalText.hideBubble(); }
  syncToolbar();
}
