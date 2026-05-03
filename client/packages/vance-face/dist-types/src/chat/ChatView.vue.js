import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { WebSocketRequestError, AUTO_LANGUAGE, SUPPORTED_SPEECH_LANGUAGES, getSpeechLanguage, resolveSpeechLanguage, setSpeechLanguage, buildUtterance, getSpeakerEnabled, getSpeechRate, getSpeechVoiceURI, getSpeechVolume, isSpeechSynthesisSupported, listVoices, onVoicesChanged, setSpeakerEnabled, setSpeechRate, setSpeechVoiceURI, setSpeechVolume, stripMarkdown, MIN_RATE, MAX_RATE, MIN_VOLUME, MAX_VOLUME, } from '@vance/shared';
import { useChatHistory } from '@composables/useChatHistory';
import { VAlert, VButton, VSelect, VTextarea } from '@components/index';
import MessageBubble from './MessageBubble.vue';
import ProgressFeed from './ProgressFeed.vue';
const props = defineProps();
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
const composerText = ref('');
const sending = ref(false);
const sendError = ref(null);
/**
 * Composer mode: single-line uses Enter to send (Shift+Enter for a hard
 * break), multi-line uses Ctrl/Cmd+Enter (because plain Enter is the
 * obvious gesture for newline once the user has multiple lines).
 */
const multiline = ref(false);
const composerRows = computed(() => (multiline.value ? 4 : 1));
const composerPlaceholder = computed(() => multiline.value
    ? 'Type a message — Ctrl/Cmd+Enter to send, Enter for newline'
    : 'Type a message — Enter to send, Shift+Enter for newline');
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
        speechError.value = `Microphone error: ${event.error}`;
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
        speechError.value = e instanceof Error ? e.message : 'Failed to start recording.';
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
        { value: '__auto__', label: 'Auto (default voice for language)' },
        ...matching.map((v) => ({
            value: v.voiceURI,
            label: `${v.name} (${v.lang})${v.default ? ' · default' : ''}`,
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
    }
    catch {
        sessionDisplay.value = props.sessionId;
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
function scrollToBottom() {
    nextTick(() => {
        const el = messageContainer.value;
        if (el)
            el.scrollTop = el.scrollHeight;
    });
}
async function send() {
    const text = composerText.value.trim();
    if (!text || sending.value || !chatProcessName.value)
        return;
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
        role: 'USER',
        content: text,
        createdAt: new Date(),
    });
    composerText.value = '';
    scrollToBottom();
    try {
        await props.socket.send('process-steer', {
            processName: chatProcessName.value,
            content: text,
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
            sendError.value = e instanceof Error ? e.message : 'Failed to send.';
        }
    }
    finally {
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
    subscriptions.push(props.socket.on('chat-message-appended', appendMessageBubble), props.socket.on('chat-message-stream-chunk', appendChunk), props.socket.on('process-progress', recordProgress));
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
var __VLS_3;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex-1 min-w-0 truncate" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "font-medium" },
});
(__VLS_ctx.sessionDisplay ?? __VLS_ctx.sessionId);
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
}
else if (__VLS_ctx.historyError) {
    const __VLS_8 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
        variant: "error",
    }));
    const __VLS_10 = __VLS_9({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_9));
    __VLS_11.slots.default;
    (__VLS_ctx.historyError);
    var __VLS_11;
}
for (const [msg] of __VLS_getVForSourceType((__VLS_ctx.allMessages))) {
    /** @type {[typeof MessageBubble, ]} */ ;
    // @ts-ignore
    const __VLS_12 = __VLS_asFunctionalComponent(MessageBubble, new MessageBubble({
        key: (msg.messageId),
        role: (String(msg.role)),
        content: (msg.content),
        createdAt: (msg.createdAt),
        worker: (__VLS_ctx.workerMessageIds.has(msg.messageId)),
    }));
    const __VLS_13 = __VLS_12({
        key: (msg.messageId),
        role: (String(msg.role)),
        content: (msg.content),
        createdAt: (msg.createdAt),
        worker: (__VLS_ctx.workerMessageIds.has(msg.messageId)),
    }, ...__VLS_functionalComponentArgsRest(__VLS_12));
}
if (__VLS_ctx.visibleDraft) {
    /** @type {[typeof MessageBubble, ]} */ ;
    // @ts-ignore
    const __VLS_15 = __VLS_asFunctionalComponent(MessageBubble, new MessageBubble({
        role: (String(__VLS_ctx.visibleDraft.role)),
        content: (__VLS_ctx.visibleDraft.content),
        streaming: (true),
    }));
    const __VLS_16 = __VLS_15({
        role: (String(__VLS_ctx.visibleDraft.role)),
        content: (__VLS_ctx.visibleDraft.content),
        streaming: (true),
    }, ...__VLS_functionalComponentArgsRest(__VLS_15));
}
for (const [draft] of __VLS_getVForSourceType((__VLS_ctx.visibleWorkerDrafts))) {
    /** @type {[typeof MessageBubble, ]} */ ;
    // @ts-ignore
    const __VLS_18 = __VLS_asFunctionalComponent(MessageBubble, new MessageBubble({
        key: (`worker-draft-${draft.processName}`),
        role: (String(draft.role)),
        content: (draft.content),
        worker: (true),
        processName: (draft.processName),
        streaming: (true),
    }));
    const __VLS_19 = __VLS_18({
        key: (`worker-draft-${draft.processName}`),
        role: (String(draft.role)),
        content: (draft.content),
        worker: (true),
        processName: (draft.processName),
        streaming: (true),
    }, ...__VLS_functionalComponentArgsRest(__VLS_18));
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.footer, __VLS_intrinsicElements.footer)({
    ...{ class: "border-t border-base-300 bg-base-100 p-4" },
});
if (__VLS_ctx.sendError) {
    const __VLS_21 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_22 = __VLS_asFunctionalComponent(__VLS_21, new __VLS_21({
        variant: "error",
        ...{ class: "mb-2" },
    }));
    const __VLS_23 = __VLS_22({
        variant: "error",
        ...{ class: "mb-2" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_22));
    __VLS_24.slots.default;
    (__VLS_ctx.sendError);
    var __VLS_24;
}
if (__VLS_ctx.sessionResolveError) {
    const __VLS_25 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_26 = __VLS_asFunctionalComponent(__VLS_25, new __VLS_25({
        variant: "warning",
        ...{ class: "mb-2" },
    }));
    const __VLS_27 = __VLS_26({
        variant: "warning",
        ...{ class: "mb-2" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_26));
    __VLS_28.slots.default;
    (__VLS_ctx.sessionResolveError);
    var __VLS_28;
}
if (__VLS_ctx.speechError) {
    const __VLS_29 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_30 = __VLS_asFunctionalComponent(__VLS_29, new __VLS_29({
        variant: "warning",
        ...{ class: "mb-2" },
    }));
    const __VLS_31 = __VLS_30({
        variant: "warning",
        ...{ class: "mb-2" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_30));
    __VLS_32.slots.default;
    (__VLS_ctx.speechError);
    var __VLS_32;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "max-w-3xl mx-auto flex gap-2 items-end" },
});
const __VLS_33 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_34 = __VLS_asFunctionalComponent(__VLS_33, new __VLS_33({
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
    title: (__VLS_ctx.multiline ? 'Switch to single-line input' : 'Switch to multi-line input'),
}));
const __VLS_35 = __VLS_34({
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
    title: (__VLS_ctx.multiline ? 'Switch to single-line input' : 'Switch to multi-line input'),
}, ...__VLS_functionalComponentArgsRest(__VLS_34));
let __VLS_37;
let __VLS_38;
let __VLS_39;
const __VLS_40 = {
    onClick: (...[$event]) => {
        __VLS_ctx.multiline = !__VLS_ctx.multiline;
    }
};
__VLS_36.slots.default;
(__VLS_ctx.multiline ? '▲' : '▼');
var __VLS_36;
if (__VLS_ctx.speechSupported || __VLS_ctx.speakerSupported) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "relative" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex gap-1" },
    });
    if (__VLS_ctx.speechSupported) {
        const __VLS_41 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_42 = __VLS_asFunctionalComponent(__VLS_41, new __VLS_41({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            ...{ class: (__VLS_ctx.speechRecording ? 'text-error animate-pulse' : '') },
            title: (__VLS_ctx.speechRecording ? 'Stop speech-to-text' : 'Start speech-to-text'),
        }));
        const __VLS_43 = __VLS_42({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            ...{ class: (__VLS_ctx.speechRecording ? 'text-error animate-pulse' : '') },
            title: (__VLS_ctx.speechRecording ? 'Stop speech-to-text' : 'Start speech-to-text'),
        }, ...__VLS_functionalComponentArgsRest(__VLS_42));
        let __VLS_45;
        let __VLS_46;
        let __VLS_47;
        const __VLS_48 = {
            onClick: (__VLS_ctx.toggleSpeech)
        };
        __VLS_44.slots.default;
        var __VLS_44;
    }
    if (__VLS_ctx.speakerSupported) {
        const __VLS_49 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_50 = __VLS_asFunctionalComponent(__VLS_49, new __VLS_49({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            ...{ class: (__VLS_ctx.speakerEnabled ? (__VLS_ctx.speakerSpeaking ? 'text-success animate-pulse' : 'text-success') : '') },
            title: (__VLS_ctx.speakerEnabled ? 'Mute incoming messages' : 'Read incoming messages aloud'),
        }));
        const __VLS_51 = __VLS_50({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
            ...{ class: (__VLS_ctx.speakerEnabled ? (__VLS_ctx.speakerSpeaking ? 'text-success animate-pulse' : 'text-success') : '') },
            title: (__VLS_ctx.speakerEnabled ? 'Mute incoming messages' : 'Read incoming messages aloud'),
        }, ...__VLS_functionalComponentArgsRest(__VLS_50));
        let __VLS_53;
        let __VLS_54;
        let __VLS_55;
        const __VLS_56 = {
            onClick: (__VLS_ctx.toggleSpeaker)
        };
        __VLS_52.slots.default;
        (__VLS_ctx.speakerEnabled ? '🔊' : '🔇');
        var __VLS_52;
    }
    const __VLS_57 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_58 = __VLS_asFunctionalComponent(__VLS_57, new __VLS_57({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        title: "Speech settings",
    }));
    const __VLS_59 = __VLS_58({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        title: "Speech settings",
    }, ...__VLS_functionalComponentArgsRest(__VLS_58));
    let __VLS_61;
    let __VLS_62;
    let __VLS_63;
    const __VLS_64 = {
        onClick: (...[$event]) => {
            if (!(__VLS_ctx.speechSupported || __VLS_ctx.speakerSupported))
                return;
            __VLS_ctx.speechSettingsOpen = !__VLS_ctx.speechSettingsOpen;
        }
    };
    __VLS_60.slots.default;
    var __VLS_60;
    if (__VLS_ctx.speechSettingsOpen) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "absolute bottom-full mb-2 left-0 z-10 w-80 bg-base-100 border border-base-300 rounded shadow-lg p-3 flex flex-col gap-3" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs uppercase tracking-wide opacity-60 font-semibold mb-1" },
        });
        const __VLS_65 = {}.VSelect;
        /** @type {[typeof __VLS_components.VSelect, ]} */ ;
        // @ts-ignore
        const __VLS_66 = __VLS_asFunctionalComponent(__VLS_65, new __VLS_65({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.speechLanguageStored),
            options: (__VLS_ctx.speechLanguageOptions),
        }));
        const __VLS_67 = __VLS_66({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.speechLanguageStored),
            options: (__VLS_ctx.speechLanguageOptions),
        }, ...__VLS_functionalComponentArgsRest(__VLS_66));
        let __VLS_69;
        let __VLS_70;
        let __VLS_71;
        const __VLS_72 = {
            'onUpdate:modelValue': (__VLS_ctx.onLanguageChanged)
        };
        var __VLS_68;
        if (__VLS_ctx.speakerSupported) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs uppercase tracking-wide opacity-60 font-semibold mb-1" },
            });
            const __VLS_73 = {}.VSelect;
            /** @type {[typeof __VLS_components.VSelect, ]} */ ;
            // @ts-ignore
            const __VLS_74 = __VLS_asFunctionalComponent(__VLS_73, new __VLS_73({
                ...{ 'onUpdate:modelValue': {} },
                modelValue: (__VLS_ctx.speechVoiceUri ?? '__auto__'),
                options: (__VLS_ctx.voiceOptions),
            }));
            const __VLS_75 = __VLS_74({
                ...{ 'onUpdate:modelValue': {} },
                modelValue: (__VLS_ctx.speechVoiceUri ?? '__auto__'),
                options: (__VLS_ctx.voiceOptions),
            }, ...__VLS_functionalComponentArgsRest(__VLS_74));
            let __VLS_77;
            let __VLS_78;
            let __VLS_79;
            const __VLS_80 = {
                'onUpdate:modelValue': (__VLS_ctx.onVoiceChanged)
            };
            var __VLS_76;
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs uppercase tracking-wide opacity-60 font-semibold mb-1 flex justify-between" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
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
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex-1" },
});
const __VLS_81 = {}.VTextarea;
/** @type {[typeof __VLS_components.VTextarea, ]} */ ;
// @ts-ignore
const __VLS_82 = __VLS_asFunctionalComponent(__VLS_81, new __VLS_81({
    ...{ 'onKeydown': {} },
    modelValue: (__VLS_ctx.composerText),
    placeholder: (__VLS_ctx.composerPlaceholder),
    rows: (__VLS_ctx.composerRows),
}));
const __VLS_83 = __VLS_82({
    ...{ 'onKeydown': {} },
    modelValue: (__VLS_ctx.composerText),
    placeholder: (__VLS_ctx.composerPlaceholder),
    rows: (__VLS_ctx.composerRows),
}, ...__VLS_functionalComponentArgsRest(__VLS_82));
let __VLS_85;
let __VLS_86;
let __VLS_87;
const __VLS_88 = {
    onKeydown: (__VLS_ctx.onComposerKeydown)
};
var __VLS_84;
const __VLS_89 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_90 = __VLS_asFunctionalComponent(__VLS_89, new __VLS_89({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.composerText.trim() || __VLS_ctx.sending || !__VLS_ctx.chatProcessName),
    loading: (__VLS_ctx.sending),
}));
const __VLS_91 = __VLS_90({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.composerText.trim() || __VLS_ctx.sending || !__VLS_ctx.chatProcessName),
    loading: (__VLS_ctx.sending),
}, ...__VLS_functionalComponentArgsRest(__VLS_90));
let __VLS_93;
let __VLS_94;
let __VLS_95;
const __VLS_96 = {
    onClick: (__VLS_ctx.send)
};
__VLS_92.slots.default;
var __VLS_92;
if (__VLS_ctx.sending) {
    const __VLS_97 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_98 = __VLS_asFunctionalComponent(__VLS_97, new __VLS_97({
        ...{ 'onClick': {} },
        variant: "danger",
        title: "Pause the chat (and all workers in this session)",
    }));
    const __VLS_99 = __VLS_98({
        ...{ 'onClick': {} },
        variant: "danger",
        title: "Pause the chat (and all workers in this session)",
    }, ...__VLS_functionalComponentArgsRest(__VLS_98));
    let __VLS_101;
    let __VLS_102;
    let __VLS_103;
    const __VLS_104 = {
        onClick: (__VLS_ctx.pause)
    };
    __VLS_100.slots.default;
    var __VLS_100;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.aside, __VLS_intrinsicElements.aside)({
    ...{ class: "w-80 shrink-0 border-l border-base-300 bg-base-100 overflow-y-auto" },
});
/** @type {[typeof ProgressFeed, ]} */ ;
// @ts-ignore
const __VLS_105 = __VLS_asFunctionalComponent(ProgressFeed, new ProgressFeed({
    events: (__VLS_ctx.progressEvents),
}));
const __VLS_106 = __VLS_105({
    events: (__VLS_ctx.progressEvents),
}, ...__VLS_functionalComponentArgsRest(__VLS_105));
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
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
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
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
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
            VAlert: VAlert,
            VButton: VButton,
            VSelect: VSelect,
            VTextarea: VTextarea,
            MessageBubble: MessageBubble,
            ProgressFeed: ProgressFeed,
            emit: emit,
            historyLoading: historyLoading,
            historyError: historyError,
            workerMessageIds: workerMessageIds,
            progressEvents: progressEvents,
            composerText: composerText,
            sending: sending,
            sendError: sendError,
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
            sessionDisplay: sessionDisplay,
            sessionResolveError: sessionResolveError,
            messageContainer: messageContainer,
            allMessages: allMessages,
            visibleDraft: visibleDraft,
            visibleWorkerDrafts: visibleWorkerDrafts,
            send: send,
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