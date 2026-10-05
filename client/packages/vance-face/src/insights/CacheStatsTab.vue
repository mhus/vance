<script setup lang="ts">
import { computed, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { CacheStatsProviderDto } from '@vance/generated';
import { VAlert, VButton, VCard, VEmptyState } from '@/components';
import { useCacheStats } from '@/composables/useCacheStats';

const props = defineProps<{
  /** Mongo id of the process whose cache-stats to load. */
  processId: string;
}>();

const { t } = useI18n();

const { stats, loading, error, load, reset } = useCacheStats();

watch(
  () => props.processId,
  async (next) => {
    if (!next) {
      reset();
      return;
    }
    await load(next);
  },
  { immediate: true },
);

/** Aggregate input including the cached portions — denominator of the
 *  hit-rate formula. Used to detect "no data" and to render absolute
 *  totals in the table. */
const totalInput = computed<number>(() => {
  if (!stats.value) return 0;
  return (
    stats.value.inputTokens
    + stats.value.cacheCreationInputTokens
    + stats.value.cacheReadInputTokens
  );
});

/** Hit-rate as a percentage, 0–100. Measured cache reads only. */
const hitRatePct = computed<number>(() => (stats.value ? stats.value.hitRate * 100 : 0));

/** Same, but including the estimate — the honest figure for tail-billing
 *  gateways. Always labeled as an estimate where shown. */
const hitRateEstimatePct = computed<number>(() =>
  (stats.value ? stats.value.hitRateIncludingEstimate : 0) * 100,
);

/** Only rendered when there is something to label — providers that itemize
 *  their cache never produce an estimate. */
const hasEstimate = computed<boolean>(() => (stats.value?.implicitCacheReadTokens ?? 0) > 0);

/** Cost-saved: cache-read tokens are billed at ~10% of input vs. ~100%
 *  if they had to come in fresh. Saved = cacheRead × 0.9. Display only,
 *  approximate ("~"); the actual price depends on model + tier. */
const tokensSaved = computed<number>(() => {
  if (!stats.value) return 0;
  return Math.round(stats.value.cacheReadInputTokens * 0.9);
});

/** Per-wire slices, biggest call count first — "which provider produced
 *  these cache numbers?" is the question this tab used to be unable to
 *  answer. */
const providers = computed<CacheStatsProviderDto[]>(() =>
  [...(stats.value?.providers ?? [])].sort((a, b) => b.roundTrips - a.roundTrips),
);

function fmt(n: number): string {
  if (n < 1_000) return String(n);
  if (n < 1_000_000) return `${(n / 1_000).toFixed(1)}k`;
  return `${(n / 1_000_000).toFixed(1)}M`;
}

function fmtPct(n: number): string {
  return `${n.toFixed(1)}%`;
}

/** Pick a colour bucket for the hit-rate bar — green for ≥70% (the
 *  spec target), amber for 40–70%, red below. */
const hitRateClass = computed<string>(() => {
  if (hitRatePct.value >= 70) return 'rate-bar--good';
  if (hitRatePct.value >= 40) return 'rate-bar--mid';
  return 'rate-bar--bad';
});

/**
 * Why this wire shows zero cache reads — but only when the catalog knows the
 * model's cacheable minimum. Prompts below that floor are processed uncached
 * even with markers, and zero counters are then the expected result, not a
 * fault. {@code null} when the catalog has no entry: say nothing rather than
 * guess.
 */
function tooSmallHint(p: CacheStatsProviderDto): string | null {
  if (!p.minCacheableInputTokens || p.roundTrips === 0) return null;
  const total =
    p.inputTokens
    + p.cacheCreationInputTokens
    + p.cacheReadInputTokens
    + (p.implicitCacheReadTokens ?? 0);
  const avgPerCall = total / p.roundTrips;
  return avgPerCall < p.minCacheableInputTokens
    ? t('insights.cacheStats.minCacheableHint', { tokens: p.minCacheableInputTokens })
    : null;
}

function providerLabel(p: CacheStatsProviderDto): string {
  return p.providerModel || t('insights.cacheStats.unknownProvider');
}

async function reload(): Promise<void> {
  await load(props.processId);
}
</script>

<template>
  <div class="flex flex-col gap-3">
    <VAlert v-if="error" variant="error">
      <span>{{ error }}</span>
    </VAlert>

    <div v-if="loading && !stats" class="opacity-70">
      {{ t('insights.cacheStats.loading') }}
    </div>

    <VEmptyState
      v-else-if="!loading && (!stats || stats.roundTrips === 0)"
      :headline="t('insights.cacheStats.emptyHeadline')"
      :body="t('insights.cacheStats.emptyBody')"
    />

    <template v-else-if="stats">
      <!-- Headline card: the hit-rate. The single number that answers
           'is caching paying off?'. The bar uses three buckets so a
           glance at the colour communicates good/mediocre/bad. -->
      <VCard :title="t('insights.cacheStats.headlineTitle')">
        <div class="flex flex-col gap-3">
          <div class="flex items-baseline gap-3">
            <span class="text-4xl font-semibold">{{ fmtPct(hitRatePct) }}</span>
            <span class="text-sm opacity-70">
              {{ t('insights.cacheStats.headlineSub', { rounds: stats.roundTrips }) }}
            </span>
          </div>
          <div class="rate-bar">
            <div
              class="rate-bar__fill"
              :class="hitRateClass"
              :style="{ width: `${Math.min(hitRatePct, 100)}%` }"
            />
          </div>
          <p v-if="hasEstimate" class="text-sm opacity-70">
            {{ t('insights.cacheStats.headlineEstimate', { pct: fmtPct(hitRateEstimatePct) }) }}
          </p>
          <p class="text-xs opacity-60">
            {{ t('insights.cacheStats.headlineHint') }}
          </p>
        </div>
      </VCard>

      <!-- Token breakdown: the counters that make up totalInput + the
           output total. Measured cache reads and the labeled estimate stay
           in separate rows — they are never summed into one number. -->
      <VCard :title="t('insights.cacheStats.breakdownTitle')">
        <dl class="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
          <dt class="opacity-60">{{ t('insights.cacheStats.cacheRead') }}</dt>
          <dd class="font-mono">{{ fmt(stats.cacheReadInputTokens) }}</dd>

          <template v-if="hasEstimate">
            <dt class="opacity-60">{{ t('insights.cacheStats.implicitCacheRead') }}</dt>
            <dd class="font-mono">≈ {{ fmt(stats.implicitCacheReadTokens) }}</dd>
          </template>

          <dt class="opacity-60">{{ t('insights.cacheStats.cacheCreate') }}</dt>
          <dd class="font-mono">{{ fmt(stats.cacheCreationInputTokens) }}</dd>

          <dt class="opacity-60">{{ t('insights.cacheStats.uncachedInput') }}</dt>
          <dd class="font-mono">{{ fmt(stats.inputTokens) }}</dd>

          <dt class="opacity-60">{{ t('insights.cacheStats.totalInput') }}</dt>
          <dd class="font-mono font-semibold">{{ fmt(totalInput) }}</dd>

          <dt class="opacity-60">{{ t('insights.cacheStats.outputTokens') }}</dt>
          <dd class="font-mono">{{ fmt(stats.outputTokens) }}</dd>

          <dt class="opacity-60">{{ t('insights.cacheStats.tokensSaved') }}</dt>
          <dd class="font-mono">~{{ fmt(tokensSaved) }}</dd>
        </dl>
        <p class="text-xs opacity-60 mt-3">
          {{ t('insights.cacheStats.savingsHint') }}
        </p>
      </VCard>

      <!-- Per-wire slice: which provider produced these numbers. One row
           per providerModel seen in the process, fallback chain included. -->
      <VCard v-if="providers.length" :title="t('insights.cacheStats.providersTitle')">
        <table class="w-full text-sm provider-table">
          <thead>
            <tr>
              <th class="text-left opacity-60">{{ t('insights.cacheStats.colProvider') }}</th>
              <th class="text-right opacity-60">{{ t('insights.cacheStats.colRounds') }}</th>
              <th class="text-right opacity-60">{{ t('insights.cacheStats.colHitRate') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in providers" :key="p.providerModel || 'unknown'">
              <td>
                <div class="font-mono">{{ providerLabel(p) }}</div>
                <div v-if="tooSmallHint(p)" class="text-xs opacity-60">
                  {{ tooSmallHint(p) }}
                </div>
              </td>
              <td class="text-right font-mono">{{ fmt(p.roundTrips) }}</td>
              <td class="text-right font-mono">{{ fmtPct(p.hitRate * 100) }}</td>
            </tr>
          </tbody>
        </table>
      </VCard>

      <div class="flex justify-end">
        <VButton variant="ghost" :disabled="loading" @click="reload">
          {{ t('insights.cacheStats.reload') }}
        </VButton>
      </div>
    </template>
  </div>
</template>

<style scoped>
.rate-bar {
  height: 0.5rem;
  border-radius: 9999px;
  background: rgba(127, 127, 127, 0.2);
  overflow: hidden;
}
.rate-bar__fill {
  height: 100%;
  border-radius: 9999px;
  transition: width 0.3s ease;
}
.rate-bar--good { background: #4caf50; }
.rate-bar--mid  { background: #ffb74d; }
.rate-bar--bad  { background: #e57373; }
.provider-table th,
.provider-table td {
  padding: 0.375rem 0.5rem;
  border-bottom: 1px solid rgba(127, 127, 127, 0.2);
}
</style>