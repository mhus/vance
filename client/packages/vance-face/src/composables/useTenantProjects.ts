import { computed, ref, type Ref } from 'vue';
import type { ProjectGroupSummary, ProjectSummary, TenantProjectsResponse } from '@vance/generated';
import { brainFetch } from '@vance/shared';
import { getActiveUiLevel } from '@/platform';
import { pushProjectSnapshot } from '@/platform/faceliftShareSetup';
import { isHiddenProject } from '@/util/hiddenProjects';

/**
 * Loads the tenant's project groups and projects once. Used by the project
 * selector in the document editor (and any future editor that needs the same
 * dropdown).
 *
 * <p>With {@code hideSystemProject} the standard surface does not list the
 * tenant project (see {@link isHiddenProject}); the expert surface and
 * callers that do not ask still get every project.
 */
export function useTenantProjects(options: { hideSystemProject?: boolean } = {}): {
  groups: Ref<ProjectGroupSummary[]>;
  projects: Readonly<Ref<ProjectSummary[]>>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  reload: () => Promise<void>;
} {
  const groups = ref<ProjectGroupSummary[]>([]);
  const allProjects = ref<ProjectSummary[]>([]);
  const projects = computed(() =>
    options.hideSystemProject
      ? allProjects.value.filter((p) => !isHiddenProject(p.name, getActiveUiLevel()))
      : allProjects.value,
  );
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function reload(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const data = await brainFetch<TenantProjectsResponse>('GET', 'projects');
      groups.value = data.groups ?? [];
      allProjects.value = data.projects ?? [];
      // No-op outside Facelift; otherwise persists the list so the
      // iOS Share-Extension's project picker stays in sync.
      pushProjectSnapshot(allProjects.value);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load projects.';
    } finally {
      loading.value = false;
    }
  }

  return { groups, projects, loading, error, reload };
}
