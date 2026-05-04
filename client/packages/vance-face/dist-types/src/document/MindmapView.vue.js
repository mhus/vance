import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { Transformer } from 'markmap-lib';
import { Markmap } from 'markmap-view';
import { treeToMarkmapMarkdown } from './mindmapAdapter';
/**
 * Read-only mindmap renderer for `kind: mindmap` documents. Reuses
 * the parsed {@link TreeDocument} from the same codec the tree
 * editor uses; we just project it into markmap-flavoured markdown
 * and hand it to {@code markmap-view}.
 *
 * Edit happens in the sibling {@code <TreeView>} tab — the spec
 * (`specification/doc-kind-mindmap.md` §5) keeps the v1 mindmap as
 * a viewer to ship the renderer without the in-place-editing
 * complexity that {@code mind-elixir} would unlock later.
 */
defineOptions({ name: 'MindmapView' });
const props = defineProps();
const { t } = useI18n();
const svgRef = ref(null);
let markmap = null;
const transformer = new Transformer();
function render() {
    if (!svgRef.value)
        return;
    const md = treeToMarkmapMarkdown(props.doc);
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
// Re-render when the parent re-emits the doc (e.g. raw-tab edits).
// `deep: true` so per-item text/extra changes propagate even when
// the outer object identity stays the same.
watch(() => props.doc, () => render(), { deep: true });
onBeforeUnmount(() => {
    if (markmap) {
        markmap.destroy();
        markmap = null;
    }
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "mindmap-view" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.svg)({
    ref: "svgRef",
    ...{ class: "mindmap-svg" },
});
/** @type {typeof __VLS_ctx.svgRef} */ ;
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
    ...{ class: "mindmap-hint" },
});
(__VLS_ctx.t('documents.mindmapView.panZoomHint'));
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
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeProps: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=MindmapView.vue.js.map