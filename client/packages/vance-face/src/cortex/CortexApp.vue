<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, provide, ref, watch } from 'vue';
import {
  type Crumb,
  EditorShell,
  type FocusZone,
  VAlert,
  VANCE_LINK_HANDLER_KEY,
  type VanceLinkHandler,
  VButton,
  VEmptyState,
  VInput,
  VModal,
} from '@/components';
import { brainFetch } from '@vance/shared';
import type { SessionSummaryRichDto } from '@vance/generated';
import { useTenantProjects } from '@composables/useTenantProjects';
import DocumentPresenceStrip from '@/ws/DocumentPresenceStrip.vue';
import { useCortexStore } from './stores/cortexStore';
import { CortexClientToolService } from './clientToolService';
import FileTreeSidebar from './components/FileTreeSidebar.vue';
import EditorTabs from './components/EditorTabs.vue';
import TabRendererHost from './components/TabRendererHost.vue';
import CortexRightPanel from './components/CortexRightPanel.vue';

// The generated SessionSummaryRichDto picks up these fields on the next
// {@code mvn install} of the api module. Until then, augment locally so
// the rest of this file stays type-safe and the regen is a no-op diff.
type CortexAwareSessionSummary = SessionSummaryRichDto & {
  openDocumentIds?: string[];
  chatBoundDocumentId?: string | null;
};

interface CortexStateBody {
  openDocumentIds: string[];
  chatBoundDocumentId: string | null;
}

// sessionId is mandatory — without it, send the user back to chat.html
// where they can pick or create a session. Cortex never operates without
// a chat session as its anchor (see planning/cortex.md §4.2).
const sessionId = ref<string | null>(null);
const sessionTitle = ref<string | null>(null);
const projectId = ref<string | null>(null);
const chatBoundDocumentId = ref<string | null>(null);
const focusZone = ref<FocusZone>('main');

const store = useCortexStore();

// Hijack vance:-doc links inside any descendant MarkdownView (chat
// bubbles, help panel, …) so a plain click opens the document as a
// Cortex tab instead of navigating away from the page. Cmd/Ctrl-click
// is left untouched by MarkdownView itself so the user can always pop
// out into a documents.html tab. Cross-project refs fall through to
// the default jump — Cortex can only host files from the session's
// own project.
const onVanceLink: VanceLinkHandler = async ({ documentId, projectId, newTab }) => {
  if (newTab) return false;
  if (!store.projectId || projectId !== store.projectId) return false;
  focusZone.value = 'main';
  try {
    await store.openFile(documentId);
  } catch (e) {
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

const bootError = ref<string | null>(null);
const saving = ref(false);
const saveError = ref<string | null>(null);

const showCreate = ref(false);
/** Directory portion of the new file's path — editable, prefilled
 *  from the active tab's folder when the dialog opens. Empty means
 *  project root. Trailing slash is normalised away in {@link confirmCreate}. */
const createDir = ref('');
const createName = ref('');
const createError = ref<string | null>(null);
const creating = ref(false);

// "New folder" dialog state. The folder is purely client-side — see
// {@code cortexStore.addVirtualFolder} — so there's no async / saving
// state, just a single editable path field.
const showNewFolder = ref(false);
const newFolderPath = ref('');
const newFolderError = ref<string | null>(null);

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
async function resolveSession(id: string): Promise<void> {
  try {
    const sessions = await brainFetch<CortexAwareSessionSummary[]>('GET', 'sessions');
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
  } catch (e) {
    bootError.value = e instanceof Error ? e.message : 'Failed to load session.';
  }
}

/**
 * Re-open the tabs and chat-bind that the user had open the last time
 * Cortex was active for this session. Failures on individual document
 * loads are swallowed (probably deleted in the meantime); the user
 * sees a partial restore rather than a hard error.
 */
async function restoreCortexState(summary: CortexAwareSessionSummary): Promise<void> {
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
      } catch {
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
    } else if (bound && store.openTabs.some((t) => t.id === bound)) {
      store.setActiveTab(bound);
    }
    // Normalise the URL so the active tab is reflected even when the
    // user landed on a bare `?sessionId=…` — replaceState (not push)
    // because this is the natural entry point, not a navigation.
    replaceDocInUrl(store.activeTabId ?? null);
  } finally {
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
async function persistCortexState(): Promise<void> {
  if (!sessionId.value || restoring.value) return;
  const body: CortexStateBody = {
    openDocumentIds: store.openTabs.map((t) => t.id),
    chatBoundDocumentId: chatBoundDocumentId.value,
  };
  try {
    await brainFetch<void>(
      'PUT',
      `sessions/${encodeURIComponent(sessionId.value)}/cortex-state`,
      { body },
    );
  } catch (e) {
    console.warn('Failed to persist Cortex state', e);
  }
}

// Bind chat to the first opened tab automatically if nothing is bound
// yet — common path for fresh sessions where the user opens their
// first document. Lets the chat have an arbeitsdoc without an explicit
// "bind here" click.
watch(
  () => store.openTabs.map((t) => t.id).join(','),
  (idsKey) => {
    if (restoring.value) return;
    const ids = idsKey ? idsKey.split(',') : [];
    if (ids.length === 0) {
      if (chatBoundDocumentId.value !== null) chatBoundDocumentId.value = null;
    } else if (
      chatBoundDocumentId.value === null
      || !ids.includes(chatBoundDocumentId.value)
    ) {
      chatBoundDocumentId.value = ids[0];
    }
    void persistCortexState();
  },
);

watch(chatBoundDocumentId, () => {
  void persistCortexState();
});

const title = computed<string>(() => {
  if (sessionTitle.value) return `Cortex · ${sessionTitle.value}`;
  return 'Cortex';
});

// Human-readable project label: prefer the title from the tenant
// project list, fall back to the technical id while the list is still
// loading so the breadcrumb never appears blank.
const projectLabel = computed<string | null>(() => {
  const id = projectId.value;
  if (!id) return null;
  const p = tenantProjects.value.find((x) => x.name === id);
  const title = p?.title?.trim();
  return title && title.length > 0 ? title : id;
});

/**
 * Jumps from Cortex back to the chat picker with the current project
 * pre-selected — mirrors the chat-mode breadcrumb behaviour (see
 * {@code ChatApp.goToPickerWithProject}). Full navigation rather than
 * {@code pushState} because Cortex and chat are separate MPA entries.
 */
function goToChatPickerWithProject(): void {
  const id = projectId.value;
  if (!id) return;
  window.location.href = `/chat.html?project=${encodeURIComponent(id)}`;
}

const breadcrumbs = computed<Crumb[]>(() => {
  const crumbs: Crumb[] = [];
  if (projectLabel.value && projectId.value) {
    crumbs.push({ text: projectLabel.value, onClick: goToChatPickerWithProject });
  } else if (projectLabel.value) {
    crumbs.push(projectLabel.value);
  }
  if (store.activeTab?.path) crumbs.push(store.activeTab.path);
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

function readDocFromUrl(): string | null {
  const params = new URLSearchParams(window.location.search);
  return params.get(URL_DOC_PARAM);
}

function buildUrlWithDoc(docId: string | null): string {
  const params = new URLSearchParams(window.location.search);
  if (docId) params.set(URL_DOC_PARAM, docId);
  else params.delete(URL_DOC_PARAM);
  const query = params.toString();
  return `${window.location.pathname}${query ? `?${query}` : ''}`;
}

function pushDocToUrl(docId: string | null): void {
  const next = buildUrlWithDoc(docId);
  if (next === `${window.location.pathname}${window.location.search}`) return;
  window.history.pushState({ doc: docId }, '', next);
}

function replaceDocInUrl(docId: string | null): void {
  const next = buildUrlWithDoc(docId);
  if (next === `${window.location.pathname}${window.location.search}`) return;
  window.history.replaceState({ doc: docId }, '', next);
}

function onPopState(): void {
  const urlDoc = readDocFromUrl();
  // History may carry a doc that is no longer open (closed in the
  // meantime) — ignore those entries rather than fighting the user's
  // navigation; their next tab switch will re-sync the URL.
  if (urlDoc && !store.openTabs.some((t) => t.id === urlDoc)) return;
  if ((urlDoc ?? null) === (store.activeTabId ?? null)) return;
  suppressHistoryPush = true;
  if (urlDoc) {
    store.setActiveTab(urlDoc);
  }
}

const activeTab = computed(() => store.activeTab);

const chatBoundDocumentPath = computed<string | null>(() => {
  const id = chatBoundDocumentId.value;
  if (!id) return null;
  const tab = store.openTabs.find((t) => t.id === id);
  return tab?.path ?? null;
});

const hasDirtyTabs = computed<boolean>(() => store.openTabs.some((t) => t.dirty));

const isActiveTabBound = computed<boolean>(() =>
  activeTab.value !== null && chatBoundDocumentId.value === activeTab.value.id,
);

/**
 * Truncated form of the bound document's path for the menu-bar status
 * area — leading directories collapse to ellipses so a deeply-nested
 * path doesn't push the rest of the bar off-screen.
 */
const chatBoundDocumentPathDisplay = computed<string | null>(() => {
  const p = chatBoundDocumentPath.value;
  if (!p) return null;
  const MAX = 32;
  if (p.length <= MAX) return p;
  return '…' + p.slice(p.length - (MAX - 1));
});

/**
 * Hover text for the menu-bar link button — describes what a click
 * will do or, when the button is disabled (already bound to the active
 * tab), just states the current binding.
 */
const bindIconTooltip = computed<string>(() => {
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
    if (!id) return null;
    return store.openTabs.find((t) => t.id === id) ?? null;
  },
  getSelection: () => store.currentSelection,
  getActiveTab: () => {
    const tab = store.activeTab;
    if (!tab) return null;
    return { documentId: tab.id, path: tab.path };
  },
  openFileByPath: async (path) => {
    // Path is the project-relative file path the agent sees in
    // {@code cortex_read} results or in the file tree. Resolve to a
    // documentId via {@link store.files}; on a miss we return null so
    // the tool surfaces a clear "no such file" error to the LLM.
    const normalised = path.replace(/^\/+/, '');
    const file = store.files.find((f) => f.path === normalised);
    if (!file) return null;
    const alreadyOpen = store.openTabs.some((t) => t.id === file.id);
    focusZone.value = 'main';
    await store.openFile(file.id);
    return { documentId: file.id, path: file.path, alreadyOpen };
  },
});

async function onSave(): Promise<void> {
  if (!activeTab.value) return;
  saving.value = true;
  saveError.value = null;
  try {
    await store.saveActive();
  } catch (e) {
    saveError.value = e instanceof Error ? e.message : 'Save failed';
  } finally {
    saving.value = false;
  }
}

function onNew(): void {
  // Prefill the directory from the currently active tab so "New file…"
  // in the same folder is one-click. Without an active tab, suggest
  // `documents` — the conventional user-content folder — instead of
  // leaving the field blank (which would land the file in the project
  // root). Path stays editable so the user can rewrite either default.
  const ref = activeTab.value;
  if (ref) {
    const idx = ref.path.lastIndexOf('/');
    createDir.value = idx >= 0 ? ref.path.slice(0, idx) : '';
  } else {
    createDir.value = 'documents';
  }
  createName.value = '';
  createError.value = null;
  showCreate.value = true;
}

async function confirmCreate(): Promise<void> {
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
  } catch (e) {
    createError.value = e instanceof Error ? e.message : 'Create failed';
  } finally {
    creating.value = false;
  }
}

function onNewFolder(): void {
  // Prefill with the active tab's folder so "make a sibling
  // sub-folder" is the easy case. Without an active tab, suggest
  // `documents` for the same reason {@link onNew} does.
  const ref = activeTab.value;
  if (ref) {
    const idx = ref.path.lastIndexOf('/');
    newFolderPath.value = idx >= 0 ? ref.path.slice(0, idx) : '';
  } else {
    newFolderPath.value = 'documents';
  }
  newFolderError.value = null;
  showNewFolder.value = true;
}

function confirmNewFolder(): void {
  const path = newFolderPath.value.trim().replace(/^\/+|\/+$/g, '');
  if (!path) {
    newFolderError.value = 'Path required';
    return;
  }
  store.addVirtualFolder(path);
  showNewFolder.value = false;
}

async function onDelete(id: string): Promise<void> {
  if (!confirm('Delete this document?')) return;
  await store.deleteFile(id);
}

// ──────────────── File-tree drag & drop ────────────────
//
// {@link treeError} surfaces above the tree when a move / upload fails
// (path conflict, network error). Cleared by every successful op so it
// doesn't linger from an unrelated previous attempt.
const treeError = ref<string | null>(null);

async function onMoveFile(payload: { id: string; targetFolder: string }): Promise<void> {
  const doc = store.files.find((f) => f.id === payload.id)
    ?? store.openTabs.find((t) => t.id === payload.id);
  if (!doc) return;
  const slash = doc.path.lastIndexOf('/');
  const basename = slash === -1 ? doc.path : doc.path.slice(slash + 1);
  const newPath = payload.targetFolder
    ? `${payload.targetFolder}/${basename}`
    : basename;
  if (newPath === doc.path) return; // no-op: dropped into the source folder
  treeError.value = null;
  try {
    await store.moveFile(payload.id, newPath);
  } catch (e) {
    treeError.value = e instanceof Error ? e.message : 'Move failed';
  }
}

async function onUploadFiles(payload: { files: File[]; targetFolder: string }): Promise<void> {
  treeError.value = null;
  const failures: string[] = [];
  for (const file of payload.files) {
    try {
      await store.uploadExternalFile(file, payload.targetFolder);
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Upload failed';
      failures.push(`${file.name}: ${msg}`);
    }
  }
  if (failures.length > 0) {
    treeError.value = failures.join('\n');
  }
}

function backToChat(): void {
  if (sessionId.value) {
    const params = new URLSearchParams();
    params.set('sessionId', sessionId.value);
    // Carry the project id over so ChatApp can seed
    // {@link documentRefStore.currentProject} before the historical
    // messages mount — otherwise the WS session-list lookup races the
    // first `vance:`-link in chat history and EmbeddedKindBox fails
    // with "No project context to resolve vance: URI".
    if (projectId.value) params.set('project', projectId.value);
    window.location.href = `/chat.html?${params.toString()}`;
  } else {
    window.location.href = '/chat.html';
  }
}

async function onSaveAll(): Promise<void> {
  saving.value = true;
  saveError.value = null;
  try {
    await store.saveAllDirty();
  } catch (e) {
    saveError.value = e instanceof Error ? e.message : 'Save failed';
  } finally {
    saving.value = false;
  }
}

function onCloseActiveTab(): void {
  if (!activeTab.value) return;
  store.closeTab(activeTab.value.id);
}

function onBindActiveTab(): void {
  if (!activeTab.value) return;
  chatBoundDocumentId.value = activeTab.value.id;
}

function onUnbindChat(): void {
  chatBoundDocumentId.value = null;
}

/**
 * Closes any open dropdown by removing focus from its trigger. Daisy's
 * CSS-only dropdown stays open as long as the trigger or any child
 * holds focus — clicking a menu item doesn't naturally blur. Call this
 * at the start of every menu action so the menu collapses afterwards.
 */
function closeMenus(): void {
  const el = document.activeElement;
  if (el instanceof HTMLElement) el.blur();
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
let autoSaveTimer: ReturnType<typeof setTimeout> | null = null;

function scheduleAutoSave(): void {
  if (autoSaveTimer !== null) clearTimeout(autoSaveTimer);
  autoSaveTimer = setTimeout(() => {
    autoSaveTimer = null;
    void store.saveAllDirty();
  }, AUTO_SAVE_DEBOUNCE_MS);
}

watch(
  () => store.openTabs.map((t) => `${t.id}:${t.inlineText.length}:${t.dirty ? 1 : 0}`).join('|'),
  () => {
    if (restoring.value) return;
    if (store.openTabs.some((t) => t.dirty)) {
      scheduleAutoSave();
    }
  },
);

// Force-flush dirty tabs the user is navigating away from — switching
// to a different tab inside Cortex shouldn't lose unsaved work waiting
// on the debounce. The previous tab is the one that was active before
// the change; if it's dirty, save it now.
watch(
  () => store.activeTabId,
  (_curr, prev) => {
    if (!prev || restoring.value) return;
    const previousTab = store.openTabs.find((t) => t.id === prev);
    if (previousTab?.dirty) {
      void store.saveTab(prev);
    }
  },
);

// Mirror the active tab to the URL so browser back/forward step
// through it. Skipped while {@link restoring} is true (initial restore
// chooses the start point via {@link replaceDocInUrl} once) and when a
// popstate event already drove the change (no double-push).
watch(
  () => store.activeTabId,
  (curr) => {
    if (restoring.value) return;
    if (suppressHistoryPush) {
      suppressHistoryPush = false;
      return;
    }
    pushDocToUrl(curr ?? null);
  },
);

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
function onBeforeUnload(e: BeforeUnloadEvent): void {
  if (store.openTabs.some((t) => t.dirty)) {
    e.preventDefault();
    e.returnValue = '';
  }
}

/**
 * Ctrl/Cmd-S — explicit save shortcut. Beats waiting for the debounce
 * and matches what every editor user expects.
 */
function onKeyDown(e: KeyboardEvent): void {
  const cmd = e.metaKey || e.ctrlKey;
  if (!cmd) return;
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
</script>

<template>
  <EditorShell
    v-if="sessionId"
    v-model:focus-zone="focusZone"
    :title="title"
    :breadcrumbs="breadcrumbs"
    :full-height="true"
    focus-model="auto"
    :show-sidebar="true"
    :show-right-panel="true"
    title-clickable
    @title-click="focusZone = 'sidebar'"
  >
    <!-- Topbar action: jump back to the plain chat view for this
         session. Mirrors the {@code Open Cortex →} button in
         {@link ChatApp.vue} so the reverse navigation lives in the
         same screen region (top-right of the header) and the two
         views stay visually symmetrical. -->
    <template #topbar-extra>
      <DocumentPresenceStrip
        v-if="activeTab?.path"
        :path="activeTab.path"
        class="mr-2"
      />
      <VButton size="sm" variant="ghost" @click="backToChat">← Chat</VButton>
    </template>

    <template #sidebar>
      <div class="flex flex-col h-full min-h-0">
        <div class="flex-1 min-h-0 overflow-y-auto">
          <VAlert v-if="treeError" variant="error" class="m-2">
            <span>{{ treeError }}</span>
          </VAlert>
          <FileTreeSidebar
            v-if="projectId"
            :root="store.fileTree"
            :active-file-id="store.activeTabId"
            @open-file="(id: string) => { focusZone = 'main'; store.openFile(id); }"
            @delete-file="onDelete"
            @move-file="onMoveFile"
            @upload-files="onUploadFiles"
          />
          <div v-else-if="bootError" class="p-3 text-sm">
            <VAlert variant="error">{{ bootError }}</VAlert>
          </div>
          <div v-else class="p-3 text-sm opacity-60">
            Loading…
          </div>
        </div>
      </div>
    </template>

    <div class="flex flex-col h-full min-h-0">
      <!-- Menu bar — sits above tabs so it stays put when the tab strip
           scrolls horizontally. CSS-only Daisy dropdowns: clicking a
           menu item calls closeMenus() to blur the trigger so the menu
           collapses (Daisy keeps it open while any descendant has
           focus). New action groups land here as the app grows. -->
      <div class="flex items-center gap-1 px-2 py-1 border-b border-base-300 bg-base-200 text-sm shrink-0">
        <div class="dropdown">
          <div tabindex="0" role="button" class="btn btn-ghost btn-xs">File</div>
          <ul tabindex="0" class="dropdown-content menu menu-sm bg-base-100 rounded-box z-[20] mt-1 w-56 p-2 shadow">
            <li>
              <a @click="closeMenus(); onNew()">
                <span class="flex-1">New file…</span>
              </a>
            </li>
            <li>
              <a @click="closeMenus(); onNewFolder()">
                <span class="flex-1">New folder…</span>
              </a>
            </li>
            <li :class="{ disabled: !activeTab || !activeTab.dirty }">
              <a @click="closeMenus(); onSave()">
                <span class="flex-1">Save</span>
                <kbd class="kbd kbd-xs">⌘S</kbd>
              </a>
            </li>
            <li :class="{ disabled: !hasDirtyTabs }">
              <a @click="closeMenus(); onSaveAll()">
                <span class="flex-1">Save all</span>
              </a>
            </li>
            <li><div class="divider my-0" /></li>
            <li :class="{ disabled: !activeTab }">
              <a @click="closeMenus(); onCloseActiveTab()">
                <span class="flex-1">Close tab</span>
                <kbd class="kbd kbd-xs">⌘W</kbd>
              </a>
            </li>
            <li><div class="divider my-0" /></li>
            <li>
              <a @click="closeMenus(); backToChat()">
                <span class="flex-1">Back to chat</span>
              </a>
            </li>
          </ul>
        </div>

        <div class="dropdown">
          <div tabindex="0" role="button" class="btn btn-ghost btn-xs">Chat</div>
          <ul tabindex="0" class="dropdown-content menu menu-sm bg-base-100 rounded-box z-[20] mt-1 w-64 p-2 shadow">
            <li :class="{ disabled: !activeTab || isActiveTabBound }">
              <a @click="closeMenus(); onBindActiveTab()">
                <span class="flex-1">Bind chat to current tab</span>
              </a>
            </li>
            <li :class="{ disabled: !chatBoundDocumentId }">
              <a @click="closeMenus(); onUnbindChat()">
                <span class="flex-1">Unbind chat</span>
              </a>
            </li>
          </ul>
        </div>

        <span class="flex-1" />

        <!-- Status area: agent activity wins visually over the static
             bound-doc indicator, since "something is happening" needs
             more attention than "where it would happen". -->
        <span
          v-if="clientToolService.isExecuting.value"
          class="text-xs px-2 py-0.5 rounded bg-warning/15 text-warning border border-warning/30 animate-pulse"
          title="An agent tool is currently editing the chat-bound document"
        >agent editing…</span>
        <button
          v-else-if="activeTab || chatBoundDocumentId"
          type="button"
          class="text-xs px-2 py-0.5 rounded font-mono flex items-center gap-1
                 transition-colors enabled:hover:bg-base-200 disabled:cursor-default"
          :class="isActiveTabBound ? 'text-primary bg-primary/10' : 'opacity-70'"
          :disabled="!activeTab || isActiveTabBound"
          :title="bindIconTooltip"
          @click="onBindActiveTab"
        >
          <span aria-hidden="true">🔗</span>
          <span v-if="chatBoundDocumentPathDisplay">{{ chatBoundDocumentPathDisplay }}</span>
        </button>
      </div>

      <EditorTabs
        :tabs="store.openTabs"
        :active-tab-id="store.activeTabId"
        @select="store.setActiveTab"
        @close="store.closeTab"
      />

      <VAlert v-if="saveError" variant="error" class="m-2">{{ saveError }}</VAlert>

      <div v-if="!activeTab" class="flex-1 flex items-center justify-center">
        <VEmptyState
          headline="No document open"
          body="Pick one from the tree on the left, or create a new file."
        />
      </div>

      <div v-else class="flex-1 min-h-0 overflow-hidden">
        <TabRendererHost
          :document="activeTab"
          :session-id="sessionId"
          @update="store.updateActiveContent"
        />
      </div>
    </div>

    <template #right-panel>
      <CortexRightPanel
        v-if="sessionId && projectId"
        :session-id="sessionId"
        :project-id="projectId"
        :tool-service="clientToolService"
        :active-document="activeTab"
      />
      <div v-else class="h-full p-3 text-sm opacity-60">
        Waiting for session…
      </div>
    </template>
  </EditorShell>

  <VModal v-model="showCreate" title="New document">
    <form class="space-y-3 p-2" @submit.prevent="confirmCreate">
      <VInput
        v-model="createDir"
        label="Path"
        placeholder="(project root)"
      />
      <VInput
        v-model="createName"
        label="Name"
        placeholder="idea.md"
        :disabled="creating"
      />
      <VAlert v-if="createError" variant="error">{{ createError }}</VAlert>
      <div class="flex justify-end gap-2 pt-2">
        <VButton type="button" variant="ghost" @click="showCreate = false">Cancel</VButton>
        <VButton type="submit" variant="primary" :loading="creating">Create</VButton>
      </div>
    </form>
  </VModal>

  <VModal v-model="showNewFolder" title="New folder">
    <form class="space-y-3 p-2" @submit.prevent="confirmNewFolder">
      <VInput
        v-model="newFolderPath"
        label="Folder path"
        placeholder="documents/notes"
      />
      <p class="text-xs opacity-60">
        Folders are virtual until a file lives in them — this entry is
        client-side and disappears on refresh unless something gets
        moved into it.
      </p>
      <VAlert v-if="newFolderError" variant="error">{{ newFolderError }}</VAlert>
      <div class="flex justify-end gap-2 pt-2">
        <VButton type="button" variant="ghost" @click="showNewFolder = false">Cancel</VButton>
        <VButton type="submit" variant="primary">Create</VButton>
      </div>
    </form>
  </VModal>
</template>

<style>
/* Cortex-specific responsive override (intentionally unscoped — Cortex
 * is its own MPA entry, so this CSS only ships with cortex.html).
 *
 * On iPad-class viewports (≤1366px, covers iPad Pro 13" landscape down
 * to iPad Mini portrait), collapse the sidebar to 0 whenever the
 * centre, right panel, or footer is focused. Cortex carries three
 * dense zones — file tree + document editor + chat panel — and the
 * baseline EditorShell rule (sidebar fixed at 16rem until ≤900px)
 * leaves the centre too narrow on tablet sizes. The reclaim handle
 * keeps the sidebar one tap away. */
@media (max-width: 1366px) {
  .editor-shell-grid[data-focus='main']:not([data-help-open]),
  .editor-shell-grid[data-focus='right']:not([data-help-open]),
  .editor-shell-grid[data-focus='footer']:not([data-help-open]) {
    --shell-sidebar-w: 0;
  }
}
</style>
