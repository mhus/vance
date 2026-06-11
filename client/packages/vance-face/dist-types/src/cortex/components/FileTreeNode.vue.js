import { computed, inject } from 'vue';
const props = defineProps();
const emit = defineEmits();
function isOpen(path) {
    return path === '' || props.expanded.has(path);
}
function fileIcon(name) {
    if (name.endsWith('.js') || name.endsWith('.mjs'))
        return 'JS';
    if (name.endsWith('.json'))
        return '{}';
    if (name.endsWith('.md'))
        return 'MD';
    if (name.endsWith('.yaml') || name.endsWith('.yml'))
        return 'Y';
    if (name.endsWith('.txt'))
        return 'T';
    return '·';
}
function indentStyle(extra) {
    return { paddingLeft: `${(props.depth + extra) * 12}px` };
}
// ──────────────── Drag & Drop ────────────────
//
// File rows are draggable. Folder rows (including the synthetic root)
// are drop targets that accept either:
//
//   - another tracked file → emits {@code move-file}, the parent
//     translates to {@code store.moveFile(id, newPath)}.
//   - OS files from the desktop → emits {@code upload-files}, the
//     parent translates to {@code store.uploadExternalFile(file, folder)}.
//
// {@code application/vance-doc-id} is our private MIME — used to
// distinguish an internal drag from a cross-tab drag without leaking
// the document id into dataTransfer.types (which is observable during
// dragover, when the data itself is not yet readable).
//
// {@link dragOverPath} is a tree-wide shared ref (provided by the
// FileTreeSidebar) so that only the deepest folder under the cursor
// shows the highlight — child {@code dragover} overwrites the path,
// {@code drop} or document-level cleanup clears it.
const VANCE_DOC_MIME = 'application/vance-doc-id';
const dragOverPath = inject('cortexDragOverPath');
const folderDragOver = computed(() => dragOverPath?.value === props.node.path);
function setDragOver(path) {
    if (dragOverPath)
        dragOverPath.value = path;
}
function onFileDragStart(ev, fileId) {
    if (!ev.dataTransfer)
        return;
    ev.dataTransfer.setData(VANCE_DOC_MIME, fileId);
    // text/plain mirror keeps non-conforming targets (the URL bar, an
    // editor outside the tree) from claiming ownership of the drag.
    ev.dataTransfer.setData('text/plain', fileId);
    ev.dataTransfer.effectAllowed = 'move';
}
function isAcceptableDrag(ev) {
    const types = ev.dataTransfer?.types;
    if (!types)
        return false;
    // {@code DataTransferItemList} reports OS files as "Files".
    return Array.from(types).some((t) => t === VANCE_DOC_MIME || t === 'Files');
}
function onFolderDragOver(ev) {
    if (!isAcceptableDrag(ev))
        return;
    ev.preventDefault();
    ev.stopPropagation();
    const isExternal = Array.from(ev.dataTransfer?.types ?? []).includes('Files');
    if (ev.dataTransfer) {
        ev.dataTransfer.dropEffect = isExternal ? 'copy' : 'move';
    }
    setDragOver(props.node.path);
}
function onFolderDrop(ev) {
    if (!isAcceptableDrag(ev))
        return;
    ev.preventDefault();
    ev.stopPropagation();
    setDragOver(null);
    const dt = ev.dataTransfer;
    if (!dt)
        return;
    const docId = dt.getData(VANCE_DOC_MIME);
    if (docId) {
        emit('move-file', { id: docId, targetFolder: props.node.path });
        return;
    }
    const files = Array.from(dt.files ?? []);
    if (files.length > 0) {
        emit('upload-files', { files, targetFolder: props.node.path });
    }
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ onDragover: (__VLS_ctx.onFolderDragOver) },
    ...{ onDrop: (__VLS_ctx.onFolderDrop) },
    ...{ class: ([
            'rounded',
            __VLS_ctx.folderDragOver ? 'ring-2 ring-primary/40 bg-primary/5' : '',
        ]) },
});
if (__VLS_ctx.node.path !== '') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.node.path !== ''))
                    return;
                __VLS_ctx.emit('toggle', __VLS_ctx.node.path);
            } },
        type: "button",
        ...{ class: "w-full text-left px-2 py-1 hover:bg-base-200 rounded flex items-center gap-1" },
        ...{ style: (__VLS_ctx.indentStyle(0)) },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "opacity-50 w-3 inline-block text-xs" },
    });
    (__VLS_ctx.isOpen(__VLS_ctx.node.path) ? '▾' : '▸');
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "truncate" },
    });
    (__VLS_ctx.node.name);
}
if (__VLS_ctx.isOpen(__VLS_ctx.node.path)) {
    for (const [child] of __VLS_getVForSourceType((__VLS_ctx.node.children))) {
        const __VLS_0 = {}.FileTreeNode;
        /** @type {[typeof __VLS_components.FileTreeNode, ]} */ ;
        // @ts-ignore
        const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
            ...{ 'onToggle': {} },
            ...{ 'onOpenFile': {} },
            ...{ 'onDeleteFile': {} },
            ...{ 'onMoveFile': {} },
            ...{ 'onUploadFiles': {} },
            key: (`f:${child.path}`),
            node: (child),
            depth: (__VLS_ctx.depth + 1),
            activeFileId: (__VLS_ctx.activeFileId ?? null),
            expanded: (__VLS_ctx.expanded),
        }));
        const __VLS_2 = __VLS_1({
            ...{ 'onToggle': {} },
            ...{ 'onOpenFile': {} },
            ...{ 'onDeleteFile': {} },
            ...{ 'onMoveFile': {} },
            ...{ 'onUploadFiles': {} },
            key: (`f:${child.path}`),
            node: (child),
            depth: (__VLS_ctx.depth + 1),
            activeFileId: (__VLS_ctx.activeFileId ?? null),
            expanded: (__VLS_ctx.expanded),
        }, ...__VLS_functionalComponentArgsRest(__VLS_1));
        let __VLS_4;
        let __VLS_5;
        let __VLS_6;
        const __VLS_7 = {
            onToggle: ((p) => __VLS_ctx.emit('toggle', p))
        };
        const __VLS_8 = {
            onOpenFile: ((id) => __VLS_ctx.emit('open-file', id))
        };
        const __VLS_9 = {
            onDeleteFile: ((id) => __VLS_ctx.emit('delete-file', id))
        };
        const __VLS_10 = {
            onMoveFile: ((payload) => __VLS_ctx.emit('move-file', payload))
        };
        const __VLS_11 = {
            onUploadFiles: ((payload) => __VLS_ctx.emit('upload-files', payload))
        };
        var __VLS_3;
    }
    for (const [file] of __VLS_getVForSourceType((__VLS_ctx.node.files))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ onDragstart: ((ev) => __VLS_ctx.onFileDragStart(ev, file.id)) },
            key: (`x:${file.id}`),
            'data-file-id': (file.id),
            draggable: "true",
            ...{ class: ([
                    'group flex items-center gap-1 px-2 py-1 hover:bg-base-200 rounded cursor-pointer',
                    __VLS_ctx.activeFileId === file.id ? 'bg-base-200 font-semibold' : '',
                ]) },
            ...{ style: ({ paddingLeft: `${(__VLS_ctx.depth + 1) * 12 + 4}px` }) },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!(__VLS_ctx.isOpen(__VLS_ctx.node.path)))
                        return;
                    __VLS_ctx.emit('open-file', file.id);
                } },
            type: "button",
            ...{ class: "flex-1 text-left flex items-center gap-1 min-w-0" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "opacity-50 w-6 text-xs font-mono" },
        });
        (__VLS_ctx.fileIcon(file.name));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "truncate" },
        });
        (file.name);
        if (file.dirty) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "opacity-50 text-xs" },
            });
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!(__VLS_ctx.isOpen(__VLS_ctx.node.path)))
                        return;
                    __VLS_ctx.emit('delete-file', file.id);
                } },
            type: "button",
            ...{ class: "opacity-0 group-hover:opacity-60 hover:opacity-100 px-1 text-xs" },
            title: "delete",
        });
    }
}
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['text-left']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['w-3']} */ ;
/** @type {__VLS_StyleScopedClasses['inline-block']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['group']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-left']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['w-6']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-0']} */ ;
/** @type {__VLS_StyleScopedClasses['group-hover:opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:opacity-100']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            emit: emit,
            isOpen: isOpen,
            fileIcon: fileIcon,
            indentStyle: indentStyle,
            folderDragOver: folderDragOver,
            onFileDragStart: onFileDragStart,
            onFolderDragOver: onFolderDragOver,
            onFolderDrop: onFolderDrop,
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
//# sourceMappingURL=FileTreeNode.vue.js.map