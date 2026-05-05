import { computed } from 'vue';
import { WorkspaceNodeType } from '@vance/generated';
const props = defineProps();
const emit = defineEmits();
const isDir = computed(() => props.node.type === WorkspaceNodeType.DIR);
const isExpanded = computed(() => props.expanded.has(props.node.path));
const isLoading = computed(() => props.loadingPaths.has(props.node.path));
const isSelected = computed(() => props.selectedPath === props.node.path);
function onClick() {
    if (isDir.value) {
        emit('toggle', props.node.path, true);
    }
    else {
        emit('selectFile', props.node);
    }
}
const sortedChildren = computed(() => {
    if (!props.node.children)
        return [];
    // Folders first, then files; alphabetic within each group.
    return props.node.children.slice().sort((a, b) => {
        if (a.type !== b.type)
            return a.type === WorkspaceNodeType.DIR ? -1 : 1;
        return a.name.localeCompare(b.name);
    });
});
function formatSize(bytes) {
    if (bytes < 1024)
        return `${bytes} B`;
    if (bytes < 1024 * 1024)
        return `${(bytes / 1024).toFixed(0)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['row']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (__VLS_ctx.onClick) },
    type: "button",
    ...{ class: "row" },
    ...{ class: ({ 'row--selected': __VLS_ctx.isSelected }) },
    ...{ style: ({ paddingLeft: `${__VLS_ctx.level * 1.25 + 0.5}rem` }) },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "chevron" },
});
if (__VLS_ctx.isDir) {
    if (__VLS_ctx.isLoading) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
            ...{ class: "loading loading-spinner loading-xs" },
        });
    }
    else if (__VLS_ctx.isExpanded) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "icon" },
    ...{ class: (__VLS_ctx.isDir ? 'opacity-70' : 'opacity-40') },
});
(__VLS_ctx.isDir ? '📁' : '📄');
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "name truncate" },
});
(__VLS_ctx.node.name);
if (!__VLS_ctx.isDir) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "size" },
    });
    (__VLS_ctx.formatSize(__VLS_ctx.node.size));
}
if (__VLS_ctx.isDir && __VLS_ctx.isExpanded && __VLS_ctx.node.children !== undefined) {
    for (const [child] of __VLS_getVForSourceType((__VLS_ctx.sortedChildren))) {
        const __VLS_0 = {}.WorkspaceTreeNode;
        /** @type {[typeof __VLS_components.WorkspaceTreeNode, ]} */ ;
        // @ts-ignore
        const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
            ...{ 'onToggle': {} },
            ...{ 'onSelectFile': {} },
            key: (child.path),
            node: (child),
            expanded: (__VLS_ctx.expanded),
            loadingPaths: (__VLS_ctx.loadingPaths),
            selectedPath: (__VLS_ctx.selectedPath),
            level: (__VLS_ctx.level + 1),
        }));
        const __VLS_2 = __VLS_1({
            ...{ 'onToggle': {} },
            ...{ 'onSelectFile': {} },
            key: (child.path),
            node: (child),
            expanded: (__VLS_ctx.expanded),
            loadingPaths: (__VLS_ctx.loadingPaths),
            selectedPath: (__VLS_ctx.selectedPath),
            level: (__VLS_ctx.level + 1),
        }, ...__VLS_functionalComponentArgsRest(__VLS_1));
        let __VLS_4;
        let __VLS_5;
        let __VLS_6;
        const __VLS_7 = {
            onToggle: ((p, isD) => __VLS_ctx.emit('toggle', p, isD))
        };
        const __VLS_8 = {
            onSelectFile: ((n) => __VLS_ctx.emit('selectFile', n))
        };
        var __VLS_3;
    }
}
/** @type {__VLS_StyleScopedClasses['row']} */ ;
/** @type {__VLS_StyleScopedClasses['row--selected']} */ ;
/** @type {__VLS_StyleScopedClasses['chevron']} */ ;
/** @type {__VLS_StyleScopedClasses['loading']} */ ;
/** @type {__VLS_StyleScopedClasses['loading-spinner']} */ ;
/** @type {__VLS_StyleScopedClasses['loading-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['icon']} */ ;
/** @type {__VLS_StyleScopedClasses['name']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['size']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            emit: emit,
            isDir: isDir,
            isExpanded: isExpanded,
            isLoading: isLoading,
            isSelected: isSelected,
            onClick: onClick,
            sortedChildren: sortedChildren,
            formatSize: formatSize,
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
//# sourceMappingURL=WorkspaceTreeNode.vue.js.map