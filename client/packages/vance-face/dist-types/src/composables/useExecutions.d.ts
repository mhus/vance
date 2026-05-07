import { type Ref } from 'vue';
import type { ExecutionInsightsDto, ExecutionTailDto } from '@vance/generated';
/**
 * REST snapshot of every execution the project's owner pod knows
 * about. The Web-UI hits Layer 1 ({@code /brain/{tenant}/projects/...
 * /executions}) which proxies to Layer 2 on the project owner pod —
 * see {@code ExecutionsController}.
 *
 * <p>Live updates are not part of v1 (matches the existing Insights
 * convention of REST snapshots + manual refresh). Tail is fetched
 * lazily per-execution when the user expands a row.
 */
export interface UseExecutions {
    list: Ref<ExecutionInsightsDto[]>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    /** Filter checkboxes — owned by the tab; the composable only reads. */
    filters: {
        onlyRunning: boolean;
        ownerLabel: string | null;
    };
    load: (projectId: string) => Promise<void>;
    clear: () => void;
}
export declare function useExecutions(): UseExecutions;
export interface UseExecutionTail {
    tail: Ref<ExecutionTailDto | null>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    load: (projectId: string, id: string, n: number, stream: 'stdout' | 'stderr') => Promise<void>;
    clear: () => void;
}
export declare function useExecutionTail(): UseExecutionTail;
//# sourceMappingURL=useExecutions.d.ts.map