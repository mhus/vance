// Codec for `kind: sheet` documents — sparse 2D-grid with A1 cell
// addresses (Excel-standard). JSON and YAML only; markdown is not
// supported (see `specification/doc-kind-sheet.md` §3.3).
//
// Cells are stored as a sparse list — only cells with content or
// formatting appear on disk. Formulas live as plain strings (lead
// with `=`) and round-trip stably; v1 does not evaluate them, see
// spec §6.1 for v2 client-eval and §6.2 for v3 server-eval.

import {
  dumpYamlMultiDoc,
  mergeYamlMultiDoc,
  unwrapJsonMeta,
  wrapJsonMeta,
} from './documentHeaderCodec';

export interface SheetCell {
  /** A1-Adresse, kanonisch uppercase (`A1`, `B5`, `AB99`). */
  field: string;
  /** Cell-Inhalt als String. Beginnt mit `=` für Formeln. */
  data: string;
  color?: string;
  background?: string;
  /** Unknown per-cell fields, preserved across round-trip. */
  extra: Record<string, unknown>;
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
  /** Unknown top-level fields, preserved across round-trip. */
  extra: Record<string, unknown>;
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
    merged = mergeYamlMultiDoc(body);
  } catch (e) {
    throw new SheetCodecError('Invalid YAML: ' + (e instanceof Error ? e.message : String(e)), e);
  }
  return promoteToSheetDocument(merged);
}

function serializeSheetYaml(doc: SheetDocument): string {
  return dumpYamlMultiDoc(doc.kind || 'sheet', buildBody(doc));
}

// ── Promotion ───────────────────────────────────────────────────────

function emptyDoc(): SheetDocument {
  return { kind: 'sheet', schema: [], rows: null, cells: [], extra: {} };
}

function promoteToSheetDocument(obj: Record<string, unknown>): SheetDocument {
  const kind = typeof obj.kind === 'string' ? obj.kind : '';
  const schema = promoteSchema(obj.schema);
  const rows = promoteRows(obj.rows);
  const cells = promoteCells(obj.cells);
  const { kind: _k, schema: _s, rows: _r, cells: _c, ...extra } = obj;
  return { kind, schema, rows, cells, extra };
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
    for (const [k, v] of Object.entries(r)) {
      if (k === 'field' || k === 'data' || k === 'color' || k === 'background') continue;
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
  body.cells = doc.cells.map(cellToObject);
  for (const [k, v] of Object.entries(doc.extra)) {
    if (!(k in body)) body[k] = v;
  }
  return body;
}

function cellToObject(cell: SheetCell): Record<string, unknown> {
  const obj: Record<string, unknown> = { field: cell.field, data: cell.data };
  if (cell.color !== undefined) obj.color = cell.color;
  if (cell.background !== undefined) obj.background = cell.background;
  for (const [k, v] of Object.entries(cell.extra)) {
    if (!(k in obj)) obj[k] = v;
  }
  return obj;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
