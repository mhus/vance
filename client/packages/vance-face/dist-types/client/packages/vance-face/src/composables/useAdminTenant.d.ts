import { type Ref } from 'vue';
import type { TenantDto, TenantUpdateRequest } from '@vance/generated';
/**
 * Read + update of the caller's own tenant. {@code name} is immutable;
 * {@code title} and {@code enabled} are editable.
 */
export declare function useAdminTenant(): {
    tenant: Ref<TenantDto | null>;
    loading: Ref<boolean>;
    saving: Ref<boolean>;
    error: Ref<string | null>;
    reload: () => Promise<void>;
    save: (patch: TenantUpdateRequest) => Promise<void>;
};
//# sourceMappingURL=useAdminTenant.d.ts.map