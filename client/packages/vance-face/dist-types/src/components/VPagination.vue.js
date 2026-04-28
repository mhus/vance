import { computed } from 'vue';
const props = defineProps();
const emit = defineEmits();
const pageCount = computed(() => {
    if (props.pageSize <= 0)
        return 1;
    return Math.max(1, Math.ceil(props.totalCount / props.pageSize));
});
const firstShownIndex = computed(() => props.totalCount === 0 ? 0 : props.page * props.pageSize + 1);
const lastShownIndex = computed(() => Math.min(props.totalCount, (props.page + 1) * props.pageSize));
function setPage(p) {
    const clamped = Math.max(0, Math.min(p, pageCount.value - 1));
    if (clamped !== props.page)
        emit('update:page', clamped);
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex items-center justify-between gap-3 text-sm" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "opacity-70" },
});
if (__VLS_ctx.totalCount === 0) {
}
else {
    (__VLS_ctx.firstShownIndex);
    (__VLS_ctx.lastShownIndex);
    (__VLS_ctx.totalCount);
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "join" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (...[$event]) => {
            __VLS_ctx.setPage(0);
        } },
    type: "button",
    ...{ class: "btn btn-sm btn-ghost join-item" },
    disabled: (__VLS_ctx.page <= 0),
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (...[$event]) => {
            __VLS_ctx.setPage(__VLS_ctx.page - 1);
        } },
    type: "button",
    ...{ class: "btn btn-sm btn-ghost join-item" },
    disabled: (__VLS_ctx.page <= 0),
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "btn btn-sm btn-ghost join-item pointer-events-none" },
});
(__VLS_ctx.page + 1);
(__VLS_ctx.pageCount);
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (...[$event]) => {
            __VLS_ctx.setPage(__VLS_ctx.page + 1);
        } },
    type: "button",
    ...{ class: "btn btn-sm btn-ghost join-item" },
    disabled: (__VLS_ctx.page >= __VLS_ctx.pageCount - 1),
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (...[$event]) => {
            __VLS_ctx.setPage(__VLS_ctx.pageCount - 1);
        } },
    type: "button",
    ...{ class: "btn btn-sm btn-ghost join-item" },
    disabled: (__VLS_ctx.page >= __VLS_ctx.pageCount - 1),
});
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['join']} */ ;
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-ghost']} */ ;
/** @type {__VLS_StyleScopedClasses['join-item']} */ ;
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-ghost']} */ ;
/** @type {__VLS_StyleScopedClasses['join-item']} */ ;
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-ghost']} */ ;
/** @type {__VLS_StyleScopedClasses['join-item']} */ ;
/** @type {__VLS_StyleScopedClasses['pointer-events-none']} */ ;
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-ghost']} */ ;
/** @type {__VLS_StyleScopedClasses['join-item']} */ ;
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-ghost']} */ ;
/** @type {__VLS_StyleScopedClasses['join-item']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            pageCount: pageCount,
            firstShownIndex: firstShownIndex,
            lastShownIndex: lastShownIndex,
            setPage: setPage,
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
//# sourceMappingURL=VPagination.vue.js.map