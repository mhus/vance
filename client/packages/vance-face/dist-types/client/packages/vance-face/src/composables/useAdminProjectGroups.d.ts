import { type Ref } from 'vue';
import type { ProjectGroupCreateRequest, ProjectGroupSummary, ProjectGroupUpdateRequest } from '@vance/generated';
/**
 * CRUD on project groups. Server enforces "delete only if empty" via 409.
 */
export declare function useAdminProjectGroups(): {
    groups: Ref<ProjectGroupSummary[]>;
    loading: Ref<boolean>;
    busy: Ref<boolean>;
    error: Ref<string | null>;
    reload: () => Promise<void>;
    create: (req: ProjectGroupCreateRequest) => Promise<ProjectGroupSummary>;
    update: (name: string, req: ProjectGroupUpdateRequest) => Promise<ProjectGroupSummary>;
    remove: (name: string) => Promise<void>;
};
//# sourceMappingURL=useAdminProjectGroups.d.ts.map