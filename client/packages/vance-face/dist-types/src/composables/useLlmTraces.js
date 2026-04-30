import { computed, ref } from 'vue';
import { brainFetch } from '@vance/shared';
/**
 * Reactive wrapper around the LLM-trace inspection endpoint. One
 * instance per Insights process-tab — paginates through the rows of
 * one process, exposes them grouped by {@code turnId}.
 *
 * <p>Group order: the API returns rows newest-first by {@code createdAt};
 * we preserve that order at the group level (the most recent round-trip
 * sits on top), but within each group legs run in their natural
 * {@code sequence} ascending order.
 */
export function useLlmTraces(pageSize = 50) {
    const items = ref([]);
    const page = ref(0);
    const totalCount = ref(0);
    const pageSizeRef = ref(pageSize);
    const loading = ref(false);
    const error = ref(null);
    async function loadPage(processId, p) {
        if (!processId)
            return;
        loading.value = true;
        error.value = null;
        try {
            const params = new URLSearchParams({
                page: String(p),
                size: String(pageSizeRef.value),
            });
            const data = await brainFetch('GET', `admin/processes/${encodeURIComponent(processId)}/llm-traces?${params}`);
            items.value = data.items ?? [];
            page.value = data.page;
            pageSizeRef.value = data.pageSize;
            totalCount.value = data.totalCount;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load LLM traces.';
            items.value = [];
            totalCount.value = 0;
        }
        finally {
            loading.value = false;
        }
    }
    function reset() {
        items.value = [];
        page.value = 0;
        totalCount.value = 0;
        error.value = null;
    }
    /**
     * Group rows by {@code turnId} preserving the API's newest-first
     * order at the group level. Rows without a {@code turnId} (legacy /
     * malformed) collapse into a single synthetic group keyed by their
     * {@code id} so they stay visible.
     */
    const turns = computed(() => {
        const order = [];
        const groups = new Map();
        for (const row of items.value) {
            const key = row.turnId ?? `__loose:${row.id ?? Math.random()}`;
            if (!groups.has(key)) {
                order.push(key);
                groups.set(key, []);
            }
            groups.get(key).push(row);
        }
        return order.map((key) => {
            const legs = (groups.get(key) ?? []).sort((a, b) => (a.sequence ?? 0) - (b.sequence ?? 0));
            return summarise(key, legs);
        });
    });
    return {
        items,
        turns,
        page,
        totalCount,
        pageSize: pageSizeRef,
        loading,
        error,
        loadPage,
        reset,
    };
}
function summarise(turnId, legs) {
    // Header fields prefer the OUTPUT row — that's where tokens / elapsedMs
    // were stamped. Fall back to the first leg's createdAt for the timestamp.
    let modelAlias = null;
    let tokensIn = null;
    let tokensOut = null;
    let elapsedMs = null;
    let toolCallCount = 0;
    let startedAt = null;
    for (const leg of legs) {
        if (!startedAt && leg.createdAt) {
            startedAt = String(leg.createdAt);
        }
        if (leg.direction === 'output') {
            if (leg.modelAlias)
                modelAlias = leg.modelAlias;
            if (leg.tokensIn != null)
                tokensIn = leg.tokensIn;
            if (leg.tokensOut != null)
                tokensOut = leg.tokensOut;
            if (leg.elapsedMs != null)
                elapsedMs = leg.elapsedMs;
        }
        if (leg.direction === 'tool_call') {
            toolCallCount++;
        }
    }
    return {
        turnId,
        legs,
        startedAt,
        modelAlias,
        tokensIn,
        tokensOut,
        elapsedMs,
        toolCallCount,
    };
}
//# sourceMappingURL=useLlmTraces.js.map