import { type Ref } from 'vue';
import { type KitExportRequestDto, type KitImportRequestDto, type KitManifestDto, type KitOperationResultDto } from '@vance/generated';
/**
 * Read + mutate the active kit on a single project. Wraps the
 * {@code KitAdminController} endpoints under
 * {@code /brain/{tenant}/admin/kits/...}.
 */
export declare function useKitAdmin(): {
    manifest: Ref<KitManifestDto | null>;
    loading: Ref<boolean>;
    busy: Ref<boolean>;
    error: Ref<string | null>;
    lastResult: Ref<KitOperationResultDto | null>;
    load: (projectId: string) => Promise<void>;
    install: (projectId: string, request: KitImportRequestDto) => Promise<KitOperationResultDto>;
    update: (projectId: string, request: KitImportRequestDto) => Promise<KitOperationResultDto>;
    apply: (projectId: string, request: KitImportRequestDto) => Promise<KitOperationResultDto>;
    export: (projectId: string, request: KitExportRequestDto) => Promise<KitOperationResultDto>;
    clear: () => void;
};
//# sourceMappingURL=useKitAdmin.d.ts.map