import { reactive, ref, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';
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
  filters: { onlyRunning: boolean; ownerLabel: string | null };
  load: (projectId: string) => Promise<void>;
  clear: () => void;
}

export function useExecutions(): UseExecutions {
  const list = ref<ExecutionInsightsDto[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);
  const filters = reactive<{ onlyRunning: boolean; ownerLabel: string | null }>({
    onlyRunning: false,
    ownerLabel: null,
  });

  async function load(projectId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const params = new URLSearchParams();
      if (filters.onlyRunning) params.set('onlyRunning', 'true');
      if (filters.ownerLabel) params.set('ownerLabel', filters.ownerLabel);
      const qs = params.toString();
      list.value = await brainFetch<ExecutionInsightsDto[]>(
        'GET',
        `projects/${encodeURIComponent(projectId)}/executions/list${qs ? `?${qs}` : ''}`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load executions.';
      list.value = [];
    } finally {
      loading.value = false;
    }
  }

  function clear(): void {
    list.value = [];
    error.value = null;
  }

  return { list, loading, error, filters, load, clear };
}

export interface UseExecutionTail {
  tail: Ref<ExecutionTailDto | null>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  load: (projectId: string, id: string, n: number, stream: 'stdout' | 'stderr') => Promise<void>;
  clear: () => void;
}

export function useExecutionTail(): UseExecutionTail {
  const tail = ref<ExecutionTailDto | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function load(
    projectId: string,
    id: string,
    n: number,
    stream: 'stdout' | 'stderr',
  ): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const params = new URLSearchParams({ n: String(n), stream });
      tail.value = await brainFetch<ExecutionTailDto>(
        'GET',
        `projects/${encodeURIComponent(projectId)}/executions/${encodeURIComponent(id)}/tail?${params}`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load tail.';
      tail.value = null;
    } finally {
      loading.value = false;
    }
  }

  function clear(): void {
    tail.value = null;
    error.value = null;
  }

  return { tail, loading, error, load, clear };
}
