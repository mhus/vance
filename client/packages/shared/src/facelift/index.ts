/**
 * Facelift detection + action helpers.
 *
 * The Vance Web-UI is hosted unchanged inside the `vance-facelift`
 * Capacitor wrapper (see `repos/vance/client/packages/facelift-bridge`)
 * and the Electron desktop wrapper (`facelift-desktop`). Both signal
 * their presence by appending tokens to the default User-Agent —
 * `VanceFacelift/<version>` everywhere, plus `VanceFaceliftDesktop/<version>`
 * on the desktop only — and listen for `vance-facelift://*` navigations
 * to trigger wrapper-side actions like switching accounts or returning
 * to the account picker.
 *
 * Code that adapts UI to "we're inside the wrapper" should branch on
 * {@link isFacelift}; code that must not activate on iPad/iPhone branches
 * on {@link isFaceliftDesktop}. Never depend on the exact version unless
 * you know what range of behaviour you require.
 */

const FACELIFT_UA_RE = /\bVanceFacelift\/(\S+)/;
const FACELIFT_DESKTOP_UA_RE = /\bVanceFaceliftDesktop\/(\S+)/;

/** True when the page is running inside the Facelift wrapper. */
export function isFacelift(): boolean {
  if (typeof navigator === 'undefined') return false;
  return FACELIFT_UA_RE.test(navigator.userAgent);
}

/** Wrapper version string (e.g. `"0.1.0"`), or `null` when not in
 *  Facelift. Use sparingly — feature-detect via {@link isFacelift}
 *  instead of comparing versions when possible. */
export function getFaceliftVersion(): string | null {
  if (typeof navigator === 'undefined') return null;
  const match = FACELIFT_UA_RE.exec(navigator.userAgent);
  return match === null ? null : match[1];
}

/** True when the page is running inside the Facelift **desktop**
 *  (Electron) wrapper — implies {@link isFacelift}. Gate desktop-only
 *  extensions (features that make no sense on iPad/iPhone) on this,
 *  never on device-string sniffing of the surrounding UA. */
export function isFaceliftDesktop(): boolean {
  if (typeof navigator === 'undefined') return false;
  return FACELIFT_DESKTOP_UA_RE.test(navigator.userAgent);
}

/** Desktop wrapper version string (e.g. `"0.1.0"`), or `null` when
 *  not in the desktop wrapper. Use sparingly — feature-detect via
 *  {@link isFaceliftDesktop} instead of comparing versions when
 *  possible. */
export function getFaceliftDesktopVersion(): string | null {
  if (typeof navigator === 'undefined') return null;
  const match = FACELIFT_DESKTOP_UA_RE.exec(navigator.userAgent);
  return match === null ? null : match[1];
}

function requestAction(action: string): void {
  if (typeof window === 'undefined') return;
  // Top-level navigation to the custom scheme. The Facelift Swift
  // plugin's WKNavigationDelegate cancels the request and forwards
  // it to the wrapper's Vue shell as an `urlOpen` event.
  window.location.href = `vance-facelift://${action}`;
}

/** Ask the wrapper to dismiss the active website WebView and show
 *  the account-picker / manage screen. No-op when not in Facelift. */
export function requestBackToPicker(): void {
  if (!isFacelift()) return;
  requestAction('back-to-picker');
}

/** Ask the wrapper to open its account-switcher bottom-sheet on top
 *  of the website. No-op when not in Facelift. */
export function requestSwitchAccount(): void {
  if (!isFacelift()) return;
  requestAction('switch-account');
}

/** Ask the wrapper to open its Add-Account form. No-op when not in
 *  Facelift. */
export function requestAddAccount(): void {
  if (!isFacelift()) return;
  requestAction('add-account');
}

export * from './desktopTools';
