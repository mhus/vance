import { getCurrentEditorId, getTenantId } from '../auth/jwtStorage';
import { getRestConfig, getStorage } from '../platform/index';
import { StorageKeys } from '../storage/keys';

export class RestError extends Error {
  constructor(
    public readonly status: number,
    public readonly path: string,
    message: string,
    /**
     * Machine-readable code from a refusal body, when the endpoint sends one
     * (`{"reason": "...", "message": "..."}`).
     *
     * The message is the sentence; this is which rule was hit. Without it a
     * caller can only pattern-match on prose, so an endpoint that carefully
     * returns a stable code would have it thrown away at the door — and the
     * user gets the server's English instead of a translated line that says
     * what to do.
     */
    public readonly reason?: string,
  ) {
    super(message);
    this.name = 'RestError';
  }
}

/** The `reason` code out of an error body, if it carries one. */
function reasonCodeFrom(text: string): string | undefined {
  if (!text) return undefined;
  try {
    const body = JSON.parse(text) as { reason?: unknown };
    return typeof body.reason === 'string' && body.reason ? body.reason : undefined;
  } catch {
    return undefined;
  }
}

/**
 * The sentence out of an error body, not the envelope around it.
 *
 * The Brain answers a refusal as Spring's error JSON: a timestamp, a path,
 * a status — and the written reason under `message`. Handing the whole
 * object to a screen shows the machinery where the reason belongs, which
 * is how "already has a receipt" reached a user as a line of JSON. Anything
 * that is not that shape is passed through untouched: a proxy's HTML or a
 * plain string is still better than nothing.
 */
function reasonFrom(text: string, response: Response): string {
  if (text) {
    try {
      const body = JSON.parse(text) as { message?: unknown; error?: unknown };
      const message = typeof body.message === 'string' ? body.message.trim() : '';
      if (message && message !== 'No message available') return message;
      const error = typeof body.error === 'string' ? body.error.trim() : '';
      if (error) return error;
    } catch {
      // Not JSON. The body itself is then the most informative thing here.
    }
  }
  return text || response.statusText;
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

export interface RestOptions {
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
  /**
   * Override / suppress the auto-attached {@code X-Editor-Id} header.
   *
   * - Default ({@code undefined}): read the current value from
   *   {@link getCurrentEditorId} and attach if non-null. This covers
   *   the common case — components don't need to thread the editor id
   *   through their REST calls explicitly.
   * - String: use this value verbatim (rarely needed; useful when a
   *   pre-handshake bootstrap fires a write with a known id).
   * - {@code false}: skip the header entirely (login/refresh flows
   *   that run before any WebSocket exists).
   */
  editorId?: string | false;
  /**
   * Optional {@link RequestCache} mode forwarded to the underlying
   * `fetch`. Content viewers pass `'no-store'` because the bytes at a
   * stable content URL change after an in-place save and the browser
   * would otherwise hand back the stale prior body.
   */
  cache?: RequestCache;
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
  let response = await doFetch(url, method, options);

  if (response.status === 401 && options.authenticated !== false) {
    const refreshed = await getRestConfig().refreshAccess();
    if (refreshed) {
      // Retry once with the fresh credential. Any non-ok retry status
      // (403 genuine denial, 404, transient 500) must surface as a
      // RestError — NOT a logout. Only a failed refresh (below) or a
      // second 401 means the session is truly dead. Aligns with the
      // WithMeta / Blob / Raw helpers.
      response = await doFetch(url, method, options);
    } else {
      redirectToLogin();
      return new Promise<T>(() => {});
    }
  }

  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new RestError(response.status, path, reasonFrom(text, response), reasonCodeFrom(text));
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
  // X-Editor-Id: forwards the per-connection identity assigned by the
  // brain in its `welcome` frame. Server-side documents.changed broadcast
  // uses it to skip the writer's own WS during local fan-out — without
  // the header the writer's tab sees its own save as an external change.
  // Only set when we have an open socket; skip explicitly when caller
  // opts out (some prefetch/login flows run before the socket is up).
  if (options.editorId !== false) {
    const editorId = options.editorId ?? getCurrentEditorId();
    if (editorId) headers['X-Editor-Id'] = editorId;
  }
  let body: BodyInit | undefined;
  if (options.body !== undefined) {
    body = isFormData ? (options.body as FormData) : JSON.stringify(options.body);
  }
  return fetch(url, {
    method,
    headers,
    body,
    ...(options.cache ? { cache: options.cache } : {}),
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
 * Tenant-scoped REST request that returns the body **and** the raw
 * response so callers can read non-error response headers (e.g. the
 * {@code X-Vance-Kit-Install-Error} warning emitted by the project-create
 * endpoint when the requested kit failed to install but the project was
 * still saved). Auth + 401-refresh + error mapping match {@link brainFetch}.
 */
export async function brainFetchWithMeta<T>(
  method: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH',
  path: string,
  options: RestOptions = {},
): Promise<{ data: T; response: Response }> {
  const tenant = getTenantId();
  if (!tenant) throw new RestError(0, path, 'No tenant configured — user is not logged in.');

  const url = `${brainBaseUrl()}/brain/${encodeURIComponent(tenant)}/${path.replace(/^\//, '')}`;
  let response = await doFetch(url, method, options);

  if (response.status === 401 && options.authenticated !== false) {
    const refreshed = await getRestConfig().refreshAccess();
    if (refreshed) {
      response = await doFetch(url, method, options);
    } else {
      redirectToLogin();
      return new Promise<{ data: T; response: Response }>(() => {});
    }
  }
  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new RestError(response.status, path, reasonFrom(text, response), reasonCodeFrom(text));
  }
  const data = await parseJson<T>(response);
  return { data, response };
}

/**
 * GET a tenant-scoped resource as a binary blob. Same auth + 401-refresh
 * behaviour as {@link brainFetch}, but returns the response body as a
 * {@link Blob} together with the server-suggested filename parsed out
 * of the {@code Content-Disposition} header (or `null` if absent).
 *
 * <p>Used by file-download UIs that cannot rely on a plain
 * `<a download>` tag because the request must carry the bearer token
 * on Mobile / cross-origin Web.
 */
export async function brainFetchBlob(
  path: string,
  options: RestOptions = {},
  method = 'GET',
): Promise<{ blob: Blob; filename: string | null }> {
  const tenant = getTenantId();
  if (!tenant) throw new RestError(0, path, 'No tenant configured — user is not logged in.');

  const url = `${brainBaseUrl()}/brain/${encodeURIComponent(tenant)}/${path.replace(/^\//, '')}`;
  let response = await doFetch(url, method, options);

  if (response.status === 401) {
    const refreshed = await getRestConfig().refreshAccess();
    if (refreshed) {
      response = await doFetch(url, method, options);
    } else {
      redirectToLogin();
      return new Promise<{ blob: Blob; filename: string | null }>(() => {});
    }
  }

  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new RestError(response.status, path, reasonFrom(text, response), reasonCodeFrom(text));
  }

  const blob = await response.blob();
  const disposition = response.headers.get('Content-Disposition');
  return { blob, filename: parseContentDispositionFilename(disposition) };
}

/**
 * Extract the filename from a `Content-Disposition: attachment; filename="…"`
 * header. Returns `null` if the header is missing or malformed — callers
 * fall back to their own default name in that case.
 */
function parseContentDispositionFilename(header: string | null): string | null {
  if (!header) return null;
  // Prefer RFC 5987's filename* (UTF-8) when present, otherwise the
  // quoted plain `filename=`.
  const star = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(header);
  if (star) return decodeURIComponent(star[1]);
  const quoted = /filename\s*=\s*"([^"]+)"/i.exec(header);
  if (quoted) return quoted[1];
  const bare = /filename\s*=\s*([^;]+)/i.exec(header);
  return bare ? bare[1].trim() : null;
}

/**
 * GET a tenant-scoped resource as plain text. Same auth + 401-refresh
 * behaviour as {@link brainFetch}, but returns the raw response body
 * as a string (e.g. for markdown / HTML help content). Returns
 * `null` on 404 — many help-style routes treat "not present" as a
 * normal outcome rather than an error.
 */
export async function brainFetchText(
  path: string,
  options: RestOptions = {},
): Promise<string | null> {
  return (await brainFetchTextWithMeta(path, options)).text;
}

/**
 * {@link brainFetchText} plus the raw {@link Response}.
 *
 * <p>Exists for conditional requests: the caller needs the `ETag` header to
 * send back as `If-Match` on the next write, and a text helper that only
 * returns the body cannot hand it over. Computing the validator on the client
 * instead — from a DTO field — would put the version rule in two places, which
 * is the one thing the server side of this deliberately avoids.
 */
export async function brainFetchTextWithMeta(
  path: string,
  options: RestOptions = {},
): Promise<{ text: string | null; response: Response }> {
  const tenant = getTenantId();
  if (!tenant) throw new RestError(0, path, 'No tenant configured — user is not logged in.');

  const url = `${brainBaseUrl()}/brain/${encodeURIComponent(tenant)}/${path.replace(/^\//, '')}`;
  let response = await doFetch(url, 'GET', options);

  if (response.status === 404) return { text: null, response };

  if (response.status === 401) {
    const refreshed = await getRestConfig().refreshAccess();
    if (refreshed) {
      // Retry once; a non-ok retry (other than 404) surfaces as a
      // RestError below, not a logout. Only a failed refresh means the
      // session is dead. Aligns with the WithMeta / Blob / Raw helpers.
      response = await doFetch(url, 'GET', options);
    } else {
      redirectToLogin();
      return new Promise<{ text: string | null; response: Response }>(() => {});
    }
  }

  if (response.status === 404) return { text: null, response };
  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new RestError(response.status, path, reasonFrom(text, response), reasonCodeFrom(text));
  }
  return { text: await response.text(), response };
}

function redirectToLogin(): void {
  getRestConfig().onUnauthorized();
}

/**
 * Tenant-scoped PUT/POST that streams a raw body (string or binary) instead
 * of JSON. Used by the document-content endpoint where the server reads the
 * body as an {@link InputStream} — JSON-stringifying would double-encode.
 * The {@code Content-Type} header is set verbatim from {@code contentType};
 * pass the document's mime so the server can re-classify on save.
 *
 * <p>Auth + 401-refresh behaviour identical to {@link brainFetch}. Parses
 * the response as JSON when present (the document endpoint returns the
 * updated {@code DocumentDto}); falls back to {@code undefined} on
 * empty / non-JSON bodies.
 */
export async function brainSendRaw<T>(
  method: 'PUT' | 'POST',
  path: string,
  body: string | Blob | ArrayBuffer | Uint8Array,
  contentType: string,
  headers: Record<string, string> = {},
): Promise<T> {
  return (await brainSendRawWithMeta<T>(method, path, body, contentType, headers)).data;
}

/**
 * {@link brainSendRaw} plus the raw {@link Response}, and with room for extra
 * request headers.
 *
 * <p>Both exist for the same reason: a conditional write sends `If-Match` and
 * needs the new `ETag` back, so that a caller doing read-modify-write in a
 * loop does not have to re-read the document after every save purely to learn
 * what to send next time.
 */
export async function brainSendRawWithMeta<T>(
  method: 'PUT' | 'POST',
  path: string,
  body: string | Blob | ArrayBuffer | Uint8Array,
  contentType: string,
  extraHeaders: Record<string, string> = {},
): Promise<{ data: T; response: Response }> {
  const tenant = getTenantId();
  if (!tenant) throw new RestError(0, path, 'No tenant configured — user is not logged in.');

  const url = `${brainBaseUrl()}/brain/${encodeURIComponent(tenant)}/${path.replace(/^\//, '')}`;
  const send = async (): Promise<Response> => {
    const config = getRestConfig();
    const headers: Record<string, string> = {
      'Content-Type': contentType,
      ...extraHeaders,
    };
    if (config.authMode === 'bearer') {
      const token = getStorage().secureStore.get(StorageKeys.authAccessToken);
      if (token !== null) headers['Authorization'] = `Bearer ${token}`;
    }
    // Same X-Editor-Id contract as brainFetch — see doFetch above.
    // brainSendRaw is the document-content save path in particular, so
    // missing the header here is exactly the case the live-broadcast
    // writer-skip needs to defend against.
    const editorId = getCurrentEditorId();
    if (editorId) headers['X-Editor-Id'] = editorId;
    return fetch(url, {
      method,
      headers,
      body: body as BodyInit,
      credentials: config.authMode === 'cookie' ? 'include' : 'omit',
    });
  };

  let response = await send();
  if (response.status === 401) {
    const refreshed = await getRestConfig().refreshAccess();
    if (refreshed) {
      response = await send();
    } else {
      redirectToLogin();
      return new Promise<{ data: T; response: Response }>(() => {});
    }
  }
  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new RestError(response.status, path, reasonFrom(text, response), reasonCodeFrom(text));
  }
  return { data: await parseJson<T>(response), response };
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
