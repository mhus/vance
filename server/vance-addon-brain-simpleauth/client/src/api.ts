import { brainFetch } from '@vance/shared';
import type { GrantCreateRequest, GrantDto, GrantScopeType, GrantSubjectType } from './types';

const BASE = 'admin/permission-grants';

function qs(params: Record<string, string>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) u.set(k, v);
  return u.toString();
}

/** All grants on one scope. Requires ADMIN on that scope (enforced server-side). */
export async function listGrants(
  scopeType: GrantScopeType,
  scopeId: string,
): Promise<GrantDto[]> {
  return brainFetch<GrantDto[]>('GET', `${BASE}?${qs({ scopeType, scopeId })}`);
}

/** Grant or update a role. */
export async function setGrant(request: GrantCreateRequest): Promise<GrantDto> {
  return brainFetch<GrantDto>('POST', BASE, { body: request });
}

/** Remove a subject's grant on a scope. */
export async function removeGrant(
  scopeType: GrantScopeType,
  scopeId: string,
  subjectType: GrantSubjectType,
  subjectId: string,
): Promise<void> {
  await brainFetch<void>(
    'DELETE',
    `${BASE}?${qs({ scopeType, scopeId, subjectType, subjectId })}`,
  );
}
