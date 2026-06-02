import { computed, ref } from 'vue';
import { documentContentUrl } from '@vance/shared';
const props = withDefaults(defineProps(), { mode: 'embedded' });
const lightbox = ref(false);
const src = computed(() => {
    const doc = props.document;
    if (!doc)
        return '';
    // Inline svg: render the text directly via blob URL so DOMPurify
    // catches XSS payloads. Future hardening — for now, only load
    // images we got from the trusted brain endpoint.
    if (doc.inline && (doc.mimeType ?? '').startsWith('image/svg')) {
        if (!doc.inlineText)
            return '';
        const blob = new Blob([doc.inlineText], { type: doc.mimeType ?? 'image/svg+xml' });
        return URL.createObjectURL(blob);
    }
    if (!doc.id)
        return '';
    return documentContentUrl(doc.id);
});
const alt = computed(() => props.embedRef?.text || props.document?.title || props.document?.path || 'image');
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
/** @type {__VLS_StyleScopedClasses['image-view__img']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "image-view" },
    ...{ class: (`image-view--${__VLS_ctx.mode}`) },
});
if (!__VLS_ctx.src) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "image-view__empty" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "opacity-60" },
    });
    (__VLS_ctx.alt);
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.img)({
        ...{ onClick: (__VLS_ctx.openLightbox) },
        src: (__VLS_ctx.src),
        alt: (__VLS_ctx.alt),
        ...{ class: "image-view__img" },
    });
}
if (__VLS_ctx.embedRef?.caption) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "image-view__caption" },
    });
    (__VLS_ctx.embedRef.caption);
}
if (__VLS_ctx.lightbox) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ onClick: (__VLS_ctx.closeLightbox) },
        ...{ onKeydown: (__VLS_ctx.closeLightbox) },
        ...{ class: "image-view__lightbox" },
        role: "dialog",
        tabindex: "-1",
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.img)({
        src: (__VLS_ctx.src),
        alt: (__VLS_ctx.alt),
        ...{ class: "image-view__lightbox-img" },
    });
}
/** @type {__VLS_StyleScopedClasses['image-view']} */ ;
/** @type {__VLS_StyleScopedClasses['image-view__empty']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['image-view__img']} */ ;
/** @type {__VLS_StyleScopedClasses['image-view__caption']} */ ;
/** @type {__VLS_StyleScopedClasses['image-view__lightbox']} */ ;
/** @type {__VLS_StyleScopedClasses['image-view__lightbox-img']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            lightbox: lightbox,
            src: src,
            alt: alt,
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
//# sourceMappingURL=ImageView.vue.js.map