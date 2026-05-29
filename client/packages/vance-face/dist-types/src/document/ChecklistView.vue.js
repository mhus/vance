import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VueDraggable } from 'vue-draggable-plus';
import { VButton } from '@/components';
import { parseChecklist, } from './checklistCodec';
const props = withDefaults(defineProps(), {
    mode: 'editor',
    meta: () => ({}),
});
const emit = defineEmits();
const { t } = useI18n();
// ── Edit state ─────────────────────────────────────────────────────
const editingIndex = ref(null);
const editBuffer = ref('');
const inputRefs = ref([]);
// ── Selection state ────────────────────────────────────────────────
const selectedIndices = ref(new Set());
const selectionAnchor = ref(null);
function clearSelection() {
    selectedIndices.value = new Set();
    selectionAnchor.value = null;
}
function selectionCount() {
    return selectedIndices.value.size;
}
// ── Status dropdown state ──────────────────────────────────────────
/** Index of the row whose status dropdown is currently open, or null. */
const statusMenuIndex = ref(null);
function openStatusMenu(idx) {
    statusMenuIndex.value = idx;
}
function closeStatusMenu() {
    statusMenuIndex.value = null;
}
// Document-level click handler dismisses the dropdown when the user
// clicks anywhere outside it. The menu's wrapper stops propagation
// (`@click.stop`), and the trigger toggles via its own handler before
// the document listener fires, so a click on the chevron doesn't
// instantly re-close the menu it just opened.
function onDocumentClick(_event) {
    if (statusMenuIndex.value !== null)
        closeStatusMenu();
}
onMounted(() => document.addEventListener('click', onDocumentClick));
onBeforeUnmount(() => document.removeEventListener('click', onDocumentClick));
const isEditor = computed(() => props.mode === 'editor');
// ── Resolved doc + local mutable copy ──────────────────────────────
const resolvedDoc = computed(() => {
    if (props.mode === 'editor') {
        return props.doc ?? emptyDoc();
    }
    if (props.mode === 'inline') {
        try {
            return parseChecklist(props.content ?? '', 'text/markdown');
        }
        catch (e) {
            console.warn('ChecklistView: failed to parse inline content', e);
            return emptyDoc();
        }
    }
    const d = props.document;
    if (!d || !d.inlineText)
        return emptyDoc();
    try {
        return parseChecklist(d.inlineText, d.mimeType ?? 'text/markdown');
    }
    catch (e) {
        console.warn('ChecklistView: failed to parse embedded document', e);
        return emptyDoc();
    }
});
function emptyDoc() {
    return { kind: 'checklist', items: [], extra: {} };
}
const localItems = ref(cloneItems(resolvedDoc.value.items));
watch(() => resolvedDoc.value.items, (next) => {
    localItems.value = cloneItems(next);
}, { deep: true });
function cloneItems(src) {
    return src.map((it) => ({
        text: it.text,
        status: it.status,
        priority: it.priority,
        extra: { ...it.extra },
    }));
}
function emitDoc() {
    if (!isEditor.value)
        return;
    emit('update:doc', {
        kind: resolvedDoc.value.kind || 'checklist',
        items: localItems.value,
        extra: resolvedDoc.value.extra,
    });
}
// ── Aggregate header (status counts) ───────────────────────────────
/** Display order for the aggregate counts — most actionable first. */
const STATUS_DISPLAY_ORDER = [
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
const statusFilter = ref(null);
const statusCounts = computed(() => {
    const counts = {
        open: 0, done: 0, in_progress: 0, review: 0, blocked: 0,
        needs_info: 0, deferred: 0, delegated: 0, waiting: 0,
    };
    for (const it of localItems.value)
        counts[it.status]++;
    return counts;
});
const visibleAggregate = computed(() => {
    const counts = statusCounts.value;
    return STATUS_DISPLAY_ORDER
        .map((s) => ({ status: s, count: counts[s] }))
        .filter((e) => e.count > 0);
});
function toggleStatusFilter(s) {
    statusFilter.value = statusFilter.value === s ? null : s;
}
/** Whether the row at `idx` is currently visible under the active filter. */
function isVisible(idx) {
    if (statusFilter.value == null)
        return true;
    return localItems.value[idx]?.status === statusFilter.value;
}
// ── Edit lifecycle ─────────────────────────────────────────────────
function startEdit(idx) {
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
function cancelEdit() {
    editingIndex.value = null;
    editBuffer.value = '';
}
function commitEdit() {
    if (editingIndex.value == null)
        return;
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
function addItem() {
    insertItemAt(localItems.value.length);
}
function insertItemAt(idx) {
    localItems.value.splice(idx, 0, {
        text: '',
        status: 'open',
        extra: {},
    });
    clearSelection();
    emitDoc();
    void nextTick(() => startEdit(idx));
}
function deleteItem(idx) {
    localItems.value.splice(idx, 1);
    editingIndex.value = null;
    editBuffer.value = '';
    clearSelection();
    emitDoc();
}
function deleteSelected() {
    if (selectedIndices.value.size === 0)
        return;
    const sorted = [...selectedIndices.value].sort((a, b) => b - a);
    for (const idx of sorted)
        localItems.value.splice(idx, 1);
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
function cycleStatus(idx) {
    const item = localItems.value[idx];
    if (!item)
        return;
    let next;
    switch (item.status) {
        case 'open':
            next = 'in_progress';
            break;
        case 'in_progress':
            next = 'done';
            break;
        case 'done':
            next = 'open';
            break;
        default:
            next = 'open';
            break;
    }
    localItems.value[idx] = { ...item, status: next };
    emitDoc();
}
function setStatus(idx, status) {
    const item = localItems.value[idx];
    if (!item)
        return;
    localItems.value[idx] = { ...item, status };
    closeStatusMenu();
    emitDoc();
}
// ── Priority cycle (none → high → low → none) ──────────────────────
function cyclePriority(idx) {
    const item = localItems.value[idx];
    if (!item)
        return;
    let next;
    if (item.priority === undefined)
        next = 'high';
    else if (item.priority === 'high')
        next = 'low';
    else
        next = undefined;
    localItems.value[idx] = { ...item, priority: next };
    emitDoc();
}
// ── Multi-select on text click ─────────────────────────────────────
function onItemClick(event, idx) {
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
    if (selectedIndices.value.size > 0)
        clearSelection();
    selectionAnchor.value = idx;
    startEdit(idx);
}
function isSelected(idx) {
    return selectedIndices.value.has(idx);
}
// ── Drag reorder ───────────────────────────────────────────────────
function onDragStart() {
    cancelEdit();
    clearSelection();
    closeStatusMenu();
}
function onDragEnd() {
    emitDoc();
}
// ── Keyboard handlers in the inline-edit field ────────────────────
function onEditKeydown(event, idx) {
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
function autoGrow(event) {
    const el = event.target;
    el.style.height = 'auto';
    el.style.height = el.scrollHeight + 'px';
}
// ── Status visual helpers ──────────────────────────────────────────
/** Single-char glyph rendered inside the status box. Uses the same
 *  markdown char as the codec — keeps the editor visually consistent
 *  with the on-disk source. */
function statusGlyph(s) {
    const map = {
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
function statusColorClass(s) {
    return `status--${s}`;
}
const ALL_STATUSES = [
    'open', 'done', 'in_progress', 'review', 'blocked',
    'needs_info', 'deferred', 'delegated', 'waiting',
];
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    mode: 'editor',
    meta: () => ({}),
});
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['aggregate-pill']} */ ;
/** @type {__VLS_StyleScopedClasses['aggregate-clear']} */ ;
/** @type {__VLS_StyleScopedClasses['row']} */ ;
/** @type {__VLS_StyleScopedClasses['drag-handle']} */ ;
/** @type {__VLS_StyleScopedClasses['drag-handle']} */ ;
/** @type {__VLS_StyleScopedClasses['status-box']} */ ;
/** @type {__VLS_StyleScopedClasses['status-dropdown-trigger']} */ ;
/** @type {__VLS_StyleScopedClasses['status-menu__option']} */ ;
/** @type {__VLS_StyleScopedClasses['text']} */ ;
/** @type {__VLS_StyleScopedClasses['edit-input']} */ ;
/** @type {__VLS_StyleScopedClasses['prio-toggle']} */ ;
/** @type {__VLS_StyleScopedClasses['row-delete']} */ ;
// CSS variable injection 
// CSS variable injection end 
if (!__VLS_ctx.isEditor) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
        ...{ class: (['checklist-read', `checklist-read--${__VLS_ctx.mode}`]) },
    });
    for (const [item, idx] of __VLS_getVForSourceType((__VLS_ctx.localItems))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
            key: (idx),
            ...{ class: "checklist-read__item" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "checklist-read__box" },
            ...{ class: (__VLS_ctx.statusColorClass(item.status)) },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "checklist-read__glyph" },
        });
        (__VLS_ctx.statusGlyph(item.status));
        if (item.text) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "checklist-read__text" },
            });
            (item.text);
        }
        else {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "checklist-read__empty" },
            });
        }
        if (item.priority) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: (['prio-badge', `prio-badge--${item.priority}`]) },
            });
            (item.priority === 'high' ? '↑' : '↓');
        }
    }
    if (__VLS_ctx.localItems.length === 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
            ...{ class: "checklist-read__empty-row" },
        });
        (__VLS_ctx.t('documents.checklistEditor.empty'));
    }
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "checklist-edit" },
    });
    if (__VLS_ctx.localItems.length > 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "aggregate-bar" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "aggregate-total" },
        });
        (__VLS_ctx.t('documents.checklistEditor.totalItems', { count: __VLS_ctx.localItems.length }));
        for (const [entry] of __VLS_getVForSourceType((__VLS_ctx.visibleAggregate))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.isEditor))
                            return;
                        if (!(__VLS_ctx.localItems.length > 0))
                            return;
                        __VLS_ctx.toggleStatusFilter(entry.status);
                    } },
                key: (entry.status),
                type: "button",
                ...{ class: ([
                        'aggregate-pill',
                        __VLS_ctx.statusColorClass(entry.status),
                        { 'aggregate-pill--active': __VLS_ctx.statusFilter === entry.status },
                    ]) },
                title: (__VLS_ctx.t(`documents.checklistEditor.status.${entry.status}`)),
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "aggregate-glyph" },
            });
            (__VLS_ctx.statusGlyph(entry.status));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "aggregate-count" },
            });
            (entry.count);
        }
        if (__VLS_ctx.statusFilter) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "aggregate-filter-hint" },
            });
            (__VLS_ctx.t('documents.checklistEditor.filterActive'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.isEditor))
                            return;
                        if (!(__VLS_ctx.localItems.length > 0))
                            return;
                        if (!(__VLS_ctx.statusFilter))
                            return;
                        __VLS_ctx.statusFilter = null;
                    } },
                type: "button",
                ...{ class: "aggregate-clear" },
            });
        }
    }
    if (__VLS_ctx.selectionCount() > 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "bulk-bar" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "bulk-count" },
        });
        (__VLS_ctx.selectionCount() === 1
            ? __VLS_ctx.t('documents.checklistEditor.selectedCountSingular', { count: __VLS_ctx.selectionCount() })
            : __VLS_ctx.t('documents.checklistEditor.selectedCountPlural', { count: __VLS_ctx.selectionCount() }));
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
        (__VLS_ctx.t('documents.checklistEditor.clearSelection'));
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
        (__VLS_ctx.t('documents.checklistEditor.deleteSelected'));
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
            __VLS_asFunctionalDirective(__VLS_directives.vShow)(null, { ...__VLS_directiveBindingRestFields, value: (__VLS_ctx.isVisible(idx)) }, null, null);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "drag-handle" },
                title: (__VLS_ctx.t('documents.checklistEditor.dragHandle')),
                'aria-hidden': "true",
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "status-cell" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.isEditor))
                            return;
                        if (!(__VLS_ctx.localItems.length > 0))
                            return;
                        __VLS_ctx.cycleStatus(idx);
                    } },
                ...{ onContextmenu: (...[$event]) => {
                        if (!!(!__VLS_ctx.isEditor))
                            return;
                        if (!(__VLS_ctx.localItems.length > 0))
                            return;
                        __VLS_ctx.openStatusMenu(idx);
                    } },
                type: "button",
                ...{ class: "status-box" },
                ...{ class: (__VLS_ctx.statusColorClass(item.status)) },
                title: (__VLS_ctx.t(`documents.checklistEditor.status.${item.status}`)),
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "status-glyph" },
            });
            (__VLS_ctx.statusGlyph(item.status));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.isEditor))
                            return;
                        if (!(__VLS_ctx.localItems.length > 0))
                            return;
                        __VLS_ctx.statusMenuIndex === idx ? __VLS_ctx.closeStatusMenu() : __VLS_ctx.openStatusMenu(idx);
                    } },
                type: "button",
                ...{ class: "status-dropdown-trigger" },
                title: (__VLS_ctx.t('documents.checklistEditor.openStatusMenu')),
            });
            if (__VLS_ctx.statusMenuIndex === idx) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ onClick: () => { } },
                    ...{ class: "status-menu" },
                });
                for (const [s] of __VLS_getVForSourceType((__VLS_ctx.ALL_STATUSES))) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                        ...{ onClick: (...[$event]) => {
                                if (!!(!__VLS_ctx.isEditor))
                                    return;
                                if (!(__VLS_ctx.localItems.length > 0))
                                    return;
                                if (!(__VLS_ctx.statusMenuIndex === idx))
                                    return;
                                __VLS_ctx.setStatus(idx, s);
                            } },
                        key: (s),
                        type: "button",
                        ...{ class: "status-menu__option" },
                        ...{ class: ([__VLS_ctx.statusColorClass(s), { 'status-menu__option--active': item.status === s }]) },
                    });
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "status-menu__glyph" },
                    });
                    (__VLS_ctx.statusGlyph(s));
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "status-menu__label" },
                    });
                    (__VLS_ctx.t(`documents.checklistEditor.status.${s}`));
                }
            }
            if (__VLS_ctx.editingIndex === idx) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.textarea)({
                    ...{ onBlur: (__VLS_ctx.commitEdit) },
                    ...{ onKeydown: (...[$event]) => {
                            if (!!(!__VLS_ctx.isEditor))
                                return;
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
                            if (!!(!__VLS_ctx.isEditor))
                                return;
                            if (!(__VLS_ctx.localItems.length > 0))
                                return;
                            if (!!(__VLS_ctx.editingIndex === idx))
                                return;
                            __VLS_ctx.onItemClick($event, idx);
                        } },
                    type: "button",
                    ...{ class: "text" },
                    title: (__VLS_ctx.t('documents.checklistEditor.clickToEdit')),
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
                    (__VLS_ctx.t('documents.checklistEditor.emptyItem'));
                }
            }
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.isEditor))
                            return;
                        if (!(__VLS_ctx.localItems.length > 0))
                            return;
                        __VLS_ctx.cyclePriority(idx);
                    } },
                type: "button",
                ...{ class: ([
                        'prio-toggle',
                        item.priority ? `prio-toggle--${item.priority}` : 'prio-toggle--none',
                    ]) },
                title: (__VLS_ctx.t('documents.checklistEditor.togglePriority')),
            });
            if (item.priority === 'high') {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            }
            else if (item.priority === 'low') {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            }
            else {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "prio-toggle__placeholder" },
                });
            }
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.isEditor))
                            return;
                        if (!(__VLS_ctx.localItems.length > 0))
                            return;
                        __VLS_ctx.deleteItem(idx);
                    } },
                type: "button",
                ...{ class: "row-delete" },
                title: (__VLS_ctx.t('documents.checklistEditor.deleteItem')),
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
    (__VLS_ctx.t('documents.checklistEditor.addItem'));
    var __VLS_28;
}
/** @type {__VLS_StyleScopedClasses['checklist-read']} */ ;
/** @type {__VLS_StyleScopedClasses['checklist-read__item']} */ ;
/** @type {__VLS_StyleScopedClasses['checklist-read__box']} */ ;
/** @type {__VLS_StyleScopedClasses['checklist-read__glyph']} */ ;
/** @type {__VLS_StyleScopedClasses['checklist-read__text']} */ ;
/** @type {__VLS_StyleScopedClasses['checklist-read__empty']} */ ;
/** @type {__VLS_StyleScopedClasses['prio-badge']} */ ;
/** @type {__VLS_StyleScopedClasses['checklist-read__empty-row']} */ ;
/** @type {__VLS_StyleScopedClasses['checklist-edit']} */ ;
/** @type {__VLS_StyleScopedClasses['aggregate-bar']} */ ;
/** @type {__VLS_StyleScopedClasses['aggregate-total']} */ ;
/** @type {__VLS_StyleScopedClasses['aggregate-pill']} */ ;
/** @type {__VLS_StyleScopedClasses['aggregate-pill--active']} */ ;
/** @type {__VLS_StyleScopedClasses['aggregate-glyph']} */ ;
/** @type {__VLS_StyleScopedClasses['aggregate-count']} */ ;
/** @type {__VLS_StyleScopedClasses['aggregate-filter-hint']} */ ;
/** @type {__VLS_StyleScopedClasses['aggregate-clear']} */ ;
/** @type {__VLS_StyleScopedClasses['bulk-bar']} */ ;
/** @type {__VLS_StyleScopedClasses['bulk-count']} */ ;
/** @type {__VLS_StyleScopedClasses['grow']} */ ;
/** @type {__VLS_StyleScopedClasses['rows']} */ ;
/** @type {__VLS_StyleScopedClasses['row']} */ ;
/** @type {__VLS_StyleScopedClasses['row--selected']} */ ;
/** @type {__VLS_StyleScopedClasses['drag-handle']} */ ;
/** @type {__VLS_StyleScopedClasses['status-cell']} */ ;
/** @type {__VLS_StyleScopedClasses['status-box']} */ ;
/** @type {__VLS_StyleScopedClasses['status-glyph']} */ ;
/** @type {__VLS_StyleScopedClasses['status-dropdown-trigger']} */ ;
/** @type {__VLS_StyleScopedClasses['status-menu']} */ ;
/** @type {__VLS_StyleScopedClasses['status-menu__option']} */ ;
/** @type {__VLS_StyleScopedClasses['status-menu__option--active']} */ ;
/** @type {__VLS_StyleScopedClasses['status-menu__glyph']} */ ;
/** @type {__VLS_StyleScopedClasses['status-menu__label']} */ ;
/** @type {__VLS_StyleScopedClasses['edit-input']} */ ;
/** @type {__VLS_StyleScopedClasses['text']} */ ;
/** @type {__VLS_StyleScopedClasses['text-content']} */ ;
/** @type {__VLS_StyleScopedClasses['text-empty']} */ ;
/** @type {__VLS_StyleScopedClasses['prio-toggle']} */ ;
/** @type {__VLS_StyleScopedClasses['prio-toggle__placeholder']} */ ;
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
            statusMenuIndex: statusMenuIndex,
            openStatusMenu: openStatusMenu,
            closeStatusMenu: closeStatusMenu,
            isEditor: isEditor,
            localItems: localItems,
            statusFilter: statusFilter,
            visibleAggregate: visibleAggregate,
            toggleStatusFilter: toggleStatusFilter,
            isVisible: isVisible,
            commitEdit: commitEdit,
            addItem: addItem,
            deleteItem: deleteItem,
            deleteSelected: deleteSelected,
            cycleStatus: cycleStatus,
            setStatus: setStatus,
            cyclePriority: cyclePriority,
            onItemClick: onItemClick,
            isSelected: isSelected,
            onDragStart: onDragStart,
            onDragEnd: onDragEnd,
            onEditKeydown: onEditKeydown,
            autoGrow: autoGrow,
            statusGlyph: statusGlyph,
            statusColorClass: statusColorClass,
            ALL_STATUSES: ALL_STATUSES,
        };
    },
    __typeEmits: {},
    __typeProps: {},
    props: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeEmits: {},
    __typeProps: {},
    props: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=ChecklistView.vue.js.map