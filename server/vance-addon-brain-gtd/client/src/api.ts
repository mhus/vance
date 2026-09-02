import { brainFetch } from '@vance/shared';
import type { GtdView } from './generated/gtd/GtdView';
import type { GtdActionContentView } from './generated/gtd/GtdActionContentView';
import type { GtdActionRequest } from './generated/gtd/GtdActionRequest';
import type { GtdCaptureRequest } from './generated/gtd/GtdCaptureRequest';
import type { GtdMoveRequest } from './generated/gtd/GtdMoveRequest';
import type { GtdProjectMoveRequest } from './generated/gtd/GtdProjectMoveRequest';
import type { GtdReorderRequest } from './generated/gtd/GtdReorderRequest';
import type { GtdSearchResponse } from './generated/gtd/GtdSearchResponse';
import type { GtdRebuildResponse } from './generated/gtd/GtdRebuildResponse';

function qs(params: Record<string, string | number | undefined>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === '') continue;
    u.set(k, String(v));
  }
  return u.toString();
}

export async function scanGtd(projectId: string, folder: string): Promise<GtdView> {
  return brainFetch<GtdView>('GET', `addon/gtd/scan?${qs({ projectId, folder })}`);
}

export async function getGtdAction(
  projectId: string,
  path: string,
): Promise<GtdActionContentView> {
  return brainFetch<GtdActionContentView>('GET', `addon/gtd/action?${qs({ projectId, path })}`);
}

export async function captureGtd(
  projectId: string,
  folder: string,
  request: GtdCaptureRequest,
): Promise<GtdActionContentView> {
  return brainFetch<GtdActionContentView>(
    'POST',
    `addon/gtd/capture?${qs({ projectId, folder })}`,
    { body: request },
  );
}

export async function createGtdAction(
  projectId: string,
  folder: string,
  request: GtdActionRequest,
): Promise<GtdActionContentView> {
  return brainFetch<GtdActionContentView>(
    'POST',
    `addon/gtd/action?${qs({ projectId, folder })}`,
    { body: request },
  );
}

export async function patchGtdAction(
  projectId: string,
  path: string,
  request: GtdActionRequest,
): Promise<GtdActionContentView> {
  return brainFetch<GtdActionContentView>(
    'PATCH',
    `addon/gtd/action?${qs({ projectId, path })}`,
    { body: request },
  );
}

export async function moveGtdAction(
  projectId: string,
  folder: string,
  path: string,
  request: GtdMoveRequest,
): Promise<GtdActionContentView> {
  return brainFetch<GtdActionContentView>(
    'POST',
    `addon/gtd/move?${qs({ projectId, folder, path })}`,
    { body: request },
  );
}

/** Re-file an action into `projects/<name>/` — a blank project moves it to `actions/`. */
export async function moveGtdActionToProject(
  projectId: string,
  folder: string,
  path: string,
  request: GtdProjectMoveRequest,
): Promise<GtdActionContentView> {
  return brainFetch<GtdActionContentView>(
    'POST',
    `addon/gtd/project?${qs({ projectId, folder, path })}`,
    { body: request },
  );
}

/**
 * Delete an action — which the server reads as "move it to the trash bucket"
 * everywhere except inside the trash, where it is the project-wide soft delete.
 * The client does not choose: it says which action, the folder decides which
 * of the two happens.
 */
export async function deleteGtdAction(
  projectId: string,
  folder: string,
  path: string,
): Promise<void> {
  await brainFetch<unknown>('DELETE', `addon/gtd/action?${qs({ projectId, folder, path })}`);
}

export async function searchGtd(
  projectId: string,
  folder: string,
  query: string,
  context?: string,
): Promise<GtdSearchResponse> {
  return brainFetch<GtdSearchResponse>(
    'GET',
    `addon/gtd/search?${qs({ projectId, folder, q: query, context })}`,
  );
}

export async function rebuildGtd(
  projectId: string,
  folder: string,
): Promise<GtdRebuildResponse> {
  return brainFetch<GtdRebuildResponse>('POST', `addon/gtd/rebuild?${qs({ projectId, folder })}`);
}

/**
 * Persist a new manual order for one bucket (§8b) and get the fresh view back.
 *
 * `orderedIds` may be a subset of the bucket — a project or context filter
 * narrows the list. The server splices it into the order the manifest already
 * records rather than replacing it, drops ids that left the bucket, and writes
 * `_app.yaml` once. The returned view therefore carries the resynced order,
 * which need not be the one that was sent.
 */
export async function reorderGtdActions(
  projectId: string,
  folder: string,
  request: GtdReorderRequest,
): Promise<GtdView> {
  return brainFetch<GtdView>(
    'POST',
    `addon/gtd/reorder?${qs({ projectId, folder })}`,
    { body: request },
  );
}
