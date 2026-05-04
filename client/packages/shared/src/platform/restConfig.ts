/**
 * How the REST client authenticates a request.
 *
 * - `'cookie'`: the host (browser) attaches the `vance_access`
 *   cookie automatically on every same-origin request. The REST
 *   client sets `credentials: 'include'` on each fetch and never
 *   touches the token from JavaScript. Used by the Web UI.
 * - `'bearer'`: the REST client reads the access token from
 *   {@link PlatformStorage.secureStore} (key
 *   `StorageKeys.authAccessToken`) and sets
 *   `Authorization: Bearer <token>` on each request. Used by
 *   Mobile and any other non-browser consumer.
 */
export type AuthMode = 'cookie' | 'bearer';

/**
 * Configuration that the REST and WebSocket modules read at request
 * time. Set once at boot via {@link configurePlatform}.
 */
export interface RestConfig {
  /**
   * Brain base URL. A fully-qualified origin
   * (`'https://brain.example.com'`) — Web's `bootWeb.ts` substitutes
   * `${location.protocol}//${location.host}` for same-origin
   * production builds before calling {@link configurePlatform}.
   * Empty strings are rejected by the WebSocket module; Mobile must
   * always provide an explicit origin.
   *
   * The WebSocket module derives its `ws(s)://` URL from this value
   * by string-replacing the protocol prefix.
   */
  baseUrl: string;
  /**
   * See {@link AuthMode}.
   */
  authMode: AuthMode;
  /**
   * Called by the REST client when a request fails with 401, to
   * attempt a silent re-mint of the access credential. Returns
   * `true` when fresh credentials are now available; the REST
   * client will retry the original request once. Web binds this to
   * the cookie-refresh helper in `vance-face/src/platform/refreshWeb.ts`;
   * Mobile binds it to a body-mode refresh that reads the refresh
   * token from {@link PlatformStorage.secureStore}.
   *
   * Implementations MUST be safe against parallel calls (single-flight
   * the underlying network request); the REST client may issue
   * multiple concurrent requests that all 401 simultaneously.
   */
  refreshAccess: () => Promise<boolean>;
  /**
   * Called by the REST client when a request fails with 401 and the
   * subsequent {@link refreshAccess} also fails — i.e. the user
   * must re-authenticate. Web binds this to
   * `window.location.href = '/index.html?next=...'`; Mobile binds
   * it to a navigation callback that pushes the Login screen.
   *
   * The callback returns `void` — the REST client's pending promise
   * is intentionally left unresolved so the caller never sees an
   * error from a request that is being aborted by a navigation.
   */
  onUnauthorized: () => void;
}
