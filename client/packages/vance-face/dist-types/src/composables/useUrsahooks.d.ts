import { type Ref } from 'vue';
import type { EventLogEntryDto, UrsaHookDto, UrsaHookSummary } from '@vance/generated';
/**
 * Read + write ursahooks for one project. Mirrors {@link useSchedulers}
 * for the ursahook subsystem — see {@code specification/ursahooks.md} §10.
 *
 * <p>Wire paths still use {@code /hooks/...} for backward compatibility
 * with existing clients (Brain controller URL paths were not renamed
 * during the Hook→Ursahook refactor).
 */
export declare function useUrsahooks(): {
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
};
//# sourceMappingURL=useUrsahooks.d.ts.map