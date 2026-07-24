import { silentLogin } from './loginWeb';
import {
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
  if (!isRefreshAlive()) return false;
  const ok = await silentLogin({ tenant: session.tenantId, username: session.username });
  if (ok) {
    hydrateIdentity();
  }
  return ok;
}
