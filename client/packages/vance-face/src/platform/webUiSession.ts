// Cookie-based session helpers. The web UI keeps the JWT in an
// HttpOnly cookie set by the server on login:
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
import { StorageKeys, getStorage } from '@vance/shared';

const DATA_COOKIE_NAME = 'vance_data';

/**
 * Read and decode the `vance_data` cookie. Returns `null` when the
 * cookie is missing, malformed, or fails to parse.
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
 * Returns `false` when no session is present at all.
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
 * server-side `POST /brain/{tenant}/logout` clears all three. Use
 * this only when the data cookie has gone stale and the server is
 * unreachable.
 */
export function clearLocalSessionData(): void {
  document.cookie = `${DATA_COOKIE_NAME}=; Max-Age=0; Path=/; SameSite=Strict`;
}

// ──────────────── Identity mirror ────────────────
//
// `@vance/shared/auth/jwtStorage` reads tenant + username from the
// platform-bound prefsStore. On Web the authoritative source is the
// `vance_data` cookie; this mirror copies the relevant fields into
// localStorage so shared modules pick them up via the same code path
// as Mobile.

/**
 * Copy tenantId + username from the data cookie into the prefsStore.
 * Idempotent — call after every successful login / refresh / boot.
 * Clears the keys when the cookie is missing so a stale identity does
 * not survive a server-side logout.
 */
export function hydrateIdentity(): void {
  const prefs = getStorage().prefsStore;
  const s = getSessionData();
  if (s) {
    prefs.set(StorageKeys.identityTenantId, s.tenantId);
    prefs.set(StorageKeys.identityUsername, s.username);
  } else {
    prefs.remove(StorageKeys.identityTenantId);
    prefs.remove(StorageKeys.identityUsername);
  }
}

// ──────────────── Active webui.* settings (session-scoped) ────────────────
//
// The data cookie carries the webui.* setting snapshot from the moment
// of login. Mid-session edits (e.g. switching language on the profile
// page) need to take effect without re-logging in. We mirror the
// settings into sessionStorage and prefer that on every read; the
// data cookie stays authoritative across tabs/sessions.

/**
 * sessionStorage key for the live language preference. Mirrors
 * `webui.language` from the data cookie but updates immediately when
 * the user switches language on the profile page.
 */
const SESSION_LANGUAGE_KEY = 'vance.session.webui.language';

/**
 * Mirror the webui.* settings carried in the data cookie into the
 * tab's sessionStorage. Idempotent — call freely after login or after
 * the data cookie has been refreshed. Existing sessionStorage values
 * are overwritten so cross-tab cookie updates win on the next page
 * load.
 */
export function hydrateActiveWebUiSettings(): void {
  const s = getSessionData();
  if (!s || !s.webUiSettings) return;
  const language = s.webUiSettings['webui.language'];
  if (language != null) {
    window.sessionStorage.setItem(SESSION_LANGUAGE_KEY, language);
  } else {
    window.sessionStorage.removeItem(SESSION_LANGUAGE_KEY);
  }
}

/**
 * The active web-UI language. Reads the session-scoped override first
 * (set by the profile page on every change) and falls back to the
 * snapshot in the data cookie. Returns `null` when the user has not
 * picked a language and the server has no default — callers should
 * then defer to the browser's `navigator.language`.
 */
export function getActiveLanguage(): string | null {
  const fromSession = window.sessionStorage.getItem(SESSION_LANGUAGE_KEY);
  if (fromSession != null && fromSession.length > 0) return fromSession;
  const fromCookie = getSessionData()?.webUiSettings?.['webui.language'];
  return fromCookie && fromCookie.length > 0 ? fromCookie : null;
}

/**
 * Update the active language for this tab. Called by the profile
 * page after a successful `PUT /profile/settings/webui.language` so
 * other components that read {@link getActiveLanguage} pick up the
 * new value without the user having to re-log in.
 *
 * Pass `null` (or empty string) to fall back to the data-cookie
 * value / browser default — this is the "use server default" choice.
 */
export function setActiveLanguage(value: string | null): void {
  if (value == null || value.length === 0) {
    window.sessionStorage.removeItem(SESSION_LANGUAGE_KEY);
    return;
  }
  window.sessionStorage.setItem(SESSION_LANGUAGE_KEY, value);
}

/**
 * Wipe the session-scoped UI overrides. Called from the logout path
 * so a subsequent login on the same tab cleanly picks up the next
 * user's settings instead of inheriting the previous session's
 * language.
 */
export function clearActiveWebUiSettings(): void {
  window.sessionStorage.removeItem(SESSION_LANGUAGE_KEY);
}

function readCookie(name: string): string | null {
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
