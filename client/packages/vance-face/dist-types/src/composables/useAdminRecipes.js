import { ref } from 'vue';
import { brainFetch } from '@vance/shared';
/**
 * Read + write recipes at tenant scope (when {@code projectId} is null
 * or empty) or project scope (when set). The "effective" list walks
 * Project → Tenant → Bundled and returns one entry per name with a
 * {@code source} field describing where the visible copy lives.
 */
export function useAdminRecipes() {
    const recipes = ref([]);
    const loading = ref(false);
    const busy = ref(false);
    const error = ref(null);
    async function loadEffective(projectId) {
        loading.value = true;
        error.value = null;
        try {
            const params = new URLSearchParams();
            if (projectId)
                params.set('projectId', projectId);
            const path = `admin/recipes/effective${params.toString() ? '?' + params.toString() : ''}`;
            recipes.value = await brainFetch('GET', path);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load recipes.';
            recipes.value = [];
        }
        finally {
            loading.value = false;
        }
    }
    function pathFor(projectId, name) {
        return projectId
            ? `admin/projects/${encodeURIComponent(projectId)}/recipes/${encodeURIComponent(name)}`
            : `admin/recipes/${encodeURIComponent(name)}`;
    }
    async function upsert(projectId, name, body) {
        busy.value = true;
        error.value = null;
        try {
            const saved = await brainFetch('PUT', pathFor(projectId, name), { body });
            await loadEffective(projectId);
            return saved;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to save recipe.';
            throw e;
        }
        finally {
            busy.value = false;
        }
    }
    async function remove(projectId, name) {
        busy.value = true;
        error.value = null;
        try {
            await brainFetch('DELETE', pathFor(projectId, name));
            await loadEffective(projectId);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to delete recipe.';
            throw e;
        }
        finally {
            busy.value = false;
        }
    }
    return { recipes, loading, busy, error, loadEffective, upsert, remove };
}
//# sourceMappingURL=useAdminRecipes.js.map