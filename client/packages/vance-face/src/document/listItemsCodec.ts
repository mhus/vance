// Codec for `kind: list` documents — parses an on-disk body (markdown,
// JSON or YAML) into a typed ListDocument and serializes it back.
//
// Lives client-side only. The server stores the document body as-is;
// the editor parses on view, serializes on save. Round-trip preserves
// unknown top-level keys and unknown per-item fields where the wire
// format supports them (json/yaml). Markdown can only express the
// `text` field per item — extra fields would be lost on save, so the
// editor refuses to round-trip a markdown body whose items carry
// extra fields (see `parseListMarkdown` notes).
//
// See `specification/doc-kind-items.md` for the schema.

import {
  dumpYamlMultiDoc,
  mergeYamlMultiDoc,
  unwrapJsonMeta,
  wrapJsonMeta,
} from './documentHeaderCodec';

/** A single list item. Extra fields are preserved across round-trip
 *  for json/yaml; markdown can only carry `text`. */
export interface ListItem {
  text: string;
  /** Unknown fields the editor doesn't recognise. Re-emitted on save. */
  extra: Record<string, unknown>;
}

export interface ListDocument {
  /** Always `'list'` for list documents — kept generic so future
   *  related kinds can reuse the codec shape. */
  kind: string;
  items: ListItem[];
  /** Unknown top-level fields (json/yaml only). For markdown this is
   *  the residual front-matter map keyed by field name → raw value. */
  extra: Record<string, unknown>;
}

export class ListCodecError extends Error {
  constructor(message: string, public override readonly cause?: unknown) {
    super(message);
    this.name = 'ListCodecError';
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

export function parseList(body: string, mimeType: string): ListDocument {
  if (isMarkdown(mimeType)) return parseListMarkdown(body);
  if (isJson(mimeType)) return parseListJson(body);
  if (isYaml(mimeType)) return parseListYaml(body);
  throw new ListCodecError(`Unsupported mime type for list: ${mimeType}`);
}

export function serializeList(doc: ListDocument, mimeType: string): string {
  if (isMarkdown(mimeType)) return serializeListMarkdown(doc);
  if (isJson(mimeType)) return serializeListJson(doc);
  if (isYaml(mimeType)) return serializeListYaml(doc);
  throw new ListCodecError(`Unsupported mime type for list: ${mimeType}`);
}

/** Whether the codec can handle this mime type — used by the editor
 *  to decide whether to offer the List tab at all. */
export function isListMime(mimeType: string | null | undefined): boolean {
  if (!mimeType) return false;
  return isMarkdown(mimeType) || isJson(mimeType) || isYaml(mimeType);
}

// ── Markdown ─────────────────────────────────────────────────────────

const MD_FENCE = '---';

/** Parse a markdown body with optional `---`-fenced front-matter and a
 *  flat bullet list. Continuation lines (≥2 spaces indent that doesn't
 *  itself start a bullet) are appended to the previous item's text. */
function parseListMarkdown(body: string): ListDocument {
  const lines = body.split(/\r?\n/);
  let cursor = 0;

  // Front matter
  const extra: Record<string, unknown> = {};
  let kind = '';
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
      } else {
        extra[key] = value;
      }
    }
    // Skip the closing fence
    if (cursor < lines.length && lines[cursor].trim() === MD_FENCE) cursor++;
  }

  // Bullet list
  const items: ListItem[] = [];
  let current: ListItem | null = null;
  for (let i = cursor; i < lines.length; i++) {
    const raw = lines[i];
    if (raw.trim() === '') {
      // Blank line ends a continuation block; new bullet starts a new item.
      current = null;
      continue;
    }
    const bullet = raw.match(/^\s*[-*]\s+(.*)$/);
    if (bullet) {
      current = { text: bullet[1], extra: {} };
      items.push(current);
      continue;
    }
    // Continuation: ≥2 leading spaces, append to previous item's text.
    if (current && /^\s{2,}/.test(raw)) {
      current.text += '\n' + raw.replace(/^\s{2,}/, '');
      continue;
    }
    // Anything else (text outside the list area, headings, paragraphs)
    // is ignored. The list view cannot represent it, but the round-trip
    // is preserved by the front-matter passthrough — non-bullet body
    // content is dropped. v1 limitation; document accordingly.
  }

  return { kind, items, extra };
}

function serializeListMarkdown(doc: ListDocument): string {
  const out: string[] = [];
  out.push(MD_FENCE);
  out.push(`kind: ${doc.kind || 'list'}`);
  for (const [key, value] of Object.entries(doc.extra)) {
    out.push(`${key}: ${stringifyMdExtra(value)}`);
  }
  out.push(MD_FENCE);
  for (const item of doc.items) {
    const lines = item.text.split('\n');
    out.push(`- ${lines[0] ?? ''}`);
    for (let i = 1; i < lines.length; i++) {
      out.push(`  ${lines[i]}`);
    }
  }
  // Trailing newline so editors don't show "no newline at EOF".
  return out.join('\n') + '\n';
}

function stringifyMdExtra(value: unknown): string {
  if (value == null) return '';
  if (typeof value === 'string') return value;
  return String(value);
}

// ── JSON ─────────────────────────────────────────────────────────────

function parseListJson(body: string): ListDocument {
  if (body.trim() === '') {
    return { kind: 'list', items: [], extra: {} };
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch (e) {
    throw new ListCodecError('Invalid JSON: ' + (e instanceof Error ? e.message : String(e)), e);
  }
  if (!isObject(parsed)) {
    throw new ListCodecError('Top-level JSON must be an object');
  }
  return promoteToListDocument(unwrapJsonMeta(parsed));
}

function serializeListJson(doc: ListDocument): string {
  return JSON.stringify(wrapJsonMeta(doc.kind || 'list', {
    items: doc.items.map(itemToObject),
    ...doc.extra,
  }), null, 2) + '\n';
}

// ── YAML ─────────────────────────────────────────────────────────────

function parseListYaml(body: string): ListDocument {
  if (body.trim() === '') {
    return { kind: 'list', items: [], extra: {} };
  }
  let merged: Record<string, unknown>;
  try {
    merged = mergeYamlMultiDoc(body);
  } catch (e) {
    throw new ListCodecError('Invalid YAML: ' + (e instanceof Error ? e.message : String(e)), e);
  }
  return promoteToListDocument(merged);
}

function serializeListYaml(doc: ListDocument): string {
  return dumpYamlMultiDoc(doc.kind || 'list', {
    items: doc.items.map(itemToObject),
    ...doc.extra,
  });
}

// ── Shared promotion logic (json + yaml share their object shape) ───

/** Lift a parsed JSON/YAML object into the typed ListDocument shape.
 *  Accepts both the canonical object form (`items[]` of objects) and
 *  the shorthand string-array form. Unknown fields go into `extra`. */
function promoteToListDocument(obj: Record<string, unknown>): ListDocument {
  const kind = typeof obj.kind === 'string' ? obj.kind : '';
  const itemsRaw = obj.items;
  const items: ListItem[] = [];
  if (Array.isArray(itemsRaw)) {
    for (const raw of itemsRaw) {
      if (typeof raw === 'string') {
        items.push({ text: raw, extra: {} });
      } else if (isObject(raw)) {
        const text = typeof raw.text === 'string' ? raw.text : '';
        const { text: _omit, ...rest } = raw;
        items.push({ text, extra: rest });
      }
      // Other shapes (numbers, arrays) are silently dropped. v1 limit.
    }
  }
  // Top-level `extra` is everything that isn't `kind` or `items`.
  const { kind: _k, items: _i, ...extra } = obj;
  return { kind, items, extra };
}

function itemToObject(item: ListItem): Record<string, unknown> {
  return { text: item.text, ...item.extra };
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
