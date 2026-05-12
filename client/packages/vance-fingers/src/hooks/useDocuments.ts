import { useQuery } from '@tanstack/react-query';
import type { DocumentDto, DocumentListResponse } from '@vance/generated';
import { getDocument, listDocuments } from '@/api/documentsApi';

interface ListFilter {
  projectId?: string;
  pathPrefix?: string;
  kind?: string;
}

const documentKeys = {
  list: (filter: ListFilter) =>
    ['documents', 'list', filter.projectId, filter.pathPrefix, filter.kind] as const,
  item: (id: string) => ['documents', 'item', id] as const,
};

export function useDocumentList(filter: ListFilter = {}) {
  return useQuery<DocumentListResponse>({
    queryKey: documentKeys.list(filter),
    queryFn: () => listDocuments(filter),
  });
}

export function useDocumentItem(id: string | undefined) {
  return useQuery<DocumentDto>({
    queryKey: id ? documentKeys.item(id) : ['documents', 'item', '__none__'],
    queryFn: () => getDocument(id as string),
    enabled: id !== undefined,
  });
}
