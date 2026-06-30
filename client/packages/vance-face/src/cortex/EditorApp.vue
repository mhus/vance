<script setup lang="ts">
/**
 * Unified editor surface for {@code cortex.html}. Two boot modes are
 * driven purely by URL params:
 *
 *  - With {@code ?sessionId=…}: session-bound. Resolves session →
 *    project, restores open-tabs from the session document, mounts
 *    the chat right-panel + {@code CortexClientToolService} tool
 *    surface, enables the bind-icon and chat-bound persistence.
 *  - With {@code ?project=…} (no sessionId): chatless. Project comes
 *    from URL, optional {@code ?doc=…} deep-link, optional
 *    {@code ?path=…&create=1} to auto-open the create-document modal.
 *    Right-panel collapses by default; the user can toggle it open
 *    (session-picker content lands here later).
 *
 * The right-panel toggle is always available regardless of mode —
 * a stupid show/hide switch on top of the slot.
 */
import { computed, onBeforeUnmount, onMounted, provide, ref, watch, type Ref } from 'vue';
import {
  type Crumb,
  EditorShell,
  type FocusZone,
  VAlert,
  VANCE_LINK_HANDLER_KEY,
  type VanceLinkHandler,
  VButton,
  VEmptyState,
} from '@/components';
import { brainFetch } from '@vance/shared';
import type { SessionSummaryRichDto } from '@vance/generated';
import { useTenantProjects } from '@composables/useTenantProjects';
import DocumentPresenceStrip from '@/ws/DocumentPresenceStrip.vue';
import { brainFetchText } from '@vance/shared';
import {
  isAudioVideoMime,
  tryThreeWayMerge,
} from '@/composables/useDocumentChangeReaction';
import { onDocumentChanged } from '@/ws/wsConnectionStore';
import VanceEmbedView from '@/components/VanceEmbedView.vue';
import VanceFormView from '@/components/VanceFormView.vue';
import { useDocumentRefStore } from '@/document/documentRefStore';
import { isBinaryMime } from './stores/cortexStore';
import { useCortexStore } from './stores/cortexStore';
import { useViewEditMode } from './useViewEditMode';
import { CortexClientToolService } from './clientToolService';
import { useDocumentInvalidate } from './composables/useDocumentInvalidate';
import FileTreeSidebar from './components/FileTreeSidebar.vue';
import EditorTabs from './components/EditorTabs.vue';
import TabRendererHost from './components/TabRendererHost.vue';
import CortexRightPanel from './components/CortexRightPanel.vue';
import SessionPickerPanel from './components/SessionPickerPanel.vue';
import CreateDocumentModal, {
  type CreateModalResult,
} from './components/CreateDocumentModal.vue';
import NewFolderModal from './components/NewFolderModal.vue';

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

// Session-id is read from the URL synchronously so the rest of the
// script setup can branch on it (e.g. {@link clientToolService}). Boot
// still happens in {@link onMounted}.
const initialSessionId = new URLSearchParams(window.location.search).get('sessionId');
const sessionId = ref<string | null>(initialSessionId);
const sessionTitle = ref<string | null>(null);
const projectId = ref<string | null>(null);
const chatBoundDocumentId = ref<string | null>(null);
const focusZone = ref<FocusZone>('main');

// Session-bound mode = there's a sessionId on this tab. Used everywhere
// the chat-bound logic, persistence, bind-icon, etc. needs to gate.
const hasSession = computed(() => sessionId.value !== null);

// Right-panel visibility — stupid show/hide toggle. Default is open
// when chat is available, closed otherwise. The toggle works regardless
// of state; when no session is bound the slot stays empty (session-
// picker lands in phase 2).
const rightPanelOpen = ref(hasSession.value);

const store = useCortexStore();

// Hijack vance:-doc links inside any descendant MarkdownView so a plain
// click opens the document as a tab instead of navigating away. Both
// modes benefit — chatless mode just doesn't have a chat bubble to do
// this for. Cross-project refs fall through to the default jump.
const onVanceLink: VanceLinkHandler = async ({ documentId, projectId: refProjectId, newTab }) => {
  if (newTab) return false;
  if (!store.projectId || refProjectId !== store.projectId) return false;
  focusZone.value = 'main';
  try {
    await store.openFile(documentId);
  } catch (e) {
    console.warn('Editor: failed to open vance: link in editor', e);
    return false;
  }
  return true;
};
provide(VANCE_LINK_HANDLER_KEY, onVanceLink);

// Expose the embed-renderer to module-federation remotes (canvas
// editor inside the workspace addon, etc.) via a string-keyed
// provide. The remote can `inject('vance:embed-component', null)`
// without importing vance-face directly. The component takes a single
// `uri` prop and renders the full kind-aware EmbeddedKindBox.
provide('vance:embed-component', VanceEmbedView);

// Same federation-bridge pattern for the editable form block: the
// remote injects 'vance:form-component' and mounts it with the
// edit-config `config` URI as the only prop.
provide('vance:form-component', VanceFormView);

const { projects: tenantProjects, reload: loadTenantProjects } = useTenantProjects();

const bootError = ref<string | null>(null);
const saving = ref(false);
const saveError = ref<string | null>(null);

const showCreate = ref(false);
const createPrefill = ref<{ path: string } | null>(null);

const showNewFolder = ref(false);
const newFolderInitial = ref('');

// True while initial state restoration is running. While set, the
// state-persistence watcher is muted — otherwise the per-{@code openFile}
// rebuild would echo the restored state straight back to the server.
const restoring = ref(false);

onMounted(async () => {
  const params = new URLSearchParams(window.location.search);
  const pid = params.get('project') ?? params.get('projectId');
  if (sessionId.value) {
    await resolveSession(sessionId.value);
  } else if (pid) {
    await resolveProject(pid);
    // Optional URL-driven actions for the explorer → chatless handoff.
    const createPath = params.get('path');
    if (params.get('create') === '1') {
      createPrefill.value = { path: createPath ?? '' };
      showCreate.value = true;
    }
    const urlDoc = readDocFromUrl();
    if (urlDoc) {
      try {
        await store.openFile(urlDoc);
      } catch {
        // doc id might be stale (deleted, moved); stay on empty state.
      }
    }
  } else {
    window.location.replace('/documents.html');
  }
});

/**
 * Cortex bootstrap: resolve project via the session list, restore
 * open-tabs from the session document. The list is owner-scoped so
 * the user only sees their own sessions; if id is not found we surface
 * a recoverable error rather than redirecting.
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
    // See resolveProject() — seed the documentRefStore so embed
    // NodeViews can resolve `vance:` URIs without an explicit project
    // authority.
    useDocumentRefStore().setCurrentProject(match.projectId);
    void loadTenantProjects();
    await store.loadList(match.projectId);
    await restoreCortexState(match);
  } catch (e) {
    bootError.value = e instanceof Error ? e.message : 'Failed to load session.';
  }
}

/**
 * Chatless bootstrap: project is given via URL — no session involved.
 * Tab state is ephemeral by design (deep-links via {@code ?doc=…} cover
 * the bookmark case; we don't persist multi-tab state to the server).
 */
async function resolveProject(pid: string): Promise<void> {
  try {
    projectId.value = pid;
    // Seed the documentRefStore so any embedded `vance:` URI inside
    // a doc (workspace embed-block, chat history loaded later, …) can
    // resolve without an explicit authority. Without this, the embed
    // NodeView fails with "No project context to resolve vance: URI".
    useDocumentRefStore().setCurrentProject(pid);
    void loadTenantProjects();
    await store.loadList(pid);
  } catch (e) {
    bootError.value = e instanceof Error ? e.message : 'Failed to load project.';
  }
}

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
    const urlDoc = readDocFromUrl();
    if (urlDoc && store.openTabs.some((t) => t.id === urlDoc)) {
      store.setActiveTab(urlDoc);
    } else if (bound && store.openTabs.some((t) => t.id === bound)) {
      store.setActiveTab(bound);
    }
    replaceDocInUrl(store.activeTabId ?? null);
  } finally {
    restoring.value = false;
  }
}

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

// Bind chat to the first opened tab automatically — session-mode only.
watch(
  () => store.openTabs.map((t) => t.id).join(','),
  (idsKey) => {
    if (!hasSession.value) return;
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
  if (hasSession.value && sessionTitle.value) return `Cortex · ${sessionTitle.value}`;
  if (projectLabel.value) return `Cortex · ${projectLabel.value}`;
  return 'Cortex';
});

const projectLabel = computed<string | null>(() => {
  const id = projectId.value;
  if (!id) return null;
  const p = tenantProjects.value.find((x) => x.name === id);
  const titleStr = p?.title?.trim();
  return titleStr && titleStr.length > 0 ? titleStr : id;
});

/**
 * Session mode: jump back to chat picker with project pre-selected.
 * Chatless mode: jump back to documents.html explorer with the same
 * project selected.
 */
function onProjectCrumbClick(): void {
  const id = projectId.value;
  if (!id) return;
  if (hasSession.value) {
    window.location.href = `/chat.html?project=${encodeURIComponent(id)}`;
  } else {
    window.location.href = `/documents.html?projectId=${encodeURIComponent(id)}`;
  }
}

const breadcrumbs = computed<Crumb[]>(() => {
  const crumbs: Crumb[] = [];
  if (projectLabel.value && projectId.value) {
    crumbs.push({ text: projectLabel.value, onClick: onProjectCrumbClick });
  } else if (projectLabel.value) {
    crumbs.push(projectLabel.value);
  }
  if (store.activeTab?.path) crumbs.push(store.activeTab.path);
  return crumbs;
});

// ──────────────── URL sync for active document tab ────────────────
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
  if (urlDoc && !store.openTabs.some((t) => t.id === urlDoc)) return;
  if ((urlDoc ?? null) === (store.activeTabId ?? null)) return;
  suppressHistoryPush = true;
  if (urlDoc) {
    store.setActiveTab(urlDoc);
  }
}

const activeTab = computed(() => store.activeTab);

const viewEditMode = useViewEditMode();

// True when the active tab is a kind: application document (an
// `_app.yaml` manifest of a folder-level app) AND the user is in App
// view-mode. In that case we suppress the file-tree sidebar so the App
// has more horizontal room; the chat right-panel and tabs strip stay.
// Clicking the slim header's Edit toggle flips viewEditMode to 'edit',
// which brings the sidebar back so the user can navigate while patching
// the raw YAML manifest. The App itself owns any deeper "fullscreen"
// toggle (Slideshow already does this internally).
const isAppTab = computed<boolean>(() =>
  (activeTab.value?.kind ?? '').toLowerCase() === 'application'
    && viewEditMode.value === 'view',
);

// ──────────────── Live document-change reactions ────────────────
interface TabReaction {
  path: string;
  pendingChange: Ref<string | null>;
  recentEditor: Ref<{ displayName: string; setAt: number } | null>;
  unsubscribe: () => void;
  fadeTimer: ReturnType<typeof setTimeout> | null;
}

const tabReactions = new Map<string, TabReaction>();
const tabReactionRevision = ref(0);
const RECENT_EDITOR_TTL_MS = 2500;

function tryApplyForTab(
  tabId: string,
  notification: { kind?: string },
): Promise<boolean> {
  const tab = store.openTabs.find((t) => t.id === tabId);
  if (!tab) {
    return Promise.resolve(true);
  }
  return runTryApply(tab, notification.kind ?? 'upserted');
}

async function runTryApply(
  tab: ReturnType<typeof store.openTabs.find> & object,
  _kind: string,
): Promise<boolean> {
  const mime = tab.mimeType ?? '';
  if (isAudioVideoMime(mime)) {
    return false;
  }
  if (!isBinaryMime(mime) && tab.dirty) {
    const remoteText = await brainFetchText(
      `documents/${encodeURIComponent(tab.id)}/content`,
    );
    if (remoteText === null) {
      return false;
    }
    const outcome = tryThreeWayMerge(
      tab.baselineInlineText,
      tab.inlineText,
      remoteText,
    );
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

function attachTabReaction(tab: ReturnType<typeof store.openTabs.find> & object): void {
  const pending: Ref<string | null> = ref(null);
  const recent: Ref<{ displayName: string; setAt: number } | null> = ref(null);
  const path = tab.path;
  const reaction: TabReaction = {
    path,
    pendingChange: pending,
    recentEditor: recent,
    unsubscribe: () => {},
    fadeTimer: null,
  };
  reaction.unsubscribe = onDocumentChanged(path, async (notification) => {
    const kind = notification.kind ?? 'upserted';
    try {
      const handled = await tryApplyForTab(tab.id, notification);
      if (!handled) {
        pending.value = kind;
        tabReactionRevision.value++;
      } else {
        const name = notification.editorDisplayName ?? notification.editorUserId ?? null;
        if (name) {
          recent.value = { displayName: name, setAt: Date.now() };
          if (reaction.fadeTimer) clearTimeout(reaction.fadeTimer);
          reaction.fadeTimer = setTimeout(() => {
            recent.value = null;
            reaction.fadeTimer = null;
            tabReactionRevision.value++;
          }, RECENT_EDITOR_TTL_MS);
          tabReactionRevision.value++;
        }
      }
    } catch (e) {
      console.warn(`[documents.changed] tryApply threw for tab='${path}':`, e);
      pending.value = kind;
      tabReactionRevision.value++;
    }
  });
  tabReactions.set(tab.id, reaction);
}

function detachTabReaction(tabId: string): void {
  const r = tabReactions.get(tabId);
  if (!r) return;
  try { r.unsubscribe(); } catch { /* ignore */ }
  if (r.fadeTimer) { clearTimeout(r.fadeTimer); r.fadeTimer = null; }
  tabReactions.delete(tabId);
  tabReactionRevision.value++;
}

watch(
  () => store.openTabs.map((t) => ({ id: t.id, path: t.path })),
  (current, previous) => {
    const currentIds = new Set(current.map((t) => t.id));
    for (const old of previous ?? []) {
      if (!currentIds.has(old.id)) detachTabReaction(old.id);
    }
    for (const t of current) {
      const existing = tabReactions.get(t.id);
      if (!existing) {
        const tabObj = store.openTabs.find((x) => x.id === t.id);
        if (tabObj) attachTabReaction(tabObj);
      } else if (existing.path !== t.path) {
        detachTabReaction(t.id);
        const tabObj = store.openTabs.find((x) => x.id === t.id);
        if (tabObj) attachTabReaction(tabObj);
      }
    }
  },
  { immediate: true, deep: true },
);

onBeforeUnmount(() => {
  for (const id of Array.from(tabReactions.keys())) detachTabReaction(id);
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
    if (!tab) return;
    try {
      const handled = await runTryApply(tab, 'upserted');
      if (!handled) {
        const r = tabReactions.get(tab.id);
        if (r) {
          r.pendingChange.value = 'upserted';
          tabReactionRevision.value++;
        }
      }
    } catch (e) {
      console.warn(`[document-invalidate] apply for tab='${tab.path}' threw:`, e);
    }
  },
});

const activePendingChange = computed<string | null>(() => {
  // eslint-disable-next-line @typescript-eslint/no-unused-expressions
  tabReactionRevision.value;
  const id = activeTab.value?.id;
  if (!id) return null;
  return tabReactions.get(id)?.pendingChange.value ?? null;
});

const activeRecentEditor = computed<{ displayName: string } | null>(() => {
  // eslint-disable-next-line @typescript-eslint/no-unused-expressions
  tabReactionRevision.value;
  const id = activeTab.value?.id;
  if (!id) return null;
  return tabReactions.get(id)?.recentEditor.value ?? null;
});

function keepLocalForActive(): void {
  const id = activeTab.value?.id;
  if (!id) return;
  const r = tabReactions.get(id);
  if (r) {
    r.pendingChange.value = null;
    tabReactionRevision.value++;
  }
}

async function acceptRemoteForActive(): Promise<void> {
  const tab = activeTab.value;
  if (!tab) return;
  const r = tabReactions.get(tab.id);
  if (r) {
    r.pendingChange.value = null;
    tabReactionRevision.value++;
  }
  await store.reloadTab(tab.id);
}

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

const chatBoundDocumentPathDisplay = computed<string | null>(() => {
  const p = chatBoundDocumentPath.value;
  if (!p) return null;
  const MAX = 32;
  if (p.length <= MAX) return p;
  return '…' + p.slice(p.length - (MAX - 1));
});

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
 * The client-tool surface Cortex exposes to the LLM agent. Built only
 * when a session is bound — chatless mode has no agent to talk to.
 */
const clientToolService = hasSession.value
  ? new CortexClientToolService({
    getSelection: () => store.currentSelection,
    getActiveTab: () => {
      const tab = store.activeTab;
      if (!tab) return null;
      return { documentId: tab.id, path: tab.path };
    },
    openFileByPath: async (path) => {
      const normalised = path.replace(/^\/+/, '');
      const file = store.files.find((f) => f.path === normalised);
      if (!file) return null;
      const alreadyOpen = store.openTabs.some((t) => t.id === file.id);
      focusZone.value = 'main';
      await store.openFile(file.id);
      return { documentId: file.id, path: file.path, alreadyOpen };
    },
  })
  : null;

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
  // leaving the field blank.
  const ref = activeTab.value;
  let path = '';
  if (ref) {
    const idx = ref.path.lastIndexOf('/');
    path = idx >= 0 ? ref.path.slice(0, idx) : '';
  } else {
    path = 'documents';
  }
  createPrefill.value = { path };
  showCreate.value = true;
}

async function onCreateConfirm(result: CreateModalResult): Promise<void> {
  if (!projectId.value) return;
  if (result.kind === 'inline') {
    await store.createFile({
      path: result.fullPath,
      title: result.title,
      tags: result.tags,
      mimeType: result.mimeType,
      inlineText: result.inlineText,
    });
  } else {
    for (const file of result.files) {
      await store.uploadExternalFile(file, result.targetFolder);
    }
    // Open the first uploaded file as a tab if there was exactly one.
    if (result.files.length === 1) {
      const last = store.files[store.files.length - 1];
      if (last) await store.openFile(last.id);
    }
  }
  showCreate.value = false;
  createPrefill.value = null;
}

function onNewFolder(): void {
  const ref = activeTab.value;
  if (ref) {
    const idx = ref.path.lastIndexOf('/');
    newFolderInitial.value = idx >= 0 ? ref.path.slice(0, idx) : '';
  } else {
    newFolderInitial.value = 'documents';
  }
  showNewFolder.value = true;
}

function onNewFolderConfirm(path: string): void {
  store.addVirtualFolder(path);
  showNewFolder.value = false;
}

async function onDelete(id: string): Promise<void> {
  if (!confirm('Delete this document?')) return;
  await store.deleteFile(id);
}

// ──────────────── File-tree drag & drop ────────────────
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
  if (newPath === doc.path) return;
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

function backHome(): void {
  if (hasSession.value && sessionId.value) {
    const params = new URLSearchParams();
    params.set('sessionId', sessionId.value);
    if (projectId.value) params.set('project', projectId.value);
    window.location.href = `/chat.html?${params.toString()}`;
  } else if (projectId.value) {
    window.location.href = `/documents.html?projectId=${encodeURIComponent(projectId.value)}`;
  } else {
    window.location.href = '/documents.html';
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

function closeMenus(): void {
  const el = document.activeElement;
  if (el instanceof HTMLElement) el.blur();
}

watch(title, (t) => {
  document.title = t;
}, { immediate: true });

// ─── Auto-save with debounce ───────────────────────────────────────
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

function onBeforeUnload(e: BeforeUnloadEvent): void {
  if (store.openTabs.some((t) => t.dirty)) {
    e.preventDefault();
    e.returnValue = '';
  }
}

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
  void store.saveAllDirty();
});

const backLabel = computed(() => (hasSession.value ? '← Chat' : '← Documents'));
const bootReadyKey = computed(() => !!projectId.value);

/**
 * Right-panel toggle.
 *
 * In session mode, "hide the chat" means **leave the session** — drop
 * the {@code sessionId} from the URL and reload, which brings us back
 * to chatless mode (the session-picker can then be re-opened with the
 * same toggle). This matches the user's mental model: the toggle is a
 * single "am I currently in a chat?" switch, not a panel-visibility
 * checkbox.
 *
 * In chatless mode the toggle is a stupid show/hide of the picker
 * panel — no URL change.
 */
function toggleRightPanel(): void {
  if (hasSession.value) {
    const params = new URLSearchParams();
    if (projectId.value) params.set('project', projectId.value);
    const qs = params.toString();
    window.location.href = `/cortex.html${qs ? `?${qs}` : ''}`;
    return;
  }
  rightPanelOpen.value = !rightPanelOpen.value;
}

const toggleTooltip = computed<string>(() => {
  if (hasSession.value) return 'Leave chat';
  return rightPanelOpen.value ? 'Hide sessions' : 'Show sessions';
});
</script>

<template>
  <EditorShell
    v-if="bootReadyKey"
    v-model:focus-zone="focusZone"
    :title="title"
    :breadcrumbs="breadcrumbs"
    :full-height="true"
    focus-model="auto"
    :show-sidebar="!isAppTab"
    :show-right-panel="rightPanelOpen"
    title-clickable
    @title-click="focusZone = 'sidebar'"
  >
    <template #topbar-extra>
      <transition name="recent-editor-fade">
        <span
          v-if="isAgentEditing"
          class="mr-2 inline-flex items-center gap-1 text-xs
                 text-info font-medium select-none animate-pulse"
          :title="$t('documents.agentEditing.tooltip')"
        >
          <span aria-hidden="true">✎</span>
          {{ $t('documents.agentEditing.label') }}
        </span>
      </transition>

      <transition name="recent-editor-fade">
        <span
          v-if="activeRecentEditor && activeTab?.path"
          class="mr-2 inline-flex items-center gap-1 text-xs
                 text-base-content/70 font-medium select-none"
          :title="$t('documents.recentEditor.tooltip', { name: activeRecentEditor.displayName })"
        >
          <span aria-hidden="true" class="text-success">⏺</span>
          {{ activeRecentEditor.displayName }}
        </span>
      </transition>

      <div
        v-if="activePendingChange && activeTab?.path"
        class="mr-3 inline-flex items-center gap-2 rounded-md
               border border-warning/40 bg-warning/15 px-2 py-1
               text-xs font-medium text-warning-content"
        :title="activePendingChange === 'deleted'
                ? $t('documents.externallyChanged.deletedTooltip')
                : $t('documents.externallyChanged.upsertedTooltip')"
      >
        <span aria-hidden="true">●</span>
        <span>{{ activePendingChange === 'deleted'
                 ? $t('documents.externallyChanged.deleted')
                 : $t('documents.externallyChanged.upserted') }}</span>
        <button
          type="button"
          class="rounded border border-warning/50 px-1.5 py-0.5
                 hover:bg-warning/30"
          @click="keepLocalForActive()"
        >
          {{ $t('documents.externallyChanged.keepLocal') }}
        </button>
        <button
          type="button"
          class="rounded border border-warning/50 px-1.5 py-0.5
                 hover:bg-warning/30"
          @click="acceptRemoteForActive()"
        >
          {{ $t('documents.externallyChanged.acceptRemote') }}
        </button>
      </div>
      <DocumentPresenceStrip
        v-if="activeTab?.path"
        :path="activeTab.path"
        class="mr-2"
      />
      <VButton size="sm" variant="ghost" @click="backHome">{{ backLabel }}</VButton>
      <VButton
        size="sm"
        variant="ghost"
        :class="hasSession
          ? 'bg-primary/15 text-primary hover:bg-primary/25'
          : 'opacity-60 hover:opacity-100'"
        :title="toggleTooltip"
        @click="toggleRightPanel"
      >
        <span aria-hidden="true">💬</span>
      </VButton>
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
            @reload="() => projectId && store.loadList(projectId)"
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
      <!-- File/Chat menu bar. Hidden in App view-mode so the App component
           gets the full vertical space; the slim header inside
           DocumentTabShell carries the App|Edit toggle to leave the mode. -->
      <div
        v-if="!isAppTab"
        class="flex items-center gap-1 px-2 py-1 border-b border-base-300 bg-base-200 text-sm shrink-0"
      >
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
              <a @click="closeMenus(); backHome()">
                <span class="flex-1">{{ hasSession ? 'Back to chat' : 'Back to documents' }}</span>
              </a>
            </li>
          </ul>
        </div>

        <div v-if="hasSession" class="dropdown">
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

        <template v-if="hasSession">
          <span
            v-if="clientToolService?.isExecuting.value"
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
        </template>
      </div>

      <!-- Tab strip. Hidden in App view-mode — an _app.yaml manifest
           is folder-bound; the user's mental model is "I'm in this app",
           not "I'm flipping between docs". Edit-toggle in the slim
           header brings the strip back along with the rest of the
           chrome. -->
      <EditorTabs
        v-if="!isAppTab"
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
        v-if="hasSession && sessionId && projectId && clientToolService"
        :session-id="sessionId"
        :project-id="projectId"
        :tool-service="clientToolService"
        :active-document="activeTab"
      />
      <div
        v-else-if="hasSession"
        class="h-full p-3 text-sm opacity-60"
      >
        Waiting for session…
      </div>
      <SessionPickerPanel
        v-else-if="projectId"
        :project-id="projectId"
      />
      <div
        v-else
        class="h-full p-3 text-sm opacity-60"
      >
        No project context.
      </div>
    </template>
  </EditorShell>

  <CreateDocumentModal
    v-model:open="showCreate"
    :project-id="projectId"
    :initial-path="createPrefill?.path ?? ''"
    :consume-draft="!hasSession"
    @confirm="onCreateConfirm"
  />

  <NewFolderModal
    v-model:open="showNewFolder"
    :initial-path="newFolderInitial"
    @confirm="onNewFolderConfirm"
  />
</template>

<style>
.recent-editor-fade-enter-active,
.recent-editor-fade-leave-active {
  transition: opacity 0.6s ease-in-out;
}
.recent-editor-fade-enter-from,
.recent-editor-fade-leave-to {
  opacity: 0;
}

@media (max-width: 1366px) {
  .editor-shell-grid[data-focus='main']:not([data-help-open]),
  .editor-shell-grid[data-focus='right']:not([data-help-open]),
  .editor-shell-grid[data-focus='footer']:not([data-help-open]) {
    --shell-sidebar-w: 0;
  }
}
</style>
