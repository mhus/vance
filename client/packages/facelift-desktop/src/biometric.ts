import { systemPreferences } from 'electron';

import type { BiometricAvailability, BiometricResult } from './types';

/**
 * Touch ID is macOS-only. Windows/Linux report unavailable, so the JS
 * lock screen falls back to its PIN path (which owns the fallback, same as
 * on the mobile plugins). Android's BiometricManager can't name the
 * modality either, so 'touchID' here is the honest Apple-specific value.
 */
export function isBiometricAvailable(): BiometricAvailability {
  if (process.platform !== 'darwin') {
    return { available: false, biometryType: 'none' };
  }
  const available = systemPreferences.canPromptTouchID();
  return { available, biometryType: available ? 'touchID' : 'none' };
}

export async function authenticateBiometric(
  reason: string,
): Promise<BiometricResult> {
  if (process.platform !== 'darwin' || !systemPreferences.canPromptTouchID()) {
    return { success: false, errorMessage: 'biometrics unavailable' };
  }
  try {
    await systemPreferences.promptTouchID(reason);
    return { success: true };
  } catch (e) {
    return { success: false, errorMessage: e instanceof Error ? e.message : String(e) };
  }
}
