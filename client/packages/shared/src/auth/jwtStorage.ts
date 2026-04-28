import { StorageKeys } from '../persistence/keys';

// Thin wrapper around `localStorage` for the four auth-relevant values.
// All access to those keys must go through this module — direct
// `localStorage.getItem('vance.jwt')` calls in the UI are forbidden.

export function getJwt(): string | null {
  return localStorage.getItem(StorageKeys.jwt);
}

export function getTenantId(): string | null {
  return localStorage.getItem(StorageKeys.tenantId);
}

export function getUsername(): string | null {
  return localStorage.getItem(StorageKeys.username);
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
 * Store a freshly-minted token together with the user/tenant it belongs to.
 * Called by `loginClient` and `refreshClient`.
 */
export function storeAuth(params: { jwt: string; tenantId: string; username: string }): void {
  localStorage.setItem(StorageKeys.jwt, params.jwt);
  localStorage.setItem(StorageKeys.tenantId, params.tenantId);
  localStorage.setItem(StorageKeys.username, params.username);
}

/**
 * Wipe everything auth-related. Called on logout and on irrecoverable 401s.
 * Leaves `activeSessionId` in place so a subsequent login can resume it —
 * unless the caller explicitly wants a clean slate, in which case they
 * call `setActiveSessionId(null)` afterwards.
 */
export function clearAuth(): void {
  localStorage.removeItem(StorageKeys.jwt);
  localStorage.removeItem(StorageKeys.tenantId);
  localStorage.removeItem(StorageKeys.username);
}
