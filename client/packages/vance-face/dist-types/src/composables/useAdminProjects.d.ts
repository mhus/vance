import { type Ref } from 'vue';
import type { ProjectCreateRequest, ProjectDto, ProjectUpdateRequest } from '@vance/generated';
/**
 * CRUD on projects. {@code remove} archives — sets status to ARCHIVED and
 * moves the project into the reserved "archived" group.
 */
export declare function useAdminProjects(): {
    projects: Ref<ProjectDto[]>;
    loading: Ref<boolean>;
    busy: Ref<boolean>;
    error: Ref<string | null>;
    reload: () => Promise<void>;
    create: (req: ProjectCreateRequest) => Promise<ProjectDto>;
    update: (name: string, req: ProjectUpdateRequest) => Promise<ProjectDto>;
    archive: (name: string) => Promise<ProjectDto>;
};
//# sourceMappingURL=useAdminProjects.d.ts.map