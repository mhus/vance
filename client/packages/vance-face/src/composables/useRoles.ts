import { ref, type Ref } from 'vue';
import {
  ProjectRole,
  TenantRole,
  type RoleAssignmentDto,
  type RoleSubjectType,
} from '@vance/generated';
import { brainFetch, RestError } from '@vance/shared';

/** Rank order of the tenant roles — declaration order of the Java enum. */
export const TENANT_ROLES: TenantRole[] = [
  TenantRole.USER,
  TenantRole.CREATOR,
  TenantRole.ADMIN,
  TenantRole.TENANT_ADMIN,
];

/** Rank order of the project roles. */
export const PROJECT_ROLES: ProjectRole[] = [ProjectRole.READER, ProjectRole.MEMBER, ProjectRole.ADMIN];

/** `actual` is at least `required` in the tenant-role order. */
export function tenantRoleAtLeast(actual: TenantRole | null | undefined, required: TenantRole): boolean {
  if (!actual) return false;
  return TENANT_ROLES.indexOf(actual) >= TENANT_ROLES.indexOf(required);
}

/** Tenant roles of users (tenant ADMIN). Everyone not listed holds USER. */
export function useTenantRoles(): {
  assignments: Ref<RoleAssignmentDto[]>;
  busy: Ref<boolean>;
  error: Ref<string | null>;
  reload: () => Promise<void>;
  roleOf: (user: string) => TenantRole;
  setRole: (user: string, role: TenantRole) => Promise<void>;
} {
  const assignments = ref<RoleAssignmentDto[]>([]);
  const busy = ref(false);
  const error = ref<string | null>(null);

  async function reload(): Promise<void> {
    error.value = null;
    try {
      assignments.value = await brainFetch<RoleAssignmentDto[]>('GET', 'admin/roles/tenant');
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load tenant roles.';
      assignments.value = [];
    }
  }

  function roleOf(user: string): TenantRole {
    const hit = assignments.value.find((a) => a.subjectId === user && a.tenantRole);
    return hit?.tenantRole ?? TenantRole.USER;
  }

  async function setRole(user: string, role: TenantRole): Promise<void> {
    busy.value = true;
    error.value = null;
    try {
      await brainFetch<void>('PUT', `admin/roles/tenant/${encodeURIComponent(user)}`, { body: { role } });
      await reload();
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to set the tenant role.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  return { assignments, busy, error, reload, roleOf, setRole };
}

/** Members of one project (project ADMIN). */
export function useProjectMembers(): {
  members: Ref<RoleAssignmentDto[]>;
  /** The caller is no admin of the project — the membership is not theirs to see. */
  forbidden: Ref<boolean>;
  loading: Ref<boolean>;
  busy: Ref<boolean>;
  error: Ref<string | null>;
  load: (project: string) => Promise<void>;
  setMember: (project: string, subjectType: RoleSubjectType, subjectId: string, role: ProjectRole) => Promise<void>;
  removeMember: (project: string, subjectType: RoleSubjectType, subjectId: string) => Promise<void>;
} {
  const members = ref<RoleAssignmentDto[]>([]);
  const forbidden = ref(false);
  const loading = ref(false);
  const busy = ref(false);
  const error = ref<string | null>(null);

  async function load(project: string): Promise<void> {
    loading.value = true;
    error.value = null;
    forbidden.value = false;
    try {
      members.value = await brainFetch<RoleAssignmentDto[]>(
        'GET', `admin/roles/projects/${encodeURIComponent(project)}`);
    } catch (e) {
      members.value = [];
      if (e instanceof RestError && e.status === 403) {
        forbidden.value = true;
        return;
      }
      error.value = e instanceof Error ? e.message : 'Failed to load project members.';
    } finally {
      loading.value = false;
    }
  }

  async function setMember(
    project: string, subjectType: RoleSubjectType, subjectId: string, role: ProjectRole,
  ): Promise<void> {
    busy.value = true;
    error.value = null;
    try {
      await brainFetch<RoleAssignmentDto>('PUT', `admin/roles/projects/${encodeURIComponent(project)}`, {
        body: { subjectType, subjectId, role },
      });
      await load(project);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to set the project role.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  async function removeMember(project: string, subjectType: RoleSubjectType, subjectId: string): Promise<void> {
    busy.value = true;
    error.value = null;
    try {
      const query = `?subjectType=${encodeURIComponent(subjectType)}&subjectId=${encodeURIComponent(subjectId)}`;
      await brainFetch<void>('DELETE', `admin/roles/projects/${encodeURIComponent(project)}${query}`);
      await load(project);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to remove the member.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  return { members, forbidden, loading, busy, error, load, setMember, removeMember };
}
