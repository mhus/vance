import { type Ref } from 'vue';
import type { EventLogEntryDto, SchedulerDto, SchedulerSummary } from '@vance/generated';
/**
 * Read + write schedulers at one project. Mirrors {@code useAdminServerTools}
 * for the scheduler subsystem — see {@code specification/scheduler.md} §10.
 */
/** Server response of {@code POST /scheduler/{name}/fire}. */
export interface FireResult {
    correlationId: string;
    logPath: string;
}
export declare function useSchedulers(): {
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
    fire: (projectId: string, name: string) => Promise<FireResult>;
    clearCurrent: () => void;
};
//# sourceMappingURL=useSchedulers.d.ts.map