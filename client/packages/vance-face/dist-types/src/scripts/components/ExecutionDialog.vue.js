import { onMounted, onBeforeUnmount, ref } from 'vue';
import { VAlert, VButton, VModal, VTextarea } from '@/components';
import { brainFetch, BrainWebSocket, getTenantId } from '@vance/shared';
const props = defineProps();
const emit = defineEmits();
// Initial false so VModal's watch fires on the false→true transition
// inside onMounted — otherwise <dialog>.showModal() never runs and
// nothing appears on screen. See specification/web-ui.md §7.7.
const visible = ref(false);
const argsJson = ref('{}');
const argsError = ref(null);
const state = ref('idle');
const executionId = ref(null);
const logLines = ref([]);
const resultValue = ref(null);
const errorMessage = ref(null);
const durationMs = ref(null);
let ws = null;
let wsSubscribed = false;
let pollTimer = null;
async function start() {
    let parsed = {};
    argsError.value = null;
    if (argsJson.value.trim()) {
        try {
            parsed = JSON.parse(argsJson.value);
            if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
                throw new Error('args must be a JSON object');
            }
        }
        catch (e) {
            argsError.value = e instanceof Error ? e.message : 'Invalid JSON';
            return;
        }
    }
    state.value = 'starting';
    logLines.value = [];
    resultValue.value = null;
    errorMessage.value = null;
    durationMs.value = null;
    wsSubscribed = false;
    let resp;
    try {
        resp = await brainFetch('POST', `scripts/execute?projectId=${encodeURIComponent(props.projectId)}`, {
            body: {
                scriptId: props.file.id,
                args: parsed,
                sourceName: props.file.path,
            },
        });
    }
    catch (e) {
        state.value = 'failed';
        errorMessage.value = e instanceof Error ? e.message : 'Execute failed';
        return;
    }
    executionId.value = resp.executionId;
    state.value = 'running';
    // Best-effort WS subscribe for live streaming. If it fails for any
    // reason — no session, network blip, server rejection — fall back
    // to polling. The execution itself already runs server-side; we
    // just need a way to surface its state to the user.
    try {
        if (!ws) {
            ws = await BrainWebSocket.connect({
                tenant: getTenantId() ?? '',
                profile: 'web',
                clientVersion: '0.1.0',
            });
            bindWs(ws);
        }
        await ws.send('script-execution-subscribe', { executionId: resp.executionId });
        wsSubscribed = true;
    }
    catch (e) {
        console.warn('[script-cortex] WS subscribe failed, falling back to polling:', e);
        wsSubscribed = false;
    }
    // Polling backstop: covers the WS-failed case AND the race where
    // the worker finishes before our subscribe lands.
    startPolling();
}
function bindWs(w) {
    w.on('script-execution-started', (d) => {
        if (d.executionId !== executionId.value)
            return;
        state.value = 'running';
    });
    w.on('script-execution-log', (d) => {
        if (d.executionId !== executionId.value)
            return;
        logLines.value.push(`[${d.stream}] ${d.logLine ?? ''}`);
        if (logLines.value.length > 5000) {
            logLines.value = logLines.value.slice(-4000);
        }
    });
    w.on('script-execution-finished', (d) => {
        if (d.executionId !== executionId.value)
            return;
        state.value = 'finished';
        resultValue.value = d.resultValue ?? null;
        durationMs.value = d.durationMs ?? null;
        stopPolling();
    });
    w.on('script-execution-failed', (d) => {
        if (d.executionId !== executionId.value)
            return;
        state.value = 'failed';
        errorMessage.value = d.errorMessage ?? 'Execution failed';
        durationMs.value = d.durationMs ?? null;
        stopPolling();
    });
    w.on('script-execution-cancelled', (d) => {
        if (d.executionId !== executionId.value)
            return;
        state.value = 'cancelled';
        durationMs.value = d.durationMs ?? null;
        stopPolling();
    });
}
function startPolling() {
    stopPolling();
    const tick = async () => {
        if (!executionId.value)
            return;
        try {
            const snap = await brainFetch('GET', `scripts/executions/${executionId.value}`);
            // Merge the snapshot. WS events take precedence when they
            // arrive, but if WS is dead the snapshot drives the UI.
            const snapState = snap.state;
            if (snapState !== 'running') {
                state.value = snapState;
                resultValue.value = snap.resultValue ?? null;
                errorMessage.value = snap.errorMessage ?? null;
                durationMs.value = snap.durationMs ?? null;
            }
            // Log buffer — only patch when WS isn't streaming, otherwise
            // we'd duplicate lines.
            if (!wsSubscribed && snap.logBuffer && snap.logBuffer.length > 0) {
                logLines.value = snap.logBuffer.map((l) => `[buffered] ${l}`);
            }
            if (snapState !== 'running') {
                stopPolling();
                return;
            }
        }
        catch (e) {
            // 404 = the execution evicted (5min retention). Stop polling.
            console.warn('[script-cortex] poll error:', e);
            stopPolling();
            return;
        }
        pollTimer = window.setTimeout(tick, 1_500);
    };
    pollTimer = window.setTimeout(tick, 800);
}
function stopPolling() {
    if (pollTimer != null) {
        window.clearTimeout(pollTimer);
        pollTimer = null;
    }
}
async function cancel() {
    if (!executionId.value)
        return;
    try {
        await brainFetch('POST', `scripts/executions/${executionId.value}/cancel`);
    }
    catch (e) {
        errorMessage.value = e instanceof Error ? e.message : 'Cancel failed';
    }
}
function close() {
    visible.value = false;
    emit('close');
}
onBeforeUnmount(() => {
    stopPolling();
    if (ws) {
        ws.close();
        ws = null;
    }
});
onMounted(() => {
    visible.value = true;
});
function fmtResult(v) {
    if (v === null || v === undefined)
        return '(no return value)';
    if (typeof v === 'string')
        return v;
    try {
        return JSON.stringify(v, null, 2);
    }
    catch {
        return String(v);
    }
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
const __VLS_0 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.visible),
    title: (`Execute · ${__VLS_ctx.file.path}`),
    closeOnBackdrop: (false),
}));
const __VLS_2 = __VLS_1({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.visible),
    title: (`Execute · ${__VLS_ctx.file.path}`),
    closeOnBackdrop: (false),
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
let __VLS_4;
let __VLS_5;
let __VLS_6;
const __VLS_7 = {
    'onUpdate:modelValue': ((v) => !v && __VLS_ctx.close())
};
var __VLS_8 = {};
__VLS_3.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "space-y-3 p-1" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
__VLS_asFunctionalElement(__VLS_intrinsicElements.label, __VLS_intrinsicElements.label)({
    ...{ class: "label" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "label-text font-semibold" },
});
const __VLS_9 = {}.VTextarea;
/** @type {[typeof __VLS_components.VTextarea, ]} */ ;
// @ts-ignore
const __VLS_10 = __VLS_asFunctionalComponent(__VLS_9, new __VLS_9({
    modelValue: (__VLS_ctx.argsJson),
    rows: (3),
    placeholder: '{ "n": 7 }',
    disabled: (__VLS_ctx.state === 'running' || __VLS_ctx.state === 'starting'),
}));
const __VLS_11 = __VLS_10({
    modelValue: (__VLS_ctx.argsJson),
    rows: (3),
    placeholder: '{ "n": 7 }',
    disabled: (__VLS_ctx.state === 'running' || __VLS_ctx.state === 'starting'),
}, ...__VLS_functionalComponentArgsRest(__VLS_10));
if (__VLS_ctx.argsError) {
    const __VLS_13 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_14 = __VLS_asFunctionalComponent(__VLS_13, new __VLS_13({
        variant: "error",
        ...{ class: "mt-1" },
    }));
    const __VLS_15 = __VLS_14({
        variant: "error",
        ...{ class: "mt-1" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_14));
    __VLS_16.slots.default;
    (__VLS_ctx.argsError);
    var __VLS_16;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex items-center gap-2" },
});
if (__VLS_ctx.state === 'idle' || __VLS_ctx.state === 'finished' || __VLS_ctx.state === 'failed' || __VLS_ctx.state === 'cancelled') {
    const __VLS_17 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_18 = __VLS_asFunctionalComponent(__VLS_17, new __VLS_17({
        ...{ 'onClick': {} },
        variant: "primary",
    }));
    const __VLS_19 = __VLS_18({
        ...{ 'onClick': {} },
        variant: "primary",
    }, ...__VLS_functionalComponentArgsRest(__VLS_18));
    let __VLS_21;
    let __VLS_22;
    let __VLS_23;
    const __VLS_24 = {
        onClick: (__VLS_ctx.start)
    };
    __VLS_20.slots.default;
    var __VLS_20;
}
if (__VLS_ctx.state === 'running' || __VLS_ctx.state === 'starting') {
    const __VLS_25 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_26 = __VLS_asFunctionalComponent(__VLS_25, new __VLS_25({
        ...{ 'onClick': {} },
        variant: "danger",
    }));
    const __VLS_27 = __VLS_26({
        ...{ 'onClick': {} },
        variant: "danger",
    }, ...__VLS_functionalComponentArgsRest(__VLS_26));
    let __VLS_29;
    let __VLS_30;
    let __VLS_31;
    const __VLS_32 = {
        onClick: (__VLS_ctx.cancel)
    };
    __VLS_28.slots.default;
    var __VLS_28;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "text-sm opacity-70 font-mono" },
});
(__VLS_ctx.state);
if (__VLS_ctx.durationMs !== null) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.durationMs);
}
if (__VLS_ctx.executionId) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "opacity-50" },
    });
    (__VLS_ctx.executionId.substring(0, 8));
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "label" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "label-text font-semibold" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.pre, __VLS_intrinsicElements.pre)({
    ...{ class: "font-mono text-xs bg-base-200 p-2 rounded max-h-64 overflow-y-auto whitespace-pre-wrap" },
});
(__VLS_ctx.logLines.length === 0 ? '(empty)' : __VLS_ctx.logLines.join('\n'));
if (__VLS_ctx.state === 'finished') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "label" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "label-text font-semibold text-success" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.pre, __VLS_intrinsicElements.pre)({
        ...{ class: "font-mono text-xs bg-base-200 p-2 rounded whitespace-pre-wrap" },
    });
    (__VLS_ctx.fmtResult(__VLS_ctx.resultValue));
}
if (__VLS_ctx.errorMessage) {
    const __VLS_33 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_34 = __VLS_asFunctionalComponent(__VLS_33, new __VLS_33({
        variant: "error",
    }));
    const __VLS_35 = __VLS_34({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_34));
    __VLS_36.slots.default;
    (__VLS_ctx.errorMessage);
    var __VLS_36;
}
{
    const { actions: __VLS_thisSlot } = __VLS_3.slots;
    const __VLS_37 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_38 = __VLS_asFunctionalComponent(__VLS_37, new __VLS_37({
        ...{ 'onClick': {} },
        variant: "ghost",
    }));
    const __VLS_39 = __VLS_38({
        ...{ 'onClick': {} },
        variant: "ghost",
    }, ...__VLS_functionalComponentArgsRest(__VLS_38));
    let __VLS_41;
    let __VLS_42;
    let __VLS_43;
    const __VLS_44 = {
        onClick: (__VLS_ctx.close)
    };
    __VLS_40.slots.default;
    var __VLS_40;
}
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['space-y-3']} */ ;
/** @type {__VLS_StyleScopedClasses['p-1']} */ ;
/** @type {__VLS_StyleScopedClasses['label']} */ ;
/** @type {__VLS_StyleScopedClasses['label-text']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['label']} */ ;
/** @type {__VLS_StyleScopedClasses['label-text']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['max-h-64']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['whitespace-pre-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['label']} */ ;
/** @type {__VLS_StyleScopedClasses['label-text']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-success']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['whitespace-pre-wrap']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            VButton: VButton,
            VModal: VModal,
            VTextarea: VTextarea,
            visible: visible,
            argsJson: argsJson,
            argsError: argsError,
            state: state,
            executionId: executionId,
            logLines: logLines,
            resultValue: resultValue,
            errorMessage: errorMessage,
            durationMs: durationMs,
            start: start,
            cancel: cancel,
            close: close,
            fmtResult: fmtResult,
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
//# sourceMappingURL=ExecutionDialog.vue.js.map