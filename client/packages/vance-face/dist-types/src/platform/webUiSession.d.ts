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
 * Wipe the session-scoped UI overrides. Called from the logout path
 * so a subsequent login on the same tab cleanly picks up the next
 * user's settings instead of inheriting the previous session's
 * language.
 */
export declare function clearActiveWebUiSettings(): void;
//# sourceMappingURL=webUiSession.d.ts.map