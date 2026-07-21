// Codec for `kind: diagram` documents — parses an on-disk body
// (markdown, JSON or YAML) into a typed DiagramDocument and serializes
// it back. The diagram source is an opaque string in a renderer DSL
// (Mermaid in v1); the codec does not parse it.
//
// Markdown is the canonical form: YAML front-matter + one fenced code
// block with the dialect as info string. Text before the fence lands
// in `extra._preamble`, after in `extra._postamble`, additional fences
// in `extra._unparsedBody`. All three round-trip verbatim.
//
// See `specification/doc-kind-diagram.md` for the schema. Mirrors the
// server-side `DiagramCodec` in `vance-shared`.
//
// Parity harness: this codec and its Java twin
// `server/vance-shared/src/main/java/de/mhus/vance/shared/document/kind/DiagramCodec.java` must agree on the
// wire format. A shared fixture corpus at
// `test-fixtures/kind-codecs/diagram/` pins that agreement; it is read
// by both `diagramCodec.parity.test.ts` (TS) and
// `DiagramCodecParityTest.java` (Java). Edit the codec + corpus together.

import yaml from 'js-yaml';
import {
  dumpYamlBody,
  parseYamlBody,
  unwrapJsonMeta,
  wrapJsonMeta,
} from '@vance/shared';

export type DiagramTheme = 'default' | 'dark' | 'forest' | 'neutral' | 'base';
export type DiagramLook = 'classic' | 'handDrawn';

const THEMES: ReadonlySet<DiagramTheme> = new Set([
  'default', 'dark', 'forest', 'neutral', 'base',
]);
const LOOKS: ReadonlySet<DiagramLook> = new Set(['classic', 'handDrawn']);

export const DEFAULT_DIALECT = 'mermaid';

export interface DiagramHeader {
  /** Mermaid built-in theme name. Default `'default'`. */
  theme: DiagramTheme;
  /** Render look — `'classic'` SVG strokes or `'handDrawn'` (Mermaid 10+). */
  look: DiagramLook;
  /** Optional font-family override; falls back to Mermaid's default. */
  fontFamily?: string;
  /** Unknown header fields, re-emitted on save. */
  extra: Record<string, unknown>;
}

export interface DiagramDocument {
  /** Always `'diagram'`. */
  kind: string;
  /** Source dialect. Default `'mermaid'`; unknown values round-trip
   *  but fall back to the raw editor on the client. */
  dialect: string;
  diagram: DiagramHeader;
  /** Opaque diagram source. May be empty for a freshly created doc. */
  source: string;
  /** Unknown top-level fields plus the reserved markdown-roundtrip
   *  keys (`_preamble`, `_postamble`, `_unparsedBody`). */
  extra: Record<string, unknown>;
}

export class DiagramCodecError extends Error {
  constructor(message: string, public override readonly cause?: unknown) {
    super(message);
    this.name = 'DiagramCodecError';
  }
}

/** Reserved keys in {@link DiagramDocument#extra} carrying the
 *  markdown text fragments around the source fence. */
export const EXTRA_PREAMBLE = '_preamble';
export const EXTRA_POSTAMBLE = '_postamble';
export const EXTRA_UNPARSED_BODY = '_unparsedBody';

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

export function parseDiagram(body: string, mimeType: string): DiagramDocument {
  if (isMarkdown(mimeType)) return parseDiagramMarkdown(body);
  if (isJson(mimeType)) return parseDiagramJson(body);
  if (isYaml(mimeType)) return parseDiagramYaml(body);
  throw new DiagramCodecError(`Unsupported mime type for diagram: ${mimeType}`);
}

export function serializeDiagram(doc: DiagramDocument, mimeType: string): string {
  if (isMarkdown(mimeType)) return serializeDiagramMarkdown(doc);
  if (isJson(mimeType)) return serializeDiagramJson(doc);
  if (isYaml(mimeType)) return serializeDiagramYaml(doc);
  throw new DiagramCodecError(`Unsupported mime type for diagram: ${mimeType}`);
}

/** Drives the Diagram tab activation in the document editor. */
export function isDiagramMime(mimeType: string | null | undefined): boolean {
  if (!mimeType) return false;
  return isMarkdown(mimeType) || isJson(mimeType) || isYaml(mimeType);
}

export function emptyDoc(): DiagramDocument {
  return {
    kind: 'diagram',
    dialect: DEFAULT_DIALECT,
    diagram: defaultHeader(),
    source: '',
    extra: {},
  };
}

function defaultHeader(): DiagramHeader {
  return { theme: 'default', look: 'classic', extra: {} };
}

function isHeaderDefault(h: DiagramHeader): boolean {
  return h.theme === 'default'
    && h.look === 'classic'
    && h.fontFamily === undefined
    && Object.keys(h.extra).length === 0;
}

// ── Markdown ─────────────────────────────────────────────────────────

const FM_FENCE = '---';
const CODE_FENCE = /^(`{3,}|~{3,})\s*([A-Za-z0-9_-]*)\s*$/;

function parseDiagramMarkdown(body: string): DiagramDocument {
  if (body.trim() === '') return emptyDoc();

  const lines = body.split(/\r?\n/);
  let cursor = 0;

  // Front-matter — parse as YAML so the nested `diagram:` block lifts
  // cleanly. Lenient on parse failure: empty map, no throw.
  let frontMatter: Record<string, unknown> = {};
  if (lines[0]?.trim() === FM_FENCE) {
    let end = -1;
    for (let i = 1; i < lines.length; i++) {
      if (lines[i].trim() === FM_FENCE) { end = i; break; }
    }
    if (end > 0) {
      const fmText = lines.slice(1, end).join('\n');
      try {
        const parsed = yaml.load(fmText, { schema: yaml.JSON_SCHEMA });
        if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
          frontMatter = parsed as Record<string, unknown>;
        }
      } catch {
        // Broken front-matter → ignore; the source fence is still
        // discoverable in the body.
      }
      cursor = end + 1;
    }
  }

  const kind = typeof frontMatter.kind === 'string' && frontMatter.kind
    ? frontMatter.kind
    : 'diagram';
  const dialect = typeof frontMatter.dialect === 'string' && frontMatter.dialect
    ? frontMatter.dialect
    : DEFAULT_DIALECT;
  const header = promoteHeader(frontMatter.diagram);

  const scan = scanFences(lines, cursor, dialect);

  // Pass-through for unknown front-matter keys.
  const extra: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(frontMatter)) {
    if (k === 'kind' || k === 'dialect' || k === 'diagram') continue;
    extra[k] = v;
  }
  if (scan.preamble) extra[EXTRA_PREAMBLE] = scan.preamble;
  if (scan.postamble) extra[EXTRA_POSTAMBLE] = scan.postamble;
  if (scan.unparsedBody) extra[EXTRA_UNPARSED_BODY] = scan.unparsedBody;

  return {
    kind,
    dialect,
    diagram: header,
    source: scan.source,
    extra,
  };
}

interface FenceScan {
  preamble: string;
  source: string;
  postamble: string;
  unparsedBody: string;
}

function scanFences(lines: string[], from: number, dialect: string): FenceScan {
  let state: 0 | 1 | 2 = 0; // 0: pre, 1: inside-source, 2: post
  let openMark = '';
  const preamble: string[] = [];
  const source: string[] = [];
  const postamble: string[] = [];
  const unparsed: string[] = [];

  for (let i = from; i < lines.length; i++) {
    const line = lines[i];
    if (state === 0) {
      const m = CODE_FENCE.exec(line);
      if (m) {
        const info = m[2];
        if (!info || info === dialect) {
          // Empty info string is accepted as the dialect fence — LLMs
          // forget the info string often, and a single info-less fence
          // is almost certainly the diagram.
          state = 1;
          openMark = m[1];
          continue;
        }
      }
      preamble.push(line);
    } else if (state === 1) {
      if (line.startsWith(openMark) && line.slice(openMark.length).trim() === '') {
        state = 2;
        openMark = '';
        continue;
      }
      source.push(line);
    } else {
      const m = CODE_FENCE.exec(line);
      if (m) {
        // Additional fence: capture it (including content + closing)
        // verbatim into unparsedBody.
        const mark = m[1];
        let closeIdx = -1;
        for (let j = i + 1; j < lines.length; j++) {
          const l2 = lines[j];
          if (l2.startsWith(mark) && l2.slice(mark.length).trim() === '') {
            closeIdx = j;
            break;
          }
        }
        if (closeIdx < 0) closeIdx = lines.length - 1;
        for (let j = i; j <= closeIdx; j++) unparsed.push(lines[j]);
        i = closeIdx;
        continue;
      }
      postamble.push(line);
    }
  }

  return {
    preamble: trimSurroundingBlankLines(preamble.join('\n')),
    source: source.join('\n'),
    postamble: trimSurroundingBlankLines(postamble.join('\n')),
    unparsedBody: unparsed.join('\n'),
  };
}

function trimSurroundingBlankLines(s: string): string {
  return s.replace(/^(?:\s*\n)+/, '').replace(/(?:\n\s*)+$/, '');
}

function serializeDiagramMarkdown(doc: DiagramDocument): string {
  const out: string[] = [];

  // Front-matter — always kind; dialect + diagram only when non-default.
  out.push(FM_FENCE);
  out.push(`kind: ${doc.kind || 'diagram'}`);
  if (doc.dialect && doc.dialect !== DEFAULT_DIALECT) {
    out.push(`dialect: ${doc.dialect}`);
  }
  if (!isHeaderDefault(doc.diagram)) {
    const headerObj = headerToObject(doc.diagram);
    // Render as YAML so the nested `diagram:` block stays valid.
    const dumped = yaml.dump({ diagram: headerObj }, {
      indent: 2,
      lineWidth: 100,
      noRefs: true,
    }).trimEnd();
    out.push(dumped);
  }
  // Pass-through front-matter scalars from extra (non-reserved keys).
  for (const [k, v] of Object.entries(doc.extra)) {
    if (k === EXTRA_PREAMBLE || k === EXTRA_POSTAMBLE || k === EXTRA_UNPARSED_BODY) continue;
    if (typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean') {
      out.push(`${k}: ${stringifyScalar(v)}`);
    } else {
      const dumped = yaml.dump({ [k]: v }, { indent: 2, lineWidth: 100, noRefs: true }).trimEnd();
      out.push(dumped);
    }
  }
  out.push(FM_FENCE);

  const preamble = typeof doc.extra[EXTRA_PREAMBLE] === 'string'
    ? (doc.extra[EXTRA_PREAMBLE] as string) : '';
  const postamble = typeof doc.extra[EXTRA_POSTAMBLE] === 'string'
    ? (doc.extra[EXTRA_POSTAMBLE] as string) : '';
  const unparsed = typeof doc.extra[EXTRA_UNPARSED_BODY] === 'string'
    ? (doc.extra[EXTRA_UNPARSED_BODY] as string) : '';

  if (preamble) {
    out.push('');
    out.push(preamble);
  }

  out.push('');
  out.push('```' + (doc.dialect || DEFAULT_DIALECT));
  // Source already may or may not end with a newline; strip and let
  // the fence-close add its own line break.
  out.push(doc.source.replace(/\n$/, ''));
  out.push('```');

  if (postamble) {
    out.push('');
    out.push(postamble);
  }
  if (unparsed) {
    out.push('');
    out.push(unparsed);
  }

  return out.join('\n') + '\n';
}

function stringifyScalar(value: unknown): string {
  if (value == null) return '';
  if (typeof value === 'string') {
    if (/[:#\n"]/.test(value) || /^\s|\s$/.test(value)) {
      return JSON.stringify(value);
    }
    return value;
  }
  return String(value);
}

// ── JSON ─────────────────────────────────────────────────────────────

function parseDiagramJson(body: string): DiagramDocument {
  if (body.trim() === '') return emptyDoc();
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch (e) {
    throw new DiagramCodecError(
      'Invalid JSON: ' + (e instanceof Error ? e.message : String(e)),
      e,
    );
  }
  if (!isObject(parsed)) {
    throw new DiagramCodecError('Top-level JSON must be an object');
  }
  return promoteToDocument(unwrapJsonMeta(parsed));
}

function serializeDiagramJson(doc: DiagramDocument): string {
  return JSON.stringify(
    wrapJsonMeta(doc.kind || 'diagram', buildStructuredBody(doc)),
    null, 2,
  ) + '\n';
}

// ── YAML ─────────────────────────────────────────────────────────────

function parseDiagramYaml(body: string): DiagramDocument {
  if (body.trim() === '') return emptyDoc();
  let merged: Record<string, unknown>;
  try {
    merged = parseYamlBody(body);
  } catch (e) {
    throw new DiagramCodecError(
      'Invalid YAML: ' + (e instanceof Error ? e.message : String(e)),
      e,
    );
  }
  return promoteToDocument(merged);
}

function serializeDiagramYaml(doc: DiagramDocument): string {
  return dumpYamlBody(doc.kind || 'diagram', buildStructuredBody(doc));
}

// ── Shared promotion ─────────────────────────────────────────────────

function promoteToDocument(obj: Record<string, unknown>): DiagramDocument {
  const kind = typeof obj.kind === 'string' && obj.kind ? obj.kind : 'diagram';
  const dialect = typeof obj.dialect === 'string' && obj.dialect
    ? obj.dialect : DEFAULT_DIALECT;
  const diagram = promoteHeader(obj.diagram);
  const source = typeof obj.source === 'string' ? obj.source : '';

  const extra: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(obj)) {
    if (k === 'kind' || k === 'dialect' || k === 'diagram' || k === 'source') continue;
    extra[k] = v;
  }

  return { kind, dialect, diagram, source, extra };
}

function promoteHeader(raw: unknown): DiagramHeader {
  if (!isObject(raw)) return defaultHeader();
  let theme: DiagramTheme = 'default';
  if (typeof raw.theme === 'string' && THEMES.has(raw.theme as DiagramTheme)) {
    theme = raw.theme as DiagramTheme;
  }
  let look: DiagramLook = 'classic';
  if (typeof raw.look === 'string' && LOOKS.has(raw.look as DiagramLook)) {
    look = raw.look as DiagramLook;
  }
  const fontFamily = typeof raw.fontFamily === 'string' && raw.fontFamily
    ? raw.fontFamily : undefined;

  const extra: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(raw)) {
    if (k === 'theme' || k === 'look' || k === 'fontFamily') continue;
    extra[k] = v;
  }
  return { theme, look, fontFamily, extra };
}

function buildStructuredBody(doc: DiagramDocument): Record<string, unknown> {
  const body: Record<string, unknown> = {};
  if (doc.dialect && doc.dialect !== DEFAULT_DIALECT) body.dialect = doc.dialect;
  if (!isHeaderDefault(doc.diagram)) body.diagram = headerToObject(doc.diagram);
  body.source = doc.source;
  for (const [k, v] of Object.entries(doc.extra)) {
    if (!(k in body)) body[k] = v;
  }
  return body;
}

function headerToObject(h: DiagramHeader): Record<string, unknown> {
  const o: Record<string, unknown> = {};
  if (h.theme !== 'default') o.theme = h.theme;
  if (h.look !== 'classic') o.look = h.look;
  if (h.fontFamily !== undefined) o.fontFamily = h.fontFamily;
  for (const [k, v] of Object.entries(h.extra)) {
    if (!(k in o)) o[k] = v;
  }
  return o;
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}
