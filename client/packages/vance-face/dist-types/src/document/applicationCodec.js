// Codec for `kind: application` documents — the manifest at the root
// of a Vance "app folder" (à la macOS .app bundles). Carries the
// app-type discriminator ($meta.app) plus an app-specific nested
// config block (e.g. `calendar: { lanes, gantt, conflicts, window }`
// for app=calendar).
//
// JSON and YAML only — Markdown makes no sense for an app manifest.
//
// Mirrors the server-side `ApplicationCodec` in `vance-shared`.
import { dumpYamlBody, parseYamlBody, unwrapJsonMeta, } from '@vance/shared';
export class ApplicationCodecError extends Error {
    cause;
    constructor(message, cause) {
        super(message);
        this.cause = cause;
        this.name = 'ApplicationCodecError';
    }
}
// ── MIME helpers ─────────────────────────────────────────────────────
function isJson(mime) {
    return mime === 'application/json';
}
function isYaml(mime) {
    return mime === 'application/yaml'
        || mime === 'application/x-yaml'
        || mime === 'text/yaml'
        || mime === 'text/x-yaml';
}
// ── Public API ───────────────────────────────────────────────────────
export function parseApplication(body, mimeType) {
    if (isJson(mimeType))
        return parseApplicationJson(body);
    if (isYaml(mimeType))
        return parseApplicationYaml(body);
    throw new ApplicationCodecError(`Unsupported mime type for application: ${mimeType}`);
}
export function serializeApplication(doc, mimeType) {
    if (isJson(mimeType))
        return serializeApplicationJson(doc);
    if (isYaml(mimeType))
        return serializeApplicationYaml(doc);
    throw new ApplicationCodecError(`Unsupported mime type for application: ${mimeType}`);
}
export function isApplicationMime(mimeType) {
    if (!mimeType)
        return false;
    return isJson(mimeType) || isYaml(mimeType);
}
export function emptyApplication(app = '') {
    return { kind: 'application', app, config: {}, extra: {} };
}
// ── JSON ─────────────────────────────────────────────────────────────
function parseApplicationJson(body) {
    if (body.trim() === '')
        return emptyApplication();
    let parsed;
    try {
        parsed = JSON.parse(body);
    }
    catch (e) {
        throw new ApplicationCodecError('Invalid JSON: ' + (e instanceof Error ? e.message : String(e)), e);
    }
    if (!isObject(parsed)) {
        throw new ApplicationCodecError('Top-level JSON must be an object');
    }
    return promoteToDocument(unwrapJsonMeta(parsed));
}
function serializeApplicationJson(doc) {
    // $meta needs both kind + app inline — we build the wrapper by
    // hand since wrapJsonMeta only knows about `kind`.
    const meta = { kind: doc.kind || 'application' };
    if (doc.app)
        meta.app = doc.app;
    const wrapped = {
        $meta: meta,
        ...buildBody(doc),
    };
    return JSON.stringify(wrapped, null, 2) + '\n';
}
// ── YAML ─────────────────────────────────────────────────────────────
function parseApplicationYaml(body) {
    if (body.trim() === '')
        return emptyApplication();
    let merged;
    try {
        merged = parseYamlBody(body);
    }
    catch (e) {
        throw new ApplicationCodecError('Invalid YAML: ' + (e instanceof Error ? e.message : String(e)), e);
    }
    return promoteToDocument(merged);
}
function serializeApplicationYaml(doc) {
    const headerExtra = {};
    if (doc.app)
        headerExtra.app = doc.app;
    return dumpYamlBody(doc.kind || 'application', buildBody(doc), headerExtra);
}
// ── Shared promotion ─────────────────────────────────────────────────
function promoteToDocument(obj) {
    const kind = typeof obj.kind === 'string' && obj.kind ? obj.kind : 'application';
    const app = typeof obj.app === 'string' ? obj.app : '';
    const title = typeof obj.title === 'string' && obj.title ? obj.title : undefined;
    const description = typeof obj.description === 'string' && obj.description
        ? obj.description : undefined;
    const config = {};
    const extra = {};
    for (const [k, v] of Object.entries(obj)) {
        if (k === 'kind' || k === 'app' || k === 'title' || k === 'description')
            continue;
        if (isObject(v)) {
            config[k] = v;
        }
        else {
            extra[k] = v;
        }
    }
    return { kind, app, title, description, config, extra };
}
function buildBody(doc) {
    const body = {};
    if (doc.title)
        body.title = doc.title;
    if (doc.description)
        body.description = doc.description;
    for (const [k, v] of Object.entries(doc.config)) {
        if (!(k in body))
            body[k] = v;
    }
    for (const [k, v] of Object.entries(doc.extra)) {
        if (!(k in body))
            body[k] = v;
    }
    return body;
}
function isObject(v) {
    return typeof v === 'object' && v !== null && !Array.isArray(v);
}
// ── App-type helpers ─────────────────────────────────────────────────
export function applicationAppType(doc) {
    return doc.app || '';
}
//# sourceMappingURL=applicationCodec.js.map