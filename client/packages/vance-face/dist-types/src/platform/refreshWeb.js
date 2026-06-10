import { silentLogin } from './loginWeb';
import { getSessionData, hydrateIdentity, isRefreshAlive, } from './webUiSession';
/**
 * Re-mint the access cookie via the refresh cookie. Returns `true`
 * when the server issued fresh cookies, `false` when the refresh
 * cookie is missing/expired/rejected — caller should treat that as a
 * hard logout.
 *
 * JavaScript never holds the access or refresh token; the refresh
 * cookie is `HttpOnly` and travels with the request automatically.
 *
 * Re-hydrates the identity mirror in the platform's prefsStore on
 * success so shared modules see the fresh tenant/username. The
 * webui.* settings ride along in the freshly issued data cookie and
 * are read straight from there by `getActive*()`.
 */
export async function refreshAccessCookie() {
    const session = getSessionData();
    if (!session)
        return false;
    if (!isRefreshAlive())
        return false;
    const ok = await silentLogin({ tenant: session.tenantId, username: session.username });
    if (ok) {
        hydrateIdentity();
    }
    return ok;
}
//# sourceMappingURL=refreshWeb.js.map