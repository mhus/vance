// Codec for `kind: chart` documents — parses an on-disk body into a
// typed ChartDocument and serializes it back. JSON and YAML only;
// markdown is intentionally not supported (see
// `specification/doc-kind-chart.md` §3.3).
//
// One discriminator (chartType) covers all chart kinds; the per-type
// data-point shape is validated on read, with malformed points
// silently dropped and empty series elided.
//
// Each data point survives round-trip in the form it arrived in — an
// object-form point ({x, y}) is re-emitted as an object, a tuple-form
// point ([x, y]) as a tuple. The codec does not normalise between the
// two.

import {
  dumpYamlBody,
  parseYamlBody,
  unwrapJsonMeta,
  wrapJsonMeta,
} from '@vance/shared';

export type ChartType =
  | 'line'
  | 'bar'
  | 'area'
  | 'scatter'
  | 'pie'
  | 'donut'
  | 'candlestick'
  | 'heatmap';

export type AxisType = 'category' | 'value' | 'time' | 'log';

const CHART_TYPES: ReadonlySet<ChartType> = new Set([
  'line', 'bar', 'area', 'scatter', 'pie', 'donut', 'candlestick', 'heatmap',
]);

const AXIS_TYPES: ReadonlySet<AxisType> = new Set([
  'category', 'value', 'time', 'log',
]);

export interface ChartHeader {
  chartType: ChartType;
  title?: string;
  subtitle?: string;
  /** Whether the legend is shown. Default true. */
  legend: boolean;
  /** Whether series are stacked (bar / area / line only). Default false. */
  stacked: boolean;
  /** Whether line / area series use spline interpolation. Default false. */
  smooth: boolean;
}

export interface ChartAxis {
  type: AxisType;
  label?: string;
  min?: number;
  max?: number;
  /** Explicit category order; only meaningful when {@code type} is
   *  {@code category}. Empty by default. */
  categories: string[];
}

/** A single data point. Object-form (Map<string, unknown>) or tuple-
 *  form (unknown[]); the renderer interprets the shape based on the
 *  document's {@code chartType}. */
export type ChartDataPoint = Record<string, unknown> | unknown[];

export interface ChartSeries {
  name: string;
  color?: string;
  data: ChartDataPoint[];
  /** Unknown per-series fields, preserved across round-trip. */
  extra: Record<string, unknown>;
}

export interface ChartDocument {
  /** Always `'chart'`. */
  kind: string;
  chart: ChartHeader;
  /** {@code null} for pie / donut where axes are meaningless. */
  xAxis: ChartAxis | null;
  yAxis: ChartAxis | null;
  series: ChartSeries[];
  /** Raw ECharts option subtree, deep-merged over the schema-generated
   *  option at render time. {@code null} when not present on disk. */
  echartsOptionOverride: Record<string, unknown> | null;
  /** Unknown top-level fields. Re-emitted verbatim on save. */
  extra: Record<string, unknown>;
}

export class ChartCodecError extends Error {
  constructor(message: string, public override readonly cause?: unknown) {
    super(message);
    this.name = 'ChartCodecError';
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

// ── Public API ───────────────────────────────────────────────────────

export function parseChart(body: string, mimeType: string): ChartDocument {
  if (isJson(mimeType)) return parseChartJson(body);
  if (isYaml(mimeType)) return parseChartYaml(body);
  throw new ChartCodecError(`Unsupported mime type for chart: ${mimeType}`);
}

export function serializeChart(doc: ChartDocument, mimeType: string): string {
  if (isJson(mimeType)) return serializeChartJson(doc);
  if (isYaml(mimeType)) return serializeChartYaml(doc);
  throw new ChartCodecError(`Unsupported mime type for chart: ${mimeType}`);
}

/** Whether the codec can handle this mime type — used by the editor
 *  to decide whether to offer the Chart tab. Markdown is intentionally
 *  excluded; spec §3.3. */
export function isChartMime(mimeType: string | null | undefined): boolean {
  if (!mimeType) return false;
  return isJson(mimeType) || isYaml(mimeType);
}

/** {@code true} when the chart type uses {x, y}-shaped data points
 *  (line, bar, area, scatter). Useful for type-switch compatibility
 *  checks in the editor. */
export function isXyShaped(type: ChartType): boolean {
  return type === 'line' || type === 'bar' || type === 'area' || type === 'scatter';
}

/** {@code true} for pie / donut — data points are {name, value}. */
export function isNamedValueShaped(type: ChartType): boolean {
  return type === 'pie' || type === 'donut';
}

// ── JSON ─────────────────────────────────────────────────────────────

function parseChartJson(body: string): ChartDocument {
  if (body.trim() === '') return emptyDoc();
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch (e) {
    throw new ChartCodecError('Invalid JSON: ' + (e instanceof Error ? e.message : String(e)), e);
  }
  if (!isObject(parsed)) {
    throw new ChartCodecError('Top-level JSON must be an object');
  }
  return promoteToChartDocument(unwrapJsonMeta(parsed));
}

function serializeChartJson(doc: ChartDocument): string {
  return JSON.stringify(wrapJsonMeta(doc.kind || 'chart', buildOnDiskBody(doc)), null, 2) + '\n';
}

// ── YAML ─────────────────────────────────────────────────────────────

function parseChartYaml(body: string): ChartDocument {
  if (body.trim() === '') return emptyDoc();
  let merged: Record<string, unknown>;
  try {
    merged = parseYamlBody(body);
  } catch (e) {
    throw new ChartCodecError('Invalid YAML: ' + (e instanceof Error ? e.message : String(e)), e);
  }
  return promoteToChartDocument(merged);
}

function serializeChartYaml(doc: ChartDocument): string {
  return dumpYamlBody(doc.kind || 'chart', buildOnDiskBody(doc));
}

// ── Shared promotion + writeback ────────────────────────────────────

export function emptyDoc(): ChartDocument {
  return {
    kind: 'chart',
    chart: {
      chartType: 'line',
      legend: true,
      stacked: false,
      smooth: false,
    },
    xAxis: { type: 'category', categories: [] },
    yAxis: { type: 'value', categories: [] },
    series: [],
    echartsOptionOverride: null,
    extra: {},
  };
}

function promoteToChartDocument(obj: Record<string, unknown>): ChartDocument {
  const kind = typeof obj.kind === 'string' ? obj.kind : 'chart';
  const chart = promoteHeader(obj.chart);

  // Pie / donut have no axis semantics — drop any axis blocks that
  // accidentally made it onto disk.
  const xAxis = isNamedValueShaped(chart.chartType)
    ? null
    : promoteAxis(obj.xAxis, 'category');
  const yAxis = isNamedValueShaped(chart.chartType)
    ? null
    : promoteAxis(obj.yAxis, 'value');

  const series = promoteSeries(obj.series, chart.chartType);
  const echartsOptionOverride = promoteOverride(obj.echartsOptionOverride);

  const {
    kind: _k,
    chart: _c,
    xAxis: _x,
    yAxis: _y,
    series: _s,
    echartsOptionOverride: _o,
    ...extra
  } = obj;

  return {
    kind: kind || 'chart',
    chart,
    xAxis,
    yAxis,
    series,
    echartsOptionOverride,
    extra,
  };
}

function promoteHeader(raw: unknown): ChartHeader {
  if (!isObject(raw)) {
    throw new ChartCodecError('Missing or non-object `chart` block');
  }
  const typeRaw = raw.chartType;
  if (typeof typeRaw !== 'string') {
    throw new ChartCodecError('Missing or non-string `chart.chartType`');
  }
  const type = typeRaw.trim().toLowerCase() as ChartType;
  if (!CHART_TYPES.has(type)) {
    throw new ChartCodecError(`Unknown chartType: ${typeRaw}`);
  }
  const title = typeof raw.title === 'string' ? raw.title : undefined;
  const subtitle = typeof raw.subtitle === 'string' ? raw.subtitle : undefined;
  // legend defaults to true; we only flip it false when explicitly set.
  const legend = typeof raw.legend === 'boolean' ? raw.legend : true;
  const stacked = typeof raw.stacked === 'boolean' ? raw.stacked : false;
  const smooth = typeof raw.smooth === 'boolean' ? raw.smooth : false;
  return { chartType: type, title, subtitle, legend, stacked, smooth };
}

function promoteAxis(raw: unknown, defaultType: AxisType): ChartAxis {
  if (!isObject(raw)) {
    return { type: defaultType, categories: [] };
  }
  let type = defaultType;
  if (typeof raw.type === 'string') {
    const candidate = raw.type.trim().toLowerCase() as AxisType;
    if (AXIS_TYPES.has(candidate)) type = candidate;
  }
  const label = typeof raw.label === 'string' ? raw.label : undefined;
  const min = typeof raw.min === 'number' && Number.isFinite(raw.min) ? raw.min : undefined;
  const max = typeof raw.max === 'number' && Number.isFinite(raw.max) ? raw.max : undefined;
  const categories: string[] = [];
  if (Array.isArray(raw.categories)) {
    for (const c of raw.categories) {
      if (typeof c === 'string') categories.push(c);
      else if (typeof c === 'number' && Number.isFinite(c)) categories.push(String(c));
    }
  }
  return { type, label, min, max, categories };
}

function promoteSeries(raw: unknown, chartType: ChartType): ChartSeries[] {
  if (!Array.isArray(raw)) return [];
  const out: ChartSeries[] = [];
  for (const r of raw) {
    if (!isObject(r)) continue;
    const nameRaw = r.name;
    if (typeof nameRaw !== 'string' || !nameRaw.trim()) continue;
    const color = typeof r.color === 'string' && r.color ? r.color : undefined;

    const data: ChartDataPoint[] = [];
    if (Array.isArray(r.data)) {
      for (const pt of r.data) {
        if (isValidDataPoint(pt, chartType)) data.push(pt as ChartDataPoint);
      }
    }
    // Series with zero valid points are elided — they'd render as an
    // empty legend entry with no bars/lines.
    if (data.length === 0) continue;

    const extra: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(r)) {
      if (k === 'name' || k === 'color' || k === 'data') continue;
      extra[k] = v;
    }
    out.push({ name: nameRaw, color, data, extra });
  }
  // If the on-disk body offered series entries but none survived
  // validation, the input is structurally wrong (most often raw
  // ECharts options with `series[].type` and no `name`/`data`).
  // A silent empty chart would hide the mistake; throw so the editor
  // surfaces the parse error and the LLM sees the contract on retry.
  if (out.length === 0 && raw.length > 0) {
    throw new ChartCodecError(
      `No valid series in \`series\` (input had ${raw.length} entries). `
      + 'Each series needs `name` (string) and `data` (non-empty array of points '
      + `matching chartType \`${chartType}\`, e.g. ${sampleShape(chartType)}). `
      + 'Vance charts use this schema directly — do not use raw ECharts options '
      + 'like `dataset` or bare `series[].type`.',
    );
  }
  return out;
}

function sampleShape(type: ChartType): string {
  switch (type) {
    case 'line':
    case 'bar':
    case 'area':
    case 'scatter':
      return "{ x: 'A', y: 10 }";
    case 'pie':
    case 'donut':
      return "{ name: 'A', value: 10 }";
    case 'candlestick':
      return "{ t: '2024-01-01', o: 1, h: 2, l: 0.5, c: 1.5 }";
    case 'heatmap':
      return '{ x: 0, y: 0, v: 1 }';
  }
}

function promoteOverride(raw: unknown): Record<string, unknown> | null {
  if (!isObject(raw)) return null;
  const keys = Object.keys(raw);
  if (keys.length === 0) return null;
  return raw;
}

/** Per-point shape validation. Tuple-form (Array) requires the minimum
 *  element count; object-form (Record) requires the keys the renderer
 *  needs. Wrong inner types (e.g. {@code y} as a string) survive — the
 *  renderer coerces or drops them. The codec is only the structural
 *  gate. */
function isValidDataPoint(pt: unknown, type: ChartType): boolean {
  if (isObject(pt)) {
    switch (type) {
      case 'line':
      case 'bar':
      case 'area':
      case 'scatter':
        return 'x' in pt && 'y' in pt;
      case 'pie':
      case 'donut':
        return 'name' in pt && 'value' in pt;
      case 'candlestick':
        return 't' in pt && 'o' in pt && 'h' in pt && 'l' in pt && 'c' in pt;
      case 'heatmap':
        return 'x' in pt && 'y' in pt && 'v' in pt;
    }
  }
  if (Array.isArray(pt)) {
    switch (type) {
      case 'line':
      case 'bar':
      case 'area':
      case 'scatter':
        return pt.length >= 2;
      case 'pie':
      case 'donut':
        // No canonical ordering for {name, value} when both can be of
        // different types depending on the chart context.
        return false;
      case 'candlestick':
        return pt.length >= 5;
      case 'heatmap':
        return pt.length >= 3;
    }
  }
  return false;
}

// ── On-disk body builder ────────────────────────────────────────────

function buildOnDiskBody(doc: ChartDocument): Record<string, unknown> {
  const body: Record<string, unknown> = {
    chart: headerToObject(doc.chart),
  };
  if (doc.xAxis) body.xAxis = axisToObject(doc.xAxis);
  if (doc.yAxis) body.yAxis = axisToObject(doc.yAxis);
  body.series = doc.series.map(seriesToObject);
  if (doc.echartsOptionOverride && Object.keys(doc.echartsOptionOverride).length > 0) {
    body.echartsOptionOverride = doc.echartsOptionOverride;
  }
  for (const [k, v] of Object.entries(doc.extra)) {
    if (!(k in body)) body[k] = v;
  }
  return body;
}

function headerToObject(h: ChartHeader): Record<string, unknown> {
  const o: Record<string, unknown> = { chartType: h.chartType };
  if (h.title !== undefined) o.title = h.title;
  if (h.subtitle !== undefined) o.subtitle = h.subtitle;
  // legend defaults to true — only emit when explicitly false so the
  // on-disk shape stays compact.
  if (!h.legend) o.legend = false;
  if (h.stacked) o.stacked = true;
  if (h.smooth) o.smooth = true;
  return o;
}

function axisToObject(a: ChartAxis): Record<string, unknown> {
  const o: Record<string, unknown> = { type: a.type };
  if (a.label !== undefined) o.label = a.label;
  if (a.min !== undefined) o.min = a.min;
  if (a.max !== undefined) o.max = a.max;
  if (a.categories.length > 0) o.categories = [...a.categories];
  return o;
}

function seriesToObject(s: ChartSeries): Record<string, unknown> {
  const o: Record<string, unknown> = { name: s.name };
  if (s.color !== undefined) o.color = s.color;
  o.data = [...s.data];
  for (const [k, v] of Object.entries(s.extra)) {
    if (!(k in o)) o[k] = v;
  }
  return o;
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}
