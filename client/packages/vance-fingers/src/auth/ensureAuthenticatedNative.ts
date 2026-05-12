import { decodeJwt, getStorage, StorageKeys } from '@vance/shared';
import { silentRefresh } from './loginNative';

/**
 * Boot-time gate. Returns `true` when a request can proceed with a
 * valid bearer token (existing access token is fresh, or the refresh
 * round-trip succeeded), `false` when the user must sign in from
 * scratch.
 *
 * Conservative 30 s safety margin on the access expiry — a request
 * that takes a few seconds to round-trip should not see a 401
 * mid-flight when we could have refreshed proactively.
 */
export async function ensureAuthenticated(): Promise<boolean> {
  const store = getStorage();
  const access = store.secureStore.get(StorageKeys.authAccessToken);
  if (access !== null) {
    const claims = decodeJwt(access);
    if (claims !== null && claims.expiresAtMs - 30_000 > Date.now()) {
      return true;
    }
  }

  const refresh = store.secureStore.get(StorageKeys.authRefreshToken);
  if (refresh === null) return false;
  return silentRefresh();
}
