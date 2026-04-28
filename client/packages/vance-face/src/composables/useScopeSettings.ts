import { ref, type Ref } from 'vue';
import type { SettingDto, SettingType, SettingWriteRequest } from '@vance/generated';
import { brainFetch } from '@vance/shared';

/**
 * Read + write settings on a single scope (e.g. tenant + name, or
 * project + name). Wraps the existing {@code AdminSettingsController}
 * endpoints.
 */
export function useScopeSettings(): {
  settings: Ref<SettingDto[]>;
  loading: Ref<boolean>;
  busy: Ref<boolean>;
  error: Ref<string | null>;
  load: (referenceType: string, referenceId: string) => Promise<void>;
  upsert: (
    referenceType: string,
    referenceId: string,
    key: string,
    value: string | null,
    type: SettingType,
    description?: string | null,
  ) => Promise<SettingDto>;
  remove: (referenceType: string, referenceId: string, key: string) => Promise<void>;
  clear: () => void;
} {
  const settings = ref<SettingDto[]>([]);
  const loading = ref(false);
  const busy = ref(false);
  const error = ref<string | null>(null);

  function clear(): void {
    settings.value = [];
    error.value = null;
  }

  async function load(referenceType: string, referenceId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const params = new URLSearchParams({ referenceType, referenceId });
      const list = await brainFetch<SettingDto[]>(
        'GET', `admin/settings?${params.toString()}`);
      settings.value = list.sort((a, b) => a.key.localeCompare(b.key));
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load settings.';
      settings.value = [];
    } finally {
      loading.value = false;
    }
  }

  async function upsert(
    referenceType: string,
    referenceId: string,
    key: string,
    value: string | null,
    type: SettingType,
    description?: string | null,
  ): Promise<SettingDto> {
    busy.value = true;
    error.value = null;
    try {
      const body: SettingWriteRequest = {
        value: value ?? undefined,
        type,
        description: description ?? undefined,
      };
      const path = `admin/settings/${encodeURIComponent(referenceType)}`
        + `/${encodeURIComponent(referenceId)}/${encodeURIComponent(key)}`;
      const saved = await brainFetch<SettingDto>('PUT', path, { body });
      await load(referenceType, referenceId);
      return saved;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to save setting.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  async function remove(
    referenceType: string, referenceId: string, key: string,
  ): Promise<void> {
    busy.value = true;
    error.value = null;
    try {
      const path = `admin/settings/${encodeURIComponent(referenceType)}`
        + `/${encodeURIComponent(referenceId)}/${encodeURIComponent(key)}`;
      await brainFetch<void>('DELETE', path);
      await load(referenceType, referenceId);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to delete setting.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  return { settings, loading, busy, error, load, upsert, remove, clear };
}
