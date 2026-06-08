import { i as index_vite_proxy_tmp_default } from './js-yaml-K7iB6vJi.js';

// Shared helpers for the `$meta` header convention used by every
// kind-codec for both JSON and YAML.
//
// Server-side strategies live in
// `vance-shared/document/{Json,Yaml,Markdown}HeaderStrategy.java` —
// these client helpers mirror their on-disk shape so that
// `DocumentDocument.kind` gets correctly mirrored on save.
//
// JSON and YAML are symmetric: both carry a top-level `$meta`
// mapping with `kind` (plus optional scalar extras) at the head of
// the body keys.
const META_KEY = '$meta';
/**
 * Lift a JSON object out of the {@code $meta} wrapper. If the
 * caller's object has a {@code $meta} key whose value is an object,
 * its scalar entries are merged on top of the body keys.
 *
 * Non-scalar {@code $meta} values are dropped — they wouldn't survive
 * a round-trip through the server's {@code JsonHeaderStrategy} either.
 */
function unwrapJsonMeta(obj) {
    const metaVal = obj[META_KEY];
    if (!isObject$1(metaVal))
        return obj;
    const { [META_KEY]: _drop, ...rest } = obj;
    const merged = { ...rest };
    for (const [k, v] of Object.entries(metaVal)) {
        if (isScalar(v)) {
            merged[k] = v;
        }
    }
    return merged;
}
/**
 * Parse a single-document YAML body and unwrap its {@code $meta}
 * mapping. Returns a flattened object that the kind-specific
 * {@code promoteTo…Document} can consume directly — scalar
 * {@code $meta} entries (kind, schema, …) land at the top level next
 * to the body keys.
 *
 * The top-level YAML value must be a mapping; anything else (sequence,
 * scalar) raises {@link Error}. The caller wraps with the codec's own
 * error type.
 */
function parseYamlBody(body) {
    const root = index_vite_proxy_tmp_default.load(body, { schema: index_vite_proxy_tmp_default.JSON_SCHEMA });
    if (root === null || root === undefined)
        return {};
    if (!isObject$1(root)) {
        throw new Error('Top-level YAML must be a mapping');
    }
    return unwrapJsonMeta(root);
}
function isObject$1(v) {
    return typeof v === 'object' && v !== null && !Array.isArray(v);
}
function isScalar(v) {
    return v === null
        || typeof v === 'string'
        || typeof v === 'number'
        || typeof v === 'boolean';
}

class CalendarCodecError extends Error {
  constructor(message, cause) {
    super(message);
    this.cause = cause;
    this.name = "CalendarCodecError";
  }
}
function isJson(mime) {
  return mime === "application/json";
}
function isYaml(mime) {
  return mime === "application/yaml" || mime === "application/x-yaml" || mime === "text/yaml" || mime === "text/x-yaml";
}
function parseCalendar(body, mimeType) {
  if (isJson(mimeType)) return parseCalendarJson(body);
  if (isYaml(mimeType)) return parseCalendarYaml(body);
  throw new CalendarCodecError(`Unsupported mime type for calendar: ${mimeType}`);
}
function emptyCalendar() {
  return { kind: "calendar", events: [], extra: {} };
}
function parseCalendarJson(body) {
  if (body.trim() === "") return emptyCalendar();
  let parsed;
  try {
    parsed = JSON.parse(body);
  } catch (e) {
    throw new CalendarCodecError(
      "Invalid JSON: " + (e instanceof Error ? e.message : String(e)),
      e
    );
  }
  if (!isObject(parsed)) {
    throw new CalendarCodecError("Top-level JSON must be an object");
  }
  return promoteToDocument(unwrapJsonMeta(parsed));
}
function parseCalendarYaml(body) {
  if (body.trim() === "") return emptyCalendar();
  let merged;
  try {
    merged = parseYamlBody(body);
  } catch (e) {
    throw new CalendarCodecError(
      "Invalid YAML: " + (e instanceof Error ? e.message : String(e)),
      e
    );
  }
  return promoteToDocument(merged);
}
function promoteToDocument(obj) {
  const kind = typeof obj.kind === "string" && obj.kind ? obj.kind : "calendar";
  const events = promoteEvents(obj.events);
  const extra = {};
  for (const [k, v] of Object.entries(obj)) {
    if (k === "kind" || k === "events") continue;
    extra[k] = v;
  }
  return { kind, events, extra };
}
function promoteEvents(raw) {
  if (!Array.isArray(raw)) return [];
  const out = [];
  for (const r of raw) {
    if (!isObject(r)) continue;
    const title = coerceString(r.title);
    if (!title) continue;
    const start = coerceString(r.start);
    if (!start) continue;
    const id = coerceString(r.id) ?? uuid();
    const end = coerceString(r.end) ?? void 0;
    const allDay = r.allDay === true;
    const location = coerceString(r.location) ?? void 0;
    const attendees = promoteStringList(r.attendees);
    const recurrence = coerceString(r.recurrence) ?? void 0;
    const color = coerceString(r.color) ?? void 0;
    const tags = promoteStringList(r.tags);
    const notes = coerceString(r.notes) ?? void 0;
    const extra = {};
    for (const [k, v] of Object.entries(r)) {
      if (isKnownEventKey(k)) continue;
      extra[k] = v;
    }
    out.push({
      id,
      title,
      start,
      end,
      allDay,
      location,
      attendees,
      recurrence,
      color,
      tags,
      notes,
      extra
    });
  }
  return out;
}
const KNOWN_EVENT_KEYS = /* @__PURE__ */ new Set([
  "id",
  "title",
  "start",
  "end",
  "allDay",
  "location",
  "attendees",
  "recurrence",
  "color",
  "tags",
  "notes"
]);
function isKnownEventKey(k) {
  return KNOWN_EVENT_KEYS.has(k);
}
function promoteStringList(raw) {
  if (!Array.isArray(raw)) return [];
  const out = [];
  for (const item of raw) {
    const s = coerceString(item);
    if (s) out.push(s);
  }
  return out;
}
function coerceString(raw) {
  if (raw == null) return null;
  if (typeof raw === "string") {
    return raw.trim() === "" ? null : raw;
  }
  if (raw instanceof Date) {
    if (Number.isNaN(raw.getTime())) return null;
    const iso = raw.toISOString();
    if (iso.endsWith("T00:00:00.000Z")) return iso.slice(0, 10);
    return iso.replace(/\.\d{3}Z$/, "Z");
  }
  if (typeof raw === "number" || typeof raw === "boolean") {
    return String(raw);
  }
  return null;
}
function uuid() {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = Math.random() * 16 | 0;
    const v = c === "x" ? r : r & 3 | 8;
    return v.toString(16);
  });
}
function isObject(v) {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

export { emptyCalendar as e, parseCalendar as p };
