import { computed, ref } from 'vue';
import { documentContentUrl } from '@vance/shared';
import { useCortexStore } from '../stores/cortexStore';
const props = defineProps();
const __VLS_emit = defineEmits();
const store = useCortexStore();
const reloading = ref(false);
async function onReload() {
    if (reloading.value)
        return;
    reloading.value = true;
    try {
        await store.reloadTab(props.document.id);
    }
    catch (e) {
        console.warn('Failed to reload document', e);
    }
    finally {
        reloading.value = false;
    }
}
// External-edit deep-link — full metadata editing lives in
// documents.html; cortex just jumps there in a new tab so the user can
// edit title/path/MIME/tags/RAG-mode/etc. without us reimplementing
// the entire properties panel here.
const propertiesUrl = computed(() => {
    const pid = store.projectId;
    if (!pid)
        return null;
    const params = new URLSearchParams({
        projectId: pid,
        documentId: props.document.id,
    });
    return `/documents.html?${params.toString()}`;
});
/**
 * Brain-hosted image URL. The server enforces auth on the
 * {@code documents/{id}/content} endpoint, so we can drop the id
 * straight into an {@code <img>} src without leaking bytes through
 * the JS heap.
 *
 * <p>Inline SVG (where {@code inlineText} carries the markup) renders
 * via a Blob URL so DOMPurify or future CSP rules can intercept; today
 * we trust brain output verbatim. Same posture as
 * {@code document/ImageView.vue} which we deliberately don't share
 * because Cortex's prop surface is its own.
 */
const src = computed(() => {
    const doc = props.document;
    if ((doc.mimeType ?? '').startsWith('image/svg') && doc.inlineText) {
        const blob = new Blob([doc.inlineText], { type: doc.mimeType ?? 'image/svg+xml' });
        return URL.createObjectURL(blob);
    }
    return documentContentUrl(doc.id);
});
const alt = computed(() => props.document.title || props.document.path || 'image');
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "h-full flex flex-col min-h-0" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex items-center gap-2 px-3 py-2 border-b border-base-300 bg-base-100 text-sm" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (__VLS_ctx.onReload) },
    type: "button",
    ...{ class: "\u006f\u0070\u0061\u0063\u0069\u0074\u0079\u002d\u0036\u0030\u0020\u0065\u006e\u0061\u0062\u006c\u0065\u0064\u003a\u0068\u006f\u0076\u0065\u0072\u003a\u006f\u0070\u0061\u0063\u0069\u0074\u0079\u002d\u0031\u0030\u0030\u0020\u0065\u006e\u0061\u0062\u006c\u0065\u0064\u003a\u0068\u006f\u0076\u0065\u0072\u003a\u0062\u0067\u002d\u0062\u0061\u0073\u0065\u002d\u0032\u0030\u0030\u0020\u0064\u0069\u0073\u0061\u0062\u006c\u0065\u0064\u003a\u0063\u0075\u0072\u0073\u006f\u0072\u002d\u0064\u0065\u0066\u0061\u0075\u006c\u0074\u000a\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0020\u0072\u006f\u0075\u006e\u0064\u0065\u0064\u0020\u0070\u0078\u002d\u0031\u0020\u006c\u0065\u0061\u0064\u0069\u006e\u0067\u002d\u006e\u006f\u006e\u0065" },
    disabled: (__VLS_ctx.reloading),
    title: "Reload from server",
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: (__VLS_ctx.reloading ? 'animate-spin inline-block' : '') },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "font-mono opacity-80 truncate" },
});
(__VLS_ctx.document.path);
if (__VLS_ctx.propertiesUrl) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
        href: (__VLS_ctx.propertiesUrl),
        target: "_blank",
        rel: "noopener",
        ...{ class: "opacity-60 hover:opacity-100 hover:bg-base-200 rounded px-1 leading-none" },
        title: "Open document properties in a new tab",
    });
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
    ...{ class: "flex-1" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "opacity-50 text-xs font-mono" },
});
(__VLS_ctx.document.mimeType ?? 'image');
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex-1 min-h-0 overflow-auto bg-base-200/40 flex items-start justify-center p-4" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.img)({
    src: (__VLS_ctx.src),
    alt: (__VLS_ctx.alt),
    ...{ class: "max-w-full h-auto rounded shadow-sm" },
    ...{ style: {} },
});
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['enabled:hover:opacity-100']} */ ;
/** @type {__VLS_StyleScopedClasses['enabled:hover:bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['disabled:cursor-default']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['leading-none']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:opacity-100']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['leading-none']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200/40']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-start']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['h-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['shadow-sm']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            reloading: reloading,
            onReload: onReload,
            propertiesUrl: propertiesUrl,
            src: src,
            alt: alt,
        };
    },
    __typeEmits: {},
    __typeProps: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeEmits: {},
    __typeProps: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=ImageTabRenderer.vue.js.map