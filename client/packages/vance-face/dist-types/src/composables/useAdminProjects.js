import { ref } from 'vue';
import { brainFetch } from '@vance/shared';
/**
 * CRUD on projects. {@code remove} archives — sets status to ARCHIVED and
 * moves the project into the reserved "archived" group.
 */
export function useAdminProjects() {
    const projects = ref([]);
    const loading = ref(false);
    const busy = ref(false);
    const error = ref(null);
    async function reload() {
        loading.value = true;
        error.value = null;
        try {
            projects.value = await brainFetch('GET', 'admin/projects');
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load projects.';
        }
        finally {
            loading.value = false;
        }
    }
    async function create(req) {
        busy.value = true;
        error.value = null;
        try {
            const created = await brainFetch('POST', 'admin/projects', { body: req });
            await reload();
            return created;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to create project.';
            throw e;
        }
        finally {
            busy.value = false;
        }
    }
    async function update(name, req) {
        busy.value = true;
        error.value = null;
        try {
            const saved = await brainFetch('PUT', `admin/projects/${encodeURIComponent(name)}`, { body: req });
            await reload();
            return saved;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to update project.';
            throw e;
        }
        finally {
            busy.value = false;
        }
    }
    async function archive(name) {
        busy.value = true;
        error.value = null;
        try {
            const saved = await brainFetch('DELETE', `admin/projects/${encodeURIComponent(name)}`);
            await reload();
            return saved;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to archive project.';
            throw e;
        }
        finally {
            busy.value = false;
        }
    }
    return { projects, loading, busy, error, reload, create, update, archive };
}
//# sourceMappingURL=useAdminProjects.js.map