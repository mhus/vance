import { ref } from 'vue';
import { brainFetch } from '@vance/shared';
/**
 * Read + admin-trigger events for one project. Mirrors {@link useSchedulers}
 * for the events subsystem — see {@code specification/events.md}.
 *
 * <p>The admin-trigger path here is intentionally separate from the
 * public {@code /brain/{tenant}/event/{project}/{event}} REST endpoint:
 * it goes through {@code /brain/{tenant}/project/{project}/events/{name}/trigger}
 * which is JWT-authenticated and bypasses the event's bearer-token
 * check. The Web-UI user doesn't need to know the event's bearer secret.
 */
export function useEvents() {
    const events = ref([]);
    const current = ref(null);
    const loading = ref(false);
    const busy = ref(false);
    const error = ref(null);
    const lastResult = ref(null);
    async function loadProject(projectId) {
        loading.value = true;
        error.value = null;
        try {
            events.value = await brainFetch('GET', `project/${encodeURIComponent(projectId)}/events`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load events.';
            events.value = [];
        }
        finally {
            loading.value = false;
        }
    }
    async function loadOne(projectId, name) {
        busy.value = true;
        error.value = null;
        try {
            current.value = await brainFetch('GET', `project/${encodeURIComponent(projectId)}/events/${encodeURIComponent(name)}`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load event.';
            current.value = null;
        }
        finally {
            busy.value = false;
        }
    }
    /**
     * POST the admin trigger. {@code payload} is the value forwarded to
     * the workflow as {@code params.payload}; pass {@code null} for no
     * payload. The server wraps it in an {@code AdminTriggerRequest}
     * envelope and the JSON body sent on the wire is
     * {@code {"payload": <value>}}.
     */
    async function trigger(projectId, name, payload) {
        busy.value = true;
        error.value = null;
        lastResult.value = null;
        try {
            const result = await brainFetch('POST', `project/${encodeURIComponent(projectId)}/events/${encodeURIComponent(name)}/trigger`, { body: { payload } });
            lastResult.value = result;
            return result;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Trigger failed.';
            throw e;
        }
        finally {
            busy.value = false;
        }
    }
    function clearCurrent() {
        current.value = null;
    }
    function clearLastResult() {
        lastResult.value = null;
    }
    return {
        events,
        current,
        loading,
        busy,
        error,
        lastResult,
        loadProject,
        loadOne,
        trigger,
        clearCurrent,
        clearLastResult,
    };
}
//# sourceMappingURL=useEvents.js.map