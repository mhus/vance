import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
const props = defineProps();
const { t } = useI18n();
const showPending = computed(() => props.mode === 'PLANNING' && props.planMeta !== null);
const showTodos = computed(() => props.todos.length > 0);
const visible = computed(() => showPending.value || showTodos.value);
const planTitle = computed(() => {
    const v = props.planMeta?.version ?? 1;
    return v > 1
        ? t('chat.planMode.planTitleVersioned', { version: v })
        : t('chat.planMode.planTitle');
});
function statusOf(item) {
    return item.status ?? 'PENDING';
}
function markerOf(item) {
    switch (statusOf(item)) {
        case 'IN_PROGRESS': return '[~]';
        case 'COMPLETED': return '[✓]';
        case 'PENDING':
        default: return '[ ]';
    }
}
function labelOf(item) {
    if (statusOf(item) === 'IN_PROGRESS' && item.activeForm && item.activeForm.trim()) {
        return item.activeForm;
    }
    return item.content;
}
function rowClassOf(item) {
    switch (statusOf(item)) {
        case 'IN_PROGRESS': return 'text-warning font-medium';
        case 'COMPLETED': return 'opacity-50 line-through';
        case 'PENDING':
        default: return '';
    }
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
if (__VLS_ctx.visible) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "border-t border-base-300 bg-base-200/60 px-6 py-3 flex flex-col gap-3" },
    });
    if (__VLS_ctx.showPending) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "rounded-lg border border-info/40 bg-info/10 px-3 py-2 text-sm flex flex-col gap-1" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center gap-2" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "font-semibold text-info" },
        });
        (__VLS_ctx.planTitle);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-xs uppercase tracking-wide opacity-60" },
        });
        (__VLS_ctx.$t('chat.planMode.awaitingApproval'));
        if (__VLS_ctx.planMeta?.summary) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs opacity-80 break-words" },
            });
            (__VLS_ctx.planMeta.summary);
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60" },
        });
        (__VLS_ctx.$t('chat.planMode.approvalHint'));
    }
    if (__VLS_ctx.showTodos) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "uppercase tracking-wide opacity-60 font-semibold mb-1.5" },
        });
        (__VLS_ctx.$t('chat.planMode.todosTitle'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
            ...{ class: "flex flex-col gap-0.5 font-mono" },
        });
        for (const [item] of __VLS_getVForSourceType((__VLS_ctx.todos))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                key: (item.id || item.content),
                ...{ class: (__VLS_ctx.rowClassOf(item)) },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            (__VLS_ctx.markerOf(item));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "ml-2" },
            });
            (__VLS_ctx.labelOf(item));
        }
    }
}
/** @type {__VLS_StyleScopedClasses['border-t']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200/60']} */ ;
/** @type {__VLS_StyleScopedClasses['px-6']} */ ;
/** @type {__VLS_StyleScopedClasses['py-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-info/40']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-info/10']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-info']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['break-words']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['ml-2']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            showPending: showPending,
            showTodos: showTodos,
            visible: visible,
            planTitle: planTitle,
            markerOf: markerOf,
            labelOf: labelOf,
            rowClassOf: rowClassOf,
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
//# sourceMappingURL=PlanModeIndicator.vue.js.map