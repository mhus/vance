import { onBeforeUnmount, onMounted, ref, computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { BrainWebSocket, WebSocketRequestError, getTenantId, getUsername, setActiveSessionId, getActiveSessionId, } from '@vance/shared';
import { EditorShell, VAlert, VButton } from '@components/index';
import PickerView from './PickerView.vue';
import ChatView from './ChatView.vue';
const { t } = useI18n();
const CLIENT_VERSION = '0.1.0';
const mode = ref('connecting');
const errorMessage = ref(null);
const socket = ref(null);
const activeSessionId = ref(null);
const mediation = ref(null);
/**
 * Single-level back-stack: the session id we were bound to before the
 * current switch-to. {@code null} means we're at the hub already.
 * {@code /hub} closes the current WS and reopens one bound to this
 * id. v2 can grow this into a real stack for multi-hop project
 * context switching.
 */
const previousSessionId = ref(null);
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
    if (!tenant) {
        throw new Error('Missing tenant — cannot open chat connection.');
    }
    // Same-origin upgrade ships the {@code vance_access} cookie
    // automatically. No JWT lookup in JS — that's the whole point of
    // the cookie-based auth flow.
    return BrainWebSocket.connect({
        tenant,
        profile: 'web',
        clientVersion: CLIENT_VERSION,
    });
}
/**
 * Open a session-less socket and attach the global listeners. Used
 * by the picker, which then drives session-bootstrap on this same
 * socket. The picker's success path goes through
 * {@link onSessionBootstrapped} without re-opening the socket.
 */
async function openSocketForPicker() {
    await teardownCurrentSocket();
    try {
        socket.value = await openSocket();
    }
    catch (e) {
        mode.value = 'failed';
        errorMessage.value = e instanceof Error ? e.message : t('chat.failedToOpen');
        return false;
    }
    attachSocketListeners(socket.value);
    return true;
}
/**
 * Drop the current socket and detach its listeners. Called before
 * any switch — the listener unsubs run before close() so onClose
 * doesn't fire the "connection lost" UI message for what's really an
 * intentional swap.
 */
async function teardownCurrentSocket() {
    switchToUnsubscribe?.();
    switchToUnsubscribe = null;
    onCloseUnsubscribe?.();
    onCloseUnsubscribe = null;
    if (socket.value) {
        socket.value.close();
        socket.value = null;
    }
}
/**
 * Attach onClose + switch-to listeners on the given socket. Pulled
 * out so both paths (session-bound via {@link openAndBind} and
 * session-less via {@link openSocketForPicker}) share the same
 * wiring. The handles live in module-level refs so
 * {@link teardownCurrentSocket} can detach them before an intentional
 * close.
 */
function attachSocketListeners(s) {
    onCloseUnsubscribe = s.onClose(() => {
        if (mode.value === 'live') {
            mode.value = 'failed';
            errorMessage.value = t('chat.connectionLost');
        }
        else if (mode.value === 'picker' || mode.value === 'occupied') {
            mode.value = 'failed';
            errorMessage.value = t('chat.connectionClosed');
        }
    });
    switchToUnsubscribe = s.on('switch-to', (data) => { void onSwitchTo(data); });
}
/**
 * Open a fresh WS and bind it to {@code sessionId}. Closes any existing
 * WS first. This is the unified switch path used by both the initial
 * connect (from picker / URL hint) and the server-pushed switch-to
 * frame. Re-registers the switch-to listener on the new socket so a
 * subsequent switch still finds us.
 *
 * @returns {@code true} on a clean bind, {@code false} when resume
 *          failed (mode + errorMessage are populated as a side effect).
 */
async function openAndBind(sessionId) {
    await teardownCurrentSocket();
    try {
        socket.value = await openSocket();
    }
    catch (e) {
        mode.value = 'failed';
        errorMessage.value = e instanceof Error ? e.message : t('chat.failedToOpen');
        return false;
    }
    attachSocketListeners(socket.value);
    try {
        await socket.value.send('session-resume', { sessionId });
        activeSessionId.value = sessionId;
        setActiveSessionId(sessionId);
        pushSessionIdToUrl(sessionId);
        // Clear any stale failure message from a prior connection cycle —
        // we just successfully bound, so anything that complained about a
        // lost connection is no longer true.
        errorMessage.value = null;
        mode.value = 'live';
        return true;
    }
    catch (e) {
        if (e instanceof WebSocketRequestError) {
            switch (e.errorCode) {
                case 409:
                    mode.value = 'occupied';
                    errorMessage.value = t('chat.sessionOccupiedBy', { id: sessionId });
                    return false;
                case 404:
                    setActiveSessionId(null);
                    pushSessionIdToUrl(null);
                    mode.value = 'picker';
                    errorMessage.value = t('chat.sessionNotFound', { id: sessionId });
                    return false;
                case 403:
                    mode.value = 'failed';
                    errorMessage.value = t('chat.sessionForbidden', { id: sessionId });
                    return false;
            }
        }
        mode.value = 'failed';
        errorMessage.value = e instanceof Error ? e.message : t('chat.failedToResume');
        return false;
    }
}
/**
 * Server-pushed {@code switch-to} frame — Eddie's MEDIATE action (or a
 * future flow like project-tab switching) asks the client to drop the
 * current WS and open a new one bound to {@code targetSessionId}. The
 * previous session id is pushed onto our single-level back-stack so
 * {@code /hub} can return.
 */
async function onSwitchTo(data) {
    const target = data.targetSessionId;
    if (!target)
        return;
    const fromSessionId = activeSessionId.value;
    const ok = await openAndBind(target);
    if (!ok)
        return;
    previousSessionId.value = fromSessionId;
    mediation.value = {
        workerProjectName: data.targetProjectId || data.targetProcessName || target,
    };
}
/**
 * User-triggered {@code /hub} — purely client-side. Close the current
 * WS, open a new one bound to the remembered previous session, drop
 * the mediation banner. No server round-trip; the server doesn't know
 * (or care) about the back-stack — workers are regular sessions, the
 * hub is a regular session, switching between them is local.
 *
 * No-op when we're already at the hub (no previous session remembered).
 */
async function backToHub() {
    const target = previousSessionId.value;
    if (!target)
        return;
    const ok = await openAndBind(target);
    if (!ok)
        return;
    previousSessionId.value = null;
    mediation.value = null;
}
async function onSessionPicked(sessionId) {
    errorMessage.value = null;
    await openAndBind(sessionId);
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
    // No confirm dialog — the session stays alive on the server and the
    // user can always re-enter it from the picker. Sending session-
    // unbind frees the binding so the server marks the connection
    // available; the WS itself stays open for picker / bootstrap use.
    if (socket.value && !socket.value.closed()) {
        socket.value.sendNoReply('session-unbind');
    }
    activeSessionId.value = null;
    pushSessionIdToUrl(null);
    // localStorage.activeSessionId stays — it's a hint for next visit, not state.
    mode.value = 'picker';
}
function backToPicker() {
    errorMessage.value = null;
    pushSessionIdToUrl(null);
    mode.value = 'picker';
}
let switchToUnsubscribe = null;
let onCloseUnsubscribe = null;
onMounted(async () => {
    // Resume hint: URL param wins over localStorage. With a hint we go
    // straight to a session-bound socket via openAndBind; without one
    // we open a session-less socket for the picker.
    const hinted = urlSessionId() ?? getActiveSessionId();
    if (hinted) {
        await openAndBind(hinted);
    }
    else {
        const ok = await openSocketForPicker();
        if (ok)
            mode.value = 'picker';
    }
});
onBeforeUnmount(() => {
    switchToUnsubscribe?.();
    onCloseUnsubscribe?.();
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
    title: (__VLS_ctx.$t('chat.pageTitle')),
    connectionState: (__VLS_ctx.connectionState),
    fullHeight: (true),
}));
const __VLS_2 = __VLS_1({
    title: (__VLS_ctx.$t('chat.pageTitle')),
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
        (__VLS_ctx.$t('chat.pickAnotherSession'));
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
                __VLS_ctx.openAndBind(__VLS_ctx.activeSessionId ?? '');
            }
        };
        __VLS_20.slots.default;
        (__VLS_ctx.$t('chat.tryAgain'));
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
        (__VLS_ctx.$t('chat.backToPicker'));
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
        ...{ 'onHub': {} },
        key: (__VLS_ctx.activeSessionId),
        socket: (__VLS_ctx.socket),
        sessionId: (__VLS_ctx.activeSessionId),
        mediation: (__VLS_ctx.mediation),
    }));
    const __VLS_42 = __VLS_41({
        ...{ 'onLeave': {} },
        ...{ 'onHub': {} },
        key: (__VLS_ctx.activeSessionId),
        socket: (__VLS_ctx.socket),
        sessionId: (__VLS_ctx.activeSessionId),
        mediation: (__VLS_ctx.mediation),
    }, ...__VLS_functionalComponentArgsRest(__VLS_41));
    let __VLS_44;
    let __VLS_45;
    let __VLS_46;
    const __VLS_47 = {
        onLeave: (__VLS_ctx.leaveLive)
    };
    const __VLS_48 = {
        onHub: (__VLS_ctx.backToHub)
    };
    var __VLS_43;
}
else if (__VLS_ctx.mode === 'connecting') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "p-6 text-sm opacity-60" },
    });
    (__VLS_ctx.$t('chat.connecting'));
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
            mediation: mediation,
            username: username,
            connectionState: connectionState,
            openAndBind: openAndBind,
            backToHub: backToHub,
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