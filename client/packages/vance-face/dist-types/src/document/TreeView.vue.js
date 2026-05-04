import { computed, inject, nextTick, provide, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VueDraggable } from 'vue-draggable-plus';
/**
 * Recursive editor for `kind: tree` documents. The component runs in
 * two modes:
 *
 * 1. **Top-level** — the parent passes a {@code doc} prop. We build
 *    the editor state ({@link useTreeEditor}) here, provide it to
 *    descendants via {@code inject}, and render the tree's root list.
 *
 * 2. **Recursive** — when the component renders its children we pass
 *    the children array plus a {@code pathPrefix}. The recursive
 *    instance reads the editor state via {@code inject} and operates
 *    on its slice through that path.
 *
 * Mutations bubble up through the editor's {@code emit} callback as a
 * fresh {@link TreeDocument}; the parent re-serializes into the raw
 * body so the existing Save button writes the canonical form back.
 *
 * Spec: `specification/doc-kind-tree.md` (especially §5).
 */
defineOptions({ name: 'TreeView' });
const props = withDefaults(defineProps(), {
    doc: null,
    items: () => [],
    pathPrefix: () => [],
});
const emit = defineEmits();
const { t } = useI18n();
const isTopLevel = props.doc !== null;
const editor = isTopLevel
    ? createEditor(props.doc, (next) => emit('update:doc', next))
    : inject('treeEditor');
if (isTopLevel) {
    provide('treeEditor', editor);
}
/** The list of items to render at this level — bound via v-model
 *  on the underlying VueDraggable so reorder events flow back into
 *  the source array.
 *
 *  Top-level: read/write the editor's mutable ref directly.
 *  Recursive: read the {@code props.items} reference; when
 *  vue-draggable-plus emits a reordered array we splice it in place
 *  so the parent's same-array-reference picks up the new order. */
const renderItems = computed({
    get: () => isTopLevel ? editor.items.value : props.items,
    set: (next) => {
        if (isTopLevel) {
            editor.items.value = next;
        }
        else {
            // Mutate in place — keeps the array reference stable so the
            // parent's editor.items still points at the same slice.
            const arr = props.items;
            arr.splice(0, arr.length, ...next);
        }
    },
});
function pathFor(idx) {
    return [...props.pathPrefix, idx];
}
// ── Per-row keyboard / drag handlers (delegate to editor) ───────────
function onEditKeydown(event, path) {
    if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        editor.commitEdit();
        editor.addSibling(path);
        return;
    }
    if (event.key === 'Escape') {
        event.preventDefault();
        editor.cancelEdit();
        return;
    }
    if (event.key === 'Tab') {
        event.preventDefault();
        // Commit the current text first so indent/outdent moves the
        // committed item, not a stale one.
        editor.commitEdit();
        if (event.shiftKey) {
            editor.outdentAt(path);
        }
        else {
            editor.indentAt(path);
        }
        return;
    }
    if (event.key === 'Backspace' && editor.editBuffer.value === '') {
        event.preventDefault();
        const prev = editor.prevDfsPath(path);
        editor.deleteAt(path);
        if (prev) {
            void nextTick(() => editor.startEdit(prev));
        }
    }
}
function autoGrow(event) {
    const el = event.target;
    el.style.height = 'auto';
    el.style.height = el.scrollHeight + 'px';
}
function registerInput(path, el) {
    const key = editor.pathKey(path);
    if (el) {
        editor.inputRefs.value.set(key, el);
    }
    else {
        editor.inputRefs.value.delete(key);
    }
}
function onDragStart() {
    editor.cancelEdit();
}
function onDragEnd() {
    editor.emitChange();
}
/**
 * Per-level drag group. `vue-draggable-plus` only allows drops within
 * the same group, so giving each depth its own group blocks
 * cross-level drops at the library layer. Spec §5.4.
 */
const dragGroup = computed(() => `tree-level-${props.pathPrefix.length}`);
// ── Editor factory ─────────────────────────────────────────────────
function createEditor(initial, onCommit) {
    const items = ref(cloneItems(initial.items));
    const editingPath = ref(null);
    const editBuffer = ref('');
    const collapsed = ref(new Set());
    const inputRefs = ref(new Map());
    // Keep the local copy in sync with external doc changes (e.g. Raw
    // tab edits flowing in). The codec is idempotent on round-trip, so
    // this watch firing after our own emit is a no-op effect.
    watch(() => initial.items, (next) => {
        items.value = cloneItems(next);
    }, { deep: true });
    function emitChange() {
        onCommit({
            kind: initial.kind || 'tree',
            items: items.value,
            extra: initial.extra,
        });
    }
    function getList(path) {
        if (path.length === 0)
            return null;
        let curr = items.value;
        for (let i = 0; i < path.length - 1; i++) {
            const item = curr[path[i]];
            if (!item)
                return null;
            curr = item.children;
        }
        return curr;
    }
    function getItem(path) {
        const list = getList(path);
        if (!list)
            return null;
        return list[path[path.length - 1]] ?? null;
    }
    function startEdit(path) {
        editingPath.value = path;
        editBuffer.value = getItem(path)?.text ?? '';
        void nextTick(() => {
            const el = inputRefs.value.get(pathKey(path));
            if (el) {
                el.focus();
                el.setSelectionRange(el.value.length, el.value.length);
            }
        });
    }
    function cancelEdit() {
        editingPath.value = null;
        editBuffer.value = '';
    }
    function commitEdit() {
        if (!editingPath.value)
            return;
        const item = getItem(editingPath.value);
        if (item) {
            item.text = editBuffer.value;
        }
        editingPath.value = null;
        editBuffer.value = '';
        emitChange();
    }
    function addSibling(path) {
        const list = getList(path);
        if (!list) {
            // path === []? push a top-level item.
            items.value.push({ text: '', children: [], extra: {} });
            const newPath = [items.value.length - 1];
            emitChange();
            void nextTick(() => startEdit(newPath));
            return;
        }
        const idx = path[path.length - 1];
        const newItem = { text: '', children: [], extra: {} };
        list.splice(idx + 1, 0, newItem);
        const newPath = [...path.slice(0, -1), idx + 1];
        emitChange();
        void nextTick(() => startEdit(newPath));
    }
    function addChild(path) {
        const item = getItem(path);
        if (!item)
            return;
        const newItem = { text: '', children: [], extra: {} };
        item.children.unshift(newItem);
        // Expand the parent so the new child is visible.
        collapsed.value.delete(pathKey(path));
        collapsed.value = new Set(collapsed.value);
        const newPath = [...path, 0];
        emitChange();
        void nextTick(() => startEdit(newPath));
    }
    /** Add a new top-level item at the end of the root list. */
    function addRoot() {
        items.value.push({ text: '', children: [], extra: {} });
        const newPath = [items.value.length - 1];
        emitChange();
        void nextTick(() => startEdit(newPath));
    }
    function deleteAt(path) {
        const list = getList(path);
        if (!list)
            return;
        const idx = path[path.length - 1];
        list.splice(idx, 1);
        cancelEdit();
        emitChange();
    }
    function indentAt(path) {
        const list = getList(path);
        if (!list)
            return;
        const idx = path[path.length - 1];
        if (idx === 0)
            return; // first sibling can't be indented
        const prev = list[idx - 1];
        const me = list[idx];
        prev.children.push(me);
        list.splice(idx, 1);
        // Make sure the new parent is expanded so the moved item stays
        // visible.
        collapsed.value.delete(pathKey([...path.slice(0, -1), idx - 1]));
        collapsed.value = new Set(collapsed.value);
        // Re-focus the moved item.
        const newPath = [...path.slice(0, -1), idx - 1, prev.children.length - 1];
        if (editingPath.value && pathsEqual(editingPath.value, path)) {
            editingPath.value = newPath;
        }
        emitChange();
        void nextTick(() => {
            const el = inputRefs.value.get(pathKey(newPath));
            if (el)
                el.focus();
        });
    }
    function outdentAt(path) {
        if (path.length <= 1)
            return; // top-level can't be outdented
        const myList = getList(path);
        if (!myList)
            return;
        const idx = path[path.length - 1];
        const myItem = myList[idx];
        const parentPath = path.slice(0, -1);
        const parentList = getList(parentPath);
        if (!parentList)
            return; // shouldn't happen for non-root
        const parentIdx = parentPath[parentPath.length - 1];
        myList.splice(idx, 1);
        parentList.splice(parentIdx + 1, 0, myItem);
        const newPath = [...parentPath.slice(0, -1), parentIdx + 1];
        if (editingPath.value && pathsEqual(editingPath.value, path)) {
            editingPath.value = newPath;
        }
        emitChange();
        void nextTick(() => {
            const el = inputRefs.value.get(pathKey(newPath));
            if (el)
                el.focus();
        });
    }
    function toggleCollapsed(path) {
        const key = pathKey(path);
        const next = new Set(collapsed.value);
        if (next.has(key))
            next.delete(key);
        else
            next.add(key);
        collapsed.value = next;
    }
    function isCollapsed(path) {
        return collapsed.value.has(pathKey(path));
    }
    function isEditing(path) {
        return editingPath.value != null && pathsEqual(editingPath.value, path);
    }
    function pathKey(path) {
        return path.join('.');
    }
    /** Previous item in pre-order DFS — used for Backspace-on-empty
     *  to focus the natural "before" neighbor. Returns null when we're
     *  the very first node. */
    function prevDfsPath(path) {
        if (path.length === 0)
            return null;
        const idx = path[path.length - 1];
        if (idx === 0) {
            // Walk up to parent.
            const parentPath = path.slice(0, -1);
            return parentPath.length === 0 ? null : parentPath;
        }
        // Walk down the rightmost branch of the previous sibling.
        let cursorPath = [...path.slice(0, -1), idx - 1];
        while (true) {
            const item = getItem(cursorPath);
            if (!item || item.children.length === 0)
                break;
            if (collapsed.value.has(pathKey(cursorPath)))
                break;
            cursorPath = [...cursorPath, item.children.length - 1];
        }
        return cursorPath;
    }
    return {
        items,
        editingPath,
        editBuffer,
        collapsed,
        inputRefs,
        emitChange,
        startEdit,
        cancelEdit,
        commitEdit,
        addSibling,
        addChild,
        addRoot,
        deleteAt,
        indentAt,
        outdentAt,
        toggleCollapsed,
        isCollapsed,
        isEditing,
        pathKey,
        prevDfsPath,
    };
}
/** Tree-aware deep clone: preserves text/extra/children. */
function cloneItems(src) {
    return src.map((it) => ({
        text: it.text,
        extra: { ...it.extra },
        children: cloneItems(it.children),
    }));
}
function pathsEqual(a, b) {
    if (a.length !== b.length)
        return false;
    for (let i = 0; i < a.length; i++)
        if (a[i] !== b[i])
            return false;
    return true;
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    doc: null,
    items: () => [],
    pathPrefix: () => [],
});
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['drag-handle']} */ ;
/** @type {__VLS_StyleScopedClasses['drag-handle']} */ ;
/** @type {__VLS_StyleScopedClasses['disclosure']} */ ;
/** @type {__VLS_StyleScopedClasses['text']} */ ;
/** @type {__VLS_StyleScopedClasses['edit-input']} */ ;
/** @type {__VLS_StyleScopedClasses['row-action']} */ ;
/** @type {__VLS_StyleScopedClasses['add-root']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: (__VLS_ctx.isTopLevel ? 'tree-edit' : 'tree-edit-nested') },
});
if (__VLS_ctx.renderItems.length > 0) {
    const __VLS_0 = {}.VueDraggable;
    /** @type {[typeof __VLS_components.VueDraggable, typeof __VLS_components.VueDraggable, ]} */ ;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
        ...{ 'onStart': {} },
        ...{ 'onEnd': {} },
        modelValue: (__VLS_ctx.renderItems),
        tag: "ul",
        ...{ class: "tree-rows" },
        animation: (150),
        group: (__VLS_ctx.dragGroup),
        handle: ".drag-handle",
        ghostClass: "row--ghost",
        chosenClass: "row--chosen",
        dragClass: "row--drag",
    }));
    const __VLS_2 = __VLS_1({
        ...{ 'onStart': {} },
        ...{ 'onEnd': {} },
        modelValue: (__VLS_ctx.renderItems),
        tag: "ul",
        ...{ class: "tree-rows" },
        animation: (150),
        group: (__VLS_ctx.dragGroup),
        handle: ".drag-handle",
        ghostClass: "row--ghost",
        chosenClass: "row--chosen",
        dragClass: "row--drag",
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    let __VLS_4;
    let __VLS_5;
    let __VLS_6;
    const __VLS_7 = {
        onStart: (__VLS_ctx.onDragStart)
    };
    const __VLS_8 = {
        onEnd: (__VLS_ctx.onDragEnd)
    };
    __VLS_3.slots.default;
    for (const [item, idx] of __VLS_getVForSourceType((__VLS_ctx.renderItems))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
            key: (idx),
            ...{ class: "tree-row" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "row-head" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "drag-handle" },
            title: (__VLS_ctx.t('documents.treeEditor.dragHandle')),
            'aria-hidden': "true",
        });
        if (item.children.length > 0) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!(__VLS_ctx.renderItems.length > 0))
                            return;
                        if (!(item.children.length > 0))
                            return;
                        __VLS_ctx.editor.toggleCollapsed(__VLS_ctx.pathFor(idx));
                    } },
                type: "button",
                ...{ class: "disclosure" },
                title: (__VLS_ctx.editor.isCollapsed(__VLS_ctx.pathFor(idx))
                    ? __VLS_ctx.t('documents.treeEditor.expand')
                    : __VLS_ctx.t('documents.treeEditor.collapse')),
            });
            (__VLS_ctx.editor.isCollapsed(__VLS_ctx.pathFor(idx)) ? '▸' : '▾');
        }
        else {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
                ...{ class: "disclosure-spacer" },
                'aria-hidden': "true",
            });
        }
        if (__VLS_ctx.editor.isEditing(__VLS_ctx.pathFor(idx))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.textarea)({
                ...{ onBlur: (...[$event]) => {
                        if (!(__VLS_ctx.renderItems.length > 0))
                            return;
                        if (!(__VLS_ctx.editor.isEditing(__VLS_ctx.pathFor(idx))))
                            return;
                        __VLS_ctx.editor.commitEdit();
                    } },
                ...{ onKeydown: (...[$event]) => {
                        if (!(__VLS_ctx.renderItems.length > 0))
                            return;
                        if (!(__VLS_ctx.editor.isEditing(__VLS_ctx.pathFor(idx))))
                            return;
                        __VLS_ctx.onEditKeydown($event, __VLS_ctx.pathFor(idx));
                    } },
                ...{ onInput: (__VLS_ctx.autoGrow) },
                ref: ((el) => __VLS_ctx.registerInput(__VLS_ctx.pathFor(idx), el)),
                value: (__VLS_ctx.editor.editBuffer.value),
                ...{ class: "edit-input" },
                rows: "1",
            });
        }
        else {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!(__VLS_ctx.renderItems.length > 0))
                            return;
                        if (!!(__VLS_ctx.editor.isEditing(__VLS_ctx.pathFor(idx))))
                            return;
                        __VLS_ctx.editor.startEdit(__VLS_ctx.pathFor(idx));
                    } },
                type: "button",
                ...{ class: "text" },
                title: (__VLS_ctx.t('documents.treeEditor.clickToEdit')),
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
                (__VLS_ctx.t('documents.treeEditor.emptyItem'));
            }
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!(__VLS_ctx.renderItems.length > 0))
                        return;
                    __VLS_ctx.editor.addChild(__VLS_ctx.pathFor(idx));
                } },
            type: "button",
            ...{ class: "row-action" },
            title: (__VLS_ctx.t('documents.treeEditor.addChild')),
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!(__VLS_ctx.renderItems.length > 0))
                        return;
                    __VLS_ctx.editor.deleteAt(__VLS_ctx.pathFor(idx));
                } },
            type: "button",
            ...{ class: "row-action row-delete" },
            title: (__VLS_ctx.t('documents.treeEditor.deleteItem')),
        });
        if (item.children.length > 0 && !__VLS_ctx.editor.isCollapsed(__VLS_ctx.pathFor(idx))) {
            const __VLS_9 = {}.TreeView;
            /** @type {[typeof __VLS_components.TreeView, ]} */ ;
            // @ts-ignore
            const __VLS_10 = __VLS_asFunctionalComponent(__VLS_9, new __VLS_9({
                items: (item.children),
                pathPrefix: (__VLS_ctx.pathFor(idx)),
            }));
            const __VLS_11 = __VLS_10({
                items: (item.children),
                pathPrefix: (__VLS_ctx.pathFor(idx)),
            }, ...__VLS_functionalComponentArgsRest(__VLS_10));
        }
    }
    var __VLS_3;
}
if (__VLS_ctx.isTopLevel) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "add-row" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.isTopLevel))
                    return;
                __VLS_ctx.editor.addRoot();
            } },
        type: "button",
        ...{ class: "add-root" },
    });
    (__VLS_ctx.t('documents.treeEditor.addItem'));
}
/** @type {__VLS_StyleScopedClasses['tree-rows']} */ ;
/** @type {__VLS_StyleScopedClasses['tree-row']} */ ;
/** @type {__VLS_StyleScopedClasses['row-head']} */ ;
/** @type {__VLS_StyleScopedClasses['drag-handle']} */ ;
/** @type {__VLS_StyleScopedClasses['disclosure']} */ ;
/** @type {__VLS_StyleScopedClasses['disclosure-spacer']} */ ;
/** @type {__VLS_StyleScopedClasses['edit-input']} */ ;
/** @type {__VLS_StyleScopedClasses['text']} */ ;
/** @type {__VLS_StyleScopedClasses['text-content']} */ ;
/** @type {__VLS_StyleScopedClasses['text-empty']} */ ;
/** @type {__VLS_StyleScopedClasses['row-action']} */ ;
/** @type {__VLS_StyleScopedClasses['row-action']} */ ;
/** @type {__VLS_StyleScopedClasses['row-delete']} */ ;
/** @type {__VLS_StyleScopedClasses['add-row']} */ ;
/** @type {__VLS_StyleScopedClasses['add-root']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VueDraggable: VueDraggable,
            t: t,
            isTopLevel: isTopLevel,
            editor: editor,
            renderItems: renderItems,
            pathFor: pathFor,
            onEditKeydown: onEditKeydown,
            autoGrow: autoGrow,
            registerInput: registerInput,
            onDragStart: onDragStart,
            onDragEnd: onDragEnd,
            dragGroup: dragGroup,
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
//# sourceMappingURL=TreeView.vue.js.map