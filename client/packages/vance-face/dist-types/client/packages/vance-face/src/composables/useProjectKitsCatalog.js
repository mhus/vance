import { ref } from 'vue';
import { brainFetch } from '@vance/shared';
/**
 * Loads the tenant-wide project-kits catalog
 * ({@code GET /brain/{tenant}/admin/project-kits/catalog}) so the
 * project-create dialog can offer a typed picker.
 *
 * <p>Spec: {@code specification/project-kits-catalog.md} §4.2.
 */
export function useProjectKitsCatalog() {
    const catalog = ref(null);
    const loading = ref(false);
    const error = ref(null);
    async function load() {
        loading.value = true;
        error.value = null;
        try {
            catalog.value = await brainFetch('GET', 'admin/project-kits/catalog');
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load project-kits catalog.';
            catalog.value = null;
        }
        finally {
            loading.value = false;
        }
    }
    return { catalog, loading, error, load };
}
//# sourceMappingURL=useProjectKitsCatalog.js.map