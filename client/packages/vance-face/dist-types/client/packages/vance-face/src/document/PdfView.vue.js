import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { documentContentUrl } from '@vance/shared';
import * as pdfjsLib from 'pdfjs-dist';
// PDF.js v5 worker — Vite bundles it as a URL.
// eslint-disable-next-line @typescript-eslint/ban-ts-comment
// @ts-ignore — Vite asset import resolves at build time.
import pdfWorkerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
const props = withDefaults(defineProps(), { mode: 'embedded' });
pdfjsLib
    .GlobalWorkerOptions.workerSrc = pdfWorkerUrl;
const canvasRef = ref(null);
const pageCount = ref(0);
const currentPage = ref(1);
const loadError = ref(null);
const loading = ref(false);
let pdfDoc = null;
const url = computed(() => {
    const doc = props.document;
    if (!doc || !doc.id)
        return '';
    return documentContentUrl(doc.id);
});
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
onMounted(() => { void loadPdf(); });
watch(() => url.value, () => { void loadPdf(); });
onBeforeUnmount(() => {
    void pdfDoc?.destroy();
    pdfDoc = null;
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({ mode: 'embedded' });
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['pdf-view__canvas']} */ ;
/** @type {__VLS_StyleScopedClasses['pdf-view__nav-btn']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "pdf-view" },
    ...{ class: (`pdf-view--${__VLS_ctx.mode}`) },
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
/** @type {__VLS_StyleScopedClasses['pdf-view']} */ ;
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
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            canvasRef: canvasRef,
            pageCount: pageCount,
            currentPage: currentPage,
            loadError: loadError,
            loading: loading,
            prev: prev,
            next: next,
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