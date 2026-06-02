import type { WebUiSessionData } from '@vance/generated';
/**
 * Read and decode the `vance_data` cookie. Returns `null` when the
 * cookie is missing, malformed, or fails to parse.
 */
export declare function getSessionData(): WebUiSessionData | null;
/**
 * Whether the access cookie's claimed expiry is still in the future
 * (with a default 30-second safety margin so callers refresh
 * proactively before the very last second).
 *
 * Returns `false` when no session is present at all.
 */
export declare function isAccessAlive(marginMs?: number): boolean;
/**
 * Whether the refresh cookie's claimed expiry is still in the future.
 * Margin is intentionally smaller than {@link isAccessAlive} — by the
 * time we test the refresh, we've already decided to renew.
 */
export declare function isRefreshAlive(marginMs?: number): boolean;
/**
 * Best-effort client-side delete of the data cookie. The HttpOnly
 * access/refresh cookies cannot be deleted from JavaScript — the
 * server-side `POST /brain/{tenant}/logout` clears all three. Use
 * this only when the data cookie has gone stale and the server is
 * unreachable.
 */
export declare function clearLocalSessionData(): void;
/**
 * Copy tenantId + username from the data cookie into the prefsStore.
 * Idempotent — call after every successful login / refresh / boot.
 * Clears the keys when the cookie is missing so a stale identity does
 * not survive a server-side logout.
 */
export declare function hydrateIdentity(): void;
export type WebUiTheme = 'auto' | 'light' | 'dark';
export type WebUiLevel = 'standard' | 'expert' | 'admin';
export declare function rankOf(level: WebUiLevel): number;
/**
 * Mirror the webui.* settings carried in the data cookie into the
 * tab's sessionStorage. Idempotent — call freely after login or after
 * the data cookie has been refreshed. Existing sessionStorage values
 * are overwritten so cross-tab cookie updates win on the next page
 * load.
 */
export declare function hydrateActiveWebUiSettings(): void;
/**
 * The active web-UI language. Reads the session-scoped override first
 * (set by the profile page on every change) and falls back to the
 * snapshot in the data cookie. Returns `null` when the user has not
 * picked a language and the server has no default — callers should
 * then defer to the browser's `navigator.language`.
 */
export declare function getActiveLanguage(): string | null;
/**
 * Update the active language for this tab. Called by the profile
 * page after a successful `PUT /profile/settings/webui.language` so
 * other components that read {@link getActiveLanguage} pick up the
 * new value without the user having to re-log in.
 *
 * Pass `null` (or empty string) to fall back to the data-cookie
 * value / browser default — this is the "use server default" choice.
 */
export declare function setActiveLanguage(value: string | null): void;
/**
 * The active web-UI theme. Reads the session-scoped override first,
 * falls back to the data-cookie snapshot, defaults to {@code 'auto'}
 * when nothing is set — matching the user-visible "follow the system"
 * default.
 */
export declare function getActiveTheme(): WebUiTheme;
/**
 * Update the active theme for this tab. Pass {@code null} or
 * {@code 'auto'} to clear the override and fall back to the system
 * preference. Caller is responsible for re-running
 * {@link applyTheme} from {@code themeWeb.ts} after this — the two
 * are split so that boot can apply without writing.
 */
export declare function setActiveTheme(value: WebUiTheme | null): void;
/**
 * The active web-UI level. Reads the session-scoped override first,
 * falls back to the data-cookie snapshot, defaults to {@code 'standard'}
 * — the safest baseline that hides every tile that is not part of
 * everyday use.
 */
export declare function getActiveUiLevel(): WebUiLevel;
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
export declare function setActiveUiLevel(value: WebUiLevel | null): void;
/**
 * Wipe the session-scoped UI overrides. Called from the logout path
 * so a subsequent login on the same tab cleanly picks up the next
 * user's settings instead of inheriting the previous session's
 * language or theme choice.
 */
export declare function clearActiveWebUiSettings(): void;
//# sourceMappingURL=webUiSession.d.ts.map