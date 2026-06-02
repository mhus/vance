/**
 * Editor-page guard. Call at the very top of an editor's `main.ts`
 * before mounting the Vue app:
 *
 * ```ts
 * import '../platform/bootWeb';
 * import { ensureAuthenticated } from '../platform/ensureAuthenticatedWeb';
 *
 * await ensureAuthenticated();
 * createApp(App).mount('#app');
 * ```
 *
 * Cookie-era behaviour:
 * 1. If the `vance_data` cookie shows a still-alive access expiry
 *    (with a 30s safety margin), return immediately. The
 *    `vance_access` cookie is HttpOnly so we trust the expiry
 *    timestamp the server stamped into the data cookie at login.
 * 2. If access has expired but refresh is still alive, fire a silent
 *    re-mint. On success the server issues fresh cookies and we
 *    return.
 * 3. Otherwise redirect to `index.html` with the current URL as the
 *    `next` query parameter, and never resolve — the page is being
 *    replaced.
 */
export declare function ensureAuthenticated(): Promise<void>;
//# sourceMappingURL=ensureAuthenticatedWeb.d.ts.map