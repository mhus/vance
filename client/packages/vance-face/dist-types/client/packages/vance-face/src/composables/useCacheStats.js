import { ref } from 'vue';
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
export function useCacheStats() {
    const stats = ref(null);
    const loading = ref(false);
    const error = ref(null);
    async function load(processId) {
        if (!processId)
            return;
        loading.value = true;
        error.value = null;
        try {
            stats.value = await brainFetch('GET', `admin/processes/${encodeURIComponent(processId)}/cache-stats`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load cache stats.';
            stats.value = null;
        }
        finally {
            loading.value = false;
        }
    }
    function reset() {
        stats.value = null;
        error.value = null;
    }
    return { stats, loading, error, load, reset };
}
//# sourceMappingURL=useCacheStats.js.map