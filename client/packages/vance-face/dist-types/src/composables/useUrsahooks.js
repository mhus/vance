import { ref } from 'vue';
import { brainFetch } from '@vance/shared';
/**
 * Read + write ursahooks for one project. Mirrors {@link useSchedulers}
 * for the ursahook subsystem — see {@code specification/ursahooks.md} §10.
 *
 * <p>Wire paths still use {@code /hooks/...} for backward compatibility
 * with existing clients (Brain controller URL paths were not renamed
 * during the Hook→Ursahook refactor).
 */
export function useUrsahooks() {
    const hooks = ref([]);
    const current = ref(null);
    const events = ref([]);
    const loading = ref(false);
    const busy = ref(false);
    const error = ref(null);
    async function loadProject(projectId) {
        loading.value = true;
        error.value = null;
        try {
            hooks.value = await brainFetch('GET', `project/${encodeURIComponent(projectId)}/hooks`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load ursahooks.';
            hooks.value = [];
        }
        finally {
            loading.value = false;
        }
    }
    async function loadOne(projectId, event, name) {
        busy.value = true;
        error.value = null;
        try {
            current.value = await brainFetch('GET', `project/${encodeURIComponent(projectId)}/hooks/${encodeURIComponent(event)}/${encodeURIComponent(name)}`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load ursahook.';
            current.value = null;
        }
        finally {
            busy.value = false;
        }
    }
    async function loadEvents(projectId, event, name, limit = 50) {
        try {
            events.value = await brainFetch('GET', `project/${encodeURIComponent(projectId)}/hooks/${encodeURIComponent(event)}/${encodeURIComponent(name)}/events?limit=${limit}`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load events.';
            events.value = [];
        }
    }
    async function save(projectId, event, name, yaml) {
        busy.value = true;
        error.value = null;
        try {
            const body = { yaml };
            const saved = await brainFetch('PUT', `project/${encodeURIComponent(projectId)}/hooks/${encodeURIComponent(event)}/${encodeURIComponent(name)}`, { body });
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
    async function remove(projectId, event, name) {
        busy.value = true;
        error.value = null;
        try {
            await brainFetch('DELETE', `project/${encodeURIComponent(projectId)}/hooks/${encodeURIComponent(event)}/${encodeURIComponent(name)}`);
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
            const r = await brainFetch('POST', `project/${encodeURIComponent(projectId)}/hooks/refresh`);
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
        hooks,
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
//# sourceMappingURL=useUrsahooks.js.map