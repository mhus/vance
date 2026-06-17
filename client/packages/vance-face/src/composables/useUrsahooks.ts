import { ref, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type {
  EventLogEntryDto,
  UrsaHookDto,
  UrsaHookSaveRequest,
  UrsaHookSummary,
} from '@vance/generated';

/**
 * Read + write ursahooks for one project. Mirrors {@link useSchedulers}
 * for the ursahook subsystem — see {@code specification/ursahooks.md} §10.
 *
 * <p>Wire paths still use {@code /hooks/...} for backward compatibility
 * with existing clients (Brain controller URL paths were not renamed
 * during the Hook→Ursahook refactor).
 */
export function useUrsahooks(): {
  hooks: Ref<UrsaHookSummary[]>;
  current: Ref<UrsaHookDto | null>;
  events: Ref<EventLogEntryDto[]>;
  loading: Ref<boolean>;
  busy: Ref<boolean>;
  error: Ref<string | null>;
  loadProject: (projectId: string) => Promise<void>;
  loadOne: (projectId: string, event: string, name: string) => Promise<void>;
  loadEvents: (projectId: string, event: string, name: string, limit?: number) => Promise<void>;
  save: (projectId: string, event: string, name: string, yaml: string) => Promise<UrsaHookDto>;
  remove: (projectId: string, event: string, name: string) => Promise<void>;
  refresh: (projectId: string) => Promise<number>;
  clearCurrent: () => void;
} {
  const hooks = ref<UrsaHookSummary[]>([]);
  const current = ref<UrsaHookDto | null>(null);
  const events = ref<EventLogEntryDto[]>([]);
  const loading = ref(false);
  const busy = ref(false);
  const error = ref<string | null>(null);

  async function loadProject(projectId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      hooks.value = await brainFetch<UrsaHookSummary[]>(
        'GET',
        `project/${encodeURIComponent(projectId)}/hooks`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load ursahooks.';
      hooks.value = [];
    } finally {
      loading.value = false;
    }
  }

  async function loadOne(projectId: string, event: string, name: string): Promise<void> {
    busy.value = true;
    error.value = null;
    try {
      current.value = await brainFetch<UrsaHookDto>(
        'GET',
        `project/${encodeURIComponent(projectId)}/hooks/${encodeURIComponent(event)}/${encodeURIComponent(name)}`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load ursahook.';
      current.value = null;
    } finally {
      busy.value = false;
    }
  }

  async function loadEvents(
    projectId: string,
    event: string,
    name: string,
    limit = 50,
  ): Promise<void> {
    try {
      events.value = await brainFetch<EventLogEntryDto[]>(
        'GET',
        `project/${encodeURIComponent(projectId)}/hooks/${encodeURIComponent(event)}/${encodeURIComponent(name)}/events?limit=${limit}`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load events.';
      events.value = [];
    }
  }

  async function save(
    projectId: string,
    event: string,
    name: string,
    yaml: string,
  ): Promise<UrsaHookDto> {
    busy.value = true;
    error.value = null;
    try {
      const body: UrsaHookSaveRequest = { yaml };
      const saved = await brainFetch<UrsaHookDto>(
        'PUT',
        `project/${encodeURIComponent(projectId)}/hooks/${encodeURIComponent(event)}/${encodeURIComponent(name)}`,
        { body },
      );
      current.value = saved;
      await loadProject(projectId);
      return saved;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Save failed.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  async function remove(projectId: string, event: string, name: string): Promise<void> {
    busy.value = true;
    error.value = null;
    try {
      await brainFetch<void>(
        'DELETE',
        `project/${encodeURIComponent(projectId)}/hooks/${encodeURIComponent(event)}/${encodeURIComponent(name)}`,
      );
      await loadProject(projectId);
      current.value = null;
      events.value = [];
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Delete failed.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  async function refresh(projectId: string): Promise<number> {
    busy.value = true;
    error.value = null;
    try {
      const r = await brainFetch<{ registered: number }>(
        'POST',
        `project/${encodeURIComponent(projectId)}/hooks/refresh`,
      );
      await loadProject(projectId);
      return r.registered;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Refresh failed.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  function clearCurrent(): void {
    current.value = null;
    events.value = [];
  }

  return {
    hooks,
    current,
    events,
    loading,
    busy,
    error,
    loadProject,
    loadOne,
    loadEvents,
    save,
    remove,
    refresh,
    clearCurrent,
  };
}
