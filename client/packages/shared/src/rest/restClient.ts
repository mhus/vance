import { getTenantId } from '../auth/jwtStorage';
import { refreshAccessCookie } from '../auth/refreshClient';

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
 * Resolve the Brain's base URL.
 *
 * - In production builds the UI is served from the same origin as the Brain,
 *   so an empty string yields same-origin requests.
 * - In `vite dev` the env var `VITE_BRAIN_URL` (e.g. `http://localhost:8080`)
 *   points fetch at the locally running Brain.
 */
export function brainBaseUrl(): string {
  const fromEnv = (import.meta as ImportMeta & { env?: { VITE_BRAIN_URL?: string } }).env?.VITE_BRAIN_URL;
  return fromEnv ?? '';
}

interface RestOptions {
  /**
   * Whether the request should carry credentials. Default {@code true}
   * — fetch is called with {@code credentials: 'include'} so the
   * browser attaches the {@code vance_access} cookie automatically.
   * Only the login endpoint sets this to {@code false} (it carries
   * its credentials in the JSON body and must not echo a stale
   * cookie).
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
 * On `401` the helper attempts a single silent re-mint (using the
 * {@code vance_refresh} cookie) and retries the original request once.
 * If the retry also fails (or no refresh is possible), it redirects
 * to the login page.
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
    const refreshed = await refreshAccessCookie();
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
  const headers: Record<string, string> = { ...(options.headers ?? {}) };
  // FormData carries its own multipart boundary — let the browser set
  // Content-Type so the boundary is correct, and never JSON-stringify it.
  const isFormData = typeof FormData !== 'undefined' && options.body instanceof FormData;
  if (options.body !== undefined && !isFormData) {
    headers['Content-Type'] = 'application/json';
  }
  let body: BodyInit | undefined;
  if (options.body !== undefined) {
    body = isFormData ? (options.body as FormData) : JSON.stringify(options.body);
  }
  return fetch(url, {
    method,
    headers,
    body,
    // Cookies (vance_access) ride along on every authenticated call.
    // The login route sets {@code authenticated: false} so a stale
    // cookie can't shadow a fresh password attempt.
    credentials: options.authenticated === false ? 'omit' : 'include',
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
 * behaviour as {@link brainFetch}, but returns the raw response body as
 * a string (e.g. for markdown / HTML help content). Returns
 * {@code null} on 404 — many help-style routes treat "not present" as
 * a normal outcome rather than an error.
 */
export async function brainFetchText(path: string): Promise<string | null> {
  const tenant = getTenantId();
  if (!tenant) throw new RestError(0, path, 'No tenant configured — user is not logged in.');

  const url = `${brainBaseUrl()}/brain/${encodeURIComponent(tenant)}/${path.replace(/^\//, '')}`;
  const response = await doFetch(url, 'GET', {});

  if (response.status === 404) return null;

  if (response.status === 401) {
    const refreshed = await refreshAccessCookie();
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
  const next = encodeURIComponent(window.location.pathname + window.location.search + window.location.hash);
  window.location.href = `/index.html?next=${next}`;
}

/**
 * Build a tenant-scoped URL for a document's streaming-content
 * endpoint. Used by {@code <img src>} / PDF.js viewers / {@code <a
 * href download>} — places where we cannot inject an
 * {@code Authorization} header.
 *
 * <p>Same-origin {@code <img>} loads carry the {@code vance_access}
 * cookie automatically, so no {@code ?token=} URL hack is required
 * any more. The query parameter {@code download=1} stays purely
 * for content-disposition.
 */
export function documentContentUrl(documentId: string, download = false): string {
  const tenant = getTenantId();
  if (!tenant) return '';
  const params = new URLSearchParams();
  if (download) params.set('download', '1');
  const query = params.toString();
  return `${brainBaseUrl()}/brain/${encodeURIComponent(tenant)}/documents/${encodeURIComponent(documentId)}/content${query ? '?' + query : ''}`;
}
