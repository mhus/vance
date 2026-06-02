import { ref } from 'vue';
import { brainFetch } from '@vance/shared';
export function useClusterPods() {
    const pods = ref([]);
    const loading = ref(false);
    const error = ref(null);
    async function load() {
        loading.value = true;
        error.value = null;
        try {
            pods.value = await brainFetch('GET', 'admin/cluster/pods');
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load cluster pods.';
            pods.value = [];
        }
        finally {
            loading.value = false;
        }
    }
    function clear() {
        pods.value = [];
        error.value = null;
    }
    return { pods, loading, error, load, clear };
}
//# sourceMappingURL=useCluster.js.map