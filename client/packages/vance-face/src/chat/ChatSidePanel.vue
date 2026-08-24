<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { WebSocketRequestError, type BrainWsApi } from '@vance/shared';
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
import { VAlert, VBackButton, VButton } from '@/components';
import ChatView from '@/chat/ChatView.vue';
import ChatComposer, {
  type ComposerCurrentFileSource,
} from '@/chat/ChatComposer.vue';

/**
 * An agent conversation in a side column: owns the session bind, the
 * takeover/occupied states, the optional client-tool attachment, and the
 * ChatView/ChatComposer pair.
 *
 * <p>Extracted from Cortex, which had all of this inline. The reason to share
 * it rather than write a second one is that almost none of it is chat: it is
 * the WebSocket bind lifecycle — the tool-attach that must wait for the
 * server-confirmed bind, the reconnect that swaps the socket under us, the
 * "open in another window" fork. A second implementation of that would be a
 * second set of the same bugs.
 *
 * <p><b>Everything host-specific arrives as a prop.</b> Cortex derives
 * {@code currentFileSource}/{@code activeApp}/{@code boundDocSelection} from
 * its open tab; a host without documents (the inbox) passes none and gets a
 * plain conversation. The panel itself reads no store.
 */

/** What this panel needs of a client-tool service — {@code attach}, {@code detach}. */
export interface ChatPanelToolService {
  attach(ws: BrainWsApi): Promise<void>;
  detach(): void;
}

interface Props {
  sessionId: string;
  projectId: string;
  /**
   * Owned by the host — one instance for the lifetime of its view. Attached
   * to the WS whenever the session goes live; the brain pushes invocations
   * through the same connection. Omit when the host exposes no client tools.
   */
  toolService?: ChatPanelToolService | null;
  /**
   * Document bound to the chat this turn (the host's `bind file` affordance).
   * Forwarded to the composer so every steer carries it as LLM context.
   */
  boundDocumentId?: string | null;
  /**
   * Char-range selection inside the bound document. The host decides whether
   * its selection really lives in the bound document — a range pointing into
   * another file would show the agent the wrong text.
   */
  boundDocSelection?: BoundDocSelection | null;
  /** Per-turn active-app hint, when the host is running a Vance application. */
  activeApp?: ActiveAppContext | null;
  /** One-click attachment offer for whatever the host is showing right now. */
  currentFileSource?: ComposerCurrentFileSource | null;
  /** Namespace for the composer's persisted draft. Distinct per host. */
  draftKey: string;
  /**
   * Names a way out of this conversation, back to whatever the host shows
   * instead (its session list). Set it and the header carries a chevron;
   * leave it out and there is none — Cortex's way back is the topbar toggle,
   * which navigates, so a second one in here would be a dead end for it.
   *
   * <p>The text is the tooltip and the accessible name, not visible label: the
   * chevron alone is unmistakable next to a session id, and the bar is narrow.
   */
  backLabel?: string;
}

const props = defineProps<Props>();

const emit = defineEmits<{
  /**
   * The session ended (archived/deleted) or the user asked for the hub. The
   * host decides what that means — Cortex leaves for chat.html, the inbox
   * drops back to its session picker.
   */
  (e: 'leave'): void;
  /** The {@link Props.backLabel} button was pressed. */
  (e: 'back'): void;
  (e: 'conversation-exported',
   payload: { documentId: string; document: DocumentDto }): void;
}>();

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
      console.warn('Failed to register client tools', regError);
    });
  },
  { immediate: true },
);

// Imperative cross-component routing — ChatComposer pushes optimistic
// user-message echoes; ChatView appends them to its message list so the
// user sees their message before the server frame arrives. Same dance
// chat.html does in its parent ChatApp.
const chatViewRef = ref<InstanceType<typeof ChatView> | null>(null);

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
  // 10s grace timer — if the user comes back to a panel for the same
  // session within 10s, the bind survives and no roundtrip is made.
  leaveChat();
});

// ─── Cross-component routing (subset of ChatApp.vue) ───
//
// The side panel skips: follow-up ghost suggestions, wizard deep-links,
// TTS / speak gates, ask-user pick (rare), talk-mode. Those add a lot
// of surface area and the chat is functional without them — they can
// be ported piecemeal.

function onLocalEcho(msg: ChatMessageDto): void {
  chatViewRef.value?.appendLocalEcho(msg);
}

function onRollbackEcho(messageId: string): void {
  chatViewRef.value?.rollbackLocalEcho(messageId);
}
</script>

<template>
  <div class="h-full min-h-0 flex flex-col">
    <div
      class="px-3 py-1.5 text-xs border-b border-base-300 bg-base-200/40 text-base-content/60
             flex items-center gap-2 shrink-0"
    >
      <!-- The way out, as an actual control: bare grey text next to grey text
           reads as a label, not as something to press, so this takes the house
           affordance. Chevron only — the label lives in the tooltip, because
           the one thing this narrow bar has to spend width on is the id. -->
      <VBackButton
        v-if="backLabel"
        label=""
        :title="backLabel"
        :aria-label="backLabel"
        class="shrink-0 -ml-2"
        @click="emit('back')"
      />
      <span v-else class="uppercase tracking-wide opacity-70">Session</span>
      <span class="font-mono truncate" title="Session ID">{{ sessionId }}</span>
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
          @leave="emit('leave')"
          @hub="emit('leave')"
          @conversation-exported="(p: { documentId: string; document: DocumentDto }) =>
            emit('conversation-exported', p)"
        />
      </div>
      <div class="shrink-0 border-t border-base-300">
        <ChatComposer
          v-if="socket"
          :socket="socket"
          :chat-process-name="CHAT_PROCESS_NAME"
          :chat-project-id="projectId"
          :compact-tools="true"
          :current-file-source="currentFileSource ?? null"
          :active-app="activeApp ?? null"
          :bound-document-id="boundDocumentId ?? null"
          :bound-doc-selection="boundDocSelection ?? null"
          :ensure-connected="ensureReady"
          :draft-key="draftKey"
          @hub="emit('leave')"
          @local-echo="onLocalEcho"
          @rollback-echo="onRollbackEcho"
        />
      </div>
    </template>
  </div>
</template>
