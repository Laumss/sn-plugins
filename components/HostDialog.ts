import { NativeModules } from 'react-native';
import { NativeUIUtils } from 'sn-plugin-lib';

type OwnedDialogModule = {
  showOwnedTipDialog?: (message: string) => void;
  showOwnedConfirmDialog?: (
    message: string, leftText: string, rightText: string, isSuccess: boolean,
  ) => Promise<boolean>;
};

function owned(): OwnedDialogModule | undefined {
  return NativeModules.FloatingToolbar as OwnedDialogModule | undefined;
}

const HostDialog = {
  tip(message: string): void {
    const native = owned();
    if (native?.showOwnedTipDialog) {
      native.showOwnedTipDialog(message);
      return;
    }
    NativeUIUtils.showErrorTipDialog(message);
  },

  confirm(message: string, leftText: string, rightText: string, isSuccess: boolean): Promise<boolean> {
    const native = owned();
    if (native?.showOwnedConfirmDialog) return native.showOwnedConfirmDialog(message, leftText, rightText, isSuccess);
    return NativeUIUtils.showRattaDialog(message, leftText, rightText, isSuccess);
  },
};

export default HostDialog;
