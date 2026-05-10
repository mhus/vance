import Constants from 'expo-constants';
import { configurePlatform } from '@vance/shared';
import {
  currentAccount,
  logoutLocal,
  migrateFromFlat,
  silentRefresh,
} from '@/auth';
import { resetToLogin } from '@/navigation/navigationRef';
import { preloadStorage, storageNative } from './storageNative';

/**
 * Mobile boot hook. Imported for its side effect at the top of
 * `App.tsx` before any other module touches storage, REST or
 * WebSocket. Mirrors the role of `vance-face/src/platform/bootWeb.ts`.
 *
 * <p>Brain-URL resolution is two-phase. The synchronous phase
 * (module-load) configures the platform with the {@code app.config.ts}
 * default so any module that touches REST at module-load time has a
 * working baseUrl. After {@link preloadStorage} resolves we know
 * whether the user pinned a different URL on a previous login; if so,
 * we rebind {@link configurePlatform} with the persisted value.
 * {@code configurePlatform} is idempotent — last call wins, so the
 * rebind is safe.
 *
 * <p>Phase B wires the body-mode refresh and the navigation-driven
 * {@code onUnauthorized} callback. The latter resets the navigation
 * stack onto the Login screen — {@code navigationRef} is established
 * by {@code App.tsx}'s {@code <NavigationContainer ref={navigationRef}>},
 * so the reset is a no-op until the container has mounted (boot-time
 * 401 cannot happen, since we don't make network calls before the
 * container is ready).
 */
function defaultBaseUrl(): string {
  const fromExtra = Constants.expoConfig?.extra?.brainUrl;
  if (typeof fromExtra === 'string' && fromExtra.length > 0) return fromExtra;
  // Final-fallback when neither persistence nor Constants give us
  // a URL — keeps the login screen mountable so the user can type
  // one in. The actual sign-in attempt fails fast against this
  // address if it isn't reachable.
  return 'http://localhost:8080';
}

function applyBaseUrl(baseUrl: string): void {
  configurePlatform({
    storage: storageNative,
    rest: {
      baseUrl,
      authMode: 'bearer',
      refreshAccess: silentRefresh,
      onUnauthorized: () => {
        // logoutLocal() may switch to a fallback account or wipe the
        // active mirror. Either way the navigation reset is correct
        // — App.tsx's <RootNavigator key={currentAccountId} /> will
        // re-mount on the resulting account flip; the explicit
        // resetToLogin handles the no-fallback case.
        void logoutLocal().finally(() => {
          resetToLogin();
        });
      },
    },
  });
}

// Synchronous initial bind — uses the app.config.ts value so anything
// that imports @vance/shared at module-load time has a working
// platform configuration. preloadStorage() resolves shortly after and
// rebinds with the active account's URL if one is set.
applyBaseUrl(defaultBaseUrl());

/**
 * Resolve once the in-memory storage caches are warm. `App.tsx`
 * awaits this before rendering any auth-dependent UI — `getStorage()`
 * reads return correct values only after the preload completes.
 *
 * <p>After the preload three things happen, in order:
 *
 * <ol>
 *   <li>{@code migrateFromFlat()} runs (idempotent) — pre-Phase-B
 *       installs that signed in once before the multi-account
 *       inventory existed get their existing identity registered as
 *       Account #0.</li>
 *   <li>If an active account is now in the inventory, its brain URL
 *       is bound via {@link configurePlatform}.</li>
 * </ol>
 */
export const bootStoragePromise = preloadStorage().then(async () => {
  await migrateFromFlat();
  const active = currentAccount();
  if (active !== null) {
    applyBaseUrl(active.brainUrl);
  }
});
