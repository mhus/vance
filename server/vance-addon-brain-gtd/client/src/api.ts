import { brainFetch } from '@vance/shared';
import type { GtdView } from './generated/gtd/GtdView';
import type { GtdActionContentView } from './generated/gtd/GtdActionContentView';
import type { GtdActionRequest } from './generated/gtd/GtdActionRequest';
import type { GtdCaptureRequest } from './generated/gtd/GtdCaptureRequest';
import type { GtdMoveRequest } from './generated/gtd/GtdMoveRequest';
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

export async function deleteGtdAction(
  projectId: string,
  path: string,
): Promise<void> {
  await brainFetch<unknown>('DELETE', `addon/gtd/action?${qs({ projectId, path })}`);
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
