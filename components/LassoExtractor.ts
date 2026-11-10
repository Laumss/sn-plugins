import { PluginCommAPI, PluginFileAPI, PluginNoteAPI } from 'sn-plugin-lib';
import HostDialog from './HostDialog';
import { getRNFS } from './rnfs';
import { FileLogger } from './FileLogger';
import FloatingToolbarBridge from './FloatingToolbarBridge';
import { t } from './i18n';
import { isHostNoPageError, showHostNoPageHint } from './PenTools';

interface LinkedFile {
  path: string;
  linkType: number;
  label: string;
}

export interface ExtractedContent {
  text: string;
  imagePaths: string[];
  linkedFiles: LinkedFile[];

  noPage?: boolean;

  lastTextBoxRect?: { left: number; top: number; right: number; bottom: number };

  lassoRect?: { left: number; top: number; right: number; bottom: number };
  stats: {
    strokes: number;
    textBoxes: number;
    pictures: number;
    others: number;
    recognizedStrokes: number;
  };
}

export type ExtractStage = 'recognizing' | 'done';
export type ExtractProgress = (stage: ExtractStage) => void;

let _lassoDataPrePopulated = false;
export function markLassoDataPrePopulated(): void { _lassoDataPrePopulated = true; }
export function consumeLassoDataPrePopulated(): boolean {
  const value = _lassoDataPrePopulated;
  _lassoDataPrePopulated = false;
  return value;
}

export class LassoExtractor {

  static async extract(onProgress?: ExtractProgress): Promise<ExtractedContent> {
    const result: ExtractedContent = {
      text: '',
      imagePaths: [],
      linkedFiles: [],
      stats: { strokes: 0, textBoxes: 0, pictures: 0, others: 0, recognizedStrokes: 0 },
    };

    const res = await PluginCommAPI.getLassoElements() as any;
    if (!res?.success || !Array.isArray(res.result)) {
      FileLogger.logEvent('LassoExtract', `getLassoElements failed: ${res?.error?.message ?? 'unknown'}`);
      if (isHostNoPageError(res)) {
        showHostNoPageHint();
        result.noPage = true;
      }
      return result;
    }

    const elements = res.result;
    FileLogger.logEvent('LassoExtract', `found ${elements.length} elements`);

    try {
      const lr: any = await PluginCommAPI.getLassoRect();
      if (lr?.success && lr.result &&
          typeof lr.result.bottom === 'number' && typeof lr.result.left === 'number') {
        result.lassoRect = {
          left: lr.result.left, top: lr.result.top,
          right: lr.result.right, bottom: lr.result.bottom,
        };
      }
    } catch (e) {
      FileLogger.logEvent('LassoExtract', `getLassoRect failed (non-fatal): ${String(e)}`);
    }

    type SortableItem = {
      sortY: number;
      text?: string;
      imagePath?: string;
      rect?: { left: number; top: number; right: number; bottom: number };
    };
    const sortable: SortableItem[] = [];
    const unrecognizedStrokes: { el: any; sortY: number }[] = [];

    for (const el of elements) {
      try {
        switch (el.type) {
          case 500:
          case 501:
          case 502: {
            result.stats.textBoxes++;
            const content = el.textBox?.textContentFull ?? '';
            const rect = el.textBox?.textRect as
              | { left: number; top: number; right: number; bottom: number }
              | undefined;
            if (content.trim()) {
              sortable.push({ sortY: rect?.top ?? 0, text: content.trim(), rect });
            }
            break;
          }

          case 0: {
            result.stats.strokes++;
            let sortY = 0;
            try {
              const cSize = await el.contoursSrc?.size?.();
              if (cSize && cSize > 0) {
                const firstContour = await el.contoursSrc.get(0);
                if (Array.isArray(firstContour) && firstContour.length > 0) {
                  sortY = firstContour[0].y ?? 0;
                }
              }
            } catch (_) {}
            unrecognizedStrokes.push({ el, sortY });
            break;
          }

          case 200: {
            result.stats.pictures++;

            const imgPath = el.picture?.picturePath ?? el.picture?.path;
            if (imgPath) {
              sortable.push({ sortY: 0, imagePath: imgPath });
              result.imagePaths.push(imgPath);
            }
            break;
          }

          default:
            result.stats.others++;
            break;
        }
      } catch (e) {
        console.warn('[LassoExtractor]: error processing element', el.type, e);
      }
    }

    if (unrecognizedStrokes.length > 0) {
      onProgress?.('recognizing');
      try {

        const canWrite = await FloatingToolbarBridge.requestFileWritePermission();
        if (!canWrite) {
          FileLogger.logEvent('LassoExtract', 'recognizeElements skipped: no FILE:WRITE permission');
          try { HostDialog.tip(t('perm_required')); } catch (_) {}
          throw new Error('no FILE:WRITE permission');
        }
        const fpRes: any = await PluginCommAPI.getCurrentFilePath();
        const pgRes: any = await PluginCommAPI.getCurrentPageNum();
        if (fpRes?.success && fpRes.result && pgRes?.success && pgRes.result !== undefined) {
          const psRes: any = await PluginFileAPI.getPageSize(fpRes.result, pgRes.result);
          if (psRes?.success && psRes.result) {
            const els = unrecognizedStrokes.map(u => u.el);
            const ocrRes: any = await PluginCommAPI.recognizeElements(els, psRes.result);
            if (ocrRes?.success && typeof ocrRes.result === 'string' && ocrRes.result.trim()) {
              const minSortY = Math.min(...unrecognizedStrokes.map(u => u.sortY));
              sortable.push({ sortY: minSortY, text: ocrRes.result.trim() });
              result.stats.recognizedStrokes += unrecognizedStrokes.length;
              FileLogger.logEvent('LassoExtract',
                `recognizeElements ok: ${unrecognizedStrokes.length} strokes → ${ocrRes.result.length}chars`);
            } else {
              FileLogger.logEvent('LassoExtract',
                `recognizeElements empty/failed: ${ocrRes?.error?.message ?? 'no text'}`);
            }
          } else {
            FileLogger.logEvent('LassoExtract',
              `getPageSize failed for OCR: ${psRes?.error?.message ?? 'unknown'}`);
          }
        }
      } catch (e) {
        console.warn('[LassoExtractor]: recognizeElements error:', e);
        FileLogger.logEvent('LassoExtract', `recognizeElements threw: ${String(e)}`);
      }
    }
    onProgress?.('done');

    sortable.sort((a, b) => a.sortY - b.sortY);

    const textParts: string[] = [];
    for (const item of sortable) {
      if (item.text) textParts.push(item.text);
    }
    result.text = textParts.join('\n');

    for (const item of sortable) {
      if (item.rect && typeof item.rect.bottom === 'number') {
        if (!result.lastTextBoxRect || item.rect.bottom > result.lastTextBoxRect.bottom) {
          result.lastTextBoxRect = item.rect;
        }
      }
    }

    try {
      const linkRes = await PluginNoteAPI.getLassoLinks();
      if (linkRes?.success && Array.isArray(linkRes.result)) {
        const currentFilePath = (await PluginCommAPI.getCurrentFilePath())?.result ?? '';
        for (const link of linkRes.result) {
          const lt = link.linkType as number;
          const dest = (link.destPath ?? '') as string;
          if (lt === 4 && dest) {

            result.linkedFiles.push({ path: dest, linkType: lt, label: dest });
          } else if (lt === 0 && currentFilePath) {

            if (!result.linkedFiles.some(f => f.path === currentFilePath)) {
              const name = currentFilePath.split('/').pop()?.replace(/\.[^.]+$/, '') ?? 'note';
              result.linkedFiles.push({ path: currentFilePath, linkType: lt, label: name });
            }
          } else if ([1, 2, 3, 6].includes(lt) && dest) {

            const exists = await getRNFS()?.exists(dest);
            if (exists) {
              const name = dest.split('/').pop()?.replace(/\.[^.]+$/, '') ?? 'file';
              result.linkedFiles.push({ path: dest, linkType: lt, label: name });
            } else {
              FileLogger.logEvent('LassoExtract', `linked file not found: ${dest}`);
            }
          }
        }
        if (result.linkedFiles.length > 0) {
          FileLogger.logEvent('LassoExtract',
            `found ${result.linkedFiles.length} linked file(s): ${result.linkedFiles.map(f => f.label).join(', ')}`);
        }
      }
    } catch (e) {
      FileLogger.logEvent('LassoExtract', `getLassoLinks failed (non-fatal): ${String(e)}`);
    }

    for (const el of elements) {

      el?.recycle?.();
    }

    FileLogger.logEvent('LassoExtract',
      `extracted: text=${result.text.length}chars images=${result.imagePaths.length} stats=${JSON.stringify(result.stats)}`);
    return result;
  }

}
