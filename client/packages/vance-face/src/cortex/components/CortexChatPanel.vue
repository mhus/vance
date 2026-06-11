<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import {
  BrainWebSocket,
  WebSocketRequestError,
  getTenantId,
  setActiveSessionId,
} from '@vance/shared';
import type {
  ChatMessageDto,
  SessionResumeRequest,
  SessionResumeResponse,
} from '@vance/generated';
import { VAlert, VButton } from '@/components';
import ChatView from '@/chat/ChatView.vue';
import ChatComposer from '@/chat/ChatComposer.vue';
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

const CLIENT_VERSION = '0.1.0';

// The chat-process name is fixed by {@code SessionChatBootstrapper} to
// "chat" — exactly one per session, see chat/ChatApp.vue's
// resolveSessionAndProcess. We don't need a session-list lookup here;
// the constant is the contract.
const CHAT_PROCESS_NAME = 'chat';

type Status = 'connecting' | 'live' | 'occupied' | 'failed';

const status = ref<Status>('connecting');
const errorMessage = ref<string | null>(null);
const socket = ref<BrainWebSocket | null>(null);

// Imperative cross-component routing — ChatComposer pushes optimistic
// user-message echoes; ChatView appends them to its message list so the
// user sees their message before the server frame arrives. Same dance
// chat.html does in its parent ChatApp.
const chatViewRef = ref<InstanceType<typeof ChatView> | null>(null);
const composerRef = ref<InstanceType<typeof ChatComposer> | null>(null);

let onCloseUnsubscribe: (() => void) | null = null;

async function openSocket(): Promise<BrainWebSocket> {
  const tenant = getTenantId();
  if (!tenant) {
    throw new Error('Missing tenant — cannot open chat connection.');
  }
  return BrainWebSocket.connect({
    tenant,
    profile: 'web',
    clientVersion: CLIENT_VERSION,
  });
}

async function open(): Promise<void> {
  status.value = 'connecting';
  errorMessage.value = null;
  try {
    socket.value = await openSocket();
  } catch (e) {
    status.value = 'failed';
    errorMessage.value = e instanceof Error ? e.message : 'Failed to open chat connection.';
    return;
  }
  onCloseUnsubscribe = socket.value.onClose(() => {
    if (status.value === 'live') {
      status.value = 'failed';
      errorMessage.value = 'Chat connection lost.';
    }
  });
  try {
    await socket.value.send<SessionResumeRequest, SessionResumeResponse>(
      'session-resume',
      { sessionId: props.sessionId },
    );
    setActiveSessionId(props.sessionId);
    status.value = 'live';
    // Push the Cortex tool surface as soon as the bind succeeds —
    // failures here are non-fatal for the chat itself (the user can
    // still talk to the agent without doc tools), but log them so we
    // can spot a broken registration in practice.
    if (props.toolService && socket.value) {
      try {
        await props.toolService.attach(socket.value);
      } catch (regError) {
        console.warn('Failed to register Cortex client tools', regError);
      }
    }
  } catch (e) {
    if (e instanceof WebSocketRequestError && e.errorCode === 409) {
      status.value = 'occupied';
      errorMessage.value = 'Another connection holds this session — close that tab and retry.';
    } else if (e instanceof WebSocketRequestError && e.errorCode === 404) {
      status.value = 'failed';
      errorMessage.value = `Session ${props.sessionId} not found.`;
    } else if (e instanceof WebSocketRequestError && e.errorCode === 403) {
      status.value = 'failed';
      errorMessage.value = 'Access to this session was denied.';
    } else {
      status.value = 'failed';
      errorMessage.value = e instanceof Error ? e.message : 'Failed to bind chat session.';
    }
  }
}

async function teardown(): Promise<void> {
  onCloseUnsubscribe?.();
  onCloseUnsubscribe = null;
  props.toolService?.detach();
  if (socket.value && !socket.value.closed()) {
    socket.value.sendNoReply('session-unbind');
  }
  socket.value?.close();
  socket.value = null;
}

async function retry(): Promise<void> {
  await teardown();
  await open();
}

onMounted(() => {
  void open();
});

onBeforeUnmount(() => {
  void teardown();
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
          @hub="onLeave"
          @local-echo="onLocalEcho"
          @rollback-echo="onRollbackEcho"
        />
      </div>
    </template>
  </div>
</template>
