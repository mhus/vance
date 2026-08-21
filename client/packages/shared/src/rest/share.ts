import type {
  ShareFormDto,
  ShareHandlerDto,
  ShareResultDto,
  ShareSubjectDto,
} from '@vance/generated';
import { brainFetch } from './restClient';

/**
 * Milliways — showing something to a human. See
 * specification/public/milliways-system.md.
 *
 * All three calls are `POST`, including the two reads: the subject carries a
 * link and a snippet, which do not fit in a query string.
 *
 * Handler list and handler form stay two calls by design — the list is cheap,
 * a form costs a user list or a pack list, and merely opening the share menu
 * should not hand out a user directory.
 */

/**
 * POST /brain/{tenant}/share/handlers — every way of sharing this subject,
 * including the ones that are not available here. Those carry
 * `available: false` plus a `statusText` saying what is missing; render them
 * greyed out rather than hiding them.
 */
export async function listShareHandlers(
  projectId: string,
  subject: ShareSubjectDto,
): Promise<ShareHandlerDto[]> {
  return brainFetch<ShareHandlerDto[]>('POST', 'share/handlers', {
    body: { projectId, subject },
  });
}

/**
 * POST /brain/{tenant}/share/handlers/{id}/form — the fields this handler
 * needs, with any option list already filled in. Rejected with 409 when the
 * handler is not available in this scope.
 */
export async function fetchShareForm(
  handlerId: string,
  projectId: string,
  subject: ShareSubjectDto,
): Promise<ShareFormDto> {
  return brainFetch<ShareFormDto>(
    'POST',
    `share/handlers/${encodeURIComponent(handlerId)}/form`,
    { body: { projectId, subject } },
  );
}

/** POST /brain/{tenant}/share/handlers/{id} — perform the share. */
export async function submitShare(
  handlerId: string,
  projectId: string,
  subject: ShareSubjectDto,
  values: Record<string, unknown>,
): Promise<ShareResultDto> {
  return brainFetch<ShareResultDto>(
    'POST',
    `share/handlers/${encodeURIComponent(handlerId)}`,
    { body: { projectId, subject, values } },
  );
}
