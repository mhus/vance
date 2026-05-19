import { ref, computed } from 'vue';
import { VAlert, VButton } from '@/components';
import { brainFetch } from '@vance/shared';
const props = defineProps();
const quickResult = ref(null);
const deepResult = ref(null);
const quickBusy = ref(false);
const deepBusy = ref(false);
const error = ref(null);
const cachedDeepWarnings = computed(() => {
    if (!props.file.lastDeepReviewWarningsJson)
        return null;
    try {
        return JSON.parse(props.file.lastDeepReviewWarningsJson);
    }
    catch {
        return null;
    }
});
const reviewedHashMatches = computed(() => {
    return !!props.file.lastDeepReviewedHash && !props.file.dirty;
});
async function runQuick() {
    quickBusy.value = true;
    error.value = null;
    try {
        quickResult.value = await brainFetch('POST', 'scripts/validate', {
            body: {
                scriptId: props.file.id,
                code: props.file.inlineText,
                sourceName: props.file.path,
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
                scriptId: props.file.id,
                code: props.file.inlineText,
                sourceName: props.file.path,
            },
        });
        // Also mirror onto the file so the badge above the editor flips
        // to "still reviewed" until the next edit.
        // We don't compute the hash client-side — server already cached
        // it; on next file-load it comes back through DocumentDto.
    }
    catch (e) {
        error.value = e instanceof Error ? e.message : 'Deep validate failed';
    }
    finally {
        deepBusy.value = false;
    }
}
const __VLS_exposed = { runQuick, runDeep };
defineExpose(__VLS_exposed);
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "p-3 text-sm space-y-3" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex items-center gap-2" },
});
const __VLS_0 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    ...{ 'onClick': {} },
    size: "sm",
    loading: (__VLS_ctx.quickBusy),
}));
const __VLS_2 = __VLS_1({
    ...{ 'onClick': {} },
    size: "sm",
    loading: (__VLS_ctx.quickBusy),
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
let __VLS_4;
let __VLS_5;
let __VLS_6;
const __VLS_7 = {
    onClick: (__VLS_ctx.runQuick)
};
__VLS_3.slots.default;
var __VLS_3;
const __VLS_8 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "secondary",
    loading: (__VLS_ctx.deepBusy),
}));
const __VLS_10 = __VLS_9({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "secondary",
    loading: (__VLS_ctx.deepBusy),
}, ...__VLS_functionalComponentArgsRest(__VLS_9));
let __VLS_12;
let __VLS_13;
let __VLS_14;
const __VLS_15 = {
    onClick: (__VLS_ctx.runDeep)
};
__VLS_11.slots.default;
var __VLS_11;
if (__VLS_ctx.error) {
    const __VLS_16 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
        variant: "error",
    }));
    const __VLS_18 = __VLS_17({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_17));
    __VLS_19.slots.default;
    (__VLS_ctx.error);
    var __VLS_19;
}
if (__VLS_ctx.quickResult) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm" },
    });
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
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm" },
    });
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
        ...{ class: "text-sm opacity-80" },
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
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['space-y-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-success']} */ ;
/** @type {__VLS_StyleScopedClasses['list-disc']} */ ;
/** @type {__VLS_StyleScopedClasses['pl-4']} */ ;
/** @type {__VLS_StyleScopedClasses['text-error']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
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
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
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
            quickResult: quickResult,
            deepResult: deepResult,
            quickBusy: quickBusy,
            deepBusy: deepBusy,
            error: error,
            cachedDeepWarnings: cachedDeepWarnings,
            reviewedHashMatches: reviewedHashMatches,
            runQuick: runQuick,
            runDeep: runDeep,
        };
    },
    __typeProps: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {
            ...__VLS_exposed,
        };
    },
    __typeProps: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=ValidatePanel.vue.js.map