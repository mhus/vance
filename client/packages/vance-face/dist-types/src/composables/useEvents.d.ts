import { type Ref } from 'vue';
import type { EventDto, EventSummary, EventTriggerResponse } from '@vance/generated';
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
export declare function useEvents(): {
    events: Ref<EventSummary[]>;
    current: Ref<EventDto | null>;
    loading: Ref<boolean>;
    busy: Ref<boolean>;
    error: Ref<string | null>;
    lastResult: Ref<EventTriggerResponse | null>;
    loadProject: (projectId: string) => Promise<void>;
    loadOne: (projectId: string, name: string) => Promise<void>;
    trigger: (projectId: string, name: string, payload: unknown) => Promise<EventTriggerResponse>;
    clearCurrent: () => void;
    clearLastResult: () => void;
};
//# sourceMappingURL=useEvents.d.ts.map