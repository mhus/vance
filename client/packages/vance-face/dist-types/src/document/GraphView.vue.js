import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VueFlow } from '@vue-flow/core';
import '@vue-flow/core/dist/style.css';
import '@vue-flow/core/dist/theme-default.css';
import { Graph as DagreGraph, layout as dagreLayout } from '@dagrejs/dagre';
import { VButton } from '@/components';
import { edgeKey, emptyNode } from './graphCodec';
/** Approximate vue-flow default node dimensions, used as the
 *  layout-input box size when we hand the graph to dagre. The
 *  layout positions are computed for centered boxes; vue-flow
 *  expects top-left, so we subtract half the box on the way out. */
const NODE_W = 160;
const NODE_H = 44;
/**
 * Editor for `kind: graph` documents. Top-level `nodes` and `edges`
 * arrays drive vue-flow directly — `source`/`target` matches its
 * native edge shape, no adapter layer.
 *
 * Spec: `specification/doc-kind-graph.md`.
 */
defineOptions({ name: 'GraphView' });
const props = defineProps();
const emit = defineEmits();
const { t } = useI18n();
// ── Local source-of-truth ──────────────────────────────────────────
//
// `localNodes` / `localEdges` are the editor's mutable models. We
// don't update node positions during a drag (vue-flow keeps its own
// internal store and animates smoothly); only the final position is
// written on drag-end. That avoids round-trip-flicker that would
// happen if every dragmove re-emitted and the parent re-serialised.
const localNodes = ref(cloneNodes(props.doc.nodes));
const localEdges = ref(cloneEdges(props.doc.edges));
watch(() => props.doc.nodes, (next) => { localNodes.value = cloneNodes(next); }, { deep: true });
watch(() => props.doc.edges, (next) => { localEdges.value = cloneEdges(next); }, { deep: true });
function cloneNodes(src) {
    return src.map((n) => ({
        id: n.id,
        label: n.label,
        color: n.color,
        position: n.position ? { ...n.position } : undefined,
        extra: { ...n.extra },
    }));
}
function cloneEdges(src) {
    return src.map((e) => ({
        id: e.id,
        source: e.source,
        target: e.target,
        label: e.label,
        color: e.color,
        extra: { ...e.extra },
    }));
}
function emitDoc() {
    emit('update:doc', {
        kind: props.doc.kind || 'graph',
        graph: { directed: props.doc.graph?.directed ?? true },
        nodes: localNodes.value,
        edges: localEdges.value,
        extra: props.doc.extra,
    });
}
// ── vue-flow projection ────────────────────────────────────────────
const knownIds = computed(() => new Set(localNodes.value.map((n) => n.id)));
const vfNodes = computed(() => localNodes.value.map((n, idx) => ({
    id: n.id,
    position: n.position ?? { x: 60 + (idx % 4) * 200, y: 60 + Math.floor(idx / 4) * 120 },
    data: { label: n.label && n.label.length > 0 ? n.label : n.id },
    type: 'default',
    style: n.color
        ? { background: n.color, color: contrastText(n.color), border: '1px solid ' + n.color }
        : undefined,
})));
const vfEdges = computed(() => {
    const out = [];
    const directed = props.doc.graph?.directed ?? true;
    for (const e of localEdges.value) {
        if (!knownIds.value.has(e.source) || !knownIds.value.has(e.target))
            continue;
        out.push({
            id: edgeKey(e),
            source: e.source,
            target: e.target,
            label: e.label,
            type: 'default',
            markerEnd: directed ? 'arrowclosed' : undefined,
            style: e.color ? { stroke: e.color } : undefined,
        });
    }
    return out;
});
// ── Selection ──────────────────────────────────────────────────────
const selectedNodeId = ref(null);
const selectedEdgeId = ref(null);
const selectedNode = computed(() => {
    if (!selectedNodeId.value)
        return null;
    return localNodes.value.find((n) => n.id === selectedNodeId.value) ?? null;
});
const selectedEdge = computed(() => {
    if (!selectedEdgeId.value)
        return null;
    return localEdges.value.find((e) => edgeKey(e) === selectedEdgeId.value) ?? null;
});
function onNodeClick({ node }) {
    selectedNodeId.value = node.id;
    selectedEdgeId.value = null;
    panelError.value = null;
}
function onEdgeClick({ edge }) {
    selectedEdgeId.value = edge.id;
    selectedNodeId.value = null;
}
function onPaneClick() {
    selectedNodeId.value = null;
    selectedEdgeId.value = null;
}
// ── Change handlers ────────────────────────────────────────────────
function onNodesChange(changes) {
    let dirty = false;
    for (const c of changes) {
        if (c.type === 'position' && c.position && c.dragging === false) {
            const n = localNodes.value.find((x) => x.id === c.id);
            if (n) {
                n.position = { x: c.position.x, y: c.position.y };
                dirty = true;
            }
        }
        else if (c.type === 'remove') {
            removeNodeLocal(c.id);
            dirty = true;
        }
    }
    if (dirty) {
        localNodes.value = [...localNodes.value];
        localEdges.value = [...localEdges.value]; // node remove drops edges; force re-eval
        emitDoc();
    }
}
function onEdgesChange(changes) {
    let dirty = false;
    for (const c of changes) {
        if (c.type === 'remove') {
            removeEdgeLocal(c.id);
            dirty = true;
        }
    }
    if (dirty) {
        localEdges.value = [...localEdges.value];
        emitDoc();
    }
}
function onConnect(connection) {
    if (!connection.source || !connection.target)
        return;
    if (connection.source === connection.target)
        return; // no self-loops in v1
    // Dedupe: skip if an edge with the same source+target already exists.
    if (localEdges.value.some((e) => e.source === connection.source && e.target === connection.target)) {
        return;
    }
    localEdges.value = [
        ...localEdges.value,
        { source: connection.source, target: connection.target, extra: {} },
    ];
    emitDoc();
}
function removeNodeLocal(id) {
    localNodes.value = localNodes.value.filter((n) => n.id !== id);
    // Drop incident edges so the on-disk form stays clean.
    localEdges.value = localEdges.value.filter((e) => e.source !== id && e.target !== id);
    if (selectedNodeId.value === id)
        selectedNodeId.value = null;
}
function removeEdgeLocal(targetEdgeId) {
    localEdges.value = localEdges.value.filter((e) => edgeKey(e) !== targetEdgeId);
    if (selectedEdgeId.value === targetEdgeId)
        selectedEdgeId.value = null;
}
// ── Toolbar / panel actions ────────────────────────────────────────
const panelError = ref(null);
function addNode() {
    const node = emptyNode(localNodes.value.map((n) => n.id));
    const idx = localNodes.value.length;
    node.position = { x: 80 + (idx % 4) * 200, y: 80 + Math.floor(idx / 4) * 120 };
    localNodes.value = [...localNodes.value, node];
    selectedNodeId.value = node.id;
    selectedEdgeId.value = null;
    panelError.value = null;
    emitDoc();
}
/**
 * Run a hierarchical layout via dagre and write the resulting
 * positions back to every node. Overwrites manual positions on
 * purpose — the user explicitly asks for this. Spec §5.7 (v2).
 */
function runAutoLayout() {
    if (localNodes.value.length === 0)
        return;
    const g = new DagreGraph();
    g.setGraph({ rankdir: 'LR', nodesep: 50, ranksep: 90, marginx: 20, marginy: 20 });
    g.setDefaultEdgeLabel(() => ({}));
    for (const node of localNodes.value) {
        g.setNode(node.id, { width: NODE_W, height: NODE_H });
    }
    for (const edge of localEdges.value) {
        if (knownIds.value.has(edge.source) && knownIds.value.has(edge.target)) {
            g.setEdge(edge.source, edge.target);
        }
    }
    dagreLayout(g);
    for (const node of localNodes.value) {
        const laid = g.node(node.id);
        if (!laid)
            continue;
        const x = laid.x;
        const y = laid.y;
        if (typeof x !== 'number' || typeof y !== 'number')
            continue;
        node.position = { x: x - NODE_W / 2, y: y - NODE_H / 2 };
    }
    localNodes.value = [...localNodes.value];
    emitDoc();
}
function toggleDirected() {
    emit('update:doc', {
        kind: props.doc.kind || 'graph',
        graph: { directed: !(props.doc.graph?.directed ?? true) },
        nodes: localNodes.value,
        edges: localEdges.value,
        extra: props.doc.extra,
    });
}
function renameNode(rawId, inputEl) {
    const node = selectedNode.value;
    if (!node)
        return;
    const newId = rawId.trim();
    const oldId = node.id;
    if (newId === oldId) {
        inputEl.value = oldId;
        return;
    }
    if (!newId) {
        inputEl.value = oldId;
        panelError.value = t('documents.graphView.idEmpty');
        return;
    }
    if (knownIds.value.has(newId)) {
        inputEl.value = oldId;
        panelError.value = t('documents.graphView.idDuplicate', { id: newId });
        return;
    }
    node.id = newId;
    // Rewire every edge incident to the renamed node — both directions.
    for (const e of localEdges.value) {
        if (e.source === oldId)
            e.source = newId;
        if (e.target === oldId)
            e.target = newId;
    }
    selectedNodeId.value = newId;
    panelError.value = null;
    localNodes.value = [...localNodes.value];
    localEdges.value = [...localEdges.value];
    emitDoc();
}
function setLabel(label) {
    if (!selectedNode.value)
        return;
    const trimmed = label.trim();
    selectedNode.value.label = trimmed.length > 0 ? trimmed : undefined;
    localNodes.value = [...localNodes.value];
    emitDoc();
}
function setColor(color) {
    if (!selectedNode.value)
        return;
    selectedNode.value.color = color && color.length > 0 ? color : undefined;
    localNodes.value = [...localNodes.value];
    emitDoc();
}
function setEdgeLabel(label) {
    if (!selectedEdge.value)
        return;
    const trimmed = label.trim();
    selectedEdge.value.label = trimmed.length > 0 ? trimmed : undefined;
    localEdges.value = [...localEdges.value];
    emitDoc();
}
function setEdgeColor(color) {
    if (!selectedEdge.value)
        return;
    selectedEdge.value.color = color && color.length > 0 ? color : undefined;
    localEdges.value = [...localEdges.value];
    emitDoc();
}
function deleteSelectedNode() {
    if (!selectedNodeId.value)
        return;
    removeNodeLocal(selectedNodeId.value);
    localNodes.value = [...localNodes.value];
    localEdges.value = [...localEdges.value];
    emitDoc();
}
function deleteSelectedEdge() {
    if (!selectedEdgeId.value)
        return;
    removeEdgeLocal(selectedEdgeId.value);
    localEdges.value = [...localEdges.value];
    emitDoc();
}
// Keyboard delete on the canvas wrapper. Inputs in the side panel
// keep their native Backspace handling.
function onKeyDown(e) {
    const target = e.target;
    const inForm = target?.tagName === 'INPUT' || target?.tagName === 'TEXTAREA';
    if (inForm)
        return;
    if (e.key === 'Delete' || e.key === 'Backspace') {
        if (selectedNodeId.value) {
            e.preventDefault();
            deleteSelectedNode();
        }
        else if (selectedEdgeId.value) {
            e.preventDefault();
            deleteSelectedEdge();
        }
    }
}
/** Pick black or white for the node's text colour given a hex
 *  background, using a simple luminance threshold. */
function contrastText(bgColor) {
    const hex = bgColor.startsWith('#') ? bgColor.slice(1) : '';
    if (hex.length !== 3 && hex.length !== 6)
        return '';
    const expand = hex.length === 3 ? hex.split('').map((c) => c + c).join('') : hex;
    const r = parseInt(expand.slice(0, 2), 16);
    const g = parseInt(expand.slice(2, 4), 16);
    const b = parseInt(expand.slice(4, 6), 16);
    if (Number.isNaN(r) || Number.isNaN(g) || Number.isNaN(b))
        return '';
    const lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
    return lum > 0.55 ? '#0f172a' : '#ffffff';
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['panel']} */ ;
/** @type {__VLS_StyleScopedClasses['panel']} */ ;
/** @type {__VLS_StyleScopedClasses['panel-input']} */ ;
/** @type {__VLS_StyleScopedClasses['color-row']} */ ;
/** @type {__VLS_StyleScopedClasses['clear-color']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ onKeydown: (__VLS_ctx.onKeyDown) },
    ...{ class: "graph-view" },
    tabindex: "0",
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "toolbar" },
});
const __VLS_0 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "primary",
}));
const __VLS_2 = __VLS_1({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "primary",
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
let __VLS_4;
let __VLS_5;
let __VLS_6;
const __VLS_7 = {
    onClick: (__VLS_ctx.addNode)
};
__VLS_3.slots.default;
(__VLS_ctx.t('documents.graphView.addNode'));
var __VLS_3;
const __VLS_8 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "ghost",
    disabled: (__VLS_ctx.localNodes.length === 0),
    title: (__VLS_ctx.t('documents.graphView.autoLayoutHint')),
}));
const __VLS_10 = __VLS_9({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "ghost",
    disabled: (__VLS_ctx.localNodes.length === 0),
    title: (__VLS_ctx.t('documents.graphView.autoLayoutHint')),
}, ...__VLS_functionalComponentArgsRest(__VLS_9));
let __VLS_12;
let __VLS_13;
let __VLS_14;
const __VLS_15 = {
    onClick: (__VLS_ctx.runAutoLayout)
};
__VLS_11.slots.default;
(__VLS_ctx.t('documents.graphView.autoLayout'));
var __VLS_11;
__VLS_asFunctionalElement(__VLS_intrinsicElements.label, __VLS_intrinsicElements.label)({
    ...{ class: "directed-toggle" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
    ...{ onChange: (__VLS_ctx.toggleDirected) },
    type: "checkbox",
    checked: (props.doc.graph?.directed ?? true),
});
(__VLS_ctx.t('documents.graphView.directed'));
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "hint" },
});
(__VLS_ctx.t('documents.graphView.hint'));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "canvas-and-panel" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "canvas" },
});
const __VLS_16 = {}.VueFlow;
/** @type {[typeof __VLS_components.VueFlow, ]} */ ;
// @ts-ignore
const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
    ...{ 'onNodesChange': {} },
    ...{ 'onEdgesChange': {} },
    ...{ 'onConnect': {} },
    ...{ 'onNodeClick': {} },
    ...{ 'onEdgeClick': {} },
    ...{ 'onPaneClick': {} },
    nodes: (__VLS_ctx.vfNodes),
    edges: (__VLS_ctx.vfEdges),
    fitViewOnInit: (true),
}));
const __VLS_18 = __VLS_17({
    ...{ 'onNodesChange': {} },
    ...{ 'onEdgesChange': {} },
    ...{ 'onConnect': {} },
    ...{ 'onNodeClick': {} },
    ...{ 'onEdgeClick': {} },
    ...{ 'onPaneClick': {} },
    nodes: (__VLS_ctx.vfNodes),
    edges: (__VLS_ctx.vfEdges),
    fitViewOnInit: (true),
}, ...__VLS_functionalComponentArgsRest(__VLS_17));
let __VLS_20;
let __VLS_21;
let __VLS_22;
const __VLS_23 = {
    onNodesChange: (__VLS_ctx.onNodesChange)
};
const __VLS_24 = {
    onEdgesChange: (__VLS_ctx.onEdgesChange)
};
const __VLS_25 = {
    onConnect: (__VLS_ctx.onConnect)
};
const __VLS_26 = {
    onNodeClick: (__VLS_ctx.onNodeClick)
};
const __VLS_27 = {
    onEdgeClick: (__VLS_ctx.onEdgeClick)
};
const __VLS_28 = {
    onPaneClick: (__VLS_ctx.onPaneClick)
};
var __VLS_19;
if (__VLS_ctx.selectedNode) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.aside, __VLS_intrinsicElements.aside)({
        ...{ class: "panel" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h4, __VLS_intrinsicElements.h4)({});
    (__VLS_ctx.t('documents.graphView.nodeProps'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.label, __VLS_intrinsicElements.label)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
        ...{ onChange: ((e) => __VLS_ctx.renameNode(e.target.value, e.target)) },
        ...{ onKeydown: (...[$event]) => {
                if (!(__VLS_ctx.selectedNode))
                    return;
                $event.target.blur();
            } },
        type: "text",
        ...{ class: "panel-input" },
        value: (__VLS_ctx.selectedNode.id),
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.label, __VLS_intrinsicElements.label)({});
    (__VLS_ctx.t('documents.graphView.labelField'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
        ...{ onChange: ((e) => __VLS_ctx.setLabel(e.target.value)) },
        ...{ onKeydown: (...[$event]) => {
                if (!(__VLS_ctx.selectedNode))
                    return;
                $event.target.blur();
            } },
        type: "text",
        ...{ class: "panel-input" },
        value: (__VLS_ctx.selectedNode.label ?? ''),
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.label, __VLS_intrinsicElements.label)({});
    (__VLS_ctx.t('documents.graphView.colorField'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "color-row" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
        ...{ onInput: ((e) => __VLS_ctx.setColor(e.target.value)) },
        type: "color",
        value: (__VLS_ctx.selectedNode.color ?? '#cccccc'),
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.selectedNode))
                    return;
                __VLS_ctx.setColor('');
            } },
        type: "button",
        ...{ class: "clear-color" },
        disabled: (!__VLS_ctx.selectedNode.color),
    });
    (__VLS_ctx.t('documents.graphView.clearColor'));
    if (__VLS_ctx.panelError) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "panel-error" },
        });
        (__VLS_ctx.panelError);
    }
    const __VLS_29 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_30 = __VLS_asFunctionalComponent(__VLS_29, new __VLS_29({
        ...{ 'onClick': {} },
        size: "sm",
        variant: "danger",
    }));
    const __VLS_31 = __VLS_30({
        ...{ 'onClick': {} },
        size: "sm",
        variant: "danger",
    }, ...__VLS_functionalComponentArgsRest(__VLS_30));
    let __VLS_33;
    let __VLS_34;
    let __VLS_35;
    const __VLS_36 = {
        onClick: (__VLS_ctx.deleteSelectedNode)
    };
    __VLS_32.slots.default;
    (__VLS_ctx.t('documents.graphView.deleteNode'));
    var __VLS_32;
}
else if (__VLS_ctx.selectedEdge) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.aside, __VLS_intrinsicElements.aside)({
        ...{ class: "panel" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h4, __VLS_intrinsicElements.h4)({});
    (__VLS_ctx.t('documents.graphView.edgeProps'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "edge-route" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "edge-endpoint" },
    });
    (__VLS_ctx.selectedEdge.source);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "edge-arrow" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "edge-endpoint" },
    });
    (__VLS_ctx.selectedEdge.target);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.label, __VLS_intrinsicElements.label)({});
    (__VLS_ctx.t('documents.graphView.labelField'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
        ...{ onChange: ((e) => __VLS_ctx.setEdgeLabel(e.target.value)) },
        ...{ onKeydown: (...[$event]) => {
                if (!!(__VLS_ctx.selectedNode))
                    return;
                if (!(__VLS_ctx.selectedEdge))
                    return;
                $event.target.blur();
            } },
        type: "text",
        ...{ class: "panel-input" },
        value: (__VLS_ctx.selectedEdge.label ?? ''),
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.label, __VLS_intrinsicElements.label)({});
    (__VLS_ctx.t('documents.graphView.colorField'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "color-row" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
        ...{ onInput: ((e) => __VLS_ctx.setEdgeColor(e.target.value)) },
        type: "color",
        value: (__VLS_ctx.selectedEdge.color ?? '#888888'),
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (...[$event]) => {
                if (!!(__VLS_ctx.selectedNode))
                    return;
                if (!(__VLS_ctx.selectedEdge))
                    return;
                __VLS_ctx.setEdgeColor('');
            } },
        type: "button",
        ...{ class: "clear-color" },
        disabled: (!__VLS_ctx.selectedEdge.color),
    });
    (__VLS_ctx.t('documents.graphView.clearColor'));
    const __VLS_37 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_38 = __VLS_asFunctionalComponent(__VLS_37, new __VLS_37({
        ...{ 'onClick': {} },
        size: "sm",
        variant: "danger",
    }));
    const __VLS_39 = __VLS_38({
        ...{ 'onClick': {} },
        size: "sm",
        variant: "danger",
    }, ...__VLS_functionalComponentArgsRest(__VLS_38));
    let __VLS_41;
    let __VLS_42;
    let __VLS_43;
    const __VLS_44 = {
        onClick: (__VLS_ctx.deleteSelectedEdge)
    };
    __VLS_40.slots.default;
    (__VLS_ctx.t('documents.graphView.deleteEdge'));
    var __VLS_40;
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.aside, __VLS_intrinsicElements.aside)({
        ...{ class: "panel panel--empty" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "panel-empty-hint" },
    });
    (__VLS_ctx.t('documents.graphView.emptySelectionHint'));
}
/** @type {__VLS_StyleScopedClasses['graph-view']} */ ;
/** @type {__VLS_StyleScopedClasses['toolbar']} */ ;
/** @type {__VLS_StyleScopedClasses['directed-toggle']} */ ;
/** @type {__VLS_StyleScopedClasses['hint']} */ ;
/** @type {__VLS_StyleScopedClasses['canvas-and-panel']} */ ;
/** @type {__VLS_StyleScopedClasses['canvas']} */ ;
/** @type {__VLS_StyleScopedClasses['panel']} */ ;
/** @type {__VLS_StyleScopedClasses['panel-input']} */ ;
/** @type {__VLS_StyleScopedClasses['panel-input']} */ ;
/** @type {__VLS_StyleScopedClasses['color-row']} */ ;
/** @type {__VLS_StyleScopedClasses['clear-color']} */ ;
/** @type {__VLS_StyleScopedClasses['panel-error']} */ ;
/** @type {__VLS_StyleScopedClasses['panel']} */ ;
/** @type {__VLS_StyleScopedClasses['edge-route']} */ ;
/** @type {__VLS_StyleScopedClasses['edge-endpoint']} */ ;
/** @type {__VLS_StyleScopedClasses['edge-arrow']} */ ;
/** @type {__VLS_StyleScopedClasses['edge-endpoint']} */ ;
/** @type {__VLS_StyleScopedClasses['panel-input']} */ ;
/** @type {__VLS_StyleScopedClasses['color-row']} */ ;
/** @type {__VLS_StyleScopedClasses['clear-color']} */ ;
/** @type {__VLS_StyleScopedClasses['panel']} */ ;
/** @type {__VLS_StyleScopedClasses['panel--empty']} */ ;
/** @type {__VLS_StyleScopedClasses['panel-empty-hint']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VueFlow: VueFlow,
            VButton: VButton,
            t: t,
            localNodes: localNodes,
            vfNodes: vfNodes,
            vfEdges: vfEdges,
            selectedNode: selectedNode,
            selectedEdge: selectedEdge,
            onNodeClick: onNodeClick,
            onEdgeClick: onEdgeClick,
            onPaneClick: onPaneClick,
            onNodesChange: onNodesChange,
            onEdgesChange: onEdgesChange,
            onConnect: onConnect,
            panelError: panelError,
            addNode: addNode,
            runAutoLayout: runAutoLayout,
            toggleDirected: toggleDirected,
            renameNode: renameNode,
            setLabel: setLabel,
            setColor: setColor,
            setEdgeLabel: setEdgeLabel,
            setEdgeColor: setEdgeColor,
            deleteSelectedNode: deleteSelectedNode,
            deleteSelectedEdge: deleteSelectedEdge,
            onKeyDown: onKeyDown,
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
//# sourceMappingURL=GraphView.vue.js.map