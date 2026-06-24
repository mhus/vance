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

const { summary, byProject, byModel, loading, error, loadAll } = useUsageReport();

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
  const byCurrency = new Map<string, UsageBucketDto[]>();
  for (const b of report.buckets) {
    const cur = b.currency || '?';
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
    out.byCurrency.set(b.currency, (out.byCurrency.get(b.currency) || 0) + b.costTotal);
  }
  return out;
});

const hasData = computed<boolean>(() =>
  (summary.value?.buckets.length ?? 0) > 0
  || (byProject.value?.buckets.length ?? 0) > 0
  || (byModel.value?.buckets.length ?? 0) > 0,
);
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
      body="Once an LLM call records its tokens, this view fills in. Models without a pricing block in ai-models.yaml are skipped — add inputPerMTok / outputPerMTok to see costs."
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
        <div ref="chartHost" class="usage-tab__chart" />
      </VCard>

      <VCard title="Top projects">
        <table class="usage-tab__table" v-if="byProject && byProject.buckets.length">
          <thead>
            <tr>
              <th>Project</th>
              <th class="num">Calls</th>
              <th class="num">Tokens in</th>
              <th class="num">Tokens out</th>
              <th class="num">Cost</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, idx) in byProject.buckets" :key="`${row.key}-${row.currency}-${idx}`">
              <td>{{ row.key || '—' }}</td>
              <td class="num">{{ row.calls }}</td>
              <td class="num">{{ fmtTokens(row.tokensIn) }}</td>
              <td class="num">{{ fmtTokens(row.tokensOut) }}</td>
              <td class="num">{{ fmtCost(row.costTotal, row.currency) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="muted">No project data in this window.</p>
      </VCard>

      <VCard title="Top models">
        <table class="usage-tab__table" v-if="byModel && byModel.buckets.length">
          <thead>
            <tr>
              <th>Model</th>
              <th class="num">Calls</th>
              <th class="num">Tokens in</th>
              <th class="num">Tokens out</th>
              <th class="num">Cost</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, idx) in byModel.buckets" :key="`${row.key}-${row.currency}-${idx}`">
              <td>{{ row.key || '—' }}</td>
              <td class="num">{{ row.calls }}</td>
              <td class="num">{{ fmtTokens(row.tokensIn) }}</td>
              <td class="num">{{ fmtTokens(row.tokensOut) }}</td>
              <td class="num">{{ fmtCost(row.costTotal, row.currency) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="muted">No model data in this window.</p>
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
.usage-tab__table {
  width: 100%;
  border-collapse: collapse;
  font-variant-numeric: tabular-nums;
}
.usage-tab__table th,
.usage-tab__table td {
  padding: 0.375rem 0.75rem;
  border-bottom: 1px solid hsl(var(--border) / 0.5);
  text-align: left;
}
.usage-tab__table th.num,
.usage-tab__table td.num {
  text-align: right;
}
.muted {
  color: hsl(var(--muted-foreground));
  font-size: 0.875rem;
}
</style>
