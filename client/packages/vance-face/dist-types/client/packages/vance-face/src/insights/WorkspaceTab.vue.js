import { ref, watch } from 'vue';
import { VAlert, VButton, VEmptyState } from '@/components';
import { useWorkspaceTree } from '@/composables/useWorkspaceTree';
import { useWorkspaceFile } from '@/composables/useWorkspaceFile';
import WorkspaceTreeNode from './WorkspaceTreeNode.vue';
import WorkspaceFileViewer from './WorkspaceFileViewer.vue';
const props = defineProps();
const tree = useWorkspaceTree();
const file = useWorkspaceFile();
const selectedNode = ref(null);
watch(() => props.projectId, async (next, prev) => {
    if (next === prev)
        return;
    selectedNode.value = null;
    file.clear();
    if (next) {
        await tree.loadRoot(next);
    }
}, { immediate: true });
function onToggle(path, isDir) {
    if (!props.projectId)
        return;
    void tree.toggle(props.projectId, path, isDir);
}
async function onSelectFile(node) {
    if (!props.projectId)
        return;
    selectedNode.value = node;
    await file.load(props.projectId, node.path, node.name);
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex h-[calc(100vh-12rem)] min-h-0 gap-3" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col w-80 shrink-0 min-h-0 border border-base-300 rounded" },
});
if (!__VLS_ctx.projectId) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "p-4 opacity-60 text-sm" },
    });
    (__VLS_ctx.$t('workspace.pickProjectHint'));
}
else if (__VLS_ctx.tree.loading.value && !__VLS_ctx.tree.root.value) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 flex items-center justify-center text-sm opacity-60" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
        ...{ class: "loading loading-spinner loading-md" },
    });
}
else if (__VLS_ctx.tree.error.value) {
    const __VLS_0 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
        variant: "error",
        ...{ class: "m-3" },
    }));
    const __VLS_2 = __VLS_1({
        variant: "error",
        ...{ class: "m-3" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    __VLS_3.slots.default;
    (__VLS_ctx.tree.error.value);
    var __VLS_3;
}
else if (__VLS_ctx.tree.root.value) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 min-h-0 overflow-auto p-2" },
    });
    /** @type {[typeof WorkspaceTreeNode, ]} */ ;
    // @ts-ignore
    const __VLS_4 = __VLS_asFunctionalComponent(WorkspaceTreeNode, new WorkspaceTreeNode({
        ...{ 'onToggle': {} },
        ...{ 'onSelectFile': {} },
        node: (__VLS_ctx.tree.root.value),
        expanded: (__VLS_ctx.tree.expanded),
        loadingPaths: (__VLS_ctx.tree.loadingPaths),
        selectedPath: (__VLS_ctx.selectedNode?.path ?? null),
        level: (0),
    }));
    const __VLS_5 = __VLS_4({
        ...{ 'onToggle': {} },
        ...{ 'onSelectFile': {} },
        node: (__VLS_ctx.tree.root.value),
        expanded: (__VLS_ctx.tree.expanded),
        loadingPaths: (__VLS_ctx.tree.loadingPaths),
        selectedPath: (__VLS_ctx.selectedNode?.path ?? null),
        level: (0),
    }, ...__VLS_functionalComponentArgsRest(__VLS_4));
    let __VLS_7;
    let __VLS_8;
    let __VLS_9;
    const __VLS_10 = {
        onToggle: (__VLS_ctx.onToggle)
    };
    const __VLS_11 = {
        onSelectFile: (__VLS_ctx.onSelectFile)
    };
    var __VLS_6;
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 flex items-center justify-center px-4" },
    });
    const __VLS_12 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
        headline: (__VLS_ctx.$t('workspace.empty.emptyTreeHeadline')),
        body: (__VLS_ctx.$t('workspace.empty.emptyTreeBody')),
    }));
    const __VLS_14 = __VLS_13({
        headline: (__VLS_ctx.$t('workspace.empty.emptyTreeHeadline')),
        body: (__VLS_ctx.$t('workspace.empty.emptyTreeBody')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_13));
}
if (__VLS_ctx.projectId) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "p-2 border-t border-base-300 flex justify-between items-center text-xs opacity-60" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.$t('workspace.footer.podHint'));
    const __VLS_16 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }));
    const __VLS_18 = __VLS_17({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }, ...__VLS_functionalComponentArgsRest(__VLS_17));
    let __VLS_20;
    let __VLS_21;
    let __VLS_22;
    const __VLS_23 = {
        onClick: (...[$event]) => {
            if (!(__VLS_ctx.projectId))
                return;
            __VLS_ctx.projectId && __VLS_ctx.tree.loadRoot(__VLS_ctx.projectId);
        }
    };
    __VLS_19.slots.default;
    (__VLS_ctx.$t('workspace.footer.refresh'));
    var __VLS_19;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex-1 min-w-0 border border-base-300 rounded" },
});
/** @type {[typeof WorkspaceFileViewer, ]} */ ;
// @ts-ignore
const __VLS_24 = __VLS_asFunctionalComponent(WorkspaceFileViewer, new WorkspaceFileViewer({
    name: (__VLS_ctx.selectedNode?.name ?? null),
    path: (__VLS_ctx.selectedNode?.path ?? null),
    loading: (__VLS_ctx.file.loading.value),
    error: (__VLS_ctx.file.error.value),
    result: (__VLS_ctx.file.result.value),
}));
const __VLS_25 = __VLS_24({
    name: (__VLS_ctx.selectedNode?.name ?? null),
    path: (__VLS_ctx.selectedNode?.path ?? null),
    loading: (__VLS_ctx.file.loading.value),
    error: (__VLS_ctx.file.error.value),
    result: (__VLS_ctx.file.result.value),
}, ...__VLS_functionalComponentArgsRest(__VLS_24));
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['h-[calc(100vh-12rem)]']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['w-80']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['loading']} */ ;
/** @type {__VLS_StyleScopedClasses['loading-spinner']} */ ;
/** @type {__VLS_StyleScopedClasses['loading-md']} */ ;
/** @type {__VLS_StyleScopedClasses['m-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['border-t']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            VButton: VButton,
            VEmptyState: VEmptyState,
            WorkspaceTreeNode: WorkspaceTreeNode,
            WorkspaceFileViewer: WorkspaceFileViewer,
            tree: tree,
            file: file,
            selectedNode: selectedNode,
            onToggle: onToggle,
            onSelectFile: onSelectFile,
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
//# sourceMappingURL=WorkspaceTab.vue.js.map