import { silentLogin } from './loginWeb';
import { getSessionData, hydrateActiveWebUiSettings, hydrateIdentity, isRefreshAlive, } from './webUiSession';
/**
 * Re-mint the access cookie via the refresh cookie. Returns `true`
 * when the server issued fresh cookies, `false` when the refresh
 * cookie is missing/expired/rejected — caller should treat that as a
 * hard logout.
 *
 * JavaScript never holds the access or refresh token; the refresh
 * cookie is `HttpOnly` and travels with the request automatically.
 *
 * Re-hydrates the session-storage UI settings and the identity
 * mirror in the platform's prefsStore on success — a silent-refresh
 * re-issues the data cookie with whatever the server thinks the
 * current `webui.*` values are, and shared modules read identity from
 * the prefsStore.
 */
export async function refreshAccessCookie() {
    const session = getSessionData();
    if (!session)
        return false;
    if (!isRefreshAlive())
        return false;
    const ok = await silentLogin({ tenant: session.tenantId, username: session.username });
    if (ok) {
        hydrateActiveWebUiSettings();
        hydrateIdentity();
    }
    return ok;
}
//# sourceMappingURL=refreshWeb.js.map