<script setup lang="ts">
/**
 * Starting a run — the one place where runs are started from the UI.
 *
 * <p>Two ways in, both ending in the same form. A **document path** (the
 * Cortex "Run" button hands one over) runs *that* document, wherever it lives;
 * without one the user picks a workflow by name from the project's cascade.
 * Either way the form shows the declared parameters, offers the debug switch
 * and starts the run; the run view then takes over.
 *
 * <p>Debug halts the run before each state, with real calls — it is not a
 * dry run — and is stepped from the run's detail.
 */
import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { brainFetch, brainFetchText } from '@vance/shared';
import type { DocumentDto } from '@vance/generated';
import { VAlert, VButton, VCard, VCheckbox, VInput, VSelect, VTextarea } from '@/components';
import { useWorkflows } from '@/composables/useWorkflows';
import { parseWorkflowGraph } from '@/workflowGraph/workflowGraphCodec';
import {
  buildParams, fieldTypeOf, seedInputs,
  type ParamInput, type ParamIssue, type StartParamSpec,
} from './startParams';

const props = defineProps<{
  projectId: string;
  /** Run this document. Takes precedence over {@link initialWorkflow}. */
  startPath?: string;
  /** Preselect a workflow by name. */
  initialWorkflow?: string;
}>();
const emit = defineEmits<{
  (e: 'started', runId: string): void;
  (e: 'cancel'): void;
}>();
const { t } = useI18n();

const workflows = useWorkflows();

const selectedName = ref<string | null>(props.initialWorkflow ?? null);
const specs = ref<StartParamSpec[]>([]);
const inputs = ref<Record<string, ParamInput>>({});
const problems = ref<string[]>([]);
const loadError = ref<string | null>(null);
const loadingDefinition = ref(false);
const debug = ref(false);
const submitError = ref<string | null>(null);

const byPath = computed(() => !!props.startPath);

const nameOptions = computed(() =>
  [...workflows.workflows.value]
    .sort((a, b) => (a.name ?? '').localeCompare(b.name ?? ''))
    .map((w) => ({ value: w.name ?? '', label: w.name ?? '' })));

/** Something to run: a path, or a chosen name — and nothing the drawing already knows to be broken. */
const canStart = computed(() =>
  (byPath.value || !!selectedName.value)
  && problems.value.length === 0
  && !loadingDefinition.value
  && !workflows.busy.value);

function useSpecs(next: StartParamSpec[]): void {
  specs.value = next;
  inputs.value = seedInputs(next);
}

/** Reads the document and takes its parameters from the same projection the drawing uses. */
async function loadByPath(path: string): Promise<void> {
  loadingDefinition.value = true;
  loadError.value = null;
  problems.value = [];
  try {
    const query = new URLSearchParams({ projectId: props.projectId, path });
    const doc = await brainFetch<DocumentDto>('GET', `documents/by-path?${query}`);
    const text = await brainFetchText(`documents/${encodeURIComponent(doc.id)}/content`);
    const graph = parseWorkflowGraph(text ?? '');
    problems.value = graph.states.length === 0
      ? [t('documents.workflowView.empty'), ...graph.problems]
      : graph.problems;
    useSpecs(graph.parameters);
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : String(e);
    useSpecs([]);
  } finally {
    loadingDefinition.value = false;
  }
}

async function loadByName(name: string): Promise<void> {
  loadingDefinition.value = true;
  loadError.value = null;
  problems.value = [];
  try {
    await workflows.loadOne(props.projectId, name);
    const declared = workflows.current.value?.parameters ?? {};
    useSpecs(Object.entries(declared).map(([key, spec]) => ({
      name: key,
      type: spec.type ?? 'string',
      required: !!spec.required,
      defaultValue: spec.defaultValue,
    })));
    if (workflows.error.value) loadError.value = workflows.error.value;
  } finally {
    loadingDefinition.value = false;
  }
}

function issueText(issue: ParamIssue): string {
  switch (issue.code) {
    case 'required': return t('runs.new.paramRequired', { key: issue.key });
    case 'notInteger': return t('runs.new.paramNotInteger', { key: issue.key, raw: issue.raw });
    case 'notJson': return t('runs.new.paramNotJson', { key: issue.key, error: issue.error });
  }
}

async function start(): Promise<void> {
  submitError.value = null;
  const built = buildParams(specs.value, inputs.value);
  if (!built.ok) {
    submitError.value = issueText(built.issue);
    return;
  }
  const params = Object.keys(built.params).length > 0 ? built.params : null;
  try {
    const result = props.startPath
      ? await workflows.startDocument(props.projectId, props.startPath, params, debug.value)
      : await workflows.start(props.projectId, selectedName.value ?? '', params, debug.value);
    emit('started', `workflow:${result.workflowRunId}`);
  } catch (e) {
    submitError.value = e instanceof Error ? e.message : t('runs.new.startFailed');
  }
}

watch(selectedName, (name) => {
  if (!byPath.value && name) void loadByName(name);
});

onMounted(async () => {
  if (props.startPath) {
    await loadByPath(props.startPath);
    return;
  }
  await workflows.loadProject(props.projectId);
  if (selectedName.value) await loadByName(selectedName.value);
});
</script>

<template>
  <VCard :title="t('runs.new.title')">
    <div class="new-run">
      <div v-if="byPath" class="source">
        <span class="source-label">{{ t('runs.new.document') }}</span>
        <code class="source-path">{{ startPath }}</code>
      </div>
      <VSelect
        v-else
        v-model="selectedName"
        :label="t('runs.new.workflow')"
        :placeholder="t('runs.new.pickWorkflow')"
        :options="nameOptions"
      />

      <VAlert v-if="loadError" variant="error">{{ loadError }}</VAlert>
      <VAlert v-if="problems.length > 0" variant="warning">
        <p class="problems-title">{{ t('documents.workflowView.problemsTitle') }}</p>
        <ul class="problems">
          <li v-for="problem in problems" :key="problem">{{ problem }}</li>
        </ul>
      </VAlert>

      <p v-if="loadingDefinition" class="hint">{{ t('runs.new.loading') }}</p>

      <template v-for="spec in specs" :key="spec.name">
        <VCheckbox
          v-if="fieldTypeOf(spec) === 'boolean'"
          v-model="inputs[spec.name].checked"
          :label="`${spec.name}${spec.required ? ' *' : ''}`"
        />
        <VTextarea
          v-else-if="fieldTypeOf(spec) === 'json'"
          v-model="inputs[spec.name].text"
          :label="`${spec.name}${spec.required ? ' *' : ''} (${spec.type})`"
          :rows="4"
          class="mono"
        />
        <VInput
          v-else
          v-model="inputs[spec.name].text"
          :label="`${spec.name}${spec.required ? ' *' : ''} (${spec.type})`"
          :placeholder="spec.defaultValue != null ? String(spec.defaultValue) : ''"
        />
      </template>
      <p v-if="!loadingDefinition && specs.length === 0 && (byPath || selectedName)" class="hint">
        {{ t('runs.new.noParams') }}
      </p>

      <div class="debug">
        <VCheckbox v-model="debug" :label="t('runs.new.debug')" />
        <p class="hint">{{ t('runs.new.debugHint') }}</p>
      </div>

      <VAlert v-if="submitError" variant="error">{{ submitError }}</VAlert>

      <div class="buttons">
        <VButton variant="primary" :disabled="!canStart" :loading="workflows.busy.value" @click="start">
          {{ t('runs.new.start') }}
        </VButton>
        <VButton variant="plain" @click="emit('cancel')">{{ t('runs.new.cancel') }}</VButton>
      </div>
    </div>
  </VCard>
</template>

<style scoped>
.new-run { display: flex; flex-direction: column; gap: 0.6rem; max-width: 40rem; }
.source { display: flex; align-items: baseline; gap: 0.5rem; flex-wrap: wrap; }
.source-label { font-size: 0.75rem; opacity: 0.65; }
.source-path { font-size: 0.8rem; word-break: break-all; }
.hint { margin: 0; font-size: 0.78rem; opacity: 0.65; }
.problems-title { margin: 0 0 0.2rem; font-weight: 600; }
.problems { margin: 0; padding-left: 1.1rem; font-size: 0.82rem; }
.mono { font-family: ui-monospace, monospace; }
.buttons { display: flex; gap: 0.5rem; }
</style>
