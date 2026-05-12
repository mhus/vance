import { brainFetch, brainBaseUrl, getStorage, getTenantId, StorageKeys } from '@vance/shared';
import type {
  DocumentDto,
  DocumentFoldersResponse,
  DocumentKindsResponse,
  DocumentListResponse,
} from '@vance/generated';

/**
 * REST wrappers for the Brain's documents endpoints. Mobile v1 is
 * read-only — list, detail, and authenticated content fetch.
 * Editing endpoints exist server-side but are intentionally not
 * exposed here (per `mobile-ui.md` §1).
 */

interface ListParams {
  projectId?: string;
  pathPrefix?: string;
  kind?: string;
  page?: number;
  pageSize?: number;
}

export function listDocuments(params: ListParams = {}): Promise<DocumentListResponse> {
  const q = new URLSearchParams();
  if (params.projectId) q.set('projectId', params.projectId);
  if (params.pathPrefix) q.set('pathPrefix', params.pathPrefix);
  if (params.kind) q.set('kind', params.kind);
  if (params.page !== undefined) q.set('page', String(params.page));
  if (params.pageSize !== undefined) q.set('pageSize', String(params.pageSize));
  const qs = q.toString();
  return brainFetch<DocumentListResponse>('GET', `documents${qs ? '?' + qs : ''}`);
}

export function getDocument(id: string): Promise<DocumentDto> {
  return brainFetch<DocumentDto>('GET', `documents/${encodeURIComponent(id)}`);
}

export function listDocumentFolders(): Promise<DocumentFoldersResponse> {
  return brainFetch<DocumentFoldersResponse>('GET', 'documents/folders');
}

export function listDocumentKinds(): Promise<DocumentKindsResponse> {
  return brainFetch<DocumentKindsResponse>('GET', 'documents/kinds');
}

/**
 * Build the absolute URL for a document's binary content. Used as
 * the `uri` in `<Image source={...}>` together with
 * {@link contentHeaders}.
 *
 * The Brain serves the bytes only with a valid bearer token; on
 * Web the cookie rides along automatically, but on Mobile we must
 * inject the `Authorization` header via RN's `source.headers`
 * support.
 */
export function documentContentUrl(documentId: string, download = false): string {
  const tenant = getTenantId();
  if (!tenant) return '';
  const params = new URLSearchParams();
  if (download) params.set('download', '1');
  const query = params.toString();
  return `${brainBaseUrl()}/brain/${encodeURIComponent(tenant)}/documents/${encodeURIComponent(documentId)}/content${query ? '?' + query : ''}`;
}

/**
 * Header bag for `<Image source={{ uri, headers }}>` etc. Reads the
 * current bearer token from the secure store; returns an empty
 * record if no token is present so the URI still loads (the server
 * will respond 401 and the REST 401 path takes over).
 */
export function contentHeaders(): Record<string, string> {
  const token = getStorage().secureStore.get(StorageKeys.authAccessToken);
  if (!token) return {};
  return { Authorization: `Bearer ${token}` };
}
