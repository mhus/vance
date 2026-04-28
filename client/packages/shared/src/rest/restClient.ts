import { clearAuth, getJwt, getTenantId } from '../auth/jwtStorage';
import { refreshToken } from '../auth/refreshClient';

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
  /** Append `Authorization: Bearer <jwt>`. Default `true` — only set to `false` for the login endpoint. */
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
 * On `401` the helper attempts a single token refresh and retries the
 * original request once. If the retry also fails (or there is no refresh
 * possible), it clears the local auth state and redirects to the login page.
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
    const refreshed = await refreshToken();
    if (refreshed) {
      const retry = await doFetch(url, method, options);
      if (retry.ok) return parseJson<T>(retry);
    }
    clearAuth();
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
  if (options.authenticated !== false) {
    const jwt = getJwt();
    if (jwt) headers['Authorization'] = `Bearer ${jwt}`;
  }
  let body: BodyInit | undefined;
  if (options.body !== undefined) {
    body = isFormData ? (options.body as FormData) : JSON.stringify(options.body);
  }
  return fetch(url, { method, headers, body });
}

async function parseJson<T>(response: Response): Promise<T> {
  if (response.status === 204) return undefined as T;
  const contentType = response.headers.get('Content-Type') ?? '';
  if (!contentType.includes('application/json')) return undefined as T;
  return (await response.json()) as T;
}

function redirectToLogin(): void {
  const next = encodeURIComponent(window.location.pathname + window.location.search + window.location.hash);
  window.location.href = `/index.html?next=${next}`;
}

/**
 * Build a tenant-scoped, JWT-authenticated URL for a document's
 * streaming-content endpoint. Used by `<img src>` / PDF.js viewers
 * / `<a href download>` — places where we can't inject an
 * `Authorization` header.
 *
 * <p>The Brain's {@code BrainAccessFilter} accepts {@code ?token=}
 * as a fallback for this specific GET-only route.
 *
 * @param documentId   Mongo id of the document
 * @param download     `true` adds {@code &download=1} so the brain
 *                     emits {@code Content-Disposition: attachment}
 */
export function documentContentUrl(documentId: string, download = false): string {
  const tenant = getTenantId();
  const jwt = getJwt();
  if (!tenant || !jwt) return '';
  const params = new URLSearchParams({ token: jwt });
  if (download) params.set('download', '1');
  return `${brainBaseUrl()}/brain/${encodeURIComponent(tenant)}/documents/${encodeURIComponent(documentId)}/content?${params}`;
}
