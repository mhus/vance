import { type Ref } from 'vue';
import type { UserCreateRequest, UserDto, UserUpdateRequest } from '@vance/generated';
/** Admin CRUD on users — passwords go through a dedicated endpoint. */
export declare function useAdminUsers(): {
    users: Ref<UserDto[]>;
    loading: Ref<boolean>;
    busy: Ref<boolean>;
    error: Ref<string | null>;
    reload: () => Promise<void>;
    create: (req: UserCreateRequest) => Promise<UserDto>;
    update: (name: string, req: UserUpdateRequest) => Promise<UserDto>;
    setPassword: (name: string, password: string) => Promise<void>;
    remove: (name: string) => Promise<void>;
};
//# sourceMappingURL=useAdminUsers.d.ts.map