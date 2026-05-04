<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VButton } from '@/components';
import type { SheetCell, SheetDocument } from './sheetCodec';
import { columnIndexFromLetter, columnLetterFromIndex } from './sheetCodec';

/**
 * Editor for `kind: sheet` documents. HTML grid with A1 cell
 * addresses. Sparse model — only cells with data or formatting are
 * kept; the editor renders an N×M grid driven by `schema` (columns)
 * and `rows`. Click a cell to edit, Tab/Enter to navigate, Esc to
 * cancel. Side-panel exposes color and background per cell.
 *
 * Spec: `specification/doc-kind-sheet.md`.
 */
defineOptions({ name: 'SheetView' });

const props = defineProps<{ doc: SheetDocument }>();
const emit = defineEmits<{
  (event: 'update:doc', doc: SheetDocument): void;
}>();

const { t } = useI18n();

const DEFAULT_COLS = ['A', 'B', 'C'];
const DEFAULT_ROWS = 5;

// ── Local source-of-truth ──────────────────────────────────────────

const localCells = ref<SheetCell[]>(cloneCells(props.doc.cells));
const localSchema = ref<string[]>(deriveSchema(props.doc.schema, props.doc.cells));
const localRows = ref<number>(deriveRows(props.doc.rows, props.doc.cells));

watch(
  () => props.doc.cells,
  (next) => {
    localCells.value = cloneCells(next);
    localSchema.value = deriveSchema(props.doc.schema, next);
    localRows.value = deriveRows(props.doc.rows, next);
  },
  { deep: true },
);
watch(() => props.doc.schema, (next) => {
  localSchema.value = deriveSchema(next, localCells.value);
}, { deep: true });
watch(() => props.doc.rows, (next) => {
  localRows.value = deriveRows(next, localCells.value);
});

function cloneCells(src: SheetCell[]): SheetCell[] {
  return src.map((c) => ({
    field: c.field,
    data: c.data,
    color: c.color,
    background: c.background,
    extra: { ...c.extra },
  }));
}

/** If the doc has an explicit schema, use it (uppercased). Otherwise
 *  derive from the highest column-letter referenced in cells, plus
 *  the default A-C buffer. Always returns at least the defaults so
 *  the empty-sheet case shows something. */
function deriveSchema(explicit: string[], cells: SheetCell[]): string[] {
  if (explicit.length > 0) return [...explicit];
  let maxIdx = 0;
  for (const c of cells) {
    const m = /^([A-Z]+)/.exec(c.field);
    if (!m) continue;
    const idx = columnIndexFromLetter(m[1]);
    if (idx > maxIdx) maxIdx = idx;
  }
  const total = Math.max(maxIdx, DEFAULT_COLS.length);
  const out: string[] = [];
  for (let i = 1; i <= total; i++) out.push(columnLetterFromIndex(i));
  return out;
}

function deriveRows(explicit: number | null, cells: SheetCell[]): number {
  if (explicit != null) return explicit;
  let maxRow = 0;
  for (const c of cells) {
    const m = /[A-Z]+([0-9]+)$/.exec(c.field);
    if (!m) continue;
    const r = parseInt(m[1], 10);
    if (r > maxRow) maxRow = r;
  }
  return Math.max(maxRow, DEFAULT_ROWS);
}

// ── Cell lookup ────────────────────────────────────────────────────

const cellsByAddress = computed<Map<string, SheetCell>>(() => {
  const m = new Map<string, SheetCell>();
  for (const c of localCells.value) m.set(c.field, c);
  return m;
});

function getCell(addr: string): SheetCell | undefined {
  return cellsByAddress.value.get(addr);
}

function getValue(addr: string): string {
  return getCell(addr)?.data ?? '';
}

// ── Selection / Edit ───────────────────────────────────────────────

const selectedAddr = ref<string | null>(null);
const editingAddr = ref<string | null>(null);
const editBuffer = ref('');
const inputRefs = ref<Map<string, HTMLInputElement>>(new Map());

function registerInput(addr: string, el: Element | null): void {
  if (el) inputRefs.value.set(addr, el as HTMLInputElement);
  else inputRefs.value.delete(addr);
}

function selectCell(addr: string): void {
  selectedAddr.value = addr;
}

function startEdit(addr: string): void {
  selectedAddr.value = addr;
  editingAddr.value = addr;
  editBuffer.value = getValue(addr);
  void nextTick(() => {
    const el = inputRefs.value.get(addr);
    if (el) {
      el.focus();
      el.setSelectionRange(el.value.length, el.value.length);
    }
  });
}

function cancelEdit(): void {
  editingAddr.value = null;
  editBuffer.value = '';
}

function commitEdit(): void {
  if (!editingAddr.value) return;
  const addr = editingAddr.value;
  const value = editBuffer.value;
  upsertCell(addr, { data: value });
  editingAddr.value = null;
  editBuffer.value = '';
}

function upsertCell(addr: string, patch: Partial<SheetCell>): void {
  const idx = localCells.value.findIndex((c) => c.field === addr);
  if (idx >= 0) {
    const merged: SheetCell = { ...localCells.value[idx], ...patch, field: addr };
    if (cellShouldBeDropped(merged)) {
      localCells.value = localCells.value.filter((c) => c.field !== addr);
    } else {
      localCells.value = localCells.value.map((c, i) => i === idx ? merged : c);
    }
  } else {
    const fresh: SheetCell = {
      field: addr, data: '', extra: {}, ...patch,
    };
    if (!cellShouldBeDropped(fresh)) {
      localCells.value = [...localCells.value, fresh];
    }
  }
  emitDoc();
}

/** A cell with empty `data`, no color, no background, and no extras
 *  carries no information — drop it from the sparse store. Round-trip
 *  with the codec stays stable because the codec wouldn't have
 *  emitted such a cell either. */
function cellShouldBeDropped(c: SheetCell): boolean {
  return c.data === ''
    && c.color === undefined
    && c.background === undefined
    && Object.keys(c.extra).length === 0;
}

function emitDoc(): void {
  emit('update:doc', {
    kind: props.doc.kind || 'sheet',
    schema: [...localSchema.value],
    rows: localRows.value,
    cells: localCells.value,
    extra: props.doc.extra,
  });
}

// ── Navigation between cells ───────────────────────────────────────

function nextAddr(addr: string, dir: 'right' | 'left' | 'down' | 'up'): string | null {
  const m = /^([A-Z]+)([0-9]+)$/.exec(addr);
  if (!m) return null;
  const colIdx = columnIndexFromLetter(m[1]);
  const row = parseInt(m[2], 10);
  const cols = localSchema.value;
  const colsLen = cols.length;

  if (dir === 'right') {
    if (colIdx < colsLen) return cols[colIdx] + row;
    if (row < localRows.value) return cols[0] + (row + 1);
    return null;
  }
  if (dir === 'left') {
    if (colIdx > 1) return cols[colIdx - 2] + row;
    if (row > 1) return cols[colsLen - 1] + (row - 1);
    return null;
  }
  if (dir === 'down') {
    if (row < localRows.value) return cols[colIdx - 1] + (row + 1);
    return null;
  }
  if (dir === 'up') {
    if (row > 1) return cols[colIdx - 1] + (row - 1);
    return null;
  }
  return null;
}

function onEditKeydown(event: KeyboardEvent, addr: string): void {
  if (event.key === 'Enter') {
    event.preventDefault();
    commitEdit();
    let target = nextAddr(addr, 'down');
    if (!target) {
      addRow();
      target = nextAddr(addr, 'down');
    }
    if (target) void nextTick(() => startEdit(target!));
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
    const dir = event.shiftKey ? 'left' : 'right';
    let target = nextAddr(addr, dir);
    if (!target && !event.shiftKey) {
      addRow();
      target = nextAddr(addr, 'right');
    }
    if (target) void nextTick(() => startEdit(target!));
  }
}

// ── Toolbar actions ────────────────────────────────────────────────

function addColumn(): void {
  const next = columnLetterFromIndex(localSchema.value.length + 1);
  // Skip already-used letters (sparse cells may reference columns
  // beyond the explicit schema; we want a real free letter).
  let letter = next;
  while (localSchema.value.includes(letter)) {
    letter = columnLetterFromIndex(columnIndexFromLetter(letter) + 1);
  }
  localSchema.value = [...localSchema.value, letter];
  emitDoc();
}

function addRow(): void {
  localRows.value = localRows.value + 1;
  emitDoc();
}

function deleteSelectedRow(): void {
  if (!selectedAddr.value) return;
  const m = /^([A-Z]+)([0-9]+)$/.exec(selectedAddr.value);
  if (!m) return;
  const row = parseInt(m[2], 10);
  // Remove cells in this row and renumber cells below it.
  const survivors: SheetCell[] = [];
  for (const c of localCells.value) {
    const am = /^([A-Z]+)([0-9]+)$/.exec(c.field);
    if (!am) continue;
    const cellRow = parseInt(am[2], 10);
    if (cellRow === row) continue;
    if (cellRow > row) {
      survivors.push({ ...c, field: am[1] + (cellRow - 1) });
    } else {
      survivors.push(c);
    }
  }
  localCells.value = survivors;
  if (localRows.value > 1) localRows.value = localRows.value - 1;
  selectedAddr.value = null;
  cancelEdit();
  emitDoc();
}

function deleteSelectedColumn(): void {
  if (!selectedAddr.value) return;
  const m = /^([A-Z]+)([0-9]+)$/.exec(selectedAddr.value);
  if (!m) return;
  const col = m[1];
  const colIdx = columnIndexFromLetter(col);
  if (localSchema.value.length <= 1) return; // keep at least one column
  // Drop the column from schema, drop matching cells, renumber
  // higher columns down by one letter so the visible layout stays
  // contiguous.
  const newSchema = localSchema.value.filter((c) => c !== col);
  const survivors: SheetCell[] = [];
  for (const c of localCells.value) {
    const am = /^([A-Z]+)([0-9]+)$/.exec(c.field);
    if (!am) continue;
    const cellColIdx = columnIndexFromLetter(am[1]);
    if (cellColIdx === colIdx) continue;
    if (cellColIdx > colIdx) {
      survivors.push({ ...c, field: columnLetterFromIndex(cellColIdx - 1) + am[2] });
    } else {
      survivors.push(c);
    }
  }
  localSchema.value = newSchema;
  localCells.value = survivors;
  selectedAddr.value = null;
  cancelEdit();
  emitDoc();
}

// ── Format actions (side panel) ────────────────────────────────────

const selectedCell = computed<SheetCell | null>(() => {
  if (!selectedAddr.value) return null;
  return getCell(selectedAddr.value) ?? null;
});

function setColor(color: string): void {
  if (!selectedAddr.value) return;
  upsertCell(selectedAddr.value, {
    color: color && color.length > 0 ? color : undefined,
  });
}

function setBackground(bg: string): void {
  if (!selectedAddr.value) return;
  upsertCell(selectedAddr.value, {
    background: bg && bg.length > 0 ? bg : undefined,
  });
}

function clearCellFormat(): void {
  if (!selectedAddr.value) return;
  upsertCell(selectedAddr.value, { color: undefined, background: undefined });
}

// ── Helpers for template ───────────────────────────────────────────

const gridStyle = computed(() => ({
  gridTemplateColumns: `2.5rem repeat(${localSchema.value.length}, minmax(6rem, 1fr))`,
}));

const rowNumbers = computed<number[]>(() => {
  const out: number[] = [];
  for (let r = 1; r <= localRows.value; r++) out.push(r);
  return out;
});

function isFormula(addr: string): boolean {
  const v = getValue(addr);
  return v.startsWith('=');
}

function cellStyle(addr: string): Record<string, string> {
  const c = getCell(addr);
  if (!c) return {};
  const out: Record<string, string> = {};
  if (c.color) out.color = c.color;
  if (c.background) out.background = c.background;
  return out;
}
</script>

<template>
  <div class="sheet-view">
    <div class="toolbar">
      <VButton size="sm" variant="ghost" @click="addRow">
        + {{ t('documents.sheetView.addRow') }}
      </VButton>
      <VButton size="sm" variant="ghost" @click="addColumn">
        + {{ t('documents.sheetView.addColumn') }}
      </VButton>
      <VButton
        size="sm"
        variant="ghost"
        :disabled="!selectedAddr"
        @click="deleteSelectedRow"
      >{{ t('documents.sheetView.deleteRow') }}</VButton>
      <VButton
        size="sm"
        variant="ghost"
        :disabled="!selectedAddr || localSchema.length <= 1"
        @click="deleteSelectedColumn"
      >{{ t('documents.sheetView.deleteColumn') }}</VButton>
      <span class="hint">{{ t('documents.sheetView.hint') }}</span>
    </div>

    <div class="grid-and-panel">
      <div class="grid-wrap">
        <div class="header-row" :style="gridStyle">
          <span class="header-corner" aria-hidden="true" />
          <span
            v-for="col in localSchema"
            :key="col"
            class="header-col"
          >{{ col }}</span>
        </div>
        <div
          v-for="row in rowNumbers"
          :key="row"
          class="data-row"
          :style="gridStyle"
        >
          <span class="row-num" aria-hidden="true">{{ row }}</span>
          <template v-for="col in localSchema" :key="col + row">
            <input
              v-if="editingAddr === col + row"
              :ref="(el) => registerInput(col + row, el as Element | null)"
              v-model="editBuffer"
              type="text"
              class="cell-input"
              :style="cellStyle(col + row)"
              @blur="commitEdit"
              @keydown="onEditKeydown($event, col + row)"
            />
            <button
              v-else
              type="button"
              class="cell"
              :class="{
                'cell--selected': selectedAddr === col + row,
                'cell--formula': isFormula(col + row),
              }"
              :style="cellStyle(col + row)"
              :title="col + row"
              @click="selectCell(col + row)"
              @dblclick="startEdit(col + row)"
            >
              <span class="cell-text">{{ getValue(col + row) }}</span>
            </button>
          </template>
        </div>
      </div>

      <aside v-if="selectedCell" class="panel">
        <h4>{{ t('documents.sheetView.cellProps') }}</h4>
        <p class="cell-addr">{{ selectedAddr }}</p>
        <label>
          {{ t('documents.sheetView.colorField') }}
          <div class="color-row">
            <input
              type="color"
              :value="selectedCell.color ?? '#1f2937'"
              @input="(e) => setColor((e.target as HTMLInputElement).value)"
            />
            <button
              type="button"
              class="clear-btn"
              :disabled="!selectedCell.color"
              @click="setColor('')"
            >{{ t('documents.sheetView.clear') }}</button>
          </div>
        </label>
        <label>
          {{ t('documents.sheetView.backgroundField') }}
          <div class="color-row">
            <input
              type="color"
              :value="selectedCell.background ?? '#ffffff'"
              @input="(e) => setBackground((e.target as HTMLInputElement).value)"
            />
            <button
              type="button"
              class="clear-btn"
              :disabled="!selectedCell.background"
              @click="setBackground('')"
            >{{ t('documents.sheetView.clear') }}</button>
          </div>
        </label>
        <VButton
          size="sm"
          variant="ghost"
          :disabled="!selectedCell.color && !selectedCell.background"
          @click="clearCellFormat"
        >{{ t('documents.sheetView.clearFormat') }}</VButton>
      </aside>

      <aside v-else-if="selectedAddr" class="panel">
        <h4>{{ t('documents.sheetView.cellProps') }}</h4>
        <p class="cell-addr">{{ selectedAddr }}</p>
        <p class="panel-empty-hint">{{ t('documents.sheetView.cellEmptyHint') }}</p>
      </aside>

      <aside v-else class="panel panel--empty">
        <p class="panel-empty-hint">{{ t('documents.sheetView.emptySelectionHint') }}</p>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.sheet-view {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  font-size: 0.85rem;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  flex-wrap: wrap;
}
.hint {
  font-size: 0.7rem;
  opacity: 0.55;
  margin-left: auto;
}
.grid-and-panel {
  display: flex;
  gap: 0.75rem;
  align-items: flex-start;
}
.grid-wrap {
  flex: 1 1 auto;
  min-width: 0;
  border: 1px solid hsl(var(--bc) / 0.18);
  border-radius: 0.4rem;
  background: hsl(var(--b1));
  max-height: 65vh;
  overflow: auto;
}
.header-row,
.data-row {
  display: grid;
  align-items: stretch;
  border-bottom: 1px solid hsl(var(--bc) / 0.08);
}
.header-row {
  position: sticky;
  top: 0;
  z-index: 2;
  background: hsl(var(--b2));
  border-bottom-color: hsl(var(--bc) / 0.18);
}
.header-corner,
.row-num {
  background: hsl(var(--b2));
  font-family: ui-monospace, monospace;
  font-size: 0.7rem;
  color: hsl(var(--bc) / 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  border-right: 1px solid hsl(var(--bc) / 0.08);
  min-height: 1.85rem;
}
.row-num {
  position: sticky;
  left: 0;
  z-index: 1;
}
.header-col {
  font-family: ui-monospace, monospace;
  font-size: 0.75rem;
  text-align: center;
  padding: 0.35rem 0;
  letter-spacing: 0.04em;
  color: hsl(var(--bc) / 0.7);
  border-right: 1px solid hsl(var(--bc) / 0.08);
}
.cell,
.cell-input {
  background: transparent;
  border: 1px solid transparent;
  padding: 0.25rem 0.4rem;
  text-align: left;
  font: inherit;
  color: inherit;
  outline: none;
  min-height: 1.85rem;
  cursor: text;
  border-right: 1px solid hsl(var(--bc) / 0.08);
}
.cell:hover {
  background: hsl(var(--bc) / 0.04);
}
.cell--selected {
  background: hsl(var(--p) / 0.06);
  outline: 1px solid hsl(var(--p) / 0.5);
  outline-offset: -1px;
}
.cell--formula {
  box-shadow: inset 2px 0 0 hsl(var(--p));
}
.cell-text {
  display: block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.cell-input {
  border-color: hsl(var(--p));
  box-shadow: 0 0 0 2px hsl(var(--p) / 0.2);
  width: 100%;
}
.panel {
  width: 14rem;
  flex: 0 0 14rem;
  background: hsl(var(--b1));
  border: 1px solid hsl(var(--bc) / 0.15);
  border-radius: 0.5rem;
  padding: 0.7rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  font-size: 0.82rem;
  align-self: flex-start;
}
.panel h4 {
  margin: 0;
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  opacity: 0.65;
}
.cell-addr {
  font-family: ui-monospace, monospace;
  font-size: 0.95rem;
  margin: 0;
}
.panel label {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  font-size: 0.72rem;
  opacity: 0.85;
}
.color-row {
  display: flex;
  gap: 0.4rem;
  align-items: center;
}
.color-row input[type="color"] {
  flex: 0 0 2.5rem;
  height: 1.85rem;
  border: 1px solid hsl(var(--bc) / 0.25);
  border-radius: 0.25rem;
  background: transparent;
  cursor: pointer;
  padding: 0;
}
.clear-btn {
  background: transparent;
  border: 1px solid hsl(var(--bc) / 0.2);
  border-radius: 0.25rem;
  padding: 0.2rem 0.5rem;
  font-size: 0.72rem;
  cursor: pointer;
  color: inherit;
}
.clear-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.panel--empty {
  align-items: stretch;
  justify-content: center;
  min-height: 5rem;
}
.panel-empty-hint {
  font-size: 0.78rem;
  opacity: 0.55;
  text-align: center;
  font-style: italic;
  margin: 0;
}
</style>
