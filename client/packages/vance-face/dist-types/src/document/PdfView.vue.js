import { computed, ref } from 'vue';
import { documentContentUrl } from '@vance/shared';
const props = withDefaults(defineProps(), { mode: 'embedded' });
const lightbox = ref(false);
const url = computed(() => {
    const doc = props.document;
    if (!doc || !doc.id)
        return '';
    return documentContentUrl(doc.id);
});
function openLightbox() {
    lightbox.value = true;
}
function closeLightbox() {
    lightbox.value = false;
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({ mode: 'embedded' });
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['pdf-view__open-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__open-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__lightbox-close']} */ ;
// CSS variable injection 
// CSS variable injection end 
if (__VLS_ctx.mode === 'embedded') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "pdf-view pdf-view--card" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.openLightbox) },
        type: "button",
        ...{ class: "pdf-view__open-btn" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "pdf-view__open-icon" },
        'aria-hidden': "true",
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    if (__VLS_ctx.embedRef?.caption) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "pdf-view__caption" },
        });
        (__VLS_ctx.embedRef.caption);
    }
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "pdf-view pdf-view--editor" },
    });
    if (__VLS_ctx.url) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.iframe)({
            src: (__VLS_ctx.url),
            title: "PDF",
            ...{ class: "pdf-view__frame" },
        });
    }
    if (__VLS_ctx.embedRef?.caption) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "pdf-view__caption" },
        });
        (__VLS_ctx.embedRef.caption);
    }
}
const __VLS_0 = {}.Teleport;
/** @type {[typeof __VLS_components.Teleport, typeof __VLS_components.Teleport, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    to: "body",
}));
const __VLS_2 = __VLS_1({
    to: "body",
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
__VLS_3.slots.default;
if (__VLS_ctx.lightbox) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ onClick: (__VLS_ctx.closeLightbox) },
        ...{ onKeydown: (__VLS_ctx.closeLightbox) },
        ...{ class: "pdf-view__lightbox" },
        role: "dialog",
        tabindex: "-1",
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.closeLightbox) },
        type: "button",
        ...{ class: "pdf-view__lightbox-close" },
        'aria-label': "Close",
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ onClick: () => { } },
        ...{ class: "pdf-view__lightbox-inner" },
    });
    if (__VLS_ctx.url) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.iframe)({
            src: (__VLS_ctx.url),
            title: "PDF",
            ...{ class: "pdf-view__frame pdf-view__frame--lightbox" },
        });
    }
    if (__VLS_ctx.embedRef?.caption) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "pdf-view__caption" },
        });
        (__VLS_ctx.embedRef.caption);
    }
}
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['pdf-view']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view--card']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__open-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__open-icon']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__caption']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view--editor']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__frame']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__caption']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__lightbox']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__lightbox-close']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__lightbox-inner']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__frame']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__frame--lightbox']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__caption']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            lightbox: lightbox,
            url: url,
            openLightbox: openLightbox,
            closeLightbox: closeLightbox,
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
//# sourceMappingURL=PdfView.vue.js.map