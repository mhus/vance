import { ref, type Ref } from 'vue';
import type { ProjectGroupSummary, ProjectSummary, TenantProjectsResponse } from '@vance/generated';
import { brainFetch } from '@vance/shared';

/**
 * Loads the tenant's project groups and projects once. Used by the project
 * selector in the document editor (and any future editor that needs the same
 * dropdown).
 */
export function useTenantProjects(): {
  groups: Ref<ProjectGroupSummary[]>;
  projects: Ref<ProjectSummary[]>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  reload: () => Promise<void>;
} {
  const groups = ref<ProjectGroupSummary[]>([]);
  const projects = ref<ProjectSummary[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function reload(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const data = await brainFetch<TenantProjectsResponse>('GET', 'projects');
      groups.value = data.groups ?? [];
      projects.value = data.projects ?? [];
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load projects.';
    } finally {
      loading.value = false;
    }
  }

  return { groups, projects, loading, error, reload };
}
