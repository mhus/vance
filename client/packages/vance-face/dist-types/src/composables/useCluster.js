import { computed, ref } from 'vue';
import { brainFetch } from '@vance/shared';
export function useClusterPods() {
    const cluster = ref(null);
    const loading = ref(false);
    const error = ref(null);
    const pods = computed(() => cluster.value?.pods ?? []);
    async function load() {
        loading.value = true;
        error.value = null;
        try {
            cluster.value = await brainFetch('GET', 'admin/cluster/pods');
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load cluster pods.';
            cluster.value = null;
        }
        finally {
            loading.value = false;
        }
    }
    function clear() {
        cluster.value = null;
        error.value = null;
    }
    return { cluster, pods, loading, error, load, clear };
}
//# sourceMappingURL=useCluster.js.map