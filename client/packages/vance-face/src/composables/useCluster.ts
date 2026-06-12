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

  // Defensive: tolerate a missing or non-array `pods` field on the
  // wire. The DTO declares it as a non-null List, but the empty-cluster
  // case on a fresh brain has been observed to deliver `pods=null` from
  // Jackson when @Builder.Default lost a race against the response
  // serializer — guarding here keeps `reduce`/`map` downstream from
  // throwing on the cluster tab.
  const pods = computed<BrainPodInsightsDto[]>(() => {
    const raw = cluster.value?.pods;
    return Array.isArray(raw) ? raw : [];
  });

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
