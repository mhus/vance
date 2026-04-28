import { ref, type Ref } from 'vue';
import type {
  ProjectGroupCreateRequest,
  ProjectGroupSummary,
  ProjectGroupUpdateRequest,
} from '@vance/generated';
import { brainFetch } from '@vance/shared';

/**
 * CRUD on project groups. Server enforces "delete only if empty" via 409.
 */
export function useAdminProjectGroups(): {
  groups: Ref<ProjectGroupSummary[]>;
  loading: Ref<boolean>;
  busy: Ref<boolean>;
  error: Ref<string | null>;
  reload: () => Promise<void>;
  create: (req: ProjectGroupCreateRequest) => Promise<ProjectGroupSummary>;
  update: (name: string, req: ProjectGroupUpdateRequest) => Promise<ProjectGroupSummary>;
  remove: (name: string) => Promise<void>;
} {
  const groups = ref<ProjectGroupSummary[]>([]);
  const loading = ref(false);
  const busy = ref(false);
  const error = ref<string | null>(null);

  async function reload(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      groups.value = await brainFetch<ProjectGroupSummary[]>('GET', 'admin/project-groups');
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load project groups.';
    } finally {
      loading.value = false;
    }
  }

  async function create(req: ProjectGroupCreateRequest): Promise<ProjectGroupSummary> {
    busy.value = true;
    error.value = null;
    try {
      const created = await brainFetch<ProjectGroupSummary>(
        'POST', 'admin/project-groups', { body: req });
      await reload();
      return created;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to create group.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  async function update(name: string, req: ProjectGroupUpdateRequest): Promise<ProjectGroupSummary> {
    busy.value = true;
    error.value = null;
    try {
      const saved = await brainFetch<ProjectGroupSummary>(
        'PUT', `admin/project-groups/${encodeURIComponent(name)}`, { body: req });
      await reload();
      return saved;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to update group.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  async function remove(name: string): Promise<void> {
    busy.value = true;
    error.value = null;
    try {
      await brainFetch<void>('DELETE', `admin/project-groups/${encodeURIComponent(name)}`);
      await reload();
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to delete group.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  return { groups, loading, busy, error, reload, create, update, remove };
}
