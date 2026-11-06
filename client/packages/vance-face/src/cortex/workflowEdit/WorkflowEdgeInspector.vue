<script setup lang="ts">
/**
 * Side panel for a picked connection: where it goes, what it is called, and a
 * way to remove it. Changing the target applies at once; changing the name
 * takes the button, since a half-typed outcome would route nowhere.
 *
 * Plan: {@code planning/workflow-editor.md}.
 */
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VButton, VCombobox, VGroup, VGroupRow, VInput, VSelect, useConfirm } from '@/components';
import { parseWorkflowGraph } from '@/workflowGraph/workflowGraphCodec';
import { ERROR_KINDS, outcomeSuggestions } from './connectionVocabulary';
import { useWorkflowChange } from './useWorkflowChange';
import { disconnect, relabel, retarget, type EdgeRef } from './workflowMutations';

defineOptions({ name: 'WorkflowEdgeInspector' });

const props = defineProps<{
  doc: string;
  edge: EdgeRef;
  editable?: boolean;
}>();
const emit = defineEmits<{
  (e: 'update:doc', doc: string): void;
  /** The connection now sits under another reference (its name changed), or is gone ({@code null}). */
  (e: 'select-edge', ref: EdgeRef | null): void;
  (e: 'select', state: string): void;
}>();

const { t } = useI18n();
const confirmAction = useConfirm();
const { error, apply } = useWorkflowChange(() => props.doc, (next) => emit('update:doc', next));

const graph = computed(() => parseWorkflowGraph(props.doc));
const found = computed(() =>
  graph.value.edges.find(
    (e) => e.source === props.edge.source && e.kind === props.edge.kind && e.key === props.edge.key,
  ),
);
const sourceType = computed(
  () => graph.value.states.find((s) => s.name === props.edge.source)?.type ?? '',
);

const targetOptions = computed(() =>
  graph.value.states.filter((s) => !s.missing).map((s) => ({ value: s.name, label: s.name })),
);

const named = computed(() => props.edge.kind === 'on' || props.edge.kind === 'catch');
const labelOptions = computed(() => {
  const pool: readonly string[] = props.edge.kind === 'catch'
    ? ERROR_KINDS
    : outcomeSuggestions(sourceType.value);
  return pool.map((word) => ({ value: word, label: word }));
});

/** What the label field starts from: the full text, not the truncated one the drawing shows. */
const savedLabel = computed(() => (named.value ? props.edge.key : found.value?.label ?? ''));
const draft = ref(savedLabel.value);
watch(savedLabel, (value) => { draft.value = value; });
const canRelabel = computed(() => draft.value.trim() !== '' && draft.value.trim() !== savedLabel.value);

function onTarget(target: string | null): void {
  if (target && target !== found.value?.target) apply(() => retarget(props.doc, props.edge, target));
}

function rename(): void {
  const next = draft.value.trim();
  if (!apply(() => relabel(props.doc, props.edge, next))) return;
  // An on / catch transition is addressed by its name; a branch by its place, which stays.
  if (named.value) emit('select-edge', { ...props.edge, key: next });
}

async function remove(): Promise<void> {
  const confirmed = await confirmAction({
    title: t('documents.workflowView.edge.deleteTitle'),
    message: t('documents.workflowView.edge.deleteBody', { from: props.edge.source }),
    confirmLabel: t('documents.workflowView.edge.delete'),
  });
  if (!confirmed) return;
  if (apply(() => disconnect(props.doc, props.edge))) emit('select-edge', null);
}
</script>

<template>
  <aside v-if="found" class="wf-edge-inspector">
    <VAlert v-if="error" variant="error">{{ error }}</VAlert>

    <VGroup :label="t('documents.workflowView.edge.title')">
      <VGroupRow
        :label="edge.source"
        :meta="t('documents.workflowView.edge.from')"
        navigates
        @click="emit('select', edge.source)"
      />
      <VGroupRow
        :label="t(`documents.workflowView.connect.kind.${edge.kind}`)"
        :meta="t('documents.workflowView.edge.kind')"
      />
    </VGroup>

    <VGroup v-if="edge.kind !== 'else'" :label="named ? (edge.kind === 'catch' ? t('documents.workflowView.connect.errorKind') : t('documents.workflowView.connect.outcome')) : t('documents.workflowView.connect.condition')">
      <div v-if="editable" class="wf-edge-pad wf-edge-rename">
        <div class="wf-edge-grow">
          <VCombobox v-if="named" :model-value="draft" :options="labelOptions" @update:model-value="draft = $event ?? ''" />
          <VInput v-else v-model="draft" />
        </div>
        <VButton size="sm" variant="secondary" :disabled="!canRelabel" @click="rename">
          {{ t('documents.workflowView.edge.apply') }}
        </VButton>
      </div>
      <p v-else class="wf-edge-pad wf-edge-text">{{ savedLabel }}</p>
    </VGroup>

    <VGroup :label="t('documents.workflowView.edge.to')">
      <div v-if="editable" class="wf-edge-pad">
        <VSelect :model-value="found.target" :options="targetOptions" @update:model-value="onTarget" />
      </div>
      <VGroupRow v-else :label="found.target" navigates @click="emit('select', found.target)" />
    </VGroup>

    <VGroup v-if="editable">
      <VGroupRow :label="t('documents.workflowView.edge.deleteRow')" danger navigates @click="remove" />
    </VGroup>
  </aside>
</template>

<style scoped>
.wf-edge-inspector { display: flex; flex-direction: column; gap: 0.75rem; min-width: 0; }
.wf-edge-pad { padding: 0.5rem 0.75rem; }
.wf-edge-rename { display: flex; align-items: center; gap: 0.5rem; }
.wf-edge-grow { flex: 1; min-width: 0; }
.wf-edge-text { margin: 0; font-size: 0.85rem; font-family: ui-monospace, monospace; }
</style>
