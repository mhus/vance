import { type Ref } from 'vue';
import type { ProjectKitsCatalogDto } from '@vance/generated';
/**
 * Loads the tenant-wide project-kits catalog
 * ({@code GET /brain/{tenant}/admin/project-kits/catalog}) so the
 * project-create dialog can offer a typed picker.
 *
 * <p>Spec: {@code specification/project-kits-catalog.md} §4.2.
 */
export declare function useProjectKitsCatalog(): {
    catalog: Ref<ProjectKitsCatalogDto | null>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    load: () => Promise<void>;
};
//# sourceMappingURL=useProjectKitsCatalog.d.ts.map