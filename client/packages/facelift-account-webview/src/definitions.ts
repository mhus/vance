import type { PluginListenerHandle } from '@capacitor/core';

/**
 * Rectangle in CSS pixels (= UIKit points on iOS) relative to the
 * top-left of the Capacitor host view. The plugin places the native
 * WebView at this rectangle, layered above the Capacitor main
 * WebView. On the Electron desktop shell the same rectangle positions
 * a WebContentsView inside the window.
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
   * derives the isolated data store from it: iOS
   * `WKWebsiteDataStore(forIdentifier:)`, Android an androidx.webkit
   * `Profile`, Electron a `session.fromPartition('persist:vance-<id>')`.
   * Keeps cookies, IndexedDB, Service-Worker and LocalStorage isolated
   * per account even when two accounts point at the same Brain origin.
   */
  accountId: string;
  /** Initial URL to load. Ignored if a cached WebView for this
   *  accountId already exists — switching back to a cached account
   *  preserves its in-WebView navigation state. */
  url: string;
}

export interface RemoveOptions {
  /** UUID of the account whose cached WebView and persistent data
   *  store should be torn down. */
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

export interface BiometricAvailability {
  available: boolean;
  biometryType: 'faceID' | 'touchID' | 'none';
  errorCode?: number;
  errorMessage?: string;
}

export interface BiometricResult {
  success: boolean;
  errorCode?: number;
  errorMessage?: string;
}

export interface VanceAccountWebViewPlugin {
  /**
   * Show the WebView for `accountId`, creating it if needed.
   * Subsequent calls switch between cached WebViews (instant), or
   * resize/reposition the currently visible one if the same
   * `accountId` is passed.
   *
   * Rejects on non-iOS-17 devices, when Android multi-account
   * isolation is unavailable, or outside a Facelift host.
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
   *  preserving its data store (cookies / login state). No-op when
   *  the WebView for this account exists yet. */
  navigateHome(options: { accountId: string; url: string }): Promise<void>;

  /** Tear down the cached WebView for an account and wipe its
   *  persistent data store. Called when the user removes an account
   *  from the Manage screen. */
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

  /** Persist the wrapper's account list so the iOS Share-Extension
   *  (or the desktop share flow) can populate its account picker.
   *  Pass the JSON-stringified array. */
  setAccountSnapshot(options: { accountsJson: string }): Promise<void>;

  /** Persist the long-lived bearer credentials minted by the website
   *  for one account. Stored keyed by `accountId`; subsequent writes
   *  replace just that account's entry. `credentialsJson` must encode
   *  an object with `{faceUrl, tenant, username, token, refreshToken?}`. */
  setShareCredentials(options: {
    accountId: string;
    credentialsJson: string;
  }): Promise<void>;

  /** Persist the per-account project list so the share flow can
   *  populate its project picker. `projectsJson` must encode an array
   *  of `{name, title}` objects. */
  setProjectSnapshot(options: {
    accountId: string;
    projectsJson: string;
  }): Promise<void>;

  /** Whether the device can run a biometric check at all. */
  isBiometricAvailable(): Promise<BiometricAvailability>;

  /** Trigger the system biometric prompt. `reason` is shown by the OS
   *  beneath the Face-ID / Touch-ID dialog title. */
  authenticateBiometric(options: { reason?: string }): Promise<BiometricResult>;
}

/**
 * Options for a desktop-bridged HTTP GET — the relevant slice of
 * CapacitorHttp's request shape. Used by the Add-Account verification
 * (`verifyVanceUrl`) when running inside the Electron desktop shell.
 */
export interface HttpGetOptions {
  url: string;
  connectTimeout?: number;
  readTimeout?: number;
  headers?: Record<string, string>;
}

/**
 * Result of a desktop-bridged HTTP GET, structurally compatible with
 * Capacitor's `HttpResponse` so `verifyVanceUrl` can consume either
 * transport unchanged. `data` is a parsed object when the response
 * content-type is JSON, otherwise the raw text body.
 */
export interface HttpGetResult {
  status: number;
  headers: Record<string, string>;
  data: unknown;
  url: string;
}

/**
 * Contract for the object the facelift-desktop Electron preload injects
 * via `contextBridge` (see planning/vance-facelift-desktop.md §4). Kept
 * in lock-step with that preload. Absent in a plain browser — the web
 * plugin implementation then reports the plugin as unavailable.
 *
 * Declared here (rather than in `web.ts`) so the `declare global`
 * augmentation below is visible to consumers that statically import the
 * package — `window.faceliftDesktop` is then typed in `facelift-bridge`
 * without the lazy-loaded `web` module having to be pulled in.
 */
export interface FaceliftDesktopBridge {
  present(options: PresentOptions): Promise<void>;
  dismiss(): Promise<void>;
  setBounds(options: AccountWebViewBounds): Promise<void>;
  reload(): Promise<void>;
  navigateHome(options: { accountId: string; url: string }): Promise<void>;
  remove(options: RemoveOptions): Promise<void>;
  setAccountSnapshot(options: { accountsJson: string }): Promise<void>;
  setShareCredentials(options: {
    accountId: string;
    credentialsJson: string;
  }): Promise<void>;
  setProjectSnapshot(options: {
    accountId: string;
    projectsJson: string;
  }): Promise<void>;
  isBiometricAvailable(): Promise<BiometricAvailability>;
  authenticateBiometric(options: { reason?: string }): Promise<BiometricResult>;
  /** Subscribe to vance-facelift:// url events; returns an unsubscribe fn. */
  onUrlOpen(callback: (event: UrlOpenEvent) => void): () => void;
  /**
   * Perform a CORS-free HTTP GET via the Electron main process.
   *
   * `verifyVanceUrl` calls this instead of `CapacitorHttp` on the desktop
   * shell: there CapacitorHttp falls back to a browser `fetch`, which
   * enforces a CORS preflight that a Vance deployment is not expected to
   * answer (the design relies on native HTTP — see `verifyVanceUrl`).
   * Routing through the main process mirrors the native iOS/Android
   * (URLSession) path.
   */
  httpGet(options: HttpGetOptions): Promise<HttpGetResult>;
}

declare global {
  interface Window {
    faceliftDesktop?: FaceliftDesktopBridge;
  }
}
