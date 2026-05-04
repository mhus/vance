<script setup lang="ts">
import { nextTick, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VueDraggable } from 'vue-draggable-plus';
import { VButton } from '@/components';
import type { ListDocument, ListItem } from './listItemsCodec';

/**
 * Editor for `kind: list` documents — flat list of items, one row
 * per item. Click an item to edit, press Enter to save and append a
 * fresh row, Esc to cancel, Backspace on an empty row to delete and
 * focus the previous item. The trash icon is the explicit delete
 * affordance for non-empty items. Each row carries a drag handle —
 * grab it to reorder.
 *
 * Mutations bubble up through {@code update:doc} as a fresh
 * {@link ListDocument}. The parent re-serialises into the raw body
 * so the existing Save button writes the canonical form back.
 */
const props = defineProps<{
  doc: ListDocument;
}>();

const emit = defineEmits<{
  (event: 'update:doc', doc: ListDocument): void;
}>();

const { t } = useI18n();

/** Current edit-target row index. {@code null} = no edit in flight. */
const editingIndex = ref<number | null>(null);
/** Buffered text of the edit-input — committed on blur / Enter,
 *  discarded on Esc. */
const editBuffer = ref('');
/** Refs to the input elements, indexed by row. Lets us focus the
 *  next/previous item after a keyboard-driven mutation. */
const inputRefs = ref<HTMLTextAreaElement[]>([]);

/**
 * Local mutable copy of the item list — required by `vue-draggable-plus`,
 * which mutates its `v-model` array in place during a drag. CRUD
 * operations also work on this ref; every mutation calls {@link emitDoc}
 * so the parent receives a fresh ListDocument and re-serialises into the
 * raw body. External updates (e.g. user typed in the Raw tab) flow back
 * via the {@link watch} below.
 */
const localItems = ref<ListItem[]>(cloneItems(props.doc.items));

watch(
  () => props.doc.items,
  (next) => {
    // Always sync local from props — the codec is idempotent on a
    // round-trip, so mirroring our own emit's parsed-and-reserialised
    // result back into localItems is a no-op effect, not a loop.
    localItems.value = cloneItems(next);
  },
  { deep: true },
);

function cloneItems(src: ListItem[]): ListItem[] {
  return src.map((it) => ({ text: it.text, extra: { ...it.extra } }));
}

function emitDoc(): void {
  emit('update:doc', {
    kind: props.doc.kind || 'list',
    items: localItems.value,
    extra: props.doc.extra,
  });
}

// ─── Click → edit ───────────────────────────────────────────────────

function startEdit(idx: number): void {
  editingIndex.value = idx;
  editBuffer.value = localItems.value[idx]?.text ?? '';
  // Focus the matching textarea once Vue mounts it.
  void nextTick(() => {
    const el = inputRefs.value[idx];
    if (el) {
      el.focus();
      // Place caret at end so Enter-to-extend feels natural.
      el.setSelectionRange(el.value.length, el.value.length);
    }
  });
}

function cancelEdit(): void {
  editingIndex.value = null;
  editBuffer.value = '';
}

function commitEdit(): void {
  if (editingIndex.value == null) return;
  const idx = editingIndex.value;
  // Preserve the per-item `extra` field across an edit so unknown
  // fields survive the round-trip — we only mutate `text`.
  const original = localItems.value[idx];
  localItems.value[idx] = { text: editBuffer.value, extra: original?.extra ?? {} };
  editingIndex.value = null;
  editBuffer.value = '';
  emitDoc();
}

// ─── Add ────────────────────────────────────────────────────────────

function addItem(): void {
  insertItemAt(localItems.value.length);
}

function insertItemAt(idx: number): void {
  localItems.value.splice(idx, 0, { text: '', extra: {} });
  emitDoc();
  // Open the freshly added row for editing once Vue rerenders.
  void nextTick(() => startEdit(idx));
}

// ─── Delete ─────────────────────────────────────────────────────────

function deleteItem(idx: number): void {
  localItems.value.splice(idx, 1);
  // Cancel any in-flight edit — the indices have shifted.
  editingIndex.value = null;
  editBuffer.value = '';
  emitDoc();
}

// ─── Drag-Reorder ───────────────────────────────────────────────────
//
// `vue-draggable-plus` mutates `localItems` in-place during the drag;
// we only need to commit on drop. Cancel any in-flight edit so the
// editing-index doesn't point at a moved row.
function onDragStart(): void {
  cancelEdit();
}
function onDragEnd(): void {
  emitDoc();
}

// ─── Keyboard handlers in the inline-edit field ─────────────────────

function onEditKeydown(event: KeyboardEvent, idx: number): void {
  if (event.key === 'Enter' && !event.shiftKey) {
    // Enter commits and creates a fresh row after this one.
    event.preventDefault();
    commitEdit();
    insertItemAt(idx + 1);
    return;
  }
  if (event.key === 'Escape') {
    event.preventDefault();
    cancelEdit();
    return;
  }
  if (event.key === 'Backspace' && editBuffer.value === '') {
    // Backspace on an empty row deletes it and focuses the previous
    // item if there is one. Mirrors outliner-style editors.
    event.preventDefault();
    const prev = idx - 1;
    deleteItem(idx);
    if (prev >= 0) {
      void nextTick(() => startEdit(prev));
    }
  }
}

// Auto-grow textarea height to fit content. Cheap inline solution
// that matches DaisyUI input typography.
function autoGrow(event: Event): void {
  const el = event.target as HTMLTextAreaElement;
  el.style.height = 'auto';
  el.style.height = el.scrollHeight + 'px';
}
</script>

<template>
  <div class="list-edit">
    <VueDraggable
      v-if="localItems.length > 0"
      v-model="localItems"
      tag="ul"
      class="rows"
      :animation="150"
      handle=".drag-handle"
      ghost-class="row--ghost"
      chosen-class="row--chosen"
      drag-class="row--drag"
      @start="onDragStart"
      @end="onDragEnd"
    >
      <li
        v-for="(item, idx) in localItems"
        :key="idx"
        class="row"
      >
        <span
          class="drag-handle"
          :title="t('documents.listEditor.dragHandle')"
          aria-hidden="true"
        >⠿</span>
        <span class="bullet" aria-hidden="true">•</span>
        <textarea
          v-if="editingIndex === idx"
          :ref="(el) => { if (el) inputRefs[idx] = el as HTMLTextAreaElement; }"
          v-model="editBuffer"
          class="edit-input"
          rows="1"
          @blur="commitEdit"
          @keydown="onEditKeydown($event, idx)"
          @input="autoGrow"
        />
        <button
          v-else
          type="button"
          class="text"
          :title="t('documents.listEditor.clickToEdit')"
          @click="startEdit(idx)"
        >
          <span v-if="item.text" class="text-content">{{ item.text }}</span>
          <span v-else class="text-empty">{{ t('documents.listEditor.emptyItem') }}</span>
        </button>
        <button
          type="button"
          class="row-delete"
          :title="t('documents.listEditor.deleteItem')"
          @click="deleteItem(idx)"
        >✕</button>
      </li>
    </VueDraggable>

    <div class="add-row">
      <VButton variant="ghost" size="sm" @click="addItem">
        + {{ t('documents.listEditor.addItem') }}
      </VButton>
    </div>
  </div>
</template>

<style scoped>
.list-edit {
  font-size: 0.95rem;
}
.rows {
  list-style: none;
  padding: 0;
  margin: 0;
}
.row {
  display: grid;
  grid-template-columns: 1rem 1.25rem 1fr auto;
  gap: 0.5rem;
  align-items: start;
  padding: 0.4rem 0.25rem;
  border-bottom: 1px solid hsl(var(--bc) / 0.08);
  background: transparent;
}
.row:last-child {
  border-bottom: 0;
}
.drag-handle {
  cursor: grab;
  user-select: none;
  text-align: center;
  opacity: 0.25;
  padding-top: 0.1rem;
  font-size: 1rem;
  line-height: 1;
}
.drag-handle:hover { opacity: 0.7; }
.drag-handle:active { cursor: grabbing; }
.bullet {
  opacity: 0.5;
  text-align: center;
  user-select: none;
  padding-top: 0.15rem;
}
/* Source row visual while it's being dragged. */
.row--ghost {
  opacity: 0.35;
  background: hsl(var(--p) / 0.08);
}
.row--chosen {
  background: hsl(var(--bc) / 0.04);
}
.row--drag {
  opacity: 0.95;
  background: hsl(var(--b1));
  box-shadow: 0 4px 14px hsl(var(--bc) / 0.15);
}
.text {
  text-align: left;
  background: transparent;
  border: 1px dashed transparent;
  border-radius: 0.25rem;
  padding: 0.25rem 0.5rem;
  cursor: text;
  min-height: 1.6rem;
  width: 100%;
}
.text:hover {
  border-color: hsl(var(--bc) / 0.15);
  background: hsl(var(--bc) / 0.04);
}
.text-content {
  white-space: pre-wrap;
  word-break: break-word;
}
.text-empty {
  opacity: 0.4;
  font-style: italic;
}
.edit-input {
  background: transparent;
  border: 1px solid hsl(var(--p) / 0.4);
  border-radius: 0.25rem;
  padding: 0.25rem 0.5rem;
  font: inherit;
  color: inherit;
  width: 100%;
  resize: none;
  outline: none;
  overflow: hidden;
  min-height: 1.6rem;
}
.edit-input:focus {
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
</style>
