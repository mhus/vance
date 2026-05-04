import { StorageKeys } from '../persistence/keys';
import { getSessionData } from './webUiSession';

// Identity helpers that used to read from localStorage. After the
// move to cookie-based auth, the access and refresh tokens live in
// HttpOnly cookies that JavaScript cannot read. Identity (username,
// tenant, display name) comes from the JS-readable {@code vance_data}
// cookie via {@link getSessionData}.
//
// {@code getJwt} is gone — JavaScript never sees the JWT now. Editor
// code that wants identity calls {@link getTenantId} /
// {@link getUsername}; code that needs to authenticate a request just
// uses {@code credentials: 'include'} on the fetch call so the browser
// attaches the cookies automatically.

export function getTenantId(): string | null {
  return getSessionData()?.tenantId ?? null;
}

export function getUsername(): string | null {
  return getSessionData()?.username ?? null;
}

export function getActiveSessionId(): string | null {
  return localStorage.getItem(StorageKeys.activeSessionId);
}

export function setActiveSessionId(sessionId: string | null): void {
  if (sessionId === null) {
    localStorage.removeItem(StorageKeys.activeSessionId);
  } else {
    localStorage.setItem(StorageKeys.activeSessionId, sessionId);
  }
}

/**
 * Migrate a localStorage install. Old builds wrote {@code vance.jwt},
 * {@code vance.tenantId}, {@code vance.username}; the cookie shape
 * makes those obsolete. Removing them on first load keeps a stale
 * token from leaking back into a page that was about to be reloaded.
 */
export function clearLegacyAuth(): void {
  localStorage.removeItem(StorageKeys.jwt);
  localStorage.removeItem(StorageKeys.tenantId);
  localStorage.removeItem(StorageKeys.username);
}

/**
 * No-op kept as a deprecation surface — call {@code logout(tenant)}
 * from {@code loginClient} instead, which fires a server-side cookie
 * clear. This stub exists only so old call sites don't break the
 * build until they migrate.
 *
 * @deprecated use {@code logout(tenant)} from {@code loginClient}
 */
export function clearAuth(): void {
  clearLegacyAuth();
}
