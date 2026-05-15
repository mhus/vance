import { type Ref } from 'vue';
import type { HactarProcessDto, HactarWorkflowDto, HactarWorkflowSummary } from '@vance/generated';
/**
 * Workflow definitions + run listings for the insights workflows tab.
 *
 * <p>Workflow definitions cascade via the document layer
 * ({@code project → _vance}); workflow runs are read from the
 * {@code hactar_journal} via the projector. {@code start} mints a
 * fresh runId and the projector reaches DONE once the lane finishes.
 */
export interface WorkflowStartResult {
    workflowName: string;
    workflowRunId: string;
}
export declare function useWorkflows(): {
    workflows: Ref<HactarWorkflowSummary[]>;
    current: Ref<HactarWorkflowDto | null>;
    runs: Ref<HactarProcessDto[]>;
    loading: Ref<boolean>;
    busy: Ref<boolean>;
    error: Ref<string | null>;
    lastResult: Ref<WorkflowStartResult | null>;
    loadProject: (projectId: string) => Promise<void>;
    loadOne: (projectId: string, name: string) => Promise<void>;
    loadRuns: (projectId: string, name: string) => Promise<void>;
    start: (projectId: string, name: string, params: Record<string, unknown> | null) => Promise<WorkflowStartResult>;
    clearCurrent: () => void;
    clearLastResult: () => void;
};
//# sourceMappingURL=useWorkflows.d.ts.map