<script setup lang="ts">
/**
 * Draws a Magrathea workflow as a flow diagram — and nothing else.
 *
 * Read-only by construction: this component has no code that changes a
 * workflow, so everything that only reads (the Cortex view tab, the run view)
 * can import it without pulling an editor into its bundle. Editing, when it
 * comes, is a separate component under Cortex that builds on this one.
 *
 * The layout is derived, never authored — there are no stored node positions
 * to preserve, so every render is a fresh Dagre layout. A caller may lay a
 * {@link GraphOverlay} on top (what a run did, state by state); the drawing
 * itself does not change for it.
 *
 * Structural problems the drawing can show (dangling transition, unknown task
 * type, start naming nothing) are listed above the canvas and the offending
 * node is drawn as a ghost. Authoritative validation stays on the server —
 * see the `vance-workflow` KindHandler.
 *
 * Spec: `specification/public/workflows.md` §2.5.
 */
import { computed, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { Handle, Position, VueFlow } from '@vue-flow/core';
import type { Edge, Node } from '@vue-flow/core';
import '@vue-flow/core/dist/style.css';
import '@vue-flow/core/dist/theme-default.css';
import { Graph as DagreGraph, layout as dagreLayout } from '@dagrejs/dagre';
import { VAlert, VButton, VIcon } from '@/components';
import {
  parseWorkflowGraph,
  type WorkflowEdgeKind,
  type WorkflowStateNode,
} from './workflowGraphCodec';
import type { GraphOverlay, GraphStateTone } from './overlay';
import { formatDuration } from '@/util/formatDuration';

defineOptions({ name: 'WorkflowGraph' });

interface Props {
  /** The workflow YAML. */
  doc: string;
  /** What a run did per state; absent for a plain drawing of the definition. */
  overlay?: GraphOverlay;
  /** Canvas height — a view in a tab wants most of the screen, a panel less. */
  height?: string;
  /** List structural problems above the canvas. */
  showProblems?: boolean;
}
const props = withDefaults(defineProps<Props>(), {
  overlay: undefined,
  height: '65vh',
  showProblems: true,
});

const { t } = useI18n();

/** Layout box for one state node — matches the CSS below. */
const NODE_W = 210;
const NODE_H = 74;

/** Top-down reads like a flowchart; wide graphs are easier left-right. */
const direction = ref<'TB' | 'LR'>('TB');

const graph = computed(() => parseWorkflowGraph(props.doc));

const positions = computed<Map<string, { x: number; y: number }>>(() => {
  const out = new Map<string, { x: number; y: number }>();
  const g = graph.value;
  if (g.states.length === 0) return out;

  const dag = new DagreGraph();
  dag.setGraph({
    rankdir: direction.value,
    nodesep: 44,
    ranksep: 78,
    marginx: 24,
    marginy: 24,
  });
  dag.setDefaultEdgeLabel(() => ({}));
  for (const state of g.states) {
    dag.setNode(state.name, { width: NODE_W, height: NODE_H });
  }
  const known = new Set(g.states.map((s) => s.name));
  for (const edge of g.edges) {
    if (known.has(edge.source) && known.has(edge.target)) {
      dag.setEdge(edge.source, edge.target);
    }
  }
  dagreLayout(dag);

  for (const state of g.states) {
    const laid = dag.node(state.name) as { x?: number; y?: number } | undefined;
    if (typeof laid?.x !== 'number' || typeof laid?.y !== 'number') continue;
    // Dagre centres its boxes; vue-flow positions by top-left corner.
    out.set(state.name, { x: laid.x - NODE_W / 2, y: laid.y - NODE_H / 2 });
  }
  return out;
});

const vfNodes = computed<Node[]>(() =>
  graph.value.states.map((state, idx) => ({
    id: state.name,
    type: 'state',
    position: positions.value.get(state.name)
      ?? { x: 40, y: 40 + idx * (NODE_H + 30) },
    data: { state, run: props.overlay?.[state.name] },
    draggable: false,
    selectable: false,
    connectable: false,
  })),
);

const vfEdges = computed<Edge[]>(() =>
  graph.value.edges.map((edge) => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    label: edge.label,
    type: 'smoothstep',
    markerEnd: 'arrowclosed',
    animated: false,
    class: `wf-edge wf-edge--${edge.kind}${edge.dangling ? ' wf-edge--dangling' : ''}`,
    style: edgeStyle(edge.kind, edge.dangling),
    labelBgStyle: { fill: 'var(--color-base-100)' },
    labelStyle: { fontSize: '11px', fill: 'var(--color-base-content)' },
  })),
);

function edgeStyle(kind: WorkflowEdgeKind, dangling: boolean): Record<string, string> {
  if (dangling) return { stroke: 'var(--color-error)', strokeWidth: '1.5', strokeDasharray: '2 3' };
  switch (kind) {
    // `catch:` is the failure lane — dashed and warm, so an eye scanning
    // the diagram separates error routing from the happy path at once.
    case 'catch':
      return { stroke: 'var(--color-warning)', strokeWidth: '1.5', strokeDasharray: '6 4' };
    case 'condition':
      return { stroke: 'var(--color-primary)', strokeWidth: '1.5' };
    case 'else':
      return { stroke: 'var(--color-primary)', strokeWidth: '1.5', strokeDasharray: '6 4' };
    default:
      return { stroke: 'var(--color-base-content)', strokeWidth: '1.5' };
  }
}

/** Colour band per task type — the node's left edge. */
function typeColor(state: WorkflowStateNode): string {
  if (state.missing || !state.knownType) return 'var(--color-error)';
  switch (state.type) {
    case 'agent_task': return 'var(--color-primary)';
    case 'gate_task': return 'var(--color-warning)';
    case 'timer_task': return 'var(--color-info)';
    case 'condition_task': return 'var(--color-secondary)';
    case 'workflow_task': return 'var(--color-accent)';
    case 'terminal':
      return state.terminalOutcome === 'failure'
        ? 'var(--color-error)'
        : 'var(--color-success)';
    default: return 'var(--color-base-content)';
  }
}

function typeLabel(state: WorkflowStateNode): string {
  if (state.missing) return t('documents.workflowView.undeclared');
  return state.type || t('documents.workflowView.noType');
}

const hasStates = computed(() => graph.value.states.length > 0);
const stateCount = computed(() => graph.value.states.filter((s) => !s.missing).length);

function toggleDirection(): void {
  direction.value = direction.value === 'TB' ? 'LR' : 'TB';
}

/** Line under the type: how the run left this state, and how often it was here. */
function runLine(run: { tone: GraphStateTone; entries: number; outcome?: string; durationMs?: number }): string {
  const parts: string[] = [];
  if (run.outcome) parts.push(run.outcome);
  if (run.durationMs != null) parts.push(formatDuration(run.durationMs));
  if (run.entries > 1) parts.push(`×${run.entries}`);
  return parts.join(' · ');
}
</script>

<template>
  <div class="workflow-view">
    <div class="toolbar">
      <span v-if="hasStates" class="summary">
        {{ t('documents.workflowView.summary', {
          states: stateCount,
          edges: graph.edges.length,
        }) }}
      </span>
      <span v-if="graph.start" class="summary summary--muted">
        {{ t('documents.workflowView.startsAt', { state: graph.start }) }}
      </span>
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

    <VAlert v-if="showProblems && graph.problems.length > 0" variant="warning" class="problems">
      <p class="problems-title">{{ t('documents.workflowView.problemsTitle') }}</p>
      <ul>
        <li v-for="problem in graph.problems" :key="problem">{{ problem }}</li>
      </ul>
    </VAlert>

    <div v-if="hasStates" class="canvas" :style="{ height }">
      <VueFlow
        :nodes="vfNodes"
        :edges="vfEdges"
        :fit-view-on-init="true"
        :nodes-draggable="false"
        :nodes-connectable="false"
        :elements-selectable="false"
        :zoom-on-double-click="false"
      >
        <template #node-state="{ data }">
          <div
            :class="['wf-node', {
              'wf-node--start': data.state.isStart,
              'wf-node--missing': data.state.missing,
              [`wf-node--run-${data.run.tone}`]: !!data.run,
            }]"
            :style="{ borderLeftColor: typeColor(data.state) }"
          >
            <Handle type="target" :position="direction === 'TB' ? Position.Top : Position.Left" />
            <div class="wf-node-head">
              <span class="wf-node-name">{{ data.state.name }}</span>
              <span v-if="data.state.isStart" class="wf-badge wf-badge--start">
                {{ t('documents.workflowView.startBadge') }}
              </span>
              <span
                v-if="data.state.hasRetry"
                class="inline-flex shrink-0 opacity-70"
                :title="t('documents.workflowView.retryHint')"
              ><VIcon name="refresh" /></span>
            </div>
            <div class="wf-node-type" :style="{ color: typeColor(data.state) }">
              {{ typeLabel(data.state) }}
            </div>
            <div v-if="data.state.detail" class="wf-node-detail" :title="data.state.detail">
              {{ data.state.detail }}
            </div>
            <div v-else-if="data.state.storeAs" class="wf-node-detail">
              <VIcon name="arrow-right" class="inline align-middle" /> {{ data.state.storeAs }}
            </div>
            <div v-if="data.run" class="wf-node-run">
              <template v-if="data.run.tone === 'halted'">{{ t('documents.workflowView.runHalted') }}</template>
              <template v-else-if="data.run.tone === 'current'">{{ t('documents.workflowView.runCurrent') }}</template>
              <template v-else>{{ runLine(data.run) }}</template>
            </div>
            <Handle
              v-if="!data.state.isTerminal"
              type="source"
              :position="direction === 'TB' ? Position.Bottom : Position.Right"
            />
          </div>
        </template>
      </VueFlow>
    </div>

    <p v-else class="empty">{{ t('documents.workflowView.empty') }}</p>
  </div>
</template>

<style scoped>
.workflow-view {
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
.summary {
  font-size: 0.8rem;
  opacity: 0.75;
}
.summary--muted {
  font-family: ui-monospace, monospace;
  font-size: 0.75rem;
  opacity: 0.55;
}
.toolbar :deep(button) {
  margin-left: auto;
}
.problems {
  font-size: 0.82rem;
}
.problems-title {
  margin: 0 0 0.2rem;
  font-weight: 600;
}
.problems ul {
  margin: 0;
  padding-left: 1.1rem;
}
.canvas {
  min-height: 320px;
  background: var(--color-base-100);
  border: 1px solid color-mix(in oklab, var(--color-base-content) 15%, transparent);
  border-radius: 0.5rem;
  overflow: hidden;
}
.empty {
  font-size: 0.85rem;
  opacity: 0.6;
  font-style: italic;
  margin: 0;
}

/* ── node ─────────────────────────────────────────────────────── */
.wf-node {
  width: 210px;
  min-height: 74px;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  padding: 0.45rem 0.6rem;
  background: var(--color-base-100);
  border: 1px solid color-mix(in oklab, var(--color-base-content) 20%, transparent);
  border-left-width: 4px;
  border-left-style: solid;
  border-radius: 0.4rem;
  text-align: left;
  font-size: 0.8rem;
  cursor: default;
}
.wf-node--start {
  box-shadow: 0 0 0 2px color-mix(in oklab, var(--color-primary) 45%, transparent);
}
.wf-node--missing {
  border-style: dashed;
  opacity: 0.75;
}
.wf-node-head {
  display: flex;
  align-items: center;
  gap: 0.3rem;
}
.wf-node-name {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wf-node-type {
  font-size: 0.68rem;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  opacity: 0.85;
}
.wf-node-detail {
  font-family: ui-monospace, monospace;
  font-size: 0.7rem;
  opacity: 0.65;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wf-badge {
  flex: 0 0 auto;
  font-size: 0.6rem;
  line-height: 1;
  padding: 0.15rem 0.3rem;
  border-radius: 0.2rem;
  background: color-mix(in oklab, var(--color-base-content) 10%, transparent);
  opacity: 0.8;
}
.wf-badge--start {
  background: color-mix(in oklab, var(--color-primary) 20%, transparent);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.wf-node-run {
  margin-top: 0.1rem;
  font-size: 0.7rem;
  font-variant-numeric: tabular-nums;
  opacity: 0.85;
}
.wf-node--run-ok {
  background: color-mix(in oklab, var(--color-success) 12%, var(--color-base-100));
}
.wf-node--run-other {
  background: color-mix(in oklab, var(--color-warning) 16%, var(--color-base-100));
}
.wf-node--run-current {
  box-shadow: 0 0 0 2px color-mix(in oklab, var(--color-info) 70%, transparent);
}
/* Halted: the run stands in front of this state and has not started it. */
.wf-node--run-halted {
  box-shadow: 0 0 0 2px color-mix(in oklab, var(--color-warning) 80%, transparent);
  border-style: dashed;
}
</style>
