import { registerPlugin } from '@capacitor/core';

/**
 * Rectangle in CSS pixels (= UIKit points on iOS) relative to the
 * top-left of the Capacitor host view. The plugin places the native
 * WKWebView at this rectangle, layered above the Capacitor main
 * WebView.
 */
export interface AccountWebViewBounds {
  top: number;
  left: number;
  width: number;
  height: number;
}

export interface PresentOptions extends AccountWebViewBounds {
  /**
   * Stable per-account identifier. Must be a UUID — the plugin
   * derives the iOS `WKWebsiteDataStore` identifier from it via
   * `WKWebsiteDataStore(forIdentifier:)`, which keeps cookies,
   * IndexedDB, Service-Worker and LocalStorage isolated per account
   * even when two accounts point at the same Brain origin.
   */
  accountId: string;
  /** Initial URL to load. Ignored if a cached WebView for this
   *  accountId already exists — switching back to a cached account
   *  preserves its in-WebView navigation state. */
  url: string;
}

export interface RemoveOptions {
  /** UUID of the account whose cached WebView and persistent
   *  `WKWebsiteDataStore` should be torn down. */
  accountId: string;
}

export interface VanceAccountWebViewPlugin {
  /**
   * Show the WebView for `accountId`, creating it if needed.
   * Subsequent calls switch between cached WebViews (instant), or
   * resize/reposition the currently visible one if the same
   * `accountId` is passed.
   *
   * Rejects on non-iOS-17 devices.
   */
  present(options: PresentOptions): Promise<void>;

  /** Hide the currently visible account WebView. Cached state is
   *  retained so a subsequent `present` of the same account resumes
   *  instantly. */
  dismiss(): Promise<void>;

  /** Update the visible WebView's frame (e.g. on window resize or
   *  when the header height changes). No-op when nothing is
   *  presented. */
  setBounds(options: AccountWebViewBounds): Promise<void>;

  /** Reload the currently visible account WebView's page. */
  reload(): Promise<void>;

  /** Tear down the cached WebView for an account and wipe its
   *  persistent `WKWebsiteDataStore`. Called when the user removes
   *  an account from the Manage screen. */
  remove(options: RemoveOptions): Promise<void>;
}

export const VanceAccountWebView = registerPlugin<VanceAccountWebViewPlugin>(
  'VanceAccountWebView',
);
