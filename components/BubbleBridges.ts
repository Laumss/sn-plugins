import { NativeModules, NativeEventEmitter, DeviceEventEmitter } from 'react-native';
import type { Subscription } from './FloatingToolbarBridge';

const { FloatingBubble } = NativeModules;

type BubblePosition = {
  screenY: number; pageY: number;
  screenX: number; pageX: number;
  screenBottomY: number; pageBottomY: number;
  bubbleHeight: number;
};

let _bubbleEmitter: NativeEventEmitter | null = null;
function getBubbleEmitter(): NativeEventEmitter | null {
  if (!FloatingBubble) return null;
  if (!_bubbleEmitter) _bubbleEmitter = new NativeEventEmitter(FloatingBubble);
  return _bubbleEmitter;
}

const FloatingBubbleBridge = {
  isAvailable: !!FloatingBubble,

  remember(statusText: string, mode?: string): void {
    try { FloatingBubble?.remember(statusText, mode ?? ''); } catch (e) {
      console.warn('[FloatingBubbleBridge]: remember failed:', e);
    }
  },

  show(statusText: string, mode?: string): void {
    console.log('[BUBBLE-DBG/JS] bridge.show statusText=', statusText, 'mode=', mode, 'nativeAvailable=', !!FloatingBubble);
    try { FloatingBubble?.show(statusText, mode ?? ''); } catch (e) {
      console.warn('[FloatingBubbleBridge]: show failed:', e);
    }
  },

  showAt(statusText: string, pageX: number, pageY: number, mode?: string): void {
    console.log('[BUBBLE-DBG/JS] bridge.showAt statusText=', statusText, 'page=', pageX, pageY, 'mode=', mode, 'nativeAvailable=', !!FloatingBubble);
    try { FloatingBubble?.showAt(statusText, Math.round(pageX), Math.round(pageY), mode ?? ''); } catch (e) {
      console.warn('[FloatingBubbleBridge]: showAt failed:', e);
    }
  },

  hide(): void {
    try { FloatingBubble?.hide(); } catch (e) {
      console.warn('[FloatingBubbleBridge]: hide failed:', e);
    }
  },

  setPending(pending: boolean): void {
    try { FloatingBubble?.setPending(pending); } catch (_) {}
  },

  setPageFull(full: boolean): void {
    try { FloatingBubble?.setPageFull?.(full); } catch (_) {}
  },

  setPageHeight(height: number): void {
    try { FloatingBubble?.setPageHeight(height); } catch (_) {}
  },

  setPageWidth(width: number): void {
    try { FloatingBubble?.setPageWidth(width); } catch (_) {}
  },

  onTap(callback: (data: BubblePosition & { bubbleWidth: number }) => void): Subscription {
    const em = getBubbleEmitter();
    if (!em) return { remove() {} };
    return em.addListener('onBubbleTap', (event) => {
      const bh = event?.bubbleHeight ?? 0;
      const pageY = event?.pageY ?? 0;
      const pbY = (typeof event?.pageBottomY === 'number' && event.pageBottomY > pageY)
        ? event.pageBottomY
        : pageY + bh;
      callback({
        screenY: event?.screenY ?? 0,
        pageY,
        screenX: event?.screenX ?? 0,
        pageX: event?.pageX ?? 0,
        screenBottomY: event?.screenBottomY ?? event?.screenY ?? 0,
        pageBottomY: pbY,
        bubbleHeight: bh,
        bubbleWidth: event?.bubbleWidth ?? 0,
      });
    });
  },

  onLongPress(callback: () => void): Subscription {
    const em = getBubbleEmitter();
    if (!em) return { remove() {} };
    return em.addListener('onBubbleLongPress', callback);
  },

  onDragEnd(callback: (data: BubblePosition) => void): Subscription {
    const em = getBubbleEmitter();
    if (!em) return { remove() {} };
    return em.addListener('onBubbleDragEnd', (event) => {
      console.log('[BubbleBridge]: onBubbleDragEnd raw keys=', Object.keys(event), 'pageBottomY=', event.pageBottomY, 'bubbleHeight=', event.bubbleHeight);
      const screenH = event.screenBottomY ?? event.screenY;
      const bh = event.bubbleHeight ?? 0;

      const pbY = (typeof event.pageBottomY === 'number' && event.pageBottomY > event.pageY)
        ? event.pageBottomY
        : event.pageY + bh;
      callback({
        screenY: event.screenY,
        pageY: event.pageY,
        screenX: event.screenX ?? 0,
        pageX: event.pageX ?? 0,
        screenBottomY: screenH,
        pageBottomY: pbY,
        bubbleHeight: bh,
      });
    });
  },

  onLayout(callback: (data: BubblePosition) => void): Subscription {
    const em = getBubbleEmitter();
    if (!em) return { remove() {} };
    return em.addListener('onBubbleLayout', (event) => {
      const bh = event.bubbleHeight ?? 0;
      const pbY = (typeof event.pageBottomY === 'number' && event.pageBottomY > event.pageY)
        ? event.pageBottomY
        : event.pageY + bh;
      callback({
        screenY: event.screenY,
        pageY: event.pageY,
        screenX: event.screenX ?? 0,
        pageX: event.pageX ?? 0,
        screenBottomY: event.screenBottomY ?? event.screenY,
        pageBottomY: pbY,
        bubbleHeight: bh,
      });
    });
  },

};

const { AIRelayModule: RelayWindow } = NativeModules;

export type RelayWindowAction = 'lasso_ai' | 'screenshot_ai' | 'mode' | 'mode_text' | 'reply_failed';

const RelayWindowBridge = {
  isAvailable: !!RelayWindow?.openWindow,

  open(forPage: boolean): Promise<boolean> {
    try {
      return Promise.resolve(RelayWindow?.openWindow?.(forPage) ?? false).catch((e: unknown) => {
        console.warn('[RelayWindowBridge]: open failed:', e);
        return false;
      });
    } catch (e) {
      console.warn('[RelayWindowBridge]: open failed:', e);
      return Promise.resolve(false);
    }
  },

  close(): void {
    try { RelayWindow?.closeWindow?.(); } catch (e) {
      console.warn('[RelayWindowBridge]: close failed:', e);
    }
  },

  setStatus(text: string): void {
    try { RelayWindow?.setWindowStatus?.(text); } catch (e) {
      console.warn('[RelayWindowBridge]: setStatus failed:', e);
    }
  },

  flash(text: string, durationMs: number): void {
    try { RelayWindow?.flashWindowStatus?.(text, durationMs); } catch (e) {
      console.warn('[RelayWindowBridge]: flash failed:', e);
    }
  },

  setSelectionMode(mode: 'text' | 'formula', switchable: boolean): void {
    try { RelayWindow?.setWindowSelectionMode?.(mode, switchable); } catch (_) {}
  },

  setAside(aside: boolean): void {
    try { RelayWindow?.setWindowAside?.(aside); } catch (_) {}
  },

  onAction(cb: (e: { actionId: RelayWindowAction }) => void): Subscription {
    return DeviceEventEmitter.addListener('onRelayWindowAction', cb);
  },
};

const { PaletteBubble } = NativeModules;

let _paletteEmitter: NativeEventEmitter | null = null;
function getPaletteEmitter(): NativeEventEmitter | null {
  if (!PaletteBubble) return null;
  if (!_paletteEmitter) _paletteEmitter = new NativeEventEmitter(PaletteBubble);
  return _paletteEmitter;
}

const PaletteBubbleBridge = {
  isAvailable: !!PaletteBubble,

  show(): void {
    try { PaletteBubble?.show(); } catch (e) {
      console.warn('[PaletteBubbleBridge]: show failed:', e);
    }
  },

  hideTemporarily(): void {
    try { PaletteBubble?.hideTemporarily?.(); } catch (e) {
      console.warn('[PaletteBubbleBridge]: hideTemporarily failed:', e);
    }
  },

  restore(): void {
    try { PaletteBubble?.restore?.(); } catch (e) {
      console.warn('[PaletteBubbleBridge]: restore failed:', e);
    }
  },

  onSlotTap(cb: (e: { slotIndex: number; color: string; thickness: number; penType: number }) => void): Subscription {
    return getPaletteEmitter()?.addListener('onPaletteBubbleSlotTap', cb) ?? { remove() {} };
  },
};

export default FloatingBubbleBridge;
export { RelayWindowBridge, PaletteBubbleBridge };
