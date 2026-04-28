import { ref } from 'vue';
import { brainFetch } from '@vance/shared';
/**
 * CRUD on project groups. Server enforces "delete only if empty" via 409.
 */
export function useAdminProjectGroups() {
    const groups = ref([]);
    const loading = ref(false);
    const busy = ref(false);
    const error = ref(null);
    async function reload() {
        loading.value = true;
        error.value = null;
        try {
            groups.value = await brainFetch('GET', 'admin/project-groups');
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load project groups.';
        }
        finally {
            loading.value = false;
        }
    }
    async function create(req) {
        busy.value = true;
        error.value = null;
        try {
            const created = await brainFetch('POST', 'admin/project-groups', { body: req });
            await reload();
            return created;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to create group.';
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
            const saved = await brainFetch('PUT', `admin/project-groups/${encodeURIComponent(name)}`, { body: req });
            await reload();
            return saved;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to update group.';
            throw e;
        }
        finally {
            busy.value = false;
        }
    }
    async function remove(name) {
        busy.value = true;
        error.value = null;
        try {
            await brainFetch('DELETE', `admin/project-groups/${encodeURIComponent(name)}`);
            await reload();
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to delete group.';
            throw e;
        }
        finally {
            busy.value = false;
        }
    }
    return { groups, loading, busy, error, reload, create, update, remove };
}
//# sourceMappingURL=useAdminProjectGroups.js.map