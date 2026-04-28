import { computed } from 'vue';
const props = withDefaults(defineProps(), {
    variant: 'primary',
    type: 'button',
    loading: false,
    disabled: false,
    block: false,
    size: 'md',
});
const __VLS_emit = defineEmits();
const variantClass = computed(() => {
    switch (props.variant) {
        case 'primary': return 'btn-primary';
        case 'secondary': return 'btn-secondary';
        case 'ghost': return 'btn-ghost';
        case 'danger': return 'btn-error';
        case 'link': return 'btn-link';
    }
});
const sizeClass = computed(() => (props.size === 'sm' ? 'btn-sm' : ''));
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    variant: 'primary',
    type: 'button',
    loading: false,
    disabled: false,
    block: false,
    size: 'md',
});
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
if (__VLS_ctx.href) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
        ...{ onClick: ((e) => __VLS_ctx.$emit('click', e)) },
        href: (__VLS_ctx.href),
        ...{ class: (['btn', __VLS_ctx.variantClass, __VLS_ctx.sizeClass, { 'btn-block': __VLS_ctx.block, 'btn-disabled': __VLS_ctx.disabled }]) },
    });
    if (__VLS_ctx.loading) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
            ...{ class: "loading loading-spinner loading-sm" },
        });
    }
    var __VLS_0 = {};
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: ((e) => __VLS_ctx.$emit('click', e)) },
        type: (__VLS_ctx.type),
        disabled: (__VLS_ctx.disabled || __VLS_ctx.loading),
        ...{ class: (['btn', __VLS_ctx.variantClass, __VLS_ctx.sizeClass, { 'btn-block': __VLS_ctx.block }]) },
    });
    if (__VLS_ctx.loading) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
            ...{ class: "loading loading-spinner loading-sm" },
        });
    }
    var __VLS_2 = {};
}
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-block']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-disabled']} */ ;
/** @type {__VLS_StyleScopedClasses['loading']} */ ;
/** @type {__VLS_StyleScopedClasses['loading-spinner']} */ ;
/** @type {__VLS_StyleScopedClasses['loading-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-block']} */ ;
/** @type {__VLS_StyleScopedClasses['loading']} */ ;
/** @type {__VLS_StyleScopedClasses['loading-spinner']} */ ;
/** @type {__VLS_StyleScopedClasses['loading-sm']} */ ;
// @ts-ignore
var __VLS_1 = __VLS_0, __VLS_3 = __VLS_2;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            variantClass: variantClass,
            sizeClass: sizeClass,
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
//# sourceMappingURL=VButton.vue.js.map