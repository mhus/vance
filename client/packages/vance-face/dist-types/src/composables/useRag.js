import { ref } from 'vue';
import { brainFetch } from '@vance/shared';
export function useRag() {
    const status = ref(null);
    const loading = ref(false);
    const busy = ref(false);
    const error = ref(null);
    const lastResult = ref(null);
    async function load(projectId) {
        loading.value = true;
        error.value = null;
        try {
            status.value = await brainFetch('GET', `projects/${encodeURIComponent(projectId)}/rag/status`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load RAG status.';
            status.value = null;
        }
        finally {
            loading.value = false;
        }
    }
    async function reindex(projectId, rebuild) {
        busy.value = true;
        error.value = null;
        try {
            lastResult.value = await brainFetch('POST', `projects/${encodeURIComponent(projectId)}/rag/reindex?rebuild=${rebuild ? 'true' : 'false'}`);
            // Refresh the status so the user sees the (possibly recreated) RAG row.
            await load(projectId);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Reindex failed.';
        }
        finally {
            busy.value = false;
        }
    }
    function clear() {
        status.value = null;
        error.value = null;
        lastResult.value = null;
    }
    return { status, loading, busy, error, lastResult, load, reindex, clear };
}
//# sourceMappingURL=useRag.js.map