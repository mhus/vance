import { ref, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type {
  EventLogEntryDto,
  SchedulerDto,
  SchedulerSaveRequest,
  SchedulerSummary,
} from '@vance/generated';

/**
 * Read + write schedulers at one project. Mirrors {@code useAdminServerTools}
 * for the scheduler subsystem — see {@code specification/scheduler.md} §10.
 */
export function useSchedulers(): {
  schedulers: Ref<SchedulerSummary[]>;
  current: Ref<SchedulerDto | null>;
  events: Ref<EventLogEntryDto[]>;
  loading: Ref<boolean>;
  busy: Ref<boolean>;
  error: Ref<string | null>;
  loadProject: (projectId: string) => Promise<void>;
  loadOne: (projectId: string, name: string) => Promise<void>;
  loadEvents: (projectId: string, name: string, limit?: number) => Promise<void>;
  save: (projectId: string, name: string, yaml: string) => Promise<SchedulerDto>;
  remove: (projectId: string, name: string) => Promise<void>;
  refresh: (projectId: string) => Promise<number>;
  clearCurrent: () => void;
} {
  const schedulers = ref<SchedulerSummary[]>([]);
  const current = ref<SchedulerDto | null>(null);
  const events = ref<EventLogEntryDto[]>([]);
  const loading = ref(false);
  const busy = ref(false);
  const error = ref<string | null>(null);

  async function loadProject(projectId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      schedulers.value = await brainFetch<SchedulerSummary[]>(
        'GET',
        `project/${encodeURIComponent(projectId)}/scheduler`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load schedulers.';
      schedulers.value = [];
    } finally {
      loading.value = false;
    }
  }

  async function loadOne(projectId: string, name: string): Promise<void> {
    busy.value = true;
    error.value = null;
    try {
      current.value = await brainFetch<SchedulerDto>(
        'GET',
        `project/${encodeURIComponent(projectId)}/scheduler/${encodeURIComponent(name)}`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load scheduler.';
      current.value = null;
    } finally {
      busy.value = false;
    }
  }

  async function loadEvents(
    projectId: string,
    name: string,
    limit = 50,
  ): Promise<void> {
    try {
      events.value = await brainFetch<EventLogEntryDto[]>(
        'GET',
        `project/${encodeURIComponent(projectId)}/scheduler/${encodeURIComponent(name)}/events?limit=${limit}`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load events.';
      events.value = [];
    }
  }

  async function save(
    projectId: string,
    name: string,
    yaml: string,
  ): Promise<SchedulerDto> {
    busy.value = true;
    error.value = null;
    try {
      const body: SchedulerSaveRequest = { yaml };
      const saved = await brainFetch<SchedulerDto>(
        'PUT',
        `project/${encodeURIComponent(projectId)}/scheduler/${encodeURIComponent(name)}`,
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

  async function remove(projectId: string, name: string): Promise<void> {
    busy.value = true;
    error.value = null;
    try {
      await brainFetch<void>(
        'DELETE',
        `project/${encodeURIComponent(projectId)}/scheduler/${encodeURIComponent(name)}`,
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
        `project/${encodeURIComponent(projectId)}/scheduler/refresh`,
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
    schedulers,
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
