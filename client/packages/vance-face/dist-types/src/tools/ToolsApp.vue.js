import { computed, onMounted, reactive, ref, watch } from 'vue';
import { CodeEditor, EditorShell, VAlert, VButton, VCard, VCheckbox, VEmptyState, VInput, VModal, VSelect, VTextarea, } from '@/components';
import { useTenantProjects } from '@/composables/useTenantProjects';
import { useAdminServerTools } from '@/composables/useAdminServerTools';
const VANCE_PROJECT = '_vance';
const NAME_PATTERN = /^[a-z0-9][a-z0-9_]*$/;
const tenantProjects = useTenantProjects();
const toolsState = useAdminServerTools();
// ─── Project selection ──────────────────────────────────────────────────
const selectedProject = ref(VANCE_PROJECT);
const selectedName = ref(null);
const projectOptions = computed(() => {
    const list = [
        { value: VANCE_PROJECT, label: '_vance — system defaults' },
    ];
    for (const p of tenantProjects.projects.value) {
        if (p.name === VANCE_PROJECT)
            continue;
        list.push({
            value: p.name,
            label: (p.title ? p.title + ' ' : '') + '(' + p.name + ')',
            group: 'Projects',
        });
    }
    return list;
});
const isVanceProject = computed(() => selectedProject.value === VANCE_PROJECT);
// ─── Form state ─────────────────────────────────────────────────────────
const form = reactive({
    type: '',
    description: '',
    parametersJson: '{}',
    labelsText: '',
    enabled: true,
    primary: false,
});
const banner = ref(null);
const formError = ref(null);
// ─── New-tool modal ─────────────────────────────────────────────────────
const showNewModal = ref(false);
const newName = ref('');
const newType = ref('');
const newError = ref(null);
// ─── Derived ────────────────────────────────────────────────────────────
const selectedTool = computed(() => selectedName.value
    ? toolsState.tools.value.find(t => t.name === selectedName.value) ?? null
    : null);
const typeOptions = computed(() => toolsState.types.value.map(t => ({
    value: t.typeId,
    label: t.typeId,
})));
const selectedTypeSchema = computed(() => {
    const id = form.type || selectedTool.value?.type || '';
    if (!id)
        return null;
    return toolsState.types.value.find(t => t.typeId === id) ?? null;
});
const breadcrumbs = computed(() => {
    const projectLabel = isVanceProject.value
        ? '_vance (system)'
        : 'Project: ' + selectedProject.value;
    return selectedName.value
        ? [projectLabel, selectedName.value]
        : [projectLabel];
});
const combinedError = computed(() => toolsState.error.value || tenantProjects.error.value);
// ─── Lifecycle ──────────────────────────────────────────────────────────
onMounted(async () => {
    await Promise.all([
        tenantProjects.reload(),
        toolsState.loadTypes(),
        toolsState.loadProject(selectedProject.value),
    ]);
});
watch(selectedProject, async (pid) => {
    selectedName.value = null;
    resetForm();
    await toolsState.loadProject(pid);
});
watch(selectedName, () => {
    banner.value = null;
    formError.value = null;
    if (!selectedTool.value) {
        resetForm();
        return;
    }
    populateForm(selectedTool.value);
});
// ─── Form helpers ───────────────────────────────────────────────────────
function resetForm() {
    form.type = '';
    form.description = '';
    form.parametersJson = '{}';
    form.labelsText = '';
    form.enabled = true;
    form.primary = false;
}
function populateForm(t) {
    form.type = t.type;
    form.description = t.description;
    form.parametersJson = formatJson(t.parameters ?? {});
    form.labelsText = (t.labels ?? []).join('\n');
    form.enabled = t.enabled;
    form.primary = t.primary;
}
function formatJson(obj) {
    try {
        return JSON.stringify(obj, null, 2);
    }
    catch {
        return '{}';
    }
}
function buildWriteRequest() {
    const type = form.type.trim();
    const description = form.description.trim();
    if (!type) {
        formError.value = 'Type is required.';
        return null;
    }
    if (!description) {
        formError.value = 'Description is required.';
        return null;
    }
    let parameters;
    try {
        const parsed = JSON.parse(form.parametersJson || '{}');
        if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
            formError.value = 'Parameters must be a JSON object.';
            return null;
        }
        parameters = parsed;
    }
    catch (e) {
        formError.value = 'Parameters: invalid JSON — ' + (e instanceof Error ? e.message : 'parse error');
        return null;
    }
    const labels = splitLines(form.labelsText);
    return {
        type,
        description,
        parameters,
        labels,
        enabled: form.enabled,
        primary: form.primary,
    };
}
function splitLines(s) {
    return s
        .split(/[\n,]/)
        .map(x => x.trim())
        .filter(x => x.length > 0);
}
// ─── Save / Delete ──────────────────────────────────────────────────────
async function save() {
    if (!selectedName.value)
        return;
    formError.value = null;
    banner.value = null;
    const req = buildWriteRequest();
    if (!req)
        return;
    try {
        await toolsState.upsert(selectedProject.value, selectedName.value, req);
        banner.value = 'Saved.';
    }
    catch {
        /* error in toolsState.error */
    }
}
async function deleteTool() {
    if (!selectedName.value)
        return;
    if (!confirm(`Delete server tool "${selectedName.value}"? Bundled bean tools with the same name will become visible again through the cascade fallback.`))
        return;
    try {
        await toolsState.remove(selectedProject.value, selectedName.value);
        selectedName.value = null;
        banner.value = 'Deleted.';
    }
    catch {
        /* error */
    }
}
// ─── New-tool modal ─────────────────────────────────────────────────────
function openNewTool() {
    newName.value = '';
    newType.value = toolsState.types.value[0]?.typeId ?? '';
    newError.value = null;
    showNewModal.value = true;
}
async function submitNewTool() {
    newError.value = null;
    const name = newName.value.trim();
    const type = newType.value.trim();
    if (!name) {
        newError.value = 'Name is required.';
        return;
    }
    if (!NAME_PATTERN.test(name)) {
        newError.value = 'Name must be lower-case alphanumerics with optional "_" (snake_case).';
        return;
    }
    if (toolsState.tools.value.some(t => t.name === name)) {
        newError.value = `A tool named "${name}" already exists in this project.`;
        return;
    }
    if (!type) {
        newError.value = 'Type is required.';
        return;
    }
    const stub = {
        type,
        description: name + ' — TODO description',
        parameters: {},
        labels: [],
        enabled: true,
        primary: false,
    };
    try {
        await toolsState.upsert(selectedProject.value, name, stub);
        showNewModal.value = false;
        selectedName.value = name;
        banner.value = `Created tool "${name}". Fill in the parameters and description.`;
    }
    catch (e) {
        newError.value = e instanceof Error ? e.message : 'Failed to create tool.';
    }
}
// ─── Sidebar selection ──────────────────────────────────────────────────
function selectTool(name) {
    selectedName.value = name;
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['tool-item']} */ ;
// CSS variable injection 
// CSS variable injection end 
const __VLS_0 = {}.EditorShell;
/** @type {[typeof __VLS_components.EditorShell, typeof __VLS_components.EditorShell, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    title: "Server Tools",
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    wideRightPanel: true,
}));
const __VLS_2 = __VLS_1({
    title: "Server Tools",
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    wideRightPanel: true,
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
var __VLS_4 = {};
__VLS_3.slots.default;
{
    const { 'topbar-extra': __VLS_thisSlot } = __VLS_3.slots;
    const __VLS_5 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_6 = __VLS_asFunctionalComponent(__VLS_5, new __VLS_5({
        modelValue: (__VLS_ctx.selectedProject),
        options: (__VLS_ctx.projectOptions),
    }));
    const __VLS_7 = __VLS_6({
        modelValue: (__VLS_ctx.selectedProject),
        options: (__VLS_ctx.projectOptions),
    }, ...__VLS_functionalComponentArgsRest(__VLS_6));
}
{
    const { sidebar: __VLS_thisSlot } = __VLS_3.slots;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.nav, __VLS_intrinsicElements.nav)({
        ...{ class: "flex flex-col gap-1 p-2" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center justify-between px-2 mb-1" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-xs uppercase opacity-50" },
    });
    (__VLS_ctx.isVanceProject ? '_vance (system)' : __VLS_ctx.selectedProject);
    const __VLS_9 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_10 = __VLS_asFunctionalComponent(__VLS_9, new __VLS_9({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }));
    const __VLS_11 = __VLS_10({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }, ...__VLS_functionalComponentArgsRest(__VLS_10));
    let __VLS_13;
    let __VLS_14;
    let __VLS_15;
    const __VLS_16 = {
        onClick: (__VLS_ctx.openNewTool)
    };
    __VLS_12.slots.default;
    var __VLS_12;
    if (__VLS_ctx.toolsState.loading.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "px-2 text-xs opacity-60" },
        });
    }
    else if (__VLS_ctx.toolsState.tools.value.length === 0) {
        const __VLS_17 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_18 = __VLS_asFunctionalComponent(__VLS_17, new __VLS_17({
            headline: "No tools",
            body: "Click + New to create one.",
        }));
        const __VLS_19 = __VLS_18({
            headline: "No tools",
            body: "Click + New to create one.",
        }, ...__VLS_functionalComponentArgsRest(__VLS_18));
    }
    for (const [t] of __VLS_getVForSourceType((__VLS_ctx.toolsState.tools.value))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    __VLS_ctx.selectTool(t.name);
                } },
            key: (t.name),
            ...{ class: "tool-item" },
            ...{ class: ({ 'tool-item--active': __VLS_ctx.selectedName === t.name }) },
            type: "button",
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center justify-between gap-2" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "font-mono text-sm truncate" },
        });
        (t.name);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-xs px-1.5 py-0.5 rounded badge-type" },
        });
        (t.type);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center gap-2 text-xs opacity-60" },
        });
        if (!t.enabled) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "badge-disabled" },
            });
        }
        if (t.primary) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "badge-primary" },
            });
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "truncate" },
        });
        (t.description);
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "p-6 flex flex-col gap-3 max-w-4xl" },
});
if (__VLS_ctx.combinedError) {
    const __VLS_21 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_22 = __VLS_asFunctionalComponent(__VLS_21, new __VLS_21({
        variant: "error",
    }));
    const __VLS_23 = __VLS_22({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_22));
    __VLS_24.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.combinedError);
    var __VLS_24;
}
if (__VLS_ctx.banner) {
    const __VLS_25 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_26 = __VLS_asFunctionalComponent(__VLS_25, new __VLS_25({
        variant: "success",
    }));
    const __VLS_27 = __VLS_26({
        variant: "success",
    }, ...__VLS_functionalComponentArgsRest(__VLS_26));
    __VLS_28.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.banner);
    var __VLS_28;
}
if (__VLS_ctx.formError) {
    const __VLS_29 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_30 = __VLS_asFunctionalComponent(__VLS_29, new __VLS_29({
        variant: "error",
    }));
    const __VLS_31 = __VLS_30({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_30));
    __VLS_32.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.formError);
    var __VLS_32;
}
if (!__VLS_ctx.selectedTool) {
    const __VLS_33 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_34 = __VLS_asFunctionalComponent(__VLS_33, new __VLS_33({
        headline: "Select a tool",
        body: "Pick one from the list, or click + New to create one in this project.",
    }));
    const __VLS_35 = __VLS_34({
        headline: "Select a tool",
        body: "Pick one from the list, or click + New to create one in this project.",
    }, ...__VLS_functionalComponentArgsRest(__VLS_34));
}
else {
    const __VLS_37 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_38 = __VLS_asFunctionalComponent(__VLS_37, new __VLS_37({}));
    const __VLS_39 = __VLS_38({}, ...__VLS_functionalComponentArgsRest(__VLS_38));
    __VLS_40.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center justify-between gap-3" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "font-mono text-lg" },
    });
    (__VLS_ctx.selectedTool.name);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-70" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
    (__VLS_ctx.selectedProject);
    if (__VLS_ctx.selectedTool.updatedAtTimestamp) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (new Date(__VLS_ctx.selectedTool.updatedAtTimestamp).toLocaleString());
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex gap-2" },
    });
    const __VLS_41 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_42 = __VLS_asFunctionalComponent(__VLS_41, new __VLS_41({
        ...{ 'onClick': {} },
        variant: "danger",
        loading: (__VLS_ctx.toolsState.busy.value),
    }));
    const __VLS_43 = __VLS_42({
        ...{ 'onClick': {} },
        variant: "danger",
        loading: (__VLS_ctx.toolsState.busy.value),
    }, ...__VLS_functionalComponentArgsRest(__VLS_42));
    let __VLS_45;
    let __VLS_46;
    let __VLS_47;
    const __VLS_48 = {
        onClick: (__VLS_ctx.deleteTool)
    };
    __VLS_44.slots.default;
    var __VLS_44;
    const __VLS_49 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_50 = __VLS_asFunctionalComponent(__VLS_49, new __VLS_49({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.toolsState.busy.value),
    }));
    const __VLS_51 = __VLS_50({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.toolsState.busy.value),
    }, ...__VLS_functionalComponentArgsRest(__VLS_50));
    let __VLS_53;
    let __VLS_54;
    let __VLS_55;
    const __VLS_56 = {
        onClick: (__VLS_ctx.save)
    };
    __VLS_52.slots.default;
    var __VLS_52;
    if (__VLS_ctx.isVanceProject) {
        const __VLS_57 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_58 = __VLS_asFunctionalComponent(__VLS_57, new __VLS_57({
            variant: "info",
            ...{ class: "mt-3" },
        }));
        const __VLS_59 = __VLS_58({
            variant: "info",
            ...{ class: "mt-3" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_58));
        __VLS_60.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
        var __VLS_60;
    }
    var __VLS_40;
    const __VLS_61 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_62 = __VLS_asFunctionalComponent(__VLS_61, new __VLS_61({
        title: "Identity",
    }));
    const __VLS_63 = __VLS_62({
        title: "Identity",
    }, ...__VLS_functionalComponentArgsRest(__VLS_62));
    __VLS_64.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-3" },
    });
    const __VLS_65 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_66 = __VLS_asFunctionalComponent(__VLS_65, new __VLS_65({
        modelValue: (__VLS_ctx.form.type),
        options: (__VLS_ctx.typeOptions),
        label: "Type",
        required: true,
    }));
    const __VLS_67 = __VLS_66({
        modelValue: (__VLS_ctx.form.type),
        options: (__VLS_ctx.typeOptions),
        label: "Type",
        required: true,
    }, ...__VLS_functionalComponentArgsRest(__VLS_66));
    const __VLS_69 = {}.VTextarea;
    /** @type {[typeof __VLS_components.VTextarea, ]} */ ;
    // @ts-ignore
    const __VLS_70 = __VLS_asFunctionalComponent(__VLS_69, new __VLS_69({
        modelValue: (__VLS_ctx.form.description),
        label: "Description",
        help: "Shown to the LLM. One short paragraph, plain text.",
        rows: (3),
        required: true,
    }));
    const __VLS_71 = __VLS_70({
        modelValue: (__VLS_ctx.form.description),
        label: "Description",
        help: "Shown to the LLM. One short paragraph, plain text.",
        rows: (3),
        required: true,
    }, ...__VLS_functionalComponentArgsRest(__VLS_70));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "grid grid-cols-2 gap-3" },
    });
    const __VLS_73 = {}.VCheckbox;
    /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
    // @ts-ignore
    const __VLS_74 = __VLS_asFunctionalComponent(__VLS_73, new __VLS_73({
        modelValue: (__VLS_ctx.form.enabled),
        label: "Enabled",
    }));
    const __VLS_75 = __VLS_74({
        modelValue: (__VLS_ctx.form.enabled),
        label: "Enabled",
    }, ...__VLS_functionalComponentArgsRest(__VLS_74));
    const __VLS_77 = {}.VCheckbox;
    /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
    // @ts-ignore
    const __VLS_78 = __VLS_asFunctionalComponent(__VLS_77, new __VLS_77({
        modelValue: (__VLS_ctx.form.primary),
        label: "Primary (advertised on every turn)",
    }));
    const __VLS_79 = __VLS_78({
        modelValue: (__VLS_ctx.form.primary),
        label: "Primary (advertised on every turn)",
    }, ...__VLS_functionalComponentArgsRest(__VLS_78));
    var __VLS_64;
    const __VLS_81 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_82 = __VLS_asFunctionalComponent(__VLS_81, new __VLS_81({
        title: "Parameters (JSON)",
    }));
    const __VLS_83 = __VLS_82({
        title: "Parameters (JSON)",
    }, ...__VLS_functionalComponentArgsRest(__VLS_82));
    __VLS_84.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "text-xs opacity-70 mb-2" },
    });
    const __VLS_85 = {}.CodeEditor;
    /** @type {[typeof __VLS_components.CodeEditor, ]} */ ;
    // @ts-ignore
    const __VLS_86 = __VLS_asFunctionalComponent(__VLS_85, new __VLS_85({
        modelValue: (__VLS_ctx.form.parametersJson),
        mimeType: "application/json",
        rows: (14),
    }));
    const __VLS_87 = __VLS_86({
        modelValue: (__VLS_ctx.form.parametersJson),
        mimeType: "application/json",
        rows: (14),
    }, ...__VLS_functionalComponentArgsRest(__VLS_86));
    var __VLS_84;
    const __VLS_89 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_90 = __VLS_asFunctionalComponent(__VLS_89, new __VLS_89({
        title: "Labels",
    }));
    const __VLS_91 = __VLS_90({
        title: "Labels",
    }, ...__VLS_functionalComponentArgsRest(__VLS_90));
    __VLS_92.slots.default;
    const __VLS_93 = {}.VTextarea;
    /** @type {[typeof __VLS_components.VTextarea, ]} */ ;
    // @ts-ignore
    const __VLS_94 = __VLS_asFunctionalComponent(__VLS_93, new __VLS_93({
        modelValue: (__VLS_ctx.form.labelsText),
        label: "Labels",
        help: "One per line (or comma-separated). Recipes can target labels via @<label>.",
        rows: (4),
    }));
    const __VLS_95 = __VLS_94({
        modelValue: (__VLS_ctx.form.labelsText),
        label: "Labels",
        help: "One per line (or comma-separated). Recipes can target labels via @<label>.",
        rows: (4),
    }, ...__VLS_functionalComponentArgsRest(__VLS_94));
    var __VLS_92;
}
{
    const { 'right-panel': __VLS_thisSlot } = __VLS_3.slots;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "p-4 flex flex-col gap-4" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({
        ...{ class: "text-xs uppercase opacity-60 mb-2" },
    });
    if (!__VLS_ctx.selectedTypeSchema) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60" },
        });
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "font-mono text-sm mb-1" },
        });
        (__VLS_ctx.selectedTypeSchema.typeId);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.pre, __VLS_intrinsicElements.pre)({
            ...{ class: "text-xs whitespace-pre-wrap break-words bg-base-200 p-2 rounded" },
        });
        (JSON.stringify(__VLS_ctx.selectedTypeSchema.parametersSchema, null, 2));
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({
        ...{ class: "text-xs uppercase opacity-60 mb-2" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "text-xs opacity-70" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.code, __VLS_intrinsicElements.code)({});
}
const __VLS_97 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_98 = __VLS_asFunctionalComponent(__VLS_97, new __VLS_97({
    modelValue: (__VLS_ctx.showNewModal),
    title: "New server tool",
}));
const __VLS_99 = __VLS_98({
    modelValue: (__VLS_ctx.showNewModal),
    title: "New server tool",
}, ...__VLS_functionalComponentArgsRest(__VLS_98));
__VLS_100.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
if (__VLS_ctx.newError) {
    const __VLS_101 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_102 = __VLS_asFunctionalComponent(__VLS_101, new __VLS_101({
        variant: "error",
    }));
    const __VLS_103 = __VLS_102({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_102));
    __VLS_104.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.newError);
    var __VLS_104;
}
const __VLS_105 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_106 = __VLS_asFunctionalComponent(__VLS_105, new __VLS_105({
    modelValue: (__VLS_ctx.newName),
    label: "Name",
    required: true,
    help: "Lower-case alphanumerics + '_'. Snake_case, like the bundled tools (e.g. doc_getting_started).",
}));
const __VLS_107 = __VLS_106({
    modelValue: (__VLS_ctx.newName),
    label: "Name",
    required: true,
    help: "Lower-case alphanumerics + '_'. Snake_case, like the bundled tools (e.g. doc_getting_started).",
}, ...__VLS_functionalComponentArgsRest(__VLS_106));
const __VLS_109 = {}.VSelect;
/** @type {[typeof __VLS_components.VSelect, ]} */ ;
// @ts-ignore
const __VLS_110 = __VLS_asFunctionalComponent(__VLS_109, new __VLS_109({
    modelValue: (__VLS_ctx.newType),
    options: (__VLS_ctx.typeOptions),
    label: "Type",
    required: true,
}));
const __VLS_111 = __VLS_110({
    modelValue: (__VLS_ctx.newType),
    options: (__VLS_ctx.typeOptions),
    label: "Type",
    required: true,
}, ...__VLS_functionalComponentArgsRest(__VLS_110));
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
    ...{ class: "text-xs opacity-70" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
(__VLS_ctx.selectedProject);
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex justify-end gap-2" },
});
const __VLS_113 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_114 = __VLS_asFunctionalComponent(__VLS_113, new __VLS_113({
    ...{ 'onClick': {} },
    variant: "ghost",
}));
const __VLS_115 = __VLS_114({
    ...{ 'onClick': {} },
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_114));
let __VLS_117;
let __VLS_118;
let __VLS_119;
const __VLS_120 = {
    onClick: (...[$event]) => {
        __VLS_ctx.showNewModal = false;
    }
};
__VLS_116.slots.default;
var __VLS_116;
const __VLS_121 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_122 = __VLS_asFunctionalComponent(__VLS_121, new __VLS_121({
    ...{ 'onClick': {} },
    variant: "primary",
    loading: (__VLS_ctx.toolsState.busy.value),
}));
const __VLS_123 = __VLS_122({
    ...{ 'onClick': {} },
    variant: "primary",
    loading: (__VLS_ctx.toolsState.busy.value),
}, ...__VLS_functionalComponentArgsRest(__VLS_122));
let __VLS_125;
let __VLS_126;
let __VLS_127;
const __VLS_128 = {
    onClick: (__VLS_ctx.submitNewTool)
};
__VLS_124.slots.default;
var __VLS_124;
var __VLS_100;
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['tool-item']} */ ;
/** @type {__VLS_StyleScopedClasses['tool-item--active']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-type']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-disabled']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-primary']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['p-6']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-4xl']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-2']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['whitespace-pre-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['break-words']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            CodeEditor: CodeEditor,
            EditorShell: EditorShell,
            VAlert: VAlert,
            VButton: VButton,
            VCard: VCard,
            VCheckbox: VCheckbox,
            VEmptyState: VEmptyState,
            VInput: VInput,
            VModal: VModal,
            VSelect: VSelect,
            VTextarea: VTextarea,
            toolsState: toolsState,
            selectedProject: selectedProject,
            selectedName: selectedName,
            projectOptions: projectOptions,
            isVanceProject: isVanceProject,
            form: form,
            banner: banner,
            formError: formError,
            showNewModal: showNewModal,
            newName: newName,
            newType: newType,
            newError: newError,
            selectedTool: selectedTool,
            typeOptions: typeOptions,
            selectedTypeSchema: selectedTypeSchema,
            breadcrumbs: breadcrumbs,
            combinedError: combinedError,
            save: save,
            deleteTool: deleteTool,
            openNewTool: openNewTool,
            submitNewTool: submitNewTool,
            selectTool: selectTool,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=ToolsApp.vue.js.map