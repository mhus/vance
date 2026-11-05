import { ref, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type {
  RunBreakpointsRequest, RunDetailDto, RunSimulateRequest, RunSummaryDto,
} from '@vance/generated';

/**
 * Runs of one project, merged across sources by the server.
 *
 * <p>Snapshot + refresh, no live push: the same trade the workflows tab
 * makes. A run view that pushed would need a channel per source, and the
 * question it answers ("what happened to the thing I started") tolerates
 * a manual refresh.
 */
/** {@code silent}: read again without blanking the screen — for a refresh the user did not ask for. */
export interface LoadOptions {
  silent?: boolean;
}

export function useRuns(): {
  runs: Ref<RunSummaryDto[]>;
  detail: Ref<RunDetailDto | null>;
  loading: Ref<boolean>;
  detailLoading: Ref<boolean>;
  error: Ref<string | null>;
  loadRuns: (projectId: string, options?: LoadOptions) => Promise<void>;
  loadDetail: (projectId: string, runId: string, options?: LoadOptions) => Promise<void>;
  perform: (projectId: string, runId: string, action: string) => Promise<void>;
  setBreakpoints: (projectId: string, runId: string, states: string[]) => Promise<void>;
  simulate: (projectId: string, runId: string, body: RunSimulateRequest) => Promise<void>;
  acting: Ref<boolean>;
  clearDetail: () => void;
} {
  const runs = ref<RunSummaryDto[]>([]);
  const detail = ref<RunDetailDto | null>(null);
  const loading = ref(false);
  const detailLoading = ref(false);
  const error = ref<string | null>(null);
  const acting = ref(false);

  async function loadRuns(projectId: string, options: LoadOptions = {}): Promise<void> {
    if (!options.silent) loading.value = true;
    error.value = null;
    try {
      runs.value = await brainFetch<RunSummaryDto[]>(
        'GET', `runs?projectId=${encodeURIComponent(projectId)}`);
    } catch (e) {
      if (!options.silent) {
        error.value = e instanceof Error ? e.message : 'Failed to load runs.';
        runs.value = [];
      }
    } finally {
      if (!options.silent) loading.value = false;
    }
  }

  /** The run the user asked for last; an older answer arriving later must not replace it. */
  let requestedRunId = '';

  async function loadDetail(
    projectId: string, runId: string, options: LoadOptions = {},
  ): Promise<void> {
    requestedRunId = runId;
    if (!options.silent) detailLoading.value = true;
    error.value = null;
    try {
      const fresh = await brainFetch<RunDetailDto>(
        'GET',
        `runs/${encodeURIComponent(runId)}?projectId=${encodeURIComponent(projectId)}`);
      if (requestedRunId === runId) detail.value = fresh;
    } catch (e) {
      // A refresh nobody asked for keeps what is on screen; the next one tries again.
      if (!options.silent && requestedRunId === runId) {
        error.value = e instanceof Error ? e.message : 'Failed to load run.';
        detail.value = null;
      }
    } finally {
      if (!options.silent) detailLoading.value = false;
    }
  }

  /**
   * Run an action and take the server's fresh detail as the new truth —
   * the response carries the state after the action, so the view never
   * has to guess what the action did.
   */
  async function perform(projectId: string, runId: string, action: string): Promise<void> {
    acting.value = true;
    error.value = null;
    try {
      detail.value = await brainFetch<RunDetailDto>(
        'POST',
        `runs/${encodeURIComponent(runId)}/actions/${encodeURIComponent(action)}`
          + `?projectId=${encodeURIComponent(projectId)}`);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Action failed.';
    } finally {
      acting.value = false;
    }
  }

  /**
   * Replace the breakpoints of a debuggable run. Like an action, the answer is
   * the run as it is now, so the view shows what the server holds.
   */
  async function setBreakpoints(projectId: string, runId: string, states: string[]): Promise<void> {
    error.value = null;
    try {
      const body: RunBreakpointsRequest = { breakpoints: states };
      detail.value = await brainFetch<RunDetailDto>(
        'PUT',
        `runs/${encodeURIComponent(runId)}/breakpoints?projectId=${encodeURIComponent(projectId)}`,
        { body });
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Could not set the breakpoints.';
    }
  }

  /**
   * Give a halted run a simulated result instead of executing its next state. The
   * answer is the run as it is now, like after an action.
   */
  async function simulate(projectId: string, runId: string, body: RunSimulateRequest): Promise<void> {
    acting.value = true;
    error.value = null;
    try {
      detail.value = await brainFetch<RunDetailDto>(
        'POST',
        `runs/${encodeURIComponent(runId)}/simulate?projectId=${encodeURIComponent(projectId)}`,
        { body });
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Could not simulate.';
    } finally {
      acting.value = false;
    }
  }

  function clearDetail(): void {
    requestedRunId = '';
    detail.value = null;
  }

  return { runs, detail, loading, detailLoading, error, acting,
    loadRuns, loadDetail, perform, setBreakpoints, simulate, clearDetail };
}
