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
// Sidebar (project selector + file tree) vs. main (editor) vs.
// right (validate panel). Same convention as other editors —
// clicking a row in the file tree pulls focus to main.
const focusZone = ref('main');
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
    ...{ 'onTitleClick': {} },
    focusZone: (__VLS_ctx.focusZone),
    title: "Script Cortex",
    fullHeight: (true),
    showSidebar: (true),
    showRightPanel: (!!__VLS_ctx.activeTab),
    focusModel: "auto",
    titleClickable: true,
    helpPath: "script-cortex.md",
}));
const __VLS_2 = __VLS_1({
    ...{ 'onTitleClick': {} },
    focusZone: (__VLS_ctx.focusZone),
    title: "Script Cortex",
    fullHeight: (true),
    showSidebar: (true),
    showRightPanel: (!!__VLS_ctx.activeTab),
    focusModel: "auto",
    titleClickable: true,
    helpPath: "script-cortex.md",
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
let __VLS_4;
let __VLS_5;
let __VLS_6;
const __VLS_7 = {
    onTitleClick: (...[$event]) => {
        __VLS_ctx.focusZone = 'sidebar';
    }
};
__VLS_3.slots.default;
{
    const { sidebar: __VLS_thisSlot } = __VLS_3.slots;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col h-full min-h-0" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "p-3 border-b border-base-300 shrink-0" },
    });
    if (__VLS_ctx.projectOptions.length > 0) {
        const __VLS_8 = {}.VSelect;
        /** @type {[typeof __VLS_components.VSelect, ]} */ ;
        // @ts-ignore
        const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
            modelValue: (__VLS_ctx.selectedProjectId),
            options: (__VLS_ctx.projectOptions),
            placeholder: "Select project…",
        }));
        const __VLS_10 = __VLS_9({
            modelValue: (__VLS_ctx.selectedProjectId),
            options: (__VLS_ctx.projectOptions),
            placeholder: "Select project…",
        }, ...__VLS_functionalComponentArgsRest(__VLS_9));
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 min-h-0 overflow-y-auto" },
    });
    if (__VLS_ctx.selectedProjectId) {
        /** @type {[typeof FileTreeSidebar, ]} */ ;
        // @ts-ignore
        const __VLS_12 = __VLS_asFunctionalComponent(FileTreeSidebar, new FileTreeSidebar({
            ...{ 'onOpenFile': {} },
            ...{ 'onNewFile': {} },
            ...{ 'onDeleteFile': {} },
            root: (__VLS_ctx.store.fileTree),
            activeFileId: (__VLS_ctx.store.activeTabId),
        }));
        const __VLS_13 = __VLS_12({
            ...{ 'onOpenFile': {} },
            ...{ 'onNewFile': {} },
            ...{ 'onDeleteFile': {} },
            root: (__VLS_ctx.store.fileTree),
            activeFileId: (__VLS_ctx.store.activeTabId),
        }, ...__VLS_functionalComponentArgsRest(__VLS_12));
        let __VLS_15;
        let __VLS_16;
        let __VLS_17;
        const __VLS_18 = {
            onOpenFile: ((id) => { __VLS_ctx.focusZone = 'main'; __VLS_ctx.store.openFile(id); })
        };
        const __VLS_19 = {
            onNewFile: (__VLS_ctx.onNew)
        };
        const __VLS_20 = {
            onDeleteFile: (__VLS_ctx.onDelete)
        };
        var __VLS_14;
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
const __VLS_21 = __VLS_asFunctionalComponent(EditorTabs, new EditorTabs({
    ...{ 'onSelect': {} },
    ...{ 'onClose': {} },
    tabs: (__VLS_ctx.store.openTabs),
    activeTabId: (__VLS_ctx.store.activeTabId),
}));
const __VLS_22 = __VLS_21({
    ...{ 'onSelect': {} },
    ...{ 'onClose': {} },
    tabs: (__VLS_ctx.store.openTabs),
    activeTabId: (__VLS_ctx.store.activeTabId),
}, ...__VLS_functionalComponentArgsRest(__VLS_21));
let __VLS_24;
let __VLS_25;
let __VLS_26;
const __VLS_27 = {
    onSelect: (__VLS_ctx.store.setActiveTab)
};
const __VLS_28 = {
    onClose: (__VLS_ctx.store.closeTab)
};
var __VLS_23;
if (!__VLS_ctx.activeTab) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 flex items-center justify-center" },
    });
    const __VLS_29 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_30 = __VLS_asFunctionalComponent(__VLS_29, new __VLS_29({
        headline: "No file open",
        body: "Pick a file from the left, or create a new one.",
    }));
    const __VLS_31 = __VLS_30({
        headline: "No file open",
        body: "Pick a file from the left, or create a new one.",
    }, ...__VLS_functionalComponentArgsRest(__VLS_30));
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
    const __VLS_33 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_34 = __VLS_asFunctionalComponent(__VLS_33, new __VLS_33({
        ...{ 'onClick': {} },
        size: "sm",
        loading: (__VLS_ctx.saving),
        disabled: (!__VLS_ctx.activeTab.dirty),
    }));
    const __VLS_35 = __VLS_34({
        ...{ 'onClick': {} },
        size: "sm",
        loading: (__VLS_ctx.saving),
        disabled: (!__VLS_ctx.activeTab.dirty),
    }, ...__VLS_functionalComponentArgsRest(__VLS_34));
    let __VLS_37;
    let __VLS_38;
    let __VLS_39;
    const __VLS_40 = {
        onClick: (__VLS_ctx.onSave)
    };
    __VLS_36.slots.default;
    var __VLS_36;
    if (__VLS_ctx.isExecutable) {
        const __VLS_41 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_42 = __VLS_asFunctionalComponent(__VLS_41, new __VLS_41({
            ...{ 'onClick': {} },
            size: "sm",
            variant: "primary",
        }));
        const __VLS_43 = __VLS_42({
            ...{ 'onClick': {} },
            size: "sm",
            variant: "primary",
        }, ...__VLS_functionalComponentArgsRest(__VLS_42));
        let __VLS_45;
        let __VLS_46;
        let __VLS_47;
        const __VLS_48 = {
            onClick: (__VLS_ctx.onExecute)
        };
        __VLS_44.slots.default;
        var __VLS_44;
    }
    const __VLS_49 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_50 = __VLS_asFunctionalComponent(__VLS_49, new __VLS_49({
        ...{ 'onClick': {} },
        size: "sm",
        variant: "ghost",
    }));
    const __VLS_51 = __VLS_50({
        ...{ 'onClick': {} },
        size: "sm",
        variant: "ghost",
    }, ...__VLS_functionalComponentArgsRest(__VLS_50));
    let __VLS_53;
    let __VLS_54;
    let __VLS_55;
    const __VLS_56 = {
        onClick: (__VLS_ctx.onOpenHactar)
    };
    __VLS_52.slots.default;
    var __VLS_52;
    if (__VLS_ctx.saveError) {
        const __VLS_57 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_58 = __VLS_asFunctionalComponent(__VLS_57, new __VLS_57({
            variant: "error",
            ...{ class: "m-2" },
        }));
        const __VLS_59 = __VLS_58({
            variant: "error",
            ...{ class: "m-2" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_58));
        __VLS_60.slots.default;
        (__VLS_ctx.saveError);
        var __VLS_60;
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 min-h-0 overflow-hidden" },
    });
    const __VLS_61 = {}.CodeEditor;
    /** @type {[typeof __VLS_components.CodeEditor, ]} */ ;
    // @ts-ignore
    const __VLS_62 = __VLS_asFunctionalComponent(__VLS_61, new __VLS_61({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.activeTab.inlineText),
        mimeType: (__VLS_ctx.editorMime),
    }));
    const __VLS_63 = __VLS_62({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.activeTab.inlineText),
        mimeType: (__VLS_ctx.editorMime),
    }, ...__VLS_functionalComponentArgsRest(__VLS_62));
    let __VLS_65;
    let __VLS_66;
    let __VLS_67;
    const __VLS_68 = {
        'onUpdate:modelValue': (__VLS_ctx.store.updateActiveContent)
    };
    var __VLS_64;
}
{
    const { 'right-panel': __VLS_thisSlot } = __VLS_3.slots;
    if (__VLS_ctx.activeTab) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "h-full overflow-y-auto" },
        });
        /** @type {[typeof ValidatePanel, ]} */ ;
        // @ts-ignore
        const __VLS_69 = __VLS_asFunctionalComponent(ValidatePanel, new ValidatePanel({
            file: (__VLS_ctx.activeTab),
        }));
        const __VLS_70 = __VLS_69({
            file: (__VLS_ctx.activeTab),
        }, ...__VLS_functionalComponentArgsRest(__VLS_69));
    }
}
var __VLS_3;
const __VLS_72 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_73 = __VLS_asFunctionalComponent(__VLS_72, new __VLS_72({
    modelValue: (__VLS_ctx.showCreate),
    title: "New file",
}));
const __VLS_74 = __VLS_73({
    modelValue: (__VLS_ctx.showCreate),
    title: "New file",
}, ...__VLS_functionalComponentArgsRest(__VLS_73));
__VLS_75.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "space-y-2 p-2" },
});
const __VLS_76 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_77 = __VLS_asFunctionalComponent(__VLS_76, new __VLS_76({
    modelValue: (__VLS_ctx.createPath),
    label: "Path",
    placeholder: "utils/sum.js",
}));
const __VLS_78 = __VLS_77({
    modelValue: (__VLS_ctx.createPath),
    label: "Path",
    placeholder: "utils/sum.js",
}, ...__VLS_functionalComponentArgsRest(__VLS_77));
if (__VLS_ctx.createError) {
    const __VLS_80 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_81 = __VLS_asFunctionalComponent(__VLS_80, new __VLS_80({
        variant: "error",
    }));
    const __VLS_82 = __VLS_81({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_81));
    __VLS_83.slots.default;
    (__VLS_ctx.createError);
    var __VLS_83;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex justify-end gap-2 pt-2" },
});
const __VLS_84 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_85 = __VLS_asFunctionalComponent(__VLS_84, new __VLS_84({
    ...{ 'onClick': {} },
    variant: "ghost",
}));
const __VLS_86 = __VLS_85({
    ...{ 'onClick': {} },
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_85));
let __VLS_88;
let __VLS_89;
let __VLS_90;
const __VLS_91 = {
    onClick: (...[$event]) => {
        __VLS_ctx.showCreate = false;
    }
};
__VLS_87.slots.default;
var __VLS_87;
const __VLS_92 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_93 = __VLS_asFunctionalComponent(__VLS_92, new __VLS_92({
    ...{ 'onClick': {} },
    variant: "primary",
    loading: (__VLS_ctx.creating),
}));
const __VLS_94 = __VLS_93({
    ...{ 'onClick': {} },
    variant: "primary",
    loading: (__VLS_ctx.creating),
}, ...__VLS_functionalComponentArgsRest(__VLS_93));
let __VLS_96;
let __VLS_97;
let __VLS_98;
const __VLS_99 = {
    onClick: (__VLS_ctx.confirmCreate)
};
__VLS_95.slots.default;
var __VLS_95;
var __VLS_75;
if (__VLS_ctx.showExecuteDialog && __VLS_ctx.activeTab && __VLS_ctx.selectedProjectId) {
    /** @type {[typeof ExecutionDialog, ]} */ ;
    // @ts-ignore
    const __VLS_100 = __VLS_asFunctionalComponent(ExecutionDialog, new ExecutionDialog({
        ...{ 'onClose': {} },
        file: (__VLS_ctx.activeTab),
        projectId: (__VLS_ctx.selectedProjectId),
    }));
    const __VLS_101 = __VLS_100({
        ...{ 'onClose': {} },
        file: (__VLS_ctx.activeTab),
        projectId: (__VLS_ctx.selectedProjectId),
    }, ...__VLS_functionalComponentArgsRest(__VLS_100));
    let __VLS_103;
    let __VLS_104;
    let __VLS_105;
    const __VLS_106 = {
        onClose: (...[$event]) => {
            if (!(__VLS_ctx.showExecuteDialog && __VLS_ctx.activeTab && __VLS_ctx.selectedProjectId))
                return;
            __VLS_ctx.showExecuteDialog = false;
        }
    };
    var __VLS_102;
}
if (__VLS_ctx.showHactar && __VLS_ctx.selectedProjectId) {
    /** @type {[typeof HactarPanel, ]} */ ;
    // @ts-ignore
    const __VLS_107 = __VLS_asFunctionalComponent(HactarPanel, new HactarPanel({
        ...{ 'onClose': {} },
        ...{ 'onApply': {} },
        file: (__VLS_ctx.activeTab),
        projectId: (__VLS_ctx.selectedProjectId),
    }));
    const __VLS_108 = __VLS_107({
        ...{ 'onClose': {} },
        ...{ 'onApply': {} },
        file: (__VLS_ctx.activeTab),
        projectId: (__VLS_ctx.selectedProjectId),
    }, ...__VLS_functionalComponentArgsRest(__VLS_107));
    let __VLS_110;
    let __VLS_111;
    let __VLS_112;
    const __VLS_113 = {
        onClose: (...[$event]) => {
            if (!(__VLS_ctx.showHactar && __VLS_ctx.selectedProjectId))
                return;
            __VLS_ctx.showHactar = false;
        }
    };
    const __VLS_114 = {
        onApply: (__VLS_ctx.onHactarApplied)
    };
    var __VLS_109;
}
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
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
            focusZone: focusZone,
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