import { ref } from 'vue';
import { brainFetch } from '@vance/shared';
/**
 * State + actions for the tool-templates wizard. Three calls:
 *
 * - {@link loadCatalog}      — tenant-wide catalog from `_tenant/config/tool-templates.yaml`
 * - {@link describe}         — resolves one template (clones the kit + parses
 *                              `template.yaml`); call this when the user picks
 *                              a row before showing the form
 * - {@link apply}             — POST inputs, kit is applied, returns the
 *                              installer stats + postInstall hook
 */
export function useToolTemplates() {
    const catalog = ref([]);
    const loading = ref(false);
    const busy = ref(false);
    const error = ref(null);
    async function loadCatalog() {
        loading.value = true;
        error.value = null;
        try {
            const res = await brainFetch('GET', 'admin/tool-templates/catalog');
            catalog.value = (res.templates ?? []).slice();
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load catalog.';
            catalog.value = [];
        }
        finally {
            loading.value = false;
        }
    }
    async function describe(name) {
        busy.value = true;
        error.value = null;
        try {
            return await brainFetch('GET', `admin/tool-templates/${encodeURIComponent(name)}`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to describe template.';
            throw e;
        }
        finally {
            busy.value = false;
        }
    }
    async function apply(name, body) {
        busy.value = true;
        error.value = null;
        try {
            return await brainFetch('POST', `admin/tool-templates/${encodeURIComponent(name)}/apply`, { body });
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Apply failed.';
            throw e;
        }
        finally {
            busy.value = false;
        }
    }
    return { catalog, loading, busy, error, loadCatalog, describe, apply };
}
//# sourceMappingURL=useToolTemplates.js.map