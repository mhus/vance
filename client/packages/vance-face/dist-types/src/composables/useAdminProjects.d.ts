import { type Ref } from 'vue';
import type { ProjectCreateRequest, ProjectDto, ProjectUpdateRequest } from '@vance/generated';
/** Result of a {@link useAdminProjects.create} call. */
export interface ProjectCreateResult {
    project: ProjectDto;
    /**
     * Set when the catalog kit referenced by {@code kitName} could not be
     * installed but the project was created anyway. Backend surfaces this
     * via the {@code X-Vance-Kit-Install-Error} response header.
     */
    kitInstallError: string | null;
}
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
    create: (req: ProjectCreateRequest) => Promise<ProjectCreateResult>;
    update: (name: string, req: ProjectUpdateRequest) => Promise<ProjectDto>;
    archive: (name: string) => Promise<ProjectDto>;
};
//# sourceMappingURL=useAdminProjects.d.ts.map