<script setup lang="ts">
/**
 * The canvas of the workflow editor: the same drawing as the read-only
 * {@code WorkflowGraph} (shared layout, node card and edge look), plus what
 * only an editor needs — a picked state or connection, handles that take a
 * line, and a line dropped on empty canvas. Lives under Cortex because it is
 * editing code; the run view and the plain drawing never import it.
 *
 * It changes nothing itself: the caller owns the text and the selection, this
 * component reports what the person did.
 *
 * Plan: {@code planning/workflow-editor.md}.
 */
import { computed, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { VueFlow } from '@vue-flow/core';
import '@vue-flow/core/dist/style.css';
import '@vue-flow/core/dist/theme-default.css';
import { VAlert, VButton } from '@/components';
import { toFlowEdges, toFlowNodes } from '@/workflowGraph/flowElements';
import type { EdgeRef } from './workflowMutations';
import { layoutPositions, type GraphDirection } from '@/workflowGraph/layout';
import { useFitOnResize } from '@/workflowGraph/useFitOnResize';
import { parseWorkflowGraph } from '@/workflowGraph/workflowGraphCodec';
import WorkflowNodeCard from '@/workflowGraph/WorkflowNodeCard.vue';

defineOptions({ name: 'WorkflowEditGraph' });

interface Props {
  /** The workflow YAML. */
  doc: string;
  /** The picked state; the caller owns it. */
  selected?: string | null;
  /** The picked connection; the caller owns it. */
  selectedEdge?: EdgeRef | null;
  height?: string;
}
const props = withDefaults(defineProps<Props>(), { selected: null, selectedEdge: null, height: '65vh' });
const emit = defineEmits<{
  (e: 'select', state: string | null): void;
  /** The person wants to add a state; the caller shows what can be added. */
  (e: 'add'): void;
  /** A connection was picked; {@code null} when the pick is cleared. */
  (e: 'select-edge', edge: EdgeRef | null): void;
  /** A line was drawn from {@code source} to {@code target}, or dropped on empty canvas ({@code null}). */
  (e: 'connect', source: string, target: string | null): void;
}>();

const { t } = useI18n();

const canvas = ref<HTMLElement | null>(null);
const { onPaneReady } = useFitOnResize(canvas);

const direction = ref<GraphDirection>('TB');
const graph = computed(() => parseWorkflowGraph(props.doc));
const positions = computed(() => layoutPositions(graph.value, direction.value));

const vfNodes = computed(() =>
  toFlowNodes(
    graph.value,
    positions.value,
    (state) => ({ selected: props.selected === state.name }),
    { draggable: false, selectable: false, connectable: true },
  ),
);
const vfEdges = computed(() =>
  toFlowEdges(graph.value.edges).map((edge, index) => {
    const own = graph.value.edges[index];
    const picked = props.selectedEdge !== null
      && own.source === props.selectedEdge.source
      && own.kind === props.selectedEdge.kind
      && own.key === props.selectedEdge.key;
    return picked ? { ...edge, style: { ...edge.style, strokeWidth: '3.5' }, selected: true } : edge;
  }),
);

const hasStates = computed(() => graph.value.states.length > 0);

function onNodeClick(event: { node: { id: string } }): void {
  const state = graph.value.states.find((s) => s.name === event.node.id);
  if (state && !state.missing) emit('select', state.name);
}

function onEdgeClick(event: { edge: { id: string } }): void {
  const edge = graph.value.edges.find((e) => e.id === event.edge.id);
  if (edge) emit('select-edge', { source: edge.source, kind: edge.kind, key: edge.key });
}

// A line drawn from a handle ends in one of two ways: on another node's handle
// (`connect`), or anywhere else. Only a drop on the bare canvas asks for a new
// state; vue-flow reports `connect` before `connect-end`, which is how the two
// are told apart.
let dragFrom: string | null = null;
let dragConnected = false;

function onConnectStart(event: { nodeId?: string; handleType?: string }): void {
  dragFrom = event.handleType === 'source' && event.nodeId ? event.nodeId : null;
  dragConnected = false;
}

function onConnect(connection: { source: string; target: string }): void {
  dragConnected = true;
  emit('connect', connection.source, connection.target);
}

function onConnectEnd(event?: MouseEvent | TouchEvent): void {
  const from = dragFrom;
  const connected = dragConnected;
  dragFrom = null;
  dragConnected = false;
  const dropped = event?.target instanceof Element && event.target.closest('.vue-flow__pane') !== null;
  if (from && !connected && dropped) emit('connect', from, null);
}

function toggleDirection(): void {
  direction.value = direction.value === 'TB' ? 'LR' : 'TB';
}
</script>

<template>
  <div class="wf-edit">
    <div class="wf-edit-bar">
      <VButton size="sm" variant="secondary" icon="add" @click="emit('add')">
        {{ t('documents.workflowView.addState') }}
      </VButton>
      <span class="wf-edit-bar-fill" />
      <VButton
        size="sm"
        variant="plain"
        :title="t('documents.workflowView.directionHint')"
        @click="toggleDirection"
      >
        {{ direction === 'TB'
          ? t('documents.workflowView.directionVertical')
          : t('documents.workflowView.directionHorizontal') }}
      </VButton>
    </div>

    <VAlert v-if="graph.problems.length > 0" variant="warning" class="wf-edit-problems">
      <p class="wf-edit-problems-title">{{ t('documents.workflowView.problemsTitle') }}</p>
      <ul>
        <li v-for="problem in graph.problems" :key="problem">{{ problem }}</li>
      </ul>
    </VAlert>

    <div v-if="hasStates" ref="canvas" class="wf-edit-canvas" :style="{ height }">
      <VueFlow
        :nodes="vfNodes"
        :edges="vfEdges"
        :fit-view-on-init="true"
        :nodes-draggable="false"
        :nodes-connectable="true"
        :elements-selectable="false"
        :zoom-on-double-click="false"
        @pane-ready="onPaneReady"
        @node-click="onNodeClick"
        @pane-click="emit('select', null); emit('select-edge', null)"
        @edge-click="onEdgeClick"
        @connect-start="onConnectStart"
        @connect="onConnect"
        @connect-end="onConnectEnd"
      >
        <template #node-state="{ data }">
          <WorkflowNodeCard
            :state="data.state"
            :direction="direction"
            :selected="data.selected"
            connectable
            interactive
          />
        </template>
      </VueFlow>
    </div>
    <p v-else class="wf-edit-empty">{{ t('documents.workflowView.empty') }}</p>
  </div>
</template>

<style scoped>
.wf-edit { display: flex; flex-direction: column; gap: 0.5rem; min-width: 0; }
.wf-edit-bar { display: flex; align-items: center; gap: 0.5rem; }
.wf-edit-bar-fill { flex: 1; }
.wf-edit-problems { font-size: 0.82rem; }
.wf-edit-problems-title { margin: 0 0 0.2rem; font-weight: 600; }
.wf-edit-problems ul { margin: 0; padding-left: 1.1rem; }
.wf-edit-canvas {
  min-height: 320px;
  background: var(--color-base-100);
  border: 1px solid color-mix(in oklab, var(--color-base-content) 15%, transparent);
  border-radius: 0.5rem;
  overflow: hidden;
}
.wf-edit-empty { font-size: 0.85rem; opacity: 0.6; font-style: italic; margin: 0; }
</style>
