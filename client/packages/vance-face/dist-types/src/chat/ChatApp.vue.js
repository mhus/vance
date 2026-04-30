import { onBeforeUnmount, onMounted, ref, computed, watch } from 'vue';
import { BrainWebSocket, WebSocketRequestError, getJwt, getTenantId, getUsername, setActiveSessionId, getActiveSessionId, } from '@vance/shared';
import { EditorShell, VAlert, VButton } from '@components/index';
import PickerView from './PickerView.vue';
import ChatView from './ChatView.vue';
const CLIENT_VERSION = '0.1.0';
const mode = ref('connecting');
const errorMessage = ref(null);
const socket = ref(null);
const activeSessionId = ref(null);
const username = computed(() => getUsername());
const connectionState = computed(() => {
    if (mode.value === 'live')
        return 'connected';
    if (mode.value === 'occupied')
        return 'occupied';
    if (mode.value === 'picker' || mode.value === 'connecting')
        return 'idle';
    return undefined;
});
function urlSessionId() {
    const params = new URLSearchParams(window.location.search);
    const id = params.get('sessionId');
    return id && id.length > 0 ? id : null;
}
function pushSessionIdToUrl(sessionId) {
    const url = new URL(window.location.href);
    if (sessionId)
        url.searchParams.set('sessionId', sessionId);
    else
        url.searchParams.delete('sessionId');
    window.history.replaceState(null, '', url.toString());
}
async function openSocket() {
    const tenant = getTenantId();
    const jwt = getJwt();
    if (!tenant || !jwt) {
        throw new Error('Missing tenant or JWT — cannot open chat connection.');
    }
    return BrainWebSocket.connect({
        tenant,
        jwt,
        profile: 'web',
        clientVersion: CLIENT_VERSION,
    });
}
async function resumeSessionId(sessionId) {
    if (!socket.value)
        return;
    try {
        await socket.value.send('session-resume', { sessionId });
        activeSessionId.value = sessionId;
        setActiveSessionId(sessionId);
        pushSessionIdToUrl(sessionId);
        mode.value = 'live';
    }
    catch (e) {
        if (e instanceof WebSocketRequestError) {
            switch (e.errorCode) {
                case 409:
                    mode.value = 'occupied';
                    errorMessage.value = `Session "${sessionId}" is held by another connection.`;
                    return;
                case 404:
                    // Stale sessionId (closed or never existed) — drop it and fall back to picker.
                    setActiveSessionId(null);
                    pushSessionIdToUrl(null);
                    mode.value = 'picker';
                    errorMessage.value = `Session "${sessionId}" no longer exists. Pick another.`;
                    return;
                case 403:
                    mode.value = 'failed';
                    errorMessage.value = `Session "${sessionId}" belongs to another user.`;
                    return;
            }
        }
        mode.value = 'failed';
        errorMessage.value = e instanceof Error ? e.message : 'Failed to resume session.';
    }
}
async function onSessionPicked(sessionId) {
    errorMessage.value = null;
    await resumeSessionId(sessionId);
}
async function onSessionBootstrapped(sessionId) {
    // session-bootstrap binds the socket as a side effect; no extra resume.
    errorMessage.value = null;
    activeSessionId.value = sessionId;
    setActiveSessionId(sessionId);
    pushSessionIdToUrl(sessionId);
    mode.value = 'live';
}
async function leaveLive() {
    // Guard against accidental loss: the WS will be unbound and any
    // composer draft / unread progress disappears. Native confirm() is
    // intentional — a modal dialog buys polish but adds component
    // weight, and this is the only confirmation surface in the chat
    // editor for now.
    const ok = window.confirm('Leave this chat session? The connection will be released and any unsent draft is lost.');
    if (!ok)
        return;
    if (socket.value && !socket.value.closed()) {
        socket.value.sendNoReply('session-unbind');
    }
    activeSessionId.value = null;
    pushSessionIdToUrl(null);
    // localStorage.activeSessionId stays — it's a hint for next visit, not state.
    mode.value = 'picker';
}
/**
 * Browser tab close / navigation guard. Active only while in live
 * mode — the picker has no state worth protecting. The actual prompt
 * text is browser-controlled (locked down for anti-spam reasons), so
 * we only need to set {@code returnValue} to a non-empty string.
 */
function beforeUnloadGuard(event) {
    event.preventDefault();
    event.returnValue = '';
}
watch(mode, (next, prev) => {
    if (next === 'live' && prev !== 'live') {
        window.addEventListener('beforeunload', beforeUnloadGuard);
    }
    else if (prev === 'live' && next !== 'live') {
        window.removeEventListener('beforeunload', beforeUnloadGuard);
    }
});
function backToPicker() {
    errorMessage.value = null;
    pushSessionIdToUrl(null);
    mode.value = 'picker';
}
onMounted(async () => {
    try {
        socket.value = await openSocket();
    }
    catch (e) {
        mode.value = 'failed';
        errorMessage.value = e instanceof Error ? e.message : 'Failed to open WebSocket.';
        return;
    }
    socket.value.onClose(() => {
        if (mode.value === 'live') {
            mode.value = 'failed';
            errorMessage.value = 'Connection lost. Reload to reconnect.';
        }
        else if (mode.value === 'picker' || mode.value === 'occupied') {
            mode.value = 'failed';
            errorMessage.value = 'Connection closed.';
        }
    });
    // Resume hint: URL param wins over localStorage.
    const hinted = urlSessionId() ?? getActiveSessionId();
    if (hinted) {
        await resumeSessionId(hinted);
    }
    else {
        mode.value = 'picker';
    }
});
onBeforeUnmount(() => {
    window.removeEventListener('beforeunload', beforeUnloadGuard);
    socket.value?.close();
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
const __VLS_0 = {}.EditorShell;
/** @type {[typeof __VLS_components.EditorShell, typeof __VLS_components.EditorShell, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    title: "Chat",
    connectionState: (__VLS_ctx.connectionState),
    fullHeight: (true),
}));
const __VLS_2 = __VLS_1({
    title: "Chat",
    connectionState: (__VLS_ctx.connectionState),
    fullHeight: (true),
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
var __VLS_4 = {};
__VLS_3.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "h-full min-h-0 flex flex-col" },
});
if (__VLS_ctx.errorMessage) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "px-6 pt-4" },
    });
    const __VLS_5 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_6 = __VLS_asFunctionalComponent(__VLS_5, new __VLS_5({
        variant: (__VLS_ctx.mode === 'occupied' ? 'warning' : 'error'),
    }));
    const __VLS_7 = __VLS_6({
        variant: (__VLS_ctx.mode === 'occupied' ? 'warning' : 'error'),
    }, ...__VLS_functionalComponentArgsRest(__VLS_6));
    __VLS_8.slots.default;
    (__VLS_ctx.errorMessage);
    if (__VLS_ctx.mode === 'occupied') {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "mt-2 flex gap-2" },
        });
        const __VLS_9 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_10 = __VLS_asFunctionalComponent(__VLS_9, new __VLS_9({
            ...{ 'onClick': {} },
            variant: "secondary",
        }));
        const __VLS_11 = __VLS_10({
            ...{ 'onClick': {} },
            variant: "secondary",
        }, ...__VLS_functionalComponentArgsRest(__VLS_10));
        let __VLS_13;
        let __VLS_14;
        let __VLS_15;
        const __VLS_16 = {
            onClick: (__VLS_ctx.backToPicker)
        };
        __VLS_12.slots.default;
        var __VLS_12;
        const __VLS_17 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_18 = __VLS_asFunctionalComponent(__VLS_17, new __VLS_17({
            ...{ 'onClick': {} },
            variant: "ghost",
        }));
        const __VLS_19 = __VLS_18({
            ...{ 'onClick': {} },
            variant: "ghost",
        }, ...__VLS_functionalComponentArgsRest(__VLS_18));
        let __VLS_21;
        let __VLS_22;
        let __VLS_23;
        const __VLS_24 = {
            onClick: (...[$event]) => {
                if (!(__VLS_ctx.errorMessage))
                    return;
                if (!(__VLS_ctx.mode === 'occupied'))
                    return;
                __VLS_ctx.resumeSessionId(__VLS_ctx.activeSessionId ?? '');
            }
        };
        __VLS_20.slots.default;
        var __VLS_20;
    }
    else if (__VLS_ctx.mode === 'failed') {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "mt-2" },
        });
        const __VLS_25 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_26 = __VLS_asFunctionalComponent(__VLS_25, new __VLS_25({
            ...{ 'onClick': {} },
            variant: "secondary",
        }));
        const __VLS_27 = __VLS_26({
            ...{ 'onClick': {} },
            variant: "secondary",
        }, ...__VLS_functionalComponentArgsRest(__VLS_26));
        let __VLS_29;
        let __VLS_30;
        let __VLS_31;
        const __VLS_32 = {
            onClick: (__VLS_ctx.backToPicker)
        };
        __VLS_28.slots.default;
        var __VLS_28;
    }
    var __VLS_8;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex-1 min-h-0" },
});
if (__VLS_ctx.mode === 'picker' && __VLS_ctx.socket) {
    /** @type {[typeof PickerView, ]} */ ;
    // @ts-ignore
    const __VLS_33 = __VLS_asFunctionalComponent(PickerView, new PickerView({
        ...{ 'onSessionPicked': {} },
        ...{ 'onSessionBootstrapped': {} },
        socket: (__VLS_ctx.socket),
        username: (__VLS_ctx.username),
    }));
    const __VLS_34 = __VLS_33({
        ...{ 'onSessionPicked': {} },
        ...{ 'onSessionBootstrapped': {} },
        socket: (__VLS_ctx.socket),
        username: (__VLS_ctx.username),
    }, ...__VLS_functionalComponentArgsRest(__VLS_33));
    let __VLS_36;
    let __VLS_37;
    let __VLS_38;
    const __VLS_39 = {
        onSessionPicked: (__VLS_ctx.onSessionPicked)
    };
    const __VLS_40 = {
        onSessionBootstrapped: (__VLS_ctx.onSessionBootstrapped)
    };
    var __VLS_35;
}
else if (__VLS_ctx.mode === 'live' && __VLS_ctx.socket && __VLS_ctx.activeSessionId) {
    /** @type {[typeof ChatView, ]} */ ;
    // @ts-ignore
    const __VLS_41 = __VLS_asFunctionalComponent(ChatView, new ChatView({
        ...{ 'onLeave': {} },
        socket: (__VLS_ctx.socket),
        sessionId: (__VLS_ctx.activeSessionId),
    }));
    const __VLS_42 = __VLS_41({
        ...{ 'onLeave': {} },
        socket: (__VLS_ctx.socket),
        sessionId: (__VLS_ctx.activeSessionId),
    }, ...__VLS_functionalComponentArgsRest(__VLS_41));
    let __VLS_44;
    let __VLS_45;
    let __VLS_46;
    const __VLS_47 = {
        onLeave: (__VLS_ctx.leaveLive)
    };
    var __VLS_43;
}
else if (__VLS_ctx.mode === 'connecting') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "p-6 text-sm opacity-60" },
    });
}
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['px-6']} */ ;
/** @type {__VLS_StyleScopedClasses['pt-4']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['p-6']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            EditorShell: EditorShell,
            VAlert: VAlert,
            VButton: VButton,
            PickerView: PickerView,
            ChatView: ChatView,
            mode: mode,
            errorMessage: errorMessage,
            socket: socket,
            activeSessionId: activeSessionId,
            username: username,
            connectionState: connectionState,
            resumeSessionId: resumeSessionId,
            onSessionPicked: onSessionPicked,
            onSessionBootstrapped: onSessionBootstrapped,
            leaveLive: leaveLive,
            backToPicker: backToPicker,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=ChatApp.vue.js.map