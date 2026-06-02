import { ref } from 'vue';
const props = withDefaults(defineProps(), {
    disabled: false,
    maxTags: 20,
    maxTagChars: 50,
});
const emit = defineEmits();
const draft = ref('');
function normalise(raw) {
    return raw.trim().toLowerCase().slice(0, props.maxTagChars);
}
function commitDraft() {
    if (props.disabled)
        return;
    const value = normalise(draft.value);
    draft.value = '';
    if (!value)
        return;
    if (props.modelValue.includes(value))
        return;
    if (props.modelValue.length >= props.maxTags)
        return;
    emit('update:modelValue', [...props.modelValue, value]);
}
function remove(tag) {
    if (props.disabled)
        return;
    emit('update:modelValue', props.modelValue.filter((t) => t !== tag));
}
function onKey(event) {
    // Enter / Comma / Tab all commit the current draft; Backspace on an
    // empty draft removes the last chip — same convention as common
    // chip-inputs (GitHub labels, Linear tags).
    if (event.key === 'Enter' || event.key === ',' || event.key === 'Tab') {
        if (draft.value.trim()) {
            event.preventDefault();
            commitDraft();
        }
    }
    else if (event.key === 'Backspace' && draft.value === '' && props.modelValue.length > 0) {
        event.preventDefault();
        const last = props.modelValue[props.modelValue.length - 1];
        remove(last);
    }
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    disabled: false,
    maxTags: 20,
    maxTagChars: 50,
});
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-2" },
});
if (__VLS_ctx.label) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-xs opacity-70" },
    });
    (__VLS_ctx.label);
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-wrap items-center gap-1 rounded-md border border-base-300 bg-base-100 px-2 py-1.5 min-h-[2.25rem]" },
    ...{ class: (__VLS_ctx.disabled ? 'opacity-60' : '') },
});
for (const [tag] of __VLS_getVForSourceType((__VLS_ctx.modelValue))) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        key: (tag),
        ...{ class: "inline-flex items-center gap-1 text-xs px-1.5 py-0.5 rounded bg-base-200" },
    });
    (tag);
    if (!__VLS_ctx.disabled) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!(!__VLS_ctx.disabled))
                        return;
                    __VLS_ctx.remove(tag);
                } },
            type: "button",
            ...{ class: "opacity-60 hover:opacity-100" },
            'aria-label': (`Remove ${tag}`),
        });
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
    ...{ onKeydown: (__VLS_ctx.onKey) },
    ...{ onBlur: (__VLS_ctx.commitDraft) },
    value: (__VLS_ctx.draft),
    type: "text",
    ...{ class: "flex-1 min-w-[6rem] bg-transparent outline-none text-sm py-0.5" },
    placeholder: (__VLS_ctx.placeholder ?? ''),
    disabled: (__VLS_ctx.disabled || __VLS_ctx.modelValue.length >= __VLS_ctx.maxTags),
    maxlength: (__VLS_ctx.maxTagChars),
});
if (__VLS_ctx.modelValue.length >= __VLS_ctx.maxTags) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-[10px] opacity-60" },
    });
    (__VLS_ctx.modelValue.length);
    (__VLS_ctx.maxTags);
}
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-md']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-[2.25rem]']} */ ;
/** @type {__VLS_StyleScopedClasses['inline-flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:opacity-100']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-[6rem]']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-transparent']} */ ;
/** @type {__VLS_StyleScopedClasses['outline-none']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-[10px]']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            draft: draft,
            commitDraft: commitDraft,
            remove: remove,
            onKey: onKey,
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
//# sourceMappingURL=VTagEditor.vue.js.map