import { configurePlatform, configureVanceWs } from '@vance/shared';
import { refreshAccessCookie } from './refreshWeb';
import { hydrateIdentity } from './webUiSession';
import { applyTheme } from './themeWeb';
import { storageWeb } from './storageWeb';
import { onDocumentChanged, onDocumentChangedPrefix, onDocumentPrefixReconnect, subscribeDocument, subscribeDocumentPrefix, unsubscribeDocument, unsubscribeDocumentPrefix, } from '@/ws/wsConnectionStore';
/**
 * Web boot hook. Imported for side effect at the top of every
 * editor's `main.ts` so `configurePlatform` runs before any other
 * `@vance/shared` module touches storage or the network.
 *
 * The base URL is always the page's own origin — in production the
 * SPA is same-origin-served by the brain, in `vite dev` the
 * dev-server proxies `/brain/*` to `http://localhost:9990`. No
 * build-time environment variable: deployment-specific values like
 * the brain URL belong in the runtime `config.json` written by the
 * pod entrypoint, not baked into the JS bundle.
 *
 * After binding, mirror the cookie-derived identity into the
 * prefsStore so `getTenantId()` / `getUsername()` from
 * `@vance/shared` find the values via the same path Mobile uses.
 */
function resolveBaseUrl() {
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
// Expose the wsConnectionStore singleton through `@vance/shared` so
// Module-Federation addons (Calendar, Kanban, Slideshow, …) can drive
// the same WebSocket / subscription set. See
// `repos/vance/client/packages/shared/src/ws/bridge.ts` for the
// rationale (each addon ships its own `@vance/shared` copy; the
// `__VANCE_WS__` globalThis stash is the rendezvous point).
configureVanceWs({
    subscribeDocument,
    unsubscribeDocument,
    onDocumentChanged,
    subscribeDocumentPrefix,
    unsubscribeDocumentPrefix,
    onDocumentChangedPrefix,
    onDocumentPrefixReconnect,
});
// Cookie-derived identity → prefsStore. Idempotent — runs every page
// load, ensuring the values stay synchronised with the data cookie.
hydrateIdentity();
// Paint the resolved theme as early as possible — before Vue mounts,
// so the first frame matches the user's choice. "auto" attaches a
// matchMedia listener that keeps tracking the OS preference live.
applyTheme();
//# sourceMappingURL=bootWeb.js.map