import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { WebSocketRequestError, } from '@vance/shared';
import { useChatHistory } from '@composables/useChatHistory';
import { VAlert, VButton, VTextarea } from '@components/index';
import MessageBubble from './MessageBubble.vue';
import ProgressFeed from './ProgressFeed.vue';
const props = defineProps();
const emit = defineEmits();
const PROGRESS_CAP = 50;
const { messages: history, loading: historyLoading, error: historyError, load, reset } = useChatHistory();
/** Messages received via chat-message-appended after history load. Same shape as history. */
const liveMessages = ref([]);
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
    instance.lang = navigator.language || 'en-US';
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
    // Commit beats the pending draft of this process — clear it.
    streamingDrafts.value.delete(data.processName);
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
    await Promise.all([
        load(props.sessionId),
        resolveSessionAndProcess(),
    ]);
    scrollToBottom();
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
    reset();
});
watch(() => props.sessionId, async (newId, oldId) => {
    if (!newId || newId === oldId)
        return;
    liveMessages.value = [];
    streamingDrafts.value = new Map();
    progressEvents.value = [];
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
    ...{ class: "flex-1 min-w-0 flex flex-col" },
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
    }));
    const __VLS_13 = __VLS_12({
        key: (msg.messageId),
        role: (String(msg.role)),
        content: (msg.content),
        createdAt: (msg.createdAt),
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
__VLS_asFunctionalElement(__VLS_intrinsicElements.footer, __VLS_intrinsicElements.footer)({
    ...{ class: "border-t border-base-300 bg-base-100 p-4" },
});
if (__VLS_ctx.sendError) {
    const __VLS_18 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_19 = __VLS_asFunctionalComponent(__VLS_18, new __VLS_18({
        variant: "error",
        ...{ class: "mb-2" },
    }));
    const __VLS_20 = __VLS_19({
        variant: "error",
        ...{ class: "mb-2" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_19));
    __VLS_21.slots.default;
    (__VLS_ctx.sendError);
    var __VLS_21;
}
if (__VLS_ctx.sessionResolveError) {
    const __VLS_22 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_23 = __VLS_asFunctionalComponent(__VLS_22, new __VLS_22({
        variant: "warning",
        ...{ class: "mb-2" },
    }));
    const __VLS_24 = __VLS_23({
        variant: "warning",
        ...{ class: "mb-2" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_23));
    __VLS_25.slots.default;
    (__VLS_ctx.sessionResolveError);
    var __VLS_25;
}
if (__VLS_ctx.speechError) {
    const __VLS_26 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_27 = __VLS_asFunctionalComponent(__VLS_26, new __VLS_26({
        variant: "warning",
        ...{ class: "mb-2" },
    }));
    const __VLS_28 = __VLS_27({
        variant: "warning",
        ...{ class: "mb-2" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_27));
    __VLS_29.slots.default;
    (__VLS_ctx.speechError);
    var __VLS_29;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "max-w-3xl mx-auto flex gap-2 items-end" },
});
const __VLS_30 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_31 = __VLS_asFunctionalComponent(__VLS_30, new __VLS_30({
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
    title: (__VLS_ctx.multiline ? 'Switch to single-line input' : 'Switch to multi-line input'),
}));
const __VLS_32 = __VLS_31({
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
    title: (__VLS_ctx.multiline ? 'Switch to single-line input' : 'Switch to multi-line input'),
}, ...__VLS_functionalComponentArgsRest(__VLS_31));
let __VLS_34;
let __VLS_35;
let __VLS_36;
const __VLS_37 = {
    onClick: (...[$event]) => {
        __VLS_ctx.multiline = !__VLS_ctx.multiline;
    }
};
__VLS_33.slots.default;
(__VLS_ctx.multiline ? '▲' : '▼');
var __VLS_33;
if (__VLS_ctx.speechSupported) {
    const __VLS_38 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_39 = __VLS_asFunctionalComponent(__VLS_38, new __VLS_38({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        ...{ class: (__VLS_ctx.speechRecording ? 'text-error animate-pulse' : '') },
        title: (__VLS_ctx.speechRecording ? 'Stop speech-to-text' : 'Start speech-to-text'),
    }));
    const __VLS_40 = __VLS_39({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        ...{ class: (__VLS_ctx.speechRecording ? 'text-error animate-pulse' : '') },
        title: (__VLS_ctx.speechRecording ? 'Stop speech-to-text' : 'Start speech-to-text'),
    }, ...__VLS_functionalComponentArgsRest(__VLS_39));
    let __VLS_42;
    let __VLS_43;
    let __VLS_44;
    const __VLS_45 = {
        onClick: (__VLS_ctx.toggleSpeech)
    };
    __VLS_41.slots.default;
    var __VLS_41;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex-1" },
});
const __VLS_46 = {}.VTextarea;
/** @type {[typeof __VLS_components.VTextarea, ]} */ ;
// @ts-ignore
const __VLS_47 = __VLS_asFunctionalComponent(__VLS_46, new __VLS_46({
    ...{ 'onKeydown': {} },
    modelValue: (__VLS_ctx.composerText),
    placeholder: (__VLS_ctx.composerPlaceholder),
    rows: (__VLS_ctx.composerRows),
}));
const __VLS_48 = __VLS_47({
    ...{ 'onKeydown': {} },
    modelValue: (__VLS_ctx.composerText),
    placeholder: (__VLS_ctx.composerPlaceholder),
    rows: (__VLS_ctx.composerRows),
}, ...__VLS_functionalComponentArgsRest(__VLS_47));
let __VLS_50;
let __VLS_51;
let __VLS_52;
const __VLS_53 = {
    onKeydown: (__VLS_ctx.onComposerKeydown)
};
var __VLS_49;
const __VLS_54 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_55 = __VLS_asFunctionalComponent(__VLS_54, new __VLS_54({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.composerText.trim() || __VLS_ctx.sending || !__VLS_ctx.chatProcessName),
    loading: (__VLS_ctx.sending),
}));
const __VLS_56 = __VLS_55({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.composerText.trim() || __VLS_ctx.sending || !__VLS_ctx.chatProcessName),
    loading: (__VLS_ctx.sending),
}, ...__VLS_functionalComponentArgsRest(__VLS_55));
let __VLS_58;
let __VLS_59;
let __VLS_60;
const __VLS_61 = {
    onClick: (__VLS_ctx.send)
};
__VLS_57.slots.default;
var __VLS_57;
__VLS_asFunctionalElement(__VLS_intrinsicElements.aside, __VLS_intrinsicElements.aside)({
    ...{ class: "w-80 shrink-0 border-l border-base-300 bg-base-100 overflow-y-auto" },
});
/** @type {[typeof ProgressFeed, ]} */ ;
// @ts-ignore
const __VLS_62 = __VLS_asFunctionalComponent(ProgressFeed, new ProgressFeed({
    events: (__VLS_ctx.progressEvents),
}));
const __VLS_63 = __VLS_62({
    events: (__VLS_ctx.progressEvents),
}, ...__VLS_functionalComponentArgsRest(__VLS_62));
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
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
            VAlert: VAlert,
            VButton: VButton,
            VTextarea: VTextarea,
            MessageBubble: MessageBubble,
            ProgressFeed: ProgressFeed,
            emit: emit,
            historyLoading: historyLoading,
            historyError: historyError,
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
            toggleSpeech: toggleSpeech,
            chatProcessName: chatProcessName,
            sessionDisplay: sessionDisplay,
            sessionResolveError: sessionResolveError,
            messageContainer: messageContainer,
            allMessages: allMessages,
            visibleDraft: visibleDraft,
            send: send,
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