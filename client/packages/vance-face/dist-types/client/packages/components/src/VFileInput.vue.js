import { ref } from 'vue';
const props = withDefaults(defineProps(), {
    multiple: false,
    required: false,
    disabled: false,
});
const emit = defineEmits();
const inputRef = ref(null);
const dragActive = ref(false);
function setFiles(picked) {
    if (!picked) {
        emit('update:modelValue', []);
        return;
    }
    const incoming = Array.from(picked);
    if (!props.multiple) {
        emit('update:modelValue', incoming.slice(0, 1));
        return;
    }
    // Multi-mode: append to existing selection so subsequent drops or picker
    // re-opens stack rather than replace. Dedupe by name + size + lastModified
    // so dragging the same file twice doesn't show twice.
    const merged = props.modelValue.slice();
    const fingerprint = (f) => `${f.name}|${f.size}|${f.lastModified}`;
    const seen = new Set(merged.map(fingerprint));
    for (const f of incoming) {
        const key = fingerprint(f);
        if (!seen.has(key)) {
            merged.push(f);
            seen.add(key);
        }
    }
    emit('update:modelValue', merged);
}
function onChange(event) {
    const input = event.target;
    setFiles(input.files);
    // Reset so the same file picked twice in a row still fires `change` —
    // matters in multi-mode where "add more" opens the picker again.
    input.value = '';
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
    setFiles(event.dataTransfer?.files ?? null);
}
function clearAll() {
    emit('update:modelValue', []);
    if (inputRef.value)
        inputRef.value.value = '';
}
function removeAt(index) {
    const next = props.modelValue.slice();
    next.splice(index, 1);
    emit('update:modelValue', next);
    if (next.length === 0 && inputRef.value)
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
    multiple: false,
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
            'border-2 border-dashed rounded-lg px-6 py-6 transition-colors',
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
    multiple: (__VLS_ctx.multiple),
    required: (__VLS_ctx.required),
    disabled: (__VLS_ctx.disabled),
});
/** @type {typeof __VLS_ctx.inputRef} */ ;
if (__VLS_ctx.modelValue.length === 0) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-2xl mb-2 opacity-60" },
        'aria-hidden': "true",
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-sm" },
    });
    (__VLS_ctx.multiple ? 'Drop files here, or' : 'Drop a file here, or');
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
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
        ...{ class: "w-full flex flex-col gap-1.5 text-left" },
    });
    for (const [file, idx] of __VLS_getVForSourceType((__VLS_ctx.modelValue))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
            key: (`${file.name}-${idx}`),
            ...{ class: "flex items-center gap-2 text-sm" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            'aria-hidden': "true",
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "font-mono truncate flex-1" },
        });
        (file.name);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-xs opacity-60 shrink-0" },
        });
        (__VLS_ctx.formatSize(file.size));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(__VLS_ctx.modelValue.length === 0))
                        return;
                    __VLS_ctx.removeAt(idx);
                } },
            type: "button",
            ...{ class: "btn btn-ghost btn-xs" },
            disabled: (__VLS_ctx.disabled),
            'aria-label': "Remove file",
        });
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "mt-3 text-xs opacity-70" },
    });
    if (__VLS_ctx.multiple) {
        (__VLS_ctx.modelValue.length);
        (__VLS_ctx.modelValue.length === 1 ? '' : 's');
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "link link-primary" },
        });
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.clearAll) },
        type: "button",
        ...{ class: "link link-error" },
        disabled: (__VLS_ctx.disabled),
    });
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
/** @type {__VLS_StyleScopedClasses['py-6']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['text-2xl']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['link']} */ ;
/** @type {__VLS_StyleScopedClasses['link-primary']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-left']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-ghost']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['link']} */ ;
/** @type {__VLS_StyleScopedClasses['link-primary']} */ ;
/** @type {__VLS_StyleScopedClasses['link']} */ ;
/** @type {__VLS_StyleScopedClasses['link-error']} */ ;
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
            clearAll: clearAll,
            removeAt: removeAt,
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