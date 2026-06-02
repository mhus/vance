import { type Ref } from 'vue';
import type { ProjectGroupSummary, ProjectSummary } from '@vance/generated';
/**
 * Loads the tenant's project groups and projects once. Used by the project
 * selector in the document editor (and any future editor that needs the same
 * dropdown).
 */
export declare function useTenantProjects(): {
    groups: Ref<ProjectGroupSummary[]>;
    projects: Ref<ProjectSummary[]>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    reload: () => Promise<void>;
};
//# sourceMappingURL=useTenantProjects.d.ts.map