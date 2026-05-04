import { nextTick, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VueDraggable } from 'vue-draggable-plus';
import { VButton } from '@/components';
const props = defineProps();
const emit = defineEmits();
const { t } = useI18n();
/** Current edit-target row index. {@code null} = no edit in flight. */
const editingIndex = ref(null);
/** Buffered text of the edit-input — committed on blur / Enter,
 *  discarded on Esc. */
const editBuffer = ref('');
/** Refs to the input elements, indexed by row. Lets us focus the
 *  next/previous item after a keyboard-driven mutation. */
const inputRefs = ref([]);
/**
 * Multi-select state. Indexed by row, mutated through Cmd/Ctrl-click
 * (toggle a single row) or Shift-click (range from {@link selectionAnchor}
 * to the clicked row). Cleared by every structural mutation (add /
 * delete / drag) so dangling indices can't outlive the row they
 * pointed at.
 */
const selectedIndices = ref(new Set());
/** Most recently single-clicked row — anchor for Shift-click ranges. */
const selectionAnchor = ref(null);
function clearSelection() {
    selectedIndices.value = new Set();
    selectionAnchor.value = null;
}
function selectionCount() {
    return selectedIndices.value.size;
}
/**
 * Local mutable copy of the item list — required by `vue-draggable-plus`,
 * which mutates its `v-model` array in place during a drag. CRUD
 * operations also work on this ref; every mutation calls {@link emitDoc}
 * so the parent receives a fresh ListDocument and re-serialises into the
 * raw body. External updates (e.g. user typed in the Raw tab) flow back
 * via the {@link watch} below.
 */
const localItems = ref(cloneItems(props.doc.items));
watch(() => props.doc.items, (next) => {
    // Always sync local from props — the codec is idempotent on a
    // round-trip, so mirroring our own emit's parsed-and-reserialised
    // result back into localItems is a no-op effect, not a loop.
    localItems.value = cloneItems(next);
}, { deep: true });
function cloneItems(src) {
    return src.map((it) => ({ text: it.text, extra: { ...it.extra } }));
}
function emitDoc() {
    emit('update:doc', {
        kind: props.doc.kind || 'list',
        items: localItems.value,
        extra: props.doc.extra,
    });
}
// ─── Click → edit ───────────────────────────────────────────────────
function startEdit(idx) {
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
function cancelEdit() {
    editingIndex.value = null;
    editBuffer.value = '';
}
function commitEdit() {
    if (editingIndex.value == null)
        return;
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
function addItem() {
    insertItemAt(localItems.value.length);
}
function insertItemAt(idx) {
    localItems.value.splice(idx, 0, { text: '', extra: {} });
    clearSelection();
    emitDoc();
    // Open the freshly added row for editing once Vue rerenders.
    void nextTick(() => startEdit(idx));
}
// ─── Delete ─────────────────────────────────────────────────────────
function deleteItem(idx) {
    localItems.value.splice(idx, 1);
    // Cancel any in-flight edit — the indices have shifted.
    editingIndex.value = null;
    editBuffer.value = '';
    clearSelection();
    emitDoc();
}
/**
 * Bulk-delete every row in the current selection. Walks the indices
 * in descending order so each splice doesn't shift the rest.
 */
function deleteSelected() {
    if (selectedIndices.value.size === 0)
        return;
    const sorted = [...selectedIndices.value].sort((a, b) => b - a);
    for (const idx of sorted) {
        localItems.value.splice(idx, 1);
    }
    editingIndex.value = null;
    editBuffer.value = '';
    clearSelection();
    emitDoc();
}
// ─── Selection ──────────────────────────────────────────────────────
//
// Default click on an item starts the inline editor; Cmd/Ctrl-click
// toggles the row in/out of the multi-select; Shift-click selects the
// range from the previous anchor to the clicked row. The drag handle
// is a separate target — clicks there belong to vue-draggable, not to
// the editor or the selection.
function onItemClick(event, idx) {
    // Range select — Shift takes precedence.
    if (event.shiftKey && selectionAnchor.value != null) {
        const start = Math.min(selectionAnchor.value, idx);
        const end = Math.max(selectionAnchor.value, idx);
        const next = new Set(selectedIndices.value);
        for (let i = start; i <= end; i++)
            next.add(i);
        selectedIndices.value = next;
        cancelEdit();
        return;
    }
    // Toggle single — Cmd (Mac) / Ctrl (others).
    if (event.metaKey || event.ctrlKey) {
        const next = new Set(selectedIndices.value);
        if (next.has(idx)) {
            next.delete(idx);
        }
        else {
            next.add(idx);
            selectionAnchor.value = idx;
        }
        selectedIndices.value = next;
        cancelEdit();
        return;
    }
    // Plain click — abandon any selection and switch to edit mode.
    if (selectedIndices.value.size > 0)
        clearSelection();
    selectionAnchor.value = idx;
    startEdit(idx);
}
function isSelected(idx) {
    return selectedIndices.value.has(idx);
}
// ─── Drag-Reorder ───────────────────────────────────────────────────
//
// `vue-draggable-plus` mutates `localItems` in-place during the drag;
// we only need to commit on drop. Cancel any in-flight edit so the
// editing-index doesn't point at a moved row, and drop the selection
// because indices may have shifted underneath us.
function onDragStart() {
    cancelEdit();
    clearSelection();
}
function onDragEnd() {
    emitDoc();
}
// ─── Keyboard handlers in the inline-edit field ─────────────────────
function onEditKeydown(event, idx) {
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
function autoGrow(event) {
    const el = event.target;
    el.style.height = 'auto';
    el.style.height = el.scrollHeight + 'px';
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['row']} */ ;
/** @type {__VLS_StyleScopedClasses['drag-handle']} */ ;
/** @type {__VLS_StyleScopedClasses['drag-handle']} */ ;
/** @type {__VLS_StyleScopedClasses['text']} */ ;
/** @type {__VLS_StyleScopedClasses['edit-input']} */ ;
/** @type {__VLS_StyleScopedClasses['row-delete']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "list-edit" },
});
if (__VLS_ctx.selectionCount() > 0) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "bulk-bar" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "bulk-count" },
    });
    (__VLS_ctx.selectionCount() === 1
        ? __VLS_ctx.t('documents.listEditor.selectedCountSingular', { count: __VLS_ctx.selectionCount() })
        : __VLS_ctx.t('documents.listEditor.selectedCountPlural', { count: __VLS_ctx.selectionCount() }));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
        ...{ class: "grow" },
    });
    const __VLS_0 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }));
    const __VLS_2 = __VLS_1({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    let __VLS_4;
    let __VLS_5;
    let __VLS_6;
    const __VLS_7 = {
        onClick: (__VLS_ctx.clearSelection)
    };
    __VLS_3.slots.default;
    (__VLS_ctx.t('documents.listEditor.clearSelection'));
    var __VLS_3;
    const __VLS_8 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
        ...{ 'onClick': {} },
        variant: "danger",
        size: "sm",
    }));
    const __VLS_10 = __VLS_9({
        ...{ 'onClick': {} },
        variant: "danger",
        size: "sm",
    }, ...__VLS_functionalComponentArgsRest(__VLS_9));
    let __VLS_12;
    let __VLS_13;
    let __VLS_14;
    const __VLS_15 = {
        onClick: (__VLS_ctx.deleteSelected)
    };
    __VLS_11.slots.default;
    (__VLS_ctx.t('documents.listEditor.deleteSelected'));
    var __VLS_11;
}
if (__VLS_ctx.localItems.length > 0) {
    const __VLS_16 = {}.VueDraggable;
    /** @type {[typeof __VLS_components.VueDraggable, typeof __VLS_components.VueDraggable, ]} */ ;
    // @ts-ignore
    const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
        ...{ 'onStart': {} },
        ...{ 'onEnd': {} },
        modelValue: (__VLS_ctx.localItems),
        tag: "ul",
        ...{ class: "rows" },
        animation: (150),
        handle: ".drag-handle",
        ghostClass: "row--ghost",
        chosenClass: "row--chosen",
        dragClass: "row--drag",
    }));
    const __VLS_18 = __VLS_17({
        ...{ 'onStart': {} },
        ...{ 'onEnd': {} },
        modelValue: (__VLS_ctx.localItems),
        tag: "ul",
        ...{ class: "rows" },
        animation: (150),
        handle: ".drag-handle",
        ghostClass: "row--ghost",
        chosenClass: "row--chosen",
        dragClass: "row--drag",
    }, ...__VLS_functionalComponentArgsRest(__VLS_17));
    let __VLS_20;
    let __VLS_21;
    let __VLS_22;
    const __VLS_23 = {
        onStart: (__VLS_ctx.onDragStart)
    };
    const __VLS_24 = {
        onEnd: (__VLS_ctx.onDragEnd)
    };
    __VLS_19.slots.default;
    for (const [item, idx] of __VLS_getVForSourceType((__VLS_ctx.localItems))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
            key: (idx),
            ...{ class: "row" },
            ...{ class: ({ 'row--selected': __VLS_ctx.isSelected(idx) }) },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "drag-handle" },
            title: (__VLS_ctx.t('documents.listEditor.dragHandle')),
            'aria-hidden': "true",
        });
        if (__VLS_ctx.editingIndex === idx) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.textarea)({
                ...{ onBlur: (__VLS_ctx.commitEdit) },
                ...{ onKeydown: (...[$event]) => {
                        if (!(__VLS_ctx.localItems.length > 0))
                            return;
                        if (!(__VLS_ctx.editingIndex === idx))
                            return;
                        __VLS_ctx.onEditKeydown($event, idx);
                    } },
                ...{ onInput: (__VLS_ctx.autoGrow) },
                ref: ((el) => { if (el)
                    __VLS_ctx.inputRefs[idx] = el; }),
                value: (__VLS_ctx.editBuffer),
                ...{ class: "edit-input" },
                rows: "1",
            });
        }
        else {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!(__VLS_ctx.localItems.length > 0))
                            return;
                        if (!!(__VLS_ctx.editingIndex === idx))
                            return;
                        __VLS_ctx.onItemClick($event, idx);
                    } },
                type: "button",
                ...{ class: "text" },
                title: (__VLS_ctx.t('documents.listEditor.clickToEdit')),
            });
            if (item.text) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "text-content" },
                });
                (item.text);
            }
            else {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "text-empty" },
                });
                (__VLS_ctx.t('documents.listEditor.emptyItem'));
            }
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!(__VLS_ctx.localItems.length > 0))
                        return;
                    __VLS_ctx.deleteItem(idx);
                } },
            type: "button",
            ...{ class: "row-delete" },
            title: (__VLS_ctx.t('documents.listEditor.deleteItem')),
        });
    }
    var __VLS_19;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "add-row" },
});
const __VLS_25 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_26 = __VLS_asFunctionalComponent(__VLS_25, new __VLS_25({
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
}));
const __VLS_27 = __VLS_26({
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
}, ...__VLS_functionalComponentArgsRest(__VLS_26));
let __VLS_29;
let __VLS_30;
let __VLS_31;
const __VLS_32 = {
    onClick: (__VLS_ctx.addItem)
};
__VLS_28.slots.default;
(__VLS_ctx.t('documents.listEditor.addItem'));
var __VLS_28;
/** @type {__VLS_StyleScopedClasses['list-edit']} */ ;
/** @type {__VLS_StyleScopedClasses['bulk-bar']} */ ;
/** @type {__VLS_StyleScopedClasses['bulk-count']} */ ;
/** @type {__VLS_StyleScopedClasses['grow']} */ ;
/** @type {__VLS_StyleScopedClasses['rows']} */ ;
/** @type {__VLS_StyleScopedClasses['row']} */ ;
/** @type {__VLS_StyleScopedClasses['row--selected']} */ ;
/** @type {__VLS_StyleScopedClasses['drag-handle']} */ ;
/** @type {__VLS_StyleScopedClasses['edit-input']} */ ;
/** @type {__VLS_StyleScopedClasses['text']} */ ;
/** @type {__VLS_StyleScopedClasses['text-content']} */ ;
/** @type {__VLS_StyleScopedClasses['text-empty']} */ ;
/** @type {__VLS_StyleScopedClasses['row-delete']} */ ;
/** @type {__VLS_StyleScopedClasses['add-row']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VueDraggable: VueDraggable,
            VButton: VButton,
            t: t,
            editingIndex: editingIndex,
            editBuffer: editBuffer,
            inputRefs: inputRefs,
            clearSelection: clearSelection,
            selectionCount: selectionCount,
            localItems: localItems,
            commitEdit: commitEdit,
            addItem: addItem,
            deleteItem: deleteItem,
            deleteSelected: deleteSelected,
            onItemClick: onItemClick,
            isSelected: isSelected,
            onDragStart: onDragStart,
            onDragEnd: onDragEnd,
            onEditKeydown: onEditKeydown,
            autoGrow: autoGrow,
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
//# sourceMappingURL=ListView.vue.js.map