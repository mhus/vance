<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { brainFetch } from '@vance/shared';
import { VButton } from '@/components';
import type {
  SheetCell,
  SheetColumn,
  SheetComputed,
  SheetComputedValue,
  SheetDocument,
} from './sheetCodec';
import { columnIndexFromLetter, columnLetterFromIndex, normalizeBorders } from './sheetCodec';

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

const props = defineProps<{
  doc: SheetDocument;
  /** Document identity for server-side recalc (`/sheet/calc`). Passed by
   *  the Cortex shell; absent in embed/documents.html — recalc is then
   *  disabled and cells render the last persisted `$computed` overlay. */
  projectId?: string;
  docPath?: string;
}>();
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

const localColumns = ref<Record<string, SheetColumn>>(cloneColumns(props.doc.columns));
watch(() => props.doc.columns, (next) => {
  localColumns.value = cloneColumns(next);
}, { deep: true });

function cloneColumns(src: Record<string, SheetColumn> | undefined): Record<string, SheetColumn> {
  const out: Record<string, SheetColumn> = {};
  for (const [k, v] of Object.entries(src ?? {})) out[k] = { ...v };
  return out;
}

const localRowHeights = ref<Record<string, number>>({ ...(props.doc.rowHeights ?? {}) });
watch(() => props.doc.rowHeights, (next) => {
  localRowHeights.value = { ...(next ?? {}) };
}, { deep: true });

const localRowBorders = ref<Record<string, string>>({ ...(props.doc.rowBorders ?? {}) });
watch(() => props.doc.rowBorders, (next) => {
  localRowBorders.value = { ...(next ?? {}) };
}, { deep: true });

function cloneCells(src: SheetCell[]): SheetCell[] {
  return src.map((c) => ({
    field: c.field,
    data: c.data,
    color: c.color,
    background: c.background,
    bold: c.bold,
    italic: c.italic,
    align: c.align,
    numberFormat: c.numberFormat,
    borders: c.borders,
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
// The opposite corner of a rectangular multi-cell selection. null = the
// selection is just the single `selectedAddr`.
const selectionFocus = ref<string | null>(null);
const selecting = ref(false); // true while drag-selecting
const selectedColumn = ref<string | null>(null);
const selectedRow = ref<number | null>(null);
const editingAddr = ref<string | null>(null);
const editBuffer = ref('');
const inputRefs = ref<Map<string, HTMLInputElement>>(new Map());

function registerInput(addr: string, el: Element | null): void {
  if (el) inputRefs.value.set(addr, el as HTMLInputElement);
  else inputRefs.value.delete(addr);
}

function startEdit(addr: string): void {
  selectedAddr.value = addr;
  selectionFocus.value = null;
  selectedColumn.value = null;
  selectedRow.value = null;
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
  scheduleRecalc();
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
    && !c.bold
    && !c.italic
    && c.align === undefined
    && c.numberFormat === undefined
    && c.borders === undefined
    && Object.keys(c.extra).length === 0;
}

function emitDoc(): void {
  emit('update:doc', {
    kind: props.doc.kind || 'sheet',
    schema: [...localSchema.value],
    rows: localRows.value,
    cells: localCells.value,
    columns: { ...localColumns.value },
    rowHeights: { ...localRowHeights.value },
    rowBorders: { ...localRowBorders.value },
    extra: props.doc.extra,
  });
}

// ── Rectangular multi-cell selection ──────────────────────────────

/** Locate an address by its column POSITION in the visible schema (so
 *  gaps in `schema` work) and its row number. */
function cellPos(addr: string): { pos: number; row: number } | null {
  const m = /^([A-Z]+)([0-9]+)$/.exec(addr);
  if (!m) return null;
  const pos = localSchema.value.indexOf(m[1]);
  if (pos < 0) return null;
  return { pos, row: parseInt(m[2], 10) };
}

const selectionRect = computed(() => {
  const a = selectedAddr.value ? cellPos(selectedAddr.value) : null;
  if (!a) return null;
  const b = (selectionFocus.value ? cellPos(selectionFocus.value) : null) ?? a;
  return {
    minPos: Math.min(a.pos, b.pos), maxPos: Math.max(a.pos, b.pos),
    minRow: Math.min(a.row, b.row), maxRow: Math.max(a.row, b.row),
  };
});

function isSelected(addr: string): boolean {
  const r = selectionRect.value;
  const p = cellPos(addr);
  if (!r || !p) return false;
  return p.pos >= r.minPos && p.pos <= r.maxPos && p.row >= r.minRow && p.row <= r.maxRow;
}

/** All addresses inside the current selection rectangle, clamped to the
 *  visible schema columns and row count. */
const selectedAddresses = computed<string[]>(() => {
  const r = selectionRect.value;
  if (!r) return [];
  const out: string[] = [];
  for (let pos = r.minPos; pos <= r.maxPos; pos++) {
    const col = localSchema.value[pos];
    if (!col) continue;
    for (let row = r.minRow; row <= Math.min(r.maxRow, localRows.value); row++) {
      out.push(col + row);
    }
  }
  return out;
});

const selectionCount = computed(() => selectedAddresses.value.length);

function onCellPointerDown(addr: string, ev: PointerEvent): void {
  if (ev.button !== 0 || editingAddr.value === addr) return;
  selectedColumn.value = null;
  selectedRow.value = null;
  if (ev.shiftKey && selectedAddr.value) {
    selectionFocus.value = addr; // extend the rectangle from the anchor
    return;
  }
  selectedAddr.value = addr;
  selectionFocus.value = null;
  selecting.value = true;
}

function onCellPointerEnter(addr: string): void {
  if (selecting.value) selectionFocus.value = addr;
}

function endSelecting(): void { selecting.value = false; }

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
  // Shift row heights: drop the removed row, renumber higher ones down.
  const newHeights: Record<string, number> = {};
  for (const [r, h] of Object.entries(localRowHeights.value)) {
    const idx = parseInt(r, 10);
    if (idx === row) continue;
    newHeights[idx > row ? String(idx - 1) : r] = h;
  }
  localRowHeights.value = newHeights;
  selectedAddr.value = null;
  cancelEdit();
  emitDoc();
}

function deleteSelectedColumn(): void {
  const col = activeColumn.value;
  if (!col) return;
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
  // Shift column metadata: drop the removed column, renumber higher ones down.
  const newColumns: Record<string, SheetColumn> = {};
  for (const [c, meta] of Object.entries(localColumns.value)) {
    const idx = columnIndexFromLetter(c);
    if (idx === colIdx) continue;
    newColumns[idx > colIdx ? columnLetterFromIndex(idx - 1) : c] = meta;
  }
  localColumns.value = newColumns;
  selectedAddr.value = null;
  selectedColumn.value = null;
  cancelEdit();
  emitDoc();
}

// ── Column selection / resize / border ─────────────────────────────

/** The column a column-op targets: an explicit header selection, else
 *  the column of the selected cell. */
const activeColumn = computed<string | null>(() => {
  if (selectedColumn.value) return selectedColumn.value;
  if (selectedAddr.value) {
    const m = /^([A-Z]+)[0-9]+$/.exec(selectedAddr.value);
    if (m) return m[1];
  }
  return null;
});

function selectColumn(col: string): void {
  selectedColumn.value = col;
  selectedAddr.value = null;
  selectionFocus.value = null;
  selectedRow.value = null;
  cancelEdit();
}

const columnBorderLabel = computed<string>(() => {
  const col = activeColumn.value;
  const b = col ? localColumns.value[col]?.border : undefined;
  return b ?? '—';
});

// ── Row selection / border ─────────────────────────────────────────

/** The row a row-op targets: an explicit row-number selection, else the
 *  row of the selected cell. */
const activeRow = computed<number | null>(() => {
  if (selectedRow.value != null) return selectedRow.value;
  if (selectedAddr.value) {
    const m = /^[A-Z]+([0-9]+)$/.exec(selectedAddr.value);
    if (m) return parseInt(m[1], 10);
  }
  return null;
});

function selectRow(row: number): void {
  selectedRow.value = row;
  selectedAddr.value = null;
  selectionFocus.value = null;
  selectedColumn.value = null;
  cancelEdit();
}

const rowBorderLabel = computed<string>(() => {
  const row = activeRow.value;
  const b = row != null ? localRowBorders.value[String(row)] : undefined;
  return b ?? '—';
});

const ROW_BORDER_CYCLE: (string | undefined)[] = [undefined, 'bottom', 'top', 'both'];

/** Cycle the active row's border none → bottom → top → both → none. */
function cycleRowBorder(): void {
  const row = activeRow.value;
  if (row == null) return;
  const key = String(row);
  const cur = localRowBorders.value[key];
  const idx = ROW_BORDER_CYCLE.indexOf(cur ?? undefined);
  const next = ROW_BORDER_CYCLE[(idx + 1) % ROW_BORDER_CYCLE.length];
  if (next === undefined) {
    const { [key]: _drop, ...rest } = localRowBorders.value;
    localRowBorders.value = rest;
  } else {
    localRowBorders.value = { ...localRowBorders.value, [key]: next };
  }
  emitDoc();
}

/** CSS borders for a row: top/bottom edges on every cell of the row. */
function rowBorderStyle(row: number): Record<string, string> {
  const b = localRowBorders.value[String(row)];
  if (!b) return {};
  const line = '2px solid oklch(var(--bc) / 0.5)';
  const out: Record<string, string> = {};
  if (b === 'top' || b === 'both') out.borderTop = line;
  if (b === 'bottom' || b === 'both') out.borderBottom = line;
  return out;
}

const BORDER_CYCLE: (string | undefined)[] = [undefined, 'right', 'left', 'both'];

/** Cycle the active column's border none → right → left → both → none. */
function cycleColumnBorder(): void {
  const col = activeColumn.value;
  if (!col) return;
  const cur = localColumns.value[col]?.border;
  const idx = BORDER_CYCLE.indexOf(cur ?? undefined);
  const next = BORDER_CYCLE[(idx + 1) % BORDER_CYCLE.length];
  patchColumn(col, { border: next });
}

function patchColumn(col: string, patch: Partial<SheetColumn>): void {
  const merged: SheetColumn = { ...localColumns.value[col], ...patch };
  if (merged.border === undefined && merged.width === undefined) {
    const { [col]: _drop, ...rest } = localColumns.value;
    localColumns.value = rest;
  } else {
    // strip explicit-undefined keys so the codec/emit stays sparse
    const clean: SheetColumn = {};
    if (merged.width !== undefined) clean.width = merged.width;
    if (merged.border !== undefined && merged.border !== '') clean.border = merged.border;
    localColumns.value = { ...localColumns.value, [col]: clean };
  }
  emitDoc();
}

const COLUMN_MIN_WIDTH = 48;
const COLUMN_DEFAULT_WIDTH = 112;

function columnWidth(col: string): number | null {
  return localColumns.value[col]?.width ?? null;
}

let resizeCol: string | null = null;
let resizeStartX = 0;
let resizeStartWidth = 0;

function startColumnResize(col: string, ev: PointerEvent): void {
  ev.preventDefault();
  ev.stopPropagation();
  resizeCol = col;
  resizeStartX = ev.clientX;
  resizeStartWidth = columnWidth(col) ?? COLUMN_DEFAULT_WIDTH;
  window.addEventListener('pointermove', onColumnResizeMove);
  window.addEventListener('pointerup', onColumnResizeEnd, { once: true });
}

function onColumnResizeMove(ev: PointerEvent): void {
  if (!resizeCol) return;
  const w = Math.max(COLUMN_MIN_WIDTH, Math.round(resizeStartWidth + (ev.clientX - resizeStartX)));
  // live update without persisting every pixel
  localColumns.value = {
    ...localColumns.value,
    [resizeCol]: { ...localColumns.value[resizeCol], width: w },
  };
}

function onColumnResizeEnd(): void {
  window.removeEventListener('pointermove', onColumnResizeMove);
  if (resizeCol) {
    // persist the final width via the sparse-normalising patch
    patchColumn(resizeCol, { width: localColumns.value[resizeCol]?.width });
  }
  resizeCol = null;
}

// ── Row height (drag on the row-number edge) ───────────────────────

const ROW_MIN_HEIGHT = 22;
const ROW_DEFAULT_HEIGHT = 30;

function rowHeight(row: number): number | null {
  return localRowHeights.value[String(row)] ?? null;
}

function rowStyle(row: number): Record<string, string> {
  const h = rowHeight(row);
  return h != null ? { height: `${h}px` } : {};
}

let resizeRow: number | null = null;
let rowResizeStartY = 0;
let rowResizeStartHeight = 0;

function startRowResize(row: number, ev: PointerEvent): void {
  ev.preventDefault();
  ev.stopPropagation();
  resizeRow = row;
  rowResizeStartY = ev.clientY;
  rowResizeStartHeight = rowHeight(row) ?? ROW_DEFAULT_HEIGHT;
  window.addEventListener('pointermove', onRowResizeMove);
  window.addEventListener('pointerup', onRowResizeEnd, { once: true });
}

function onRowResizeMove(ev: PointerEvent): void {
  if (resizeRow == null) return;
  const h = Math.max(ROW_MIN_HEIGHT, Math.round(rowResizeStartHeight + (ev.clientY - rowResizeStartY)));
  localRowHeights.value = { ...localRowHeights.value, [String(resizeRow)]: h };
}

function onRowResizeEnd(): void {
  window.removeEventListener('pointermove', onRowResizeMove);
  resizeRow = null;
  emitDoc(); // persist the final height
}

// ── Format actions (side panel) ────────────────────────────────────

/** The value of a string cell attribute shared by ALL selected cells,
 *  or undefined when they differ / none is set (→ panel shows "none"). */
function selectionCommon(attr: 'color' | 'background' | 'align' | 'numberFormat'): string | undefined {
  const addrs = selectedAddresses.value;
  if (!addrs.length) return undefined;
  const first = getCell(addrs[0])?.[attr];
  return addrs.every((a) => getCell(a)?.[attr] === first) ? (first ?? undefined) : undefined;
}

const selectionColor = computed(() => selectionCommon('color'));
const selectionBackground = computed(() => selectionCommon('background'));
const selectionAlign = computed(() => selectionCommon('align'));
const selectionNumberFormat = computed(() => selectionCommon('numberFormat') ?? '');
const selectionBold = computed(() =>
  selectedAddresses.value.length > 0 && selectedAddresses.value.every((a) => getCell(a)?.bold === true));
const selectionItalic = computed(() =>
  selectedAddresses.value.length > 0 && selectedAddresses.value.every((a) => getCell(a)?.italic === true));
const selectionHasFormat = computed(() =>
  selectedAddresses.value.some((a) => {
    const c = getCell(a);
    return c?.color || c?.background || c?.bold || c?.italic || c?.align
      || c?.numberFormat || c?.borders;
  }));

function toggleBold(): void { applyToSelection({ bold: selectionBold.value ? undefined : true }); }
function toggleItalic(): void { applyToSelection({ italic: selectionItalic.value ? undefined : true }); }
function setAlign(a: string): void {
  applyToSelection({ align: selectionAlign.value === a ? undefined : a });
}
function setNumberFormat(code: string): void {
  applyToSelection({ numberFormat: code ? code : undefined });
}

const NUMBER_FORMATS: { value: string; labelKey: string }[] = [
  { value: '', labelKey: 'documents.sheetView.fmtGeneral' },
  { value: '#,##0.00', labelKey: 'documents.sheetView.fmtNumber' },
  { value: '#,##0', labelKey: 'documents.sheetView.fmtInteger' },
  { value: '0%', labelKey: 'documents.sheetView.fmtPercent' },
  { value: '@', labelKey: 'documents.sheetView.fmtText' },
];

/** Apply a formatting patch to EVERY selected cell in one batch (one emit). */
function applyToSelection(patch: Partial<SheetCell>): void {
  const addrs = selectedAddresses.value;
  if (!addrs.length) return;
  const cells = [...localCells.value];
  const byField = new Map<string, number>();
  cells.forEach((c, i) => byField.set(c.field, i));
  for (const addr of addrs) {
    const idx = byField.get(addr);
    if (idx != null) {
      cells[idx] = { ...cells[idx], ...patch, field: addr };
    } else {
      cells.push({ field: addr, data: '', extra: {}, ...patch });
      byField.set(addr, cells.length - 1);
    }
  }
  localCells.value = cells.filter((c) => !cellShouldBeDropped(c));
  emitDoc();
}

/** Like applyToSelection but the patch is computed per cell (needed for
 *  borders, where each cell's new value depends on its current one). */
function applyPerCell(patchFn: (addr: string, cell: SheetCell | undefined) => Partial<SheetCell>): void {
  const addrs = selectedAddresses.value;
  if (!addrs.length) return;
  const cells = [...localCells.value];
  const byField = new Map<string, number>();
  cells.forEach((c, i) => byField.set(c.field, i));
  for (const addr of addrs) {
    const idx = byField.get(addr);
    const existing = idx != null ? cells[idx] : undefined;
    const patch = patchFn(addr, existing);
    if (idx != null) {
      cells[idx] = { ...cells[idx], ...patch, field: addr };
    } else {
      cells.push({ field: addr, data: '', extra: {}, ...patch });
      byField.set(addr, cells.length - 1);
    }
  }
  localCells.value = cells.filter((c) => !cellShouldBeDropped(c));
  emitDoc();
}

// ── Cell borders (per side, additive to column/row borders) ────────

function selectionBorderSide(side: string): boolean {
  const addrs = selectedAddresses.value;
  return addrs.length > 0 && addrs.every((a) => (getCell(a)?.borders ?? '').includes(side));
}

function toggleBorderSide(side: string): void {
  const all = selectionBorderSide(side);
  applyPerCell((_addr, cell) => {
    const cur = cell?.borders ?? '';
    const next = all ? cur.replace(side, '') : cur + side;
    return { borders: normalizeBorders(next) || undefined };
  });
}

/** Outer frame: enable the outward edges of the cells on the selection's
 *  perimeter, so a border is drawn around the whole selection. Additive. */
function applyOuterFrame(): void {
  const r = selectionRect.value;
  if (!r) return;
  applyPerCell((addr, cell) => {
    const p = cellPos(addr);
    if (!p) return {};
    let b = cell?.borders ?? '';
    if (p.row === r.minRow) b += 't';
    if (p.row === Math.min(r.maxRow, localRows.value)) b += 'b';
    if (p.pos === r.minPos) b += 'l';
    if (p.pos === r.maxPos) b += 'r';
    return { borders: normalizeBorders(b) || undefined };
  });
}

function clearBorders(): void {
  applyPerCell(() => ({ borders: undefined }));
}

const selectionHasBorders = computed(() =>
  selectedAddresses.value.some((a) => getCell(a)?.borders));

function setColor(color: string): void {
  applyToSelection({ color: color && color.length > 0 ? color : undefined });
}

function setBackground(bg: string): void {
  applyToSelection({ background: bg && bg.length > 0 ? bg : undefined });
}

function clearCellFormat(): void {
  applyToSelection({
    color: undefined, background: undefined,
    bold: undefined, italic: undefined, align: undefined, numberFormat: undefined,
    borders: undefined,
  });
}

// ── Helpers for template ───────────────────────────────────────────

const gridStyle = computed(() => {
  // Excel-like: fixed column widths (no 1fr) so the grid keeps its
  // natural width and scrolls horizontally instead of squeezing columns
  // into the viewport. Unlike `records`, a sheet is a spatial grid.
  const cols = localSchema.value
    .map((col) => `${columnWidth(col) ?? COLUMN_DEFAULT_WIDTH}px`)
    .join(' ');
  return { gridTemplateColumns: `2.5rem ${cols}` };
});

function columnBorderStyle(col: string): Record<string, string> {
  const b = localColumns.value[col]?.border;
  if (!b) return {};
  const line = '2px solid oklch(var(--bc) / 0.5)';
  const out: Record<string, string> = {};
  if (b === 'left' || b === 'both') out.borderLeft = line;
  if (b === 'right' || b === 'both') out.borderRight = line;
  return out;
}

const rowNumbers = computed<number[]>(() => {
  const out: number[] = [];
  for (let r = 1; r <= localRows.value; r++) out.push(r);
  return out;
});

function isFormula(addr: string): boolean {
  const v = getValue(addr);
  return v.startsWith('=');
}

// ── Computed overlay (server-evaluated, finance-style) ──────────────

function computedMap(c?: SheetComputed): Map<string, SheetComputedValue> {
  const m = new Map<string, SheetComputedValue>();
  if (c) for (const v of c.values) m.set(v.field, v);
  return m;
}

const computedValues = ref<Map<string, SheetComputedValue>>(computedMap(props.doc.computed));
watch(() => props.doc.computed, (c) => { computedValues.value = computedMap(c); }, { deep: true });

const canRecalc = computed(() => !!(props.projectId && props.docPath));
const recalcing = ref(false);
let recalcTimer: ReturnType<typeof setTimeout> | null = null;

/** Recompute formulas server-side (Apache POI) and refresh the overlay. */
async function recalc(): Promise<void> {
  if (!canRecalc.value || recalcing.value) return;
  recalcing.value = true;
  try {
    const res = await brainFetch<SheetComputed>(
      'POST',
      `sheet/calc?projectId=${encodeURIComponent(props.projectId!)}`
        + `&path=${encodeURIComponent(props.docPath!)}`,
    );
    computedValues.value = computedMap(res);
  } catch {
    // best-effort; the "Neu berechnen" button retries
  } finally {
    recalcing.value = false;
  }
}

/** Debounced auto-recalc after an edit — long enough for the shell's
 *  content save to land first (the endpoint evaluates the persisted doc). */
function scheduleRecalc(): void {
  if (!canRecalc.value) return;
  if (recalcTimer) clearTimeout(recalcTimer);
  recalcTimer = setTimeout(() => void recalc(), 1500);
}

onMounted(() => {
  window.addEventListener('pointerup', endSelecting);
});
onBeforeUnmount(() => {
  if (recalcTimer) clearTimeout(recalcTimer);
  window.removeEventListener('pointermove', onColumnResizeMove);
  window.removeEventListener('pointermove', onRowResizeMove);
  window.removeEventListener('pointerup', endSelecting);
});

/** Displayed cell content: computed value for a formula cell (once
 *  evaluated), otherwise the raw source. Editing always shows the source. */
function cellDisplay(addr: string): string {
  const c = getCell(addr);
  if (!c) return '';
  let raw: string;
  if (c.data.startsWith('=')) {
    const cv = computedValues.value.get(addr);
    raw = cv ? cv.value : c.data;
  } else {
    raw = c.data;
  }
  return c.numberFormat ? applyNumberFormat(raw, c.numberFormat) : raw;
}

function cellIsError(addr: string): boolean {
  return computedValues.value.get(addr)?.type === 'error';
}

function cellStyle(addr: string): Record<string, string> {
  const c = getCell(addr);
  const m = /^([A-Z]+)([0-9]+)$/.exec(addr);
  // column + row borders form the base (additive); cell borders override per side.
  const out: Record<string, string> = m
    ? { ...columnBorderStyle(m[1]), ...rowBorderStyle(parseInt(m[2], 10)) }
    : {};
  if (c?.color) out.color = c.color;
  if (c?.background) out.background = c.background;
  if (c?.bold) out.fontWeight = '700';
  if (c?.italic) out.fontStyle = 'italic';
  if (c?.align) out.textAlign = c.align;
  const b = c?.borders;
  if (b) {
    const line = '2px solid oklch(var(--bc) / 0.75)';
    if (b.includes('t')) out.borderTop = line;
    if (b.includes('r')) out.borderRight = line;
    if (b.includes('b')) out.borderBottom = line;
    if (b.includes('l')) out.borderLeft = line;
  }
  return out;
}

/** Apply an Excel-style number format code to a display value. Supports
 *  the common subset (text `@`, percent, grouping, fixed decimals). */
function applyNumberFormat(raw: string, code: string): string {
  if (code === '@') return raw;
  if (raw === '') return raw;
  const n = Number(raw);
  if (!Number.isFinite(n)) return raw;
  const isPct = code.includes('%');
  const useGrouping = code.includes(',');
  const dot = code.indexOf('.');
  const decimals = dot >= 0 ? code.slice(dot + 1).replace(/[^0#]/g, '').length : 0;
  const value = isPct ? n * 100 : n;
  const fmt = new Intl.NumberFormat(undefined, {
    useGrouping,
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
  return fmt.format(value) + (isPct ? '%' : '');
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
        :disabled="!activeColumn || localSchema.length <= 1"
        @click="deleteSelectedColumn"
      >{{ t('documents.sheetView.deleteColumn') }}</VButton>
      <VButton
        v-if="activeColumn"
        size="sm"
        variant="ghost"
        :title="t('documents.sheetView.columnBorderHint')"
        @click="cycleColumnBorder"
      >{{ t('documents.sheetView.columnBorder') }}: {{ columnBorderLabel }}</VButton>
      <VButton
        v-if="activeRow != null"
        size="sm"
        variant="ghost"
        :title="t('documents.sheetView.rowBorderHint')"
        @click="cycleRowBorder"
      >{{ t('documents.sheetView.rowBorder') }}: {{ rowBorderLabel }}</VButton>
      <VButton
        v-if="canRecalc"
        size="sm"
        variant="ghost"
        :disabled="recalcing"
        :title="t('documents.sheetView.recalcHint')"
        @click="recalc"
      >{{ recalcing ? '…' : t('documents.sheetView.recalc') }}</VButton>
      <span class="hint">{{ t('documents.sheetView.hint') }}</span>
    </div>

    <div class="grid-and-panel">
      <div class="grid-wrap">
        <div class="header-row" :style="gridStyle">
          <span class="header-corner" aria-hidden="true" />
          <div
            v-for="col in localSchema"
            :key="col"
            class="header-col"
            :class="{ 'header-col--selected': activeColumn === col }"
            :style="columnBorderStyle(col)"
            :title="col"
            @click="selectColumn(col)"
          >
            <span class="header-col-label">{{ col }}</span>
            <span
              class="col-resize"
              @click.stop
              @pointerdown="startColumnResize(col, $event)"
            />
          </div>
        </div>
        <div
          v-for="row in rowNumbers"
          :key="row"
          class="data-row"
          :style="{ ...gridStyle, ...rowStyle(row) }"
        >
          <span
            class="row-num"
            :class="{ 'row-num--selected': activeRow === row }"
            @click="selectRow(row)"
          >
            {{ row }}
            <span
              class="row-resize"
              @click.stop
              @pointerdown="startRowResize(row, $event)"
            />
          </span>
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
                'cell--selected': isSelected(col + row),
                'cell--active': selectedAddr === col + row,
                'cell--col-selected': activeColumn === col,
                'cell--row-selected': activeRow === row,
                'cell--formula': isFormula(col + row),
                'cell--error': cellIsError(col + row),
              }"
              :style="cellStyle(col + row)"
              :title="isFormula(col + row) ? getValue(col + row) : (col + row)"
              @pointerdown="onCellPointerDown(col + row, $event)"
              @pointerenter="onCellPointerEnter(col + row)"
              @dblclick="startEdit(col + row)"
            >
              <span class="cell-text">{{ cellDisplay(col + row) }}</span>
            </button>
          </template>
        </div>
      </div>

      <aside v-if="selectionCount > 0" class="panel">
        <h4>{{ t('documents.sheetView.cellProps') }}</h4>
        <p class="cell-addr">
          {{ selectionCount === 1
            ? selectedAddr
            : t('documents.sheetView.cellsSelected', { n: selectionCount }) }}
        </p>
        <label>
          {{ t('documents.sheetView.colorField') }}
          <div class="color-row">
            <input
              type="color"
              :value="selectionColor ?? '#1f2937'"
              @input="(e) => setColor((e.target as HTMLInputElement).value)"
            />
            <button
              type="button"
              class="clear-btn"
              :disabled="!selectionColor"
              @click="setColor('')"
            >{{ t('documents.sheetView.clear') }}</button>
          </div>
        </label>
        <label>
          {{ t('documents.sheetView.backgroundField') }}
          <div class="color-row">
            <input
              type="color"
              :value="selectionBackground ?? '#ffffff'"
              @input="(e) => setBackground((e.target as HTMLInputElement).value)"
            />
            <button
              type="button"
              class="clear-btn"
              :disabled="!selectionBackground"
              @click="setBackground('')"
            >{{ t('documents.sheetView.clear') }}</button>
          </div>
        </label>
        <label>
          {{ t('documents.sheetView.style') }}
          <div class="fmt-row">
            <button
              type="button"
              class="fmt-btn"
              :class="{ 'fmt-btn--on': selectionBold }"
              style="font-weight: 700"
              :title="t('documents.sheetView.bold')"
              @click="toggleBold"
            >B</button>
            <button
              type="button"
              class="fmt-btn"
              :class="{ 'fmt-btn--on': selectionItalic }"
              style="font-style: italic"
              :title="t('documents.sheetView.italic')"
              @click="toggleItalic"
            >I</button>
            <span class="fmt-sep" aria-hidden="true" />
            <button
              type="button"
              class="fmt-btn"
              :class="{ 'fmt-btn--on': selectionAlign === 'left' }"
              :title="t('documents.sheetView.alignLeft')"
              @click="setAlign('left')"
            >⇤</button>
            <button
              type="button"
              class="fmt-btn"
              :class="{ 'fmt-btn--on': selectionAlign === 'center' }"
              :title="t('documents.sheetView.alignCenter')"
              @click="setAlign('center')"
            >↔</button>
            <button
              type="button"
              class="fmt-btn"
              :class="{ 'fmt-btn--on': selectionAlign === 'right' }"
              :title="t('documents.sheetView.alignRight')"
              @click="setAlign('right')"
            >⇥</button>
          </div>
        </label>
        <label>
          {{ t('documents.sheetView.numberFormat') }}
          <select
            class="fmt-select"
            :value="selectionNumberFormat"
            @change="(e) => setNumberFormat((e.target as HTMLSelectElement).value)"
          >
            <option v-for="f in NUMBER_FORMATS" :key="f.value" :value="f.value">
              {{ t(f.labelKey) }}
            </option>
          </select>
        </label>
        <label>
          {{ t('documents.sheetView.borders') }}
          <div class="fmt-row">
            <button
              type="button"
              class="fmt-btn"
              :class="{ 'fmt-btn--on': selectionBorderSide('t') }"
              :title="t('documents.sheetView.borderTop')"
              @click="toggleBorderSide('t')"
            >▔</button>
            <button
              type="button"
              class="fmt-btn"
              :class="{ 'fmt-btn--on': selectionBorderSide('l') }"
              :title="t('documents.sheetView.borderLeft')"
              @click="toggleBorderSide('l')"
            >▏</button>
            <button
              type="button"
              class="fmt-btn"
              :class="{ 'fmt-btn--on': selectionBorderSide('r') }"
              :title="t('documents.sheetView.borderRight')"
              @click="toggleBorderSide('r')"
            >▕</button>
            <button
              type="button"
              class="fmt-btn"
              :class="{ 'fmt-btn--on': selectionBorderSide('b') }"
              :title="t('documents.sheetView.borderBottom')"
              @click="toggleBorderSide('b')"
            >▁</button>
            <span class="fmt-sep" aria-hidden="true" />
            <button
              type="button"
              class="fmt-btn"
              :title="t('documents.sheetView.borderOuter')"
              @click="applyOuterFrame"
            >▢</button>
            <button
              type="button"
              class="fmt-btn"
              :disabled="!selectionHasBorders"
              :title="t('documents.sheetView.borderClear')"
              @click="clearBorders"
            >⌫</button>
          </div>
        </label>
        <VButton
          size="sm"
          variant="ghost"
          :disabled="!selectionHasFormat"
          @click="clearCellFormat"
        >{{ t('documents.sheetView.clearFormat') }}</VButton>
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
  /* Fill the tab body so only the grid scrolls, not the whole page. */
  height: 100%;
  min-height: 0;
  min-width: 0;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  flex-wrap: wrap;
  flex: none;
}
.hint {
  font-size: 0.7rem;
  opacity: 0.55;
  margin-left: auto;
}
.grid-and-panel {
  display: flex;
  gap: 0.75rem;
  align-items: stretch;
  /* Take the remaining height and contain the grid's own scroll. */
  flex: 1 1 auto;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
}
.grid-wrap {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  border: 1px solid oklch(var(--bc) / 0.18);
  border-radius: 0.4rem;
  background: oklch(var(--b1));
  /* The single scroll container: sticky header + row numbers stay put. */
  overflow: auto;
}
.header-row,
.data-row {
  display: grid;
  align-items: stretch;
  border-bottom: 1px solid oklch(var(--bc) / 0.08);
  /* Take the grid's natural (summed) width so it scrolls horizontally,
     but never shrink below the viewport (fills empty space on the right). */
  width: max-content;
  min-width: 100%;
}
.header-row {
  position: sticky;
  top: 0;
  z-index: 2;
  background: oklch(var(--b2));
  border-bottom-color: oklch(var(--bc) / 0.18);
}
.header-corner,
.row-num {
  background: oklch(var(--b2));
  font-family: ui-monospace, monospace;
  font-size: 0.7rem;
  color: oklch(var(--bc) / 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  border-right: 1px solid oklch(var(--bc) / 0.08);
  min-height: 1.85rem;
}
.row-num {
  position: sticky;
  left: 0;
  z-index: 1;
  cursor: pointer;
  user-select: none;
}
.header-corner {
  position: sticky;
  left: 0;
  z-index: 3; /* above the sticky header row (2) and row numbers (1) */
}
.header-col {
  position: relative;
  font-family: ui-monospace, monospace;
  font-size: 0.75rem;
  text-align: center;
  padding: 0.35rem 0;
  letter-spacing: 0.04em;
  color: oklch(var(--bc) / 0.7);
  border-right: 1px solid oklch(var(--bc) / 0.08);
  cursor: pointer;
  user-select: none;
}
.header-col--selected {
  background: oklch(var(--p) / 0.28);
  color: oklch(var(--bc));
  font-weight: 700;
  box-shadow: inset 0 -3px 0 oklch(var(--p));
}
.col-resize {
  position: absolute;
  top: 0;
  right: -3px;
  width: 7px;
  height: 100%;
  cursor: col-resize;
  z-index: 1;
}
.col-resize:hover {
  background: oklch(var(--p) / 0.5);
}
.row-resize {
  position: absolute;
  left: 0;
  bottom: -3px;
  width: 100%;
  height: 7px;
  cursor: row-resize;
  z-index: 2;
}
.row-resize:hover {
  background: oklch(var(--p) / 0.5);
}
.cell--col-selected {
  background: oklch(var(--p) / 0.1);
}
.cell--row-selected {
  background: oklch(var(--p) / 0.1);
}
.row-num--selected {
  background: oklch(var(--p) / 0.28);
  color: oklch(var(--bc));
  font-weight: 700;
  box-shadow: inset -3px 0 0 oklch(var(--p));
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
  cursor: cell;
  border-right: 1px solid oklch(var(--bc) / 0.08);
  user-select: none;
}
.cell-input {
  cursor: text;
  user-select: text;
}
.cell:hover {
  background: oklch(var(--bc) / 0.04);
}
.cell--selected {
  /* every cell in the selection rectangle */
  background: oklch(var(--p) / 0.1);
}
.cell--active {
  /* the anchor cell of the selection */
  outline: 2px solid oklch(var(--p) / 0.7);
  outline-offset: -1px;
  z-index: 1;
}
.cell--formula {
  box-shadow: inset 2px 0 0 oklch(var(--p));
}
.cell--error {
  color: oklch(var(--er));
  box-shadow: inset 2px 0 0 oklch(var(--er));
}
.cell-text {
  display: block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.cell-input {
  border-color: oklch(var(--p));
  box-shadow: 0 0 0 2px oklch(var(--p) / 0.2);
  width: 100%;
}
.panel {
  width: 14rem;
  flex: 0 0 14rem;
  background: oklch(var(--b1));
  border: 1px solid oklch(var(--bc) / 0.15);
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
  border: 1px solid oklch(var(--bc) / 0.25);
  border-radius: 0.25rem;
  background: transparent;
  cursor: pointer;
  padding: 0;
}
.clear-btn {
  background: transparent;
  border: 1px solid oklch(var(--bc) / 0.2);
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
.fmt-row {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  margin-top: 0.2rem;
}
.fmt-btn {
  min-width: 1.75rem;
  height: 1.75rem;
  padding: 0 0.35rem;
  border: 1px solid oklch(var(--bc) / 0.2);
  border-radius: 0.3rem;
  background: transparent;
  color: inherit;
  cursor: pointer;
  line-height: 1;
}
.fmt-btn:hover {
  background: oklch(var(--bc) / 0.06);
}
.fmt-btn--on {
  background: oklch(var(--p) / 0.18);
  border-color: oklch(var(--p) / 0.6);
}
.fmt-sep {
  width: 1px;
  height: 1.25rem;
  background: oklch(var(--bc) / 0.15);
  margin: 0 0.15rem;
}
.fmt-select {
  width: 100%;
  margin-top: 0.2rem;
  padding: 0.25rem 0.35rem;
  border: 1px solid oklch(var(--bc) / 0.2);
  border-radius: 0.3rem;
  background: oklch(var(--b1));
  color: inherit;
  font: inherit;
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
