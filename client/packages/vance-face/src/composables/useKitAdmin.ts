import { ref, type Ref } from 'vue';
import {
  KitImportMode,
  type KitExportRequestDto,
  type KitImportRequestDto,
  type KitManifestDto,
  type KitOperationResultDto,
} from '@vance/generated';
import { brainFetch } from '@vance/shared';

/**
 * Read + mutate the active kit on a single project. Wraps the
 * {@code KitAdminController} endpoints under
 * {@code /brain/{tenant}/admin/kits/...}.
 */
export function useKitAdmin(): {
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
} {
  const manifest = ref<KitManifestDto | null>(null);
  const loading = ref(false);
  const busy = ref(false);
  const error = ref<string | null>(null);
  const lastResult = ref<KitOperationResultDto | null>(null);

  function clear(): void {
    manifest.value = null;
    error.value = null;
    lastResult.value = null;
  }

  async function load(projectId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const response = await brainFetch<KitManifestDto | undefined>(
        'GET', `admin/kits/${encodeURIComponent(projectId)}/status`);
      manifest.value = response ?? null;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load kit status.';
      manifest.value = null;
    } finally {
      loading.value = false;
    }
  }

  async function runImport(
    verb: 'install' | 'update' | 'apply',
    mode: KitImportMode,
    projectId: string,
    request: KitImportRequestDto,
  ): Promise<KitOperationResultDto> {
    busy.value = true;
    error.value = null;
    try {
      const body: KitImportRequestDto = { ...request, projectId, mode };
      const path = `admin/kits/${encodeURIComponent(projectId)}/${verb}`;
      const result = await brainFetch<KitOperationResultDto>('POST', path, { body });
      lastResult.value = result;
      await load(projectId);
      return result;
    } catch (e) {
      error.value = e instanceof Error ? e.message : `Failed to ${verb} kit.`;
      throw e;
    } finally {
      busy.value = false;
    }
  }

  async function install(
    projectId: string, request: KitImportRequestDto,
  ): Promise<KitOperationResultDto> {
    return runImport('install', KitImportMode.INSTALL, projectId, request);
  }

  async function update(
    projectId: string, request: KitImportRequestDto,
  ): Promise<KitOperationResultDto> {
    return runImport('update', KitImportMode.UPDATE, projectId, request);
  }

  async function apply(
    projectId: string, request: KitImportRequestDto,
  ): Promise<KitOperationResultDto> {
    return runImport('apply', KitImportMode.APPLY, projectId, request);
  }

  async function exportKit(
    projectId: string, request: KitExportRequestDto,
  ): Promise<KitOperationResultDto> {
    busy.value = true;
    error.value = null;
    try {
      const path = `admin/kits/${encodeURIComponent(projectId)}/export`;
      const result = await brainFetch<KitOperationResultDto>('POST', path, { body: request });
      lastResult.value = result;
      await load(projectId);
      return result;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to export kit.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  return {
    manifest,
    loading,
    busy,
    error,
    lastResult,
    load,
    install,
    update,
    apply,
    export: exportKit,
    clear,
  };
}
