import AsyncStorage from '@react-native-async-storage/async-storage';
import * as SecureStore from 'expo-secure-store';
import type { KeyValueStore, PlatformStorage } from '@vance/shared';
import { StorageKeys } from '@vance/shared';

/**
 * React Native implementation of `@vance/shared`'s
 * {@link PlatformStorage}. Two stores:
 *
 * - {@link secureStore}: backed by `expo-secure-store` (Keychain on
 *   iOS, Android Keystore). Holds access + refresh JWTs.
 * - {@link prefsStore}: backed by `@react-native-async-storage`. Holds
 *   identity hints, speech preferences, remembered-login pair.
 *
 * The {@link KeyValueStore} contract is synchronous; both backends
 * are async natively. We bridge by pre-loading the relevant key set
 * into in-memory caches at boot via {@link preloadStorage}, then
 * answer reads from cache and write through asynchronously
 * fire-and-forget. The handful of keys is small enough that the
 * boot cost is negligible (single-digit milliseconds on simulator,
 * ~30 ms cold-start on a low-end Android).
 *
 * Trade-off: a write that fails (storage quota, hardware error)
 * silently de-syncs cache from durable storage. Failure is rare in
 * practice; if it becomes an issue we add error logging in the
 * write path.
 */

const secureCache = new Map<string, string>();
const prefsCache = new Map<string, string>();

// Explicit per-store key lists — SecureStore has no "list all keys"
// API and AsyncStorage's `getAllKeys` would return entries from
// other apps' storage if the bundle id collides. Keep these in
// sync with `@vance/shared/storage/keys.ts`.
const SECURE_KEYS: readonly string[] = [
  StorageKeys.authAccessToken,
  StorageKeys.authRefreshToken,
];

const PREF_KEYS: readonly string[] = [
  StorageKeys.identityTenantId,
  StorageKeys.identityUsername,
  StorageKeys.activeSessionId,
  StorageKeys.rememberedLogin,
  StorageKeys.speechLanguage,
  StorageKeys.speechVoiceUri,
  StorageKeys.speechRate,
  StorageKeys.speechVolume,
  StorageKeys.speakerEnabled,
];

/**
 * Populate the in-memory caches from the durable backends. Resolves
 * once both stores are warm — the App component awaits this before
 * mounting any UI that reads identity or preferences.
 */
export async function preloadStorage(): Promise<void> {
  await Promise.all([
    Promise.all(
      SECURE_KEYS.map(async (key) => {
        const value = await SecureStore.getItemAsync(key);
        if (value !== null) secureCache.set(key, value);
      }),
    ),
    AsyncStorage.multiGet([...PREF_KEYS]).then((pairs) => {
      for (const [key, value] of pairs) {
        if (value !== null) prefsCache.set(key, value);
      }
    }),
  ]);
}

const secureStore: KeyValueStore = {
  get(key) {
    return secureCache.get(key) ?? null;
  },
  set(key, value) {
    secureCache.set(key, value);
    void SecureStore.setItemAsync(key, value);
  },
  remove(key) {
    secureCache.delete(key);
    void SecureStore.deleteItemAsync(key);
  },
};

const prefsStore: KeyValueStore = {
  get(key) {
    return prefsCache.get(key) ?? null;
  },
  set(key, value) {
    prefsCache.set(key, value);
    void AsyncStorage.setItem(key, value);
  },
  remove(key) {
    prefsCache.delete(key);
    void AsyncStorage.removeItem(key);
  },
};

export const storageNative: PlatformStorage = { secureStore, prefsStore };
