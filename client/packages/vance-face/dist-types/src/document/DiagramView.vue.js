import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import mermaid from 'mermaid';
import panzoom from 'panzoom';
import { parseDiagram } from './diagramCodec';
import { VAlert } from '@components/index';
/**
 * Read-only renderer for `kind: diagram` documents. The diagram
 * source is passed verbatim to Mermaid which returns SVG; we mount
 * it into a scoped container. Render errors surface as an alert
 * banner with the original source shown below for debugging.
 *
 * Two modes:
 *   - `editor`   — full editor surface, `doc` prop required.
 *   - `embedded` — compact render from a loaded {@link DocumentDto}.
 *
 * `securityLevel: 'strict'` is non-negotiable: it blocks `<script>`
 * and inline event handlers in the source. LLM-generated diagrams
 * must not be able to execute code via this renderer.
 *
 * Spec: `specification/doc-kind-diagram.md` §5.
 */
defineOptions({ name: 'DiagramView' });
const props = withDefaults(defineProps(), { mode: 'editor' });
const { t } = useI18n();
const stageHost = ref(null);
const rendering = ref(false);
const error = ref(null);
const svg = ref('');
/** Live PanZoom instance attached to the rendered SVG. Recreated
 *  whenever the source SVG changes; disposed on unmount. */
let pzInstance = null;
/** Per-instance render id — Mermaid uses it to namespace generated
 *  SVG ids. Stable across renders so id-suffix-sensitive consumers
 *  (download, deep-links) don't churn. */
const renderId = `vance-diagram-${Math.random().toString(36).slice(2, 10)}`;
const resolvedDoc = computed(() => {
    if (props.mode === 'editor') {
        return props.doc ?? emptyDoc();
    }
    const d = props.document;
    if (!d || !d.inlineText)
        return emptyDoc();
    const mime = d.mimeType ?? 'text/markdown';
    try {
        return parseDiagram(d.inlineText, mime);
    }
    catch (e) {
        console.warn('DiagramView: failed to parse embedded document', e);
        return emptyDoc();
    }
});
function emptyDoc() {
    return {
        kind: 'diagram',
        dialect: 'mermaid',
        diagram: { theme: 'default', look: 'classic', extra: {} },
        source: '',
        extra: {},
    };
}
/** Initialise Mermaid once. Theme/look come from the current document
 *  — we re-initialise on every render so a theme change in the doc
 *  takes effect. Re-init is cheap; it just updates the config object. */
function initMermaid() {
    const h = resolvedDoc.value.diagram;
    mermaid.initialize({
        startOnLoad: false,
        securityLevel: 'strict',
        theme: h.theme,
        look: h.look,
        fontFamily: h.fontFamily,
    });
}
async function render() {
    const doc = resolvedDoc.value;
    const dialect = doc.dialect || 'mermaid';
    // v1 only renders Mermaid. Other dialects fall back to the empty
    // state — the parent UI shows a banner explaining the Raw fallback.
    if (dialect !== 'mermaid') {
        svg.value = '';
        error.value = null;
        return;
    }
    if (!doc.source.trim()) {
        svg.value = '';
        error.value = null;
        return;
    }
    rendering.value = true;
    try {
        initMermaid();
        const out = await mermaid.render(renderId, doc.source);
        svg.value = out.svg;
        error.value = null;
    }
    catch (e) {
        svg.value = '';
        error.value = e instanceof Error ? e.message : String(e);
    }
    finally {
        rendering.value = false;
    }
}
function applyStage() {
    if (stageHost.value)
        stageHost.value.innerHTML = svg.value;
    attachPanZoom();
}
/**
 * Attach a fresh {@link PanZoom} instance to the rendered SVG. Mermaid
 * gives the SVG a {@code max-width: 100%} attribute which the library
 * needs cleared — otherwise the scaled element overshoots its layout
 * box and clips inside the stage. Initial state is identity transform;
 * the {@link fitToContainer} helper re-runs after a tick so the SVG
 * has settled dimensions before we compute the fit ratio.
 */
function attachPanZoom() {
    pzInstance?.dispose();
    pzInstance = null;
    if (!stageHost.value || !svg.value)
        return;
    const svgEl = stageHost.value.querySelector('svg');
    if (!svgEl)
        return;
    // Mermaid hard-codes `style="max-width: ..."` on the root <svg>;
    // panzoom's transform fights it. Strip it so zoom > 1 stays visible.
    svgEl.style.maxWidth = 'none';
    svgEl.style.height = 'auto';
    svgEl.style.transformOrigin = '0 0';
    // Cast: panzoom's TS bindings type the argument as HTMLElement, but
    // it works on SVG / SVGGraphicsElement too — the underlying impl
    // uses generic DOM APIs.
    pzInstance = panzoom(svgEl, {
        maxZoom: 10,
        minZoom: 0.1,
        bounds: false,
        zoomDoubleClickSpeed: 1, // disable double-click zoom (1 = no animation = effectively off)
        smoothScroll: false,
    });
    // Give Vue a tick so the SVG's natural size is measurable before
    // the initial fit-to-container calculation.
    requestAnimationFrame(() => fitToContainer());
}
/** Reset zoom and centre the SVG inside the stage. Used for the
 *  toolbar reset button and the initial render. */
function fitToContainer() {
    if (!pzInstance || !stageHost.value)
        return;
    const svgEl = stageHost.value.querySelector('svg');
    if (!svgEl)
        return;
    const stageRect = stageHost.value.getBoundingClientRect();
    // The SVG's intrinsic width/height comes from Mermaid's render —
    // either explicit attributes or computed from viewBox. Use the
    // bounding rect of the SVG element itself as the source of truth.
    const svgRect = svgEl.getBoundingClientRect();
    if (svgRect.width === 0 || svgRect.height === 0)
        return;
    // Fit to 95% of the stage so there's a small margin around the edges.
    const zoomX = (stageRect.width * 0.95) / svgRect.width;
    const zoomY = (stageRect.height * 0.95) / svgRect.height;
    const zoom = Math.min(zoomX, zoomY, 1); // never auto-upscale beyond 1
    pzInstance.zoomAbs(0, 0, zoom);
    const offsetX = (stageRect.width - svgRect.width * zoom) / 2;
    const offsetY = (stageRect.height - svgRect.height * zoom) / 2;
    pzInstance.moveTo(offsetX, offsetY);
}
function zoomBy(factor) {
    if (!pzInstance || !stageHost.value)
        return;
    const rect = stageHost.value.getBoundingClientRect();
    // Anchor the zoom on the stage centre — most natural for keyboard /
    // toolbar zoom. Wheel-zoom is handled by panzoom itself with the
    // cursor as the anchor.
    pzInstance.smoothZoom(rect.width / 2, rect.height / 2, factor);
}
/** Download the rendered SVG as a file. Filename derives from the
 *  embedded document's path / title when available, falls back to a
 *  generic name. */
function downloadSvg() {
    if (!svg.value)
        return;
    const blob = new Blob([svg.value], { type: 'image/svg+xml' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filenameForDownload();
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
}
function filenameForDownload() {
    const d = props.document;
    const base = (d?.title || d?.name || 'diagram')
        .replace(/\.[^.]+$/, '')
        .replace(/[^A-Za-z0-9_\-]+/g, '-')
        .replace(/^-+|-+$/g, '');
    return (base || 'diagram') + '.svg';
}
onMounted(() => {
    void render().then(applyStage);
});
watch(() => resolvedDoc.value, () => {
    void render().then(applyStage);
}, { deep: true });
watch(svg, () => {
    applyStage();
});
onBeforeUnmount(() => {
    // Mermaid keeps no per-instance state we need to tear down; the
    // panzoom instance, however, attaches window-level wheel/mouse
    // listeners and must be disposed explicitly.
    pzInstance?.dispose();
    pzInstance = null;
    if (stageHost.value)
        stageHost.value.innerHTML = '';
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({ mode: 'editor' });
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['diagram-stage']} */ ;
/** @type {__VLS_StyleScopedClasses['diagram-stage']} */ ;
/** @type {__VLS_StyleScopedClasses['diagram-error-source']} */ ;
/** @type {__VLS_StyleScopedClasses['diagram-tool-btn']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: (['diagram-view', `diagram-view--${__VLS_ctx.mode}`]) },
});
if (__VLS_ctx.error) {
    const __VLS_0 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
        variant: "warning",
        ...{ class: "diagram-error" },
    }));
    const __VLS_2 = __VLS_1({
        variant: "warning",
        ...{ class: "diagram-error" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    __VLS_3.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "diagram-error-title" },
    });
    (__VLS_ctx.t('documents.diagramView.renderError'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.pre, __VLS_intrinsicElements.pre)({
        ...{ class: "diagram-error-message" },
    });
    (__VLS_ctx.error);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.details, __VLS_intrinsicElements.details)({
        ...{ class: "diagram-error-source" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.summary, __VLS_intrinsicElements.summary)({});
    (__VLS_ctx.t('documents.diagramView.sourceLabel'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.pre, __VLS_intrinsicElements.pre)({});
    (__VLS_ctx.resolvedDoc.source);
    var __VLS_3;
}
if (!__VLS_ctx.error) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div)({
        ref: "stageHost",
        ...{ class: "diagram-stage" },
        'aria-busy': (__VLS_ctx.rendering),
    });
    /** @type {typeof __VLS_ctx.stageHost} */ ;
}
if (!__VLS_ctx.error && !__VLS_ctx.resolvedDoc.source.trim()) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "diagram-empty" },
    });
    (__VLS_ctx.t('documents.diagramView.empty'));
}
if (!__VLS_ctx.error && __VLS_ctx.resolvedDoc.dialect !== 'mermaid') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "diagram-empty" },
    });
    (__VLS_ctx.t('documents.diagramView.unsupportedDialect', { dialect: __VLS_ctx.resolvedDoc.dialect }));
}
if (__VLS_ctx.mode === 'editor' && __VLS_ctx.svg) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "diagram-toolbar" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.mode === 'editor' && __VLS_ctx.svg))
                    return;
                __VLS_ctx.zoomBy(0.8);
            } },
        type: "button",
        ...{ class: "diagram-tool-btn" },
        title: (__VLS_ctx.t('documents.diagramView.zoomOut')),
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.mode === 'editor' && __VLS_ctx.svg))
                    return;
                __VLS_ctx.zoomBy(1.25);
            } },
        type: "button",
        ...{ class: "diagram-tool-btn" },
        title: (__VLS_ctx.t('documents.diagramView.zoomIn')),
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.fitToContainer) },
        type: "button",
        ...{ class: "diagram-tool-btn" },
        title: (__VLS_ctx.t('documents.diagramView.fitToView')),
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.downloadSvg) },
        type: "button",
        ...{ class: "diagram-tool-btn diagram-tool-btn--text" },
        title: (__VLS_ctx.t('documents.diagramView.downloadSvg')),
    });
}
/** @type {__VLS_StyleScopedClasses['diagram-view']} */ ;
/** @type {__VLS_StyleScopedClasses['diagram-error']} */ ;
/** @type {__VLS_StyleScopedClasses['diagram-error-title']} */ ;
/** @type {__VLS_StyleScopedClasses['diagram-error-message']} */ ;
/** @type {__VLS_StyleScopedClasses['diagram-error-source']} */ ;
/** @type {__VLS_StyleScopedClasses['diagram-stage']} */ ;
/** @type {__VLS_StyleScopedClasses['diagram-empty']} */ ;
/** @type {__VLS_StyleScopedClasses['diagram-empty']} */ ;
/** @type {__VLS_StyleScopedClasses['diagram-toolbar']} */ ;
/** @type {__VLS_StyleScopedClasses['diagram-tool-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['diagram-tool-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['diagram-tool-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['diagram-tool-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['diagram-tool-btn--text']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            t: t,
            stageHost: stageHost,
            rendering: rendering,
            error: error,
            svg: svg,
            resolvedDoc: resolvedDoc,
            fitToContainer: fitToContainer,
            zoomBy: zoomBy,
            downloadSvg: downloadSvg,
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
//# sourceMappingURL=DiagramView.vue.js.map