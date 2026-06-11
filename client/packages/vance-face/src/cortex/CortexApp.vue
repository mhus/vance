<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  EditorShell,
  type FocusZone,
  VAlert,
  VButton,
  VEmptyState,
  VInput,
  VModal,
} from '@/components';
import { brainFetch } from '@vance/shared';
import type { SessionSummaryRichDto } from '@vance/generated';
import { useCortexStore } from './stores/cortexStore';
import { CortexClientToolService } from './clientToolService';
import FileTreeSidebar from './components/FileTreeSidebar.vue';
import EditorTabs from './components/EditorTabs.vue';
import TabRendererHost from './components/TabRendererHost.vue';
import CortexChatPanel from './components/CortexChatPanel.vue';

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

const bootError = ref<string | null>(null);
const saving = ref(false);
const saveError = ref<string | null>(null);

const showCreate = ref(false);
const createPath = ref('');
const createError = ref<string | null>(null);
const creating = ref(false);

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
    // If the bound document survived restoration, surface it as the
    // active tab so the user lands where the chat is working.
    if (bound && store.openTabs.some((t) => t.id === bound)) {
      store.setActiveTab(bound);
    }
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
  if (sessionId.value) return `Cortex · ${sessionId.value}`;
  return 'Cortex';
});

const breadcrumbs = computed(() => {
  const crumbs: { label: string }[] = [];
  if (projectId.value) crumbs.push({ label: projectId.value });
  if (sessionTitle.value) crumbs.push({ label: sessionTitle.value });
  return crumbs;
});

const activeTab = computed(() => store.activeTab);

const chatBoundDocumentPath = computed<string | null>(() => {
  const id = chatBoundDocumentId.value;
  if (!id) return null;
  const tab = store.openTabs.find((t) => t.id === id);
  return tab?.path ?? null;
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

function onNew(parentPath: string): void {
  createPath.value = parentPath ? `${parentPath}/` : '';
  createError.value = null;
  showCreate.value = true;
}

async function confirmCreate(): Promise<void> {
  if (!createPath.value.trim()) {
    createError.value = 'Path required';
    return;
  }
  creating.value = true;
  createError.value = null;
  try {
    await store.createFile({
      path: createPath.value.trim(),
      inlineText: '',
    });
    showCreate.value = false;
  } catch (e) {
    createError.value = e instanceof Error ? e.message : 'Create failed';
  } finally {
    creating.value = false;
  }
}

async function onDelete(id: string): Promise<void> {
  if (!confirm('Delete this document?')) return;
  await store.deleteFile(id);
}

function backToChat(): void {
  if (sessionId.value) {
    window.location.href = `/chat.html?sessionId=${encodeURIComponent(sessionId.value)}`;
  } else {
    window.location.href = '/chat.html';
  }
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
  const isSave = (e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 's';
  if (!isSave) return;
  e.preventDefault();
  void store.saveAllDirty();
}

onMounted(() => {
  window.addEventListener('beforeunload', onBeforeUnload);
  window.addEventListener('keydown', onKeyDown);
});

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', onBeforeUnload);
  window.removeEventListener('keydown', onKeyDown);
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
    <template #sidebar>
      <div class="flex flex-col h-full min-h-0">
        <div class="p-3 border-b border-base-300 shrink-0 flex items-center gap-2">
          <VButton size="sm" variant="ghost" @click="backToChat">← Chat</VButton>
        </div>
        <div class="flex-1 min-h-0 overflow-y-auto">
          <FileTreeSidebar
            v-if="projectId"
            :root="store.fileTree"
            :active-file-id="store.activeTabId"
            @open-file="(id: string) => { focusZone = 'main'; store.openFile(id); }"
            @new-file="onNew"
            @delete-file="onDelete"
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
      <EditorTabs
        :tabs="store.openTabs"
        :active-tab-id="store.activeTabId"
        @select="store.setActiveTab"
        @close="store.closeTab"
      />

      <div v-if="!activeTab" class="flex-1 flex items-center justify-center">
        <VEmptyState
          headline="No document open"
          body="Pick one from the tree on the left, or create a new file."
        />
      </div>

      <div v-else class="flex-1 flex flex-col min-h-0">
        <div class="flex items-center gap-2 px-3 py-2 border-b border-base-300 bg-base-100 text-sm">
          <span class="font-mono opacity-80 truncate">{{ activeTab.path }}</span>
          <span v-if="activeTab.dirty" class="opacity-60">●</span>
          <span
            v-if="chatBoundDocumentId === activeTab.id && clientToolService.isExecuting.value"
            class="text-xs px-2 py-0.5 rounded bg-warning/15 text-warning border border-warning/30 animate-pulse"
            title="An agent tool is currently editing this document"
          >agent editing…</span>
          <span
            v-else-if="chatBoundDocumentId === activeTab.id"
            class="text-xs px-2 py-0.5 rounded bg-primary/10 text-primary opacity-80"
            title="Cortex chat is bound to this document"
          >chat bound</span>
          <VButton
            v-else
            size="sm"
            variant="ghost"
            title="Bind the Cortex chat to this document"
            @click="chatBoundDocumentId = activeTab.id"
          >Bind chat here</VButton>
          <span class="flex-1" />
          <VButton
            size="sm"
            :loading="saving"
            :disabled="!activeTab.dirty"
            @click="onSave"
          >Save</VButton>
        </div>

        <VAlert v-if="saveError" variant="error" class="m-2">{{ saveError }}</VAlert>

        <div class="flex-1 min-h-0 overflow-hidden">
          <TabRendererHost
            :document="activeTab"
            @update="store.updateActiveContent"
          />
        </div>
      </div>
    </div>

    <template #right-panel>
      <CortexChatPanel
        v-if="sessionId && projectId"
        :session-id="sessionId"
        :project-id="projectId"
        :chat-bound-document-path="chatBoundDocumentPath"
        :tool-service="clientToolService"
      />
      <div v-else class="h-full p-3 text-sm opacity-60">
        Waiting for session…
      </div>
    </template>
  </EditorShell>

  <VModal v-model="showCreate" title="New document">
    <div class="space-y-2 p-2">
      <VInput
        v-model="createPath"
        label="Path"
        placeholder="notes/idea.md"
      />
      <VAlert v-if="createError" variant="error">{{ createError }}</VAlert>
      <div class="flex justify-end gap-2 pt-2">
        <VButton variant="ghost" @click="showCreate = false">Cancel</VButton>
        <VButton variant="primary" :loading="creating" @click="confirmCreate">Create</VButton>
      </div>
    </div>
  </VModal>
</template>
