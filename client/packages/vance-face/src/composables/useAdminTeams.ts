import { ref, type Ref } from 'vue';
import type {
  TeamCreateRequest,
  TeamDto,
  TeamUpdateRequest,
} from '@vance/generated';
import { brainFetch } from '@vance/shared';

/** Admin CRUD on teams. */
export function useAdminTeams(): {
  teams: Ref<TeamDto[]>;
  loading: Ref<boolean>;
  busy: Ref<boolean>;
  error: Ref<string | null>;
  reload: () => Promise<void>;
  create: (req: TeamCreateRequest) => Promise<TeamDto>;
  update: (name: string, req: TeamUpdateRequest) => Promise<TeamDto>;
  remove: (name: string) => Promise<void>;
} {
  const teams = ref<TeamDto[]>([]);
  const loading = ref(false);
  const busy = ref(false);
  const error = ref<string | null>(null);

  async function reload(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      teams.value = await brainFetch<TeamDto[]>('GET', 'admin/teams');
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load teams.';
      teams.value = [];
    } finally {
      loading.value = false;
    }
  }

  async function create(req: TeamCreateRequest): Promise<TeamDto> {
    busy.value = true;
    error.value = null;
    try {
      const created = await brainFetch<TeamDto>('POST', 'admin/teams', { body: req });
      await reload();
      return created;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to create team.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  async function update(name: string, req: TeamUpdateRequest): Promise<TeamDto> {
    busy.value = true;
    error.value = null;
    try {
      const saved = await brainFetch<TeamDto>(
        'PUT', `admin/teams/${encodeURIComponent(name)}`, { body: req });
      await reload();
      return saved;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to update team.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  async function remove(name: string): Promise<void> {
    busy.value = true;
    error.value = null;
    try {
      await brainFetch<void>('DELETE', `admin/teams/${encodeURIComponent(name)}`);
      await reload();
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to delete team.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  return { teams, loading, busy, error, reload, create, update, remove };
}
