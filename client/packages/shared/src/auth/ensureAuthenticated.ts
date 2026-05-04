import { refreshAccessCookie } from './refreshClient';
import { getSessionData, isAccessAlive, isRefreshAlive } from './webUiSession';

/**
 * Editor-page guard. Call at the very top of an editor's `main.ts`
 * before mounting the Vue app:
 *
 * ```ts
 * await ensureAuthenticated();
 * createApp(App).mount('#app');
 * ```
 *
 * Cookie-era behaviour:
 * 1. If the {@code vance_data} cookie shows a still-alive access
 *    expiry (with a 30s safety margin), return immediately. The
 *    {@code vance_access} cookie is HttpOnly so we trust the
 *    expiry timestamp the server stamped into the data cookie at
 *    login.
 * 2. If access has expired but refresh is still alive, fire a silent
 *    re-mint. On success the server issues fresh cookies and we
 *    return.
 * 3. Otherwise redirect to {@code index.html} with the current URL as
 *    the {@code next} query parameter, and never resolve — the page
 *    is being replaced.
 */
export async function ensureAuthenticated(): Promise<void> {
  if (isAccessAlive()) return;

  if (getSessionData() && isRefreshAlive()) {
    const ok = await refreshAccessCookie();
    if (ok && isAccessAlive()) return;
  }

  redirectToLogin();
  // The redirect is async; suspend forever so the caller does not mount.
  return new Promise<void>(() => {});
}

function redirectToLogin(): void {
  const currentUrl = window.location.pathname + window.location.search + window.location.hash;
  const next = encodeURIComponent(currentUrl);
  window.location.href = `/index.html?next=${next}`;
}
