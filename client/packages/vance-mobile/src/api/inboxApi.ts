import { brainFetch } from '@vance/shared';
import {
  AnswerOutcome,
  Criticality,
  InboxItemStatus,
  InboxItemType,
  type InboxAnswerRequest,
  type InboxDelegateRequest,
  type InboxItemDto,
  type InboxListResponse,
  type InboxTagsResponse,
} from '@vance/generated';
import { enumName, normalizeEnum } from '@/util/enum';

/**
 * Thin REST wrappers around the Brain's inbox endpoints. Auth +
 * 401 refresh come from `@vance/shared/brainFetch`; this module
 * only adapts URL paths to typed inputs / outputs and bridges the
 * enum-name-vs-numeric-ordinal wire format (see `util/enum.ts`).
 *
 * `assignedTo` defaults to the calling user (`currentUser` resolved
 * server-side); pass an explicit user id for a team member's inbox.
 */

interface ListParams {
  status?: InboxItemStatus;
  tag?: string;
  assignedTo?: string;
}

export async function listInbox(params: ListParams = {}): Promise<InboxListResponse> {
  const q = new URLSearchParams();
  if (params.status !== undefined) q.set('status', enumName(InboxItemStatus, params.status));
  if (params.tag !== undefined) q.set('tag', params.tag);
  if (params.assignedTo !== undefined) q.set('assignedTo', params.assignedTo);
  const qs = q.toString();
  const raw = await brainFetch<InboxListResponse>('GET', `inbox${qs ? '?' + qs : ''}`);
  return { ...raw, items: raw.items.map(normalizeItem) };
}

export async function getInboxItem(id: string): Promise<InboxItemDto> {
  const raw = await brainFetch<InboxItemDto>('GET', `inbox/${encodeURIComponent(id)}`);
  return normalizeItem(raw);
}

export function listInboxTags(): Promise<InboxTagsResponse> {
  return brainFetch<InboxTagsResponse>('GET', 'inbox/tags');
}

export async function answerInboxItem(
  id: string,
  body: InboxAnswerRequest,
): Promise<InboxItemDto> {
  const wireBody = { ...body, outcome: enumName(AnswerOutcome, body.outcome) };
  const raw = await brainFetch<InboxItemDto>(
    'POST',
    `inbox/${encodeURIComponent(id)}/answer`,
    { body: wireBody },
  );
  return normalizeItem(raw);
}

export async function archiveInboxItem(id: string): Promise<InboxItemDto> {
  const raw = await brainFetch<InboxItemDto>('POST', `inbox/${encodeURIComponent(id)}/archive`);
  return normalizeItem(raw);
}

export async function unarchiveInboxItem(id: string): Promise<InboxItemDto> {
  const raw = await brainFetch<InboxItemDto>(
    'POST',
    `inbox/${encodeURIComponent(id)}/unarchive`,
  );
  return normalizeItem(raw);
}

export async function dismissInboxItem(id: string): Promise<InboxItemDto> {
  const raw = await brainFetch<InboxItemDto>('POST', `inbox/${encodeURIComponent(id)}/dismiss`);
  return normalizeItem(raw);
}

export async function delegateInboxItem(
  id: string,
  body: InboxDelegateRequest,
): Promise<InboxItemDto> {
  const raw = await brainFetch<InboxItemDto>(
    'POST',
    `inbox/${encodeURIComponent(id)}/delegate`,
    { body },
  );
  return normalizeItem(raw);
}

function normalizeItem(raw: InboxItemDto): InboxItemDto {
  return {
    ...raw,
    type: normalizeEnum(InboxItemType, raw.type),
    status: normalizeEnum(InboxItemStatus, raw.status),
    criticality: normalizeEnum(Criticality, raw.criticality),
    answer: raw.answer
      ? { ...raw.answer, outcome: normalizeEnum(AnswerOutcome, raw.answer.outcome) }
      : raw.answer,
  };
}
