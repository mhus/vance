import { ref } from 'vue';
import { brainFetch, RestError } from '@vance/shared';
/**
 * State + actions for the tool-templates wizard:
 *
 * - {@link loadCatalog}  — tenant-wide catalog from `_tenant/config/tool-templates.yaml`
 * - {@link describe}     — resolves one template (clones the kit + parses
 *                          `template.yaml`); call this when the user picks
 *                          a row before showing the form
 * - {@link loadApplied}  — last applied state for (template, projectId);
 *                          returns `null` when the template has never been
 *                          applied (so the wizard can fall back to the
 *                          template's declared defaults). PASSWORD inputs
 *                          are structurally absent from the response.
 * - {@link apply}        — POST inputs, kit is applied, returns the
 *                          installer stats + postInstall hook
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
    async function loadApplied(name, projectId) {
        try {
            return await brainFetch('GET', `admin/tool-templates/${encodeURIComponent(name)}/applied`
                + `?projectId=${encodeURIComponent(projectId)}`);
        }
        catch (e) {
            // 404 = "template never applied here yet" — caller falls back
            // to the template's declared defaults. Anything else is surfaced.
            if (e instanceof RestError && e.status === 404)
                return null;
            throw e;
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
    return { catalog, loading, busy, error, loadCatalog, describe, loadApplied, apply };
}
//# sourceMappingURL=useToolTemplates.js.map