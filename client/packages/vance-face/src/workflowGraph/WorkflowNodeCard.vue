<script setup lang="ts">
/**
 * One state of a workflow as a card: name, task type, the type's core detail,
 * and — when a run is laid over the drawing — how the run left the state.
 *
 * Shared by the read-only {@code WorkflowGraph} and by the editor's canvas so
 * both draw a state the same way. It knows nothing about running or editing:
 * the caller hands in what to show and, for an editor, whether the handles
 * accept connections.
 */
import { ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { Handle, Position } from '@vue-flow/core';
import { VIcon } from '@/components';
import type { IconName } from '@vance/components';
import { formatDuration } from '@/util/formatDuration';
import { typeColor } from './flowElements';
import type { GraphDirection } from './layout';
import type { NodeIcon } from './nodeIcon';
import { nodeClasses } from './nodeClasses';
import { taskIcon } from './taskIcon';
import type { GraphStateRun } from './overlay';
import type { WorkflowStateNode } from './workflowGraphCodec';

defineOptions({ name: 'WorkflowNodeCard' });

interface Props {
  state: WorkflowStateNode;
  direction: GraphDirection;
  /** What a run did here; absent for a plain drawing of the definition. */
  run?: GraphStateRun;
  breakpoint?: boolean;
  /** The card looks clickable. */
  interactive?: boolean;
  /** Handles accept new connections (the editor). */
  connectable?: boolean;
  /** The card is the one picked in an inspector. */
  selected?: boolean;
  /** What stands before the type instead of its task icon — a service's logo. */
  icon?: NodeIcon | null;
}
const props = withDefaults(defineProps<Props>(), {
  icon: null,
  run: undefined,
  breakpoint: false,
  interactive: false,
  connectable: false,
  selected: false,
});

const { t } = useI18n();

// A logo that fails to load gives way to the task icon.
const brokenSrc = ref<string | null>(null);
watch(() => props.icon, () => { brokenSrc.value = null; });
function shownIcon(): NodeIcon | null {
  const icon = props.icon;
  if (!icon) return null;
  return icon.kind === 'image' && brokenSrc.value === icon.src ? null : icon;
}

function typeLabel(state: WorkflowStateNode): string {
  if (state.missing) return t('documents.workflowView.undeclared');
  return state.type || t('documents.workflowView.noType');
}

/** Line under the type: how the run left this state, and how often it was here. */
function runLine(run: GraphStateRun): string {
  const parts: string[] = [];
  if (run.simulated) parts.push(t('documents.workflowView.simulated'));
  if (run.outcome) parts.push(run.outcome);
  if (run.durationMs != null) parts.push(formatDuration(run.durationMs));
  if (run.entries > 1) parts.push(`×${run.entries}`);
  return parts.join(' · ');
}
</script>

<template>
  <div
    :class="[...nodeClasses(state, run, breakpoint), { 'wf-node--interactive': interactive, 'wf-node--selected': selected }]"
    :style="{ borderLeftColor: typeColor(state) }"
  >
    <Handle
      type="target"
      :connectable="connectable"
      :position="direction === 'TB' ? Position.Top : Position.Left"
    />
    <div class="wf-node-head">
      <span
        v-if="breakpoint"
        class="wf-breakpoint"
        :title="t('documents.workflowView.breakpoint')"
      ></span>
      <span class="wf-node-name">{{ state.name }}</span>
      <span v-if="state.isStart" class="wf-badge wf-badge--start">
        {{ t('documents.workflowView.startBadge') }}
      </span>
      <span
        v-if="state.hasRetry"
        class="inline-flex shrink-0 opacity-70"
        :title="t('documents.workflowView.retryHint')"
      ><VIcon name="refresh" /></span>
    </div>
    <div class="wf-node-type" :style="{ color: typeColor(state) }">
      <template v-if="!state.missing">
        <img
          v-if="shownIcon()?.kind === 'image'"
          class="wf-node-logo"
          :src="(shownIcon() as { src: string }).src"
          alt=""
          @error="brokenSrc = (icon as { src: string }).src"
        />
        <VIcon v-else-if="shownIcon()?.kind === 'ui'" :name="(shownIcon() as { name: IconName }).name" />
        <VIcon v-else-if="taskIcon(state.type)" :name="taskIcon(state.type)!" />
      </template>
      {{ typeLabel(state) }}
    </div>
    <div v-if="state.detail" class="wf-node-detail" :title="state.detail">
      {{ state.detail }}
    </div>
    <div v-else-if="state.storeAs" class="wf-node-detail">
      <VIcon name="arrow-right" class="inline align-middle" /> {{ state.storeAs }}
    </div>
    <div v-if="run" class="wf-node-run">
      <template v-if="run.tone === 'halted'">{{ t('documents.workflowView.runHalted') }}</template>
      <template v-else-if="run.tone === 'current'">{{ t('documents.workflowView.runCurrent') }}</template>
      <template v-else>{{ runLine(run) }}</template>
    </div>
    <Handle
      v-if="!state.isTerminal"
      type="source"
      :connectable="connectable"
      :position="direction === 'TB' ? Position.Bottom : Position.Right"
    />
  </div>
</template>

<style scoped>
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
  display: flex;
  align-items: center;
  gap: 0.3rem;
  font-size: 0.68rem;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  opacity: 0.85;
}
.wf-node-type :deep(.v-icon) {
  width: 12px;
  height: 12px;
}
.wf-node-logo {
  width: 12px;
  height: 12px;
  flex-shrink: 0;
  object-fit: contain;
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
.wf-node--selected {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 2px color-mix(in oklab, var(--color-primary) 35%, transparent);
}
.wf-node--interactive { cursor: pointer; }
.wf-node--interactive:hover {
  border-color: color-mix(in oklab, var(--color-base-content) 45%, transparent);
}
/* A breakpoint: the run halts in front of this state. */
.wf-breakpoint {
  flex: 0 0 auto;
  width: 0.65rem;
  height: 0.65rem;
  border-radius: 50%;
  background: var(--color-error);
}
.wf-node--breakpoint {
  border-top-color: var(--color-error);
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
