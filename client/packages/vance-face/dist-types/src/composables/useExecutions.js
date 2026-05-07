import { reactive, ref } from 'vue';
import { brainFetch } from '@vance/shared';
export function useExecutions() {
    const list = ref([]);
    const loading = ref(false);
    const error = ref(null);
    const filters = reactive({
        onlyRunning: false,
        ownerLabel: null,
    });
    async function load(projectId) {
        loading.value = true;
        error.value = null;
        try {
            const params = new URLSearchParams();
            if (filters.onlyRunning)
                params.set('onlyRunning', 'true');
            if (filters.ownerLabel)
                params.set('ownerLabel', filters.ownerLabel);
            const qs = params.toString();
            list.value = await brainFetch('GET', `projects/${encodeURIComponent(projectId)}/executions/list${qs ? `?${qs}` : ''}`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load executions.';
            list.value = [];
        }
        finally {
            loading.value = false;
        }
    }
    function clear() {
        list.value = [];
        error.value = null;
    }
    return { list, loading, error, filters, load, clear };
}
export function useExecutionTail() {
    const tail = ref(null);
    const loading = ref(false);
    const error = ref(null);
    async function load(projectId, id, n, stream) {
        loading.value = true;
        error.value = null;
        try {
            const params = new URLSearchParams({ n: String(n), stream });
            tail.value = await brainFetch('GET', `projects/${encodeURIComponent(projectId)}/executions/${encodeURIComponent(id)}/tail?${params}`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load tail.';
            tail.value = null;
        }
        finally {
            loading.value = false;
        }
    }
    function clear() {
        tail.value = null;
        error.value = null;
    }
    return { tail, loading, error, load, clear };
}
//# sourceMappingURL=useExecutions.js.map