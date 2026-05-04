import { computed, nextTick, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VueDraggable } from 'vue-draggable-plus';
import { VButton } from '@/components';
import { emptyRecord } from './recordsCodec';
const props = defineProps();
const emit = defineEmits();
const { t } = useI18n();
// ── Editor state ────────────────────────────────────────────────────
/** Local mutable copy of the schema. Schema-edit mode mutates this
 *  in place; the body cells re-render against it immediately. */
const localSchema = ref([...props.doc.schema]);
/** Local mutable copy of the record list — vue-draggable-plus mutates
 *  the array via `v-model` during a drag. The watch below mirrors
 *  external doc updates back into this ref (e.g. Raw-tab edits). */
const localItems = ref(cloneItems(props.doc.items));
watch(() => props.doc.items, (next) => { localItems.value = cloneItems(next); }, { deep: true });
watch(() => props.doc.schema, (next) => {
    localSchema.value = [...next];
    localItems.value = cloneItems(props.doc.items);
}, { deep: true });
/** {row, field} of the currently editing cell. `null` = no edit. */
const editingRow = ref(null);
const editingField = ref(null);
/** Buffered text of the input in flight. */
const editBuffer = ref('');
/** Refs to the input elements keyed by {@code `${row}.${field}`}. */
const inputRefs = ref(new Map());
/** Multi-select state — row-level. Set of row indices. */
const selectedRows = ref(new Set());
const selectionAnchor = ref(null);
/** When true, header cells become editable and body-cell editing is
 *  blocked. See spec §5.6 (Schema-Editor). */
const schemaMode = ref(false);
/** Last validation error from a schema mutation — surfaces in the
 *  toolbar so the user knows why a rename was rejected. Cleared on
 *  every successful schema operation and on schema-mode toggle. */
const schemaError = ref(null);
const schema = computed(() => localSchema.value);
function cellKey(row, field) {
    return `${row}.${field}`;
}
function cloneItems(src) {
    return src.map((it) => ({
        values: { ...it.values },
        extra: { ...it.extra },
        overflow: [...it.overflow],
    }));
}
function emitDoc() {
    emit('update:doc', {
        kind: props.doc.kind || 'records',
        schema: [...localSchema.value],
        items: localItems.value,
        extra: props.doc.extra,
    });
}
function clearSelection() {
    selectedRows.value = new Set();
    selectionAnchor.value = null;
}
// ── Edit lifecycle ──────────────────────────────────────────────────
function startEdit(row, field) {
    if (schemaMode.value)
        return;
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
function cancelEdit() {
    editingRow.value = null;
    editingField.value = null;
    editBuffer.value = '';
}
function commitEdit() {
    if (editingRow.value == null || editingField.value == null)
        return;
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
function onEditKeydown(event, row, field) {
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
            if (target)
                void nextTick(() => startEdit(target.row, target.field));
        }
        else {
            const target = nextCell(row, field);
            if (target) {
                void nextTick(() => startEdit(target.row, target.field));
            }
            else {
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
function rowAllEmpty(row, skipField) {
    const item = localItems.value[row];
    if (!item)
        return true;
    for (const f of schema.value) {
        if (f === skipField)
            continue;
        if ((item.values[f] ?? '').length > 0)
            return false;
    }
    return true;
}
function nextCell(row, field) {
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
function prevCell(row, field) {
    const fields = schema.value;
    const fi = fields.indexOf(field);
    if (fi > 0)
        return { row, field: fields[fi - 1] };
    if (row > 0)
        return { row: row - 1, field: fields[fields.length - 1] };
    return null;
}
// ── Row CRUD ────────────────────────────────────────────────────────
function addRow() {
    localItems.value.push(emptyRecord(schema.value));
    clearSelection();
    emitDoc();
}
function addRowAndEdit() {
    addRow();
    void nextTick(() => startEdit(localItems.value.length - 1, schema.value[0]));
}
function deleteRow(row) {
    if (schemaMode.value)
        return;
    localItems.value.splice(row, 1);
    cancelEdit();
    clearSelection();
    emitDoc();
}
function deleteSelected() {
    if (selectedRows.value.size === 0)
        return;
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
function toggleSchemaMode() {
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
function addColumn() {
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
function nextDefaultColumnName() {
    const existing = new Set(localSchema.value);
    for (let n = localSchema.value.length + 1; n < 10000; n++) {
        const candidate = `column_${n}`;
        if (!existing.has(candidate))
            return candidate;
    }
    // Astronomically unlikely fallback.
    return `column_${Date.now()}`;
}
function deleteColumn(idx) {
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
function renameColumn(idx, rawName, inputEl) {
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
        const next = {};
        for (const f of localSchema.value) {
            // For the renamed slot, pull the value out of the OLD key.
            if (f === newName) {
                next[newName] = item.values[oldName] ?? '';
            }
            else {
                next[f] = item.values[f] ?? '';
            }
        }
        item.values = next;
    }
    schemaError.value = null;
    emitDoc();
}
function moveColumn(idx, direction) {
    const target = idx + direction;
    if (target < 0 || target >= localSchema.value.length)
        return;
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
function onHandleClick(event, row) {
    if (schemaMode.value)
        return;
    if (event.shiftKey && selectionAnchor.value != null) {
        event.preventDefault();
        const start = Math.min(selectionAnchor.value, row);
        const end = Math.max(selectionAnchor.value, row);
        const next = new Set(selectedRows.value);
        for (let i = start; i <= end; i++)
            next.add(i);
        selectedRows.value = next;
        cancelEdit();
        return;
    }
    if (event.metaKey || event.ctrlKey) {
        event.preventDefault();
        const next = new Set(selectedRows.value);
        if (next.has(row)) {
            next.delete(row);
        }
        else {
            next.add(row);
            selectionAnchor.value = row;
        }
        selectedRows.value = next;
        cancelEdit();
    }
    // Plain click on the handle without modifiers: leave the click
    // alone — vue-draggable will pick it up for a drag start.
}
function isRowSelected(row) {
    return selectedRows.value.has(row);
}
// ── Drag reorder ────────────────────────────────────────────────────
function onDragStart() {
    cancelEdit();
    clearSelection();
}
function onDragEnd() {
    emitDoc();
}
// ── Layout ──────────────────────────────────────────────────────────
const gridStyle = computed(() => ({
    // Drag handle | N schema columns | delete button (or "+ column" in
    // schema mode — same auto-width slot, content swapped)
    gridTemplateColumns: `1.25rem repeat(${schema.value.length}, minmax(8rem, 1fr)) auto`,
}));
function selectionCount() {
    return selectedRows.value.size;
}
function registerInput(row, field, el) {
    const key = cellKey(row, field);
    if (el) {
        inputRefs.value.set(key, el);
    }
    else {
        inputRefs.value.delete(key);
    }
}
const hasOverflow = computed(() => localItems.value.some((it) => it.overflow.length > 0));
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['header-row']} */ ;
/** @type {__VLS_StyleScopedClasses['header-row']} */ ;
/** @type {__VLS_StyleScopedClasses['row']} */ ;
/** @type {__VLS_StyleScopedClasses['row']} */ ;
/** @type {__VLS_StyleScopedClasses['drag-handle']} */ ;
/** @type {__VLS_StyleScopedClasses['drag-handle']} */ ;
/** @type {__VLS_StyleScopedClasses['cell']} */ ;
/** @type {__VLS_StyleScopedClasses['cell-input']} */ ;
/** @type {__VLS_StyleScopedClasses['row-delete']} */ ;
/** @type {__VLS_StyleScopedClasses['row--frozen']} */ ;
/** @type {__VLS_StyleScopedClasses['drag-handle']} */ ;
/** @type {__VLS_StyleScopedClasses['schema-arrow']} */ ;
/** @type {__VLS_StyleScopedClasses['schema-remove']} */ ;
/** @type {__VLS_StyleScopedClasses['schema-add']} */ ;
/** @type {__VLS_StyleScopedClasses['schema-arrow']} */ ;
/** @type {__VLS_StyleScopedClasses['schema-remove']} */ ;
/** @type {__VLS_StyleScopedClasses['schema-remove']} */ ;
/** @type {__VLS_StyleScopedClasses['schema-add']} */ ;
/** @type {__VLS_StyleScopedClasses['schema-name-input']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "records-edit" },
    ...{ class: ({ 'records-edit--schema': __VLS_ctx.schemaMode }) },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "records-toolbar" },
});
const __VLS_0 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    ...{ 'onClick': {} },
    variant: (__VLS_ctx.schemaMode ? 'primary' : 'ghost'),
    size: "sm",
}));
const __VLS_2 = __VLS_1({
    ...{ 'onClick': {} },
    variant: (__VLS_ctx.schemaMode ? 'primary' : 'ghost'),
    size: "sm",
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
let __VLS_4;
let __VLS_5;
let __VLS_6;
const __VLS_7 = {
    onClick: (__VLS_ctx.toggleSchemaMode)
};
__VLS_3.slots.default;
(__VLS_ctx.schemaMode
    ? __VLS_ctx.t('documents.recordsEditor.doneEditingSchema')
    : __VLS_ctx.t('documents.recordsEditor.editSchema'));
var __VLS_3;
if (__VLS_ctx.schemaError) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "schema-error" },
    });
    (__VLS_ctx.schemaError);
}
if (__VLS_ctx.selectionCount() > 0 && !__VLS_ctx.schemaMode) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "bulk-bar" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "bulk-count" },
    });
    (__VLS_ctx.selectionCount() === 1
        ? __VLS_ctx.t('documents.recordsEditor.selectedCountSingular', { count: __VLS_ctx.selectionCount() })
        : __VLS_ctx.t('documents.recordsEditor.selectedCountPlural', { count: __VLS_ctx.selectionCount() }));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
        ...{ class: "grow" },
    });
    const __VLS_8 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }));
    const __VLS_10 = __VLS_9({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }, ...__VLS_functionalComponentArgsRest(__VLS_9));
    let __VLS_12;
    let __VLS_13;
    let __VLS_14;
    const __VLS_15 = {
        onClick: (__VLS_ctx.clearSelection)
    };
    __VLS_11.slots.default;
    (__VLS_ctx.t('documents.recordsEditor.clearSelection'));
    var __VLS_11;
    const __VLS_16 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
        ...{ 'onClick': {} },
        variant: "danger",
        size: "sm",
    }));
    const __VLS_18 = __VLS_17({
        ...{ 'onClick': {} },
        variant: "danger",
        size: "sm",
    }, ...__VLS_functionalComponentArgsRest(__VLS_17));
    let __VLS_20;
    let __VLS_21;
    let __VLS_22;
    const __VLS_23 = {
        onClick: (__VLS_ctx.deleteSelected)
    };
    __VLS_19.slots.default;
    (__VLS_ctx.t('documents.recordsEditor.deleteSelected'));
    var __VLS_19;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "header-row" },
    ...{ style: (__VLS_ctx.gridStyle) },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
    ...{ class: "header-cell handle-cell" },
    'aria-hidden': "true",
});
if (!__VLS_ctx.schemaMode) {
    for (const [field] of __VLS_getVForSourceType((__VLS_ctx.schema))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            key: (field),
            ...{ class: "header-cell" },
            title: (field),
        });
        (field);
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
        ...{ class: "header-cell action-cell" },
        'aria-hidden': "true",
    });
}
else {
    for (const [field, idx] of __VLS_getVForSourceType((__VLS_ctx.schema))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            key: (field + '@' + idx),
            ...{ class: "header-cell schema-edit" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "schema-controls" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.schemaMode))
                        return;
                    __VLS_ctx.moveColumn(idx, -1);
                } },
            type: "button",
            ...{ class: "schema-arrow" },
            disabled: (idx === 0),
            title: (__VLS_ctx.t('documents.recordsEditor.moveColumnLeft')),
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.schemaMode))
                        return;
                    __VLS_ctx.moveColumn(idx, 1);
                } },
            type: "button",
            ...{ class: "schema-arrow" },
            disabled: (idx === __VLS_ctx.schema.length - 1),
            title: (__VLS_ctx.t('documents.recordsEditor.moveColumnRight')),
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.schemaMode))
                        return;
                    __VLS_ctx.deleteColumn(idx);
                } },
            type: "button",
            ...{ class: "schema-remove" },
            disabled: (__VLS_ctx.schema.length <= 1),
            title: (__VLS_ctx.t('documents.recordsEditor.deleteColumn')),
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
            ...{ onChange: ((e) => __VLS_ctx.renameColumn(idx, e.target.value, e.target)) },
            ...{ onKeydown: (...[$event]) => {
                    if (!!(!__VLS_ctx.schemaMode))
                        return;
                    $event.target.blur();
                } },
            type: "text",
            ...{ class: "schema-name-input" },
            value: (field),
        });
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "header-cell action-cell" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.addColumn) },
        type: "button",
        ...{ class: "schema-add" },
        title: (__VLS_ctx.t('documents.recordsEditor.addColumn')),
    });
}
if (__VLS_ctx.localItems.length > 0) {
    const __VLS_24 = {}.VueDraggable;
    /** @type {[typeof __VLS_components.VueDraggable, typeof __VLS_components.VueDraggable, ]} */ ;
    // @ts-ignore
    const __VLS_25 = __VLS_asFunctionalComponent(__VLS_24, new __VLS_24({
        ...{ 'onStart': {} },
        ...{ 'onEnd': {} },
        modelValue: (__VLS_ctx.localItems),
        tag: "div",
        ...{ class: "rows" },
        animation: (150),
        handle: ".drag-handle",
        disabled: (__VLS_ctx.schemaMode),
        ghostClass: "row--ghost",
        chosenClass: "row--chosen",
        dragClass: "row--drag",
    }));
    const __VLS_26 = __VLS_25({
        ...{ 'onStart': {} },
        ...{ 'onEnd': {} },
        modelValue: (__VLS_ctx.localItems),
        tag: "div",
        ...{ class: "rows" },
        animation: (150),
        handle: ".drag-handle",
        disabled: (__VLS_ctx.schemaMode),
        ghostClass: "row--ghost",
        chosenClass: "row--chosen",
        dragClass: "row--drag",
    }, ...__VLS_functionalComponentArgsRest(__VLS_25));
    let __VLS_28;
    let __VLS_29;
    let __VLS_30;
    const __VLS_31 = {
        onStart: (__VLS_ctx.onDragStart)
    };
    const __VLS_32 = {
        onEnd: (__VLS_ctx.onDragEnd)
    };
    __VLS_27.slots.default;
    for (const [item, row] of __VLS_getVForSourceType((__VLS_ctx.localItems))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            key: (row),
            ...{ class: "row" },
            ...{ class: ({
                    'row--selected': __VLS_ctx.isRowSelected(row),
                    'row--frozen': __VLS_ctx.schemaMode,
                }) },
            ...{ style: (__VLS_ctx.gridStyle) },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ onClick: (...[$event]) => {
                    if (!(__VLS_ctx.localItems.length > 0))
                        return;
                    __VLS_ctx.onHandleClick($event, row);
                } },
            ...{ class: "drag-handle" },
            title: (__VLS_ctx.t('documents.recordsEditor.dragHandle')),
        });
        for (const [field] of __VLS_getVForSourceType((__VLS_ctx.schema))) {
            (field);
            if (__VLS_ctx.editingRow === row && __VLS_ctx.editingField === field) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
                    ...{ onBlur: (__VLS_ctx.commitEdit) },
                    ...{ onKeydown: (...[$event]) => {
                            if (!(__VLS_ctx.localItems.length > 0))
                                return;
                            if (!(__VLS_ctx.editingRow === row && __VLS_ctx.editingField === field))
                                return;
                            __VLS_ctx.onEditKeydown($event, row, field);
                        } },
                    ref: ((el) => __VLS_ctx.registerInput(row, field, el)),
                    value: (__VLS_ctx.editBuffer),
                    type: "text",
                    ...{ class: "cell-input" },
                });
            }
            else {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                    ...{ onClick: (...[$event]) => {
                            if (!(__VLS_ctx.localItems.length > 0))
                                return;
                            if (!!(__VLS_ctx.editingRow === row && __VLS_ctx.editingField === field))
                                return;
                            __VLS_ctx.startEdit(row, field);
                        } },
                    type: "button",
                    ...{ class: "cell" },
                    title: (__VLS_ctx.t('documents.recordsEditor.clickToEdit')),
                });
                if (item.values[field]) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "cell-content" },
                    });
                    (item.values[field]);
                }
                else {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "cell-empty" },
                    });
                    (__VLS_ctx.t('documents.recordsEditor.emptyCell'));
                }
            }
        }
        if (!__VLS_ctx.schemaMode) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!(__VLS_ctx.localItems.length > 0))
                            return;
                        if (!(!__VLS_ctx.schemaMode))
                            return;
                        __VLS_ctx.deleteRow(row);
                    } },
                type: "button",
                ...{ class: "row-delete" },
                title: (__VLS_ctx.t('documents.recordsEditor.deleteItem')),
            });
        }
        else {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
                ...{ class: "row-delete-spacer" },
                'aria-hidden': "true",
            });
        }
    }
    var __VLS_27;
}
if (__VLS_ctx.hasOverflow) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "overflow-note" },
    });
    (__VLS_ctx.t('documents.recordsEditor.overflowHint'));
}
if (!__VLS_ctx.schemaMode) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "add-row" },
    });
    const __VLS_33 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_34 = __VLS_asFunctionalComponent(__VLS_33, new __VLS_33({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }));
    const __VLS_35 = __VLS_34({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }, ...__VLS_functionalComponentArgsRest(__VLS_34));
    let __VLS_37;
    let __VLS_38;
    let __VLS_39;
    const __VLS_40 = {
        onClick: (__VLS_ctx.addRowAndEdit)
    };
    __VLS_36.slots.default;
    (__VLS_ctx.t('documents.recordsEditor.addRow'));
    var __VLS_36;
}
/** @type {__VLS_StyleScopedClasses['records-edit']} */ ;
/** @type {__VLS_StyleScopedClasses['records-edit--schema']} */ ;
/** @type {__VLS_StyleScopedClasses['records-toolbar']} */ ;
/** @type {__VLS_StyleScopedClasses['schema-error']} */ ;
/** @type {__VLS_StyleScopedClasses['bulk-bar']} */ ;
/** @type {__VLS_StyleScopedClasses['bulk-count']} */ ;
/** @type {__VLS_StyleScopedClasses['grow']} */ ;
/** @type {__VLS_StyleScopedClasses['header-row']} */ ;
/** @type {__VLS_StyleScopedClasses['header-cell']} */ ;
/** @type {__VLS_StyleScopedClasses['handle-cell']} */ ;
/** @type {__VLS_StyleScopedClasses['header-cell']} */ ;
/** @type {__VLS_StyleScopedClasses['header-cell']} */ ;
/** @type {__VLS_StyleScopedClasses['action-cell']} */ ;
/** @type {__VLS_StyleScopedClasses['header-cell']} */ ;
/** @type {__VLS_StyleScopedClasses['schema-edit']} */ ;
/** @type {__VLS_StyleScopedClasses['schema-controls']} */ ;
/** @type {__VLS_StyleScopedClasses['schema-arrow']} */ ;
/** @type {__VLS_StyleScopedClasses['schema-arrow']} */ ;
/** @type {__VLS_StyleScopedClasses['schema-remove']} */ ;
/** @type {__VLS_StyleScopedClasses['schema-name-input']} */ ;
/** @type {__VLS_StyleScopedClasses['header-cell']} */ ;
/** @type {__VLS_StyleScopedClasses['action-cell']} */ ;
/** @type {__VLS_StyleScopedClasses['schema-add']} */ ;
/** @type {__VLS_StyleScopedClasses['rows']} */ ;
/** @type {__VLS_StyleScopedClasses['row']} */ ;
/** @type {__VLS_StyleScopedClasses['row--selected']} */ ;
/** @type {__VLS_StyleScopedClasses['row--frozen']} */ ;
/** @type {__VLS_StyleScopedClasses['drag-handle']} */ ;
/** @type {__VLS_StyleScopedClasses['cell-input']} */ ;
/** @type {__VLS_StyleScopedClasses['cell']} */ ;
/** @type {__VLS_StyleScopedClasses['cell-content']} */ ;
/** @type {__VLS_StyleScopedClasses['cell-empty']} */ ;
/** @type {__VLS_StyleScopedClasses['row-delete']} */ ;
/** @type {__VLS_StyleScopedClasses['row-delete-spacer']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-note']} */ ;
/** @type {__VLS_StyleScopedClasses['add-row']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VueDraggable: VueDraggable,
            VButton: VButton,
            t: t,
            localItems: localItems,
            editingRow: editingRow,
            editingField: editingField,
            editBuffer: editBuffer,
            schemaMode: schemaMode,
            schemaError: schemaError,
            schema: schema,
            clearSelection: clearSelection,
            startEdit: startEdit,
            commitEdit: commitEdit,
            onEditKeydown: onEditKeydown,
            addRowAndEdit: addRowAndEdit,
            deleteRow: deleteRow,
            deleteSelected: deleteSelected,
            toggleSchemaMode: toggleSchemaMode,
            addColumn: addColumn,
            deleteColumn: deleteColumn,
            renameColumn: renameColumn,
            moveColumn: moveColumn,
            onHandleClick: onHandleClick,
            isRowSelected: isRowSelected,
            onDragStart: onDragStart,
            onDragEnd: onDragEnd,
            gridStyle: gridStyle,
            selectionCount: selectionCount,
            registerInput: registerInput,
            hasOverflow: hasOverflow,
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
//# sourceMappingURL=RecordsView.vue.js.map