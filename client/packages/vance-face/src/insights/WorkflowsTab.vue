<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type {
  HactarParameterDto,
  HactarProcessDto,
  HactarWorkflowSource,
  HactarWorkflowSummary,
} from '@vance/generated';
import {
  VAlert,
  VButton,
  VCard,
  VCheckbox,
  VEmptyState,
  VInput,
  VTextarea,
} from '@/components';
import { useWorkflows } from '@/composables/useWorkflows';

const props = defineProps<{ projectId: string | null }>();
const { t } = useI18n();

const state = useWorkflows();

const selectedName = ref<string | null>(null);
type SubTab = 'definition' | 'runs';
const subTab = ref<SubTab>('definition');

/**
 * Per-parameter input state. Keyed by parameter name; values are
 * always strings in the UI and parsed against the schema on submit
 * (integer/boolean coercion + JSON parse for object/array).
 */
const paramInputs = ref<Record<string, string>>({});
const paramBooleans = ref<Record<string, boolean>>({});

/** Trigger result / error displayed in the Definition tab. */
const triggerError = ref<string | null>(null);

watch(
  () => props.projectId,
  (next) => {
    selectedName.value = null;
    subTab.value = 'definition';
    state.clearCurrent();
    state.clearLastResult();
    triggerError.value = null;
    paramInputs.value = {};
    paramBooleans.value = {};
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
  state.clearLastResult();
  triggerError.value = null;
  paramInputs.value = {};
  paramBooleans.value = {};
  await state.loadOne(props.projectId, name);
  // Eagerly load runs too — counter in the tab label needs them.
  await state.loadRuns(props.projectId, name);
  // Seed param inputs with defaults so the form is pre-populated.
  const params = state.current.value?.parameters;
  if (params) {
    for (const [key, spec] of Object.entries(params)) {
      if (spec.type === 'boolean') {
        paramBooleans.value[key] = Boolean(spec.defaultValue ?? false);
      } else if (spec.defaultValue != null) {
        paramInputs.value[key] = typeof spec.defaultValue === 'string'
          ? spec.defaultValue
          : JSON.stringify(spec.defaultValue);
      } else {
        paramInputs.value[key] = '';
      }
    }
  }
}

function sourceLabel(source: HactarWorkflowSource | string): string {
  return String(source) === 'PROJECT' ? 'project' : '_vance';
}

function sourceClass(source: HactarWorkflowSource | string): string {
  return String(source) === 'PROJECT'
    ? 'badge-source badge-source--project'
    : 'badge-source badge-source--vance';
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

const sortedWorkflows = computed<HactarWorkflowSummary[]>(() =>
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

const paramEntries = computed<Array<[string, HactarParameterDto]>>(() => {
  const params = detail.value?.parameters;
  if (!params) return [];
  return Object.entries(params);
});

/**
 * Parses a param value from the form input. Throws with a
 * field-context message on coercion failure so the user sees which
 * field is bad. Returns {@code undefined} for an empty optional —
 * lets the caller omit the key entirely.
 */
function parseParamValue(
  key: string,
  spec: HactarParameterDto,
): unknown {
  if (spec.type === 'boolean') {
    return paramBooleans.value[key] ?? false;
  }
  const raw = (paramInputs.value[key] ?? '').trim();
  if (raw === '') {
    if (spec.required) {
      throw new Error(t('insights.workflows.paramRequired', { key }));
    }
    return undefined;
  }
  switch (spec.type) {
    case 'integer': {
      const n = Number.parseInt(raw, 10);
      if (Number.isNaN(n)) {
        throw new Error(t('insights.workflows.paramNotInteger', { key, raw }));
      }
      return n;
    }
    case 'object':
    case 'array': {
      try {
        return JSON.parse(raw);
      } catch (e) {
        throw new Error(t('insights.workflows.paramNotJson', {
          key,
          error: e instanceof Error ? e.message : String(e),
        }));
      }
    }
    default:
      return raw;
  }
}

async function onTrigger(): Promise<void> {
  if (!props.projectId || !detail.value) return;
  triggerError.value = null;

  // Walk the parameter schema; an unparseable required field is fatal.
  const collected: Record<string, unknown> = {};
  try {
    for (const [key, spec] of paramEntries.value) {
      const v = parseParamValue(key, spec);
      if (v !== undefined) collected[key] = v;
    }
  } catch (e) {
    triggerError.value = e instanceof Error ? e.message : String(e);
    return;
  }

  try {
    await state.start(props.projectId, detail.value.name, collected);
    // Refresh the runs list so the new run appears under the Runs tab.
    await state.loadRuns(props.projectId, detail.value.name);
  } catch (e) {
    triggerError.value =
      e instanceof Error ? e.message : t('insights.workflows.triggerGenericError');
  }
}

async function refreshRuns(): Promise<void> {
  if (!props.projectId || !detail.value) return;
  await state.loadRuns(props.projectId, detail.value.name);
}

function fmt(value: unknown): string {
  if (value == null) return '—';
  if (value instanceof Date) return value.toISOString();
  return String(value);
}

function paramFieldType(spec: HactarParameterDto): 'text' | 'integer' | 'boolean' | 'json' {
  switch (spec.type) {
    case 'integer': return 'integer';
    case 'boolean': return 'boolean';
    case 'object':
    case 'array':
      return 'json';
    default:
      return 'text';
  }
}

function processSelectHandler(run: HactarProcessDto): void {
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

              <!-- Trigger panel — present for every workflow. -->
              <VCard :title="$t('insights.workflows.trigger.title')">
                <p class="text-xs opacity-70 mb-2">
                  {{ $t('insights.workflows.trigger.help') }}
                </p>

                <div v-if="paramEntries.length === 0" class="text-xs opacity-60 italic">
                  {{ $t('insights.workflows.trigger.noParams') }}
                </div>

                <div v-else class="flex flex-col gap-2">
                  <template
                    v-for="[key, spec] in paramEntries"
                    :key="key"
                  >
                    <!-- boolean → checkbox -->
                    <VCheckbox
                      v-if="paramFieldType(spec) === 'boolean'"
                      v-model="paramBooleans[key]"
                      :label="`${key}${spec.required ? ' *' : ''}`"
                    />
                    <!-- json (object / array) → multi-line textarea -->
                    <VTextarea
                      v-else-if="paramFieldType(spec) === 'json'"
                      v-model="paramInputs[key]"
                      :label="`${key}${spec.required ? ' *' : ''} (${spec.type})`"
                      :rows="4"
                      class="font-mono"
                    />
                    <!-- string / integer → single-line input -->
                    <VInput
                      v-else
                      v-model="paramInputs[key]"
                      :label="`${key}${spec.required ? ' *' : ''} (${spec.type})`"
                      :placeholder="
                        spec.defaultValue != null
                          ? String(spec.defaultValue)
                          : ''
                      "
                    />
                  </template>
                </div>

                <VAlert v-if="triggerError" variant="error" class="mt-3">
                  <span>{{ triggerError }}</span>
                </VAlert>

                <div class="flex items-center gap-2 mt-3">
                  <VButton
                    :loading="state.busy.value"
                    variant="primary"
                    @click="onTrigger"
                  >{{ $t('insights.workflows.trigger.button') }}</VButton>
                </div>

                <VAlert
                  v-if="state.lastResult.value"
                  variant="success"
                  class="mt-3"
                >
                  <div class="flex flex-col gap-1 text-sm">
                    <span>
                      {{ $t('insights.workflows.trigger.spawnedPrefix') }}
                      <span class="font-mono">{{ state.lastResult.value.workflowName }}</span>
                    </span>
                    <span>
                      {{ $t('insights.workflows.trigger.runIdLabel') }}
                      <button
                        class="link"
                        @click="subTab = 'runs'"
                      >{{ state.lastResult.value.workflowRunId }}</button>
                    </span>
                  </div>
                </VAlert>
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
                    variant="ghost"
                    size="sm"
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
                      <span :class="runStatusClass(run.status)" class="text-xs">
                        {{ run.status }}
                      </span>
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
.wf-row:hover { background: hsl(var(--bc) / 0.06); }
.wf-row--active {
  background: hsl(var(--p) / 0.12);
  border-color: hsl(var(--p) / 0.3);
}

.badge-source {
  padding: 0 0.4rem;
  border-radius: 0.25rem;
  font-family: ui-monospace, monospace;
}
.badge-source--project {
  background: hsl(var(--p) / 0.18);
  color: hsl(var(--p));
}
.badge-source--vance {
  background: hsl(var(--bc) / 0.12);
  color: hsl(var(--bc) / 0.7);
}

.sub-tab-bar {
  display: flex;
  gap: 0.25rem;
  border-bottom: 1px solid hsl(var(--bc) / 0.15);
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
.sub-tab:hover { background: hsl(var(--bc) / 0.06); }
.sub-tab--active {
  background: hsl(var(--p) / 0.12);
  border-color: hsl(var(--p) / 0.3);
  font-weight: 600;
}

.badge-run {
  padding: 0 0.4rem;
  border-radius: 0.25rem;
  font-family: ui-monospace, monospace;
}
.badge-run--running    { background: hsl(var(--in) / 0.18); color: hsl(var(--in)); }
.badge-run--done       { background: hsl(var(--su) / 0.18); color: hsl(var(--su)); }
.badge-run--failed     { background: hsl(var(--er) / 0.18); color: hsl(var(--er)); }
.badge-run--terminated { background: hsl(var(--bc) / 0.12); color: hsl(var(--bc) / 0.7); }
.badge-run--paused     { background: hsl(var(--wa) / 0.18); color: hsl(var(--wa)); }

.json-block {
  background: hsl(var(--bc) / 0.05);
  padding: 0.5rem 0.75rem;
  border-radius: 0.375rem;
  font-size: 0.8125rem;
  white-space: pre-wrap;
  word-break: break-word;
  margin-top: 0.25rem;
}

.link {
  color: hsl(var(--p));
  text-decoration: underline;
  background: transparent;
  cursor: pointer;
  font-family: ui-monospace, monospace;
  font-size: 0.85rem;
}
</style>
