import { ref, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type {
  EventDto,
  EventSummary,
  EventTriggerResponse,
} from '@vance/generated';

/**
 * Read + admin-trigger events for one project. Mirrors {@link useSchedulers}
 * for the events subsystem — see {@code specification/events.md}.
 *
 * <p>The admin-trigger path here is intentionally separate from the
 * public {@code /brain/{tenant}/event/{project}/{event}} REST endpoint:
 * it goes through {@code /brain/{tenant}/project/{project}/events/{name}/trigger}
 * which is JWT-authenticated and bypasses the event's bearer-token
 * check. The Web-UI user doesn't need to know the event's bearer secret.
 */
export function useEvents(): {
  events: Ref<EventSummary[]>;
  current: Ref<EventDto | null>;
  loading: Ref<boolean>;
  busy: Ref<boolean>;
  error: Ref<string | null>;
  lastResult: Ref<EventTriggerResponse | null>;
  loadProject: (projectId: string) => Promise<void>;
  loadOne: (projectId: string, name: string) => Promise<void>;
  trigger: (
    projectId: string,
    name: string,
    payload: unknown,
  ) => Promise<EventTriggerResponse>;
  clearCurrent: () => void;
  clearLastResult: () => void;
} {
  const events = ref<EventSummary[]>([]);
  const current = ref<EventDto | null>(null);
  const loading = ref(false);
  const busy = ref(false);
  const error = ref<string | null>(null);
  const lastResult = ref<EventTriggerResponse | null>(null);

  async function loadProject(projectId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      events.value = await brainFetch<EventSummary[]>(
        'GET',
        `project/${encodeURIComponent(projectId)}/events`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load events.';
      events.value = [];
    } finally {
      loading.value = false;
    }
  }

  async function loadOne(projectId: string, name: string): Promise<void> {
    busy.value = true;
    error.value = null;
    try {
      current.value = await brainFetch<EventDto>(
        'GET',
        `project/${encodeURIComponent(projectId)}/events/${encodeURIComponent(name)}`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load event.';
      current.value = null;
    } finally {
      busy.value = false;
    }
  }

  /**
   * POST the admin trigger. {@code payload} is the value forwarded to
   * the workflow as {@code params.payload}; pass {@code null} for no
   * payload. The server wraps it in an {@code AdminTriggerRequest}
   * envelope and the JSON body sent on the wire is
   * {@code {"payload": <value>}}.
   */
  async function trigger(
    projectId: string,
    name: string,
    payload: unknown,
  ): Promise<EventTriggerResponse> {
    busy.value = true;
    error.value = null;
    lastResult.value = null;
    try {
      const result = await brainFetch<EventTriggerResponse>(
        'POST',
        `project/${encodeURIComponent(projectId)}/events/${encodeURIComponent(name)}/trigger`,
        { body: { payload } },
      );
      lastResult.value = result;
      return result;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Trigger failed.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  function clearCurrent(): void {
    current.value = null;
  }

  function clearLastResult(): void {
    lastResult.value = null;
  }

  return {
    events,
    current,
    loading,
    busy,
    error,
    lastResult,
    loadProject,
    loadOne,
    trigger,
    clearCurrent,
    clearLastResult,
  };
}
