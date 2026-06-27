<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  WebSocketRequestError,
} from '@vance/shared';
import type {
  ActiveAppContext,
  ChatMessageDto,
  DocumentDto,
} from '@vance/generated';
import {
  bindSession,
  leaveChat,
  useWsConnection,
} from '@/ws/wsConnectionStore';
import { VAlert, VButton } from '@/components';
import ChatView from '@/chat/ChatView.vue';
import ChatComposer, {
  type ComposerCurrentFileSource,
} from '@/chat/ChatComposer.vue';
import { useCortexStore } from '../stores/cortexStore';
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
const activeApp = computed<ActiveAppContext | null>(() => {
  const tab = cortexStore.activeTab;
  if (!tab) return null;
  if ((tab.kind ?? '').toLowerCase() !== 'application') return null;
  const app = tab.headers?.app;
  if (!app || typeof app !== 'string' || app.trim() === '') return null;
  const folder = tab.path.replace(/\/_app\.yaml$/, '');
  if (!folder) return null;
  return { folder, app };
});

// The chat-process name is fixed by {@code SessionChatBootstrapper} to
// "chat" — exactly one per session, see chat/ChatApp.vue's
// resolveSessionAndProcess. We don't need a session-list lookup here;
// the constant is the contract.
const CHAT_PROCESS_NAME = 'chat';

type Status = 'connecting' | 'live' | 'occupied' | 'failed';

const { socket, activeSessionId, status: wsStatus } = useWsConnection();
const bindError = ref<string | null>(null);
const occupied = ref(false);

const status = computed<Status>(() => {
  if (occupied.value) return 'occupied';
  if (bindError.value) return 'failed';
  if (activeSessionId.value === props.sessionId
      && (wsStatus.value === 'connected' || wsStatus.value === 'reconnecting')) {
    return 'live';
  }
  return 'connecting';
});

const errorMessage = computed<string | null>(() => {
  if (occupied.value) {
    return 'Another connection holds this session — close that tab and retry.';
  }
  return bindError.value;
});

// ToolService attach follows the singleton socket — re-attach after
// every fresh socket (e.g. after an auto-reconnect).
let attachedToolSocket: typeof socket.value = null;
watch(
  socket,
  (next) => {
    if (!props.toolService) return;
    if (next && next !== attachedToolSocket) {
      try {
        void props.toolService.attach(next);
        attachedToolSocket = next;
      } catch (regError) {
        console.warn('Failed to register Cortex client tools', regError);
      }
    } else if (!next && attachedToolSocket) {
      attachedToolSocket = null;
    }
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
    if (e instanceof WebSocketRequestError && e.errorCode === 409) {
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
  await bindToSession();
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
          :draft-key="`cortex:${sessionId}`"
          @hub="onLeave"
          @local-echo="onLocalEcho"
          @rollback-echo="onRollbackEcho"
        />
      </div>
    </template>
  </div>
</template>
