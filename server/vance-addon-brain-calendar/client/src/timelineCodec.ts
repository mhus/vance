// Codec for `kind: timeline` documents — parses an on-disk body
// (JSON or YAML) into a typed TimelineDocument and serializes it back.
// Markdown is intentionally not supported: an axis declaration, lanes
// and four uncertainty bounds per entry do not survive a markdown
// table (see `specification/public/doc-kind-timeline.md`).
//
// Positions (`from`, `to`, the four *Earliest/*Latest bounds) are
// pass-through strings; resolving them against the axis is
// `timelinePosition()`'s job, mirroring the server's TimelineScale.
//
// Mirrors the server-side `TimelineCodec` in this addon. One knowing
// divergence: JavaScript numbers cannot hold `201.40` as written, so a
// trailing zero is lost on a client-side serialize where the Java side
// keeps it. Irrelevant in practice — the timeline Kind is read-only in
// the UI, edits go through the raw editor, and the server owns saves.

import {
  dumpYamlBody,
  parseYamlBody,
  unwrapJsonMeta,
  wrapJsonMeta,
} from '@vance/shared';

/** Number line, or absolute date-times. One per document, never mixed. */
export type TimelineAxisMode = 'numeric' | 'datetime';

/** Whether a larger number on a numeric axis means later or earlier. */
export type TimelineDirection = 'forward' | 'ago';

export interface TimelineAxis {
  /** `'numeric'` (default) or `'datetime'`. */
  mode: TimelineAxisMode;
  /** Free-form unit suffix for tick labels — `'Ma'`, `'ka'`, `'min'`. Numeric only. */
  unit?: string;
  /** `'ago'` makes a larger number earlier (geological scales). */
  direction: TimelineDirection;
  /** Optional viewport lower bound, in axis values. */
  from?: string;
  /** Optional viewport upper bound. */
  to?: string;
  /** Optional caption under the ruler. */
  label?: string;
  /** Unknown axis keys, re-emitted on save. */
  extra: Record<string, unknown>;
}

export interface TimelineLane {
  /** Lane id, referenced by `TimelineEntry.lane`. */
  id: string;
  /** Display label; falls back to the id. */
  title?: string;
  /** Palette name or CSS colour inherited by the lane's entries. */
  color?: string;
}

export interface TimelineEntry {
  /** Stable id. Auto-filled when missing on read. */
  id: string;
  /** Display label. Required. */
  title: string;
  /** Start position on the axis. Required. */
  from: string;
  /** End position — present makes this a period, absent a point. */
  to?: string;
  /** Earliest the start could be. */
  fromEarliest?: string;
  /** Latest the start could be. */
  fromLatest?: string;
  /** Earliest the end could be. */
  toEarliest?: string;
  /** Latest the end could be. */
  toLatest?: string;
  /** Lane id; absent means the default lane. */
  lane?: string;
  /** Id of the entry this one sits inside (era > period > epoch). */
  parent?: string;
  /** Palette name or CSS colour. */
  color?: string;
  /** Free-form filter tags. */
  tags: string[];
  /** Long-form notes. */
  notes?: string;
  /** Unknown entry fields, re-emitted on save. */
  extra: Record<string, unknown>;
}

export interface TimelineDocument {
  /** Always `'timeline'`. */
  kind: string;
  /** Optional heading rendered above the ruler. */
  title?: string;
  /** The axis declaration; never absent (defaults to a forward numeric line). */
  axis: TimelineAxis;
  /** Declared lanes in render order. May be empty. */
  lanes: TimelineLane[];
  /** Flat entry list. Order is preserved but carries no meaning. */
  entries: TimelineEntry[];
  /** Unknown top-level fields, passthrough. */
  extra: Record<string, unknown>;
}

export class TimelineCodecError extends Error {
  constructor(message: string, public override readonly cause?: unknown) {
    super(message);
    this.name = 'TimelineCodecError';
  }
}

// ── MIME helpers ─────────────────────────────────────────────────────

function isJson(mime: string): boolean {
  return mime === 'application/json';
}
function isYaml(mime: string): boolean {
  return mime === 'application/yaml'
    || mime === 'application/x-yaml'
    || mime === 'text/yaml'
    || mime === 'text/x-yaml';
}

export function isTimelineMime(mimeType: string | null | undefined): boolean {
  if (!mimeType) return false;
  return isJson(mimeType) || isYaml(mimeType);
}

// ── Public API ───────────────────────────────────────────────────────

export function emptyTimeline(): TimelineDocument {
  return {
    kind: 'timeline',
    axis: { mode: 'numeric', direction: 'forward', extra: {} },
    lanes: [],
    entries: [],
    extra: {},
  };
}

export function parseTimeline(body: string, mimeType: string): TimelineDocument {
  if (isJson(mimeType)) return parseTimelineJson(body);
  if (isYaml(mimeType)) return parseTimelineYaml(body);
  throw new TimelineCodecError(`Unsupported mime type for timeline: ${mimeType}`);
}

export function serializeTimeline(doc: TimelineDocument, mimeType: string): string {
  if (isJson(mimeType)) {
    return JSON.stringify(wrapJsonMeta(doc.kind || 'timeline', buildBody(doc)), null, 2) + '\n';
  }
  if (isYaml(mimeType)) {
    return dumpYamlBody(doc.kind || 'timeline', buildBody(doc));
  }
  throw new TimelineCodecError(`Unsupported mime type for timeline: ${mimeType}`);
}

// ── JSON / YAML ──────────────────────────────────────────────────────

function parseTimelineJson(body: string): TimelineDocument {
  if (body.trim() === '') return emptyTimeline();
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch (e) {
    throw new TimelineCodecError(
      'Invalid JSON: ' + (e instanceof Error ? e.message : String(e)), e,
    );
  }
  if (!isObject(parsed)) {
    throw new TimelineCodecError('Top-level JSON must be an object');
  }
  return promoteToDocument(unwrapJsonMeta(parsed));
}

function parseTimelineYaml(body: string): TimelineDocument {
  if (body.trim() === '') return emptyTimeline();
  let merged: Record<string, unknown>;
  try {
    merged = parseYamlBody(body);
  } catch (e) {
    throw new TimelineCodecError(
      'Invalid YAML: ' + (e instanceof Error ? e.message : String(e)), e,
    );
  }
  return promoteToDocument(merged);
}

// ── Promotion ────────────────────────────────────────────────────────

function promoteToDocument(obj: Record<string, unknown>): TimelineDocument {
  const kind = typeof obj.kind === 'string' && obj.kind ? obj.kind : 'timeline';
  const extra: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(obj)) {
    if (k === 'kind' || k === 'title' || k === 'axis' || k === 'lanes' || k === 'entries') continue;
    extra[k] = v;
  }
  return {
    kind,
    title: coerce(obj.title) ?? undefined,
    axis: promoteAxis(obj.axis),
    lanes: promoteLanes(obj.lanes),
    entries: promoteEntries(obj.entries),
    extra,
  };
}

const KNOWN_AXIS_KEYS = new Set(['mode', 'unit', 'direction', 'from', 'to', 'label']);

/**
 * Lenient on both enums: an unknown `mode` reads as `numeric` and an
 * unknown `direction` as `forward` rather than failing the document. A
 * typo should render something the author can see and fix — the kind
 * validator names it separately.
 */
function promoteAxis(raw: unknown): TimelineAxis {
  if (!isObject(raw)) return { mode: 'numeric', direction: 'forward', extra: {} };
  const modeRaw = (coerce(raw.mode) ?? '').trim().toLowerCase();
  const mode: TimelineAxisMode =
    modeRaw === 'datetime' || modeRaw === 'date-time' || modeRaw === 'date'
      || modeRaw === 'time' || modeRaw === 'absolute'
      ? 'datetime' : 'numeric';
  const dirRaw = (coerce(raw.direction) ?? '').trim().toLowerCase();
  const direction: TimelineDirection =
    dirRaw === 'ago' || dirRaw === 'backward' || dirRaw === 'backwards'
      || dirRaw === 'bp' || dirRaw === 'reverse'
      ? 'ago' : 'forward';

  const extra: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(raw)) {
    if (!KNOWN_AXIS_KEYS.has(k)) extra[k] = v;
  }

  return {
    mode,
    unit: coerce(raw.unit) ?? undefined,
    direction,
    from: coerce(raw.from) ?? undefined,
    to: coerce(raw.to) ?? undefined,
    label: coerce(raw.label) ?? undefined,
    extra,
  };
}

/** Lanes as an id list, an object list (canonical), or a map keyed by id. */
function promoteLanes(raw: unknown): TimelineLane[] {
  const out: TimelineLane[] = [];
  if (Array.isArray(raw)) {
    for (const r of raw) {
      if (isObject(r)) {
        const id = coerce(r.id);
        if (!id) continue;
        out.push({ id, title: coerce(r.title) ?? undefined, color: coerce(r.color) ?? undefined });
      } else {
        const id = coerce(r);
        if (id) out.push({ id });
      }
    }
  } else if (isObject(raw)) {
    for (const [id, cfg] of Object.entries(raw)) {
      if (!id) continue;
      if (isObject(cfg)) {
        out.push({
          id,
          title: coerce(cfg.title) ?? undefined,
          color: coerce(cfg.color) ?? undefined,
        });
      } else {
        out.push({ id, title: coerce(cfg) ?? undefined });
      }
    }
  }
  return out;
}

const KNOWN_ENTRY_KEYS = new Set([
  'id', 'title', 'from', 'at', 'start', 'to', 'end', 'until',
  'fromEarliest', 'fromLatest', 'toEarliest', 'toLatest',
  'lane', 'parent', 'color', 'tags', 'notes',
]);

function promoteEntries(raw: unknown): TimelineEntry[] {
  if (!Array.isArray(raw)) return [];
  const out: TimelineEntry[] = [];
  for (const r of raw) {
    if (!isObject(r)) continue;
    const title = coerce(r.title);
    if (!title) continue;
    // `at` / `start` are read as `from` and `end` / `until` as `to`:
    // models reach for those words, and the alternative to accepting
    // them is an entry that silently vanishes.
    const from = firstOf(r, 'from', 'at', 'start');
    if (!from) continue;

    const extra: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(r)) {
      if (!KNOWN_ENTRY_KEYS.has(k)) extra[k] = v;
    }

    out.push({
      id: coerce(r.id) ?? uuid(),
      title,
      from,
      to: firstOf(r, 'to', 'end', 'until') ?? undefined,
      fromEarliest: coerce(r.fromEarliest) ?? undefined,
      fromLatest: coerce(r.fromLatest) ?? undefined,
      toEarliest: coerce(r.toEarliest) ?? undefined,
      toLatest: coerce(r.toLatest) ?? undefined,
      lane: coerce(r.lane) ?? undefined,
      parent: coerce(r.parent) ?? undefined,
      color: coerce(r.color) ?? undefined,
      tags: promoteStringList(r.tags),
      notes: coerce(r.notes) ?? undefined,
      extra,
    });
  }
  return out;
}

function firstOf(obj: Record<string, unknown>, ...keys: string[]): string | null {
  for (const key of keys) {
    const v = coerce(obj[key]);
    if (v) return v;
  }
  return null;
}

function promoteStringList(raw: unknown): string[] {
  if (!Array.isArray(raw)) return [];
  const out: string[] = [];
  for (const item of raw) {
    const s = coerce(item);
    if (s) out.push(s);
  }
  return out;
}

/**
 * Coerce a YAML/JSON scalar to a non-blank string. YAML loaders promote
 * an unquoted `1969-07-20` to a Date and a bare `201.4` to a number;
 * without this the position would not be a string and the entry would
 * be dropped as malformed.
 */
function coerce(raw: unknown): string | null {
  if (raw == null) return null;
  if (typeof raw === 'string') return raw.trim() === '' ? null : raw;
  if (raw instanceof Date) {
    if (Number.isNaN(raw.getTime())) return null;
    const iso = raw.toISOString();
    if (iso.endsWith('T00:00:00.000Z')) return iso.slice(0, 10);
    return iso.replace(/\.\d{3}Z$/, 'Z');
  }
  if (typeof raw === 'number' || typeof raw === 'boolean') return String(raw);
  return null;
}

function uuid(): string {
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

function buildBody(doc: TimelineDocument): Record<string, unknown> {
  const body: Record<string, unknown> = {};
  if (doc.title !== undefined) body.title = doc.title;
  body.axis = axisToObject(doc.axis);
  if (doc.lanes.length > 0) body.lanes = doc.lanes.map(laneToObject);
  body.entries = doc.entries.map(entryToObject);
  for (const [k, v] of Object.entries(doc.extra)) {
    if (!(k in body)) body[k] = v;
  }
  return body;
}

function axisToObject(axis: TimelineAxis): Record<string, unknown> {
  const o: Record<string, unknown> = { mode: axis.mode };
  if (axis.unit !== undefined) o.unit = axis.unit;
  // `forward` is the default; emitting it would add noise to every
  // document that never cared about direction.
  if (axis.direction !== 'forward') o.direction = axis.direction;
  if (axis.from !== undefined) o.from = numberOrString(axis.from);
  if (axis.to !== undefined) o.to = numberOrString(axis.to);
  if (axis.label !== undefined) o.label = axis.label;
  for (const [k, v] of Object.entries(axis.extra)) {
    if (!(k in o)) o[k] = v;
  }
  return o;
}

function laneToObject(lane: TimelineLane): Record<string, unknown> {
  const o: Record<string, unknown> = { id: lane.id };
  if (lane.title !== undefined) o.title = lane.title;
  if (lane.color !== undefined) o.color = lane.color;
  return o;
}

function entryToObject(en: TimelineEntry): Record<string, unknown> {
  const o: Record<string, unknown> = {};
  o.id = en.id;
  o.title = en.title;
  o.from = numberOrString(en.from);
  for (const key of ['to', 'fromEarliest', 'fromLatest', 'toEarliest', 'toLatest'] as const) {
    const v = en[key];
    if (v !== undefined) o[key] = numberOrString(v);
  }
  if (en.lane !== undefined) o.lane = en.lane;
  if (en.parent !== undefined) o.parent = en.parent;
  if (en.color !== undefined) o.color = en.color;
  if (en.tags.length > 0) o.tags = [...en.tags];
  if (en.notes !== undefined) o.notes = en.notes;
  for (const [k, v] of Object.entries(en.extra)) {
    if (!(k in o)) o[k] = v;
  }
  return o;
}

/** A plain number is emitted unquoted so YAML reads `from: 201.4`. */
function numberOrString(stored: string): string | number {
  const t = stored.trim();
  if (!/^-?\d+(\.\d+)?$/.test(t)) return stored;
  const n = Number(t);
  return Number.isFinite(n) ? n : stored;
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

// ── Positions ────────────────────────────────────────────────────────

/**
 * ISO-8601-ish instant, deliberately more permissive than `Date.parse`:
 * a bare year is accepted (a historical timeline is naturally written
 * in years) and so is a leading minus for BCE.
 */
const INSTANT =
  /^(-?\d{1,9})(?:-(\d{1,2})(?:-(\d{1,2}))?)?(?:[T ](\d{1,2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?)?\s*(Z|z|[+-]\d{2}:?\d{2})?$/;

/**
 * Project a position onto the number line where smaller means earlier,
 * for the axis the document declared. `null` when the value cannot be
 * read — the renderer then skips that entry rather than drawing it in
 * the wrong place.
 *
 * Mirror of the server's `TimelineScale`. Datetime values without an
 * offset are read as UTC, which is a positioning convention: a uniform
 * shift cannot change the order, and the ruler labels itself from the
 * same numbers.
 */
export function timelinePosition(axis: TimelineAxis, raw: string | null | undefined): number | null {
  if (raw == null || raw.trim() === '') return null;
  if (axis.mode === 'datetime') return instantSeconds(raw);
  const v = numericValue(raw);
  if (v === null) return null;
  return axis.direction === 'ago' ? -v : v;
}

/** A bare number, tolerating whitespace and a leading `+`. */
export function numericValue(raw: string): number | null {
  let s = raw.trim();
  if (s.startsWith('+')) s = s.slice(1);
  if (!/^-?\d+(\.\d+)?([eE][+-]?\d+)?$/.test(s)) return null;
  const n = Number(s);
  return Number.isFinite(n) ? n : null;
}

/**
 * Epoch seconds for an ISO-8601-ish instant. Missing components default
 * to the start of their range, so a bare `1969` means the beginning of
 * that year and sorts before `1969-07-20`.
 */
export function instantSeconds(raw: string): number | null {
  const m = INSTANT.exec(raw.trim());
  if (!m) return null;
  const year = Number(m[1]);
  const month = m[2] !== undefined ? Number(m[2]) : 1;
  const day = m[3] !== undefined ? Number(m[3]) : 1;
  const hour = m[4] !== undefined ? Number(m[4]) : 0;
  const minute = m[5] !== undefined ? Number(m[5]) : 0;
  const second = m[6] !== undefined ? Number(m[6]) : 0;
  if (hour > 23 || minute > 59 || second > 59) return null;

  // setUTCFullYear rather than Date.UTC: the latter maps years 0..99
  // into the 1900s, which would put a Roman date 1900 years late.
  const d = new Date(0);
  d.setUTCFullYear(year, month - 1, day);
  d.setUTCHours(hour, minute, second, 0);
  // A date component out of range rolls over (2026-02-31 becomes
  // March 3rd) instead of failing. Reject it, so an impossible date is
  // unreadable rather than silently drawn a few days off.
  if (d.getUTCFullYear() !== year
      || d.getUTCMonth() !== month - 1
      || d.getUTCDate() !== day) {
    return null;
  }
  return d.getTime() / 1000 - offsetSeconds(m[7]);
}

function offsetSeconds(raw: string | undefined): number {
  if (!raw || raw.toUpperCase() === 'Z') return 0;
  const sign = raw.startsWith('-') ? -1 : 1;
  const digits = raw.slice(1).replace(':', '');
  const hours = Number(digits.slice(0, 2));
  const minutes = Number(digits.slice(2, 4));
  if (!Number.isFinite(hours) || !Number.isFinite(minutes)) return 0;
  return sign * (hours * 3600 + minutes * 60);
}
