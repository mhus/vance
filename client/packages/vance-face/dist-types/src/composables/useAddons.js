import { ref } from 'vue';
import { brainFetch } from '@vance/shared';
export function useAddonInsights() {
    const addons = ref([]);
    const loading = ref(false);
    const error = ref(null);
    async function load() {
        loading.value = true;
        error.value = null;
        try {
            addons.value = await brainFetch('GET', 'admin/addons');
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load addons.';
            addons.value = [];
        }
        finally {
            loading.value = false;
        }
    }
    function clear() {
        addons.value = [];
        error.value = null;
    }
    return { addons, loading, error, load, clear };
}
//# sourceMappingURL=useAddons.js.map