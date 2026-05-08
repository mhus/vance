import { ref, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type { BrainPodInsightsDto } from '@vance/generated';

/**
 * REST loader for the cluster pods admin endpoint. Returns one row per
 * brain-pod in the cluster the answering brain belongs to;
 * {@code tenantProjects} is already filtered to the current tenant on
 * the server side.
 */
export interface UseClusterPods {
  pods: Ref<BrainPodInsightsDto[]>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  load: () => Promise<void>;
  clear: () => void;
}

export function useClusterPods(): UseClusterPods {
  const pods = ref<BrainPodInsightsDto[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function load(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      pods.value = await brainFetch<BrainPodInsightsDto[]>(
        'GET',
        'admin/cluster/pods',
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load cluster pods.';
      pods.value = [];
    } finally {
      loading.value = false;
    }
  }

  function clear(): void {
    pods.value = [];
    error.value = null;
  }

  return { pods, loading, error, load, clear };
}
