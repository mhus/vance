import { getStorage } from '@vance/shared';

/**
 * Tiny preference store for per-screen sort orders. Lives in the
 * platform-bound prefsStore so the user's choice survives app
 * restarts. We pick our own key namespace (`vance.sort.<scope>`) to
 * avoid colliding with shared `StorageKeys`.
 */
const KEY_PREFIX = 'vance.sort.';

export function getSortPref<T extends string>(scope: string, fallback: T): T {
  const raw = getStorage().prefsStore.get(KEY_PREFIX + scope);
  return raw === null ? fallback : (raw as T);
}

export function setSortPref(scope: string, value: string): void {
  getStorage().prefsStore.set(KEY_PREFIX + scope, value);
}
