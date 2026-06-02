import { ref } from 'vue';
const props = withDefaults(defineProps(), {
    disabled: false,
    placeholder: '💬',
});
const emit = defineEmits();
const open = ref(false);
const custom = ref('');
// A small curated set of topic emojis. Order roughly: planning, coding,
// design, debug, research, communication, scheduling. Kept small so the
// picker fits inside a card without scrolling.
const EMOJIS = [
    '💡', '📝', '✏️', '📌', '📋', '🧩',
    '💻', '🛠️', '⚙️', '🔧', '🧪', '🚀',
    '🐛', '🩹', '🔍', '🧠', '📊', '📈',
    '🎨', '🖼️', '🧵', '🗂️', '📚', '📦',
    '🤖', '🦄', '🌱', '🔥', '⭐', '✅',
    '⚠️', '❓', '💬', '🗒️', '📢', '🎯',
];
function pick(emoji) {
    if (props.disabled)
        return;
    emit('update:modelValue', emoji);
    open.value = false;
}
function clear() {
    if (props.disabled)
        return;
    emit('update:modelValue', null);
    open.value = false;
}
function applyCustom() {
    if (props.disabled)
        return;
    const trimmed = custom.value.trim();
    if (!trimmed)
        return;
    // Take only the first cluster — guard against an accidental
    // multi-emoji paste. Intl.Segmenter is available in modern browsers.
    let value = trimmed;
    if (typeof Intl !== 'undefined' && typeof Intl.Segmenter === 'function') {
        const segmenter = new Intl.Segmenter(undefined, { granularity: 'grapheme' });
        const first = segmenter.segment(trimmed)[Symbol.iterator]().next();
        if (!first.done)
            value = first.value.segment;
    }
    emit('update:modelValue', value);
    custom.value = '';
    open.value = false;
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    disabled: false,
    placeholder: '💬',
});
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-1" },
});
if (__VLS_ctx.label) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-xs opacity-70" },
    });
    (__VLS_ctx.label);
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "relative inline-block" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (...[$event]) => {
            __VLS_ctx.open = !__VLS_ctx.open;
        } },
    type: "button",
    disabled: (__VLS_ctx.disabled),
    ...{ class: "inline-flex items-center justify-center size-9 rounded-md border border-base-300 bg-base-100 hover:bg-base-200 text-2xl leading-none" },
    ...{ class: (__VLS_ctx.disabled ? 'cursor-not-allowed opacity-60' : 'cursor-pointer') },
    'aria-label': (__VLS_ctx.modelValue ? `Emoji ${__VLS_ctx.modelValue}` : 'Pick emoji'),
});
if (__VLS_ctx.modelValue) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.modelValue);
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "opacity-40" },
    });
    (__VLS_ctx.placeholder);
}
if (__VLS_ctx.open) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "absolute z-30 mt-2 w-72 rounded-md border border-base-300 bg-base-100 shadow-lg p-3 flex flex-col gap-3" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "grid grid-cols-6 gap-1" },
    });
    for (const [emoji] of __VLS_getVForSourceType((__VLS_ctx.EMOJIS))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!(__VLS_ctx.open))
                        return;
                    __VLS_ctx.pick(emoji);
                } },
            key: (emoji),
            type: "button",
            ...{ class: "size-9 rounded hover:bg-base-200 text-xl leading-none" },
            ...{ class: (__VLS_ctx.modelValue === emoji ? 'bg-base-200 ring-2 ring-primary' : '') },
        });
        (emoji);
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center gap-2" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
        ...{ onKeyup: (__VLS_ctx.applyCustom) },
        value: (__VLS_ctx.custom),
        type: "text",
        ...{ class: "input input-sm input-bordered flex-1 text-lg" },
        placeholder: "🎲 …",
        maxlength: "8",
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.applyCustom) },
        type: "button",
        ...{ class: "btn btn-xs" },
        disabled: (!__VLS_ctx.custom.trim()),
    });
    if (__VLS_ctx.modelValue) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (__VLS_ctx.clear) },
            type: "button",
            ...{ class: "btn btn-xs btn-ghost self-start" },
        });
    }
}
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['relative']} */ ;
/** @type {__VLS_StyleScopedClasses['inline-block']} */ ;
/** @type {__VLS_StyleScopedClasses['inline-flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['size-9']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-md']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['text-2xl']} */ ;
/** @type {__VLS_StyleScopedClasses['leading-none']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-40']} */ ;
/** @type {__VLS_StyleScopedClasses['absolute']} */ ;
/** @type {__VLS_StyleScopedClasses['z-30']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['w-72']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-md']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['shadow-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-6']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['size-9']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xl']} */ ;
/** @type {__VLS_StyleScopedClasses['leading-none']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['input']} */ ;
/** @type {__VLS_StyleScopedClasses['input-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['input-bordered']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-ghost']} */ ;
/** @type {__VLS_StyleScopedClasses['self-start']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            open: open,
            custom: custom,
            EMOJIS: EMOJIS,
            pick: pick,
            clear: clear,
            applyCustom: applyCustom,
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
//# sourceMappingURL=VEmojiPicker.vue.js.map