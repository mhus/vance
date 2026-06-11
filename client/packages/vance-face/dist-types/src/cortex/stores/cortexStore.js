import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { brainFetch, brainFetchText, brainSendRaw } from '@vance/shared';
/**
 * Heuristic for "this document is not text we should pull as inline
 * content". Mirrors the same check in clientToolService.ts — images
 * and other binaries are surfaced by their renderer (e.g. ImageView
 * loads via documentContentUrl) and don't need their bytes streamed
 * into JS memory.
 */
function isBinaryMime(mime) {
    const m = (mime ?? '').toLowerCase();
    if (!m)
        return false;
    if (m.startsWith('image/'))
        return true;
    if (m.startsWith('audio/'))
        return true;
    if (m.startsWith('video/'))
        return true;
    if (m === 'application/pdf' || m === 'application/zip'
        || m === 'application/octet-stream')
        return true;
    return false;
}
export const useCortexStore = defineStore('cortex', () => {
    const projectId = ref(null);
    const files = ref([]);
    const openTabs = ref([]);
    const activeTabId = ref(null);
    const loading = ref(false);
    const error = ref(null);
    const currentSelection = ref(null);
    const activeTab = computed(() => {
        if (!activeTabId.value)
            return null;
        return openTabs.value.find((t) => t.id === activeTabId.value) ?? null;
    });
    function summaryToDocument(s) {
        return {
            id: s.id,
            path: s.path,
            name: s.name,
            title: s.title ?? null,
            mimeType: s.mimeType ?? null,
            kind: s.kind ?? null,
            inlineText: '', // populated on full load via openFile
            dirty: false,
        };
    }
    function dtoToDocument(d) {
        return {
            id: d.id,
            path: d.path,
            name: d.name,
            title: d.title ?? null,
            mimeType: d.mimeType ?? null,
            kind: d.kind ?? null,
            inlineText: d.inlineText ?? '',
            dirty: false,
            lastDeepReviewedHash: d.lastDeepReviewedHash ?? null,
            lastDeepReviewWarningsJson: d.lastDeepReviewWarningsJson ?? null,
        };
    }
    async function loadList(pid) {
        projectId.value = pid;
        loading.value = true;
        error.value = null;
        try {
            const params = new URLSearchParams({
                projectId: pid,
                page: '0',
                size: '500',
            });
            const data = await brainFetch('GET', `documents?${params}`);
            files.value = (data.items ?? []).map(summaryToDocument);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load documents.';
        }
        finally {
            loading.value = false;
        }
    }
    async function openFile(id) {
        const existing = openTabs.value.find((t) => t.id === id);
        if (existing) {
            activeTabId.value = id;
            return;
        }
        // Two-step load after the inline→storage migration: DTO carries
        // metadata only, the body lives behind /documents/{id}/content. See
        // composables/useDocuments.ts loadContent() for the same pattern.
        const dto = await brainFetch('GET', `documents/${encodeURIComponent(id)}`);
        const file = dtoToDocument(dto);
        if (!isBinaryMime(dto.mimeType)) {
            const text = await brainFetchText(`documents/${encodeURIComponent(id)}/content`);
            file.inlineText = text ?? '';
        }
        openTabs.value = [...openTabs.value, file];
        activeTabId.value = id;
    }
    function setActiveTab(id) {
        activeTabId.value = id;
    }
    /**
     * Move a document to a new path. Used by the file tree's drag &
     * drop — drag a file onto a folder row to call this with the folder's
     * path + the file's basename. The server validates conflicts (409 on
     * existing path) and we surface the error to the caller.
     */
    async function moveFile(id, newPath) {
        const updated = await brainFetch('PUT', `documents/${encodeURIComponent(id)}`, { body: { newPath } });
        const patch = {
            path: updated.path,
            name: updated.name,
        };
        const fIdx = files.value.findIndex((f) => f.id === id);
        if (fIdx >= 0) {
            files.value = [
                ...files.value.slice(0, fIdx),
                { ...files.value[fIdx], ...patch },
                ...files.value.slice(fIdx + 1),
            ];
        }
        const tIdx = openTabs.value.findIndex((t) => t.id === id);
        if (tIdx >= 0) {
            openTabs.value = [
                ...openTabs.value.slice(0, tIdx),
                { ...openTabs.value[tIdx], ...patch },
                ...openTabs.value.slice(tIdx + 1),
            ];
        }
    }
    /**
     * Upload one external (OS file system) file into the project at the
     * given folder. The server picks a unique path if the basename
     * collides — caller doesn't have to dedupe. Returns the new document
     * summary so the caller can refresh visual state or open it as a tab.
     */
    async function uploadExternalFile(file, folderPath) {
        if (!projectId.value)
            throw new Error('No project selected');
        const form = new FormData();
        form.append('file', file);
        const targetPath = folderPath ? `${folderPath}/${file.name}` : file.name;
        form.append('path', targetPath);
        if (file.type)
            form.append('mimeType', file.type);
        const params = new URLSearchParams({ projectId: projectId.value });
        const dto = await brainFetch('POST', `documents/upload?${params}`, { body: form });
        const created = dtoToDocument(dto);
        files.value = [...files.value, created];
        return created;
    }
    /**
     * Re-fetch metadata + content for an already-open tab and replace the
     * in-memory copy. Any local dirty edits on that tab are dropped — the
     * caller is responsible for confirming with the user beforehand.
     */
    async function reloadTab(id) {
        const idx = openTabs.value.findIndex((t) => t.id === id);
        if (idx < 0)
            return;
        const dto = await brainFetch('GET', `documents/${encodeURIComponent(id)}`);
        const fresh = dtoToDocument(dto);
        if (!isBinaryMime(dto.mimeType)) {
            const text = await brainFetchText(`documents/${encodeURIComponent(id)}/content`);
            fresh.inlineText = text ?? '';
        }
        openTabs.value = [
            ...openTabs.value.slice(0, idx),
            fresh,
            ...openTabs.value.slice(idx + 1),
        ];
    }
    function closeTab(id) {
        const idx = openTabs.value.findIndex((t) => t.id === id);
        if (idx < 0)
            return;
        openTabs.value = openTabs.value.filter((t) => t.id !== id);
        if (activeTabId.value === id) {
            activeTabId.value =
                openTabs.value.length === 0
                    ? null
                    : openTabs.value[Math.max(0, idx - 1)].id;
        }
    }
    function updateActiveContent(text) {
        const tab = activeTab.value;
        if (!tab)
            return;
        tab.inlineText = text;
        tab.dirty = true;
    }
    async function saveTab(id) {
        const tab = openTabs.value.find((t) => t.id === id);
        if (!tab || !tab.dirty)
            return;
        // Content lives at /documents/{id}/content after the inline→storage
        // migration. The body is the raw text (not JSON); Content-Type
        // carries the doc's mime so the server can re-classify on save.
        // See composables/useDocuments.ts replaceContent() for the canonical
        // pattern.
        const mime = (tab.mimeType ?? '').trim() || 'text/plain';
        const dto = await brainSendRaw('PUT', `documents/${encodeURIComponent(tab.id)}/content`, tab.inlineText, `${mime}; charset=utf-8`);
        // The server DTO has inlineText=null after migration — keep our
        // local copy so Vue doesn't redraw the editor with an empty body.
        const preservedText = tab.inlineText;
        const fresh = dtoToDocument(dto);
        Object.assign(tab, fresh);
        tab.inlineText = preservedText;
        tab.dirty = false;
        const li = files.value.findIndex((f) => f.id === tab.id);
        if (li >= 0) {
            files.value[li] = {
                ...files.value[li],
                path: dto.path,
                name: dto.name,
                title: dto.title ?? null,
                mimeType: dto.mimeType ?? files.value[li].mimeType,
            };
        }
    }
    async function saveActive() {
        if (!activeTabId.value)
            return;
        await saveTab(activeTabId.value);
    }
    /**
     * Flush every tab with pending edits. Sequential to keep server-side
     * order predictable — tabs are few, so we don't need parallelism.
     */
    async function saveAllDirty() {
        const dirtyTabs = openTabs.value.filter((t) => t.dirty);
        for (const t of dirtyTabs) {
            try {
                await saveTab(t.id);
            }
            catch (e) {
                console.warn(`Auto-save failed for ${t.path}`, e);
            }
        }
    }
    async function createFile(body) {
        if (!projectId.value)
            throw new Error('No project selected');
        const params = new URLSearchParams({ projectId: projectId.value });
        const dto = await brainFetch('POST', `documents?${params}`, { body });
        const file = dtoToDocument(dto);
        files.value = [...files.value, summaryToDocument({
                id: dto.id,
                projectId: dto.projectId,
                path: dto.path,
                name: dto.name,
                title: dto.title,
                mimeType: dto.mimeType,
                size: dto.size,
                tags: dto.tags ?? [],
                createdAtMs: dto.createdAtMs,
                createdBy: dto.createdBy,
                inline: dto.inline,
                kind: dto.kind,
            })];
        openTabs.value = [...openTabs.value, file];
        activeTabId.value = file.id;
        return file;
    }
    function setSelection(sel) {
        currentSelection.value = sel;
    }
    function clearSelection() {
        currentSelection.value = null;
    }
    async function deleteFile(id) {
        await brainFetch('DELETE', `documents/${encodeURIComponent(id)}`);
        files.value = files.value.filter((f) => f.id !== id);
        closeTab(id);
    }
    /**
     * Group the file list into a recursive folder tree based on
     * forward-slash-separated path segments. Files at the root sit
     * directly under the synthetic root node with path === "".
     */
    const fileTree = computed(() => {
        const root = { path: '', name: '', children: [], files: [] };
        const folderIndex = new Map();
        folderIndex.set('', root);
        for (const f of files.value) {
            const segments = f.path.split('/');
            const fileName = segments.pop();
            let current = root;
            let prefix = '';
            for (const seg of segments) {
                prefix = prefix ? `${prefix}/${seg}` : seg;
                let next = folderIndex.get(prefix);
                if (!next) {
                    next = { path: prefix, name: seg, children: [], files: [] };
                    folderIndex.set(prefix, next);
                    current.children.push(next);
                }
                current = next;
            }
            current.files.push({ ...f, name: fileName });
        }
        function sortNode(n) {
            n.children.sort((a, b) => a.name.localeCompare(b.name));
            n.files.sort((a, b) => a.name.localeCompare(b.name));
            n.children.forEach(sortNode);
        }
        sortNode(root);
        return root;
    });
    return {
        projectId,
        files,
        openTabs,
        activeTabId,
        activeTab,
        loading,
        error,
        fileTree,
        loadList,
        openFile,
        reloadTab,
        moveFile,
        uploadExternalFile,
        setActiveTab,
        closeTab,
        updateActiveContent,
        saveActive,
        saveTab,
        saveAllDirty,
        createFile,
        deleteFile,
        currentSelection,
        setSelection,
        clearSelection,
    };
});
//# sourceMappingURL=cortexStore.js.map