import { computed, onMounted, ref, watch } from 'vue';
import { CodeEditor, EditorShell, VAlert, VButton, VEmptyState, VInput, VModal, VSelect, } from '@/components';
import { useTenantProjects } from '@/composables/useTenantProjects';
import { useScriptStore } from './stores/scriptStore';
import FileTreeSidebar from './components/FileTreeSidebar.vue';
import EditorTabs from './components/EditorTabs.vue';
import ValidatePanel from './components/ValidatePanel.vue';
import ExecutionDialog from './components/ExecutionDialog.vue';
import HactarPanel from './components/HactarPanel.vue';
const projectsState = useTenantProjects();
const store = useScriptStore();
const selectedProjectId = ref(null);
const showCreate = ref(false);
const createPath = ref('');
const createError = ref(null);
const creating = ref(false);
const saving = ref(false);
const saveError = ref(null);
const showExecuteDialog = ref(false);
const showHactar = ref(false);
onMounted(async () => {
    await projectsState.reload();
    const params = new URLSearchParams(window.location.search);
    const fromUrl = params.get('projectId');
    if (fromUrl) {
        selectedProjectId.value = fromUrl;
    }
    else if (projectsState.projects.value.length === 1) {
        selectedProjectId.value = projectsState.projects.value[0].name;
    }
});
watch(selectedProjectId, async (pid) => {
    if (!pid)
        return;
    const url = new URL(window.location.href);
    url.searchParams.set('projectId', pid);
    window.history.replaceState(null, '', url.toString());
    await store.loadList(pid);
});
const activeTab = computed(() => store.activeTab);
const editorMime = computed(() => {
    const t = activeTab.value;
    if (!t)
        return 'text/javascript';
    if (t.mimeType)
        return t.mimeType;
    // Derive from path if missing.
    const lower = t.path.toLowerCase();
    if (lower.endsWith('.js') || lower.endsWith('.mjs'))
        return 'text/javascript';
    if (lower.endsWith('.json'))
        return 'application/json';
    if (lower.endsWith('.md'))
        return 'text/markdown';
    if (lower.endsWith('.yml') || lower.endsWith('.yaml'))
        return 'application/yaml';
    return 'text/plain';
});
const isExecutable = computed(() => {
    const t = activeTab.value;
    if (!t)
        return false;
    const lower = t.path.toLowerCase();
    return lower.endsWith('.js') || lower.endsWith('.mjs');
});
async function onSave() {
    saving.value = true;
    saveError.value = null;
    try {
        await store.saveActive();
    }
    catch (e) {
        saveError.value = e instanceof Error ? e.message : 'Save failed';
    }
    finally {
        saving.value = false;
    }
}
function onNew(parentPath) {
    // Default to scripts/ when the user clicks "+ new" at the root —
    // the most common case, but they're free to delete it and write
    // any other project-relative path (e.g. skills/myskill/foo.js,
    // documents/, etc.).
    createPath.value = parentPath ? `${parentPath}/` : 'scripts/';
    createError.value = null;
    showCreate.value = true;
}
async function confirmCreate() {
    if (!createPath.value.trim()) {
        createError.value = 'Path required';
        return;
    }
    creating.value = true;
    createError.value = null;
    try {
        await store.createFile({
            path: createPath.value.trim(),
            inlineText: '',
        });
        showCreate.value = false;
    }
    catch (e) {
        createError.value = e instanceof Error ? e.message : 'Create failed';
    }
    finally {
        creating.value = false;
    }
}
async function onDelete(id) {
    if (!confirm('Delete this file?'))
        return;
    await store.deleteFile(id);
}
function onExecute() {
    if (!activeTab.value)
        return;
    showExecuteDialog.value = true;
}
function onOpenHactar() {
    showHactar.value = true;
}
function onHactarApplied(code) {
    if (!activeTab.value)
        return;
    store.updateActiveContent(code);
    showHactar.value = false;
}
const projectOptions = computed(() => projectsState.projects.value.map((p) => ({ value: p.name, label: p.title ?? p.name })));
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
const __VLS_0 = {}.EditorShell;
/** @type {[typeof __VLS_components.EditorShell, typeof __VLS_components.EditorShell, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    title: "Script Cortex",
    fullHeight: true,
    helpPath: "script-cortex.md",
}));
const __VLS_2 = __VLS_1({
    title: "Script Cortex",
    fullHeight: true,
    helpPath: "script-cortex.md",
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
__VLS_3.slots.default;
{
    const { 'topbar-extra': __VLS_thisSlot } = __VLS_3.slots;
    if (__VLS_ctx.projectOptions.length > 0) {
        const __VLS_4 = {}.VSelect;
        /** @type {[typeof __VLS_components.VSelect, ]} */ ;
        // @ts-ignore
        const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
            modelValue: (__VLS_ctx.selectedProjectId),
            options: (__VLS_ctx.projectOptions),
            placeholder: "Select project…",
        }));
        const __VLS_6 = __VLS_5({
            modelValue: (__VLS_ctx.selectedProjectId),
            options: (__VLS_ctx.projectOptions),
            placeholder: "Select project…",
        }, ...__VLS_functionalComponentArgsRest(__VLS_5));
    }
}
{
    const { sidebar: __VLS_thisSlot } = __VLS_3.slots;
    if (__VLS_ctx.selectedProjectId) {
        /** @type {[typeof FileTreeSidebar, ]} */ ;
        // @ts-ignore
        const __VLS_8 = __VLS_asFunctionalComponent(FileTreeSidebar, new FileTreeSidebar({
            ...{ 'onOpenFile': {} },
            ...{ 'onNewFile': {} },
            ...{ 'onDeleteFile': {} },
            root: (__VLS_ctx.store.fileTree),
            activeFileId: (__VLS_ctx.store.activeTabId),
        }));
        const __VLS_9 = __VLS_8({
            ...{ 'onOpenFile': {} },
            ...{ 'onNewFile': {} },
            ...{ 'onDeleteFile': {} },
            root: (__VLS_ctx.store.fileTree),
            activeFileId: (__VLS_ctx.store.activeTabId),
        }, ...__VLS_functionalComponentArgsRest(__VLS_8));
        let __VLS_11;
        let __VLS_12;
        let __VLS_13;
        const __VLS_14 = {
            onOpenFile: (__VLS_ctx.store.openFile)
        };
        const __VLS_15 = {
            onNewFile: (__VLS_ctx.onNew)
        };
        const __VLS_16 = {
            onDeleteFile: (__VLS_ctx.onDelete)
        };
        var __VLS_10;
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "p-3 text-sm opacity-60" },
        });
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col h-full min-h-0" },
});
/** @type {[typeof EditorTabs, ]} */ ;
// @ts-ignore
const __VLS_17 = __VLS_asFunctionalComponent(EditorTabs, new EditorTabs({
    ...{ 'onSelect': {} },
    ...{ 'onClose': {} },
    tabs: (__VLS_ctx.store.openTabs),
    activeTabId: (__VLS_ctx.store.activeTabId),
}));
const __VLS_18 = __VLS_17({
    ...{ 'onSelect': {} },
    ...{ 'onClose': {} },
    tabs: (__VLS_ctx.store.openTabs),
    activeTabId: (__VLS_ctx.store.activeTabId),
}, ...__VLS_functionalComponentArgsRest(__VLS_17));
let __VLS_20;
let __VLS_21;
let __VLS_22;
const __VLS_23 = {
    onSelect: (__VLS_ctx.store.setActiveTab)
};
const __VLS_24 = {
    onClose: (__VLS_ctx.store.closeTab)
};
var __VLS_19;
if (!__VLS_ctx.activeTab) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 flex items-center justify-center" },
    });
    const __VLS_25 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_26 = __VLS_asFunctionalComponent(__VLS_25, new __VLS_25({
        headline: "No file open",
        body: "Pick a file from the left, or create a new one.",
    }));
    const __VLS_27 = __VLS_26({
        headline: "No file open",
        body: "Pick a file from the left, or create a new one.",
    }, ...__VLS_functionalComponentArgsRest(__VLS_26));
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 flex flex-col min-h-0" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center gap-2 px-3 py-2 border-b border-base-300 bg-base-100 text-sm" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "font-mono opacity-80 truncate" },
    });
    (__VLS_ctx.activeTab.path);
    if (__VLS_ctx.activeTab.dirty) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "opacity-60" },
        });
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
        ...{ class: "flex-1" },
    });
    const __VLS_29 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_30 = __VLS_asFunctionalComponent(__VLS_29, new __VLS_29({
        ...{ 'onClick': {} },
        size: "sm",
        loading: (__VLS_ctx.saving),
        disabled: (!__VLS_ctx.activeTab.dirty),
    }));
    const __VLS_31 = __VLS_30({
        ...{ 'onClick': {} },
        size: "sm",
        loading: (__VLS_ctx.saving),
        disabled: (!__VLS_ctx.activeTab.dirty),
    }, ...__VLS_functionalComponentArgsRest(__VLS_30));
    let __VLS_33;
    let __VLS_34;
    let __VLS_35;
    const __VLS_36 = {
        onClick: (__VLS_ctx.onSave)
    };
    __VLS_32.slots.default;
    var __VLS_32;
    if (__VLS_ctx.isExecutable) {
        const __VLS_37 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_38 = __VLS_asFunctionalComponent(__VLS_37, new __VLS_37({
            ...{ 'onClick': {} },
            size: "sm",
            variant: "primary",
        }));
        const __VLS_39 = __VLS_38({
            ...{ 'onClick': {} },
            size: "sm",
            variant: "primary",
        }, ...__VLS_functionalComponentArgsRest(__VLS_38));
        let __VLS_41;
        let __VLS_42;
        let __VLS_43;
        const __VLS_44 = {
            onClick: (__VLS_ctx.onExecute)
        };
        __VLS_40.slots.default;
        var __VLS_40;
    }
    const __VLS_45 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_46 = __VLS_asFunctionalComponent(__VLS_45, new __VLS_45({
        ...{ 'onClick': {} },
        size: "sm",
        variant: "ghost",
    }));
    const __VLS_47 = __VLS_46({
        ...{ 'onClick': {} },
        size: "sm",
        variant: "ghost",
    }, ...__VLS_functionalComponentArgsRest(__VLS_46));
    let __VLS_49;
    let __VLS_50;
    let __VLS_51;
    const __VLS_52 = {
        onClick: (__VLS_ctx.onOpenHactar)
    };
    __VLS_48.slots.default;
    var __VLS_48;
    if (__VLS_ctx.saveError) {
        const __VLS_53 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_54 = __VLS_asFunctionalComponent(__VLS_53, new __VLS_53({
            variant: "error",
            ...{ class: "m-2" },
        }));
        const __VLS_55 = __VLS_54({
            variant: "error",
            ...{ class: "m-2" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_54));
        __VLS_56.slots.default;
        (__VLS_ctx.saveError);
        var __VLS_56;
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 min-h-0 overflow-hidden" },
    });
    const __VLS_57 = {}.CodeEditor;
    /** @type {[typeof __VLS_components.CodeEditor, ]} */ ;
    // @ts-ignore
    const __VLS_58 = __VLS_asFunctionalComponent(__VLS_57, new __VLS_57({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.activeTab.inlineText),
        mimeType: (__VLS_ctx.editorMime),
    }));
    const __VLS_59 = __VLS_58({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.activeTab.inlineText),
        mimeType: (__VLS_ctx.editorMime),
    }, ...__VLS_functionalComponentArgsRest(__VLS_58));
    let __VLS_61;
    let __VLS_62;
    let __VLS_63;
    const __VLS_64 = {
        'onUpdate:modelValue': (__VLS_ctx.store.updateActiveContent)
    };
    var __VLS_60;
}
{
    const { 'right-panel': __VLS_thisSlot } = __VLS_3.slots;
    if (__VLS_ctx.activeTab) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "h-full overflow-y-auto" },
        });
        /** @type {[typeof ValidatePanel, ]} */ ;
        // @ts-ignore
        const __VLS_65 = __VLS_asFunctionalComponent(ValidatePanel, new ValidatePanel({
            file: (__VLS_ctx.activeTab),
        }));
        const __VLS_66 = __VLS_65({
            file: (__VLS_ctx.activeTab),
        }, ...__VLS_functionalComponentArgsRest(__VLS_65));
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "p-3 text-sm opacity-60" },
        });
    }
}
var __VLS_3;
const __VLS_68 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_69 = __VLS_asFunctionalComponent(__VLS_68, new __VLS_68({
    modelValue: (__VLS_ctx.showCreate),
    title: "New file",
}));
const __VLS_70 = __VLS_69({
    modelValue: (__VLS_ctx.showCreate),
    title: "New file",
}, ...__VLS_functionalComponentArgsRest(__VLS_69));
__VLS_71.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "space-y-2 p-2" },
});
const __VLS_72 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_73 = __VLS_asFunctionalComponent(__VLS_72, new __VLS_72({
    modelValue: (__VLS_ctx.createPath),
    label: "Path",
    placeholder: "utils/sum.js",
}));
const __VLS_74 = __VLS_73({
    modelValue: (__VLS_ctx.createPath),
    label: "Path",
    placeholder: "utils/sum.js",
}, ...__VLS_functionalComponentArgsRest(__VLS_73));
if (__VLS_ctx.createError) {
    const __VLS_76 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_77 = __VLS_asFunctionalComponent(__VLS_76, new __VLS_76({
        variant: "error",
    }));
    const __VLS_78 = __VLS_77({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_77));
    __VLS_79.slots.default;
    (__VLS_ctx.createError);
    var __VLS_79;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex justify-end gap-2 pt-2" },
});
const __VLS_80 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_81 = __VLS_asFunctionalComponent(__VLS_80, new __VLS_80({
    ...{ 'onClick': {} },
    variant: "ghost",
}));
const __VLS_82 = __VLS_81({
    ...{ 'onClick': {} },
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_81));
let __VLS_84;
let __VLS_85;
let __VLS_86;
const __VLS_87 = {
    onClick: (...[$event]) => {
        __VLS_ctx.showCreate = false;
    }
};
__VLS_83.slots.default;
var __VLS_83;
const __VLS_88 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_89 = __VLS_asFunctionalComponent(__VLS_88, new __VLS_88({
    ...{ 'onClick': {} },
    variant: "primary",
    loading: (__VLS_ctx.creating),
}));
const __VLS_90 = __VLS_89({
    ...{ 'onClick': {} },
    variant: "primary",
    loading: (__VLS_ctx.creating),
}, ...__VLS_functionalComponentArgsRest(__VLS_89));
let __VLS_92;
let __VLS_93;
let __VLS_94;
const __VLS_95 = {
    onClick: (__VLS_ctx.confirmCreate)
};
__VLS_91.slots.default;
var __VLS_91;
var __VLS_71;
if (__VLS_ctx.showExecuteDialog && __VLS_ctx.activeTab && __VLS_ctx.selectedProjectId) {
    /** @type {[typeof ExecutionDialog, ]} */ ;
    // @ts-ignore
    const __VLS_96 = __VLS_asFunctionalComponent(ExecutionDialog, new ExecutionDialog({
        ...{ 'onClose': {} },
        file: (__VLS_ctx.activeTab),
        projectId: (__VLS_ctx.selectedProjectId),
    }));
    const __VLS_97 = __VLS_96({
        ...{ 'onClose': {} },
        file: (__VLS_ctx.activeTab),
        projectId: (__VLS_ctx.selectedProjectId),
    }, ...__VLS_functionalComponentArgsRest(__VLS_96));
    let __VLS_99;
    let __VLS_100;
    let __VLS_101;
    const __VLS_102 = {
        onClose: (...[$event]) => {
            if (!(__VLS_ctx.showExecuteDialog && __VLS_ctx.activeTab && __VLS_ctx.selectedProjectId))
                return;
            __VLS_ctx.showExecuteDialog = false;
        }
    };
    var __VLS_98;
}
if (__VLS_ctx.showHactar && __VLS_ctx.selectedProjectId) {
    /** @type {[typeof HactarPanel, ]} */ ;
    // @ts-ignore
    const __VLS_103 = __VLS_asFunctionalComponent(HactarPanel, new HactarPanel({
        ...{ 'onClose': {} },
        ...{ 'onApply': {} },
        file: (__VLS_ctx.activeTab),
        projectId: (__VLS_ctx.selectedProjectId),
    }));
    const __VLS_104 = __VLS_103({
        ...{ 'onClose': {} },
        ...{ 'onApply': {} },
        file: (__VLS_ctx.activeTab),
        projectId: (__VLS_ctx.selectedProjectId),
    }, ...__VLS_functionalComponentArgsRest(__VLS_103));
    let __VLS_106;
    let __VLS_107;
    let __VLS_108;
    const __VLS_109 = {
        onClose: (...[$event]) => {
            if (!(__VLS_ctx.showHactar && __VLS_ctx.selectedProjectId))
                return;
            __VLS_ctx.showHactar = false;
        }
    };
    const __VLS_110 = {
        onApply: (__VLS_ctx.onHactarApplied)
    };
    var __VLS_105;
}
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
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
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['m-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['space-y-2']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['pt-2']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            CodeEditor: CodeEditor,
            EditorShell: EditorShell,
            VAlert: VAlert,
            VButton: VButton,
            VEmptyState: VEmptyState,
            VInput: VInput,
            VModal: VModal,
            VSelect: VSelect,
            FileTreeSidebar: FileTreeSidebar,
            EditorTabs: EditorTabs,
            ValidatePanel: ValidatePanel,
            ExecutionDialog: ExecutionDialog,
            HactarPanel: HactarPanel,
            store: store,
            selectedProjectId: selectedProjectId,
            showCreate: showCreate,
            createPath: createPath,
            createError: createError,
            creating: creating,
            saving: saving,
            saveError: saveError,
            showExecuteDialog: showExecuteDialog,
            showHactar: showHactar,
            activeTab: activeTab,
            editorMime: editorMime,
            isExecutable: isExecutable,
            onSave: onSave,
            onNew: onNew,
            confirmCreate: confirmCreate,
            onDelete: onDelete,
            onExecute: onExecute,
            onOpenHactar: onOpenHactar,
            onHactarApplied: onHactarApplied,
            projectOptions: projectOptions,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=ScriptCortexApp.vue.js.map