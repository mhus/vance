<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  type BrainWsApi,
  WebSocketRequestError,
  AUTO_LANGUAGE,
  SUPPORTED_SPEECH_LANGUAGES,
  getSpeechLanguage,
  resolveSpeechLanguage,
  setSpeechLanguage,
  buildUtterance,
  getSpeakerEnabled,
  getSpeechRate,
  getSpeechVoiceURI,
  getSpeechVolume,
  isSpeechSynthesisSupported,
  listVoices,
  onVoicesChanged,
  setSpeakerEnabled,
  setSpeechRate,
  setSpeechVoiceURI,
  setSpeechVolume,
  stripMarkdown,
  MIN_RATE,
  MAX_RATE,
  MIN_VOLUME,
  MAX_VOLUME,
} from '@vance/shared';
import type {
  ChatMessageAppendedData,
  ChatMessageChunkData,
  ChatMessageDto,
  ChatRole,
  ProcessPauseRequest,
  ProcessProgressNotification,
  ProcessSteerRequest,
  ProcessSteerResponse,
  SessionListRequest,
  SessionListResponse,
} from '@vance/generated';
import { useChatHistory } from '@composables/useChatHistory';
import { VAlert, VButton, VSelect, VTextarea } from '@components/index';
import MessageBubble from './MessageBubble.vue';
import ProgressFeed from './ProgressFeed.vue';

const props = defineProps<{
  socket: BrainWsApi;
  sessionId: string;
}>();

const { t } = useI18n();

const emit = defineEmits<{ (event: 'leave'): void }>();

const PROGRESS_CAP = 50;

const { messages: history, loading: historyLoading, error: historyError, load, reset } =
  useChatHistory();

/** Messages received via chat-message-appended after history load. Same shape as history. */
const liveMessages = ref<ChatMessageDto[]>([]);

/**
 * Set of {@code messageId}s that arrived from a sub-process (worker)
 * rather than the main chat process. The bubble for these renders in
 * the compact green worker variant of {@link MessageBubble}, mirroring
 * the foot client's {@code worker()} channel. History from REST is
 * filtered to the chat-process server-side, so this only ever fills
 * for live frames.
 */
const workerMessageIds = ref<Set<string>>(new Set());

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
    ? t('chat.composerPlaceholderMulti')
    : t('chat.composerPlaceholderSingle'));

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
const speechSettingsOpen = ref(false);
const speechLanguageStored = ref<string>(getSpeechLanguage());
const speechLanguageOptions = SUPPORTED_SPEECH_LANGUAGES.map((opt) => ({
  value: opt.code,
  label: opt.label,
}));
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
  instance.lang = resolveSpeechLanguage();
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
    speechError.value = t('chat.speech.microphoneError', { error: event.error });
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
  // Pick up any language change applied since the last start().
  recognition.lang = resolveSpeechLanguage();
  try {
    recognition.start();
    speechRecording.value = true;
  } catch (e) {
    // start() throws if the recognizer is already running — usually
    // a desync with our own state. Reset and surface.
    speechRecording.value = false;
    speechError.value = e instanceof Error ? e.message : t('chat.speech.recordStartFailed');
  }
}

function onLanguageChanged(code: string | null): void {
  // VSelect emits null when the placeholder option is picked; treat
  // that as "auto" so we never persist `null` as a wire value.
  const next = code ?? AUTO_LANGUAGE;
  setSpeechLanguage(next === AUTO_LANGUAGE ? null : next);
  speechLanguageStored.value = next;
  // Stop a running recognition so the next start() picks up the new
  // language; setting `lang` on a live instance is unreliable across
  // browsers.
  if (recognition && speechRecording.value) {
    recognition.stop();
  }
  // Refresh voice list — the defaults filter by language.
  refreshVoiceOptions();
}

// ──────────────── Speaker (text-to-speech queue) ────────────────
//
// SpeechSynthesis already has an internal queue: speak() enqueues,
// cancel() flushes the queue and stops the active utterance. We rely
// on that — no client-side queue plumbing needed. The toggle is
// persisted; while enabled, every non-USER `chat-message-appended`
// frame is read aloud with the configured language / voice / rate /
// volume.

const speakerSupported = ref(false);
const speakerEnabled = ref(false);
const speakerSpeaking = ref(false);
/**
 * Becomes true once the REST history snapshot has finished loading.
 * The WS subscriptions are wired *before* the load to avoid losing
 * frames, but messages that arrive in that window are typically
 * "live" anyway — we still gate speaking behind this flag so the
 * speaker only ever reads aloud what arrives over the WebSocket as
 * a new message, never anything related to the initial backfill.
 */
const speakerLiveReady = ref(false);
const speechRate = ref<number>(getSpeechRate());
const speechVolume = ref<number>(getSpeechVolume());
const speechVoiceUri = ref<string | null>(getSpeechVoiceURI());

interface VoiceOption {
  value: string;
  label: string;
  group?: string;
}
const voiceOptions = ref<VoiceOption[]>([]);
let voicesUnsubscribe: (() => void) | null = null;

function refreshVoiceOptions(): void {
  if (!speakerSupported.value) return;
  const targetLang = resolveSpeechLanguage().toLowerCase().split('-')[0];
  // Filter to voices whose `lang` matches the active language at the
  // primary-tag level (`de` matches `de-DE`, `de-AT`, ...). Most
  // platforms expose `lang` as `xx-YY`; some report `xx_YY`, so we
  // normalise the separator before comparing.
  const matching = listVoices()
    .filter((v) => v.lang.toLowerCase().replace('_', '-').split('-')[0] === targetLang)
    .slice()
    .sort((a, b) => a.name.localeCompare(b.name));
  voiceOptions.value = [
    { value: '__auto__', label: t('chat.speech.voiceAuto') },
    ...matching.map((v) => ({
      value: v.voiceURI,
      label: `${v.name} (${v.lang})${v.default ? t('chat.speech.voiceDefaultSuffix') : ''}`,
    })),
  ];
}

function initSpeechSynthesis(): void {
  if (!isSpeechSynthesisSupported()) return;
  speakerSupported.value = true;
  speakerEnabled.value = getSpeakerEnabled();
  voicesUnsubscribe = onVoicesChanged(refreshVoiceOptions);
  refreshVoiceOptions();
}

function speakMessage(content: string): void {
  if (!speakerEnabled.value || !speakerSupported.value) return;
  // Guard against speaking anything that might be tied to the initial
  // history backfill — only frames that arrive after the REST snapshot
  // has loaded count as "new from the WebSocket".
  if (!speakerLiveReady.value) return;
  const text = stripMarkdown(content);
  if (!text) return;
  const utter = buildUtterance(text, resolveSpeechLanguage());
  if (!utter) return;
  utter.onstart = () => { speakerSpeaking.value = true; };
  utter.onend = () => {
    // Stays true until the queue actually drains — the browser fires
    // `end` per utterance, so re-check whether more are queued.
    if (window.speechSynthesis && !window.speechSynthesis.speaking) {
      speakerSpeaking.value = false;
    }
  };
  utter.onerror = () => { speakerSpeaking.value = false; };
  window.speechSynthesis.speak(utter);
}

function toggleSpeaker(): void {
  if (!speakerSupported.value) return;
  const next = !speakerEnabled.value;
  speakerEnabled.value = next;
  setSpeakerEnabled(next);
  if (!next) {
    // Disable: flush the queue and stop the active utterance.
    window.speechSynthesis.cancel();
    speakerSpeaking.value = false;
  }
}

function onVoiceChanged(uri: string | null): void {
  // VSelect emits the value or null. The pseudo-option `__auto__`
  // means "let the heuristic pick a voice for the language".
  const next = uri && uri !== '__auto__' ? uri : null;
  speechVoiceUri.value = next;
  setSpeechVoiceURI(next);
}

function onRateInput(event: Event): void {
  const value = parseFloat((event.target as HTMLInputElement).value);
  if (!Number.isFinite(value)) return;
  speechRate.value = value;
  setSpeechRate(value);
}

function onVolumeInput(event: Event): void {
  const value = parseFloat((event.target as HTMLInputElement).value);
  if (!Number.isFinite(value)) return;
  speechVolume.value = value;
  setSpeechVolume(value);
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

/**
 * All currently-streaming worker drafts (sub-process chat chunks). Each
 * one renders as a compact green {@link MessageBubble} below the main
 * chat scrollback so the user sees the worker is alive even before its
 * commit frame arrives.
 */
const visibleWorkerDrafts = computed(() => {
  const out: Array<{ role: ChatRole; content: string; processName: string }> = [];
  for (const [name, entry] of streamingDrafts.value.entries()) {
    if (!entry.content) continue;
    if (name === chatProcessName.value) continue;
    out.push(entry);
  }
  return out;
});

function isWorkerProcess(processName: string | null | undefined): boolean {
  if (!processName) return false;
  if (!chatProcessName.value) return false;
  return processName !== chatProcessName.value;
}

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
  if (isWorkerProcess(data.processName)) {
    workerMessageIds.value = new Set(workerMessageIds.value).add(data.chatMessageId);
  }
  // Commit beats the pending draft of this process — clear it.
  streamingDrafts.value.delete(data.processName);
  // Speak non-USER messages from the main chat process when the
  // speaker is enabled. USER frames are the canonical echo of what
  // the user typed; worker echoes are side-channel chatter — neither
  // should be read back to the user.
  if (String(data.role) !== 'USER' && !isWorkerProcess(data.processName)) {
    speakMessage(data.content);
  }
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
      sendError.value = e instanceof Error ? e.message : t('chat.failedToSend');
    }
  } finally {
    sending.value = false;
  }
}

/**
 * Fire-and-forget {@code process-pause} for the bound session. Mirrors
 * {@code ChatInputService.requestPause} in the foot client: pauses
 * everything alive in the session (chat + workers) and immediately
 * drops the local "sending" spinner so the composer is usable again.
 * The eventual {@code process-steer} reply still resolves in the
 * background — its terminal write is harmless because {@code sending}
 * is already false.
 *
 * <p>Goes through {@code sendNoReply} so we don't block on a server
 * round-trip the user has explicitly said they don't want to wait for.
 */
function pause(): void {
  if (!sending.value) return;
  try {
    props.socket.sendNoReply<ProcessPauseRequest>('process-pause', {});
  } catch (e) {
    // Swallow — pause is best-effort. The user gets visible feedback
    // via the spinner clearing; if the brain ignores us they'll see
    // chat-message-appended frames continuing.
    sendError.value = e instanceof Error ? e.message : 'Pause failed.';
  }
  sending.value = false;
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
  initSpeechSynthesis();
  await Promise.all([
    load(props.sessionId),
    resolveSessionAndProcess(),
  ]);
  scrollToBottom();
  // From here on, any chat-message-appended frame is by definition a
  // fresh server-side event, not a history backfill — open the gate.
  speakerLiveReady.value = true;
});

onBeforeUnmount(() => {
  for (const off of subscriptions) off();
  if (recognition && speechRecording.value) {
    try { recognition.stop(); } catch { /* already stopped */ }
  }
  if (voicesUnsubscribe) voicesUnsubscribe();
  if (speakerSupported.value && window.speechSynthesis.speaking) {
    window.speechSynthesis.cancel();
  }
  reset();
});

watch(() => props.sessionId, async (newId, oldId) => {
  if (!newId || newId === oldId) return;
  liveMessages.value = [];
  workerMessageIds.value = new Set();
  streamingDrafts.value = new Map();
  progressEvents.value = [];
  sending.value = false;
  await load(newId);
  scrollToBottom();
});
</script>

<template>
  <div class="flex h-full min-h-0">
    <!-- Main chat column -->
    <section class="flex-1 min-w-0 min-h-0 flex flex-col">
      <header class="px-6 py-3 border-b border-base-300 bg-base-100 flex items-center gap-3">
        <VButton variant="ghost" size="sm" @click="emit('leave')">
          {{ $t('chat.backToSessions') }}
        </VButton>
        <div class="flex-1 min-w-0 truncate">
          <span class="font-medium">{{ sessionDisplay ?? sessionId }}</span>
        </div>
      </header>

      <div ref="messageContainer" class="flex-1 min-h-0 overflow-y-auto px-6 py-4">
        <div class="max-w-3xl mx-auto flex flex-col gap-3">
          <div v-if="historyLoading" class="text-sm opacity-60">
            {{ $t('chat.historyLoading') }}
          </div>
          <VAlert v-else-if="historyError" variant="error">{{ historyError }}</VAlert>

          <MessageBubble
            v-for="msg in allMessages"
            :key="msg.messageId"
            :role="String(msg.role)"
            :content="msg.content"
            :created-at="msg.createdAt"
            :worker="workerMessageIds.has(msg.messageId)"
          />

          <MessageBubble
            v-if="visibleDraft"
            :role="String(visibleDraft.role)"
            :content="visibleDraft.content"
            :streaming="true"
          />

          <MessageBubble
            v-for="draft in visibleWorkerDrafts"
            :key="`worker-draft-${draft.processName}`"
            :role="String(draft.role)"
            :content="draft.content"
            :worker="true"
            :process-name="draft.processName"
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
            :title="multiline ? $t('chat.multilineToggleSingle') : $t('chat.multilineToggleMulti')"
            @click="multiline = !multiline"
          >
            {{ multiline ? '▲' : '▼' }}
          </VButton>
          <div v-if="speechSupported || speakerSupported" class="relative">
            <div class="flex gap-1">
              <VButton
                v-if="speechSupported"
                variant="ghost"
                size="sm"
                :class="speechRecording ? 'text-error animate-pulse' : ''"
                :title="speechRecording ? $t('chat.speech.stopSpeechToText') : $t('chat.speech.startSpeechToText')"
                @click="toggleSpeech"
              >
                🎤
              </VButton>
              <VButton
                v-if="speakerSupported"
                variant="ghost"
                size="sm"
                :class="speakerEnabled ? (speakerSpeaking ? 'text-success animate-pulse' : 'text-success') : ''"
                :title="speakerEnabled ? $t('chat.speech.muteIncoming') : $t('chat.speech.readAloud')"
                @click="toggleSpeaker"
              >
                {{ speakerEnabled ? '🔊' : '🔇' }}
              </VButton>
              <VButton
                variant="ghost"
                size="sm"
                :title="$t('chat.speech.settings')"
                @click="speechSettingsOpen = !speechSettingsOpen"
              >
                ⚙
              </VButton>
            </div>
            <div
              v-if="speechSettingsOpen"
              class="absolute bottom-full mb-2 left-0 z-10 w-80 bg-base-100 border border-base-300 rounded shadow-lg p-3 flex flex-col gap-3"
            >
              <div>
                <div class="text-xs uppercase tracking-wide opacity-60 font-semibold mb-1">
                  {{ $t('chat.speech.language') }}
                </div>
                <VSelect
                  :model-value="speechLanguageStored"
                  :options="speechLanguageOptions"
                  @update:model-value="onLanguageChanged"
                />
              </div>

              <template v-if="speakerSupported">
                <div>
                  <div class="text-xs uppercase tracking-wide opacity-60 font-semibold mb-1">
                    {{ $t('chat.speech.voice') }}
                  </div>
                  <VSelect
                    :model-value="speechVoiceUri ?? '__auto__'"
                    :options="voiceOptions"
                    @update:model-value="onVoiceChanged"
                  />
                </div>

                <div>
                  <div class="text-xs uppercase tracking-wide opacity-60 font-semibold mb-1 flex justify-between">
                    <span>{{ $t('chat.speech.rate') }}</span>
                    <span class="opacity-70">{{ speechRate.toFixed(2) }}×</span>
                  </div>
                  <input
                    type="range"
                    class="range range-sm w-full"
                    :min="MIN_RATE"
                    :max="MAX_RATE"
                    step="0.05"
                    :value="speechRate"
                    @input="onRateInput"
                  />
                </div>

                <div>
                  <div class="text-xs uppercase tracking-wide opacity-60 font-semibold mb-1 flex justify-between">
                    <span>{{ $t('chat.speech.volume') }}</span>
                    <span class="opacity-70">{{ Math.round(speechVolume * 100) }}%</span>
                  </div>
                  <input
                    type="range"
                    class="range range-sm w-full"
                    :min="MIN_VOLUME"
                    :max="MAX_VOLUME"
                    step="0.05"
                    :value="speechVolume"
                    @input="onVolumeInput"
                  />
                </div>
              </template>

              <p class="text-xs opacity-60">
                {{ $t('chat.speech.savedLocally') }}
              </p>
            </div>
          </div>
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
            {{ $t('chat.send') }}
          </VButton>
          <VButton
            v-if="sending"
            variant="danger"
            :title="$t('chat.pauseTooltip')"
            @click="pause"
          >
            {{ $t('chat.pause') }}
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
