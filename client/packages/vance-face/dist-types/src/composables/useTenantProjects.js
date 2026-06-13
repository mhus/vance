import { ref } from 'vue';
import { brainFetch } from '@vance/shared';
import { pushProjectSnapshot } from '@/platform/faceliftShareSetup';
/**
 * Loads the tenant's project groups and projects once. Used by the project
 * selector in the document editor (and any future editor that needs the same
 * dropdown).
 */
export function useTenantProjects() {
    const groups = ref([]);
    const projects = ref([]);
    const loading = ref(false);
    const error = ref(null);
    async function reload() {
        loading.value = true;
        error.value = null;
        try {
            const data = await brainFetch('GET', 'projects');
            groups.value = data.groups ?? [];
            projects.value = data.projects ?? [];
            // No-op outside Facelift; otherwise persists the list so the
            // iOS Share-Extension's project picker stays in sync.
            pushProjectSnapshot(projects.value);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load projects.';
        }
        finally {
            loading.value = false;
        }
    }
    return { groups, projects, loading, error, reload };
}
//# sourceMappingURL=useTenantProjects.js.map