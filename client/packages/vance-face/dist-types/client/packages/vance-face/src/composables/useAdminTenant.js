import { ref } from 'vue';
import { brainFetch } from '@vance/shared';
/**
 * Read + update of the caller's own tenant. {@code name} is immutable;
 * {@code title} and {@code enabled} are editable.
 */
export function useAdminTenant() {
    const tenant = ref(null);
    const loading = ref(false);
    const saving = ref(false);
    const error = ref(null);
    async function reload() {
        loading.value = true;
        error.value = null;
        try {
            tenant.value = await brainFetch('GET', 'admin/tenant');
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load tenant.';
        }
        finally {
            loading.value = false;
        }
    }
    async function save(patch) {
        saving.value = true;
        error.value = null;
        try {
            tenant.value = await brainFetch('PUT', 'admin/tenant', { body: patch });
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to save tenant.';
            throw e;
        }
        finally {
            saving.value = false;
        }
    }
    return { tenant, loading, saving, error, reload, save };
}
//# sourceMappingURL=useAdminTenant.js.map