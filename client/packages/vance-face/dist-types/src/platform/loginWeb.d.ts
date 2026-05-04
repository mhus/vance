export declare class LoginError extends Error {
    readonly status: number;
    constructor(status: number, message: string);
}
/**
 * `POST /brain/{tenant}/access/{username}` — exchange username +
 * password for fresh credentials. The web UI runs the cookie-based
 * variant: the server sets `vance_access`, `vance_refresh` and
 * `vance_data` cookies on success and JavaScript never holds the
 * token. `credentials: 'include'` ensures the `Set-Cookie` headers
 * are honoured even when the SPA is hosted on a different origin in
 * dev.
 *
 * Throws {@link LoginError} on any non-2xx response. The Brain
 * returns 401 with no body for any failure (unknown user, wrong
 * password, deactivated account) to prevent user enumeration — we
 * surface that as a generic error.
 */
export declare function login(params: {
    tenant: string;
    username: string;
    password: string;
}): Promise<void>;
/**
 * Silent re-mint via the refresh cookie. The browser ships
 * `vance_refresh` (HttpOnly) automatically; the server uses it as
 * the credential and sets fresh access/data cookies. JavaScript
 * never touches the refresh token itself.
 *
 * Returns `true` on success, `false` when the refresh cookie is
 * missing/expired/rejected. Caller treats `false` as "redirect to
 * login".
 */
export declare function silentLogin(params: {
    tenant: string;
    username: string;
}): Promise<boolean>;
/**
 * Server-side logout. Clears all three cookies via `Max-Age=0`. The
 * `HttpOnly` access and refresh cookies cannot be cleared from
 * JavaScript, so this server round-trip is the only way to fully log
 * a user out.
 *
 * Best-effort: if the network call fails, we still wipe the
 * JS-readable data cookie locally and let the caller redirect.
 */
export declare function logout(tenant: string): Promise<void>;
//# sourceMappingURL=loginWeb.d.ts.map