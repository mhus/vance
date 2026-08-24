<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { VAlert, VButton, VEmptyState } from '@vance/components';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import FormFieldsView from './FormFieldsView.vue';
import type { ViewNode } from './generated/bistromath/ViewNode';
import type { ViewAction } from './generated/bistromath/ViewAction';

/**
 * Renders one widget and, recursively, its children.
 *
 * <p>Every branch composes `V*` primitives plus Tailwind layout classes. The
 * rule that DaisyUI classes stay inside `@vance/components` matters more here
 * than anywhere: a generic renderer is exactly where a document-defined app
 * would otherwise start looking like whatever its author felt like.
 *
 * <p><b>One binding.</b> A widget shows state, via `from: <key>`. There is no
 * path, no table name and no expression — the program writes the key, the
 * widget reads it. That is the whole data model on this side.
 */
const props = defineProps<{
  node: ViewNode;
  /** The host state the program writes. */
  state: Record<string, unknown>;
  /** Record key from the entry handle, for a detail view. */
  recordKey: string | null;
  depth?: number;
}>();

const emit = defineEmits<{
  (e: 'action', action: ViewAction, recordKey?: string): void;
}>();

const depth = computed(() => props.depth ?? 0);

/** Which tab is open. Client state — nothing is written. */
const activeTab = ref(0);

const bound = computed<unknown>(() =>
  props.node.from ? props.state[props.node.from] : undefined,
);

/** Rows of a `table`: whatever the program put there, if it is a list. */
const rows = computed<Record<string, unknown>[]>(() => {
  const v = bound.value;
  if (!Array.isArray(v)) return [];
  return v.filter((r): r is Record<string, unknown> => !!r && typeof r === 'object');
});

/**
 * Columns: what the widget asks for, else the union of the keys present.
 *
 * <p>A widget naming a column the rows do not have still shows it, as empty
 * cells. Silently dropping it would make a typo look like missing data.
 */
const columns = computed<string[]>(() => {
  if (props.node.columns.length > 0) return props.node.columns;
  const seen = new Set<string>();
  for (const r of rows.value) for (const k of Object.keys(r)) seen.add(k);
  seen.delete('key');
  return [...seen];
});

/**
 * The record a `form` shows.
 *
 * <p>A bound value that is a list is indexed by the entry handle's record key —
 * that is how "click a row, see it in a form" works without the program having
 * to mirror the selection into a second state key.
 */
const record = computed<Record<string, unknown> | null>(() => {
  const v = bound.value;
  if (Array.isArray(v)) {
    if (!props.recordKey) return null;
    const hit = v.find(
      (r) => r && typeof r === 'object' && String((r as Record<string, unknown>).key) === props.recordKey,
    );
    return (hit as Record<string, unknown>) ?? null;
  }
  if (v && typeof v === 'object') return v as Record<string, unknown>;
  return null;
});

/** Text: the state value when bound, else the literal. */
const textValue = computed<string>(() => {
  if (props.node.from) {
    const v = bound.value;
    if (v === undefined || v === null) return '';
    return typeof v === 'string' ? v : JSON.stringify(v);
  }
  return props.node.text ?? '';
});

const mdHtml = ref('');

watch(
  () => [props.node.type, textValue.value] as const,
  async ([type, text]) => {
    if (type !== 'markdown') return;
    mdHtml.value = DOMPurify.sanitize(await marked.parse(text));
  },
  { immediate: true },
);

function fire(event: string, key?: string): void {
  const action = props.node.on[event];
  if (action) emit('action', action, key);
}

function hasHandler(event: string): boolean {
  return Boolean(props.node.on[event]);
}

/** A row's key, by convention its `key` field — what `documents.list` returns. */
function keyOf(row: Record<string, unknown>, index: number): string {
  const k = row.key;
  return k === undefined || k === null ? String(index) : String(k);
}

function cell(row: Record<string, unknown>, column: string): string {
  const v = row[column];
  if (v === undefined || v === null) return '';
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}

const headingClass = computed(() =>
  depth.value === 0 ? 'text-xl font-semibold' : 'text-base font-semibold',
);
</script>

<template>
  <section v-if="node.type === 'page'" class="flex min-h-0 flex-col gap-3">
    <h2 v-if="node.label" :class="headingClass">{{ node.label }}</h2>
    <WidgetNode
      v-for="(child, i) in node.children"
      :key="i"
      :node="child"
      :state="state"
      :record-key="recordKey"
      :depth="depth + 1"
      @action="(a, k) => emit('action', a, k)"
    />
  </section>

  <div v-else-if="node.type === 'toolbar'" class="flex flex-wrap items-center gap-2">
    <WidgetNode
      v-for="(child, i) in node.children"
      :key="i"
      :node="child"
      :state="state"
      :record-key="recordKey"
      :depth="depth + 1"
      @action="(a, k) => emit('action', a, k)"
    />
  </div>

  <VButton
    v-else-if="node.type === 'button'"
    size="sm"
    :variant="hasHandler('click') ? 'primary' : 'ghost'"
    @click="fire('click')"
  >
    {{ node.label }}
  </VButton>

  <p v-else-if="node.type === 'text'" class="text-sm opacity-80 whitespace-pre-line">
    {{ textValue }}
  </p>

  <div v-else-if="node.type === 'markdown'" class="prose prose-sm max-w-none" v-html="mdHtml" />

  <div v-else-if="node.type === 'table'" class="flex flex-col gap-2">
    <h3 v-if="node.label" class="text-base font-semibold">{{ node.label }}</h3>

    <VEmptyState
      v-if="rows.length === 0"
      headline="Nothing to show"
      :body="`The program has not put rows into \`${node.from}\` yet. It fills state with vance.state.set('${node.from}', rows).`"
    />

    <div v-else class="overflow-x-auto">
      <table class="w-full border-collapse text-sm">
        <thead>
          <tr class="border-b border-base-300 text-left">
            <th v-for="col in columns" :key="col" class="px-2 py-1 font-semibold opacity-60">
              {{ col }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="(row, i) in rows"
            :key="keyOf(row, i)"
            :class="[
              'border-b border-base-200',
              hasHandler('rowClick') ? 'cursor-pointer hover:bg-base-200' : '',
              recordKey && keyOf(row, i) === recordKey ? 'bg-base-200' : '',
            ]"
            @click="fire('rowClick', keyOf(row, i))"
          >
            <td v-for="col in columns" :key="col" class="px-2 py-1">{{ cell(row, col) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <div v-else-if="node.type === 'form'" class="flex flex-col gap-2">
    <h3 v-if="node.label" class="text-base font-semibold">{{ node.label }}</h3>
    <FormFieldsView :fields="node.fields" :record="record" />
  </div>

  <div v-else-if="node.type === 'tabs'" class="flex min-h-0 flex-col gap-2">
    <div class="flex flex-wrap gap-1">
      <VButton
        v-for="(child, i) in node.children"
        :key="i"
        size="sm"
        :variant="activeTab === i ? 'primary' : 'ghost'"
        @click="activeTab = i"
      >
        {{ child.label ?? `Tab ${i + 1}` }}
      </VButton>
    </div>
    <WidgetNode
      v-if="node.children[activeTab]"
      :node="node.children[activeTab]"
      :state="state"
      :record-key="recordKey"
      :depth="depth + 1"
      @action="(a, k) => emit('action', a, k)"
    />
  </div>

  <!-- Unreachable while the server enforces the whitelist. A visible failure
       rather than an empty div, because an empty div in a generic renderer is
       the hardest thing there is to debug. -->
  <VAlert v-else variant="warning">
    This build cannot render a <code class="font-mono">{{ node.type }}</code> widget.
  </VAlert>
</template>
