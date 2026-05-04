import Constants from 'expo-constants';
import { configurePlatform } from '@vance/shared';
import { preloadStorage, storageNative } from './storageNative';

/**
 * Mobile boot hook. Imported for its side effect at the top of
 * `App.tsx` before any other module touches storage, REST or
 * WebSocket. Mirrors the role of `vance-face/src/platform/bootWeb.ts`.
 *
 * Phase A wires the adapters and preloads the storage caches.
 * The {@link RestConfig.refreshAccess} and
 * {@link RestConfig.onUnauthorized} callbacks are stubs at this
 * stage — Phase B replaces them with the body-mode refresh helper
 * and a navigation callback into the Login screen.
 */
function resolveBaseUrl(): string {
  const fromExtra = Constants.expoConfig?.extra?.brainUrl;
  if (typeof fromExtra === 'string' && fromExtra.length > 0) return fromExtra;
  throw new Error(
    'vance-mobile: brainUrl is not configured. Set extra.brainUrl in app.config.ts or VANCE_BRAIN_URL.',
  );
}

configurePlatform({
  storage: storageNative,
  rest: {
    baseUrl: resolveBaseUrl(),
    authMode: 'bearer',
    refreshAccess: async () => {
      // Phase B: plug in `refreshNative()` here.
      // Until then any 401 is treated as a hard logout.
      return false;
    },
    onUnauthorized: () => {
      // Phase B: navigation.replace('Login').
      console.warn(
        'vance-mobile: 401 received but Login navigation not yet wired (Phase B).',
      );
    },
  },
});

/**
 * Resolve once the in-memory storage caches are warm. `App.tsx`
 * awaits this before rendering any auth-dependent UI — `getStorage()`
 * reads return correct values only after the preload completes.
 */
export const bootStoragePromise = preloadStorage();
