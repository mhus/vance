import { brainFetch } from '@vance/shared';
import type { WorkspaceView } from './generated/workspace/WorkspaceView';
import type { WorkspaceRebuildResponse } from './generated/workspace/WorkspaceRebuildResponse';

function qs(params: Record<string, string>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) u.set(k, v);
  return u.toString();
}

export async function scanWorkspace(
  projectId: string,
  folder: string,
): Promise<WorkspaceView> {
  return brainFetch<WorkspaceView>('GET', `workspace/scan?${qs({ projectId, folder })}`);
}

export async function rebuildWorkspace(
  projectId: string,
  folder: string,
): Promise<WorkspaceRebuildResponse> {
  return brainFetch<WorkspaceRebuildResponse>(
    'POST',
    `workspace/rebuild?${qs({ projectId, folder })}`,
  );
}
