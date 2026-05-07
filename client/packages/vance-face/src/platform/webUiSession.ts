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

/**
 * Re-write the {@code vance_data} cookie with a mutated payload.
 * Keeps the original attributes that {@code AccessController} uses so
 * the client-side update is indistinguishable from a server reissue:
 *
 * <ul>
 *   <li>Path=/, SameSite=Strict — matches {@code baseCookie} on the
 *       brain (and is required for the cookie to overwrite the
 *       server-set one rather than create a sibling at a sub-path).</li>
 *   <li>Secure only on https — same conditional as {@code cookieSecure}
 *       on the brain. Without this, browsers reject Secure cookies on
 *       a plain-http dev origin.</li>
 *   <li>Max-Age = remaining time until the longer-lived credential
 *       expires. Mirrors the brain's "data cookie outlives whichever
 *       credential cookie outlives the other" logic.</li>
 * </ul>
 *
 * The cookie is JS-readable (not HttpOnly), so we are within the
 * browser's policy boundaries here. We never write JWT material —
 * only the SPA-visible session metadata (settings, expiry hints).
 */
function writeSessionDataCookie(data: WebUiSessionData): void {
  const json = JSON.stringify(data);
  const encoded = encodeURIComponent(json);
  const expiresAt = Math.max(
    data.accessExpiresAtTimestamp,
    data.refreshExpiresAtTimestamp ?? data.accessExpiresAtTimestamp,
  );
  const maxAgeSec = Math.max(0, Math.floor((expiresAt - Date.now()) / 1000));
  const secure = window.location.protocol === 'https:' ? '; Secure' : '';
  document.cookie =
    `${DATA_COOKIE_NAME}=${encoded}; Path=/; SameSite=Strict; Max-Age=${maxAgeSec}${secure}`;
}

/**
 * Mutate the {@code webUiSettings} map inside the data cookie.
 * Passing {@code null} (or an empty string) removes the key — the
 * "use the server default" sentinel that the profile page sends
 * for "browser default" / "auto".
 *
 * <p>Why this matters: the server only re-issues the data cookie on
 * login / refresh. Settings saved via {@code PUT /profile/settings}
 * land in MongoDB but never flow back into the cookie. Without this
 * write the next page load would call {@code hydrateActiveWebUiSettings}
 * against a stale cookie and clobber the freshly chosen value in
 * sessionStorage — which is exactly the "theme only sticks on the
 * profile page" bug.
 */
function updateWebUiSettingInCookie(key: string, value: string | null): void {
  const data = getSessionData();
  if (!data) return;
  const settings: Record<string, string> = { ...(data.webUiSettings ?? {}) };
  if (value == null || value.length === 0) {
    delete settings[key];
  } else {
    settings[key] = value;
  }
  writeSessionDataCookie({ ...data, webUiSettings: settings });
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
 * sessionStorage key for the live theme preference (`light` / `dark` /
 * `auto`). Mirrors `webui.theme` from the data cookie. Same live-update
 * pattern as the language mirror — the profile page writes here on
 * every change so the active document repaints without re-login.
 */
const SESSION_THEME_KEY = 'vance.session.webui.theme';

/**
 * sessionStorage key for the live UI level preference (`standard` /
 * `expert` / `admin`). Drives index-page tile filtering — server-side
 * permissions remain the actual boundary on every REST endpoint;
 * this is purely a clutter-reduction toggle.
 */
const SESSION_UI_LEVEL_KEY = 'vance.session.webui.uiLevel';

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
  const theme = s.webUiSettings['webui.theme'];
  if (isWebUiTheme(theme)) {
    window.sessionStorage.setItem(SESSION_THEME_KEY, theme);
  } else {
    window.sessionStorage.removeItem(SESSION_THEME_KEY);
  }
  const uiLevel = s.webUiSettings['webui.uiLevel'];
  if (isWebUiLevel(uiLevel)) {
    window.sessionStorage.setItem(SESSION_UI_LEVEL_KEY, uiLevel);
  } else {
    window.sessionStorage.removeItem(SESSION_UI_LEVEL_KEY);
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
  const normalised = value != null && value.length > 0 ? value : null;
  if (normalised === null) {
    window.sessionStorage.removeItem(SESSION_LANGUAGE_KEY);
  } else {
    window.sessionStorage.setItem(SESSION_LANGUAGE_KEY, normalised);
  }
  // Sync the data-cookie snapshot too, so any other tab / a fresh
  // navigation in this tab picks the new value up via the next
  // {@link hydrateActiveWebUiSettings} round.
  updateWebUiSettingInCookie('webui.language', normalised);
}

/**
 * The active web-UI theme. Reads the session-scoped override first,
 * falls back to the data-cookie snapshot, defaults to {@code 'auto'}
 * when nothing is set — matching the user-visible "follow the system"
 * default.
 */
export function getActiveTheme(): WebUiTheme {
  const fromSession = window.sessionStorage.getItem(SESSION_THEME_KEY);
  if (isWebUiTheme(fromSession)) return fromSession;
  const fromCookie = getSessionData()?.webUiSettings?.['webui.theme'];
  return isWebUiTheme(fromCookie) ? fromCookie : 'auto';
}

/**
 * Update the active theme for this tab. Pass {@code null} or
 * {@code 'auto'} to clear the override and fall back to the system
 * preference. Caller is responsible for re-running
 * {@link applyTheme} from {@code themeWeb.ts} after this — the two
 * are split so that boot can apply without writing.
 */
export function setActiveTheme(value: WebUiTheme | null): void {
  let normalised: 'light' | 'dark' | null;
  if (value == null || value === 'auto') {
    normalised = null;
  } else if (value === 'light' || value === 'dark') {
    normalised = value;
  } else {
    return;
  }
  if (normalised === null) {
    window.sessionStorage.removeItem(SESSION_THEME_KEY);
  } else {
    window.sessionStorage.setItem(SESSION_THEME_KEY, normalised);
  }
  // Same cross-tab / cross-page rationale as {@link setActiveLanguage}:
  // mirror the change into the data-cookie snapshot so subsequent
  // hydrations don't roll back to the login-time value.
  updateWebUiSettingInCookie('webui.theme', normalised);
}

/**
 * The active web-UI level. Reads the session-scoped override first,
 * falls back to the data-cookie snapshot, defaults to {@code 'standard'}
 * — the safest baseline that hides every tile that is not part of
 * everyday use.
 */
export function getActiveUiLevel(): WebUiLevel {
  const fromSession = window.sessionStorage.getItem(SESSION_UI_LEVEL_KEY);
  if (isWebUiLevel(fromSession)) return fromSession;
  const fromCookie = getSessionData()?.webUiSettings?.['webui.uiLevel'];
  return isWebUiLevel(fromCookie) ? fromCookie : 'standard';
}

/**
 * Update the active UI level for this tab. Pass {@code null} or
 * {@code 'standard'} to clear the override and fall back to the
 * default. Reactive consumers (e.g. {@link IndexApp}) should re-read
 * via {@link getActiveUiLevel} after calling.
 *
 * <p>This is purely a UI-clutter toggle. The brain enforces real
 * authorization on every endpoint — flipping the level to
 * {@code 'admin'} on a non-admin account just exposes a tile that
 * the server will refuse to back.
 */
export function setActiveUiLevel(value: WebUiLevel | null): void {
  let normalised: 'expert' | 'admin' | null;
  if (value == null || value === 'standard') {
    normalised = null;
  } else if (value === 'expert' || value === 'admin') {
    normalised = value;
  } else {
    return;
  }
  if (normalised === null) {
    window.sessionStorage.removeItem(SESSION_UI_LEVEL_KEY);
  } else {
    window.sessionStorage.setItem(SESSION_UI_LEVEL_KEY, normalised);
  }
  updateWebUiSettingInCookie('webui.uiLevel', normalised);
}

/**
 * Wipe the session-scoped UI overrides. Called from the logout path
 * so a subsequent login on the same tab cleanly picks up the next
 * user's settings instead of inheriting the previous session's
 * language or theme choice.
 */
export function clearActiveWebUiSettings(): void {
  window.sessionStorage.removeItem(SESSION_LANGUAGE_KEY);
  window.sessionStorage.removeItem(SESSION_THEME_KEY);
  window.sessionStorage.removeItem(SESSION_UI_LEVEL_KEY);
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
