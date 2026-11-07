<script setup lang="ts">
/**
 * The parameters of a {@code tool_task}, as a form drawn from the called
 * tool's JSON Schema: required parameters first, the rest folded away unless
 * already set. A value may always be a placeholder ({@code ${state.x}}). What
 * the schema does not describe — parameters it does not list, or all of them
 * when the tool is unknown — stays editable as a YAML fragment.
 *
 * Plan: {@code planning/workflow-editor.md}.
 */
import { computed, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { parse, stringify } from 'yaml';
import { VAlert, VButton, VGroup, VTextarea } from '@/components';
import ParamControl from './ParamControl.vue';
import ToolResultInfo from './ToolResultInfo.vue';
import {
  findTool,
  fromParamDraft,
  INVALID,
  paramFields,
  toParamDraft,
  type ParamField,
} from './toolParamFields';
import { useDraftTimers } from './useDraftTimers';
import { useWorkflowChange } from './useWorkflowChange';
import type { WorkflowTools } from './useWorkflowTools';
import { setMapExtras, setStateField } from './workflowMutations';
import { readStateBody } from './workflowRead';

defineOptions({ name: 'ToolParamsForm' });

const props = defineProps<{
  doc: string;
  state: string;
  tools: WorkflowTools;
}>();
const emit = defineEmits<{ (e: 'update:doc', doc: string): void }>();

const { t } = useI18n();
const { error, apply } = useWorkflowChange(() => props.doc, (next) => emit('update:doc', next));
const timers = useDraftTimers();

// The tool list is fetched the first time a form wants it.
void props.tools.ensureLoaded();

const body = computed(() => readStateBody(props.doc, props.state) ?? {});
const toolName = computed(() => (typeof body.value.tool === 'string' ? body.value.tool : ''));
const stored = computed<Record<string, unknown>>(() => {
  const params = body.value.params;
  return typeof params === 'object' && params !== null && !Array.isArray(params)
    ? (params as Record<string, unknown>)
    : {};
});

const spec = computed(() => findTool(props.tools.services.value, toolName.value));
/** The variable the state keeps its result in, or empty. */
const storeAs = computed(() => (typeof body.value.storeAs === 'string' ? body.value.storeAs : ''));
const fields = computed(() => paramFields(spec.value?.paramsSchema));
const described = computed(() => new Set(fields.value.map((f) => f.name)));

/** Required parameters and those already set; the rest wait behind a fold. */
const shown = computed(() => fields.value.filter((f) => f.required || f.name in stored.value));
const folded = computed(() => fields.value.filter((f) => !f.required && !(f.name in stored.value)));

// ── one control per described parameter ──────────────────────────

const drafts = reactive<Record<string, string>>({});
const invalid = reactive<Record<string, boolean>>({});
const slot = (name: string): string => `${props.state}:${name}`;

function sync(): void {
  for (const field of fields.value) {
    if (timers.pending(slot(field.name))) continue;
    drafts[field.name] = toParamDraft(field, stored.value[field.name]);
    invalid[field.name] = false;
  }
}
watch([() => props.state, fields, stored], sync, { immediate: true });

function write(state: string, field: ParamField, text: string): void {
  const value = fromParamDraft(field, text);
  if (value === INVALID) {
    invalid[field.name] = true;
    return;
  }
  invalid[field.name] = false;
  apply(() => setStateField(props.doc, state, ['params', field.name], value));
}

function onInput(field: ParamField, text: string): void {
  drafts[field.name] = text;
  const state = props.state;
  timers.schedule(slot(field.name), () => write(state, field, text));
}

function hint(field: ParamField): string | undefined {
  if (invalid[field.name]) return t(`documents.workflowView.toolParams.invalid.${field.kind === 'yaml' ? 'yaml' : field.kind}`);
  const parts = [field.description];
  if (field.defaultText) parts.push(t('documents.workflowView.toolParams.default', { value: field.defaultText }));
  return parts.filter((p) => p !== '').join(' ') || undefined;
}

const label = (field: ParamField): string => `${field.name}${field.required ? ' *' : ''}`;

// ── the rest, as YAML ────────────────────────────────────────────

/** Parameters the schema does not list — all of them when the tool has no schema. */
const storedExtras = computed(() => {
  const rest = Object.fromEntries(Object.entries(stored.value).filter(([key]) => !described.value.has(key)));
  return Object.keys(rest).length === 0 ? '' : stringify(rest).trimEnd();
});
const extrasDraft = ref(storedExtras.value);
const extrasError = ref<string | null>(null);

watch([storedExtras, () => props.state], ([next], [previous, previousState]) => {
  if (props.state !== previousState || extrasDraft.value === previous) extrasDraft.value = next;
  extrasError.value = null;
});

const extrasChanged = computed(() => extrasDraft.value.trim() !== storedExtras.value);

function applyExtras(): void {
  let extras: unknown;
  try {
    extras = extrasDraft.value.trim() === '' ? {} : parse(extrasDraft.value);
  } catch (e) {
    extrasError.value = e instanceof Error ? e.message : String(e);
    return;
  }
  if (extras === null || typeof extras !== 'object' || Array.isArray(extras)) {
    extrasError.value = t('documents.workflowView.extras.notMap');
    return;
  }
  extrasError.value = null;
  apply(() => setMapExtras(props.doc, props.state, ['params'], extras as Record<string, unknown>, described.value));
}

const waiting = computed(() => !props.tools.loaded.value && props.tools.busy.value);
const unknownTool = computed(() => props.tools.loaded.value && toolName.value !== '' && !spec.value);
</script>

<template>
  <VGroup :label="t('documents.workflowView.toolParams.title')">
    <div class="wf-tool">
      <VAlert v-if="error" variant="error">{{ error }}</VAlert>
      <VAlert v-if="props.tools.error.value" variant="warning">
        {{ props.tools.error.value }}
        <VButton size="sm" variant="secondary" @click="props.tools.load()">
          {{ t('documents.workflowView.palette.retry') }}
        </VButton>
      </VAlert>
      <p v-else-if="waiting" class="wf-tool-note">{{ t('documents.workflowView.palette.loading') }}</p>
      <p v-else-if="toolName === ''" class="wf-tool-note">{{ t('documents.workflowView.toolParams.noTool') }}</p>
      <p v-else-if="unknownTool" class="wf-tool-note">{{ t('documents.workflowView.toolParams.unknownTool', { tool: toolName }) }}</p>
      <p v-else-if="spec && fields.length === 0" class="wf-tool-note">{{ t('documents.workflowView.toolParams.noParams') }}</p>
      <p v-if="spec?.description" class="wf-tool-note">{{ spec.description }}</p>

      <ParamControl
        v-for="field in shown"
        :key="field.name"
        :field="field"
        :model-value="drafts[field.name] ?? ''"
        :label="label(field)"
        :hint="hint(field)"
        :invalid="invalid[field.name]"
        @update:model-value="onInput(field, $event)"
      />

      <details v-if="folded.length > 0" class="wf-tool-fold">
        <summary>{{ t('documents.workflowView.toolParams.optional', { n: folded.length }) }}</summary>
        <div class="wf-tool-fold-body">
          <ParamControl
            v-for="field in folded"
            :key="field.name"
            :field="field"
            :model-value="drafts[field.name] ?? ''"
            :label="label(field)"
            :hint="hint(field)"
            :invalid="invalid[field.name]"
            @update:model-value="onInput(field, $event)"
          />
        </div>
      </details>

      <ToolResultInfo v-if="spec" :schema="spec.outputSchema" :store-as="storeAs" />

      <div class="wf-tool-extras">
        <p class="wf-tool-note">
          {{ fields.length > 0 ? t('documents.workflowView.toolParams.otherParams') : t('documents.workflowView.toolParams.allParams') }}
        </p>
        <VAlert v-if="extrasError" variant="error">{{ extrasError }}</VAlert>
        <VTextarea v-model="extrasDraft" :rows="4" />
        <div class="wf-tool-actions">
          <VButton size="sm" variant="secondary" :disabled="!extrasChanged" @click="applyExtras">
            {{ t('documents.workflowView.extras.apply') }}
          </VButton>
        </div>
      </div>
    </div>
  </VGroup>
</template>

<style scoped>
.wf-tool { display: flex; flex-direction: column; gap: 0.6rem; padding: 0.6rem 0.75rem; }
.wf-tool-note { margin: 0; font-size: 0.8rem; opacity: 0.7; }
.wf-tool-fold summary { cursor: pointer; font-size: 0.82rem; opacity: 0.8; }
.wf-tool-fold-body { display: flex; flex-direction: column; gap: 0.6rem; margin-top: 0.6rem; }
.wf-tool-extras { display: flex; flex-direction: column; gap: 0.4rem; }
.wf-tool-actions { display: flex; justify-content: flex-end; }
</style>
