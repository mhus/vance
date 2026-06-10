import { ref } from 'vue';
import { brainFetch, brainFetchText, brainSendRaw } from '@vance/shared';
/**
 * Reactive wrapper around the document REST endpoints. One instance per
 * editor — keeps current page state and exposes `loadPage` / `loadOne` /
 * `update` for the editor to call.
 */
export function useDocuments(pageSize = 20) {
    const items = ref([]);
    const page = ref(0);
    const totalCount = ref(0);
    const pageSizeRef = ref(pageSize);
    const selected = ref(null);
    const loading = ref(false);
    const error = ref(null);
    const folders = ref([]);
    /** Direct subfolders of the current {@link pathPrefix} — populated
     *  by every {@link loadPage} call so the UI can render the folder
     *  tree alongside the file list. Alphabetically sorted server-side. */
    const subFolders = ref([]);
    const kinds = ref([]);
    // Sticky path-filter — owned by the composable so reloads
    // (e.g. after upload, after page-change) keep the active filter.
    const pathPrefix = ref('');
    const kindFilter = ref('');
    /** Free-text search needle — server-side filtered against file
     *  path/title and folder names. Sticky like {@link pathPrefix}. */
    const search = ref('');
    async function loadPage(projectId, p, prefixOverride, kindOverride, searchOverride) {
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
            const data = await brainFetch('GET', `documents/folder?${params}`);
            items.value = data.files ?? [];
            subFolders.value = data.folders ?? [];
            page.value = data.page;
            pageSizeRef.value = data.pageSize;
            totalCount.value = data.totalCount;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load documents.';
        }
        finally {
            loading.value = false;
        }
    }
    async function loadFolders(projectId) {
        try {
            const params = new URLSearchParams({ projectId });
            const data = await brainFetch('GET', `documents/folders?${params}`);
            folders.value = data.folders ?? [];
        }
        catch (e) {
            // Folder list is a UX nicety — don't surface an error that
            // would mask the actual document load. Just clear and log.
            folders.value = [];
            console.warn('Failed to load folders', e);
        }
    }
    async function loadKinds(projectId) {
        try {
            const params = new URLSearchParams({ projectId });
            const data = await brainFetch('GET', `documents/kinds?${params}`);
            kinds.value = data.kinds ?? [];
        }
        catch (e) {
            // Same posture as folder loading — surfaced errors would mask
            // the document list. Drop quietly.
            kinds.value = [];
            console.warn('Failed to load kinds', e);
        }
    }
    async function loadOne(id) {
        loading.value = true;
        error.value = null;
        try {
            selected.value = await brainFetch('GET', `documents/${encodeURIComponent(id)}`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load document.';
        }
        finally {
            loading.value = false;
        }
    }
    function clearSelection() {
        selected.value = null;
    }
    async function create(projectId, body) {
        loading.value = true;
        error.value = null;
        try {
            const params = new URLSearchParams({ projectId });
            const created = await brainFetch('POST', `documents?${params}`, { body });
            // Refresh the current page so the new entry appears (or shifts other
            // items, depending on its sort position).
            await loadPage(projectId, page.value);
            return created;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to create document.';
            return null;
        }
        finally {
            loading.value = false;
        }
    }
    async function upload(projectId, opts) {
        loading.value = true;
        error.value = null;
        try {
            const form = new FormData();
            form.append('file', opts.file);
            if (opts.path)
                form.append('path', opts.path);
            if (opts.title)
                form.append('title', opts.title);
            if (opts.tags && opts.tags.length > 0)
                form.append('tags', opts.tags.join(','));
            if (opts.mimeType)
                form.append('mimeType', opts.mimeType);
            const params = new URLSearchParams({ projectId });
            const created = await brainFetch('POST', `documents/upload?${params}`, { body: form });
            await loadPage(projectId, page.value);
            return created;
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to upload document.';
            return null;
        }
        finally {
            loading.value = false;
        }
    }
    async function update(id, body) {
        loading.value = true;
        error.value = null;
        try {
            const updated = await brainFetch('PUT', `documents/${encodeURIComponent(id)}`, { body });
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
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to save document.';
        }
        finally {
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
    async function setSummary(id, summary) {
        error.value = null;
        try {
            const updated = await brainFetch('PUT', `documents/${encodeURIComponent(id)}/summary`, { body: { summary } });
            // `items` is the lightweight DocumentSummary list and doesn't
            // carry summary — only the selected detail does. Patch
            // `selected` so the editor's bound `editSummary` stays in
            // sync with what the server just persisted.
            if (selected.value?.id === id) {
                selected.value = updated;
            }
            return updated;
        }
        catch (e) {
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
    async function loadContent(id) {
        error.value = null;
        try {
            return await brainFetchText(`documents/${encodeURIComponent(id)}/content`);
        }
        catch (e) {
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
    async function replaceContent(id, content, mimeType) {
        loading.value = true;
        error.value = null;
        try {
            const mime = mimeType.trim() || 'text/plain';
            const updated = await brainSendRaw('PUT', `documents/${encodeURIComponent(id)}/content`, content, `${mime}; charset=utf-8`);
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
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to save document content.';
            return null;
        }
        finally {
            loading.value = false;
        }
    }
    async function remove(id) {
        loading.value = true;
        error.value = null;
        try {
            await brainFetch('DELETE', `documents/${encodeURIComponent(id)}`);
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
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to delete document.';
            return false;
        }
        finally {
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
        pathPrefix,
        kinds,
        kindFilter,
        search,
        loadPage,
        loadFolders,
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
    };
}
//# sourceMappingURL=useDocuments.js.map