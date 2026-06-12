import { computed, ref } from 'vue';
import { brainFetch } from '@vance/shared';
export function useClusterPods() {
    const cluster = ref(null);
    const loading = ref(false);
    const error = ref(null);
    // Defensive: tolerate a missing or non-array `pods` field on the
    // wire. The DTO declares it as a non-null List, but the empty-cluster
    // case on a fresh brain has been observed to deliver `pods=null` from
    // Jackson when @Builder.Default lost a race against the response
    // serializer — guarding here keeps `reduce`/`map` downstream from
    // throwing on the cluster tab.
    const pods = computed(() => {
        const raw = cluster.value?.pods;
        return Array.isArray(raw) ? raw : [];
    });
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