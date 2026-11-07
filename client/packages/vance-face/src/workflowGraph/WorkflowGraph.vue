<script setup lang="ts">
/**
 * Draws a Magrathea workflow as a flow diagram — and nothing else.
 *
 * Read-only by construction: this component has no code that changes a
 * workflow, so everything that only reads (the Cortex view tab, the run view)
 * can import it without pulling an editor into its bundle. Editing, when it
 * comes, is a separate component under Cortex that shares this one's building
 * blocks (layout, node card, flow elements) instead of extending it.
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
import { VueFlow } from '@vue-flow/core';
import '@vue-flow/core/dist/style.css';
import '@vue-flow/core/dist/theme-default.css';
import { VAlert, VButton } from '@/components';
import type { NodeIcon } from './nodeIcon';
import { toFlowEdges, toFlowNodes, toFrameNodes } from './flowElements';
import { useFitOnResize } from './useFitOnResize';
import { layoutGraph, type GraphDirection } from './layout';
import { parseWorkflowGraph } from './workflowGraphCodec';
import type { GraphOverlay } from './overlay';
import LoopFrame from './LoopFrame.vue';
import WorkflowNodeCard from './WorkflowNodeCard.vue';

defineOptions({ name: 'WorkflowGraph' });

interface Props {
  /** The workflow YAML. */
  doc: string;
  /** What a run did per state; absent for a plain drawing of the definition. */
  overlay?: GraphOverlay;
  /** Canvas height — a view in a tab wants most of the screen, a panel less. */
  height?: string;
  /** Take all the height the parent gives (the parent must have a definite one); overrides {@link height}. */
  fill?: boolean;
  /** List structural problems above the canvas. */
  showProblems?: boolean;
  /** States marked with a breakpoint. The drawing only shows them; setting them is the caller's. */
  breakpoints?: string[];
  /** State name → the icon to show instead of its task icon (a tool's service logo). */
  icons?: Record<string, NodeIcon>;
  /** Nodes answer a click (event `node-click`) and look clickable. */
  interactive?: boolean;
  /** The state to mark as picked; the caller owns the selection. */
  selected?: string | null;
}
const props = withDefaults(defineProps<Props>(), {
  overlay: undefined,
  height: '65vh',
  showProblems: true,
  breakpoints: () => [],
  icons: () => ({}),
  interactive: false,
  fill: false,
  selected: null,
});
const emit = defineEmits<{ (e: 'node-click', state: string): void }>();

const { t } = useI18n();

const canvas = ref<HTMLElement | null>(null);
const { onPaneReady } = useFitOnResize(canvas);

const direction = ref<GraphDirection>('TB');

const graph = computed(() => parseWorkflowGraph(props.doc));
const layout = computed(() => layoutGraph(graph.value, direction.value));
const positions = computed(() => layout.value.positions);

const vfNodes = computed(() => [
  ...toFrameNodes(layout.value.frames),
  ...toFlowNodes(
    graph.value,
    positions.value,
    (state) => ({
      run: props.overlay?.[state.name],
      breakpoint: props.breakpoints.includes(state.name),
      icon: props.icons[state.name] ?? null,
      selected: props.selected === state.name,
    }),
    { draggable: false, selectable: false, connectable: false },
  ),
]);
const vfEdges = computed(() => toFlowEdges(graph.value.edges));

const hasStates = computed(() => graph.value.states.length > 0);
const stateCount = computed(() => graph.value.states.filter((s) => !s.missing).length);

function onNodeClick(event: { node: { id: string } }): void {
  const state = graph.value.states.find((s) => s.name === event.node.id);
  if (props.interactive && state && !state.missing) emit('node-click', event.node.id);
}

function toggleDirection(): void {
  direction.value = direction.value === 'TB' ? 'LR' : 'TB';
}
</script>

<template>
  <div :class="['workflow-view', { 'workflow-view--fill': fill }]">
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

    <div
      v-if="hasStates"
      ref="canvas"
      :class="['canvas', { 'canvas--fill': fill }]"
      :style="fill ? undefined : { height }"
    >
      <VueFlow
        :nodes="vfNodes"
        :edges="vfEdges"
        :fit-view-on-init="true"
        :nodes-draggable="false"
        :nodes-connectable="false"
        :elements-selectable="false"
        :zoom-on-double-click="false"
        @pane-ready="onPaneReady"
        @node-click="onNodeClick"
      >
        <template #node-frame="{ data }">
          <LoopFrame :loop="data.loop" />
        </template>
        <template #node-state="{ data }">
          <WorkflowNodeCard
            :state="data.state"
            :run="data.run"
            :breakpoint="data.breakpoint"
            :icon="data.icon"
            :direction="direction"
            :interactive="interactive"
            :selected="data.selected"
          />
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
.canvas.canvas--fill { flex: 1 1 0; min-height: 0; }
.workflow-view--fill { height: 100%; }
.empty {
  font-size: 0.85rem;
  opacity: 0.6;
  font-style: italic;
  margin: 0;
}
</style>
