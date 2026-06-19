import { getStorage } from '../platform/index';
import { StorageKeys } from '../storage/keys';

// Identity helpers read from the platform-bound prefsStore. The host
// is responsible for keeping these keys in sync with the authoritative
// source: Web copies them from the `vance_data` cookie at boot,
// Mobile writes them after a successful body-mode login.
//
// JavaScript never sees the access JWT on Web (HttpOnly cookie); on
// Mobile the bearer token lives in the platform's `secureStore`
// (a different store under the same {@link PlatformStorage} binding).

export function getTenantId(): string | null {
  return getStorage().prefsStore.get(StorageKeys.identityTenantId);
}

export function getUsername(): string | null {
  return getStorage().prefsStore.get(StorageKeys.identityUsername);
}

export function getActiveSessionId(): string | null {
  return getStorage().prefsStore.get(StorageKeys.activeSessionId);
}

export function setActiveSessionId(sessionId: string | null): void {
  const prefs = getStorage().prefsStore;
  if (sessionId === null) {
    prefs.remove(StorageKeys.activeSessionId);
  } else {
    prefs.set(StorageKeys.activeSessionId, sessionId);
  }
}

/**
 * Per-WebSocket-connection identity assigned by the server in the
 * {@code welcome} frame. Kept in module-level state (not persisted) so
 * REST writes can include it as the {@code X-Editor-Id} header — the
 * brain's live-broadcast layer uses it to filter the writer's own
 * connection out of {@code documents.changed} fan-out, preventing the
 * "saw my own save as an external change" banner. The value is reset
 * to {@code null} on disconnect; a reconnect produces a fresh value.
 */
let currentEditorId: string | null = null;

export function getCurrentEditorId(): string | null {
  return currentEditorId;
}

export function setCurrentEditorId(editorId: string | null): void {
  currentEditorId = editorId;
}

/**
 * Migrate from a previous-era localStorage install. Removes the
 * legacy `vance.jwt`, `vance.tenantId`, `vance.username` entries that
 * the Web UI used before the cookie-based auth landed. Idempotent —
 * safe to call on every boot. Web binds the prefsStore to
 * localStorage, so this still has the same effect as before; on
 * Mobile it is a no-op (those keys never existed in AsyncStorage).
 */
export function clearLegacyAuth(): void {
  const prefs = getStorage().prefsStore;
  prefs.remove('vance.jwt');
  prefs.remove('vance.tenantId');
  prefs.remove('vance.username');
}

/**
 * @deprecated use `logout(tenant)` from `loginClient` instead, which
 * fires a server-side cookie clear. This stub exists only so old
 * call sites don't break the build until they migrate.
 */
export function clearAuth(): void {
  clearLegacyAuth();
}
