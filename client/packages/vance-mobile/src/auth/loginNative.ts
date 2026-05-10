import type { AccessTokenRequest, AccessTokenResponse } from '@vance/generated';
import {
  brainBaseUrl,
  configurePlatform,
  getRestConfig,
  getStorage,
  StorageKeys,
} from '@vance/shared';
import {
  type Account,
  currentAccount,
  fallbackAccountAfter,
  removeAccount,
  saveTokensFor,
  setCurrent,
  upsertAccount,
} from './accountStore';
import {
  clearActiveMirror,
  switchToAccount,
} from './accountSwitch';

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
 * Rebind the platform's REST baseUrl to {@code newBaseUrl} and
 * persist it under {@link StorageKeys.identityBrainUrl} so a future
 * cold-start picks up the same URL. Re-uses the rest of the
 * configuration ({@code authMode}, {@code refreshAccess},
 * {@code onUnauthorized}) — those are owned by {@code bootNative.ts}
 * and don't change at login time.
 */
function applyBrainUrl(newBaseUrl: string): void {
  const trimmed = newBaseUrl.replace(/\/+$/, '');
  const current = getRestConfig();
  if (current.baseUrl === trimmed) return;
  configurePlatform({
    storage: getStorage(),
    rest: {
      ...current,
      baseUrl: trimmed,
    },
  });
  getStorage().prefsStore.set(StorageKeys.identityBrainUrl, trimmed);
}

/**
 * Initial login. Trades password for access + refresh tokens.
 *
 * <p>If {@code brainUrl} is provided, rebinds the platform's REST
 * baseUrl and persists the value before the credentials hit the
 * network — so the {@code postAccess} call below already targets the
 * user's chosen server.
 *
 * <p>On success the account is registered in the multi-account
 * inventory ({@code accountStore}) and marked as current; the new
 * tokens land both in the per-account backup and in the flat
 * active-mirror keys. Existing entries with the same
 * {@code (brainUrl, tenantId, username)} triple are reused — repeat
 * logins for the same account don't pile up duplicates.
 */
export async function login(params: {
  tenant: string;
  username: string;
  password: string;
  brainUrl?: string;
  /** Optional human-friendly label for the account; falls back to
   *  {@code username@tenant}. Honoured only when a fresh entry is
   *  created — re-logins keep the existing display name unless
   *  callers explicitly pass a new one. */
  displayName?: string;
}): Promise<Account> {
  if (params.brainUrl !== undefined && params.brainUrl.trim().length > 0) {
    applyBrainUrl(params.brainUrl.trim());
  }
  const resolvedBrainUrl = getRestConfig().baseUrl;
  const resp = await postAccess(params.tenant, params.username, {
    password: params.password,
    requestRefreshToken: true,
    requestCookies: false,
    includeWebUiSettings: false,
  });
  const account = upsertAccount({
    brainUrl: resolvedBrainUrl,
    tenantId: params.tenant,
    username: params.username,
    displayName: params.displayName,
  });
  setCurrent(account.id);
  await saveTokensFor(account.id, resp.token, resp.refreshToken ?? undefined);
  // Mirror identity into the flat keys for synchronous readers
  // (getTenantId, getUsername, ...) — same shape as before, just
  // sourced from the account record now.
  persistTokens(account.tenantId, account.username, resp);
  return account;
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
 * Sign the active account out. Bearer tokens are stateless on the
 * server (no revocation list), so the server round-trip would be a
 * no-op for Mobile — we just forget the tokens locally.
 *
 * <p>Multi-account semantics:
 *
 * <ul>
 *   <li>Removes the active account from the inventory (its per-account
 *       token backup is wiped along with it).</li>
 *   <li>If another account remains, switches to the most-recently-used
 *       one — flat-mirror is updated, brain URL rebound,
 *       {@code queryClient} cleared. Returns the new active account
 *       so the caller can decide whether to navigate (most callers
 *       simply rely on the {@code <RootNavigator key=…>} re-mount
 *       triggered by the {@code accountStore} subscription).</li>
 *   <li>If no other account remains, the active mirror is wiped and
 *       the function returns {@code null} — the caller should reset
 *       to the login screen.</li>
 * </ul>
 *
 * <p>After this call any pending REST/WS request that holds the old
 * token will continue with it until expiry; callers that need a
 * hard cut-off must additionally cancel in-flight work.
 */
export async function logoutLocal(): Promise<Account | null> {
  const active = currentAccount();
  if (active === null) {
    // No active account — only flat mirror to clean up.
    clearActiveMirror();
    return null;
  }
  await removeAccount(active.id);
  const next = fallbackAccountAfter(active.id);
  if (next === null) {
    clearActiveMirror();
    return null;
  }
  return switchToAccount(next.id);
}

