// Codec for `kind: calendar` documents — parses an on-disk body
// (JSON or YAML) into a typed CalendarDocument and serializes it back.
// Markdown is intentionally not supported (events are nested enough
// that an MD table loses fidelity — see `specification/doc-kind-calendar.md`).
//
// Date/time and RRULE strings are pass-through; the codec does not
// validate ISO-8601 or RFC 5545. Event ids missing on read are
// auto-filled with a fresh UUID so the renderer can rely on stable
// identity.
//
// Mirrors the server-side `CalendarCodec` in `vance-shared`.
import { dumpYamlBody, parseYamlBody, unwrapJsonMeta, wrapJsonMeta, } from './documentHeaderCodec';
export class CalendarCodecError extends Error {
    cause;
    constructor(message, cause) {
        super(message);
        this.cause = cause;
        this.name = 'CalendarCodecError';
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
export function parseCalendar(body, mimeType) {
    if (isJson(mimeType))
        return parseCalendarJson(body);
    if (isYaml(mimeType))
        return parseCalendarYaml(body);
    throw new CalendarCodecError(`Unsupported mime type for calendar: ${mimeType}`);
}
export function serializeCalendar(doc, mimeType) {
    if (isJson(mimeType))
        return serializeCalendarJson(doc);
    if (isYaml(mimeType))
        return serializeCalendarYaml(doc);
    throw new CalendarCodecError(`Unsupported mime type for calendar: ${mimeType}`);
}
export function isCalendarMime(mimeType) {
    if (!mimeType)
        return false;
    return isJson(mimeType) || isYaml(mimeType);
}
export function emptyCalendar() {
    return { kind: 'calendar', events: [], extra: {} };
}
// ── JSON ─────────────────────────────────────────────────────────────
function parseCalendarJson(body) {
    if (body.trim() === '')
        return emptyCalendar();
    let parsed;
    try {
        parsed = JSON.parse(body);
    }
    catch (e) {
        throw new CalendarCodecError('Invalid JSON: ' + (e instanceof Error ? e.message : String(e)), e);
    }
    if (!isObject(parsed)) {
        throw new CalendarCodecError('Top-level JSON must be an object');
    }
    return promoteToDocument(unwrapJsonMeta(parsed));
}
function serializeCalendarJson(doc) {
    return JSON.stringify(wrapJsonMeta(doc.kind || 'calendar', buildBody(doc)), null, 2) + '\n';
}
// ── YAML ─────────────────────────────────────────────────────────────
function parseCalendarYaml(body) {
    if (body.trim() === '')
        return emptyCalendar();
    let merged;
    try {
        merged = parseYamlBody(body);
    }
    catch (e) {
        throw new CalendarCodecError('Invalid YAML: ' + (e instanceof Error ? e.message : String(e)), e);
    }
    return promoteToDocument(merged);
}
function serializeCalendarYaml(doc) {
    return dumpYamlBody(doc.kind || 'calendar', buildBody(doc));
}
// ── Promotion ────────────────────────────────────────────────────────
function promoteToDocument(obj) {
    const kind = typeof obj.kind === 'string' && obj.kind ? obj.kind : 'calendar';
    const events = promoteEvents(obj.events);
    const extra = {};
    for (const [k, v] of Object.entries(obj)) {
        if (k === 'kind' || k === 'events')
            continue;
        extra[k] = v;
    }
    return { kind, events, extra };
}
function promoteEvents(raw) {
    if (!Array.isArray(raw))
        return [];
    const out = [];
    for (const r of raw) {
        if (!isObject(r))
            continue;
        const title = coerceString(r.title);
        if (!title)
            continue;
        const start = coerceString(r.start);
        if (!start)
            continue;
        const id = coerceString(r.id) ?? uuid();
        const end = coerceString(r.end) ?? undefined;
        const allDay = r.allDay === true;
        const location = coerceString(r.location) ?? undefined;
        const attendees = promoteStringList(r.attendees);
        const recurrence = coerceString(r.recurrence) ?? undefined;
        const color = coerceString(r.color) ?? undefined;
        const tags = promoteStringList(r.tags);
        const notes = coerceString(r.notes) ?? undefined;
        const extra = {};
        for (const [k, v] of Object.entries(r)) {
            if (isKnownEventKey(k))
                continue;
            extra[k] = v;
        }
        out.push({
            id, title, start, end, allDay, location,
            attendees, recurrence, color, tags, notes, extra,
        });
    }
    return out;
}
const KNOWN_EVENT_KEYS = new Set([
    'id', 'title', 'start', 'end', 'allDay',
    'location', 'attendees', 'recurrence',
    'color', 'tags', 'notes',
]);
function isKnownEventKey(k) {
    return KNOWN_EVENT_KEYS.has(k);
}
function promoteStringList(raw) {
    if (!Array.isArray(raw))
        return [];
    const out = [];
    for (const item of raw) {
        const s = coerceString(item);
        if (s)
            out.push(s);
    }
    return out;
}
function coerceString(raw) {
    if (raw == null)
        return null;
    if (typeof raw === 'string') {
        return raw.trim() === '' ? null : raw;
    }
    // YAML loaders that recognise unquoted ISO dates may return a Date.
    if (raw instanceof Date) {
        if (Number.isNaN(raw.getTime()))
            return null;
        const iso = raw.toISOString();
        // Date-only inputs end up at midnight UTC; emit `yyyy-MM-dd` so
        // allDay events stay clean on round-trip.
        if (iso.endsWith('T00:00:00.000Z'))
            return iso.slice(0, 10);
        return iso.replace(/\.\d{3}Z$/, 'Z');
    }
    if (typeof raw === 'number' || typeof raw === 'boolean') {
        return String(raw);
    }
    return null;
}
function uuid() {
    // Browsers + Node 19+ ship crypto.randomUUID; fall back to a v4-ish
    // string when the runtime is older. The renderer only needs a
    // stable opaque token, not crypto guarantees.
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
        return crypto.randomUUID();
    }
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
        const r = (Math.random() * 16) | 0;
        const v = c === 'x' ? r : (r & 0x3) | 0x8;
        return v.toString(16);
    });
}
// ── Body builder ─────────────────────────────────────────────────────
function buildBody(doc) {
    const body = {};
    body.events = doc.events.map(eventToObject);
    for (const [k, v] of Object.entries(doc.extra)) {
        if (!(k in body))
            body[k] = v;
    }
    return body;
}
function eventToObject(ev) {
    const o = {};
    o.id = ev.id;
    o.title = ev.title;
    o.start = ev.start;
    if (ev.end !== undefined)
        o.end = ev.end;
    if (ev.allDay)
        o.allDay = true;
    if (ev.location !== undefined)
        o.location = ev.location;
    if (ev.attendees.length > 0)
        o.attendees = [...ev.attendees];
    if (ev.recurrence !== undefined)
        o.recurrence = ev.recurrence;
    if (ev.color !== undefined)
        o.color = ev.color;
    if (ev.tags.length > 0)
        o.tags = [...ev.tags];
    if (ev.notes !== undefined)
        o.notes = ev.notes;
    for (const [k, v] of Object.entries(ev.extra)) {
        if (!(k in o))
            o[k] = v;
    }
    return o;
}
function isObject(v) {
    return typeof v === 'object' && v !== null && !Array.isArray(v);
}
//# sourceMappingURL=calendarCodec.js.map