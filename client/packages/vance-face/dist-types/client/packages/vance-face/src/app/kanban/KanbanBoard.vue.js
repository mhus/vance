import { computed, onMounted, ref } from 'vue';
import { VueDraggable } from 'vue-draggable-plus';
import { VAlert, VButton, VEmptyState, VInput, VModal, VSelect, } from '@/components';
import KanbanCardDetail from './KanbanCardDetail.vue';
import { createKanbanCard, deleteKanbanCard, getKanbanBoard, moveKanbanCard, rebuildKanbanBoard, updateKanbanCard, } from '@vance/shared';
const props = defineProps();
const board = ref(null);
const loading = ref(true);
const error = ref(null);
const warnings = ref([]);
const selectedCardPath = ref(null);
const showCreateModal = ref(false);
const newCardForm = ref({
    title: '',
    column: '',
    labels: [],
    blocked: false,
});
const selectedCard = computed(() => board.value?.cards.find((c) => c.path === selectedCardPath.value) ?? null);
const cardsByColumn = computed(() => {
    const out = {};
    if (!board.value)
        return out;
    for (const col of board.value.columns)
        out[col.name] = [];
    for (const card of board.value.cards) {
        if (!out[card.column])
            out[card.column] = [];
        out[card.column].push(card);
    }
    // Sort each column: priority desc → dueDate asc → title asc
    for (const col of Object.keys(out)) {
        out[col].sort(compareCards);
    }
    return out;
});
function compareCards(a, b) {
    const pa = priorityWeight(a.priority);
    const pb = priorityWeight(b.priority);
    if (pa !== pb)
        return pb - pa;
    const da = a.dueDate ?? '9999-99-99';
    const db = b.dueDate ?? '9999-99-99';
    if (da !== db)
        return da.localeCompare(db);
    return (a.title ?? '').localeCompare(b.title ?? '');
}
function priorityWeight(priority) {
    switch ((priority ?? '').toLowerCase()) {
        case 'critical': return 4;
        case 'high': return 3;
        case 'med':
        case 'medium':
        case 'normal': return 2;
        case 'low': return 0;
        default: return 1;
    }
}
async function load() {
    loading.value = true;
    error.value = null;
    try {
        board.value = await getKanbanBoard(props.projectId, props.folder);
    }
    catch (e) {
        error.value = `Could not load board: ${e.message}`;
    }
    finally {
        loading.value = false;
    }
}
async function refresh() {
    warnings.value = [];
    try {
        await rebuildKanbanBoard(props.projectId, props.folder);
        await load();
    }
    catch (e) {
        error.value = `Rebuild failed: ${e.message}`;
    }
}
// Drag callback: vue-draggable-plus mutates the array model directly, so by
// the time `onEnd` fires the card is already in the new column slot. We just
// need to persist the move on the server.
async function onCardDropped(toColumn, card) {
    if (card.column === toColumn)
        return;
    const previousColumn = card.column;
    // Optimistic UI: update the card's column locally.
    card.column = toColumn;
    try {
        const response = await moveKanbanCard(props.projectId, props.folder, {
            card: card.path,
            toColumn,
        });
        card.path = response.card;
        warnings.value = response.warnings ?? [];
    }
    catch (e) {
        // Rollback on error.
        card.column = previousColumn;
        error.value = `Move failed: ${e.message}`;
    }
}
async function onCardUpdate(path, patch) {
    try {
        const updated = await updateKanbanCard(props.projectId, props.folder, path, patch);
        if (!board.value)
            return;
        const idx = board.value.cards.findIndex((c) => c.path === path);
        if (idx >= 0)
            board.value.cards[idx] = updated;
        selectedCardPath.value = updated.path;
    }
    catch (e) {
        error.value = `Update failed: ${e.message}`;
    }
}
async function onCardDelete(path) {
    try {
        await deleteKanbanCard(props.projectId, props.folder, path);
        if (board.value) {
            board.value.cards = board.value.cards.filter((c) => c.path !== path);
        }
        selectedCardPath.value = null;
    }
    catch (e) {
        error.value = `Delete failed: ${e.message}`;
    }
}
function openCreateModal(column) {
    newCardForm.value = {
        title: '',
        column,
        labels: [],
        blocked: false,
    };
    showCreateModal.value = true;
}
async function submitCreate() {
    if (!newCardForm.value.title.trim())
        return;
    try {
        const created = await createKanbanCard(props.projectId, props.folder, newCardForm.value);
        if (board.value)
            board.value.cards.push(created);
        showCreateModal.value = false;
        selectedCardPath.value = created.path;
    }
    catch (e) {
        error.value = `Create failed: ${e.message}`;
    }
}
function priorityClass(priority) {
    switch ((priority ?? '').toLowerCase()) {
        case 'critical': return 'border-l-4 border-error';
        case 'high': return 'border-l-4 border-warning';
        case 'med':
        case 'medium': return 'border-l-2 border-info';
        case 'low': return 'border-l-2 border-base-300';
        default: return 'border-l-2 border-base-300';
    }
}
onMounted(load);
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col h-full" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex items-center justify-between p-4 border-b border-base-300" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
__VLS_asFunctionalElement(__VLS_intrinsicElements.h1, __VLS_intrinsicElements.h1)({
    ...{ class: "text-xl font-semibold" },
});
(__VLS_ctx.title ?? __VLS_ctx.folder);
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "text-sm text-base-content/60 mt-0.5" },
});
(__VLS_ctx.folder);
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex gap-2 items-center" },
});
if (__VLS_ctx.board) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-sm text-base-content/60" },
    });
    (__VLS_ctx.board.cards.length);
    (__VLS_ctx.board.columns.length);
}
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
    onClick: (__VLS_ctx.load)
};
__VLS_3.slots.default;
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
    onClick: (__VLS_ctx.refresh)
};
__VLS_11.slots.default;
var __VLS_11;
if (__VLS_ctx.error) {
    const __VLS_16 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
        variant: "error",
        ...{ class: "m-4" },
    }));
    const __VLS_18 = __VLS_17({
        variant: "error",
        ...{ class: "m-4" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_17));
    __VLS_19.slots.default;
    (__VLS_ctx.error);
    var __VLS_19;
}
if (__VLS_ctx.warnings.length > 0) {
    const __VLS_20 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_21 = __VLS_asFunctionalComponent(__VLS_20, new __VLS_20({
        variant: "warning",
        ...{ class: "m-4" },
    }));
    const __VLS_22 = __VLS_21({
        variant: "warning",
        ...{ class: "m-4" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_21));
    __VLS_23.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
        ...{ class: "list-disc pl-4" },
    });
    for (const [w] of __VLS_getVForSourceType((__VLS_ctx.warnings))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
            key: (w),
        });
        (w);
    }
    var __VLS_23;
}
if (__VLS_ctx.loading) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "p-8 text-base-content/70" },
    });
}
else if (__VLS_ctx.board && __VLS_ctx.board.columns.length === 0) {
    const __VLS_24 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_25 = __VLS_asFunctionalComponent(__VLS_24, new __VLS_24({
        ...{ class: "m-4" },
        headline: "No columns yet",
        body: "Add columns to _app.yaml to start using this board.",
    }));
    const __VLS_26 = __VLS_25({
        ...{ class: "m-4" },
        headline: "No columns yet",
        body: "Add columns to _app.yaml to start using this board.",
    }, ...__VLS_functionalComponentArgsRest(__VLS_25));
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 flex overflow-hidden" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 flex overflow-x-auto p-4 gap-3" },
    });
    for (const [col] of __VLS_getVForSourceType((__VLS_ctx.board?.columns ?? []))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            key: (col.name),
            ...{ class: "flex flex-col w-72 flex-shrink-0 bg-base-200/40 rounded-lg" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center justify-between px-3 py-2 border-b border-base-300" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center gap-2" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "font-medium" },
        });
        (col.title ?? col.name);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-xs text-base-content/60" },
            ...{ class: ({ 'text-error font-semibold': col.wipExceeded }) },
        });
        (col.cardCount);
        if (col.wipLimit) {
            (col.wipLimit);
        }
        if (!col.declared) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-xs text-warning" },
            });
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(__VLS_ctx.loading))
                        return;
                    if (!!(__VLS_ctx.board && __VLS_ctx.board.columns.length === 0))
                        return;
                    __VLS_ctx.openCreateModal(col.name);
                } },
            ...{ class: "text-base-content/60 hover:text-base-content text-lg leading-none" },
            title: "Add card",
        });
        const __VLS_28 = {}.VueDraggable;
        /** @type {[typeof __VLS_components.VueDraggable, typeof __VLS_components.VueDraggable, ]} */ ;
        // @ts-ignore
        const __VLS_29 = __VLS_asFunctionalComponent(__VLS_28, new __VLS_28({
            ...{ 'onAdd': {} },
            modelValue: (__VLS_ctx.cardsByColumn[col.name]),
            group: "kanban-cards",
            animation: (150),
            itemKey: "path",
            ...{ class: "flex-1 flex flex-col gap-2 p-2 min-h-[80px] overflow-y-auto" },
        }));
        const __VLS_30 = __VLS_29({
            ...{ 'onAdd': {} },
            modelValue: (__VLS_ctx.cardsByColumn[col.name]),
            group: "kanban-cards",
            animation: (150),
            itemKey: "path",
            ...{ class: "flex-1 flex flex-col gap-2 p-2 min-h-[80px] overflow-y-auto" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_29));
        let __VLS_32;
        let __VLS_33;
        let __VLS_34;
        const __VLS_35 = {
            onAdd: ((e) => {
                const card = __VLS_ctx.cardsByColumn[col.name][e.newIndex];
                if (card)
                    __VLS_ctx.onCardDropped(col.name, card);
            })
        };
        __VLS_31.slots.default;
        for (const [card] of __VLS_getVForSourceType((__VLS_ctx.cardsByColumn[col.name]))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ onClick: (...[$event]) => {
                        if (!!(__VLS_ctx.loading))
                            return;
                        if (!!(__VLS_ctx.board && __VLS_ctx.board.columns.length === 0))
                            return;
                        __VLS_ctx.selectedCardPath = card.path;
                    } },
                key: (card.path),
                ...{ class: "bg-base-100 rounded p-2 cursor-grab active:cursor-grabbing shadow-sm hover:shadow-md transition-shadow" },
                ...{ class: (__VLS_ctx.priorityClass(card.priority)) },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "font-medium text-sm" },
            });
            (card.title);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex flex-wrap items-center gap-1 mt-1 text-xs text-base-content/70" },
            });
            if (card.assignee) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "bg-base-200 rounded px-1.5 py-0.5" },
                });
                (card.assignee);
            }
            if (card.priority) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "bg-base-200 rounded px-1.5 py-0.5" },
                });
                (card.priority);
            }
            if (card.dueDate) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "bg-base-200 rounded px-1.5 py-0.5" },
                });
                (card.dueDate);
            }
            if (card.estimate) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "bg-base-200 rounded px-1.5 py-0.5" },
                });
                (card.estimate);
            }
            if (card.blocked) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "bg-error/20 text-error rounded px-1.5 py-0.5" },
                });
            }
            if (card.subtaskTotal > 0) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "bg-base-200 rounded px-1.5 py-0.5" },
                });
                (card.subtaskDone);
                (card.subtaskTotal);
            }
            if (card.labels.length > 0) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "flex flex-wrap gap-1 mt-1" },
                });
                for (const [label] of __VLS_getVForSourceType((card.labels))) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        key: (label),
                        ...{ class: "text-xs bg-info/15 text-info rounded px-1.5 py-0.5" },
                    });
                    (label);
                }
            }
        }
        var __VLS_31;
    }
    if (__VLS_ctx.selectedCard) {
        /** @type {[typeof KanbanCardDetail, ]} */ ;
        // @ts-ignore
        const __VLS_36 = __VLS_asFunctionalComponent(KanbanCardDetail, new KanbanCardDetail({
            ...{ 'onClose': {} },
            ...{ 'onUpdate': {} },
            ...{ 'onDelete': {} },
            card: (__VLS_ctx.selectedCard),
            ...{ class: "w-96 flex-shrink-0 border-l border-base-300 bg-base-100 overflow-y-auto" },
        }));
        const __VLS_37 = __VLS_36({
            ...{ 'onClose': {} },
            ...{ 'onUpdate': {} },
            ...{ 'onDelete': {} },
            card: (__VLS_ctx.selectedCard),
            ...{ class: "w-96 flex-shrink-0 border-l border-base-300 bg-base-100 overflow-y-auto" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_36));
        let __VLS_39;
        let __VLS_40;
        let __VLS_41;
        const __VLS_42 = {
            onClose: (...[$event]) => {
                if (!!(__VLS_ctx.loading))
                    return;
                if (!!(__VLS_ctx.board && __VLS_ctx.board.columns.length === 0))
                    return;
                if (!(__VLS_ctx.selectedCard))
                    return;
                __VLS_ctx.selectedCardPath = null;
            }
        };
        const __VLS_43 = {
            onUpdate: ((patch) => __VLS_ctx.onCardUpdate(__VLS_ctx.selectedCard.path, patch))
        };
        const __VLS_44 = {
            onDelete: (...[$event]) => {
                if (!!(__VLS_ctx.loading))
                    return;
                if (!!(__VLS_ctx.board && __VLS_ctx.board.columns.length === 0))
                    return;
                if (!(__VLS_ctx.selectedCard))
                    return;
                __VLS_ctx.onCardDelete(__VLS_ctx.selectedCard.path);
            }
        };
        var __VLS_38;
    }
}
const __VLS_45 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_46 = __VLS_asFunctionalComponent(__VLS_45, new __VLS_45({
    modelValue: (__VLS_ctx.showCreateModal),
    title: "New card",
}));
const __VLS_47 = __VLS_46({
    modelValue: (__VLS_ctx.showCreateModal),
    title: "New card",
}, ...__VLS_functionalComponentArgsRest(__VLS_46));
__VLS_48.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
const __VLS_49 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_50 = __VLS_asFunctionalComponent(__VLS_49, new __VLS_49({
    modelValue: (__VLS_ctx.newCardForm.title),
    placeholder: "Card title",
}));
const __VLS_51 = __VLS_50({
    modelValue: (__VLS_ctx.newCardForm.title),
    placeholder: "Card title",
}, ...__VLS_functionalComponentArgsRest(__VLS_50));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex gap-2" },
});
const __VLS_53 = {}.VSelect;
/** @type {[typeof __VLS_components.VSelect, ]} */ ;
// @ts-ignore
const __VLS_54 = __VLS_asFunctionalComponent(__VLS_53, new __VLS_53({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.newCardForm.column ?? ''),
    options: (__VLS_ctx.board?.columns.map((c) => ({ value: c.name, label: c.title ?? c.name })) ?? []),
    ...{ class: "flex-1" },
}));
const __VLS_55 = __VLS_54({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.newCardForm.column ?? ''),
    options: (__VLS_ctx.board?.columns.map((c) => ({ value: c.name, label: c.title ?? c.name })) ?? []),
    ...{ class: "flex-1" },
}, ...__VLS_functionalComponentArgsRest(__VLS_54));
let __VLS_57;
let __VLS_58;
let __VLS_59;
const __VLS_60 = {
    'onUpdate:modelValue': ((v) => __VLS_ctx.newCardForm.column = v ?? '')
};
var __VLS_56;
const __VLS_61 = {}.VSelect;
/** @type {[typeof __VLS_components.VSelect, ]} */ ;
// @ts-ignore
const __VLS_62 = __VLS_asFunctionalComponent(__VLS_61, new __VLS_61({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.newCardForm.priority ?? ''),
    options: ([
        { value: '', label: 'No priority' },
        { value: 'low', label: 'Low' },
        { value: 'med', label: 'Medium' },
        { value: 'high', label: 'High' },
        { value: 'critical', label: 'Critical' },
    ]),
    ...{ class: "flex-1" },
}));
const __VLS_63 = __VLS_62({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.newCardForm.priority ?? ''),
    options: ([
        { value: '', label: 'No priority' },
        { value: 'low', label: 'Low' },
        { value: 'med', label: 'Medium' },
        { value: 'high', label: 'High' },
        { value: 'critical', label: 'Critical' },
    ]),
    ...{ class: "flex-1" },
}, ...__VLS_functionalComponentArgsRest(__VLS_62));
let __VLS_65;
let __VLS_66;
let __VLS_67;
const __VLS_68 = {
    'onUpdate:modelValue': ((v) => __VLS_ctx.newCardForm.priority = v ?? '')
};
var __VLS_64;
const __VLS_69 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_70 = __VLS_asFunctionalComponent(__VLS_69, new __VLS_69({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.newCardForm.assignee ?? ''),
    placeholder: "Assignee (optional)",
}));
const __VLS_71 = __VLS_70({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.newCardForm.assignee ?? ''),
    placeholder: "Assignee (optional)",
}, ...__VLS_functionalComponentArgsRest(__VLS_70));
let __VLS_73;
let __VLS_74;
let __VLS_75;
const __VLS_76 = {
    'onUpdate:modelValue': ((v) => __VLS_ctx.newCardForm.assignee = v)
};
var __VLS_72;
const __VLS_77 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_78 = __VLS_asFunctionalComponent(__VLS_77, new __VLS_77({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.newCardForm.dueDate ?? ''),
    placeholder: "Due date YYYY-MM-DD (optional)",
}));
const __VLS_79 = __VLS_78({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.newCardForm.dueDate ?? ''),
    placeholder: "Due date YYYY-MM-DD (optional)",
}, ...__VLS_functionalComponentArgsRest(__VLS_78));
let __VLS_81;
let __VLS_82;
let __VLS_83;
const __VLS_84 = {
    'onUpdate:modelValue': ((v) => __VLS_ctx.newCardForm.dueDate = v)
};
var __VLS_80;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex justify-end gap-2 pt-2" },
});
const __VLS_85 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_86 = __VLS_asFunctionalComponent(__VLS_85, new __VLS_85({
    ...{ 'onClick': {} },
    variant: "ghost",
}));
const __VLS_87 = __VLS_86({
    ...{ 'onClick': {} },
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_86));
let __VLS_89;
let __VLS_90;
let __VLS_91;
const __VLS_92 = {
    onClick: (...[$event]) => {
        __VLS_ctx.showCreateModal = false;
    }
};
__VLS_88.slots.default;
var __VLS_88;
const __VLS_93 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_94 = __VLS_asFunctionalComponent(__VLS_93, new __VLS_93({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.newCardForm.title.trim()),
}));
const __VLS_95 = __VLS_94({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.newCardForm.title.trim()),
}, ...__VLS_functionalComponentArgsRest(__VLS_94));
let __VLS_97;
let __VLS_98;
let __VLS_99;
const __VLS_100 = {
    onClick: (__VLS_ctx.submitCreate)
};
__VLS_96.slots.default;
var __VLS_96;
var __VLS_48;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xl']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/60']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/60']} */ ;
/** @type {__VLS_StyleScopedClasses['m-4']} */ ;
/** @type {__VLS_StyleScopedClasses['m-4']} */ ;
/** @type {__VLS_StyleScopedClasses['list-disc']} */ ;
/** @type {__VLS_StyleScopedClasses['pl-4']} */ ;
/** @type {__VLS_StyleScopedClasses['p-8']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/70']} */ ;
/** @type {__VLS_StyleScopedClasses['m-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-x-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['w-72']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200/40']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-error']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-warning']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/60']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:text-base-content']} */ ;
/** @type {__VLS_StyleScopedClasses['text-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['leading-none']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-[80px]']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-grab']} */ ;
/** @type {__VLS_StyleScopedClasses['active:cursor-grabbing']} */ ;
/** @type {__VLS_StyleScopedClasses['shadow-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:shadow-md']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-shadow']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/70']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-error/20']} */ ;
/** @type {__VLS_StyleScopedClasses['text-error']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-info/15']} */ ;
/** @type {__VLS_StyleScopedClasses['text-info']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['w-96']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['border-l']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['pt-2']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VueDraggable: VueDraggable,
            VAlert: VAlert,
            VButton: VButton,
            VEmptyState: VEmptyState,
            VInput: VInput,
            VModal: VModal,
            VSelect: VSelect,
            KanbanCardDetail: KanbanCardDetail,
            board: board,
            loading: loading,
            error: error,
            warnings: warnings,
            selectedCardPath: selectedCardPath,
            showCreateModal: showCreateModal,
            newCardForm: newCardForm,
            selectedCard: selectedCard,
            cardsByColumn: cardsByColumn,
            load: load,
            refresh: refresh,
            onCardDropped: onCardDropped,
            onCardUpdate: onCardUpdate,
            onCardDelete: onCardDelete,
            openCreateModal: openCreateModal,
            submitCreate: submitCreate,
            priorityClass: priorityClass,
        };
    },
    __typeProps: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeProps: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=KanbanBoard.vue.js.map