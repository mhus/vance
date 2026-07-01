import type {
  SessionGroupAssignRequest,
  SessionGroupCreateRequest,
  SessionGroupDto,
  SessionGroupRenameRequest,
  SessionGroupReorderRequest,
} from '@vance/generated';
import { brainFetch } from './restClient';

/**
 * Session groups — per-user, per-project grouping of sessions for UI
 * organisation only. Scoped server-side to (tenant, project, current user);
 * every call carries the {@code projectId}. See planning/session-groups.md.
 */

/** GET /brain/{tenant}/session-groups?projectId=… */
export async function listSessionGroups(
  projectId: string,
): Promise<SessionGroupDto[]> {
  return brainFetch<SessionGroupDto[]>(
    'GET',
    `session-groups?projectId=${encodeURIComponent(projectId)}`,
  );
}

/** POST /brain/{tenant}/session-groups */
export async function createSessionGroup(
  req: SessionGroupCreateRequest,
): Promise<SessionGroupDto> {
  return brainFetch<SessionGroupDto>('POST', 'session-groups', { body: req });
}

/** PUT /brain/{tenant}/session-groups/{name}?projectId=… — rename (title only). */
export async function renameSessionGroup(
  projectId: string,
  name: string,
  req: SessionGroupRenameRequest,
): Promise<SessionGroupDto> {
  return brainFetch<SessionGroupDto>(
    'PUT',
    `session-groups/${encodeURIComponent(name)}?projectId=${encodeURIComponent(projectId)}`,
    { body: req },
  );
}

/** DELETE /brain/{tenant}/session-groups/{name}?projectId=… — members become ungrouped. */
export async function deleteSessionGroup(
  projectId: string,
  name: string,
): Promise<void> {
  await brainFetch<void>(
    'DELETE',
    `session-groups/${encodeURIComponent(name)}?projectId=${encodeURIComponent(projectId)}`,
  );
}

/** PUT /brain/{tenant}/session-groups/order?projectId=… — returns the reordered list. */
export async function reorderSessionGroups(
  projectId: string,
  orderedNames: string[],
): Promise<SessionGroupDto[]> {
  const req: SessionGroupReorderRequest = { orderedNames };
  return brainFetch<SessionGroupDto[]>(
    'PUT',
    `session-groups/order?projectId=${encodeURIComponent(projectId)}`,
    { body: req },
  );
}

/**
 * PUT /brain/{tenant}/session-groups/assign?projectId=… — move a session into
 * {@code groupName}, or pass {@code null} to ungroup it.
 */
export async function assignSessionToGroup(
  projectId: string,
  sessionId: string,
  groupName: string | null,
): Promise<void> {
  // groupName omitted (undefined) deserialises to null server-side → ungroup.
  const req: SessionGroupAssignRequest =
    groupName === null ? { sessionId } : { sessionId, groupName };
  await brainFetch<void>(
    'PUT',
    `session-groups/assign?projectId=${encodeURIComponent(projectId)}`,
    { body: req },
  );
}
