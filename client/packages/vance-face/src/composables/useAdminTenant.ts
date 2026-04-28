import { ref, type Ref } from 'vue';
import type { TenantDto, TenantUpdateRequest } from '@vance/generated';
import { brainFetch } from '@vance/shared';

/**
 * Read + update of the caller's own tenant. {@code name} is immutable;
 * {@code title} and {@code enabled} are editable.
 */
export function useAdminTenant(): {
  tenant: Ref<TenantDto | null>;
  loading: Ref<boolean>;
  saving: Ref<boolean>;
  error: Ref<string | null>;
  reload: () => Promise<void>;
  save: (patch: TenantUpdateRequest) => Promise<void>;
} {
  const tenant = ref<TenantDto | null>(null);
  const loading = ref(false);
  const saving = ref(false);
  const error = ref<string | null>(null);

  async function reload(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      tenant.value = await brainFetch<TenantDto>('GET', 'admin/tenant');
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load tenant.';
    } finally {
      loading.value = false;
    }
  }

  async function save(patch: TenantUpdateRequest): Promise<void> {
    saving.value = true;
    error.value = null;
    try {
      tenant.value = await brainFetch<TenantDto>('PUT', 'admin/tenant', { body: patch });
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to save tenant.';
      throw e;
    } finally {
      saving.value = false;
    }
  }

  return { tenant, loading, saving, error, reload, save };
}
