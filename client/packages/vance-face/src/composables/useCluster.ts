import { computed, ref, type ComputedRef, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type { BrainPodInsightsDto, ClusterInsightsDto } from '@vance/generated';

/**
 * REST loader for the cluster pods admin endpoint. Returns the answering
 * brain's view of the cluster: pods + current Cluster-Master lease.
 * {@code tenantProjects} is already filtered to the current tenant on
 * the server side.
 */
export interface UseClusterPods {
  cluster: Ref<ClusterInsightsDto | null>;
  pods: ComputedRef<BrainPodInsightsDto[]>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  load: () => Promise<void>;
  clear: () => void;
}

export function useClusterPods(): UseClusterPods {
  const cluster = ref<ClusterInsightsDto | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);

  const pods = computed<BrainPodInsightsDto[]>(() => cluster.value?.pods ?? []);

  async function load(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      cluster.value = await brainFetch<ClusterInsightsDto>(
        'GET',
        'admin/cluster/pods',
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load cluster pods.';
      cluster.value = null;
    } finally {
      loading.value = false;
    }
  }

  function clear(): void {
    cluster.value = null;
    error.value = null;
  }

  return { cluster, pods, loading, error, load, clear };
}
