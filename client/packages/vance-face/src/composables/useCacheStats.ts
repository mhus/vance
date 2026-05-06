import { ref, type Ref } from 'vue';
import type { CacheStatsDto } from '@vance/generated';
import { brainFetch } from '@vance/shared';

/**
 * Reactive wrapper around the per-process cache-stats endpoint.
 *
 * <p>Hits {@code GET /admin/processes/{id}/cache-stats} on demand and
 * caches the result. {@code reload} re-fetches; {@code reset} clears.
 * Stats are aggregated over <i>every</i> OUTPUT row of the process —
 * the value reflects the entire LLM history, not just the current
 * page of the trace list.
 *
 * <p>Stats become meaningful only when {@code tracing.llm} has been
 * on for the process; otherwise the row count is zero and every
 * counter sits at zero. The UI surfaces that case as an empty state.
 */
export function useCacheStats(): {
  stats: Ref<CacheStatsDto | null>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  load: (processId: string) => Promise<void>;
  reset: () => void;
} {
  const stats = ref<CacheStatsDto | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function load(processId: string): Promise<void> {
    if (!processId) return;
    loading.value = true;
    error.value = null;
    try {
      stats.value = await brainFetch<CacheStatsDto>(
        'GET',
        `admin/processes/${encodeURIComponent(processId)}/cache-stats`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load cache stats.';
      stats.value = null;
    } finally {
      loading.value = false;
    }
  }

  function reset(): void {
    stats.value = null;
    error.value = null;
  }

  return { stats, loading, error, load, reset };
}
