import { computed, onBeforeUnmount, onMounted, provide, ref, watch } from 'vue';
import { EditorShell, VAlert, VANCE_LINK_HANDLER_KEY, VButton, VEmptyState, } from '@/components';
import { brainFetch } from '@vance/shared';
import { useTenantProjects } from '@composables/useTenantProjects';
import DocumentPresenceStrip from '@/ws/DocumentPresenceStrip.vue';
import { brainFetchText } from '@vance/shared';
import { isAudioVideoMime, tryThreeWayMerge, } from '@/composables/useDocumentChangeReaction';
import { onDocumentChanged } from '@/ws/wsConnectionStore';
import { isBinaryMime } from './stores/cortexStore';
import { useCortexStore } from './stores/cortexStore';
import { CortexClientToolService } from './clientToolService';
import { useDocumentInvalidate } from './composables/useDocumentInvalidate';
import FileTreeSidebar from './components/FileTreeSidebar.vue';
import EditorTabs from './components/EditorTabs.vue';
import TabRendererHost from './components/TabRendererHost.vue';
import CortexRightPanel from './components/CortexRightPanel.vue';
import CreateDocumentModal from './components/CreateDocumentModal.vue';
import NewFolderModal from './components/NewFolderModal.vue';
const props = withDefaults(defineProps(), { mode: 'cortex' });
const isCortex = computed(() => props.mode === 'cortex');
const sessionId = ref(null);
const sessionTitle = ref(null);
const projectId = ref(null);
const chatBoundDocumentId = ref(null);
const focusZone = ref('main');
const store = useCortexStore();
// Hijack vance:-doc links inside any descendant MarkdownView so a plain
// click opens the document as a tab instead of navigating away. Both
// modes benefit — notepad just doesn't have a chat bubble to do this
// for. Cross-project refs fall through to the default jump.
const onVanceLink = async ({ documentId, projectId: refProjectId, newTab }) => {
    if (newTab)
        return false;
    if (!store.projectId || refProjectId !== store.projectId)
        return false;
    focusZone.value = 'main';
    try {
        await store.openFile(documentId);
    }
    catch (e) {
        console.warn('Editor: failed to open vance: link in editor', e);
        return false;
    }
    return true;
};
provide(VANCE_LINK_HANDLER_KEY, onVanceLink);
const { projects: tenantProjects, reload: loadTenantProjects } = useTenantProjects();
const bootError = ref(null);
const saving = ref(false);
const saveError = ref(null);
const showCreate = ref(false);
const createPrefill = ref(null);
const showNewFolder = ref(false);
const newFolderInitial = ref('');
// True while initial state restoration is running. While set, the
// state-persistence watcher is muted — otherwise the per-{@code openFile}
// rebuild would echo the restored state straight back to the server.
const restoring = ref(false);
onMounted(async () => {
    const params = new URLSearchParams(window.location.search);
    if (isCortex.value) {
        const id = params.get('sessionId');
        if (!id) {
            window.location.replace('/chat.html');
            return;
        }
        sessionId.value = id;
        await resolveSession(id);
    }
    else {
        const pid = params.get('project') ?? params.get('projectId');
        if (!pid) {
            window.location.replace('/documents.html');
            return;
        }
        await resolveProject(pid);
        // Optional URL-driven actions for the explorer → notepad handoff.
        const createPath = params.get('path');
        if (params.get('create') === '1') {
            createPrefill.value = { path: createPath ?? '' };
            showCreate.value = true;
        }
        const urlDoc = readDocFromUrl();
        if (urlDoc) {
            try {
                await store.openFile(urlDoc);
            }
            catch {
                // doc id might be stale (deleted, moved); stay on empty state.
            }
        }
    }
});
/**
 * Cortex bootstrap: resolve project via the session list, restore
 * open-tabs from the session document. The list is owner-scoped so
 * the user only sees their own sessions; if id is not found we surface
 * a recoverable error rather than redirecting.
 */
async function resolveSession(id) {
    try {
        const sessions = await brainFetch('GET', 'sessions');
        const match = sessions.find((s) => s.sessionId === id);
        if (!match) {
            bootError.value = `Session ${id} not found.`;
            return;
        }
        projectId.value = match.projectId;
        sessionTitle.value = match.title ?? null;
        void loadTenantProjects();
        await store.loadList(match.projectId);
        await restoreCortexState(match);
    }
    catch (e) {
        bootError.value = e instanceof Error ? e.message : 'Failed to load session.';
    }
}
/**
 * Notepad bootstrap: project is given via URL — no session involved.
 * Tab state is ephemeral by design (deep-links via {@code ?doc=…} cover
 * the bookmark case; we don't persist multi-tab state to the server).
 */
async function resolveProject(pid) {
    try {
        projectId.value = pid;
        void loadTenantProjects();
        await store.loadList(pid);
    }
    catch (e) {
        bootError.value = e instanceof Error ? e.message : 'Failed to load project.';
    }
}
async function restoreCortexState(summary) {
    const tabIds = summary.openDocumentIds ?? [];
    if (tabIds.length === 0) {
        chatBoundDocumentId.value = null;
        return;
    }
    restoring.value = true;
    try {
        for (const docId of tabIds) {
            try {
                await store.openFile(docId);
            }
            catch {
                // Document gone or unreadable — skip silently.
            }
        }
        const bound = summary.chatBoundDocumentId ?? null;
        chatBoundDocumentId.value = bound;
        const urlDoc = readDocFromUrl();
        if (urlDoc && store.openTabs.some((t) => t.id === urlDoc)) {
            store.setActiveTab(urlDoc);
        }
        else if (bound && store.openTabs.some((t) => t.id === bound)) {
            store.setActiveTab(bound);
        }
        replaceDocInUrl(store.activeTabId ?? null);
    }
    finally {
        restoring.value = false;
    }
}
async function persistCortexState() {
    if (!isCortex.value)
        return;
    if (!sessionId.value || restoring.value)
        return;
    const body = {
        openDocumentIds: store.openTabs.map((t) => t.id),
        chatBoundDocumentId: chatBoundDocumentId.value,
    };
    try {
        await brainFetch('PUT', `sessions/${encodeURIComponent(sessionId.value)}/cortex-state`, { body });
    }
    catch (e) {
        console.warn('Failed to persist Cortex state', e);
    }
}
// Bind chat to the first opened tab automatically — Cortex only.
watch(() => store.openTabs.map((t) => t.id).join(','), (idsKey) => {
    if (!isCortex.value)
        return;
    if (restoring.value)
        return;
    const ids = idsKey ? idsKey.split(',') : [];
    if (ids.length === 0) {
        if (chatBoundDocumentId.value !== null)
            chatBoundDocumentId.value = null;
    }
    else if (chatBoundDocumentId.value === null
        || !ids.includes(chatBoundDocumentId.value)) {
        chatBoundDocumentId.value = ids[0];
    }
    void persistCortexState();
});
watch(chatBoundDocumentId, () => {
    void persistCortexState();
});
const title = computed(() => {
    if (isCortex.value) {
        if (sessionTitle.value)
            return `Cortex · ${sessionTitle.value}`;
        return 'Cortex';
    }
    if (projectLabel.value)
        return `Notepad · ${projectLabel.value}`;
    return 'Notepad';
});
const projectLabel = computed(() => {
    const id = projectId.value;
    if (!id)
        return null;
    const p = tenantProjects.value.find((x) => x.name === id);
    const titleStr = p?.title?.trim();
    return titleStr && titleStr.length > 0 ? titleStr : id;
});
/**
 * Cortex: jump back to chat picker with project pre-selected. Notepad:
 * jump back to documents.html explorer with the same project selected.
 */
function onProjectCrumbClick() {
    const id = projectId.value;
    if (!id)
        return;
    if (isCortex.value) {
        window.location.href = `/chat.html?project=${encodeURIComponent(id)}`;
    }
    else {
        window.location.href = `/documents.html?projectId=${encodeURIComponent(id)}`;
    }
}
const breadcrumbs = computed(() => {
    const crumbs = [];
    if (projectLabel.value && projectId.value) {
        crumbs.push({ text: projectLabel.value, onClick: onProjectCrumbClick });
    }
    else if (projectLabel.value) {
        crumbs.push(projectLabel.value);
    }
    if (store.activeTab?.path)
        crumbs.push(store.activeTab.path);
    return crumbs;
});
// ──────────────── URL sync for active document tab ────────────────
const URL_DOC_PARAM = 'doc';
let suppressHistoryPush = false;
function readDocFromUrl() {
    const params = new URLSearchParams(window.location.search);
    return params.get(URL_DOC_PARAM);
}
function buildUrlWithDoc(docId) {
    const params = new URLSearchParams(window.location.search);
    if (docId)
        params.set(URL_DOC_PARAM, docId);
    else
        params.delete(URL_DOC_PARAM);
    const query = params.toString();
    return `${window.location.pathname}${query ? `?${query}` : ''}`;
}
function pushDocToUrl(docId) {
    const next = buildUrlWithDoc(docId);
    if (next === `${window.location.pathname}${window.location.search}`)
        return;
    window.history.pushState({ doc: docId }, '', next);
}
function replaceDocInUrl(docId) {
    const next = buildUrlWithDoc(docId);
    if (next === `${window.location.pathname}${window.location.search}`)
        return;
    window.history.replaceState({ doc: docId }, '', next);
}
function onPopState() {
    const urlDoc = readDocFromUrl();
    if (urlDoc && !store.openTabs.some((t) => t.id === urlDoc))
        return;
    if ((urlDoc ?? null) === (store.activeTabId ?? null))
        return;
    suppressHistoryPush = true;
    if (urlDoc) {
        store.setActiveTab(urlDoc);
    }
}
const activeTab = computed(() => store.activeTab);
const tabReactions = new Map();
const tabReactionRevision = ref(0);
const RECENT_EDITOR_TTL_MS = 2500;
function tryApplyForTab(tabId, notification) {
    const tab = store.openTabs.find((t) => t.id === tabId);
    if (!tab) {
        return Promise.resolve(true);
    }
    return runTryApply(tab, notification.kind ?? 'upserted');
}
async function runTryApply(tab, _kind) {
    const mime = tab.mimeType ?? '';
    if (isAudioVideoMime(mime)) {
        return false;
    }
    if (!isBinaryMime(mime) && tab.dirty) {
        const remoteText = await brainFetchText(`documents/${encodeURIComponent(tab.id)}/content`);
        if (remoteText === null) {
            return false;
        }
        const outcome = tryThreeWayMerge(tab.baselineInlineText, tab.inlineText, remoteText);
        if (!outcome.ok) {
            return false;
        }
        tab.inlineText = outcome.merged;
        tab.baselineInlineText = outcome.remote;
        tab.dirty = outcome.merged !== outcome.remote;
        return true;
    }
    await store.reloadTab(tab.id);
    return true;
}
function attachTabReaction(tab) {
    const pending = ref(null);
    const recent = ref(null);
    const path = tab.path;
    const reaction = {
        path,
        pendingChange: pending,
        recentEditor: recent,
        unsubscribe: () => { },
        fadeTimer: null,
    };
    reaction.unsubscribe = onDocumentChanged(path, async (notification) => {
        const kind = notification.kind ?? 'upserted';
        try {
            const handled = await tryApplyForTab(tab.id, notification);
            if (!handled) {
                pending.value = kind;
                tabReactionRevision.value++;
            }
            else {
                const name = notification.editorDisplayName ?? notification.editorUserId ?? null;
                if (name) {
                    recent.value = { displayName: name, setAt: Date.now() };
                    if (reaction.fadeTimer)
                        clearTimeout(reaction.fadeTimer);
                    reaction.fadeTimer = setTimeout(() => {
                        recent.value = null;
                        reaction.fadeTimer = null;
                        tabReactionRevision.value++;
                    }, RECENT_EDITOR_TTL_MS);
                    tabReactionRevision.value++;
                }
            }
        }
        catch (e) {
            console.warn(`[documents.changed] tryApply threw for tab='${path}':`, e);
            pending.value = kind;
            tabReactionRevision.value++;
        }
    });
    tabReactions.set(tab.id, reaction);
}
function detachTabReaction(tabId) {
    const r = tabReactions.get(tabId);
    if (!r)
        return;
    try {
        r.unsubscribe();
    }
    catch { /* ignore */ }
    if (r.fadeTimer) {
        clearTimeout(r.fadeTimer);
        r.fadeTimer = null;
    }
    tabReactions.delete(tabId);
    tabReactionRevision.value++;
}
watch(() => store.openTabs.map((t) => ({ id: t.id, path: t.path })), (current, previous) => {
    const currentIds = new Set(current.map((t) => t.id));
    for (const old of previous ?? []) {
        if (!currentIds.has(old.id))
            detachTabReaction(old.id);
    }
    for (const t of current) {
        const existing = tabReactions.get(t.id);
        if (!existing) {
            const tabObj = store.openTabs.find((x) => x.id === t.id);
            if (tabObj)
                attachTabReaction(tabObj);
        }
        else if (existing.path !== t.path) {
            detachTabReaction(t.id);
            const tabObj = store.openTabs.find((x) => x.id === t.id);
            if (tabObj)
                attachTabReaction(tabObj);
        }
    }
}, { immediate: true, deep: true });
onBeforeUnmount(() => {
    for (const id of Array.from(tabReactions.keys()))
        detachTabReaction(id);
});
// Session-channel {@code document-invalidate} frames — server-side
// tools (doc_write / doc_edit / doc_note_*) telling us their target was
// just mutated on behalf of this session. Cross-pod fallback for the
// documents-channel push that needs Redis. Debounce + 3-way-merge on
// dirty tabs (same path as the documents.changed handler above), and
// expose an {@code isAgentEditing} flag for the topbar banner.
const openTabIdsRef = computed(() => store.openTabs.map((t) => t.id));
const { isAgentEditing } = useDocumentInvalidate({
    openDocumentIds: openTabIdsRef,
    apply: async (docId, _kind) => {
        const tab = store.openTabs.find((t) => t.id === docId);
        if (!tab)
            return;
        try {
            const handled = await runTryApply(tab, 'upserted');
            if (!handled) {
                const r = tabReactions.get(tab.id);
                if (r) {
                    r.pendingChange.value = 'upserted';
                    tabReactionRevision.value++;
                }
            }
        }
        catch (e) {
            console.warn(`[document-invalidate] apply for tab='${tab.path}' threw:`, e);
        }
    },
});
const activePendingChange = computed(() => {
    // eslint-disable-next-line @typescript-eslint/no-unused-expressions
    tabReactionRevision.value;
    const id = activeTab.value?.id;
    if (!id)
        return null;
    return tabReactions.get(id)?.pendingChange.value ?? null;
});
const activeRecentEditor = computed(() => {
    // eslint-disable-next-line @typescript-eslint/no-unused-expressions
    tabReactionRevision.value;
    const id = activeTab.value?.id;
    if (!id)
        return null;
    return tabReactions.get(id)?.recentEditor.value ?? null;
});
function keepLocalForActive() {
    const id = activeTab.value?.id;
    if (!id)
        return;
    const r = tabReactions.get(id);
    if (r) {
        r.pendingChange.value = null;
        tabReactionRevision.value++;
    }
}
async function acceptRemoteForActive() {
    const tab = activeTab.value;
    if (!tab)
        return;
    const r = tabReactions.get(tab.id);
    if (r) {
        r.pendingChange.value = null;
        tabReactionRevision.value++;
    }
    await store.reloadTab(tab.id);
}
const chatBoundDocumentPath = computed(() => {
    const id = chatBoundDocumentId.value;
    if (!id)
        return null;
    const tab = store.openTabs.find((t) => t.id === id);
    return tab?.path ?? null;
});
const hasDirtyTabs = computed(() => store.openTabs.some((t) => t.dirty));
const isActiveTabBound = computed(() => activeTab.value !== null && chatBoundDocumentId.value === activeTab.value.id);
const chatBoundDocumentPathDisplay = computed(() => {
    const p = chatBoundDocumentPath.value;
    if (!p)
        return null;
    const MAX = 32;
    if (p.length <= MAX)
        return p;
    return '…' + p.slice(p.length - (MAX - 1));
});
const bindIconTooltip = computed(() => {
    if (isActiveTabBound.value) {
        return 'Chat is bound to this document';
    }
    if (!activeTab.value) {
        return chatBoundDocumentPath.value
            ? `Chat is bound to ${chatBoundDocumentPath.value}`
            : 'Open a document to bind';
    }
    return chatBoundDocumentPath.value
        ? `Bind chat to current tab (currently: ${chatBoundDocumentPath.value})`
        : 'Bind chat to current tab';
});
/**
 * The client-tool surface Cortex exposes to the LLM agent. Built only
 * in cortex mode — notepad has no chat agent to talk to.
 */
const clientToolService = isCortex.value
    ? new CortexClientToolService({
        getSelection: () => store.currentSelection,
        getActiveTab: () => {
            const tab = store.activeTab;
            if (!tab)
                return null;
            return { documentId: tab.id, path: tab.path };
        },
        openFileByPath: async (path) => {
            const normalised = path.replace(/^\/+/, '');
            const file = store.files.find((f) => f.path === normalised);
            if (!file)
                return null;
            const alreadyOpen = store.openTabs.some((t) => t.id === file.id);
            focusZone.value = 'main';
            await store.openFile(file.id);
            return { documentId: file.id, path: file.path, alreadyOpen };
        },
    })
    : null;
async function onSave() {
    if (!activeTab.value)
        return;
    saving.value = true;
    saveError.value = null;
    try {
        await store.saveActive();
    }
    catch (e) {
        saveError.value = e instanceof Error ? e.message : 'Save failed';
    }
    finally {
        saving.value = false;
    }
}
function onNew() {
    // Prefill the directory from the currently active tab so "New file…"
    // in the same folder is one-click. Without an active tab, suggest
    // `documents` — the conventional user-content folder — instead of
    // leaving the field blank.
    const ref = activeTab.value;
    let path = '';
    if (ref) {
        const idx = ref.path.lastIndexOf('/');
        path = idx >= 0 ? ref.path.slice(0, idx) : '';
    }
    else {
        path = 'documents';
    }
    createPrefill.value = { path };
    showCreate.value = true;
}
async function onCreateConfirm(result) {
    if (!projectId.value)
        return;
    if (result.kind === 'inline') {
        await store.createFile({
            path: result.fullPath,
            title: result.title,
            tags: result.tags,
            mimeType: result.mimeType,
            inlineText: result.inlineText,
        });
    }
    else {
        for (const file of result.files) {
            await store.uploadExternalFile(file, result.targetFolder);
        }
        // Open the first uploaded file as a tab if there was exactly one.
        if (result.files.length === 1) {
            const last = store.files[store.files.length - 1];
            if (last)
                await store.openFile(last.id);
        }
    }
    showCreate.value = false;
    createPrefill.value = null;
}
function onNewFolder() {
    const ref = activeTab.value;
    if (ref) {
        const idx = ref.path.lastIndexOf('/');
        newFolderInitial.value = idx >= 0 ? ref.path.slice(0, idx) : '';
    }
    else {
        newFolderInitial.value = 'documents';
    }
    showNewFolder.value = true;
}
function onNewFolderConfirm(path) {
    store.addVirtualFolder(path);
    showNewFolder.value = false;
}
async function onDelete(id) {
    if (!confirm('Delete this document?'))
        return;
    await store.deleteFile(id);
}
// ──────────────── File-tree drag & drop ────────────────
const treeError = ref(null);
async function onMoveFile(payload) {
    const doc = store.files.find((f) => f.id === payload.id)
        ?? store.openTabs.find((t) => t.id === payload.id);
    if (!doc)
        return;
    const slash = doc.path.lastIndexOf('/');
    const basename = slash === -1 ? doc.path : doc.path.slice(slash + 1);
    const newPath = payload.targetFolder
        ? `${payload.targetFolder}/${basename}`
        : basename;
    if (newPath === doc.path)
        return;
    treeError.value = null;
    try {
        await store.moveFile(payload.id, newPath);
    }
    catch (e) {
        treeError.value = e instanceof Error ? e.message : 'Move failed';
    }
}
async function onUploadFiles(payload) {
    treeError.value = null;
    const failures = [];
    for (const file of payload.files) {
        try {
            await store.uploadExternalFile(file, payload.targetFolder);
        }
        catch (e) {
            const msg = e instanceof Error ? e.message : 'Upload failed';
            failures.push(`${file.name}: ${msg}`);
        }
    }
    if (failures.length > 0) {
        treeError.value = failures.join('\n');
    }
}
function backHome() {
    if (isCortex.value) {
        if (sessionId.value) {
            const params = new URLSearchParams();
            params.set('sessionId', sessionId.value);
            if (projectId.value)
                params.set('project', projectId.value);
            window.location.href = `/chat.html?${params.toString()}`;
        }
        else {
            window.location.href = '/chat.html';
        }
    }
    else {
        if (projectId.value) {
            window.location.href = `/documents.html?projectId=${encodeURIComponent(projectId.value)}`;
        }
        else {
            window.location.href = '/documents.html';
        }
    }
}
async function onSaveAll() {
    saving.value = true;
    saveError.value = null;
    try {
        await store.saveAllDirty();
    }
    catch (e) {
        saveError.value = e instanceof Error ? e.message : 'Save failed';
    }
    finally {
        saving.value = false;
    }
}
function onCloseActiveTab() {
    if (!activeTab.value)
        return;
    store.closeTab(activeTab.value.id);
}
function onBindActiveTab() {
    if (!activeTab.value)
        return;
    chatBoundDocumentId.value = activeTab.value.id;
}
function onUnbindChat() {
    chatBoundDocumentId.value = null;
}
function closeMenus() {
    const el = document.activeElement;
    if (el instanceof HTMLElement)
        el.blur();
}
watch(title, (t) => {
    document.title = t;
}, { immediate: true });
// ─── Auto-save with debounce ───────────────────────────────────────
const AUTO_SAVE_DEBOUNCE_MS = 2000;
let autoSaveTimer = null;
function scheduleAutoSave() {
    if (autoSaveTimer !== null)
        clearTimeout(autoSaveTimer);
    autoSaveTimer = setTimeout(() => {
        autoSaveTimer = null;
        void store.saveAllDirty();
    }, AUTO_SAVE_DEBOUNCE_MS);
}
watch(() => store.openTabs.map((t) => `${t.id}:${t.inlineText.length}:${t.dirty ? 1 : 0}`).join('|'), () => {
    if (restoring.value)
        return;
    if (store.openTabs.some((t) => t.dirty)) {
        scheduleAutoSave();
    }
});
watch(() => store.activeTabId, (_curr, prev) => {
    if (!prev || restoring.value)
        return;
    const previousTab = store.openTabs.find((t) => t.id === prev);
    if (previousTab?.dirty) {
        void store.saveTab(prev);
    }
});
watch(() => store.activeTabId, (curr) => {
    if (restoring.value)
        return;
    if (suppressHistoryPush) {
        suppressHistoryPush = false;
        return;
    }
    pushDocToUrl(curr ?? null);
});
function onBeforeUnload(e) {
    if (store.openTabs.some((t) => t.dirty)) {
        e.preventDefault();
        e.returnValue = '';
    }
}
function onKeyDown(e) {
    const cmd = e.metaKey || e.ctrlKey;
    if (!cmd)
        return;
    const key = e.key.toLowerCase();
    if (key === 's') {
        e.preventDefault();
        void store.saveAllDirty();
        return;
    }
    if (key === 'w' && activeTab.value) {
        e.preventDefault();
        store.closeTab(activeTab.value.id);
        return;
    }
}
onMounted(() => {
    window.addEventListener('beforeunload', onBeforeUnload);
    window.addEventListener('keydown', onKeyDown);
    window.addEventListener('popstate', onPopState);
});
onBeforeUnmount(() => {
    window.removeEventListener('beforeunload', onBeforeUnload);
    window.removeEventListener('keydown', onKeyDown);
    window.removeEventListener('popstate', onPopState);
    if (autoSaveTimer !== null) {
        clearTimeout(autoSaveTimer);
        autoSaveTimer = null;
    }
    void store.saveAllDirty();
});
const backLabel = computed(() => (isCortex.value ? '← Chat' : '← Documents'));
const bootReadyKey = computed(() => (isCortex.value ? !!sessionId.value : !!projectId.value));
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({ mode: 'cortex' });
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
// CSS variable injection 
// CSS variable injection end 
if (__VLS_ctx.bootReadyKey) {
    const __VLS_0 = {}.EditorShell;
    /** @type {[typeof __VLS_components.EditorShell, typeof __VLS_components.EditorShell, ]} */ ;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
        ...{ 'onTitleClick': {} },
        focusZone: (__VLS_ctx.focusZone),
        title: (__VLS_ctx.title),
        breadcrumbs: (__VLS_ctx.breadcrumbs),
        fullHeight: (true),
        focusModel: "auto",
        showSidebar: (true),
        showRightPanel: (__VLS_ctx.isCortex),
        titleClickable: true,
    }));
    const __VLS_2 = __VLS_1({
        ...{ 'onTitleClick': {} },
        focusZone: (__VLS_ctx.focusZone),
        title: (__VLS_ctx.title),
        breadcrumbs: (__VLS_ctx.breadcrumbs),
        fullHeight: (true),
        focusModel: "auto",
        showSidebar: (true),
        showRightPanel: (__VLS_ctx.isCortex),
        titleClickable: true,
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    let __VLS_4;
    let __VLS_5;
    let __VLS_6;
    const __VLS_7 = {
        onTitleClick: (...[$event]) => {
            if (!(__VLS_ctx.bootReadyKey))
                return;
            __VLS_ctx.focusZone = 'sidebar';
        }
    };
    __VLS_3.slots.default;
    {
        const { 'topbar-extra': __VLS_thisSlot } = __VLS_3.slots;
        const __VLS_8 = {}.transition;
        /** @type {[typeof __VLS_components.Transition, typeof __VLS_components.transition, typeof __VLS_components.Transition, typeof __VLS_components.transition, ]} */ ;
        // @ts-ignore
        const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
            name: "recent-editor-fade",
        }));
        const __VLS_10 = __VLS_9({
            name: "recent-editor-fade",
        }, ...__VLS_functionalComponentArgsRest(__VLS_9));
        __VLS_11.slots.default;
        if (__VLS_ctx.isAgentEditing) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "\u006d\u0072\u002d\u0032\u0020\u0069\u006e\u006c\u0069\u006e\u0065\u002d\u0066\u006c\u0065\u0078\u0020\u0069\u0074\u0065\u006d\u0073\u002d\u0063\u0065\u006e\u0074\u0065\u0072\u0020\u0067\u0061\u0070\u002d\u0031\u0020\u0074\u0065\u0078\u0074\u002d\u0078\u0073\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0074\u0065\u0078\u0074\u002d\u0069\u006e\u0066\u006f\u0020\u0066\u006f\u006e\u0074\u002d\u006d\u0065\u0064\u0069\u0075\u006d\u0020\u0073\u0065\u006c\u0065\u0063\u0074\u002d\u006e\u006f\u006e\u0065\u0020\u0061\u006e\u0069\u006d\u0061\u0074\u0065\u002d\u0070\u0075\u006c\u0073\u0065" },
                title: (__VLS_ctx.$t('documents.agentEditing.tooltip')),
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                'aria-hidden': "true",
            });
            (__VLS_ctx.$t('documents.agentEditing.label'));
        }
        var __VLS_11;
        const __VLS_12 = {}.transition;
        /** @type {[typeof __VLS_components.Transition, typeof __VLS_components.transition, typeof __VLS_components.Transition, typeof __VLS_components.transition, ]} */ ;
        // @ts-ignore
        const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
            name: "recent-editor-fade",
        }));
        const __VLS_14 = __VLS_13({
            name: "recent-editor-fade",
        }, ...__VLS_functionalComponentArgsRest(__VLS_13));
        __VLS_15.slots.default;
        if (__VLS_ctx.activeRecentEditor && __VLS_ctx.activeTab?.path) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "\u006d\u0072\u002d\u0032\u0020\u0069\u006e\u006c\u0069\u006e\u0065\u002d\u0066\u006c\u0065\u0078\u0020\u0069\u0074\u0065\u006d\u0073\u002d\u0063\u0065\u006e\u0074\u0065\u0072\u0020\u0067\u0061\u0070\u002d\u0031\u0020\u0074\u0065\u0078\u0074\u002d\u0078\u0073\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0074\u0065\u0078\u0074\u002d\u0062\u0061\u0073\u0065\u002d\u0063\u006f\u006e\u0074\u0065\u006e\u0074\u002f\u0037\u0030\u0020\u0066\u006f\u006e\u0074\u002d\u006d\u0065\u0064\u0069\u0075\u006d\u0020\u0073\u0065\u006c\u0065\u0063\u0074\u002d\u006e\u006f\u006e\u0065" },
                title: (__VLS_ctx.$t('documents.recentEditor.tooltip', { name: __VLS_ctx.activeRecentEditor.displayName })),
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                'aria-hidden': "true",
                ...{ class: "text-success" },
            });
            (__VLS_ctx.activeRecentEditor.displayName);
        }
        var __VLS_15;
        if (__VLS_ctx.activePendingChange && __VLS_ctx.activeTab?.path) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "\u006d\u0072\u002d\u0033\u0020\u0069\u006e\u006c\u0069\u006e\u0065\u002d\u0066\u006c\u0065\u0078\u0020\u0069\u0074\u0065\u006d\u0073\u002d\u0063\u0065\u006e\u0074\u0065\u0072\u0020\u0067\u0061\u0070\u002d\u0032\u0020\u0072\u006f\u0075\u006e\u0064\u0065\u0064\u002d\u006d\u0064\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u002d\u0077\u0061\u0072\u006e\u0069\u006e\u0067\u002f\u0034\u0030\u0020\u0062\u0067\u002d\u0077\u0061\u0072\u006e\u0069\u006e\u0067\u002f\u0031\u0035\u0020\u0070\u0078\u002d\u0032\u0020\u0070\u0079\u002d\u0031\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0074\u0065\u0078\u0074\u002d\u0078\u0073\u0020\u0066\u006f\u006e\u0074\u002d\u006d\u0065\u0064\u0069\u0075\u006d\u0020\u0074\u0065\u0078\u0074\u002d\u0077\u0061\u0072\u006e\u0069\u006e\u0067\u002d\u0063\u006f\u006e\u0074\u0065\u006e\u0074" },
                title: (__VLS_ctx.activePendingChange === 'deleted'
                    ? __VLS_ctx.$t('documents.externallyChanged.deletedTooltip')
                    : __VLS_ctx.$t('documents.externallyChanged.upsertedTooltip')),
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                'aria-hidden': "true",
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            (__VLS_ctx.activePendingChange === 'deleted'
                ? __VLS_ctx.$t('documents.externallyChanged.deleted')
                : __VLS_ctx.$t('documents.externallyChanged.upserted'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!(__VLS_ctx.bootReadyKey))
                            return;
                        if (!(__VLS_ctx.activePendingChange && __VLS_ctx.activeTab?.path))
                            return;
                        __VLS_ctx.keepLocalForActive();
                    } },
                type: "button",
                ...{ class: "\u0072\u006f\u0075\u006e\u0064\u0065\u0064\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u002d\u0077\u0061\u0072\u006e\u0069\u006e\u0067\u002f\u0035\u0030\u0020\u0070\u0078\u002d\u0031\u002e\u0035\u0020\u0070\u0079\u002d\u0030\u002e\u0035\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0068\u006f\u0076\u0065\u0072\u003a\u0062\u0067\u002d\u0077\u0061\u0072\u006e\u0069\u006e\u0067\u002f\u0033\u0030" },
            });
            (__VLS_ctx.$t('documents.externallyChanged.keepLocal'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!(__VLS_ctx.bootReadyKey))
                            return;
                        if (!(__VLS_ctx.activePendingChange && __VLS_ctx.activeTab?.path))
                            return;
                        __VLS_ctx.acceptRemoteForActive();
                    } },
                type: "button",
                ...{ class: "\u0072\u006f\u0075\u006e\u0064\u0065\u0064\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u002d\u0077\u0061\u0072\u006e\u0069\u006e\u0067\u002f\u0035\u0030\u0020\u0070\u0078\u002d\u0031\u002e\u0035\u0020\u0070\u0079\u002d\u0030\u002e\u0035\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0068\u006f\u0076\u0065\u0072\u003a\u0062\u0067\u002d\u0077\u0061\u0072\u006e\u0069\u006e\u0067\u002f\u0033\u0030" },
            });
            (__VLS_ctx.$t('documents.externallyChanged.acceptRemote'));
        }
        if (__VLS_ctx.activeTab?.path) {
            /** @type {[typeof DocumentPresenceStrip, ]} */ ;
            // @ts-ignore
            const __VLS_16 = __VLS_asFunctionalComponent(DocumentPresenceStrip, new DocumentPresenceStrip({
                path: (__VLS_ctx.activeTab.path),
                ...{ class: "mr-2" },
            }));
            const __VLS_17 = __VLS_16({
                path: (__VLS_ctx.activeTab.path),
                ...{ class: "mr-2" },
            }, ...__VLS_functionalComponentArgsRest(__VLS_16));
        }
        const __VLS_19 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_20 = __VLS_asFunctionalComponent(__VLS_19, new __VLS_19({
            ...{ 'onClick': {} },
            size: "sm",
            variant: "ghost",
        }));
        const __VLS_21 = __VLS_20({
            ...{ 'onClick': {} },
            size: "sm",
            variant: "ghost",
        }, ...__VLS_functionalComponentArgsRest(__VLS_20));
        let __VLS_23;
        let __VLS_24;
        let __VLS_25;
        const __VLS_26 = {
            onClick: (__VLS_ctx.backHome)
        };
        __VLS_22.slots.default;
        (__VLS_ctx.backLabel);
        var __VLS_22;
    }
    {
        const { sidebar: __VLS_thisSlot } = __VLS_3.slots;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex flex-col h-full min-h-0" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex-1 min-h-0 overflow-y-auto" },
        });
        if (__VLS_ctx.treeError) {
            const __VLS_27 = {}.VAlert;
            /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
            // @ts-ignore
            const __VLS_28 = __VLS_asFunctionalComponent(__VLS_27, new __VLS_27({
                variant: "error",
                ...{ class: "m-2" },
            }));
            const __VLS_29 = __VLS_28({
                variant: "error",
                ...{ class: "m-2" },
            }, ...__VLS_functionalComponentArgsRest(__VLS_28));
            __VLS_30.slots.default;
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            (__VLS_ctx.treeError);
            var __VLS_30;
        }
        if (__VLS_ctx.projectId) {
            /** @type {[typeof FileTreeSidebar, ]} */ ;
            // @ts-ignore
            const __VLS_31 = __VLS_asFunctionalComponent(FileTreeSidebar, new FileTreeSidebar({
                ...{ 'onOpenFile': {} },
                ...{ 'onDeleteFile': {} },
                ...{ 'onMoveFile': {} },
                ...{ 'onUploadFiles': {} },
                ...{ 'onReload': {} },
                root: (__VLS_ctx.store.fileTree),
                activeFileId: (__VLS_ctx.store.activeTabId),
            }));
            const __VLS_32 = __VLS_31({
                ...{ 'onOpenFile': {} },
                ...{ 'onDeleteFile': {} },
                ...{ 'onMoveFile': {} },
                ...{ 'onUploadFiles': {} },
                ...{ 'onReload': {} },
                root: (__VLS_ctx.store.fileTree),
                activeFileId: (__VLS_ctx.store.activeTabId),
            }, ...__VLS_functionalComponentArgsRest(__VLS_31));
            let __VLS_34;
            let __VLS_35;
            let __VLS_36;
            const __VLS_37 = {
                onOpenFile: ((id) => { __VLS_ctx.focusZone = 'main'; __VLS_ctx.store.openFile(id); })
            };
            const __VLS_38 = {
                onDeleteFile: (__VLS_ctx.onDelete)
            };
            const __VLS_39 = {
                onMoveFile: (__VLS_ctx.onMoveFile)
            };
            const __VLS_40 = {
                onUploadFiles: (__VLS_ctx.onUploadFiles)
            };
            const __VLS_41 = {
                onReload: (() => __VLS_ctx.projectId && __VLS_ctx.store.loadList(__VLS_ctx.projectId))
            };
            var __VLS_33;
        }
        else if (__VLS_ctx.bootError) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "p-3 text-sm" },
            });
            const __VLS_42 = {}.VAlert;
            /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
            // @ts-ignore
            const __VLS_43 = __VLS_asFunctionalComponent(__VLS_42, new __VLS_42({
                variant: "error",
            }));
            const __VLS_44 = __VLS_43({
                variant: "error",
            }, ...__VLS_functionalComponentArgsRest(__VLS_43));
            __VLS_45.slots.default;
            (__VLS_ctx.bootError);
            var __VLS_45;
        }
        else {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "p-3 text-sm opacity-60" },
            });
        }
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col h-full min-h-0" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center gap-1 px-2 py-1 border-b border-base-300 bg-base-200 text-sm shrink-0" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "dropdown" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        tabindex: "0",
        role: "button",
        ...{ class: "btn btn-ghost btn-xs" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
        tabindex: "0",
        ...{ class: "dropdown-content menu menu-sm bg-base-100 rounded-box z-[20] mt-1 w-56 p-2 shadow" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.bootReadyKey))
                    return;
                __VLS_ctx.closeMenus();
                __VLS_ctx.onNew();
            } },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "flex-1" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.bootReadyKey))
                    return;
                __VLS_ctx.closeMenus();
                __VLS_ctx.onNewFolder();
            } },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "flex-1" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
        ...{ class: ({ disabled: !__VLS_ctx.activeTab || !__VLS_ctx.activeTab.dirty }) },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.bootReadyKey))
                    return;
                __VLS_ctx.closeMenus();
                __VLS_ctx.onSave();
            } },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "flex-1" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.kbd, __VLS_intrinsicElements.kbd)({
        ...{ class: "kbd kbd-xs" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
        ...{ class: ({ disabled: !__VLS_ctx.hasDirtyTabs }) },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.bootReadyKey))
                    return;
                __VLS_ctx.closeMenus();
                __VLS_ctx.onSaveAll();
            } },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "flex-1" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div)({
        ...{ class: "divider my-0" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
        ...{ class: ({ disabled: !__VLS_ctx.activeTab }) },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.bootReadyKey))
                    return;
                __VLS_ctx.closeMenus();
                __VLS_ctx.onCloseActiveTab();
            } },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "flex-1" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.kbd, __VLS_intrinsicElements.kbd)({
        ...{ class: "kbd kbd-xs" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div)({
        ...{ class: "divider my-0" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.bootReadyKey))
                    return;
                __VLS_ctx.closeMenus();
                __VLS_ctx.backHome();
            } },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "flex-1" },
    });
    (__VLS_ctx.isCortex ? 'Back to chat' : 'Back to documents');
    if (__VLS_ctx.isCortex) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "dropdown" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            tabindex: "0",
            role: "button",
            ...{ class: "btn btn-ghost btn-xs" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
            tabindex: "0",
            ...{ class: "dropdown-content menu menu-sm bg-base-100 rounded-box z-[20] mt-1 w-64 p-2 shadow" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
            ...{ class: ({ disabled: !__VLS_ctx.activeTab || __VLS_ctx.isActiveTabBound }) },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
            ...{ onClick: (...[$event]) => {
                    if (!(__VLS_ctx.bootReadyKey))
                        return;
                    if (!(__VLS_ctx.isCortex))
                        return;
                    __VLS_ctx.closeMenus();
                    __VLS_ctx.onBindActiveTab();
                } },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "flex-1" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
            ...{ class: ({ disabled: !__VLS_ctx.chatBoundDocumentId }) },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
            ...{ onClick: (...[$event]) => {
                    if (!(__VLS_ctx.bootReadyKey))
                        return;
                    if (!(__VLS_ctx.isCortex))
                        return;
                    __VLS_ctx.closeMenus();
                    __VLS_ctx.onUnbindChat();
                } },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "flex-1" },
        });
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
        ...{ class: "flex-1" },
    });
    if (__VLS_ctx.isCortex) {
        if (__VLS_ctx.clientToolService?.isExecuting.value) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-xs px-2 py-0.5 rounded bg-warning/15 text-warning border border-warning/30 animate-pulse" },
                title: "An agent tool is currently editing the chat-bound document",
            });
        }
        else if (__VLS_ctx.activeTab || __VLS_ctx.chatBoundDocumentId) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (__VLS_ctx.onBindActiveTab) },
                type: "button",
                ...{ class: "\u0074\u0065\u0078\u0074\u002d\u0078\u0073\u0020\u0070\u0078\u002d\u0032\u0020\u0070\u0079\u002d\u0030\u002e\u0035\u0020\u0072\u006f\u0075\u006e\u0064\u0065\u0064\u0020\u0066\u006f\u006e\u0074\u002d\u006d\u006f\u006e\u006f\u0020\u0066\u006c\u0065\u0078\u0020\u0069\u0074\u0065\u006d\u0073\u002d\u0063\u0065\u006e\u0074\u0065\u0072\u0020\u0067\u0061\u0070\u002d\u0031\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0074\u0072\u0061\u006e\u0073\u0069\u0074\u0069\u006f\u006e\u002d\u0063\u006f\u006c\u006f\u0072\u0073\u0020\u0065\u006e\u0061\u0062\u006c\u0065\u0064\u003a\u0068\u006f\u0076\u0065\u0072\u003a\u0062\u0067\u002d\u0062\u0061\u0073\u0065\u002d\u0032\u0030\u0030\u0020\u0064\u0069\u0073\u0061\u0062\u006c\u0065\u0064\u003a\u0063\u0075\u0072\u0073\u006f\u0072\u002d\u0064\u0065\u0066\u0061\u0075\u006c\u0074" },
                ...{ class: (__VLS_ctx.isActiveTabBound ? 'text-primary bg-primary/10' : 'opacity-70') },
                disabled: (!__VLS_ctx.activeTab || __VLS_ctx.isActiveTabBound),
                title: (__VLS_ctx.bindIconTooltip),
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                'aria-hidden': "true",
            });
            if (__VLS_ctx.chatBoundDocumentPathDisplay) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.chatBoundDocumentPathDisplay);
            }
        }
    }
    /** @type {[typeof EditorTabs, ]} */ ;
    // @ts-ignore
    const __VLS_46 = __VLS_asFunctionalComponent(EditorTabs, new EditorTabs({
        ...{ 'onSelect': {} },
        ...{ 'onClose': {} },
        tabs: (__VLS_ctx.store.openTabs),
        activeTabId: (__VLS_ctx.store.activeTabId),
    }));
    const __VLS_47 = __VLS_46({
        ...{ 'onSelect': {} },
        ...{ 'onClose': {} },
        tabs: (__VLS_ctx.store.openTabs),
        activeTabId: (__VLS_ctx.store.activeTabId),
    }, ...__VLS_functionalComponentArgsRest(__VLS_46));
    let __VLS_49;
    let __VLS_50;
    let __VLS_51;
    const __VLS_52 = {
        onSelect: (__VLS_ctx.store.setActiveTab)
    };
    const __VLS_53 = {
        onClose: (__VLS_ctx.store.closeTab)
    };
    var __VLS_48;
    if (__VLS_ctx.saveError) {
        const __VLS_54 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_55 = __VLS_asFunctionalComponent(__VLS_54, new __VLS_54({
            variant: "error",
            ...{ class: "m-2" },
        }));
        const __VLS_56 = __VLS_55({
            variant: "error",
            ...{ class: "m-2" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_55));
        __VLS_57.slots.default;
        (__VLS_ctx.saveError);
        var __VLS_57;
    }
    if (!__VLS_ctx.activeTab) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex-1 flex items-center justify-center" },
        });
        const __VLS_58 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_59 = __VLS_asFunctionalComponent(__VLS_58, new __VLS_58({
            headline: "No document open",
            body: "Pick one from the tree on the left, or create a new file.",
        }));
        const __VLS_60 = __VLS_59({
            headline: "No document open",
            body: "Pick one from the tree on the left, or create a new file.",
        }, ...__VLS_functionalComponentArgsRest(__VLS_59));
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex-1 min-h-0 overflow-hidden" },
        });
        /** @type {[typeof TabRendererHost, ]} */ ;
        // @ts-ignore
        const __VLS_62 = __VLS_asFunctionalComponent(TabRendererHost, new TabRendererHost({
            ...{ 'onUpdate': {} },
            document: (__VLS_ctx.activeTab),
            sessionId: (__VLS_ctx.sessionId),
        }));
        const __VLS_63 = __VLS_62({
            ...{ 'onUpdate': {} },
            document: (__VLS_ctx.activeTab),
            sessionId: (__VLS_ctx.sessionId),
        }, ...__VLS_functionalComponentArgsRest(__VLS_62));
        let __VLS_65;
        let __VLS_66;
        let __VLS_67;
        const __VLS_68 = {
            onUpdate: (__VLS_ctx.store.updateActiveContent)
        };
        var __VLS_64;
    }
    if (__VLS_ctx.isCortex) {
        {
            const { 'right-panel': __VLS_thisSlot } = __VLS_3.slots;
            if (__VLS_ctx.sessionId && __VLS_ctx.projectId && __VLS_ctx.clientToolService) {
                /** @type {[typeof CortexRightPanel, ]} */ ;
                // @ts-ignore
                const __VLS_69 = __VLS_asFunctionalComponent(CortexRightPanel, new CortexRightPanel({
                    sessionId: (__VLS_ctx.sessionId),
                    projectId: (__VLS_ctx.projectId),
                    toolService: (__VLS_ctx.clientToolService),
                    activeDocument: (__VLS_ctx.activeTab),
                }));
                const __VLS_70 = __VLS_69({
                    sessionId: (__VLS_ctx.sessionId),
                    projectId: (__VLS_ctx.projectId),
                    toolService: (__VLS_ctx.clientToolService),
                    activeDocument: (__VLS_ctx.activeTab),
                }, ...__VLS_functionalComponentArgsRest(__VLS_69));
            }
            else {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "h-full p-3 text-sm opacity-60" },
                });
            }
        }
    }
    var __VLS_3;
}
/** @type {[typeof CreateDocumentModal, ]} */ ;
// @ts-ignore
const __VLS_72 = __VLS_asFunctionalComponent(CreateDocumentModal, new CreateDocumentModal({
    ...{ 'onConfirm': {} },
    open: (__VLS_ctx.showCreate),
    projectId: (__VLS_ctx.projectId),
    initialPath: (__VLS_ctx.createPrefill?.path ?? ''),
    consumeDraft: (!__VLS_ctx.isCortex),
}));
const __VLS_73 = __VLS_72({
    ...{ 'onConfirm': {} },
    open: (__VLS_ctx.showCreate),
    projectId: (__VLS_ctx.projectId),
    initialPath: (__VLS_ctx.createPrefill?.path ?? ''),
    consumeDraft: (!__VLS_ctx.isCortex),
}, ...__VLS_functionalComponentArgsRest(__VLS_72));
let __VLS_75;
let __VLS_76;
let __VLS_77;
const __VLS_78 = {
    onConfirm: (__VLS_ctx.onCreateConfirm)
};
var __VLS_74;
/** @type {[typeof NewFolderModal, ]} */ ;
// @ts-ignore
const __VLS_79 = __VLS_asFunctionalComponent(NewFolderModal, new NewFolderModal({
    ...{ 'onConfirm': {} },
    open: (__VLS_ctx.showNewFolder),
    initialPath: (__VLS_ctx.newFolderInitial),
}));
const __VLS_80 = __VLS_79({
    ...{ 'onConfirm': {} },
    open: (__VLS_ctx.showNewFolder),
    initialPath: (__VLS_ctx.newFolderInitial),
}, ...__VLS_functionalComponentArgsRest(__VLS_79));
let __VLS_82;
let __VLS_83;
let __VLS_84;
const __VLS_85 = {
    onConfirm: (__VLS_ctx.onNewFolderConfirm)
};
var __VLS_81;
/** @type {__VLS_StyleScopedClasses['mr-2']} */ ;
/** @type {__VLS_StyleScopedClasses['inline-flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-info']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['select-none']} */ ;
/** @type {__VLS_StyleScopedClasses['animate-pulse']} */ ;
/** @type {__VLS_StyleScopedClasses['mr-2']} */ ;
/** @type {__VLS_StyleScopedClasses['inline-flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/70']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['select-none']} */ ;
/** @type {__VLS_StyleScopedClasses['text-success']} */ ;
/** @type {__VLS_StyleScopedClasses['mr-3']} */ ;
/** @type {__VLS_StyleScopedClasses['inline-flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-md']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-warning/40']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-warning/15']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['text-warning-content']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-warning/50']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-warning/30']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-warning/50']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-warning/30']} */ ;
/** @type {__VLS_StyleScopedClasses['mr-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['m-2']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['dropdown']} */ ;
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-ghost']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['dropdown-content']} */ ;
/** @type {__VLS_StyleScopedClasses['menu']} */ ;
/** @type {__VLS_StyleScopedClasses['menu-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-box']} */ ;
/** @type {__VLS_StyleScopedClasses['z-[20]']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['w-56']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['shadow']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['disabled']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['kbd']} */ ;
/** @type {__VLS_StyleScopedClasses['kbd-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['disabled']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['divider']} */ ;
/** @type {__VLS_StyleScopedClasses['my-0']} */ ;
/** @type {__VLS_StyleScopedClasses['disabled']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['kbd']} */ ;
/** @type {__VLS_StyleScopedClasses['kbd-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['divider']} */ ;
/** @type {__VLS_StyleScopedClasses['my-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['dropdown']} */ ;
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-ghost']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['dropdown-content']} */ ;
/** @type {__VLS_StyleScopedClasses['menu']} */ ;
/** @type {__VLS_StyleScopedClasses['menu-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-box']} */ ;
/** @type {__VLS_StyleScopedClasses['z-[20]']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['w-64']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['shadow']} */ ;
/** @type {__VLS_StyleScopedClasses['disabled']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['disabled']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-warning/15']} */ ;
/** @type {__VLS_StyleScopedClasses['text-warning']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-warning/30']} */ ;
/** @type {__VLS_StyleScopedClasses['animate-pulse']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['enabled:hover:bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['disabled:cursor-default']} */ ;
/** @type {__VLS_StyleScopedClasses['m-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            EditorShell: EditorShell,
            VAlert: VAlert,
            VButton: VButton,
            VEmptyState: VEmptyState,
            DocumentPresenceStrip: DocumentPresenceStrip,
            FileTreeSidebar: FileTreeSidebar,
            EditorTabs: EditorTabs,
            TabRendererHost: TabRendererHost,
            CortexRightPanel: CortexRightPanel,
            CreateDocumentModal: CreateDocumentModal,
            NewFolderModal: NewFolderModal,
            isCortex: isCortex,
            sessionId: sessionId,
            projectId: projectId,
            chatBoundDocumentId: chatBoundDocumentId,
            focusZone: focusZone,
            store: store,
            bootError: bootError,
            saveError: saveError,
            showCreate: showCreate,
            createPrefill: createPrefill,
            showNewFolder: showNewFolder,
            newFolderInitial: newFolderInitial,
            title: title,
            breadcrumbs: breadcrumbs,
            activeTab: activeTab,
            isAgentEditing: isAgentEditing,
            activePendingChange: activePendingChange,
            activeRecentEditor: activeRecentEditor,
            keepLocalForActive: keepLocalForActive,
            acceptRemoteForActive: acceptRemoteForActive,
            hasDirtyTabs: hasDirtyTabs,
            isActiveTabBound: isActiveTabBound,
            chatBoundDocumentPathDisplay: chatBoundDocumentPathDisplay,
            bindIconTooltip: bindIconTooltip,
            clientToolService: clientToolService,
            onSave: onSave,
            onNew: onNew,
            onCreateConfirm: onCreateConfirm,
            onNewFolder: onNewFolder,
            onNewFolderConfirm: onNewFolderConfirm,
            onDelete: onDelete,
            treeError: treeError,
            onMoveFile: onMoveFile,
            onUploadFiles: onUploadFiles,
            backHome: backHome,
            onSaveAll: onSaveAll,
            onCloseActiveTab: onCloseActiveTab,
            onBindActiveTab: onBindActiveTab,
            onUnbindChat: onUnbindChat,
            closeMenus: closeMenus,
            backLabel: backLabel,
            bootReadyKey: bootReadyKey,
        };
    },
    __typeProps: {},
    props: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeProps: {},
    props: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=EditorApp.vue.js.map