import { brainFetch } from '@vance/shared';
import type { TenantProjectsResponse } from '@vance/generated';

/**
 * Read-only projects view for the Documents picker. Same endpoint
 * the Web UI uses; mobile just consumes the `projects` slice and
 * ignores the `groups` (group navigation isn't a v1 mobile flow).
 */
export function listTenantProjects(): Promise<TenantProjectsResponse> {
  return brainFetch<TenantProjectsResponse>('GET', 'projects');
}
