import { brainFetch } from '@vance/shared';
import type { BinderView } from './generated/binder/BinderView';
import type { BinderDocSearchResponse } from './generated/binder/BinderDocSearchResponse';
import type { RebuildResponse } from './generated/binder/RebuildResponse';

function qs(params: Record<string, string>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) u.set(k, v);
  return u.toString();
}

export async function scanBinder(projectId: string, folder: string): Promise<BinderView> {
  return brainFetch<BinderView>('GET', `addon/binder/scan?${qs({ projectId, folder })}`);
}

export async function addEntry(
  projectId: string,
  folder: string,
  ref: string,
  section?: string | null,
  title?: string | null,
): Promise<BinderView> {
  return brainFetch<BinderView>('POST', `addon/binder/entry?${qs({ projectId, folder })}`, {
    body: { ref, section: section ?? null, title: title ?? null },
  });
}

export async function removeEntry(
  projectId: string,
  folder: string,
  ref: string,
): Promise<BinderView> {
  return brainFetch<BinderView>(
    'DELETE',
    `addon/binder/entry?${qs({ projectId, folder, ref })}`,
  );
}

export async function reorderBinder(
  projectId: string,
  folder: string,
  orderedRefs: string[],
): Promise<BinderView> {
  return brainFetch<BinderView>('POST', `addon/binder/reorder?${qs({ projectId, folder })}`, {
    body: { orderedRefs },
  });
}

export async function setEntrySection(
  projectId: string,
  folder: string,
  ref: string,
  section?: string | null,
  title?: string | null,
): Promise<BinderView> {
  return brainFetch<BinderView>(
    'POST',
    `addon/binder/entry/section?${qs({ projectId, folder })}`,
    { body: { ref, section: section ?? null, title: title ?? null } },
  );
}

export async function setLanding(
  projectId: string,
  folder: string,
  ref?: string | null,
): Promise<BinderView> {
  return brainFetch<BinderView>('POST', `addon/binder/landing?${qs({ projectId, folder })}`, {
    body: { ref: ref ?? null },
  });
}

export async function searchDocuments(
  projectId: string,
  query: string,
): Promise<BinderDocSearchResponse> {
  return brainFetch<BinderDocSearchResponse>(
    'GET',
    `addon/binder/documents/search?${qs({ projectId, query, size: '40' })}`,
  );
}

export async function rebuildBinder(projectId: string, folder: string): Promise<RebuildResponse> {
  return brainFetch<RebuildResponse>('POST', `addon/binder/rebuild?${qs({ projectId, folder })}`);
}
