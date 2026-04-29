<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  type BrainWsApi,
  WebSocketRequestError,
} from '@vance/shared';
import type {
  ChatMessageAppendedData,
  ChatMessageChunkData,
  ChatMessageDto,
  ChatRole,
  ProcessProgressNotification,
  ProcessSteerRequest,
  ProcessSteerResponse,
  SessionListRequest,
  SessionListResponse,
} from '@vance/generated';
import { useChatHistory } from '@composables/useChatHistory';
import { VAlert, VButton, VTextarea } from '@components/index';
import MessageBubble from './MessageBubble.vue';
import ProgressFeed from './ProgressFeed.vue';

const props = defineProps<{
  socket: BrainWsApi;
  sessionId: string;
}>();

const emit = defineEmits<{ (event: 'leave'): void }>();

const PROGRESS_CAP = 50;

const { messages: history, loading: historyLoading, error: historyError, load, reset } =
  useChatHistory();

/** Messages received via chat-message-appended after history load. Same shape as history. */
const liveMessages = ref<ChatMessageDto[]>([]);

/** Per-process buffer of streaming chunks waiting for their commit frame. */
const streamingDrafts = ref<Map<string, { role: ChatRole; content: string; processName: string }>>(
  new Map());

const progressEvents = ref<ProcessProgressNotification[]>([]);

const composerText = ref('');
const sending = ref(false);
const sendError = ref<string | null>(null);

/**
 * Composer mode: single-line uses Enter to send (Shift+Enter for a hard
 * break), multi-line uses Ctrl/Cmd+Enter (because plain Enter is the
 * obvious gesture for newline once the user has multiple lines).
 */
const multiline = ref(false);
const composerRows = computed(() => (multiline.value ? 4 : 1));
const composerPlaceholder = computed(() =>
  multiline.value
    ? 'Type a message — Ctrl/Cmd+Enter to send, Enter for newline'
    : 'Type a message — Enter to send, Shift+Enter for newline');

/** Sequence for optimistic temp message ids — never collides with server ids. */
let optimisticSeq = 0;
const OPTIMISTIC_PREFIX = 'tmp_';

// ──────────────── Speech-to-text via browser SpeechRecognition ────────────────
//
// Web Speech API: standard `SpeechRecognition` in Safari 14.1+, plus a
// `webkitSpeechRecognition` alias in Chrome and Edge. Firefox ships
// the constructor only behind a build flag, so we detect at runtime
// and hide the button entirely when the API is missing — no fallback.
//
// We keep `interimResults = false` to avoid live-overwrite of text the
// user is editing; final transcripts are appended on each pause.

interface SpeechRecognitionAlternative { transcript: string }
interface SpeechRecognitionResult {
  isFinal: boolean;
  0: SpeechRecognitionAlternative;
}
interface SpeechRecognitionResultList { resultIndex: number; results: SpeechRecognitionResult[] }
interface SpeechRecognitionErrorEvent { error: string }
interface SpeechRecognitionLike {
  continuous: boolean;
  interimResults: boolean;
  lang: string;
  onresult: ((event: SpeechRecognitionResultList & { results: SpeechRecognitionResult[]; resultIndex: number }) => void) | null;
  onerror: ((event: SpeechRecognitionErrorEvent) => void) | null;
  onend: (() => void) | null;
  start(): void;
  stop(): void;
}
type SpeechRecognitionCtor = new () => SpeechRecognitionLike;

const speechSupported = ref(false);
const speechRecording = ref(false);
const speechError = ref<string | null>(null);
let recognition: SpeechRecognitionLike | null = null;

function initSpeechRecognition(): void {
  const w = window as unknown as {
    SpeechRecognition?: SpeechRecognitionCtor;
    webkitSpeechRecognition?: SpeechRecognitionCtor;
  };
  const Ctor = w.SpeechRecognition ?? w.webkitSpeechRecognition;
  if (!Ctor) return;
  speechSupported.value = true;
  const instance = new Ctor();
  instance.continuous = true;
  instance.interimResults = false;
  instance.lang = navigator.language || 'en-US';
  instance.onresult = (event) => {
    for (let i = event.resultIndex; i < event.results.length; i++) {
      const r = event.results[i];
      if (!r.isFinal) continue;
      const text = (r[0].transcript ?? '').trim();
      if (!text) continue;
      composerText.value = composerText.value
        ? `${composerText.value} ${text}`
        : text;
    }
  };
  instance.onerror = (event) => {
    // Common errors: 'not-allowed' (permission denied), 'no-speech',
    // 'audio-capture' (no mic), 'network'. Show the raw code; the
    // browser localises the user-facing prompt for `not-allowed`.
    speechError.value = `Microphone error: ${event.error}`;
    speechRecording.value = false;
  };
  instance.onend = () => {
    speechRecording.value = false;
  };
  recognition = instance;
}

function toggleSpeech(): void {
  if (!recognition) return;
  if (speechRecording.value) {
    recognition.stop();
    return;
  }
  speechError.value = null;
  try {
    recognition.start();
    speechRecording.value = true;
  } catch (e) {
    // start() throws if the recognizer is already running — usually
    // a desync with our own state. Reset and surface.
    speechRecording.value = false;
    speechError.value = e instanceof Error ? e.message : 'Failed to start recording.';
  }
}

/** Resolved chat-process name — needed to address `process-steer`. */
const chatProcessName = ref<string | null>(null);
const sessionDisplay = ref<string | null>(null);
const sessionResolveError = ref<string | null>(null);

const messageContainer = ref<HTMLElement | null>(null);

/**
 * Combined history + live tail. Live messages are appended in arrival order.
 * If a live message has the same id as one already in history, drop the
 * duplicate (idempotent reload).
 */
const allMessages = computed<ChatMessageDto[]>(() => {
  const seen = new Set<string>();
  const result: ChatMessageDto[] = [];
  for (const m of history.value) {
    if (m.messageId && !seen.has(m.messageId)) {
      seen.add(m.messageId);
      result.push(m);
    }
  }
  for (const m of liveMessages.value) {
    if (m.messageId && !seen.has(m.messageId)) {
      seen.add(m.messageId);
      result.push(m);
    }
  }
  return result;
});

/** Sticky chat-process draft for the optimistic streaming bubble. */
const visibleDraft = computed(() => {
  if (!chatProcessName.value) return null;
  const entry = streamingDrafts.value.get(chatProcessName.value);
  if (!entry || !entry.content) return null;
  return entry;
});

async function resolveSessionAndProcess(): Promise<void> {
  // session-list returns SessionSummary; the chatProcessName comes from
  // session-bootstrap, but on plain resume we don't have it. Fall back to
  // the well-known "session_chat" name used by SessionChatBootstrapper.
  // Worst case: send fails with 404 and we surface the error.
  sessionResolveError.value = null;
  try {
    const resp = await props.socket.send<SessionListRequest, SessionListResponse>(
      'session-list', {});
    const summary = resp.sessions?.find((s) => s.sessionId === props.sessionId);
    sessionDisplay.value = summary?.displayName ?? props.sessionId;
  } catch {
    sessionDisplay.value = props.sessionId;
  }
  // The chat-process name is fixed by SessionChatBootstrapper to
  // `CHAT_PROCESS_NAME = "chat"` — there is exactly one per session.
  // session-bootstrap's response also reports it explicitly, but
  // session-resume doesn't, so we rely on the convention.
  chatProcessName.value = 'chat';
}

function appendMessageBubble(data: ChatMessageAppendedData): void {
  // Dedupe against optimistic local echo: when the canonical user
  // message arrives from the server, drop the matching `tmp_*` entry
  // that the composer pushed at send-time. We match on role + content
  // because the optimistic entry has no server-assigned id yet.
  const optimisticIdx = liveMessages.value.findIndex(
    (m) =>
      m.messageId.startsWith(OPTIMISTIC_PREFIX) &&
      m.role === data.role &&
      m.content === data.content,
  );
  if (optimisticIdx >= 0) {
    liveMessages.value.splice(optimisticIdx, 1);
  }
  liveMessages.value.push({
    messageId: data.chatMessageId,
    thinkProcessId: data.thinkProcessId,
    role: data.role,
    content: data.content,
    createdAt: data.createdAt,
  });
  // Commit beats the pending draft of this process — clear it.
  streamingDrafts.value.delete(data.processName);
  scrollToBottom();
}

function appendChunk(data: ChatMessageChunkData): void {
  const existing = streamingDrafts.value.get(data.processName);
  if (existing && existing.role === data.role) {
    existing.content += data.chunk;
  } else {
    streamingDrafts.value.set(data.processName, {
      role: data.role,
      content: data.chunk,
      processName: data.processName,
    });
  }
  // Trigger reactivity on the Map.
  streamingDrafts.value = new Map(streamingDrafts.value);
  scrollToBottom();
}

function recordProgress(data: ProcessProgressNotification): void {
  progressEvents.value.push(data);
  if (progressEvents.value.length > PROGRESS_CAP) {
    progressEvents.value.splice(0, progressEvents.value.length - PROGRESS_CAP);
  }
}

function scrollToBottom(): void {
  nextTick(() => {
    const el = messageContainer.value;
    if (el) el.scrollTop = el.scrollHeight;
  });
}

async function send(): Promise<void> {
  const text = composerText.value.trim();
  if (!text || sending.value || !chatProcessName.value) return;
  sending.value = true;
  sendError.value = null;

  // Optimistic local echo: the server only emits chat-message-appended
  // for the user message *after* the engine drains its pending queue
  // (same lane turn that generates the assistant reply). To give the
  // user immediate feedback we render the bubble now and let
  // {@link appendMessageBubble} drop this temp entry when the
  // canonical frame arrives. If the steer fails, we remove it again.
  const optimisticId = `${OPTIMISTIC_PREFIX}${++optimisticSeq}`;
  liveMessages.value.push({
    messageId: optimisticId,
    thinkProcessId: '',
    role: 'USER' as unknown as ChatRole,
    content: text,
    createdAt: new Date(),
  });
  composerText.value = '';
  scrollToBottom();

  try {
    await props.socket.send<ProcessSteerRequest, ProcessSteerResponse>('process-steer', {
      processName: chatProcessName.value,
      content: text,
    });
  } catch (e) {
    // Roll back the optimistic bubble — the server didn't accept the
    // message at all, so the user shouldn't see it as committed.
    const idx = liveMessages.value.findIndex((m) => m.messageId === optimisticId);
    if (idx >= 0) liveMessages.value.splice(idx, 1);
    if (e instanceof WebSocketRequestError) {
      sendError.value = `${e.message} (code ${e.errorCode})`;
    } else {
      sendError.value = e instanceof Error ? e.message : 'Failed to send.';
    }
  } finally {
    sending.value = false;
  }
}

function onComposerKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Enter') return;
  if (multiline.value) {
    // Multi-line mode: Ctrl or Cmd + Enter sends; bare Enter inserts a
    // newline (textarea default behaviour, no preventDefault needed).
    if (event.ctrlKey || event.metaKey) {
      event.preventDefault();
      void send();
    }
    return;
  }
  // Single-line mode: bare Enter sends; Shift+Enter still inserts a
  // hard break in case the user wants one despite the compact UI.
  if (!event.shiftKey) {
    event.preventDefault();
    void send();
  }
}

const subscriptions: Array<() => void> = [];

onMounted(async () => {
  // Wire up live frames before history load so we don't miss anything.
  subscriptions.push(
    props.socket.on<ChatMessageAppendedData>('chat-message-appended', appendMessageBubble),
    props.socket.on<ChatMessageChunkData>('chat-message-stream-chunk', appendChunk),
    props.socket.on<ProcessProgressNotification>('process-progress', recordProgress),
  );
  initSpeechRecognition();
  await Promise.all([
    load(props.sessionId),
    resolveSessionAndProcess(),
  ]);
  scrollToBottom();
});

onBeforeUnmount(() => {
  for (const off of subscriptions) off();
  if (recognition && speechRecording.value) {
    try { recognition.stop(); } catch { /* already stopped */ }
  }
  reset();
});

watch(() => props.sessionId, async (newId, oldId) => {
  if (!newId || newId === oldId) return;
  liveMessages.value = [];
  streamingDrafts.value = new Map();
  progressEvents.value = [];
  await load(newId);
  scrollToBottom();
});
</script>

<template>
  <div class="flex h-full min-h-0">
    <!-- Main chat column -->
    <section class="flex-1 min-w-0 flex flex-col">
      <header class="px-6 py-3 border-b border-base-300 bg-base-100 flex items-center gap-3">
        <VButton variant="ghost" size="sm" @click="emit('leave')">← Sessions</VButton>
        <div class="flex-1 min-w-0 truncate">
          <span class="font-medium">{{ sessionDisplay ?? sessionId }}</span>
        </div>
      </header>

      <div ref="messageContainer" class="flex-1 min-h-0 overflow-y-auto px-6 py-4">
        <div class="max-w-3xl mx-auto flex flex-col gap-3">
          <div v-if="historyLoading" class="text-sm opacity-60">Loading history…</div>
          <VAlert v-else-if="historyError" variant="error">{{ historyError }}</VAlert>

          <MessageBubble
            v-for="msg in allMessages"
            :key="msg.messageId"
            :role="String(msg.role)"
            :content="msg.content"
            :created-at="msg.createdAt"
          />

          <MessageBubble
            v-if="visibleDraft"
            :role="String(visibleDraft.role)"
            :content="visibleDraft.content"
            :streaming="true"
          />
        </div>
      </div>

      <footer class="border-t border-base-300 bg-base-100 p-4">
        <VAlert v-if="sendError" variant="error" class="mb-2">{{ sendError }}</VAlert>
        <VAlert v-if="sessionResolveError" variant="warning" class="mb-2">{{ sessionResolveError }}</VAlert>
        <VAlert v-if="speechError" variant="warning" class="mb-2">{{ speechError }}</VAlert>
        <div class="max-w-3xl mx-auto flex gap-2 items-end">
          <VButton
            variant="ghost"
            size="sm"
            :title="multiline ? 'Switch to single-line input' : 'Switch to multi-line input'"
            @click="multiline = !multiline"
          >
            {{ multiline ? '▲' : '▼' }}
          </VButton>
          <VButton
            v-if="speechSupported"
            variant="ghost"
            size="sm"
            :class="speechRecording ? 'text-error animate-pulse' : ''"
            :title="speechRecording ? 'Stop speech-to-text' : 'Start speech-to-text'"
            @click="toggleSpeech"
          >
            🎤
          </VButton>
          <div class="flex-1">
            <VTextarea
              v-model="composerText"
              :placeholder="composerPlaceholder"
              :rows="composerRows"
              @keydown="onComposerKeydown"
            />
          </div>
          <VButton
            variant="primary"
            :disabled="!composerText.trim() || sending || !chatProcessName"
            :loading="sending"
            @click="send"
          >
            Send
          </VButton>
        </div>
      </footer>
    </section>

    <!-- Right panel: live progress feed -->
    <aside class="w-80 shrink-0 border-l border-base-300 bg-base-100 overflow-y-auto">
      <ProgressFeed :events="progressEvents" />
    </aside>
  </div>
</template>
