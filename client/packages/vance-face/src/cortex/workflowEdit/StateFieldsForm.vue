<script setup lang="ts">
/**
 * The typed form of one state: a control per field of its task type (recipe,
 * command, duration, inbox title, …), taken from {@code stateFieldSpecs}.
 * Text fields are written once typing pauses, selections at once; an empty
 * field removes the key. A value that is no legal value of its kind (a
 * negative timeout) is flagged and not written.
 *
 * Plan: {@code planning/workflow-editor.md}.
 */
import { computed, reactive, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VGroup, VInput, VSelect, VTextarea } from '@/components';
import { fieldSpecsFor, fromDraft, toDraft, valueAt, type FieldSpec } from './stateFieldSpecs';
import { useDraftTimers } from './useDraftTimers';
import { useWorkflowChange } from './useWorkflowChange';
import { setStateField } from './workflowMutations';
import { readStateBody } from './workflowRead';

defineOptions({ name: 'StateFieldsForm' });

const props = defineProps<{
  doc: string;
  state: string;
  /** The task type of the state. */
  type: string;
}>();
const emit = defineEmits<{ (e: 'update:doc', doc: string): void }>();

const { t, te } = useI18n();
const { error, apply } = useWorkflowChange(() => props.doc, (next) => emit('update:doc', next));

const body = computed(() => readStateBody(props.doc, props.state) ?? {});

const visible = computed<FieldSpec[]>(() =>
  fieldSpecsFor(props.type).filter(
    (spec) => !spec.onlyWhen || valueAt(body.value, spec.onlyWhen.path) === spec.onlyWhen.equals,
  ),
);

const drafts = reactive<Record<string, string>>({});
const invalid = reactive<Record<string, boolean>>({});
const timers = useDraftTimers();

const slot = (spec: FieldSpec): string => `${props.state}:${spec.key}`;

/** Show what the document holds — except for a field being typed in right now. */
function sync(): void {
  for (const spec of fieldSpecsFor(props.type)) {
    if (timers.pending(slot(spec))) continue;
    drafts[spec.key] = toDraft(spec.kind, valueAt(body.value, spec.path));
    invalid[spec.key] = false;
  }
}
watch([() => props.state, body], sync, { immediate: true });

function write(state: string, spec: FieldSpec, text: string): void {
  const value = fromDraft(spec.kind, text);
  if (value === 'invalid') {
    invalid[spec.key] = true;
    return;
  }
  invalid[spec.key] = false;
  apply(() => setStateField(props.doc, state, spec.path, value));
}

function onInput(spec: FieldSpec, text: string): void {
  drafts[spec.key] = text;
  if (spec.kind === 'select') {
    timers.cancel(slot(spec));
    write(props.state, spec, text);
    return;
  }
  const state = props.state;
  timers.schedule(slot(spec), () => write(state, spec, text));
}

const label = (spec: FieldSpec): string =>
  `${t(`documents.workflowView.field.${spec.key}`)}${spec.required ? ' *' : ''}`;

function help(spec: FieldSpec): string | undefined {
  if (invalid[spec.key]) return t('documents.workflowView.fieldInvalid.integer');
  const key = `documents.workflowView.field.${spec.key}Help`;
  if (te(key)) return t(key);
  return spec.kind === 'list' ? t('documents.workflowView.fieldListHelp') : undefined;
}

function options(spec: FieldSpec): { value: string; label: string }[] {
  return [
    { value: '', label: t('documents.workflowView.fieldDefault') },
    ...(spec.options ?? []).map((value) => ({ value, label: value })),
  ];
}
</script>

<template>
  <VGroup v-if="visible.length > 0" :label="t('documents.workflowView.inspector.settings')">
    <VAlert v-if="error" variant="error">{{ error }}</VAlert>
    <div class="wf-form">
      <template v-for="spec in visible" :key="spec.key">
        <VSelect
          v-if="spec.kind === 'select'"
          :model-value="drafts[spec.key] ?? ''"
          :options="options(spec)"
          :label="label(spec)"
          @update:model-value="onInput(spec, $event ?? '')"
        />
        <VTextarea
          v-else-if="spec.kind === 'text'"
          :model-value="drafts[spec.key] ?? ''"
          :label="label(spec)"
          :help="help(spec)"
          :rows="3"
          :mono="false"
          @update:model-value="onInput(spec, $event)"
        />
        <VInput
          v-else
          size="sm"
          :model-value="drafts[spec.key] ?? ''"
          :label="label(spec)"
          :help="help(spec)"
          :error="invalid[spec.key] ? help(spec) : undefined"
          @update:model-value="onInput(spec, $event)"
        />
      </template>
    </div>
  </VGroup>
</template>

<style scoped>
.wf-form { display: flex; flex-direction: column; gap: 0.4rem; padding: 0.3rem 0.75rem; }
</style>
