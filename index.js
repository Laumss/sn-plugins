import { log, reportPluginVersion } from './src/common';
import { AppRegistry, DeviceEventEmitter, Image, TurboModuleRegistry } from 'react-native';
import { PluginManager } from 'sn-plugin-lib';
import pluginSdkPackage from 'sn-plugin-lib/package.json';
import { dispatch } from './src/actions';
import App, { markHostButtonEvent, markHostButtonHandled } from './src/App';
import * as capture from './src/capture';
import * as clipboard from './src/clipboard';
import { initHost, onHostState } from './src/host';
import { getLocale, localeFromTag, setLocale, t } from './src/i18n';
import * as lasso from './src/lasso';
import * as links from './src/links';
import * as localSend from './src/localSend';
import * as mosaic from './src/mosaic';
import * as relay from './src/relay';
import { notipalHost, notipalToolbar, onNative } from './src/native';
import * as textInsert from './src/textInsert';

const TAG = 'index';

const NOTE_TOOLBAR_BUTTON_ID = 100;
const DOC_TOOLBAR_BUTTON_ID = 300;

const CLIP_LASSO_BUTTON_ID = 400;

const OLD_DOC_SEND_BUTTON_ID = 301;

function registerClipLassoButton() {
  PluginManager.registerButton(2, ['NOTE', 'DOC'], {
    id: CLIP_LASSO_BUTTON_ID,
    name: t('clip_lasso_button'),
    icon: Image.resolveAssetSource(require('./assets/smart_lasso.png')).uri,
    showType: 0,

    editDataTypes: [0, 1, 2, 3, 4, 5],
  });
}

AppRegistry.registerComponent('me_laumss_notipal', () => App);

TurboModuleRegistry.getEnforcing('NativePluginManager').reportSdkVersion(pluginSdkPackage.version);
PluginManager.init();

setLocale(getLocale());
reportPluginVersion();

onNative('host:state', onHostState);
onNative('toolbar:action', dispatch);
onNative('clipboard:clearAll', clipboard.onClearAll);
onNative('clipboard:sketchRequest', clipboard.onSketchRequest);
onNative('clipboard:syncApply', clipboard.onSyncApply);
onNative('lasso:selectionShown', lasso.onSelectionShown);
onNative('lasso:penBbox', lasso.onPenBbox);
onNative('lasso:penCancel', lasso.onPenCancel);
onNative('image:insert', capture.onInsertImage);
onNative('capture:regionClosed', capture.onRegionClosed);
onNative('links:insert', links.onInsertLink);
onNative('text:bubble', textInsert.onBubble);
onNative('text:bubbleClosed', textInsert.onBubbleClosed);
onNative('toolbar:closed', textInsert.onToolbarClosed);
onNative('panel:closed', localSend.onPanelClosed);
onNative('localsend:text', localSend.onText);
onNative('localsend:openReceiver', localSend.onOpenReceiver);
onNative('localsend:prepareImage', localSend.onPrepareImage);
onNative('relay:action', relay.onAction);
onNative('relay:insert', relay.onInsert);
onNative('relay:replyArrived', relay.onReplyArrived);
onNative('relay:off', relay.onOff);
initHost();
clipboard.register();
lasso.register();
capture.register();
links.register();
textInsert.register();
localSend.register();
mosaic.register();
relay.register();

DeviceEventEmitter.addListener('plugin_button_event', (msg) => {
  markHostButtonEvent();
  notipalHost.reportHostButtonChannel(true);
  log.i(TAG, `raw plugin_button_event: ${JSON.stringify(msg)}`);
});

PluginManager.registerLangListener({
  onMsg(msg) {
    setLocale(localeFromTag((msg && msg.lang) || ''));

    registerClipLassoButton();
  },
});

PluginManager.registerButtonListener({
  async onButtonPress(event) {
    const buttonId = Number(event && event.id);
    if (!Number.isFinite(buttonId)) {
      log.w(TAG, `ignored host button event without numeric id: ${JSON.stringify(event)}`);
      return;
    }
    log.i(TAG, `button id=${buttonId}`);
    if (buttonId === NOTE_TOOLBAR_BUTTON_ID || buttonId === DOC_TOOLBAR_BUTTON_ID) {
      markHostButtonHandled();
      notipalToolbar.toggle(buttonId === DOC_TOOLBAR_BUTTON_ID ? 'doc_notipal' : 'note_notipal');
      clipboard.refreshTitleClips().catch(e => log.w(TAG, 'title clips refresh failed', e));
      return;
    }
    if (buttonId === CLIP_LASSO_BUTTON_ID) {
      markHostButtonHandled();
      try {
        await clipboard.onLassoMenuButton();
      } finally {

        PluginManager.closePluginView().catch(e => log.w(TAG, 'lasso menu close plugin view failed', e));
      }
    }
  },
});

const toolbarIcon = Image.resolveAssetSource(require('./assets/toolbar_icon.png')).uri;
PluginManager.registerButton(1, ['NOTE'], { id: NOTE_TOOLBAR_BUTTON_ID, name: 'Inkling', icon: toolbarIcon, showType: 0 });
PluginManager.registerButton(1, ['DOC'], { id: DOC_TOOLBAR_BUTTON_ID, name: 'Inkling', icon: toolbarIcon, showType: 0 });
registerClipLassoButton();
PluginManager.unregisterButton(OLD_DOC_SEND_BUTTON_ID)
  .catch(e => log.i(TAG, `old doc send button not unregistered: ${e}`));

PluginManager.registerConfigButton();
PluginManager.registerConfigButtonListener({
  onClick() {
    log.i(TAG, 'config button clicked');

    markHostButtonHandled();
    notipalToolbar.openSettings();
  },
});
