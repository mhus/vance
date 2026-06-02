import { computed, nextTick, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VButton } from '@/components';
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
const props = defineProps();
const emit = defineEmits();
const { t } = useI18n();
const DEFAULT_COLS = ['A', 'B', 'C'];
const DEFAULT_ROWS = 5;
// ── Local source-of-truth ──────────────────────────────────────────
const localCells = ref(cloneCells(props.doc.cells));
const localSchema = ref(deriveSchema(props.doc.schema, props.doc.cells));
const localRows = ref(deriveRows(props.doc.rows, props.doc.cells));
watch(() => props.doc.cells, (next) => {
    localCells.value = cloneCells(next);
    localSchema.value = deriveSchema(props.doc.schema, next);
    localRows.value = deriveRows(props.doc.rows, next);
}, { deep: true });
watch(() => props.doc.schema, (next) => {
    localSchema.value = deriveSchema(next, localCells.value);
}, { deep: true });
watch(() => props.doc.rows, (next) => {
    localRows.value = deriveRows(next, localCells.value);
});
function cloneCells(src) {
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
function deriveSchema(explicit, cells) {
    if (explicit.length > 0)
        return [...explicit];
    let maxIdx = 0;
    for (const c of cells) {
        const m = /^([A-Z]+)/.exec(c.field);
        if (!m)
            continue;
        const idx = columnIndexFromLetter(m[1]);
        if (idx > maxIdx)
            maxIdx = idx;
    }
    const total = Math.max(maxIdx, DEFAULT_COLS.length);
    const out = [];
    for (let i = 1; i <= total; i++)
        out.push(columnLetterFromIndex(i));
    return out;
}
function deriveRows(explicit, cells) {
    if (explicit != null)
        return explicit;
    let maxRow = 0;
    for (const c of cells) {
        const m = /[A-Z]+([0-9]+)$/.exec(c.field);
        if (!m)
            continue;
        const r = parseInt(m[1], 10);
        if (r > maxRow)
            maxRow = r;
    }
    return Math.max(maxRow, DEFAULT_ROWS);
}
// ── Cell lookup ────────────────────────────────────────────────────
const cellsByAddress = computed(() => {
    const m = new Map();
    for (const c of localCells.value)
        m.set(c.field, c);
    return m;
});
function getCell(addr) {
    return cellsByAddress.value.get(addr);
}
function getValue(addr) {
    return getCell(addr)?.data ?? '';
}
// ── Selection / Edit ───────────────────────────────────────────────
const selectedAddr = ref(null);
const editingAddr = ref(null);
const editBuffer = ref('');
const inputRefs = ref(new Map());
function registerInput(addr, el) {
    if (el)
        inputRefs.value.set(addr, el);
    else
        inputRefs.value.delete(addr);
}
function selectCell(addr) {
    selectedAddr.value = addr;
}
function startEdit(addr) {
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
function cancelEdit() {
    editingAddr.value = null;
    editBuffer.value = '';
}
function commitEdit() {
    if (!editingAddr.value)
        return;
    const addr = editingAddr.value;
    const value = editBuffer.value;
    upsertCell(addr, { data: value });
    editingAddr.value = null;
    editBuffer.value = '';
}
function upsertCell(addr, patch) {
    const idx = localCells.value.findIndex((c) => c.field === addr);
    if (idx >= 0) {
        const merged = { ...localCells.value[idx], ...patch, field: addr };
        if (cellShouldBeDropped(merged)) {
            localCells.value = localCells.value.filter((c) => c.field !== addr);
        }
        else {
            localCells.value = localCells.value.map((c, i) => i === idx ? merged : c);
        }
    }
    else {
        const fresh = {
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
function cellShouldBeDropped(c) {
    return c.data === ''
        && c.color === undefined
        && c.background === undefined
        && Object.keys(c.extra).length === 0;
}
function emitDoc() {
    emit('update:doc', {
        kind: props.doc.kind || 'sheet',
        schema: [...localSchema.value],
        rows: localRows.value,
        cells: localCells.value,
        extra: props.doc.extra,
    });
}
// ── Navigation between cells ───────────────────────────────────────
function nextAddr(addr, dir) {
    const m = /^([A-Z]+)([0-9]+)$/.exec(addr);
    if (!m)
        return null;
    const colIdx = columnIndexFromLetter(m[1]);
    const row = parseInt(m[2], 10);
    const cols = localSchema.value;
    const colsLen = cols.length;
    if (dir === 'right') {
        if (colIdx < colsLen)
            return cols[colIdx] + row;
        if (row < localRows.value)
            return cols[0] + (row + 1);
        return null;
    }
    if (dir === 'left') {
        if (colIdx > 1)
            return cols[colIdx - 2] + row;
        if (row > 1)
            return cols[colsLen - 1] + (row - 1);
        return null;
    }
    if (dir === 'down') {
        if (row < localRows.value)
            return cols[colIdx - 1] + (row + 1);
        return null;
    }
    if (dir === 'up') {
        if (row > 1)
            return cols[colIdx - 1] + (row - 1);
        return null;
    }
    return null;
}
function onEditKeydown(event, addr) {
    if (event.key === 'Enter') {
        event.preventDefault();
        commitEdit();
        let target = nextAddr(addr, 'down');
        if (!target) {
            addRow();
            target = nextAddr(addr, 'down');
        }
        if (target)
            void nextTick(() => startEdit(target));
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
        if (target)
            void nextTick(() => startEdit(target));
    }
}
// ── Toolbar actions ────────────────────────────────────────────────
function addColumn() {
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
function addRow() {
    localRows.value = localRows.value + 1;
    emitDoc();
}
function deleteSelectedRow() {
    if (!selectedAddr.value)
        return;
    const m = /^([A-Z]+)([0-9]+)$/.exec(selectedAddr.value);
    if (!m)
        return;
    const row = parseInt(m[2], 10);
    // Remove cells in this row and renumber cells below it.
    const survivors = [];
    for (const c of localCells.value) {
        const am = /^([A-Z]+)([0-9]+)$/.exec(c.field);
        if (!am)
            continue;
        const cellRow = parseInt(am[2], 10);
        if (cellRow === row)
            continue;
        if (cellRow > row) {
            survivors.push({ ...c, field: am[1] + (cellRow - 1) });
        }
        else {
            survivors.push(c);
        }
    }
    localCells.value = survivors;
    if (localRows.value > 1)
        localRows.value = localRows.value - 1;
    selectedAddr.value = null;
    cancelEdit();
    emitDoc();
}
function deleteSelectedColumn() {
    if (!selectedAddr.value)
        return;
    const m = /^([A-Z]+)([0-9]+)$/.exec(selectedAddr.value);
    if (!m)
        return;
    const col = m[1];
    const colIdx = columnIndexFromLetter(col);
    if (localSchema.value.length <= 1)
        return; // keep at least one column
    // Drop the column from schema, drop matching cells, renumber
    // higher columns down by one letter so the visible layout stays
    // contiguous.
    const newSchema = localSchema.value.filter((c) => c !== col);
    const survivors = [];
    for (const c of localCells.value) {
        const am = /^([A-Z]+)([0-9]+)$/.exec(c.field);
        if (!am)
            continue;
        const cellColIdx = columnIndexFromLetter(am[1]);
        if (cellColIdx === colIdx)
            continue;
        if (cellColIdx > colIdx) {
            survivors.push({ ...c, field: columnLetterFromIndex(cellColIdx - 1) + am[2] });
        }
        else {
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
const selectedCell = computed(() => {
    if (!selectedAddr.value)
        return null;
    return getCell(selectedAddr.value) ?? null;
});
function setColor(color) {
    if (!selectedAddr.value)
        return;
    upsertCell(selectedAddr.value, {
        color: color && color.length > 0 ? color : undefined,
    });
}
function setBackground(bg) {
    if (!selectedAddr.value)
        return;
    upsertCell(selectedAddr.value, {
        background: bg && bg.length > 0 ? bg : undefined,
    });
}
function clearCellFormat() {
    if (!selectedAddr.value)
        return;
    upsertCell(selectedAddr.value, { color: undefined, background: undefined });
}
// ── Helpers for template ───────────────────────────────────────────
const gridStyle = computed(() => ({
    gridTemplateColumns: `2.5rem repeat(${localSchema.value.length}, minmax(6rem, 1fr))`,
}));
const rowNumbers = computed(() => {
    const out = [];
    for (let r = 1; r <= localRows.value; r++)
        out.push(r);
    return out;
});
function isFormula(addr) {
    const v = getValue(addr);
    return v.startsWith('=');
}
function cellStyle(addr) {
    const c = getCell(addr);
    if (!c)
        return {};
    const out = {};
    if (c.color)
        out.color = c.color;
    if (c.background)
        out.background = c.background;
    return out;
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['header-row']} */ ;
/** @type {__VLS_StyleScopedClasses['row-num']} */ ;
/** @type {__VLS_StyleScopedClasses['cell']} */ ;
/** @type {__VLS_StyleScopedClasses['cell-input']} */ ;
/** @type {__VLS_StyleScopedClasses['panel']} */ ;
/** @type {__VLS_StyleScopedClasses['panel']} */ ;
/** @type {__VLS_StyleScopedClasses['color-row']} */ ;
/** @type {__VLS_StyleScopedClasses['clear-btn']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "sheet-view" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "toolbar" },
});
const __VLS_0 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "ghost",
}));
const __VLS_2 = __VLS_1({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
let __VLS_4;
let __VLS_5;
let __VLS_6;
const __VLS_7 = {
    onClick: (__VLS_ctx.addRow)
};
__VLS_3.slots.default;
(__VLS_ctx.t('documents.sheetView.addRow'));
var __VLS_3;
const __VLS_8 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "ghost",
}));
const __VLS_10 = __VLS_9({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_9));
let __VLS_12;
let __VLS_13;
let __VLS_14;
const __VLS_15 = {
    onClick: (__VLS_ctx.addColumn)
};
__VLS_11.slots.default;
(__VLS_ctx.t('documents.sheetView.addColumn'));
var __VLS_11;
const __VLS_16 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "ghost",
    disabled: (!__VLS_ctx.selectedAddr),
}));
const __VLS_18 = __VLS_17({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "ghost",
    disabled: (!__VLS_ctx.selectedAddr),
}, ...__VLS_functionalComponentArgsRest(__VLS_17));
let __VLS_20;
let __VLS_21;
let __VLS_22;
const __VLS_23 = {
    onClick: (__VLS_ctx.deleteSelectedRow)
};
__VLS_19.slots.default;
(__VLS_ctx.t('documents.sheetView.deleteRow'));
var __VLS_19;
const __VLS_24 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_25 = __VLS_asFunctionalComponent(__VLS_24, new __VLS_24({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "ghost",
    disabled: (!__VLS_ctx.selectedAddr || __VLS_ctx.localSchema.length <= 1),
}));
const __VLS_26 = __VLS_25({
    ...{ 'onClick': {} },
    size: "sm",
    variant: "ghost",
    disabled: (!__VLS_ctx.selectedAddr || __VLS_ctx.localSchema.length <= 1),
}, ...__VLS_functionalComponentArgsRest(__VLS_25));
let __VLS_28;
let __VLS_29;
let __VLS_30;
const __VLS_31 = {
    onClick: (__VLS_ctx.deleteSelectedColumn)
};
__VLS_27.slots.default;
(__VLS_ctx.t('documents.sheetView.deleteColumn'));
var __VLS_27;
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "hint" },
});
(__VLS_ctx.t('documents.sheetView.hint'));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "grid-and-panel" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "grid-wrap" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "header-row" },
    ...{ style: (__VLS_ctx.gridStyle) },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
    ...{ class: "header-corner" },
    'aria-hidden': "true",
});
for (const [col] of __VLS_getVForSourceType((__VLS_ctx.localSchema))) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        key: (col),
        ...{ class: "header-col" },
    });
    (col);
}
for (const [row] of __VLS_getVForSourceType((__VLS_ctx.rowNumbers))) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        key: (row),
        ...{ class: "data-row" },
        ...{ style: (__VLS_ctx.gridStyle) },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "row-num" },
        'aria-hidden': "true",
    });
    (row);
    for (const [col] of __VLS_getVForSourceType((__VLS_ctx.localSchema))) {
        (col + row);
        if (__VLS_ctx.editingAddr === col + row) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
                ...{ onBlur: (__VLS_ctx.commitEdit) },
                ...{ onKeydown: (...[$event]) => {
                        if (!(__VLS_ctx.editingAddr === col + row))
                            return;
                        __VLS_ctx.onEditKeydown($event, col + row);
                    } },
                ref: ((el) => __VLS_ctx.registerInput(col + row, el)),
                value: (__VLS_ctx.editBuffer),
                type: "text",
                ...{ class: "cell-input" },
                ...{ style: (__VLS_ctx.cellStyle(col + row)) },
            });
        }
        else {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(__VLS_ctx.editingAddr === col + row))
                            return;
                        __VLS_ctx.selectCell(col + row);
                    } },
                ...{ onDblclick: (...[$event]) => {
                        if (!!(__VLS_ctx.editingAddr === col + row))
                            return;
                        __VLS_ctx.startEdit(col + row);
                    } },
                type: "button",
                ...{ class: "cell" },
                ...{ class: ({
                        'cell--selected': __VLS_ctx.selectedAddr === col + row,
                        'cell--formula': __VLS_ctx.isFormula(col + row),
                    }) },
                ...{ style: (__VLS_ctx.cellStyle(col + row)) },
                title: (col + row),
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "cell-text" },
            });
            (__VLS_ctx.getValue(col + row));
        }
    }
}
if (__VLS_ctx.selectedCell) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.aside, __VLS_intrinsicElements.aside)({
        ...{ class: "panel" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h4, __VLS_intrinsicElements.h4)({});
    (__VLS_ctx.t('documents.sheetView.cellProps'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "cell-addr" },
    });
    (__VLS_ctx.selectedAddr);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.label, __VLS_intrinsicElements.label)({});
    (__VLS_ctx.t('documents.sheetView.colorField'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "color-row" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
        ...{ onInput: ((e) => __VLS_ctx.setColor(e.target.value)) },
        type: "color",
        value: (__VLS_ctx.selectedCell.color ?? '#1f2937'),
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.selectedCell))
                    return;
                __VLS_ctx.setColor('');
            } },
        type: "button",
        ...{ class: "clear-btn" },
        disabled: (!__VLS_ctx.selectedCell.color),
    });
    (__VLS_ctx.t('documents.sheetView.clear'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.label, __VLS_intrinsicElements.label)({});
    (__VLS_ctx.t('documents.sheetView.backgroundField'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "color-row" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
        ...{ onInput: ((e) => __VLS_ctx.setBackground(e.target.value)) },
        type: "color",
        value: (__VLS_ctx.selectedCell.background ?? '#ffffff'),
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.selectedCell))
                    return;
                __VLS_ctx.setBackground('');
            } },
        type: "button",
        ...{ class: "clear-btn" },
        disabled: (!__VLS_ctx.selectedCell.background),
    });
    (__VLS_ctx.t('documents.sheetView.clear'));
    const __VLS_32 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_33 = __VLS_asFunctionalComponent(__VLS_32, new __VLS_32({
        ...{ 'onClick': {} },
        size: "sm",
        variant: "ghost",
        disabled: (!__VLS_ctx.selectedCell.color && !__VLS_ctx.selectedCell.background),
    }));
    const __VLS_34 = __VLS_33({
        ...{ 'onClick': {} },
        size: "sm",
        variant: "ghost",
        disabled: (!__VLS_ctx.selectedCell.color && !__VLS_ctx.selectedCell.background),
    }, ...__VLS_functionalComponentArgsRest(__VLS_33));
    let __VLS_36;
    let __VLS_37;
    let __VLS_38;
    const __VLS_39 = {
        onClick: (__VLS_ctx.clearCellFormat)
    };
    __VLS_35.slots.default;
    (__VLS_ctx.t('documents.sheetView.clearFormat'));
    var __VLS_35;
}
else if (__VLS_ctx.selectedAddr) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.aside, __VLS_intrinsicElements.aside)({
        ...{ class: "panel" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h4, __VLS_intrinsicElements.h4)({});
    (__VLS_ctx.t('documents.sheetView.cellProps'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "cell-addr" },
    });
    (__VLS_ctx.selectedAddr);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "panel-empty-hint" },
    });
    (__VLS_ctx.t('documents.sheetView.cellEmptyHint'));
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.aside, __VLS_intrinsicElements.aside)({
        ...{ class: "panel panel--empty" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "panel-empty-hint" },
    });
    (__VLS_ctx.t('documents.sheetView.emptySelectionHint'));
}
/** @type {__VLS_StyleScopedClasses['sheet-view']} */ ;
/** @type {__VLS_StyleScopedClasses['toolbar']} */ ;
/** @type {__VLS_StyleScopedClasses['hint']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-and-panel']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['header-row']} */ ;
/** @type {__VLS_StyleScopedClasses['header-corner']} */ ;
/** @type {__VLS_StyleScopedClasses['header-col']} */ ;
/** @type {__VLS_StyleScopedClasses['data-row']} */ ;
/** @type {__VLS_StyleScopedClasses['row-num']} */ ;
/** @type {__VLS_StyleScopedClasses['cell-input']} */ ;
/** @type {__VLS_StyleScopedClasses['cell']} */ ;
/** @type {__VLS_StyleScopedClasses['cell--selected']} */ ;
/** @type {__VLS_StyleScopedClasses['cell--formula']} */ ;
/** @type {__VLS_StyleScopedClasses['cell-text']} */ ;
/** @type {__VLS_StyleScopedClasses['panel']} */ ;
/** @type {__VLS_StyleScopedClasses['cell-addr']} */ ;
/** @type {__VLS_StyleScopedClasses['color-row']} */ ;
/** @type {__VLS_StyleScopedClasses['clear-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['color-row']} */ ;
/** @type {__VLS_StyleScopedClasses['clear-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['panel']} */ ;
/** @type {__VLS_StyleScopedClasses['cell-addr']} */ ;
/** @type {__VLS_StyleScopedClasses['panel-empty-hint']} */ ;
/** @type {__VLS_StyleScopedClasses['panel']} */ ;
/** @type {__VLS_StyleScopedClasses['panel--empty']} */ ;
/** @type {__VLS_StyleScopedClasses['panel-empty-hint']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VButton: VButton,
            t: t,
            localSchema: localSchema,
            getValue: getValue,
            selectedAddr: selectedAddr,
            editingAddr: editingAddr,
            editBuffer: editBuffer,
            registerInput: registerInput,
            selectCell: selectCell,
            startEdit: startEdit,
            commitEdit: commitEdit,
            onEditKeydown: onEditKeydown,
            addColumn: addColumn,
            addRow: addRow,
            deleteSelectedRow: deleteSelectedRow,
            deleteSelectedColumn: deleteSelectedColumn,
            selectedCell: selectedCell,
            setColor: setColor,
            setBackground: setBackground,
            clearCellFormat: clearCellFormat,
            gridStyle: gridStyle,
            rowNumbers: rowNumbers,
            isFormula: isFormula,
            cellStyle: cellStyle,
        };
    },
    __typeEmits: {},
    __typeProps: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeEmits: {},
    __typeProps: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=SheetView.vue.js.map