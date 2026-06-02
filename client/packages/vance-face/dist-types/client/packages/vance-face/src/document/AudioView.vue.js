import { computed } from 'vue';
import { documentContentUrl } from '@vance/shared';
const props = withDefaults(defineProps(), { mode: 'embedded' });
const src = computed(() => {
    const doc = props.document;
    if (!doc || !doc.id)
        return '';
    return documentContentUrl(doc.id);
});
const title = computed(() => props.embedRef?.text || props.document?.title || props.document?.path || 'audio');
const mimeType = computed(() => props.document?.mimeType ?? 'audio/mpeg');
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({ mode: 'embedded' });
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "audio-view" },
    ...{ class: (`audio-view--${__VLS_ctx.mode}`) },
});
if (!__VLS_ctx.src) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "audio-view__empty" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "opacity-60" },
    });
    (__VLS_ctx.title);
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "audio-view__title" },
    });
    (__VLS_ctx.title);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.audio, __VLS_intrinsicElements.audio)({
        controls: true,
        preload: "metadata",
        ...{ class: "audio-view__player" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.source)({
        src: (__VLS_ctx.src),
        type: (__VLS_ctx.mimeType),
    });
    if (__VLS_ctx.embedRef?.caption) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "audio-view__caption" },
        });
        (__VLS_ctx.embedRef.caption);
    }
}
/** @type {__VLS_StyleScopedClasses['audio-view']} */ ;
/** @type {__VLS_StyleScopedClasses['audio-view__empty']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['audio-view__title']} */ ;
/** @type {__VLS_StyleScopedClasses['audio-view__player']} */ ;
/** @type {__VLS_StyleScopedClasses['audio-view__caption']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            src: src,
            title: title,
            mimeType: mimeType,
        };
    },
    __typeProps: {},
    props: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeProps: {},
    props: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=AudioView.vue.js.map