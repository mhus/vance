import { useQuery } from '@tanstack/react-query';
import type { TenantProjectsResponse } from '@vance/generated';
import { listTenantProjects } from '@/api/projectsApi';

const projectKeys = {
  list: ['projects', 'list'] as const,
};

export function useTenantProjects() {
  return useQuery<TenantProjectsResponse>({
    queryKey: projectKeys.list,
    queryFn: listTenantProjects,
  });
}
