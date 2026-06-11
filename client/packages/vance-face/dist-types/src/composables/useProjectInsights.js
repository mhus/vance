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
    async function setOverride(projectId, instanceId, enabled) {
        loading.value = true;
        error.value = null;
        try {
            instances.value = await brainFetch('PUT', `admin/projects/${encodeURIComponent(projectId)}/insights/zarniwoop/${encodeURIComponent(instanceId)}/override`, { body: { enabled } });
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to set override.';
        }
        finally {
            loading.value = false;
        }
    }
    async function clearOverride(projectId, instanceId) {
        loading.value = true;
        error.value = null;
        try {
            instances.value = await brainFetch('DELETE', `admin/projects/${encodeURIComponent(projectId)}/insights/zarniwoop/${encodeURIComponent(instanceId)}/override`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to clear override.';
        }
        finally {
            loading.value = false;
        }
    }
    return { instances, loading, error, load, clear, setOverride, clearOverride };
}
//# sourceMappingURL=useProjectInsights.js.map