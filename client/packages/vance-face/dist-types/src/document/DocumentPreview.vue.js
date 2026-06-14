import { computed, defineAsyncComponent, onBeforeUnmount, ref, watch } from 'vue';
import { documentContentUrl } from '@vance/shared';
// Polyfill Map.prototype.getOrInsertComputed before pdfjs initialises —
// pdfjs-dist v5 calls it from its message handler and crashes on
// browsers that haven't shipped the TC39 upsert proposal yet.
import '../polyfills/mapGetOrInsert';
import * as pdfjsLib from 'pdfjs-dist';
import { configurePdfWorker } from './pdfWorkerPort';
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
    // Office previews — async so the mammoth / xlsx bundles stay out
    // of the initial document-app chunk; they only load when the user
    // opens a DOCX/XLSX document.
    const DocxView = defineAsyncComponent(() => import('./DocxView.vue'));
    const XlsxView = defineAsyncComponent(() => import('./XlsxView.vue'));
    const DOCX_MIME = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
    const XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
    const props = defineProps();
    configurePdfWorker(pdfjsLib.GlobalWorkerOptions);
    const streamUrl = computed(() => props.documentId ? documentContentUrl(props.documentId, false) : '');
    const kind = computed(() => {
        if (props.inline)
            return 'inline';
        const mt = (props.mimeType ?? '').toLowerCase();
        if (mt === 'application/pdf')
            return 'pdf';
        if (mt === DOCX_MIME)
            return 'docx';
        if (mt === XLSX_MIME)
            return 'xlsx';
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
            (__VLS_ctx.$t('documents.preview.pdfRendering'));
        }
        if (__VLS_ctx.pdfError) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-sm text-error" },
            });
            (__VLS_ctx.$t('documents.preview.pdfError', { error: __VLS_ctx.pdfError }));
        }
        for (const [canvas, idx] of __VLS_getVForSourceType((__VLS_ctx.pdfPages))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div)({
                key: (idx),
                ...{ class: "document-preview__pdf-page" },
                ref: ((el) => __VLS_ctx.attachCanvas(el, canvas)),
            });
        }
    }
    else if (__VLS_ctx.kind === 'docx') {
        const __VLS_0 = {}.DocxView;
        /** @type {[typeof __VLS_components.DocxView, ]} */ ;
        // @ts-ignore
        const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
            mode: "editor",
            documentId: (__VLS_ctx.documentId),
        }));
        const __VLS_2 = __VLS_1({
            mode: "editor",
            documentId: (__VLS_ctx.documentId),
        }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    }
    else if (__VLS_ctx.kind === 'xlsx') {
        const __VLS_4 = {}.XlsxView;
        /** @type {[typeof __VLS_components.XlsxView, ]} */ ;
        // @ts-ignore
        const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
            mode: "editor",
            documentId: (__VLS_ctx.documentId),
        }));
        const __VLS_6 = __VLS_5({
            mode: "editor",
            documentId: (__VLS_ctx.documentId),
        }, ...__VLS_functionalComponentArgsRest(__VLS_5));
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-sm opacity-70 italic" },
        });
        (__VLS_ctx.$t('documents.preview.binary'));
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
                DocxView: DocxView,
                XlsxView: XlsxView,
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