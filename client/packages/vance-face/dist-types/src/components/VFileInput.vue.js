import { ref } from 'vue';
const props = withDefaults(defineProps(), {
    required: false,
    disabled: false,
});
const emit = defineEmits();
const inputRef = ref(null);
const dragActive = ref(false);
function onChange(event) {
    const input = event.target;
    const file = input.files && input.files.length > 0 ? input.files[0] : null;
    emit('update:modelValue', file);
}
function onDragEnter(event) {
    if (props.disabled)
        return;
    event.preventDefault();
    dragActive.value = true;
}
function onDragOver(event) {
    if (props.disabled)
        return;
    // dragover must call preventDefault for the drop event to fire.
    event.preventDefault();
    dragActive.value = true;
}
function onDragLeave(event) {
    // dragleave fires for child elements too; only deactivate when the pointer
    // actually leaves the drop zone (relatedTarget is outside it).
    const related = event.relatedTarget;
    if (related && event.currentTarget.contains(related))
        return;
    dragActive.value = false;
}
function onDrop(event) {
    event.preventDefault();
    dragActive.value = false;
    if (props.disabled)
        return;
    const files = event.dataTransfer?.files;
    if (!files || files.length === 0)
        return;
    // Single-file component — take the first dropped item and ignore the rest.
    emit('update:modelValue', files[0]);
}
function clear() {
    emit('update:modelValue', null);
    if (inputRef.value)
        inputRef.value.value = '';
}
function formatSize(bytes) {
    if (bytes < 1024)
        return `${bytes} B`;
    if (bytes < 1024 * 1024)
        return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    required: false,
    disabled: false,
});
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
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
__VLS_asFunctionalElement(__VLS_intrinsicElements.label, __VLS_intrinsicElements.label)({
    ...{ onDragenter: (__VLS_ctx.onDragEnter) },
    ...{ onDragover: (__VLS_ctx.onDragOver) },
    ...{ onDragleave: (__VLS_ctx.onDragLeave) },
    ...{ onDrop: (__VLS_ctx.onDrop) },
    ...{ class: ([
            'flex flex-col items-center justify-center text-center',
            'border-2 border-dashed rounded-lg px-6 py-8 transition-colors',
            __VLS_ctx.disabled ? 'opacity-60 cursor-not-allowed' : 'cursor-pointer',
            __VLS_ctx.dragActive
                ? 'border-primary bg-primary/10'
                : (__VLS_ctx.error ? 'border-error' : 'border-base-300'),
            !__VLS_ctx.disabled && !__VLS_ctx.dragActive ? 'hover:border-primary hover:bg-base-200' : '',
        ]) },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
    ...{ onChange: (__VLS_ctx.onChange) },
    ref: "inputRef",
    type: "file",
    ...{ class: "hidden" },
    accept: (__VLS_ctx.accept),
    required: (__VLS_ctx.required),
    disabled: (__VLS_ctx.disabled),
});
/** @type {typeof __VLS_ctx.inputRef} */ ;
if (__VLS_ctx.modelValue) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-2xl mb-2" },
        'aria-hidden': "true",
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "font-mono text-sm break-all" },
    });
    (__VLS_ctx.modelValue.name);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-xs opacity-60 mt-1" },
    });
    (__VLS_ctx.formatSize(__VLS_ctx.modelValue.size));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.clear) },
        type: "button",
        ...{ class: "btn btn-ghost btn-xs mt-3" },
        disabled: (__VLS_ctx.disabled),
    });
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-2xl mb-2 opacity-60" },
        'aria-hidden': "true",
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-sm" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "link link-primary" },
    });
    if (__VLS_ctx.accept) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-xs opacity-60 mt-1" },
        });
        (__VLS_ctx.accept);
    }
}
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
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['text-center']} */ ;
/** @type {__VLS_StyleScopedClasses['border-2']} */ ;
/** @type {__VLS_StyleScopedClasses['border-dashed']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['px-6']} */ ;
/** @type {__VLS_StyleScopedClasses['py-8']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['text-2xl']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['break-all']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-ghost']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-2xl']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['link']} */ ;
/** @type {__VLS_StyleScopedClasses['link-primary']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['label']} */ ;
/** @type {__VLS_StyleScopedClasses['label-text-alt']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            inputRef: inputRef,
            dragActive: dragActive,
            onChange: onChange,
            onDragEnter: onDragEnter,
            onDragOver: onDragOver,
            onDragLeave: onDragLeave,
            onDrop: onDrop,
            clear: clear,
            formatSize: formatSize,
        };
    },
    __typeEmits: {},
    __typeProps: {},
    props: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeEmits: {},
    __typeProps: {},
    props: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=VFileInput.vue.js.map