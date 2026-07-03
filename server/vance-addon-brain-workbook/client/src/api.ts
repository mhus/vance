import { brainFetch } from '@vance/shared';
import type { WorkbookView } from './generated/workbook/WorkbookView';
import type { WorkbookRebuildResponse } from './generated/workbook/WorkbookRebuildResponse';
import type { WorkbookCreatePageRequest } from './generated/workbook/WorkbookCreatePageRequest';
import type { WorkbookUpdatePageRequest } from './generated/workbook/WorkbookUpdatePageRequest';
import type { WorkbookReorderRequest } from './generated/workbook/WorkbookReorderRequest';
import type { WorkbookRenameSectionRequest } from './generated/workbook/WorkbookRenameSectionRequest';
import type { WorkbookSetLandingRequest } from './generated/workbook/WorkbookSetLandingRequest';
import type { WorkbookPageView } from './generated/workbook/WorkbookPageView';

function qs(params: Record<string, string>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) u.set(k, v);
  return u.toString();
}

export async function scanWorkbook(
  projectId: string,
  folder: string,
): Promise<WorkbookView> {
  return brainFetch<WorkbookView>('GET', `addon/workbook/scan?${qs({ projectId, folder })}`);
}

export async function rebuildWorkbook(
  projectId: string,
  folder: string,
): Promise<WorkbookRebuildResponse> {
  return brainFetch<WorkbookRebuildResponse>(
    'POST',
    `addon/workbook/rebuild?${qs({ projectId, folder })}`,
  );
}

export async function createWorkbookPage(
  projectId: string,
  folder: string,
  request: WorkbookCreatePageRequest,
): Promise<WorkbookPageView> {
  return brainFetch<WorkbookPageView>(
    'POST',
    `addon/workbook/page?${qs({ projectId, folder })}`,
    { body: request },
  );
}

export async function updateWorkbookPage(
  projectId: string,
  folder: string,
  id: string,
  request: WorkbookUpdatePageRequest,
): Promise<WorkbookPageView> {
  return brainFetch<WorkbookPageView>(
    'PUT',
    `addon/workbook/page/${encodeURIComponent(id)}?${qs({ projectId, folder })}`,
    { body: request },
  );
}

export async function reorderWorkbookPages(
  projectId: string,
  folder: string,
  request: WorkbookReorderRequest,
): Promise<void> {
  await brainFetch<unknown>(
    'POST',
    `addon/workbook/reorder?${qs({ projectId, folder })}`,
    { body: request },
  );
}

export async function duplicateWorkbookPage(
  projectId: string,
  folder: string,
  id: string,
): Promise<WorkbookPageView> {
  return brainFetch<WorkbookPageView>(
    'POST',
    `addon/workbook/page/${encodeURIComponent(id)}/duplicate?${qs({ projectId, folder })}`,
  );
}

export async function setWorkbookLandingPage(
  projectId: string,
  folder: string,
  request: WorkbookSetLandingRequest,
): Promise<WorkbookView> {
  return brainFetch<WorkbookView>(
    'POST',
    `addon/workbook/landing?${qs({ projectId, folder })}`,
    { body: request },
  );
}

export async function renameWorkbookSection(
  projectId: string,
  folder: string,
  request: WorkbookRenameSectionRequest,
): Promise<void> {
  await brainFetch<unknown>(
    'POST',
    `addon/workbook/section/rename?${qs({ projectId, folder })}`,
    { body: request },
  );
}

export async function deleteWorkbookPage(
  projectId: string,
  folder: string,
  id: string,
): Promise<void> {
  await brainFetch<unknown>(
    'DELETE',
    `addon/workbook/page/${encodeURIComponent(id)}?${qs({ projectId, folder })}`,
  );
}
