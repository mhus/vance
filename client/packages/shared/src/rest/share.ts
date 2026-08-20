import type {
  ShareFormDto,
  ShareHandlerDto,
  ShareResultDto,
  ShareSubmitRequest,
} from '@vance/generated';
import { brainFetch } from './restClient';

/**
 * Milliways — showing a document to a human. See
 * planning/milliways-sharing.md.
 *
 * Handler list and handler form are two calls by design: the list is
 * cheap, a form costs a user list or a pack list, and merely opening the
 * share menu should not hand out a user directory.
 */

/**
 * GET /brain/{tenant}/share/handlers — every way of sharing this
 * document, including the ones that are not available here. Those carry
 * {@code available: false} plus a {@code statusText} saying what is
 * missing; render them greyed out rather than hiding them.
 */
export async function listShareHandlers(
  projectId: string,
  path: string,
): Promise<ShareHandlerDto[]> {
  return brainFetch<ShareHandlerDto[]>(
    'GET',
    `share/handlers?projectId=${encodeURIComponent(projectId)}`
      + `&path=${encodeURIComponent(path)}`,
  );
}

/**
 * GET /brain/{tenant}/share/handlers/{id}/form — the fields this handler
 * needs, with any option list already filled in. Rejected with 409 when
 * the handler is not available in this scope.
 */
export async function fetchShareForm(
  handlerId: string,
  projectId: string,
  path: string,
): Promise<ShareFormDto> {
  return brainFetch<ShareFormDto>(
    'GET',
    `share/handlers/${encodeURIComponent(handlerId)}/form`
      + `?projectId=${encodeURIComponent(projectId)}`
      + `&path=${encodeURIComponent(path)}`,
  );
}

/** POST /brain/{tenant}/share/handlers/{id} — perform the share. */
export async function submitShare(
  handlerId: string,
  req: ShareSubmitRequest,
): Promise<ShareResultDto> {
  return brainFetch<ShareResultDto>(
    'POST',
    `share/handlers/${encodeURIComponent(handlerId)}`,
    { body: req },
  );
}
