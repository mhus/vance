import { a as SessionStatus, b as SessionSearchScope } from './SettingType-UjWoPh8Q.js';

// Lightweight client-side view of a Vance JWT. Mirrors the fields exposed by
// `de.mhus.vance.shared.jwt.VanceJwtClaims` on the server.
//
// We never *trust* what we decode here — the server re-verifies on every
// request. This decoder is only for UI decisions: showing the username, or
// triggering a refresh before the token expires.
/**
 * Decode the payload segment of a JWT without verifying its signature.
 * Returns `null` if the token is malformed or missing required claims.
 */
function decodeJwt(token) {
    const parts = token.split('.');
    if (parts.length !== 3)
        return null;
    let payloadJson;
    try {
        const padded = parts[1].padEnd(parts[1].length + ((4 - (parts[1].length % 4)) % 4), '=');
        const base64 = padded.replace(/-/g, '+').replace(/_/g, '/');
        payloadJson = JSON.parse(atob(base64));
    }
    catch {
        return null;
    }
    if (!isObject(payloadJson))
        return null;
    const sub = payloadJson['sub'];
    const tid = payloadJson['tid'];
    const exp = payloadJson['exp'];
    if (typeof sub !== 'string' || typeof tid !== 'string' || typeof exp !== 'number') {
        return null;
    }
    const iat = typeof payloadJson['iat'] === 'number' ? payloadJson['iat'] * 1000 : null;
    return {
        username: sub,
        tenantId: tid,
        expiresAtMs: exp * 1000,
        issuedAtMs: iat,
    };
}
/**
 * Whether the given token is still valid right now, with an optional safety
 * margin. Default margin: 30 seconds — i.e. tokens that expire in under
 * 30s are considered already expired so callers refresh proactively.
 */
function isTokenValid(token, marginMs = 30_000) {
    const claims = decodeJwt(token);
    if (!claims)
        return false;
    return claims.expiresAtMs - marginMs > Date.now();
}
function isObject(v) {
    return typeof v === 'object' && v !== null;
}

let bindings = null;
/**
 * Bind the host platform's KV stores and REST configuration once at
 * boot. Must be called before any `@vance/shared` module that touches
 * storage or makes a network request.
 *
 * Safe to call again with a different configuration (e.g. after
 * logout the host may rebind `onUnauthorized` to point at a fresh
 * navigation context); the most recent configuration wins. There is
 * no listener mechanism — callers that cached references should not,
 * and instead re-read via {@link getStorage} / {@link getRestConfig}
 * on each access.
 */
function configurePlatform(opts) {
    bindings = opts;
}
function require_() {
    if (bindings === null) {
        throw new Error('@vance/shared: platform not configured — call configurePlatform({ storage, rest }) at app startup.');
    }
    return bindings;
}
/**
 * Resolve the host-provided storage bindings. Throws if
 * {@link configurePlatform} has not been called yet — there is no
 * sensible default, since the choice between `localStorage` and
 * `AsyncStorage` (and their secure counterparts) is the host's
 * responsibility.
 */
function getStorage() {
    return require_().storage;
}
/**
 * Resolve the host-provided REST configuration. Throws if
 * {@link configurePlatform} has not been called yet.
 */
function getRestConfig() {
    return require_().rest;
}
/**
 * Test-only: forget the current bindings. Production code does not
 * call this — there is no use case for transitioning back to
 * "unconfigured" once an app has booted. Test suites use it between
 * cases to start each test with a fresh in-memory KV.
 */
function __resetPlatform() {
    bindings = null;
}

/**
 * Canonical key strings for the platform's {@link KeyValueStore}
 * bindings. All keys are prefixed `vance.` to avoid collisions with
 * other apps that share the storage namespace (Web `localStorage`,
 * Mobile `AsyncStorage`).
 *
 * Each key documents which store it belongs to:
 * - `secureStore`: tokens and other sensitive material
 * - `prefsStore`: UI preferences, identity hints, draft state
 *
 * Web collapses both stores onto `localStorage`, so the distinction
 * is non-load-bearing there; Mobile honours it (Keychain vs.
 * AsyncStorage).
 *
 * This module replaces the legacy `persistence/keys.ts`, which is
 * kept for backwards compatibility until Phase 4 of the
 * platform-neutrality refactor (see
 * `readme/reorg-webui-to-clean-shared.md`).
 */
const StorageKeys = {
    // ── secureStore ─────────────────────────────────────────────────
    /** Access JWT — Bearer-mode REST/WS authentication. Mobile only.
     *  Web cookie-mode never sees the token. */
    authAccessToken: 'vance.auth.accessToken',
    /** Refresh JWT — exchanged at `POST /brain/{tenant}/refresh` for a
     *  fresh access token. Mobile only. */
    authRefreshToken: 'vance.auth.refreshToken',
    // ── prefsStore ──────────────────────────────────────────────────
    /** Tenant the user belongs to. Set after successful login on both
     *  Web (mirrored from the `vance_data` cookie) and Mobile (read
     *  from the login response body). */
    identityTenantId: 'vance.identity.tenantId',
    /** Username of the currently signed-in user. Mirror semantics like
     *  {@link identityTenantId}. */
    identityUsername: 'vance.identity.username',
    /** Brain HTTP base URL (e.g. {@code http://10.0.0.5:8080}) the
     *  user pointed the app at on the last successful login. Mobile
     *  only — Web pulls the brain origin from {@code window.location}.
     *  Pre-fills the login screen on subsequent launches and serves as
     *  the boot-time {@code configurePlatform} input ahead of the
     *  {@code app.config.ts} default. */
    identityBrainUrl: 'vance.identity.brainUrl',
    /** Multi-account inventory — JSON-encoded {@code Account[]} listing
     *  every {@code (brainUrl, tenantId, username)} the user has signed
     *  in with on this device. Mobile only — Web is single-account per
     *  browser tab. */
    accountsList: 'vance.accounts.list',
    /** Active-account pointer — id of the entry in
     *  {@link accountsList} whose tokens currently mirror into the flat
     *  identity / auth keys. Empty / missing means no active account
     *  (post-logout, pre-first-login). */
    accountsCurrent: 'vance.accounts.current',
    /** Currently active session id. Set when the user enters a Chat
     *  session, cleared on logout. */
    activeSessionId: 'vance.activeSessionId',
    /** Tenant + username pair pre-filled on the login form when the
     *  user ticked "Remember user" on a previous successful sign-in.
     *  Stored as JSON `{tenant, username}`. Never carries credentials. */
    rememberedLogin: 'vance.rememberedLogin',
    /** BCP-47 code for speech features (STT + TTS). Sentinel `'auto'`
     *  means fall back to the platform locale. */
    speechLanguage: 'vance.speechLanguage',
    /** Voice URI (Web: `SpeechSynthesisVoice.voiceURI`; Mobile:
     *  `expo-speech` voice identifier) of the user's preferred TTS
     *  voice. Empty / missing means pick a sensible default. */
    speechVoiceUri: 'vance.speechVoiceUri',
    /** TTS playback rate (0.5–2.0). String-stored decimal. */
    speechRate: 'vance.speechRate',
    /** TTS volume (0.0–1.0). Web only — Mobile uses system volume.
     *  String-stored decimal. */
    speechVolume: 'vance.speechVolume',
    /** Chat speaker on/off — `'1'` enabled, anything else disabled. */
    speakerEnabled: 'vance.speakerEnabled',
};

// Identity helpers read from the platform-bound prefsStore. The host
// is responsible for keeping these keys in sync with the authoritative
// source: Web copies them from the `vance_data` cookie at boot,
// Mobile writes them after a successful body-mode login.
//
// JavaScript never sees the access JWT on Web (HttpOnly cookie); on
// Mobile the bearer token lives in the platform's `secureStore`
// (a different store under the same {@link PlatformStorage} binding).
function getTenantId() {
    return getStorage().prefsStore.get(StorageKeys.identityTenantId);
}
function getUsername() {
    return getStorage().prefsStore.get(StorageKeys.identityUsername);
}
function getActiveSessionId() {
    return getStorage().prefsStore.get(StorageKeys.activeSessionId);
}
function setActiveSessionId(sessionId) {
    const prefs = getStorage().prefsStore;
    if (sessionId === null) {
        prefs.remove(StorageKeys.activeSessionId);
    }
    else {
        prefs.set(StorageKeys.activeSessionId, sessionId);
    }
}
/**
 * Migrate from a previous-era localStorage install. Removes the
 * legacy `vance.jwt`, `vance.tenantId`, `vance.username` entries that
 * the Web UI used before the cookie-based auth landed. Idempotent —
 * safe to call on every boot. Web binds the prefsStore to
 * localStorage, so this still has the same effect as before; on
 * Mobile it is a no-op (those keys never existed in AsyncStorage).
 */
function clearLegacyAuth() {
    const prefs = getStorage().prefsStore;
    prefs.remove('vance.jwt');
    prefs.remove('vance.tenantId');
    prefs.remove('vance.username');
}
/**
 * @deprecated use `logout(tenant)` from `loginClient` instead, which
 * fires a server-side cookie clear. This stub exists only so old
 * call sites don't break the build until they migrate.
 */
function clearAuth() {
    clearLegacyAuth();
}

function getRememberedLogin() {
    const raw = getStorage().prefsStore.get(StorageKeys.rememberedLogin);
    if (!raw)
        return null;
    try {
        const parsed = JSON.parse(raw);
        if (typeof parsed?.tenant !== 'string' || typeof parsed?.username !== 'string') {
            return null;
        }
        return { tenant: parsed.tenant, username: parsed.username };
    }
    catch {
        return null;
    }
}
function setRememberedLogin(value) {
    getStorage().prefsStore.set(StorageKeys.rememberedLogin, JSON.stringify(value));
}
function clearRememberedLogin() {
    getStorage().prefsStore.remove(StorageKeys.rememberedLogin);
}

class RestError extends Error {
    status;
    path;
    constructor(status, path, message) {
        super(message);
        this.status = status;
        this.path = path;
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
function brainBaseUrl() {
    return getRestConfig().baseUrl;
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
async function brainFetch(method, path, options = {}) {
    const tenant = getTenantId();
    if (!tenant)
        throw new RestError(0, path, 'No tenant configured — user is not logged in.');
    const url = `${brainBaseUrl()}/brain/${encodeURIComponent(tenant)}/${path.replace(/^\//, '')}`;
    const response = await doFetch(url, method, options);
    if (response.status === 401 && options.authenticated !== false) {
        const refreshed = await getRestConfig().refreshAccess();
        if (refreshed) {
            const retry = await doFetch(url, method, options);
            if (retry.ok)
                return parseJson(retry);
        }
        redirectToLogin();
        return new Promise(() => { });
    }
    if (!response.ok) {
        const text = await response.text().catch(() => '');
        throw new RestError(response.status, path, text || response.statusText);
    }
    return parseJson(response);
}
async function doFetch(url, method, options) {
    const config = getRestConfig();
    const headers = { ...(options.headers ?? {}) };
    // FormData carries its own multipart boundary — let the host set
    // Content-Type so the boundary is correct, and never JSON-stringify it.
    const isFormData = typeof FormData !== 'undefined' && options.body instanceof FormData;
    if (options.body !== undefined && !isFormData) {
        headers['Content-Type'] = 'application/json';
    }
    if (config.authMode === 'bearer' && options.authenticated !== false) {
        const token = getStorage().secureStore.get(StorageKeys.authAccessToken);
        if (token !== null)
            headers['Authorization'] = `Bearer ${token}`;
    }
    let body;
    if (options.body !== undefined) {
        body = isFormData ? options.body : JSON.stringify(options.body);
    }
    return fetch(url, {
        method,
        headers,
        body,
        credentials: config.authMode === 'cookie' && options.authenticated !== false ? 'include' : 'omit',
    });
}
async function parseJson(response) {
    if (response.status === 204)
        return undefined;
    const contentType = response.headers.get('Content-Type') ?? '';
    if (!contentType.includes('application/json'))
        return undefined;
    return (await response.json());
}
/**
 * Tenant-scoped REST request that returns the body **and** the raw
 * response so callers can read non-error response headers (e.g. the
 * {@code X-Vance-Kit-Install-Error} warning emitted by the project-create
 * endpoint when the requested kit failed to install but the project was
 * still saved). Auth + 401-refresh + error mapping match {@link brainFetch}.
 */
async function brainFetchWithMeta(method, path, options = {}) {
    const tenant = getTenantId();
    if (!tenant)
        throw new RestError(0, path, 'No tenant configured — user is not logged in.');
    const url = `${brainBaseUrl()}/brain/${encodeURIComponent(tenant)}/${path.replace(/^\//, '')}`;
    let response = await doFetch(url, method, options);
    if (response.status === 401 && options.authenticated !== false) {
        const refreshed = await getRestConfig().refreshAccess();
        if (refreshed) {
            response = await doFetch(url, method, options);
        }
        else {
            redirectToLogin();
            return new Promise(() => { });
        }
    }
    if (!response.ok) {
        const text = await response.text().catch(() => '');
        throw new RestError(response.status, path, text || response.statusText);
    }
    const data = await parseJson(response);
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
async function brainFetchBlob(path) {
    const tenant = getTenantId();
    if (!tenant)
        throw new RestError(0, path, 'No tenant configured — user is not logged in.');
    const url = `${brainBaseUrl()}/brain/${encodeURIComponent(tenant)}/${path.replace(/^\//, '')}`;
    let response = await doFetch(url, 'GET', {});
    if (response.status === 401) {
        const refreshed = await getRestConfig().refreshAccess();
        if (refreshed) {
            response = await doFetch(url, 'GET', {});
        }
        else {
            redirectToLogin();
            return new Promise(() => { });
        }
    }
    if (!response.ok) {
        const text = await response.text().catch(() => '');
        throw new RestError(response.status, path, text || response.statusText);
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
function parseContentDispositionFilename(header) {
    if (!header)
        return null;
    // Prefer RFC 5987's filename* (UTF-8) when present, otherwise the
    // quoted plain `filename=`.
    const star = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(header);
    if (star)
        return decodeURIComponent(star[1]);
    const quoted = /filename\s*=\s*"([^"]+)"/i.exec(header);
    if (quoted)
        return quoted[1];
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
async function brainFetchText(path) {
    const tenant = getTenantId();
    if (!tenant)
        throw new RestError(0, path, 'No tenant configured — user is not logged in.');
    const url = `${brainBaseUrl()}/brain/${encodeURIComponent(tenant)}/${path.replace(/^\//, '')}`;
    const response = await doFetch(url, 'GET', {});
    if (response.status === 404)
        return null;
    if (response.status === 401) {
        const refreshed = await getRestConfig().refreshAccess();
        if (refreshed) {
            const retry = await doFetch(url, 'GET', {});
            if (retry.status === 404)
                return null;
            if (retry.ok)
                return retry.text();
        }
        redirectToLogin();
        return new Promise(() => { });
    }
    if (!response.ok) {
        const text = await response.text().catch(() => '');
        throw new RestError(response.status, path, text || response.statusText);
    }
    return response.text();
}
function redirectToLogin() {
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
function documentContentUrl(documentId, download = false) {
    const tenant = getTenantId();
    if (!tenant)
        return '';
    const params = new URLSearchParams();
    if (download)
        params.set('download', '1');
    const query = params.toString();
    return `${brainBaseUrl()}/brain/${encodeURIComponent(tenant)}/documents/${encodeURIComponent(documentId)}/content${query ? '?' + query : ''}`;
}

function qs$1(params) {
    const u = new URLSearchParams();
    for (const [k, v] of Object.entries(params))
        u.set(k, v);
    return u.toString();
}
async function getCalendarPlanner(projectId, folder) {
    return brainFetch('GET', `calendar/planner?${qs$1({ projectId, folder })}`);
}
async function createCalendarEvent(projectId, folder, request) {
    return brainFetch('POST', `calendar/events?${qs$1({ projectId, folder })}`, { body: request });
}
async function updateCalendarEvent(projectId, folder, id, request) {
    return brainFetch('PATCH', `calendar/events?${qs$1({ projectId, folder, id })}`, { body: request });
}
async function deleteCalendarEvent(projectId, folder, id) {
    return brainFetch('DELETE', `calendar/events?${qs$1({ projectId, folder, id })}`);
}
async function rebuildCalendarPlanner(projectId, folder) {
    return brainFetch('POST', `calendar/rebuild?${qs$1({ projectId, folder })}`);
}

function qs(params) {
    const u = new URLSearchParams();
    for (const [k, v] of Object.entries(params))
        u.set(k, v);
    return u.toString();
}
async function getKanbanBoard(projectId, folder) {
    return brainFetch('GET', `kanban/board?${qs({ projectId, folder })}`);
}
async function moveKanbanCard(projectId, folder, request) {
    return brainFetch('POST', `kanban/move?${qs({ projectId, folder })}`, { body: request });
}
async function createKanbanCard(projectId, folder, request) {
    return brainFetch('POST', `kanban/cards?${qs({ projectId, folder })}`, { body: request });
}
async function updateKanbanCard(projectId, folder, path, request) {
    return brainFetch('PATCH', `kanban/cards?${qs({ projectId, folder, path })}`, { body: request });
}
async function deleteKanbanCard(projectId, folder, path) {
    return brainFetch('DELETE', `kanban/cards?${qs({ projectId, folder, path })}`);
}
async function rebuildKanbanBoard(projectId, folder) {
    return brainFetch('POST', `kanban/rebuild?${qs({ projectId, folder })}`);
}

/**
 * In-memory LRU cache for link previews. Sized to ~50 entries which
 * covers a typical chat session without ballooning memory — older
 * entries get evicted on insert. Tab-scoped: when the user reloads,
 * the server-side Mongo cache absorbs the cold start.
 */
const CACHE_LIMIT = 50;
const cache = new Map();
/**
 * In-flight de-duplication: when the same URL is requested twice
 * before the first response lands (e.g. the same link appears in
 * two messages currently on screen), share the promise so we only
 * hit the server once.
 */
const inflight = new Map();
/**
 * GET /brain/{tenant}/link-preview?url={url} — fetches OpenGraph
 * metadata for an external link through the Brain CORS proxy.
 *
 * Always resolves with a populated DTO; the caller checks `ok` to
 * decide between rendering a full card and a muted "preview not
 * available" badge. Network errors fall through as `RestError` —
 * the caller may swallow them silently since previews are best-effort.
 */
async function fetchLinkPreview(url) {
    const cached = cache.get(url);
    if (cached) {
        // LRU touch: re-insert moves the entry to the end (Map preserves
        // insertion order, so this is the cheapest LRU implementation).
        cache.delete(url);
        cache.set(url, cached);
        return cached;
    }
    const existing = inflight.get(url);
    if (existing)
        return existing;
    const promise = brainFetch('GET', `link-preview?url=${encodeURIComponent(url)}`)
        .then((dto) => {
        rememberPreview(url, dto);
        return dto;
    })
        .finally(() => {
        inflight.delete(url);
    });
    inflight.set(url, promise);
    return promise;
}
function rememberPreview(url, dto) {
    if (cache.size >= CACHE_LIMIT) {
        // Map iteration order = insertion order; drop the oldest entry.
        const first = cache.keys().next();
        if (!first.done)
            cache.delete(first.value);
    }
    cache.set(url, dto);
}
/** Visible-for-testing: clears the in-memory cache. */
function _clearLinkPreviewCache() {
    cache.clear();
    inflight.clear();
}

/**
 * GET /brain/{tenant}/sessions — owner-scoped list of the current user's
 * sessions. By default excludes ARCHIVED and CLOSED; flip {@code includeArchived}
 * or pass an explicit {@code status} set to override.
 */
async function listSessions(options = {}) {
    const params = new URLSearchParams();
    if (options.projectId)
        params.set('projectId', options.projectId);
    if (options.status && options.status.length > 0) {
        // Generated TS enums are numeric (Java enum order). Brain expects
        // the enum name on the wire, same as Jackson.
        params.set('status', options.status.map((s) => SessionStatus[s]).join(','));
    }
    if (options.includeArchived)
        params.set('includeArchived', 'true');
    if (options.tag)
        params.set('tag', options.tag);
    const qs = params.toString();
    return brainFetch('GET', `sessions${qs ? `?${qs}` : ''}`);
}
/**
 * GET /brain/{tenant}/sessions/search — owner-scoped search. Default
 * scope BOTH (metadata + chat content); default includeArchived=true so
 * the dialog can find archived sessions.
 */
async function searchSessions(options) {
    const params = new URLSearchParams();
    params.set('q', options.q);
    if (options.scope !== undefined) {
        params.set('scope', SessionSearchScope[options.scope]);
    }
    if (options.includeArchived !== undefined) {
        params.set('includeArchived', String(options.includeArchived));
    }
    if (options.limit)
        params.set('limit', String(options.limit));
    return brainFetch('GET', `sessions/search?${params.toString()}`);
}
/**
 * PATCH /brain/{tenant}/sessions/{id}/metadata — partial update of
 * title/icon/color/tags/pinned. Returns the post-patch metadata.
 */
async function patchSessionMetadata(sessionId, patch) {
    return brainFetch('PATCH', `sessions/${encodeURIComponent(sessionId)}/metadata`, { body: patch });
}
/** POST /brain/{tenant}/sessions/{id}/archive — idempotent. */
async function archiveSession(sessionId) {
    await brainFetch('POST', `sessions/${encodeURIComponent(sessionId)}/archive`);
}
/** POST /brain/{tenant}/sessions/{id}/reactivate — only valid on ARCHIVED. */
async function reactivateSession(sessionId) {
    await brainFetch('POST', `sessions/${encodeURIComponent(sessionId)}/reactivate`);
}
/** DELETE /brain/{tenant}/sessions/{id} — hard delete, no undo. */
async function deleteSession(sessionId) {
    await brainFetch('DELETE', `sessions/${encodeURIComponent(sessionId)}`);
}
/**
 * GET /brain/{tenant}/sessions/{id}/messages — chat history. Used by the
 * search-result preview and the chat editor; works on ARCHIVED sessions
 * too (their chat history persists across archive).
 */
async function getSessionMessages(sessionId, limit) {
    const qs = limit ? `?limit=${limit}` : '';
    return brainFetch('GET', `sessions/${encodeURIComponent(sessionId)}/messages${qs}`);
}

/**
 * Setting Forms REST client — wraps the
 * {@code /brain/{tenant}/setting-forms} endpoint surface (see
 * {@code specification/setting-forms.md §8}).
 *
 * <p>All endpoints accept an optional {@code projectId}: when omitted
 * the brain treats the request as tenant-scoped (system-project
 * cascade only).
 *
 * <p>The {@code FormValue} type is shared with the wizard module —
 * the same {@code Record<string, FormValue>} shape works for both
 * form subsystems because they share the underlying
 * {@code FormFieldDto} contract.
 */
/**
 * GET /brain/{tenant}/setting-forms — listing of forms available in
 * the current cascade after the form's {@code availableIn} glob
 * filter has been applied against {@code projectId}.
 */
async function listSettingForms(projectId) {
    const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
    return brainFetch('GET', `setting-forms${qs}`);
}
/**
 * GET /brain/{tenant}/setting-forms/{name} — full form definition
 * with live cascade values populated per direct-mapped field. The
 * response strips backend-only fields (Pebble templates for
 * {@code showIf}/{@code writeIf}/computed {@code value}); the UI
 * only sees the schema + the current effective values.
 */
async function getSettingForm(name, projectId) {
    const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
    return brainFetch('GET', `setting-forms/${encodeURIComponent(name)}${qs}`);
}
/**
 * POST /brain/{tenant}/setting-forms/{name}/apply — validate + render +
 * persist the form values. The response's {@code applied} list tells
 * the UI exactly which keys were written/deleted/skipped.
 *
 * <p>Password-typed fields submitted with an empty string mean
 * "do not modify" — the brain returns a SKIP entry for those.
 */
async function applySettingForm(name, values, projectId, lang) {
    const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
    return brainFetch('POST', `setting-forms/${encodeURIComponent(name)}/apply${qs}`, { body: { values, lang } });
}
/**
 * POST /brain/{tenant}/setting-forms/{name}/validate — dry-run of the
 * apply pipeline. Returns the same {@code applied} list that
 * {@link applySettingForm} would have produced, but without
 * persisting. Used by the "Preview" button in the UI.
 */
async function validateSettingForm(name, values, projectId, lang) {
    const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
    return brainFetch('POST', `setting-forms/${encodeURIComponent(name)}/validate${qs}`, { body: { values, lang } });
}
/**
 * POST /brain/{tenant}/setting-forms/{name}/reset — delete every key
 * the form references on its respective scope. Falls back to the
 * next outer cascade layer. Rejected with HTTP 400 when the form
 * declares {@code clearable: false}.
 */
async function resetSettingForm(name, projectId) {
    const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
    return brainFetch('POST', `setting-forms/${encodeURIComponent(name)}/reset${qs}`);
}

/**
 * GET /brain/{tenant}/wizards — listing of wizards available in the
 * current cascade (project → user → tenant → bundled).
 */
async function listWizards(projectId) {
    const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
    return brainFetch('GET', `wizards${qs}`);
}
/**
 * GET /brain/{tenant}/wizards/{name} — full wizard with field schema
 * for form rendering. Throws RestError on 404 when the wizard doesn't
 * exist in any cascade layer.
 */
async function getWizard(name, projectId) {
    const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
    return brainFetch('GET', `wizards/${encodeURIComponent(name)}${qs}`);
}
/**
 * POST /brain/{tenant}/wizards/{name}/render — submit form values,
 * receive the Pebble-rendered prompt ready for the chat input.
 *
 * `lang` overrides the resolver's tenant default — used by the Web-UI
 * to render with the active webui locale when it differs from chat lang.
 */
async function renderWizard(name, values, projectId, lang) {
    const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
    return brainFetch('POST', `wizards/${encodeURIComponent(name)}/render${qs}`, { body: { values, lang } });
}

/**
 * Platform-neutral speech preferences and language picker. Persisted
 * via the host-bound {@link KeyValueStore} from
 * {@link configurePlatform}.
 *
 * Web Speech API specifics (voice catalogue, utterance construction)
 * live in `vance-face/src/platform/speechWeb.ts`. Mobile equivalents
 * (via `expo-speech`) live in the mobile app's platform layer. Both
 * read the same preferences from this module so a user's choices
 * survive across clients on the same device when the storage backend
 * is shared.
 */
// ──────────────── Range constants ────────────────
const DEFAULT_RATE = 1.0;
const MIN_RATE = 0.5;
const MAX_RATE = 2.0;
const DEFAULT_VOLUME = 1.0;
const MIN_VOLUME = 0.0;
const MAX_VOLUME = 1.0;
// ──────────────── Language ────────────────
/** Sentinel value: defer to the platform locale. */
const AUTO_LANGUAGE = 'auto';
/**
 * Curated short list. Long enough to cover the team's likely user
 * base, short enough to avoid a wall of options. Extend by editing
 * this array — the picker reads it directly.
 */
const SUPPORTED_SPEECH_LANGUAGES = [
    { code: AUTO_LANGUAGE, label: 'Auto (browser default)' },
    { code: 'de-DE', label: 'Deutsch (Deutschland)' },
    { code: 'en-US', label: 'English (US)' },
    { code: 'en-GB', label: 'English (UK)' },
    { code: 'fr-FR', label: 'Français' },
    { code: 'es-ES', label: 'Español' },
    { code: 'it-IT', label: 'Italiano' },
    { code: 'nl-NL', label: 'Nederlands' },
    { code: 'pt-BR', label: 'Português (Brasil)' },
];
/** Returns the stored preference, or {@link AUTO_LANGUAGE} on first run. */
function getSpeechLanguage() {
    return getStorage().prefsStore.get(StorageKeys.speechLanguage) ?? AUTO_LANGUAGE;
}
/**
 * Persist a BCP-47 code (or {@link AUTO_LANGUAGE}). Pass `null` to
 * clear and fall back to auto. Validates against
 * {@link SUPPORTED_SPEECH_LANGUAGES} so we don't store typos.
 */
function setSpeechLanguage(code) {
    const prefs = getStorage().prefsStore;
    if (code === null || code === AUTO_LANGUAGE) {
        prefs.remove(StorageKeys.speechLanguage);
        return;
    }
    const known = SUPPORTED_SPEECH_LANGUAGES.some((opt) => opt.code === code);
    if (!known) {
        throw new Error(`Unknown speech language: ${code}`);
    }
    prefs.set(StorageKeys.speechLanguage, code);
}
/**
 * Effective BCP-47 code. Resolves the {@link AUTO_LANGUAGE} sentinel
 * against the host's current locale (`navigator.language` on Web,
 * Expo Localization shim on Mobile), falling back to `'en-US'` when
 * the host has no locale to report.
 */
function resolveSpeechLanguage() {
    const stored = getSpeechLanguage();
    if (stored !== AUTO_LANGUAGE)
        return stored;
    return (typeof navigator !== 'undefined' && navigator.language) || 'en-US';
}
// ──────────────── Voice URI ────────────────
/** Voice URI of the user's chosen TTS voice, or `null` for auto. */
function getSpeechVoiceURI() {
    return getStorage().prefsStore.get(StorageKeys.speechVoiceUri);
}
function setSpeechVoiceURI(uri) {
    const prefs = getStorage().prefsStore;
    if (uri === null || uri === '') {
        prefs.remove(StorageKeys.speechVoiceUri);
        return;
    }
    prefs.set(StorageKeys.speechVoiceUri, uri);
}
// ──────────────── Rate ────────────────
function getSpeechRate() {
    const raw = getStorage().prefsStore.get(StorageKeys.speechRate);
    if (!raw)
        return DEFAULT_RATE;
    const parsed = parseFloat(raw);
    if (!Number.isFinite(parsed))
        return DEFAULT_RATE;
    return clamp(parsed, MIN_RATE, MAX_RATE);
}
function setSpeechRate(rate) {
    if (!Number.isFinite(rate))
        return;
    const prefs = getStorage().prefsStore;
    const clamped = clamp(rate, MIN_RATE, MAX_RATE);
    if (clamped === DEFAULT_RATE) {
        prefs.remove(StorageKeys.speechRate);
        return;
    }
    prefs.set(StorageKeys.speechRate, String(clamped));
}
// ──────────────── Volume ────────────────
function getSpeechVolume() {
    const raw = getStorage().prefsStore.get(StorageKeys.speechVolume);
    if (!raw)
        return DEFAULT_VOLUME;
    const parsed = parseFloat(raw);
    if (!Number.isFinite(parsed))
        return DEFAULT_VOLUME;
    return clamp(parsed, MIN_VOLUME, MAX_VOLUME);
}
function setSpeechVolume(volume) {
    if (!Number.isFinite(volume))
        return;
    const prefs = getStorage().prefsStore;
    const clamped = clamp(volume, MIN_VOLUME, MAX_VOLUME);
    if (clamped === DEFAULT_VOLUME) {
        prefs.remove(StorageKeys.speechVolume);
        return;
    }
    prefs.set(StorageKeys.speechVolume, String(clamped));
}
// ──────────────── Speaker toggle ────────────────
function getSpeakerEnabled() {
    return getStorage().prefsStore.get(StorageKeys.speakerEnabled) === '1';
}
function setSpeakerEnabled(enabled) {
    const prefs = getStorage().prefsStore;
    if (enabled) {
        prefs.set(StorageKeys.speakerEnabled, '1');
    }
    else {
        prefs.remove(StorageKeys.speakerEnabled);
    }
}
// ──────────────── Helpers ────────────────
function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
}

/**
 * @deprecated Use {@code markdownToSpeech} from
 * {@code ./markdownToSpeech} instead. The old stripper here keeps
 * fenced-code-block bodies in the output and the TTS engine reads
 * them out loud — which defeats the "speak-but-show" pattern voice
 * mode relies on (see specification/voice-mode.md §5). The new
 * `markdownToSpeech` is a faithful TS port of the Java
 * {@code MarkdownToSpeech} and replaces fences / tables with short
 * hints ("Code-Block mit N Zeilen").
 *
 * Kept as a thin wrapper around the new implementation so older
 * tests / call-sites don't break. Will be removed once nothing
 * imports this name.
 */
function stripMarkdown(text) {
    return text
        // fenced code blocks → just the inner text
        .replace(/```[\s\S]*?```/g, (m) => m.replace(/```[a-zA-Z0-9]*\n?|```/g, ''))
        // inline code
        .replace(/`([^`]+)`/g, '$1')
        // images and links — keep the alt / link text only
        .replace(/!?\[([^\]]+)\]\([^)]+\)/g, '$1')
        // headings
        .replace(/^#{1,6}\s+/gm, '')
        // emphasis markers
        .replace(/[*_~]+/g, '')
        // collapse whitespace
        .replace(/[ \t]+\n/g, '\n')
        .trim();
}

/**
 * Strip Markdown formatting for text-to-speech rendering — TS port of
 * {@code de.mhus.vance.shared.voice.MarkdownToSpeech}.
 *
 * Spec: specification/inline-and-embedded-content.md §10.3.
 *
 * Voice clients (mobile-voice, foot-with-TTS, future web-voice) call
 * this on the engine's raw Markdown before handing it to the system
 * or cloud TTS synthesiser. Engine output stays uniform; channel-
 * specific rendering is the client's responsibility.
 *
 * Pure string transformation — no DOM, no storage, no platform
 * dependency. Safe from any client.
 */
const ORDINALS_DE = [
    'Erstens', 'Zweitens', 'Drittens', 'Viertens', 'Fünftens',
    'Sechstens', 'Siebtens', 'Achtens', 'Neuntens', 'Zehntens',
];
const NUMBERS_DE = [
    'Eins', 'Zwei', 'Drei', 'Vier', 'Fünf',
    'Sechs', 'Sieben', 'Acht', 'Neun', 'Zehn',
];
// Same patterns as Java side; flags differ slightly for JS regex.
const FENCED = /^( {0,3})(```+|~~~+)([^\n]*)\n([\s\S]*?)\n\1\2[^\n]*$/gm;
const TABLE = /^\|.+\|[ \t]*\n[ \t]*\|[\s|:\-]+\|[ \t]*\n(?:[ \t]*\|.+\|[ \t]*\n?)*/gm;
const IMAGE_LINK = /!\[([^\]]*)\]\(([^)]*)\)/g;
const LINK = /\[([^\]]*)\]\(([^)]*)\)/g;
const HEADING = /^ {0,3}#{1,6}\s+(.*?)\s*#*\s*$/gm;
const BULLET_ITEM = /^ {0,3}[*+\-]\s+(.*)$/;
const ORDERED_ITEM = /^ {0,3}\d+[.)]\s+(.*)$/;
const HRULE = /^ {0,3}([-*_])(?:\s*\1){2,}\s*$/gm;
const INLINE_CODE = /`([^`]+)`/g;
const BOLD_ITALIC = /([*_]{1,3})(\S(?:.*?\S)?)\1/g;
const STRIKE = /~~([^~]+)~~/g;
const HTML_TAG = /<[^>]+>/g;
const FOOTNOTE_REF = /\[\^[^\]]+\]/g;
const BLOCKQUOTE = /^\s*>\s?/gm;
function markdownToSpeech(markdown) {
    if (!markdown)
        return '';
    let s = markdown;
    // ── 1. Fenced code blocks → "(Code-Block mit N Zeilen)"
    s = s.replace(FENCED, (_match, _indent, _fence, _lang, body) => {
        const lines = body.length === 0 ? 0 : body.split('\n').length;
        return `(Code-Block mit ${lines} Zeilen)`;
    });
    // ── 2. Pipe-tables → "(Tabelle mit X Zeilen, Y Spalten)"
    s = s.replace(TABLE, (block) => {
        const lines = block.split('\n');
        let rows = 0;
        let cols = 0;
        for (let i = 0; i < lines.length; i++) {
            const ln = lines[i].trim();
            if (!ln)
                continue;
            if (i === 1 && /^\|[\s|:\-]+\|$/.test(ln))
                continue;
            rows++;
            if (cols === 0) {
                const inner = ln.replace(/^\||\|$/g, '');
                cols = inner.split('|').length;
            }
        }
        return `(Tabelle mit ${rows} Zeilen, ${cols} Spalten)`;
    });
    // ── 3. Image links → alt; regular links → text (or "Link zu <host>")
    s = s.replace(IMAGE_LINK, (_m, alt) => alt.trim() || 'Bild');
    s = s.replace(LINK, (_m, text, url) => {
        const t = text.trim();
        if (t)
            return t;
        const host = extractHost(url.trim());
        return host ? `Link zu ${host}` : 'Link';
    });
    // ── 4. Headings → "text." (period pause hint)
    s = s.replace(HEADING, (_m, text) => {
        const t = text.trim();
        if (!t)
            return '';
        return /[.!?]$/.test(t) ? t : t + '.';
    });
    // ── 5. Horizontal rules → ". ."
    s = s.replace(HRULE, '. .');
    // ── 6. Lists → "Erstens: …; Zweitens: …"
    s = collapseList(s, BULLET_ITEM, ORDINALS_DE);
    s = collapseList(s, ORDERED_ITEM, NUMBERS_DE);
    // ── 7. Blockquote marker
    s = s.replace(BLOCKQUOTE, '');
    // ── 8. Inline markers
    s = s.replace(INLINE_CODE, '$1');
    s = s.replace(STRIKE, '$1');
    s = s.replace(BOLD_ITALIC, '$2');
    // ── 9. HTML tags + footnote refs
    s = s.replace(HTML_TAG, '');
    s = s.replace(FOOTNOTE_REF, '');
    // ── 10. Collapse leftover whitespace
    s = s.replace(/[ \t]+/g, ' ').replace(/\n{3,}/g, '\n\n').trim();
    return s;
}
function extractHost(url) {
    if (!url)
        return '';
    try {
        const schemeEnd = url.indexOf('://');
        let rest = schemeEnd >= 0 ? url.substring(schemeEnd + 3) : url;
        const slash = rest.indexOf('/');
        if (slash >= 0)
            rest = rest.substring(0, slash);
        const question = rest.indexOf('?');
        if (question >= 0)
            rest = rest.substring(0, question);
        return rest;
    }
    catch {
        return '';
    }
}
function collapseList(input, itemPattern, connectors) {
    const lines = input.split('\n');
    const out = [];
    let bucket = [];
    const flush = () => {
        if (bucket.length === 0)
            return;
        out.push(joinList(bucket, connectors));
        bucket = [];
    };
    for (const line of lines) {
        const m = itemPattern.exec(line);
        if (m) {
            bucket.push(m[1].trim());
        }
        else {
            flush();
            out.push(line);
        }
    }
    flush();
    return out.join('\n');
}
function joinList(items, connectors) {
    return items
        .map((it, i) => `${i < connectors.length ? connectors[i] : 'Weitens'}: ${it}`)
        .join('; ');
}

/**
 * Error thrown when the brain replies with an `error` frame to a
 * request. Carries the HTTP-style status code so the chat editor can
 * distinguish a 409 (occupied) from a 404 (missing) without parsing
 * the message string.
 */
class WebSocketRequestError extends Error {
    errorCode;
    type;
    constructor(errorCode, type, message) {
        super(message);
        this.errorCode = errorCode;
        this.type = type;
        this.name = 'WebSocketRequestError';
    }
}
/** Thrown when the connection closes before the request was answered. */
class WebSocketClosedError extends Error {
    constructor(message = 'WebSocket connection closed') {
        super(message);
        this.name = 'WebSocketClosedError';
    }
}

/**
 * Opens a WebSocket to the brain, waits for the `welcome` frame, and
 * exposes a typed request/response + push-subscription API on top.
 *
 * Browser WebSocket cannot send custom HTTP headers, so JWT and
 * client-identity travel as query parameters
 * (`?token=&profile=&clientVersion=`). The server's
 * {@code BrainAccessFilter} and {@code VanceHandshakeInterceptor}
 * accept those for the WS upgrade route — see
 * `specification/websocket-protokoll.md` §2.
 *
 * Lifecycle expectations:
 *  - {@link connect} returns a fresh, ready instance once `welcome` arrives.
 *  - The caller subscribes to server-pushed frames via {@link on}.
 *  - {@link send} resolves with the matching `replyTo` frame, or rejects
 *    with a {@link WebSocketRequestError} (server-sent `error`) or
 *    {@link WebSocketClosedError} (connection died first).
 *  - Reconnects are explicit: when the socket closes, the caller decides
 *    whether to open a fresh instance.
 */
class BrainWebSocket {
    socket;
    handlers = new Map();
    pending = new Map();
    closeListeners = new Set();
    requestSeq = 0;
    tenantId;
    welcome = null;
    isClosed = false;
    constructor(socket, tenant) {
        this.socket = socket;
        this.tenantId = tenant;
        this.socket.addEventListener('message', this.handleMessage);
        this.socket.addEventListener('close', this.handleClose);
    }
    /**
     * Open a connection and resolve once the server's `welcome` frame
     * has arrived. Rejects if the upgrade fails (HTTP error from the
     * filter) or the socket closes before `welcome`.
     */
    static connect(options) {
        const url = options.url ?? buildBrainWsUrl(options);
        const socket = new WebSocket(url);
        const instance = new BrainWebSocket(socket, options.tenant);
        return new Promise((resolve, reject) => {
            let settled = false;
            const onWelcome = (data) => {
                if (settled)
                    return;
                settled = true;
                instance.welcome = data;
                resolve(instance);
            };
            instance.on('welcome', onWelcome);
            // If the socket dies before we get a welcome, fail the connect.
            const onCloseBeforeReady = (event) => {
                if (settled)
                    return;
                settled = true;
                socket.removeEventListener('close', onCloseBeforeReady);
                reject(new WebSocketClosedError(`WebSocket closed before welcome (code ${event.code})`));
            };
            socket.addEventListener('close', onCloseBeforeReady);
            // Likewise an explicit error event from the underlying socket.
            socket.addEventListener('error', () => {
                if (settled)
                    return;
                settled = true;
                reject(new WebSocketClosedError('WebSocket error during handshake'));
            }, { once: true });
        });
    }
    /**
     * Send a request frame and wait for its reply (matched on
     * {@code replyTo === id}). Rejects on server `error` reply or socket
     * close.
     */
    send(type, data) {
        if (this.isClosed) {
            return Promise.reject(new WebSocketClosedError());
        }
        const id = `req_${++this.requestSeq}`;
        const envelope = { id, type, data };
        return new Promise((resolve, reject) => {
            this.pending.set(id, {
                type,
                resolve: (raw) => resolve(raw),
                reject,
            });
            this.socket.send(JSON.stringify(envelope));
        });
    }
    /** Send a frame without expecting a reply (e.g. `logout`). */
    sendNoReply(type, data) {
        if (this.isClosed)
            return;
        const envelope = { type, data };
        this.socket.send(JSON.stringify(envelope));
    }
    /**
     * Subscribe to server-pushed frames of {@code type}. Returns an
     * unsubscribe function. Multiple handlers per type are allowed.
     *
     * Pushed frames have no {@code replyTo} — the dispatcher routes
     * frames with {@code replyTo} to the pending-request map instead.
     */
    on(type, handler) {
        let set = this.handlers.get(type);
        if (!set) {
            set = new Set();
            this.handlers.set(type, set);
        }
        set.add(handler);
        return () => set.delete(handler);
    }
    /** Subscribe to the underlying socket close event. */
    onClose(handler) {
        this.closeListeners.add(handler);
        return () => this.closeListeners.delete(handler);
    }
    /** Welcome payload as received during connect. {@code null} only before {@link connect} resolves. */
    getWelcome() {
        return this.welcome;
    }
    /** Tenant id this connection is scoped to. */
    getTenantId() {
        return this.tenantId;
    }
    /** Whether the socket has been observed closed (or close() called). */
    closed() {
        return this.isClosed;
    }
    /** Close the connection. Pending requests are rejected with {@link WebSocketClosedError}. */
    close(code, reason) {
        if (this.isClosed)
            return;
        this.socket.close(code, reason);
    }
    // ── internals ───────────────────────────────────────────────────────
    handleMessage = (event) => {
        const raw = typeof event.data === 'string' ? event.data : '';
        if (!raw)
            return;
        let envelope;
        try {
            envelope = JSON.parse(raw);
        }
        catch {
            // Malformed frame — log to console; protocol violation, but don't
            // crash the editor.
            console.warn('Discarding non-JSON WebSocket frame', raw);
            return;
        }
        // Reply path: a request the client is waiting for.
        if (envelope.replyTo) {
            const pending = this.pending.get(envelope.replyTo);
            if (pending) {
                this.pending.delete(envelope.replyTo);
                if (envelope.type === 'error') {
                    const err = envelope.data;
                    pending.reject(new WebSocketRequestError(err?.errorCode ?? 0, pending.type, err?.errorMessage ?? `Server error on ${pending.type}`));
                }
                else {
                    pending.resolve(envelope.data);
                }
                return;
            }
            // Else fall through — replyTo with no pending request gets
            // dispatched to handlers in case someone wants to observe it.
        }
        // Push path: dispatch to subscribers.
        const subscribers = this.handlers.get(envelope.type);
        if (subscribers && subscribers.size > 0) {
            for (const handler of subscribers) {
                try {
                    handler(envelope.data);
                }
                catch (e) {
                    console.error(`WS handler for '${envelope.type}' threw`, e);
                }
            }
        }
    };
    handleClose = (event) => {
        this.isClosed = true;
        for (const [id, pending] of this.pending) {
            pending.reject(new WebSocketClosedError(`WebSocket closed (code ${event.code}) — request '${pending.type}' (${id}) abandoned`));
        }
        this.pending.clear();
        for (const listener of this.closeListeners) {
            try {
                listener(event);
            }
            catch (e) {
                console.error('WS close listener threw', e);
            }
        }
    };
}
/**
 * Construct the WebSocket URL with auth + client-identity passed as
 * query parameters. The base URL comes from
 * {@link configurePlatform} — the host (Web's `bootWeb.ts`) is
 * responsible for substituting `${location.protocol}//${location.host}`
 * before binding when same-origin connections are wanted; this module
 * never reads the platform URL itself.
 */
function buildBrainWsUrl(options) {
    const httpBase = brainBaseUrl();
    if (!httpBase) {
        throw new Error('@vance/shared: brain base URL is empty — call configurePlatform with a non-empty baseUrl before opening WebSocket.');
    }
    const wsOrigin = httpBase
        .replace(/^http:\/\//, 'ws://')
        .replace(/^https:\/\//, 'wss://');
    const params = new URLSearchParams({
        profile: options.profile,
        clientVersion: options.clientVersion,
    });
    // Cookie-only callers (web UI same-origin) leave `jwt` empty; the
    // browser ships `vance_access` on the upgrade request. Bearer
    // callers (Mobile, cross-origin embeds) pass the access token here
    // — it is rendered as the `?token=` query parameter because the
    // browser WebSocket constructor cannot set custom headers.
    if (options.jwt) {
        params.set('token', options.jwt);
    }
    return `${wsOrigin}/brain/${encodeURIComponent(options.tenant)}/ws?${params}`;
}

export { AUTO_LANGUAGE, BrainWebSocket, DEFAULT_RATE, DEFAULT_VOLUME, MAX_RATE, MAX_VOLUME, MIN_RATE, MIN_VOLUME, RestError, SUPPORTED_SPEECH_LANGUAGES, StorageKeys, WebSocketClosedError, WebSocketRequestError, __resetPlatform, _clearLinkPreviewCache, applySettingForm, archiveSession, brainBaseUrl, brainFetch, brainFetchBlob, brainFetchText, brainFetchWithMeta, clearAuth, clearLegacyAuth, clearRememberedLogin, configurePlatform, createCalendarEvent, createKanbanCard, decodeJwt, deleteCalendarEvent, deleteKanbanCard, deleteSession, documentContentUrl, fetchLinkPreview, getActiveSessionId, getCalendarPlanner, getKanbanBoard, getRememberedLogin, getRestConfig, getSessionMessages, getSettingForm, getSpeakerEnabled, getSpeechLanguage, getSpeechRate, getSpeechVoiceURI, getSpeechVolume, getStorage, getTenantId, getUsername, getWizard, isTokenValid, listSessions, listSettingForms, listWizards, markdownToSpeech, moveKanbanCard, patchSessionMetadata, reactivateSession, rebuildCalendarPlanner, rebuildKanbanBoard, renderWizard, resetSettingForm, resolveSpeechLanguage, searchSessions, setActiveSessionId, setRememberedLogin, setSpeakerEnabled, setSpeechLanguage, setSpeechRate, setSpeechVoiceURI, setSpeechVolume, stripMarkdown, updateCalendarEvent, updateKanbanCard, validateSettingForm };
