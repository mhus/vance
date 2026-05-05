import type { AccessTokenRequest, AccessTokenResponse } from '@vance/generated';
import { brainBaseUrl, getStorage, StorageKeys } from '@vance/shared';

export class LoginError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = 'LoginError';
  }
}

/**
 * Body-mode auth. The Brain's `/access/{username}` endpoint accepts
 * either a password (initial login) or a still-valid refresh token
 * (silent re-mint). Both branches return an `AccessTokenResponse`
 * carrying a fresh access token plus an optionally rotated refresh
 * token.
 *
 * Mobile bypasses the Brain's cookie path entirely
 * (`requestCookies: false`); JavaScript holds the bearer token and
 * places it in `Authorization` headers itself. The platform
 * `secureStore` keeps the tokens out of the app's regular
 * AsyncStorage (Keychain / Keystore on the underlying OS).
 */

async function postAccess(
  tenant: string,
  username: string,
  body: AccessTokenRequest,
): Promise<AccessTokenResponse> {
  const url = `${brainBaseUrl()}/brain/${encodeURIComponent(tenant)}/access/${encodeURIComponent(username)}`;
  const r = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!r.ok) {
    throw new LoginError(
      r.status,
      r.status === 401 ? 'Invalid credentials.' : `Login failed (${r.status}).`,
    );
  }
  return (await r.json()) as AccessTokenResponse;
}

function persistTokens(tenant: string, username: string, resp: AccessTokenResponse): void {
  const store = getStorage();
  store.secureStore.set(StorageKeys.authAccessToken, resp.token);
  if (resp.refreshToken !== undefined) {
    store.secureStore.set(StorageKeys.authRefreshToken, resp.refreshToken);
  }
  store.prefsStore.set(StorageKeys.identityTenantId, tenant);
  store.prefsStore.set(StorageKeys.identityUsername, username);
}

/**
 * Initial login. Trades password for access + refresh tokens.
 */
export async function login(params: {
  tenant: string;
  username: string;
  password: string;
}): Promise<void> {
  const resp = await postAccess(params.tenant, params.username, {
    password: params.password,
    requestRefreshToken: true,
    requestCookies: false,
    includeWebUiSettings: false,
  });
  persistTokens(params.tenant, params.username, resp);
}

/**
 * Single-flight body-mode refresh. Concurrent callers (multiple
 * REST requests that all 401 at the same time) share the same
 * underlying network round-trip — the second call returns the
 * promise the first call made.
 */
let inFlight: Promise<boolean> | null = null;

export function silentRefresh(): Promise<boolean> {
  if (inFlight !== null) return inFlight;
  inFlight = doRefresh().finally(() => {
    inFlight = null;
  });
  return inFlight;
}

async function doRefresh(): Promise<boolean> {
  const store = getStorage();
  const tenant = store.prefsStore.get(StorageKeys.identityTenantId);
  const username = store.prefsStore.get(StorageKeys.identityUsername);
  const refreshToken = store.secureStore.get(StorageKeys.authRefreshToken);
  if (tenant === null || username === null || refreshToken === null) {
    return false;
  }
  try {
    const resp = await postAccess(tenant, username, {
      refreshToken,
      requestRefreshToken: true,
      requestCookies: false,
      includeWebUiSettings: false,
    });
    persistTokens(tenant, username, resp);
    return true;
  } catch {
    // Network error or 401 — caller treats both as "must re-login".
    return false;
  }
}

/**
 * Local-only logout. Bearer tokens are stateless on the server
 * (no revocation list), so a server round-trip would be a no-op
 * for Mobile — we just forget them. The `/logout` endpoint exists
 * to clear browser cookies and is irrelevant to the bearer flow.
 *
 * After this call any pending REST/WS request that holds the old
 * token will continue with it until expiry; callers that need a
 * hard cut-off must additionally cancel in-flight work.
 */
export function logoutLocal(): void {
  const store = getStorage();
  store.secureStore.remove(StorageKeys.authAccessToken);
  store.secureStore.remove(StorageKeys.authRefreshToken);
  store.prefsStore.remove(StorageKeys.identityTenantId);
  store.prefsStore.remove(StorageKeys.identityUsername);
  store.prefsStore.remove(StorageKeys.activeSessionId);
}
