import { SessionColor } from '@vance/generated';
import { computed } from 'vue';
function colorName(c) {
    return SessionColor[c];
}
const props = withDefaults(defineProps(), {
    allowClear: true,
    disabled: false,
});
const emit = defineEmits();
// 12 colors map to Tailwind hues. Pick a saturation/lightness pair that
// works against both light and dark backgrounds — `-500` is the safe
// middle ground. Background tinting in the chip uses `/15` opacity so
// the picked chip never overpowers the surrounding card.
const SWATCHES = [
    { value: SessionColor.SLATE, bg: 'bg-slate-500', ring: 'ring-slate-500' },
    { value: SessionColor.RED, bg: 'bg-red-500', ring: 'ring-red-500' },
    { value: SessionColor.ORANGE, bg: 'bg-orange-500', ring: 'ring-orange-500' },
    { value: SessionColor.AMBER, bg: 'bg-amber-500', ring: 'ring-amber-500' },
    { value: SessionColor.GREEN, bg: 'bg-green-500', ring: 'ring-green-500' },
    { value: SessionColor.TEAL, bg: 'bg-teal-500', ring: 'ring-teal-500' },
    { value: SessionColor.CYAN, bg: 'bg-cyan-500', ring: 'ring-cyan-500' },
    { value: SessionColor.BLUE, bg: 'bg-blue-500', ring: 'ring-blue-500' },
    { value: SessionColor.INDIGO, bg: 'bg-indigo-500', ring: 'ring-indigo-500' },
    { value: SessionColor.PURPLE, bg: 'bg-purple-500', ring: 'ring-purple-500' },
    { value: SessionColor.PINK, bg: 'bg-pink-500', ring: 'ring-pink-500' },
    { value: SessionColor.ROSE, bg: 'bg-rose-500', ring: 'ring-rose-500' },
];
const current = computed(() => props.modelValue ?? null);
function pick(value) {
    if (props.disabled)
        return;
    emit('update:modelValue', value);
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    allowClear: true,
    disabled: false,
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
    ...{ class: "flex flex-wrap gap-2" },
});
for (const [swatch] of __VLS_getVForSourceType((__VLS_ctx.SWATCHES))) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button)({
        ...{ onClick: (...[$event]) => {
                __VLS_ctx.pick(swatch.value);
            } },
        key: (swatch.value),
        type: "button",
        disabled: (__VLS_ctx.disabled),
        ...{ class: "size-6 rounded-full ring-2 ring-offset-2 ring-offset-base-100 transition-opacity" },
        ...{ class: ([
                swatch.bg,
                swatch.ring,
                __VLS_ctx.current === swatch.value ? 'opacity-100' : 'opacity-60 ring-transparent hover:opacity-100',
                __VLS_ctx.disabled ? 'cursor-not-allowed' : 'cursor-pointer',
            ]) },
        'aria-label': (__VLS_ctx.colorName(swatch.value)),
        'aria-pressed': (__VLS_ctx.current === swatch.value),
    });
}
if (__VLS_ctx.allowClear) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.allowClear))
                    return;
                __VLS_ctx.pick(null);
            } },
        type: "button",
        disabled: (__VLS_ctx.disabled),
        ...{ class: "size-6 rounded-full ring-2 ring-offset-2 ring-offset-base-100 transition-opacity bg-base-200 border border-base-300" },
        ...{ class: ([
                __VLS_ctx.current === null ? 'opacity-100 ring-base-content' : 'opacity-60 ring-transparent hover:opacity-100',
                __VLS_ctx.disabled ? 'cursor-not-allowed' : 'cursor-pointer',
            ]) },
        'aria-label': "no-color",
        'aria-pressed': (__VLS_ctx.current === null),
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-xs opacity-70" },
    });
}
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['size-6']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
/** @type {__VLS_StyleScopedClasses['ring-2']} */ ;
/** @type {__VLS_StyleScopedClasses['ring-offset-2']} */ ;
/** @type {__VLS_StyleScopedClasses['ring-offset-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-opacity']} */ ;
/** @type {__VLS_StyleScopedClasses['size-6']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
/** @type {__VLS_StyleScopedClasses['ring-2']} */ ;
/** @type {__VLS_StyleScopedClasses['ring-offset-2']} */ ;
/** @type {__VLS_StyleScopedClasses['ring-offset-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-opacity']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            colorName: colorName,
            SWATCHES: SWATCHES,
            current: current,
            pick: pick,
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
//# sourceMappingURL=VColorPicker.vue.js.map