import type { AccessTokenRequest, AccessTokenResponse } from '@vance/generated';
import { brainBaseUrl } from '../rest/restClient';
import { clearActiveWebUiSettings } from './webUiSession';

export class LoginError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
    this.name = 'LoginError';
  }
}

/**
 * `POST /brain/{tenant}/access/{username}` — exchange username + password
 * for fresh credentials. The web UI runs the cookie-based variant: the
 * server sets {@code vance_access}, {@code vance_refresh} and
 * {@code vance_data} cookies on success and JavaScript never holds the
 * token. {@code credentials: 'include'} ensures the {@code Set-Cookie}
 * headers are honoured even when the SPA is hosted on a different
 * origin in dev.
 *
 * Throws {@link LoginError} on any non-2xx response. The Brain returns
 * 401 with no body for any failure (unknown user, wrong password,
 * deactivated account) to prevent user enumeration — we surface that
 * as a generic error.
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
    throw new LoginError(response.status, response.status === 401
      ? 'Invalid credentials.'
      : `Login failed with status ${response.status}.`);
  }

  // The server has set the cookies. We don't keep anything in JS —
  // subsequent reads go through {@code getSessionData()} on the
  // {@code vance_data} cookie. Discard the JSON body; it carries the
  // same tokens as the cookies already do.
  await response.json().catch(() => undefined as unknown as AccessTokenResponse);
}

/**
 * Silent re-mint via the refresh cookie. The browser ships
 * {@code vance_refresh} (HttpOnly) automatically; the server uses it as
 * the credential and sets fresh access/data cookies. JavaScript never
 * touches the refresh token itself.
 *
 * Returns {@code true} on success, {@code false} when the refresh
 * cookie is missing/expired/rejected. Caller treats {@code false} as
 * "redirect to login".
 */
export async function silentLogin(params: {
  tenant: string;
  username: string;
}): Promise<boolean> {
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
    return response.ok;
  } catch {
    return false;
  }
}

/**
 * Server-side logout. Clears all three cookies via {@code Max-Age=0}.
 * The {@code HttpOnly} access and refresh cookies cannot be cleared
 * from JavaScript, so this server round-trip is the only way to fully
 * log a user out.
 *
 * Best-effort: if the network call fails, we still wipe the JS-readable
 * data cookie locally and let the caller redirect.
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
  // Drop the per-tab UI overrides too — otherwise the next user on
  // this tab inherits the previous user's language.
  clearActiveWebUiSettings();
}
