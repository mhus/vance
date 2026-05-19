import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { brainFetch } from '@vance/shared';
/**
 * Holds the open-tabs state, the active-tab pointer and the project's
 * full file list. Persists nothing across reloads in v1 — re-opening
 * a script after reload is one click in the tree.
 */
export const useScriptStore = defineStore('scriptCortex', () => {
    const projectId = ref(null);
    const files = ref([]);
    const openTabs = ref([]);
    const activeTabId = ref(null);
    const loading = ref(false);
    const error = ref(null);
    const activeTab = computed(() => {
        if (!activeTabId.value)
            return null;
        return openTabs.value.find((t) => t.id === activeTabId.value) ?? null;
    });
    function summaryToScriptFile(s) {
        return {
            id: s.id,
            path: s.path,
            name: s.name,
            title: s.title ?? null,
            mimeType: s.mimeType ?? null,
            inlineText: '', // populated on full load via fetchOne
            dirty: false,
        };
    }
    function dtoToScriptFile(d) {
        return {
            id: d.id,
            path: d.path,
            name: d.name,
            title: d.title ?? null,
            mimeType: d.mimeType ?? null,
            inlineText: d.inlineText ?? '',
            lastDeepReviewedHash: d.lastDeepReviewedHash ?? null,
            lastDeepReviewWarningsJson: d.lastDeepReviewWarningsJson ?? null,
            lastDeepReviewedAtMs: d.lastDeepReviewedAtMs ?? null,
            dirty: false,
        };
    }
    async function loadList(pid) {
        projectId.value = pid;
        loading.value = true;
        error.value = null;
        try {
            const data = await brainFetch('GET', `scripts?projectId=${encodeURIComponent(pid)}`);
            files.value = (data.items ?? []).map(summaryToScriptFile);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load scripts.';
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
        const dto = await brainFetch('GET', `scripts/${id}`);
        const file = dtoToScriptFile(dto);
        openTabs.value = [...openTabs.value, file];
        activeTabId.value = id;
    }
    function setActiveTab(id) {
        activeTabId.value = id;
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
    async function saveActive() {
        const tab = activeTab.value;
        if (!tab)
            return;
        const body = { inlineText: tab.inlineText };
        const dto = await brainFetch('PUT', `scripts/${tab.id}`, { body });
        const fresh = dtoToScriptFile(dto);
        Object.assign(tab, fresh);
        // Patch the file in the list (path/title might have changed).
        const li = files.value.findIndex((f) => f.id === tab.id);
        if (li >= 0)
            files.value[li] = { ...files.value[li], path: dto.path, name: dto.name, title: dto.title ?? null };
    }
    async function createFile(body) {
        if (!projectId.value)
            throw new Error('No project selected');
        const dto = await brainFetch('POST', `scripts?projectId=${encodeURIComponent(projectId.value)}`, { body });
        const file = dtoToScriptFile(dto);
        files.value = [...files.value, summaryToScriptFile({
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
    async function deleteFile(id) {
        await brainFetch('DELETE', `scripts/${id}`);
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
        // Sort folders + files alphabetically inside every node.
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
        setActiveTab,
        closeTab,
        updateActiveContent,
        saveActive,
        createFile,
        deleteFile,
    };
});
//# sourceMappingURL=scriptStore.js.map