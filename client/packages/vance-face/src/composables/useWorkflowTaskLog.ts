import { ref, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type { WorkflowTaskRunInsightsDto } from '@vance/generated';

/**
 * Loader for the Task Log in Insights: which workflow tasks ran in a
 * project, on which pod, when and for how long. Read from the shared run
 * journal, so any pod answers the same.
 */
export interface UseWorkflowTaskLog {
  tasks: Ref<WorkflowTaskRunInsightsDto[]>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  load: (projectId: string) => Promise<void>;
  clear: () => void;
}

export function useWorkflowTaskLog(): UseWorkflowTaskLog {
  const tasks = ref<WorkflowTaskRunInsightsDto[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function load(projectId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      tasks.value = await brainFetch<WorkflowTaskRunInsightsDto[]>(
        'GET',
        `admin/projects/${encodeURIComponent(projectId)}/insights/workflow-tasks`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load the task log.';
      tasks.value = [];
    } finally {
      loading.value = false;
    }
  }

  function clear(): void {
    tasks.value = [];
    error.value = null;
  }

  return { tasks, loading, error, load, clear };
}
