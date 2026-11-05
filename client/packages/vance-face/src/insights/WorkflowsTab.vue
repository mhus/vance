<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type {
  MagratheaProcessDto,
  MagratheaWorkflowSource,
  MagratheaWorkflowSummary,
} from '@vance/generated';
import {
  VAlert,
  VButton,
  VCard,
  VEmptyState,
  VIcon,
} from '@/components';
import { useWorkflows } from '@/composables/useWorkflows';

const props = defineProps<{ projectId: string | null }>();
const { t } = useI18n();

const state = useWorkflows();

const selectedName = ref<string | null>(null);
type SubTab = 'definition' | 'runs';
const subTab = ref<SubTab>('definition');

watch(
  () => props.projectId,
  (next) => {
    selectedName.value = null;
    subTab.value = 'definition';
    state.clearCurrent();
    if (next) {
      void state.loadProject(next);
    } else {
      state.workflows.value = [];
    }
  },
  { immediate: true },
);

async function selectWorkflow(name: string): Promise<void> {
  if (!props.projectId) return;
  selectedName.value = name;
  subTab.value = 'definition';
  await state.loadOne(props.projectId, name);
  // Eagerly load runs too — counter in the tab label needs them.
  await state.loadRuns(props.projectId, name);
}

/**
 * Starting a run is the run view's job — parameters, debug and stepping are
 * there. This tab only knows the workflow's name, so it hands that over.
 */
function runViewHref(name: string): string {
  return `/runs.html?project=${encodeURIComponent(props.projectId ?? '')}`
    + `&workflow=${encodeURIComponent(name)}`;
}

function sourceLabel(source: MagratheaWorkflowSource | string): string {
  return String(source) === 'PROJECT' ? 'project' : '_vance';
}

function sourceClass(source: MagratheaWorkflowSource | string): string {
  return String(source) === 'PROJECT'
    ? 'badge-source badge-source--project'
    : 'badge-source badge-source--vance';
}

/** Deep link into the run view for one Magrathea run. */
function runsHref(run: { workflowRunId: string; projectId?: string | null }): string {
  const project = run.projectId ?? props.projectId ?? '';
  return `/runs.html?project=${encodeURIComponent(project)}`
    + `&run=${encodeURIComponent('workflow:' + run.workflowRunId)}`;
}

function runStatusClass(status: unknown): string {
  switch (String(status)) {
    case 'DONE': return 'badge-run badge-run--done';
    case 'FAILED': return 'badge-run badge-run--failed';
    case 'TERMINATED': return 'badge-run badge-run--terminated';
    case 'PAUSED': return 'badge-run badge-run--paused';
    case 'RUNNING':
    default:
      return 'badge-run badge-run--running';
  }
}

const detail = computed(() => state.current.value);

const sortedWorkflows = computed<MagratheaWorkflowSummary[]>(() =>
  [...state.workflows.value].sort((a, b) =>
    (a.name ?? '').localeCompare(b.name ?? ''),
  ),
);

/**
 * Number of runs currently in {@code RUNNING} status. Drives the
 * "Runs (N running)" badge in the sub-tab label so the operator
 * doesn't have to switch tabs to know whether something is live.
 */
const runningCount = computed<number>(() =>
  state.runs.value.filter((r) => String(r.status) === 'RUNNING').length,
);

const runsTabLabel = computed<string>(() => {
  const total = state.runs.value.length;
  if (runningCount.value > 0) {
    return t('insights.workflows.runsTabWithRunning', {
      total,
      running: runningCount.value,
    });
  }
  return t('insights.workflows.runsTab', { total });
});

async function refreshRuns(): Promise<void> {
  if (!props.projectId || !detail.value) return;
  await state.loadRuns(props.projectId, detail.value.name);
}

function fmt(value: unknown): string {
  if (value == null) return '—';
  if (value instanceof Date) return value.toISOString();
  return String(value);
}

function processSelectHandler(run: MagratheaProcessDto): void {
  // No-op for now — clicking a run could open a journal modal later.
  // Keep the click handler in place so callers wire it without a re-fetch.
  void run;
}
</script>

<template>
  <div class="flex flex-col gap-3 p-2">
    <div v-if="!projectId" class="opacity-60 text-sm">
      {{ $t('insights.workflows.pickProject') }}
    </div>

    <template v-else>
      <VAlert v-if="state.error.value" variant="error">
        <span>{{ state.error.value }}</span>
      </VAlert>

      <div v-if="state.loading.value" class="text-sm opacity-60">
        {{ $t('insights.workflows.loading') }}
      </div>

      <VEmptyState
        v-else-if="state.workflows.value.length === 0"
        :headline="$t('insights.workflows.emptyHeadline')"
        :body="$t('insights.workflows.emptyBody')"
      />

      <div v-else class="grid grid-cols-12 gap-3">
        <!-- ─── List (left) ─── -->
        <nav class="col-span-5 flex flex-col gap-1">
          <button
            v-for="wf in sortedWorkflows"
            :key="wf.name"
            type="button"
            class="wf-row"
            :class="{ 'wf-row--active': selectedName === wf.name }"
            @click="selectWorkflow(wf.name)"
          >
            <div class="flex items-center justify-between gap-2">
              <span class="font-mono text-sm truncate">{{ wf.name }}</span>
              <span :class="sourceClass(wf.source)" class="text-xs">
                {{ sourceLabel(wf.source) }}
              </span>
            </div>
            <div class="text-xs opacity-60 truncate mt-0.5">
              {{ $t('insights.workflows.summaryLine', {
                params: wf.paramCount,
                states: wf.stateCount,
              }) }}
              <span v-if="wf.version"> · v{{ wf.version }}</span>
            </div>
            <div
              v-if="wf.description"
              class="text-xs opacity-60 truncate mt-0.5"
              :title="wf.description"
            >
              {{ wf.description }}
            </div>
          </button>
        </nav>

        <!-- ─── Detail (right) ─── -->
        <div class="col-span-7 flex flex-col gap-3">
          <VEmptyState
            v-if="!detail"
            :headline="$t('insights.workflows.selectHeadline')"
            :body="$t('insights.workflows.selectBody')"
          />

          <template v-else>
            <div class="sub-tab-bar">
              <button
                class="sub-tab"
                :class="{ 'sub-tab--active': subTab === 'definition' }"
                @click="subTab = 'definition'"
              >{{ $t('insights.workflows.definitionTab') }}</button>
              <button
                class="sub-tab"
                :class="{ 'sub-tab--active': subTab === 'runs' }"
                @click="subTab = 'runs'"
              >{{ runsTabLabel }}</button>
            </div>

            <!-- ─── Definition sub-tab ─── -->
            <template v-if="subTab === 'definition'">
              <VCard :title="detail.name">
                <dl class="grid grid-cols-3 gap-x-3 gap-y-1 text-sm">
                  <dt class="opacity-60 col-span-1">
                    {{ $t('insights.workflows.detail.start') }}
                  </dt>
                  <dd class="col-span-2 font-mono">{{ detail.start ?? '—' }}</dd>

                  <dt class="opacity-60 col-span-1">
                    {{ $t('insights.workflows.detail.source') }}
                  </dt>
                  <dd class="col-span-2">{{ sourceLabel(detail.source) }}</dd>

                  <template v-if="detail.version">
                    <dt class="opacity-60 col-span-1">
                      {{ $t('insights.workflows.detail.version') }}
                    </dt>
                    <dd class="col-span-2">{{ detail.version }}</dd>
                  </template>

                  <template v-if="detail.description">
                    <dt class="opacity-60 col-span-1">
                      {{ $t('insights.workflows.detail.description') }}
                    </dt>
                    <dd class="col-span-2">{{ detail.description }}</dd>
                  </template>

                  <template v-if="detail.allowedTools && detail.allowedTools.length > 0">
                    <dt class="opacity-60 col-span-1">
                      {{ $t('insights.workflows.detail.allowedTools') }}
                    </dt>
                    <dd class="col-span-2 text-xs">
                      <span
                        v-for="tool in detail.allowedTools"
                        :key="tool"
                        class="inline-block mr-1 px-1.5 py-0.5 rounded bg-base-300/50 font-mono"
                      >{{ tool }}</span>
                    </dd>
                  </template>
                </dl>

                <details v-if="detail.yaml" class="mt-3">
                  <summary class="text-xs opacity-70 cursor-pointer">
                    {{ $t('insights.workflows.detail.rawYaml') }}
                  </summary>
                  <pre class="json-block">{{ detail.yaml }}</pre>
                </details>
              </VCard>

              <!-- Starting happens in the run view; this card is the way over. -->
              <VCard :title="$t('insights.workflows.trigger.title')">
                <p class="text-xs opacity-70 mb-2">
                  {{ $t('insights.workflows.trigger.help') }}
                </p>
                <a :href="runViewHref(detail.name)" class="inline-flex items-center gap-1 underline text-sm">
                  {{ $t('insights.workflows.trigger.button') }}<VIcon name="forward" />
                </a>
              </VCard>
            </template>

            <!-- ─── Runs sub-tab ─── -->
            <template v-else>
              <VCard :title="$t('insights.workflows.runs.title')">
                <div class="flex items-center justify-between mb-2">
                  <span class="text-xs opacity-70">
                    {{ $t('insights.workflows.runs.subtitle', {
                      total: state.runs.value.length,
                    }) }}
                  </span>
                  <VButton
                    variant="plain"
                    size="sm"
                    icon="refresh"
                    @click="refreshRuns"
                  >{{ $t('insights.workflows.runs.refresh') }}</VButton>
                </div>

                <VEmptyState
                  v-if="state.runs.value.length === 0"
                  :headline="$t('insights.workflows.runs.emptyHeadline')"
                  :body="$t('insights.workflows.runs.emptyBody')"
                />

                <ul v-else class="flex flex-col divide-y divide-base-300">
                  <li
                    v-for="run in state.runs.value"
                    :key="run.workflowRunId"
                    class="py-2 px-2 hover:bg-base-200/40 rounded cursor-default"
                    @click="processSelectHandler(run)"
                  >
                    <div class="flex items-center justify-between gap-2">
                      <span class="font-mono text-sm">{{ run.workflowRunId }}</span>
                      <div class="flex items-center gap-2">
                        <!-- This tab lists definitions and their runs; the
                             run view is where a single run can be followed
                             across every source. -->
                        <a
                          :href="runsHref(run)"
                          class="text-xs underline opacity-70 hover:opacity-100 inline-flex items-center gap-1"
                          @click.stop
                        >{{ $t('insights.workflows.runs.openInRunView') }}<VIcon name="forward" /></a>
                        <span :class="runStatusClass(run.status)" class="text-xs">
                          {{ run.status }}
                        </span>
                      </div>
                    </div>
                    <div class="text-xs opacity-60 mt-0.5 flex flex-wrap gap-x-3">
                      <span v-if="run.currentState">
                        {{ $t('insights.workflows.runs.state') }}
                        <span class="font-mono">{{ run.currentState }}</span>
                      </span>
                      <span v-if="run.startedBy">
                        {{ $t('insights.workflows.runs.startedBy') }}
                        {{ run.startedBy }}
                      </span>
                      <span v-if="run.createdAt">
                        {{ $t('insights.workflows.runs.startedAt') }}
                        {{ fmt(run.createdAt) }}
                      </span>
                    </div>
                  </li>
                </ul>
              </VCard>
            </template>
          </template>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.wf-row {
  display: block;
  text-align: left;
  padding: 0.5rem 0.75rem;
  border-radius: 0.375rem;
  background: transparent;
  cursor: pointer;
  border: 1px solid transparent;
}
.wf-row:hover { background: color-mix(in oklab, var(--color-base-content) 6%, transparent); }
.wf-row--active {
  background: color-mix(in oklab, var(--color-primary) 12%, transparent);
  border-color: color-mix(in oklab, var(--color-primary) 30%, transparent);
}

.badge-source {
  padding: 0 0.4rem;
  border-radius: 0.25rem;
  font-family: ui-monospace, monospace;
}
.badge-source--project {
  background: color-mix(in oklab, var(--color-primary) 18%, transparent);
  color: var(--color-primary);
}
.badge-source--vance {
  background: color-mix(in oklab, var(--color-base-content) 12%, transparent);
  color: color-mix(in oklab, var(--color-base-content) 70%, transparent);
}

.sub-tab-bar {
  display: flex;
  gap: 0.25rem;
  border-bottom: 1px solid color-mix(in oklab, var(--color-base-content) 15%, transparent);
  padding-bottom: 0.25rem;
}
.sub-tab {
  padding: 0.3rem 0.7rem;
  border-radius: 0.375rem;
  background: transparent;
  border: 1px solid transparent;
  font-size: 0.85rem;
  cursor: pointer;
}
.sub-tab:hover { background: color-mix(in oklab, var(--color-base-content) 6%, transparent); }
.sub-tab--active {
  background: color-mix(in oklab, var(--color-primary) 12%, transparent);
  border-color: color-mix(in oklab, var(--color-primary) 30%, transparent);
  font-weight: 600;
}

.badge-run {
  padding: 0 0.4rem;
  border-radius: 0.25rem;
  font-family: ui-monospace, monospace;
}
.badge-run--running    { background: color-mix(in oklab, var(--color-info) 18%, transparent); color: var(--color-info); }
.badge-run--done       { background: color-mix(in oklab, var(--color-success) 18%, transparent); color: var(--color-success); }
.badge-run--failed     { background: color-mix(in oklab, var(--color-error) 18%, transparent); color: var(--color-error); }
.badge-run--terminated { background: color-mix(in oklab, var(--color-base-content) 12%, transparent); color: color-mix(in oklab, var(--color-base-content) 70%, transparent); }
.badge-run--paused     { background: color-mix(in oklab, var(--color-warning) 18%, transparent); color: var(--color-warning); }

.json-block {
  background: color-mix(in oklab, var(--color-base-content) 5%, transparent);
  padding: 0.5rem 0.75rem;
  border-radius: 0.375rem;
  font-size: 0.8125rem;
  white-space: pre-wrap;
  word-break: break-word;
  margin-top: 0.25rem;
}

.link {
  color: var(--color-primary);
  text-decoration: underline;
  background: transparent;
  cursor: pointer;
  font-family: ui-monospace, monospace;
  font-size: 0.85rem;
}
</style>
