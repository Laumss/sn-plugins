import { registerAction } from './actions';
import { TOOL, log } from './common';
import { dialog } from './host';
import { t } from './i18n';
import { hasNativeModule } from './native';

const TAG = 'mosaic';

export function register(): void {
  if (hasNativeModule('NotipalMosaic')) { return; }
  log.e(TAG, 'NotipalMosaic missing, import to Mosaic is unavailable');
  for (const tool of [TOOL.NOTE_TO_MOSAIC, TOOL.DOC_TO_MOSAIC]) {
    registerAction(tool, async () => {
      await dialog.tip(t('feature_not_ready'));
    });
  }
}
