import { ref } from 'vue';
import { brainFetch } from '@vance/shared';
export function useEffectiveRecipes() {
    const recipes = ref([]);
    const loading = ref(false);
    const error = ref(null);
    async function load(projectId) {
        loading.value = true;
        error.value = null;
        try {
            recipes.value = await brainFetch('GET', `admin/projects/${encodeURIComponent(projectId)}/insights/recipes`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load recipes.';
            recipes.value = [];
        }
        finally {
            loading.value = false;
        }
    }
    function clear() {
        recipes.value = [];
        error.value = null;
    }
    return { recipes, loading, error, load, clear };
}
export function useEffectiveTools() {
    const tools = ref([]);
    const loading = ref(false);
    const error = ref(null);
    async function load(projectId) {
        loading.value = true;
        error.value = null;
        try {
            tools.value = await brainFetch('GET', `admin/projects/${encodeURIComponent(projectId)}/insights/tools`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load tools.';
            tools.value = [];
        }
        finally {
            loading.value = false;
        }
    }
    function clear() {
        tools.value = [];
        error.value = null;
    }
    return { tools, loading, error, load, clear };
}
export function useZarniwoopInsights() {
    const instances = ref([]);
    const loading = ref(false);
    const error = ref(null);
    async function load(projectId) {
        loading.value = true;
        error.value = null;
        try {
            instances.value = await brainFetch('GET', `admin/projects/${encodeURIComponent(projectId)}/insights/zarniwoop`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load search providers.';
            instances.value = [];
        }
        finally {
            loading.value = false;
        }
    }
    function clear() {
        instances.value = [];
        error.value = null;
    }
    return { instances, loading, error, load, clear };
}
//# sourceMappingURL=useProjectInsights.js.map