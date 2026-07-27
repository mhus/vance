<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  WebSocketRequestError,
} from '@vance/shared';
import type {
  ActiveAppContext,
  BoundDocSelection,
  ChatMessageDto,
  DocumentDto,
} from '@vance/generated';
import {
  bindSession,
  ensureBound,
  leaveChat,
  takeoverSession,
  useWsConnection,
} from '@/ws/wsConnectionStore';
import { VAlert, VButton } from '@/components';
import ChatView from '@/chat/ChatView.vue';
import ChatComposer, {
  type ComposerCurrentFileSource,
} from '@/chat/ChatComposer.vue';
import { useCortexStore } from '../stores/cortexStore';
import { useViewEditMode } from '../useViewEditMode';
import type { CortexClientToolService } from '../clientToolService';

interface Props {
  sessionId: string;
  projectId: string;
  /**
   * Owned by the parent app — single instance for the lifetime of the
   * Cortex view. Attached to the WS whenever a session goes live; the
   * brain pushes invocations through this same connection.
   */
  toolService?: CortexClientToolService | null;
  /**
   * Document currently bound to the chat (the `bind file` affordance,
   * owned by EditorApp). Forwarded to the composer so every steer
   * carries it as per-turn LLM context.
   */
  boundDocumentId?: string | null;
  /**
   * An app-owned structured selection ({@code appDocId} = the app tab's doc
   * id, {@code selection} = a freeform hint like a canvas board's selected
   * node ids). Folded into the `activeApp` context when it belongs to the
   * active app tab — the char-range `boundDocSelection` can't express it.
   */
  appSelection?: { appDocId: string; selection: string } | null;
}

const props = defineProps<Props>();

const cortexStore = useCortexStore();

/**
 * Surfaces the Cortex active tab as a one-click chat attachment. Reactive,
 * so the composer's dropdown label always reflects what the user is
 * currently looking at in the main editor. {@code null} when no tab is
 * open (Cortex starts blank for fresh sessions) — the composer then
 * falls back to the plain native file-picker UX.
 */
const currentFileSource = computed<ComposerCurrentFileSource | null>(() => {
  const tab = cortexStore.activeTab;
  if (!tab) return null;
  return { documentId: tab.id, label: tab.path };
});

/**
 * Per-turn active-app hint forwarded to the brain via
 * {@code ProcessSteerRequest.activeApp}. Derived from the visible
 * Cortex tab — when its kind is {@code application} and the manifest
 * carries an {@code app:} discriminator, the brain renders an
 * app-context block in the engine prompt and asks the app's
 * {@code VanceApplication.promptInject(...)} for dynamic content.
 */
/**
 * Per-turn text-selection hint forwarded to the brain via
 * {@code ProcessSteerRequest.boundDocSelection}. Only surfaced when the
 * user's current selection lives inside the document that is actually
 * bound to the chat this turn — otherwise the range would point into a
 * file the agent isn't being shown. Only the {@code from}/{@code to}
 * range travels; the model reads the selected text on demand via
 * {@code doc_get_selection}.
 */
const boundDocSelection = computed<BoundDocSelection | null>(() => {
  const boundId = props.boundDocumentId ?? null;
  if (!boundId) return null;
  const sel = cortexStore.currentSelection;
  if (!sel || sel.docId !== boundId) return null;
  if (sel.from === sel.to) return null;
  return { from: sel.from, to: sel.to };
});

const viewEditMode = useViewEditMode();

const activeApp = computed<ActiveAppContext | null>(() => {
  // Only forward the hint when the tab is actually running the app
  // ("App" mode). In "Edit" mode the user is editing the raw _app.yaml
  // manifest, not operating the app, so the app-context block would be
  // misleading. (App mode only exists for application docs, so the
  // kind check below is the precondition; the mode is the deciding one.)
  if (viewEditMode.value !== 'view') return null;
  const tab = cortexStore.activeTab;
  if (!tab) return null;
  if ((tab.kind ?? '').toLowerCase() !== 'application') return null;
  const app = tab.headers?.app;
  if (!app || typeof app !== 'string' || app.trim() === '') return null;
  const folder = tab.path.replace(/\/_app\.yaml$/, '');
  if (!folder) return null;
  // An app-owned selection (e.g. canvas node ids) rides along when it belongs
  // to this app tab — carried on activeApp.selection (not boundDocSelection).
  const selection = props.appSelection && props.appSelection.appDocId === tab.id
    ? props.appSelection.selection
    : undefined;
  return { folder, app, selection };
});

// The chat-process name is fixed by {@code SessionChatBootstrapper} to
// "chat" — exactly one per session, see chat/ChatApp.vue's
// resolveSessionAndProcess. We don't need a session-list lookup here;
// the constant is the contract.
const CHAT_PROCESS_NAME = 'chat';

type Status = 'connecting' | 'live' | 'occupied' | 'failed' | 'elsewhere';

const { socket, activeSessionId, bindConflict, status: wsStatus } = useWsConnection();

/**
 * True once the user dismissed the "take over?" dialog for this session
 * (chose to leave it in the other window). Without this the panel would
 * fall back to a permanent "Connecting…" — see {@link status}. Reset the
 * moment the session actually binds here.
 */
const declinedTakeover = ref(false);
watch(bindConflict, (now, prev) => {
  if (now === props.sessionId) {
    // Dialog (re)opened for our session — not a declined state (yet).
    declinedTakeover.value = false;
  } else if (prev === props.sessionId && activeSessionId.value !== props.sessionId) {
    // Dialog closed without binding here → the user declined the takeover.
    declinedTakeover.value = true;
  }
});
watch(activeSessionId, (id) => {
  if (id === props.sessionId) declinedTakeover.value = false;
});

/**
 * True once the tab-singleton socket is open <em>and</em> our session is
 * the server-confirmed bound one. The tool-service attach gates on this:
 * {@code client-tool-register} is a session-scoped frame (server-side
 * {@code canExecute} = "session bound"), so registering the instant a
 * fresh socket appears — before {@code session-resume} lands — earns a
 * 403 "requires a bound session". That window opens on every mount and
 * again on every auto-reconnect (which swaps {@code socket.value} before
 * the re-resume completes).
 */
const sessionBound = computed(
  () => activeSessionId.value === props.sessionId,
);
const bindError = ref<string | null>(null);
const occupied = ref(false);

const status = computed<Status>(() => {
  if (occupied.value) return 'occupied';
  if (bindError.value) return 'failed';
  if (activeSessionId.value === props.sessionId
      && (wsStatus.value === 'connected' || wsStatus.value === 'reconnecting')) {
    return 'live';
  }
  // User declined the takeover — the session stays live in the other
  // window and is not bound here. Show a distinct state (not a permanent
  // "Connecting…"). While the dialog is still up (bindConflict === us) we
  // keep 'connecting' — the modal covers the panel anyway.
  if (declinedTakeover.value && bindConflict.value !== props.sessionId) {
    return 'elsewhere';
  }
  return 'connecting';
});

const errorMessage = computed<string | null>(() => {
  if (occupied.value) {
    return 'Another connection holds this session — close that tab and retry.';
  }
  return bindError.value;
});

// ToolService attach follows the singleton socket AND the session bind —
// re-attach after every fresh socket (e.g. after an auto-reconnect) once
// the session is server-confirmed bound again. Attaching before the bind
// lands would 403 (see {@link sessionBound}).
let attachedToolSocket: typeof socket.value = null;
watch(
  [socket, sessionBound],
  ([next, bound]) => {
    if (!props.toolService) return;
    if (!next || !bound) {
      // Socket gone (reconnect) or session not bound yet — nothing to
      // attach to. Clear the marker so the next ready socket re-attaches.
      if (attachedToolSocket) attachedToolSocket = null;
      return;
    }
    if (next === attachedToolSocket) return;
    const target = next;
    attachedToolSocket = target;
    props.toolService.attach(target).catch((regError) => {
      // Register failed (stale socket swapped out under us, transient
      // error) — drop the marker so a fresh ready socket retries.
      if (attachedToolSocket === target) attachedToolSocket = null;
      console.warn('Failed to register Cortex client tools', regError);
    });
  },
  { immediate: true },
);

// Imperative cross-component routing — ChatComposer pushes optimistic
// user-message echoes; ChatView appends them to its message list so the
// user sees their message before the server frame arrives. Same dance
// chat.html does in its parent ChatApp.
const chatViewRef = ref<InstanceType<typeof ChatView> | null>(null);
const composerRef = ref<InstanceType<typeof ChatComposer> | null>(null);

async function bindToSession(): Promise<void> {
  bindError.value = null;
  occupied.value = false;
  try {
    await bindSession(props.sessionId);
  } catch (e) {
    if (e instanceof WebSocketRequestError
        && e.errorCode === 409
        && e.reason === 'session_bound_elsewhere') {
      // Same user, session live in another window — the global
      // SessionTakeoverDialog owns this UX (the store flagged bindConflict).
      // Don't also show the local "occupied" panel.
    } else if (e instanceof WebSocketRequestError && e.errorCode === 409) {
      occupied.value = true;
    } else if (e instanceof WebSocketRequestError && e.errorCode === 404) {
      bindError.value = `Session ${props.sessionId} not found.`;
    } else if (e instanceof WebSocketRequestError && e.errorCode === 403) {
      bindError.value = 'Access to this session was denied.';
    } else {
      bindError.value = e instanceof Error
        ? e.message
        : 'Failed to bind chat session.';
    }
  }
}

async function retry(): Promise<void> {
  // Re-attempt a plain bind. If the other window has since let go, this
  // binds cleanly; if it still holds the session, the takeover dialog
  // pops again.
  declinedTakeover.value = false;
  await bindToSession();
}

async function takeOverHere(): Promise<void> {
  declinedTakeover.value = false;
  await takeoverSession();
}

/**
 * Composer-facing pre-send hook. Guarantees the tab-singleton socket is
 * up <em>and</em> this session is server-confirmed bound before a steer
 * goes out — a steer on a socket that reconnected but has not re-resumed
 * the session earns a 403 "requires a bound session". Idempotent when
 * already bound.
 */
async function ensureReady(): Promise<boolean> {
  try {
    return await ensureBound();
  } catch {
    return false;
  }
}

onMounted(() => {
  void bindToSession();
});

onBeforeUnmount(() => {
  props.toolService?.detach();
  // 10s grace timer — if the user comes back to a Cortex panel for the
  // same session within 10s, the bind survives and no roundtrip is made.
  leaveChat();
});

// ─── Cross-component routing (subset of ChatApp.vue) ───
//
// Cortex V1 skips: follow-up ghost suggestions, wizard deep-links,
// TTS / speak gates, ask-user pick (rare), talk-mode. Those add a lot
// of surface area and the chat is functional without them — they can
// be ported piecemeal once the embedded layout proves itself.

function onLocalEcho(msg: ChatMessageDto): void {
  chatViewRef.value?.appendLocalEcho(msg);
}

function onRollbackEcho(messageId: string): void {
  chatViewRef.value?.rollbackLocalEcho(messageId);
}

function onLeave(): void {
  // ChatView emits 'leave' when the user archives/deletes the session
  // via SessionHeader. Bounce back to chat.html so they can pick a
  // different session — Cortex without a session has nothing to do.
  window.location.href = '/chat.html';
}

/**
 * Open the freshly-saved conversation-export document as a Cortex tab so
 * the user can rename/move it without leaving the editor. The chat-side
 * banner (rendered inside ChatView) still shows the success path; this
 * handler just adds the "open it" affordance that's unique to Cortex.
 */
async function onConversationExported(
  payload: { documentId: string; document: DocumentDto },
): Promise<void> {
  try {
    await cortexStore.openFile(payload.documentId);
  } catch (e) {
    console.warn('Failed to open exported conversation in Cortex', e);
  }
}
</script>

<template>
  <div class="h-full min-h-0 flex flex-col">
    <div
      class="px-3 py-1.5 text-xs border-b border-base-300 bg-base-200/40 text-base-content/60
             flex items-center gap-2 shrink-0"
    >
      <span class="uppercase tracking-wide opacity-70">Session</span>
      <span class="font-mono truncate">{{ sessionId }}</span>
    </div>

    <div v-if="status === 'connecting'" class="flex-1 flex items-center justify-center text-sm opacity-60">
      Connecting…
    </div>

    <div v-else-if="status === 'elsewhere'" class="p-3 space-y-2">
      <VAlert variant="warning">
        This session is open in another window or on another device — it is
        not connected here.
      </VAlert>
      <div class="flex flex-col gap-2">
        <VButton size="sm" variant="secondary" @click="retry">Reconnect</VButton>
        <VButton size="sm" variant="primary" @click="takeOverHere">Take over here</VButton>
      </div>
    </div>

    <div v-else-if="status !== 'live'" class="p-3">
      <VAlert :variant="status === 'occupied' ? 'warning' : 'error'">
        {{ errorMessage }}
        <div class="mt-2">
          <VButton size="sm" variant="secondary" @click="retry">Retry</VButton>
        </div>
      </VAlert>
    </div>

    <template v-else>
      <div class="flex-1 min-h-0 overflow-hidden">
        <ChatView
          v-if="socket"
          ref="chatViewRef"
          :socket="socket"
          :session-id="sessionId"
          :chat-process-name="CHAT_PROCESS_NAME"
          :chat-project-id="projectId"
          @leave="onLeave"
          @hub="onLeave"
          @conversation-exported="onConversationExported"
        />
      </div>
      <div class="shrink-0 border-t border-base-300">
        <ChatComposer
          v-if="socket"
          ref="composerRef"
          :socket="socket"
          :chat-process-name="CHAT_PROCESS_NAME"
          :chat-project-id="projectId"
          :compact-tools="true"
          :current-file-source="currentFileSource"
          :active-app="activeApp"
          :bound-document-id="boundDocumentId ?? null"
          :bound-doc-selection="boundDocSelection"
          :ensure-connected="ensureReady"
          :draft-key="`cortex:${sessionId}`"
          @hub="onLeave"
          @local-echo="onLocalEcho"
          @rollback-echo="onRollbackEcho"
        />
      </div>
    </template>
  </div>
</template>
