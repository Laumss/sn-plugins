import { PluginCommAPI, PluginFileAPI, PluginNoteAPI } from 'sn-plugin-lib';
import HostDialog from './HostDialog';
import { FileLogger } from './FileLogger';
import FloatingToolbarBridge, { type Subscription } from './FloatingToolbarBridge';
import { NativeModules } from 'react-native';
import { t } from './i18n';

const { AIRelayModule } = NativeModules;

export interface SelectionPage {
  filePath: string;
  pageNum: number;
  width: number;
  height: number;
}
type SelectionRect = { left: number; top: number; right: number; bottom: number };
type PenLassoResult =
  | { status: 'selected'; page: SelectionPage; rect: SelectionRect }
  | { status: 'cancelled' | 'empty' | 'stale' | 'error' | 'no_page' };

export interface AiSelectionRequest {
  expectedPage?: SelectionPage;
  canContinue?: () => boolean;
}

export async function selectionRequestIsCurrent(request?: AiSelectionRequest): Promise<boolean> {
  if (request?.canContinue && !request.canContinue()) return false;
  if (request?.expectedPage && !await isSameSelectionPage(request.expectedPage)) return false;
  return !request?.canContinue || request.canContinue();
}

const HOST_NO_PAGE_CODE = 97;
export function isHostNoPageError(response: any): boolean {
  const error = response?.error ?? response;
  return error?.code === HOST_NO_PAGE_CODE ||
    /page does not exist/i.test(String(error?.message ?? ''));
}

export function showHostNoPageHint(): void {
  try { HostDialog.tip(t('doc_last_page_lasso')); } catch (_) {}
}

function hostPagePath(filePath: string): string {
  return /\.pdf$/i.test(filePath) ? `${filePath}.mark` : filePath;
}

export async function readSelectionPage(): Promise<SelectionPage | 'no_page' | null> {
  const [fp, pg]: any[] = await Promise.all([
    PluginCommAPI.getCurrentFilePath(), PluginCommAPI.getCurrentPageNum(),
  ]);
  if (!fp?.success || typeof fp.result !== 'string' || !fp.result ||
      !pg?.success || !Number.isInteger(pg.result)) return null;
  const size: any = await PluginFileAPI.getPageSize(hostPagePath(fp.result), pg.result);
  if (isHostNoPageError(size)) return 'no_page';
  const width = size?.result?.width, height = size?.result?.height;
  if (!size?.success || !Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) return null;
  return { filePath: fp.result, pageNum: pg.result, width, height };
}

export async function isSameSelectionPage(expected: SelectionPage): Promise<boolean> {
  const current = await readSelectionPage();
  return !!current && current !== 'no_page' && current.filePath === expected.filePath && current.pageNum === expected.pageNum &&
    current.width === expected.width && current.height === expected.height;
}

export function validSelectionRect(rect: any): rect is SelectionRect {
  return !!rect && [rect.left, rect.top, rect.right, rect.bottom].every(Number.isFinite) &&
    rect.right > rect.left && rect.bottom > rect.top;
}

export async function hasSelectedContent(): Promise<boolean | 'no_page'> {
  const rect: any = await PluginCommAPI.getLassoRect();
  if (isHostNoPageError(rect)) return 'no_page';
  if (!rect?.success || !validSelectionRect(rect.result)) return false;
  const elements: any = await PluginCommAPI.getLassoElements();
  if (isHostNoPageError(elements)) return 'no_page';
  if (elements?.success && Array.isArray(elements.result)) {
    const selected = elements.result.length > 0;
    for (const el of elements.result) el?.recycle?.();
    if (selected) return true;
  }

  try {
    const links = await PluginNoteAPI.getLassoLinks?.();
    return links?.success === true && Array.isArray(links.result) && links.result.length > 0;
  } catch (_) { return false; }
}

let sequence = 0;
let current: { id: string; promise: Promise<PenLassoResult>; cancel(): void } | null = null;
const BBOX_PADDING_PX = 100;

export const PenLasso = {
  arm(options: { page?: SelectionPage } = {}): Promise<PenLassoResult> {
    if (current) return current.promise;
    const id = `selection-${Date.now()}-${++sequence}`;
    let settle!: (result: PenLassoResult) => void;
    const promise = new Promise<PenLassoResult>(resolve => { settle = resolve; });
    let settled = false, processing = false;

    let hostSelectionMade = false;
    let bboxSub: Subscription | null = null;
    let cancelSub: Subscription | null = null;
    const alive = () => !settled && current?.id === id;
    const clearHostSelection = () => {
      PluginCommAPI.setLassoBoxState(2).catch((e: unknown) =>
        FileLogger.logEvent('PenLasso', `request=${id} orphan selection clear failed: ${String(e)}`));
    };
    const finish = (result: PenLassoResult) => {
      if (settled) return;
      settled = true;
      bboxSub?.remove(); cancelSub?.remove();
      if (result.status !== 'selected') {
        FloatingToolbarBridge.cancelPenLassoSelection(id);
        if (hostSelectionMade) clearHostSelection();
      }
      if (current?.id === id) current = null;
      FloatingToolbarBridge.restoreToolbar();
      settle(result);
    };
    current = { id, promise, cancel: () => finish({ status: 'cancelled' }) };
    (async () => {
      const page = options.page ?? await readSelectionPage();
      if (!alive()) return;
      if (page === 'no_page') return finish({ status: 'no_page' });
      if (!page) return finish({ status: 'error' });
      bboxSub = FloatingToolbarBridge.onPenLassoBbox(event => {
        if (!alive() || processing || event.requestId !== id) return;
        processing = true;
        (async () => {
          if (!await isSameSelectionPage(page)) return finish({ status: 'stale' });
          if (!alive()) return;
          if (!validSelectionRect(event)) return finish({ status: 'empty' });
          const rect = {
            left: Math.max(0, Math.floor(event.left) - BBOX_PADDING_PX),
            top: Math.max(0, Math.floor(event.top) - BBOX_PADDING_PX),
            right: Math.min(page.width, Math.ceil(event.right) + BBOX_PADDING_PX),
            bottom: Math.min(page.height, Math.ceil(event.bottom) + BBOX_PADDING_PX),
          };
          if (!validSelectionRect(rect)) return finish({ status: 'empty' });
          FileLogger.logEvent('PenLasso', `request=${id} create rect=${JSON.stringify(rect)}`);

          try {
            const synced = await AIRelayModule?.prepareLivePageForSelection?.(
              page.filePath, page.pageNum, page.width, page.height);
            FileLogger.logEvent('PenLasso', `request=${id} live-page-sync=${synced === true}`);
          } catch (e) {
            FileLogger.logEvent('PenLasso', `request=${id} live-page-sync-error=${String(e)}`);
          }
          if (!alive() || !await isSameSelectionPage(page)) return finish({ status: 'stale' });
          const selected = await PluginCommAPI.lassoElements(rect);
          hostSelectionMade = selected?.success === true && selected.result !== false;
          if (!alive()) {

            if (hostSelectionMade) clearHostSelection();
            return;
          }
          if (!hostSelectionMade) return finish({ status: 'empty' });
          if (!await isSameSelectionPage(page)) return finish({ status: 'stale' });
          if (!alive()) return;
          const shown = await PluginCommAPI.setLassoBoxState(0);
          if (!alive()) return;
          if (shown?.success === false) return finish({ status: 'error' });
          const hasContent = await hasSelectedContent();
          if (!alive()) return;
          if (hasContent === 'no_page') return finish({ status: 'no_page' });
          if (!hasContent) return finish({ status: 'empty' });
          if (!await isSameSelectionPage(page)) return finish({ status: 'stale' });
          if (!alive()) return;

          const completed = await FloatingToolbarBridge.finishPenLassoSelection(id);
          if (!alive()) return;
          if (!completed) return finish({ status: 'cancelled' });
          FileLogger.logEvent('PenLasso', `request=${id} selection ready`);
          finish({ status: 'selected', page, rect });
        })().catch(e => { FileLogger.logEvent('PenLasso', String(e)); finish({ status: 'error' }); });
      });
      cancelSub = FloatingToolbarBridge.onPenLassoCancel(event => {
        if (event?.requestId === id) finish({ status: 'cancelled' });
      });
      FloatingToolbarBridge.beginPenLassoSelection(id);
    })().catch(e => { FileLogger.logEvent('PenLasso', String(e)); finish({ status: 'error' }); });
    return promise;
  },
  cancel(): void { current?.cancel(); },
};
