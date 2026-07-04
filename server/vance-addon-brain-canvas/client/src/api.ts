import { brainFetch } from '@vance/shared';
import type { CanvasbookView } from './generated/canvas/CanvasbookView';
import type { CanvasbookPageView } from './generated/canvas/CanvasbookPageView';
import type { CanvasbookRebuildResponse } from './generated/canvas/CanvasbookRebuildResponse';
import type { CanvasbookCreatePageRequest } from './generated/canvas/CanvasbookCreatePageRequest';
import type { CanvasGraphDto } from './generated/canvas/CanvasGraphDto';

function qs(params: Record<string, string>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) u.set(k, v);
  return u.toString();
}

export async function scanCanvasbook(
  projectId: string,
  folder: string,
): Promise<CanvasbookView> {
  return brainFetch<CanvasbookView>('GET', `addon/canvas/scan?${qs({ projectId, folder })}`);
}

export async function createCanvasPage(
  projectId: string,
  folder: string,
  request: CanvasbookCreatePageRequest,
): Promise<CanvasbookPageView> {
  return brainFetch<CanvasbookPageView>(
    'POST',
    `addon/canvas/page?${qs({ projectId, folder })}`,
    { body: request },
  );
}

export async function rebuildCanvasbook(
  projectId: string,
  folder: string,
): Promise<CanvasbookRebuildResponse> {
  return brainFetch<CanvasbookRebuildResponse>(
    'POST',
    `addon/canvas/rebuild?${qs({ projectId, folder })}`,
  );
}

export async function getGraph(projectId: string, path: string): Promise<CanvasGraphDto> {
  return brainFetch<CanvasGraphDto>('GET', `addon/canvas/graph?${qs({ projectId, path })}`);
}

export async function putGraph(
  projectId: string,
  path: string,
  graph: CanvasGraphDto,
): Promise<CanvasGraphDto> {
  return brainFetch<CanvasGraphDto>(
    'PUT',
    `addon/canvas/graph?${qs({ projectId, path })}`,
    { body: graph },
  );
}
