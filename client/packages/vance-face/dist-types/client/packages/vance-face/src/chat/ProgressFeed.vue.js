import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { VEmptyState } from '@components/index';
const { t } = useI18n();
const props = defineProps();
const reversed = computed(() => props.events.slice().reverse());
function kindOf(event) {
    return event.kind;
}
function summarise(event) {
    switch (kindOf(event)) {
        case 'METRICS': {
            const m = event.metrics;
            if (!m)
                return t('chat.progress.metricsLabel');
            const inK = Math.round(m.tokensInTotal / 100) / 10;
            const outK = Math.round(m.tokensOutTotal / 100) / 10;
            return t('chat.progress.metricsLine', {
                calls: m.llmCallCount,
                tokensIn: inK,
                tokensOut: outK,
            });
        }
        case 'PLAN':
            return event.plan?.rootNode?.title ?? t('chat.progress.planUpdated');
        case 'STATUS':
            return event.status?.text ?? t('chat.progress.status');
        default:
            return String(event.kind);
    }
}
function tagOf(event) {
    if (kindOf(event) !== 'STATUS' || !event.status)
        return null;
    return event.status.tag;
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "p-3 flex flex-col gap-3 min-h-0" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "text-xs uppercase tracking-wide opacity-60 font-semibold px-1" },
});
(__VLS_ctx.$t('chat.progress.title'));
if (__VLS_ctx.reversed.length === 0) {
    const __VLS_0 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
        headline: (__VLS_ctx.$t('chat.progress.empty')),
        body: (__VLS_ctx.$t('chat.progress.emptyBody')),
    }));
    const __VLS_2 = __VLS_1({
        headline: (__VLS_ctx.$t('chat.progress.empty')),
        body: (__VLS_ctx.$t('chat.progress.emptyBody')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.ol, __VLS_intrinsicElements.ol)({
        ...{ class: "flex flex-col gap-1.5 text-sm" },
    });
    for (const [event, idx] of __VLS_getVForSourceType((__VLS_ctx.reversed))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
            key: (`${event.processId}-${event.emittedAt}-${idx}`),
            ...{ class: "bg-base-200 rounded px-2.5 py-1.5" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center gap-1.5 text-xs opacity-60" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "font-mono" },
        });
        (event.engine);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "opacity-50" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "truncate" },
        });
        (event.processTitle || event.processName);
        if (__VLS_ctx.tagOf(event)) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "ml-auto px-1.5 rounded bg-base-300 text-[10px] uppercase" },
            });
            (__VLS_ctx.tagOf(event));
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "mt-0.5 break-words" },
        });
        (__VLS_ctx.summarise(event));
    }
}
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['ml-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['text-[10px]']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['break-words']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VEmptyState: VEmptyState,
            reversed: reversed,
            summarise: summarise,
            tagOf: tagOf,
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
//# sourceMappingURL=ProgressFeed.vue.js.map