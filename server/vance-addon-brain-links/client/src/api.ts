import { brainFetch } from '@vance/shared';
import type { LinksView } from './generated/links/LinksView';
import type { LinksRebuildResponse } from './generated/links/LinksRebuildResponse';

/**
 * The links REST surface.
 *
 * Every mutation answers with the whole {@link LinksView}. That is not
 * chattiness: a group change re-anchors the entry at the end of its new
 * group, so the order after an edit is the server's answer, not something
 * the client may assume it already knows.
 */

function qs(params: Record<string, string>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) u.set(k, v);
  return u.toString();
}

/** Fields of an add or an edit. Omitted = unchanged; '' = cleared. */
export interface LinkFields {
  title?: string | null;
  teaser?: string | null;
  image?: string | null;
  group?: string | null;
  tags?: string[] | null;
  note?: string | null;
}

export async function scanLinks(projectId: string, folder: string): Promise<LinksView> {
  return brainFetch<LinksView>('GET', `addon/links/scan?${qs({ projectId, folder })}`);
}

export async function addLink(
  projectId: string,
  folder: string,
  url: string,
  fields: LinkFields = {},
): Promise<LinksView> {
  return brainFetch<LinksView>('POST', `addon/links/entry?${qs({ projectId, folder })}`, {
    body: { url, ...fields },
  });
}

export async function updateLink(
  projectId: string,
  folder: string,
  url: string,
  fields: LinkFields,
): Promise<LinksView> {
  return brainFetch<LinksView>('PATCH', `addon/links/entry?${qs({ projectId, folder })}`, {
    body: { url, ...fields },
  });
}

export async function removeLink(
  projectId: string,
  folder: string,
  url: string,
): Promise<LinksView> {
  return brainFetch<LinksView>('DELETE', `addon/links/entry?${qs({ projectId, folder, url })}`);
}

export async function reorderLinks(
  projectId: string,
  folder: string,
  orderedUrls: string[],
): Promise<LinksView> {
  return brainFetch<LinksView>('POST', `addon/links/reorder?${qs({ projectId, folder })}`, {
    body: { orderedUrls },
  });
}

export async function setGroups(
  projectId: string,
  folder: string,
  groups: string[],
): Promise<LinksView> {
  return brainFetch<LinksView>('POST', `addon/links/groups?${qs({ projectId, folder })}`, {
    body: { groups },
  });
}

/** A blank `to` dissolves the group: its links move out, the heading goes. */
export async function renameGroup(
  projectId: string,
  folder: string,
  from: string,
  to: string | null,
): Promise<LinksView> {
  return brainFetch<LinksView>('POST', `addon/links/group/rename?${qs({ projectId, folder })}`, {
    body: { from, to },
  });
}

export async function rebuildLinks(
  projectId: string,
  folder: string,
): Promise<LinksRebuildResponse> {
  return brainFetch<LinksRebuildResponse>(
    'POST',
    `addon/links/rebuild?${qs({ projectId, folder })}`,
  );
}
