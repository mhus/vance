<script setup lang="ts">
import { computed, inject, onBeforeUnmount, ref, watch, type Component } from 'vue';
import {
  CodeEditor,
  FormFields,
  VAlert,
  VBadge,
  VButton,
  VCard,
  VCheckbox,
  VEmptyState,
  VFileInput,
  VInput,
  VModal,
  VPagination,
  VSelect,
  type FormValue,
} from '@vance/components';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import FormFieldsView from './FormFieldsView.vue';
import { fromFormModel, toFormModel } from './formModel';
import { isVisible } from './visibility';
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
 *
 * <p>A `form` uses that same binding in the other direction: what the reader
 * types goes back into the key it came from. Nothing is stored anywhere — the
 * program reads the key and decides. A `details` is the read-only twin, so an
 * author never has to reason about a `readOnly` default.
 */
const props = defineProps<{
  node: ViewNode;
  /** The host state the program writes. */
  state: Record<string, unknown>;
  /** Record key from the entry handle, for a detail view. */
  recordKey: string | null;
  /** A path in the app's grammar → the project path. Supplied by the host. */
  resolve: (path: string) => string;
  /**
   * Inside a `repeat`, the element being rendered.
   *
   * <p>`from:` is asked of this first and falls back to the surrounding state.
   * Two levels, no path syntax — the widget still names a key, it is just asked
   * of the element. Anything deeper would be the start of an expression
   * language, and there is already exactly one of those in the browser.
   */
  scope?: Record<string, unknown> | null;
  depth?: number;
}>();

const emit = defineEmits<{
  (e: 'action', action: ViewAction, recordKey?: string): void;
  /** A form edit. The host owns state; this widget only says what it became. */
  (e: 'state', key: string, value: unknown): void;
}>();

const depth = computed(() => props.depth ?? 0);

/** Which tab is open. Client state — nothing is written. */
const activeTab = ref(0);

/** The bound value: the repeat element first, the surrounding state otherwise. */
function lookup(key: string): unknown {
  const scope = props.scope;
  if (scope && Object.prototype.hasOwnProperty.call(scope, key)) return scope[key];
  return props.state[key];
}

const bound = computed<unknown>(() => (props.node.from ? lookup(props.node.from) : undefined));

/** Whether this widget is shown at all — see `visibility.ts` for the rule. */
const visible = computed<boolean>(() => isVisible(props.node, lookup));

/**
 * The tabs a reader can actually reach.
 *
 * <p>Filtered here rather than left to each pane's own gate, because the open
 * tab is an **index**: a hidden child that still occupies a slot would shift
 * every tab behind it, and clicking "Report" would open something else.
 */
const visibleTabs = computed(() => props.node.children.filter((c) => isVisible(c, lookup)));

/**
 * Keep the open tab in range when the set of tabs changes under it.
 *
 * <p>A program that flips a `show:` key can make the open tab vanish. Clamping
 * to the last one rather than resetting to the first: the author put the tabs in
 * an order, and a reader deep in that order is closer to the end than to the
 * beginning.
 */
watch(visibleTabs, (tabs) => {
  if (activeTab.value >= tabs.length) activeTab.value = Math.max(0, tabs.length - 1);
});

/** Elements of a `repeat`. Anything not a list repeats zero times. */
const items = computed<unknown[]>(() => (Array.isArray(bound.value) ? bound.value : []));

/**
 * What an `embed` points at, as a `vance:` URI.
 *
 * <p>The author writes a path in the same grammar as everywhere else in an app
 * — relative to the app folder, leading slash for the project root. The URI is
 * built from the resolved path, so nobody has to learn a second spelling for
 * "this document".
 */
const embedUri = computed<string | null>(() => {
  const raw = props.node.from ? bound.value : props.node.text;
  if (typeof raw !== 'string' || raw.trim() === '') return null;
  return `vance:/${props.resolve(raw.trim())}`;
});

/**
 * The Cortex embed renderer, if there is a host that provides one.
 *
 * <p>Injected rather than imported: it routes on document kind and pulls in
 * every kind renderer the host knows. This addon shipping its own would mean
 * shipping a charting library, a PDF viewer and a mindmap — which is exactly
 * why there is no `chart` and no `image` widget.
 */
const embedComponent = inject<Component | null>('vance:embed-component', null);

/**
 * The host's markdown renderer — the same one the Cortex, the chat and the
 * inbox use, so `vance:` links become embed cards and a fenced kind becomes an
 * inline canvas.
 *
 * <p>Injected for the same reason as the embed renderer, and it is the reason
 * this widget does not simply call `marked` itself: that path cannot resolve a
 * document reference and does not know the kind registry, so it renders a
 * `vance:` link as a dead URL and a `mermaid` fence as a code block. Moving the
 * renderer into `@vance/components` was the alternative and it is the wrong
 * one — it reaches the document-ref store, the registry and the link handler,
 * so it belongs where those live.
 *
 * <p>Absent on a surface without a host (a standalone view preview). Then the
 * local `marked` path renders, which is plain markdown and says so.
 */
const markdownComponent = inject<Component | null>('vance:markdown-component', null);

// ── direct input ───────────────────────────────────────────────────

/**
 * What an input shows, as the control wants it.
 *
 * <p>The four direct inputs write **native** values into state — a number stays
 * a number, a toggle a boolean. That is the whole difference to `form`, which
 * goes through `FormFieldDto` and its string encoding: a form's values are on
 * their way into a *document* and have to round-trip, while these are on their
 * way into *state*, where the program decides what to make of them. Nothing to
 * preserve, so nothing to encode.
 */
const inputText = computed<string>(() => {
  const v = bound.value;
  return v === undefined || v === null ? '' : String(v);
});

const inputChecked = computed<boolean>(() => {
  const v = bound.value;
  return v === true || v === 'true' || v === 1;
});

/** Write a value into the bound key, then let `on.change` fire if there is one. */
function writeBound(value: unknown): void {
  const key = props.node.from;
  if (!key) return;
  emit('state', key, value);
  scheduleChange();
}

/**
 * An emptied number field means "no value", not zero.
 *
 * <p>Zero is a number somebody may have typed on purpose, so it cannot double
 * as the empty marker. `null` says the field is blank and survives the trip to
 * the guest, which `undefined` would not.
 */
function writeNumber(raw: string): void {
  const text = raw.trim();
  if (text === '') {
    writeBound(null);
    return;
  }
  const n = Number(text);
  writeBound(Number.isNaN(n) ? text : n);
}

/**
 * A `pagination`'s three numbers, from the one key it is bound to.
 *
 * <p>Missing fields count as zero rather than as an error: a program that has
 * not filled the key yet renders a pager over nothing, which is the same
 * "waiting for the program" state every other widget shows.
 */
const paging = computed(() => {
  const v = bound.value;
  const o = v && typeof v === 'object' ? (v as Record<string, unknown>) : {};
  return {
    page: Number(o.page) || 0,
    pageSize: Number(o.pageSize) || 20,
    totalCount: Number(o.totalCount) || 0,
  };
});

/** Change the page and hand the rest of the object back untouched. */
function writePage(page: number): void {
  const v = bound.value;
  const base = v && typeof v === 'object' ? (v as Record<string, unknown>) : {};
  writeBound({ ...base, page });
}

/**
 * Read picked files as **text** and put `{name, text}` into state.
 *
 * <p>The whole reason this widget is not a pass-through: a `File` object would
 * cross into the sandbox and be useless there — nothing in `vance.*` takes one.
 * Its text is useful in one line (`documents.write`), so that is what the
 * program gets. Binary would arrive as mojibake; the manual says so rather than
 * the code pretending to detect it, because "is this text" has no honest answer
 * at this layer.
 */
async function readFiles(files: File[]): Promise<void> {
  const out: { name: string; size: number; text: string }[] = [];
  for (const f of files) {
    out.push({ name: f.name, size: f.size, text: await f.text() });
  }
  writeBound(out);
}

/** Options as `VSelect` wants them. The parser already filled every label. */
const selectOptions = computed(() =>
  props.node.options.map((o) => ({ value: o.value, label: o.label })),
);

/**
 * Rows of a `table`, each carrying the key it had **before** any sorting.
 *
 * <p>The key is fixed here rather than derived from the display position, and
 * that is what makes sorting safe: a row without its own `key` field falls back
 * to its index, and if that index were the *displayed* one, sorting would
 * silently rename every such row — a `rowClick` would then hand a detail view
 * the wrong record. The program's order is the row's identity.
 */
const rows = computed<{ row: Record<string, unknown>; key: string; index: number }[]>(() => {
  const v = bound.value;
  if (!Array.isArray(v)) return [];
  const out: { row: Record<string, unknown>; key: string; index: number }[] = [];
  for (let i = 0; i < v.length; i++) {
    const r = v[i];
    if (!r || typeof r !== 'object') continue;
    const row = r as Record<string, unknown>;
    const k = row.key;
    // The position in the *program's* array, not in this list: it is what a
    // write has to put the edited record back into.
    out.push({ row, key: k === undefined || k === null ? String(i) : String(k), index: i });
  }
  return out;
});

// ── sorting and filtering: client state, like the open tab ──────────
//
// Neither is a state key and neither is in the URL. A reader's sort order is
// not something the program decided, so it has no business in the program's
// state — and putting it there would mean every table write triggered a guest
// round trip.

const sortColumn = ref<string | null>(null);
const sortDescending = ref(false);
const tableFilter = ref('');

/**
 * Three steps, not two: ascending, descending, **off**.
 *
 * <p>Off has to be reachable, because the order the program produced is itself
 * information — newest first, ranked, in the sequence the documents were read.
 * A two-state toggle would make that order unrecoverable without a reload.
 */
function toggleSort(column: string): void {
  if (sortColumn.value !== column) {
    sortColumn.value = column;
    sortDescending.value = false;
  } else if (!sortDescending.value) {
    sortDescending.value = true;
  } else {
    sortColumn.value = null;
    sortDescending.value = false;
  }
}

function sortMarker(column: string): string {
  if (sortColumn.value !== column) return '';
  return sortDescending.value ? ' ↓' : ' ↑';
}

/**
 * Above this many rows a table gets a filter box.
 *
 * <p>A threshold rather than a schema flag, for the reason already written down
 * for long choice lists in `FormFields`: how many rows justify a filter box is
 * a property of the renderer, not of the table's meaning — and an author cannot
 * know how many rows a program will put there at render time. It also spares
 * the schema a boolean whose default would be arguable either way.
 */
const FILTER_THRESHOLD = 10;

const showFilter = computed(() => rows.value.length > FILTER_THRESHOLD);

/**
 * What the table shows: filtered, then sorted.
 *
 * <p>Comparison is numeric when **both** values parse as numbers and textual
 * otherwise, so an `amount` column sorts 9 before 77 instead of after it. Mixed
 * columns fall back to text, which is at least stable.
 */
const tableRows = computed(() => {
  const needle = tableFilter.value.trim().toLowerCase();
  let out = rows.value;
  if (needle) {
    out = out.filter(({ row }) =>
      columns.value.some((c) => cell(row, c).toLowerCase().includes(needle)),
    );
  }
  const column = sortColumn.value;
  if (!column) return out;
  const factor = sortDescending.value ? -1 : 1;
  // Copied before sorting: `out` may still be the array the program owns.
  return [...out].sort((a, b) => factor * compareCells(a.row[column], b.row[column]));
});

function compareCells(a: unknown, b: unknown): number {
  const emptyA = a === undefined || a === null || a === '';
  const emptyB = b === undefined || b === null || b === '';
  // Empty sorts last in both directions: it is the absence of a value, not the
  // smallest one, and a column of blanks at the top hides the data.
  if (emptyA || emptyB) return emptyA && emptyB ? 0 : emptyA ? 1 : -1;
  const na = Number(a);
  const nb = Number(b);
  if (!Number.isNaN(na) && !Number.isNaN(nb)) return na - nb;
  return String(a).localeCompare(String(b));
}

/**
 * Columns: what the widget asks for, else the union of the keys present.
 *
 * <p>A widget naming a column the rows do not have still shows it, as empty
 * cells. Silently dropping it would make a typo look like missing data.
 */
const columns = computed<string[]>(() => {
  if (props.node.columns.length > 0) return props.node.columns;
  const seen = new Set<string>();
  for (const { row } of rows.value) for (const k of Object.keys(row)) seen.add(k);
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
    // The same keying the table displays, so a row without its own `key` field
    // is reachable too — matching `row.key` directly never found one of those.
    return rows.value.find((e) => e.key === props.recordKey)?.row ?? null;
  }
  if (v && typeof v === 'object') return v as Record<string, unknown>;
  return null;
});

/** What the form engine edits: the record, in its string encoding. */
const formModel = computed<Record<string, FormValue>>(() =>
  toFormModel(record.value, props.node.fields),
);

/**
 * An edit: decode back to the program's types, put it where it came from.
 *
 * <p>Written to the bound key as a whole value rather than mutated in place —
 * the host owns state, and one writer is what keeps "who changed this" a
 * question with an answer. When the binding is a list indexed by the entry
 * handle, the edited record replaces that one element and the rest is copied
 * across untouched.
 */
function onFormInput(model: Record<string, FormValue>): void {
  const key = props.node.from;
  if (!key) return;
  const merged = fromFormModel(model, props.node.fields, record.value);
  const current = bound.value;

  if (Array.isArray(current)) {
    const hit = rows.value.find((e) => e.key === props.recordKey);
    if (!hit) return;
    // By position, not by matching the key again: one place decides what a row
    // is called, and the write puts the record back exactly where it came from.
    emit(
      'state',
      key,
      current.map((row, i) => (i === hit.index ? merged : row)),
    );
  } else {
    emit('state', key, merged);
  }
  scheduleChange();
}

/**
 * `on: { change: … }`, one step behind the keystroke.
 *
 * <p>Debounced because the handler crosses into the sandbox, where calls are
 * serialised: a fast typist would otherwise queue one program invocation per
 * character and the last one — the only one whose result the reader sees —
 * would arrive after all of them. The delay is short enough to read as
 * immediate and long enough that a word costs one call, not eight.
 */
const CHANGE_DELAY_MS = 150;
let changeTimer: ReturnType<typeof setTimeout> | null = null;

function scheduleChange(): void {
  const action = props.node.on.change;
  if (!action) return;
  if (changeTimer !== null) clearTimeout(changeTimer);
  changeTimer = setTimeout(() => {
    changeTimer = null;
    emit('action', action);
  }, CHANGE_DELAY_MS);
}

onBeforeUnmount(() => {
  // A pending change would otherwise fire into a program that is being torn
  // down, and the error would name a widget the reader can no longer see.
  if (changeTimer !== null) clearTimeout(changeTimer);
});

/** Text: the bound value when there is one, else the literal. */
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
  <!-- `show:` gates every widget from one place. Per-branch it would be six
       chances to forget one, and a forgotten gate is a widget that ignores a
       condition the document states. -->
  <template v-if="!visible" />

  <section v-else-if="node.type === 'page'" class="flex min-h-0 flex-col gap-3">
    <h2 v-if="node.label" :class="headingClass">{{ node.label }}</h2>
    <WidgetNode
      v-for="(child, i) in node.children"
      :key="i"
      :node="child"
      :state="state"
      :record-key="recordKey"
      :resolve="resolve"
      :scope="scope"
      :depth="depth + 1"
      @action="(a, k) => emit('action', a, k)"
      @state="(k, v) => emit('state', k, v)"
    />
  </section>

  <div v-else-if="node.type === 'column'" class="flex flex-col gap-3">
    <WidgetNode
      v-for="(child, i) in node.children"
      :key="i"
      :node="child"
      :state="state"
      :record-key="recordKey"
      :resolve="resolve"
      :scope="scope"
      :depth="depth + 1"
      @action="(a, k) => emit('action', a, k)"
      @state="(k, v) => emit('state', k, v)"
    />
  </div>

  <!-- A row divides the width; a toolbar sizes to its content and wraps. Same
       axis, different job — `flex-1` on the children is the whole difference. -->
  <div v-else-if="node.type === 'row'" class="flex flex-wrap items-end gap-3">
    <div v-for="(child, i) in node.children" :key="i" class="min-w-40 flex-1">
      <WidgetNode
        :node="child"
        :state="state"
        :record-key="recordKey"
        :resolve="resolve"
        :scope="scope"
        :depth="depth + 1"
        @action="(a, k) => emit('action', a, k)"
        @state="(k, v) => emit('state', k, v)"
      />
    </div>
  </div>

  <div v-else-if="node.type === 'toolbar'" class="flex flex-wrap items-center gap-2">
    <WidgetNode
      v-for="(child, i) in node.children"
      :key="i"
      :node="child"
      :state="state"
      :record-key="recordKey"
      :resolve="resolve"
      :scope="scope"
      :depth="depth + 1"
      @action="(a, k) => emit('action', a, k)"
      @state="(k, v) => emit('state', k, v)"
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

  <div v-else-if="node.type === 'markdown'">
    <!-- The host renderer when there is one: `vance:` links resolve, fenced
         kinds render. The local `marked` fallback is plain markdown — correct
         for a surface with no Cortex around it, and visibly less. -->
    <component :is="markdownComponent" v-if="markdownComponent" :source="textValue" />
    <div v-else class="prose prose-sm max-w-none" v-html="mdHtml" />
  </div>

  <VCard v-else-if="node.type === 'card'" :title="node.label ?? undefined">
    <div class="flex flex-col gap-3">
      <WidgetNode
        v-for="(child, i) in node.children"
        :key="i"
        :node="child"
        :state="state"
        :record-key="recordKey"
        :resolve="resolve"
        :scope="scope"
        :depth="depth + 1"
        @action="(a, k) => emit('action', a, k)"
        @state="(k, v) => emit('state', k, v)"
      />
    </div>
  </VCard>

  <VBadge
    v-else-if="node.type === 'badge'"
    :variant="(node.variant ?? 'neutral') as never"
  >
    {{ textValue }}
  </VBadge>

  <VAlert
    v-else-if="node.type === 'alert'"
    :variant="(node.variant ?? 'info') as never"
    class="whitespace-pre-line"
  >
    {{ textValue }}
  </VAlert>

  <!-- Read-only on purpose: `code` is to `markdown` what a listing is to
       prose. An editable one would be an input widget and would say so in
       its name. -->
  <CodeEditor
    v-else-if="node.type === 'code'"
    :model-value="textValue"
    :mime-type="node.mimeType"
    :label="node.label ?? undefined"
    readonly
  />

  <div v-else-if="node.type === 'pagination'" class="flex justify-center">
    <VPagination
      :page="paging.page"
      :page-size="paging.pageSize"
      :total-count="paging.totalCount"
      @update:page="writePage"
    />
  </div>

  <VFileInput
    v-else-if="node.type === 'file'"
    :model-value="[]"
    :label="node.label ?? undefined"
    :accept="node.accept ?? undefined"
    @update:model-value="readFiles"
  />

  <!-- The four direct inputs. One widget, one state key, the native type —
       no field list and no string encoding on the way in. -->
  <VInput
    v-else-if="node.type === 'input'"
    :model-value="inputText"
    :label="node.label ?? undefined"
    @update:model-value="(v: string) => writeBound(v)"
  />

  <VInput
    v-else-if="node.type === 'number'"
    :model-value="inputText"
    type="number"
    :label="node.label ?? undefined"
    @update:model-value="(v: string) => writeNumber(v)"
  />

  <VCheckbox
    v-else-if="node.type === 'toggle'"
    :model-value="inputChecked"
    :label="node.label ?? ''"
    @update:model-value="(v: boolean) => writeBound(v)"
  />

  <VSelect
    v-else-if="node.type === 'select'"
    :model-value="inputText || null"
    :label="node.label ?? undefined"
    :options="selectOptions"
    placeholder="—"
    @update:model-value="(v: string | null) => writeBound(v ?? '')"
  />

  <div v-else-if="node.type === 'table'" class="flex flex-col gap-2">
    <h3 v-if="node.label" class="text-base font-semibold">{{ node.label }}</h3>

    <!-- The filter is the reader's, not the program's: it narrows what is on
         screen and never touches state. -->
    <VInput
      v-if="showFilter"
      :model-value="tableFilter"
      placeholder="Filter…"
      @update:model-value="(v: string) => (tableFilter = v)"
    />

    <VEmptyState
      v-if="rows.length === 0"
      headline="Nothing to show"
      :body="`The program has not put rows into \`${node.from}\` yet. It fills state with vance.state.set('${node.from}', rows).`"
    />

    <VEmptyState
      v-else-if="tableRows.length === 0"
      headline="Nothing matches the filter"
      :body="`${rows.length} row(s) are hidden by »${tableFilter}«.`"
    />

    <div v-else class="overflow-x-auto">
      <table class="w-full border-collapse text-sm">
        <thead>
          <tr class="border-b border-base-300 text-left">
            <!-- A header is a button in effect: three clicks cycle
                 ascending → descending → the program's own order. -->
            <th
              v-for="col in columns"
              :key="col"
              class="cursor-pointer px-2 py-1 font-semibold opacity-60 select-none hover:opacity-100"
              :title="`Sort by ${col}`"
              @click="toggleSort(col)"
            >
              {{ col }}{{ sortMarker(col) }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="entry in tableRows"
            :key="entry.key"
            :class="[
              'border-b border-base-200',
              hasHandler('rowClick') ? 'cursor-pointer hover:bg-base-200' : '',
              recordKey && entry.key === recordKey ? 'bg-base-200' : '',
            ]"
            @click="fire('rowClick', entry.key)"
          >
            <td v-for="col in columns" :key="col" class="px-2 py-1">
              {{ cell(entry.row, col) }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <div v-else-if="node.type === 'form'" class="flex flex-col gap-2">
    <h3 v-if="node.label" class="text-base font-semibold">{{ node.label }}</h3>
    <!-- A form bound to a list needs to know which row; without a record key
         there is nothing to edit, and an empty form would look like a bug. -->
    <VEmptyState
      v-if="!record && Array.isArray(bound)"
      headline="Nothing selected"
      body="Click a row in the table to edit it here."
    />
    <FormFields
      v-else
      :fields="node.fields"
      :model-value="formModel"
      @update:model-value="onFormInput"
    />
  </div>

  <div v-else-if="node.type === 'details'" class="flex flex-col gap-2">
    <h3 v-if="node.label" class="text-base font-semibold">{{ node.label }}</h3>
    <FormFieldsView :fields="node.fields" :record="record" />
  </div>

  <div v-else-if="node.type === 'tabs'" class="flex min-h-0 flex-col gap-2">
    <div class="flex flex-wrap gap-1">
      <VButton
        v-for="(child, i) in visibleTabs"
        :key="i"
        size="sm"
        :variant="activeTab === i ? 'primary' : 'ghost'"
        @click="activeTab = i"
      >
        {{ child.label ?? `Tab ${i + 1}` }}
      </VButton>
    </div>
    <WidgetNode
      v-if="visibleTabs[activeTab]"
      :node="visibleTabs[activeTab]"
      :state="state"
      :record-key="recordKey"
      :resolve="resolve"
      :scope="scope"
      :depth="depth + 1"
      @action="(a, k) => emit('action', a, k)"
      @state="(k, v) => emit('state', k, v)"
    />
  </div>

  <!-- `repeat`: children once per element, with the element as the inner
       scope. `key` by index because an element need not carry an id — and a
       list a program rebuilt wholesale re-renders anyway. -->
  <div v-else-if="node.type === 'repeat'" class="flex flex-col gap-3">
    <h3 v-if="node.label" class="text-base font-semibold">{{ node.label }}</h3>
    <VEmptyState
      v-if="items.length === 0"
      headline="Nothing here yet"
      :body="`The program has not put a list into \`${node.from}\`.`"
    />
    <template v-for="(item, i) in items" v-else :key="i">
      <WidgetNode
        v-for="(child, c) in node.children"
        :key="`${i}-${c}`"
        :node="child"
        :state="state"
        :record-key="recordKey"
        :resolve="resolve"
        :scope="(item as Record<string, unknown>) ?? null"
        :depth="depth + 1"
        @action="(a, k) => emit('action', a, k)"
        @state="(k, v) => emit('state', k, v)"
      />
    </template>
  </div>

  <div v-else-if="node.type === 'embed'" class="flex flex-col gap-2">
    <h3 v-if="node.label" class="text-base font-semibold">{{ node.label }}</h3>
    <VAlert v-if="!embedUri" variant="info">
      Nothing to embed — <code class="font-mono">{{ node.from ?? '(no path)' }}</code> holds no
      document path.
    </VAlert>
    <component :is="embedComponent" v-else-if="embedComponent" :uri="embedUri" />
    <!-- No host renderer: say which document was meant rather than nothing.
         Standalone renders of an app view have no Cortex around them. -->
    <VAlert v-else variant="info">
      This surface cannot render embedded documents. Meant:
      <code class="font-mono">{{ embedUri }}</code>
    </VAlert>
  </div>

  <!-- A dialog has no close button of its own and no `vance.ui.closeDialog()`:
       its `show:` key is the whole mechanism, so the ✕ writes it back to false
       through the same path the program uses. One rule, not three. -->
  <VModal
    v-else-if="node.type === 'dialog'"
    :model-value="true"
    :title="node.label ?? undefined"
    @update:model-value="() => node.show && emit('state', node.show, false)"
  >
    <div class="flex flex-col gap-3">
      <WidgetNode
        v-for="(child, i) in node.children"
        :key="i"
        :node="child"
        :state="state"
        :record-key="recordKey"
        :resolve="resolve"
        :scope="scope"
        :depth="depth + 1"
        @action="(a, k) => emit('action', a, k)"
        @state="(k, v) => emit('state', k, v)"
      />
    </div>
  </VModal>

  <!-- Unreachable while the server enforces the whitelist. A visible failure
       rather than an empty div, because an empty div in a generic renderer is
       the hardest thing there is to debug. -->
  <VAlert v-else variant="warning">
    This build cannot render a <code class="font-mono">{{ node.type }}</code> widget.
  </VAlert>
</template>
