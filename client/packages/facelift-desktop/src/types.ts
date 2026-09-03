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

/** Options for the desktop-bridged HTTP GET (mirror of the
 *  `FaceliftDesktopBridge` contract in facelift-account-webview). */
export interface HttpGetOptions {
  url: string;
  connectTimeout?: number;
  readTimeout?: number;
  headers?: Record<string, string>;
}

/** Result of the desktop-bridged HTTP GET (mirror of the
 *  `FaceliftDesktopBridge` contract). `data` is a parsed object when the
 *  response content-type is JSON, otherwise the raw text body. */
export interface HttpGetResult {
  status: number;
  headers: Record<string, string>;
  data: unknown;
  url: string;
}

/** UA suffix appended to every account view's User-Agent, matching the
 *  iOS/Android plugins so the website's isFacelift() detection works. */
export const USER_AGENT_SUFFIX = 'VanceFacelift/0.1.0';

/** Custom scheme the website navigates to for wrapper actions. */
export const URL_SCHEME = 'vance-facelift';
