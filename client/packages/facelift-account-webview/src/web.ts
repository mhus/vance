import { WebPlugin } from '@capacitor/core';

import type {
  AccountWebViewBounds,
  BiometricAvailability,
  BiometricResult,
  FaceliftDesktopBridge,
  PresentOptions,
  RemoveOptions,
  VanceAccountWebViewPlugin,
} from './definitions';

/**
 * Web/Electron implementation. Every call forwards to the Electron main
 * process through the preload-injected `window.faceliftDesktop` bridge.
 * Outside Facelift (a plain browser tab) there is no bridge and every
 * method rejects via {@link WebPlugin.unavailable}.
 */
export class VanceAccountWebViewWeb
  extends WebPlugin
  implements VanceAccountWebViewPlugin
{
  constructor() {
    super();
    // Forward native url-open events into Capacitor's listener system so
    // the Vue shell's addListener('urlOpen', …) works unchanged. The
    // subscription lives for the whole app session (a single global
    // stream, like the native listener), so the unsubscribe handle is
    // intentionally not retained.
    window.faceliftDesktop?.onUrlOpen((event) => {
      this.notifyListeners('urlOpen', event);
    });
  }

  private bridge(): FaceliftDesktopBridge {
    const bridge = window.faceliftDesktop;
    if (!bridge) {
      throw this.unavailable(
        'VanceAccountWebView is only available inside Facelift (native iOS/Android or the Electron desktop shell).',
      );
    }
    return bridge;
  }

  present(options: PresentOptions): Promise<void> {
    return this.bridge().present(options);
  }

  dismiss(): Promise<void> {
    return this.bridge().dismiss();
  }

  setBounds(options: AccountWebViewBounds): Promise<void> {
    return this.bridge().setBounds(options);
  }

  reload(): Promise<void> {
    return this.bridge().reload();
  }

  navigateHome(options: { accountId: string; url: string }): Promise<void> {
    return this.bridge().navigateHome(options);
  }

  remove(options: RemoveOptions): Promise<void> {
    return this.bridge().remove(options);
  }

  setAccountSnapshot(options: { accountsJson: string }): Promise<void> {
    return this.bridge().setAccountSnapshot(options);
  }

  setShareCredentials(options: {
    accountId: string;
    credentialsJson: string;
  }): Promise<void> {
    return this.bridge().setShareCredentials(options);
  }

  setProjectSnapshot(options: {
    accountId: string;
    projectsJson: string;
  }): Promise<void> {
    return this.bridge().setProjectSnapshot(options);
  }

  isBiometricAvailable(): Promise<BiometricAvailability> {
    return this.bridge().isBiometricAvailable();
  }

  authenticateBiometric(options: {
    reason?: string;
  }): Promise<BiometricResult> {
    return this.bridge().authenticateBiometric(options);
  }
}
