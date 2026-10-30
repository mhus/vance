import { clearMe } from './me';
import type {
  AccessTokenRequest,
  AccessTokenResponse,
  EmailTokenStartResponse,
  EmailTokenVerifyRequest,
  LoginMethodsResponse,
} from '@vance/generated';
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
 * `GET /brain/{tenant}/login-methods` — which sign-in ways the installation
 * offers. The answer is the same for every tenant name, so a page that does not
 * know its tenant yet asks with a placeholder. `null` when the server cannot be
 * reached or predates the endpoint: the caller then keeps the classic password
 * form, which is what such a server accepts anyway.
 */
export async function fetchLoginMethods(tenant: string | null): Promise<LoginMethodsResponse | null> {
  try {
    const response = await fetch(
      `${brainBaseUrl()}/brain/${encodeURIComponent(tenant ?? '_')}/login-methods`,
      { credentials: 'include' },
    );
    if (!response.ok) return null;
    return (await response.json()) as LoginMethodsResponse;
  } catch {
    return null;
  }
}

/**
 * `POST /brain/{tenant}/access/{username}/email-token` — ask for a sign-in code
 * by e-mail. The server answers the same for every username, so success here
 * says nothing about the account. Throws {@link LoginError} on a refusal: 429
 * when this address asked too often, 404 when the mode is off.
 */
export async function startEmailToken(params: {
  tenant: string;
  username: string;
}): Promise<EmailTokenStartResponse> {
  const response = await fetch(`${emailTokenUrl(params)}`, {
    method: 'POST',
    credentials: 'include',
  });
  if (!response.ok) {
    throw new LoginError(response.status, `Requesting the code failed with status ${response.status}.`);
  }
  return (await response.json()) as EmailTokenStartResponse;
}

/**
 * `POST …/email-token/verify` — trade the code for the same cookies the password
 * login sets. Throws {@link LoginError}; 401 means a wrong, spent or expired code.
 */
export async function verifyEmailToken(params: {
  tenant: string;
  username: string;
  transactionId: string;
  token: string;
}): Promise<void> {
  const body: EmailTokenVerifyRequest = {
    transactionId: params.transactionId,
    token: params.token,
    requestRefreshToken: true,
    requestCookies: true,
  };
  const response = await fetch(`${emailTokenUrl(params)}/verify`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    throw new LoginError(
      response.status,
      response.status === 401 ? 'Invalid code.' : `Verification failed with status ${response.status}.`,
    );
  }
  const parsed = (await response.json().catch(() => undefined)) as AccessTokenResponse | undefined;
  if (parsed !== undefined && typeof parsed.token === 'string') {
    pushShareCredentials({
      tenant: params.tenant,
      username: params.username,
      token: parsed.token,
      refreshToken: parsed.refreshToken,
    });
  }
}

function emailTokenUrl(params: { tenant: string; username: string }): string {
  return `${brainBaseUrl()}/brain/${encodeURIComponent(params.tenant)}/access/${encodeURIComponent(params.username)}/email-token`;
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
  clearMe();
}
