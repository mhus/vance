import { ref } from 'vue';
const __VLS_props = withDefaults(defineProps(), {
    type: 'text',
    required: false,
    disabled: false,
});
const __VLS_emit = defineEmits();
const inputRef = ref(null);
/** Imperative focus — callers grab a {@code ref="…"} and call this
 *  to programmatically focus the input. Useful when a wrapping modal
 *  keeps its content mounted across open/close cycles so the native
 *  {@code autofocus} attribute fires only on first paint. */
const __VLS_exposed = {
    focus() {
        inputRef.value?.focus();
    },
};
defineExpose(__VLS_exposed);
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    type: 'text',
    required: false,
    disabled: false,
});
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.label, __VLS_intrinsicElements.label)({
    ...{ class: "form-control w-full" },
});
if (__VLS_ctx.label) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "label" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "label-text" },
    });
    (__VLS_ctx.label);
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
    ...{ onInput: ((e) => __VLS_ctx.$emit('update:modelValue', e.target.value)) },
    ref: "inputRef",
    type: (__VLS_ctx.type),
    value: (__VLS_ctx.modelValue),
    placeholder: (__VLS_ctx.placeholder),
    required: (__VLS_ctx.required),
    disabled: (__VLS_ctx.disabled),
    autocomplete: (__VLS_ctx.autocomplete),
    ...{ class: (['input', 'input-bordered', 'w-full', { 'input-error': !!__VLS_ctx.error }]) },
});
/** @type {typeof __VLS_ctx.inputRef} */ ;
if (__VLS_ctx.error || __VLS_ctx.help) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "label" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: (['label-text-alt', __VLS_ctx.error ? 'text-error' : 'opacity-70']) },
    });
    (__VLS_ctx.error || __VLS_ctx.help);
}
/** @type {__VLS_StyleScopedClasses['form-control']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['label']} */ ;
/** @type {__VLS_StyleScopedClasses['label-text']} */ ;
/** @type {__VLS_StyleScopedClasses['input']} */ ;
/** @type {__VLS_StyleScopedClasses['input-bordered']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['input-error']} */ ;
/** @type {__VLS_StyleScopedClasses['label']} */ ;
/** @type {__VLS_StyleScopedClasses['label-text-alt']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            inputRef: inputRef,
        };
    },
    __typeEmits: {},
    __typeProps: {},
    props: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {
            ...__VLS_exposed,
        };
    },
    __typeEmits: {},
    __typeProps: {},
    props: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=VInput.vue.js.map