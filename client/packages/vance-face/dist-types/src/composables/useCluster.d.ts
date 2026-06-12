import { type ComputedRef, type Ref } from 'vue';
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
export declare function useClusterPods(): UseClusterPods;
//# sourceMappingURL=useCluster.d.ts.map