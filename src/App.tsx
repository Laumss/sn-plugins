import React, { useEffect } from 'react';
import { StatusBar, StyleSheet, View } from 'react-native';
import { log } from './common';
import { hostState } from './host';
import { hasNativeModule, notipalHost, notipalToolbar } from './native';

const TAG = 'App';

const FLUSHED_ENTRY_WAIT_MS = 1200;

let lastHostButtonEventAt = 0;
let lastHostButtonHandledAt = 0;

export function markHostButtonEvent(): void {
  lastHostButtonEventAt = Date.now();
}

export function markHostButtonHandled(): void {
  lastHostButtonHandledAt = Date.now();
}

function toolbarShowing(): boolean {
  const state = notipalToolbar.getState();
  return state.session && state.windowAttached;
}

function inspectHostEntry(): void {
  if (toolbarShowing()) {
    log.i(TAG, 'toolbar already showing; native lifecycle owns host close');
    return;
  }
  log.i(TAG, `source-less plugin view mount, restoring=${hostState().restoring} -> inspect host entry`);
  notipalHost.inspectAndFlushHostEntry().then(entry => {
    if (entry === 'config') {
      log.i(TAG, 'host entry is config -> open settings');
      notipalToolbar.openSettings();
      return;
    }
    if (entry !== 'flushed') {
      log.i(TAG, 'no recoverable host entry; wait for button listener');
      return;
    }
    const flushAt = Date.now();

    setTimeout(() => {
      if (toolbarShowing()) {
        log.i(TAG, 'flushed entry already owned by native toolbar; skip fallback');
        return;
      }
      if (lastHostButtonHandledAt >= flushAt) {
        log.i(TAG, 'flushed entry handled by button listener; skip fallback');
        return;
      }
      if (lastHostButtonEventAt >= flushAt) { return; }
      notipalHost.reportHostButtonChannel(false);
      log.i(TAG, 'flushed event never reached JS -> fallback toggle toolbar');
      notipalToolbar.toggle(null);
    }, FLUSHED_ENTRY_WAIT_MS);
  }, e => log.w(TAG, 'inspect host entry failed', e));
}

export default function App(): React.JSX.Element {
  useEffect(() => {
    log.i(TAG, 'mount');
    if (hasNativeModule('NotipalHost') && hasNativeModule('NotipalToolbar')) { inspectHostEntry(); }
    return () => log.i(TAG, 'unmount');
  }, []);

  return (
    <View style={st.container}>
      <StatusBar barStyle="dark-content" />
    </View>
  );
}

const st = StyleSheet.create({
  container: { flex: 1, backgroundColor: 'transparent' },
});
