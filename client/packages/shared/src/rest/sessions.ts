import type {
  ChatMessageDto,
  SessionDuplicateResponse,
  SessionMetadataDto,
  SessionMetadataPatchRequest,
  SessionSearchHitDto,
  SessionSummaryRichDto,
} from '@vance/generated';
import { SessionSearchScope, SessionStatus } from '@vance/generated';
import { brainFetch } from './restClient';

export interface ListSessionsOptions {
  projectId?: string;
  status?: SessionStatus[];
  includeArchived?: boolean;
  tag?: string;
}

/**
 * GET /brain/{tenant}/sessions — owner-scoped list of the current user's
 * sessions. By default excludes ARCHIVED and CLOSED; flip {@code includeArchived}
 * or pass an explicit {@code status} set to override.
 */
export async function listSessions(
  options: ListSessionsOptions = {},
): Promise<SessionSummaryRichDto[]> {
  const params = new URLSearchParams();
  if (options.projectId) params.set('projectId', options.projectId);
  if (options.status && options.status.length > 0) {
    // Generated TS enums are numeric (Java enum order). Brain expects
    // the enum name on the wire, same as Jackson.
    params.set('status', options.status.map((s) => SessionStatus[s]).join(','));
  }
  if (options.includeArchived) params.set('includeArchived', 'true');
  if (options.tag) params.set('tag', options.tag);
  const qs = params.toString();
  return brainFetch<SessionSummaryRichDto[]>('GET', `sessions${qs ? `?${qs}` : ''}`);
}

export interface SearchSessionsOptions {
  q: string;
  scope?: SessionSearchScope;
  includeArchived?: boolean;
  limit?: number;
}

/**
 * GET /brain/{tenant}/sessions/search — owner-scoped search. Default
 * scope BOTH (metadata + chat content); default includeArchived=true so
 * the dialog can find archived sessions.
 */
export async function searchSessions(
  options: SearchSessionsOptions,
): Promise<SessionSearchHitDto[]> {
  const params = new URLSearchParams();
  params.set('q', options.q);
  if (options.scope !== undefined) {
    params.set('scope', SessionSearchScope[options.scope]);
  }
  if (options.includeArchived !== undefined) {
    params.set('includeArchived', String(options.includeArchived));
  }
  if (options.limit) params.set('limit', String(options.limit));
  return brainFetch<SessionSearchHitDto[]>(
    'GET',
    `sessions/search?${params.toString()}`,
  );
}

/**
 * PATCH /brain/{tenant}/sessions/{id}/metadata — partial update of
 * title/icon/color/tags/pinned. Returns the post-patch metadata.
 */
export async function patchSessionMetadata(
  sessionId: string,
  patch: SessionMetadataPatchRequest,
): Promise<SessionMetadataDto> {
  return brainFetch<SessionMetadataDto>(
    'PATCH',
    `sessions/${encodeURIComponent(sessionId)}/metadata`,
    { body: patch },
  );
}

/** POST /brain/{tenant}/sessions/{id}/archive — idempotent. */
export async function archiveSession(sessionId: string): Promise<void> {
  await brainFetch<void>(
    'POST',
    `sessions/${encodeURIComponent(sessionId)}/archive`,
  );
}

/** POST /brain/{tenant}/sessions/{id}/reactivate — only valid on ARCHIVED. */
export async function reactivateSession(sessionId: string): Promise<void> {
  await brainFetch<void>(
    'POST',
    `sessions/${encodeURIComponent(sessionId)}/reactivate`,
  );
}

/** DELETE /brain/{tenant}/sessions/{id} — hard delete, no undo. */
export async function deleteSession(sessionId: string): Promise<void> {
  await brainFetch<void>('DELETE', `sessions/${encodeURIComponent(sessionId)}`);
}

/**
 * POST /brain/{tenant}/sessions/{id}/duplicate — duplicates the session
 * together with its chat memory (chat process + history + memories) into
 * a fresh, resumable copy in the same project. Returns the new session's
 * business id + resolved title. Owner-only.
 */
export async function duplicateSession(
  sessionId: string,
  title?: string,
): Promise<SessionDuplicateResponse> {
  return brainFetch<SessionDuplicateResponse>(
    'POST',
    `sessions/${encodeURIComponent(sessionId)}/duplicate`,
    { body: title != null ? { title } : {} },
  );
}

/**
 * GET /brain/{tenant}/sessions/{id}/messages — chat history. Used by the
 * search-result preview and the chat editor; works on ARCHIVED sessions
 * too (their chat history persists across archive).
 */
export async function getSessionMessages(
  sessionId: string,
  limit?: number,
): Promise<ChatMessageDto[]> {
  const qs = limit ? `?limit=${limit}` : '';
  return brainFetch<ChatMessageDto[]>(
    'GET',
    `sessions/${encodeURIComponent(sessionId)}/messages${qs}`,
  );
}
