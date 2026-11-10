import { NativeModules } from 'react-native';

const { TextboxMetrics } = NativeModules;

type TextboxMeasurementCalibration = {
  offsetX: number;
  offsetY: number;
  widthAdjustment: number;
};

const DEFAULT_CALIBRATION: TextboxMeasurementCalibration = {
  offsetX: 0,
  offsetY: 0,
  widthAdjustment: 30,
};

type SplitResult = {
  fittingText: string;
  overflowText: string;
  fittingHeight: number;
  fittingLineCount: number;
  didSplit: boolean;
};

function getRequestedWidth(width: number, calibration: TextboxMeasurementCalibration): number {
  return Math.max(24, width - calibration.widthAdjustment);
}

export async function splitTextForHeight(
  text: string,
  width: number,
  fontSize: number,
  availableHeight: number,
  calibration: TextboxMeasurementCalibration = DEFAULT_CALIBRATION,
): Promise<SplitResult | null> {
  if (!TextboxMetrics?.splitTextForHeight) return null;
  try {
    return await TextboxMetrics.splitTextForHeight({
      text,
      width: getRequestedWidth(width, calibration),
      fontSize,
      includePad: true,
      availableHeight,
    });
  } catch (e) {
    console.warn('[TextboxMetrics]: splitTextForHeight failed:', e);
    return null;
  }
}
