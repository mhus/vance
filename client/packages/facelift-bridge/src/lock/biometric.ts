/**
 * Biometric unlock — wraps the `isBiometricAvailable` +
 * `authenticateBiometric` methods exposed by the
 * `@vance/facelift-account-webview` Swift plugin. We use our own
 * plugin (which calls `LAContext` directly) instead of a community
 * one so the iOS code path is a single Swift file we control.
 *
 * Both helpers return Booleans for ease of consumption by the lock
 * UI — error codes / messages are logged to the console for
 * debugging but not surfaced to the user (the UX falls back to PIN
 * either way).
 */
import { VanceAccountWebView } from '@vance/facelift-account-webview';

export async function isBiometricSupported(): Promise<boolean> {
  try {
    const result = await VanceAccountWebView.isBiometricAvailable();
    if (!result.available) {
      console.info(
        '[facelift] biometric not available:',
        result.biometryType,
        result.errorMessage,
      );
    }
    return result.available === true;
  } catch (e) {
    console.warn('[facelift] isBiometricAvailable threw', e);
    return false;
  }
}

export async function tryBiometricUnlock(): Promise<boolean> {
  try {
    const result = await VanceAccountWebView.authenticateBiometric({
      reason: 'Unlock Vance',
    });
    if (!result.success) {
      console.info('[facelift] biometric auth failed:', result.errorCode, result.errorMessage);
    }
    return result.success === true;
  } catch (e) {
    console.warn('[facelift] authenticateBiometric threw', e);
    return false;
  }
}
