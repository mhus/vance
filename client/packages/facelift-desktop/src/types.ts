// Main-process view of the VanceAccountWebView contract. Mirrors the
// renderer-side FaceliftDesktopBridge in
// facelift-account-webview/src/web.ts — keep the two in lock-step.

export interface AccountWebViewBounds {
  top: number;
  left: number;
  width: number;
  height: number;
}

export interface PresentOptions extends AccountWebViewBounds {
  accountId: string;
  url: string;
}

export interface RemoveOptions {
  accountId: string;
}

export interface NavigateHomeOptions {
  accountId: string;
  url: string;
}

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

/** UA suffix appended to every account view's User-Agent, matching the
 *  iOS/Android plugins so the website's isFacelift() detection works. */
export const USER_AGENT_SUFFIX = 'VanceFacelift/0.1.0';

/** Custom scheme the website navigates to for wrapper actions. */
export const URL_SCHEME = 'vance-facelift';
