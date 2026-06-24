import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import * as echarts from 'echarts/core';
import { BarChart, LineChart } from 'echarts/charts';
import { DataZoomComponent, GridComponent, LegendComponent, TitleComponent, TooltipComponent, } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { VAlert, VCard, VEmptyState, VSelect } from '@/components';
import { useUsageReport } from '@/composables/useUsageReport';
// Register ECharts modules. Mirrors ChartView.vue but only loads the
// two chart types this tab uses, so the bundle stays small.
echarts.use([
    LineChart,
    BarChart,
    GridComponent,
    TooltipComponent,
    LegendComponent,
    TitleComponent,
    DataZoomComponent,
    CanvasRenderer,
]);
const groupBy = ref('day');
const rangeDays = ref(30);
const { summary, byProject, byModel, loading, error, loadAll } = useUsageReport();
async function refresh() {
    const to = new Date();
    const from = new Date(to.getTime() - rangeDays.value * 24 * 60 * 60 * 1000);
    await loadAll({
        from: from.toISOString(),
        to: to.toISOString(),
        groupBy: groupBy.value,
    });
}
watch([groupBy, rangeDays], refresh);
onMounted(refresh);
// ── Time-series chart ────────────────────────────────────────────
const chartHost = ref(null);
const chartInstance = shallowRef(null);
watch(summary, async () => {
    await nextTick();
    renderChart();
});
onMounted(() => {
    window.addEventListener('resize', handleResize);
});
onBeforeUnmount(() => {
    window.removeEventListener('resize', handleResize);
    if (chartInstance.value) {
        chartInstance.value.dispose();
        chartInstance.value = null;
    }
});
function handleResize() {
    chartInstance.value?.resize();
}
function renderChart() {
    if (!chartHost.value)
        return;
    if (!chartInstance.value) {
        chartInstance.value = echarts.init(chartHost.value);
    }
    const report = summary.value;
    if (!report || !report.buckets || report.buckets.length === 0) {
        chartInstance.value.clear();
        return;
    }
    // Multi-currency: group buckets by currency, plot one cost series
    // per currency. Tokens are currency-agnostic — single series.
    // bucketStart arrives as a Date (jackson-jsr310 serializes ISO,
    // generated DTO has `Date`); normalize to its ISO key for matching.
    const keyOf = (d) => d ? new Date(d).toISOString() : null;
    const byCurrency = new Map();
    for (const b of report.buckets) {
        const cur = b.currency || '?';
        if (!byCurrency.has(cur))
            byCurrency.set(cur, []);
        byCurrency.get(cur).push(b);
    }
    const allTimes = Array.from(new Set(report.buckets.map((b) => keyOf(b.bucketStart)).filter((s) => !!s))).sort();
    const tokenSeries = {
        name: 'Tokens (in+out)',
        type: 'bar',
        yAxisIndex: 1,
        itemStyle: { color: '#94a3b8', opacity: 0.6 },
        data: allTimes.map((t) => {
            const sum = report.buckets
                .filter((b) => keyOf(b.bucketStart) === t)
                .reduce((acc, b) => acc + b.tokensIn + b.tokensOut, 0);
            return [t, sum];
        }),
    };
    const costSeries = Array.from(byCurrency.entries()).map(([cur, rows]) => ({
        name: `Cost (${cur})`,
        type: 'line',
        smooth: true,
        yAxisIndex: 0,
        symbol: 'circle',
        symbolSize: 6,
        data: allTimes.map((t) => {
            const row = rows.find((r) => keyOf(r.bucketStart) === t);
            return [t, row ? Number(row.costTotal.toFixed(4)) : 0];
        }),
    }));
    chartInstance.value.setOption({
        grid: { top: 32, left: 60, right: 60, bottom: 60 },
        legend: { top: 0 },
        tooltip: { trigger: 'axis' },
        xAxis: { type: 'time' },
        yAxis: [
            { type: 'value', name: 'Cost', position: 'left' },
            { type: 'value', name: 'Tokens', position: 'right', splitLine: { show: false } },
        ],
        dataZoom: [{ type: 'inside' }, { type: 'slider', height: 20, bottom: 10 }],
        series: [...costSeries, tokenSeries],
    }, true);
}
// ── Table helpers ────────────────────────────────────────────────
function fmtTokens(n) {
    if (n < 1_000)
        return String(n);
    if (n < 1_000_000)
        return `${(n / 1_000).toFixed(1)}k`;
    return `${(n / 1_000_000).toFixed(2)}M`;
}
function fmtCost(n, currency) {
    // 4 decimals for small numbers, 2 for big — micro-USD reads better
    // when you can see the cents.
    const fixed = n < 1 ? n.toFixed(4) : n.toFixed(2);
    return `${fixed} ${currency}`;
}
const totals = computed(() => {
    const out = { tokensIn: 0, tokensOut: 0, byCurrency: new Map() };
    if (!summary.value)
        return out;
    for (const b of summary.value.buckets) {
        out.tokensIn += b.tokensIn;
        out.tokensOut += b.tokensOut;
        out.byCurrency.set(b.currency, (out.byCurrency.get(b.currency) || 0) + b.costTotal);
    }
    return out;
});
const hasData = computed(() => (summary.value?.buckets.length ?? 0) > 0
    || (byProject.value?.buckets.length ?? 0) > 0
    || (byModel.value?.buckets.length ?? 0) > 0);
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['usage-tab__totals']} */ ;
/** @type {__VLS_StyleScopedClasses['usage-tab__totals']} */ ;
/** @type {__VLS_StyleScopedClasses['usage-tab__table']} */ ;
/** @type {__VLS_StyleScopedClasses['usage-tab__table']} */ ;
/** @type {__VLS_StyleScopedClasses['usage-tab__table']} */ ;
/** @type {__VLS_StyleScopedClasses['usage-tab__table']} */ ;
/** @type {__VLS_StyleScopedClasses['num']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "usage-tab" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "usage-tab__controls" },
});
const __VLS_0 = {}.VSelect;
/** @type {[typeof __VLS_components.VSelect, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    modelValue: (__VLS_ctx.groupBy),
    options: ([
        { value: 'day', label: 'Per day' },
        { value: 'week', label: 'Per week' },
        { value: 'month', label: 'Per month' },
    ]),
    label: "Bucket",
}));
const __VLS_2 = __VLS_1({
    modelValue: (__VLS_ctx.groupBy),
    options: ([
        { value: 'day', label: 'Per day' },
        { value: 'week', label: 'Per week' },
        { value: 'month', label: 'Per month' },
    ]),
    label: "Bucket",
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
const __VLS_4 = {}.VSelect;
/** @type {[typeof __VLS_components.VSelect, ]} */ ;
// @ts-ignore
const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
    modelValue: (__VLS_ctx.rangeDays),
    options: ([
        { value: 7, label: 'Last 7 days' },
        { value: 30, label: 'Last 30 days' },
        { value: 90, label: 'Last 90 days' },
        { value: 365, label: 'Last 365 days' },
    ]),
    label: "Range",
}));
const __VLS_6 = __VLS_5({
    modelValue: (__VLS_ctx.rangeDays),
    options: ([
        { value: 7, label: 'Last 7 days' },
        { value: 30, label: 'Last 30 days' },
        { value: 90, label: 'Last 90 days' },
        { value: 365, label: 'Last 365 days' },
    ]),
    label: "Range",
}, ...__VLS_functionalComponentArgsRest(__VLS_5));
if (__VLS_ctx.error) {
    const __VLS_8 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
        type: "error",
    }));
    const __VLS_10 = __VLS_9({
        type: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_9));
    __VLS_11.slots.default;
    (__VLS_ctx.error);
    var __VLS_11;
}
if (!__VLS_ctx.loading && !__VLS_ctx.error && !__VLS_ctx.hasData) {
    const __VLS_12 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
        headline: "No usage data yet",
        body: "Once an LLM call records its tokens, this view fills in. Models without a pricing block in ai-models.yaml are skipped — add inputPerMTok / outputPerMTok to see costs.",
    }));
    const __VLS_14 = __VLS_13({
        headline: "No usage data yet",
        body: "Once an LLM call records its tokens, this view fills in. Models without a pricing block in ai-models.yaml are skipped — add inputPerMTok / outputPerMTok to see costs.",
    }, ...__VLS_functionalComponentArgsRest(__VLS_13));
}
else {
    const __VLS_16 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
        title: "Tokens & Cost over time",
    }));
    const __VLS_18 = __VLS_17({
        title: "Tokens & Cost over time",
    }, ...__VLS_functionalComponentArgsRest(__VLS_17));
    __VLS_19.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "usage-tab__totals" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "muted" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
    (__VLS_ctx.fmtTokens(__VLS_ctx.totals.tokensIn));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "muted" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
    (__VLS_ctx.fmtTokens(__VLS_ctx.totals.tokensOut));
    for (const [[cur, sum]] of __VLS_getVForSourceType((__VLS_ctx.totals.byCurrency))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            key: (cur),
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "muted" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
        (__VLS_ctx.fmtCost(sum, cur));
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div)({
        ref: "chartHost",
        ...{ class: "usage-tab__chart" },
    });
    /** @type {typeof __VLS_ctx.chartHost} */ ;
    var __VLS_19;
    const __VLS_20 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_21 = __VLS_asFunctionalComponent(__VLS_20, new __VLS_20({
        title: "Top projects",
    }));
    const __VLS_22 = __VLS_21({
        title: "Top projects",
    }, ...__VLS_functionalComponentArgsRest(__VLS_21));
    __VLS_23.slots.default;
    if (__VLS_ctx.byProject && __VLS_ctx.byProject.buckets.length) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.table, __VLS_intrinsicElements.table)({
            ...{ class: "usage-tab__table" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.thead, __VLS_intrinsicElements.thead)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.tr, __VLS_intrinsicElements.tr)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
            ...{ class: "num" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
            ...{ class: "num" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
            ...{ class: "num" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
            ...{ class: "num" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.tbody, __VLS_intrinsicElements.tbody)({});
        for (const [row, idx] of __VLS_getVForSourceType((__VLS_ctx.byProject.buckets))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.tr, __VLS_intrinsicElements.tr)({
                key: (`${row.key}-${row.currency}-${idx}`),
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({});
            (row.key || '—');
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "num" },
            });
            (row.calls);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "num" },
            });
            (__VLS_ctx.fmtTokens(row.tokensIn));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "num" },
            });
            (__VLS_ctx.fmtTokens(row.tokensOut));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "num" },
            });
            (__VLS_ctx.fmtCost(row.costTotal, row.currency));
        }
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "muted" },
        });
    }
    var __VLS_23;
    const __VLS_24 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_25 = __VLS_asFunctionalComponent(__VLS_24, new __VLS_24({
        title: "Top models",
    }));
    const __VLS_26 = __VLS_25({
        title: "Top models",
    }, ...__VLS_functionalComponentArgsRest(__VLS_25));
    __VLS_27.slots.default;
    if (__VLS_ctx.byModel && __VLS_ctx.byModel.buckets.length) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.table, __VLS_intrinsicElements.table)({
            ...{ class: "usage-tab__table" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.thead, __VLS_intrinsicElements.thead)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.tr, __VLS_intrinsicElements.tr)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
            ...{ class: "num" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
            ...{ class: "num" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
            ...{ class: "num" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.th, __VLS_intrinsicElements.th)({
            ...{ class: "num" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.tbody, __VLS_intrinsicElements.tbody)({});
        for (const [row, idx] of __VLS_getVForSourceType((__VLS_ctx.byModel.buckets))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.tr, __VLS_intrinsicElements.tr)({
                key: (`${row.key}-${row.currency}-${idx}`),
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({});
            (row.key || '—');
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "num" },
            });
            (row.calls);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "num" },
            });
            (__VLS_ctx.fmtTokens(row.tokensIn));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "num" },
            });
            (__VLS_ctx.fmtTokens(row.tokensOut));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "num" },
            });
            (__VLS_ctx.fmtCost(row.costTotal, row.currency));
        }
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "muted" },
        });
    }
    var __VLS_27;
}
/** @type {__VLS_StyleScopedClasses['usage-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['usage-tab__controls']} */ ;
/** @type {__VLS_StyleScopedClasses['usage-tab__totals']} */ ;
/** @type {__VLS_StyleScopedClasses['muted']} */ ;
/** @type {__VLS_StyleScopedClasses['muted']} */ ;
/** @type {__VLS_StyleScopedClasses['muted']} */ ;
/** @type {__VLS_StyleScopedClasses['usage-tab__chart']} */ ;
/** @type {__VLS_StyleScopedClasses['usage-tab__table']} */ ;
/** @type {__VLS_StyleScopedClasses['num']} */ ;
/** @type {__VLS_StyleScopedClasses['num']} */ ;
/** @type {__VLS_StyleScopedClasses['num']} */ ;
/** @type {__VLS_StyleScopedClasses['num']} */ ;
/** @type {__VLS_StyleScopedClasses['num']} */ ;
/** @type {__VLS_StyleScopedClasses['num']} */ ;
/** @type {__VLS_StyleScopedClasses['num']} */ ;
/** @type {__VLS_StyleScopedClasses['num']} */ ;
/** @type {__VLS_StyleScopedClasses['muted']} */ ;
/** @type {__VLS_StyleScopedClasses['usage-tab__table']} */ ;
/** @type {__VLS_StyleScopedClasses['num']} */ ;
/** @type {__VLS_StyleScopedClasses['num']} */ ;
/** @type {__VLS_StyleScopedClasses['num']} */ ;
/** @type {__VLS_StyleScopedClasses['num']} */ ;
/** @type {__VLS_StyleScopedClasses['num']} */ ;
/** @type {__VLS_StyleScopedClasses['num']} */ ;
/** @type {__VLS_StyleScopedClasses['num']} */ ;
/** @type {__VLS_StyleScopedClasses['num']} */ ;
/** @type {__VLS_StyleScopedClasses['muted']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            VCard: VCard,
            VEmptyState: VEmptyState,
            VSelect: VSelect,
            groupBy: groupBy,
            rangeDays: rangeDays,
            byProject: byProject,
            byModel: byModel,
            loading: loading,
            error: error,
            chartHost: chartHost,
            fmtTokens: fmtTokens,
            fmtCost: fmtCost,
            totals: totals,
            hasData: hasData,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=UsageCostTab.vue.js.map