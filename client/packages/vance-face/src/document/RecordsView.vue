<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VueDraggable } from 'vue-draggable-plus';
import { VButton } from '@/components';
import type { RecordsDocument, RecordsItem } from './recordsCodec';
import { emptyRecord } from './recordsCodec';

/**
 * Editor for `kind: records` documents — flat table with a fixed
 * schema and one record per row. Click a cell to edit, Enter to
 * commit and jump to the same column of the next row, Tab/Shift+Tab
 * to walk cells left-to-right, Esc to cancel. Bulk-select rows via
 * Cmd/Ctrl-click on the drag-handle column.
 *
 * Mutations bubble up through {@code update:doc} as a fresh
 * {@link RecordsDocument}; the parent re-serialises into the raw
 * body so the existing Save button writes the canonical form.
 *
 * Spec: `specification/doc-kind-records.md`.
 */
const props = defineProps<{ doc: RecordsDocument }>();

const emit = defineEmits<{
  (event: 'update:doc', doc: RecordsDocument): void;
}>();

const { t } = useI18n();

// ── Editor state ────────────────────────────────────────────────────

/** Local mutable copy of the schema. Schema-edit mode mutates this
 *  in place; the body cells re-render against it immediately. */
const localSchema = ref<string[]>([...props.doc.schema]);
/** Local mutable copy of the record list — vue-draggable-plus mutates
 *  the array via `v-model` during a drag. The watch below mirrors
 *  external doc updates back into this ref (e.g. Raw-tab edits). */
const localItems = ref<RecordsItem[]>(cloneItems(props.doc.items));

watch(
  () => props.doc.items,
  (next) => { localItems.value = cloneItems(next); },
  { deep: true },
);
watch(
  () => props.doc.schema,
  (next) => {
    localSchema.value = [...next];
    localItems.value = cloneItems(props.doc.items);
  },
  { deep: true },
);

/** {row, field} of the currently editing cell. `null` = no edit. */
const editingRow = ref<number | null>(null);
const editingField = ref<string | null>(null);
/** Buffered text of the input in flight. */
const editBuffer = ref('');
/** Refs to the input elements keyed by {@code `${row}.${field}`}. */
const inputRefs = ref<Map<string, HTMLInputElement>>(new Map());

/** Multi-select state — row-level. Set of row indices. */
const selectedRows = ref<Set<number>>(new Set());
const selectionAnchor = ref<number | null>(null);

/** When true, header cells become editable and body-cell editing is
 *  blocked. See spec §5.6 (Schema-Editor). */
const schemaMode = ref(false);
/** Last validation error from a schema mutation — surfaces in the
 *  toolbar so the user knows why a rename was rejected. Cleared on
 *  every successful schema operation and on schema-mode toggle. */
const schemaError = ref<string | null>(null);

const schema = computed<string[]>(() => localSchema.value);

function cellKey(row: number, field: string): string {
  return `${row}.${field}`;
}

function cloneItems(src: RecordsItem[]): RecordsItem[] {
  return src.map((it) => ({
    values: { ...it.values },
    extra: { ...it.extra },
    overflow: [...it.overflow],
  }));
}

function emitDoc(): void {
  emit('update:doc', {
    kind: props.doc.kind || 'records',
    schema: [...localSchema.value],
    items: localItems.value,
    extra: props.doc.extra,
  });
}

function clearSelection(): void {
  selectedRows.value = new Set();
  selectionAnchor.value = null;
}

// ── Edit lifecycle ──────────────────────────────────────────────────

function startEdit(row: number, field: string): void {
  if (schemaMode.value) return;
  editingRow.value = row;
  editingField.value = field;
  editBuffer.value = localItems.value[row]?.values[field] ?? '';
  void nextTick(() => {
    const el = inputRefs.value.get(cellKey(row, field));
    if (el) {
      el.focus();
      el.setSelectionRange(el.value.length, el.value.length);
    }
  });
}

function cancelEdit(): void {
  editingRow.value = null;
  editingField.value = null;
  editBuffer.value = '';
}

function commitEdit(): void {
  if (editingRow.value == null || editingField.value == null) return;
  const row = editingRow.value;
  const field = editingField.value;
  const item = localItems.value[row];
  if (item) {
    // Replace the values map so Vue sees a new reference — keeps
    // reactivity tight when downstream watches lean on identity.
    item.values = { ...item.values, [field]: editBuffer.value };
  }
  editingRow.value = null;
  editingField.value = null;
  editBuffer.value = '';
  emitDoc();
}

// ── Keyboard navigation between cells ───────────────────────────────

function onEditKeydown(event: KeyboardEvent, row: number, field: string): void {
  if (event.key === 'Enter') {
    event.preventDefault();
    commitEdit();
    // Same column, next row — append a new row first if we're at the end.
    let nextRow = row + 1;
    if (nextRow >= localItems.value.length) {
      addRow();
      nextRow = localItems.value.length - 1;
    }
    void nextTick(() => startEdit(nextRow, field));
    return;
  }
  if (event.key === 'Escape') {
    event.preventDefault();
    cancelEdit();
    return;
  }
  if (event.key === 'Tab') {
    event.preventDefault();
    commitEdit();
    if (event.shiftKey) {
      const target = prevCell(row, field);
      if (target) void nextTick(() => startEdit(target.row, target.field));
    } else {
      const target = nextCell(row, field);
      if (target) {
        void nextTick(() => startEdit(target.row, target.field));
      } else {
        // At the very last cell — append a new row and jump there.
        addRow();
        void nextTick(() => startEdit(localItems.value.length - 1, schema.value[0]));
      }
    }
    return;
  }
  if (event.key === 'Backspace' && editBuffer.value === '' && rowAllEmpty(row, field)) {
    event.preventDefault();
    deleteRow(row);
    if (row > 0) {
      void nextTick(() => startEdit(row - 1, field));
    }
  }
}

/** True if every schema-field of {@code row} except {@code skipField}
 *  is already empty. The caller passes the field whose buffer is in
 *  flight so the live edit-buffer check covers the current cell. */
function rowAllEmpty(row: number, skipField: string): boolean {
  const item = localItems.value[row];
  if (!item) return true;
  for (const f of schema.value) {
    if (f === skipField) continue;
    if ((item.values[f] ?? '').length > 0) return false;
  }
  return true;
}

function nextCell(row: number, field: string): { row: number; field: string } | null {
  const fields = schema.value;
  const fi = fields.indexOf(field);
  if (fi >= 0 && fi < fields.length - 1) {
    return { row, field: fields[fi + 1] };
  }
  if (row < localItems.value.length - 1) {
    return { row: row + 1, field: fields[0] };
  }
  return null;
}

function prevCell(row: number, field: string): { row: number; field: string } | null {
  const fields = schema.value;
  const fi = fields.indexOf(field);
  if (fi > 0) return { row, field: fields[fi - 1] };
  if (row > 0) return { row: row - 1, field: fields[fields.length - 1] };
  return null;
}

// ── Row CRUD ────────────────────────────────────────────────────────

function addRow(): void {
  localItems.value.push(emptyRecord(schema.value));
  clearSelection();
  emitDoc();
}

function addRowAndEdit(): void {
  addRow();
  void nextTick(() => startEdit(localItems.value.length - 1, schema.value[0]));
}

function deleteRow(row: number): void {
  if (schemaMode.value) return;
  localItems.value.splice(row, 1);
  cancelEdit();
  clearSelection();
  emitDoc();
}

function deleteSelected(): void {
  if (selectedRows.value.size === 0) return;
  const sorted = [...selectedRows.value].sort((a, b) => b - a);
  for (const idx of sorted) {
    localItems.value.splice(idx, 1);
  }
  cancelEdit();
  clearSelection();
  emitDoc();
}

// ── Schema editing (v2) ─────────────────────────────────────────────
//
// Schema-mode is an explicit toggle. While active, body cells are
// frozen (cell click / row drag / row select disabled), and the
// header row exposes per-column controls: rename input, ←/→ move
// arrows, ✕ delete. A `+ column` button at the end appends a new
// column with a default name. Spec: `doc-kind-records.md` §5.6.

function toggleSchemaMode(): void {
  if (schemaMode.value) {
    // Leaving schema mode — no commit step needed, every operation
    // already emitted on the way in. Just clear transient state.
    schemaMode.value = false;
    schemaError.value = null;
    return;
  }
  cancelEdit();
  clearSelection();
  schemaError.value = null;
  schemaMode.value = true;
}

function addColumn(): void {
  const name = nextDefaultColumnName();
  localSchema.value.push(name);
  for (const item of localItems.value) {
    item.values = { ...item.values, [name]: '' };
  }
  schemaError.value = null;
  emitDoc();
}

/** Build a default name like `column_3`, picking the smallest
 *  positive integer that doesn't collide with an existing field. */
function nextDefaultColumnName(): string {
  const existing = new Set(localSchema.value);
  for (let n = localSchema.value.length + 1; n < 10000; n++) {
    const candidate = `column_${n}`;
    if (!existing.has(candidate)) return candidate;
  }
  // Astronomically unlikely fallback.
  return `column_${Date.now()}`;
}

function deleteColumn(idx: number): void {
  if (localSchema.value.length <= 1) {
    schemaError.value = t('documents.recordsEditor.schemaMinOneColumn');
    return;
  }
  const name = localSchema.value[idx];
  localSchema.value.splice(idx, 1);
  for (const item of localItems.value) {
    if (name in item.values) {
      const { [name]: _drop, ...rest } = item.values;
      item.values = rest;
    }
  }
  schemaError.value = null;
  emitDoc();
}

/** Validate-and-apply a column rename. {@code inputEl} is passed in
 *  so we can snap the visible value back to the old name when the
 *  rename is rejected — the input is bound by `:value`, not v-model,
 *  so its DOM state diverges from {@code localSchema} until commit. */
function renameColumn(idx: number, rawName: string, inputEl: HTMLInputElement): void {
  const newName = rawName.trim();
  const oldName = localSchema.value[idx];
  if (newName === oldName) {
    inputEl.value = oldName;
    return;
  }
  if (newName === '') {
    inputEl.value = oldName;
    schemaError.value = t('documents.recordsEditor.schemaEmptyName');
    return;
  }
  if (localSchema.value.includes(newName)) {
    inputEl.value = oldName;
    schemaError.value = t('documents.recordsEditor.schemaDuplicateName', { name: newName });
    return;
  }
  // Apply: schema slot + every record's keyed value
  localSchema.value[idx] = newName;
  for (const item of localItems.value) {
    const next: Record<string, string> = {};
    for (const f of localSchema.value) {
      // For the renamed slot, pull the value out of the OLD key.
      if (f === newName) {
        next[newName] = item.values[oldName] ?? '';
      } else {
        next[f] = item.values[f] ?? '';
      }
    }
    item.values = next;
  }
  schemaError.value = null;
  emitDoc();
}

function moveColumn(idx: number, direction: -1 | 1): void {
  const target = idx + direction;
  if (target < 0 || target >= localSchema.value.length) return;
  const arr = localSchema.value;
  [arr[idx], arr[target]] = [arr[target], arr[idx]];
  schemaError.value = null;
  emitDoc();
}

// ── Selection (row-level, triggered from drag-handle column) ────────
//
// Plain click on a cell starts cell-edit. Cmd/Ctrl-click on the
// drag-handle column toggles the row in/out of the multi-select;
// Shift-click on the drag-handle column extends the range from the
// last anchor.

function onHandleClick(event: MouseEvent, row: number): void {
  if (schemaMode.value) return;
  if (event.shiftKey && selectionAnchor.value != null) {
    event.preventDefault();
    const start = Math.min(selectionAnchor.value, row);
    const end = Math.max(selectionAnchor.value, row);
    const next = new Set(selectedRows.value);
    for (let i = start; i <= end; i++) next.add(i);
    selectedRows.value = next;
    cancelEdit();
    return;
  }
  if (event.metaKey || event.ctrlKey) {
    event.preventDefault();
    const next = new Set(selectedRows.value);
    if (next.has(row)) {
      next.delete(row);
    } else {
      next.add(row);
      selectionAnchor.value = row;
    }
    selectedRows.value = next;
    cancelEdit();
  }
  // Plain click on the handle without modifiers: leave the click
  // alone — vue-draggable will pick it up for a drag start.
}

function isRowSelected(row: number): boolean {
  return selectedRows.value.has(row);
}

// ── Drag reorder ────────────────────────────────────────────────────

function onDragStart(): void {
  cancelEdit();
  clearSelection();
}
function onDragEnd(): void {
  emitDoc();
}

// ── Layout ──────────────────────────────────────────────────────────

const gridStyle = computed(() => ({
  // Drag handle | N schema columns | delete button (or "+ column" in
  // schema mode — same auto-width slot, content swapped)
  gridTemplateColumns: `1.25rem repeat(${schema.value.length}, minmax(8rem, 1fr)) auto`,
}));

function selectionCount(): number {
  return selectedRows.value.size;
}

function registerInput(row: number, field: string, el: Element | null): void {
  const key = cellKey(row, field);
  if (el) {
    inputRefs.value.set(key, el as HTMLInputElement);
  } else {
    inputRefs.value.delete(key);
  }
}

const hasOverflow = computed(() =>
  localItems.value.some((it) => it.overflow.length > 0));
</script>

<template>
  <div class="records-edit" :class="{ 'records-edit--schema': schemaMode }">
    <!-- Toolbar: schema-mode toggle and (when active) inline error. -->
    <div class="records-toolbar">
      <VButton
        :variant="schemaMode ? 'primary' : 'ghost'"
        size="sm"
        @click="toggleSchemaMode"
      >
        {{ schemaMode
          ? t('documents.recordsEditor.doneEditingSchema')
          : t('documents.recordsEditor.editSchema') }}
      </VButton>
      <span v-if="schemaError" class="schema-error">{{ schemaError }}</span>
    </div>

    <!-- Bulk-action bar — only visible while a multi-select is live. -->
    <div v-if="selectionCount() > 0 && !schemaMode" class="bulk-bar">
      <span class="bulk-count">
        {{
          selectionCount() === 1
            ? t('documents.recordsEditor.selectedCountSingular', { count: selectionCount() })
            : t('documents.recordsEditor.selectedCountPlural', { count: selectionCount() })
        }}
      </span>
      <span class="grow" />
      <VButton variant="ghost" size="sm" @click="clearSelection">
        {{ t('documents.recordsEditor.clearSelection') }}
      </VButton>
      <VButton variant="danger" size="sm" @click="deleteSelected">
        {{ t('documents.recordsEditor.deleteSelected') }}
      </VButton>
    </div>

    <!-- Schema header row — sticky. In display-mode it's plain
         labels; in schema-mode it becomes a row of editable inputs
         with move-arrow / delete controls plus a `+ column` button.
         Spec §5.6. -->
    <div class="header-row" :style="gridStyle">
      <span class="header-cell handle-cell" aria-hidden="true" />
      <template v-if="!schemaMode">
        <span
          v-for="field in schema"
          :key="field"
          class="header-cell"
          :title="field"
        >{{ field }}</span>
        <span class="header-cell action-cell" aria-hidden="true" />
      </template>
      <template v-else>
        <div
          v-for="(field, idx) in schema"
          :key="field + '@' + idx"
          class="header-cell schema-edit"
        >
          <div class="schema-controls">
            <button
              type="button"
              class="schema-arrow"
              :disabled="idx === 0"
              :title="t('documents.recordsEditor.moveColumnLeft')"
              @click="moveColumn(idx, -1)"
            >←</button>
            <button
              type="button"
              class="schema-arrow"
              :disabled="idx === schema.length - 1"
              :title="t('documents.recordsEditor.moveColumnRight')"
              @click="moveColumn(idx, 1)"
            >→</button>
            <button
              type="button"
              class="schema-remove"
              :disabled="schema.length <= 1"
              :title="t('documents.recordsEditor.deleteColumn')"
              @click="deleteColumn(idx)"
            >✕</button>
          </div>
          <input
            type="text"
            class="schema-name-input"
            :value="field"
            @change="(e) => renameColumn(idx, (e.target as HTMLInputElement).value, e.target as HTMLInputElement)"
            @keydown.enter.prevent="($event.target as HTMLInputElement).blur()"
          />
        </div>
        <div class="header-cell action-cell">
          <button
            type="button"
            class="schema-add"
            :title="t('documents.recordsEditor.addColumn')"
            @click="addColumn"
          >+</button>
        </div>
      </template>
    </div>

    <VueDraggable
      v-if="localItems.length > 0"
      v-model="localItems"
      tag="div"
      class="rows"
      :animation="150"
      handle=".drag-handle"
      :disabled="schemaMode"
      ghost-class="row--ghost"
      chosen-class="row--chosen"
      drag-class="row--drag"
      @start="onDragStart"
      @end="onDragEnd"
    >
      <div
        v-for="(item, row) in localItems"
        :key="row"
        class="row"
        :class="{
          'row--selected': isRowSelected(row),
          'row--frozen': schemaMode,
        }"
        :style="gridStyle"
      >
        <span
          class="drag-handle"
          :title="t('documents.recordsEditor.dragHandle')"
          @click="onHandleClick($event, row)"
        >⠿</span>

        <template v-for="field in schema" :key="field">
          <input
            v-if="editingRow === row && editingField === field"
            :ref="(el) => registerInput(row, field, el as Element | null)"
            v-model="editBuffer"
            type="text"
            class="cell-input"
            @blur="commitEdit"
            @keydown="onEditKeydown($event, row, field)"
          />
          <button
            v-else
            type="button"
            class="cell"
            :title="t('documents.recordsEditor.clickToEdit')"
            @click="startEdit(row, field)"
          >
            <span v-if="item.values[field]" class="cell-content">{{ item.values[field] }}</span>
            <span v-else class="cell-empty">{{ t('documents.recordsEditor.emptyCell') }}</span>
          </button>
        </template>

        <button
          v-if="!schemaMode"
          type="button"
          class="row-delete"
          :title="t('documents.recordsEditor.deleteItem')"
          @click="deleteRow(row)"
        >✕</button>
        <span v-else class="row-delete-spacer" aria-hidden="true" />
      </div>
    </VueDraggable>

    <!-- Overflow indicator — markdown rows that carried more values
         than the schema length. We keep them round-trip-stable but
         flag them so the user notices and can fold them back into a
         schema column if needed. -->
    <div v-if="hasOverflow" class="overflow-note">
      {{ t('documents.recordsEditor.overflowHint') }}
    </div>

    <div v-if="!schemaMode" class="add-row">
      <VButton variant="ghost" size="sm" @click="addRowAndEdit">
        + {{ t('documents.recordsEditor.addRow') }}
      </VButton>
    </div>
  </div>
</template>

<style scoped>
.records-edit {
  font-size: 0.95rem;
}
.records-toolbar {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding-bottom: 0.4rem;
}
.schema-error {
  color: hsl(var(--er));
  font-size: 0.8rem;
}
.records-edit--schema .header-row {
  background: hsl(var(--p) / 0.06);
  border-bottom-color: hsl(var(--p) / 0.4);
}
.header-row,
.row {
  display: grid;
  gap: 0.4rem;
  align-items: stretch;
  padding: 0.3rem 0.25rem;
  border-bottom: 1px solid hsl(var(--bc) / 0.08);
}
.header-row {
  position: sticky;
  top: 0;
  z-index: 1;
  background: hsl(var(--b2));
  border-bottom: 1px solid hsl(var(--bc) / 0.18);
  padding: 0.45rem 0.25rem;
}
.header-cell {
  font-size: 0.75rem;
  font-family: ui-monospace, monospace;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  opacity: 0.75;
  align-self: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.handle-cell,
.action-cell { width: 1rem; }
.row { background: transparent; }
.row:last-child { border-bottom: 0; }
.drag-handle {
  cursor: grab;
  user-select: none;
  text-align: center;
  opacity: 0.25;
  padding-top: 0.2rem;
  font-size: 1rem;
  line-height: 1;
}
.drag-handle:hover { opacity: 0.7; }
.drag-handle:active { cursor: grabbing; }
.cell {
  text-align: left;
  background: transparent;
  border: 1px dashed transparent;
  border-radius: 0.25rem;
  padding: 0.25rem 0.5rem;
  cursor: text;
  min-height: 1.6rem;
  width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cell:hover {
  border-color: hsl(var(--bc) / 0.15);
  background: hsl(var(--bc) / 0.04);
}
.cell-content {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}
.cell-empty {
  opacity: 0.4;
  font-style: italic;
}
.cell-input {
  background: transparent;
  border: 1px solid hsl(var(--p) / 0.4);
  border-radius: 0.25rem;
  padding: 0.25rem 0.5rem;
  font: inherit;
  color: inherit;
  width: 100%;
  outline: none;
  min-height: 1.6rem;
}
.cell-input:focus {
  border-color: hsl(var(--p));
  box-shadow: 0 0 0 2px hsl(var(--p) / 0.2);
}
.row-delete {
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 0.25rem 0.4rem;
  border-radius: 0.25rem;
  opacity: 0.4;
  font-size: 0.85rem;
  line-height: 1;
}
.row-delete:hover {
  opacity: 1;
  background: hsl(var(--er) / 0.15);
  color: hsl(var(--er));
}
.add-row {
  margin-top: 0.5rem;
  padding-top: 0.25rem;
}
.bulk-bar {
  position: sticky;
  top: 0;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.4rem 0.6rem;
  margin-bottom: 0.4rem;
  border-radius: 0.375rem;
  background: hsl(var(--p) / 0.1);
  border: 1px solid hsl(var(--p) / 0.3);
  font-size: 0.85rem;
}
.bulk-count { font-weight: 600; }
.grow { flex: 1 1 auto; }
.row--ghost { opacity: 0.35; background: hsl(var(--p) / 0.08); }
.row--chosen { background: hsl(var(--bc) / 0.04); }
.row--drag {
  opacity: 0.95;
  background: hsl(var(--b1));
  box-shadow: 0 4px 14px hsl(var(--bc) / 0.15);
}
.row--selected {
  background: hsl(var(--p) / 0.08);
  box-shadow: inset 3px 0 0 hsl(var(--p));
}
/* Body rows while schema-edit mode is active — visible but muted so
   the user notices the cells aren't editable. The drag handle and
   delete button are hidden via v-if/disabled in the template. */
.row--frozen {
  opacity: 0.55;
  pointer-events: none;
}
.row--frozen .drag-handle { cursor: default; }
.overflow-note {
  margin-top: 0.5rem;
  font-size: 0.8rem;
  opacity: 0.6;
  font-style: italic;
}

/* Schema-edit-mode header cell — stack of [arrows + delete] above
   the rename input. Compact so it fits inside the same column
   widths as the body cells. */
.schema-edit {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  align-items: stretch;
  text-transform: none;
  letter-spacing: 0;
  opacity: 1;
}
.schema-controls {
  display: flex;
  gap: 0.15rem;
  align-items: center;
}
.schema-arrow,
.schema-remove,
.schema-add {
  background: transparent;
  border: 1px solid hsl(var(--bc) / 0.2);
  border-radius: 0.25rem;
  cursor: pointer;
  padding: 0 0.4rem;
  font-size: 0.75rem;
  line-height: 1.4;
  color: inherit;
}
.schema-arrow:hover,
.schema-remove:hover,
.schema-add:hover {
  background: hsl(var(--bc) / 0.08);
}
.schema-arrow:disabled,
.schema-remove:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}
.schema-remove:hover:not(:disabled) {
  background: hsl(var(--er) / 0.15);
  color: hsl(var(--er));
  border-color: hsl(var(--er) / 0.4);
}
.schema-add {
  font-size: 1rem;
  padding: 0.1rem 0.55rem;
}
.schema-name-input {
  background: transparent;
  border: 1px solid hsl(var(--bc) / 0.25);
  border-radius: 0.25rem;
  padding: 0.2rem 0.4rem;
  font: inherit;
  font-family: ui-monospace, monospace;
  font-size: 0.85rem;
  color: inherit;
  width: 100%;
  outline: none;
}
.schema-name-input:focus {
  border-color: hsl(var(--p));
  box-shadow: 0 0 0 2px hsl(var(--p) / 0.2);
}
.row-delete-spacer {
  width: 1.5rem;
}
</style>
