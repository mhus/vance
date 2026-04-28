import { isTokenValid } from './jwtClaims';
import { getJwt } from './jwtStorage';
import { refreshToken } from './refreshClient';

/**
 * Editor-page guard. Call at the very top of an editor's `main.ts` before
 * mounting the Vue app:
 *
 * ```ts
 * await ensureAuthenticated();
 * createApp(App).mount('#app');
 * ```
 *
 * Behaviour:
 * 1. If a valid JWT is present, return immediately.
 * 2. If a JWT is present but expiring within 30s, try to refresh. If the
 *    refresh succeeds, return.
 * 3. Otherwise redirect the browser to `index.html` with the current URL as
 *    the `next` query parameter, and never resolve — the page is being
 *    replaced.
 */
export async function ensureAuthenticated(): Promise<void> {
  const jwt = getJwt();
  if (jwt && isTokenValid(jwt)) return;

  if (jwt) {
    const refreshed = await refreshToken();
    if (refreshed && isTokenValid(refreshed)) return;
  }

  const currentUrl = window.location.pathname + window.location.search + window.location.hash;
  const next = encodeURIComponent(currentUrl);
  window.location.href = `/index.html?next=${next}`;
  // The redirect is async; suspend forever so the caller does not mount.
  return new Promise<void>(() => {});
}
