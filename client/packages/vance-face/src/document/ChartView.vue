<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import * as echarts from 'echarts/core';
import {
  BarChart,
  CandlestickChart,
  HeatmapChart,
  LineChart,
  PieChart,
  ScatterChart,
} from 'echarts/charts';
import {
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
  VisualMapComponent,
} from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import type { EChartsType } from 'echarts/core';
import {
  type AxisType,
  type ChartAxis,
  type ChartDataPoint,
  type ChartDocument,
  type ChartHeader,
  type ChartSeries,
  type ChartType,
  isNamedValueShaped,
  isXyShaped,
  parseChart,
} from './chartCodec';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';
import type { FenceMeta } from '@/kindRenderers/parseFenceLang';
import { VCheckbox, VInput, VSelect, VTextarea } from '@/components';

// Register ECharts modules once. Modular import keeps the bundle under
// ~600 KB; the full ECharts build would be ~1.1 MB.
echarts.use([
  LineChart, BarChart, ScatterChart, PieChart, CandlestickChart, HeatmapChart,
  GridComponent, TooltipComponent, LegendComponent, TitleComponent,
  DataZoomComponent, VisualMapComponent,
  CanvasRenderer,
]);

/**
 * Renderer for {@code kind: chart} documents — supports three modes
 * (spec {@code specification/inline-and-embedded-content.md} §4):
 *   - {@code editor}    — full editor surface with toolbar + sidebar.
 *   - {@code inline}    — read-only chart parsed from a fence body.
 *   - {@code embedded}  — read-only chart from a loaded Document.
 *
 * Datapoint editing is intentionally not v1 (spec §5.3) — Raw-Tab is
 * the edit path even in editor mode. The Chart-Tab exposes the
 * structural knobs only: chartType, title/legend/stacked/smooth, axis
 * types, per-series colors, plus the {@code echartsOptionOverride}
 * escape-hatch.
 */
defineOptions({ name: 'ChartView' });

const props = withDefaults(defineProps<{
  mode?: 'editor' | 'inline' | 'embedded';
  /** Editor mode — full mutable doc. */
  doc?: ChartDocument;
  /** Inline mode — fence body (JSON or YAML). */
  content?: string;
  meta?: FenceMeta;
  /** Embedded mode — loaded Document. */
  document?: DocumentDto;
  embedRef?: EmbedRef;
}>(), {
  mode: 'editor',
  meta: () => ({}),
});

const emit = defineEmits<{
  (event: 'update:doc', doc: ChartDocument): void;
}>();

const isEditor = computed(() => props.mode === 'editor');

/**
 * Resolve the source-of-truth document per mode. Editor mode trusts
 * {@code props.doc} directly; inline/embedded modes parse the
 * fence-body or loaded Document. Parse failures fall back to an empty
 * chart and a console-warn — the user sees a blank canvas rather than
 * a thrown error in a chat stream.
 */
const resolvedDoc = computed<ChartDocument>(() => {
  if (props.mode === 'editor') {
    return props.doc ?? emptyChartDoc();
  }
  if (props.mode === 'inline') {
    try {
      // Fence bodies for `chart` are JSON or YAML. Heuristic: a body
      // starting with `{` is JSON, anything else parses as YAML —
      // matches how other Vance kinds dispatch in inline mode.
      const body = props.content ?? '';
      const mime = body.trimStart().startsWith('{') ? 'application/json' : 'application/yaml';
      return parseChart(body, mime);
    } catch (e) {
      console.warn('ChartView: failed to parse inline content', e);
      return emptyChartDoc();
    }
  }
  // embedded
  const d = props.document;
  if (!d?.inlineText) return emptyChartDoc();
  try {
    return parseChart(d.inlineText, d.mimeType ?? 'application/json');
  } catch (e) {
    console.warn('ChartView: failed to parse embedded document', e);
    return emptyChartDoc();
  }
});

function emptyChartDoc(): ChartDocument {
  return {
    kind: 'chart',
    chart: { chartType: 'line', legend: true, stacked: false, smooth: false },
    xAxis: { type: 'category', categories: [] },
    yAxis: { type: 'value', categories: [] },
    series: [],
    echartsOptionOverride: null,
    extra: {},
  };
}

// ── Local state mirrors the resolved doc but tracks structural edits ─
//
// Editor mode mutates these refs and emits update:doc on every change.
// Read-only modes still use them so the same render pipeline drives
// the canvas — emitDoc is just guarded by isEditor.

const localHeader = ref<ChartHeader>(cloneHeader(resolvedDoc.value.chart));
const localXAxis = ref<ChartAxis | null>(cloneAxis(resolvedDoc.value.xAxis));
const localYAxis = ref<ChartAxis | null>(cloneAxis(resolvedDoc.value.yAxis));
const localSeries = ref<ChartSeries[]>(cloneSeries(resolvedDoc.value.series));
const localOverride = ref<string>(formatOverride(resolvedDoc.value.echartsOptionOverride));
const overrideError = ref<string | null>(null);

watch(resolvedDoc, (next) => {
  localHeader.value = cloneHeader(next.chart);
  localXAxis.value = cloneAxis(next.xAxis);
  localYAxis.value = cloneAxis(next.yAxis);
  localSeries.value = cloneSeries(next.series);
  localOverride.value = formatOverride(next.echartsOptionOverride);
  overrideError.value = null;
});

function cloneHeader(h: ChartHeader): ChartHeader {
  return { ...h };
}
function cloneAxis(a: ChartAxis | null): ChartAxis | null {
  return a ? { ...a, categories: [...a.categories] } : null;
}
function cloneSeries(src: ChartSeries[]): ChartSeries[] {
  return src.map((s) => ({
    name: s.name,
    color: s.color,
    data: s.data.map(clonePoint),
    extra: { ...s.extra },
  }));
}
function clonePoint(p: ChartDataPoint): ChartDataPoint {
  return Array.isArray(p) ? [...p] : { ...p };
}
function formatOverride(o: Record<string, unknown> | null): string {
  if (!o || Object.keys(o).length === 0) return '';
  return JSON.stringify(o, null, 2);
}

// ── Emit a refreshed document up the tree (editor mode only) ────────

function emitDoc(): void {
  if (!isEditor.value) return;
  let override: Record<string, unknown> | null = null;
  if (localOverride.value.trim()) {
    try {
      const parsed = JSON.parse(localOverride.value);
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        override = parsed as Record<string, unknown>;
        overrideError.value = null;
      } else {
        overrideError.value = 'Override must be a JSON object';
      }
    } catch (e) {
      overrideError.value = e instanceof Error ? e.message : String(e);
      // Don't emit when the override is malformed — keep the current
      // document state intact so a partial type doesn't blow away the
      // chart.
      return;
    }
  }

  emit('update:doc', {
    kind: resolvedDoc.value.kind || 'chart',
    chart: { ...localHeader.value },
    // Pie / donut have no axes (spec §2.5) — emit null instead of
    // carrying stale axis blocks.
    xAxis: isNamedValueShaped(localHeader.value.chartType)
      ? null
      : (localXAxis.value ? { ...localXAxis.value, categories: [...localXAxis.value.categories] } : null),
    yAxis: isNamedValueShaped(localHeader.value.chartType)
      ? null
      : (localYAxis.value ? { ...localYAxis.value, categories: [...localYAxis.value.categories] } : null),
    series: cloneSeries(localSeries.value),
    echartsOptionOverride: override,
    extra: { ...resolvedDoc.value.extra },
  });
}

// ── Chart-type switch with compatibility check ─────────────────────

const CHART_TYPE_OPTIONS: { value: ChartType; label: string }[] = [
  { value: 'line', label: 'Line' },
  { value: 'bar', label: 'Bar' },
  { value: 'area', label: 'Area' },
  { value: 'scatter', label: 'Scatter' },
  { value: 'pie', label: 'Pie' },
  { value: 'donut', label: 'Donut' },
  { value: 'candlestick', label: 'Candlestick' },
  { value: 'heatmap', label: 'Heatmap' },
];

const AXIS_TYPE_OPTIONS: { value: AxisType; label: string }[] = [
  { value: 'category', label: 'Category' },
  { value: 'value', label: 'Value' },
  { value: 'time', label: 'Time' },
  { value: 'log', label: 'Log' },
];

function onChartTypeChange(newType: ChartType): void {
  const oldType = localHeader.value.chartType;
  if (oldType === newType) return;

  if (localSeries.value.length > 0 && !shapesCompatible(oldType, newType)) {
    const confirmed = window.confirm(
      `Switching from ${oldType} to ${newType} requires a different data-point shape. `
      + 'The existing series data will be dropped on save. Continue?',
    );
    if (!confirmed) return;
    // Clear the data; let the user repopulate via Raw-Tab.
    localSeries.value = localSeries.value.map((s) => ({ ...s, data: [] }));
  }
  localHeader.value = { ...localHeader.value, chartType: newType };
  // Restore default axes when crossing into / out of pie/donut land.
  if (isNamedValueShaped(newType)) {
    localXAxis.value = null;
    localYAxis.value = null;
  } else if (localXAxis.value === null && localYAxis.value === null) {
    localXAxis.value = { type: 'category', categories: [] };
    localYAxis.value = { type: 'value', categories: [] };
  }
  emitDoc();
}

/** {@code true} when the two chart types share the same datapoint
 *  shape and the editor can swap them without data loss. */
function shapesCompatible(a: ChartType, b: ChartType): boolean {
  if (isXyShaped(a) && isXyShaped(b)) return true;
  if (isNamedValueShaped(a) && isNamedValueShaped(b)) return true;
  return a === b;
}

// ── ECharts lifecycle ──────────────────────────────────────────────

const chartContainer = ref<HTMLDivElement | null>(null);
const chartInstance = shallowRef<EChartsType | null>(null);

onMounted(() => {
  if (!chartContainer.value) return;
  chartInstance.value = echarts.init(chartContainer.value);
  renderChart();

  // ECharts doesn't auto-resize on container changes — we hook it up
  // explicitly with a ResizeObserver.
  if (typeof ResizeObserver !== 'undefined' && chartContainer.value) {
    resizeObserver = new ResizeObserver(() => chartInstance.value?.resize());
    resizeObserver.observe(chartContainer.value);
  }
});

let resizeObserver: ResizeObserver | null = null;

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  chartInstance.value?.dispose();
  chartInstance.value = null;
});

// Re-render whenever the local model or override changes. We rebuild
// the option fresh — ECharts' merge mode doesn't always clear stale
// fields when a switch (e.g. line → pie) removes structural blocks.
const renderTrigger = computed(() => ({
  header: localHeader.value,
  xAxis: localXAxis.value,
  yAxis: localYAxis.value,
  series: localSeries.value,
  override: localOverride.value,
}));

watch(renderTrigger, () => nextTick(renderChart), { deep: true });

function renderChart(): void {
  if (!chartInstance.value) return;
  try {
    const option = buildEChartsOption(
      localHeader.value,
      localXAxis.value,
      localYAxis.value,
      localSeries.value,
      parseOverrideSafely(localOverride.value),
    );
    chartInstance.value.setOption(option, true);
  } catch (e) {
    // Render errors shouldn't blow up the editor — log and keep the
    // previous render visible.
    console.warn('ChartView render failed', e);
  }
}

function parseOverrideSafely(raw: string): Record<string, unknown> | null {
  if (!raw.trim()) return null;
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

// ── Schema → ECharts option mapping ────────────────────────────────

function buildEChartsOption(
  header: ChartHeader,
  xAxis: ChartAxis | null,
  yAxis: ChartAxis | null,
  series: ChartSeries[],
  override: Record<string, unknown> | null,
): Record<string, unknown> {
  const option: Record<string, unknown> = {
    title: header.title || header.subtitle
      ? { text: header.title, subtext: header.subtitle, left: 'center' }
      : undefined,
    tooltip: { trigger: isNamedValueShaped(header.chartType) ? 'item' : 'axis' },
    legend: header.legend
      ? { type: 'scroll', bottom: 8, data: series.map((s) => s.name) }
      : undefined,
    series: series.map((s) => buildSeriesOption(s, header)),
  };

  // Axes for non-pie/donut types. ECharts wants xAxis/yAxis as a
  // top-level entry; for time/category x-axis we also wire a dataZoom
  // slider so the user can scrub timelines.
  if (!isNamedValueShaped(header.chartType)) {
    option.xAxis = buildAxisOption(xAxis, 'x', series);
    option.yAxis = buildAxisOption(yAxis, 'y', series);
    option.grid = { left: 56, right: 24, top: 56, bottom: header.legend ? 64 : 32 };
    if (xAxis?.type === 'time') {
      option.dataZoom = [
        { type: 'inside' },
        { type: 'slider', bottom: header.legend ? 32 : 0, height: 18 },
      ];
    }
  }

  // Heatmap needs a visualMap to colour-encode the v dimension.
  if (header.chartType === 'heatmap' && series.length > 0) {
    const vals = series[0].data
      .map((pt) => (Array.isArray(pt) ? Number(pt[2]) : Number(pt.v)))
      .filter(Number.isFinite);
    if (vals.length > 0) {
      option.visualMap = {
        min: Math.min(...vals),
        max: Math.max(...vals),
        calculable: true,
        orient: 'horizontal',
        left: 'center',
        bottom: 0,
      };
    }
  }

  // Merge the user override last so it can override anything above.
  return override ? deepMerge(option, override) : option;
}

function buildAxisOption(
  axis: ChartAxis | null,
  side: 'x' | 'y',
  series: ChartSeries[],
): Record<string, unknown> {
  const type = axis?.type ?? (side === 'x' ? 'category' : 'value');
  const out: Record<string, unknown> = { type };
  if (axis?.label) out.name = axis.label;
  if (axis?.min !== undefined) out.min = axis.min;
  if (axis?.max !== undefined) out.max = axis.max;

  // ECharts category axes need a `data` array. Either use the
  // explicit `categories` list, or infer from the first series'
  // x values (works for line/bar/area/candlestick).
  if (type === 'category' && side === 'x') {
    if (axis && axis.categories.length > 0) {
      out.data = [...axis.categories];
    } else if (series.length > 0) {
      const data = series[0].data;
      out.data = data.map((pt) => Array.isArray(pt) ? String(pt[0]) : String(pt.x ?? pt.t ?? ''));
    }
  }
  return out;
}

function buildSeriesOption(
  s: ChartSeries,
  header: ChartHeader,
): Record<string, unknown> {
  const echartsType = mapToEchartsSeriesType(header.chartType);
  const out: Record<string, unknown> = {
    name: s.name,
    type: echartsType,
    data: mapSeriesData(s.data, header.chartType),
  };

  if (s.color) out.itemStyle = { color: s.color };
  if (header.chartType === 'area') out.areaStyle = {};
  if (header.chartType === 'line' && header.smooth) out.smooth = true;
  if (header.chartType === 'area' && header.smooth) out.smooth = true;
  if (header.stacked && (header.chartType === 'bar' || header.chartType === 'area' || header.chartType === 'line')) {
    out.stack = 'total';
  }
  if (header.chartType === 'donut') {
    out.radius = ['40%', '70%'];
  }
  // Pie sits in the centre of the canvas; donut shares the radius
  // tweak above.
  if (header.chartType === 'pie' || header.chartType === 'donut') {
    out.center = ['50%', '50%'];
  }
  // Heatmap needs a grid coordinate system — already set as default.

  return out;
}

function mapToEchartsSeriesType(type: ChartType): string {
  switch (type) {
    case 'line':
    case 'area':
      return 'line';
    case 'bar':
      return 'bar';
    case 'scatter':
      return 'scatter';
    case 'pie':
    case 'donut':
      return 'pie';
    case 'candlestick':
      return 'candlestick';
    case 'heatmap':
      return 'heatmap';
  }
}

function mapSeriesData(data: ChartDataPoint[], chartType: ChartType): unknown[] {
  switch (chartType) {
    case 'line':
    case 'bar':
    case 'area':
    case 'scatter':
      return data.map((pt) => Array.isArray(pt) ? [pt[0], pt[1]] : [pt.x, pt.y]);
    case 'pie':
    case 'donut':
      return data.map((pt) =>
        Array.isArray(pt)
          ? { name: String(pt[0]), value: pt[1] }
          : { name: String(pt.name ?? ''), value: pt.value, itemStyle: pt.color ? { color: pt.color } : undefined });
    case 'candlestick':
      // ECharts candlestick wants [open, close, lowest, highest] per
      // point; the time/category value lives on the xAxis. Same
      // confusing order as the upstream docs.
      return data.map((pt) => Array.isArray(pt)
        ? [pt[1], pt[4], pt[3], pt[2]]
        : [pt.o, pt.c, pt.l, pt.h]);
    case 'heatmap':
      return data.map((pt) => Array.isArray(pt) ? [pt[0], pt[1], pt[2]] : [pt.x, pt.y, pt.v]);
  }
}

/** Shallow + recursive deep-merge. {@code override} wins; arrays are
 *  replaced wholesale (not concatenated) — matches the spec's note that
 *  echartsOptionOverride is a precise patch, not an accumulation. */
function deepMerge(
  base: Record<string, unknown>,
  override: Record<string, unknown>,
): Record<string, unknown> {
  const out: Record<string, unknown> = { ...base };
  for (const [k, v] of Object.entries(override)) {
    if (
      v && typeof v === 'object' && !Array.isArray(v)
      && out[k] && typeof out[k] === 'object' && !Array.isArray(out[k])
    ) {
      out[k] = deepMerge(out[k] as Record<string, unknown>, v as Record<string, unknown>);
    } else {
      out[k] = v;
    }
  }
  return out;
}

// ── Side-panel reactive handlers ───────────────────────────────────

function updateHeader<K extends keyof ChartHeader>(key: K, value: ChartHeader[K]): void {
  localHeader.value = { ...localHeader.value, [key]: value };
  emitDoc();
}

function updateXAxisType(type: AxisType): void {
  if (!localXAxis.value) return;
  localXAxis.value = { ...localXAxis.value, type };
  emitDoc();
}

function updateYAxisType(type: AxisType): void {
  if (!localYAxis.value) return;
  localYAxis.value = { ...localYAxis.value, type };
  emitDoc();
}

function updateSeriesColor(idx: number, color: string): void {
  const next = [...localSeries.value];
  next[idx] = { ...next[idx], color: color || undefined };
  localSeries.value = next;
  emitDoc();
}

const showsAxes = computed(() => !isNamedValueShaped(localHeader.value.chartType));
</script>

<template>
  <div :class="['chart-view', `chart-view--${mode}`]">
    <!-- Toolbar (editor only) -->
    <div v-if="isEditor" class="chart-toolbar">
      <VSelect
        :model-value="localHeader.chartType"
        :options="CHART_TYPE_OPTIONS"
        class="w-44"
        @update:model-value="(v: ChartType | null) => { if (v) onChartTypeChange(v); }"
      />
      <VInput
        :model-value="localHeader.title ?? ''"
        placeholder="Title"
        class="flex-1"
        @update:model-value="(v: string) => updateHeader('title', v || undefined)"
      />
    </div>

    <!-- Main: chart canvas (always) + side panel (editor only) -->
    <div class="chart-main">
      <div ref="chartContainer" class="chart-canvas" />

      <aside v-if="isEditor" class="chart-sidebar">
        <section>
          <h3 class="chart-sidebar-h">Chart</h3>
          <VInput
            :model-value="localHeader.subtitle ?? ''"
            placeholder="Subtitle"
            label="Subtitle"
            @update:model-value="(v: string) => updateHeader('subtitle', v || undefined)"
          />
          <VCheckbox
            :model-value="localHeader.legend"
            label="Legend"
            @update:model-value="(v: boolean) => updateHeader('legend', v)"
          />
          <VCheckbox
            v-if="['bar','area','line'].includes(localHeader.chartType)"
            :model-value="localHeader.stacked"
            label="Stacked"
            @update:model-value="(v: boolean) => updateHeader('stacked', v)"
          />
          <VCheckbox
            v-if="['line','area'].includes(localHeader.chartType)"
            :model-value="localHeader.smooth"
            label="Smooth"
            @update:model-value="(v: boolean) => updateHeader('smooth', v)"
          />
        </section>

        <section v-if="showsAxes && localXAxis">
          <h3 class="chart-sidebar-h">X-Axis</h3>
          <VSelect
            :model-value="localXAxis.type"
            :options="AXIS_TYPE_OPTIONS"
            label="Type"
            @update:model-value="(v: AxisType | null) => { if (v) updateXAxisType(v); }"
          />
        </section>
        <section v-if="showsAxes && localYAxis">
          <h3 class="chart-sidebar-h">Y-Axis</h3>
          <VSelect
            :model-value="localYAxis.type"
            :options="AXIS_TYPE_OPTIONS"
            label="Type"
            @update:model-value="(v: AxisType | null) => { if (v) updateYAxisType(v); }"
          />
        </section>

        <section v-if="localSeries.length > 0">
          <h3 class="chart-sidebar-h">Series</h3>
          <div v-for="(s, idx) in localSeries" :key="s.name + idx" class="chart-series-row">
            <span class="chart-series-name">{{ s.name }}</span>
            <input
              type="color"
              :value="s.color ?? '#3b82f6'"
              class="chart-series-color"
              @input="(e) => updateSeriesColor(idx, (e.target as HTMLInputElement).value)"
            />
          </div>
        </section>

        <section>
          <h3 class="chart-sidebar-h">ECharts override</h3>
          <p class="chart-sidebar-hint">
            JSON merged on top of the generated ECharts option. Use sparingly.
          </p>
          <VTextarea
            :model-value="localOverride"
            placeholder="{}"
            :rows="6"
            class="font-mono text-xs"
            @update:model-value="(v: string) => { localOverride = v; emitDoc(); }"
          />
          <p v-if="overrideError" class="chart-sidebar-error">{{ overrideError }}</p>
        </section>
      </aside>
    </div>

    <!-- Data-editing hint: pointing the user at Raw-Tab is the v1
         intent (spec §5.3 — datapoint edits are best-effort). Only
         shown in editor mode; the inline/embedded chat-stream uses
         charts as read-only artifacts. -->
    <p v-if="isEditor" class="chart-data-hint">
      Edit data points in the <strong>Raw</strong> tab.
    </p>
  </div>
</template>

<style scoped>
.chart-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 420px;
  gap: 0.5rem;
}

/* Inline / embedded use a compact, fixed-height canvas — the chat
   stream isn't a scrollable editor surface, so we don't stretch to
   the viewport. */
.chart-view--inline,
.chart-view--embedded {
  min-height: 280px;
  height: 280px;
}

.chart-toolbar {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding-bottom: 0.25rem;
}

.chart-main {
  display: flex;
  flex: 1;
  min-height: 0;
  gap: 1rem;
}

.chart-canvas {
  flex: 1;
  min-height: 420px;
  min-width: 0;
}

.chart-view--inline .chart-canvas,
.chart-view--embedded .chart-canvas {
  min-height: 240px;
}

.chart-sidebar {
  width: 18rem;
  max-height: 100%;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 1rem;
  padding-left: 0.75rem;
  border-left: 1px solid hsl(var(--bc) / 0.1);
}

.chart-sidebar > section {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.chart-sidebar-h {
  font-size: 0.875rem;
  font-weight: 600;
  letter-spacing: 0.02em;
  color: hsl(var(--bc) / 0.7);
}

.chart-sidebar-hint {
  font-size: 0.75rem;
  color: hsl(var(--bc) / 0.55);
}

.chart-sidebar-error {
  font-size: 0.75rem;
  color: hsl(var(--er));
}

.chart-series-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
}

.chart-series-name {
  font-size: 0.875rem;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chart-series-color {
  width: 2.5rem;
  height: 1.5rem;
  border: 1px solid hsl(var(--bc) / 0.15);
  border-radius: 0.25rem;
  cursor: pointer;
}

.chart-data-hint {
  font-size: 0.75rem;
  color: hsl(var(--bc) / 0.55);
  text-align: right;
}
</style>
