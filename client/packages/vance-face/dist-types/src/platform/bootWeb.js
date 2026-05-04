import { configurePlatform } from '@vance/shared';
import { refreshAccessCookie } from './refreshWeb';
import { hydrateActiveWebUiSettings, hydrateIdentity } from './webUiSession';
import { storageWeb } from './storageWeb';
/**
 * Web boot hook. Imported for side effect at the top of every
 * editor's `main.ts` so `configurePlatform` runs before any other
 * `@vance/shared` module touches storage or the network.
 *
 * The base URL falls back to the page's own origin in production
 * (when the SPA is served by the Brain itself); `vite dev` sets
 * `VITE_BRAIN_URL` to point at a separately running Brain.
 *
 * After binding, mirror the cookie-derived identity into the
 * prefsStore so `getTenantId()` / `getUsername()` from
 * `@vance/shared` find the values via the same path Mobile uses.
 */
function resolveBaseUrl() {
    const fromEnv = import.meta.env
        ?.VITE_BRAIN_URL;
    if (fromEnv && fromEnv.length > 0)
        return fromEnv;
    return `${window.location.protocol}//${window.location.host}`;
}
function redirectToLogin() {
    const next = encodeURIComponent(window.location.pathname + window.location.search + window.location.hash);
    window.location.href = `/index.html?next=${next}`;
}
configurePlatform({
    storage: storageWeb,
    rest: {
        baseUrl: resolveBaseUrl(),
        authMode: 'cookie',
        refreshAccess: refreshAccessCookie,
        onUnauthorized: redirectToLogin,
    },
});
// Cookie-derived identity → prefsStore. Idempotent — runs every page
// load, ensuring the values stay synchronised with the data cookie.
hydrateIdentity();
// Same for the webui.* settings mirror in sessionStorage.
hydrateActiveWebUiSettings();
//# sourceMappingURL=bootWeb.js.map