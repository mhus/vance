import { brainFetch, brainFetchBlob } from '@vance/shared';
import type { TenantProjectsResponse } from '@vance/generated';
import type { ContactCard } from './generated/contacts/ContactCard';
import type { ContactDocumentView } from './generated/contacts/ContactDocumentView';
import type { ContactImportView } from './generated/contacts/ContactImportView';
import type { ContactListView } from './generated/contacts/ContactListView';
import type { ContactScope } from './generated/contacts/ContactScope';
import type { ContactView } from './generated/contacts/ContactView';
import type { DeleteContactView } from './generated/contacts/DeleteContactView';
import type { GravatarView } from './generated/contacts/GravatarView';
import type { MoveContactView } from './generated/contacts/MoveContactView';

/**
 * The contacts REST surface. Every call carries the project the area works
 * in (optional — without one the user and tenant scopes remain); the server
 * decides per scope who may read and write.
 */

const BASE = 'addon/contacts';

function qs(params: Record<string, string | null | undefined>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== null && v !== undefined && v !== '') u.set(k, v);
  }
  const s = u.toString();
  return s ? `?${s}` : '';
}

export interface ListFilter {
  q?: string;
  scope?: ContactScope | null;
  kind?: string | null;
  category?: string | null;
}

export async function listContacts(projectId: string | null, filter: ListFilter = {}): Promise<ContactListView> {
  return brainFetch<ContactListView>(
    'GET',
    `${BASE}/contacts${qs({
      projectId,
      q: filter.q,
      scope: filter.scope?.toLowerCase(),
      kind: filter.kind,
      category: filter.category,
    })}`,
  );
}

export async function getContact(projectId: string | null, id: string, scope?: ContactScope): Promise<ContactView> {
  return brainFetch<ContactView>('GET', `${BASE}/contact/${encodeURIComponent(id)}${qs({ projectId, scope: scope?.toLowerCase() })}`);
}

export async function createContact(
  projectId: string | null,
  card: ContactCard,
  scope: ContactScope,
): Promise<ContactView> {
  return brainFetch<ContactView>('POST', `${BASE}/contact${qs({ projectId })}`, { body: { card, scope } });
}

export async function saveContact(
  projectId: string | null,
  id: string,
  card: ContactCard,
  scope: ContactScope,
): Promise<ContactView> {
  return brainFetch<ContactView>('PUT', `${BASE}/contact/${encodeURIComponent(id)}${qs({ projectId })}`, {
    body: { card, scope },
  });
}

export async function deleteContact(projectId: string | null, id: string, scope: ContactScope): Promise<DeleteContactView> {
  return brainFetch<DeleteContactView>(
    'DELETE',
    `${BASE}/contact/${encodeURIComponent(id)}${qs({ projectId, scope: scope.toLowerCase() })}`,
  );
}

export async function moveContact(
  projectId: string | null,
  id: string,
  from: ContactScope,
  to: ContactScope,
): Promise<MoveContactView> {
  return brainFetch<MoveContactView>('POST', `${BASE}/contact/${encodeURIComponent(id)}/move${qs({ projectId })}`, {
    body: { from, to },
  });
}

export async function changeMembers(
  projectId: string | null,
  id: string,
  scope: ContactScope,
  add: string[],
  remove: string[],
): Promise<ContactView> {
  return brainFetch<ContactView>('POST', `${BASE}/contact/${encodeURIComponent(id)}/members${qs({ projectId })}`, {
    body: { scope, add, remove },
  });
}

export async function uploadPhoto(projectId: string | null, id: string, scope: ContactScope, file: File): Promise<ContactView> {
  const form = new FormData();
  form.append('file', file);
  return brainFetch<ContactView>(
    'PUT',
    `${BASE}/contact/${encodeURIComponent(id)}/photo${qs({ projectId, scope: scope.toLowerCase() })}`,
    { body: form },
  );
}

export async function removePhoto(projectId: string | null, id: string, scope: ContactScope): Promise<ContactView> {
  return brainFetch<ContactView>(
    'DELETE',
    `${BASE}/contact/${encodeURIComponent(id)}/photo${qs({ projectId, scope: scope.toLowerCase() })}`,
  );
}

export async function gravatar(projectId: string | null, id: string, scope: ContactScope): Promise<GravatarView> {
  return brainFetch<GravatarView>(
    'POST',
    `${BASE}/contact/${encodeURIComponent(id)}/gravatar${qs({ projectId, scope: scope.toLowerCase() })}`,
  );
}

export async function importVcf(projectId: string | null, scope: ContactScope, file: File): Promise<ContactImportView> {
  const form = new FormData();
  form.append('file', file);
  return brainFetch<ContactImportView>('POST', `${BASE}/import${qs({ projectId, scope: scope.toLowerCase() })}`, {
    body: form,
  });
}

/** Download a vCard file of the given ids (or of a whole scope when `ids` is empty). */
export async function exportVcf(
  projectId: string | null,
  ids: string[],
  scope?: ContactScope | null,
): Promise<{ blob: Blob; filename: string | null }> {
  return brainFetchBlob(`${BASE}/export${qs({ projectId, ids: ids.join(','), scope: scope?.toLowerCase() })}`);
}

export async function loadDocument(projectId: string, path: string): Promise<ContactDocumentView> {
  return brainFetch<ContactDocumentView>('GET', `${BASE}/document${qs({ projectId, path })}`);
}

/**
 * Object URLs of contact pictures, keyed by id + version — fetched with the
 * caller's credentials (an `<img src>` cannot carry a bearer token) and kept
 * for the page's lifetime; a new version is a new key.
 */
const photoCache = new Map<string, Promise<string | null>>();

export function photoUrl(
  projectId: string | null,
  id: string,
  scope: ContactScope,
  version: number | null | undefined,
): Promise<string | null> {
  const key = `${projectId ?? ''}|${scope}|${id}|${version ?? 0}`;
  let hit = photoCache.get(key);
  if (!hit) {
    hit = brainFetchBlob(
      `${BASE}/contact/${encodeURIComponent(id)}/photo${qs({ projectId, scope: scope.toLowerCase() })}`,
    )
      .then(({ blob }) => URL.createObjectURL(blob))
      .catch(() => null);
    photoCache.set(key, hit);
  }
  return hit;
}

/** Working projects for the area's project picker — system projects left out. */
export async function loadProjects(): Promise<{ name: string; title: string }[]> {
  const answer = await brainFetch<TenantProjectsResponse>('GET', 'projects');
  return (answer.projects ?? [])
    .filter((p) => !p.name.startsWith('_'))
    .map((p) => ({ name: p.name, title: p.title || p.name }));
}
