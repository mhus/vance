import { nextTick, onBeforeUnmount, onMounted, provide, ref } from 'vue';
import FileTreeNode from './FileTreeNode.vue';
const props = defineProps();
const emit = defineEmits();
// Root pre-expanded so the project's top-level structure is visible
// immediately. Sub-folders collapse until clicked.
const expanded = ref(new Set(['']));
const sidebarEl = ref(null);
// Tree-wide single drop-target — the deepest folder currently under
// the cursor during a drag. Shared across all FileTreeNode instances
// via provide/inject so that {@code dragover} on a child folder
// implicitly clears the parent's highlight (last-writer-wins).
const dragOverPath = ref(null);
provide('cortexDragOverPath', dragOverPath);
// Safety net: an OS-originated drag has no element of ours to fire
// {@code dragend} on, and a drag the user cancels (Esc / drops outside
// our nodes) leaves no other hook to reset the highlight. Document-
// level {@code dragend}/{@code drop} listeners catch all of those — for
// drops on our own folder rows the inner handler has already cleared
// the path, so the no-op here is harmless.
function clearDragOver() {
    dragOverPath.value = null;
}
onMounted(() => {
    document.addEventListener('dragend', clearDragOver);
    document.addEventListener('drop', clearDragOver);
});
onBeforeUnmount(() => {
    document.removeEventListener('dragend', clearDragOver);
    document.removeEventListener('drop', clearDragOver);
});
function toggle(path) {
    const next = new Set(expanded.value);
    if (next.has(path)) {
        next.delete(path);
    }
    else {
        next.add(path);
    }
    expanded.value = next;
}
// Walk the folder tree to collect the chain of folder paths that lead
// to {@code fileId} — the trail we need to add to {@link expanded} so
// the file row becomes visible. Returns null when the file is not in
// this tree (e.g. just deleted on the server).
function ancestorPathsFor(node, fileId) {
    if (node.files.some((f) => f.id === fileId)) {
        return [node.path];
    }
    for (const child of node.children) {
        const sub = ancestorPathsFor(child, fileId);
        if (sub)
            return [node.path, ...sub];
    }
    return null;
}
function revealActiveFile() {
    const id = props.activeFileId;
    if (!id)
        return;
    const ancestors = ancestorPathsFor(props.root, id);
    if (!ancestors)
        return;
    const next = new Set(expanded.value);
    for (const p of ancestors)
        next.add(p);
    expanded.value = next;
    void nextTick(() => {
        const safe = window.CSS?.escape ? window.CSS.escape(id) : id;
        const el = sidebarEl.value?.querySelector(`[data-file-id="${safe}"]`);
        el?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    });
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ref: "sidebarEl",
    ...{ class: "p-2 text-sm" },
});
/** @type {typeof __VLS_ctx.sidebarEl} */ ;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "mb-2 px-1 flex items-center gap-1" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "font-semibold opacity-80 flex-1" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (__VLS_ctx.revealActiveFile) },
    type: "button",
    ...{ class: "text-xs px-1.5 py-0.5 rounded opacity-60 enabled:hover:opacity-100 enabled:hover:bg-base-200 disabled:cursor-default" },
    disabled: (!__VLS_ctx.activeFileId),
    title: "Reveal active file in tree",
});
/** @type {[typeof FileTreeNode, ]} */ ;
// @ts-ignore
const __VLS_0 = __VLS_asFunctionalComponent(FileTreeNode, new FileTreeNode({
    ...{ 'onToggle': {} },
    ...{ 'onOpenFile': {} },
    ...{ 'onDeleteFile': {} },
    ...{ 'onMoveFile': {} },
    ...{ 'onUploadFiles': {} },
    node: (__VLS_ctx.root),
    depth: (0),
    activeFileId: (__VLS_ctx.activeFileId ?? null),
    expanded: (__VLS_ctx.expanded),
}));
const __VLS_1 = __VLS_0({
    ...{ 'onToggle': {} },
    ...{ 'onOpenFile': {} },
    ...{ 'onDeleteFile': {} },
    ...{ 'onMoveFile': {} },
    ...{ 'onUploadFiles': {} },
    node: (__VLS_ctx.root),
    depth: (0),
    activeFileId: (__VLS_ctx.activeFileId ?? null),
    expanded: (__VLS_ctx.expanded),
}, ...__VLS_functionalComponentArgsRest(__VLS_0));
let __VLS_3;
let __VLS_4;
let __VLS_5;
const __VLS_6 = {
    onToggle: (__VLS_ctx.toggle)
};
const __VLS_7 = {
    onOpenFile: ((id) => __VLS_ctx.emit('open-file', id))
};
const __VLS_8 = {
    onDeleteFile: ((id) => __VLS_ctx.emit('delete-file', id))
};
const __VLS_9 = {
    onMoveFile: ((payload) => __VLS_ctx.emit('move-file', payload))
};
const __VLS_10 = {
    onUploadFiles: ((payload) => __VLS_ctx.emit('upload-files', payload))
};
var __VLS_2;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['enabled:hover:opacity-100']} */ ;
/** @type {__VLS_StyleScopedClasses['enabled:hover:bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['disabled:cursor-default']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            FileTreeNode: FileTreeNode,
            emit: emit,
            expanded: expanded,
            sidebarEl: sidebarEl,
            toggle: toggle,
            revealActiveFile: revealActiveFile,
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
//# sourceMappingURL=FileTreeSidebar.vue.js.map