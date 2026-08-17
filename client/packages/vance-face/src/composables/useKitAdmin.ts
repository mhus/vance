import { ref, type Ref } from 'vue';
import {
  KitImportMode,
  type KitConfigDto,
  type KitExportRequestDto,
  type KitImportRequestDto,
  type KitInstalledRecordDto,
  type KitManifestDto,
  type KitOperationResultDto,
} from '@vance/generated';
import { brainFetch } from '@vance/shared';

/**
 * Read + mutate the kits of a single project. Wraps the
 * {@code KitAdminController} endpoints under
 * {@code /brain/{tenant}/admin/kits/...}.
 *
 * Two distinct things, deliberately kept apart:
 * `installed` — the kits this project has installed, any number of them.
 * `manifest`  — set only when the project *is* a kit source, i.e. when
 *               someone opted into authoring. Usually null.
 */
export function useKitAdmin(): {
  installed: Ref<KitInstalledRecordDto[]>;
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
  updateOne: (projectId: string, kitId: string, prune: boolean) => Promise<KitOperationResultDto>;
  updateAll: (projectId: string, prune: boolean) => Promise<KitOperationResultDto[]>;
  uninstall: (projectId: string, kitId: string, prune: boolean) => Promise<KitOperationResultDto>;
  promote: (projectId: string, kitId: string) => Promise<KitManifestDto>;
  loadConfig: (projectId: string, kitId: string) => Promise<KitConfigDto>;
  saveConfig: (projectId: string, kitId: string, config: KitConfigDto) => Promise<KitConfigDto>;
  clear: () => void;
} {
  const installed = ref<KitInstalledRecordDto[]>([]);
  const manifest = ref<KitManifestDto | null>(null);
  const loading = ref(false);
  const busy = ref(false);
  const error = ref<string | null>(null);
  const lastResult = ref<KitOperationResultDto | null>(null);

  function clear(): void {
    installed.value = [];
    manifest.value = null;
    error.value = null;
    lastResult.value = null;
  }

  async function load(projectId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const [records, sourceManifest] = await Promise.all([
        brainFetch<KitInstalledRecordDto[] | undefined>(
          'GET', `admin/kits/${encodeURIComponent(projectId)}/status`),
        brainFetch<KitManifestDto | undefined>(
          'GET', `admin/kits/${encodeURIComponent(projectId)}/manifest`),
      ]);
      installed.value = records ?? [];
      manifest.value = sourceManifest ?? null;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load kit status.';
      installed.value = [];
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

  /**
   * Re-run one installed kit. Goes to the per-record endpoint: the
   * source comes from the record, so nothing but the knobs travels.
   */
  async function updateOne(
    projectId: string, kitId: string, prune: boolean,
  ): Promise<KitOperationResultDto> {
    return runMutation(projectId, async () => {
      const path = `admin/kits/${encodeURIComponent(projectId)}/update/${encodeURIComponent(kitId)}`;
      const result = await brainFetch<KitOperationResultDto>('POST', path, { body: { prune } });
      lastResult.value = result;
      return result;
    }, 'Failed to update kit.');
  }

  async function updateAll(projectId: string, prune: boolean): Promise<KitOperationResultDto[]> {
    return runMutation(projectId, async () => {
      const path = `admin/kits/${encodeURIComponent(projectId)}/update-all`;
      const results = await brainFetch<KitOperationResultDto[]>('POST', path, { body: { prune } });
      // The card shows one result; with several kits the last one is as
      // arbitrary as any, so show nothing rather than something misleading.
      lastResult.value = results.length === 1 ? results[0] : null;
      return results;
    }, 'Failed to update kits.');
  }

  async function uninstall(
    projectId: string, kitId: string, prune: boolean,
  ): Promise<KitOperationResultDto> {
    return runMutation(projectId, async () => {
      const path = `admin/kits/${encodeURIComponent(projectId)}/uninstall/`
        + `${encodeURIComponent(kitId)}?prune=${prune}`;
      const result = await brainFetch<KitOperationResultDto>('POST', path);
      lastResult.value = result;
      return result;
    }, 'Failed to uninstall kit.');
  }

  /** Turn an installed kit into this project's kit source. */
  async function promote(projectId: string, kitId: string): Promise<KitManifestDto> {
    return runMutation(projectId, () => {
      const path = `admin/kits/${encodeURIComponent(projectId)}/promote/`
        + `${encodeURIComponent(kitId)}`;
      return brainFetch<KitManifestDto>('POST', path);
    }, 'Failed to make this project the kit source.');
  }

  async function loadConfig(projectId: string, kitId: string): Promise<KitConfigDto> {
    // Reads do not go through runMutation (no reload, no busy flag), but
    // they must still surface: the caller opens a dialog on success and
    // has nothing to show the user if this fails silently.
    error.value = null;
    try {
      const path = `admin/kits/${encodeURIComponent(projectId)}/config/`
        + `${encodeURIComponent(kitId)}`;
      return await brainFetch<KitConfigDto>('GET', path);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load kit rules.';
      throw e;
    }
  }

  async function saveConfig(
    projectId: string, kitId: string, config: KitConfigDto,
  ): Promise<KitConfigDto> {
    return runMutation(projectId, () => {
      const path = `admin/kits/${encodeURIComponent(projectId)}/config/`
        + `${encodeURIComponent(kitId)}`;
      return brainFetch<KitConfigDto>('PUT', path, { body: config });
    }, 'Failed to save kit config.');
  }

  /**
   * Shared shell for the mutating calls: busy flag, error capture and a
   * reload so the card never shows a state the server no longer has.
   */
  async function runMutation<T>(
    projectId: string, call: () => Promise<T>, failureMessage: string,
  ): Promise<T> {
    busy.value = true;
    error.value = null;
    try {
      const result = await call();
      await load(projectId);
      return result;
    } catch (e) {
      error.value = e instanceof Error ? e.message : failureMessage;
      throw e;
    } finally {
      busy.value = false;
    }
  }

  return {
    installed,
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
    updateOne,
    updateAll,
    uninstall,
    promote,
    loadConfig,
    saveConfig,
    clear,
  };
}
