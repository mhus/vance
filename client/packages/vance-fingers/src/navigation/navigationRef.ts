import { createNavigationContainerRef } from '@react-navigation/native';
import type { RootStackParamList } from './types';

/**
 * Imperative navigation handle. Used outside of React components —
 * notably by `bootNative.ts` to wire the {@link RestConfig}'s
 * `onUnauthorized` callback to a navigation reset that drops the
 * user back into the login screen.
 *
 * Only operate on `navigationRef` after `NavigationContainer` has
 * mounted and assigned it. Boot-time calls would no-op (the ref's
 * `current` is `null`); in practice the only caller is `onUnauthorized`,
 * which fires after a runtime 401 and so always finds the container
 * already up.
 */
export const navigationRef = createNavigationContainerRef<RootStackParamList>();

/**
 * Reset the navigation stack onto the Login screen. Called from
 * the unauthorized callback and from the logout button on Home.
 */
export function resetToLogin(): void {
  if (!navigationRef.isReady()) return;
  navigationRef.reset({ index: 0, routes: [{ name: 'Login' }] });
}
