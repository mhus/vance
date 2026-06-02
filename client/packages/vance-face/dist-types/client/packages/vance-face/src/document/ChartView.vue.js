import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import * as echarts from 'echarts/core';
import { BarChart, CandlestickChart, HeatmapChart, LineChart, PieChart, ScatterChart, } from 'echarts/charts';
import { DataZoomComponent, GridComponent, LegendComponent, TitleComponent, TooltipComponent, VisualMapComponent, } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { isNamedValueShaped, isXyShaped, parseChart, } from './chartCodec';
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
const props = withDefaults(defineProps(), {
    mode: 'editor',
    meta: () => ({}),
});
const emit = defineEmits();
const isEditor = computed(() => props.mode === 'editor');
/**
 * Resolve the source-of-truth document per mode. Editor mode trusts
 * {@code props.doc} directly; inline/embedded modes parse the
 * fence-body or loaded Document. Parse failures fall back to an empty
 * chart and a console-warn — the user sees a blank canvas rather than
 * a thrown error in a chat stream.
 */
const resolvedDoc = computed(() => {
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
        }
        catch (e) {
            console.warn('ChartView: failed to parse inline content', e);
            return emptyChartDoc();
        }
    }
    // embedded
    const d = props.document;
    if (!d?.inlineText)
        return emptyChartDoc();
    try {
        return parseChart(d.inlineText, d.mimeType ?? 'application/json');
    }
    catch (e) {
        console.warn('ChartView: failed to parse embedded document', e);
        return emptyChartDoc();
    }
});
function emptyChartDoc() {
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
const localHeader = ref(cloneHeader(resolvedDoc.value.chart));
const localXAxis = ref(cloneAxis(resolvedDoc.value.xAxis));
const localYAxis = ref(cloneAxis(resolvedDoc.value.yAxis));
const localSeries = ref(cloneSeries(resolvedDoc.value.series));
const localOverride = ref(formatOverride(resolvedDoc.value.echartsOptionOverride));
const overrideError = ref(null);
watch(resolvedDoc, (next) => {
    localHeader.value = cloneHeader(next.chart);
    localXAxis.value = cloneAxis(next.xAxis);
    localYAxis.value = cloneAxis(next.yAxis);
    localSeries.value = cloneSeries(next.series);
    localOverride.value = formatOverride(next.echartsOptionOverride);
    overrideError.value = null;
});
function cloneHeader(h) {
    return { ...h };
}
function cloneAxis(a) {
    return a ? { ...a, categories: [...a.categories] } : null;
}
function cloneSeries(src) {
    return src.map((s) => ({
        name: s.name,
        color: s.color,
        data: s.data.map(clonePoint),
        extra: { ...s.extra },
    }));
}
function clonePoint(p) {
    return Array.isArray(p) ? [...p] : { ...p };
}
function formatOverride(o) {
    if (!o || Object.keys(o).length === 0)
        return '';
    return JSON.stringify(o, null, 2);
}
// ── Emit a refreshed document up the tree (editor mode only) ────────
function emitDoc() {
    if (!isEditor.value)
        return;
    let override = null;
    if (localOverride.value.trim()) {
        try {
            const parsed = JSON.parse(localOverride.value);
            if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
                override = parsed;
                overrideError.value = null;
            }
            else {
                overrideError.value = 'Override must be a JSON object';
            }
        }
        catch (e) {
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
const CHART_TYPE_OPTIONS = [
    { value: 'line', label: 'Line' },
    { value: 'bar', label: 'Bar' },
    { value: 'area', label: 'Area' },
    { value: 'scatter', label: 'Scatter' },
    { value: 'pie', label: 'Pie' },
    { value: 'donut', label: 'Donut' },
    { value: 'candlestick', label: 'Candlestick' },
    { value: 'heatmap', label: 'Heatmap' },
];
const AXIS_TYPE_OPTIONS = [
    { value: 'category', label: 'Category' },
    { value: 'value', label: 'Value' },
    { value: 'time', label: 'Time' },
    { value: 'log', label: 'Log' },
];
function onChartTypeChange(newType) {
    const oldType = localHeader.value.chartType;
    if (oldType === newType)
        return;
    if (localSeries.value.length > 0 && !shapesCompatible(oldType, newType)) {
        const confirmed = window.confirm(`Switching from ${oldType} to ${newType} requires a different data-point shape. `
            + 'The existing series data will be dropped on save. Continue?');
        if (!confirmed)
            return;
        // Clear the data; let the user repopulate via Raw-Tab.
        localSeries.value = localSeries.value.map((s) => ({ ...s, data: [] }));
    }
    localHeader.value = { ...localHeader.value, chartType: newType };
    // Restore default axes when crossing into / out of pie/donut land.
    if (isNamedValueShaped(newType)) {
        localXAxis.value = null;
        localYAxis.value = null;
    }
    else if (localXAxis.value === null && localYAxis.value === null) {
        localXAxis.value = { type: 'category', categories: [] };
        localYAxis.value = { type: 'value', categories: [] };
    }
    emitDoc();
}
/** {@code true} when the two chart types share the same datapoint
 *  shape and the editor can swap them without data loss. */
function shapesCompatible(a, b) {
    if (isXyShaped(a) && isXyShaped(b))
        return true;
    if (isNamedValueShaped(a) && isNamedValueShaped(b))
        return true;
    return a === b;
}
// ── ECharts lifecycle ──────────────────────────────────────────────
const chartContainer = ref(null);
const chartInstance = shallowRef(null);
onMounted(() => {
    if (!chartContainer.value)
        return;
    chartInstance.value = echarts.init(chartContainer.value);
    renderChart();
    // ECharts doesn't auto-resize on container changes — we hook it up
    // explicitly with a ResizeObserver.
    if (typeof ResizeObserver !== 'undefined' && chartContainer.value) {
        resizeObserver = new ResizeObserver(() => chartInstance.value?.resize());
        resizeObserver.observe(chartContainer.value);
    }
});
let resizeObserver = null;
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
function renderChart() {
    if (!chartInstance.value)
        return;
    try {
        const option = buildEChartsOption(localHeader.value, localXAxis.value, localYAxis.value, localSeries.value, parseOverrideSafely(localOverride.value));
        chartInstance.value.setOption(option, true);
    }
    catch (e) {
        // Render errors shouldn't blow up the editor — log and keep the
        // previous render visible.
        console.warn('ChartView render failed', e);
    }
}
function parseOverrideSafely(raw) {
    if (!raw.trim())
        return null;
    try {
        const parsed = JSON.parse(raw);
        return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
            ? parsed
            : null;
    }
    catch {
        return null;
    }
}
// ── Schema → ECharts option mapping ────────────────────────────────
function buildEChartsOption(header, xAxis, yAxis, series, override) {
    const option = {
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
function buildAxisOption(axis, side, series) {
    const type = axis?.type ?? (side === 'x' ? 'category' : 'value');
    const out = { type };
    if (axis?.label)
        out.name = axis.label;
    if (axis?.min !== undefined)
        out.min = axis.min;
    if (axis?.max !== undefined)
        out.max = axis.max;
    // ECharts category axes need a `data` array. Either use the
    // explicit `categories` list, or infer from the first series'
    // x values (works for line/bar/area/candlestick).
    if (type === 'category' && side === 'x') {
        if (axis && axis.categories.length > 0) {
            out.data = [...axis.categories];
        }
        else if (series.length > 0) {
            const data = series[0].data;
            out.data = data.map((pt) => Array.isArray(pt) ? String(pt[0]) : String(pt.x ?? pt.t ?? ''));
        }
    }
    return out;
}
function buildSeriesOption(s, header) {
    const echartsType = mapToEchartsSeriesType(header.chartType);
    const out = {
        name: s.name,
        type: echartsType,
        data: mapSeriesData(s.data, header.chartType),
    };
    if (s.color)
        out.itemStyle = { color: s.color };
    if (header.chartType === 'area')
        out.areaStyle = {};
    if (header.chartType === 'line' && header.smooth)
        out.smooth = true;
    if (header.chartType === 'area' && header.smooth)
        out.smooth = true;
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
function mapToEchartsSeriesType(type) {
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
function mapSeriesData(data, chartType) {
    switch (chartType) {
        case 'line':
        case 'bar':
        case 'area':
        case 'scatter':
            return data.map((pt) => Array.isArray(pt) ? [pt[0], pt[1]] : [pt.x, pt.y]);
        case 'pie':
        case 'donut':
            return data.map((pt) => Array.isArray(pt)
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
function deepMerge(base, override) {
    const out = { ...base };
    for (const [k, v] of Object.entries(override)) {
        if (v && typeof v === 'object' && !Array.isArray(v)
            && out[k] && typeof out[k] === 'object' && !Array.isArray(out[k])) {
            out[k] = deepMerge(out[k], v);
        }
        else {
            out[k] = v;
        }
    }
    return out;
}
// ── Side-panel reactive handlers ───────────────────────────────────
function updateHeader(key, value) {
    localHeader.value = { ...localHeader.value, [key]: value };
    emitDoc();
}
function updateXAxisType(type) {
    if (!localXAxis.value)
        return;
    localXAxis.value = { ...localXAxis.value, type };
    emitDoc();
}
function updateYAxisType(type) {
    if (!localYAxis.value)
        return;
    localYAxis.value = { ...localYAxis.value, type };
    emitDoc();
}
function updateSeriesColor(idx, color) {
    const next = [...localSeries.value];
    next[idx] = { ...next[idx], color: color || undefined };
    localSeries.value = next;
    emitDoc();
}
const showsAxes = computed(() => !isNamedValueShaped(localHeader.value.chartType));
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    mode: 'editor',
    meta: () => ({}),
});
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['chart-view--inline']} */ ;
/** @type {__VLS_StyleScopedClasses['chart-canvas']} */ ;
/** @type {__VLS_StyleScopedClasses['chart-view--embedded']} */ ;
/** @type {__VLS_StyleScopedClasses['chart-canvas']} */ ;
/** @type {__VLS_StyleScopedClasses['chart-sidebar']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: (['chart-view', `chart-view--${__VLS_ctx.mode}`]) },
});
if (__VLS_ctx.isEditor) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "chart-toolbar" },
    });
    const __VLS_0 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.localHeader.chartType),
        options: (__VLS_ctx.CHART_TYPE_OPTIONS),
        ...{ class: "w-44" },
    }));
    const __VLS_2 = __VLS_1({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.localHeader.chartType),
        options: (__VLS_ctx.CHART_TYPE_OPTIONS),
        ...{ class: "w-44" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    let __VLS_4;
    let __VLS_5;
    let __VLS_6;
    const __VLS_7 = {
        'onUpdate:modelValue': ((v) => { if (v)
            __VLS_ctx.onChartTypeChange(v); })
    };
    var __VLS_3;
    const __VLS_8 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.localHeader.title ?? ''),
        placeholder: "Title",
        ...{ class: "flex-1" },
    }));
    const __VLS_10 = __VLS_9({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.localHeader.title ?? ''),
        placeholder: "Title",
        ...{ class: "flex-1" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_9));
    let __VLS_12;
    let __VLS_13;
    let __VLS_14;
    const __VLS_15 = {
        'onUpdate:modelValue': ((v) => __VLS_ctx.updateHeader('title', v || undefined))
    };
    var __VLS_11;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "chart-main" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div)({
    ref: "chartContainer",
    ...{ class: "chart-canvas" },
});
/** @type {typeof __VLS_ctx.chartContainer} */ ;
if (__VLS_ctx.isEditor) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.aside, __VLS_intrinsicElements.aside)({
        ...{ class: "chart-sidebar" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({
        ...{ class: "chart-sidebar-h" },
    });
    const __VLS_16 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.localHeader.subtitle ?? ''),
        placeholder: "Subtitle",
        label: "Subtitle",
    }));
    const __VLS_18 = __VLS_17({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.localHeader.subtitle ?? ''),
        placeholder: "Subtitle",
        label: "Subtitle",
    }, ...__VLS_functionalComponentArgsRest(__VLS_17));
    let __VLS_20;
    let __VLS_21;
    let __VLS_22;
    const __VLS_23 = {
        'onUpdate:modelValue': ((v) => __VLS_ctx.updateHeader('subtitle', v || undefined))
    };
    var __VLS_19;
    const __VLS_24 = {}.VCheckbox;
    /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
    // @ts-ignore
    const __VLS_25 = __VLS_asFunctionalComponent(__VLS_24, new __VLS_24({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.localHeader.legend),
        label: "Legend",
    }));
    const __VLS_26 = __VLS_25({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.localHeader.legend),
        label: "Legend",
    }, ...__VLS_functionalComponentArgsRest(__VLS_25));
    let __VLS_28;
    let __VLS_29;
    let __VLS_30;
    const __VLS_31 = {
        'onUpdate:modelValue': ((v) => __VLS_ctx.updateHeader('legend', v))
    };
    var __VLS_27;
    if (['bar', 'area', 'line'].includes(__VLS_ctx.localHeader.chartType)) {
        const __VLS_32 = {}.VCheckbox;
        /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
        // @ts-ignore
        const __VLS_33 = __VLS_asFunctionalComponent(__VLS_32, new __VLS_32({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.localHeader.stacked),
            label: "Stacked",
        }));
        const __VLS_34 = __VLS_33({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.localHeader.stacked),
            label: "Stacked",
        }, ...__VLS_functionalComponentArgsRest(__VLS_33));
        let __VLS_36;
        let __VLS_37;
        let __VLS_38;
        const __VLS_39 = {
            'onUpdate:modelValue': ((v) => __VLS_ctx.updateHeader('stacked', v))
        };
        var __VLS_35;
    }
    if (['line', 'area'].includes(__VLS_ctx.localHeader.chartType)) {
        const __VLS_40 = {}.VCheckbox;
        /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
        // @ts-ignore
        const __VLS_41 = __VLS_asFunctionalComponent(__VLS_40, new __VLS_40({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.localHeader.smooth),
            label: "Smooth",
        }));
        const __VLS_42 = __VLS_41({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.localHeader.smooth),
            label: "Smooth",
        }, ...__VLS_functionalComponentArgsRest(__VLS_41));
        let __VLS_44;
        let __VLS_45;
        let __VLS_46;
        const __VLS_47 = {
            'onUpdate:modelValue': ((v) => __VLS_ctx.updateHeader('smooth', v))
        };
        var __VLS_43;
    }
    if (__VLS_ctx.showsAxes && __VLS_ctx.localXAxis) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({
            ...{ class: "chart-sidebar-h" },
        });
        const __VLS_48 = {}.VSelect;
        /** @type {[typeof __VLS_components.VSelect, ]} */ ;
        // @ts-ignore
        const __VLS_49 = __VLS_asFunctionalComponent(__VLS_48, new __VLS_48({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.localXAxis.type),
            options: (__VLS_ctx.AXIS_TYPE_OPTIONS),
            label: "Type",
        }));
        const __VLS_50 = __VLS_49({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.localXAxis.type),
            options: (__VLS_ctx.AXIS_TYPE_OPTIONS),
            label: "Type",
        }, ...__VLS_functionalComponentArgsRest(__VLS_49));
        let __VLS_52;
        let __VLS_53;
        let __VLS_54;
        const __VLS_55 = {
            'onUpdate:modelValue': ((v) => { if (v)
                __VLS_ctx.updateXAxisType(v); })
        };
        var __VLS_51;
    }
    if (__VLS_ctx.showsAxes && __VLS_ctx.localYAxis) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({
            ...{ class: "chart-sidebar-h" },
        });
        const __VLS_56 = {}.VSelect;
        /** @type {[typeof __VLS_components.VSelect, ]} */ ;
        // @ts-ignore
        const __VLS_57 = __VLS_asFunctionalComponent(__VLS_56, new __VLS_56({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.localYAxis.type),
            options: (__VLS_ctx.AXIS_TYPE_OPTIONS),
            label: "Type",
        }));
        const __VLS_58 = __VLS_57({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.localYAxis.type),
            options: (__VLS_ctx.AXIS_TYPE_OPTIONS),
            label: "Type",
        }, ...__VLS_functionalComponentArgsRest(__VLS_57));
        let __VLS_60;
        let __VLS_61;
        let __VLS_62;
        const __VLS_63 = {
            'onUpdate:modelValue': ((v) => { if (v)
                __VLS_ctx.updateYAxisType(v); })
        };
        var __VLS_59;
    }
    if (__VLS_ctx.localSeries.length > 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({
            ...{ class: "chart-sidebar-h" },
        });
        for (const [s, idx] of __VLS_getVForSourceType((__VLS_ctx.localSeries))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                key: (s.name + idx),
                ...{ class: "chart-series-row" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "chart-series-name" },
            });
            (s.name);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
                ...{ onInput: ((e) => __VLS_ctx.updateSeriesColor(idx, e.target.value)) },
                type: "color",
                value: (s.color ?? '#3b82f6'),
                ...{ class: "chart-series-color" },
            });
        }
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({
        ...{ class: "chart-sidebar-h" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "chart-sidebar-hint" },
    });
    const __VLS_64 = {}.VTextarea;
    /** @type {[typeof __VLS_components.VTextarea, ]} */ ;
    // @ts-ignore
    const __VLS_65 = __VLS_asFunctionalComponent(__VLS_64, new __VLS_64({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.localOverride),
        placeholder: "{}",
        rows: (6),
        ...{ class: "font-mono text-xs" },
    }));
    const __VLS_66 = __VLS_65({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.localOverride),
        placeholder: "{}",
        rows: (6),
        ...{ class: "font-mono text-xs" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_65));
    let __VLS_68;
    let __VLS_69;
    let __VLS_70;
    const __VLS_71 = {
        'onUpdate:modelValue': ((v) => { __VLS_ctx.localOverride = v; __VLS_ctx.emitDoc(); })
    };
    var __VLS_67;
    if (__VLS_ctx.overrideError) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "chart-sidebar-error" },
        });
        (__VLS_ctx.overrideError);
    }
}
if (__VLS_ctx.isEditor) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "chart-data-hint" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
}
/** @type {__VLS_StyleScopedClasses['chart-view']} */ ;
/** @type {__VLS_StyleScopedClasses['chart-toolbar']} */ ;
/** @type {__VLS_StyleScopedClasses['w-44']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['chart-main']} */ ;
/** @type {__VLS_StyleScopedClasses['chart-canvas']} */ ;
/** @type {__VLS_StyleScopedClasses['chart-sidebar']} */ ;
/** @type {__VLS_StyleScopedClasses['chart-sidebar-h']} */ ;
/** @type {__VLS_StyleScopedClasses['chart-sidebar-h']} */ ;
/** @type {__VLS_StyleScopedClasses['chart-sidebar-h']} */ ;
/** @type {__VLS_StyleScopedClasses['chart-sidebar-h']} */ ;
/** @type {__VLS_StyleScopedClasses['chart-series-row']} */ ;
/** @type {__VLS_StyleScopedClasses['chart-series-name']} */ ;
/** @type {__VLS_StyleScopedClasses['chart-series-color']} */ ;
/** @type {__VLS_StyleScopedClasses['chart-sidebar-h']} */ ;
/** @type {__VLS_StyleScopedClasses['chart-sidebar-hint']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['chart-sidebar-error']} */ ;
/** @type {__VLS_StyleScopedClasses['chart-data-hint']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VCheckbox: VCheckbox,
            VInput: VInput,
            VSelect: VSelect,
            VTextarea: VTextarea,
            isEditor: isEditor,
            localHeader: localHeader,
            localXAxis: localXAxis,
            localYAxis: localYAxis,
            localSeries: localSeries,
            localOverride: localOverride,
            overrideError: overrideError,
            emitDoc: emitDoc,
            CHART_TYPE_OPTIONS: CHART_TYPE_OPTIONS,
            AXIS_TYPE_OPTIONS: AXIS_TYPE_OPTIONS,
            onChartTypeChange: onChartTypeChange,
            chartContainer: chartContainer,
            updateHeader: updateHeader,
            updateXAxisType: updateXAxisType,
            updateYAxisType: updateYAxisType,
            updateSeriesColor: updateSeriesColor,
            showsAxes: showsAxes,
        };
    },
    __typeEmits: {},
    __typeProps: {},
    props: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeEmits: {},
    __typeProps: {},
    props: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=ChartView.vue.js.map