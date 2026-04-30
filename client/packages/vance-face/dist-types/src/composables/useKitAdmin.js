import { ref } from 'vue';
import { KitImportMode, } from '@vance/generated';
import { brainFetch } from '@vance/shared';
/**
 * Read + mutate the active kit on a single project. Wraps the
 * {@code KitAdminController} endpoints under
 * {@code /brain/{tenant}/admin/kits/...}.
 */
export function useKitAdmin() {
    const manifest = ref(null);
    const loading = ref(false);
    const busy = ref(false);
    const error = ref(null);
    const lastResult = ref(null);
    function clear() {
        manifest.value = null;
        error.value = null;
        lastResult.value = null;
    }
    async function load(projectId) {
        loading.value = true;
        error.value = null;
        try {
            const response = await brainFetch('GET', `admin/kits/${encodeURIComponent(projectId)}/status`);
            manifest.value = response ?? null;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load kit status.';
            manifest.value = null;
        }
        finally {
            loading.value = false;
        }
    }
    async function runImport(verb, mode, projectId, request) {
        busy.value = true;
        error.value = null;
        try {
            const body = { ...request, projectId, mode };
            const path = `admin/kits/${encodeURIComponent(projectId)}/${verb}`;
            const result = await brainFetch('POST', path, { body });
            lastResult.value = result;
            await load(projectId);
            return result;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : `Failed to ${verb} kit.`;
            throw e;
        }
        finally {
            busy.value = false;
        }
    }
    async function install(projectId, request) {
        return runImport('install', KitImportMode.INSTALL, projectId, request);
    }
    async function update(projectId, request) {
        return runImport('update', KitImportMode.UPDATE, projectId, request);
    }
    async function apply(projectId, request) {
        return runImport('apply', KitImportMode.APPLY, projectId, request);
    }
    async function exportKit(projectId, request) {
        busy.value = true;
        error.value = null;
        try {
            const path = `admin/kits/${encodeURIComponent(projectId)}/export`;
            const result = await brainFetch('POST', path, { body: request });
            lastResult.value = result;
            await load(projectId);
            return result;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to export kit.';
            throw e;
        }
        finally {
            busy.value = false;
        }
    }
    return {
        manifest,
        loading,
        busy,
        error,
        lastResult,
        load,
        install,
        update,
        apply,
        export: exportKit,
        clear,
    };
}
//# sourceMappingURL=useKitAdmin.js.map