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
export declare function refreshAccessCookie(): Promise<boolean>;
//# sourceMappingURL=refreshWeb.d.ts.map