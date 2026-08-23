<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import * as echarts from 'echarts/core';
import { BarChart, LineChart } from 'echarts/charts';
import {
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
} from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import type { EChartsType } from 'echarts/core';
import type { UsageBucketDto } from '@vance/generated';
import { VAlert, VCard, VEmptyState, VSelect } from '@/components';
import { useUsageReport } from '@/composables/useUsageReport';
import UsageBreakdownTable from './UsageBreakdownTable.vue';

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

const groupBy = ref<'day' | 'week' | 'month'>('day');
const rangeDays = ref<number>(30);

const { summary, byProject, byModel, byCaller, byRecipe, loading, error, loadAll } =
  useUsageReport();

async function refresh(): Promise<void> {
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
const chartHost = ref<HTMLDivElement | null>(null);
const chartInstance = shallowRef<EChartsType | null>(null);

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

function handleResize(): void {
  chartInstance.value?.resize();
}

function renderChart(): void {
  if (!chartHost.value) return;
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
  const keyOf = (d: Date | undefined): string | null =>
    d ? new Date(d).toISOString() : null;
  // Buckets without a currency come from models that have no pricing
  // block — they carry tokens but no cost, so they contribute to the
  // token series only and get no cost line of their own.
  const byCurrency = new Map<string, UsageBucketDto[]>();
  for (const b of report.buckets) {
    const cur = b.currency;
    if (!cur) continue;
    if (!byCurrency.has(cur)) byCurrency.set(cur, []);
    byCurrency.get(cur)!.push(b);
  }
  const allTimes = Array.from(
    new Set(report.buckets.map((b) => keyOf(b.bucketStart)).filter((s): s is string => !!s)),
  ).sort();

  const tokenSeries = {
    name: 'Tokens (in+out)',
    type: 'bar' as const,
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
    type: 'line' as const,
    smooth: true,
    yAxisIndex: 0,
    symbol: 'circle',
    symbolSize: 6,
    data: allTimes.map((t) => {
      const row = rows.find((r) => keyOf(r.bucketStart) === t);
      return [t, row ? Number(row.costTotal.toFixed(4)) : 0];
    }),
  }));

  chartInstance.value.setOption(
    {
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
    },
    true,
  );
}

// ── Table helpers ────────────────────────────────────────────────
function fmtTokens(n: number): string {
  if (n < 1_000) return String(n);
  if (n < 1_000_000) return `${(n / 1_000).toFixed(1)}k`;
  return `${(n / 1_000_000).toFixed(2)}M`;
}

function fmtCost(n: number, currency: string): string {
  // No currency ⇒ the model has no pricing block. The row is real
  // (tokens were burned), the cost is simply unknown — say so instead
  // of printing a misleading 0.
  if (!currency) return 'n/a';
  // 4 decimals for small numbers, 2 for big — micro-USD reads better
  // when you can see the cents.
  const fixed = n < 1 ? n.toFixed(4) : n.toFixed(2);
  return `${fixed} ${currency}`;
}

const totals = computed<{ tokensIn: number; tokensOut: number; byCurrency: Map<string, number> }>(() => {
  const out = { tokensIn: 0, tokensOut: 0, byCurrency: new Map<string, number>() };
  if (!summary.value) return out;
  for (const b of summary.value.buckets) {
    out.tokensIn += b.tokensIn;
    out.tokensOut += b.tokensOut;
    if (b.currency) {
      out.byCurrency.set(b.currency, (out.byCurrency.get(b.currency) || 0) + b.costTotal);
    }
  }
  return out;
});

const hasData = computed<boolean>(() =>
  (summary.value?.buckets.length ?? 0) > 0
  || (byProject.value?.buckets.length ?? 0) > 0
  || (byModel.value?.buckets.length ?? 0) > 0
  || (byCaller.value?.buckets.length ?? 0) > 0
  || (byRecipe.value?.buckets.length ?? 0) > 0,
);

/**
 * How much of the window the amount above actually covers, plus what went
 * wrong alongside it.
 *
 * A sum that silently omits every model without a pricing block reads as a
 * complete figure and is not one. An incomplete number with a footnote is
 * usable; an incomplete number without one is worse than no number.
 */
const coverage = computed<{
  calls: number;
  unpriced: number;
  pricedPct: number;
  failed: number;
}>(() => {
  let calls = 0;
  let unpriced = 0;
  let failed = 0;
  for (const b of summary.value?.buckets ?? []) {
    calls += b.calls;
    unpriced += b.unpricedCalls ?? 0;
    failed += b.callsFailed ?? 0;
  }
  const pricedPct = calls > 0 ? Math.round(((calls - unpriced) / calls) * 100) : 100;
  return { calls, unpriced, pricedPct, failed };
});

/**
 * Where the drill-down stops. Totals go back as far as the tenant does;
 * per-call rows expire. Saying so beats offering a drill-down that comes
 * back empty.
 */
const detailHorizon = computed<string | null>(() => {
  const iso = summary.value?.detailHorizon;
  if (!iso) return null;
  return new Date(iso).toLocaleDateString();
});
</script>

<template>
  <div class="usage-tab">
    <div class="usage-tab__controls">
      <VSelect
        v-model="groupBy"
        :options="[
          { value: 'day', label: 'Per day' },
          { value: 'week', label: 'Per week' },
          { value: 'month', label: 'Per month' },
        ]"
        label="Bucket"
      />
      <VSelect
        v-model="rangeDays"
        :options="[
          { value: 7, label: 'Last 7 days' },
          { value: 30, label: 'Last 30 days' },
          { value: 90, label: 'Last 90 days' },
          { value: 365, label: 'Last 365 days' },
        ]"
        label="Range"
      />
    </div>

    <VAlert v-if="error" type="error">{{ error }}</VAlert>

    <VEmptyState
      v-if="!loading && !error && !hasData"
      headline="No usage data yet"
      body="Once an LLM call records its tokens, this view fills in. Models without a pricing block still show their tokens and calls, but their cost reads n/a — add pricing.inputPerMTok / pricing.outputPerMTok to the model YAML to see costs."
    />

    <template v-else>
      <VCard title="Tokens & Cost over time">
        <div class="usage-tab__totals">
          <div>
            <span class="muted">Input</span>
            <strong>{{ fmtTokens(totals.tokensIn) }}</strong>
          </div>
          <div>
            <span class="muted">Output</span>
            <strong>{{ fmtTokens(totals.tokensOut) }}</strong>
          </div>
          <div v-for="[cur, sum] in totals.byCurrency" :key="cur">
            <span class="muted">Cost</span>
            <strong>{{ fmtCost(sum, cur) }}</strong>
          </div>
        </div>
        <p class="muted usage-tab__hint">
          <template v-if="coverage.unpriced > 0">
            Covers {{ coverage.pricedPct }}% of {{ coverage.calls }} calls —
            {{ coverage.unpriced }} ran on a model with no price in the catalog and
            contribute tokens but no amount. Add a <code>pricing:</code> block to those
            model entries (a genuinely free local model declares an explicit zero).
          </template>
          <template v-else>
            Covers all {{ coverage.calls }} calls in this window.
          </template>
          <template v-if="coverage.failed > 0">
            {{ coverage.failed }} attempts failed; they are counted but not added to the
            amount.
          </template>
        </p>
        <p v-if="detailHorizon" class="muted usage-tab__hint">
          Totals reach back further than the drill-down: per-call detail exists from
          {{ detailHorizon }} onwards, before that only daily sums.
        </p>
        <div ref="chartHost" class="usage-tab__chart" />
      </VCard>

      <VCard title="Top projects">
        <UsageBreakdownTable
          label="Project"
          :rows="byProject?.buckets ?? []"
          empty-text="No project data in this window."
          :fmt-tokens="fmtTokens"
          :fmt-cost="fmtCost"
        />
      </VCard>

      <VCard title="Top models">
        <UsageBreakdownTable
          label="Model"
          :rows="byModel?.buckets ?? []"
          empty-text="No model data in this window."
          :fmt-tokens="fmtTokens"
          :fmt-cost="fmtCost"
        />
      </VCard>

      <VCard title="By caller">
        <p class="muted usage-tab__hint">
          Who is spending. Autonomous work shows up here even when nobody is
          watching a chat. Think-engines appear under their own name; the rest
          name themselves — <code>_light</code> for single-shot helper calls
          (discovery, follow-up, title generation), <code>_triage</code>,
          <code>_compaction</code> for memory upkeep, <code>_fenchurch</code>
          for images, <code>_rag</code> for embeddings.
        </p>
        <UsageBreakdownTable
          label="Caller"
          :rows="byCaller?.buckets ?? []"
          empty-text="No caller data in this window."
          :fmt-tokens="fmtTokens"
          :fmt-cost="fmtCost"
        />
      </VCard>

      <VCard title="By recipe">
        <UsageBreakdownTable
          label="Recipe"
          :rows="byRecipe?.buckets ?? []"
          empty-text="No recipe data in this window."
          :fmt-tokens="fmtTokens"
          :fmt-cost="fmtCost"
        />
      </VCard>
    </template>
  </div>
</template>

<style scoped>
.usage-tab {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.usage-tab__controls {
  display: flex;
  gap: 0.75rem;
  align-items: end;
  flex-wrap: wrap;
}
.usage-tab__chart {
  width: 100%;
  height: 320px;
}
.usage-tab__totals {
  display: flex;
  gap: 1.5rem;
  margin-bottom: 0.75rem;
  flex-wrap: wrap;
}
.usage-tab__totals > div {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}
.usage-tab__totals strong {
  font-size: 1.25rem;
  font-variant-numeric: tabular-nums;
}
.usage-tab__hint {
  margin-bottom: 0.5rem;
}
.muted {
  color: color-mix(in oklab, var(--color-base-content) 60%, transparent);
  font-size: 0.875rem;
}
</style>
