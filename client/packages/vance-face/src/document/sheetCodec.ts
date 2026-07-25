// Codec for `kind: sheet` documents — sparse 2D-grid with A1 cell
// addresses (Excel-standard). JSON and YAML only; markdown is not
// supported (see `specification/doc-kind-sheet.md` §3.3).
//
// Cells are stored as a sparse list — only cells with content or
// formatting appear on disk. Formulas live as plain strings (lead
// with `=`) and round-trip stably; v1 does not evaluate them, see
// spec §6.1 for v2 client-eval and §6.2 for v3 server-eval.
//
// Parity harness: this codec and its Java twin
// `server/vance-shared/src/main/java/de/mhus/vance/shared/document/kind/SheetCodec.java` must agree on the
// wire format. A shared fixture corpus at
// `test-fixtures/kind-codecs/sheet/` pins that agreement; it is read
// by both `sheetCodec.parity.test.ts` (TS) and
// `SheetCodecParityTest.java` (Java). Edit the codec + corpus together.

import {
  dumpYamlBody,
  parseYamlBody,
  unwrapJsonMeta,
  wrapJsonMeta,
} from '@vance/shared';

export interface SheetCell {
  /** A1-Adresse, kanonisch uppercase (`A1`, `B5`, `AB99`). */
  field: string;
  /** Cell-Inhalt als String. Beginnt mit `=` für Formeln. */
  data: string;
  color?: string;
  background?: string;
  bold?: boolean;
  italic?: boolean;
  /** left | center | right */
  align?: string;
  /** Excel-style number format code, e.g. `#,##0.00`, `0%`, `@`. */
  numberFormat?: string;
  /** Cell border edges: a subset of `trbl` (top/right/bottom/left). */
  borders?: string;
  /** Unknown per-cell fields, preserved across round-trip. */
  extra: Record<string, unknown>;
}

/** Per-column metadata (width in px, vertical border). Sparse. */
export interface SheetColumn {
  width?: number;
  /** left | right | both */
  border?: string;
}

/** One evaluated cell in the `$computed` overlay (server-authoritative). */
export interface SheetComputedValue {
  field: string;
  value: string;
  /** number | text | boolean | date | error | empty */
  type: string;
  error?: string;
}

/** The derived `$computed` overlay: server-evaluated formula results. */
export interface SheetComputed {
  computedAt?: string;
  values: SheetComputedValue[];
}

export interface SheetDocument {
  kind: string;
  /** Geordnete Liste der angezeigten Spaltenbuchstaben. Optional —
   *  wenn weggelassen, leitet der Editor die Spalten aus den
   *  vorhandenen Cells ab. */
  schema: string[];
  /** Anzahl der angezeigten Zeilen. Optional — wenn `null`, leitet
   *  der Editor sie aus der höchsten referenzierten Zeile ab. */
  rows: number | null;
  cells: SheetCell[];
  /** Sparse per-column metadata (width, border), keyed by column letter. */
  columns: Record<string, SheetColumn>;
  /** Sparse per-row display height in px, keyed by row number (as string). */
  rowHeights: Record<string, number>;
  /** Unknown top-level fields, preserved across round-trip. */
  extra: Record<string, unknown>;
  /** Derived computed overlay (formula results). Read-only for display;
   *  NEVER written back by {@link serializeSheet} — the server owns it
   *  (parse drops any `$computed`; write it only via /sheet/calc). */
  computed?: SheetComputed;
}

export class SheetCodecError extends Error {
  constructor(message: string, public override readonly cause?: unknown) {
    super(message);
    this.name = 'SheetCodecError';
  }
}

// ── A1-Address helpers ──────────────────────────────────────────────

const ADDRESS_RE = /^([A-Z]+)([1-9][0-9]*)$/;

/** Parse an A1-style address. Returns null on invalid input. */
export function parseAddress(addr: string): { col: string; row: number } | null {
  const trimmed = addr.trim().toUpperCase();
  const m = ADDRESS_RE.exec(trimmed);
  if (!m) return null;
  const col = m[1];
  const row = parseInt(m[2], 10);
  if (!Number.isFinite(row) || row < 1) return null;
  return { col, row };
}

/** Convert a 1-based column index (1 = A, 27 = AA) to letters. */
export function columnLetterFromIndex(idx: number): string {
  if (idx < 1) return 'A';
  let n = idx;
  let out = '';
  while (n > 0) {
    const rem = (n - 1) % 26;
    out = String.fromCharCode(65 + rem) + out;
    n = Math.floor((n - 1) / 26);
  }
  return out;
}

/** Inverse: 'A' → 1, 'Z' → 26, 'AA' → 27. Returns 0 on invalid input. */
export function columnIndexFromLetter(col: string): number {
  if (!/^[A-Z]+$/.test(col)) return 0;
  let n = 0;
  for (const c of col) {
    n = n * 26 + (c.charCodeAt(0) - 64);
  }
  return n;
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

// ── Public API ───────────────────────────────────────────────────────

export function parseSheet(body: string, mimeType: string): SheetDocument {
  if (isJson(mimeType)) return parseSheetJson(body);
  if (isYaml(mimeType)) return parseSheetYaml(body);
  throw new SheetCodecError(`Unsupported mime type for sheet: ${mimeType}`);
}

export function serializeSheet(doc: SheetDocument, mimeType: string): string {
  if (isJson(mimeType)) return serializeSheetJson(doc);
  if (isYaml(mimeType)) return serializeSheetYaml(doc);
  throw new SheetCodecError(`Unsupported mime type for sheet: ${mimeType}`);
}

export function isSheetMime(mimeType: string | null | undefined): boolean {
  if (!mimeType) return false;
  return isJson(mimeType) || isYaml(mimeType);
}

// ── JSON ─────────────────────────────────────────────────────────────

function parseSheetJson(body: string): SheetDocument {
  if (body.trim() === '') return emptyDoc();
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch (e) {
    throw new SheetCodecError('Invalid JSON: ' + (e instanceof Error ? e.message : String(e)), e);
  }
  if (!isObject(parsed)) {
    throw new SheetCodecError('Top-level JSON must be an object');
  }
  return promoteToSheetDocument(unwrapJsonMeta(parsed));
}

function serializeSheetJson(doc: SheetDocument): string {
  return JSON.stringify(wrapJsonMeta(doc.kind || 'sheet', buildBody(doc)), null, 2) + '\n';
}

// ── YAML ─────────────────────────────────────────────────────────────

function parseSheetYaml(body: string): SheetDocument {
  if (body.trim() === '') return emptyDoc();
  let merged: Record<string, unknown>;
  try {
    merged = parseYamlBody(body);
  } catch (e) {
    throw new SheetCodecError('Invalid YAML: ' + (e instanceof Error ? e.message : String(e)), e);
  }
  return promoteToSheetDocument(merged);
}

function serializeSheetYaml(doc: SheetDocument): string {
  return dumpYamlBody(doc.kind || 'sheet', buildBody(doc));
}

// ── Promotion ───────────────────────────────────────────────────────

function emptyDoc(): SheetDocument {
  return { kind: 'sheet', schema: [], rows: null, cells: [], columns: {}, rowHeights: {}, extra: {} };
}

function promoteToSheetDocument(obj: Record<string, unknown>): SheetDocument {
  const kind = typeof obj.kind === 'string' ? obj.kind : '';
  const schema = promoteSchema(obj.schema);
  const rows = promoteRows(obj.rows);
  const cells = promoteCells(obj.cells);
  const columns = promoteColumns(obj.columns);
  const rowHeights = promoteRowHeights(obj.rowHeights);
  const computed = promoteComputed(obj['$computed']);
  // `$computed` is a derived overlay — dropped from `extra` so it never
  // round-trips through serialize (server-authoritative), matching Java.
  const {
    kind: _k, schema: _s, rows: _r, cells: _c, columns: _cols, rowHeights: _rh,
    ['$computed']: _comp, ...extra
  } = obj;
  const doc: SheetDocument = { kind, schema, rows, cells, columns, rowHeights, extra };
  if (computed) doc.computed = computed;
  return doc;
}

function promoteRowHeights(raw: unknown): Record<string, number> {
  const out: Record<string, number> = {};
  if (!isObject(raw)) return out;
  for (const [k, v] of Object.entries(raw)) {
    const row = parseInt(k.trim(), 10);
    if (!Number.isFinite(row) || row < 1) continue;
    if (typeof v === 'number' && Number.isFinite(v) && v > 0) out[String(row)] = Math.round(v);
  }
  return out;
}

function promoteColumns(raw: unknown): Record<string, SheetColumn> {
  const out: Record<string, SheetColumn> = {};
  if (!isObject(raw)) return out;
  for (const [k, v] of Object.entries(raw)) {
    const col = k.trim().toUpperCase();
    if (!/^[A-Z]+$/.test(col)) continue;
    if (!isObject(v)) continue;
    const c: SheetColumn = {};
    if (typeof v.width === 'number' && Number.isFinite(v.width) && v.width > 0) {
      c.width = Math.round(v.width);
    }
    if (typeof v.border === 'string' && isBorder(v.border)) c.border = v.border.trim().toLowerCase();
    if (c.width !== undefined || c.border !== undefined) out[col] = c;
  }
  return out;
}

function isBorder(s: string): boolean {
  const t = s.trim().toLowerCase();
  return t === 'left' || t === 'right' || t === 'both';
}

function isAlign(s: string): boolean {
  const t = s.trim().toLowerCase();
  return t === 'left' || t === 'center' || t === 'right';
}

const CELL_FIELDS = new Set([
  'field', 'data', 'color', 'background', 'bold', 'italic', 'align', 'numberFormat', 'borders',
]);

/** Canonical subset of `trbl` (top/right/bottom/left); '' when empty. */
export function normalizeBorders(s: string): string {
  const inp = s.toLowerCase();
  let out = '';
  for (const c of ['t', 'r', 'b', 'l']) if (inp.includes(c)) out += c;
  return out;
}

function promoteComputed(raw: unknown): SheetComputed | undefined {
  if (!isObject(raw)) return undefined;
  const valuesRaw = raw.values;
  if (!Array.isArray(valuesRaw)) return undefined;
  const values: SheetComputedValue[] = [];
  for (const r of valuesRaw) {
    if (!isObject(r)) continue;
    if (typeof r.field !== 'string') continue;
    const v: SheetComputedValue = {
      field: r.field,
      value: typeof r.value === 'string' ? r.value : String(r.value ?? ''),
      type: typeof r.type === 'string' ? r.type : 'text',
    };
    if (typeof r.error === 'string') v.error = r.error;
    values.push(v);
  }
  const computed: SheetComputed = { values };
  if (typeof raw.computedAt === 'string') computed.computedAt = raw.computedAt;
  return computed;
}

function promoteSchema(raw: unknown): string[] {
  if (!Array.isArray(raw)) return [];
  const out: string[] = [];
  const seen = new Set<string>();
  for (const r of raw) {
    if (typeof r !== 'string') continue;
    const col = r.trim().toUpperCase();
    if (!/^[A-Z]+$/.test(col)) continue;
    if (seen.has(col)) continue;
    seen.add(col);
    out.push(col);
  }
  return out;
}

function promoteRows(raw: unknown): number | null {
  if (typeof raw !== 'number' || !Number.isFinite(raw) || raw < 1) return null;
  return Math.floor(raw);
}

function promoteCells(raw: unknown): SheetCell[] {
  if (!Array.isArray(raw)) return [];
  const out: SheetCell[] = [];
  const seen = new Set<string>();
  for (const r of raw) {
    if (!isObject(r)) continue;
    const fieldRaw = r.field;
    if (typeof fieldRaw !== 'string') continue;
    const parsed = parseAddress(fieldRaw);
    if (!parsed) continue; // resilient: drop invalid addresses
    const field = `${parsed.col}${parsed.row}`;
    if (seen.has(field)) {
      throw new SheetCodecError(`Duplicate cell: ${field}`);
    }
    seen.add(field);
    const data = coerceCellValue(r.data);
    const cell: SheetCell = { field, data, extra: {} };
    if (typeof r.color === 'string' && r.color) cell.color = r.color;
    if (typeof r.background === 'string' && r.background) cell.background = r.background;
    if (r.bold === true) cell.bold = true;
    if (r.italic === true) cell.italic = true;
    if (typeof r.align === 'string' && isAlign(r.align)) cell.align = r.align.toLowerCase();
    if (typeof r.numberFormat === 'string' && r.numberFormat.trim()) {
      cell.numberFormat = r.numberFormat.trim();
    }
    if (typeof r.borders === 'string') {
      const b = normalizeBorders(r.borders);
      if (b) cell.borders = b;
    }
    for (const [k, v] of Object.entries(r)) {
      if (CELL_FIELDS.has(k)) continue;
      cell.extra[k] = v;
    }
    out.push(cell);
  }
  return out;
}

function coerceCellValue(v: unknown): string {
  if (v == null) return '';
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  return String(v);
}

// ── Body builder ─────────────────────────────────────────────────────

function buildBody(doc: SheetDocument): Record<string, unknown> {
  const body: Record<string, unknown> = {};
  if (doc.schema.length > 0) body.schema = [...doc.schema];
  if (doc.rows != null) body.rows = doc.rows;
  const cols = columnsToObject(doc.columns);
  if (Object.keys(cols).length > 0) body.columns = cols;
  if (doc.rowHeights && Object.keys(doc.rowHeights).length > 0) {
    body.rowHeights = { ...doc.rowHeights };
  }
  body.cells = doc.cells.map(cellToObject);
  for (const [k, v] of Object.entries(doc.extra)) {
    if (!(k in body)) body[k] = v;
  }
  return body;
}

function columnsToObject(columns: Record<string, SheetColumn>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [col, meta] of Object.entries(columns)) {
    const m: Record<string, unknown> = {};
    if (meta.width !== undefined) m.width = meta.width;
    if (meta.border !== undefined && meta.border !== '') m.border = meta.border;
    if (Object.keys(m).length > 0) out[col] = m;
  }
  return out;
}

function cellToObject(cell: SheetCell): Record<string, unknown> {
  const obj: Record<string, unknown> = { field: cell.field, data: cell.data };
  if (cell.color !== undefined) obj.color = cell.color;
  if (cell.background !== undefined) obj.background = cell.background;
  if (cell.bold === true) obj.bold = true;
  if (cell.italic === true) obj.italic = true;
  if (cell.align !== undefined) obj.align = cell.align;
  if (cell.numberFormat !== undefined) obj.numberFormat = cell.numberFormat;
  if (cell.borders !== undefined) obj.borders = cell.borders;
  for (const [k, v] of Object.entries(cell.extra)) {
    if (!(k in obj)) obj[k] = v;
  }
  return obj;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
