import { ref, type Ref } from 'vue';
import type {
  DocumentCreateRequest,
  DocumentDto,
  DocumentFoldersResponse,
  DocumentListResponse,
  DocumentSummary,
  DocumentUpdateRequest,
} from '@vance/generated';
import { brainFetch } from '@vance/shared';

export interface UploadOptions {
  file: File;
  /** Defaults to the file's `name` when omitted. */
  path?: string;
  title?: string;
  /** Already trimmed array — `useDocuments.upload` joins them. */
  tags?: string[];
  /** Defaults to the file's own MIME type. */
  mimeType?: string;
}

/**
 * Reactive wrapper around the document REST endpoints. One instance per
 * editor — keeps current page state and exposes `loadPage` / `loadOne` /
 * `update` for the editor to call.
 */
export function useDocuments(pageSize = 20): {
  items: Ref<DocumentSummary[]>;
  page: Ref<number>;
  totalCount: Ref<number>;
  pageSize: Ref<number>;
  selected: Ref<DocumentDto | null>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  folders: Ref<string[]>;
  pathPrefix: Ref<string>;
  loadPage: (projectId: string, page: number, pathPrefix?: string) => Promise<void>;
  loadFolders: (projectId: string) => Promise<void>;
  loadOne: (id: string) => Promise<void>;
  clearSelection: () => void;
  create: (projectId: string, body: DocumentCreateRequest) => Promise<DocumentDto | null>;
  upload: (projectId: string, opts: UploadOptions) => Promise<DocumentDto | null>;
  update: (id: string, body: DocumentUpdateRequest) => Promise<void>;
  remove: (id: string) => Promise<boolean>;
} {
  const items = ref<DocumentSummary[]>([]);
  const page = ref(0);
  const totalCount = ref(0);
  const pageSizeRef = ref(pageSize);
  const selected = ref<DocumentDto | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);
  const folders = ref<string[]>([]);
  // Sticky path-filter — owned by the composable so reloads
  // (e.g. after upload, after page-change) keep the active filter.
  const pathPrefix = ref('');

  async function loadPage(projectId: string, p: number, prefixOverride?: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      // Caller may pass an explicit prefix to override the sticky
      // value (e.g. when the user types into the filter combobox);
      // otherwise reuse what we have.
      if (prefixOverride !== undefined) {
        pathPrefix.value = prefixOverride;
      }
      const params = new URLSearchParams({
        projectId,
        page: String(p),
        size: String(pageSizeRef.value),
      });
      if (pathPrefix.value.trim()) {
        params.set('pathPrefix', pathPrefix.value.trim());
      }
      const data = await brainFetch<DocumentListResponse>('GET', `documents?${params}`);
      items.value = data.items ?? [];
      page.value = data.page;
      pageSizeRef.value = data.pageSize;
      totalCount.value = data.totalCount;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load documents.';
    } finally {
      loading.value = false;
    }
  }

  async function loadFolders(projectId: string): Promise<void> {
    try {
      const params = new URLSearchParams({ projectId });
      const data = await brainFetch<DocumentFoldersResponse>(
        'GET',
        `documents/folders?${params}`,
      );
      folders.value = data.folders ?? [];
    } catch (e) {
      // Folder list is a UX nicety — don't surface an error that
      // would mask the actual document load. Just clear and log.
      folders.value = [];
      console.warn('Failed to load folders', e);
    }
  }

  async function loadOne(id: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      selected.value = await brainFetch<DocumentDto>('GET', `documents/${encodeURIComponent(id)}`);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load document.';
    } finally {
      loading.value = false;
    }
  }

  function clearSelection(): void {
    selected.value = null;
  }

  async function create(projectId: string, body: DocumentCreateRequest): Promise<DocumentDto | null> {
    loading.value = true;
    error.value = null;
    try {
      const params = new URLSearchParams({ projectId });
      const created = await brainFetch<DocumentDto>(
        'POST',
        `documents?${params}`,
        { body },
      );
      // Refresh the current page so the new entry appears (or shifts other
      // items, depending on its sort position).
      await loadPage(projectId, page.value);
      return created;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to create document.';
      return null;
    } finally {
      loading.value = false;
    }
  }

  async function upload(projectId: string, opts: UploadOptions): Promise<DocumentDto | null> {
    loading.value = true;
    error.value = null;
    try {
      const form = new FormData();
      form.append('file', opts.file);
      if (opts.path) form.append('path', opts.path);
      if (opts.title) form.append('title', opts.title);
      if (opts.tags && opts.tags.length > 0) form.append('tags', opts.tags.join(','));
      if (opts.mimeType) form.append('mimeType', opts.mimeType);

      const params = new URLSearchParams({ projectId });
      const created = await brainFetch<DocumentDto>(
        'POST',
        `documents/upload?${params}`,
        { body: form },
      );
      await loadPage(projectId, page.value);
      return created;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to upload document.';
      return null;
    } finally {
      loading.value = false;
    }
  }

  async function update(id: string, body: DocumentUpdateRequest): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const updated = await brainFetch<DocumentDto>(
        'PUT',
        `documents/${encodeURIComponent(id)}`,
        { body },
      );
      selected.value = updated;
      // Reflect summary changes in the visible list without a full reload.
      const idx = items.value.findIndex((d) => d.id === id);
      if (idx >= 0) {
        items.value[idx] = {
          ...items.value[idx],
          title: updated.title,
          tags: updated.tags,
          size: updated.size,
          // Path-change (move/rename) updates the row's path + name —
          // the list shows them prominently, so without this the
          // user would still see the old path until the next page
          // load. Sort order can drift afterwards (the server sorts
          // by path); the user can refresh by changing the page if
          // needed.
          path: updated.path,
          name: updated.name,
        };
      }
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to save document.';
    } finally {
      loading.value = false;
    }
  }

  /**
   * Hard-deletes the document. Drops the row from the visible list
   * on success, clears selection if the deleted row was selected,
   * leaves total-count adjusted in-place (next page-load reconciles).
   *
   * @returns `true` on success, `false` if the server rejected.
   *          Errors land in {@link error} for the UI to surface.
   */
  async function remove(id: string): Promise<boolean> {
    loading.value = true;
    error.value = null;
    try {
      await brainFetch<void>('DELETE', `documents/${encodeURIComponent(id)}`);
      // Drop from the visible list.
      const idx = items.value.findIndex((d) => d.id === id);
      if (idx >= 0) {
        items.value.splice(idx, 1);
        totalCount.value = Math.max(0, totalCount.value - 1);
      }
      if (selected.value?.id === id) {
        selected.value = null;
      }
      return true;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to delete document.';
      return false;
    } finally {
      loading.value = false;
    }
  }

  return {
    items,
    page,
    totalCount,
    pageSize: pageSizeRef,
    selected,
    loading,
    error,
    folders,
    pathPrefix,
    loadPage,
    loadFolders,
    loadOne,
    clearSelection,
    create,
    upload,
    update,
    remove,
  };
}
