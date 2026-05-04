/**
 * Synchronous key-value store interface — the platform-neutral
 * abstraction over `localStorage` (web) / `AsyncStorage` + `SecureStore`
 * (mobile) / any other host-provided KV.
 *
 * Implementations are provided by the host application via
 * {@link configurePlatform}. Modules inside `@vance/shared` never
 * import a concrete backend; they read whichever instance the host
 * has bound at boot.
 *
 * Sync semantics are required because the existing call sites
 * (jwt-storage, remembered-login, speech preferences) treat reads as
 * cheap and synchronous. On platforms where the underlying store is
 * inherently async (React Native AsyncStorage), the host wrapper
 * pre-loads the relevant subset into an in-memory cache at app start
 * and the {@link KeyValueStore} reads from that cache. Persistence
 * happens fire-and-forget on writes.
 */
export interface KeyValueStore {
  get(key: string): string | null;
  set(key: string, value: string): void;
  remove(key: string): void;
}

/**
 * Two stores per platform: sensitive credential material (access /
 * refresh tokens) lives separately from UI preferences. Mobile binds
 * {@link secureStore} to Keychain / Keystore via `expo-secure-store`
 * and {@link prefsStore} to `AsyncStorage`. Web collapses both
 * onto `localStorage` — cookies are managed by the browser and are
 * not modeled here.
 *
 * Callers in `@vance/shared` pick the right store per concern; see
 * `@vance/shared/storage/keys` for the canonical key list and which
 * store each key belongs to.
 */
export interface PlatformStorage {
  secureStore: KeyValueStore;
  prefsStore: KeyValueStore;
}
