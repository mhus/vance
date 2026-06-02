import { ref } from 'vue';
import { brainFetch } from '@vance/shared';
/** Admin CRUD on teams. */
export function useAdminTeams() {
    const teams = ref([]);
    const loading = ref(false);
    const busy = ref(false);
    const error = ref(null);
    async function reload() {
        loading.value = true;
        error.value = null;
        try {
            teams.value = await brainFetch('GET', 'admin/teams');
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load teams.';
            teams.value = [];
        }
        finally {
            loading.value = false;
        }
    }
    async function create(req) {
        busy.value = true;
        error.value = null;
        try {
            const created = await brainFetch('POST', 'admin/teams', { body: req });
            await reload();
            return created;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to create team.';
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
            const saved = await brainFetch('PUT', `admin/teams/${encodeURIComponent(name)}`, { body: req });
            await reload();
            return saved;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to update team.';
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
            await brainFetch('DELETE', `admin/teams/${encodeURIComponent(name)}`);
            await reload();
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to delete team.';
            throw e;
        }
        finally {
            busy.value = false;
        }
    }
    return { teams, loading, busy, error, reload, create, update, remove };
}
//# sourceMappingURL=useAdminTeams.js.map