import { ref, type Ref } from 'vue';
import type {
  DocumentCopyChunkResponse,
  DocumentCreateRequest,
  DocumentDto,
  DocumentFolderListResponse,
  DocumentFoldersResponse,
  DocumentKindsResponse,
  DocumentMoveChunkResponse,
  DocumentRenameChunkResponse,
  DocumentSummary,
  DocumentTrashChunkResponse,
  DocumentUnpackResponse,
  DocumentUpdateRequest,
  MountDto,
  MountListResponse,
  MountSearchOutcome,
} from '@vance/generated';

export interface MoveChunkArgs {
  ids?: string[];
  folders?: string[];
  targetFolder: string;
  limit?: number;
  cursor?: string;
}

export interface CopyChunkArgs {
  ids?: string[];
  folders?: string[];
  targetProjectId?: string;
  targetFolder: string;
  /** Replace documents that already exist at the destination (default: skip). */
  overwrite?: boolean;
  limit?: number;
  cursor?: string;
}

export interface TrashChunkArgs {
  ids?: string[];
  folders?: string[];
  limit?: number;
  cursor?: string;
}

export interface RenameChunkArgs {
  /** Document path, or folder prefix (trailing '/') to rename. */
  path: string;
  /** New last segment (no '/'). */
  newName: string;
  limit?: number;
  cursor?: string;
}
import { brainFetch, brainFetchBlob, brainFetchText, brainSendRaw } from '@vance/shared';

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
  subFolders: Ref<string[]>;
  mountSearch: Ref<MountSearchOutcome | null>;
  /**
   * Why this mounted folder's contents are not what the source holds — a
   * failed refresh, or a folder too large to materialise. Folder-scoped, so it
   * outranks the mount-wide status text.
   */
  mountFailure: Ref<string | null>;
  /**
   * The destination list was cut short (server-side cap). Has to be shown: a
   * suggestion list that just ends reads as "there is nowhere else", and the
   * folder the user wants may be the one that got cut.
   */
  foldersTruncated: Ref<boolean>;
  mounts: Ref<MountDto[]>;
  pathPrefix: Ref<string>;
  kinds: Ref<string[]>;
  kindFilter: Ref<string>;
  search: Ref<string>;
  loadPage: (
    projectId: string,
    page: number,
    pathPrefix?: string,
    kind?: string,
    search?: string,
  ) => Promise<void>;
  loadFolders: (projectId: string) => Promise<void>;
  loadMounts: (projectId: string, refresh?: boolean) => Promise<void>;
  loadKinds: (projectId: string) => Promise<void>;
  loadOne: (id: string) => Promise<void>;
  clearSelection: () => void;
  create: (projectId: string, body: DocumentCreateRequest) => Promise<DocumentDto | null>;
  upload: (projectId: string, opts: UploadOptions) => Promise<DocumentDto | null>;
  update: (id: string, body: DocumentUpdateRequest) => Promise<void>;
  loadContent: (id: string) => Promise<string | null>;
  replaceContent: (id: string, content: string, mimeType: string) => Promise<DocumentDto | null>;
  setSummary: (id: string, summary: string) => Promise<DocumentDto | null>;
  remove: (id: string) => Promise<boolean>;
  exportZip: (
    projectId: string,
    ids: string[],
    folders?: string[],
  ) => Promise<{ blob: Blob; filename: string | null } | null>;
  unpack: (projectId: string, id: string) => Promise<DocumentUnpackResponse | null>;
  duplicate: (projectId: string, id: string) => Promise<DocumentDto | null>;
  moveChunk: (projectId: string, args: MoveChunkArgs) => Promise<DocumentMoveChunkResponse | null>;
  copyChunk: (projectId: string, args: CopyChunkArgs) => Promise<DocumentCopyChunkResponse | null>;
  trashChunk: (projectId: string, args: TrashChunkArgs) => Promise<DocumentTrashChunkResponse | null>;
  renameChunk: (projectId: string, args: RenameChunkArgs) => Promise<DocumentRenameChunkResponse | null>;
} {
  const items = ref<DocumentSummary[]>([]);
  const page = ref(0);
  const totalCount = ref(0);
  const pageSizeRef = ref(pageSize);
  const selected = ref<DocumentDto | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);
  const folders = ref<string[]>([]);
  /** Direct subfolders of the current {@link pathPrefix} — populated
   *  by every {@link loadPage} call so the UI can render the folder
   *  tree alongside the file list. Alphabetically sorted server-side. */
  const subFolders = ref<string[]>([]);
  /** Outcome of a search inside a mounted folder — see {@link loadPage}.
   *  `null` on every ordinary listing. */
  const mountSearch = ref<MountSearchOutcome | null>(null);
  const mountFailure = ref<string | null>(null);
  const foldersTruncated = ref(false);
  /** The project's mounted external sources, so an empty mounted folder can
   *  be explained rather than just looking empty. */
  const mounts = ref<MountDto[]>([]);
  const kinds = ref<string[]>([]);
  // Sticky path-filter — owned by the composable so reloads
  // (e.g. after upload, after page-change) keep the active filter.
  const pathPrefix = ref('');
  const kindFilter = ref('');
  /** Free-text search needle — server-side filtered against file
   *  path/title and folder names. Sticky like {@link pathPrefix}. */
  const search = ref('');

  async function loadPage(
    projectId: string,
    p: number,
    prefixOverride?: string,
    kindOverride?: string,
    searchOverride?: string,
  ): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      // Caller may pass an explicit prefix to override the sticky
      // value (e.g. when the user clicks a subfolder, or hits the
      // path-back button); otherwise reuse what we have.
      if (prefixOverride !== undefined) {
        pathPrefix.value = prefixOverride;
      }
      // `kindOverride` is retained on the signature for back-compat —
      // the new folder-view endpoint doesn't take a kind filter, so
      // we just remember the value for whoever still reads it. UI
      // surface for kinds has been removed in the picker-style layout.
      if (kindOverride !== undefined) {
        kindFilter.value = kindOverride;
      }
      if (searchOverride !== undefined) {
        search.value = searchOverride;
      }
      const params = new URLSearchParams({
        projectId,
        page: String(p),
        size: String(pageSizeRef.value),
      });
      if (pathPrefix.value.trim()) {
        params.set('path', pathPrefix.value.trim());
      }
      if (search.value.trim()) {
        params.set('search', search.value.trim());
      }
      const data = await brainFetch<DocumentFolderListResponse>(
        'GET',
        `documents/folder?${params}`,
      );
      items.value = data.files ?? [];
      subFolders.value = data.folders ?? [];
      page.value = data.page;
      pageSizeRef.value = data.pageSize;
      totalCount.value = data.totalCount;
      // Only set inside a mounted folder with a search term. It says which
      // question the backend actually answered — DELEGATED means the external
      // source searched itself and the hits span the whole mount, the other
      // two mean nobody looked. Without it an empty result reads as "not
      // there", which for a mount is usually wrong.
      mountSearch.value = data.mountSearch ?? null;
      mountFailure.value = data.mountFailure ?? null;
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
      foldersTruncated.value = data.truncated === true;
    } catch (e) {
      // Folder list is a UX nicety — don't surface an error that
      // would mask the actual document load. Just clear and log.
      folders.value = [];
      console.warn('Failed to load folders', e);
    }
  }

  /**
   * The project's mounted external sources.
   *
   * Loaded so an empty mounted folder can say *why*: an unreachable source and
   * an actually empty directory are the same zero files in the listing, and a
   * reader who cannot tell them apart concludes the documents are gone.
   *
   * Failure is silent on purpose — this only ever adds an explanation, and an
   * error here would mask the document load it accompanies.
   */
  async function loadMounts(projectId: string, refresh = false): Promise<void> {
    try {
      const params = new URLSearchParams({ projectId });
      // Drops the five-minute instance cache server-side. Passed on the
      // explicit reload, never on the automatic load — otherwise every folder
      // walk would re-read the settings and re-ask every source.
      if (refresh) params.set('refresh', 'true');
      const data = await brainFetch<MountListResponse>('GET', `mounts?${params}`);
      mounts.value = data.mounts ?? [];
    } catch (e) {
      mounts.value = [];
      console.warn('Failed to load mounts', e);
    }
  }

  async function loadKinds(projectId: string): Promise<void> {
    try {
      const params = new URLSearchParams({ projectId });
      const data = await brainFetch<DocumentKindsResponse>(
        'GET',
        `documents/kinds?${params}`,
      );
      kinds.value = data.kinds ?? [];
    } catch (e) {
      // Same posture as folder loading — surfaced errors would mask
      // the document list. Drop quietly.
      kinds.value = [];
      console.warn('Failed to load kinds', e);
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
      // The metadata PUT doesn't carry the body — server DTOs come back with
      // inline=false / inlineText=null since the full-storage migration.
      // Preserve the editor's content cache from the previous selection so
      // the inline editor branch stays mounted after a title/tags/path save.
      const prev = selected.value;
      if (prev?.id === id && prev.inline) {
        updated.inline = true;
        updated.inlineText = prev.inlineText;
      }
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
  /**
   * Set / clear the document's `summary` field. Hits the dedicated
   * single-field endpoint so the payload stays minimal (no risk of
   * accidentally touching tags/title/inlineText). Returns the
   * refreshed DTO so the caller can swap it into local state.
   */
  async function setSummary(id: string, summary: string): Promise<DocumentDto | null> {
    error.value = null;
    try {
      const updated = await brainFetch<DocumentDto>(
        'PUT',
        `documents/${encodeURIComponent(id)}/summary`,
        { body: { summary } },
      );
      // `items` is the lightweight DocumentSummary list and doesn't
      // carry summary — only the selected detail does. Patch
      // `selected` so the editor's bound `editSummary` stays in
      // sync with what the server just persisted.
      if (selected.value?.id === id) {
        selected.value = updated;
      }
      return updated;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to set summary.';
      return null;
    }
  }

  /**
   * Streams the document's content via the brain's
   * {@code GET /documents/{id}/content} endpoint and returns it as text.
   * Used by the editor when it selects a doc — the body lives in storage
   * since the inline→storage migration, so the list/detail DTO no longer
   * carries it. Returns `null` on 404 (deleted in the meantime).
   */
  async function loadContent(id: string): Promise<string | null> {
    error.value = null;
    try {
      return await brainFetchText(`documents/${encodeURIComponent(id)}/content`);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load document content.';
      return null;
    }
  }

  /**
   * Saves edited content back via {@code PUT /documents/{id}/content} as a
   * raw body. {@code mimeType} goes on the request as {@code Content-Type}
   * so the server can update the mime when the user corrected it. Returns
   * the refreshed DTO so callers can swap it into local state.
   */
  async function replaceContent(
    id: string,
    content: string,
    mimeType: string,
  ): Promise<DocumentDto | null> {
    loading.value = true;
    error.value = null;
    try {
      const mime = mimeType.trim() || 'text/plain';
      const updated = await brainSendRaw<DocumentDto>(
        'PUT',
        `documents/${encodeURIComponent(id)}/content`,
        content,
        `${mime}; charset=utf-8`,
      );
      // The server DTO carries inline=false / inlineText=null since the
      // full-storage migration; patch the client-side cache fields BEFORE
      // assigning so Vue's reactivity sees a consistent state on the very
      // first render after the save (otherwise the editor briefly flips to
      // the binary-preview branch).
      updated.inlineText = content;
      updated.inline = true;
      if (selected.value?.id === id) {
        selected.value = updated;
      }
      const idx = items.value.findIndex((d) => d.id === id);
      if (idx >= 0) {
        items.value[idx] = {
          ...items.value[idx],
          size: updated.size,
          mimeType: updated.mimeType,
        };
      }
      return updated;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to save document content.';
      return null;
    } finally {
      loading.value = false;
    }
  }

  /**
   * Streams a ZIP of the given documents from the brain (built on the fly,
   * never buffered whole server-side) and returns it as a Blob plus the
   * server-suggested filename. The caller triggers the browser download.
   */
  async function exportZip(
    projectId: string,
    ids: string[],
    folders: string[] = [],
  ): Promise<{ blob: Blob; filename: string | null } | null> {
    error.value = null;
    try {
      const params = new URLSearchParams({ projectId });
      return await brainFetchBlob(`documents/export?${params}`, { body: { ids, folders } }, 'POST');
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to export documents.';
      return null;
    }
  }

  /**
   * Server-side ZIP extraction: unpacks the given document (a ZIP) into
   * individual documents under a sibling folder. All work happens on the
   * brain (streamed entry by entry); we just get a summary back.
   */
  async function unpack(projectId: string, id: string): Promise<DocumentUnpackResponse | null> {
    loading.value = true;
    error.value = null;
    try {
      const params = new URLSearchParams({ projectId });
      return await brainFetch<DocumentUnpackResponse>(
        'POST',
        `documents/${encodeURIComponent(id)}/unpack?${params}`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to unpack archive.';
      return null;
    } finally {
      loading.value = false;
    }
  }

  /**
   * Copy a document next to itself — same folder, name suffixed with the
   * first free {@code copy <n>}. The free number is picked server-side: this
   * page holds one folder page at a time and would collide with siblings it
   * never loaded. Returns the new document.
   */
  async function duplicate(projectId: string, id: string): Promise<DocumentDto | null> {
    loading.value = true;
    error.value = null;
    try {
      const params = new URLSearchParams({ projectId });
      return await brainFetch<DocumentDto>(
        'POST',
        `documents/${encodeURIComponent(id)}/duplicate?${params}`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to duplicate document.';
      return null;
    } finally {
      loading.value = false;
    }
  }

  /**
   * Move one bounded chunk of the selection to a target folder. The caller
   * loops, passing {@code args.cursor} back from each response, until the
   * response reports {@code done}. The server skips anything it cannot move.
   */
  async function moveChunk(
    projectId: string,
    args: MoveChunkArgs,
  ): Promise<DocumentMoveChunkResponse | null> {
    error.value = null;
    try {
      const params = new URLSearchParams({ projectId });
      return await brainFetch<DocumentMoveChunkResponse>(
        'POST',
        `documents/move-chunk?${params}`,
        { body: args },
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to move documents.';
      return null;
    }
  }

  /**
   * Copy one bounded chunk of the selection to a target folder (optionally
   * in a different project). The caller loops, passing {@code args.cursor}
   * back from each response, until the response reports {@code done}. The
   * server skips anything it cannot copy (no READ on source, no CREATE on
   * destination, or a name collision). With {@code args.overwrite} a
   * collision rewrites the existing target instead — reported separately as
   * {@code overwritten}, and still skipped when the target is locked or the
   * caller has no WRITE there.
   */
  async function copyChunk(
    projectId: string,
    args: CopyChunkArgs,
  ): Promise<DocumentCopyChunkResponse | null> {
    error.value = null;
    try {
      const params = new URLSearchParams({ projectId });
      return await brainFetch<DocumentCopyChunkResponse>(
        'POST',
        `documents/copy-chunk?${params}`,
        { body: args },
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to copy documents.';
      return null;
    }
  }

  /**
   * Move one bounded chunk of the selection to the trash. Mirrors
   * {@link moveChunk}: loop passing {@code args.cursor} back until {@code done};
   * the server skips anything it cannot delete.
   */
  async function trashChunk(
    projectId: string,
    args: TrashChunkArgs,
  ): Promise<DocumentTrashChunkResponse | null> {
    error.value = null;
    try {
      const params = new URLSearchParams({ projectId });
      return await brainFetch<DocumentTrashChunkResponse>(
        'POST',
        `documents/trash-chunk?${params}`,
        { body: args },
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to trash documents.';
      return null;
    }
  }

  /**
   * Rename a document (one call) or a folder (chunked, cursor loop). Mirrors
   * {@link moveChunk}: the server skips anything it cannot write.
   */
  async function renameChunk(
    projectId: string,
    args: RenameChunkArgs,
  ): Promise<DocumentRenameChunkResponse | null> {
    error.value = null;
    try {
      const params = new URLSearchParams({ projectId });
      return await brainFetch<DocumentRenameChunkResponse>(
        'POST',
        `documents/rename-chunk?${params}`,
        { body: args },
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to rename.';
      return null;
    }
  }

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
    subFolders,
    mountSearch,
    mountFailure,
    foldersTruncated,
    mounts,
    pathPrefix,
    kinds,
    kindFilter,
    search,
    loadPage,
    loadFolders,
    loadMounts,
    loadKinds,
    loadOne,
    clearSelection,
    create,
    upload,
    update,
    loadContent,
    replaceContent,
    setSummary,
    remove,
    exportZip,
    unpack,
    duplicate,
    moveChunk,
    copyChunk,
    trashChunk,
    renameChunk,
  };
}
