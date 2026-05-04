import type { KeyValueStore, PlatformStorage } from '@vance/shared';

/**
 * Web `localStorage`-backed implementation of {@link KeyValueStore}.
 * The same instance backs both `secureStore` and `prefsStore` on Web —
 * cookies are managed by the browser and not modeled by this layer,
 * so the secure / pref distinction collapses (the access JWT lives in
 * an HttpOnly cookie that JavaScript never sees).
 *
 * Bound at boot by `bootWeb.ts` via `configurePlatform`.
 */
const localStorageBacked: KeyValueStore = {
  get(key) {
    return window.localStorage.getItem(key);
  },
  set(key, value) {
    window.localStorage.setItem(key, value);
  },
  remove(key) {
    window.localStorage.removeItem(key);
  },
};

export const storageWeb: PlatformStorage = {
  secureStore: localStorageBacked,
  prefsStore: localStorageBacked,
};
