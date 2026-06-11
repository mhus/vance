import { computed, onBeforeUnmount, ref } from 'vue';
import { CodeEditor } from '@/components';
import { documentContentUrl } from '@vance/shared';
import ImageView from '@/document/ImageView.vue';
import { useCortexStore } from '../stores/cortexStore';
import { resolveBinding } from '../docTypeRegistry';
const props = defineProps();
const emit = defineEmits();
const store = useCortexStore();
const binding = computed(() => resolveBinding(props.document));
const reloading = ref(false);
async function onReload() {
    if (reloading.value)
        return;
    if (props.document.dirty) {
        const ok = window.confirm(`Discard unsaved changes in ${props.document.path}?`);
        if (!ok)
            return;
    }
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
// Derive a language hint from the document's mime-type, falling back
// to the path extension when the server didn't store one. CodeEditor's
// own languageFor mapping handles common mime-types directly, but
// extension-derived mime-types give nicer defaults for files the server
// left as null (e.g. uploaded snippets, markdown notes created via API
// without explicit mime).
const effectiveMimeType = computed(() => {
    const explicit = props.document.mimeType;
    if (explicit)
        return explicit;
    const lower = props.document.path.toLowerCase();
    if (lower.endsWith('.md'))
        return 'text/markdown';
    if (lower.endsWith('.json'))
        return 'application/json';
    if (lower.endsWith('.yaml') || lower.endsWith('.yml'))
        return 'application/yaml';
    if (lower.endsWith('.js') || lower.endsWith('.mjs'))
        return 'text/javascript';
    if (lower.endsWith('.ts'))
        return 'text/typescript';
    if (lower.endsWith('.py'))
        return 'text/x-python';
    if (lower.endsWith('.sh') || lower.endsWith('.bash'))
        return 'text/x-shellscript';
    return 'text/plain';
});
function onSelectionChanged(sel) {
    if (sel.from === sel.to || !sel.text) {
        store.clearSelection();
        return;
    }
    store.setSelection({
        docId: props.document.id,
        docPath: props.document.path,
        from: sel.from,
        to: sel.to,
        text: sel.text,
    });
}
onBeforeUnmount(() => {
    store.clearSelection();
});
// ImageView consumes a DocumentDto; CortexDocument is a trimmed
// in-memory shape. Build a partial DTO with the fields ImageView
// actually reads (id, projectId, path, mimeType, inline, inlineText,
// title). Required-but-unused DTO fields get inert defaults — they
// don't affect rendering.
const docDtoForImage = computed(() => ({
    id: props.document.id,
    projectId: store.projectId ?? '',
    path: props.document.path,
    name: props.document.name,
    title: props.document.title ?? undefined,
    mimeType: props.document.mimeType ?? undefined,
    size: 0,
    tags: [],
    inline: !!props.document.inlineText,
    inlineText: props.document.inlineText || undefined,
    headers: {},
    autoSummary: false,
    summaryDirty: false,
}));
// Reference the import so an unused-symbol lint doesn't strip it; SVG
// rendering currently goes through ImageView's own documentContentUrl
// call but we may switch to a blob-URL path here once typed-model
// images land.
void documentContentUrl;
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
    title: (__VLS_ctx.document.dirty ? 'Reload (discards unsaved changes)' : 'Reload from server'),
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
if (__VLS_ctx.document.dirty && __VLS_ctx.binding.editLocation === 'client-memory') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "opacity-60" },
    });
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
    ...{ class: "flex-1" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "opacity-50 text-xs font-mono" },
});
(__VLS_ctx.binding.mode === 'code' ? __VLS_ctx.effectiveMimeType : (__VLS_ctx.document.mimeType ?? 'image'));
if (__VLS_ctx.binding.mode === 'code') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 min-h-0 overflow-hidden" },
    });
    const __VLS_0 = {}.CodeEditor;
    /** @type {[typeof __VLS_components.CodeEditor, ]} */ ;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
        ...{ 'onUpdate:modelValue': {} },
        ...{ 'onSelectionChanged': {} },
        modelValue: (__VLS_ctx.document.inlineText),
        mimeType: (__VLS_ctx.effectiveMimeType),
    }));
    const __VLS_2 = __VLS_1({
        ...{ 'onUpdate:modelValue': {} },
        ...{ 'onSelectionChanged': {} },
        modelValue: (__VLS_ctx.document.inlineText),
        mimeType: (__VLS_ctx.effectiveMimeType),
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    let __VLS_4;
    let __VLS_5;
    let __VLS_6;
    const __VLS_7 = {
        'onUpdate:modelValue': ((v) => __VLS_ctx.emit('update', v))
    };
    const __VLS_8 = {
        onSelectionChanged: (__VLS_ctx.onSelectionChanged)
    };
    var __VLS_3;
}
else if (__VLS_ctx.binding.mode === 'image') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 min-h-0 overflow-auto bg-base-200/40 flex items-start justify-center p-4" },
    });
    /** @type {[typeof ImageView, ]} */ ;
    // @ts-ignore
    const __VLS_9 = __VLS_asFunctionalComponent(ImageView, new ImageView({
        mode: "editor",
        document: (__VLS_ctx.docDtoForImage),
    }));
    const __VLS_10 = __VLS_9({
        mode: "editor",
        document: (__VLS_ctx.docDtoForImage),
    }, ...__VLS_functionalComponentArgsRest(__VLS_9));
}
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
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200/40']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-start']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            CodeEditor: CodeEditor,
            ImageView: ImageView,
            emit: emit,
            binding: binding,
            reloading: reloading,
            onReload: onReload,
            propertiesUrl: propertiesUrl,
            effectiveMimeType: effectiveMimeType,
            onSelectionChanged: onSelectionChanged,
            docDtoForImage: docDtoForImage,
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
//# sourceMappingURL=DocumentTabShell.vue.js.map