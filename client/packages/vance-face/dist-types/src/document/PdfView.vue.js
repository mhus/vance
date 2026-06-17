import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { documentContentUrl } from '@vance/shared';
// Polyfill Map.prototype.getOrInsertComputed before pdfjs initialises —
// pdfjs-dist v5 calls it from its message handler and crashes on
// browsers that haven't shipped the TC39 upsert proposal yet.
import '../polyfills/mapGetOrInsert';
import * as pdfjsLib from 'pdfjs-dist';
const props = withDefaults(defineProps(), { mode: 'embedded' });
import { configurePdfWorker } from './pdfWorkerPort';
configurePdfWorker(pdfjsLib.GlobalWorkerOptions);
const canvasRef = ref(null);
const pageCount = ref(0);
const currentPage = ref(1);
const loadError = ref(null);
const loading = ref(false);
const lightbox = ref(false);
let pdfDoc = null;
const url = computed(() => {
    const doc = props.document;
    if (!doc || !doc.id)
        return '';
    return documentContentUrl(doc.id);
});
/** True when a canvas is currently mounted — drives load-on-demand. */
const canvasMounted = computed(() => props.mode === 'editor' || lightbox.value);
async function loadPdf() {
    if (!url.value)
        return;
    loading.value = true;
    loadError.value = null;
    try {
        const task = pdfjsLib.getDocument({
            url: url.value,
            // Cookie auth (Web) — let the same-origin request carry it.
            withCredentials: true,
        });
        pdfDoc = await task.promise;
        pageCount.value = pdfDoc.numPages;
        currentPage.value = 1;
        await renderPage(currentPage.value);
    }
    catch (e) {
        loadError.value = e.message || 'Failed to load PDF';
    }
    finally {
        loading.value = false;
    }
}
async function renderPage(num) {
    if (!pdfDoc || !canvasRef.value)
        return;
    const page = await pdfDoc.getPage(num);
    const baseWidth = canvasRef.value.parentElement?.clientWidth ?? 600;
    const viewport = page.getViewport({ scale: 1 });
    const scale = baseWidth / viewport.width;
    const scaled = page.getViewport({ scale: Math.min(scale, 2) });
    const canvas = canvasRef.value;
    const ctx = canvas.getContext('2d');
    if (!ctx)
        return;
    canvas.width = scaled.width;
    canvas.height = scaled.height;
    await page.render({ canvasContext: ctx, viewport: scaled, canvas }).promise;
}
function prev() {
    if (currentPage.value > 1) {
        currentPage.value -= 1;
        void renderPage(currentPage.value);
    }
}
function next() {
    if (currentPage.value < pageCount.value) {
        currentPage.value += 1;
        void renderPage(currentPage.value);
    }
}
function openLightbox() {
    lightbox.value = true;
}
function closeLightbox() {
    lightbox.value = false;
}
function disposeDoc() {
    void pdfDoc?.destroy();
    pdfDoc = null;
    pageCount.value = 0;
    currentPage.value = 1;
    loadError.value = null;
}
// Load on mount only for editor mode; embedded waits for the lightbox.
// {@link canvasRef} is bound during the same flush so by the time
// {@link loadPdf} reaches {@link renderPage} the canvas is alive.
onMounted(() => {
    if (canvasMounted.value)
        void loadPdf();
});
// Re-load when the doc URL changes (editor switches to a different
// PDF tab) or when the lightbox toggles. Closing the lightbox tears
// down the loaded doc — frees the worker-side state plus any large
// page buffers; the next open re-fetches.
watch(() => url.value, () => {
    if (canvasMounted.value)
        void loadPdf();
});
watch(canvasMounted, (open) => {
    if (open)
        void loadPdf();
    else
        disposeDoc();
});
onBeforeUnmount(() => disposeDoc());
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({ mode: 'embedded' });
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['pdf-view__canvas']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__nav-btn']} */ ;
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
    if (__VLS_ctx.loadError) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "pdf-view__error" },
        });
        (__VLS_ctx.loadError);
    }
    else if (__VLS_ctx.loading && __VLS_ctx.pageCount === 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "pdf-view__loading" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "opacity-70" },
        });
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "pdf-view__canvas-wrap" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.canvas)({
            ref: "canvasRef",
            ...{ class: "pdf-view__canvas" },
        });
        /** @type {typeof __VLS_ctx.canvasRef} */ ;
    }
    if (__VLS_ctx.pageCount > 1) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "pdf-view__nav" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (__VLS_ctx.prev) },
            ...{ class: "pdf-view__nav-btn" },
            disabled: (__VLS_ctx.currentPage <= 1),
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "pdf-view__nav-label" },
        });
        (__VLS_ctx.currentPage);
        (__VLS_ctx.pageCount);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (__VLS_ctx.next) },
            ...{ class: "pdf-view__nav-btn" },
            disabled: (__VLS_ctx.currentPage >= __VLS_ctx.pageCount),
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
    if (__VLS_ctx.loadError) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "pdf-view__error" },
        });
        (__VLS_ctx.loadError);
    }
    else if (__VLS_ctx.loading && __VLS_ctx.pageCount === 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "pdf-view__loading" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "opacity-70" },
        });
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "pdf-view__canvas-wrap pdf-view__canvas-wrap--lightbox" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.canvas)({
            ref: "canvasRef",
            ...{ class: "pdf-view__canvas pdf-view__canvas--lightbox" },
        });
        /** @type {typeof __VLS_ctx.canvasRef} */ ;
    }
    if (__VLS_ctx.pageCount > 1) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "pdf-view__nav pdf-view__nav--lightbox" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (__VLS_ctx.prev) },
            ...{ class: "pdf-view__nav-btn" },
            disabled: (__VLS_ctx.currentPage <= 1),
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "pdf-view__nav-label" },
        });
        (__VLS_ctx.currentPage);
        (__VLS_ctx.pageCount);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (__VLS_ctx.next) },
            ...{ class: "pdf-view__nav-btn" },
            disabled: (__VLS_ctx.currentPage >= __VLS_ctx.pageCount),
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
/** @type {__VLS_StyleScopedClasses['pdf-view__error']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__loading']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__canvas-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__canvas']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__nav']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__nav-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__nav-label']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__nav-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__caption']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__lightbox']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__lightbox-close']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__lightbox-inner']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__error']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__loading']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__canvas-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__canvas-wrap--lightbox']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__canvas']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__canvas--lightbox']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__nav']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__nav--lightbox']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__nav-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__nav-label']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__nav-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__caption']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            canvasRef: canvasRef,
            pageCount: pageCount,
            currentPage: currentPage,
            loadError: loadError,
            loading: loading,
            lightbox: lightbox,
            prev: prev,
            next: next,
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