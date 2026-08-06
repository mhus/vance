import type { AccessTokenRequest, AccessTokenResponse } from '@vance/generated';
import { brainBaseUrl } from '@vance/shared';
import { pushShareCredentials } from './faceliftShareSetup';

export class LoginError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
    this.name = 'LoginError';
  }
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
export async function login(params: {
  tenant: string;
  username: string;
  password: string;
}): Promise<void> {
  const body: AccessTokenRequest = {
    password: params.password,
    requestRefreshToken: true,
    requestCookies: true,
    includeWebUiSettings: true,
  };
  const url = `${brainBaseUrl()}/brain/${encodeURIComponent(params.tenant)}/access/${encodeURIComponent(params.username)}`;

  const response = await fetch(url, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    throw new LoginError(
      response.status,
      response.status === 401
        ? 'Invalid credentials.'
        : `Login failed with status ${response.status}.`,
    );
  }

  // The server has set the cookies. We don't keep anything in JS —
  // subsequent reads go through getSessionData() on the vance_data
  // cookie. The body, though, carries the bearer access + refresh
  // tokens we forward to the Facelift wrapper so its iOS
  // Share-Extension can POST shares without holding cookies.
  const parsed = (await response.json().catch(() => undefined)) as
    | AccessTokenResponse
    | undefined;
  if (parsed !== undefined && typeof parsed.token === 'string') {
    pushShareCredentials({
      tenant: params.tenant,
      username: params.username,
      token: parsed.token,
      refreshToken: parsed.refreshToken,
    });
  }
}

/**
 * Outcome of a silent re-mint. `rejected` and `failed` both mean "not
 * authenticated", but they must not be treated alike: only `rejected`
 * proves the stored session is dead. Collapsing the two would either
 * strand the client on a stale session (see {@code refreshAccessCookie})
 * or log the user out over a dropped connection.
 */
export type SilentLoginOutcome =
  /** Server issued fresh cookies. */
  | 'ok'
  /** Server answered 401/403 — the refresh credential is dead. */
  | 'rejected'
  /** Unreachable, or answered with an unrelated status (5xx). Session unproven. */
  | 'failed';

/**
 * Silent re-mint via the refresh cookie. The browser ships
 * `vance_refresh` (HttpOnly) automatically; the server uses it as
 * the credential and sets fresh access/data cookies. JavaScript
 * never touches the refresh token itself.
 */
export async function silentLogin(params: {
  tenant: string;
  username: string;
}): Promise<SilentLoginOutcome> {
  const body: AccessTokenRequest = {
    requestRefreshToken: true,
    requestCookies: true,
    includeWebUiSettings: true,
  };
  const url = `${brainBaseUrl()}/brain/${encodeURIComponent(params.tenant)}/access/${encodeURIComponent(params.username)}`;

  try {
    const response = await fetch(url, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (response.ok) {
      // Same Facelift-share token forwarding as the initial login —
      // every silent refresh hands a fresh bearer to the App-Group.
      const parsed = (await response.json().catch(() => undefined)) as
        | AccessTokenResponse
        | undefined;
      if (parsed !== undefined && typeof parsed.token === 'string') {
        pushShareCredentials({
          tenant: params.tenant,
          username: params.username,
          token: parsed.token,
          refreshToken: parsed.refreshToken,
        });
      }
      return 'ok';
    }
    // Only an explicit auth refusal proves the credential is dead. A 5xx
    // is the server having a bad day — keep the session and let the user
    // retry rather than dropping them at the login form.
    return response.status === 401 || response.status === 403 ? 'rejected' : 'failed';
  } catch {
    return 'failed';
  }
}

/**
 * Server-side logout. Clears all three cookies via `Max-Age=0`. The
 * `HttpOnly` access and refresh cookies cannot be cleared from
 * JavaScript, so this server round-trip is the only way to fully log
 * a user out.
 *
 * Best-effort: if the network call fails, we still wipe the
 * JS-readable data cookie locally and let the caller redirect.
 */
export async function logout(tenant: string): Promise<void> {
  try {
    await fetch(`${brainBaseUrl()}/brain/${encodeURIComponent(tenant)}/logout`, {
      method: 'POST',
      credentials: 'include',
    });
  } catch {
    // Network failure is fine — the cookies will eventually expire,
    // and the local clear below removes the data cookie immediately.
  }
  document.cookie = 'vance_data=; Max-Age=0; Path=/; SameSite=Strict';
}
