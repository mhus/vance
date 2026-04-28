import { ref, type Ref } from 'vue';
import type {
  ProjectCreateRequest,
  ProjectDto,
  ProjectUpdateRequest,
} from '@vance/generated';
import { brainFetch } from '@vance/shared';

/**
 * CRUD on projects. {@code remove} archives — sets status to ARCHIVED and
 * moves the project into the reserved "archived" group.
 */
export function useAdminProjects(): {
  projects: Ref<ProjectDto[]>;
  loading: Ref<boolean>;
  busy: Ref<boolean>;
  error: Ref<string | null>;
  reload: () => Promise<void>;
  create: (req: ProjectCreateRequest) => Promise<ProjectDto>;
  update: (name: string, req: ProjectUpdateRequest) => Promise<ProjectDto>;
  archive: (name: string) => Promise<ProjectDto>;
} {
  const projects = ref<ProjectDto[]>([]);
  const loading = ref(false);
  const busy = ref(false);
  const error = ref<string | null>(null);

  async function reload(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      projects.value = await brainFetch<ProjectDto[]>('GET', 'admin/projects');
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load projects.';
    } finally {
      loading.value = false;
    }
  }

  async function create(req: ProjectCreateRequest): Promise<ProjectDto> {
    busy.value = true;
    error.value = null;
    try {
      const created = await brainFetch<ProjectDto>('POST', 'admin/projects', { body: req });
      await reload();
      return created;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to create project.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  async function update(name: string, req: ProjectUpdateRequest): Promise<ProjectDto> {
    busy.value = true;
    error.value = null;
    try {
      const saved = await brainFetch<ProjectDto>(
        'PUT', `admin/projects/${encodeURIComponent(name)}`, { body: req });
      await reload();
      return saved;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to update project.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  async function archive(name: string): Promise<ProjectDto> {
    busy.value = true;
    error.value = null;
    try {
      const saved = await brainFetch<ProjectDto>(
        'DELETE', `admin/projects/${encodeURIComponent(name)}`);
      await reload();
      return saved;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to archive project.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  return { projects, loading, busy, error, reload, create, update, archive };
}
