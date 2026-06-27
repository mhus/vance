import { brainFetch } from '@vance/shared';
import type { KanbanBoardView } from './generated/kanban/KanbanBoardView';
import type { KanbanCardCreateRequest } from './generated/kanban/KanbanCardCreateRequest';
import type { KanbanCardUpdateRequest } from './generated/kanban/KanbanCardUpdateRequest';
import type { KanbanCardView } from './generated/kanban/KanbanCardView';
import type { KanbanMoveRequest } from './generated/kanban/KanbanMoveRequest';
import type { KanbanMoveResponse } from './generated/kanban/KanbanMoveResponse';
import type { KanbanRebuildResponse } from './generated/kanban/KanbanRebuildResponse';

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
    `addon/kanban/board?${qs({ projectId, folder })}`,
  );
}

export async function moveKanbanCard(
  projectId: string,
  folder: string,
  request: KanbanMoveRequest,
): Promise<KanbanMoveResponse> {
  return brainFetch<KanbanMoveResponse>(
    'POST',
    `addon/kanban/move?${qs({ projectId, folder })}`,
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
    `addon/kanban/cards?${qs({ projectId, folder })}`,
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
    `addon/kanban/cards?${qs({ projectId, folder, path })}`,
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
    `addon/kanban/cards?${qs({ projectId, folder, path })}`,
  );
}

export async function rebuildKanbanBoard(
  projectId: string,
  folder: string,
): Promise<KanbanRebuildResponse> {
  return brainFetch<KanbanRebuildResponse>(
    'POST',
    `addon/kanban/rebuild?${qs({ projectId, folder })}`,
  );
}
