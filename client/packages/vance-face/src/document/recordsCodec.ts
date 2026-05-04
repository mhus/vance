// Codec for `kind: records` documents — parses an on-disk body
// (markdown, JSON or YAML) into a typed RecordsDocument and serializes
// it back. Direct extension of {@link ./listItemsCodec}: same flat
// shape, same Bullet-pro-Item-Form in markdown, but each item carries
// values for every schema field instead of a single `text` string.
//
// See `specification/doc-kind-records.md` for the schema, the
// schema-declaration semantics, the resilience rules (missing field
// → empty), and the markdown CSV-light grammar.

import yaml from 'js-yaml';

/** A single record. `values` holds the schema-field-keyed strings.
 *  `extra` keeps unknown json/yaml keys for round-trip; `overflow`
 *  preserves markdown values that exceeded the schema length. */
export interface RecordsItem {
  values: Record<string, string>;
  extra: Record<string, unknown>;
  overflow: string[];
}

export interface RecordsDocument {
  /** Always `'records'` for records documents — kept generic for
   *  symmetry with the list/tree codecs. */
  kind: string;
  /** Ordered, deduped list of field names. Drives every record's
   *  shape and the editor's column order. Required and non-empty. */
  schema: string[];
  items: RecordsItem[];
  /** Unknown top-level fields (json/yaml) or extra front-matter keys
   *  (markdown). Re-emitted verbatim on save. */
  extra: Record<string, unknown>;
}

export class RecordsCodecError extends Error {
  constructor(message: string, public override readonly cause?: unknown) {
    super(message);
    this.name = 'RecordsCodecError';
  }
}

// ── MIME helpers ─────────────────────────────────────────────────────

function isMarkdown(mime: string): boolean {
  return mime === 'text/markdown' || mime === 'text/x-markdown';
}
function isJson(mime: string): boolean {
  return mime === 'application/json';
}
function isYaml(mime: string): boolean {
  return mime === 'application/yaml'
    || mime === 'application/x-yaml'
    || mime === 'text/yaml'
    || mime === 'text/x-yaml';
}

// ── Public API ───────────────────────────────────────────────────────

export function parseRecords(body: string, mimeType: string): RecordsDocument {
  if (isMarkdown(mimeType)) return parseRecordsMarkdown(body);
  if (isJson(mimeType)) return parseRecordsJson(body);
  if (isYaml(mimeType)) return parseRecordsYaml(body);
  throw new RecordsCodecError(`Unsupported mime type for records: ${mimeType}`);
}

export function serializeRecords(doc: RecordsDocument, mimeType: string): string {
  if (isMarkdown(mimeType)) return serializeRecordsMarkdown(doc);
  if (isJson(mimeType)) return serializeRecordsJson(doc);
  if (isYaml(mimeType)) return serializeRecordsYaml(doc);
  throw new RecordsCodecError(`Unsupported mime type for records: ${mimeType}`);
}

/** Whether the codec can handle this mime type — used by the editor
 *  to decide whether to offer the Records tab at all. */
export function isRecordsMime(mimeType: string | null | undefined): boolean {
  if (!mimeType) return false;
  return isMarkdown(mimeType) || isJson(mimeType) || isYaml(mimeType);
}

/** Build an empty record matching the document's schema — used by the
 *  editor when the user adds a new row. Every schema field gets the
 *  empty string; `extra` and `overflow` start empty. */
export function emptyRecord(schema: string[]): RecordsItem {
  const values: Record<string, string> = {};
  for (const field of schema) values[field] = '';
  return { values, extra: {}, overflow: [] };
}

// ── Markdown ─────────────────────────────────────────────────────────

const MD_FENCE = '---';

function parseRecordsMarkdown(body: string): RecordsDocument {
  const lines = body.split(/\r?\n/);
  let cursor = 0;

  const extra: Record<string, unknown> = {};
  let kind = '';
  let schemaRaw = '';

  if (lines[0]?.trim() === MD_FENCE) {
    cursor = 1;
    while (cursor < lines.length && lines[cursor].trim() !== MD_FENCE) {
      const line = lines[cursor].trim();
      cursor++;
      if (!line || line.startsWith('#')) continue;
      const colon = line.indexOf(':');
      if (colon <= 0) continue;
      const key = line.slice(0, colon).trim();
      const value = line.slice(colon + 1).trim();
      if (key === 'kind') {
        kind = value;
      } else if (key === 'schema') {
        schemaRaw = value;
      } else {
        extra[key] = value;
      }
    }
    if (cursor < lines.length && lines[cursor].trim() === MD_FENCE) cursor++;
  }

  const schema = parseSchemaCsv(schemaRaw);
  if (schema.length === 0) {
    throw new RecordsCodecError(
      'Missing or empty schema in front-matter — `kind: records` requires `schema: field1, field2, ...`',
    );
  }

  const items: RecordsItem[] = [];
  for (let i = cursor; i < lines.length; i++) {
    const raw = lines[i];
    if (raw.trim() === '') continue;
    const bullet = raw.match(/^\s*[-*]\s+(.*)$/);
    if (!bullet) continue;
    items.push(rowFromCsvValues(parseCsvLine(bullet[1]), schema));
  }

  return { kind, schema, items, extra };
}

function serializeRecordsMarkdown(doc: RecordsDocument): string {
  if (doc.schema.length === 0) {
    throw new RecordsCodecError('Cannot serialise records without a schema');
  }
  const out: string[] = [];
  out.push(MD_FENCE);
  out.push(`kind: ${doc.kind || 'records'}`);
  out.push(`schema: ${doc.schema.join(', ')}`);
  for (const [key, value] of Object.entries(doc.extra)) {
    out.push(`${key}: ${stringifyMdExtra(value)}`);
  }
  out.push(MD_FENCE);
  for (const item of doc.items) {
    out.push(`- ${rowToCsv(item, doc.schema)}`);
  }
  return out.join('\n') + '\n';
}

/** Build a record from a list of positional CSV values. Surplus
 *  values land in {@code overflow}; missing fields default to ''. */
function rowFromCsvValues(values: string[], schema: string[]): RecordsItem {
  const out: Record<string, string> = {};
  for (let j = 0; j < schema.length; j++) {
    out[schema[j]] = values[j] ?? '';
  }
  const overflow = values.length > schema.length
    ? values.slice(schema.length)
    : [];
  return { values: out, extra: {}, overflow };
}

/** Render a record as a single comma-separated line. Markdown can't
 *  carry per-field {@code extra} (no syntax for it), so we emit the
 *  schema-field values plus the overflow tail. Throws when a value
 *  contains a newline — markdown CSV-light is single-line per
 *  bullet. */
function rowToCsv(item: RecordsItem, schema: string[]): string {
  const cells: string[] = [];
  for (const field of schema) {
    cells.push(encodeCsvValue(item.values[field] ?? '', field));
  }
  for (const v of item.overflow) {
    cells.push(encodeCsvValue(v, '<overflow>'));
  }
  return cells.join(', ');
}

function encodeCsvValue(value: string, fieldHint: string): string {
  if (value.includes('\n') || value.includes('\r')) {
    throw new RecordsCodecError(
      `Value for "${fieldHint}" contains a newline — markdown form is single-line per bullet. Use json or yaml instead.`,
    );
  }
  if (value === '') return '';
  const needsQuote = /[,"]/.test(value)
    || /^\s/.test(value)
    || /\s$/.test(value);
  if (!needsQuote) return value;
  return `"${value.replace(/"/g, '""')}"`;
}

/** CSV-light parser for the bullet body. Handles quoted values
 *  (`"…"`), embedded commas inside quotes, and `""` as a literal `"`
 *  inside a quoted run. Whitespace around comma separators is trimmed;
 *  whitespace inside quotes stays intact. */
function parseCsvLine(line: string): string[] {
  const out: string[] = [];
  let i = 0;
  const n = line.length;
  while (i < n) {
    // skip leading whitespace before a value
    while (i < n && line[i] === ' ') i++;
    if (i >= n) {
      out.push('');
      break;
    }
    if (line[i] === '"') {
      // quoted value
      i++;
      let buf = '';
      while (i < n) {
        const c = line[i];
        if (c === '"') {
          if (line[i + 1] === '"') {
            buf += '"';
            i += 2;
            continue;
          }
          i++;
          break;
        }
        buf += c;
        i++;
      }
      out.push(buf);
      // consume optional trailing whitespace and the next comma
      while (i < n && line[i] === ' ') i++;
      if (i < n && line[i] === ',') i++;
    } else {
      // unquoted value: take until next comma; trim trailing whitespace
      let buf = '';
      while (i < n && line[i] !== ',') {
        buf += line[i];
        i++;
      }
      if (i < n && line[i] === ',') i++;
      out.push(buf.trimEnd());
    }
  }
  // Handle a trailing comma "a, b, " → ['a', 'b', '']: if the line
  // ends with ',' the loop above pushed all values but missed the
  // empty tail. Detect by checking whether the last consumed char
  // was a comma without a following value.
  if (line.length > 0 && line[line.length - 1] === ',') {
    out.push('');
  }
  return out;
}

/** Parse the front-matter `schema:` value. Same CSV-light grammar
 *  as the records body, but result is deduped (first-wins, second
 *  occurrence silently dropped per spec §7) and stripped of empties. */
function parseSchemaCsv(raw: string): string[] {
  if (!raw.trim()) return [];
  const rawFields = parseCsvLine(raw).map((f) => f.trim());
  const out: string[] = [];
  const seen = new Set<string>();
  for (const f of rawFields) {
    if (!f) continue;
    if (seen.has(f)) continue;
    seen.add(f);
    out.push(f);
  }
  return out;
}

function stringifyMdExtra(value: unknown): string {
  if (value == null) return '';
  if (typeof value === 'string') return value;
  return String(value);
}

// ── JSON ─────────────────────────────────────────────────────────────

function parseRecordsJson(body: string): RecordsDocument {
  if (body.trim() === '') {
    throw new RecordsCodecError('Empty JSON body — `kind: records` requires a schema');
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch (e) {
    throw new RecordsCodecError('Invalid JSON: ' + (e instanceof Error ? e.message : String(e)), e);
  }
  if (!isObject(parsed)) {
    throw new RecordsCodecError('Top-level JSON must be an object');
  }
  return promoteToRecordsDocument(parsed);
}

function serializeRecordsJson(doc: RecordsDocument): string {
  if (doc.schema.length === 0) {
    throw new RecordsCodecError('Cannot serialise records without a schema');
  }
  const obj: Record<string, unknown> = {
    kind: doc.kind || 'records',
    schema: doc.schema,
    items: doc.items.map((item) => itemToObject(item, doc.schema)),
    ...doc.extra,
  };
  return JSON.stringify(obj, null, 2) + '\n';
}

// ── YAML ─────────────────────────────────────────────────────────────

function parseRecordsYaml(body: string): RecordsDocument {
  if (body.trim() === '') {
    throw new RecordsCodecError('Empty YAML body — `kind: records` requires a schema');
  }
  let parsed: unknown;
  try {
    parsed = yaml.load(body, { schema: yaml.JSON_SCHEMA });
  } catch (e) {
    throw new RecordsCodecError('Invalid YAML: ' + (e instanceof Error ? e.message : String(e)), e);
  }
  if (!isObject(parsed)) {
    throw new RecordsCodecError('Top-level YAML must be a mapping');
  }
  return promoteToRecordsDocument(parsed);
}

function serializeRecordsYaml(doc: RecordsDocument): string {
  if (doc.schema.length === 0) {
    throw new RecordsCodecError('Cannot serialise records without a schema');
  }
  const obj: Record<string, unknown> = {
    kind: doc.kind || 'records',
    schema: doc.schema,
    items: doc.items.map((item) => itemToObject(item, doc.schema)),
    ...doc.extra,
  };
  return yaml.dump(obj, { indent: 2, lineWidth: 100, noRefs: true });
}

// ── Shared promotion logic (json + yaml share the object shape) ─────

function promoteToRecordsDocument(obj: Record<string, unknown>): RecordsDocument {
  const kind = typeof obj.kind === 'string' ? obj.kind : '';
  const schema = promoteSchema(obj.schema);
  if (schema.length === 0) {
    throw new RecordsCodecError('Missing or empty schema — `kind: records` requires a `schema: [...]`');
  }
  const items = promoteItems(obj.items, schema);
  const { kind: _k, schema: _s, items: _i, ...extra } = obj;
  return { kind, schema, items, extra };
}

function promoteSchema(raw: unknown): string[] {
  if (Array.isArray(raw)) {
    const out: string[] = [];
    const seen = new Set<string>();
    for (const r of raw) {
      const f = typeof r === 'string' ? r.trim() : '';
      if (!f || seen.has(f)) continue;
      seen.add(f);
      out.push(f);
    }
    return out;
  }
  if (typeof raw === 'string') {
    return parseSchemaCsv(raw);
  }
  return [];
}

function promoteItems(raw: unknown, schema: string[]): RecordsItem[] {
  if (!Array.isArray(raw)) return [];
  const schemaSet = new Set(schema);
  const out: RecordsItem[] = [];
  for (const r of raw) {
    if (!isObject(r)) continue;
    const values: Record<string, string> = {};
    for (const f of schema) {
      values[f] = coerceValue(r[f]);
    }
    const extra: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(r)) {
      if (!schemaSet.has(k)) extra[k] = v;
    }
    out.push({ values, extra, overflow: [] });
  }
  return out;
}

function coerceValue(v: unknown): string {
  if (v == null) return '';
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  // Arrays / nested objects → best-effort JSON stringify so the
  // round-trip is at least visible. Editor surfaces this as an
  // opaque string; future field-types step (spec §6.2) will handle
  // structured values properly.
  try {
    return JSON.stringify(v);
  } catch {
    return String(v);
  }
}

function itemToObject(item: RecordsItem, schema: string[]): Record<string, unknown> {
  const obj: Record<string, unknown> = {};
  for (const f of schema) {
    obj[f] = item.values[f] ?? '';
  }
  for (const [k, v] of Object.entries(item.extra)) {
    if (!(k in obj)) obj[k] = v;
  }
  return obj;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
