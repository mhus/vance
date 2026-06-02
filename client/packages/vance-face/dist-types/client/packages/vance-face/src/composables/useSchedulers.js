import { ref } from 'vue';
import { brainFetch } from '@vance/shared';
/**
 * Read + write schedulers at one project. Mirrors {@code useAdminServerTools}
 * for the scheduler subsystem — see {@code specification/scheduler.md} §10.
 */
export function useSchedulers() {
    const schedulers = ref([]);
    const current = ref(null);
    const events = ref([]);
    const loading = ref(false);
    const busy = ref(false);
    const error = ref(null);
    async function loadProject(projectId) {
        loading.value = true;
        error.value = null;
        try {
            schedulers.value = await brainFetch('GET', `project/${encodeURIComponent(projectId)}/scheduler`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load schedulers.';
            schedulers.value = [];
        }
        finally {
            loading.value = false;
        }
    }
    async function loadOne(projectId, name) {
        busy.value = true;
        error.value = null;
        try {
            current.value = await brainFetch('GET', `project/${encodeURIComponent(projectId)}/scheduler/${encodeURIComponent(name)}`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load scheduler.';
            current.value = null;
        }
        finally {
            busy.value = false;
        }
    }
    async function loadEvents(projectId, name, limit = 50) {
        try {
            events.value = await brainFetch('GET', `project/${encodeURIComponent(projectId)}/scheduler/${encodeURIComponent(name)}/events?limit=${limit}`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load events.';
            events.value = [];
        }
    }
    async function save(projectId, name, yaml) {
        busy.value = true;
        error.value = null;
        try {
            const body = { yaml };
            const saved = await brainFetch('PUT', `project/${encodeURIComponent(projectId)}/scheduler/${encodeURIComponent(name)}`, { body });
            current.value = saved;
            await loadProject(projectId);
            return saved;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Save failed.';
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
            await brainFetch('DELETE', `project/${encodeURIComponent(projectId)}/scheduler/${encodeURIComponent(name)}`);
            await loadProject(projectId);
            current.value = null;
            events.value = [];
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Delete failed.';
            throw e;
        }
        finally {
            busy.value = false;
        }
    }
    async function refresh(projectId) {
        busy.value = true;
        error.value = null;
        try {
            const r = await brainFetch('POST', `project/${encodeURIComponent(projectId)}/scheduler/refresh`);
            await loadProject(projectId);
            return r.registered;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Refresh failed.';
            throw e;
        }
        finally {
            busy.value = false;
        }
    }
    function clearCurrent() {
        current.value = null;
        events.value = [];
    }
    return {
        schedulers,
        current,
        events,
        loading,
        busy,
        error,
        loadProject,
        loadOne,
        loadEvents,
        save,
        remove,
        refresh,
        clearCurrent,
    };
}
//# sourceMappingURL=useSchedulers.js.map