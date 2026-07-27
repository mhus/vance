<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VueDraggable } from 'vue-draggable-plus';
import { VButton } from '@/components';
import {
  parseChecklist,
  type ChecklistDocument,
  type ChecklistItem,
  type ChecklistPriority,
  type ChecklistStatus,
} from './checklistCodec';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';
import type { FenceMeta } from '@/kindRenderers/parseFenceLang';

/**
 * Editor for `kind: checklist` documents — flat list of items with
 * per-item status (open / done / in_progress / review / blocked /
 * needs_info / deferred / delegated / waiting) and optional priority
 * (high / low).
 *
 * Click the status box to cycle open → in_progress → done → open;
 * right-click the box (or use the dropdown) for the full status menu.
 * Click an item text to edit, Enter saves and appends a fresh row,
 * Esc cancels, Backspace on an empty row deletes and focuses the
 * previous item. Priority badge cycles none → high → low → none.
 * Drag the row handle to reorder.
 *
 * Modes:
 *   - `editor`   — full editor surface (used by DocumentApp's
 *                  Checklist tab).
 *   - `inline`   — compact read-only render from a fence body.
 *   - `embedded` — compact read-only render from a loaded Document.
 *
 * Mutations bubble through `update:doc` as a fresh ChecklistDocument;
 * the parent re-serialises into the raw body so the existing Save
 * pipeline writes it back.
 */
const props = withDefaults(defineProps<{
  mode?: 'editor' | 'inline' | 'embedded';
  doc?: ChecklistDocument;
  content?: string;
  meta?: FenceMeta;
  document?: DocumentDto;
  embedRef?: EmbedRef;
}>(), {
  mode: 'editor',
  meta: () => ({}),
});

const emit = defineEmits<{
  (event: 'update:doc', doc: ChecklistDocument): void;
}>();

const { t } = useI18n();

// ── Edit state ─────────────────────────────────────────────────────

const editingIndex = ref<number | null>(null);
const editBuffer = ref('');
const inputRefs = ref<HTMLTextAreaElement[]>([]);

// ── Selection state ────────────────────────────────────────────────

const selectedIndices = ref<Set<number>>(new Set());
const selectionAnchor = ref<number | null>(null);

function clearSelection(): void {
  selectedIndices.value = new Set();
  selectionAnchor.value = null;
}

function selectionCount(): number {
  return selectedIndices.value.size;
}

// ── Status dropdown state ──────────────────────────────────────────

/** Index of the row whose status dropdown is currently open, or null. */
const statusMenuIndex = ref<number | null>(null);

function openStatusMenu(idx: number): void {
  statusMenuIndex.value = idx;
}
function closeStatusMenu(): void {
  statusMenuIndex.value = null;
}

// Document-level click handler dismisses the dropdown when the user
// clicks anywhere outside it. The menu's wrapper stops propagation
// (`@click.stop`), and the trigger toggles via its own handler before
// the document listener fires, so a click on the chevron doesn't
// instantly re-close the menu it just opened.
function onDocumentClick(_event: MouseEvent): void {
  if (statusMenuIndex.value !== null) closeStatusMenu();
}
onMounted(() => document.addEventListener('click', onDocumentClick));
onBeforeUnmount(() => document.removeEventListener('click', onDocumentClick));

const isEditor = computed(() => props.mode === 'editor');

// ── Resolved doc + local mutable copy ──────────────────────────────

const resolvedDoc = computed<ChecklistDocument>(() => {
  if (props.mode === 'editor') {
    return props.doc ?? emptyDoc();
  }
  if (props.mode === 'inline') {
    try {
      return parseChecklist(props.content ?? '', 'text/markdown');
    } catch (e) {
      console.warn('ChecklistView: failed to parse inline content', e);
      return emptyDoc();
    }
  }
  const d = props.document;
  if (!d || !d.inlineText) return emptyDoc();
  try {
    return parseChecklist(d.inlineText, d.mimeType ?? 'text/markdown');
  } catch (e) {
    console.warn('ChecklistView: failed to parse embedded document', e);
    return emptyDoc();
  }
});

function emptyDoc(): ChecklistDocument {
  return { kind: 'checklist', items: [], extra: {} };
}

const localItems = ref<ChecklistItem[]>(cloneItems(resolvedDoc.value.items));

watch(
  () => resolvedDoc.value.items,
  (next) => {
    localItems.value = cloneItems(next);
  },
  { deep: true },
);

function cloneItems(src: ChecklistItem[]): ChecklistItem[] {
  return src.map((it) => ({
    text: it.text,
    status: it.status,
    priority: it.priority,
    extra: { ...it.extra },
  }));
}

function emitDoc(): void {
  if (!isEditor.value) return;
  emit('update:doc', {
    kind: resolvedDoc.value.kind || 'checklist',
    items: localItems.value,
    extra: resolvedDoc.value.extra,
  });
}

// ── Aggregate header (status counts) ───────────────────────────────

/** Display order for the aggregate counts — most actionable first. */
const STATUS_DISPLAY_ORDER: ChecklistStatus[] = [
  'done',
  'blocked',
  'in_progress',
  'review',
  'waiting',
  'needs_info',
  'delegated',
  'deferred',
  'open',
];

/** Click-active filter — when set, only items with this status render.
 *  null = show everything. */
const statusFilter = ref<ChecklistStatus | null>(null);

const statusCounts = computed<Record<ChecklistStatus, number>>(() => {
  const counts = {
    open: 0, done: 0, in_progress: 0, review: 0, blocked: 0,
    needs_info: 0, deferred: 0, delegated: 0, waiting: 0,
  } as Record<ChecklistStatus, number>;
  for (const it of localItems.value) counts[it.status]++;
  return counts;
});

const visibleAggregate = computed<Array<{ status: ChecklistStatus; count: number }>>(() => {
  const counts = statusCounts.value;
  return STATUS_DISPLAY_ORDER
    .map((s) => ({ status: s, count: counts[s] }))
    .filter((e) => e.count > 0);
});

function toggleStatusFilter(s: ChecklistStatus): void {
  statusFilter.value = statusFilter.value === s ? null : s;
}

/** Whether the row at `idx` is currently visible under the active filter. */
function isVisible(idx: number): boolean {
  if (statusFilter.value == null) return true;
  return localItems.value[idx]?.status === statusFilter.value;
}

// ── Edit lifecycle ─────────────────────────────────────────────────

function startEdit(idx: number): void {
  editingIndex.value = idx;
  editBuffer.value = localItems.value[idx]?.text ?? '';
  void nextTick(() => {
    const el = inputRefs.value[idx];
    if (el) {
      el.focus();
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
  const original = localItems.value[idx];
  if (!original) {
    editingIndex.value = null;
    editBuffer.value = '';
    return;
  }
  localItems.value[idx] = {
    text: editBuffer.value,
    status: original.status,
    priority: original.priority,
    extra: original.extra,
  };
  editingIndex.value = null;
  editBuffer.value = '';
  emitDoc();
}

// ── Add / delete ───────────────────────────────────────────────────

function addItem(): void {
  insertItemAt(localItems.value.length);
}

function insertItemAt(idx: number): void {
  localItems.value.splice(idx, 0, {
    text: '',
    status: 'open',
    extra: {},
  });
  clearSelection();
  emitDoc();
  void nextTick(() => startEdit(idx));
}

function deleteItem(idx: number): void {
  localItems.value.splice(idx, 1);
  editingIndex.value = null;
  editBuffer.value = '';
  clearSelection();
  emitDoc();
}

function deleteSelected(): void {
  if (selectedIndices.value.size === 0) return;
  const sorted = [...selectedIndices.value].sort((a, b) => b - a);
  for (const idx of sorted) localItems.value.splice(idx, 1);
  editingIndex.value = null;
  editBuffer.value = '';
  clearSelection();
  emitDoc();
}

// ── Status mutations ───────────────────────────────────────────────

/** Cycle the row's status: open → in_progress → done → open.
 *  Anything else (blocked, review, …) goes back to open — the cycle
 *  is the fast path for the common case. Use the dropdown for the
 *  rest. */
function cycleStatus(idx: number): void {
  const item = localItems.value[idx];
  if (!item) return;
  let next: ChecklistStatus;
  switch (item.status) {
    case 'open': next = 'in_progress'; break;
    case 'in_progress': next = 'done'; break;
    case 'done': next = 'open'; break;
    default: next = 'open'; break;
  }
  localItems.value[idx] = { ...item, status: next };
  emitDoc();
}

function setStatus(idx: number, status: ChecklistStatus): void {
  const item = localItems.value[idx];
  if (!item) return;
  localItems.value[idx] = { ...item, status };
  closeStatusMenu();
  emitDoc();
}

// ── Priority cycle (none → high → low → none) ──────────────────────

function cyclePriority(idx: number): void {
  const item = localItems.value[idx];
  if (!item) return;
  let next: ChecklistPriority | undefined;
  if (item.priority === undefined) next = 'high';
  else if (item.priority === 'high') next = 'low';
  else next = undefined;
  localItems.value[idx] = { ...item, priority: next };
  emitDoc();
}

// ── Multi-select on text click ─────────────────────────────────────

function onItemClick(event: MouseEvent, idx: number): void {
  if (event.shiftKey && selectionAnchor.value != null) {
    const start = Math.min(selectionAnchor.value, idx);
    const end = Math.max(selectionAnchor.value, idx);
    const next = new Set(selectedIndices.value);
    for (let i = start; i <= end; i++) next.add(i);
    selectedIndices.value = next;
    cancelEdit();
    return;
  }
  if (event.metaKey || event.ctrlKey) {
    const next = new Set(selectedIndices.value);
    if (next.has(idx)) {
      next.delete(idx);
    } else {
      next.add(idx);
      selectionAnchor.value = idx;
    }
    selectedIndices.value = next;
    cancelEdit();
    return;
  }
  if (selectedIndices.value.size > 0) clearSelection();
  selectionAnchor.value = idx;
  startEdit(idx);
}

function isSelected(idx: number): boolean {
  return selectedIndices.value.has(idx);
}

// ── Drag reorder ───────────────────────────────────────────────────

function onDragStart(): void {
  cancelEdit();
  clearSelection();
  closeStatusMenu();
}
function onDragEnd(): void {
  emitDoc();
}

// ── Keyboard handlers in the inline-edit field ────────────────────

function onEditKeydown(event: KeyboardEvent, idx: number): void {
  if (event.key === 'Enter' && !event.shiftKey) {
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
    event.preventDefault();
    const prev = idx - 1;
    deleteItem(idx);
    if (prev >= 0) {
      void nextTick(() => startEdit(prev));
    }
  }
}

function autoGrow(event: Event): void {
  const el = event.target as HTMLTextAreaElement;
  el.style.height = 'auto';
  el.style.height = el.scrollHeight + 'px';
}

// ── Status visual helpers ──────────────────────────────────────────

/** Single-char glyph rendered inside the status box. Uses the same
 *  markdown char as the codec — keeps the editor visually consistent
 *  with the on-disk source. */
function statusGlyph(s: ChecklistStatus): string {
  const map: Record<ChecklistStatus, string> = {
    open: ' ',
    done: 'x',
    in_progress: '~',
    review: '/',
    blocked: '!',
    needs_info: '?',
    deferred: '-',
    delegated: '>',
    waiting: '<',
  };
  return map[s];
}

/** CSS class fragment for status colour — picks DaisyUI semantic
 *  tokens. */
function statusColorClass(s: ChecklistStatus): string {
  return `status--${s}`;
}

const ALL_STATUSES: ChecklistStatus[] = [
  'open', 'done', 'in_progress', 'review', 'blocked',
  'needs_info', 'deferred', 'delegated', 'waiting',
];
</script>

<template>
  <!-- Compact read-only render for inline / embedded modes -->
  <ul v-if="!isEditor" :class="['checklist-read', `checklist-read--${mode}`]">
    <li
      v-for="(item, idx) in localItems"
      :key="idx"
      class="checklist-read__item"
    >
      <span class="checklist-read__box" :class="statusColorClass(item.status)">
        [<span class="checklist-read__glyph">{{ statusGlyph(item.status) }}</span>]
      </span>
      <span v-if="item.text" class="checklist-read__text">{{ item.text }}</span>
      <span v-else class="checklist-read__empty">—</span>
      <span v-if="item.priority" :class="['prio-badge', `prio-badge--${item.priority}`]">
        {{ item.priority === 'high' ? '↑' : '↓' }}
      </span>
    </li>
    <li v-if="localItems.length === 0" class="checklist-read__empty-row">
      ({{ t('documents.checklistEditor.empty') }})
    </li>
  </ul>

  <div v-else class="checklist-edit">
    <!-- Aggregate header — only shown when there are items -->
    <div v-if="localItems.length > 0" class="aggregate-bar">
      <span class="aggregate-total">
        {{ t('documents.checklistEditor.totalItems', { count: localItems.length }) }}
      </span>
      <button
        v-for="entry in visibleAggregate"
        :key="entry.status"
        type="button"
        :class="[
          'aggregate-pill',
          statusColorClass(entry.status),
          { 'aggregate-pill--active': statusFilter === entry.status },
        ]"
        :title="t(`documents.checklistEditor.status.${entry.status}`)"
        @click="toggleStatusFilter(entry.status)"
      >
        <span class="aggregate-glyph">{{ statusGlyph(entry.status) }}</span>
        <span class="aggregate-count">{{ entry.count }}</span>
      </button>
      <span v-if="statusFilter" class="aggregate-filter-hint">
        · {{ t('documents.checklistEditor.filterActive') }}
        <button type="button" class="aggregate-clear" @click="statusFilter = null">
          ✕
        </button>
      </span>
    </div>

    <!-- Bulk-action bar for multi-select -->
    <div v-if="selectionCount() > 0" class="bulk-bar">
      <span class="bulk-count">
        {{
          selectionCount() === 1
            ? t('documents.checklistEditor.selectedCountSingular', { count: selectionCount() })
            : t('documents.checklistEditor.selectedCountPlural', { count: selectionCount() })
        }}
      </span>
      <span class="grow" />
      <VButton variant="ghost" size="sm" @click="clearSelection">
        {{ t('documents.checklistEditor.clearSelection') }}
      </VButton>
      <VButton variant="danger" size="sm" @click="deleteSelected">
        {{ t('documents.checklistEditor.deleteSelected') }}
      </VButton>
    </div>

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
        v-show="isVisible(idx)"
        :key="idx"
        class="row"
        :class="{ 'row--selected': isSelected(idx) }"
      >
        <span
          class="drag-handle"
          :title="t('documents.checklistEditor.dragHandle')"
          aria-hidden="true"
        >⠿</span>

        <div class="status-cell">
          <button
            type="button"
            class="status-box"
            :class="statusColorClass(item.status)"
            :title="t(`documents.checklistEditor.status.${item.status}`)"
            @click="cycleStatus(idx)"
            @contextmenu.prevent="openStatusMenu(idx)"
          >
            [<span class="status-glyph">{{ statusGlyph(item.status) }}</span>]
          </button>
          <button
            type="button"
            class="status-dropdown-trigger"
            :title="t('documents.checklistEditor.openStatusMenu')"
            @click.stop="statusMenuIndex === idx ? closeStatusMenu() : openStatusMenu(idx)"
          >▾</button>
          <div v-if="statusMenuIndex === idx" class="status-menu" @click.stop>
            <button
              v-for="s in ALL_STATUSES"
              :key="s"
              type="button"
              class="status-menu__option"
              :class="[statusColorClass(s), { 'status-menu__option--active': item.status === s }]"
              @click="setStatus(idx, s)"
            >
              <span class="status-menu__glyph">[{{ statusGlyph(s) }}]</span>
              <span class="status-menu__label">
                {{ t(`documents.checklistEditor.status.${s}`) }}
              </span>
            </button>
          </div>
        </div>

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
          :title="t('documents.checklistEditor.clickToEdit')"
          @click="onItemClick($event, idx)"
        >
          <span v-if="item.text" class="text-content">{{ item.text }}</span>
          <span v-else class="text-empty">{{ t('documents.checklistEditor.emptyItem') }}</span>
        </button>

        <button
          type="button"
          :class="[
            'prio-toggle',
            item.priority ? `prio-toggle--${item.priority}` : 'prio-toggle--none',
          ]"
          :title="t('documents.checklistEditor.togglePriority')"
          @click="cyclePriority(idx)"
        >
          <span v-if="item.priority === 'high'">↑</span>
          <span v-else-if="item.priority === 'low'">↓</span>
          <span v-else class="prio-toggle__placeholder">·</span>
        </button>

        <button
          type="button"
          class="row-delete"
          :title="t('documents.checklistEditor.deleteItem')"
          @click="deleteItem(idx)"
        >✕</button>
      </li>
    </VueDraggable>

    <div class="add-row">
      <VButton variant="ghost" size="sm" @click="addItem">
        + {{ t('documents.checklistEditor.addItem') }}
      </VButton>
    </div>
  </div>
</template>

<style scoped>
/* Read-only render ------------------------------------------------- */
.checklist-read {
  list-style: none;
  padding-left: 0;
  margin: 0;
  font-size: 0.92rem;
  line-height: 1.5;
}
.checklist-read__item {
  display: flex;
  align-items: baseline;
  gap: 0.4rem;
  margin: 0.15em 0;
}
.checklist-read__box {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.85em;
  flex-shrink: 0;
}
.checklist-read__glyph {
  display: inline-block;
  width: 0.7em;
  text-align: center;
}
.checklist-read__text { word-break: break-word; flex: 1 1 auto; }
.checklist-read__empty { opacity: 0.5; }
.checklist-read__empty-row {
  opacity: 0.5;
  font-style: italic;
  padding: 0.5rem 0;
}
.checklist-read--inline,
.checklist-read--embedded {
  max-height: 18rem;
  overflow-y: auto;
}

/* Editor ----------------------------------------------------------- */
.checklist-edit {
  font-size: 0.95rem;
}

/* Aggregate header */
.aggregate-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.4rem;
  margin-bottom: 0.5rem;
  padding: 0.3rem 0.1rem;
  font-size: 0.85rem;
  border-bottom: 1px solid oklch(var(--bc) / 0.08);
}
.aggregate-total {
  font-weight: 600;
  opacity: 0.7;
  margin-right: 0.4rem;
}
.aggregate-pill {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.1rem 0.45rem;
  border-radius: 999px;
  background: oklch(var(--bc) / 0.06);
  border: 1px solid transparent;
  font-size: 0.8rem;
  cursor: pointer;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.aggregate-pill:hover {
  background: oklch(var(--bc) / 0.12);
}
.aggregate-pill--active {
  border-color: currentColor;
  background: oklch(var(--bc) / 0.16);
}
.aggregate-glyph {
  display: inline-block;
  width: 1em;
  text-align: center;
  font-weight: 600;
}
.aggregate-count {
  font-family: ui-sans-serif, system-ui, sans-serif;
  font-weight: 600;
}
.aggregate-filter-hint {
  font-size: 0.78rem;
  opacity: 0.7;
  margin-left: 0.2rem;
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
}
.aggregate-clear {
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 0 0.2rem;
  font-size: 0.85rem;
  opacity: 0.7;
}
.aggregate-clear:hover { opacity: 1; }

.rows {
  list-style: none;
  padding: 0;
  margin: 0;
}
.row {
  display: grid;
  grid-template-columns: 1rem auto 1fr auto auto;
  gap: 0.5rem;
  align-items: start;
  padding: 0.4rem 0.25rem;
  border-bottom: 1px solid oklch(var(--bc) / 0.08);
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

.row--ghost { opacity: 0.35; background: oklch(var(--p) / 0.08); }
.row--chosen { background: oklch(var(--bc) / 0.04); }
.row--drag {
  opacity: 0.95;
  background: oklch(var(--b1));
  box-shadow: 0 4px 14px oklch(var(--bc) / 0.15);
}

/* Status cell */
.status-cell {
  position: relative;
  display: flex;
  align-items: center;
  gap: 0.1rem;
}
.status-box {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.95rem;
  background: transparent;
  border: 1px dashed transparent;
  border-radius: 0.25rem;
  padding: 0.15rem 0.4rem;
  cursor: pointer;
  min-width: 2.5rem;
}
.status-box:hover {
  background: oklch(var(--bc) / 0.06);
  border-color: oklch(var(--bc) / 0.15);
}
.status-glyph {
  display: inline-block;
  width: 0.7em;
  text-align: center;
  font-weight: 600;
}
.status-dropdown-trigger {
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 0.15rem 0.2rem;
  font-size: 0.75rem;
  opacity: 0.4;
  border-radius: 0.2rem;
}
.status-dropdown-trigger:hover {
  opacity: 0.9;
  background: oklch(var(--bc) / 0.08);
}

/* Status dropdown menu */
.status-menu {
  position: absolute;
  top: calc(100% + 0.2rem);
  left: 0;
  z-index: 10;
  display: flex;
  flex-direction: column;
  background: oklch(var(--b1));
  border: 1px solid oklch(var(--bc) / 0.18);
  border-radius: 0.375rem;
  box-shadow: 0 4px 14px oklch(var(--bc) / 0.18);
  padding: 0.25rem 0;
  min-width: 12rem;
}
.status-menu__option {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.3rem 0.6rem;
  background: transparent;
  border: none;
  cursor: pointer;
  text-align: left;
  font-size: 0.85rem;
}
.status-menu__option:hover {
  background: oklch(var(--bc) / 0.08);
}
.status-menu__option--active {
  background: oklch(var(--p) / 0.12);
  font-weight: 600;
}
.status-menu__glyph {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  min-width: 2.5rem;
  font-weight: 600;
}
.status-menu__label { flex: 1 1 auto; }

/* Status colour tokens — DaisyUI semantic vars */
.status--open { color: oklch(var(--bc) / 0.55); }
.status--done { color: oklch(var(--su)); }
.status--in_progress { color: oklch(var(--in)); }
.status--review { color: oklch(var(--a)); }
.status--blocked { color: oklch(var(--er)); }
.status--needs_info { color: oklch(var(--wa)); }
.status--deferred { color: oklch(var(--bc) / 0.6); }
.status--delegated { color: oklch(var(--s)); }
.status--waiting { color: oklch(var(--bc) / 0.6); font-style: italic; }

/* Text / edit input */
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
  border-color: oklch(var(--bc) / 0.15);
  background: oklch(var(--bc) / 0.04);
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
  border: 1px solid oklch(var(--p) / 0.4);
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
  border-color: oklch(var(--p));
  box-shadow: 0 0 0 2px oklch(var(--p) / 0.2);
}

/* Priority toggle */
.prio-toggle {
  background: transparent;
  border: 1px solid transparent;
  cursor: pointer;
  padding: 0.15rem 0.4rem;
  border-radius: 0.25rem;
  font-size: 0.85rem;
  line-height: 1;
  min-width: 1.5rem;
}
.prio-toggle:hover {
  border-color: oklch(var(--bc) / 0.15);
}
.prio-toggle--high {
  color: oklch(var(--er));
  font-weight: 700;
}
.prio-toggle--low {
  color: oklch(var(--bc) / 0.4);
}
.prio-toggle--none .prio-toggle__placeholder {
  opacity: 0.25;
}
.prio-badge {
  font-size: 0.75rem;
  padding: 0 0.25rem;
  margin-left: 0.2rem;
  border-radius: 0.2rem;
}
.prio-badge--high { color: oklch(var(--er)); font-weight: 700; }
.prio-badge--low { color: oklch(var(--bc) / 0.5); }

/* Row delete */
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
  background: oklch(var(--er) / 0.15);
  color: oklch(var(--er));
}

.add-row {
  margin-top: 0.5rem;
  padding-top: 0.25rem;
}

/* Bulk-action bar */
.bulk-bar {
  position: sticky;
  top: 0;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.4rem 0.6rem;
  margin-bottom: 0.4rem;
  border-radius: 0.375rem;
  background: oklch(var(--p) / 0.1);
  border: 1px solid oklch(var(--p) / 0.3);
  font-size: 0.85rem;
}
.bulk-count { font-weight: 600; }
.grow { flex: 1 1 auto; }

.row--selected {
  background: oklch(var(--p) / 0.08);
  box-shadow: inset 3px 0 0 oklch(var(--p));
}
</style>
