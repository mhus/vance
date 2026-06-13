import { registerPlugin, type PluginListenerHandle } from '@capacitor/core';

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

/**
 * Fired when the active account's WebView navigates to a
 * `vance-facelift://*` URL. The website uses this scheme to ask the
 * native wrapper to perform an action — see
 * `facelift-bridge/README.md` for the convention.
 *
 * Example payloads:
 *   - `{ url: "vance-facelift://back-to-picker" }`
 *   - `{ url: "vance-facelift://add-account" }`
 *   - `{ url: "vance-facelift://switch-account" }`
 *
 * The Vue side parses the URL and routes accordingly.
 */
export interface UrlOpenEvent {
  url: string;
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

  /** Re-navigate the account's cached WebView to its home URL while
   *  preserving its `WKWebsiteDataStore` (cookies / login state).
   *  No-op when no WebView for this account exists yet. */
  navigateHome(options: { accountId: string; url: string }): Promise<void>;

  /** Tear down the cached WebView for an account and wipe its
   *  persistent `WKWebsiteDataStore`. Called when the user removes
   *  an account from the Manage screen. */
  remove(options: RemoveOptions): Promise<void>;

  /** Subscribe to `vance-facelift://*` URL events from any of the
   *  account WebViews. The plugin cancels the underlying navigation
   *  and forwards the URL via this callback. */
  addListener(
    eventName: 'urlOpen',
    listener: (event: UrlOpenEvent) => void,
  ): Promise<PluginListenerHandle>;

  /** Remove all event listeners attached via {@link addListener}. */
  removeAllListeners(): Promise<void>;

  /** Persist the wrapper's account list to the App-Group container
   *  so the iOS Share-Extension can populate its account picker.
   *  Pass the JSON-stringified array. */
  setAccountSnapshot(options: { accountsJson: string }): Promise<void>;

  /** Persist the long-lived bearer credentials minted by the website
   *  for one account. Stored under `credentials.json` keyed by
   *  `accountId`; subsequent writes replace just that account's
   *  entry. `credentialsJson` must encode an object with
   *  `{brainUrl, tenant, username, token, refreshToken?}`. */
  setShareCredentials(options: {
    accountId: string;
    credentialsJson: string;
  }): Promise<void>;

  /** Persist the per-account project list so the Share-Extension can
   *  populate its project picker. `projectsJson` must encode an
   *  array of `{name, title}` objects. */
  setProjectSnapshot(options: {
    accountId: string;
    projectsJson: string;
  }): Promise<void>;
}

export const VanceAccountWebView = registerPlugin<VanceAccountWebViewPlugin>(
  'VanceAccountWebView',
);
