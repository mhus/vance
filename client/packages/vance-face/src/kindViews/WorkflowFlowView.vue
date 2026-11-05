<script setup lang="ts">
/**
 * View tab for `kind: vance-workflow` — the Cortex face of a workflow.
 *
 * Drawing is the shared, read-only {@link WorkflowGraph}. Running is not
 * done here: starting, parameters and debugging live in the run view, so
 * the editor stays an editor. The one thing this tab adds is the way over —
 * a button that opens the run view for *this* document.
 *
 * The model handed in by the kind-registry entry is the raw YAML (identity
 * codec), so editing happens in the Edit tab's CodeEditor and the picture
 * simply re-renders.
 *
 * Spec: `specification/public/workflows.md` §2.5.
 */
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { VButton } from '@/components';
import WorkflowGraph from '@/workflowGraph/WorkflowGraph.vue';
import { parseWorkflowGraph } from '@/workflowGraph/workflowGraphCodec';

defineOptions({ name: 'WorkflowFlowView' });

interface Props {
  /** The workflow YAML (kind-registry parsed model = identity(text)). */
  doc: string;
  /** Supplied by the shell for identity-aware views — needed to open the run view. */
  projectId?: string;
  docPath?: string;
}
const props = defineProps<Props>();

const { t } = useI18n();

/**
 * Problems block the button. The server would reject the same document a
 * moment later; refusing here costs nothing and says why.
 */
const blockedReason = computed<string | null>(() => {
  const graph = parseWorkflowGraph(props.doc);
  if (graph.states.length === 0) return t('documents.workflowView.empty');
  return graph.problems.length > 0 ? graph.problems[0] : null;
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
</script>

<template>
  <div class="workflow-view">
    <div class="run-row">
      <VButton
        size="sm"
        variant="primary"
        icon="forward"
        :disabled="!canRun"
        :title="blockedReason ?? t('documents.workflowView.runHint')"
        @click="openRunView"
      >{{ t('documents.workflowView.run') }}</VButton>
      <span v-if="blockedReason" class="run-note">{{ t('documents.workflowView.runBlocked') }}</span>
    </div>
    <WorkflowGraph :doc="doc" />
  </div>
</template>

<style scoped>
.workflow-view { display: flex; flex-direction: column; gap: 0.5rem; }
.run-row { display: flex; align-items: center; gap: 0.6rem; flex-wrap: wrap; }
.run-note { font-size: 0.78rem; opacity: 0.7; }
</style>
