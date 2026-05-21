import { ref, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type {
  MagratheaProcessDto,
  MagratheaWorkflowDto,
  MagratheaWorkflowSummary,
} from '@vance/generated';

/**
 * Workflow definitions + run listings for the insights workflows tab.
 *
 * <p>Workflow definitions cascade via the document layer
 * ({@code project → _vance}); workflow runs are read from the
 * {@code magrathea_journal} via the projector. {@code start} mints a
 * fresh runId and the projector reaches DONE once the lane finishes.
 */
export interface WorkflowStartResult {
  workflowName: string;
  workflowRunId: string;
}

export function useWorkflows(): {
  workflows: Ref<MagratheaWorkflowSummary[]>;
  current: Ref<MagratheaWorkflowDto | null>;
  runs: Ref<MagratheaProcessDto[]>;
  loading: Ref<boolean>;
  busy: Ref<boolean>;
  error: Ref<string | null>;
  lastResult: Ref<WorkflowStartResult | null>;
  loadProject: (projectId: string) => Promise<void>;
  loadOne: (projectId: string, name: string) => Promise<void>;
  loadRuns: (projectId: string, name: string) => Promise<void>;
  start: (
    projectId: string,
    name: string,
    params: Record<string, unknown> | null,
  ) => Promise<WorkflowStartResult>;
  clearCurrent: () => void;
  clearLastResult: () => void;
} {
  const workflows = ref<MagratheaWorkflowSummary[]>([]);
  const current = ref<MagratheaWorkflowDto | null>(null);
  const runs = ref<MagratheaProcessDto[]>([]);
  const loading = ref(false);
  const busy = ref(false);
  const error = ref<string | null>(null);
  const lastResult = ref<WorkflowStartResult | null>(null);

  async function loadProject(projectId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      workflows.value = await brainFetch<MagratheaWorkflowSummary[]>(
        'GET',
        `project/${encodeURIComponent(projectId)}/workflows`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load workflows.';
      workflows.value = [];
    } finally {
      loading.value = false;
    }
  }

  async function loadOne(projectId: string, name: string): Promise<void> {
    busy.value = true;
    error.value = null;
    try {
      current.value = await brainFetch<MagratheaWorkflowDto>(
        'GET',
        `project/${encodeURIComponent(projectId)}/workflows/${encodeURIComponent(name)}`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load workflow.';
      current.value = null;
    } finally {
      busy.value = false;
    }
  }

  /**
   * GET {@code /workflows/runs?workflow=<name>} — newest first, max 100.
   * Server-side limit is enforced by MagratheaWorkflowController.LIST_LIMIT.
   */
  async function loadRuns(projectId: string, name: string): Promise<void> {
    error.value = null;
    try {
      runs.value = await brainFetch<MagratheaProcessDto[]>(
        'GET',
        `project/${encodeURIComponent(projectId)}/workflows/runs?workflow=${encodeURIComponent(name)}`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load runs.';
      runs.value = [];
    }
  }

  async function start(
    projectId: string,
    name: string,
    params: Record<string, unknown> | null,
  ): Promise<WorkflowStartResult> {
    busy.value = true;
    error.value = null;
    lastResult.value = null;
    try {
      const result = await brainFetch<WorkflowStartResult>(
        'POST',
        `project/${encodeURIComponent(projectId)}/workflows/${encodeURIComponent(name)}/start`,
        { body: { params: params ?? {}, startedBy: null } },
      );
      lastResult.value = result;
      return result;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Start failed.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  function clearCurrent(): void {
    current.value = null;
    runs.value = [];
  }

  function clearLastResult(): void {
    lastResult.value = null;
  }

  return {
    workflows,
    current,
    runs,
    loading,
    busy,
    error,
    lastResult,
    loadProject,
    loadOne,
    loadRuns,
    start,
    clearCurrent,
    clearLastResult,
  };
}
