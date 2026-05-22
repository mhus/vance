import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { WebSocketRequestError, AUTO_LANGUAGE, SUPPORTED_SPEECH_LANGUAGES, getSpeechLanguage, resolveSpeechLanguage, setSpeechLanguage, getSpeakerEnabled, getSpeechRate, getSpeechVoiceURI, getSpeechVolume, setSpeakerEnabled, setSpeechRate, setSpeechVoiceURI, setSpeechVolume, stripMarkdown, MIN_RATE, MAX_RATE, MIN_VOLUME, MAX_VOLUME, } from '@vance/shared';
import { buildUtterance, isSpeechSynthesisSupported, listVoices, onVoicesChanged, } from '../platform/speechWeb';
import { useChatHistory } from '@composables/useChatHistory';
import { useDocumentRefStore } from '@/document/documentRefStore';
import { uploadChatboxAttachments, ChatboxUploadError, } from '@composables/useChatboxUpload';
import { SessionHeader, VAlert, VButton, VSelect, VTextarea } from '@components/index';
import MessageBubble from './MessageBubble.vue';
import PlanModeIndicator from './PlanModeIndicator.vue';
import ProgressFeed from './ProgressFeed.vue';
const props = defineProps();
const { t } = useI18n();
const emit = defineEmits();
const PROGRESS_CAP = 50;
const { messages: history, loading: historyLoading, error: historyError, load, reset } = useChatHistory();
/** Messages received via chat-message-appended after history load. Same shape as history. */
const liveMessages = ref([]);
/**
 * Set of {@code messageId}s that arrived from a sub-process (worker)
 * rather than the main chat process. The bubble for these renders in
 * the compact green worker variant of {@link MessageBubble}, mirroring
 * the foot client's {@code worker()} channel. History from REST is
 * filtered to the chat-process server-side, so this only ever fills
 * for live frames.
 */
const workerMessageIds = ref(new Set());
/** Per-process buffer of streaming chunks waiting for their commit frame. */
const streamingDrafts = ref(new Map());
const progressEvents = ref([]);
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
const chatProcessMode = ref('NORMAL');
const chatTodos = ref([]);
const planMeta = ref(null);
const modeBadge = computed(() => {
    if (chatProcessMode.value === 'NORMAL')
        return null;
    return chatProcessMode.value.toLowerCase();
});
const composerText = ref('');
const sending = ref(false);
const sendError = ref(null);
/** Files the user dragged onto the composer or picked via the
 *  paperclip button. Cleared on successful send; entries are
 *  uploaded to {@code _chatbox/...} just before the steer call. */
const selectedFiles = ref([]);
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
const chatProjectId = ref('');
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
const composerPlaceholder = computed(() => multiline.value
    ? t('chat.composerPlaceholderMulti')
    : t('chat.composerPlaceholderSingle'));
/** Sequence for optimistic temp message ids — never collides with server ids. */
let optimisticSeq = 0;
const OPTIMISTIC_PREFIX = 'tmp_';
const speechSupported = ref(false);
const speechRecording = ref(false);
const speechError = ref(null);
const speechSettingsOpen = ref(false);
const speechLanguageStored = ref(getSpeechLanguage());
const speechLanguageOptions = SUPPORTED_SPEECH_LANGUAGES.map((opt) => ({
    value: opt.code,
    label: opt.label,
}));
let recognition = null;
function initSpeechRecognition() {
    const w = window;
    const Ctor = w.SpeechRecognition ?? w.webkitSpeechRecognition;
    if (!Ctor)
        return;
    speechSupported.value = true;
    const instance = new Ctor();
    instance.continuous = true;
    instance.interimResults = false;
    instance.lang = resolveSpeechLanguage();
    instance.onresult = (event) => {
        for (let i = event.resultIndex; i < event.results.length; i++) {
            const r = event.results[i];
            if (!r.isFinal)
                continue;
            const text = (r[0].transcript ?? '').trim();
            if (!text)
                continue;
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
function toggleSpeech() {
    if (!recognition)
        return;
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
    }
    catch (e) {
        // start() throws if the recognizer is already running — usually
        // a desync with our own state. Reset and surface.
        speechRecording.value = false;
        speechError.value = e instanceof Error ? e.message : t('chat.speech.recordStartFailed');
    }
}
function onLanguageChanged(code) {
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
const speechRate = ref(getSpeechRate());
const speechVolume = ref(getSpeechVolume());
const speechVoiceUri = ref(getSpeechVoiceURI());
const voiceOptions = ref([]);
let voicesUnsubscribe = null;
function refreshVoiceOptions() {
    if (!speakerSupported.value)
        return;
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
function initSpeechSynthesis() {
    if (!isSpeechSynthesisSupported())
        return;
    speakerSupported.value = true;
    speakerEnabled.value = getSpeakerEnabled();
    voicesUnsubscribe = onVoicesChanged(refreshVoiceOptions);
    refreshVoiceOptions();
}
function speakMessage(content) {
    if (!speakerEnabled.value || !speakerSupported.value)
        return;
    // Guard against speaking anything that might be tied to the initial
    // history backfill — only frames that arrive after the REST snapshot
    // has loaded count as "new from the WebSocket".
    if (!speakerLiveReady.value)
        return;
    const text = stripMarkdown(content);
    if (!text)
        return;
    const utter = buildUtterance(text, resolveSpeechLanguage());
    if (!utter)
        return;
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
function toggleSpeaker() {
    if (!speakerSupported.value)
        return;
    const next = !speakerEnabled.value;
    speakerEnabled.value = next;
    setSpeakerEnabled(next);
    if (!next) {
        // Disable: flush the queue and stop the active utterance.
        window.speechSynthesis.cancel();
        speakerSpeaking.value = false;
    }
}
function onVoiceChanged(uri) {
    // VSelect emits the value or null. The pseudo-option `__auto__`
    // means "let the heuristic pick a voice for the language".
    const next = uri && uri !== '__auto__' ? uri : null;
    speechVoiceUri.value = next;
    setSpeechVoiceURI(next);
}
function onRateInput(event) {
    const value = parseFloat(event.target.value);
    if (!Number.isFinite(value))
        return;
    speechRate.value = value;
    setSpeechRate(value);
}
function onVolumeInput(event) {
    const value = parseFloat(event.target.value);
    if (!Number.isFinite(value))
        return;
    speechVolume.value = value;
    setSpeechVolume(value);
}
/** Resolved chat-process name — needed to address `process-steer`. */
const chatProcessName = ref(null);
const sessionDisplay = ref(null);
const sessionResolveError = ref(null);
const messageContainer = ref(null);
/**
 * Combined history + live tail. Live messages are appended in arrival order.
 * If a live message has the same id as one already in history, drop the
 * duplicate (idempotent reload).
 */
const allMessages = computed(() => {
    const seen = new Set();
    const result = [];
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
const activeAskUserMessageId = computed(() => {
    const msgs = allMessages.value;
    for (let i = msgs.length - 1; i >= 0; i--) {
        const m = msgs[i];
        if (String(m.role) === 'USER') {
            // A user message came after any earlier ASK_USER, so there is
            // no pending question worth a picker.
            return null;
        }
        if (String(m.role) !== 'ASSISTANT')
            continue;
        const raw = m.meta?.['askUserOptions'];
        if (Array.isArray(raw) && raw.length > 0) {
            return m.messageId;
        }
    }
    return null;
});
function onPickAskUserOption(label) {
    if (!label || !label.trim())
        return;
    if (sending.value)
        return;
    composerText.value = label.trim();
    // Fire-and-forget — `send()` reads composerText, clears it,
    // pushes the optimistic user bubble, and dispatches the WS frame.
    // Picker click is semantically a normal user reply.
    void send();
}
/** Sticky chat-process draft for the optimistic streaming bubble. */
const visibleDraft = computed(() => {
    if (!chatProcessName.value)
        return null;
    const entry = streamingDrafts.value.get(chatProcessName.value);
    if (!entry || !entry.content)
        return null;
    return entry;
});
/**
 * All currently-streaming worker drafts (sub-process chat chunks). Each
 * one renders as a compact green {@link MessageBubble} below the main
 * chat scrollback so the user sees the worker is alive even before its
 * commit frame arrives.
 */
const visibleWorkerDrafts = computed(() => {
    const out = [];
    for (const [name, entry] of streamingDrafts.value.entries()) {
        if (!entry.content)
            continue;
        if (name === chatProcessName.value)
            continue;
        out.push(entry);
    }
    return out;
});
function isWorkerProcess(processName) {
    if (!processName)
        return false;
    if (!chatProcessName.value)
        return false;
    return processName !== chatProcessName.value;
}
async function resolveSessionAndProcess() {
    // session-list returns SessionSummary; the chatProcessName comes from
    // session-bootstrap, but on plain resume we don't have it. Fall back to
    // the well-known "session_chat" name used by SessionChatBootstrapper.
    // Worst case: send fails with 404 and we surface the error.
    sessionResolveError.value = null;
    try {
        const resp = await props.socket.send('session-list', {});
        const summary = resp.sessions?.find((s) => s.sessionId === props.sessionId);
        sessionDisplay.value = summary?.displayName ?? props.sessionId;
        // Project id needed for attachment uploads — see send() / drop handler.
        chatProjectId.value = summary?.projectId ?? '';
    }
    catch {
        sessionDisplay.value = props.sessionId;
        chatProjectId.value = '';
    }
    // The chat-process name is fixed by SessionChatBootstrapper to
    // `CHAT_PROCESS_NAME = "chat"` — there is exactly one per session.
    // session-bootstrap's response also reports it explicitly, but
    // session-resume doesn't, so we rely on the convention.
    chatProcessName.value = 'chat';
}
function appendMessageBubble(data) {
    // Dedupe against optimistic local echo: when the canonical user
    // message arrives from the server, drop the matching `tmp_*` entry
    // that the composer pushed at send-time. We match on role + content
    // because the optimistic entry has no server-assigned id yet.
    const optimisticIdx = liveMessages.value.findIndex((m) => m.messageId.startsWith(OPTIMISTIC_PREFIX) &&
        m.role === data.role &&
        m.content === data.content);
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
    scrollToBottom();
}
function appendChunk(data) {
    const existing = streamingDrafts.value.get(data.processName);
    if (existing && existing.role === data.role) {
        existing.content += data.chunk;
    }
    else {
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
function recordProgress(data) {
    progressEvents.value.push(data);
    if (progressEvents.value.length > PROGRESS_CAP) {
        progressEvents.value.splice(0, progressEvents.value.length - PROGRESS_CAP);
    }
}
function isChatProcess(processName) {
    if (!processName)
        return false;
    if (!chatProcessName.value)
        return false;
    return processName === chatProcessName.value;
}
function onProcessModeChanged(data) {
    if (!isChatProcess(data.processName))
        return;
    const next = data.newMode ?? 'NORMAL';
    chatProcessMode.value = next;
    if (next === 'NORMAL') {
        // Plan/exec cycle is over — drop the panel content so the indicator
        // hides on its own. Mirrors PlanModeState.setMode() in the foot.
        chatTodos.value = [];
        planMeta.value = null;
    }
}
function onTodosUpdated(data) {
    if (!isChatProcess(data.processName))
        return;
    chatTodos.value = data.todos ?? [];
}
function onPlanProposed(data) {
    if (!isChatProcess(data.processName))
        return;
    planMeta.value = {
        version: data.planVersion ?? 1,
        summary: data.summary ?? undefined,
    };
}
function resetPlanModeState() {
    chatProcessMode.value = 'NORMAL';
    chatTodos.value = [];
    planMeta.value = null;
}
function scrollToBottom() {
    nextTick(() => {
        const el = messageContainer.value;
        if (el)
            el.scrollTop = el.scrollHeight;
    });
}
async function send() {
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
    if (sending.value || !chatProcessName.value)
        return;
    if (!text && filesSnapshot.length === 0)
        return;
    sending.value = true;
    sendError.value = null;
    // Stage 1 — upload attachments (if any). All-or-nothing: any
    // failure aborts the send so the user doesn't end up with a
    // half-sent message that references files which never made it.
    let attachmentRefs = [];
    if (filesSnapshot.length > 0) {
        uploading.value = true;
        try {
            attachmentRefs = await uploadChatboxAttachments(chatProjectId.value, filesSnapshot);
        }
        catch (e) {
            if (e instanceof ChatboxUploadError) {
                sendError.value = e.message;
            }
            else {
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
        role: 'USER',
        content: echoText,
        createdAt: new Date(),
    });
    composerText.value = '';
    selectedFiles.value = [];
    scrollToBottom();
    try {
        await props.socket.send('process-steer', {
            processName: chatProcessName.value,
            content: text,
            attachments: attachmentRefs.length > 0 ? attachmentRefs : undefined,
        });
    }
    catch (e) {
        // Roll back the optimistic bubble — the server didn't accept the
        // message at all, so the user shouldn't see it as committed.
        const idx = liveMessages.value.findIndex((m) => m.messageId === optimisticId);
        if (idx >= 0)
            liveMessages.value.splice(idx, 1);
        if (e instanceof WebSocketRequestError) {
            sendError.value = `${e.message} (code ${e.errorCode})`;
        }
        else {
            sendError.value = e instanceof Error ? e.message : t('chat.failedToSend');
        }
    }
    finally {
        sending.value = false;
    }
}
/** Compose the inline echo of attachments shown in the optimistic
 *  user bubble. Plain markdown so MessageBubble renders it without a
 *  custom DTO field. The server-side frame only carries the text body
 *  and replaces this bubble on arrival. */
function attachmentEchoPrefix(files) {
    const lines = files.map((f) => `📎 ${f.name} _(${formatBytes(f.size)})_`);
    return lines.join('\n') + (files.length > 0 ? '\n\n' : '');
}
function formatBytes(bytes) {
    if (bytes < 1024)
        return `${bytes} B`;
    if (bytes < 1024 * 1024)
        return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}
// ──────────────── Drag-and-drop / picker ────────────────
const fileInputRef = ref(null);
function onComposerDragEnter(event) {
    if (sending.value || uploading.value)
        return;
    event.preventDefault();
    dragActive.value = true;
}
function onComposerDragOver(event) {
    if (sending.value || uploading.value)
        return;
    // dragover must call preventDefault for the drop event to fire.
    event.preventDefault();
    dragActive.value = true;
}
function onComposerDragLeave(event) {
    // dragleave fires for child elements too; only deactivate when the
    // pointer actually leaves the drop zone.
    const related = event.relatedTarget;
    if (related && event.currentTarget.contains(related))
        return;
    dragActive.value = false;
}
function onComposerDrop(event) {
    event.preventDefault();
    dragActive.value = false;
    if (sending.value || uploading.value)
        return;
    const incoming = event.dataTransfer?.files;
    if (!incoming || incoming.length === 0)
        return;
    appendFiles(Array.from(incoming));
}
function onFilePickerChange(event) {
    const input = event.target;
    if (input.files) {
        appendFiles(Array.from(input.files));
    }
    // Reset so picking the same file twice in a row still fires `change`.
    input.value = '';
}
function appendFiles(incoming) {
    // Dedupe by name + size + lastModified so dragging the same file
    // twice doesn't show twice — same fingerprint logic as VFileInput.
    const merged = selectedFiles.value.slice();
    const fingerprint = (f) => `${f.name}|${f.size}|${f.lastModified}`;
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
function removeFile(index) {
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
function pause() {
    if (!sending.value)
        return;
    try {
        props.socket.sendNoReply('process-pause', {});
    }
    catch (e) {
        // Swallow — pause is best-effort. The user gets visible feedback
        // via the spinner clearing; if the brain ignores us they'll see
        // chat-message-appended frames continuing.
        sendError.value = e instanceof Error ? e.message : 'Pause failed.';
    }
    sending.value = false;
}
function onComposerKeydown(event) {
    if (event.key !== 'Enter')
        return;
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
const subscriptions = [];
onMounted(async () => {
    // Wire up live frames before history load so we don't miss anything.
    subscriptions.push(props.socket.on('chat-message-appended', appendMessageBubble), props.socket.on('chat-message-stream-chunk', appendChunk), props.socket.on('process-progress', recordProgress), props.socket.on('process-mode-changed', onProcessModeChanged), props.socket.on('todos-updated', onTodosUpdated), props.socket.on('plan-proposed', onPlanProposed));
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
    for (const off of subscriptions)
        off();
    if (recognition && speechRecording.value) {
        try {
            recognition.stop();
        }
        catch { /* already stopped */ }
    }
    if (voicesUnsubscribe)
        voicesUnsubscribe();
    if (speakerSupported.value && window.speechSynthesis.speaking) {
        window.speechSynthesis.cancel();
    }
    reset();
});
watch(() => props.sessionId, async (newId, oldId) => {
    if (!newId || newId === oldId)
        return;
    liveMessages.value = [];
    workerMessageIds.value = new Set();
    streamingDrafts.value = new Map();
    progressEvents.value = [];
    sending.value = false;
    resetPlanModeState();
    await load(newId);
    scrollToBottom();
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex h-full min-h-0" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({
    ...{ class: "flex-1 min-w-0 min-h-0 flex flex-col" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.header, __VLS_intrinsicElements.header)({
    ...{ class: "px-6 py-3 border-b border-base-300 bg-base-100 flex items-center gap-3" },
});
const __VLS_0 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
}));
const __VLS_2 = __VLS_1({
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
let __VLS_4;
let __VLS_5;
let __VLS_6;
const __VLS_7 = {
    onClick: (...[$event]) => {
        __VLS_ctx.emit('leave');
    }
};
__VLS_3.slots.default;
(__VLS_ctx.$t('chat.backToSessions'));
var __VLS_3;
const __VLS_8 = {}.SessionHeader;
/** @type {[typeof __VLS_components.SessionHeader, ]} */ ;
// @ts-ignore
const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
    ...{ 'onArchived': {} },
    ...{ 'onDeleted': {} },
    sessionId: (__VLS_ctx.sessionId),
}));
const __VLS_10 = __VLS_9({
    ...{ 'onArchived': {} },
    ...{ 'onDeleted': {} },
    sessionId: (__VLS_ctx.sessionId),
}, ...__VLS_functionalComponentArgsRest(__VLS_9));
let __VLS_12;
let __VLS_13;
let __VLS_14;
const __VLS_15 = {
    onArchived: (...[$event]) => {
        __VLS_ctx.emit('leave');
    }
};
const __VLS_16 = {
    onDeleted: (...[$event]) => {
        __VLS_ctx.emit('leave');
    }
};
var __VLS_11;
if (__VLS_ctx.modeBadge) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-xs uppercase tracking-wide px-1.5 py-0.5 rounded bg-info/15 text-info border border-info/30" },
        title: (__VLS_ctx.$t('chat.planMode.modeBadgeTooltip')),
    });
    (__VLS_ctx.modeBadge);
}
if (__VLS_ctx.mediation) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "px-6 py-2 border-b border-base-300 bg-info/10 flex items-center gap-3 text-sm" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-base" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "flex-1 min-w-0 truncate" },
    });
    (__VLS_ctx.$t('chat.mediation.banner', { project: __VLS_ctx.mediation.workerProjectName }));
    const __VLS_17 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_18 = __VLS_asFunctionalComponent(__VLS_17, new __VLS_17({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }));
    const __VLS_19 = __VLS_18({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }, ...__VLS_functionalComponentArgsRest(__VLS_18));
    let __VLS_21;
    let __VLS_22;
    let __VLS_23;
    const __VLS_24 = {
        onClick: (...[$event]) => {
            if (!(__VLS_ctx.mediation))
                return;
            __VLS_ctx.emit('hub');
        }
    };
    __VLS_20.slots.default;
    (__VLS_ctx.$t('chat.mediation.backToHub'));
    var __VLS_20;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ref: "messageContainer",
    ...{ class: "flex-1 min-h-0 overflow-y-auto px-6 py-4" },
});
/** @type {typeof __VLS_ctx.messageContainer} */ ;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "max-w-3xl mx-auto flex flex-col gap-3" },
});
if (__VLS_ctx.historyLoading) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-60" },
    });
    (__VLS_ctx.$t('chat.historyLoading'));
}
else if (__VLS_ctx.historyError) {
    const __VLS_25 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_26 = __VLS_asFunctionalComponent(__VLS_25, new __VLS_25({
        variant: "error",
    }));
    const __VLS_27 = __VLS_26({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_26));
    __VLS_28.slots.default;
    (__VLS_ctx.historyError);
    var __VLS_28;
}
for (const [msg] of __VLS_getVForSourceType((__VLS_ctx.allMessages))) {
    /** @type {[typeof MessageBubble, ]} */ ;
    // @ts-ignore
    const __VLS_29 = __VLS_asFunctionalComponent(MessageBubble, new MessageBubble({
        ...{ 'onPickOption': {} },
        key: (msg.messageId),
        role: (String(msg.role)),
        content: (msg.content),
        createdAt: (msg.createdAt),
        worker: (__VLS_ctx.workerMessageIds.has(msg.messageId)),
        meta: (msg.meta),
        optionsActionable: (msg.messageId === __VLS_ctx.activeAskUserMessageId),
    }));
    const __VLS_30 = __VLS_29({
        ...{ 'onPickOption': {} },
        key: (msg.messageId),
        role: (String(msg.role)),
        content: (msg.content),
        createdAt: (msg.createdAt),
        worker: (__VLS_ctx.workerMessageIds.has(msg.messageId)),
        meta: (msg.meta),
        optionsActionable: (msg.messageId === __VLS_ctx.activeAskUserMessageId),
    }, ...__VLS_functionalComponentArgsRest(__VLS_29));
    let __VLS_32;
    let __VLS_33;
    let __VLS_34;
    const __VLS_35 = {
        onPickOption: (__VLS_ctx.onPickAskUserOption)
    };
    var __VLS_31;
}
if (__VLS_ctx.visibleDraft) {
    /** @type {[typeof MessageBubble, ]} */ ;
    // @ts-ignore
    const __VLS_36 = __VLS_asFunctionalComponent(MessageBubble, new MessageBubble({
        role: (String(__VLS_ctx.visibleDraft.role)),
        content: (__VLS_ctx.visibleDraft.content),
        streaming: (true),
    }));
    const __VLS_37 = __VLS_36({
        role: (String(__VLS_ctx.visibleDraft.role)),
        content: (__VLS_ctx.visibleDraft.content),
        streaming: (true),
    }, ...__VLS_functionalComponentArgsRest(__VLS_36));
}
for (const [draft] of __VLS_getVForSourceType((__VLS_ctx.visibleWorkerDrafts))) {
    /** @type {[typeof MessageBubble, ]} */ ;
    // @ts-ignore
    const __VLS_39 = __VLS_asFunctionalComponent(MessageBubble, new MessageBubble({
        key: (`worker-draft-${draft.processName}`),
        role: (String(draft.role)),
        content: (draft.content),
        worker: (true),
        processName: (draft.processName),
        streaming: (true),
    }));
    const __VLS_40 = __VLS_39({
        key: (`worker-draft-${draft.processName}`),
        role: (String(draft.role)),
        content: (draft.content),
        worker: (true),
        processName: (draft.processName),
        streaming: (true),
    }, ...__VLS_functionalComponentArgsRest(__VLS_39));
}
/** @type {[typeof PlanModeIndicator, ]} */ ;
// @ts-ignore
const __VLS_42 = __VLS_asFunctionalComponent(PlanModeIndicator, new PlanModeIndicator({
    mode: (__VLS_ctx.chatProcessMode),
    todos: (__VLS_ctx.chatTodos),
    planMeta: (__VLS_ctx.planMeta),
}));
const __VLS_43 = __VLS_42({
    mode: (__VLS_ctx.chatProcessMode),
    todos: (__VLS_ctx.chatTodos),
    planMeta: (__VLS_ctx.planMeta),
}, ...__VLS_functionalComponentArgsRest(__VLS_42));
__VLS_asFunctionalElement(__VLS_intrinsicElements.footer, __VLS_intrinsicElements.footer)({
    ...{ onDragenter: (__VLS_ctx.onComposerDragEnter) },
    ...{ onDragover: (__VLS_ctx.onComposerDragOver) },
    ...{ onDragleave: (__VLS_ctx.onComposerDragLeave) },
    ...{ onDrop: (__VLS_ctx.onComposerDrop) },
    ...{ class: "border-t border-base-300 bg-base-100 p-4 relative" },
});
if (__VLS_ctx.dragActive) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "\u0061\u0062\u0073\u006f\u006c\u0075\u0074\u0065\u0020\u0069\u006e\u0073\u0065\u0074\u002d\u0030\u0020\u0066\u006c\u0065\u0078\u0020\u0069\u0074\u0065\u006d\u0073\u002d\u0063\u0065\u006e\u0074\u0065\u0072\u0020\u006a\u0075\u0073\u0074\u0069\u0066\u0079\u002d\u0063\u0065\u006e\u0074\u0065\u0072\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u002d\u0032\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u002d\u0064\u0061\u0073\u0068\u0065\u0064\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u002d\u0070\u0072\u0069\u006d\u0061\u0072\u0079\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0062\u0067\u002d\u0070\u0072\u0069\u006d\u0061\u0072\u0079\u002f\u0031\u0030\u0020\u0072\u006f\u0075\u006e\u0064\u0065\u0064\u0020\u0070\u006f\u0069\u006e\u0074\u0065\u0072\u002d\u0065\u0076\u0065\u006e\u0074\u0073\u002d\u006e\u006f\u006e\u0065\u0020\u007a\u002d\u0031\u0030" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-sm font-semibold text-primary" },
    });
    (__VLS_ctx.$t('chat.attachments.dropToAttach'));
}
if (__VLS_ctx.sendError) {
    const __VLS_45 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_46 = __VLS_asFunctionalComponent(__VLS_45, new __VLS_45({
        variant: "error",
        ...{ class: "mb-2" },
    }));
    const __VLS_47 = __VLS_46({
        variant: "error",
        ...{ class: "mb-2" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_46));
    __VLS_48.slots.default;
    (__VLS_ctx.sendError);
    var __VLS_48;
}
if (__VLS_ctx.sessionResolveError) {
    const __VLS_49 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_50 = __VLS_asFunctionalComponent(__VLS_49, new __VLS_49({
        variant: "warning",
        ...{ class: "mb-2" },
    }));
    const __VLS_51 = __VLS_50({
        variant: "warning",
        ...{ class: "mb-2" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_50));
    __VLS_52.slots.default;
    (__VLS_ctx.sessionResolveError);
    var __VLS_52;
}
if (__VLS_ctx.speechError) {
    const __VLS_53 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_54 = __VLS_asFunctionalComponent(__VLS_53, new __VLS_53({
        variant: "warning",
        ...{ class: "mb-2" },
    }));
    const __VLS_55 = __VLS_54({
        variant: "warning",
        ...{ class: "mb-2" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_54));
    __VLS_56.slots.default;
    (__VLS_ctx.speechError);
    var __VLS_56;
}
if (__VLS_ctx.selectedFiles.length > 0) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "max-w-3xl mx-auto mb-2 flex flex-wrap gap-2" },
    });
    for (const [file, idx] of __VLS_getVForSourceType((__VLS_ctx.selectedFiles))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            key: (`att-${file.name}-${idx}`),
            ...{ class: "\u0066\u006c\u0065\u0078\u0020\u0069\u0074\u0065\u006d\u0073\u002d\u0063\u0065\u006e\u0074\u0065\u0072\u0020\u0067\u0061\u0070\u002d\u0032\u0020\u0070\u0078\u002d\u0032\u0020\u0070\u0079\u002d\u0031\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u002d\u0062\u0061\u0073\u0065\u002d\u0033\u0030\u0030\u0020\u0072\u006f\u0075\u006e\u0064\u0065\u0064\u0020\u0062\u0067\u002d\u0062\u0061\u0073\u0065\u002d\u0032\u0030\u0030\u0020\u0074\u0065\u0078\u0074\u002d\u0073\u006d" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            'aria-hidden': "true",
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "font-mono truncate max-w-xs" },
        });
        (file.name);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-xs opacity-60" },
        });
        (__VLS_ctx.formatBytes(file.size));
        const __VLS_57 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_58 = __VLS_asFunctionalComponent(__VLS_57, new __VLS_57({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            disabled: (__VLS_ctx.sending || __VLS_ctx.uploading),
            title: (__VLS_ctx.$t('chat.attachments.remove')),
        }));
        const __VLS_59 = __VLS_58({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            disabled: (__VLS_ctx.sending || __VLS_ctx.uploading),
            title: (__VLS_ctx.$t('chat.attachments.remove')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_58));
        let __VLS_61;
        let __VLS_62;
        let __VLS_63;
        const __VLS_64 = {
            onClick: (...[$event]) => {
                if (!(__VLS_ctx.selectedFiles.length > 0))
                    return;
                __VLS_ctx.removeFile(idx);
            }
        };
        __VLS_60.slots.default;
        var __VLS_60;
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "max-w-3xl mx-auto flex gap-2 items-end" },
});
const __VLS_65 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_66 = __VLS_asFunctionalComponent(__VLS_65, new __VLS_65({
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
    title: (__VLS_ctx.multiline ? __VLS_ctx.$t('chat.multilineToggleSingle') : __VLS_ctx.$t('chat.multilineToggleMulti')),
}));
const __VLS_67 = __VLS_66({
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
    title: (__VLS_ctx.multiline ? __VLS_ctx.$t('chat.multilineToggleSingle') : __VLS_ctx.$t('chat.multilineToggleMulti')),
}, ...__VLS_functionalComponentArgsRest(__VLS_66));
let __VLS_69;
let __VLS_70;
let __VLS_71;
const __VLS_72 = {
    onClick: (...[$event]) => {
        __VLS_ctx.multiline = !__VLS_ctx.multiline;
    }
};
__VLS_68.slots.default;
(__VLS_ctx.multiline ? '▲' : '▼');
var __VLS_68;
if (__VLS_ctx.speechSupported || __VLS_ctx.speakerSupported) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "relative" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex gap-1" },
    });
    if (__VLS_ctx.speechSupported) {
        const __VLS_73 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_74 = __VLS_asFunctionalComponent(__VLS_73, new __VLS_73({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            ...{ class: (__VLS_ctx.speechRecording ? 'text-error animate-pulse' : '') },
            title: (__VLS_ctx.speechRecording ? __VLS_ctx.$t('chat.speech.stopSpeechToText') : __VLS_ctx.$t('chat.speech.startSpeechToText')),
        }));
        const __VLS_75 = __VLS_74({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            ...{ class: (__VLS_ctx.speechRecording ? 'text-error animate-pulse' : '') },
            title: (__VLS_ctx.speechRecording ? __VLS_ctx.$t('chat.speech.stopSpeechToText') : __VLS_ctx.$t('chat.speech.startSpeechToText')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_74));
        let __VLS_77;
        let __VLS_78;
        let __VLS_79;
        const __VLS_80 = {
            onClick: (__VLS_ctx.toggleSpeech)
        };
        __VLS_76.slots.default;
        var __VLS_76;
    }
    if (__VLS_ctx.speakerSupported) {
        const __VLS_81 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_82 = __VLS_asFunctionalComponent(__VLS_81, new __VLS_81({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            ...{ class: (__VLS_ctx.speakerEnabled ? (__VLS_ctx.speakerSpeaking ? 'text-success animate-pulse' : 'text-success') : '') },
            title: (__VLS_ctx.speakerEnabled ? __VLS_ctx.$t('chat.speech.muteIncoming') : __VLS_ctx.$t('chat.speech.readAloud')),
        }));
        const __VLS_83 = __VLS_82({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            ...{ class: (__VLS_ctx.speakerEnabled ? (__VLS_ctx.speakerSpeaking ? 'text-success animate-pulse' : 'text-success') : '') },
            title: (__VLS_ctx.speakerEnabled ? __VLS_ctx.$t('chat.speech.muteIncoming') : __VLS_ctx.$t('chat.speech.readAloud')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_82));
        let __VLS_85;
        let __VLS_86;
        let __VLS_87;
        const __VLS_88 = {
            onClick: (__VLS_ctx.toggleSpeaker)
        };
        __VLS_84.slots.default;
        (__VLS_ctx.speakerEnabled ? '🔊' : '🔇');
        var __VLS_84;
    }
    const __VLS_89 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_90 = __VLS_asFunctionalComponent(__VLS_89, new __VLS_89({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        title: (__VLS_ctx.$t('chat.speech.settings')),
    }));
    const __VLS_91 = __VLS_90({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        title: (__VLS_ctx.$t('chat.speech.settings')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_90));
    let __VLS_93;
    let __VLS_94;
    let __VLS_95;
    const __VLS_96 = {
        onClick: (...[$event]) => {
            if (!(__VLS_ctx.speechSupported || __VLS_ctx.speakerSupported))
                return;
            __VLS_ctx.speechSettingsOpen = !__VLS_ctx.speechSettingsOpen;
        }
    };
    __VLS_92.slots.default;
    var __VLS_92;
    if (__VLS_ctx.speechSettingsOpen) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "absolute bottom-full mb-2 left-0 z-10 w-80 bg-base-100 border border-base-300 rounded shadow-lg p-3 flex flex-col gap-3" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs uppercase tracking-wide opacity-60 font-semibold mb-1" },
        });
        (__VLS_ctx.$t('chat.speech.language'));
        const __VLS_97 = {}.VSelect;
        /** @type {[typeof __VLS_components.VSelect, ]} */ ;
        // @ts-ignore
        const __VLS_98 = __VLS_asFunctionalComponent(__VLS_97, new __VLS_97({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.speechLanguageStored),
            options: (__VLS_ctx.speechLanguageOptions),
        }));
        const __VLS_99 = __VLS_98({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.speechLanguageStored),
            options: (__VLS_ctx.speechLanguageOptions),
        }, ...__VLS_functionalComponentArgsRest(__VLS_98));
        let __VLS_101;
        let __VLS_102;
        let __VLS_103;
        const __VLS_104 = {
            'onUpdate:modelValue': (__VLS_ctx.onLanguageChanged)
        };
        var __VLS_100;
        if (__VLS_ctx.speakerSupported) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs uppercase tracking-wide opacity-60 font-semibold mb-1" },
            });
            (__VLS_ctx.$t('chat.speech.voice'));
            const __VLS_105 = {}.VSelect;
            /** @type {[typeof __VLS_components.VSelect, ]} */ ;
            // @ts-ignore
            const __VLS_106 = __VLS_asFunctionalComponent(__VLS_105, new __VLS_105({
                ...{ 'onUpdate:modelValue': {} },
                modelValue: (__VLS_ctx.speechVoiceUri ?? '__auto__'),
                options: (__VLS_ctx.voiceOptions),
            }));
            const __VLS_107 = __VLS_106({
                ...{ 'onUpdate:modelValue': {} },
                modelValue: (__VLS_ctx.speechVoiceUri ?? '__auto__'),
                options: (__VLS_ctx.voiceOptions),
            }, ...__VLS_functionalComponentArgsRest(__VLS_106));
            let __VLS_109;
            let __VLS_110;
            let __VLS_111;
            const __VLS_112 = {
                'onUpdate:modelValue': (__VLS_ctx.onVoiceChanged)
            };
            var __VLS_108;
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs uppercase tracking-wide opacity-60 font-semibold mb-1 flex justify-between" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            (__VLS_ctx.$t('chat.speech.rate'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "opacity-70" },
            });
            (__VLS_ctx.speechRate.toFixed(2));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
                ...{ onInput: (__VLS_ctx.onRateInput) },
                type: "range",
                ...{ class: "range range-sm w-full" },
                min: (__VLS_ctx.MIN_RATE),
                max: (__VLS_ctx.MAX_RATE),
                step: "0.05",
                value: (__VLS_ctx.speechRate),
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs uppercase tracking-wide opacity-60 font-semibold mb-1 flex justify-between" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            (__VLS_ctx.$t('chat.speech.volume'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "opacity-70" },
            });
            (Math.round(__VLS_ctx.speechVolume * 100));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
                ...{ onInput: (__VLS_ctx.onVolumeInput) },
                type: "range",
                ...{ class: "range range-sm w-full" },
                min: (__VLS_ctx.MIN_VOLUME),
                max: (__VLS_ctx.MAX_VOLUME),
                step: "0.05",
                value: (__VLS_ctx.speechVolume),
            });
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "text-xs opacity-60" },
        });
        (__VLS_ctx.$t('chat.speech.savedLocally'));
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
    ...{ onChange: (__VLS_ctx.onFilePickerChange) },
    ref: "fileInputRef",
    type: "file",
    ...{ class: "hidden" },
    multiple: true,
});
/** @type {typeof __VLS_ctx.fileInputRef} */ ;
const __VLS_113 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_114 = __VLS_asFunctionalComponent(__VLS_113, new __VLS_113({
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
    disabled: (__VLS_ctx.sending || __VLS_ctx.uploading || !__VLS_ctx.chatProcessName),
    title: (__VLS_ctx.$t('chat.attachments.pickerTooltip')),
}));
const __VLS_115 = __VLS_114({
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
    disabled: (__VLS_ctx.sending || __VLS_ctx.uploading || !__VLS_ctx.chatProcessName),
    title: (__VLS_ctx.$t('chat.attachments.pickerTooltip')),
}, ...__VLS_functionalComponentArgsRest(__VLS_114));
let __VLS_117;
let __VLS_118;
let __VLS_119;
const __VLS_120 = {
    onClick: (() => __VLS_ctx.fileInputRef?.click())
};
__VLS_116.slots.default;
var __VLS_116;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex-1" },
});
const __VLS_121 = {}.VTextarea;
/** @type {[typeof __VLS_components.VTextarea, ]} */ ;
// @ts-ignore
const __VLS_122 = __VLS_asFunctionalComponent(__VLS_121, new __VLS_121({
    ...{ 'onKeydown': {} },
    modelValue: (__VLS_ctx.composerText),
    placeholder: (__VLS_ctx.composerPlaceholder),
    rows: (__VLS_ctx.composerRows),
}));
const __VLS_123 = __VLS_122({
    ...{ 'onKeydown': {} },
    modelValue: (__VLS_ctx.composerText),
    placeholder: (__VLS_ctx.composerPlaceholder),
    rows: (__VLS_ctx.composerRows),
}, ...__VLS_functionalComponentArgsRest(__VLS_122));
let __VLS_125;
let __VLS_126;
let __VLS_127;
const __VLS_128 = {
    onKeydown: (__VLS_ctx.onComposerKeydown)
};
var __VLS_124;
const __VLS_129 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_130 = __VLS_asFunctionalComponent(__VLS_129, new __VLS_129({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: ((!__VLS_ctx.composerText.trim() && __VLS_ctx.selectedFiles.length === 0)
        || __VLS_ctx.sending || __VLS_ctx.uploading || !__VLS_ctx.chatProcessName),
    loading: (__VLS_ctx.sending || __VLS_ctx.uploading),
}));
const __VLS_131 = __VLS_130({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: ((!__VLS_ctx.composerText.trim() && __VLS_ctx.selectedFiles.length === 0)
        || __VLS_ctx.sending || __VLS_ctx.uploading || !__VLS_ctx.chatProcessName),
    loading: (__VLS_ctx.sending || __VLS_ctx.uploading),
}, ...__VLS_functionalComponentArgsRest(__VLS_130));
let __VLS_133;
let __VLS_134;
let __VLS_135;
const __VLS_136 = {
    onClick: (__VLS_ctx.send)
};
__VLS_132.slots.default;
(__VLS_ctx.$t('chat.send'));
var __VLS_132;
if (__VLS_ctx.sending) {
    const __VLS_137 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_138 = __VLS_asFunctionalComponent(__VLS_137, new __VLS_137({
        ...{ 'onClick': {} },
        variant: "danger",
        title: (__VLS_ctx.$t('chat.pauseTooltip')),
    }));
    const __VLS_139 = __VLS_138({
        ...{ 'onClick': {} },
        variant: "danger",
        title: (__VLS_ctx.$t('chat.pauseTooltip')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_138));
    let __VLS_141;
    let __VLS_142;
    let __VLS_143;
    const __VLS_144 = {
        onClick: (__VLS_ctx.pause)
    };
    __VLS_140.slots.default;
    (__VLS_ctx.$t('chat.pause'));
    var __VLS_140;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.aside, __VLS_intrinsicElements.aside)({
    ...{ class: "w-80 shrink-0 border-l border-base-300 bg-base-100 overflow-y-auto" },
});
/** @type {[typeof ProgressFeed, ]} */ ;
// @ts-ignore
const __VLS_145 = __VLS_asFunctionalComponent(ProgressFeed, new ProgressFeed({
    events: (__VLS_ctx.progressEvents),
}));
const __VLS_146 = __VLS_145({
    events: (__VLS_ctx.progressEvents),
}, ...__VLS_functionalComponentArgsRest(__VLS_145));
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['px-6']} */ ;
/** @type {__VLS_StyleScopedClasses['py-3']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-info/15']} */ ;
/** @type {__VLS_StyleScopedClasses['text-info']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-info/30']} */ ;
/** @type {__VLS_StyleScopedClasses['px-6']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-info/10']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['px-6']} */ ;
/** @type {__VLS_StyleScopedClasses['py-4']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-3xl']} */ ;
/** @type {__VLS_StyleScopedClasses['mx-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['border-t']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['relative']} */ ;
/** @type {__VLS_StyleScopedClasses['absolute']} */ ;
/** @type {__VLS_StyleScopedClasses['inset-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['border-2']} */ ;
/** @type {__VLS_StyleScopedClasses['border-dashed']} */ ;
/** @type {__VLS_StyleScopedClasses['border-primary']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-primary/10']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['pointer-events-none']} */ ;
/** @type {__VLS_StyleScopedClasses['z-10']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-primary']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-3xl']} */ ;
/** @type {__VLS_StyleScopedClasses['mx-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-3xl']} */ ;
/** @type {__VLS_StyleScopedClasses['mx-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['items-end']} */ ;
/** @type {__VLS_StyleScopedClasses['relative']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['absolute']} */ ;
/** @type {__VLS_StyleScopedClasses['bottom-full']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['left-0']} */ ;
/** @type {__VLS_StyleScopedClasses['z-10']} */ ;
/** @type {__VLS_StyleScopedClasses['w-80']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['shadow-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['range']} */ ;
/** @type {__VLS_StyleScopedClasses['range-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['range']} */ ;
/** @type {__VLS_StyleScopedClasses['range-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['w-80']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['border-l']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            MIN_RATE: MIN_RATE,
            MAX_RATE: MAX_RATE,
            MIN_VOLUME: MIN_VOLUME,
            MAX_VOLUME: MAX_VOLUME,
            SessionHeader: SessionHeader,
            VAlert: VAlert,
            VButton: VButton,
            VSelect: VSelect,
            VTextarea: VTextarea,
            MessageBubble: MessageBubble,
            PlanModeIndicator: PlanModeIndicator,
            ProgressFeed: ProgressFeed,
            emit: emit,
            historyLoading: historyLoading,
            historyError: historyError,
            workerMessageIds: workerMessageIds,
            progressEvents: progressEvents,
            chatProcessMode: chatProcessMode,
            chatTodos: chatTodos,
            planMeta: planMeta,
            modeBadge: modeBadge,
            composerText: composerText,
            sending: sending,
            sendError: sendError,
            selectedFiles: selectedFiles,
            uploading: uploading,
            dragActive: dragActive,
            multiline: multiline,
            composerRows: composerRows,
            composerPlaceholder: composerPlaceholder,
            speechSupported: speechSupported,
            speechRecording: speechRecording,
            speechError: speechError,
            speechSettingsOpen: speechSettingsOpen,
            speechLanguageStored: speechLanguageStored,
            speechLanguageOptions: speechLanguageOptions,
            toggleSpeech: toggleSpeech,
            onLanguageChanged: onLanguageChanged,
            speakerSupported: speakerSupported,
            speakerEnabled: speakerEnabled,
            speakerSpeaking: speakerSpeaking,
            speechRate: speechRate,
            speechVolume: speechVolume,
            speechVoiceUri: speechVoiceUri,
            voiceOptions: voiceOptions,
            toggleSpeaker: toggleSpeaker,
            onVoiceChanged: onVoiceChanged,
            onRateInput: onRateInput,
            onVolumeInput: onVolumeInput,
            chatProcessName: chatProcessName,
            sessionResolveError: sessionResolveError,
            messageContainer: messageContainer,
            allMessages: allMessages,
            activeAskUserMessageId: activeAskUserMessageId,
            onPickAskUserOption: onPickAskUserOption,
            visibleDraft: visibleDraft,
            visibleWorkerDrafts: visibleWorkerDrafts,
            send: send,
            formatBytes: formatBytes,
            fileInputRef: fileInputRef,
            onComposerDragEnter: onComposerDragEnter,
            onComposerDragOver: onComposerDragOver,
            onComposerDragLeave: onComposerDragLeave,
            onComposerDrop: onComposerDrop,
            onFilePickerChange: onFilePickerChange,
            removeFile: removeFile,
            pause: pause,
            onComposerKeydown: onComposerKeydown,
        };
    },
    __typeEmits: {},
    __typeProps: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeEmits: {},
    __typeProps: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=ChatView.vue.js.map