import { ref, type Ref } from 'vue';
import type {
  ServerToolDto,
  ServerToolWriteRequest,
  ToolTypeDto,
} from '@vance/generated';
import { brainFetch } from '@vance/shared';

/**
 * Read + write configured server tools at one project. The
 * {@code _vance} system project is selected like any other project —
 * the editor surfaces it explicitly so tenant-wide defaults can be
 * managed without a separate admin UI.
 *
 * <p>The cascade fallback to bundled bean tools is <b>not</b>
 * surfaced here — bean tools are not editable. Use {@code recipe_list}
 * / runtime tooling to see what bean tools are visible.
 */
export function useAdminServerTools(): {
  tools: Ref<ServerToolDto[]>;
  types: Ref<ToolTypeDto[]>;
  loading: Ref<boolean>;
  busy: Ref<boolean>;
  error: Ref<string | null>;
  loadProject: (projectId: string) => Promise<void>;
  loadTypes: () => Promise<void>;
  upsert: (
    projectId: string,
    name: string,
    body: ServerToolWriteRequest,
  ) => Promise<ServerToolDto>;
  remove: (projectId: string, name: string) => Promise<void>;
} {
  const tools = ref<ServerToolDto[]>([]);
  const types = ref<ToolTypeDto[]>([]);
  const loading = ref(false);
  const busy = ref(false);
  const error = ref<string | null>(null);

  async function loadProject(projectId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      tools.value = await brainFetch<ServerToolDto[]>(
        'GET',
        `admin/projects/${encodeURIComponent(projectId)}/server-tools`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load server tools.';
      tools.value = [];
    } finally {
      loading.value = false;
    }
  }

  async function loadTypes(): Promise<void> {
    try {
      types.value = await brainFetch<ToolTypeDto[]>('GET', 'admin/server-tool-types');
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load tool types.';
      types.value = [];
    }
  }

  async function upsert(
    projectId: string,
    name: string,
    body: ServerToolWriteRequest,
  ): Promise<ServerToolDto> {
    busy.value = true;
    error.value = null;
    try {
      const saved = await brainFetch<ServerToolDto>(
        'PUT',
        `admin/projects/${encodeURIComponent(projectId)}/server-tools/${encodeURIComponent(name)}`,
        { body },
      );
      await loadProject(projectId);
      return saved;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to save server tool.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  async function remove(projectId: string, name: string): Promise<void> {
    busy.value = true;
    error.value = null;
    try {
      await brainFetch<void>(
        'DELETE',
        `admin/projects/${encodeURIComponent(projectId)}/server-tools/${encodeURIComponent(name)}`,
      );
      await loadProject(projectId);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to delete server tool.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  return { tools, types, loading, busy, error, loadProject, loadTypes, upsert, remove };
}
