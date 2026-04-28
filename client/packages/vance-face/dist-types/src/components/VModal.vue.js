import { onUnmounted, ref, watch } from 'vue';
const props = withDefaults(defineProps(), {
    closeOnBackdrop: true,
});
const emit = defineEmits();
const dialog = ref(null);
watch(() => props.modelValue, (open) => {
    const el = dialog.value;
    if (!el)
        return;
    if (open && !el.open)
        el.showModal();
    if (!open && el.open)
        el.close();
});
function onClose() {
    emit('update:modelValue', false);
}
function onBackdropClick(event) {
    if (!props.closeOnBackdrop)
        return;
    // The dialog element receives clicks on the backdrop when the click target
    // is the dialog itself (not its inner content).
    if (event.target === dialog.value)
        onClose();
}
onUnmounted(() => {
    if (dialog.value?.open)
        dialog.value.close();
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    closeOnBackdrop: true,
});
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.dialog, __VLS_intrinsicElements.dialog)({
    ...{ onClose: (__VLS_ctx.onClose) },
    ...{ onClick: (__VLS_ctx.onBackdropClick) },
    ref: "dialog",
    ...{ class: "modal" },
});
/** @type {typeof __VLS_ctx.dialog} */ ;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "modal-box max-w-2xl" },
});
if (__VLS_ctx.title || __VLS_ctx.$slots.header) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.header, __VLS_intrinsicElements.header)({
        ...{ class: "flex items-center justify-between mb-3" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({
        ...{ class: "text-lg font-semibold" },
    });
    var __VLS_0 = {};
    (__VLS_ctx.title);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.onClose) },
        type: "button",
        ...{ class: "btn btn-sm btn-circle btn-ghost" },
        'aria-label': "Close",
    });
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
var __VLS_2 = {};
if (__VLS_ctx.$slots.actions) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.footer, __VLS_intrinsicElements.footer)({
        ...{ class: "modal-action" },
    });
    var __VLS_4 = {};
}
/** @type {__VLS_StyleScopedClasses['modal']} */ ;
/** @type {__VLS_StyleScopedClasses['modal-box']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-2xl']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-circle']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-ghost']} */ ;
/** @type {__VLS_StyleScopedClasses['modal-action']} */ ;
// @ts-ignore
var __VLS_1 = __VLS_0, __VLS_3 = __VLS_2, __VLS_5 = __VLS_4;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            dialog: dialog,
            onClose: onClose,
            onBackdropClick: onBackdropClick,
        };
    },
    __typeEmits: {},
    __typeProps: {},
    props: {},
});
const __VLS_component = (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeEmits: {},
    __typeProps: {},
    props: {},
});
export default {};
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=VModal.vue.js.map