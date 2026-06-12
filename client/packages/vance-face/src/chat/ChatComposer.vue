<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  type BrainWsApi,
  WebSocketRequestError,
  markdownToSpeech,
  MIN_RATE,
  MAX_RATE,
  MIN_VOLUME,
  MAX_VOLUME,
} from '@vance/shared';
import {
  buildUtterance,
  isSpeechSynthesisSupported,
} from '../platform/speechWeb';
import {
  getSpeakerEnabled,
  getSpeechRate,
  getSpeechVolume,
  resolveSpeechLanguage,
  saveSpeakerEnabled,
} from '../platform/speechSettings';
import type {
  AttachmentRef,
  ChatMessageDto,
  ChatRole,
  ProcessPauseRequest,
  ProcessSteerRequest,
  ProcessSteerResponse,
} from '@vance/generated';
import {
  uploadChatboxAttachments,
  ChatboxUploadError,
} from '@composables/useChatboxUpload';
import { VAlert, VButton, VTextarea } from '@components/index';
import { OPTIMISTIC_PREFIX } from './optimisticEcho';

/**
 * Mirrors {@code ChatApp.MediationState}. Non-null while the bound
 * session is one Eddie switched us into. Lets {@link send} intercept
 * the {@code /hub} slash command so it bounces back to Eddie instead
 * of being forwarded to the mediated worker.
 */
interface MediationState {
  workerProjectName: string;
}

const props = defineProps<{
  socket: BrainWsApi;
  /** Resolved chat-process name — needed to address `process-steer`. */
  chatProcessName: string | null;
  /** Project that owns this chat session — needed for attachment uploads. */
  chatProjectId: string;
  /** Active mediation state, or null when bound to the hub directly. */
  mediation?: MediationState | null;
  /** Current follow-up suggestion (reply mode). When set AND the
   *  composer is empty, pressing Space accepts the suggestion (writes
   *  it into the input plus a trailing space, shell-autosuggestion
   *  style) instead of inserting the space. */
  followUpSuggestion?: string | null;
  /** Force the narrow-viewport tools layout (burger `⋯` toggle + popup)
   *  regardless of viewport width. Used by hosts that embed the composer
   *  in a fixed-width container (e.g. Cortex's right panel) where the
   *  `max-width: 1024px` media-query gate doesn't fire even though the
   *  composer itself has too little room. */
  compactTools?: boolean;
  /** Host-provided "current file" hint. When set, the attachment
   *  picker becomes a dropdown that offers attaching this existing
   *  document by id (no upload — it's already in the project) in
   *  addition to the regular from-computer file pick. Drives the
   *  Cortex chat panel's "attach active tab" affordance. */
  currentFileSource?: ComposerCurrentFileSource | null;
}>();

/**
 * Reference to an already-existing document in the chat's project that
 * the host wants to surface as a one-click attachment. {@code label}
 * is whatever the host wants displayed (typically the file path).
 */
export interface ComposerCurrentFileSource {
  documentId: string;
  label: string;
}

/**
 * One pending attachment that is already a server-side document — no
 * upload step needed. Distinct from {@link selectedFiles} (browser
 * {@code File} objects that need a multipart POST first).
 */
interface AttachedDoc {
  documentId: string;
  label: string;
}

const emit = defineEmits<{
  /** Slash-command interception: user typed `/hub`. Parent closes the
   *  worker socket and reopens against the hub. */
  (e: 'hub'): void;
  /** Optimistic local echo — composer pushed a temp user bubble. Parent
   *  appends to its live messages and will dedup later when the
   *  canonical server frame arrives (matched by id-prefix + role + content). */
  (e: 'local-echo', message: ChatMessageDto): void;
  /** Rollback a previously-emitted local echo because the server
   *  refused the steer. Parent removes the entry matching this id. */
  (e: 'rollback-echo', messageId: string): void;
  /** Composer text changed — parent uses this to drive the follow-up
   *  suggestion's empty-input gate. */
  (e: 'text-changed', text: string): void;
  /** User pressed Space against an active follow-up suggestion. Parent
   *  marks the suggestion as accepted so it doesn't re-appear when the
   *  composer goes empty again on the same assistant message. */
  (e: 'follow-up-accepted'): void;
  /** Composer focus state changed — parent uses this to gate the
   *  follow-up REST call (only fire when the user is plausibly about
   *  to type). */
  (e: 'focus-changed', focused: boolean): void;
}>();

const { t } = useI18n();

// ──────────────── Composer state ────────────────

const composerText = ref('');
const sending = ref(false);
const uploading = ref(false);
const sendError = ref<string | null>(null);

// Mirror composer text upward so the parent can drive the follow-up
// ghost-bubble visibility (only shown while the composer is empty).
watch(composerText, (next) => {
  emit('text-changed', next);
}, { immediate: true });

const selectedFiles = ref<File[]>([]);
const selectedDocs = ref<AttachedDoc[]>([]);
const dragActive = ref(false);

function attachCurrentFile(): void {
  const src = props.currentFileSource;
  if (!src) return;
  // Same doc shouldn't appear twice in the same turn — silently
  // dedupe so a double-click on the menu item is harmless.
  if (selectedDocs.value.some((d) => d.documentId === src.documentId)) return;
  selectedDocs.value = [...selectedDocs.value, { ...src }];
}

function removeAttachedDoc(idx: number): void {
  const next = selectedDocs.value.slice();
  next.splice(idx, 1);
  selectedDocs.value = next;
}

/**
 * Collapse the DaisyUI attachment-picker dropdown by blurring the
 * currently-focused element. Daisy's CSS-only dropdown stays open as
 * long as the trigger or any child holds focus.
 */
function closeAttachmentMenu(): void {
  const ae = document.activeElement;
  if (ae && ae instanceof HTMLElement) ae.blur();
}

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

/**
 * Narrow-viewport composer toolbar — the multiline toggle, speech
 * cluster, and file-picker collapse into a single {@code ⋯} menu
 * button on phones. Toggled open by tapping the button; closed again
 * by re-tapping it.
 */
const composerToolsOpen = ref<boolean>(false);

/** Sequence for optimistic temp message ids — never collides with server ids. */
let optimisticSeq = 0;

// ──────────────── Speech-to-text via browser SpeechRecognition ────────────────
//
// Web Speech API: standard `SpeechRecognition` in Safari 14.1+, plus a
// `webkitSpeechRecognition` alias in Chrome and Edge. Firefox ships
// the constructor only behind a build flag, so we detect at runtime
// and hide the button entirely when the API is missing — no fallback.

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
    if (gotFinal && talkMode.value) {
      noteTalkActivity();
      scheduleTalkAutoSend();
    }
  };
  instance.onerror = (event) => {
    speechError.value = t('chat.speech.microphoneError', { error: event.error });
    speechRecording.value = false;
  };
  instance.onend = () => {
    speechRecording.value = false;
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

function stopMic(): void {
  if (!recognition) return;
  if (!speechRecording.value) return;
  try { recognition.stop(); } catch { /* already stopped */ }
}

function toggleSpeech(): void {
  if (!recognition) return;
  if (talkMode.value) {
    disableTalkMode();
    return;
  }
  if (speechRecording.value) stopMic();
  else startMic();
}

// ──────────────── Speaker (text-to-speech queue) ────────────────

const speakerSupported = ref(false);
const speakerEnabled = ref(false);
const speakerSpeaking = ref(false);
/**
 * Set true by the parent once the REST history snapshot has finished
 * loading. Until then {@link speakMessage} no-ops so the speaker
 * never reads aloud backfill content.
 */
const speakerLiveReady = ref(false);
// Live rate / volume seeded from the persisted profile defaults at
// mount time. The composer exposes both as quick-adjust sliders that
// are intentionally session-only — moving them affects the running
// chat but does NOT write back to the server. The user's persistent
// defaults live on the profile page; reload the chat tab to pick up
// a fresh profile default here.
const speechRate = ref<number>(getSpeechRate());
const speechVolume = ref<number>(getSpeechVolume());

function initSpeechSynthesis(): void {
  if (!isSpeechSynthesisSupported()) return;
  speakerSupported.value = true;
  speakerEnabled.value = getSpeakerEnabled();
}

function speakMessage(content: string): void {
  if (!speakerEnabled.value || !speakerSupported.value) return;
  if (!speakerLiveReady.value) return;
  const text = markdownToSpeech(content);
  if (!text) return;
  const utter = buildUtterance(text, resolveSpeechLanguage(), {
    rate: speechRate.value,
    volume: speechVolume.value,
  });
  if (!utter) return;
  utter.onstart = () => {
    speakerSpeaking.value = true;
    if (talkMode.value && speechRecording.value) {
      stopMic();
    }
  };
  utter.onend = () => {
    if (window.speechSynthesis && !window.speechSynthesis.speaking) {
      speakerSpeaking.value = false;
      if (talkMode.value) {
        noteTalkActivity();
        if (!speechRecording.value && !sending.value) startMic();
      }
    }
  };
  utter.onerror = () => {
    speakerSpeaking.value = false;
    if (talkMode.value && !speechRecording.value && !sending.value) {
      startMic();
    }
  };
  window.speechSynthesis.speak(utter);
}

function toggleSpeaker(): void {
  if (!speakerSupported.value) return;
  if (talkMode.value) {
    disableTalkMode();
    return;
  }
  const next = !speakerEnabled.value;
  speakerEnabled.value = next;
  void saveSpeakerEnabled(next);
  if (!next) {
    window.speechSynthesis.cancel();
    speakerSpeaking.value = false;
  }
}

function onRateInput(event: Event): void {
  const value = parseFloat((event.target as HTMLInputElement).value);
  if (!Number.isFinite(value)) return;
  // Session-only override — not persisted. See speechRate / speechVolume
  // definition above.
  speechRate.value = value;
}

function onVolumeInput(event: Event): void {
  const value = parseFloat((event.target as HTMLInputElement).value);
  if (!Number.isFinite(value)) return;
  speechVolume.value = value;
}

// ──────────────── Talk-Mode (hands-free phone-call UX) ────────────────
//
// Übermode that orchestrates mic + speaker:
//   • on activation: speaker forced on, mic started, idle-timer armed.
//   • each final STT phrase appends to the composer; a 2s pause without
//     follow-up commits via auto-send (no Enter / no click needed).
//   • while the speaker is reading the reply, the mic is paused.
//   • idle-timeout (120s without mic input AND without assistant
//     output) hard-disables the whole stack.
//   • manually clicking 🎤 or 🔊 while talk mode is on also hard-
//     disables — the user taking back control.

const TALK_MODE_STORAGE_KEY = 'vance.chat.talkMode';
const TALK_MODE_IDLE_MS = 120_000;
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
  if (!speakerEnabled.value) {
    speakerEnabled.value = true;
    void saveSpeakerEnabled(true);
  }
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
  stopMic();
  if (window.speechSynthesis && window.speechSynthesis.speaking) {
    window.speechSynthesis.cancel();
  }
  speakerSpeaking.value = false;
  if (speakerEnabled.value) {
    speakerEnabled.value = false;
    void saveSpeakerEnabled(false);
  }
}

function toggleTalkMode(): void {
  if (talkMode.value) disableTalkMode();
  else enableTalkMode();
}

// ──────────────── Send / pause ────────────────

async function send(): Promise<void> {
  const text = composerText.value.trim();
  const filesSnapshot = selectedFiles.value.slice();
  const docsSnapshot = selectedDocs.value.slice();

  // While bound to a worker via Eddie's MEDIATE handover, the user can
  // type {@code /hub} to bounce back to Eddie. We intercept it here
  // so the brain's MediationEndHandler picks up the control frame
  // instead of process-steer enqueueing "/hub" as a chat message.
  if (
    props.mediation && text === '/hub'
    && filesSnapshot.length === 0
    && docsSnapshot.length === 0
  ) {
    composerText.value = '';
    emit('hub');
    return;
  }

  // Allow attachment-only sends so the user can drop a PDF and hit
  // send without typing — Arthur can then ask "what should I do with
  // this?" rather than the UI silently rejecting the click.
  if (sending.value || !props.chatProcessName) return;
  if (!text && filesSnapshot.length === 0 && docsSnapshot.length === 0) return;
  sending.value = true;
  sendError.value = null;
  clearTalkAutoSend();
  noteTalkActivity();

  // Stage 1 — upload attachments.
  let attachmentRefs: AttachmentRef[] = [];
  if (filesSnapshot.length > 0) {
    uploading.value = true;
    try {
      attachmentRefs = await uploadChatboxAttachments(
        props.chatProjectId, filesSnapshot);
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

  // Merge the upload-derived AttachmentRefs with the already-existing
  // document references (Cortex "current file" path). Existing refs go
  // first so the agent sees the user's contextual pick before the
  // ad-hoc uploads.
  const allAttachments: AttachmentRef[] = [
    ...docsSnapshot.map((d) => ({ documentId: d.documentId })),
    ...attachmentRefs,
  ];

  // Stage 2 — emit the optimistic echo (the parent appends to its
  // message stream), clear the composer, and send the steer.
  const optimisticId = `${OPTIMISTIC_PREFIX}${++optimisticSeq}`;
  const echoText = filesSnapshot.length > 0 || docsSnapshot.length > 0
    ? attachmentEchoPrefix(filesSnapshot, docsSnapshot) + text
    : text;
  emit('local-echo', {
    messageId: optimisticId,
    thinkProcessId: '',
    role: 'USER' as unknown as ChatRole,
    content: echoText,
    createdAt: new Date(),
  });
  composerText.value = '';
  selectedFiles.value = [];
  selectedDocs.value = [];

  try {
    // Per-turn voice-mode signal — see specification/voice-mode.md.
    const voiceMode = speakerEnabled.value || talkMode.value;
    await props.socket.send<ProcessSteerRequest, ProcessSteerResponse>('process-steer', {
      processName: props.chatProcessName,
      content: text,
      attachments: allAttachments.length > 0 ? allAttachments : undefined,
      voiceMode: voiceMode ? true : undefined,
    });
  } catch (e) {
    emit('rollback-echo', optimisticId);
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
 * Fire-and-forget {@code process-pause} for the bound session. Pauses
 * everything alive in the session (chat + workers) and immediately
 * drops the local "sending" spinner so the composer is usable again.
 */
function pause(): void {
  if (!sending.value) return;
  try {
    props.socket.sendNoReply<ProcessPauseRequest>('process-pause', {});
  } catch (e) {
    sendError.value = e instanceof Error ? e.message : 'Pause failed.';
  }
  sending.value = false;
}

function onComposerFocusIn(): void {
  emit('focus-changed', true);
}

function onComposerFocusOut(event: FocusEvent): void {
  // {@code focusout} bubbles before the new {@code focusin} fires, so
  // {@code relatedTarget} tells us whether focus is moving to another
  // element inside the same wrapper (e.g. the speech-rate slider in
  // the toolbar) — in which case the composer is still "active".
  const next = event.relatedTarget as Node | null;
  const root = event.currentTarget as HTMLElement;
  if (next && root.contains(next)) return;
  emit('focus-changed', false);
}

function onComposerKeydown(event: KeyboardEvent): void {
  // Follow-up acceptance: Space (or Tab) against an active suggestion
  // while the composer is empty writes the suggestion into the input
  // instead of inserting whitespace. Shell-autosuggestion style.
  if (
    !event.ctrlKey && !event.metaKey && !event.altKey &&
    composerText.value.length === 0 &&
    props.followUpSuggestion &&
    (event.key === ' ' || event.key === 'Tab')
  ) {
    event.preventDefault();
    const appendSpace = event.key === ' ';
    composerText.value = props.followUpSuggestion + (appendSpace ? ' ' : '');
    emit('follow-up-accepted');
    return;
  }
  if (event.key !== 'Enter') return;
  if (multiline.value) {
    if (event.ctrlKey || event.metaKey) {
      event.preventDefault();
      void send();
    }
    return;
  }
  if (!event.shiftKey) {
    event.preventDefault();
    void send();
  }
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
  event.preventDefault();
  dragActive.value = true;
}

function onComposerDragLeave(event: DragEvent): void {
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
  input.value = '';
}

function appendFiles(incoming: File[]): void {
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

function attachmentEchoPrefix(files: File[], docs: AttachedDoc[]): string {
  const lines: string[] = [];
  for (const d of docs) {
    lines.push(`📄 ${d.label}`);
  }
  for (const f of files) {
    lines.push(`📎 ${f.name} _(${formatBytes(f.size)})_`);
  }
  return lines.length > 0 ? lines.join('\n') + '\n\n' : '';
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

// ──────────────── Imperative API ────────────────

/** Open the TTS gate. Called by the parent once the initial REST
 *  history snapshot has loaded — from that point on, every non-USER
 *  {@code chat-message-appended} frame gets spoken when the speaker
 *  is enabled. */
function markSpeakerLive(): void {
  speakerLiveReady.value = true;
}

/** Replace the composer text — used by the wizard prompt-ready
 *  handoff and by the ASK_USER picker click. */
function setText(text: string): void {
  composerText.value = text;
}

/** Replace text AND send immediately. Used by the ASK_USER picker
 *  flow: the picker's option label is the canonical reply, no
 *  edit step expected. */
async function setTextAndSend(text: string): Promise<void> {
  if (sending.value) return;
  composerText.value = text;
  await send();
}

defineExpose({
  speakMessage,
  noteTalkActivity,
  markSpeakerLive,
  setText,
  setTextAndSend,
});

// ──────────────── Lifecycle ────────────────

onMounted(() => {
  initSpeechRecognition();
  initSpeechSynthesis();
  // Restore Talk-Mode from sessionStorage. Tab-scoped: survives a
  // session switch (this composer remounts via :key="sessionId" on
  // the parent), but not a tab restart.
  if (readTalkModeStored()) {
    enableTalkMode();
  }
});

onBeforeUnmount(() => {
  // Tear down Talk-Mode timers but keep the persisted flag — a
  // session switch (component remount) is expected to resurrect it
  // from sessionStorage on the next mount.
  clearTalkIdle();
  clearTalkAutoSend();
  if (recognition && speechRecording.value) {
    try { recognition.stop(); } catch { /* already stopped */ }
  }
  if (speakerSupported.value && window.speechSynthesis.speaking) {
    window.speechSynthesis.cancel();
  }
});
</script>

<template>
  <div
    class="p-4 relative h-full"
    :class="{ 'composer--compact': compactTools }"
    @dragenter="onComposerDragEnter"
    @dragover="onComposerDragOver"
    @dragleave="onComposerDragLeave"
    @drop="onComposerDrop"
  >
    <!-- Drag-overlay: lights up the entire composer footer while a
         file is being dragged over. pointer-events-none so the
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
    <VAlert v-if="speechError" variant="warning" class="mb-2">{{ speechError }}</VAlert>

    <!-- Pending-attachment chips. Cleared by send() on success;
         per-chip ✕ removes a single entry before send. Two flavours:
         📄 for already-existing docs (no upload step) and 📎 for
         to-be-uploaded files. -->
    <div
      v-if="selectedFiles.length > 0 || selectedDocs.length > 0"
      class="max-w-5xl mx-auto mb-2 flex flex-wrap gap-2"
    >
      <div
        v-for="(doc, idx) in selectedDocs"
        :key="`doc-${doc.documentId}`"
        class="flex items-center gap-2 px-2 py-1
               border border-primary/40 rounded bg-primary/5 text-sm"
      >
        <span aria-hidden="true">📄</span>
        <span class="font-mono truncate max-w-xs">{{ doc.label }}</span>
        <VButton
          variant="ghost"
          size="sm"
          :disabled="sending || uploading"
          :title="$t('chat.attachments.remove')"
          @click="removeAttachedDoc(idx)"
        >✕</VButton>
      </div>
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

    <div class="max-w-5xl mx-auto flex gap-2 items-end relative">
      <!-- Narrow-viewport menu toggle. CSS hides it on wide screens
           and turns .composer-tools into a popup on narrow. -->
      <VButton
        variant="ghost"
        size="sm"
        class="composer-tools-toggle"
        :title="composerToolsOpen ? 'Hide tools' : 'Show tools'"
        @click="composerToolsOpen = !composerToolsOpen"
      >
        ⋯
      </VButton>

      <div
        class="composer-tools"
        :class="{ 'composer-tools--open': composerToolsOpen }"
      >
        <VButton
          variant="ghost"
          size="sm"
          :title="multiline ? $t('chat.multilineToggleSingle') : $t('chat.multilineToggleMulti')"
          @click="multiline = !multiline"
        >
          {{ multiline ? '▲' : '▼' }}
        </VButton>
        <div v-if="speechSupported || speakerSupported" class="flex gap-1 items-center">
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
          <!-- Quick-adjust sliders for volume + rate. Saved server-side
               on every input event — these are the user's persistent
               default, not a session-only override. Voice + language
               live on the profile page. -->
          <input
            v-if="speakerSupported"
            type="range"
            class="range range-xs w-16"
            :min="MIN_VOLUME"
            :max="MAX_VOLUME"
            step="0.05"
            :value="speechVolume"
            :title="$t('chat.speech.volume') + ': ' + Math.round(speechVolume * 100) + '%'"
            @input="onVolumeInput"
          />
          <input
            v-if="speakerSupported"
            type="range"
            class="range range-xs w-16"
            :min="MIN_RATE"
            :max="MAX_RATE"
            step="0.05"
            :value="speechRate"
            :title="$t('chat.speech.rate') + ': ' + speechRate.toFixed(2) + '×'"
            @input="onRateInput"
          />
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
        <!-- Host (e.g. Cortex) declared a current-file source → render
             the 📎 button as a DaisyUI dropdown so the user can choose
             between picking a fresh file and attaching the contextual
             one without a roundtrip. Without that source we keep the
             single-click direct picker — same UX as standalone chat. -->
        <div v-if="currentFileSource" class="dropdown dropdown-top">
          <div
            tabindex="0"
            role="button"
            class="btn btn-ghost btn-sm"
            :class="{ 'btn-disabled': sending || uploading || !chatProcessName }"
            :title="$t('chat.attachments.pickerTooltip')"
          >📎</div>
          <ul
            tabindex="0"
            class="dropdown-content menu menu-sm bg-base-100 rounded-box z-[30]
                   mb-2 w-72 p-2 shadow border border-base-300"
          >
            <li>
              <a @click="closeAttachmentMenu(); fileInputRef?.click()">
                <span aria-hidden="true">📎</span>
                <span class="flex-1">{{ $t('chat.attachments.pickFromComputer') }}</span>
              </a>
            </li>
            <li>
              <a @click="closeAttachmentMenu(); attachCurrentFile()">
                <span aria-hidden="true">📄</span>
                <span class="flex-1 min-w-0">
                  <span class="block text-xs opacity-60">
                    {{ $t('chat.attachments.attachCurrentFile') }}
                  </span>
                  <span class="block truncate font-mono">{{ currentFileSource.label }}</span>
                </span>
              </a>
            </li>
          </ul>
        </div>
        <VButton
          v-else
          variant="ghost"
          size="sm"
          :disabled="sending || uploading || !chatProcessName"
          :title="$t('chat.attachments.pickerTooltip')"
          @click="() => fileInputRef?.click()"
        >
          📎
        </VButton>
      </div>
      <div class="flex-1" @focusin="onComposerFocusIn" @focusout="onComposerFocusOut">
        <VTextarea
          v-model="composerText"
          :placeholder="composerPlaceholder"
          :rows="composerRows"
          @keydown="onComposerKeydown"
        />
      </div>
      <VButton
        variant="primary"
        :disabled="(!composerText.trim() && selectedFiles.length === 0 && selectedDocs.length === 0)
          || sending || uploading || !chatProcessName"
        :loading="sending || uploading"
        :title="$t('chat.send')"
        @click="send"
      >
        ▶
      </VButton>
      <VButton
        v-if="sending"
        variant="danger"
        :title="$t('chat.pauseTooltip')"
        @click="pause"
      >
        ⏸
      </VButton>
    </div>
  </div>
</template>

<style scoped>
.composer-tools-toggle {
  display: none;
}
.composer-tools {
  display: flex;
  gap: 0.5rem;
  align-items: flex-end;
}

/* Forced-compact mode for embedded hosts (e.g. Cortex's right panel)
   where the parent column is narrow but the viewport isn't — the
   media-query gate below would otherwise leave the full toolbar in
   place and overflow horizontally. Mirrors the narrow-viewport rules
   1:1; keep both blocks in sync. */
.composer--compact .composer-tools-toggle {
  display: inline-flex;
}
.composer--compact .composer-tools {
  display: none;
}
.composer--compact .composer-tools.composer-tools--open {
  display: flex;
  position: absolute;
  bottom: calc(100% + 0.5rem);
  left: 0.5rem;
  z-index: 20;
  padding: 0.5rem;
  background-color: var(--fallback-b1, oklch(var(--b1) / 1));
  border: 1px solid var(--fallback-b3, oklch(var(--b3) / 1));
  border-radius: 0.5rem;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

/* Compact-toolbar threshold pulled up from the phone breakpoint to
   1024px so iPad-sized viewports (portrait 768, landscape 1024) get
   the popup variant — the inline toolbar plus voice / attachment
   buttons squeezed the prompt input to a few characters wide on
   tablets. Laptops at >= 1025px keep the full inline toolbar. */
@media (max-width: 1024px) {
  .composer-tools-toggle {
    display: inline-flex;
  }
  .composer-tools {
    display: none;
  }
  .composer-tools.composer-tools--open {
    display: flex;
    position: absolute;
    bottom: calc(100% + 0.5rem);
    left: 0.5rem;
    z-index: 20;
    padding: 0.5rem;
    background-color: var(--fallback-b1, oklch(var(--b1) / 1));
    border: 1px solid var(--fallback-b3, oklch(var(--b3) / 1));
    border-radius: 0.5rem;
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  }
}
</style>
