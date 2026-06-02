import { type Ref } from 'vue';
import type { CacheStatsDto } from '@vance/generated';
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
export declare function useCacheStats(): {
    stats: Ref<CacheStatsDto | null>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    load: (processId: string) => Promise<void>;
    reset: () => void;
};
//# sourceMappingURL=useCacheStats.d.ts.map