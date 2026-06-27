import { brainFetch } from '@vance/shared';
import type { WorkspaceView } from './generated/workspace/WorkspaceView';
import type { WorkspaceRebuildResponse } from './generated/workspace/WorkspaceRebuildResponse';
import type { WorkspaceCreatePageRequest } from './generated/workspace/WorkspaceCreatePageRequest';
import type { WorkspacePageView } from './generated/workspace/WorkspacePageView';

function qs(params: Record<string, string>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) u.set(k, v);
  return u.toString();
}

export async function scanWorkspace(
  projectId: string,
  folder: string,
): Promise<WorkspaceView> {
  return brainFetch<WorkspaceView>('GET', `addon/workspace/scan?${qs({ projectId, folder })}`);
}

export async function rebuildWorkspace(
  projectId: string,
  folder: string,
): Promise<WorkspaceRebuildResponse> {
  return brainFetch<WorkspaceRebuildResponse>(
    'POST',
    `addon/workspace/rebuild?${qs({ projectId, folder })}`,
  );
}

export async function createWorkspacePage(
  projectId: string,
  folder: string,
  request: WorkspaceCreatePageRequest,
): Promise<WorkspacePageView> {
  return brainFetch<WorkspacePageView>(
    'POST',
    `addon/workspace/page?${qs({ projectId, folder })}`,
    { body: request },
  );
}
