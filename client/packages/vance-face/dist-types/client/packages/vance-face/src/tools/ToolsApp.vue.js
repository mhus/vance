import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { CodeEditor, EditorShell, ProjectListSidebar, VAlert, VButton, VCard, VCheckbox, VEmptyState, VInput, VModal, VSelect, VTextarea, } from '@/components';
import { useTenantProjects } from '@/composables/useTenantProjects';
import { useAdminServerTools } from '@/composables/useAdminServerTools';
// Tenant-default project — system-wide tool overrides land here.
// Matches {@code HomeBootstrapService.TENANT_PROJECT_NAME} on the
// server. Tool resolution cascade: project → _tenant → bundled
// classpath defaults.
const TENANT_PROJECT = '_tenant';
const NAME_PATTERN = /^[a-z0-9][a-z0-9_]*$/;
const { t } = useI18n();
const tenantProjects = useTenantProjects();
const toolsState = useAdminServerTools();
// ─── Project selection ──────────────────────────────────────────────────
const selectedProject = ref(TENANT_PROJECT);
const selectedName = ref(null);
const focusZone = ref('main');
const projectTitle = computed(() => {
    const id = selectedProject.value;
    if (!id)
        return '';
    if (id === TENANT_PROJECT)
        return t('tools.tenantSystemLabel');
    const p = tenantProjects.projects.value.find((x) => x.name === id);
    return p?.title || id;
});
const inDetailMode = computed(() => !!selectedName.value);
async function onProjectListDataChanged(payload) {
    await tenantProjects.reload();
    if (payload.kind === 'project') {
        selectedProject.value = payload.name;
    }
}
// ─── Form state ─────────────────────────────────────────────────────────
const form = reactive({
    type: '',
    description: '',
    parametersJson: '{}',
    labelsText: '',
    enabled: true,
    primary: false,
    defaultDeferred: false,
    disabledSubToolsText: '',
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
    if (!selectedProject.value)
        return [t('tools.pageTitle')];
    const projectLabel = projectTitle.value;
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
        selectedProject.value
            ? toolsState.loadProject(selectedProject.value)
            : Promise.resolve(),
    ]);
});
watch(selectedProject, async (pid) => {
    selectedName.value = null;
    resetForm();
    if (pid)
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
    form.defaultDeferred = false;
    form.disabledSubToolsText = '';
}
function populateForm(t) {
    form.type = t.type;
    form.description = t.description;
    form.parametersJson = formatJson(t.parameters ?? {});
    form.labelsText = (t.labels ?? []).join('\n');
    form.enabled = t.enabled;
    form.primary = t.primary;
    form.defaultDeferred = t.defaultDeferred;
    form.disabledSubToolsText = (t.disabledSubTools ?? []).join('\n');
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
        formError.value = t('tools.errors.typeRequired');
        return null;
    }
    if (!description) {
        formError.value = t('tools.errors.descriptionRequired');
        return null;
    }
    let parameters;
    try {
        const parsed = JSON.parse(form.parametersJson || '{}');
        if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
            formError.value = t('tools.errors.parametersMustBeObject');
            return null;
        }
        parameters = parsed;
    }
    catch (e) {
        formError.value = t('tools.errors.parametersInvalidJson', {
            message: e instanceof Error ? e.message : 'parse error',
        });
        return null;
    }
    const labels = splitLines(form.labelsText);
    const disabledSubTools = splitLines(form.disabledSubToolsText);
    return {
        type,
        description,
        parameters,
        labels,
        enabled: form.enabled,
        primary: form.primary,
        disabledSubTools,
        defaultDeferred: form.defaultDeferred,
        promptHint: '',
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
    if (!selectedName.value || !selectedProject.value)
        return;
    formError.value = null;
    banner.value = null;
    const req = buildWriteRequest();
    if (!req)
        return;
    try {
        await toolsState.upsert(selectedProject.value, selectedName.value, req);
        banner.value = t('tools.banners.saved');
    }
    catch {
        /* error in toolsState.error */
    }
}
async function deleteTool() {
    if (!selectedName.value || !selectedProject.value)
        return;
    if (!confirm(t('tools.confirmDelete', { name: selectedName.value })))
        return;
    try {
        await toolsState.remove(selectedProject.value, selectedName.value);
        selectedName.value = null;
        banner.value = t('tools.banners.deleted');
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
    if (!selectedProject.value)
        return;
    const project = selectedProject.value;
    const name = newName.value.trim();
    const type = newType.value.trim();
    if (!name) {
        newError.value = t('tools.errors.nameRequired');
        return;
    }
    if (!NAME_PATTERN.test(name)) {
        newError.value = t('tools.errors.namePattern');
        return;
    }
    if (toolsState.tools.value.some(tool => tool.name === name)) {
        newError.value = t('tools.errors.nameAlreadyExists', { name });
        return;
    }
    if (!type) {
        newError.value = t('tools.errors.typeRequired');
        return;
    }
    const stub = {
        type,
        description: t('tools.stubDescription', { name }),
        parameters: {},
        labels: [],
        enabled: true,
        primary: false,
        disabledSubTools: [],
        defaultDeferred: false,
        promptHint: '',
    };
    try {
        await toolsState.upsert(project, name, stub);
        showNewModal.value = false;
        selectedName.value = name;
        banner.value = t('tools.banners.created', { name });
    }
    catch (e) {
        newError.value = e instanceof Error ? e.message : t('tools.errors.createFailed');
    }
}
// ─── Tool selection ─────────────────────────────────────────────────────
function selectTool(name) {
    selectedName.value = name;
}
function backToList() {
    selectedName.value = null;
    banner.value = null;
    formError.value = null;
}
/** CSS-class for the per-row source tag — one color per cascade
 *  tier so the user can spot at-a-glance where a tool came from.
 *  PROJECT = success-green, TENANT = info-blue, BUNDLED = neutral. */
function sourceBadgeClass(source) {
    switch (source) {
        case 'PROJECT': return 'badge-source--project';
        case 'TENANT': return 'badge-source--tenant';
        case 'BUNDLED': return 'badge-source--bundled';
        default: return '';
    }
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
    ...{ 'onTitleClick': {} },
    focusZone: (__VLS_ctx.focusZone),
    title: (__VLS_ctx.$t('tools.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    fullHeight: (true),
    showSidebar: (true),
    showRightPanel: (__VLS_ctx.inDetailMode),
    showFooter: (__VLS_ctx.inDetailMode),
    focusModel: "auto",
    titleClickable: true,
}));
const __VLS_2 = __VLS_1({
    ...{ 'onTitleClick': {} },
    focusZone: (__VLS_ctx.focusZone),
    title: (__VLS_ctx.$t('tools.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    fullHeight: (true),
    showSidebar: (true),
    showRightPanel: (__VLS_ctx.inDetailMode),
    showFooter: (__VLS_ctx.inDetailMode),
    focusModel: "auto",
    titleClickable: true,
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
let __VLS_4;
let __VLS_5;
let __VLS_6;
const __VLS_7 = {
    onTitleClick: (...[$event]) => {
        __VLS_ctx.focusZone = 'sidebar';
    }
};
var __VLS_8 = {};
__VLS_3.slots.default;
{
    const { sidebar: __VLS_thisSlot } = __VLS_3.slots;
    const __VLS_9 = {}.ProjectListSidebar;
    /** @type {[typeof __VLS_components.ProjectListSidebar, ]} */ ;
    // @ts-ignore
    const __VLS_10 = __VLS_asFunctionalComponent(__VLS_9, new __VLS_9({
        ...{ 'onFocusMain': {} },
        ...{ 'onDataChanged': {} },
        selectedProject: (__VLS_ctx.selectedProject),
        groups: (__VLS_ctx.tenantProjects.groups.value),
        projects: (__VLS_ctx.tenantProjects.projects.value),
        loading: (__VLS_ctx.tenantProjects.loading.value),
        error: (__VLS_ctx.tenantProjects.error.value),
        heading: (__VLS_ctx.$t('tools.sidebar.projectsHeading')),
        editEnabled: true,
    }));
    const __VLS_11 = __VLS_10({
        ...{ 'onFocusMain': {} },
        ...{ 'onDataChanged': {} },
        selectedProject: (__VLS_ctx.selectedProject),
        groups: (__VLS_ctx.tenantProjects.groups.value),
        projects: (__VLS_ctx.tenantProjects.projects.value),
        loading: (__VLS_ctx.tenantProjects.loading.value),
        error: (__VLS_ctx.tenantProjects.error.value),
        heading: (__VLS_ctx.$t('tools.sidebar.projectsHeading')),
        editEnabled: true,
    }, ...__VLS_functionalComponentArgsRest(__VLS_10));
    let __VLS_13;
    let __VLS_14;
    let __VLS_15;
    const __VLS_16 = {
        onFocusMain: (...[$event]) => {
            __VLS_ctx.focusZone = 'main';
        }
    };
    const __VLS_17 = {
        onDataChanged: (__VLS_ctx.onProjectListDataChanged)
    };
    var __VLS_12;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "h-full min-h-0 flex flex-col" },
});
if (__VLS_ctx.selectedProject && !__VLS_ctx.inDetailMode) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "px-6 pt-4 pb-3 border-b border-base-300 bg-base-100 flex items-center gap-3" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 min-w-0 flex items-baseline gap-2 truncate" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "font-semibold truncate" },
    });
    (__VLS_ctx.projectTitle);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "font-mono text-sm opacity-50 truncate" },
    });
    (__VLS_ctx.selectedProject);
    const __VLS_18 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_19 = __VLS_asFunctionalComponent(__VLS_18, new __VLS_18({
        ...{ 'onClick': {} },
        variant: "primary",
        size: "sm",
        title: (__VLS_ctx.$t('tools.sidebar.addNew')),
    }));
    const __VLS_20 = __VLS_19({
        ...{ 'onClick': {} },
        variant: "primary",
        size: "sm",
        title: (__VLS_ctx.$t('tools.sidebar.addNew')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_19));
    let __VLS_22;
    let __VLS_23;
    let __VLS_24;
    const __VLS_25 = {
        onClick: (__VLS_ctx.openNewTool)
    };
    __VLS_21.slots.default;
    var __VLS_21;
}
else if (__VLS_ctx.selectedProject && __VLS_ctx.selectedTool) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "px-6 pt-4 pb-3 border-b border-base-300 bg-base-100 flex items-center gap-x-3 gap-y-1 flex-wrap" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center gap-3 min-w-0 flex-1 basis-[16rem]" },
    });
    const __VLS_26 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_27 = __VLS_asFunctionalComponent(__VLS_26, new __VLS_26({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        title: (__VLS_ctx.$t('tools.detail.backToList')),
    }));
    const __VLS_28 = __VLS_27({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        title: (__VLS_ctx.$t('tools.detail.backToList')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_27));
    let __VLS_30;
    let __VLS_31;
    let __VLS_32;
    const __VLS_33 = {
        onClick: (__VLS_ctx.backToList)
    };
    __VLS_29.slots.default;
    var __VLS_29;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "font-semibold font-mono truncate" },
    });
    (__VLS_ctx.selectedTool.name);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-xs opacity-70 flex items-center gap-3 shrink-0" },
    });
    if (__VLS_ctx.selectedTool.source) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: (['badge-source', __VLS_ctx.sourceBadgeClass(__VLS_ctx.selectedTool.source)]) },
            title: (__VLS_ctx.$t('tools.sourceTooltip.' + __VLS_ctx.selectedTool.source.toLowerCase())),
        });
        (__VLS_ctx.$t('tools.source.' + __VLS_ctx.selectedTool.source.toLowerCase()));
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "badge-type px-1.5 py-0.5 rounded text-xs" },
    });
    (__VLS_ctx.selectedTool.type);
    if (__VLS_ctx.selectedTool.updatedAtTimestamp) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "opacity-70" },
        });
        (__VLS_ctx.$t('tools.detail.lastEdit', {
            at: new Date(__VLS_ctx.selectedTool.updatedAtTimestamp).toLocaleString(),
        }));
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex-1 min-h-0 overflow-y-auto" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "container mx-auto px-4 py-4 max-w-4xl flex flex-col gap-3" },
});
if (__VLS_ctx.combinedError) {
    const __VLS_34 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_35 = __VLS_asFunctionalComponent(__VLS_34, new __VLS_34({
        variant: "error",
    }));
    const __VLS_36 = __VLS_35({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_35));
    __VLS_37.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.combinedError);
    var __VLS_37;
}
if (__VLS_ctx.banner) {
    const __VLS_38 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_39 = __VLS_asFunctionalComponent(__VLS_38, new __VLS_38({
        variant: "success",
    }));
    const __VLS_40 = __VLS_39({
        variant: "success",
    }, ...__VLS_functionalComponentArgsRest(__VLS_39));
    __VLS_41.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.banner);
    var __VLS_41;
}
if (__VLS_ctx.formError) {
    const __VLS_42 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_43 = __VLS_asFunctionalComponent(__VLS_42, new __VLS_42({
        variant: "error",
    }));
    const __VLS_44 = __VLS_43({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_43));
    __VLS_45.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.formError);
    var __VLS_45;
}
if (!__VLS_ctx.selectedProject) {
    const __VLS_46 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_47 = __VLS_asFunctionalComponent(__VLS_46, new __VLS_46({
        headline: (__VLS_ctx.$t('tools.pickAProjectHeadline')),
        body: (__VLS_ctx.$t('tools.pickAProjectBody')),
    }));
    const __VLS_48 = __VLS_47({
        headline: (__VLS_ctx.$t('tools.pickAProjectHeadline')),
        body: (__VLS_ctx.$t('tools.pickAProjectBody')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_47));
}
else if (!__VLS_ctx.inDetailMode) {
    if (__VLS_ctx.toolsState.loading.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60 px-2" },
        });
        (__VLS_ctx.$t('tools.loading'));
    }
    else if (__VLS_ctx.toolsState.tools.value.length === 0) {
        const __VLS_50 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_51 = __VLS_asFunctionalComponent(__VLS_50, new __VLS_50({
            headline: (__VLS_ctx.$t('tools.sidebar.noToolsHeadline')),
            body: (__VLS_ctx.$t('tools.sidebar.noToolsBody')),
        }));
        const __VLS_52 = __VLS_51({
            headline: (__VLS_ctx.$t('tools.sidebar.noToolsHeadline')),
            body: (__VLS_ctx.$t('tools.sidebar.noToolsBody')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_51));
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
            ...{ class: "flex flex-col gap-1" },
        });
        for (const [tool] of __VLS_getVForSourceType((__VLS_ctx.toolsState.tools.value))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.selectedProject))
                            return;
                        if (!(!__VLS_ctx.inDetailMode))
                            return;
                        if (!!(__VLS_ctx.toolsState.loading.value))
                            return;
                        if (!!(__VLS_ctx.toolsState.tools.value.length === 0))
                            return;
                        __VLS_ctx.selectTool(tool.name);
                    } },
                key: (tool.name),
                ...{ class: "tool-item" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex items-center justify-between gap-2" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "flex items-center gap-2 min-w-0" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "font-mono text-sm truncate" },
            });
            (tool.name);
            if (tool.source) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: (['badge-source shrink-0', __VLS_ctx.sourceBadgeClass(tool.source)]) },
                    title: (__VLS_ctx.$t('tools.sourceTooltip.' + tool.source.toLowerCase())),
                });
                (__VLS_ctx.$t('tools.source.' + tool.source.toLowerCase()));
            }
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-xs px-1.5 py-0.5 rounded badge-type shrink-0" },
            });
            (tool.type);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex items-center gap-2 text-xs opacity-60 mt-0.5" },
            });
            if (!tool.enabled) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "badge-disabled" },
                });
                (__VLS_ctx.$t('tools.sidebar.disabled'));
            }
            if (tool.primary) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "badge-primary" },
                });
                (__VLS_ctx.$t('tools.sidebar.primary'));
            }
            if (tool.defaultDeferred) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "badge-deferred" },
                });
                (__VLS_ctx.$t('tools.sidebar.deferred'));
            }
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "truncate" },
            });
            (tool.description);
        }
    }
}
else if (__VLS_ctx.selectedTool) {
    if (__VLS_ctx.selectedProject === __VLS_ctx.TENANT_PROJECT) {
        const __VLS_54 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_55 = __VLS_asFunctionalComponent(__VLS_54, new __VLS_54({
            variant: "info",
        }));
        const __VLS_56 = __VLS_55({
            variant: "info",
        }, ...__VLS_functionalComponentArgsRest(__VLS_55));
        __VLS_57.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.$t('tools.detail.tenantNote'));
        var __VLS_57;
    }
    else if (__VLS_ctx.selectedTool.source === 'TENANT') {
        const __VLS_58 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_59 = __VLS_asFunctionalComponent(__VLS_58, new __VLS_58({
            variant: "warning",
        }));
        const __VLS_60 = __VLS_59({
            variant: "warning",
        }, ...__VLS_functionalComponentArgsRest(__VLS_59));
        __VLS_61.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.$t('tools.detail.cascadedFromTenant'));
        var __VLS_61;
    }
    else if (__VLS_ctx.selectedTool.source === 'BUNDLED') {
        const __VLS_62 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_63 = __VLS_asFunctionalComponent(__VLS_62, new __VLS_62({
            variant: "warning",
        }));
        const __VLS_64 = __VLS_63({
            variant: "warning",
        }, ...__VLS_functionalComponentArgsRest(__VLS_63));
        __VLS_65.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.$t('tools.detail.cascadedFromBundled'));
        var __VLS_65;
    }
    const __VLS_66 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_67 = __VLS_asFunctionalComponent(__VLS_66, new __VLS_66({
        title: (__VLS_ctx.$t('tools.cards.identityTitle')),
    }));
    const __VLS_68 = __VLS_67({
        title: (__VLS_ctx.$t('tools.cards.identityTitle')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_67));
    __VLS_69.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-3" },
    });
    const __VLS_70 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_71 = __VLS_asFunctionalComponent(__VLS_70, new __VLS_70({
        modelValue: (__VLS_ctx.form.type),
        options: (__VLS_ctx.typeOptions),
        label: (__VLS_ctx.$t('tools.fields.type')),
        required: true,
    }));
    const __VLS_72 = __VLS_71({
        modelValue: (__VLS_ctx.form.type),
        options: (__VLS_ctx.typeOptions),
        label: (__VLS_ctx.$t('tools.fields.type')),
        required: true,
    }, ...__VLS_functionalComponentArgsRest(__VLS_71));
    const __VLS_74 = {}.VTextarea;
    /** @type {[typeof __VLS_components.VTextarea, ]} */ ;
    // @ts-ignore
    const __VLS_75 = __VLS_asFunctionalComponent(__VLS_74, new __VLS_74({
        modelValue: (__VLS_ctx.form.description),
        label: (__VLS_ctx.$t('tools.fields.description')),
        help: (__VLS_ctx.$t('tools.fields.descriptionHelp')),
        rows: (3),
        required: true,
    }));
    const __VLS_76 = __VLS_75({
        modelValue: (__VLS_ctx.form.description),
        label: (__VLS_ctx.$t('tools.fields.description')),
        help: (__VLS_ctx.$t('tools.fields.descriptionHelp')),
        rows: (3),
        required: true,
    }, ...__VLS_functionalComponentArgsRest(__VLS_75));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "grid grid-cols-2 gap-3" },
    });
    const __VLS_78 = {}.VCheckbox;
    /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
    // @ts-ignore
    const __VLS_79 = __VLS_asFunctionalComponent(__VLS_78, new __VLS_78({
        modelValue: (__VLS_ctx.form.enabled),
        label: (__VLS_ctx.$t('tools.fields.enabled')),
    }));
    const __VLS_80 = __VLS_79({
        modelValue: (__VLS_ctx.form.enabled),
        label: (__VLS_ctx.$t('tools.fields.enabled')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_79));
    const __VLS_82 = {}.VCheckbox;
    /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
    // @ts-ignore
    const __VLS_83 = __VLS_asFunctionalComponent(__VLS_82, new __VLS_82({
        modelValue: (__VLS_ctx.form.primary),
        label: (__VLS_ctx.$t('tools.fields.primary')),
    }));
    const __VLS_84 = __VLS_83({
        modelValue: (__VLS_ctx.form.primary),
        label: (__VLS_ctx.$t('tools.fields.primary')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_83));
    var __VLS_69;
    const __VLS_86 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_87 = __VLS_asFunctionalComponent(__VLS_86, new __VLS_86({
        title: (__VLS_ctx.$t('tools.cards.packTitle')),
    }));
    const __VLS_88 = __VLS_87({
        title: (__VLS_ctx.$t('tools.cards.packTitle')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_87));
    __VLS_89.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-3" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "text-xs opacity-70" },
    });
    (__VLS_ctx.$t('tools.cards.packHelp'));
    const __VLS_90 = {}.VCheckbox;
    /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
    // @ts-ignore
    const __VLS_91 = __VLS_asFunctionalComponent(__VLS_90, new __VLS_90({
        modelValue: (__VLS_ctx.form.defaultDeferred),
        label: (__VLS_ctx.$t('tools.fields.defaultDeferred')),
        help: (__VLS_ctx.$t('tools.fields.defaultDeferredHelp')),
    }));
    const __VLS_92 = __VLS_91({
        modelValue: (__VLS_ctx.form.defaultDeferred),
        label: (__VLS_ctx.$t('tools.fields.defaultDeferred')),
        help: (__VLS_ctx.$t('tools.fields.defaultDeferredHelp')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_91));
    const __VLS_94 = {}.VTextarea;
    /** @type {[typeof __VLS_components.VTextarea, ]} */ ;
    // @ts-ignore
    const __VLS_95 = __VLS_asFunctionalComponent(__VLS_94, new __VLS_94({
        modelValue: (__VLS_ctx.form.disabledSubToolsText),
        label: (__VLS_ctx.$t('tools.fields.disabledSubTools')),
        help: (__VLS_ctx.$t('tools.fields.disabledSubToolsHelp')),
        rows: (4),
    }));
    const __VLS_96 = __VLS_95({
        modelValue: (__VLS_ctx.form.disabledSubToolsText),
        label: (__VLS_ctx.$t('tools.fields.disabledSubTools')),
        help: (__VLS_ctx.$t('tools.fields.disabledSubToolsHelp')),
        rows: (4),
    }, ...__VLS_functionalComponentArgsRest(__VLS_95));
    var __VLS_89;
    const __VLS_98 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_99 = __VLS_asFunctionalComponent(__VLS_98, new __VLS_98({
        title: (__VLS_ctx.$t('tools.cards.parametersTitle')),
    }));
    const __VLS_100 = __VLS_99({
        title: (__VLS_ctx.$t('tools.cards.parametersTitle')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_99));
    __VLS_101.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "text-xs opacity-70 mb-2" },
    });
    (__VLS_ctx.$t('tools.cards.parametersHelp'));
    const __VLS_102 = {}.CodeEditor;
    /** @type {[typeof __VLS_components.CodeEditor, ]} */ ;
    // @ts-ignore
    const __VLS_103 = __VLS_asFunctionalComponent(__VLS_102, new __VLS_102({
        modelValue: (__VLS_ctx.form.parametersJson),
        mimeType: "application/json",
        rows: (14),
    }));
    const __VLS_104 = __VLS_103({
        modelValue: (__VLS_ctx.form.parametersJson),
        mimeType: "application/json",
        rows: (14),
    }, ...__VLS_functionalComponentArgsRest(__VLS_103));
    var __VLS_101;
    const __VLS_106 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_107 = __VLS_asFunctionalComponent(__VLS_106, new __VLS_106({
        title: (__VLS_ctx.$t('tools.cards.labelsTitle')),
    }));
    const __VLS_108 = __VLS_107({
        title: (__VLS_ctx.$t('tools.cards.labelsTitle')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_107));
    __VLS_109.slots.default;
    const __VLS_110 = {}.VTextarea;
    /** @type {[typeof __VLS_components.VTextarea, ]} */ ;
    // @ts-ignore
    const __VLS_111 = __VLS_asFunctionalComponent(__VLS_110, new __VLS_110({
        modelValue: (__VLS_ctx.form.labelsText),
        label: (__VLS_ctx.$t('tools.fields.labels')),
        help: (__VLS_ctx.$t('tools.fields.labelsHelp')),
        rows: (4),
    }));
    const __VLS_112 = __VLS_111({
        modelValue: (__VLS_ctx.form.labelsText),
        label: (__VLS_ctx.$t('tools.fields.labels')),
        help: (__VLS_ctx.$t('tools.fields.labelsHelp')),
        rows: (4),
    }, ...__VLS_functionalComponentArgsRest(__VLS_111));
    var __VLS_109;
}
{
    const { 'right-panel': __VLS_thisSlot } = __VLS_3.slots;
    if (__VLS_ctx.inDetailMode) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "p-4 flex flex-col gap-4" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({
            ...{ class: "text-xs uppercase opacity-60 mb-2" },
        });
        (__VLS_ctx.$t('tools.rightPanel.typeSchemaTitle'));
        if (!__VLS_ctx.selectedTypeSchema) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs opacity-60" },
            });
            (__VLS_ctx.$t('tools.rightPanel.pickTypeHint'));
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
        (__VLS_ctx.$t('tools.rightPanel.cascadeTitle'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "text-xs opacity-70" },
        });
        (__VLS_ctx.$t('tools.rightPanel.cascadeBody'));
    }
}
{
    const { footer: __VLS_thisSlot } = __VLS_3.slots;
    if (__VLS_ctx.inDetailMode) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "px-6 py-3 flex items-center gap-2 bg-base-100" },
        });
        const __VLS_114 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_115 = __VLS_asFunctionalComponent(__VLS_114, new __VLS_114({
            ...{ 'onClick': {} },
            variant: "danger",
            loading: (__VLS_ctx.toolsState.busy.value),
        }));
        const __VLS_116 = __VLS_115({
            ...{ 'onClick': {} },
            variant: "danger",
            loading: (__VLS_ctx.toolsState.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_115));
        let __VLS_118;
        let __VLS_119;
        let __VLS_120;
        const __VLS_121 = {
            onClick: (__VLS_ctx.deleteTool)
        };
        __VLS_117.slots.default;
        (__VLS_ctx.$t('tools.detail.delete'));
        var __VLS_117;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "flex-1" },
        });
        const __VLS_122 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_123 = __VLS_asFunctionalComponent(__VLS_122, new __VLS_122({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.toolsState.busy.value),
        }));
        const __VLS_124 = __VLS_123({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.toolsState.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_123));
        let __VLS_126;
        let __VLS_127;
        let __VLS_128;
        const __VLS_129 = {
            onClick: (__VLS_ctx.save)
        };
        __VLS_125.slots.default;
        (__VLS_ctx.$t('tools.detail.save'));
        var __VLS_125;
    }
}
const __VLS_130 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_131 = __VLS_asFunctionalComponent(__VLS_130, new __VLS_130({
    modelValue: (__VLS_ctx.showNewModal),
    title: (__VLS_ctx.$t('tools.newModal.title')),
}));
const __VLS_132 = __VLS_131({
    modelValue: (__VLS_ctx.showNewModal),
    title: (__VLS_ctx.$t('tools.newModal.title')),
}, ...__VLS_functionalComponentArgsRest(__VLS_131));
__VLS_133.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
if (__VLS_ctx.newError) {
    const __VLS_134 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_135 = __VLS_asFunctionalComponent(__VLS_134, new __VLS_134({
        variant: "error",
    }));
    const __VLS_136 = __VLS_135({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_135));
    __VLS_137.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.newError);
    var __VLS_137;
}
const __VLS_138 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_139 = __VLS_asFunctionalComponent(__VLS_138, new __VLS_138({
    modelValue: (__VLS_ctx.newName),
    label: (__VLS_ctx.$t('tools.newModal.nameLabel')),
    required: true,
    help: (__VLS_ctx.$t('tools.newModal.nameHelp')),
}));
const __VLS_140 = __VLS_139({
    modelValue: (__VLS_ctx.newName),
    label: (__VLS_ctx.$t('tools.newModal.nameLabel')),
    required: true,
    help: (__VLS_ctx.$t('tools.newModal.nameHelp')),
}, ...__VLS_functionalComponentArgsRest(__VLS_139));
const __VLS_142 = {}.VSelect;
/** @type {[typeof __VLS_components.VSelect, ]} */ ;
// @ts-ignore
const __VLS_143 = __VLS_asFunctionalComponent(__VLS_142, new __VLS_142({
    modelValue: (__VLS_ctx.newType),
    options: (__VLS_ctx.typeOptions),
    label: (__VLS_ctx.$t('tools.fields.type')),
    required: true,
}));
const __VLS_144 = __VLS_143({
    modelValue: (__VLS_ctx.newType),
    options: (__VLS_ctx.typeOptions),
    label: (__VLS_ctx.$t('tools.fields.type')),
    required: true,
}, ...__VLS_functionalComponentArgsRest(__VLS_143));
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
    ...{ class: "text-xs opacity-70" },
});
(__VLS_ctx.$t('tools.newModal.stubInfo', { project: __VLS_ctx.selectedProject }));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex justify-end gap-2" },
});
const __VLS_146 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_147 = __VLS_asFunctionalComponent(__VLS_146, new __VLS_146({
    ...{ 'onClick': {} },
    variant: "ghost",
}));
const __VLS_148 = __VLS_147({
    ...{ 'onClick': {} },
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_147));
let __VLS_150;
let __VLS_151;
let __VLS_152;
const __VLS_153 = {
    onClick: (...[$event]) => {
        __VLS_ctx.showNewModal = false;
    }
};
__VLS_149.slots.default;
(__VLS_ctx.$t('tools.newModal.cancel'));
var __VLS_149;
const __VLS_154 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_155 = __VLS_asFunctionalComponent(__VLS_154, new __VLS_154({
    ...{ 'onClick': {} },
    variant: "primary",
    loading: (__VLS_ctx.toolsState.busy.value),
}));
const __VLS_156 = __VLS_155({
    ...{ 'onClick': {} },
    variant: "primary",
    loading: (__VLS_ctx.toolsState.busy.value),
}, ...__VLS_functionalComponentArgsRest(__VLS_155));
let __VLS_158;
let __VLS_159;
let __VLS_160;
const __VLS_161 = {
    onClick: (__VLS_ctx.submitNewTool)
};
__VLS_157.slots.default;
(__VLS_ctx.$t('tools.newModal.create'));
var __VLS_157;
var __VLS_133;
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['px-6']} */ ;
/** @type {__VLS_StyleScopedClasses['pt-4']} */ ;
/** @type {__VLS_StyleScopedClasses['pb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-baseline']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['px-6']} */ ;
/** @type {__VLS_StyleScopedClasses['pt-4']} */ ;
/** @type {__VLS_StyleScopedClasses['pb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-3']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-y-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['basis-[16rem]']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-source']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-type']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['container']} */ ;
/** @type {__VLS_StyleScopedClasses['mx-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['py-4']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-4xl']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['tool-item']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-source']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-type']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-disabled']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-primary']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-deferred']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-2']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
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
/** @type {__VLS_StyleScopedClasses['px-6']} */ ;
/** @type {__VLS_StyleScopedClasses['py-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
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
            ProjectListSidebar: ProjectListSidebar,
            VAlert: VAlert,
            VButton: VButton,
            VCard: VCard,
            VCheckbox: VCheckbox,
            VEmptyState: VEmptyState,
            VInput: VInput,
            VModal: VModal,
            VSelect: VSelect,
            VTextarea: VTextarea,
            TENANT_PROJECT: TENANT_PROJECT,
            tenantProjects: tenantProjects,
            toolsState: toolsState,
            selectedProject: selectedProject,
            focusZone: focusZone,
            projectTitle: projectTitle,
            inDetailMode: inDetailMode,
            onProjectListDataChanged: onProjectListDataChanged,
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
            backToList: backToList,
            sourceBadgeClass: sourceBadgeClass,
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