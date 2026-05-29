import type {
  KanbanBoardView,
  KanbanCardCreateRequest,
  KanbanCardUpdateRequest,
  KanbanCardView,
  KanbanMoveRequest,
  KanbanMoveResponse,
  KanbanRebuildResponse,
} from '@vance/generated';
import { brainFetch } from './restClient';

function qs(params: Record<string, string>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) u.set(k, v);
  return u.toString();
}

export async function getKanbanBoard(
  projectId: string,
  folder: string,
): Promise<KanbanBoardView> {
  return brainFetch<KanbanBoardView>(
    'GET',
    `kanban/board?${qs({ projectId, folder })}`,
  );
}

export async function moveKanbanCard(
  projectId: string,
  folder: string,
  request: KanbanMoveRequest,
): Promise<KanbanMoveResponse> {
  return brainFetch<KanbanMoveResponse>(
    'POST',
    `kanban/move?${qs({ projectId, folder })}`,
    { body: request },
  );
}

export async function createKanbanCard(
  projectId: string,
  folder: string,
  request: KanbanCardCreateRequest,
): Promise<KanbanCardView> {
  return brainFetch<KanbanCardView>(
    'POST',
    `kanban/cards?${qs({ projectId, folder })}`,
    { body: request },
  );
}

export async function updateKanbanCard(
  projectId: string,
  folder: string,
  path: string,
  request: KanbanCardUpdateRequest,
): Promise<KanbanCardView> {
  return brainFetch<KanbanCardView>(
    'PATCH',
    `kanban/cards?${qs({ projectId, folder, path })}`,
    { body: request },
  );
}

export async function deleteKanbanCard(
  projectId: string,
  folder: string,
  path: string,
): Promise<void> {
  return brainFetch<void>(
    'DELETE',
    `kanban/cards?${qs({ projectId, folder, path })}`,
  );
}

export async function rebuildKanbanBoard(
  projectId: string,
  folder: string,
): Promise<KanbanRebuildResponse> {
  return brainFetch<KanbanRebuildResponse>(
    'POST',
    `kanban/rebuild?${qs({ projectId, folder })}`,
  );
}
