import { ref, type Ref } from 'vue';
import type {
  UserCreateRequest,
  UserDto,
  UserPasswordRequest,
  UserUpdateRequest,
} from '@vance/generated';
import { brainFetch } from '@vance/shared';

/** Admin CRUD on users — passwords go through a dedicated endpoint. */
export function useAdminUsers(): {
  users: Ref<UserDto[]>;
  loading: Ref<boolean>;
  busy: Ref<boolean>;
  error: Ref<string | null>;
  reload: () => Promise<void>;
  create: (req: UserCreateRequest) => Promise<UserDto>;
  update: (name: string, req: UserUpdateRequest) => Promise<UserDto>;
  setPassword: (name: string, password: string) => Promise<void>;
  remove: (name: string) => Promise<void>;
} {
  const users = ref<UserDto[]>([]);
  const loading = ref(false);
  const busy = ref(false);
  const error = ref<string | null>(null);

  async function reload(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      users.value = await brainFetch<UserDto[]>('GET', 'admin/users');
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load users.';
      users.value = [];
    } finally {
      loading.value = false;
    }
  }

  async function create(req: UserCreateRequest): Promise<UserDto> {
    busy.value = true;
    error.value = null;
    try {
      const created = await brainFetch<UserDto>('POST', 'admin/users', { body: req });
      await reload();
      return created;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to create user.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  async function update(name: string, req: UserUpdateRequest): Promise<UserDto> {
    busy.value = true;
    error.value = null;
    try {
      const saved = await brainFetch<UserDto>(
        'PUT', `admin/users/${encodeURIComponent(name)}`, { body: req });
      await reload();
      return saved;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to update user.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  async function setPassword(name: string, password: string): Promise<void> {
    busy.value = true;
    error.value = null;
    try {
      const body: UserPasswordRequest = { password };
      await brainFetch<void>(
        'PUT', `admin/users/${encodeURIComponent(name)}/password`, { body });
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to set password.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  async function remove(name: string): Promise<void> {
    busy.value = true;
    error.value = null;
    try {
      await brainFetch<void>('DELETE', `admin/users/${encodeURIComponent(name)}`);
      await reload();
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to delete user.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  return { users, loading, busy, error, reload, create, update, setPassword, remove };
}
