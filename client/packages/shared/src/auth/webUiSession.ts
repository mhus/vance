// Cookie-based session helpers. The web UI no longer keeps the JWT in
// localStorage; the server sets three cookies on login:
//
//   * vance_access  — HttpOnly, the access JWT. JavaScript cannot read
//                     it; the browser ships it on every same-origin
//                     request automatically.
//   * vance_refresh — HttpOnly, the refresh JWT. Same JS-invisibility.
//   * vance_data    — JS-readable JSON: username, tenantId, displayName,
//                     access/refresh expiry timestamps, webui.* settings.
//
// This module is the single read path for the data cookie. We never
// trust its content for security decisions (the server re-verifies on
// every request); we use it only for UI hints — show the username,
// schedule a silent refresh before the access cookie expires, decide
// whether to render the login form.

import type { WebUiSessionData } from '@vance/generated';

const DATA_COOKIE_NAME = 'vance_data';

/**
 * Read and decode the {@code vance_data} cookie. Returns {@code null}
 * when the cookie is missing, malformed, or fails to parse.
 */
export function getSessionData(): WebUiSessionData | null {
  const raw = readCookie(DATA_COOKIE_NAME);
  if (!raw) return null;
  try {
    const decoded = decodeURIComponent(raw);
    const parsed = JSON.parse(decoded) as WebUiSessionData;
    if (typeof parsed?.username !== 'string' || typeof parsed?.tenantId !== 'string') {
      return null;
    }
    return parsed;
  } catch {
    return null;
  }
}

/**
 * Whether the access cookie's claimed expiry is still in the future
 * (with a default 30-second safety margin so callers refresh
 * proactively before the very last second).
 *
 * Returns {@code false} when no session is present at all.
 */
export function isAccessAlive(marginMs = 30_000): boolean {
  const s = getSessionData();
  if (!s) return false;
  return s.accessExpiresAtTimestamp - marginMs > Date.now();
}

/**
 * Whether the refresh cookie's claimed expiry is still in the future.
 * Margin is intentionally smaller than {@link isAccessAlive} — by the
 * time we test the refresh, we've already decided to renew.
 */
export function isRefreshAlive(marginMs = 5_000): boolean {
  const s = getSessionData();
  if (!s || s.refreshExpiresAtTimestamp == null) return false;
  return s.refreshExpiresAtTimestamp - marginMs > Date.now();
}

/**
 * Best-effort client-side delete of the data cookie. The HttpOnly
 * access/refresh cookies cannot be deleted from JavaScript — the
 * server-side {@code POST /brain/{tenant}/logout} clears all three.
 * Use this only when the data cookie has gone stale and the server
 * is unreachable.
 */
export function clearLocalSessionData(): void {
  document.cookie = `${DATA_COOKIE_NAME}=; Max-Age=0; Path=/; SameSite=Strict`;
}

function readCookie(name: string): string | null {
  // No URL decode here — getSessionData decodes once after splitting,
  // so the raw value goes through a single decodeURIComponent pass
  // even if it contains '=' or ';' inside the percent-encoded JSON.
  const cookies = document.cookie.split(';');
  for (const cookie of cookies) {
    const eq = cookie.indexOf('=');
    if (eq < 0) continue;
    const key = cookie.slice(0, eq).trim();
    if (key !== name) continue;
    return cookie.slice(eq + 1).trim();
  }
  return null;
}
