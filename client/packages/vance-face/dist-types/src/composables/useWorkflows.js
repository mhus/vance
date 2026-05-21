import { ref } from 'vue';
import { brainFetch } from '@vance/shared';
export function useWorkflows() {
    const workflows = ref([]);
    const current = ref(null);
    const runs = ref([]);
    const loading = ref(false);
    const busy = ref(false);
    const error = ref(null);
    const lastResult = ref(null);
    async function loadProject(projectId) {
        loading.value = true;
        error.value = null;
        try {
            workflows.value = await brainFetch('GET', `project/${encodeURIComponent(projectId)}/workflows`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load workflows.';
            workflows.value = [];
        }
        finally {
            loading.value = false;
        }
    }
    async function loadOne(projectId, name) {
        busy.value = true;
        error.value = null;
        try {
            current.value = await brainFetch('GET', `project/${encodeURIComponent(projectId)}/workflows/${encodeURIComponent(name)}`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load workflow.';
            current.value = null;
        }
        finally {
            busy.value = false;
        }
    }
    /**
     * GET {@code /workflows/runs?workflow=<name>} — newest first, max 100.
     * Server-side limit is enforced by MagratheaWorkflowController.LIST_LIMIT.
     */
    async function loadRuns(projectId, name) {
        error.value = null;
        try {
            runs.value = await brainFetch('GET', `project/${encodeURIComponent(projectId)}/workflows/runs?workflow=${encodeURIComponent(name)}`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load runs.';
            runs.value = [];
        }
    }
    async function start(projectId, name, params) {
        busy.value = true;
        error.value = null;
        lastResult.value = null;
        try {
            const result = await brainFetch('POST', `project/${encodeURIComponent(projectId)}/workflows/${encodeURIComponent(name)}/start`, { body: { params: params ?? {}, startedBy: null } });
            lastResult.value = result;
            return result;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Start failed.';
            throw e;
        }
        finally {
            busy.value = false;
        }
    }
    function clearCurrent() {
        current.value = null;
        runs.value = [];
    }
    function clearLastResult() {
        lastResult.value = null;
    }
    return {
        workflows,
        current,
        runs,
        loading,
        busy,
        error,
        lastResult,
        loadProject,
        loadOne,
        loadRuns,
        start,
        clearCurrent,
        clearLastResult,
    };
}
//# sourceMappingURL=useWorkflows.js.map