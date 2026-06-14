import { computed, onBeforeUnmount, onMounted, provide, ref, watch } from 'vue';
import { EditorShell, VAlert, VANCE_LINK_HANDLER_KEY, VButton, VEmptyState, VInput, VModal, } from '@/components';
import { brainFetch } from '@vance/shared';
import { useTenantProjects } from '@composables/useTenantProjects';
import { useCortexStore } from './stores/cortexStore';
import { CortexClientToolService } from './clientToolService';
import FileTreeSidebar from './components/FileTreeSidebar.vue';
import EditorTabs from './components/EditorTabs.vue';
import TabRendererHost from './components/TabRendererHost.vue';
import CortexRightPanel from './components/CortexRightPanel.vue';
// sessionId is mandatory — without it, send the user back to chat.html
// where they can pick or create a session. Cortex never operates without
// a chat session as its anchor (see planning/cortex.md §4.2).
const sessionId = ref(null);
const sessionTitle = ref(null);
const projectId = ref(null);
const chatBoundDocumentId = ref(null);
const focusZone = ref('main');
const store = useCortexStore();
// Hijack vance:-doc links inside any descendant MarkdownView (chat
// bubbles, help panel, …) so a plain click opens the document as a
// Cortex tab instead of navigating away from the page. Cmd/Ctrl-click
// is left untouched by MarkdownView itself so the user can always pop
// out into a documents.html tab. Cross-project refs fall through to
// the default jump — Cortex can only host files from the session's
// own project.
const onVanceLink = async ({ documentId, projectId, newTab }) => {
    if (newTab)
        return false;
    if (!store.projectId || projectId !== store.projectId)
        return false;
    focusZone.value = 'main';
    try {
        await store.openFile(documentId);
    }
    catch (e) {
        console.warn('Cortex: failed to open vance: link in editor', e);
        return false; // let the default navigation kick in as a fallback
    }
    return true;
};
provide(VANCE_LINK_HANDLER_KEY, onVanceLink);
// Tenant project list — used to resolve the human-readable project
// title for the breadcrumb. Loaded lazily once we know which session
// (and therefore which project) the user is looking at.
const { projects: tenantProjects, reload: loadTenantProjects } = useTenantProjects();
const bootError = ref(null);
const saving = ref(false);
const saveError = ref(null);
const showCreate = ref(false);
/** Directory portion of the new file's path — editable, prefilled
 *  from the active tab's folder when the dialog opens. Empty means
 *  project root. Trailing slash is normalised away in {@link confirmCreate}. */
const createDir = ref('');
const createName = ref('');
const createError = ref(null);
const creating = ref(false);
// "New folder" dialog state. The folder is purely client-side — see
// {@code cortexStore.addVirtualFolder} — so there's no async / saving
// state, just a single editable path field.
const showNewFolder = ref(false);
const newFolderPath = ref('');
const newFolderError = ref(null);
// True while initial state restoration is running. While set, the
// state-persistence watcher is muted — otherwise the per-{@code openFile}
// rebuild would echo the restored state straight back to the server.
const restoring = ref(false);
onMounted(async () => {
    const params = new URLSearchParams(window.location.search);
    const id = params.get('sessionId');
    if (!id) {
        window.location.replace('/chat.html');
        return;
    }
    sessionId.value = id;
    await resolveSession(id);
});
/**
 * Resolve the session's projectId via the sessions list endpoint, then
 * trigger the cortex store to load that project's documents. The list
 * is owner-scoped so the user only sees their own sessions; if id is
 * not found we surface a recoverable error rather than redirecting.
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
 * Re-open the tabs and chat-bind that the user had open the last time
 * Cortex was active for this session. Failures on individual document
 * loads are swallowed (probably deleted in the meantime); the user
 * sees a partial restore rather than a hard error.
 */
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
        // Initial active tab — URL `doc` param wins (the user navigated
        // here or hit back/forward to land on a specific document),
        // otherwise fall back to the chat-bound document so the user lands
        // where the agent is working.
        const urlDoc = readDocFromUrl();
        if (urlDoc && store.openTabs.some((t) => t.id === urlDoc)) {
            store.setActiveTab(urlDoc);
        }
        else if (bound && store.openTabs.some((t) => t.id === bound)) {
            store.setActiveTab(bound);
        }
        // Normalise the URL so the active tab is reflected even when the
        // user landed on a bare `?sessionId=…` — replaceState (not push)
        // because this is the natural entry point, not a navigation.
        replaceDocInUrl(store.activeTabId ?? null);
    }
    finally {
        restoring.value = false;
    }
}
/**
 * Push the current Cortex state (open tabs + chat-bind) to the server.
 * Fire-and-forget: errors are logged but don't surface in the UI —
 * worst case the user's tab layout doesn't persist for the next visit.
 *
 * <p>Watched via {@link openTabIds}/{@link chatBoundDocumentId} below.
 */
async function persistCortexState() {
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
// Bind chat to the first opened tab automatically if nothing is bound
// yet — common path for fresh sessions where the user opens their
// first document. Lets the chat have an arbeitsdoc without an explicit
// "bind here" click.
watch(() => store.openTabs.map((t) => t.id).join(','), (idsKey) => {
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
    if (sessionTitle.value)
        return `Cortex · ${sessionTitle.value}`;
    return 'Cortex';
});
// Human-readable project label: prefer the title from the tenant
// project list, fall back to the technical id while the list is still
// loading so the breadcrumb never appears blank.
const projectLabel = computed(() => {
    const id = projectId.value;
    if (!id)
        return null;
    const p = tenantProjects.value.find((x) => x.name === id);
    const title = p?.title?.trim();
    return title && title.length > 0 ? title : id;
});
const breadcrumbs = computed(() => {
    const crumbs = [];
    if (projectLabel.value)
        crumbs.push(projectLabel.value);
    if (store.activeTab?.path)
        crumbs.push(store.activeTab.path);
    return crumbs;
});
// ──────────────── URL sync for active document tab ────────────────
//
// The active tab is mirrored into a `doc=<documentId>` query parameter
// so that browser back/forward steps walk through the user's tab
// history. On every tab switch we {@code pushState} a new entry; the
// {@link onPopState} handler reverses the mapping when the user uses
// the browser's nav arrows. {@code suppressHistoryPush} breaks the
// otherwise infinite watcher⇄popstate loop.
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
    // History may carry a doc that is no longer open (closed in the
    // meantime) — ignore those entries rather than fighting the user's
    // navigation; their next tab switch will re-sync the URL.
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
const chatBoundDocumentPath = computed(() => {
    const id = chatBoundDocumentId.value;
    if (!id)
        return null;
    const tab = store.openTabs.find((t) => t.id === id);
    return tab?.path ?? null;
});
const hasDirtyTabs = computed(() => store.openTabs.some((t) => t.dirty));
const isActiveTabBound = computed(() => activeTab.value !== null && chatBoundDocumentId.value === activeTab.value.id);
/**
 * Truncated form of the bound document's path for the menu-bar status
 * area — leading directories collapse to ellipses so a deeply-nested
 * path doesn't push the rest of the bar off-screen.
 */
const chatBoundDocumentPathDisplay = computed(() => {
    const p = chatBoundDocumentPath.value;
    if (!p)
        return null;
    const MAX = 32;
    if (p.length <= MAX)
        return p;
    return '…' + p.slice(p.length - (MAX - 1));
});
/**
 * Hover text for the menu-bar link button — describes what a click
 * will do or, when the button is disabled (already bound to the active
 * tab), just states the current binding.
 */
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
 * The client-tool surface Cortex exposes to the LLM agent. Single
 * instance for the lifetime of this app — it's wired to the chat
 * panel's WS as soon as a session goes live, and re-uses the same
 * registration across reconnects. The {@code getBoundDocument} getter
 * always resolves against the current store + bind state, so tool
 * invocations see the latest text and chat-bind without forcing a
 * re-registration on every tab switch.
 */
const clientToolService = new CortexClientToolService({
    getBoundDocument: () => {
        const id = chatBoundDocumentId.value;
        if (!id)
            return null;
        return store.openTabs.find((t) => t.id === id) ?? null;
    },
    getSelection: () => store.currentSelection,
    getActiveTab: () => {
        const tab = store.activeTab;
        if (!tab)
            return null;
        return { documentId: tab.id, path: tab.path };
    },
    openFileByPath: async (path) => {
        // Path is the project-relative file path the agent sees in
        // {@code cortex_read} results or in the file tree. Resolve to a
        // documentId via {@link store.files}; on a miss we return null so
        // the tool surfaces a clear "no such file" error to the LLM.
        const normalised = path.replace(/^\/+/, '');
        const file = store.files.find((f) => f.path === normalised);
        if (!file)
            return null;
        const alreadyOpen = store.openTabs.some((t) => t.id === file.id);
        focusZone.value = 'main';
        await store.openFile(file.id);
        return { documentId: file.id, path: file.path, alreadyOpen };
    },
});
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
    // leaving the field blank (which would land the file in the project
    // root). Path stays editable so the user can rewrite either default.
    const ref = activeTab.value;
    if (ref) {
        const idx = ref.path.lastIndexOf('/');
        createDir.value = idx >= 0 ? ref.path.slice(0, idx) : '';
    }
    else {
        createDir.value = 'documents';
    }
    createName.value = '';
    createError.value = null;
    showCreate.value = true;
}
async function confirmCreate() {
    const name = createName.value.trim();
    if (!name) {
        createError.value = 'Name required';
        return;
    }
    if (name.includes('/')) {
        createError.value = 'Name must not contain "/" — put folders in the path field.';
        return;
    }
    const dir = createDir.value.trim().replace(/^\/+|\/+$/g, '');
    const fullPath = dir ? `${dir}/${name}` : name;
    creating.value = true;
    createError.value = null;
    try {
        await store.createFile({
            path: fullPath,
            inlineText: '',
        });
        showCreate.value = false;
    }
    catch (e) {
        createError.value = e instanceof Error ? e.message : 'Create failed';
    }
    finally {
        creating.value = false;
    }
}
function onNewFolder() {
    // Prefill with the active tab's folder so "make a sibling
    // sub-folder" is the easy case. Without an active tab, suggest
    // `documents` for the same reason {@link onNew} does.
    const ref = activeTab.value;
    if (ref) {
        const idx = ref.path.lastIndexOf('/');
        newFolderPath.value = idx >= 0 ? ref.path.slice(0, idx) : '';
    }
    else {
        newFolderPath.value = 'documents';
    }
    newFolderError.value = null;
    showNewFolder.value = true;
}
function confirmNewFolder() {
    const path = newFolderPath.value.trim().replace(/^\/+|\/+$/g, '');
    if (!path) {
        newFolderError.value = 'Path required';
        return;
    }
    store.addVirtualFolder(path);
    showNewFolder.value = false;
}
async function onDelete(id) {
    if (!confirm('Delete this document?'))
        return;
    await store.deleteFile(id);
}
// ──────────────── File-tree drag & drop ────────────────
//
// {@link treeError} surfaces above the tree when a move / upload fails
// (path conflict, network error). Cleared by every successful op so it
// doesn't linger from an unrelated previous attempt.
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
        return; // no-op: dropped into the source folder
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
function backToChat() {
    if (sessionId.value) {
        window.location.href = `/chat.html?sessionId=${encodeURIComponent(sessionId.value)}`;
    }
    else {
        window.location.href = '/chat.html';
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
/**
 * Closes any open dropdown by removing focus from its trigger. Daisy's
 * CSS-only dropdown stays open as long as the trigger or any child
 * holds focus — clicking a menu item doesn't naturally blur. Call this
 * at the start of every menu action so the menu collapses afterwards.
 */
function closeMenus() {
    const el = document.activeElement;
    if (el instanceof HTMLElement)
        el.blur();
}
// Keep the document title in sync so the browser tab is informative
// without forcing the user to read the in-app topbar.
watch(title, (t) => {
    document.title = t;
}, { immediate: true });
// ─── Auto-save with debounce ────────────────────────────────────────
//
// Whenever any open tab's inlineText changes, kick a 2-second timer.
// If nothing else changes in that window we flush every dirty tab to
// the server. Per planning/cortex.md §4.5: Auto-Save mit Debounce.
// Tab-switch / Cortex-close / page-unload flush synchronously below.
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
// Force-flush dirty tabs the user is navigating away from — switching
// to a different tab inside Cortex shouldn't lose unsaved work waiting
// on the debounce. The previous tab is the one that was active before
// the change; if it's dirty, save it now.
watch(() => store.activeTabId, (_curr, prev) => {
    if (!prev || restoring.value)
        return;
    const previousTab = store.openTabs.find((t) => t.id === prev);
    if (previousTab?.dirty) {
        void store.saveTab(prev);
    }
});
// Mirror the active tab to the URL so browser back/forward step
// through it. Skipped while {@link restoring} is true (initial restore
// chooses the start point via {@link replaceDocInUrl} once) and when a
// popstate event already drove the change (no double-push).
watch(() => store.activeTabId, (curr) => {
    if (restoring.value)
        return;
    if (suppressHistoryPush) {
        suppressHistoryPush = false;
        return;
    }
    pushDocToUrl(curr ?? null);
});
/**
 * beforeunload guard — if a user closes the tab while edits are still
 * unsaved (debounce hasn't fired, network failed silently, etc.), warn
 * via the browser dialog so they can cancel. Modern browsers ignore
 * the custom message but show their generic "Are you sure?" prompt as
 * long as we set {@code returnValue}.
 *
 * <p>We deliberately do NOT try to fire a fetch on unload — the
 * browser cancels in-flight requests on navigation and partial writes
 * are worse than no writes. Users either confirm and risk losing the
 * last 2s of edits, or cancel and let the debounce flush.
 */
function onBeforeUnload(e) {
    if (store.openTabs.some((t) => t.dirty)) {
        e.preventDefault();
        e.returnValue = '';
    }
}
/**
 * Ctrl/Cmd-S — explicit save shortcut. Beats waiting for the debounce
 * and matches what every editor user expects.
 */
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
    // Best-effort final flush. The browser may cancel the in-flight
    // request — beforeunload already warned the user if anything was
    // dirty, so a lost write is a "user accepted that risk" outcome.
    void store.saveAllDirty();
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
// CSS variable injection 
// CSS variable injection end 
if (__VLS_ctx.sessionId) {
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
        showRightPanel: (true),
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
        showRightPanel: (true),
        titleClickable: true,
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    let __VLS_4;
    let __VLS_5;
    let __VLS_6;
    const __VLS_7 = {
        onTitleClick: (...[$event]) => {
            if (!(__VLS_ctx.sessionId))
                return;
            __VLS_ctx.focusZone = 'sidebar';
        }
    };
    __VLS_3.slots.default;
    {
        const { sidebar: __VLS_thisSlot } = __VLS_3.slots;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex flex-col h-full min-h-0" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "p-3 border-b border-base-300 shrink-0 flex items-center gap-2" },
        });
        const __VLS_8 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
            ...{ 'onClick': {} },
            size: "sm",
            variant: "ghost",
        }));
        const __VLS_10 = __VLS_9({
            ...{ 'onClick': {} },
            size: "sm",
            variant: "ghost",
        }, ...__VLS_functionalComponentArgsRest(__VLS_9));
        let __VLS_12;
        let __VLS_13;
        let __VLS_14;
        const __VLS_15 = {
            onClick: (__VLS_ctx.backToChat)
        };
        __VLS_11.slots.default;
        var __VLS_11;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex-1 min-h-0 overflow-y-auto" },
        });
        if (__VLS_ctx.treeError) {
            const __VLS_16 = {}.VAlert;
            /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
            // @ts-ignore
            const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
                variant: "error",
                ...{ class: "m-2" },
            }));
            const __VLS_18 = __VLS_17({
                variant: "error",
                ...{ class: "m-2" },
            }, ...__VLS_functionalComponentArgsRest(__VLS_17));
            __VLS_19.slots.default;
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            (__VLS_ctx.treeError);
            var __VLS_19;
        }
        if (__VLS_ctx.projectId) {
            /** @type {[typeof FileTreeSidebar, ]} */ ;
            // @ts-ignore
            const __VLS_20 = __VLS_asFunctionalComponent(FileTreeSidebar, new FileTreeSidebar({
                ...{ 'onOpenFile': {} },
                ...{ 'onDeleteFile': {} },
                ...{ 'onMoveFile': {} },
                ...{ 'onUploadFiles': {} },
                root: (__VLS_ctx.store.fileTree),
                activeFileId: (__VLS_ctx.store.activeTabId),
            }));
            const __VLS_21 = __VLS_20({
                ...{ 'onOpenFile': {} },
                ...{ 'onDeleteFile': {} },
                ...{ 'onMoveFile': {} },
                ...{ 'onUploadFiles': {} },
                root: (__VLS_ctx.store.fileTree),
                activeFileId: (__VLS_ctx.store.activeTabId),
            }, ...__VLS_functionalComponentArgsRest(__VLS_20));
            let __VLS_23;
            let __VLS_24;
            let __VLS_25;
            const __VLS_26 = {
                onOpenFile: ((id) => { __VLS_ctx.focusZone = 'main'; __VLS_ctx.store.openFile(id); })
            };
            const __VLS_27 = {
                onDeleteFile: (__VLS_ctx.onDelete)
            };
            const __VLS_28 = {
                onMoveFile: (__VLS_ctx.onMoveFile)
            };
            const __VLS_29 = {
                onUploadFiles: (__VLS_ctx.onUploadFiles)
            };
            var __VLS_22;
        }
        else if (__VLS_ctx.bootError) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "p-3 text-sm" },
            });
            const __VLS_30 = {}.VAlert;
            /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
            // @ts-ignore
            const __VLS_31 = __VLS_asFunctionalComponent(__VLS_30, new __VLS_30({
                variant: "error",
            }));
            const __VLS_32 = __VLS_31({
                variant: "error",
            }, ...__VLS_functionalComponentArgsRest(__VLS_31));
            __VLS_33.slots.default;
            (__VLS_ctx.bootError);
            var __VLS_33;
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
                if (!(__VLS_ctx.sessionId))
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
                if (!(__VLS_ctx.sessionId))
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
                if (!(__VLS_ctx.sessionId))
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
                if (!(__VLS_ctx.sessionId))
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
                if (!(__VLS_ctx.sessionId))
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
                if (!(__VLS_ctx.sessionId))
                    return;
                __VLS_ctx.closeMenus();
                __VLS_ctx.backToChat();
            } },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "flex-1" },
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
        ...{ class: "dropdown-content menu menu-sm bg-base-100 rounded-box z-[20] mt-1 w-64 p-2 shadow" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
        ...{ class: ({ disabled: !__VLS_ctx.activeTab || __VLS_ctx.isActiveTabBound }) },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.sessionId))
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
                if (!(__VLS_ctx.sessionId))
                    return;
                __VLS_ctx.closeMenus();
                __VLS_ctx.onUnbindChat();
            } },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "flex-1" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
        ...{ class: "flex-1" },
    });
    if (__VLS_ctx.clientToolService.isExecuting.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-xs px-2 py-0.5 rounded bg-warning/15 text-warning border border-warning/30 animate-pulse" },
            title: "An agent tool is currently editing the chat-bound document",
        });
    }
    else if (__VLS_ctx.activeTab || __VLS_ctx.chatBoundDocumentId) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (__VLS_ctx.onBindActiveTab) },
            type: "button",
            ...{ class: "\u0074\u0065\u0078\u0074\u002d\u0078\u0073\u0020\u0070\u0078\u002d\u0032\u0020\u0070\u0079\u002d\u0030\u002e\u0035\u0020\u0072\u006f\u0075\u006e\u0064\u0065\u0064\u0020\u0066\u006f\u006e\u0074\u002d\u006d\u006f\u006e\u006f\u0020\u0066\u006c\u0065\u0078\u0020\u0069\u0074\u0065\u006d\u0073\u002d\u0063\u0065\u006e\u0074\u0065\u0072\u0020\u0067\u0061\u0070\u002d\u0031\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0074\u0072\u0061\u006e\u0073\u0069\u0074\u0069\u006f\u006e\u002d\u0063\u006f\u006c\u006f\u0072\u0073\u0020\u0065\u006e\u0061\u0062\u006c\u0065\u0064\u003a\u0068\u006f\u0076\u0065\u0072\u003a\u0062\u0067\u002d\u0062\u0061\u0073\u0065\u002d\u0032\u0030\u0030\u0020\u0064\u0069\u0073\u0061\u0062\u006c\u0065\u0064\u003a\u0063\u0075\u0072\u0073\u006f\u0072\u002d\u0064\u0065\u0066\u0061\u0075\u006c\u0074" },
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
    /** @type {[typeof EditorTabs, ]} */ ;
    // @ts-ignore
    const __VLS_34 = __VLS_asFunctionalComponent(EditorTabs, new EditorTabs({
        ...{ 'onSelect': {} },
        ...{ 'onClose': {} },
        tabs: (__VLS_ctx.store.openTabs),
        activeTabId: (__VLS_ctx.store.activeTabId),
    }));
    const __VLS_35 = __VLS_34({
        ...{ 'onSelect': {} },
        ...{ 'onClose': {} },
        tabs: (__VLS_ctx.store.openTabs),
        activeTabId: (__VLS_ctx.store.activeTabId),
    }, ...__VLS_functionalComponentArgsRest(__VLS_34));
    let __VLS_37;
    let __VLS_38;
    let __VLS_39;
    const __VLS_40 = {
        onSelect: (__VLS_ctx.store.setActiveTab)
    };
    const __VLS_41 = {
        onClose: (__VLS_ctx.store.closeTab)
    };
    var __VLS_36;
    if (__VLS_ctx.saveError) {
        const __VLS_42 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_43 = __VLS_asFunctionalComponent(__VLS_42, new __VLS_42({
            variant: "error",
            ...{ class: "m-2" },
        }));
        const __VLS_44 = __VLS_43({
            variant: "error",
            ...{ class: "m-2" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_43));
        __VLS_45.slots.default;
        (__VLS_ctx.saveError);
        var __VLS_45;
    }
    if (!__VLS_ctx.activeTab) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex-1 flex items-center justify-center" },
        });
        const __VLS_46 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_47 = __VLS_asFunctionalComponent(__VLS_46, new __VLS_46({
            headline: "No document open",
            body: "Pick one from the tree on the left, or create a new file.",
        }));
        const __VLS_48 = __VLS_47({
            headline: "No document open",
            body: "Pick one from the tree on the left, or create a new file.",
        }, ...__VLS_functionalComponentArgsRest(__VLS_47));
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex-1 min-h-0 overflow-hidden" },
        });
        /** @type {[typeof TabRendererHost, ]} */ ;
        // @ts-ignore
        const __VLS_50 = __VLS_asFunctionalComponent(TabRendererHost, new TabRendererHost({
            ...{ 'onUpdate': {} },
            document: (__VLS_ctx.activeTab),
            sessionId: (__VLS_ctx.sessionId),
        }));
        const __VLS_51 = __VLS_50({
            ...{ 'onUpdate': {} },
            document: (__VLS_ctx.activeTab),
            sessionId: (__VLS_ctx.sessionId),
        }, ...__VLS_functionalComponentArgsRest(__VLS_50));
        let __VLS_53;
        let __VLS_54;
        let __VLS_55;
        const __VLS_56 = {
            onUpdate: (__VLS_ctx.store.updateActiveContent)
        };
        var __VLS_52;
    }
    {
        const { 'right-panel': __VLS_thisSlot } = __VLS_3.slots;
        if (__VLS_ctx.sessionId && __VLS_ctx.projectId) {
            /** @type {[typeof CortexRightPanel, ]} */ ;
            // @ts-ignore
            const __VLS_57 = __VLS_asFunctionalComponent(CortexRightPanel, new CortexRightPanel({
                sessionId: (__VLS_ctx.sessionId),
                projectId: (__VLS_ctx.projectId),
                toolService: (__VLS_ctx.clientToolService),
                activeDocument: (__VLS_ctx.activeTab),
            }));
            const __VLS_58 = __VLS_57({
                sessionId: (__VLS_ctx.sessionId),
                projectId: (__VLS_ctx.projectId),
                toolService: (__VLS_ctx.clientToolService),
                activeDocument: (__VLS_ctx.activeTab),
            }, ...__VLS_functionalComponentArgsRest(__VLS_57));
        }
        else {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "h-full p-3 text-sm opacity-60" },
            });
        }
    }
    var __VLS_3;
}
const __VLS_60 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_61 = __VLS_asFunctionalComponent(__VLS_60, new __VLS_60({
    modelValue: (__VLS_ctx.showCreate),
    title: "New document",
}));
const __VLS_62 = __VLS_61({
    modelValue: (__VLS_ctx.showCreate),
    title: "New document",
}, ...__VLS_functionalComponentArgsRest(__VLS_61));
__VLS_63.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.form, __VLS_intrinsicElements.form)({
    ...{ onSubmit: (__VLS_ctx.confirmCreate) },
    ...{ class: "space-y-3 p-2" },
});
const __VLS_64 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_65 = __VLS_asFunctionalComponent(__VLS_64, new __VLS_64({
    modelValue: (__VLS_ctx.createDir),
    label: "Path",
    placeholder: "(project root)",
}));
const __VLS_66 = __VLS_65({
    modelValue: (__VLS_ctx.createDir),
    label: "Path",
    placeholder: "(project root)",
}, ...__VLS_functionalComponentArgsRest(__VLS_65));
const __VLS_68 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_69 = __VLS_asFunctionalComponent(__VLS_68, new __VLS_68({
    modelValue: (__VLS_ctx.createName),
    label: "Name",
    placeholder: "idea.md",
    disabled: (__VLS_ctx.creating),
}));
const __VLS_70 = __VLS_69({
    modelValue: (__VLS_ctx.createName),
    label: "Name",
    placeholder: "idea.md",
    disabled: (__VLS_ctx.creating),
}, ...__VLS_functionalComponentArgsRest(__VLS_69));
if (__VLS_ctx.createError) {
    const __VLS_72 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_73 = __VLS_asFunctionalComponent(__VLS_72, new __VLS_72({
        variant: "error",
    }));
    const __VLS_74 = __VLS_73({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_73));
    __VLS_75.slots.default;
    (__VLS_ctx.createError);
    var __VLS_75;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex justify-end gap-2 pt-2" },
});
const __VLS_76 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_77 = __VLS_asFunctionalComponent(__VLS_76, new __VLS_76({
    ...{ 'onClick': {} },
    type: "button",
    variant: "ghost",
}));
const __VLS_78 = __VLS_77({
    ...{ 'onClick': {} },
    type: "button",
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_77));
let __VLS_80;
let __VLS_81;
let __VLS_82;
const __VLS_83 = {
    onClick: (...[$event]) => {
        __VLS_ctx.showCreate = false;
    }
};
__VLS_79.slots.default;
var __VLS_79;
const __VLS_84 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_85 = __VLS_asFunctionalComponent(__VLS_84, new __VLS_84({
    type: "submit",
    variant: "primary",
    loading: (__VLS_ctx.creating),
}));
const __VLS_86 = __VLS_85({
    type: "submit",
    variant: "primary",
    loading: (__VLS_ctx.creating),
}, ...__VLS_functionalComponentArgsRest(__VLS_85));
__VLS_87.slots.default;
var __VLS_87;
var __VLS_63;
const __VLS_88 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_89 = __VLS_asFunctionalComponent(__VLS_88, new __VLS_88({
    modelValue: (__VLS_ctx.showNewFolder),
    title: "New folder",
}));
const __VLS_90 = __VLS_89({
    modelValue: (__VLS_ctx.showNewFolder),
    title: "New folder",
}, ...__VLS_functionalComponentArgsRest(__VLS_89));
__VLS_91.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.form, __VLS_intrinsicElements.form)({
    ...{ onSubmit: (__VLS_ctx.confirmNewFolder) },
    ...{ class: "space-y-3 p-2" },
});
const __VLS_92 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_93 = __VLS_asFunctionalComponent(__VLS_92, new __VLS_92({
    modelValue: (__VLS_ctx.newFolderPath),
    label: "Folder path",
    placeholder: "documents/notes",
}));
const __VLS_94 = __VLS_93({
    modelValue: (__VLS_ctx.newFolderPath),
    label: "Folder path",
    placeholder: "documents/notes",
}, ...__VLS_functionalComponentArgsRest(__VLS_93));
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
    ...{ class: "text-xs opacity-60" },
});
if (__VLS_ctx.newFolderError) {
    const __VLS_96 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_97 = __VLS_asFunctionalComponent(__VLS_96, new __VLS_96({
        variant: "error",
    }));
    const __VLS_98 = __VLS_97({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_97));
    __VLS_99.slots.default;
    (__VLS_ctx.newFolderError);
    var __VLS_99;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex justify-end gap-2 pt-2" },
});
const __VLS_100 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_101 = __VLS_asFunctionalComponent(__VLS_100, new __VLS_100({
    ...{ 'onClick': {} },
    type: "button",
    variant: "ghost",
}));
const __VLS_102 = __VLS_101({
    ...{ 'onClick': {} },
    type: "button",
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_101));
let __VLS_104;
let __VLS_105;
let __VLS_106;
const __VLS_107 = {
    onClick: (...[$event]) => {
        __VLS_ctx.showNewFolder = false;
    }
};
__VLS_103.slots.default;
var __VLS_103;
const __VLS_108 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_109 = __VLS_asFunctionalComponent(__VLS_108, new __VLS_108({
    type: "submit",
    variant: "primary",
}));
const __VLS_110 = __VLS_109({
    type: "submit",
    variant: "primary",
}, ...__VLS_functionalComponentArgsRest(__VLS_109));
__VLS_111.slots.default;
var __VLS_111;
var __VLS_91;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
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
/** @type {__VLS_StyleScopedClasses['space-y-3']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['pt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['space-y-3']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['pt-2']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            EditorShell: EditorShell,
            VAlert: VAlert,
            VButton: VButton,
            VEmptyState: VEmptyState,
            VInput: VInput,
            VModal: VModal,
            FileTreeSidebar: FileTreeSidebar,
            EditorTabs: EditorTabs,
            TabRendererHost: TabRendererHost,
            CortexRightPanel: CortexRightPanel,
            sessionId: sessionId,
            projectId: projectId,
            chatBoundDocumentId: chatBoundDocumentId,
            focusZone: focusZone,
            store: store,
            bootError: bootError,
            saveError: saveError,
            showCreate: showCreate,
            createDir: createDir,
            createName: createName,
            createError: createError,
            creating: creating,
            showNewFolder: showNewFolder,
            newFolderPath: newFolderPath,
            newFolderError: newFolderError,
            title: title,
            breadcrumbs: breadcrumbs,
            activeTab: activeTab,
            hasDirtyTabs: hasDirtyTabs,
            isActiveTabBound: isActiveTabBound,
            chatBoundDocumentPathDisplay: chatBoundDocumentPathDisplay,
            bindIconTooltip: bindIconTooltip,
            clientToolService: clientToolService,
            onSave: onSave,
            onNew: onNew,
            confirmCreate: confirmCreate,
            onNewFolder: onNewFolder,
            confirmNewFolder: confirmNewFolder,
            onDelete: onDelete,
            treeError: treeError,
            onMoveFile: onMoveFile,
            onUploadFiles: onUploadFiles,
            backToChat: backToChat,
            onSaveAll: onSaveAll,
            onCloseActiveTab: onCloseActiveTab,
            onBindActiveTab: onBindActiveTab,
            onUnbindChat: onUnbindChat,
            closeMenus: closeMenus,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=CortexApp.vue.js.map