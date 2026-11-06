<script setup lang="ts">
/**
 * Whatever a state carries that no form field covers — {@code retry},
 * {@code decide}, {@code score}, {@code result}, {@code params} of a tool
 * call — as a YAML fragment. Applying replaces exactly those keys; the form's
 * fields and the connections stay as they are.
 *
 * Plan: {@code planning/workflow-editor.md}.
 */
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { parse, stringify } from 'yaml';
import { VAlert, VButton, VGroup, VTextarea } from '@/components';
import { ownedKeys } from './stateFieldSpecs';
import { useWorkflowChange } from './useWorkflowChange';
import { setStateExtras } from './workflowMutations';
import { readStateBody } from './workflowRead';

defineOptions({ name: 'StateExtrasEditor' });

const props = defineProps<{ doc: string; state: string; type: string }>();
const emit = defineEmits<{ (e: 'update:doc', doc: string): void }>();

const { t } = useI18n();
const { error, apply } = useWorkflowChange(() => props.doc, (next) => emit('update:doc', next));

const owned = computed(() => ownedKeys(props.type));

/** The fragment as the document holds it now. */
const stored = computed(() => {
  const body = readStateBody(props.doc, props.state) ?? {};
  const extras = Object.fromEntries(Object.entries(body).filter(([key]) => !owned.value.has(key)));
  return Object.keys(extras).length === 0 ? '' : stringify(extras).trimEnd();
});

const draft = ref(stored.value);
const parseError = ref<string | null>(null);

watch([stored, () => props.state], ([next], [previous, previousState]) => {
  // Follow the document unless the person is half-way through typing something else.
  if (props.state !== previousState || draft.value === previous) draft.value = next;
  parseError.value = null;
});

const changed = computed(() => draft.value.trim() !== stored.value);

function applyFragment(): void {
  let extras: unknown;
  try {
    extras = draft.value.trim() === '' ? {} : parse(draft.value);
  } catch (e) {
    parseError.value = e instanceof Error ? e.message : String(e);
    return;
  }
  if (extras === null || typeof extras !== 'object' || Array.isArray(extras)) {
    parseError.value = t('documents.workflowView.extras.notMap');
    return;
  }
  parseError.value = null;
  apply(() => setStateExtras(props.doc, props.state, extras as Record<string, unknown>, owned.value));
}
</script>

<template>
  <VGroup :label="t('documents.workflowView.extras.title')" :note="t('documents.workflowView.extras.note')">
    <div class="wf-extras">
      <VAlert v-if="error || parseError" variant="error">{{ parseError ?? error }}</VAlert>
      <VTextarea v-model="draft" :rows="6" />
      <div class="wf-extras-actions">
        <VButton size="sm" variant="secondary" :disabled="!changed" @click="applyFragment">
          {{ t('documents.workflowView.extras.apply') }}
        </VButton>
      </div>
    </div>
  </VGroup>
</template>

<style scoped>
.wf-extras { display: flex; flex-direction: column; gap: 0.5rem; padding: 0.6rem 0.75rem; }
.wf-extras-actions { display: flex; justify-content: flex-end; }
</style>
