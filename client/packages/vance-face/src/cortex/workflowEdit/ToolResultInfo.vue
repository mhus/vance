<script setup lang="ts">
/**
 * What the called tool returns: the fields of its result, folded away until wanted. With a variable name set
 * ({@code storeAs}) each field is shown as the placeholder that reads it in a later state, and can be copied.
 *
 * Plan: {@code planning/workflow-editor.md}.
 */
import { computed, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { VIconButton, VInput } from '@/components';
import { filterFields, outputFields, placeholderFor, type OutputField } from './toolOutputFields';

defineOptions({ name: 'ToolResultInfo' });

const props = defineProps<{
  /** The tool's {@code outputSchema}; absent when it does not describe its result. */
  schema?: Record<string, unknown>;
  /** The variable the state keeps its result in; empty when it keeps none. */
  storeAs?: string;
}>();

const { t } = useI18n();

/** Rows shown at once; a large schema is narrowed with the filter instead of being listed whole. */
const SHOWN = 40;

const result = computed(() => outputFields(props.schema));
const query = ref('');
const matches = computed(() => filterFields(result.value.fields, query.value));
const visible = computed(() => matches.value.slice(0, SHOWN));
const keep = computed(() => (props.storeAs ?? '').trim());

function shown(field: OutputField): string {
  return keep.value ? placeholderFor(keep.value, field.path) : field.path;
}

async function copy(field: OutputField): Promise<void> {
  await navigator.clipboard?.writeText(shown(field));
}
</script>

<template>
  <details class="wf-result">
    <summary>{{ t('documents.workflowView.toolParams.result.title', { n: result.fields.length }) }}</summary>
    <p v-if="result.fields.length === 0" class="wf-result-note">
      {{ t('documents.workflowView.toolParams.result.none') }}
    </p>
    <template v-else>
      <p class="wf-result-note">
        {{ keep
          ? t('documents.workflowView.toolParams.result.read', { name: keep })
          : t('documents.workflowView.toolParams.result.keepFirst') }}
      </p>
      <VInput
        v-if="result.fields.length > SHOWN"
        v-model="query"
        size="sm"
        :placeholder="t('documents.workflowView.toolParams.result.filter')"
      />
      <ul class="wf-result-list">
        <li v-for="field in visible" :key="field.path" class="wf-result-row">
          <div class="wf-result-main">
            <code class="wf-result-path">{{ shown(field) }}</code>
            <span class="wf-result-type">{{ field.type }}</span>
            <span v-if="field.description" class="wf-result-desc">{{ field.description }}</span>
          </div>
          <VIconButton
            v-if="keep"
            icon="copy"
            size="sm"
            :label="t('documents.workflowView.toolParams.result.copy')"
            @click="copy(field)"
          />
        </li>
      </ul>
      <p v-if="matches.length > SHOWN" class="wf-result-note">
        {{ t('documents.workflowView.toolParams.result.more', { shown: SHOWN, total: matches.length }) }}
      </p>
      <p v-else-if="matches.length === 0" class="wf-result-note">
        {{ t('documents.workflowView.toolParams.result.noMatch') }}
      </p>
      <p v-if="result.truncated" class="wf-result-note">
        {{ t('documents.workflowView.toolParams.result.truncated', { n: result.fields.length }) }}
      </p>
    </template>
  </details>
</template>

<style scoped>
.wf-result summary { cursor: pointer; font-size: 0.82rem; opacity: 0.8; }
.wf-result-note { margin: 0.5rem 0 0; font-size: 0.78rem; opacity: 0.7; }
.wf-result-list { list-style: none; margin: 0.5rem 0 0; padding: 0; display: flex; flex-direction: column; gap: 0.35rem; }
.wf-result-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 0.4rem; }
.wf-result-main { display: flex; flex-wrap: wrap; align-items: baseline; gap: 0.1rem 0.5rem; min-width: 0; }
.wf-result-path { font-size: 0.74rem; word-break: break-all; }
.wf-result-type { font-size: 0.68rem; opacity: 0.6; }
.wf-result-desc { font-size: 0.72rem; opacity: 0.7; }
</style>
