<script setup lang="ts">
/**
 * One run in full: where it got to, what it produced, what it started,
 * and how to get out of here to the thing it runs.
 *
 * <p>The blocks below are the ones every source can fill. What only one
 * source has goes through {@link RunExtraBlock}.
 */
import { computed, defineAsyncComponent, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VBadge, VButton, VCheckbox, VIcon, VSelect } from '@/components';
import { VIconButton, VToolbar, type IconName } from '@vance/components';
import { RunAction } from '@vance/generated';
import type { RunDetailDto, RunRestartRequest, RunSimulateRequest } from '@vance/generated';
import RunStatusBadge from './RunStatusBadge.vue';
import RunExtraBlock from './RunExtraBlock.vue';
import SimulateDialog from './SimulateDialog.vue';
import { buildOverlay } from './stepOverlay';
import { canChangeBreakpoints, toggleBreakpoint } from './breakpoints';
import { formatDuration } from '@/util/formatDuration';
import { formatStepTime } from './stepFormat';

const props = defineProps<{ detail: RunDetailDto; projectId: string; acting?: boolean }>();
const emit = defineEmits<{
  (e: 'open-run', runId: string): void;
  (e: 'action', action: string): void;
  (e: 'set-breakpoints', states: string[]): void;
  (e: 'simulate', body: RunSimulateRequest): void;
  (e: 'restart', body: RunRestartRequest): void;
}>();
const { t, locale } = useI18n();

// The drawing pulls in the flow library; it loads only for a run that has a
// plan to draw, so the rest of the page does not wait for it.
const WorkflowGraph = defineAsyncComponent(() => import('@/workflowGraph/WorkflowGraph.vue'));

const overlay = computed(() => buildOverlay(props.detail.steps, props.detail.summary.status));

// A debuggable run that is not over lets the reader click states to halt before them.
const breakpoints = computed(() => props.detail.breakpoints ?? []);
const canMarkBreakpoints = computed(
  () => props.detail.debug && canChangeBreakpoints(props.detail.summary.status));

function onGraphNodeClick(state: string): void {
  emit('set-breakpoints', toggleBreakpoint(breakpoints.value, state));
}

// Simulate is offered while the run is halted in front of a state with something to replace.
const simulateOpen = ref(false);

function onSimulate(body: RunSimulateRequest): void {
  simulateOpen.value = false;
  emit('simulate', body);
}

// An ended run can be run again — from the start, or from a state it entered.
const restartFrom = ref('');
const restartDebug = ref(false);
const restartOptions = computed(() => [
  { value: '', label: t('runs.restart.fromStart') },
  ...(props.detail.restartableStates ?? []).map((state) => ({ value: state, label: state })),
]);

/** Where a retry most likely wants to begin: the step that did not succeed, else the start. */
function suggestedRestartState(): string {
  const entered = new Set(props.detail.restartableStates ?? []);
  const failed = [...(props.detail.steps ?? [])].reverse().find(
    (step) => entered.has(step.name) && step.outcome && step.outcome !== 'success');
  return failed?.name ?? '';
}

watch(() => props.detail.summary.runId, () => {
  restartFrom.value = suggestedRestartState();
  restartDebug.value = false;
}, { immediate: true });

function onRestart(): void {
  emit('restart', {
    fromState: restartFrom.value || undefined,
    debug: restartDebug.value || undefined,
  });
}

// The debugger toolbar always shows every control; the ones the run does not
// offer right now are disabled rather than hidden, so the bar never moves.
const ACTIONS: { action: RunAction; icon: IconName }[] = [
  { action: RunAction.RESUME, icon: 'play' },
  { action: RunAction.PAUSE, icon: 'pause' },
  { action: RunAction.STEP, icon: 'step' },
  { action: RunAction.ABORT, icon: 'abort' },
  { action: RunAction.STOP, icon: 'stop' },
];
const allowed = computed(() => new Set(props.detail.allowedActions ?? []));

// The lower panel holds what is read now and then; only tabs with content show.
type TabId = 'variables' | 'result' | 'children' | 'restart' | 'source';
const tabs = computed<{ id: TabId; label: string }[]>(() => {
  const list: { id: TabId; label: string }[] = [];
  if (variables.value.length) list.push({ id: 'variables', label: t('runs.detail.variables') });
  if (props.detail.result) list.push({ id: 'result', label: t('runs.detail.result') });
  if (props.detail.children?.length) list.push({ id: 'children', label: t('runs.detail.children') });
  list.push({ id: 'source', label: t('runs.detail.source', { source: props.detail.summary.source }) });
  if (props.detail.restartableStates) list.push({ id: 'restart', label: t('runs.restart.title') });
  return list;
});
const chosenTab = ref<TabId>('variables');
const activeTab = computed<TabId>(() =>
  tabs.value.some((tab) => tab.id === chosenTab.value) ? chosenTab.value : tabs.value[0].id);

// A running run whose sub-run stands in the debugger is waiting for it; say so,
// or the parent looks stuck.
const waitingChild = computed(() =>
  props.detail.summary.status === 'RUNNING'
    ? props.detail.children?.find((child) => child.status === 'PAUSED')
    : undefined,
);
// What the run's agents have cost, one amount per currency — never added together.
const costText = computed(() => {
  const amounts = props.detail.cost ?? [];
  if (amounts.length === 0) return '';
  return amounts
    .map((c) => {
      if (!c.currency) return t('runs.detail.costUnpriced', { n: c.unpricedCalls });
      try {
        return new Intl.NumberFormat(locale.value, {
          style: 'currency', currency: c.currency, maximumFractionDigits: 4 }).format(c.amount);
      } catch {
        return `${c.amount.toFixed(4)} ${c.currency}`;
      }
    })
    .join(' + ');
});
const variables = computed(() => Object.entries(props.detail.variables ?? {}));

/** Where a link points, per rel. The run view never renders those itself. */
function href(rel: string, target: string): string {
  const project = encodeURIComponent(props.projectId);
  if (rel === 'restartOf') return `/runs.html?project=${project}&run=${encodeURIComponent(target)}`;
  if (rel === 'session') return `/chat?project=${project}&sessionId=${encodeURIComponent(target)}`;
  // definition / document both open in Cortex by path.
  return `/cortex?project=${project}&path=${encodeURIComponent(target)}`;
}

function stepTone(outcome?: string | null): string {
  if (!outcome) return 'current';
  return outcome === 'success' || outcome === 'done' ? 'ok' : 'other';
}

function render(value: unknown): string {
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2);
}
</script>

<template>
  <div class="ide">
    <!-- One bar, always the same controls: the debugger is used by muscle
         memory, so a control that appears and disappears is a control
         that moves. -->
    <VToolbar>
      <h2 class="ide-title">{{ detail.summary.name }}</h2>
      <RunStatusBadge :status="detail.summary.status" />
      <VBadge v-if="detail.debug" variant="info" size="sm">{{ t('runs.detail.debug') }}</VBadge>
      <template #end>
        <VIconButton
          v-for="entry in ACTIONS"
          :key="entry.action"
          :icon="entry.icon"
          :label="t(`runs.action.${entry.action}`)"
          :danger="entry.action === 'STOP'"
          :disabled="acting || !allowed.has(entry.action)"
          @click="emit('action', entry.action)"
        />
        <span class="ide-sep" />
        <VIconButton
          icon="simulate"
          :label="t('runs.simulate.button')"
          :disabled="acting || !detail.simulation"
          @click="simulateOpen = true"
        />
        <VIconButton
          icon="redo"
          :label="t('runs.restart.title')"
          :disabled="acting || !detail.restartableStates"
          @click="chosenTab = 'restart'"
        />
      </template>
    </VToolbar>
    <SimulateDialog
      v-if="detail.simulation"
      v-model="simulateOpen"
      :simulation="detail.simulation"
      :busy="acting"
      @submit="onSimulate"
    />

    <div class="ide-info">
      <p class="meta">
        <span class="mono">{{ detail.summary.runId }}</span>
        <span v-if="detail.summary.startedBy"> · {{ detail.summary.startedBy }}</span>
        <span v-if="detail.summary.startedAt"> · {{ detail.summary.startedAt }}</span>
        <span v-if="costText" :title="t('runs.detail.costHint')"> · {{ t('runs.detail.cost') }} {{ costText }}</span>
        <a v-for="link in detail.links ?? []" :key="link.rel + link.target"
           :href="href(link.rel, link.target)" class="link inline-flex items-center gap-1">
          {{ t(`runs.link.${link.rel}`) }}<VIcon name="forward" />
        </a>
      </p>
      <VAlert v-if="detail.errorMessage" variant="error">{{ detail.errorMessage }}</VAlert>
      <!-- A halted debug run needs one sentence: the controls look alike. -->
      <VAlert v-if="detail.debug && detail.summary.status === 'PAUSED'" variant="info">
        {{ t('runs.detail.debugHint') }}
      </VAlert>
      <!-- Waiting on a person: the useful action is answering the item, not
           doing anything to the run. -->
      <VAlert v-if="detail.waitingOnInboxItemId" variant="warning" class="waiting">
        {{ t('runs.detail.waitingOnInbox') }}
        <a
          :href="`/inbox?item=${encodeURIComponent(detail.waitingOnInboxItemId)}`"
          class="inline-flex items-center gap-1"
        >
          {{ t('runs.detail.openInbox') }}<VIcon name="forward" />
        </a>
      </VAlert>
    </div>

    <div class="ide-body">
      <!-- The plan this run froze at its start, not the document as it reads
           now — so the picture stays true after the document is edited. -->
      <section class="pane pane-flow">
        <WorkflowGraph
          v-if="detail.definition"
          :doc="detail.definition"
          :overlay="overlay"
          fill
          :show-problems="false"
          :breakpoints="breakpoints"
          :interactive="canMarkBreakpoints"
          @node-click="onGraphNodeClick"
        />
        <p v-if="canMarkBreakpoints" class="plan-hint">{{ t('runs.detail.breakpointHint') }}</p>
      </section>

      <section class="pane pane-steps">
        <h3 class="pane-title">{{ t('runs.detail.steps') }}</h3>
        <ol v-if="detail.steps?.length" class="run-steps">
          <li v-for="(step, i) in detail.steps" :key="i"
              :class="['run-step', `run-step--${stepTone(step.outcome)}`]">
            <span class="step-name">{{ step.name }}</span>
            <span v-if="step.kind" class="step-kind">{{ step.kind }}</span>
            <span v-if="step.outcome" class="step-outcome">{{ step.outcome }}</span>
            <span v-if="step.simulated" class="step-simulated">{{ t('runs.detail.simulated') }}</span>
            <span v-if="step.at" class="step-time">{{ formatStepTime(step.at, locale) }}</span>
            <span v-if="step.durationMs != null" class="step-time">{{ formatDuration(step.durationMs) }}</span>
            <span v-if="step.detail" class="step-detail">{{ step.detail }}</span>
            <details v-if="step.output" class="step-output">
              <summary>{{ t('runs.detail.stepOutput') }}</summary>
              <pre>{{ step.output }}</pre>
              <p v-if="step.outputTruncated" class="step-truncated">{{ t('runs.detail.outputTruncated') }}</p>
            </details>
          </li>
        </ol>
      </section>

      <section class="pane pane-bottom">
        <div class="tablist" role="tablist">
          <button
            v-for="tab in tabs"
            :key="tab.id"
            type="button"
            role="tab"
            :aria-selected="activeTab === tab.id"
            :class="['tab', { 'tab--active': activeTab === tab.id }]"
            @click="chosenTab = tab.id"
          >{{ tab.label }}</button>
        </div>
        <div class="tabpanel">
          <dl v-if="activeTab === 'variables'" class="vars">
            <template v-for="[key, value] in variables" :key="key">
              <dt>{{ key }}</dt>
              <dd><pre>{{ render(value) }}</pre></dd>
            </template>
          </dl>

          <pre v-else-if="activeTab === 'result'" class="result">{{ render(detail.result) }}</pre>

          <div v-else-if="activeTab === 'children'">
            <p v-if="waitingChild" class="child-waiting">
              {{ t('runs.detail.childWaiting') }}
              <VButton size="sm" variant="plain" @click="emit('open-run', waitingChild.runId)">
                {{ waitingChild.name || waitingChild.runId }}
              </VButton>
            </p>
            <ul class="children">
              <li v-for="child in detail.children" :key="child.runId">
                <VButton size="sm" variant="plain" @click="emit('open-run', child.runId)">
                  {{ child.name || child.runId }}
                </VButton>
                <RunStatusBadge v-if="child.status" :status="child.status" />
                <span v-if="child.fromStep" class="from-step">{{ child.fromStep }}</span>
              </li>
            </ul>
          </div>

          <div v-else-if="activeTab === 'restart'" class="restart">
            <VSelect v-model="restartFrom" :label="t('runs.restart.from')" :options="restartOptions" />
            <VCheckbox v-model="restartDebug" :label="t('runs.restart.debug')" />
            <p class="restart-hint">{{ t('runs.restart.hint') }}</p>
            <VButton size="sm" :disabled="acting" @click="onRestart">{{ t('runs.restart.submit') }}</VButton>
          </div>

          <RunExtraBlock v-else :source="detail.summary.source" :extra="detail.extra" />
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* IDE frame: toolbar on top, then the flow on the left, the steps on the
   right and a tabbed panel under the flow. Every pane scrolls on its own;
   the page never does. */
.ide { display: flex; flex-direction: column; height: 100%; min-height: 0; }
.ide-title { margin: 0; font-size: 0.95rem; font-weight: 600; }
.ide-sep { width: 1px; height: 1.25rem; background: var(--v-separator); margin: 0 0.25rem; }
.ide-info { padding: 0.4rem 0.75rem 0; display: flex; flex-direction: column; gap: 0.4rem; }
.ide-info:empty { display: none; }
.ide-body {
  flex: 1 1 0; min-height: 0; display: grid; gap: 1px;
  grid-template-columns: minmax(0, 1fr) minmax(16rem, 26rem);
  grid-template-rows: minmax(0, 1fr) minmax(8rem, 16rem);
  grid-template-areas: 'flow steps' 'bottom steps';
  background: var(--v-separator);
}
.pane { background: var(--v-surface); min-width: 0; min-height: 0; }
.pane-flow { grid-area: flow; display: flex; flex-direction: column; padding: 0.5rem; }
.pane-steps { grid-area: steps; overflow-y: auto; padding: 0.5rem 0.75rem; }
.pane-bottom { grid-area: bottom; display: flex; flex-direction: column; }
.pane-title { margin: 0 0 0.4rem; font-size: 0.72rem; text-transform: uppercase; letter-spacing: 0.04em; opacity: 0.6; }
.tablist { display: flex; gap: 0.25rem; padding: 0 0.5rem; border-bottom: 1px solid var(--v-separator); }
.tab {
  padding: 0.4rem 0.6rem; font-size: 0.8rem; background: transparent; border: 0;
  border-bottom: 2px solid transparent; cursor: pointer; opacity: 0.65;
}
.tab:hover { opacity: 1; }
.tab--active { opacity: 1; border-bottom-color: var(--color-primary); font-weight: 600; }
.tabpanel { flex: 1 1 0; min-height: 0; overflow: auto; padding: 0.5rem 0.75rem; }
@media (max-width: 60rem) {
  .ide-body {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: minmax(16rem, 1fr) auto minmax(8rem, 16rem);
    grid-template-areas: 'flow' 'steps' 'bottom';
    overflow-y: auto;
  }
}
.meta { margin: 0; font-size: 0.75rem; opacity: 0.75; display: flex; flex-wrap: wrap; gap: 0 0.25rem; align-items: baseline; }
.meta .link { margin-left: 0.5rem; }
.mono { font-family: ui-monospace, monospace; }
.link { font-size: 0.75rem; text-decoration: underline; }
.waiting { font-size: 0.85rem; }
.waiting a { text-decoration: underline; margin-left: 0.4rem; }
.plan-hint { margin: 0.4rem 0 0; font-size: 0.78rem; opacity: 0.65; }
.run-steps { margin: 0; padding-left: 1.1rem; display: flex; flex-direction: column; gap: 0.25rem; }
.run-step { font-size: 0.85rem; display: flex; gap: 0.5rem; flex-wrap: wrap; align-items: baseline; }
.step-name { font-weight: 600; }
.step-kind { font-size: 0.68rem; text-transform: uppercase; opacity: 0.5; }
.step-outcome { font-size: 0.72rem; opacity: 0.8; }
.run-step--ok .step-outcome { color: var(--color-success); }
.run-step--other .step-outcome { color: var(--color-warning); }
.run-step--current .step-name::after { content: ' …'; opacity: 0.6; }
.step-simulated {
  font-size: 0.68rem; text-transform: uppercase; letter-spacing: 0.04em;
  padding: 0 0.3rem; border-radius: 0.2rem;
  background: color-mix(in oklab, var(--color-info) 18%, transparent);
}
.step-detail { font-size: 0.75rem; opacity: 0.7; flex-basis: 100%; }
.step-time { font-size: 0.72rem; opacity: 0.55; font-variant-numeric: tabular-nums; }
.step-output { flex-basis: 100%; font-size: 0.75rem; }
.step-output summary { cursor: pointer; opacity: 0.7; }
.step-output pre {
  margin: 0.25rem 0 0; max-height: 18rem; overflow: auto; white-space: pre-wrap;
  word-break: break-word; font-family: ui-monospace, monospace;
}
.step-truncated { margin: 0.2rem 0 0; opacity: 0.55; }
.vars { margin: 0; display: grid; grid-template-columns: max-content 1fr; gap: 0.2rem 0.75rem; }
.vars dt { font-size: 0.72rem; font-family: ui-monospace, monospace; opacity: 0.7; }
.vars dd { margin: 0; min-width: 0; }
.vars pre, .result {
  margin: 0; font-size: 0.75rem; white-space: pre-wrap; word-break: break-word;
  font-family: ui-monospace, monospace;
}
.children { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 0.2rem; }
.children li { display: flex; align-items: center; gap: 0.5rem; }
.restart { display: flex; flex-direction: column; gap: 0.6rem; align-items: flex-start; }
.restart-hint { margin: 0; font-size: 0.8rem; opacity: 0.7; }
.child-waiting { margin: 0 0 0.5rem; font-size: 0.85rem; }
.from-step { font-size: 0.72rem; opacity: 0.55; font-family: ui-monospace, monospace; }
</style>
