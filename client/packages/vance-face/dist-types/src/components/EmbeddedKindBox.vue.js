import { computed, onMounted, ref } from 'vue';
import KindBox from './KindBox.vue';
import { kindIcon, kindLabel, resolveRenderer } from '@/kindRenderers/registry';
import { useDocumentRefStore } from '@/document/documentRefStore';
const props = defineProps();
const store = useDocumentRefStore();
const doc = ref(null);
const loadError = ref(null);
const effectiveKind = computed(() => {
    // Loaded doc kind wins over hint (§3.3 conflict-resolution).
    return (doc.value?.kind ?? props.embedRef.kindHint ?? 'document').toLowerCase();
});
const renderer = computed(() => resolveRenderer(effectiveKind.value, 'embedded'));
const label = computed(() => kindLabel(effectiveKind.value));
const icon = computed(() => kindIcon(effectiveKind.value));
const title = computed(() => doc.value?.title ?? props.embedRef.text ?? props.embedRef.path);
onMounted(async () => {
    try {
        doc.value = await store.resolve(props.embedRef);
    }
    catch (e) {
        loadError.value = e.message;
    }
});
function onCopy() {
    if (typeof navigator === 'undefined' || !navigator.clipboard)
        return;
    if (doc.value?.inlineText != null) {
        void navigator.clipboard.writeText(doc.value.inlineText);
    }
    else {
        // Fall back to URI when content isn't loaded yet / is binary.
        void navigator.clipboard.writeText(props.embedRef.raw);
    }
}
function onOpen() {
    // Mirror the inline-link path in MarkdownView: deep-link into the
    // documents editor via the resolved document id. Without an id
    // (resolve failed) we can't navigate — keep the user where they are.
    const documentId = doc.value?.id;
    if (!documentId)
        return;
    const projectId = props.embedRef.project ?? doc.value?.projectId;
    if (!projectId)
        return;
    const url = `/documents.html?projectId=${encodeURIComponent(projectId)}`
        + `&documentId=${encodeURIComponent(documentId)}`;
    window.open(url, '_blank', 'noopener');
}
function onDownload() {
    // Without a content-bearing REST endpoint surfaced via the store
    // we fall back to opening the document editor where the user can
    // download from the raw tab.
    onOpen();
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['kbx-act']} */ ;
// CSS variable injection 
// CSS variable injection end 
/** @type {[typeof KindBox, typeof KindBox, ]} */ ;
// @ts-ignore
const __VLS_0 = __VLS_asFunctionalComponent(KindBox, new KindBox({
    kind: (__VLS_ctx.effectiveKind),
    label: (__VLS_ctx.label),
    icon: (__VLS_ctx.icon),
    title: (__VLS_ctx.title),
}));
const __VLS_1 = __VLS_0({
    kind: (__VLS_ctx.effectiveKind),
    label: (__VLS_ctx.label),
    icon: (__VLS_ctx.icon),
    title: (__VLS_ctx.title),
}, ...__VLS_functionalComponentArgsRest(__VLS_0));
var __VLS_3 = {};
__VLS_2.slots.default;
{
    const { actions: __VLS_thisSlot } = __VLS_2.slots;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.onCopy) },
        ...{ class: "kbx-act" },
        title: (__VLS_ctx.$t?.('chat.kindBox.copy') ?? 'Copy'),
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.onOpen) },
        ...{ class: "kbx-act" },
        title: (__VLS_ctx.$t?.('chat.kindBox.open') ?? 'Open'),
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.onDownload) },
        ...{ class: "kbx-act" },
        title: (__VLS_ctx.$t?.('chat.kindBox.download') ?? 'Download'),
    });
}
if (__VLS_ctx.loadError) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "kbx-error" },
    });
    (__VLS_ctx.loadError);
}
else if (!__VLS_ctx.doc) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "kbx-loading" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
        ...{ class: "kbx-skeleton" },
    });
}
else if (__VLS_ctx.renderer) {
    const __VLS_4 = ((__VLS_ctx.renderer.embedded));
    // @ts-ignore
    const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
        mode: "embedded",
        document: (__VLS_ctx.doc),
        embedRef: (__VLS_ctx.embedRef),
    }));
    const __VLS_6 = __VLS_5({
        mode: "embedded",
        document: (__VLS_ctx.doc),
        embedRef: (__VLS_ctx.embedRef),
    }, ...__VLS_functionalComponentArgsRest(__VLS_5));
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "kbx-fallback" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "opacity-70" },
    });
    (__VLS_ctx.embedRef.path);
}
var __VLS_2;
/** @type {__VLS_StyleScopedClasses['kbx-act']} */ ;
/** @type {__VLS_StyleScopedClasses['kbx-act']} */ ;
/** @type {__VLS_StyleScopedClasses['kbx-act']} */ ;
/** @type {__VLS_StyleScopedClasses['kbx-error']} */ ;
/** @type {__VLS_StyleScopedClasses['kbx-loading']} */ ;
/** @type {__VLS_StyleScopedClasses['kbx-skeleton']} */ ;
/** @type {__VLS_StyleScopedClasses['kbx-fallback']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            KindBox: KindBox,
            doc: doc,
            loadError: loadError,
            effectiveKind: effectiveKind,
            renderer: renderer,
            label: label,
            icon: icon,
            title: title,
            onCopy: onCopy,
            onOpen: onOpen,
            onDownload: onDownload,
        };
    },
    __typeProps: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeProps: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=EmbeddedKindBox.vue.js.map