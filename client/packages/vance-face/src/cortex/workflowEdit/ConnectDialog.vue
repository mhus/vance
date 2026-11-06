<script setup lang="ts">
/**
 * Asks what a new connection is: which kind (outcome, error, branch, else) and
 * what it is called — and, when the line was dropped on empty canvas, which
 * kind of state it leads to. Pure form: it changes nothing, it reports the
 * answer.
 *
 * Plan: {@code planning/workflow-editor.md}.
 */
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VButton, VCombobox, VInput, VModal, VSegment, VSelect } from '@/components';
import type { WorkflowEdgeKind, WorkflowTaskType } from '@/workflowGraph/workflowGraphCodec';
import { connectionKinds, defaultLabel, ERROR_KINDS, outcomeSuggestions } from './connectionVocabulary';
import { TEMPLATE_TYPES } from './stateTemplates';

defineOptions({ name: 'ConnectDialog' });

/** A connection being made: from {@code source}, to {@code target} or to a state yet to be created. */
export interface ConnectRequest {
  source: string;
  /** Type of the source state — decides which kinds and words are offered. */
  sourceType: string;
  /** The state it leads to; {@code null} when a new one is to be made. */
  target: string | null;
  /** Outcomes / error kinds the source already routes, so the defaults are free ones. */
  taken: Record<'on' | 'catch', string[]>;
  /** The source already has an else. */
  hasElse: boolean;
}

export interface ConnectAnswer {
  kind: WorkflowEdgeKind;
  label: string;
  /** Set when {@link ConnectRequest.target} was {@code null}. */
  newType: WorkflowTaskType | null;
}

const props = defineProps<{ request: ConnectRequest | null }>();
const emit = defineEmits<{
  (e: 'confirm', answer: ConnectAnswer): void;
  (e: 'cancel'): void;
}>();

const { t } = useI18n();

const kind = ref<WorkflowEdgeKind>('on');
const label = ref('');
const newType = ref<WorkflowTaskType>('shell_task');

const kinds = computed<WorkflowEdgeKind[]>(() => {
  const req = props.request;
  if (!req) return [];
  return connectionKinds(req.sourceType).filter((k) => !(k === 'else' && req.hasElse));
});

function takenFor(k: WorkflowEdgeKind): string[] {
  if (k === 'on' || k === 'catch') return props.request?.taken[k] ?? [];
  return [];
}

function resetLabel(): void {
  const req = props.request;
  if (!req) return;
  label.value = defaultLabel(kind.value, req.sourceType, takenFor(kind.value));
}

// A new request starts from the first kind on offer; a kind change re-picks the words.
watch(
  () => props.request,
  (req) => {
    if (!req) return;
    kind.value = kinds.value[0] ?? 'on';
    newType.value = 'shell_task';
    resetLabel();
  },
  { immediate: true },
);
watch(kind, resetLabel);

const kindOptions = computed(() =>
  kinds.value.map((k) => ({ value: k, label: t(`documents.workflowView.connect.kind.${k}`) })),
);

const labelOptions = computed(() => {
  const pool: readonly string[] = kind.value === 'catch'
    ? ERROR_KINDS
    : outcomeSuggestions(props.request?.sourceType ?? '');
  return pool.map((word) => ({ value: word, label: word }));
});

const typeOptions = TEMPLATE_TYPES.map((type) => ({ value: type, label: type }));

const needsLabel = computed(() => kind.value !== 'else');
const valid = computed(() => !needsLabel.value || label.value.trim() !== '');

function submit(): void {
  if (!valid.value || !props.request) return;
  emit('confirm', {
    kind: kind.value,
    label: label.value.trim(),
    newType: props.request.target === null ? newType.value : null,
  });
}
</script>

<template>
  <VModal
    :model-value="request !== null"
    size="sm"
    :title="request
      ? t('documents.workflowView.connect.title', { from: request.source, to: request.target ?? t('documents.workflowView.connect.newState') })
      : ''"
    @update:model-value="(open: boolean) => { if (!open) emit('cancel'); }"
  >
    <form v-if="request" id="wf-connect-form" class="wf-connect" @submit.prevent="submit">
      <VSegment v-model="kind" :options="kindOptions" :aria-label="t('documents.workflowView.connect.kindLabel')" />

      <VCombobox
        v-if="kind === 'on' || kind === 'catch'"
        :model-value="label"
        :options="labelOptions"
        :label="kind === 'catch' ? t('documents.workflowView.connect.errorKind') : t('documents.workflowView.connect.outcome')"
        @update:model-value="label = $event ?? ''"
      />
      <VInput
        v-else-if="kind === 'condition'"
        v-model="label"
        :label="t('documents.workflowView.connect.condition')"
        :help="t('documents.workflowView.connect.conditionHelp')"
      />

      <VSelect
        v-if="request.target === null"
        v-model="newType"
        :options="typeOptions"
        :label="t('documents.workflowView.connect.newStateType')"
      />
    </form>
    <template #actions>
      <VButton variant="secondary" @click="emit('cancel')">{{ t('documents.workflowView.connect.cancel') }}</VButton>
      <VButton variant="primary" type="submit" form="wf-connect-form" :disabled="!valid">
        {{ t('documents.workflowView.connect.confirm') }}
      </VButton>
    </template>
  </VModal>
</template>

<style scoped>
.wf-connect { display: flex; flex-direction: column; gap: 0.75rem; }
</style>
