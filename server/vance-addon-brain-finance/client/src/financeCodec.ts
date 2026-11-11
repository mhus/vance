// Codec for `kind: finance-tree` documents — parses an on-disk body (YAML or
// JSON) into the editable tree model and serializes it back.
//
// Mirrors the server-side `FinanceTreeCodec` (same grammar, same compactness
// rules), so the Cortex shell can own load / dirty / autosave / 3-way merge for
// this kind like for every other codec kind. Markdown is not supported: a
// computed tree is not prose.
//
// Compactness (symmetric with the defaults re-applied on parse): `sign` only
// when != 1, `disabled` only when true, `mode` only for one_time, empty
// `values` / `children` dropped, interest `basis` only when != vom_hundert and
// `compound` only when true.
//
// One deliberate divergence: the two *semantic* checks the server makes on a
// value (recurring needs a `period`, one_time needs a date) are not enforced
// here. While a user is mid-edit — a date field cleared, say — the model is
// transiently incomplete, and a throwing parse would throw the whole tab over
// to the raw editor. Structural errors (bad YAML/JSON, missing `name`/`value`)
// still throw.

import {
  dumpYamlBody,
  parseYamlBody,
  unwrapJsonMeta,
  wrapJsonMeta,
} from '@vance/shared';
import type { FinanceInterestDto } from './generated/finance/FinanceInterestDto';
import type { FinanceNodeDto } from './generated/finance/FinanceNodeDto';
import type { FinancePeriodDto } from './generated/finance/FinancePeriodDto';
import type { FinanceTreeDto } from './generated/finance/FinanceTreeDto';
import type { FinanceValueDto } from './generated/finance/FinanceValueDto';

export const FINANCE_KIND = 'finance-tree';

const CURRENT_VERSION = 1;

/**
 * Orients both the agent (visible in `doc_read`) and a human reading the raw
 * file. Identical to the server's header; exactly one pair is prepended on
 * every YAML serialize. Not applied to JSON (no comments).
 */
const YAML_HINT =
  "# vance finance-tree v1 · amounts are per record; a node's sign flips its whole subtree; a disabled node counts as 0\n"
  + "# agent: run manual_read('finance-tree') before interpreting · computed values under $computed\n";

/** Derived figures of one node, as persisted under `$computed.nodes.<name>`. */
export interface FinanceComputedNode {
  perYear: number;
  perMonth: number;
  perWeek: number;
  perDay: number;
  base: number;
  interest: number;
  oneTimeSum: number;
}

/** The persisted `$computed` overlay. Derived data — see {@link FinanceTreeDoc.computed}. */
export interface FinanceComputedOverlay {
  computedAt?: string;
  nodes: Record<string, FinanceComputedNode>;
}

/**
 * The editable tree plus the optional `$computed` overlay. The overlay is
 * carried (not dropped) so a plain edit-and-save does not silently erase what
 * the Reload button wrote; the view clears it on every edit, because figures
 * that no longer match the tree would mislead an agent reading the file.
 */
export interface FinanceTreeDoc extends FinanceTreeDto {
  computed?: FinanceComputedOverlay;
}

export class FinanceCodecError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'FinanceCodecError';
  }
}

export function isFinanceParseError(e: unknown): boolean {
  return e instanceof Error && e.name === 'FinanceCodecError';
}

export function isFinanceMime(mime: string | null | undefined): boolean {
  return isJson(mime) || isYaml(mime);
}

function isJson(mime: string | null | undefined): boolean {
  return mime === 'application/json';
}

function isYaml(mime: string | null | undefined): boolean {
  return mime === 'application/yaml'
    || mime === 'application/x-yaml'
    || mime === 'text/yaml'
    || mime === 'text/x-yaml';
}

// ── Entry points ────────────────────────────────────────────────

export function parseFinance(body: string, mime: string): FinanceTreeDoc {
  if (isJson(mime)) return parseFlat(parseJson(body));
  if (isYaml(mime)) return parseFlat(parseYaml(body));
  throw new FinanceCodecError(`Unsupported mime type for finance-tree: ${mime}`);
}

export function serializeFinance(doc: FinanceTreeDoc, mime: string): string {
  if (isJson(mime)) {
    return JSON.stringify(wrapJsonMeta(FINANCE_KIND, buildBody(doc)), null, 2) + '\n';
  }
  if (isYaml(mime)) {
    return YAML_HINT + dumpYamlBody(FINANCE_KIND, buildBody(doc));
  }
  throw new FinanceCodecError(`Unsupported mime type for finance-tree: ${mime}`);
}

// ── Parse ───────────────────────────────────────────────────────

function parseYaml(body: string): Record<string, unknown> {
  try {
    return parseYamlBody(body);
  } catch (e) {
    throw new FinanceCodecError(`Invalid YAML: ${e instanceof Error ? e.message : String(e)}`);
  }
}

function parseJson(body: string): Record<string, unknown> {
  if (!body.trim()) return {};
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch (e) {
    throw new FinanceCodecError(`Invalid JSON: ${e instanceof Error ? e.message : String(e)}`);
  }
  if (!isRecord(parsed)) throw new FinanceCodecError('Top-level JSON must be an object');
  return unwrapJsonMeta(parsed);
}

function parseFlat(top: Record<string, unknown>): FinanceTreeDoc {
  const doc: FinanceTreeDoc = {
    version: intOr(top.version, CURRENT_VERSION),
    title: str(top, 'title'),
    description: str(top, 'description'),
    root: top.root == null ? undefined : nodeFromMap(asRecord(top.root)),
  };
  const computed = computedFromMap(top['$computed']);
  if (computed) doc.computed = computed;
  return doc;
}

function nodeFromMap(raw: Record<string, unknown>): FinanceNodeDto {
  const name = str(raw, 'name');
  if (name === undefined) throw new FinanceCodecError('finance node requires `name`');
  return {
    name,
    title: str(raw, 'title'),
    icon: str(raw, 'icon'),
    color: str(raw, 'color'),
    sign: intOr(raw.sign, 1) < 0 ? -1 : 1,
    disabled: boolOr(raw.disabled, false),
    description: str(raw, 'description'),
    notesRef: str(raw, 'notesRef'),
    values: recordList(raw.values).map(valueFromMap),
    children: recordList(raw.children).map(nodeFromMap),
  };
}

function valueFromMap(raw: Record<string, unknown>): FinanceValueDto {
  const value = numOrUndefined(raw.value);
  if (value === undefined) throw new FinanceCodecError('finance value requires `value`');
  const mode = str(raw, 'mode')?.toLowerCase() === 'one_time' ? 'one_time' : 'recurring';
  return {
    value,
    mode,
    period: periodFromMap(raw.period),
    validFrom: dateStr(raw.validFrom),
    validTo: dateStr(raw.validTo),
    sign: intOrUndefined(raw.sign),
    interest: interestFromMap(raw.interest),
  };
}

function periodFromMap(raw: unknown): FinancePeriodDto | undefined {
  if (!isRecord(raw)) return undefined;
  const unit = periodUnit(str(raw, 'unit'));
  if (unit === undefined) throw new FinanceCodecError('finance period requires a valid `unit`');
  return { count: intOr(raw.count, 1), unit };
}

function interestFromMap(raw: unknown): FinanceInterestDto | undefined {
  if (!isRecord(raw)) return undefined;
  const rate = numOrUndefined(raw.rate);
  if (rate === undefined) throw new FinanceCodecError('finance interest requires `rate`');
  return {
    rate,
    period: periodFromMap(raw.period) ?? { count: 1, unit: 'year' },
    basis: str(raw, 'basis')?.toLowerCase() ?? 'vom_hundert',
    compound: boolOr(raw.compound, false),
  };
}

function computedFromMap(raw: unknown): FinanceComputedOverlay | undefined {
  if (!isRecord(raw) || !isRecord(raw.nodes)) return undefined;
  const nodes: Record<string, FinanceComputedNode> = {};
  for (const [name, n] of Object.entries(raw.nodes)) {
    if (!isRecord(n)) continue;
    nodes[name] = {
      perYear: numOr(n.perYear, 0),
      perMonth: numOr(n.perMonth, 0),
      perWeek: numOr(n.perWeek, 0),
      perDay: numOr(n.perDay, 0),
      base: numOr(n.base, 0),
      interest: numOr(n.interest, 0),
      oneTimeSum: numOr(n.oneTimeSum, 0),
    };
  }
  const overlay: FinanceComputedOverlay = { nodes };
  if (typeof raw.computedAt === 'string') overlay.computedAt = raw.computedAt;
  return overlay;
}

// ── Serialize ───────────────────────────────────────────────────

function buildBody(doc: FinanceTreeDoc): Record<string, unknown> {
  const body: Record<string, unknown> = { version: doc.version };
  if (doc.title) body.title = doc.title;
  if (doc.description) body.description = doc.description;
  if (doc.root) body.root = nodeToMap(doc.root);
  if (doc.computed) body['$computed'] = doc.computed;
  return body;
}

function nodeToMap(n: FinanceNodeDto): Record<string, unknown> {
  const m: Record<string, unknown> = { name: n.name };
  if (n.title) m.title = n.title;
  if (n.icon) m.icon = n.icon;
  if (n.color) m.color = n.color;
  if (n.sign !== 1) m.sign = n.sign;
  if (n.disabled) m.disabled = true;
  if (n.description) m.description = n.description;
  if (n.notesRef) m.notesRef = n.notesRef;
  if (n.values.length > 0) m.values = n.values.map(valueToMap);
  if (n.children.length > 0) m.children = n.children.map(nodeToMap);
  return m;
}

function valueToMap(v: FinanceValueDto): Record<string, unknown> {
  const m: Record<string, unknown> = { value: v.value };
  if (v.mode !== 'recurring') m.mode = v.mode;
  if (v.period) m.period = { count: v.period.count, unit: v.period.unit };
  if (v.validFrom) m.validFrom = v.validFrom;
  if (v.validTo) m.validTo = v.validTo;
  if (v.sign !== undefined) m.sign = v.sign;
  if (v.interest) m.interest = interestToMap(v.interest);
  return m;
}

function interestToMap(i: FinanceInterestDto): Record<string, unknown> {
  const m: Record<string, unknown> = {
    rate: i.rate,
    period: { count: i.period.count, unit: i.period.unit },
  };
  if (i.basis !== 'vom_hundert') m.basis = i.basis;
  if (i.compound) m.compound = true;
  return m;
}

// ── Coercion helpers ────────────────────────────────────────────

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

function asRecord(v: unknown): Record<string, unknown> {
  return isRecord(v) ? v : {};
}

function recordList(v: unknown): Record<string, unknown>[] {
  return Array.isArray(v) ? v.filter(isRecord) : [];
}

function str(raw: Record<string, unknown>, key: string): string | undefined {
  const v = raw[key];
  if (typeof v === 'string' && v.trim() !== '') return v.trim();
  return undefined;
}

/** ISO `yyyy-MM-dd`. YAML (JSON schema) keeps them strings; a Date is normalised to UTC. */
function dateStr(v: unknown): string | undefined {
  if (typeof v === 'string' && v.trim() !== '') return v.trim();
  if (v instanceof Date) return v.toISOString().slice(0, 10);
  return undefined;
}

function numOrUndefined(v: unknown): number | undefined {
  if (typeof v === 'number' && Number.isFinite(v)) return v;
  if (typeof v === 'string' && v.trim() !== '') {
    const n = Number(v.trim());
    return Number.isFinite(n) ? n : undefined;
  }
  return undefined;
}

function numOr(v: unknown, fallback: number): number {
  return numOrUndefined(v) ?? fallback;
}

function intOrUndefined(v: unknown): number | undefined {
  const n = numOrUndefined(v);
  return n === undefined ? undefined : Math.trunc(n);
}

function intOr(v: unknown, fallback: number): number {
  return intOrUndefined(v) ?? fallback;
}

function boolOr(v: unknown, fallback: boolean): boolean {
  if (typeof v === 'boolean') return v;
  if (typeof v === 'string' && v.trim() !== '') return v.trim().toLowerCase() === 'true';
  return fallback;
}

function periodUnit(v: string | undefined): string | undefined {
  switch (v?.toLowerCase()) {
    case 'day': case 'week': case 'month': case 'year': return v.toLowerCase();
    default: return undefined;
  }
}
