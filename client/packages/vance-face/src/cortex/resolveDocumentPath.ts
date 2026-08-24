import { brainFetch, RestError } from '@vance/shared';
import type { DocumentDto } from '@vance/generated';

/** The bit of a loaded document row this resolution needs. */
export interface PathIndexedDocument {
  id: string;
  path: string;
}

/**
 * The document id behind a `(project, path)` pair, or `null` when the project
 * has no such document.
 *
 * `known` is a fast path only — Cortex's file list is a single page, and the
 * server clamps its size to 200 rows however many the client asks for, so a
 * miss there says nothing about whether the document exists. The authority
 * is `GET documents/by-path`, and only its 404 means "no such document";
 * everything else (403, 500, offline) is a failure to answer and is rethrown so
 * the caller does not report a missing document that is merely unreachable.
 */
export async function resolveDocumentIdByPath(
  projectId: string,
  path: string,
  known: readonly PathIndexedDocument[] = [],
): Promise<string | null> {
  const hit = known.find((f) => f.path === path);
  if (hit) return hit.id;

  const params = new URLSearchParams({ projectId, path });
  try {
    return (await brainFetch<DocumentDto>('GET', `documents/by-path?${params}`)).id;
  } catch (e) {
    if (e instanceof RestError && e.status === 404) return null;
    throw e;
  }
}
