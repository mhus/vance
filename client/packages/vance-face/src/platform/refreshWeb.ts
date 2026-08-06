import { silentLogin } from './loginWeb';
import {
  clearLocalSessionData,
  getSessionData,
  hydrateIdentity,
  isRefreshAlive,
} from './webUiSession';

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
let inflight: Promise<boolean> | null = null;

/**
 * Single-flight guard required by the {@code RestConfig.refreshAccess}
 * contract: when the access cookie expires, several in-flight requests all
 * 401 at once and each calls here. Without dedup that fires N parallel
 * {@code POST /access/{username}} with the same (not-yet-rotated) refresh
 * cookie → Set-Cookie races and, with one-time-use refresh tokens, the
 * stragglers are rejected → the user is logged out despite a valid session.
 * Concurrent callers share the one promise; the next refresh (after this
 * one settles) starts fresh. Mirrors the in-flight pattern in linkPreview.
 */
export function refreshAccessCookie(): Promise<boolean> {
  if (inflight) return inflight;
  inflight = doRefreshAccessCookie().finally(() => {
    inflight = null;
  });
  return inflight;
}

async function doRefreshAccessCookie(): Promise<boolean> {
  const session = getSessionData();
  if (!session) return false;
  if (!isRefreshAlive()) {
    // The cookie's own stamp says the refresh credential is spent, so the
    // session is provably dead without asking the server — same stale-data
    // problem as a rejection below, same remedy.
    clearLocalSessionData();
    return false;
  }
  const outcome = await silentLogin({ tenant: session.tenantId, username: session.username });
  if (outcome === 'ok') {
    hydrateIdentity();
    return true;
  }
  if (outcome === 'rejected') {
    // The data cookie is not HttpOnly and carries the expiry stamps the
    // client trusts, so a server refusal leaves it claiming a session
    // that no longer exists. Left standing, that lie loops the user:
    // `ensureAuthenticated` / `IndexApp` see `isAccessAlive()` and bounce
    // straight back into the editor, which 401s and redirects again.
    // Dropping it makes the local view match the server's answer, so the
    // login form is finally reached.
    clearLocalSessionData();
  }
  return false;
}
