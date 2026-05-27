import { ref } from 'vue';
import { brainFetch, brainFetchBlob } from '@vance/shared';
/**
 * Read-only access to the insights inspector endpoints. One composable
 * per concept (sessions / processes / chat / memory / marvin tree)
 * keeps the component glue thin and the loading flags scoped.
 */
export function useInsightsSessions() {
    const sessions = ref([]);
    const loading = ref(false);
    const error = ref(null);
    async function reload(filter) {
        loading.value = true;
        error.value = null;
        try {
            const params = new URLSearchParams();
            if (filter.projectId)
                params.set('projectId', filter.projectId);
            if (filter.userId)
                params.set('userId', filter.userId);
            if (filter.status)
                params.set('status', filter.status);
            const path = `admin/sessions${params.toString() ? '?' + params.toString() : ''}`;
            sessions.value = await brainFetch('GET', path);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load sessions.';
            sessions.value = [];
        }
        finally {
            loading.value = false;
        }
    }
    return { sessions, loading, error, reload };
}
export function useSessionProcesses() {
    const processes = ref([]);
    const loading = ref(false);
    const error = ref(null);
    function clear() { processes.value = []; error.value = null; }
    async function load(sessionId) {
        loading.value = true;
        error.value = null;
        try {
            processes.value = await brainFetch('GET', `admin/sessions/${encodeURIComponent(sessionId)}/processes`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load processes.';
            processes.value = [];
        }
        finally {
            loading.value = false;
        }
    }
    return { processes, loading, error, load, clear };
}
export function useProcessDetail() {
    const process = ref(null);
    const loading = ref(false);
    const error = ref(null);
    function clear() { process.value = null; error.value = null; }
    async function load(processId) {
        loading.value = true;
        error.value = null;
        try {
            process.value = await brainFetch('GET', `admin/processes/${encodeURIComponent(processId)}`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load process.';
            process.value = null;
        }
        finally {
            loading.value = false;
        }
    }
    return { process, loading, error, load, clear };
}
export function useProcessChat() {
    const messages = ref([]);
    const loading = ref(false);
    const error = ref(null);
    function clear() { messages.value = []; error.value = null; }
    async function load(processId) {
        loading.value = true;
        error.value = null;
        try {
            messages.value = await brainFetch('GET', `admin/processes/${encodeURIComponent(processId)}/chat`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load chat.';
            messages.value = [];
        }
        finally {
            loading.value = false;
        }
    }
    return { messages, loading, error, load, clear };
}
export function useProcessMemory() {
    const entries = ref([]);
    const loading = ref(false);
    const error = ref(null);
    function clear() { entries.value = []; error.value = null; }
    async function load(processId) {
        loading.value = true;
        error.value = null;
        try {
            entries.value = await brainFetch('GET', `admin/processes/${encodeURIComponent(processId)}/memory`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load memory.';
            entries.value = [];
        }
        finally {
            loading.value = false;
        }
    }
    return { entries, loading, error, load, clear };
}
export function useProcessPrakRuns() {
    const runs = ref([]);
    const loading = ref(false);
    const error = ref(null);
    function clear() { runs.value = []; error.value = null; }
    async function load(processId) {
        loading.value = true;
        error.value = null;
        try {
            runs.value = await brainFetch('GET', `admin/processes/${encodeURIComponent(processId)}/prak-runs`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load Prak runs.';
            runs.value = [];
        }
        finally {
            loading.value = false;
        }
    }
    return { runs, loading, error, load, clear };
}
export function useSessionClientTools() {
    const data = ref(null);
    const loading = ref(false);
    const error = ref(null);
    function clear() { data.value = null; error.value = null; }
    async function load(sessionId) {
        loading.value = true;
        error.value = null;
        try {
            data.value = await brainFetch('GET', `admin/sessions/${encodeURIComponent(sessionId)}/insights/client-tools`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load client tools.';
            data.value = null;
        }
        finally {
            loading.value = false;
        }
    }
    return { data, loading, error, load, clear };
}
export function useMarvinTree() {
    const nodes = ref([]);
    const loading = ref(false);
    const error = ref(null);
    function clear() { nodes.value = []; error.value = null; }
    async function load(processId) {
        loading.value = true;
        error.value = null;
        try {
            nodes.value = await brainFetch('GET', `admin/processes/${encodeURIComponent(processId)}/marvin-tree`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load Marvin tree.';
            nodes.value = [];
        }
        finally {
            loading.value = false;
        }
    }
    return { nodes, loading, error, load, clear };
}
/**
 * Download the session-scoped JSON-lines diagnostic bundle and trigger
 * a browser save dialog. The server picks the filename
 * (`session-{id}-{ts}.jsonl`) via `Content-Disposition`; the local
 * default name is only used as a defensive fallback.
 */
export async function downloadSessionExport(sessionId) {
    const { blob, filename } = await brainFetchBlob(`admin/sessions/${encodeURIComponent(sessionId)}/export.jsonl`);
    const url = URL.createObjectURL(blob);
    try {
        const a = document.createElement('a');
        a.href = url;
        a.download = filename ?? `session-${sessionId}.jsonl`;
        a.style.display = 'none';
        document.body.appendChild(a);
        a.click();
        a.remove();
    }
    finally {
        // Revoke on the next tick so Chrome has time to start the download
        // before we tear down the blob URL.
        setTimeout(() => URL.revokeObjectURL(url), 0);
    }
}
//# sourceMappingURL=useInsights.js.map