<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VueFlow } from '@vue-flow/core';
import type { Connection, Edge, EdgeChange, Node, NodeChange } from '@vue-flow/core';
import '@vue-flow/core/dist/style.css';
import '@vue-flow/core/dist/theme-default.css';
import { Graph as DagreGraph, layout as dagreLayout } from '@dagrejs/dagre';
import { VButton } from '@/components';
import type { GraphDocument, GraphEdge, GraphNode, GraphPosition } from './graphCodec';
import { edgeKey, emptyNode, parseGraph } from './graphCodec';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';
import type { FenceMeta } from '@/kindRenderers/parseFenceLang';

/** Approximate vue-flow default node dimensions, used as the
 *  layout-input box size when we hand the graph to dagre. The
 *  layout positions are computed for centered boxes; vue-flow
 *  expects top-left, so we subtract half the box on the way out. */
const NODE_W = 160;
const NODE_H = 44;

/**
 * Renderer / editor for `kind: graph` documents. Top-level `nodes`
 * and `edges` arrays drive vue-flow directly — `source`/`target`
 * matches its native edge shape, no adapter layer.
 *
 * Three modes:
 *   - `editor`   — full editor (toolbar, drag, connect, side panel).
 *   - `inline`   — read-only view from a fence body (YAML/JSON).
 *   - `embedded` — read-only view from a loaded {@link DocumentDto}.
 *
 * In the two read-only modes, nodes without an explicit `position`
 * get a one-shot Dagre auto-layout — LLM-emitted graphs almost never
 * carry positions, so we render a clean DAG rather than the grid
 * fallback you'd get otherwise.
 *
 * Spec: `specification/doc-kind-graph.md`.
 */
defineOptions({ name: 'GraphView' });

const props = withDefaults(defineProps<{
  mode?: 'editor' | 'inline' | 'embedded';
  doc?: GraphDocument;
  content?: string;
  meta?: FenceMeta;
  document?: DocumentDto;
  embedRef?: EmbedRef;
}>(), {
  mode: 'editor',
  meta: () => ({}),
});

const emit = defineEmits<{
  (event: 'update:doc', doc: GraphDocument): void;
}>();

const { t } = useI18n();

const isEditor = computed(() => props.mode === 'editor');

/** Read-only GraphDocument resolved for inline / embedded modes.
 *  Editor mode passes the prop straight through. */
const resolvedDoc = computed<GraphDocument>(() => {
  if (props.mode === 'editor') {
    return props.doc ?? emptyGraphDoc();
  }
  if (props.mode === 'inline') {
    const body = props.content ?? '';
    if (!body.trim()) return emptyGraphDoc();
    // Inline fences are YAML by convention; fall back to JSON if the
    // body starts with `{` so curly-brace-style bodies still parse.
    const mime = body.trimStart().startsWith('{')
      ? 'application/json'
      : 'application/yaml';
    try {
      return parseGraph(body, mime);
    } catch (e) {
      console.warn('GraphView: failed to parse inline content', e);
      return emptyGraphDoc();
    }
  }
  const d = props.document;
  if (!d || !d.inlineText) return emptyGraphDoc();
  const mime = d.mimeType ?? 'application/json';
  try {
    return parseGraph(d.inlineText, mime);
  } catch (e) {
    console.warn('GraphView: failed to parse embedded document', e);
    return emptyGraphDoc();
  }
});

function emptyGraphDoc(): GraphDocument {
  return { kind: 'graph', graph: { directed: true }, nodes: [], edges: [], extra: {} };
}

// ── Local source-of-truth ──────────────────────────────────────────
//
// `localNodes` / `localEdges` are the editor's mutable models. We
// don't update node positions during a drag (vue-flow keeps its own
// internal store and animates smoothly); only the final position is
// written on drag-end. That avoids round-trip-flicker that would
// happen if every dragmove re-emitted and the parent re-serialised.

const localNodes = ref<GraphNode[]>(cloneNodes(resolvedDoc.value.nodes));
const localEdges = ref<GraphEdge[]>(cloneEdges(resolvedDoc.value.edges));

watch(
  () => resolvedDoc.value.nodes,
  (next) => { localNodes.value = cloneNodes(next); },
  { deep: true },
);
watch(
  () => resolvedDoc.value.edges,
  (next) => { localEdges.value = cloneEdges(next); },
  { deep: true },
);

function cloneNodes(src: GraphNode[]): GraphNode[] {
  return src.map((n) => ({
    id: n.id,
    label: n.label,
    color: n.color,
    position: n.position ? { ...n.position } : undefined,
    extra: { ...n.extra },
  }));
}
function cloneEdges(src: GraphEdge[]): GraphEdge[] {
  return src.map((e) => ({
    id: e.id,
    source: e.source,
    target: e.target,
    label: e.label,
    color: e.color,
    extra: { ...e.extra },
  }));
}

function emitDoc(): void {
  if (!isEditor.value) return;
  const base = props.doc ?? resolvedDoc.value;
  emit('update:doc', {
    kind: base.kind || 'graph',
    graph: { directed: base.graph?.directed ?? true },
    nodes: localNodes.value,
    edges: localEdges.value,
    extra: base.extra,
  });
}

// ── vue-flow projection ────────────────────────────────────────────

const knownIds = computed(() => new Set(localNodes.value.map((n) => n.id)));

/** One-shot Dagre layout for graphs that arrive without per-node
 *  positions (LLM-emitted inline / embedded graphs). Skipped when
 *  any node already has a `position` — that signals a user-fixed
 *  layout (editor mode) we must respect. */
const layoutPositions = computed<Map<string, GraphPosition>>(() => {
  const out = new Map<string, GraphPosition>();
  if (localNodes.value.length === 0) return out;
  if (localNodes.value.some((n) => n.position)) return out;

  const g = new DagreGraph();
  g.setGraph({ rankdir: 'LR', nodesep: 50, ranksep: 90, marginx: 20, marginy: 20 });
  g.setDefaultEdgeLabel(() => ({}));
  for (const node of localNodes.value) {
    g.setNode(node.id, { width: NODE_W, height: NODE_H });
  }
  const ids = knownIds.value;
  for (const edge of localEdges.value) {
    if (ids.has(edge.source) && ids.has(edge.target)) {
      g.setEdge(edge.source, edge.target);
    }
  }
  dagreLayout(g);
  for (const node of localNodes.value) {
    const laid = g.node(node.id);
    if (!laid) continue;
    const x = (laid as { x?: number }).x;
    const y = (laid as { y?: number }).y;
    if (typeof x !== 'number' || typeof y !== 'number') continue;
    out.set(node.id, { x: x - NODE_W / 2, y: y - NODE_H / 2 });
  }
  return out;
});

const vfNodes = computed<Node[]>(() => localNodes.value.map((n, idx) => ({
  id: n.id,
  position: n.position
    ?? layoutPositions.value.get(n.id)
    ?? { x: 60 + (idx % 4) * 200, y: 60 + Math.floor(idx / 4) * 120 },
  data: { label: n.label && n.label.length > 0 ? n.label : n.id },
  type: 'default',
  draggable: isEditor.value,
  selectable: isEditor.value,
  connectable: isEditor.value,
  style: n.color
    ? { background: n.color, color: contrastText(n.color), border: '1px solid ' + n.color }
    : undefined,
})));

const vfEdges = computed<Edge[]>(() => {
  const out: Edge[] = [];
  const directed = resolvedDoc.value.graph?.directed ?? true;
  for (const e of localEdges.value) {
    if (!knownIds.value.has(e.source) || !knownIds.value.has(e.target)) continue;
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

const selectedNodeId = ref<string | null>(null);
const selectedEdgeId = ref<string | null>(null);

const selectedNode = computed<GraphNode | null>(() => {
  if (!selectedNodeId.value) return null;
  return localNodes.value.find((n) => n.id === selectedNodeId.value) ?? null;
});
const selectedEdge = computed<GraphEdge | null>(() => {
  if (!selectedEdgeId.value) return null;
  return localEdges.value.find((e) => edgeKey(e) === selectedEdgeId.value) ?? null;
});

function onNodeClick({ node }: { node: Node }): void {
  selectedNodeId.value = node.id;
  selectedEdgeId.value = null;
  panelError.value = null;
}

function onEdgeClick({ edge }: { edge: Edge }): void {
  selectedEdgeId.value = edge.id;
  selectedNodeId.value = null;
}

function onPaneClick(): void {
  selectedNodeId.value = null;
  selectedEdgeId.value = null;
}

// ── Change handlers ────────────────────────────────────────────────

function onNodesChange(changes: NodeChange[]): void {
  if (!isEditor.value) return;
  let dirty = false;
  for (const c of changes) {
    if (c.type === 'position' && c.position && c.dragging === false) {
      const n = localNodes.value.find((x) => x.id === c.id);
      if (n) {
        n.position = { x: c.position.x, y: c.position.y };
        dirty = true;
      }
    } else if (c.type === 'remove') {
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

function onEdgesChange(changes: EdgeChange[]): void {
  if (!isEditor.value) return;
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

function onConnect(connection: Connection): void {
  if (!isEditor.value) return;
  if (!connection.source || !connection.target) return;
  if (connection.source === connection.target) return; // no self-loops in v1
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

function removeNodeLocal(id: string): void {
  localNodes.value = localNodes.value.filter((n) => n.id !== id);
  // Drop incident edges so the on-disk form stays clean.
  localEdges.value = localEdges.value.filter((e) => e.source !== id && e.target !== id);
  if (selectedNodeId.value === id) selectedNodeId.value = null;
}

function removeEdgeLocal(targetEdgeId: string): void {
  localEdges.value = localEdges.value.filter((e) => edgeKey(e) !== targetEdgeId);
  if (selectedEdgeId.value === targetEdgeId) selectedEdgeId.value = null;
}

// ── Toolbar / panel actions ────────────────────────────────────────

const panelError = ref<string | null>(null);

function addNode(): void {
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
function runAutoLayout(): void {
  if (localNodes.value.length === 0) return;
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
    if (!laid) continue;
    const x = (laid as { x?: number }).x;
    const y = (laid as { x?: number; y?: number }).y;
    if (typeof x !== 'number' || typeof y !== 'number') continue;
    node.position = { x: x - NODE_W / 2, y: y - NODE_H / 2 };
  }
  localNodes.value = [...localNodes.value];
  emitDoc();
}

function toggleDirected(): void {
  if (!isEditor.value) return;
  const base = props.doc ?? resolvedDoc.value;
  emit('update:doc', {
    kind: base.kind || 'graph',
    graph: { directed: !(base.graph?.directed ?? true) },
    nodes: localNodes.value,
    edges: localEdges.value,
    extra: base.extra,
  });
}

function renameNode(rawId: string, inputEl: HTMLInputElement): void {
  const node = selectedNode.value;
  if (!node) return;
  const newId = rawId.trim();
  const oldId = node.id;
  if (newId === oldId) { inputEl.value = oldId; return; }
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
    if (e.source === oldId) e.source = newId;
    if (e.target === oldId) e.target = newId;
  }
  selectedNodeId.value = newId;
  panelError.value = null;
  localNodes.value = [...localNodes.value];
  localEdges.value = [...localEdges.value];
  emitDoc();
}

function setLabel(label: string): void {
  if (!selectedNode.value) return;
  const trimmed = label.trim();
  selectedNode.value.label = trimmed.length > 0 ? trimmed : undefined;
  localNodes.value = [...localNodes.value];
  emitDoc();
}

function setColor(color: string): void {
  if (!selectedNode.value) return;
  selectedNode.value.color = color && color.length > 0 ? color : undefined;
  localNodes.value = [...localNodes.value];
  emitDoc();
}

function setEdgeLabel(label: string): void {
  if (!selectedEdge.value) return;
  const trimmed = label.trim();
  selectedEdge.value.label = trimmed.length > 0 ? trimmed : undefined;
  localEdges.value = [...localEdges.value];
  emitDoc();
}

function setEdgeColor(color: string): void {
  if (!selectedEdge.value) return;
  selectedEdge.value.color = color && color.length > 0 ? color : undefined;
  localEdges.value = [...localEdges.value];
  emitDoc();
}

function deleteSelectedNode(): void {
  if (!selectedNodeId.value) return;
  removeNodeLocal(selectedNodeId.value);
  localNodes.value = [...localNodes.value];
  localEdges.value = [...localEdges.value];
  emitDoc();
}

function deleteSelectedEdge(): void {
  if (!selectedEdgeId.value) return;
  removeEdgeLocal(selectedEdgeId.value);
  localEdges.value = [...localEdges.value];
  emitDoc();
}

// Keyboard delete on the canvas wrapper. Inputs in the side panel
// keep their native Backspace handling.
function onKeyDown(e: KeyboardEvent): void {
  if (!isEditor.value) return;
  const target = e.target as HTMLElement | null;
  const inForm = target?.tagName === 'INPUT' || target?.tagName === 'TEXTAREA';
  if (inForm) return;
  if (e.key === 'Delete' || e.key === 'Backspace') {
    if (selectedNodeId.value) {
      e.preventDefault();
      deleteSelectedNode();
    } else if (selectedEdgeId.value) {
      e.preventDefault();
      deleteSelectedEdge();
    }
  }
}

/** Pick black or white for the node's text colour given a hex
 *  background, using a simple luminance threshold. */
function contrastText(bgColor: string): string {
  const hex = bgColor.startsWith('#') ? bgColor.slice(1) : '';
  if (hex.length !== 3 && hex.length !== 6) return '';
  const expand = hex.length === 3 ? hex.split('').map((c) => c + c).join('') : hex;
  const r = parseInt(expand.slice(0, 2), 16);
  const g = parseInt(expand.slice(2, 4), 16);
  const b = parseInt(expand.slice(4, 6), 16);
  if (Number.isNaN(r) || Number.isNaN(g) || Number.isNaN(b)) return '';
  const lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
  return lum > 0.55 ? '#0f172a' : '#ffffff';
}
</script>

<template>
  <div :class="['graph-view', `graph-view--${mode}`]" tabindex="0" @keydown="onKeyDown">
    <div v-if="isEditor" class="toolbar">
      <VButton size="sm" variant="primary" @click="addNode">
        + {{ t('documents.graphView.addNode') }}
      </VButton>
      <VButton
        size="sm"
        variant="ghost"
        :disabled="localNodes.length === 0"
        :title="t('documents.graphView.autoLayoutHint')"
        @click="runAutoLayout"
      >
        ⤧ {{ t('documents.graphView.autoLayout') }}
      </VButton>
      <label class="directed-toggle">
        <input
          type="checkbox"
          :checked="props.doc?.graph?.directed ?? true"
          @change="toggleDirected"
        />
        {{ t('documents.graphView.directed') }}
      </label>
      <span class="hint">{{ t('documents.graphView.hint') }}</span>
    </div>

    <div :class="['canvas-and-panel', { 'canvas-and-panel--readonly': !isEditor }]">
      <div class="canvas">
        <VueFlow
          :nodes="vfNodes"
          :edges="vfEdges"
          :fit-view-on-init="true"
          :nodes-draggable="isEditor"
          :nodes-connectable="isEditor"
          :elements-selectable="isEditor"
          :edges-updatable="isEditor"
          @nodes-change="onNodesChange"
          @edges-change="onEdgesChange"
          @connect="onConnect"
          @node-click="onNodeClick"
          @edge-click="onEdgeClick"
          @pane-click="onPaneClick"
        />
      </div>

      <aside v-if="isEditor && selectedNode" class="panel">
        <h4>{{ t('documents.graphView.nodeProps') }}</h4>
        <label>
          ID
          <input
            type="text"
            class="panel-input"
            :value="selectedNode.id"
            @change="(e) => renameNode((e.target as HTMLInputElement).value, e.target as HTMLInputElement)"
            @keydown.enter.prevent="($event.target as HTMLInputElement).blur()"
          />
        </label>
        <label>
          {{ t('documents.graphView.labelField') }}
          <input
            type="text"
            class="panel-input"
            :value="selectedNode.label ?? ''"
            @change="(e) => setLabel((e.target as HTMLInputElement).value)"
            @keydown.enter.prevent="($event.target as HTMLInputElement).blur()"
          />
        </label>
        <label>
          {{ t('documents.graphView.colorField') }}
          <div class="color-row">
            <input
              type="color"
              :value="selectedNode.color ?? '#cccccc'"
              @input="(e) => setColor((e.target as HTMLInputElement).value)"
            />
            <button
              type="button"
              class="clear-color"
              :disabled="!selectedNode.color"
              @click="setColor('')"
            >{{ t('documents.graphView.clearColor') }}</button>
          </div>
        </label>
        <p v-if="panelError" class="panel-error">{{ panelError }}</p>
        <VButton size="sm" variant="danger" @click="deleteSelectedNode">
          {{ t('documents.graphView.deleteNode') }}
        </VButton>
      </aside>

      <aside v-else-if="isEditor && selectedEdge" class="panel">
        <h4>{{ t('documents.graphView.edgeProps') }}</h4>
        <p class="edge-route">
          <span class="edge-endpoint">{{ selectedEdge.source }}</span>
          <span class="edge-arrow">→</span>
          <span class="edge-endpoint">{{ selectedEdge.target }}</span>
        </p>
        <label>
          {{ t('documents.graphView.labelField') }}
          <input
            type="text"
            class="panel-input"
            :value="selectedEdge.label ?? ''"
            @change="(e) => setEdgeLabel((e.target as HTMLInputElement).value)"
            @keydown.enter.prevent="($event.target as HTMLInputElement).blur()"
          />
        </label>
        <label>
          {{ t('documents.graphView.colorField') }}
          <div class="color-row">
            <input
              type="color"
              :value="selectedEdge.color ?? '#888888'"
              @input="(e) => setEdgeColor((e.target as HTMLInputElement).value)"
            />
            <button
              type="button"
              class="clear-color"
              :disabled="!selectedEdge.color"
              @click="setEdgeColor('')"
            >{{ t('documents.graphView.clearColor') }}</button>
          </div>
        </label>
        <VButton size="sm" variant="danger" @click="deleteSelectedEdge">
          {{ t('documents.graphView.deleteEdge') }}
        </VButton>
      </aside>

      <aside v-else-if="isEditor" class="panel panel--empty">
        <p class="panel-empty-hint">{{ t('documents.graphView.emptySelectionHint') }}</p>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.graph-view {
  outline: none;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  flex-wrap: wrap;
}
.directed-toggle {
  display: flex;
  align-items: center;
  gap: 0.3rem;
  font-size: 0.85rem;
  cursor: pointer;
  user-select: none;
}
.hint {
  font-size: 0.75rem;
  opacity: 0.6;
  margin-left: auto;
}
.canvas-and-panel {
  display: flex;
  gap: 0.75rem;
  height: 65vh;
  min-height: 420px;
}
.canvas-and-panel--readonly {
  height: 22rem;
  min-height: 16rem;
}
.canvas {
  flex: 1 1 auto;
  background: hsl(var(--b1));
  border: 1px solid hsl(var(--bc) / 0.15);
  border-radius: 0.5rem;
  overflow: hidden;
}
.panel {
  width: 16rem;
  flex: 0 0 16rem;
  background: hsl(var(--b1));
  border: 1px solid hsl(var(--bc) / 0.15);
  border-radius: 0.5rem;
  padding: 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.55rem;
  font-size: 0.85rem;
  overflow: auto;
}
.panel h4 {
  margin: 0 0 0.25rem 0;
  font-size: 0.75rem;
  text-transform: uppercase;
  opacity: 0.65;
  letter-spacing: 0.04em;
}
.panel label {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  font-size: 0.75rem;
  opacity: 0.85;
}
.panel-input {
  background: transparent;
  border: 1px solid hsl(var(--bc) / 0.25);
  border-radius: 0.25rem;
  padding: 0.25rem 0.5rem;
  font: inherit;
  color: inherit;
  outline: none;
}
.panel-input:focus {
  border-color: hsl(var(--p));
  box-shadow: 0 0 0 2px hsl(var(--p) / 0.2);
}
.color-row {
  display: flex;
  gap: 0.4rem;
  align-items: center;
}
.color-row input[type="color"] {
  flex: 0 0 2.5rem;
  height: 2rem;
  border: 1px solid hsl(var(--bc) / 0.25);
  border-radius: 0.25rem;
  background: transparent;
  cursor: pointer;
  padding: 0;
}
.clear-color {
  background: transparent;
  border: 1px solid hsl(var(--bc) / 0.2);
  border-radius: 0.25rem;
  padding: 0.2rem 0.5rem;
  font-size: 0.75rem;
  cursor: pointer;
  color: inherit;
}
.clear-color:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.panel-error {
  color: hsl(var(--er));
  font-size: 0.8rem;
  margin: 0.25rem 0 0;
}
.edge-route {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  font-family: ui-monospace, monospace;
  font-size: 0.78rem;
  margin: 0;
  padding: 0.4rem 0.5rem;
  background: hsl(var(--bc) / 0.05);
  border-radius: 0.25rem;
}
.edge-endpoint {
  flex: 1 1 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.edge-arrow {
  flex: 0 0 auto;
  opacity: 0.6;
}
.panel--empty {
  align-items: stretch;
  justify-content: center;
}
.panel-empty-hint {
  font-size: 0.8rem;
  opacity: 0.55;
  text-align: center;
  font-style: italic;
}
</style>
