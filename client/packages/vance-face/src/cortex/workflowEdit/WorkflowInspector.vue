<script setup lang="ts">
/**
 * Side panel of the workflow view: what one state is — type, start, description,
 * its remaining fields as written, and the transitions leaving and entering it.
 *
 * Read-only unless {@code editable}; then the description can be changed and
 * the state made the start. Every change is a call into
 * {@code workflowMutations} and goes out as a new document text; the caller
 * (and behind it the shell's save pipeline) owns the text.
 *
 * Plan: {@code planning/workflow-editor.md}.
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { stringify } from 'yaml';
import { VAlert, VButton, VGroup, VGroupRow, VInput } from '@/components';
import { parseWorkflowGraph } from '@/workflowGraph/workflowGraphCodec';
import { setStart, setStateField, WorkflowEditError } from './workflowMutations';
import { readStateFields } from './workflowRead';

defineOptions({ name: 'WorkflowInspector' });

interface Props {
  doc: string;
  /** The state to show. */
  state: string;
  editable?: boolean;
}
const props = withDefaults(defineProps<Props>(), { editable: false });
const emit = defineEmits<{
  (e: 'update:doc', doc: string): void;
  (e: 'select', state: string): void;
}>();

const { t } = useI18n();

const graph = computed(() => parseWorkflowGraph(props.doc));
const node = computed(() => graph.value.states.find((s) => s.name === props.state));
const outgoing = computed(() => graph.value.edges.filter((e) => e.source === props.state));
const incoming = computed(() => graph.value.edges.filter((e) => e.target === props.state));
const fields = computed(() => readStateFields(props.doc, props.state) ?? {});
const fieldsText = computed(() =>
  Object.keys(fields.value).length === 0 ? '' : stringify(fields.value).trimEnd(),
);

const error = ref<string | null>(null);

/** Run a change; a refused one is shown here instead of thrown into the view. */
function apply(change: () => string): void {
  try {
    const next = change();
    error.value = null;
    if (next !== props.doc) emit('update:doc', next);
  } catch (e) {
    if (!(e instanceof WorkflowEditError)) throw e;
    error.value = e.message;
  }
}

// ── description: a draft that is written once typing pauses ──────

const draft = ref(node.value?.description ?? '');
let timer: ReturnType<typeof setTimeout> | undefined;

watch(
  () => [props.state, node.value?.description] as const,
  () => {
    clearTimeout(timer);
    draft.value = node.value?.description ?? '';
  },
);

function onDescription(value: string): void {
  draft.value = value;
  clearTimeout(timer);
  timer = setTimeout(() => {
    apply(() => setStateField(props.doc, props.state, ['description'], value.trim() || undefined));
  }, 500);
}

onBeforeUnmount(() => clearTimeout(timer));

function makeStart(): void {
  apply(() => setStart(props.doc, props.state));
}
</script>

<template>
  <aside v-if="node" class="wf-inspector">
    <VAlert v-if="error" variant="error">{{ error }}</VAlert>

    <VGroup :label="node.name">
      <VGroupRow :label="t('documents.workflowView.inspector.type')" :value="node.type || t('documents.workflowView.noType')" />
      <VGroupRow
        :label="t('documents.workflowView.inspector.start')"
        :value="node.isStart ? t('documents.workflowView.inspector.yes') : t('documents.workflowView.inspector.no')"
      >
        <VButton
          v-if="editable && !node.isStart"
          size="sm"
          variant="secondary"
          @click="makeStart"
        >{{ t('documents.workflowView.inspector.makeStart') }}</VButton>
      </VGroupRow>
    </VGroup>

    <VGroup :label="t('documents.workflowView.inspector.description')">
      <div class="wf-inspector-pad">
        <VInput
          v-if="editable"
          :model-value="draft"
          @update:model-value="onDescription"
        />
        <p v-else class="wf-inspector-text">
          {{ node.description || t('documents.workflowView.inspector.none') }}
        </p>
      </div>
    </VGroup>

    <VGroup v-if="fieldsText" :label="t('documents.workflowView.inspector.fields')">
      <pre class="wf-inspector-fields">{{ fieldsText }}</pre>
    </VGroup>

    <VGroup :label="t('documents.workflowView.inspector.leadsTo')">
      <VGroupRow
        v-for="edge in outgoing"
        :key="edge.id"
        :label="edge.target"
        :meta="`${edge.kind} · ${edge.label}`"
        navigates
        @click="emit('select', edge.target)"
      />
      <VGroupRow
        v-if="outgoing.length === 0"
        :label="t('documents.workflowView.inspector.none')"
        disabled
      />
    </VGroup>

    <VGroup :label="t('documents.workflowView.inspector.cameFrom')">
      <VGroupRow
        v-for="edge in incoming"
        :key="edge.id"
        :label="edge.source"
        :meta="`${edge.kind} · ${edge.label}`"
        navigates
        @click="emit('select', edge.source)"
      />
      <VGroupRow
        v-if="incoming.length === 0"
        :label="t('documents.workflowView.inspector.none')"
        disabled
      />
    </VGroup>
  </aside>
</template>

<style scoped>
.wf-inspector { display: flex; flex-direction: column; gap: 0.75rem; min-width: 0; }
.wf-inspector-pad { padding: 0.5rem 0.75rem; }
.wf-inspector-text { margin: 0; font-size: 0.85rem; }
.wf-inspector-fields {
  margin: 0;
  padding: 0.5rem 0.75rem;
  font-family: ui-monospace, monospace;
  font-size: 0.75rem;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
