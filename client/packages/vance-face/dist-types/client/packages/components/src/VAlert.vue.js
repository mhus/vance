import { computed } from 'vue';
const props = withDefaults(defineProps(), { variant: 'info' });
const variantClass = computed(() => {
    switch (props.variant) {
        case 'info': return 'alert-info';
        case 'warning': return 'alert-warning';
        case 'error': return 'alert-error';
        case 'success': return 'alert-success';
    }
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({ variant: 'info' });
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    role: "alert",
    ...{ class: (['alert', __VLS_ctx.variantClass]) },
});
var __VLS_0 = {};
/** @type {__VLS_StyleScopedClasses['alert']} */ ;
// @ts-ignore
var __VLS_1 = __VLS_0;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            variantClass: variantClass,
        };
    },
    __typeProps: {},
    props: {},
});
const __VLS_component = (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeProps: {},
    props: {},
});
export default {};
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=VAlert.vue.js.map