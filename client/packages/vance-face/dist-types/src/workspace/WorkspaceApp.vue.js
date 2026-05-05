import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { EditorShell, VAlert, VButton, VEmptyState, VSelect, } from '@/components';
import { useTenantProjects } from '@/composables/useTenantProjects';
import { useWorkspaceTree } from '@/composables/useWorkspaceTree';
import { useWorkspaceFile } from '@/composables/useWorkspaceFile';
import WorkspaceTreeNode from './WorkspaceTreeNode.vue';
import WorkspaceFileViewer from './WorkspaceFileViewer.vue';
const { t } = useI18n();
const tenantProjects = useTenantProjects();
const tree = useWorkspaceTree();
const file = useWorkspaceFile();
const selectedProjectId = ref(null);
const selectedNode = ref(null);
const projectOptions = computed(() => {
    return tenantProjects.projects.value
        .filter((p) => p.enabled)
        .map((p) => ({ value: p.name, label: p.title || p.name }));
});
const breadcrumbs = computed(() => {
    const root = { text: t('workspace.pageTitle') };
    if (!selectedProjectId.value)
        return [root];
    const proj = tenantProjects.projects.value.find((p) => p.name === selectedProjectId.value);
    return [root, { text: proj?.title || selectedProjectId.value }];
});
onMounted(async () => {
    await tenantProjects.reload();
    // Restore from URL or fall back to first enabled project. URL is the
    // source of truth for deep-links — same convention as DocumentApp.
    const params = new URLSearchParams(window.location.search);
    const queryProject = params.get('projectId');
    const queryFile = params.get('path');
    if (queryProject && tenantProjects.projects.value.some((p) => p.name === queryProject)) {
        selectedProjectId.value = queryProject;
    }
    else if (tenantProjects.projects.value.length > 0) {
        selectedProjectId.value = tenantProjects.projects.value[0].name;
    }
    if (selectedProjectId.value && queryFile) {
        // Caller deep-linked into a file; load it once the tree is ready.
        await tree.loadRoot(selectedProjectId.value);
        await loadFileByPath(queryFile);
        return;
    }
    if (selectedProjectId.value) {
        await tree.loadRoot(selectedProjectId.value);
    }
});
watch(selectedProjectId, async (next, prev) => {
    if (!next || next === prev)
        return;
    syncQueryParam('projectId', next);
    syncQueryParam('path', null);
    selectedNode.value = null;
    file.clear();
    await tree.loadRoot(next);
});
function onToggle(path, isDir) {
    if (!selectedProjectId.value)
        return;
    void tree.toggle(selectedProjectId.value, path, isDir);
}
async function onSelectFile(node) {
    if (!selectedProjectId.value)
        return;
    selectedNode.value = node;
    syncQueryParam('path', node.path);
    await file.load(selectedProjectId.value, node.path, node.name);
}
async function loadFileByPath(path) {
    // Synthetic node for deep-link load — we may not have walked the
    // tree to this exact file yet, but the file endpoint doesn't care.
    const name = path.split('/').pop() ?? path;
    const synthetic = {
        name,
        path,
        type: 0, // FILE
        size: 0,
        children: undefined,
    };
    selectedNode.value = synthetic;
    if (!selectedProjectId.value)
        return;
    await file.load(selectedProjectId.value, path, name);
}
function syncQueryParam(key, value) {
    const url = new URL(window.location.href);
    if (value === null || value === '') {
        url.searchParams.delete(key);
    }
    else {
        url.searchParams.set(key, value);
    }
    window.history.replaceState({}, '', url);
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
const __VLS_0 = {}.EditorShell;
/** @type {[typeof __VLS_components.EditorShell, typeof __VLS_components.EditorShell, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    title: (__VLS_ctx.$t('workspace.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    wideRightPanel: true,
}));
const __VLS_2 = __VLS_1({
    title: (__VLS_ctx.$t('workspace.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    wideRightPanel: true,
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
var __VLS_4 = {};
__VLS_3.slots.default;
{
    const { sidebar: __VLS_thisSlot } = __VLS_3.slots;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-3 p-2" },
    });
    const __VLS_5 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_6 = __VLS_asFunctionalComponent(__VLS_5, new __VLS_5({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.selectedProjectId ?? ''),
        options: (__VLS_ctx.projectOptions),
        label: (__VLS_ctx.$t('workspace.sidebar.projectLabel')),
    }));
    const __VLS_7 = __VLS_6({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.selectedProjectId ?? ''),
        options: (__VLS_ctx.projectOptions),
        label: (__VLS_ctx.$t('workspace.sidebar.projectLabel')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_6));
    let __VLS_9;
    let __VLS_10;
    let __VLS_11;
    const __VLS_12 = {
        'onUpdate:modelValue': ((v) => (__VLS_ctx.selectedProjectId = v ? String(v) : null))
    };
    var __VLS_8;
    if (__VLS_ctx.tenantProjects.error.value) {
        const __VLS_13 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_14 = __VLS_asFunctionalComponent(__VLS_13, new __VLS_13({
            variant: "error",
        }));
        const __VLS_15 = __VLS_14({
            variant: "error",
        }, ...__VLS_functionalComponentArgsRest(__VLS_14));
        __VLS_16.slots.default;
        (__VLS_ctx.tenantProjects.error.value);
        var __VLS_16;
    }
    if (!__VLS_ctx.tenantProjects.loading.value && __VLS_ctx.projectOptions.length === 0) {
        const __VLS_17 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_18 = __VLS_asFunctionalComponent(__VLS_17, new __VLS_17({
            headline: (__VLS_ctx.$t('workspace.sidebar.noProjectsHeadline')),
            body: (__VLS_ctx.$t('workspace.sidebar.noProjectsBody')),
        }));
        const __VLS_19 = __VLS_18({
            headline: (__VLS_ctx.$t('workspace.sidebar.noProjectsHeadline')),
            body: (__VLS_ctx.$t('workspace.sidebar.noProjectsBody')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_18));
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col h-full min-h-0" },
});
if (!__VLS_ctx.selectedProjectId) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 flex items-center justify-center" },
    });
    const __VLS_21 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_22 = __VLS_asFunctionalComponent(__VLS_21, new __VLS_21({
        headline: (__VLS_ctx.$t('workspace.empty.pickProjectHeadline')),
        body: (__VLS_ctx.$t('workspace.empty.pickProjectBody')),
    }));
    const __VLS_23 = __VLS_22({
        headline: (__VLS_ctx.$t('workspace.empty.pickProjectHeadline')),
        body: (__VLS_ctx.$t('workspace.empty.pickProjectBody')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_22));
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
    const __VLS_25 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_26 = __VLS_asFunctionalComponent(__VLS_25, new __VLS_25({
        variant: "error",
        ...{ class: "m-4" },
    }));
    const __VLS_27 = __VLS_26({
        variant: "error",
        ...{ class: "m-4" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_26));
    __VLS_28.slots.default;
    (__VLS_ctx.tree.error.value);
    var __VLS_28;
}
else if (__VLS_ctx.tree.root.value) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 min-h-0 overflow-auto p-2" },
    });
    /** @type {[typeof WorkspaceTreeNode, ]} */ ;
    // @ts-ignore
    const __VLS_29 = __VLS_asFunctionalComponent(WorkspaceTreeNode, new WorkspaceTreeNode({
        ...{ 'onToggle': {} },
        ...{ 'onSelectFile': {} },
        node: (__VLS_ctx.tree.root.value),
        expanded: (__VLS_ctx.tree.expanded),
        loadingPaths: (__VLS_ctx.tree.loadingPaths),
        selectedPath: (__VLS_ctx.selectedNode?.path ?? null),
        level: (0),
    }));
    const __VLS_30 = __VLS_29({
        ...{ 'onToggle': {} },
        ...{ 'onSelectFile': {} },
        node: (__VLS_ctx.tree.root.value),
        expanded: (__VLS_ctx.tree.expanded),
        loadingPaths: (__VLS_ctx.tree.loadingPaths),
        selectedPath: (__VLS_ctx.selectedNode?.path ?? null),
        level: (0),
    }, ...__VLS_functionalComponentArgsRest(__VLS_29));
    let __VLS_32;
    let __VLS_33;
    let __VLS_34;
    const __VLS_35 = {
        onToggle: (__VLS_ctx.onToggle)
    };
    const __VLS_36 = {
        onSelectFile: (__VLS_ctx.onSelectFile)
    };
    var __VLS_31;
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 flex items-center justify-center px-4" },
    });
    const __VLS_37 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_38 = __VLS_asFunctionalComponent(__VLS_37, new __VLS_37({
        headline: (__VLS_ctx.$t('workspace.empty.emptyTreeHeadline')),
        body: (__VLS_ctx.$t('workspace.empty.emptyTreeBody')),
    }));
    const __VLS_39 = __VLS_38({
        headline: (__VLS_ctx.$t('workspace.empty.emptyTreeHeadline')),
        body: (__VLS_ctx.$t('workspace.empty.emptyTreeBody')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_38));
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "p-2 border-t border-base-300 flex justify-between items-center text-xs opacity-60" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
(__VLS_ctx.$t('workspace.footer.podHint'));
if (__VLS_ctx.selectedProjectId) {
    const __VLS_41 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_42 = __VLS_asFunctionalComponent(__VLS_41, new __VLS_41({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }));
    const __VLS_43 = __VLS_42({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }, ...__VLS_functionalComponentArgsRest(__VLS_42));
    let __VLS_45;
    let __VLS_46;
    let __VLS_47;
    const __VLS_48 = {
        onClick: (...[$event]) => {
            if (!(__VLS_ctx.selectedProjectId))
                return;
            __VLS_ctx.selectedProjectId && __VLS_ctx.tree.loadRoot(__VLS_ctx.selectedProjectId);
        }
    };
    __VLS_44.slots.default;
    (__VLS_ctx.$t('workspace.footer.refresh'));
    var __VLS_44;
}
{
    const { 'right-panel': __VLS_thisSlot } = __VLS_3.slots;
    /** @type {[typeof WorkspaceFileViewer, ]} */ ;
    // @ts-ignore
    const __VLS_49 = __VLS_asFunctionalComponent(WorkspaceFileViewer, new WorkspaceFileViewer({
        name: (__VLS_ctx.selectedNode?.name ?? null),
        path: (__VLS_ctx.selectedNode?.path ?? null),
        loading: (__VLS_ctx.file.loading.value),
        error: (__VLS_ctx.file.error.value),
        result: (__VLS_ctx.file.result.value),
    }));
    const __VLS_50 = __VLS_49({
        name: (__VLS_ctx.selectedNode?.name ?? null),
        path: (__VLS_ctx.selectedNode?.path ?? null),
        loading: (__VLS_ctx.file.loading.value),
        error: (__VLS_ctx.file.error.value),
        result: (__VLS_ctx.file.result.value),
    }, ...__VLS_functionalComponentArgsRest(__VLS_49));
}
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-center']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['loading']} */ ;
/** @type {__VLS_StyleScopedClasses['loading-spinner']} */ ;
/** @type {__VLS_StyleScopedClasses['loading-md']} */ ;
/** @type {__VLS_StyleScopedClasses['m-4']} */ ;
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
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            EditorShell: EditorShell,
            VAlert: VAlert,
            VButton: VButton,
            VEmptyState: VEmptyState,
            VSelect: VSelect,
            WorkspaceTreeNode: WorkspaceTreeNode,
            WorkspaceFileViewer: WorkspaceFileViewer,
            tenantProjects: tenantProjects,
            tree: tree,
            file: file,
            selectedProjectId: selectedProjectId,
            selectedNode: selectedNode,
            projectOptions: projectOptions,
            breadcrumbs: breadcrumbs,
            onToggle: onToggle,
            onSelectFile: onSelectFile,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=WorkspaceApp.vue.js.map