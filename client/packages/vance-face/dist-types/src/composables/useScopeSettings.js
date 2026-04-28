import { ref } from 'vue';
import { brainFetch } from '@vance/shared';
/**
 * Read + write settings on a single scope (e.g. tenant + name, or
 * project + name). Wraps the existing {@code AdminSettingsController}
 * endpoints.
 */
export function useScopeSettings() {
    const settings = ref([]);
    const loading = ref(false);
    const busy = ref(false);
    const error = ref(null);
    function clear() {
        settings.value = [];
        error.value = null;
    }
    async function load(referenceType, referenceId) {
        loading.value = true;
        error.value = null;
        try {
            const params = new URLSearchParams({ referenceType, referenceId });
            const list = await brainFetch('GET', `admin/settings?${params.toString()}`);
            settings.value = list.sort((a, b) => a.key.localeCompare(b.key));
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load settings.';
            settings.value = [];
        }
        finally {
            loading.value = false;
        }
    }
    async function upsert(referenceType, referenceId, key, value, type, description) {
        busy.value = true;
        error.value = null;
        try {
            const body = {
                value: value ?? undefined,
                type,
                description: description ?? undefined,
            };
            const path = `admin/settings/${encodeURIComponent(referenceType)}`
                + `/${encodeURIComponent(referenceId)}/${encodeURIComponent(key)}`;
            const saved = await brainFetch('PUT', path, { body });
            await load(referenceType, referenceId);
            return saved;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to save setting.';
            throw e;
        }
        finally {
            busy.value = false;
        }
    }
    async function remove(referenceType, referenceId, key) {
        busy.value = true;
        error.value = null;
        try {
            const path = `admin/settings/${encodeURIComponent(referenceType)}`
                + `/${encodeURIComponent(referenceId)}/${encodeURIComponent(key)}`;
            await brainFetch('DELETE', path);
            await load(referenceType, referenceId);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to delete setting.';
            throw e;
        }
        finally {
            busy.value = false;
        }
    }
    return { settings, loading, busy, error, load, upsert, remove, clear };
}
//# sourceMappingURL=useScopeSettings.js.map