import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useChatHistory } from '@composables/useChatHistory';
import { useTenantProjects } from '@composables/useTenantProjects';
import { useDocumentRefStore } from '@/document/documentRefStore';
import { SessionHeader, VAlert, VButton } from '@components/index';
import MessageBubble from './MessageBubble.vue';
import PlanModeIndicator from './PlanModeIndicator.vue';
import { OPTIMISTIC_PREFIX } from './optimisticEcho';
const props = defineProps();
const emit = defineEmits();
const { t: _ } = useI18n();
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
// ──────────────── Plan-Mode state (Arthur Plan-Mode flow) ────────────────
const chatProcessMode = ref('NORMAL');
const chatTodos = ref([]);
const planMeta = ref(null);
const modeBadge = computed(() => {
    if (chatProcessMode.value === 'NORMAL')
        return null;
    return chatProcessMode.value.toLowerCase();
});
// ──────────────── Project label (header chip) ────────────────
const { projects: tenantProjects, reload: loadTenantProjects } = useTenantProjects();
const chatProjectLabel = computed(() => {
    const id = props.chatProjectId;
    if (!id)
        return '';
    const p = tenantProjects.value.find((x) => x.name === id);
    const title = p?.title?.trim();
    return title && title.length > 0 ? title : id;
});
// Keep the embedded-document resolver and the save-as-document promote
// path informed about the chat's current project — both fall back to
// this store value when a vance:/-link or a kindbox action omits the
// authority segment.
const documentRefStore = useDocumentRefStore();
watch(() => props.chatProjectId, (id) => {
    documentRefStore.setCurrentProject(id);
}, { immediate: true });
// Surface the resolved project title up to ChatApp for the breadcrumb.
// The label computed above falls back to the technical id when the
// tenant project list hasn't loaded yet; we emit the resolution
// anyway so the breadcrumb at least shows something stable.
watch(chatProjectLabel, (label) => {
    if (props.chatProjectId && label) {
        emit('project-resolved', { name: props.chatProjectId, title: label });
    }
}, { immediate: true });
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
 * only ASK_USER picker the user can still answer by clicking.
 */
const activeAskUserMessageId = computed(() => {
    const msgs = allMessages.value;
    for (let i = msgs.length - 1; i >= 0; i--) {
        const m = msgs[i];
        if (String(m.role) === 'USER')
            return null;
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
    // Composer owns the send pipeline — bubble up so the parent can
    // route this to {@code composerRef.setTextAndSend(label)}.
    emit('ask-user-pick', label.trim());
}
/** Sticky chat-process draft for the optimistic streaming bubble. */
const visibleDraft = computed(() => {
    if (!props.chatProcessName)
        return null;
    const entry = streamingDrafts.value.get(props.chatProcessName);
    if (!entry || !entry.content)
        return null;
    return entry;
});
const visibleWorkerDrafts = computed(() => {
    const out = [];
    for (const [name, entry] of streamingDrafts.value.entries()) {
        if (!entry.content)
            continue;
        if (name === props.chatProcessName)
            continue;
        out.push(entry);
    }
    return out;
});
function isWorkerProcess(processName) {
    if (!processName)
        return false;
    if (!props.chatProcessName)
        return false;
    return processName !== props.chatProcessName;
}
function isChatProcess(processName) {
    if (!processName)
        return false;
    if (!props.chatProcessName)
        return false;
    return processName === props.chatProcessName;
}
function appendMessageBubble(data) {
    // Dedupe against optimistic local echo: when the canonical user
    // message arrives from the server, drop the matching `tmp_*` entry
    // that the composer pushed at send-time.
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
    streamingDrafts.value.delete(data.processName);
    // Speak non-USER messages from the main chat process when the
    // composer's speaker is enabled — sibling component, so emit up.
    if (String(data.role) !== 'USER' && !isWorkerProcess(data.processName)) {
        emit('speak-message', data.content);
    }
    // Any frame counts as activity for talk-mode's idle timer.
    emit('note-activity');
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
function onProcessModeChanged(data) {
    if (!isChatProcess(data.processName))
        return;
    const next = data.newMode ?? 'NORMAL';
    chatProcessMode.value = next;
    if (next === 'NORMAL') {
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
// ──────────────── Imperative API ────────────────
//
// The composer (a sibling component) drives optimistic local echoes
// via emits routed through the parent. Parent calls these methods
// imperatively on this component's ref.
function appendLocalEcho(message) {
    liveMessages.value.push(message);
    scrollToBottom();
}
function rollbackLocalEcho(messageId) {
    const idx = liveMessages.value.findIndex((m) => m.messageId === messageId);
    if (idx >= 0)
        liveMessages.value.splice(idx, 1);
}
const __VLS_exposed = { appendLocalEcho, rollbackLocalEcho };
defineExpose(__VLS_exposed);
// ──────────────── Wizard deep-link plumbing ────────────────
//
// MarkdownView dispatches a 'vance-open-wizard' CustomEvent when the
// user clicks a {@code vance:/wizards/<name>?...} link. We forward it
// to the parent so it can call into the right-panel's openWizard().
function onWizardDeepLink(ev) {
    const detail = ev.detail;
    if (!detail || !detail.name)
        return;
    emit('wizard-deep-link', { name: detail.name, prefill: detail.prefill ?? {} });
}
// ──────────────── Lifecycle ────────────────
const subscriptions = [];
onMounted(async () => {
    subscriptions.push(props.socket.on('chat-message-appended', appendMessageBubble), props.socket.on('chat-message-stream-chunk', appendChunk), props.socket.on('process-mode-changed', onProcessModeChanged), props.socket.on('todos-updated', onTodosUpdated), props.socket.on('plan-proposed', onPlanProposed));
    await Promise.all([
        load(props.sessionId),
        loadTenantProjects(),
    ]);
    scrollToBottom();
    // From here on, any chat-message-appended frame is by definition a
    // fresh server-side event, not a history backfill — let the composer
    // open its TTS gate.
    emit('history-loaded');
    window.addEventListener('vance-open-wizard', onWizardDeepLink);
});
onBeforeUnmount(() => {
    window.removeEventListener('vance-open-wizard', onWizardDeepLink);
    for (const off of subscriptions)
        off();
    reset();
});
watch(() => props.sessionId, async (newId, oldId) => {
    if (!newId || newId === oldId)
        return;
    liveMessages.value = [];
    workerMessageIds.value = new Set();
    streamingDrafts.value = new Map();
    resetPlanModeState();
    await load(newId);
    scrollToBottom();
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "h-full min-h-0 flex flex-col" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.header, __VLS_intrinsicElements.header)({
    ...{ class: "px-6 py-3 border-b border-base-300 bg-base-100 flex items-center gap-3" },
});
if (__VLS_ctx.chatProjectLabel) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "\u0066\u006c\u0065\u0078\u0020\u0069\u0074\u0065\u006d\u0073\u002d\u0063\u0065\u006e\u0074\u0065\u0072\u0020\u0067\u0061\u0070\u002d\u0031\u0020\u0074\u0065\u0078\u0074\u002d\u0078\u0073\u0020\u0070\u0078\u002d\u0032\u0020\u0070\u0079\u002d\u0031\u0020\u0072\u006f\u0075\u006e\u0064\u0065\u0064\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0062\u0067\u002d\u0062\u0061\u0073\u0065\u002d\u0032\u0030\u0030\u0020\u0074\u0065\u0078\u0074\u002d\u0062\u0061\u0073\u0065\u002d\u0063\u006f\u006e\u0074\u0065\u006e\u0074\u002f\u0038\u0030\u0020\u006d\u0061\u0078\u002d\u0077\u002d\u005b\u0031\u0034\u0072\u0065\u006d\u005d\u0020\u0073\u0068\u0072\u0069\u006e\u006b\u002d\u0030" },
        title: (__VLS_ctx.$t('chat.projectTooltip', { name: __VLS_ctx.chatProjectId })),
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        'aria-hidden': "true",
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "truncate font-medium" },
    });
    (__VLS_ctx.chatProjectLabel);
}
const __VLS_0 = {}.SessionHeader;
/** @type {[typeof __VLS_components.SessionHeader, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    ...{ 'onArchived': {} },
    ...{ 'onDeleted': {} },
    sessionId: (__VLS_ctx.sessionId),
}));
const __VLS_2 = __VLS_1({
    ...{ 'onArchived': {} },
    ...{ 'onDeleted': {} },
    sessionId: (__VLS_ctx.sessionId),
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
let __VLS_4;
let __VLS_5;
let __VLS_6;
const __VLS_7 = {
    onArchived: (...[$event]) => {
        __VLS_ctx.emit('leave');
    }
};
const __VLS_8 = {
    onDeleted: (...[$event]) => {
        __VLS_ctx.emit('leave');
    }
};
var __VLS_3;
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
    const __VLS_9 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_10 = __VLS_asFunctionalComponent(__VLS_9, new __VLS_9({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }));
    const __VLS_11 = __VLS_10({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }, ...__VLS_functionalComponentArgsRest(__VLS_10));
    let __VLS_13;
    let __VLS_14;
    let __VLS_15;
    const __VLS_16 = {
        onClick: (...[$event]) => {
            if (!(__VLS_ctx.mediation))
                return;
            __VLS_ctx.emit('hub');
        }
    };
    __VLS_12.slots.default;
    (__VLS_ctx.$t('chat.mediation.backToHub'));
    var __VLS_12;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ref: "messageContainer",
    ...{ class: "flex-1 min-h-0 overflow-y-auto px-6 py-4" },
});
/** @type {typeof __VLS_ctx.messageContainer} */ ;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "max-w-5xl mx-auto flex flex-col gap-3" },
});
if (__VLS_ctx.historyLoading) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-60" },
    });
    (__VLS_ctx.$t('chat.historyLoading'));
}
else if (__VLS_ctx.historyError) {
    const __VLS_17 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_18 = __VLS_asFunctionalComponent(__VLS_17, new __VLS_17({
        variant: "error",
    }));
    const __VLS_19 = __VLS_18({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_18));
    __VLS_20.slots.default;
    (__VLS_ctx.historyError);
    var __VLS_20;
}
for (const [msg] of __VLS_getVForSourceType((__VLS_ctx.allMessages))) {
    /** @type {[typeof MessageBubble, ]} */ ;
    // @ts-ignore
    const __VLS_21 = __VLS_asFunctionalComponent(MessageBubble, new MessageBubble({
        ...{ 'onPickOption': {} },
        key: (msg.messageId),
        role: (String(msg.role)),
        content: (msg.content),
        createdAt: (msg.createdAt),
        worker: (__VLS_ctx.workerMessageIds.has(msg.messageId)),
        meta: (msg.meta),
        optionsActionable: (msg.messageId === __VLS_ctx.activeAskUserMessageId),
    }));
    const __VLS_22 = __VLS_21({
        ...{ 'onPickOption': {} },
        key: (msg.messageId),
        role: (String(msg.role)),
        content: (msg.content),
        createdAt: (msg.createdAt),
        worker: (__VLS_ctx.workerMessageIds.has(msg.messageId)),
        meta: (msg.meta),
        optionsActionable: (msg.messageId === __VLS_ctx.activeAskUserMessageId),
    }, ...__VLS_functionalComponentArgsRest(__VLS_21));
    let __VLS_24;
    let __VLS_25;
    let __VLS_26;
    const __VLS_27 = {
        onPickOption: (__VLS_ctx.onPickAskUserOption)
    };
    var __VLS_23;
}
if (__VLS_ctx.visibleDraft) {
    /** @type {[typeof MessageBubble, ]} */ ;
    // @ts-ignore
    const __VLS_28 = __VLS_asFunctionalComponent(MessageBubble, new MessageBubble({
        role: (String(__VLS_ctx.visibleDraft.role)),
        content: (__VLS_ctx.visibleDraft.content),
        streaming: (true),
    }));
    const __VLS_29 = __VLS_28({
        role: (String(__VLS_ctx.visibleDraft.role)),
        content: (__VLS_ctx.visibleDraft.content),
        streaming: (true),
    }, ...__VLS_functionalComponentArgsRest(__VLS_28));
}
for (const [draft] of __VLS_getVForSourceType((__VLS_ctx.visibleWorkerDrafts))) {
    /** @type {[typeof MessageBubble, ]} */ ;
    // @ts-ignore
    const __VLS_31 = __VLS_asFunctionalComponent(MessageBubble, new MessageBubble({
        key: (`worker-draft-${draft.processName}`),
        role: (String(draft.role)),
        content: (draft.content),
        worker: (true),
        processName: (draft.processName),
        streaming: (true),
    }));
    const __VLS_32 = __VLS_31({
        key: (`worker-draft-${draft.processName}`),
        role: (String(draft.role)),
        content: (draft.content),
        worker: (true),
        processName: (draft.processName),
        streaming: (true),
    }, ...__VLS_functionalComponentArgsRest(__VLS_31));
}
/** @type {[typeof PlanModeIndicator, ]} */ ;
// @ts-ignore
const __VLS_34 = __VLS_asFunctionalComponent(PlanModeIndicator, new PlanModeIndicator({
    mode: (__VLS_ctx.chatProcessMode),
    todos: (__VLS_ctx.chatTodos),
    planMeta: (__VLS_ctx.planMeta),
}));
const __VLS_35 = __VLS_34({
    mode: (__VLS_ctx.chatProcessMode),
    todos: (__VLS_ctx.chatTodos),
    planMeta: (__VLS_ctx.planMeta),
}, ...__VLS_functionalComponentArgsRest(__VLS_34));
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
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
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/80']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-[14rem]']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
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
/** @type {__VLS_StyleScopedClasses['max-w-5xl']} */ ;
/** @type {__VLS_StyleScopedClasses['mx-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            SessionHeader: SessionHeader,
            VAlert: VAlert,
            VButton: VButton,
            MessageBubble: MessageBubble,
            PlanModeIndicator: PlanModeIndicator,
            emit: emit,
            historyLoading: historyLoading,
            historyError: historyError,
            workerMessageIds: workerMessageIds,
            chatProcessMode: chatProcessMode,
            chatTodos: chatTodos,
            planMeta: planMeta,
            modeBadge: modeBadge,
            chatProjectLabel: chatProjectLabel,
            messageContainer: messageContainer,
            allMessages: allMessages,
            activeAskUserMessageId: activeAskUserMessageId,
            onPickAskUserOption: onPickAskUserOption,
            visibleDraft: visibleDraft,
            visibleWorkerDrafts: visibleWorkerDrafts,
        };
    },
    __typeEmits: {},
    __typeProps: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {
            ...__VLS_exposed,
        };
    },
    __typeEmits: {},
    __typeProps: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=ChatView.vue.js.map