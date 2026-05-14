import { ref, type Ref } from 'vue';
import type { ProjectKitsCatalogDto } from '@vance/generated';
import { brainFetch } from '@vance/shared';

/**
 * Loads the tenant-wide project-kits catalog
 * ({@code GET /brain/{tenant}/admin/project-kits/catalog}) so the
 * project-create dialog can offer a typed picker.
 *
 * <p>Spec: {@code specification/project-kits-catalog.md} §4.2.
 */
export function useProjectKitsCatalog(): {
  catalog: Ref<ProjectKitsCatalogDto | null>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  load: () => Promise<void>;
} {
  const catalog = ref<ProjectKitsCatalogDto | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function load(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      catalog.value = await brainFetch<ProjectKitsCatalogDto>(
        'GET',
        'admin/project-kits/catalog',
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load project-kits catalog.';
      catalog.value = null;
    } finally {
      loading.value = false;
    }
  }

  return { catalog, loading, error, load };
}
