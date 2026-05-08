import { type Ref } from 'vue';
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
export declare function useClusterPods(): UseClusterPods;
//# sourceMappingURL=useCluster.d.ts.map