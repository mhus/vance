import { silentLogin } from './loginClient';
import { getSessionData, hydrateActiveWebUiSettings, isRefreshAlive } from './webUiSession';

/**
 * Re-mint the access cookie via the refresh cookie. Returns {@code true}
 * when the server issued fresh cookies, {@code false} when the refresh
 * cookie is missing/expired/rejected — caller should treat that as a
 * hard logout.
 *
 * <p>Replaces the previous header-based {@code refreshToken()} flow.
 * JavaScript never holds the access or refresh token now; the refresh
 * cookie is {@code HttpOnly} and travels with the request automatically.
 *
 * <p>Re-hydrates the session-storage UI settings on success — a
 * silent-refresh re-issues the data cookie with whatever the server
 * thinks the current {@code webui.*} values are, and the tab needs to
 * mirror those.
 */
export async function refreshAccessCookie(): Promise<boolean> {
  const session = getSessionData();
  if (!session) return false;
  if (!isRefreshAlive()) return false;
  const ok = await silentLogin({ tenant: session.tenantId, username: session.username });
  if (ok) {
    hydrateActiveWebUiSettings();
  }
  return ok;
}
