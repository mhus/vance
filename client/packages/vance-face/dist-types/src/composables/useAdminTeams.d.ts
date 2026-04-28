import { type Ref } from 'vue';
import type { TeamCreateRequest, TeamDto, TeamUpdateRequest } from '@vance/generated';
/** Admin CRUD on teams. */
export declare function useAdminTeams(): {
    teams: Ref<TeamDto[]>;
    loading: Ref<boolean>;
    busy: Ref<boolean>;
    error: Ref<string | null>;
    reload: () => Promise<void>;
    create: (req: TeamCreateRequest) => Promise<TeamDto>;
    update: (name: string, req: TeamUpdateRequest) => Promise<TeamDto>;
    remove: (name: string) => Promise<void>;
};
//# sourceMappingURL=useAdminTeams.d.ts.map