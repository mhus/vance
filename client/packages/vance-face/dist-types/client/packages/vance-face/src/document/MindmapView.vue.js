import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { Transformer } from 'markmap-lib';
import { Markmap } from 'markmap-view';
import { parseTree } from './treeItemsCodec';
import { treeToMarkmapMarkdown } from './mindmapAdapter';
/**
 * Read-only mindmap renderer for `kind: mindmap` documents. Reuses
 * the parsed {@link TreeDocument} from the same codec the tree
 * editor uses; we just project it into markmap-flavoured markdown
 * and hand it to {@code markmap-view}.
 *
 * Three modes (spec §11.2):
 *   - `editor`  — full editor surface, `doc` prop required.
 *   - `inline`  — compact render from a fence body in `content`.
 *   - `embedded`— compact render from a loaded Document in `document`.
 *
 * Edit happens in the sibling {@code <TreeView>} tab — the spec
 * (`specification/doc-kind-mindmap.md` §5) keeps the v1 mindmap as
 * a viewer to ship the renderer without the in-place-editing
 * complexity that {@code mind-elixir} would unlock later.
 */
defineOptions({ name: 'MindmapView' });
const props = withDefaults(defineProps(), {
    mode: 'editor',
    meta: () => ({}),
});
const { t } = useI18n();
const svgRef = ref(null);
let markmap = null;
const transformer = new Transformer();
/**
 * Resolve the {@link TreeDocument} to render across all three modes.
 * Throws inline (returns empty doc with a warning) when prerequisites
 * for the selected mode are missing — we never crash the chat
 * because a fence was malformed.
 */
const resolvedDoc = computed(() => {
    if (props.mode === 'editor') {
        return props.doc ?? emptyDoc();
    }
    if (props.mode === 'inline') {
        try {
            return parseTree(props.content ?? '', 'text/markdown');
        }
        catch (e) {
            console.warn('MindmapView: failed to parse inline content', e);
            return emptyDoc();
        }
    }
    // embedded
    const d = props.document;
    if (!d || !d.inlineText)
        return emptyDoc();
    const mime = d.mimeType ?? 'text/markdown';
    try {
        return parseTree(d.inlineText, mime);
    }
    catch (e) {
        console.warn('MindmapView: failed to parse embedded document', e);
        return emptyDoc();
    }
});
function emptyDoc() {
    return { kind: 'mindmap', items: [], extra: {} };
}
function render() {
    if (!svgRef.value)
        return;
    const md = treeToMarkmapMarkdown(resolvedDoc.value);
    const { root } = transformer.transform(md);
    if (!markmap) {
        markmap = Markmap.create(svgRef.value, undefined, root);
        return;
    }
    void markmap.setData(root);
    void markmap.fit();
}
onMounted(() => {
    render();
});
// Re-render when the source changes (deep across all three modes).
watch(() => [resolvedDoc.value, props.mode], () => render(), { deep: true });
onBeforeUnmount(() => {
    if (markmap) {
        markmap.destroy();
        markmap = null;
    }
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    mode: 'editor',
    meta: () => ({}),
});
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: (['mindmap-view', `mindmap-view--${__VLS_ctx.mode}`]) },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.svg)({
    ref: "svgRef",
    ...{ class: "mindmap-svg" },
});
/** @type {typeof __VLS_ctx.svgRef} */ ;
if (__VLS_ctx.mode === 'editor') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "mindmap-hint" },
    });
    (__VLS_ctx.t('documents.mindmapView.panZoomHint'));
}
/** @type {__VLS_StyleScopedClasses['mindmap-view']} */ ;
/** @type {__VLS_StyleScopedClasses['mindmap-svg']} */ ;
/** @type {__VLS_StyleScopedClasses['mindmap-hint']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            t: t,
            svgRef: svgRef,
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
//# sourceMappingURL=MindmapView.vue.js.map