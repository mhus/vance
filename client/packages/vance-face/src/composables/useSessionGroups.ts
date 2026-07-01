import { ref, type Ref } from 'vue';
import type { SessionGroupDto } from '@vance/generated';
import {
  assignSessionToGroup,
  createSessionGroup,
  deleteSessionGroup,
  listSessionGroups,
  renameSessionGroup,
  reorderSessionGroups,
} from '@vance/shared';

/**
 * CRUD on session groups — per-user, per-project grouping of sessions for UI
 * organisation only. Every call carries the {@code projectId} (session groups
 * are scoped to (tenant, project, current user) server-side).
 *
 * Shared by scopes.html (admin CRUD), the Cortex picker (read-only), and the
 * chat picker (full: create + reorder + assign). See planning/session-groups.md.
 */
export function useSessionGroups(): {
  groups: Ref<SessionGroupDto[]>;
  loading: Ref<boolean>;
  busy: Ref<boolean>;
  error: Ref<string | null>;
  reload: (projectId: string) => Promise<void>;
  create: (projectId: string, name: string, title: string | null) => Promise<SessionGroupDto>;
  rename: (projectId: string, name: string, title: string | null) => Promise<SessionGroupDto>;
  remove: (projectId: string, name: string) => Promise<void>;
  reorder: (projectId: string, orderedNames: string[]) => Promise<void>;
  assign: (projectId: string, sessionId: string, groupName: string | null) => Promise<void>;
} {
  const groups = ref<SessionGroupDto[]>([]);
  const loading = ref(false);
  const busy = ref(false);
  const error = ref<string | null>(null);

  async function reload(projectId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      groups.value = await listSessionGroups(projectId);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load session groups.';
    } finally {
      loading.value = false;
    }
  }

  async function create(
    projectId: string,
    name: string,
    title: string | null,
  ): Promise<SessionGroupDto> {
    busy.value = true;
    error.value = null;
    try {
      const created = await createSessionGroup({
        projectId,
        name,
        title: title ?? undefined,
      });
      await reload(projectId);
      return created;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to create session group.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  async function rename(
    projectId: string,
    name: string,
    title: string | null,
  ): Promise<SessionGroupDto> {
    busy.value = true;
    error.value = null;
    try {
      const saved = await renameSessionGroup(projectId, name, { title: title ?? undefined });
      await reload(projectId);
      return saved;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to rename session group.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  async function remove(projectId: string, name: string): Promise<void> {
    busy.value = true;
    error.value = null;
    try {
      await deleteSessionGroup(projectId, name);
      await reload(projectId);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to delete session group.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  async function reorder(projectId: string, orderedNames: string[]): Promise<void> {
    busy.value = true;
    error.value = null;
    try {
      groups.value = await reorderSessionGroups(projectId, orderedNames);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to reorder session groups.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  async function assign(
    projectId: string,
    sessionId: string,
    groupName: string | null,
  ): Promise<void> {
    busy.value = true;
    error.value = null;
    try {
      await assignSessionToGroup(projectId, sessionId, groupName);
      await reload(projectId);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to move session.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  return { groups, loading, busy, error, reload, create, rename, remove, reorder, assign };
}
