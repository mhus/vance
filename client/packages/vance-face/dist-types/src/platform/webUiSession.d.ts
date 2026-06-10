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
 * The active web-UI language from the data cookie. Returns `null`
 * when the user has not picked a language and the server has no
 * default — callers should then defer to the browser's
 * `navigator.language`.
 */
export declare function getActiveLanguage(): string | null;
/**
 * The active web-UI theme from the data cookie, defaulting to
 * {@code 'auto'} when nothing is set — matching the user-visible
 * "follow the system" default.
 */
export declare function getActiveTheme(): WebUiTheme;
/**
 * The active web-UI level from the data cookie, defaulting to
 * {@code 'standard'} — the safest baseline that hides every tile
 * that is not part of everyday use.
 */
export declare function getActiveUiLevel(): WebUiLevel;
//# sourceMappingURL=webUiSession.d.ts.map