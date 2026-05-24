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
  getSpeakerEnabled,
  getSpeechRate,
  getSpeechVoiceURI,
  getSpeechVolume,
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
import {
  buildUtterance,
  isSpeechSynthesisSupported,
  listVoices,
  onVoicesChanged,
} from '../platform/speechWeb';
import type {
  AttachmentRef,
  ChatMessageAppendedData,
  ChatMessageChunkData,
  ChatMessageDto,
  ChatRole,
  PlanProposedNotification,
  ProcessModeChangedNotification,
  ProcessPauseRequest,
  ProcessProgressNotification,
  ProcessSteerRequest,
  ProcessSteerResponse,
  SessionListRequest,
  SessionListResponse,
  TodoItem,
  TodosUpdatedNotification,
} from '@vance/generated';
import { useChatHistory } from '@composables/useChatHistory';
import { useTenantProjects } from '@composables/useTenantProjects';
import { useDocumentRefStore } from '@/document/documentRefStore';
import {
  uploadChatboxAttachments,
  ChatboxUploadError,
} from '@composables/useChatboxUpload';
import { SessionHeader, VAlert, VButton, VSelect, VTextarea } from '@components/index';
import MessageBubble from './MessageBubble.vue';
import PlanModeIndicator from './PlanModeIndicator.vue';
import ProgressFeed from './ProgressFeed.vue';
import WizardPanel from './WizardPanel.vue';

// Wire form of {@code ProcessMode} (Jackson serialises by enum name).
// The generated TS enum is numeric so a runtime equality check would
// always miss — same string-literal pattern as MessageBubble's role.
type ProcessModeName = 'NORMAL' | 'EXPLORING' | 'PLANNING' | 'EXECUTING';

/**
 * Mirrors {@code ChatApp.MediationState}. Non-null while the bound
 * session is one Eddie switched us into. Drives the mediation banner
 * and lets {@code send()} intercept the {@code /hub} slash command
 * (spec eddie-engine.md §8.5).
 */
interface MediationState {
  workerProjectName: string;
}

const props = defineProps<{
  socket: BrainWsApi;
  sessionId: string;
  mediation?: MediationState | null;
}>();

const { t } = useI18n();

const emit = defineEmits<{
  (event: 'leave'): void;
  (event: 'hub'): void;
}>();

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

// ──────────────── Plan-Mode state (Arthur Plan-Mode flow) ────────────────
//
// Tracks the chat-process's mode plus its current TodoList and the
// metadata of the latest PROPOSE_PLAN. Driven by three WS notifications:
// {@code process-mode-changed}, {@code todos-updated}, {@code plan-proposed}.
//
// We only care about frames whose {@code processName} matches the
// chat-process — worker sub-processes never emit Plan-Mode messages
// today, but if they do the right place to surface them is the worker
// channel, not the user-facing main UI. Falling back to NORMAL clears
// todos and the plan banner so the indicator hides cleanly.

const chatProcessMode = ref<ProcessModeName>('NORMAL');
const chatTodos = ref<TodoItem[]>([]);
const planMeta = ref<{ version: number; summary?: string } | null>(null);

const modeBadge = computed<string | null>(() => {
  if (chatProcessMode.value === 'NORMAL') return null;
  return chatProcessMode.value.toLowerCase();
});

const composerText = ref('');
const sending = ref(false);
const sendError = ref<string | null>(null);

/** Right-aside tab selector — toggles between the live progress
 *  feed and the prompt-wizards panel. Default 'progress' preserves
 *  the pre-wizards behaviour for users that haven't engaged the
 *  feature yet. */
const rightTab = ref<'progress' | 'wizards'>('progress');

/** Imperative handle to {@link WizardPanel} — used by the wizard
 *  deep-link handler below to open a wizard with prefill. */
const wizardPanelRef = ref<InstanceType<typeof WizardPanel> | null>(null);

/** Receive a rendered prompt from {@link WizardPanel}: drop it into
 *  the composer, leave it to the user to inspect/edit and click Send. */
function onWizardPromptReady(prompt: string): void {
  composerText.value = prompt;
}

/** Listener for {@code vance:/wizards/<name>?kind=wizard&...} link
 *  clicks dispatched by {@code MarkdownView}. Switches the side panel
 *  to the wizards tab and opens the named wizard with the URL prefill. */
function onWizardDeepLink(ev: Event): void {
  const detail = (ev as CustomEvent<{ name?: string; prefill?: Record<string, string> }>).detail;
  if (!detail || !detail.name) return;
  rightTab.value = 'wizards';
  // Wait one tick so WizardPanel mounts before we call into it.
  void Promise.resolve().then(() => {
    wizardPanelRef.value?.openWizard(detail.name!, detail.prefill ?? {});
  });
}

/** Files the user dragged onto the composer or picked via the
 *  paperclip button. Cleared on successful send; entries are
 *  uploaded to {@code _chatbox/...} just before the steer call. */
const selectedFiles = ref<File[]>([]);
/** True while attachment uploads are in flight — drives the send-button
 *  spinner and disables further drops. Always falsy once {@code sending}
 *  is true (we don't double-spin). */
const uploading = ref(false);
/** Visual highlight while a drag-over event fires on the composer
 *  footer. Doesn't gate behaviour, just lights up the drop zone. */
const dragActive = ref(false);
/** Project that owns this chat session. Captured in
 *  {@code resolveSessionAndProcess} from the session-list response.
 *  Required for attachment uploads — the documents endpoint is
 *  project-scoped. */
const chatProjectId = ref<string>('');

// Tenant project list — used to resolve the chat project's display
// title (falls back to the technical name). The composable issues a
// single GET /projects; cheap enough to call on every chat open.
const { projects: tenantProjects, reload: loadTenantProjects } = useTenantProjects();

/** Display label for the chat's project: title when set, otherwise the
 *  technical name. Empty until {@code resolveSessionAndProcess} has run. */
const chatProjectLabel = computed<string>(() => {
  const id = chatProjectId.value;
  if (!id) return '';
  const p = tenantProjects.value.find((x) => x.name === id);
  const title = p?.title?.trim();
  return title && title.length > 0 ? title : id;
});

// Keep the embedded-document resolver and the save-as-document promote
// path informed about the chat's current project — both fall back to
// this store value when a vance:/-link or a kindbox action omits the
// authority segment. See documentRefStore + InlineKindBox.
const documentRefStore = useDocumentRefStore();
watch(chatProjectId, (id) => {
  documentRefStore.setCurrentProject(id);
}, { immediate: true });

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
    let gotFinal = false;
    for (let i = event.resultIndex; i < event.results.length; i++) {
      const r = event.results[i];
      if (!r.isFinal) continue;
      const text = (r[0].transcript ?? '').trim();
      if (!text) continue;
      composerText.value = composerText.value
        ? `${composerText.value} ${text}`
        : text;
      gotFinal = true;
    }
    // Talk-Mode: each final phrase counts as activity and re-arms the
    // auto-send debouncer. A long-enough pause (no further finals)
    // commits the composer just like the user hit ↵.
    if (gotFinal && talkMode.value) {
      noteTalkActivity();
      scheduleTalkAutoSend();
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
    // In talk mode the browser sometimes auto-stops the recognizer
    // after long silence even with continuous=true. Rearm it — but
    // not while the speaker is currently speaking (that path rearms
    // via utter.onend) and not mid-send (the assistant is about to
    // speak; mic restart belongs after the read-aloud).
    if (talkMode.value && !speakerSpeaking.value && !sending.value) {
      window.setTimeout(() => {
        if (talkMode.value && !speakerSpeaking.value && !sending.value
            && !speechRecording.value) {
          startMic();
        }
      }, 50);
    }
  };
  recognition = instance;
}

/** Idempotent mic start — used by manual toggle and by talk mode's
 *  auto-rearm. Tolerates start() throwing when the recognizer is
 *  already running (sometimes happens during quick stop→start cycles). */
function startMic(): void {
  if (!recognition) return;
  if (speechRecording.value) return;
  speechError.value = null;
  recognition.lang = resolveSpeechLanguage();
  try {
    recognition.start();
    speechRecording.value = true;
  } catch (e) {
    speechRecording.value = false;
    speechError.value = e instanceof Error ? e.message : t('chat.speech.recordStartFailed');
  }
}

/** Idempotent mic stop. Triggers instance.onend (which may re-arm in
 *  talk mode — caller's responsibility to guard against that if the
 *  stop is intentional and final). */
function stopMic(): void {
  if (!recognition) return;
  if (!speechRecording.value) return;
  try { recognition.stop(); } catch { /* already stopped */ }
}

function toggleSpeech(): void {
  if (!recognition) return;
  // Manual toggle while talk mode is on means "I'm taking back
  // control" — kill the whole orchestrator, both subsystems off.
  if (talkMode.value) {
    disableTalkMode();
    return;
  }
  if (speechRecording.value) stopMic();
  else startMic();
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
  utter.onstart = () => {
    speakerSpeaking.value = true;
    // Talk-Mode: pause the mic while Vance is talking, otherwise the
    // browser STT picks up our own output and feeds garbage back into
    // the composer. Restarts in this utter's onend once the queue drains.
    if (talkMode.value && speechRecording.value) {
      stopMic();
    }
  };
  utter.onend = () => {
    // Stays true until the queue actually drains — the browser fires
    // `end` per utterance, so re-check whether more are queued.
    if (window.speechSynthesis && !window.speechSynthesis.speaking) {
      speakerSpeaking.value = false;
      // Speaker queue drained — rearm the mic for the next user turn
      // and count that as activity so the idle-timer slides forward.
      if (talkMode.value) {
        noteTalkActivity();
        if (!speechRecording.value && !sending.value) startMic();
      }
    }
  };
  utter.onerror = () => {
    speakerSpeaking.value = false;
    // Same rearm path as onend — a TTS error shouldn't strand the
    // user without a mic in talk mode.
    if (talkMode.value && !speechRecording.value && !sending.value) {
      startMic();
    }
  };
  window.speechSynthesis.speak(utter);
}

function toggleSpeaker(): void {
  if (!speakerSupported.value) return;
  // Manual speaker toggle while talk mode is on tears down the whole
  // orchestrator — same contract as toggling the mic manually.
  if (talkMode.value) {
    disableTalkMode();
    return;
  }
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

// ──────────────── Talk-Mode (hands-free phone-call UX) ────────────────
//
// Übermode that orchestrates mic + speaker:
//   • on activation: speaker forced on, mic started, idle-timer armed.
//   • each final STT phrase appends to the composer; a 2s pause without
//     follow-up commits via auto-send (no Enter / no click needed).
//   • while the speaker is reading the reply, the mic is paused — we
//     don't want STT to chew on Vance's own output. Mic re-arms in
//     utter.onend once the queue drains.
//   • idle-timeout (120s without mic input AND without assistant
//     output) hard-disables the whole stack — mic off, speaker off.
//   • manually clicking 🎤 or 🔊 while talk mode is on also hard-
//     disables — that's the user taking back control.
//
// Persisted in sessionStorage so a session switch (ChatView remount
// via :key="activeSessionId") keeps the mode active. Tab-scoped on
// purpose: opening a new tab shouldn't inherit hands-free.

const TALK_MODE_STORAGE_KEY = 'vance.chat.talkMode';
/** Idle-off threshold. Activity = STT result, speaker start/end, send. */
const TALK_MODE_IDLE_MS = 120_000;
/** Pause length after the last STT final result that commits the
 *  composer. Tuned to feel natural for spoken sentences — long enough
 *  that mid-thought pauses don't auto-send, short enough that intended
 *  full-stops don't make the user wait. */
const TALK_MODE_AUTO_SEND_MS = 2_000;

const talkMode = ref<boolean>(false);
let talkIdleTimer: number | null = null;
let talkAutoSendTimer: number | null = null;

function readTalkModeStored(): boolean {
  try {
    return window.sessionStorage.getItem(TALK_MODE_STORAGE_KEY) === '1';
  } catch {
    return false;
  }
}

function writeTalkModeStored(on: boolean): void {
  try {
    if (on) window.sessionStorage.setItem(TALK_MODE_STORAGE_KEY, '1');
    else window.sessionStorage.removeItem(TALK_MODE_STORAGE_KEY);
  } catch { /* ignore */ }
}

function clearTalkIdle(): void {
  if (talkIdleTimer !== null) {
    window.clearTimeout(talkIdleTimer);
    talkIdleTimer = null;
  }
}

function clearTalkAutoSend(): void {
  if (talkAutoSendTimer !== null) {
    window.clearTimeout(talkAutoSendTimer);
    talkAutoSendTimer = null;
  }
}

function noteTalkActivity(): void {
  if (!talkMode.value) return;
  clearTalkIdle();
  talkIdleTimer = window.setTimeout(() => {
    speechError.value = t('chat.speech.talkModeIdleOff');
    disableTalkMode();
  }, TALK_MODE_IDLE_MS);
}

function scheduleTalkAutoSend(): void {
  clearTalkAutoSend();
  talkAutoSendTimer = window.setTimeout(() => {
    talkAutoSendTimer = null;
    if (!talkMode.value) return;
    if (!composerText.value.trim()) return;
    if (sending.value || uploading.value) return;
    // Stop the mic before sending — assistant is about to speak and
    // the mic is gated on speaker anyway. Avoids one extra onend cycle.
    stopMic();
    void send();
  }, TALK_MODE_AUTO_SEND_MS);
}

const talkModeSupported = computed<boolean>(
  () => speechSupported.value && speakerSupported.value);

function enableTalkMode(): void {
  if (talkMode.value) return;
  if (!talkModeSupported.value) return;
  talkMode.value = true;
  writeTalkModeStored(true);
  speechError.value = null;
  // Speaker on (force, even if user had it off).
  if (!speakerEnabled.value) {
    speakerEnabled.value = true;
    setSpeakerEnabled(true);
  }
  // Mic on (unless the speaker happens to be mid-utterance, which is
  // rare at toggle time — the speaker only fires on incoming messages).
  if (!speakerSpeaking.value && !speechRecording.value) {
    startMic();
  }
  noteTalkActivity();
}

function disableTalkMode(): void {
  if (!talkMode.value) return;
  talkMode.value = false;
  writeTalkModeStored(false);
  clearTalkIdle();
  clearTalkAutoSend();
  // Hard off: both subsystems. Per the user's contract: when talk
  // mode goes off — idle-timeout, manual toggle, anything — neither
  // mic nor speaker should keep running.
  stopMic();
  if (window.speechSynthesis && window.speechSynthesis.speaking) {
    window.speechSynthesis.cancel();
  }
  speakerSpeaking.value = false;
  if (speakerEnabled.value) {
    speakerEnabled.value = false;
    setSpeakerEnabled(false);
  }
}

function toggleTalkMode(): void {
  if (talkMode.value) disableTalkMode();
  else enableTalkMode();
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

/**
 * Id of the most recent ASSISTANT message that carries
 * {@code askUserOptions} AND has no subsequent USER message — the
 * only ASK_USER picker the user can still answer by clicking. Older
 * pickers grey out (their question has either been answered already
 * or got overtaken by a fresh exchange). Returns null when there is
 * no pending question.
 */
const activeAskUserMessageId = computed<string | null>(() => {
  const msgs = allMessages.value;
  for (let i = msgs.length - 1; i >= 0; i--) {
    const m = msgs[i];
    if (String(m.role) === 'USER') {
      // A user message came after any earlier ASK_USER, so there is
      // no pending question worth a picker.
      return null;
    }
    if (String(m.role) !== 'ASSISTANT') continue;
    const raw = m.meta?.['askUserOptions'];
    if (Array.isArray(raw) && raw.length > 0) {
      return m.messageId;
    }
  }
  return null;
});

function onPickAskUserOption(label: string): void {
  if (!label || !label.trim()) return;
  if (sending.value) return;
  composerText.value = label.trim();
  // Fire-and-forget — `send()` reads composerText, clears it,
  // pushes the optimistic user bubble, and dispatches the WS frame.
  // Picker click is semantically a normal user reply.
  void send();
}

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
    // Project id needed for attachment uploads — see send() / drop handler.
    chatProjectId.value = summary?.projectId ?? '';
  } catch {
    sessionDisplay.value = props.sessionId;
    chatProjectId.value = '';
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
    meta: data.meta,
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
  // Talk mode counts assistant frames (and the canonical user echo)
  // as activity — keeps the idle-timer from firing during a normal
  // back-and-forth.
  noteTalkActivity();
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

function isChatProcess(processName: string | null | undefined): boolean {
  if (!processName) return false;
  if (!chatProcessName.value) return false;
  return processName === chatProcessName.value;
}

function onProcessModeChanged(data: ProcessModeChangedNotification): void {
  if (!isChatProcess(data.processName)) return;
  const next = (data.newMode as unknown as ProcessModeName) ?? 'NORMAL';
  chatProcessMode.value = next;
  if (next === 'NORMAL') {
    // Plan/exec cycle is over — drop the panel content so the indicator
    // hides on its own. Mirrors PlanModeState.setMode() in the foot.
    chatTodos.value = [];
    planMeta.value = null;
  }
}

function onTodosUpdated(data: TodosUpdatedNotification): void {
  if (!isChatProcess(data.processName)) return;
  chatTodos.value = data.todos ?? [];
}

function onPlanProposed(data: PlanProposedNotification): void {
  if (!isChatProcess(data.processName)) return;
  planMeta.value = {
    version: data.planVersion ?? 1,
    summary: data.summary ?? undefined,
  };
}

function resetPlanModeState(): void {
  chatProcessMode.value = 'NORMAL';
  chatTodos.value = [];
  planMeta.value = null;
}

function scrollToBottom(): void {
  nextTick(() => {
    const el = messageContainer.value;
    if (el) el.scrollTop = el.scrollHeight;
  });
}

async function send(): Promise<void> {
  const text = composerText.value.trim();
  const filesSnapshot = selectedFiles.value.slice();

  // While bound to a worker via Eddie's MEDIATE handover, the user can
  // type {@code /hub} to bounce back to Eddie. We intercept it here
  // so the brain's MediationEndHandler picks up the control frame
  // instead of process-steer enqueueing "/hub" as a chat message at
  // the worker. Pre-empts the optimistic-bubble path below.
  if (props.mediation && text === '/hub' && filesSnapshot.length === 0) {
    composerText.value = '';
    emit('hub');
    return;
  }

  // Allow attachment-only sends so the user can drop a PDF and hit
  // send without typing — Arthur can then ask "what should I do with
  // this?" rather than the UI silently rejecting the click.
  if (sending.value || !chatProcessName.value) return;
  if (!text && filesSnapshot.length === 0) return;
  sending.value = true;
  sendError.value = null;
  // A pending auto-send fires through this path too — once we're here
  // it has done its job, so cancel any leftover timer that would
  // otherwise no-op on the empty composer a moment later.
  clearTalkAutoSend();
  noteTalkActivity();

  // Stage 1 — upload attachments (if any). All-or-nothing: any
  // failure aborts the send so the user doesn't end up with a
  // half-sent message that references files which never made it.
  let attachmentRefs: AttachmentRef[] = [];
  if (filesSnapshot.length > 0) {
    uploading.value = true;
    try {
      attachmentRefs = await uploadChatboxAttachments(
        chatProjectId.value, filesSnapshot);
    } catch (e) {
      if (e instanceof ChatboxUploadError) {
        sendError.value = e.message;
      } else {
        sendError.value = e instanceof Error
          ? e.message
          : 'Attachment upload failed.';
      }
      sending.value = false;
      uploading.value = false;
      return;
    }
    uploading.value = false;
  }

  // Stage 2 — steer the chat process. Optimistic local echo: the
  // server only emits chat-message-appended for the user message
  // *after* the engine drains its pending queue (same lane turn that
  // generates the assistant reply). To give the user immediate
  // feedback we render the bubble now and let
  // {@link appendMessageBubble} drop this temp entry when the
  // canonical frame arrives. If the steer fails, we remove it again.
  const optimisticId = `${OPTIMISTIC_PREFIX}${++optimisticSeq}`;
  const echoText = filesSnapshot.length > 0
    // Show the attachments inline in the optimistic bubble so the
    // user sees what they just sent — the canonical chat-message
    // from the server only carries text. Cosmetic only; gets
    // replaced once the server's frame arrives.
    ? attachmentEchoPrefix(filesSnapshot) + text
    : text;
  liveMessages.value.push({
    messageId: optimisticId,
    thinkProcessId: '',
    role: 'USER' as unknown as ChatRole,
    content: echoText,
    createdAt: new Date(),
  });
  composerText.value = '';
  selectedFiles.value = [];
  scrollToBottom();

  try {
    await props.socket.send<ProcessSteerRequest, ProcessSteerResponse>('process-steer', {
      processName: chatProcessName.value,
      content: text,
      attachments: attachmentRefs.length > 0 ? attachmentRefs : undefined,
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

/** Compose the inline echo of attachments shown in the optimistic
 *  user bubble. Plain markdown so MessageBubble renders it without a
 *  custom DTO field. The server-side frame only carries the text body
 *  and replaces this bubble on arrival. */
function attachmentEchoPrefix(files: File[]): string {
  const lines = files.map((f) => `📎 ${f.name} _(${formatBytes(f.size)})_`);
  return lines.join('\n') + (files.length > 0 ? '\n\n' : '');
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

// ──────────────── Drag-and-drop / picker ────────────────

const fileInputRef = ref<HTMLInputElement | null>(null);

function onComposerDragEnter(event: DragEvent): void {
  if (sending.value || uploading.value) return;
  event.preventDefault();
  dragActive.value = true;
}

function onComposerDragOver(event: DragEvent): void {
  if (sending.value || uploading.value) return;
  // dragover must call preventDefault for the drop event to fire.
  event.preventDefault();
  dragActive.value = true;
}

function onComposerDragLeave(event: DragEvent): void {
  // dragleave fires for child elements too; only deactivate when the
  // pointer actually leaves the drop zone.
  const related = event.relatedTarget as Node | null;
  if (related && (event.currentTarget as Node).contains(related)) return;
  dragActive.value = false;
}

function onComposerDrop(event: DragEvent): void {
  event.preventDefault();
  dragActive.value = false;
  if (sending.value || uploading.value) return;
  const incoming = event.dataTransfer?.files;
  if (!incoming || incoming.length === 0) return;
  appendFiles(Array.from(incoming));
}

function onFilePickerChange(event: Event): void {
  const input = event.target as HTMLInputElement;
  if (input.files) {
    appendFiles(Array.from(input.files));
  }
  // Reset so picking the same file twice in a row still fires `change`.
  input.value = '';
}

function appendFiles(incoming: File[]): void {
  // Dedupe by name + size + lastModified so dragging the same file
  // twice doesn't show twice — same fingerprint logic as VFileInput.
  const merged = selectedFiles.value.slice();
  const fingerprint = (f: File): string => `${f.name}|${f.size}|${f.lastModified}`;
  const seen = new Set(merged.map(fingerprint));
  for (const f of incoming) {
    const key = fingerprint(f);
    if (!seen.has(key)) {
      merged.push(f);
      seen.add(key);
    }
  }
  selectedFiles.value = merged;
}

function removeFile(index: number): void {
  const next = selectedFiles.value.slice();
  next.splice(index, 1);
  selectedFiles.value = next;
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
    props.socket.on<ProcessModeChangedNotification>(
      'process-mode-changed', onProcessModeChanged),
    props.socket.on<TodosUpdatedNotification>('todos-updated', onTodosUpdated),
    props.socket.on<PlanProposedNotification>('plan-proposed', onPlanProposed),
  );
  initSpeechRecognition();
  initSpeechSynthesis();
  await Promise.all([
    load(props.sessionId),
    resolveSessionAndProcess(),
    loadTenantProjects(),
  ]);
  scrollToBottom();
  // From here on, any chat-message-appended frame is by definition a
  // fresh server-side event, not a history backfill — open the gate.
  speakerLiveReady.value = true;
  // Restore Talk-Mode from sessionStorage. Tab-scoped: survives a
  // session switch (ChatView remounts via :key="activeSessionId"),
  // but not a tab restart. {@link enableTalkMode} no-ops if the
  // platform doesn't support both mic and speaker, so the flag
  // gracefully degrades on browsers that lack STT or TTS.
  if (readTalkModeStored()) {
    enableTalkMode();
  }
  window.addEventListener('vance-open-wizard', onWizardDeepLink);
});

onBeforeUnmount(() => {
  window.removeEventListener('vance-open-wizard', onWizardDeepLink);
  for (const off of subscriptions) off();
  // Tear down Talk-Mode timers but keep the persisted flag — a
  // session switch (component remount) is expected to resurrect it
  // from sessionStorage on the next ChatView mount.
  clearTalkIdle();
  clearTalkAutoSend();
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
  resetPlanModeState();
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
        <div
          v-if="chatProjectLabel"
          class="flex items-center gap-1 text-xs px-2 py-1 rounded
                 bg-base-200 text-base-content/80 max-w-[14rem] shrink-0"
          :title="$t('chat.projectTooltip', { name: chatProjectId })"
        >
          <span aria-hidden="true">📁</span>
          <span class="truncate font-medium">{{ chatProjectLabel }}</span>
        </div>
        <SessionHeader
          :session-id="sessionId"
          @archived="emit('leave')"
          @deleted="emit('leave')"
        />
        <span
          v-if="modeBadge"
          class="text-xs uppercase tracking-wide px-1.5 py-0.5 rounded bg-info/15 text-info border border-info/30"
          :title="$t('chat.planMode.modeBadgeTooltip')"
        >
          {{ modeBadge }}
        </span>
      </header>

      <!-- Mediation banner — Eddie handed us over to a worker; the
           composer below sends straight to that worker's Arthur. -->
      <div
        v-if="mediation"
        class="px-6 py-2 border-b border-base-300 bg-info/10 flex items-center gap-3 text-sm"
      >
        <span class="text-base">🔗</span>
        <span class="flex-1 min-w-0 truncate">
          {{ $t('chat.mediation.banner', { project: mediation.workerProjectName }) }}
        </span>
        <VButton variant="ghost" size="sm" @click="emit('hub')">
          {{ $t('chat.mediation.backToHub') }}
        </VButton>
      </div>

      <div ref="messageContainer" class="flex-1 min-h-0 overflow-y-auto px-6 py-4">
        <div class="max-w-5xl mx-auto flex flex-col gap-3">
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
            :meta="msg.meta"
            :options-actionable="msg.messageId === activeAskUserMessageId"
            @pick-option="onPickAskUserOption"
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

      <PlanModeIndicator
        :mode="chatProcessMode"
        :todos="chatTodos"
        :plan-meta="planMeta"
      />

      <footer
        class="border-t border-base-300 bg-base-100 p-4 relative"
        @dragenter="onComposerDragEnter"
        @dragover="onComposerDragOver"
        @dragleave="onComposerDragLeave"
        @drop="onComposerDrop"
      >
        <!-- Drag-overlay: lights up the entire composer footer while
             a file is being dragged over. pointer-events-none so the
             underlying composer keeps its event handlers. -->
        <div
          v-if="dragActive"
          class="absolute inset-0 flex items-center justify-center
                 border-2 border-dashed border-primary
                 bg-primary/10 rounded pointer-events-none z-10"
        >
          <span class="text-sm font-semibold text-primary">
            {{ $t('chat.attachments.dropToAttach') }}
          </span>
        </div>

        <VAlert v-if="sendError" variant="error" class="mb-2">{{ sendError }}</VAlert>
        <VAlert v-if="sessionResolveError" variant="warning" class="mb-2">{{ sessionResolveError }}</VAlert>
        <VAlert v-if="speechError" variant="warning" class="mb-2">{{ speechError }}</VAlert>

        <!-- Pending-attachment chips. Each chip carries filename +
             size + remove-X. Removed before send completes when the
             user clicks ✕; otherwise cleared by send() on success. -->
        <div
          v-if="selectedFiles.length > 0"
          class="max-w-5xl mx-auto mb-2 flex flex-wrap gap-2"
        >
          <div
            v-for="(file, idx) in selectedFiles"
            :key="`att-${file.name}-${idx}`"
            class="flex items-center gap-2 px-2 py-1
                   border border-base-300 rounded bg-base-200 text-sm"
          >
            <span aria-hidden="true">📎</span>
            <span class="font-mono truncate max-w-xs">{{ file.name }}</span>
            <span class="text-xs opacity-60">{{ formatBytes(file.size) }}</span>
            <VButton
              variant="ghost"
              size="sm"
              :disabled="sending || uploading"
              :title="$t('chat.attachments.remove')"
              @click="removeFile(idx)"
            >✕</VButton>
          </div>
        </div>

        <div class="max-w-5xl mx-auto flex gap-2 items-end">
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
                v-if="talkModeSupported"
                variant="ghost"
                size="sm"
                :class="talkMode ? 'text-success animate-pulse' : ''"
                :title="talkMode ? $t('chat.speech.talkModeStop') : $t('chat.speech.talkModeStart')"
                @click="toggleTalkMode"
              >
                📞
              </VButton>
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
          <!-- Hidden file picker — paperclip button below opens it.
               Drag-and-drop on the surrounding footer bypasses this
               input entirely; this is just the explicit-pick path. -->
          <input
            ref="fileInputRef"
            type="file"
            class="hidden"
            multiple
            @change="onFilePickerChange"
          />
          <VButton
            variant="ghost"
            size="sm"
            :disabled="sending || uploading || !chatProcessName"
            :title="$t('chat.attachments.pickerTooltip')"
            @click="() => fileInputRef?.click()"
          >
            📎
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
            :disabled="(!composerText.trim() && selectedFiles.length === 0)
              || sending || uploading || !chatProcessName"
            :loading="sending || uploading"
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

    <!-- Right panel: tabbed live progress + wizards -->
    <aside class="w-80 shrink-0 border-l border-base-300 bg-base-100 overflow-y-auto flex flex-col">
      <div class="flex border-b border-base-300 text-xs uppercase tracking-wide font-semibold">
        <button
          type="button"
          :class="['flex-1 py-2 transition-colors',
                   rightTab === 'progress'
                     ? 'bg-base-100 border-b-2 border-primary'
                     : 'bg-base-200 opacity-70 hover:opacity-100']"
          @click="rightTab = 'progress'"
        >
          {{ $t('chat.wizards.progressTabLabel') }}
        </button>
        <button
          type="button"
          :class="['flex-1 py-2 transition-colors',
                   rightTab === 'wizards'
                     ? 'bg-base-100 border-b-2 border-primary'
                     : 'bg-base-200 opacity-70 hover:opacity-100']"
          @click="rightTab = 'wizards'"
        >
          {{ $t('chat.wizards.tabLabel') }}
        </button>
      </div>

      <ProgressFeed v-if="rightTab === 'progress'" :events="progressEvents" />
      <WizardPanel
        v-else
        ref="wizardPanelRef"
        :project-id="chatProjectId || undefined"
        :session-key="chatProcessName ?? undefined"
        @prompt-ready="onWizardPromptReady"
      />
    </aside>
  </div>
</template>
