import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { documentContentUrl } from '@vance/shared';
import * as pdfjsLib from 'pdfjs-dist';
// PDF.js v5 uses an ESM worker that Vite can bundle as a URL via the
// `?url` import. We point pdfjs at it once on first mount — same worker
// instance is reused across renders.
// eslint-disable-next-line @typescript-eslint/ban-ts-comment
// @ts-ignore — Vite asset import resolves at build time.
import pdfWorkerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
// Helper used by the v-for ref to attach the canvas the PDF.js
// rendered into. Defining it in a module-level <script> block
// keeps it out of the reactive setup state.
function attachCanvas(host, canvas) {
    if (!host)
        return;
    // Replace any prior canvas (re-renders) so the DOM stays clean.
    while (host.firstChild)
        host.removeChild(host.firstChild);
    host.appendChild(canvas);
}
export { attachCanvas };
debugger; /* PartiallyEnd: #3632/both.vue */
export default await (async () => {
    const props = defineProps();
    pdfjsLib
        .GlobalWorkerOptions.workerSrc = pdfWorkerUrl;
    const streamUrl = computed(() => props.documentId ? documentContentUrl(props.documentId, false) : '');
    const kind = computed(() => {
        if (props.inline)
            return 'inline';
        const mt = (props.mimeType ?? '').toLowerCase();
        if (mt === 'application/pdf')
            return 'pdf';
        if (mt.startsWith('image/'))
            return 'image';
        return 'binary';
    });
    // PDF state — pages render to an array of canvases, drawn lazily
    // when the kind switches to `pdf` or the docId changes.
    const pdfPages = ref([]);
    const pdfError = ref(null);
    const pdfLoading = ref(false);
    let activePdfTask = null;
    async function renderPdf(url) {
        pdfError.value = null;
        pdfPages.value = [];
        pdfLoading.value = true;
        try {
            const loadingTask = pdfjsLib.getDocument(url);
            activePdfTask = loadingTask;
            const doc = await loadingTask.promise;
            const canvases = [];
            for (let i = 1; i <= doc.numPages; i++) {
                const page = await doc.getPage(i);
                // Render at devicePixelRatio for sharp output. Cap scale so a
                // huge PDF doesn't blow the canvas size out.
                const baseViewport = page.getViewport({ scale: 1 });
                const targetWidth = Math.min(baseViewport.width, 900);
                const scale = (targetWidth / baseViewport.width) * (window.devicePixelRatio || 1);
                const viewport = page.getViewport({ scale });
                const canvas = document.createElement('canvas');
                canvas.width = Math.floor(viewport.width);
                canvas.height = Math.floor(viewport.height);
                canvas.style.width = `${Math.floor(viewport.width / (window.devicePixelRatio || 1))}px`;
                canvas.style.height = `${Math.floor(viewport.height / (window.devicePixelRatio || 1))}px`;
                const ctx = canvas.getContext('2d');
                if (!ctx)
                    continue;
                // pdfjs v5 expects { canvasContext, viewport, canvas }.
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                await page.render({ canvasContext: ctx, viewport, canvas }).promise;
                canvases.push(canvas);
            }
            pdfPages.value = canvases;
        }
        catch (e) {
            pdfError.value = e instanceof Error ? e.message : String(e);
        }
        finally {
            pdfLoading.value = false;
        }
    }
    async function destroyActivePdf() {
        if (activePdfTask) {
            try {
                await activePdfTask.destroy();
            }
            catch {
                // best-effort cleanup
            }
            activePdfTask = null;
        }
    }
    watch(() => [kind.value, streamUrl.value], async ([k, url]) => {
        await destroyActivePdf();
        if (k === 'pdf' && url) {
            void renderPdf(url);
        }
        else {
            pdfPages.value = [];
            pdfError.value = null;
        }
    }, { immediate: true });
    onBeforeUnmount(() => {
        void destroyActivePdf();
    });
    const downloadUrl = computed(() => props.documentId ? documentContentUrl(props.documentId, true) : '');
    const __VLS_exposed = { downloadUrl };
    defineExpose(__VLS_exposed);
    debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
    const __VLS_ctx = {};
    let __VLS_components;
    let __VLS_directives;
    // CSS variable injection 
    // CSS variable injection end 
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "document-preview" },
    });
    if (__VLS_ctx.kind === 'inline') {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div)({});
    }
    else if (__VLS_ctx.kind === 'image') {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex justify-center" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.img)({
            src: (__VLS_ctx.streamUrl),
            alt: (__VLS_ctx.mimeType ?? 'image'),
            ...{ class: "document-preview__image" },
            loading: "lazy",
        });
    }
    else if (__VLS_ctx.kind === 'pdf') {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex flex-col items-center gap-3" },
        });
        if (__VLS_ctx.pdfLoading) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-sm opacity-70" },
            });
        }
        if (__VLS_ctx.pdfError) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-sm text-error" },
            });
            (__VLS_ctx.pdfError);
        }
        for (const [canvas, idx] of __VLS_getVForSourceType((__VLS_ctx.pdfPages))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div)({
                key: (idx),
                ...{ class: "document-preview__pdf-page" },
                ref: ((el) => __VLS_ctx.attachCanvas(el, canvas)),
            });
        }
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-sm opacity-70 italic" },
        });
    }
    /** @type {__VLS_StyleScopedClasses['document-preview']} */ ;
    /** @type {__VLS_StyleScopedClasses['flex']} */ ;
    /** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
    /** @type {__VLS_StyleScopedClasses['document-preview__image']} */ ;
    /** @type {__VLS_StyleScopedClasses['flex']} */ ;
    /** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
    /** @type {__VLS_StyleScopedClasses['items-center']} */ ;
    /** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
    /** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-error']} */ ;
    /** @type {__VLS_StyleScopedClasses['document-preview__pdf-page']} */ ;
    /** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
    /** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
    /** @type {__VLS_StyleScopedClasses['italic']} */ ;
    var __VLS_dollars;
    const __VLS_self = (await import('vue')).defineComponent({
        setup() {
            return {
                streamUrl: streamUrl,
                kind: kind,
                pdfPages: pdfPages,
                pdfError: pdfError,
                pdfLoading: pdfLoading,
                attachCanvas: attachCanvas,
            };
        },
        __typeProps: {},
    });
    return (await import('vue')).defineComponent({
        setup() {
            return {
                ...__VLS_exposed,
            };
        },
        __typeProps: {},
    });
})(); /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=DocumentPreview.vue.js.map