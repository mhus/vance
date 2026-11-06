<script setup lang="ts">
/**
 * View tab for `kind: vance-workflow` — the Cortex face of a workflow.
 *
 * Two modes over the same text. <b>View</b> draws the shared, read-only
 * {@link WorkflowGraph} and lets a click on a state show its details in the
 * inspector. <b>Edit</b> swaps the drawing for the editor's canvas
 * ({@link WorkflowEditGraph}) and lets the inspector change what it shows.
 * The raw YAML stays the Edit tab of the document.
 *
 * The model handed in by the kind-registry entry is the raw YAML (identity
 * codec); a change leaves as the new text through `update:doc` and the shell
 * saves it like any other edit. Running is not done here: starting,
 * parameters and debugging live in the run view — this tab only holds the
 * button that opens it.
 *
 * Spec: `specification/public/workflows.md` §2.5, §13. Plan:
 * `planning/workflow-editor.md`.
 */
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VButton, VSegment } from '@/components';
import WorkflowEditGraph from '@/cortex/workflowEdit/WorkflowEditGraph.vue';
import WorkflowInspector from '@/cortex/workflowEdit/WorkflowInspector.vue';
import { useWorkflowServerCheck } from '@/cortex/workflowEdit/useWorkflowServerCheck';
import WorkflowGraph from '@/workflowGraph/WorkflowGraph.vue';
import { parseWorkflowGraph } from '@/workflowGraph/workflowGraphCodec';

defineOptions({ name: 'WorkflowFlowView' });

interface Props {
  /** The workflow YAML (kind-registry parsed model = identity(text)). */
  doc: string;
  /** Supplied by the shell for identity-aware views — needed to open the run view. */
  projectId?: string;
  docPath?: string;
  /** Shell contract: a read-only document cannot be switched to edit. */
  readOnly?: boolean;
}
const props = defineProps<Props>();
const emit = defineEmits<{ (e: 'update:doc', doc: string): void }>();

const { t } = useI18n();

type Mode = 'view' | 'edit';
const mode = ref<Mode>('view');
const modeOptions = computed(() => [
  { value: 'view' as const, label: t('documents.workflowView.modeView') },
  { value: 'edit' as const, label: t('documents.workflowView.modeEdit') },
]);

// A document that turns read-only (locked, permission) cannot stay in edit.
watch(() => props.readOnly, (ro) => { if (ro) mode.value = 'view'; });

const graph = computed(() => parseWorkflowGraph(props.doc));

/** The picked state; dropped when it no longer exists (renamed or removed). */
const selected = ref<string | null>(null);
watch(graph, (g) => {
  if (selected.value && !g.states.some((s) => s.name === selected.value && !s.missing)) {
    selected.value = null;
  }
});

/**
 * Problems block the button. The server would reject the same document a
 * moment later; refusing here costs nothing and says why.
 */
const blockedReason = computed<string | null>(() => {
  if (graph.value.states.length === 0) return t('documents.workflowView.empty');
  return graph.value.problems.length > 0 ? graph.value.problems[0] : null;
});

const canRun = computed(() => !!props.projectId && !!props.docPath && !blockedReason.value);

/**
 * Opens the run view with a new run prepared for this document. A new tab:
 * the editor may hold unsaved changes, and a run starts from the saved
 * document anyway.
 */
function openRunView(): void {
  const url = `/runs.html?project=${encodeURIComponent(props.projectId ?? '')}`
    + `&start=${encodeURIComponent(props.docPath ?? '')}`;
  window.open(url, '_blank', 'noopener');
}

const check = useWorkflowServerCheck(() => props.projectId, () => props.docPath);
watch(() => props.docPath, () => check.clear());
</script>

<template>
  <div class="workflow-view">
    <div class="run-row">
      <VSegment v-model="mode" :options="modeOptions" :disabled="readOnly" :aria-label="t('documents.workflowView.modeLabel')" />
      <VButton
        size="sm"
        variant="primary"
        icon="forward"
        :disabled="!canRun"
        :title="blockedReason ?? t('documents.workflowView.runHint')"
        @click="openRunView"
      >{{ t('documents.workflowView.run') }}</VButton>
      <VButton
        v-if="mode === 'edit'"
        size="sm"
        variant="secondary"
        :loading="check.busy.value"
        :disabled="!projectId || !docPath"
        :title="t('documents.workflowView.checkHint')"
        @click="check.run()"
      >{{ t('documents.workflowView.check') }}</VButton>
      <span v-if="blockedReason" class="run-note">{{ t('documents.workflowView.runBlocked') }}</span>
    </div>

    <VAlert v-if="check.error.value" variant="error">{{ check.error.value }}</VAlert>
    <VAlert
      v-else-if="check.result.value"
      :variant="check.result.value.ok ? 'success' : 'warning'"
    >
      <p v-if="check.result.value.findings.length === 0" class="check-title">
        {{ t('documents.workflowView.checkOk') }}
      </p>
      <ul v-else class="check-list">
        <li v-for="f in check.result.value.findings" :key="`${f.code}:${f.location}:${f.message}`">
          {{ f.message }}
        </li>
      </ul>
    </VAlert>

    <div class="body" :class="{ 'body--with-panel': selected }">
      <WorkflowEditGraph
        v-if="mode === 'edit'"
        :doc="doc"
        :selected="selected"
        @select="selected = $event"
      />
      <WorkflowGraph
        v-else
        :doc="doc"
        :selected="selected"
        interactive
        @node-click="selected = $event"
      />
      <WorkflowInspector
        v-if="selected"
        :doc="doc"
        :state="selected"
        :editable="mode === 'edit' && !readOnly"
        @update:doc="emit('update:doc', $event)"
        @select="selected = $event"
      />
    </div>
  </div>
</template>

<style scoped>
.workflow-view { display: flex; flex-direction: column; gap: 0.5rem; }
.run-row { display: flex; align-items: center; gap: 0.6rem; flex-wrap: wrap; }
.run-note { font-size: 0.78rem; opacity: 0.7; }
.check-title { margin: 0; }
.check-list { margin: 0; padding-left: 1.1rem; }
.body { display: grid; grid-template-columns: minmax(0, 1fr); gap: 0.75rem; align-items: start; }
.body--with-panel { grid-template-columns: minmax(0, 1fr) 20rem; }
</style>
