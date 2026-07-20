import { brainFetch } from '@vance/shared';
import type { WikiView } from './generated/wiki/WikiView';
import type { WikiPageView } from './generated/wiki/WikiPageView';
import type { WikiRebuildResponse } from './generated/wiki/WikiRebuildResponse';
import type { WikiResolveResponse } from './generated/wiki/WikiResolveResponse';
import type { WikiBacklinksView } from './generated/wiki/WikiBacklinksView';
import type { WikiCreatePageRequest } from './generated/wiki/WikiCreatePageRequest';
import type { WikiDocumentSearchResponse } from './generated/wiki/WikiDocumentSearchResponse';

/** Build a query string, skipping undefined / empty values. */
function qs(params: Record<string, string | number | undefined>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === '') continue;
    u.set(k, String(v));
  }
  return u.toString();
}

export async function scanWiki(
  projectId: string,
  folder: string,
): Promise<WikiView> {
  return brainFetch<WikiView>('GET', `addon/wiki/scan?${qs({ projectId, folder })}`);
}

export async function rebuildWiki(
  projectId: string,
  folder: string,
): Promise<WikiRebuildResponse> {
  return brainFetch<WikiRebuildResponse>(
    'POST',
    `addon/wiki/rebuild?${qs({ projectId, folder })}`,
  );
}

export async function createWikiPage(
  projectId: string,
  folder: string,
  request: WikiCreatePageRequest,
): Promise<WikiPageView> {
  return brainFetch<WikiPageView>(
    'POST',
    `addon/wiki/page?${qs({ projectId, folder })}`,
    { body: request },
  );
}

export async function deleteWikiPage(
  projectId: string,
  folder: string,
  id: string,
): Promise<void> {
  await brainFetch<unknown>(
    'DELETE',
    `addon/wiki/page/${encodeURIComponent(id)}?${qs({ projectId, folder })}`,
  );
}

export async function resolveWikiLink(
  projectId: string,
  folder: string,
  target: string,
  space?: string,
): Promise<WikiResolveResponse> {
  return brainFetch<WikiResolveResponse>(
    'GET',
    `addon/wiki/resolve?${qs({ projectId, folder, target, space })}`,
  );
}

export async function wikiBacklinks(
  projectId: string,
  folder: string,
  path: string,
): Promise<WikiBacklinksView> {
  return brainFetch<WikiBacklinksView>(
    'GET',
    `addon/wiki/backlinks?${qs({ projectId, folder, path })}`,
  );
}

export async function wikiRecent(
  projectId: string,
  folder: string,
  limit?: number,
): Promise<WikiPageView[]> {
  return brainFetch<WikiPageView[]>(
    'GET',
    `addon/wiki/recent?${qs({ projectId, folder, limit })}`,
  );
}

export async function searchWikiDocuments(
  projectId: string,
  query: string,
  pathPrefix?: string,
  size?: number,
): Promise<WikiDocumentSearchResponse> {
  return brainFetch<WikiDocumentSearchResponse>(
    'GET',
    `addon/wiki/documents/search?${qs({ projectId, query, pathPrefix, size })}`,
  );
}
