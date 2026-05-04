// Codec for `kind: tree` documents — parses an on-disk body
// (markdown, JSON or YAML) into a typed TreeDocument and serializes
// it back. Mirrors the design of {@link ./listItemsCodec} but adds
// recursive `children` per item.
//
// See `specification/doc-kind-tree.md` for the schema and the
// markdown indent-nesting rules.

import {
  dumpYamlMultiDoc,
  mergeYamlMultiDoc,
  unwrapJsonMeta,
  wrapJsonMeta,
} from './documentHeaderCodec';

/** A tree item. Extra fields are preserved across round-trip for
 *  json/yaml; markdown can only carry `text` + nesting. */
export interface TreeItem {
  text: string;
  children: TreeItem[];
  /** Unknown fields the editor doesn't recognise. Re-emitted on save. */
  extra: Record<string, unknown>;
}

export interface TreeDocument {
  /** Always `'tree'` for tree documents — kept generic for symmetry
   *  with the list codec. */
  kind: string;
  items: TreeItem[];
  /** Unknown top-level fields (json/yaml). For markdown this is the
   *  residual front-matter map keyed by field name → raw value. */
  extra: Record<string, unknown>;
}

export class TreeCodecError extends Error {
  constructor(message: string, public override readonly cause?: unknown) {
    super(message);
    this.name = 'TreeCodecError';
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

export function parseTree(body: string, mimeType: string): TreeDocument {
  if (isMarkdown(mimeType)) return parseTreeMarkdown(body);
  if (isJson(mimeType)) return parseTreeJson(body);
  if (isYaml(mimeType)) return parseTreeYaml(body);
  throw new TreeCodecError(`Unsupported mime type for tree: ${mimeType}`);
}

export function serializeTree(doc: TreeDocument, mimeType: string): string {
  if (isMarkdown(mimeType)) return serializeTreeMarkdown(doc);
  if (isJson(mimeType)) return serializeTreeJson(doc);
  if (isYaml(mimeType)) return serializeTreeYaml(doc);
  throw new TreeCodecError(`Unsupported mime type for tree: ${mimeType}`);
}

/** Whether the codec can handle this mime type. */
export function isTreeMime(mimeType: string | null | undefined): boolean {
  if (!mimeType) return false;
  return isMarkdown(mimeType) || isJson(mimeType) || isYaml(mimeType);
}

// ── Markdown ─────────────────────────────────────────────────────────

const MD_FENCE = '---';

/**
 * Parse a markdown body with optional front-matter and a nested bullet
 * list. Indent depth is computed from leading whitespace (tabs = 4
 * spaces); each indent unit of 2 spaces is one nesting level. Items
 * deeper than {@code lastDepth + 1} are clamped to that — no
 * skip-level nodes.
 */
function parseTreeMarkdown(body: string): TreeDocument {
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
    if (cursor < lines.length && lines[cursor].trim() === MD_FENCE) cursor++;
  }

  // Body — build tree via an open-levels stack. The stack always has
  // at least one entry: the root list. Every bullet-line resolves to
  // a depth and pushes its own children-array onto the stack.
  const root: TreeItem[] = [];
  interface OpenLevel { depth: number; list: TreeItem[]; }
  const openLevels: OpenLevel[] = [{ depth: -1, list: root }];
  let lastItem: TreeItem | null = null;
  let lastDepth = -1;

  for (let i = cursor; i < lines.length; i++) {
    const raw = lines[i];
    if (raw.trim() === '') {
      lastItem = null;
      continue;
    }
    const bullet = raw.match(/^(\s*)[-*]\s+(.*)$/);
    if (bullet) {
      const indent = countIndent(bullet[1]);
      let depth = Math.floor(indent / 2);
      // Spec §3.1: clamp to lastDepth + 1 — no skip-level nodes.
      if (depth > lastDepth + 1) depth = lastDepth + 1;
      if (depth < 0) depth = 0;

      // Pop levels until we land at parent depth (depth-1).
      while (openLevels.length > 1
             && openLevels[openLevels.length - 1].depth >= depth) {
        openLevels.pop();
      }
      const parent = openLevels[openLevels.length - 1].list;
      const item: TreeItem = { text: bullet[2], children: [], extra: {} };
      parent.push(item);
      openLevels.push({ depth, list: item.children });
      lastItem = item;
      lastDepth = depth;
      continue;
    }
    // Continuation — line has leading whitespace and is not a bullet.
    if (lastItem && /^\s+\S/.test(raw)) {
      const indent = countIndent(raw.match(/^(\s*)/)?.[1] ?? '');
      // Continuation requires indent > lastDepth*2 + 1 (i.e. at least
      // 2 more spaces than the bullet line's indent).
      if (indent >= (lastDepth + 1) * 2) {
        const stripped = raw.replace(/^\s+/, '');
        lastItem.text += '\n' + stripped;
      }
    }
    // Anything else (free-form text outside the list) is dropped.
  }

  return { kind, items: root, extra };
}

function serializeTreeMarkdown(doc: TreeDocument): string {
  const out: string[] = [];
  out.push(MD_FENCE);
  out.push(`kind: ${doc.kind || 'tree'}`);
  for (const [key, value] of Object.entries(doc.extra)) {
    out.push(`${key}: ${stringifyMdExtra(value)}`);
  }
  out.push(MD_FENCE);
  emitMdItems(doc.items, 0, out);
  return out.join('\n') + '\n';
}

function emitMdItems(items: TreeItem[], depth: number, out: string[]): void {
  const indent = '  '.repeat(depth);
  for (const item of items) {
    const lines = item.text.split('\n');
    out.push(`${indent}- ${lines[0] ?? ''}`);
    for (let i = 1; i < lines.length; i++) {
      out.push(`${indent}  ${lines[i]}`);
    }
    if (item.children.length > 0) {
      emitMdItems(item.children, depth + 1, out);
    }
  }
}

function stringifyMdExtra(value: unknown): string {
  if (value == null) return '';
  if (typeof value === 'string') return value;
  return String(value);
}

/** Tabs count as 4 spaces; spaces count as themselves. Anything else
 *  is treated as 0 (defensive). */
function countIndent(prefix: string): number {
  let n = 0;
  for (const c of prefix) {
    if (c === ' ') n++;
    else if (c === '\t') n += 4;
  }
  return n;
}

// ── JSON ─────────────────────────────────────────────────────────────

function parseTreeJson(body: string): TreeDocument {
  if (body.trim() === '') {
    return { kind: 'tree', items: [], extra: {} };
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch (e) {
    throw new TreeCodecError('Invalid JSON: ' + (e instanceof Error ? e.message : String(e)), e);
  }
  if (!isObject(parsed)) {
    throw new TreeCodecError('Top-level JSON must be an object');
  }
  return promoteToTreeDocument(unwrapJsonMeta(parsed));
}

function serializeTreeJson(doc: TreeDocument): string {
  return JSON.stringify(wrapJsonMeta(doc.kind || 'tree', {
    items: doc.items.map(itemToObject),
    ...doc.extra,
  }), null, 2) + '\n';
}

// ── YAML ─────────────────────────────────────────────────────────────

function parseTreeYaml(body: string): TreeDocument {
  if (body.trim() === '') {
    return { kind: 'tree', items: [], extra: {} };
  }
  let merged: Record<string, unknown>;
  try {
    merged = mergeYamlMultiDoc(body);
  } catch (e) {
    throw new TreeCodecError('Invalid YAML: ' + (e instanceof Error ? e.message : String(e)), e);
  }
  return promoteToTreeDocument(merged);
}

function serializeTreeYaml(doc: TreeDocument): string {
  return dumpYamlMultiDoc(doc.kind || 'tree', {
    items: doc.items.map(itemToObject),
    ...doc.extra,
  });
}

// ── Shared promotion logic (json + yaml share the object shape) ─────

function promoteToTreeDocument(obj: Record<string, unknown>): TreeDocument {
  const kind = typeof obj.kind === 'string' ? obj.kind : '';
  const items = promoteItems(obj.items);
  const { kind: _k, items: _i, ...extra } = obj;
  return { kind, items, extra };
}

function promoteItems(raw: unknown): TreeItem[] {
  if (!Array.isArray(raw)) return [];
  const out: TreeItem[] = [];
  for (const r of raw) {
    if (!isObject(r)) continue; // String-shorthand not allowed for tree (spec §3.3)
    const text = typeof r.text === 'string' ? r.text : '';
    const children = promoteItems(r.children);
    const { text: _t, children: _c, ...extra } = r;
    out.push({ text, children, extra });
  }
  return out;
}

function itemToObject(item: TreeItem): Record<string, unknown> {
  return {
    text: item.text,
    children: item.children.map(itemToObject),
    ...item.extra,
  };
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
