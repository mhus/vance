import Constants from 'expo-constants';
import { configurePlatform } from '@vance/shared';
import { silentRefresh, logoutLocal } from '@/auth';
import { resetToLogin } from '@/navigation/navigationRef';
import { preloadStorage, storageNative } from './storageNative';

/**
 * Mobile boot hook. Imported for its side effect at the top of
 * `App.tsx` before any other module touches storage, REST or
 * WebSocket. Mirrors the role of `vance-face/src/platform/bootWeb.ts`.
 *
 * Phase B wires the body-mode refresh and the navigation-driven
 * `onUnauthorized` callback. The latter resets the navigation stack
 * onto the Login screen — `navigationRef` is established by
 * `App.tsx`'s `<NavigationContainer ref={navigationRef}>`, so the
 * reset is a no-op until the container has mounted (boot-time 401
 * cannot happen, since we don't make network calls before the
 * container is ready).
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
    refreshAccess: silentRefresh,
    onUnauthorized: () => {
      logoutLocal();
      resetToLogin();
    },
  },
});

/**
 * Resolve once the in-memory storage caches are warm. `App.tsx`
 * awaits this before rendering any auth-dependent UI — `getStorage()`
 * reads return correct values only after the preload completes.
 */
export const bootStoragePromise = preloadStorage();
