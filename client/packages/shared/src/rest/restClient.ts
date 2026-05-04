import { getTenantId } from '../auth/jwtStorage';
import { getRestConfig, getStorage } from '../platform/index';
import { StorageKeys } from '../storage/keys';

export class RestError extends Error {
  constructor(
    public readonly status: number,
    public readonly path: string,
    message: string,
  ) {
    super(message);
    this.name = 'RestError';
  }
}

/**
 * Resolve the Brain's base URL from the host-bound configuration.
 * The host calls {@link configurePlatform} once at boot with the
 * appropriate value (`''` for same-origin Web, an explicit origin
 * for Mobile or cross-origin dev). This module never inspects the
 * environment directly.
 */
export function brainBaseUrl(): string {
  return getRestConfig().baseUrl;
}

interface RestOptions {
  /**
   * Whether the request should carry credentials. Default `true`.
   *
   * - In `'cookie'` auth mode the underlying fetch is called with
   *   `credentials: 'include'` so the browser attaches the
   *   `vance_access` cookie automatically.
   * - In `'bearer'` mode the REST client reads the access token from
   *   {@link PlatformStorage.secureStore} and sets the
   *   `Authorization` header on each request.
   *
   * The login endpoint sets this to `false` (it carries its
   * credentials in the JSON body and must not echo a stale cookie /
   * bearer token).
   */
  authenticated?: boolean;
  /** Optional JSON body. */
  body?: unknown;
  /** Extra headers to merge in. */
  headers?: Record<string, string>;
}

/**
 * Tenant-scoped REST request. The `path` is appended to
 * `${baseUrl}/brain/{tenant}/`, so callers pass relative paths like
 * `'sessions'` or `'documents/abc'`.
 *
 * On `401` the helper attempts a single silent re-mint and retries
 * the original request once. If the retry also fails (or no refresh
 * is possible), it triggers the host's `onUnauthorized` callback.
 */
export async function brainFetch<T>(
  method: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH',
  path: string,
  options: RestOptions = {},
): Promise<T> {
  const tenant = getTenantId();
  if (!tenant) throw new RestError(0, path, 'No tenant configured — user is not logged in.');

  const url = `${brainBaseUrl()}/brain/${encodeURIComponent(tenant)}/${path.replace(/^\//, '')}`;
  const response = await doFetch(url, method, options);

  if (response.status === 401 && options.authenticated !== false) {
    const refreshed = await getRestConfig().refreshAccess();
    if (refreshed) {
      const retry = await doFetch(url, method, options);
      if (retry.ok) return parseJson<T>(retry);
    }
    redirectToLogin();
    return new Promise<T>(() => {});
  }

  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new RestError(response.status, path, text || response.statusText);
  }
  return parseJson<T>(response);
}

async function doFetch(url: string, method: string, options: RestOptions): Promise<Response> {
  const config = getRestConfig();
  const headers: Record<string, string> = { ...(options.headers ?? {}) };
  // FormData carries its own multipart boundary — let the host set
  // Content-Type so the boundary is correct, and never JSON-stringify it.
  const isFormData = typeof FormData !== 'undefined' && options.body instanceof FormData;
  if (options.body !== undefined && !isFormData) {
    headers['Content-Type'] = 'application/json';
  }
  if (config.authMode === 'bearer' && options.authenticated !== false) {
    const token = getStorage().secureStore.get(StorageKeys.authAccessToken);
    if (token !== null) headers['Authorization'] = `Bearer ${token}`;
  }
  let body: BodyInit | undefined;
  if (options.body !== undefined) {
    body = isFormData ? (options.body as FormData) : JSON.stringify(options.body);
  }
  return fetch(url, {
    method,
    headers,
    body,
    credentials:
      config.authMode === 'cookie' && options.authenticated !== false ? 'include' : 'omit',
  });
}

async function parseJson<T>(response: Response): Promise<T> {
  if (response.status === 204) return undefined as T;
  const contentType = response.headers.get('Content-Type') ?? '';
  if (!contentType.includes('application/json')) return undefined as T;
  return (await response.json()) as T;
}

/**
 * GET a tenant-scoped resource as plain text. Same auth + 401-refresh
 * behaviour as {@link brainFetch}, but returns the raw response body
 * as a string (e.g. for markdown / HTML help content). Returns
 * `null` on 404 — many help-style routes treat "not present" as a
 * normal outcome rather than an error.
 */
export async function brainFetchText(path: string): Promise<string | null> {
  const tenant = getTenantId();
  if (!tenant) throw new RestError(0, path, 'No tenant configured — user is not logged in.');

  const url = `${brainBaseUrl()}/brain/${encodeURIComponent(tenant)}/${path.replace(/^\//, '')}`;
  const response = await doFetch(url, 'GET', {});

  if (response.status === 404) return null;

  if (response.status === 401) {
    const refreshed = await getRestConfig().refreshAccess();
    if (refreshed) {
      const retry = await doFetch(url, 'GET', {});
      if (retry.status === 404) return null;
      if (retry.ok) return retry.text();
    }
    redirectToLogin();
    return new Promise<string | null>(() => {});
  }

  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new RestError(response.status, path, text || response.statusText);
  }
  return response.text();
}

function redirectToLogin(): void {
  getRestConfig().onUnauthorized();
}

/**
 * Build a tenant-scoped URL for a document's streaming-content
 * endpoint. Used by `<img src>` / PDF.js viewers / `<a href download>`
 * — places where we cannot inject an `Authorization` header.
 *
 * On Web (cookie auth) the same-origin `<img>` load carries the
 * `vance_access` cookie automatically. On Mobile (bearer auth) the
 * caller must replace this with an authorised fetch + blob — `<img>`
 * cannot send custom headers.
 */
export function documentContentUrl(documentId: string, download = false): string {
  const tenant = getTenantId();
  if (!tenant) return '';
  const params = new URLSearchParams();
  if (download) params.set('download', '1');
  const query = params.toString();
  return `${brainBaseUrl()}/brain/${encodeURIComponent(tenant)}/documents/${encodeURIComponent(documentId)}/content${query ? '?' + query : ''}`;
}
