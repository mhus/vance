import { computed, onMounted, ref } from 'vue';
import { VAlert, VButton, VModal } from '@/components';
import { brainFetch } from '@vance/shared';
const props = defineProps();
const emit = defineEmits();
// VModal opens on a false→true transition inside onMounted — see
// specification/web-ui.md §7.7.
const visible = ref(false);
const quickResult = ref(null);
const deepResult = ref(null);
const quickBusy = ref(false);
const deepBusy = ref(false);
const error = ref(null);
const cachedDeepWarnings = computed(() => {
    const raw = props.document.lastDeepReviewWarningsJson;
    if (!raw)
        return null;
    try {
        return JSON.parse(raw);
    }
    catch {
        return null;
    }
});
const reviewedHashMatches = computed(() => {
    return !!props.document.lastDeepReviewedHash && !props.document.dirty;
});
async function runQuick() {
    quickBusy.value = true;
    error.value = null;
    try {
        quickResult.value = await brainFetch('POST', 'scripts/validate', {
            body: {
                scriptId: props.document.id,
                code: props.document.inlineText,
                sourceName: props.document.path,
            },
        });
    }
    catch (e) {
        error.value = e instanceof Error ? e.message : 'Validate failed';
    }
    finally {
        quickBusy.value = false;
    }
}
async function runDeep() {
    deepBusy.value = true;
    error.value = null;
    try {
        deepResult.value = await brainFetch('POST', 'scripts/validate-deep', {
            body: {
                scriptId: props.document.id,
                code: props.document.inlineText,
                sourceName: props.document.path,
            },
        });
        // Server side caches lastDeepReviewedHash; next time the DTO
        // loads it'll come through dtoToDocument. We don't recompute
        // hash client-side.
    }
    catch (e) {
        error.value = e instanceof Error ? e.message : 'Deep validate failed';
    }
    finally {
        deepBusy.value = false;
    }
}
function close() {
    visible.value = false;
    emit('close');
}
onMounted(() => {
    visible.value = true;
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
    title: (`Validate · ${__VLS_ctx.document.path}`),
}));
const __VLS_2 = __VLS_1({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.visible),
    title: (`Validate · ${__VLS_ctx.document.path}`),
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
    ...{ class: "space-y-3 p-1 text-sm" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex items-center gap-2" },
});
const __VLS_9 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_10 = __VLS_asFunctionalComponent(__VLS_9, new __VLS_9({
    ...{ 'onClick': {} },
    size: "sm",
    loading: (__VLS_ctx.quickBusy),
}));
const __VLS_11 = __VLS_10({
    ...{ 'onClick': {} },
    size: "sm",
    loading: (__VLS_ctx.quickBusy),
}, ...__VLS_functionalComponentArgsRest(__VLS_10));
let __VLS_13;
let __VLS_14;
let __VLS_15;
const __VLS_16 = {
    onClick: (__VLS_ctx.runQuick)
};
__VLS_12.slots.default;
var __VLS_12;
const __VLS_17 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_18 = __VLS_asFunctionalComponent(__VLS_17, new __VLS_17({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "secondary",
    loading: (__VLS_ctx.deepBusy),
}));
const __VLS_19 = __VLS_18({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "secondary",
    loading: (__VLS_ctx.deepBusy),
}, ...__VLS_functionalComponentArgsRest(__VLS_18));
let __VLS_21;
let __VLS_22;
let __VLS_23;
const __VLS_24 = {
    onClick: (__VLS_ctx.runDeep)
};
__VLS_20.slots.default;
var __VLS_20;
if (__VLS_ctx.error) {
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
    (__VLS_ctx.error);
    var __VLS_28;
}
if (__VLS_ctx.quickResult) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "font-semibold mb-1" },
    });
    if (__VLS_ctx.quickResult.ok) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-success" },
        });
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
            ...{ class: "list-disc pl-4 text-error" },
        });
        for (const [e, i] of __VLS_getVForSourceType(((__VLS_ctx.quickResult.errors ?? [])))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                key: (i),
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "font-mono" },
            });
            (e.line);
            (e.column);
            (e.message);
        }
    }
}
if (__VLS_ctx.deepResult) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "font-semibold mb-1" },
    });
    if (__VLS_ctx.deepResult.summary) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "italic opacity-70 mb-1" },
        });
        (__VLS_ctx.deepResult.summary);
    }
    if ((__VLS_ctx.deepResult.warnings ?? []).length === 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-success" },
        });
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
            ...{ class: "space-y-1" },
        });
        for (const [w, i] of __VLS_getVForSourceType(((__VLS_ctx.deepResult.warnings ?? [])))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                key: (i),
                ...{ class: ([
                        'border-l-2 pl-2',
                        w.severity === 'error' ? 'border-error' :
                            w.severity === 'warn' ? 'border-warning' : 'border-info',
                    ]) },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs font-mono opacity-60" },
            });
            (w.category);
            (w.line);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            (w.message);
        }
    }
}
if (!__VLS_ctx.deepResult && __VLS_ctx.cachedDeepWarnings) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "opacity-80" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "font-semibold mb-1" },
    });
    if (__VLS_ctx.reviewedHashMatches) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-success text-xs" },
        });
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-warning text-xs" },
        });
    }
    if (__VLS_ctx.cachedDeepWarnings.length > 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
            ...{ class: "space-y-1 opacity-80" },
        });
        for (const [w, i] of __VLS_getVForSourceType((__VLS_ctx.cachedDeepWarnings))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                key: (i),
                ...{ class: ([
                        'border-l-2 pl-2',
                        w.severity === 'error' ? 'border-error' :
                            w.severity === 'warn' ? 'border-warning' : 'border-info',
                    ]) },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs font-mono opacity-60" },
            });
            (w.category);
            (w.line);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            (w.message);
        }
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-success text-sm" },
        });
    }
}
{
    const { actions: __VLS_thisSlot } = __VLS_3.slots;
    const __VLS_29 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_30 = __VLS_asFunctionalComponent(__VLS_29, new __VLS_29({
        ...{ 'onClick': {} },
        variant: "ghost",
    }));
    const __VLS_31 = __VLS_30({
        ...{ 'onClick': {} },
        variant: "ghost",
    }, ...__VLS_functionalComponentArgsRest(__VLS_30));
    let __VLS_33;
    let __VLS_34;
    let __VLS_35;
    const __VLS_36 = {
        onClick: (__VLS_ctx.close)
    };
    __VLS_32.slots.default;
    var __VLS_32;
}
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['space-y-3']} */ ;
/** @type {__VLS_StyleScopedClasses['p-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-success']} */ ;
/** @type {__VLS_StyleScopedClasses['list-disc']} */ ;
/** @type {__VLS_StyleScopedClasses['pl-4']} */ ;
/** @type {__VLS_StyleScopedClasses['text-error']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['italic']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-success']} */ ;
/** @type {__VLS_StyleScopedClasses['space-y-1']} */ ;
/** @type {__VLS_StyleScopedClasses['border-l-2']} */ ;
/** @type {__VLS_StyleScopedClasses['pl-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-success']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-warning']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['space-y-1']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['border-l-2']} */ ;
/** @type {__VLS_StyleScopedClasses['pl-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-success']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            VButton: VButton,
            VModal: VModal,
            visible: visible,
            quickResult: quickResult,
            deepResult: deepResult,
            quickBusy: quickBusy,
            deepBusy: deepBusy,
            error: error,
            cachedDeepWarnings: cachedDeepWarnings,
            reviewedHashMatches: reviewedHashMatches,
            runQuick: runQuick,
            runDeep: runDeep,
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
//# sourceMappingURL=CortexValidateDialog.vue.js.map