import { computed, defineAsyncComponent } from 'vue';
import { documentContentUrl } from '@vance/shared';
// Office previews — async so the mammoth / xlsx bundles stay out
// of the initial document-app chunk; they only load when the user
// opens a DOCX/XLSX document.
const DocxView = defineAsyncComponent(() => import('./DocxView.vue'));
const XlsxView = defineAsyncComponent(() => import('./XlsxView.vue'));
const DOCX_MIME = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
const XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
const props = defineProps();
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
    __VLS_asFunctionalElement(__VLS_intrinsicElements.iframe)({
        src: (__VLS_ctx.streamUrl),
        title: (__VLS_ctx.mimeType ?? 'PDF'),
        ...{ class: "document-preview__pdf" },
    });
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
/** @type {__VLS_StyleScopedClasses['document-preview__pdf']} */ ;
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
        };
    },
    __typeProps: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {
            ...__VLS_exposed,
        };
    },
    __typeProps: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=DocumentPreview.vue.js.map