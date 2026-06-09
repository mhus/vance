import { ref } from 'vue';
import { brainFetch } from '@vance/shared';
export function useRag() {
    const status = ref(null);
    const loading = ref(false);
    const busy = ref(false);
    const error = ref(null);
    const lastResult = ref(null);
    const searchHits = ref([]);
    const searchQuery = ref('');
    const searching = ref(false);
    const searchError = ref(null);
    const searched = ref(false);
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
    async function search(projectId, query) {
        searching.value = true;
        searchError.value = null;
        try {
            const response = await brainFetch('POST', `projects/${encodeURIComponent(projectId)}/rag/search`, { body: { query } });
            searchHits.value = response.hits ?? [];
            searched.value = true;
        }
        catch (e) {
            searchError.value = e instanceof Error ? e.message : 'Search failed.';
            searchHits.value = [];
            searched.value = true;
        }
        finally {
            searching.value = false;
        }
    }
    function clear() {
        status.value = null;
        error.value = null;
        lastResult.value = null;
        searchHits.value = [];
        searchQuery.value = '';
        searchError.value = null;
        searched.value = false;
    }
    return {
        status,
        loading,
        busy,
        error,
        lastResult,
        searchHits,
        searchQuery,
        searching,
        searchError,
        searched,
        load,
        reindex,
        search,
        clear,
    };
}
//# sourceMappingURL=useRag.js.map