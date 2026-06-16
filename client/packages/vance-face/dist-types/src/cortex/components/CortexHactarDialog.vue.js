import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { VAlert, VButton, VModal, VTextarea } from '@/components';
import { brainFetch } from '@vance/shared';
const props = defineProps();
const emit = defineEmits();
const visible = ref(false);
const prompt = ref(initialPrompt());
const busy = ref(false);
const polling = ref(false);
const error = ref(null);
const thinkProcessId = ref(null);
const result = ref(null);
let pollTimer = null;
const sessionId = computed(() => props.sessionId ?? '_cortex');
const title = computed(() => props.mode === 'UPDATE'
    ? 'Slart · update this script'
    : 'Slart · generate a new script');
const cta = computed(() => props.mode === 'UPDATE' ? 'Update' : 'Generate');
function initialPrompt() {
    if (props.mode === 'UPDATE' && props.failureReason) {
        return `The previous run failed with:\n\n> ${props.failureReason
            .split('\n')
            .join('\n> ')}\n\nPlease fix this.`;
    }
    return '';
}
async function start() {
    if (!prompt.value.trim()) {
        error.value = 'Description required';
        return;
    }
    busy.value = true;
    error.value = null;
    result.value = null;
    const body = {
        prompt: prompt.value,
        mode: props.mode,
    };
    if (props.mode === 'UPDATE' && props.document) {
        body.existingScriptId = props.document.id;
    }
    if (props.mode === 'UPDATE' && props.failureReason) {
        body.failureReason = props.failureReason;
    }
    try {
        const resp = await brainFetch('POST', `scripts/generate?projectId=${encodeURIComponent(props.projectId)}&sessionId=${encodeURIComponent(sessionId.value)}`, { body });
        thinkProcessId.value = resp.thinkProcessId;
        pollUntilDone();
    }
    catch (e) {
        error.value = e instanceof Error ? e.message : 'Generation failed to start';
    }
    finally {
        busy.value = false;
    }
}
function pollUntilDone() {
    polling.value = true;
    const tick = async () => {
        if (!thinkProcessId.value) {
            polling.value = false;
            return;
        }
        try {
            const r = await brainFetch('GET', `scripts/generations/${thinkProcessId.value}/result`);
            result.value = r;
            const terminal = r.status === 'CLOSED'
                || r.status === 'FAILED'
                || r.reason === 'DONE'
                || r.reason === 'FAILED';
            if (terminal) {
                polling.value = false;
                return;
            }
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Poll failed';
            polling.value = false;
            return;
        }
        pollTimer = window.setTimeout(tick, 2500);
    };
    tick();
}
function applyResult() {
    if (!result.value?.code)
        return;
    emit('apply', result.value.code);
    close();
}
function close() {
    visible.value = false;
    if (pollTimer != null) {
        window.clearTimeout(pollTimer);
        pollTimer = null;
    }
    emit('close');
}
onMounted(() => {
    visible.value = true;
});
onBeforeUnmount(() => {
    if (pollTimer != null) {
        window.clearTimeout(pollTimer);
        pollTimer = null;
    }
});
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
    title: (__VLS_ctx.title),
    closeOnBackdrop: (false),
}));
const __VLS_2 = __VLS_1({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.visible),
    title: (__VLS_ctx.title),
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
if (!__VLS_ctx.result || !__VLS_ctx.result.code) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.label, __VLS_intrinsicElements.label)({
        ...{ class: "label" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "label-text font-semibold" },
    });
    (__VLS_ctx.mode === 'UPDATE'
        ? 'What should change?'
        : 'What should the script do?');
    const __VLS_9 = {}.VTextarea;
    /** @type {[typeof __VLS_components.VTextarea, ]} */ ;
    // @ts-ignore
    const __VLS_10 = __VLS_asFunctionalComponent(__VLS_9, new __VLS_9({
        modelValue: (__VLS_ctx.prompt),
        rows: (6),
        placeholder: (__VLS_ctx.mode === 'UPDATE'
            ? 'Example: \'Also send a Slack notification when the inbox is empty.\''
            : 'Example: \'Read a number from args.n and return its factorial.\''),
    }));
    const __VLS_11 = __VLS_10({
        modelValue: (__VLS_ctx.prompt),
        rows: (6),
        placeholder: (__VLS_ctx.mode === 'UPDATE'
            ? 'Example: \'Also send a Slack notification when the inbox is empty.\''
            : 'Example: \'Read a number from args.n and return its factorial.\''),
    }, ...__VLS_functionalComponentArgsRest(__VLS_10));
    if (__VLS_ctx.mode === 'UPDATE') {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-70 mt-1" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.em, __VLS_intrinsicElements.em)({});
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center gap-2 mt-3" },
    });
    const __VLS_13 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_14 = __VLS_asFunctionalComponent(__VLS_13, new __VLS_13({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.busy),
    }));
    const __VLS_15 = __VLS_14({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.busy),
    }, ...__VLS_functionalComponentArgsRest(__VLS_14));
    let __VLS_17;
    let __VLS_18;
    let __VLS_19;
    const __VLS_20 = {
        onClick: (__VLS_ctx.start)
    };
    __VLS_16.slots.default;
    (__VLS_ctx.cta);
    var __VLS_16;
    if (__VLS_ctx.polling) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-sm opacity-70" },
        });
    }
    if (__VLS_ctx.result?.status) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-sm font-mono opacity-70" },
        });
        (__VLS_ctx.result.status);
        if (__VLS_ctx.result.reason) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            (__VLS_ctx.result.reason);
        }
    }
}
if (__VLS_ctx.error) {
    const __VLS_21 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_22 = __VLS_asFunctionalComponent(__VLS_21, new __VLS_21({
        variant: "error",
    }));
    const __VLS_23 = __VLS_22({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_22));
    __VLS_24.slots.default;
    (__VLS_ctx.error);
    var __VLS_24;
}
if (__VLS_ctx.result?.code) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "font-semibold mb-1" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.pre, __VLS_intrinsicElements.pre)({
        ...{ class: "bg-base-200 p-2 rounded text-xs max-h-72 overflow-y-auto whitespace-pre-wrap font-mono" },
    });
    (__VLS_ctx.result.code);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex gap-2 mt-3" },
    });
    const __VLS_25 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_26 = __VLS_asFunctionalComponent(__VLS_25, new __VLS_25({
        ...{ 'onClick': {} },
        variant: "primary",
    }));
    const __VLS_27 = __VLS_26({
        ...{ 'onClick': {} },
        variant: "primary",
    }, ...__VLS_functionalComponentArgsRest(__VLS_26));
    let __VLS_29;
    let __VLS_30;
    let __VLS_31;
    const __VLS_32 = {
        onClick: (__VLS_ctx.applyResult)
    };
    __VLS_28.slots.default;
    var __VLS_28;
    const __VLS_33 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_34 = __VLS_asFunctionalComponent(__VLS_33, new __VLS_33({
        ...{ 'onClick': {} },
        variant: "ghost",
    }));
    const __VLS_35 = __VLS_34({
        ...{ 'onClick': {} },
        variant: "ghost",
    }, ...__VLS_functionalComponentArgsRest(__VLS_34));
    let __VLS_37;
    let __VLS_38;
    let __VLS_39;
    const __VLS_40 = {
        onClick: (...[$event]) => {
            if (!(__VLS_ctx.result?.code))
                return;
            __VLS_ctx.result = null;
        }
    };
    __VLS_36.slots.default;
    var __VLS_36;
}
if (__VLS_ctx.result?.failureReason) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm text-error" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "font-semibold" },
    });
    (__VLS_ctx.result.failureReason);
}
{
    const { actions: __VLS_thisSlot } = __VLS_3.slots;
    const __VLS_41 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_42 = __VLS_asFunctionalComponent(__VLS_41, new __VLS_41({
        ...{ 'onClick': {} },
        variant: "ghost",
    }));
    const __VLS_43 = __VLS_42({
        ...{ 'onClick': {} },
        variant: "ghost",
    }, ...__VLS_functionalComponentArgsRest(__VLS_42));
    let __VLS_45;
    let __VLS_46;
    let __VLS_47;
    const __VLS_48 = {
        onClick: (__VLS_ctx.close)
    };
    __VLS_44.slots.default;
    var __VLS_44;
}
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['space-y-3']} */ ;
/** @type {__VLS_StyleScopedClasses['p-1']} */ ;
/** @type {__VLS_StyleScopedClasses['label']} */ ;
/** @type {__VLS_StyleScopedClasses['label-text']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['max-h-72']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['whitespace-pre-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-error']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            VButton: VButton,
            VModal: VModal,
            VTextarea: VTextarea,
            visible: visible,
            prompt: prompt,
            busy: busy,
            polling: polling,
            error: error,
            result: result,
            title: title,
            cta: cta,
            start: start,
            applyResult: applyResult,
            close: close,
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
//# sourceMappingURL=CortexHactarDialog.vue.js.map