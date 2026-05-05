import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  InboxItemStatus,
  type InboxAnswerRequest,
  type InboxDelegateRequest,
  type InboxItemDto,
  type InboxListResponse,
} from '@vance/generated';
import {
  answerInboxItem,
  archiveInboxItem,
  delegateInboxItem,
  dismissInboxItem,
  getInboxItem,
  listInbox,
  unarchiveInboxItem,
} from '@/api/inboxApi';

/**
 * React Query hooks for the inbox. Two query keys:
 *
 * - {@code ['inbox', 'list', status, tag]} — the filtered list view
 * - {@code ['inbox', 'item', id]} — single item, used by the detail
 *
 * Mutations invalidate both, so a successful answer/archive flushes
 * the list cache and the detail cache in one go.
 */

interface ListFilter {
  status?: InboxItemStatus;
  tag?: string;
}

const inboxKeys = {
  all: ['inbox'] as const,
  list: (filter: ListFilter) => ['inbox', 'list', filter.status, filter.tag] as const,
  item: (id: string) => ['inbox', 'item', id] as const,
};

export function useInboxList(filter: ListFilter = { status: InboxItemStatus.PENDING }) {
  return useQuery<InboxListResponse>({
    queryKey: inboxKeys.list(filter),
    queryFn: () => listInbox(filter),
  });
}

export function useInboxItem(id: string | undefined) {
  return useQuery<InboxItemDto>({
    queryKey: id ? inboxKeys.item(id) : ['inbox', 'item', '__none__'],
    queryFn: () => getInboxItem(id as string),
    enabled: id !== undefined,
  });
}

interface AnswerVars {
  id: string;
  body: InboxAnswerRequest;
}

export function useAnswerInboxItem() {
  const qc = useQueryClient();
  return useMutation<InboxItemDto, Error, AnswerVars>({
    mutationFn: ({ id, body }) => answerInboxItem(id, body),
    onSuccess: (item) => {
      qc.invalidateQueries({ queryKey: inboxKeys.all });
      qc.setQueryData(inboxKeys.item(item.id), item);
    },
  });
}

export function useArchiveInboxItem() {
  const qc = useQueryClient();
  return useMutation<InboxItemDto, Error, string>({
    mutationFn: (id) => archiveInboxItem(id),
    onSuccess: (item) => {
      qc.invalidateQueries({ queryKey: inboxKeys.all });
      qc.setQueryData(inboxKeys.item(item.id), item);
    },
  });
}

export function useUnarchiveInboxItem() {
  const qc = useQueryClient();
  return useMutation<InboxItemDto, Error, string>({
    mutationFn: (id) => unarchiveInboxItem(id),
    onSuccess: (item) => {
      qc.invalidateQueries({ queryKey: inboxKeys.all });
      qc.setQueryData(inboxKeys.item(item.id), item);
    },
  });
}

export function useDismissInboxItem() {
  const qc = useQueryClient();
  return useMutation<InboxItemDto, Error, string>({
    mutationFn: (id) => dismissInboxItem(id),
    onSuccess: (item) => {
      qc.invalidateQueries({ queryKey: inboxKeys.all });
      qc.setQueryData(inboxKeys.item(item.id), item);
    },
  });
}

interface DelegateVars {
  id: string;
  body: InboxDelegateRequest;
}

export function useDelegateInboxItem() {
  const qc = useQueryClient();
  return useMutation<InboxItemDto, Error, DelegateVars>({
    mutationFn: ({ id, body }) => delegateInboxItem(id, body),
    onSuccess: (item) => {
      qc.invalidateQueries({ queryKey: inboxKeys.all });
      qc.setQueryData(inboxKeys.item(item.id), item);
    },
  });
}
