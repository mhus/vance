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
// The data cookie is the single source of truth for SPA-visible
// settings. The server re-issues it on every login, silent refresh,
// and profile-settings mutation (PUT/DELETE /profile/settings/...), so
// `getActive*()` always sees the current state — no client-side
// sessionStorage mirror, no client-side cookie patching.
//
// We never trust its content for security decisions (the server
// re-verifies on every request); we use it only for UI hints — show
// the username, schedule a silent refresh before the access cookie
// expires, decide whether to render the login form.

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

// ──────────────── Active webui.* settings ────────────────
//
// Read straight from the data cookie. The server re-issues the cookie
// on every settings mutation so these reads are always current — no
// session-scoped override layer to drift out of sync.

export type WebUiTheme = 'auto' | 'light' | 'dark';
export type WebUiLevel = 'standard' | 'expert' | 'admin';

const VALID_THEMES: ReadonlyArray<WebUiTheme> = ['auto', 'light', 'dark'];
const VALID_LEVELS: ReadonlyArray<WebUiLevel> = ['standard', 'expert', 'admin'];

function isWebUiTheme(value: string | null | undefined): value is WebUiTheme {
  return value != null && (VALID_THEMES as ReadonlyArray<string>).includes(value);
}

function isWebUiLevel(value: string | null | undefined): value is WebUiLevel {
  return value != null && (VALID_LEVELS as ReadonlyArray<string>).includes(value);
}

/**
 * Numeric rank of a {@link WebUiLevel}. Higher = more visible. Used by
 * tile filters: `level >= rankOf('expert')` reveals the power-user
 * tiles, `level >= rankOf('admin')` adds the admin ones on top.
 */
const LEVEL_RANK: Readonly<Record<WebUiLevel, number>> = {
  standard: 0,
  expert: 1,
  admin: 2,
};

export function rankOf(level: WebUiLevel): number {
  return LEVEL_RANK[level];
}

/**
 * The active web-UI language from the data cookie. Returns `null`
 * when the user has not picked a language and the server has no
 * default — callers should then defer to the browser's
 * `navigator.language`.
 */
export function getActiveLanguage(): string | null {
  const fromCookie = getSessionData()?.webUiSettings?.['webui.language'];
  return fromCookie && fromCookie.length > 0 ? fromCookie : null;
}

/**
 * The active web-UI theme from the data cookie, defaulting to
 * {@code 'auto'} when nothing is set — matching the user-visible
 * "follow the system" default.
 */
export function getActiveTheme(): WebUiTheme {
  const fromCookie = getSessionData()?.webUiSettings?.['webui.theme'];
  return isWebUiTheme(fromCookie) ? fromCookie : 'auto';
}

/**
 * The active web-UI level from the data cookie, defaulting to
 * {@code 'standard'} — the safest baseline that hides every tile
 * that is not part of everyday use.
 */
export function getActiveUiLevel(): WebUiLevel {
  const fromCookie = getSessionData()?.webUiSettings?.['webui.uiLevel'];
  return isWebUiLevel(fromCookie) ? fromCookie : 'standard';
}

/**
 * Whether `vance:` document links inside Markdown should open in a new
 * browser tab (the default) or replace the current page. Read from the
 * data cookie; default {@code true} so the user keeps their place in
 * the chat / sessions list. The setting is ignored when a host like
 * Cortex has injected a {@code VanceLinkHandler} — that handler claims
 * the click first and routes the doc into an in-app tab instead.
 */
export function getOpenDocumentsInNewTab(): boolean {
  const fromCookie = getSessionData()?.webUiSettings?.['webui.document.openInNewTab'];
  return fromCookie !== 'false';
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
