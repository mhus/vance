import { ref, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type { RunDetailDto, RunSummaryDto } from '@vance/generated';

/**
 * Runs of one project, merged across sources by the server.
 *
 * <p>Snapshot + refresh, no live push: the same trade the workflows tab
 * makes. A run view that pushed would need a channel per source, and the
 * question it answers ("what happened to the thing I started") tolerates
 * a manual refresh.
 */
export function useRuns(): {
  runs: Ref<RunSummaryDto[]>;
  detail: Ref<RunDetailDto | null>;
  loading: Ref<boolean>;
  detailLoading: Ref<boolean>;
  error: Ref<string | null>;
  loadRuns: (projectId: string) => Promise<void>;
  loadDetail: (projectId: string, runId: string) => Promise<void>;
  clearDetail: () => void;
} {
  const runs = ref<RunSummaryDto[]>([]);
  const detail = ref<RunDetailDto | null>(null);
  const loading = ref(false);
  const detailLoading = ref(false);
  const error = ref<string | null>(null);

  async function loadRuns(projectId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      runs.value = await brainFetch<RunSummaryDto[]>(
        'GET', `runs?projectId=${encodeURIComponent(projectId)}`);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load runs.';
      runs.value = [];
    } finally {
      loading.value = false;
    }
  }

  async function loadDetail(projectId: string, runId: string): Promise<void> {
    detailLoading.value = true;
    error.value = null;
    try {
      detail.value = await brainFetch<RunDetailDto>(
        'GET',
        `runs/${encodeURIComponent(runId)}?projectId=${encodeURIComponent(projectId)}`);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load run.';
      detail.value = null;
    } finally {
      detailLoading.value = false;
    }
  }

  function clearDetail(): void {
    detail.value = null;
  }

  return { runs, detail, loading, detailLoading, error, loadRuns, loadDetail, clearDetail };
}
