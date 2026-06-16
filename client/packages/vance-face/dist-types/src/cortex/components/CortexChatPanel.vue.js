import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { BrainWebSocket, WebSocketRequestError, getTenantId, setActiveSessionId, } from '@vance/shared';
import { VAlert, VButton } from '@/components';
import ChatView from '@/chat/ChatView.vue';
import ChatComposer from '@/chat/ChatComposer.vue';
import { useCortexStore } from '../stores/cortexStore';
import { useNotificationSubscription } from '@/notification/useNotificationSubscription';
const props = defineProps();
const cortexStore = useCortexStore();
/**
 * Surfaces the Cortex active tab as a one-click chat attachment. Reactive,
 * so the composer's dropdown label always reflects what the user is
 * currently looking at in the main editor. {@code null} when no tab is
 * open (Cortex starts blank for fresh sessions) — the composer then
 * falls back to the plain native file-picker UX.
 */
const currentFileSource = computed(() => {
    const tab = cortexStore.activeTab;
    if (!tab)
        return null;
    return { documentId: tab.id, label: tab.path };
});
const CLIENT_VERSION = '0.1.0';
// The chat-process name is fixed by {@code SessionChatBootstrapper} to
// "chat" — exactly one per session, see chat/ChatApp.vue's
// resolveSessionAndProcess. We don't need a session-list lookup here;
// the constant is the contract.
const CHAT_PROCESS_NAME = 'chat';
const status = ref('connecting');
const errorMessage = ref(null);
const socket = ref(null);
// `notify` frames → global toast + WebAudio beep. Follows reconnects.
useNotificationSubscription(socket);
// Imperative cross-component routing — ChatComposer pushes optimistic
// user-message echoes; ChatView appends them to its message list so the
// user sees their message before the server frame arrives. Same dance
// chat.html does in its parent ChatApp.
const chatViewRef = ref(null);
const composerRef = ref(null);
let onCloseUnsubscribe = null;
async function openSocket() {
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
async function open() {
    status.value = 'connecting';
    errorMessage.value = null;
    try {
        socket.value = await openSocket();
    }
    catch (e) {
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
        await socket.value.send('session-resume', { sessionId: props.sessionId });
        setActiveSessionId(props.sessionId);
        status.value = 'live';
        // Push the Cortex tool surface as soon as the bind succeeds —
        // failures here are non-fatal for the chat itself (the user can
        // still talk to the agent without doc tools), but log them so we
        // can spot a broken registration in practice.
        if (props.toolService && socket.value) {
            try {
                await props.toolService.attach(socket.value);
            }
            catch (regError) {
                console.warn('Failed to register Cortex client tools', regError);
            }
        }
    }
    catch (e) {
        if (e instanceof WebSocketRequestError && e.errorCode === 409) {
            status.value = 'occupied';
            errorMessage.value = 'Another connection holds this session — close that tab and retry.';
        }
        else if (e instanceof WebSocketRequestError && e.errorCode === 404) {
            status.value = 'failed';
            errorMessage.value = `Session ${props.sessionId} not found.`;
        }
        else if (e instanceof WebSocketRequestError && e.errorCode === 403) {
            status.value = 'failed';
            errorMessage.value = 'Access to this session was denied.';
        }
        else {
            status.value = 'failed';
            errorMessage.value = e instanceof Error ? e.message : 'Failed to bind chat session.';
        }
    }
}
async function teardown() {
    onCloseUnsubscribe?.();
    onCloseUnsubscribe = null;
    props.toolService?.detach();
    if (socket.value && !socket.value.closed()) {
        socket.value.sendNoReply('session-unbind');
    }
    socket.value?.close();
    socket.value = null;
}
async function retry() {
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
function onLocalEcho(msg) {
    chatViewRef.value?.appendLocalEcho(msg);
}
function onRollbackEcho(messageId) {
    chatViewRef.value?.rollbackLocalEcho(messageId);
}
function onLeave() {
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
async function onConversationExported(payload) {
    try {
        await cortexStore.openFile(payload.documentId);
    }
    catch (e) {
        console.warn('Failed to open exported conversation in Cortex', e);
    }
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "h-full min-h-0 flex flex-col" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "\u0070\u0078\u002d\u0033\u0020\u0070\u0079\u002d\u0031\u002e\u0035\u0020\u0074\u0065\u0078\u0074\u002d\u0078\u0073\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u002d\u0062\u0020\u0062\u006f\u0072\u0064\u0065\u0072\u002d\u0062\u0061\u0073\u0065\u002d\u0033\u0030\u0030\u0020\u0062\u0067\u002d\u0062\u0061\u0073\u0065\u002d\u0032\u0030\u0030\u002f\u0034\u0030\u0020\u0074\u0065\u0078\u0074\u002d\u0062\u0061\u0073\u0065\u002d\u0063\u006f\u006e\u0074\u0065\u006e\u0074\u002f\u0036\u0030\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0066\u006c\u0065\u0078\u0020\u0069\u0074\u0065\u006d\u0073\u002d\u0063\u0065\u006e\u0074\u0065\u0072\u0020\u0067\u0061\u0070\u002d\u0032\u0020\u0073\u0068\u0072\u0069\u006e\u006b\u002d\u0030" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "uppercase tracking-wide opacity-70" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "font-mono truncate" },
});
(__VLS_ctx.sessionId);
if (__VLS_ctx.status === 'connecting') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 flex items-center justify-center text-sm opacity-60" },
    });
}
else if (__VLS_ctx.status !== 'live') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "p-3" },
    });
    const __VLS_0 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
        variant: (__VLS_ctx.status === 'occupied' ? 'warning' : 'error'),
    }));
    const __VLS_2 = __VLS_1({
        variant: (__VLS_ctx.status === 'occupied' ? 'warning' : 'error'),
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    __VLS_3.slots.default;
    (__VLS_ctx.errorMessage);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "mt-2" },
    });
    const __VLS_4 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
        ...{ 'onClick': {} },
        size: "sm",
        variant: "secondary",
    }));
    const __VLS_6 = __VLS_5({
        ...{ 'onClick': {} },
        size: "sm",
        variant: "secondary",
    }, ...__VLS_functionalComponentArgsRest(__VLS_5));
    let __VLS_8;
    let __VLS_9;
    let __VLS_10;
    const __VLS_11 = {
        onClick: (__VLS_ctx.retry)
    };
    __VLS_7.slots.default;
    var __VLS_7;
    var __VLS_3;
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 min-h-0 overflow-hidden" },
    });
    if (__VLS_ctx.socket) {
        /** @type {[typeof ChatView, ]} */ ;
        // @ts-ignore
        const __VLS_12 = __VLS_asFunctionalComponent(ChatView, new ChatView({
            ...{ 'onLeave': {} },
            ...{ 'onHub': {} },
            ...{ 'onConversationExported': {} },
            ref: "chatViewRef",
            socket: (__VLS_ctx.socket),
            sessionId: (__VLS_ctx.sessionId),
            chatProcessName: (__VLS_ctx.CHAT_PROCESS_NAME),
            chatProjectId: (__VLS_ctx.projectId),
        }));
        const __VLS_13 = __VLS_12({
            ...{ 'onLeave': {} },
            ...{ 'onHub': {} },
            ...{ 'onConversationExported': {} },
            ref: "chatViewRef",
            socket: (__VLS_ctx.socket),
            sessionId: (__VLS_ctx.sessionId),
            chatProcessName: (__VLS_ctx.CHAT_PROCESS_NAME),
            chatProjectId: (__VLS_ctx.projectId),
        }, ...__VLS_functionalComponentArgsRest(__VLS_12));
        let __VLS_15;
        let __VLS_16;
        let __VLS_17;
        const __VLS_18 = {
            onLeave: (__VLS_ctx.onLeave)
        };
        const __VLS_19 = {
            onHub: (__VLS_ctx.onLeave)
        };
        const __VLS_20 = {
            onConversationExported: (__VLS_ctx.onConversationExported)
        };
        /** @type {typeof __VLS_ctx.chatViewRef} */ ;
        var __VLS_21 = {};
        var __VLS_14;
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "shrink-0 border-t border-base-300" },
    });
    if (__VLS_ctx.socket) {
        /** @type {[typeof ChatComposer, ]} */ ;
        // @ts-ignore
        const __VLS_23 = __VLS_asFunctionalComponent(ChatComposer, new ChatComposer({
            ...{ 'onHub': {} },
            ...{ 'onLocalEcho': {} },
            ...{ 'onRollbackEcho': {} },
            ref: "composerRef",
            socket: (__VLS_ctx.socket),
            chatProcessName: (__VLS_ctx.CHAT_PROCESS_NAME),
            chatProjectId: (__VLS_ctx.projectId),
            compactTools: (true),
            currentFileSource: (__VLS_ctx.currentFileSource),
        }));
        const __VLS_24 = __VLS_23({
            ...{ 'onHub': {} },
            ...{ 'onLocalEcho': {} },
            ...{ 'onRollbackEcho': {} },
            ref: "composerRef",
            socket: (__VLS_ctx.socket),
            chatProcessName: (__VLS_ctx.CHAT_PROCESS_NAME),
            chatProjectId: (__VLS_ctx.projectId),
            compactTools: (true),
            currentFileSource: (__VLS_ctx.currentFileSource),
        }, ...__VLS_functionalComponentArgsRest(__VLS_23));
        let __VLS_26;
        let __VLS_27;
        let __VLS_28;
        const __VLS_29 = {
            onHub: (__VLS_ctx.onLeave)
        };
        const __VLS_30 = {
            onLocalEcho: (__VLS_ctx.onLocalEcho)
        };
        const __VLS_31 = {
            onRollbackEcho: (__VLS_ctx.onRollbackEcho)
        };
        /** @type {typeof __VLS_ctx.composerRef} */ ;
        var __VLS_32 = {};
        var __VLS_25;
    }
}
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200/40']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['border-t']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
// @ts-ignore
var __VLS_22 = __VLS_21, __VLS_33 = __VLS_32;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            VButton: VButton,
            ChatView: ChatView,
            ChatComposer: ChatComposer,
            currentFileSource: currentFileSource,
            CHAT_PROCESS_NAME: CHAT_PROCESS_NAME,
            status: status,
            errorMessage: errorMessage,
            socket: socket,
            chatViewRef: chatViewRef,
            composerRef: composerRef,
            retry: retry,
            onLocalEcho: onLocalEcho,
            onRollbackEcho: onRollbackEcho,
            onLeave: onLeave,
            onConversationExported: onConversationExported,
        };
    },
    __typeProps: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeProps: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=CortexChatPanel.vue.js.map