import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { EditorShell, VAlert, VButton, VCard, VCheckbox, VEmptyState, VInput, VModal, VSelect, VTextarea, } from '@/components';
import { useAdminTenant } from '@/composables/useAdminTenant';
import { useAdminProjectGroups } from '@/composables/useAdminProjectGroups';
import { useAdminProjects } from '@/composables/useAdminProjects';
import { useScopeSettings } from '@/composables/useScopeSettings';
import { useKitAdmin } from '@/composables/useKitAdmin';
import { KitImportMode, SettingType, } from '@vance/generated';
const ARCHIVED_GROUP = 'archived';
const { t } = useI18n();
const tenantState = useAdminTenant();
const groupsState = useAdminProjectGroups();
const projectsState = useAdminProjects();
const settingsState = useScopeSettings();
const kitState = useKitAdmin();
const selection = ref({ kind: 'tenant' });
const banner = ref(null);
// ─── Detail-form state ───
// One blob keyed off the selection — re-populated on every selection change.
const form = reactive({
    title: '',
    enabled: true,
    projectGroupId: null,
});
// ─── Modals ───
const showCreateGroup = ref(false);
const newGroupName = ref('');
const newGroupTitle = ref('');
const showCreateProject = ref(false);
const newProjectName = ref('');
const newProjectTitle = ref('');
const newProjectGroupId = ref(null);
// ─── Kit dialog state ───
const showKitDialog = ref(false);
const kitDialogMode = ref('install');
const kitForm = reactive({
    url: '',
    path: '',
    branch: '',
    commit: '',
    token: '',
    vaultPassword: '',
    prune: false,
    keepPasswords: false,
    commitMessage: '',
});
// ─── Setting editor state ───
const newSettingKey = ref('');
const newSettingType = ref(SettingType.STRING);
const newSettingValue = ref('');
const newSettingDescription = ref('');
const editingKey = ref(null);
const editValue = ref('');
const editDescription = ref('');
const settingTypeOptions = computed(() => [
    { value: SettingType.STRING, label: t('scopes.settingsPanel.types.string') },
    { value: SettingType.INT, label: t('scopes.settingsPanel.types.int') },
    { value: SettingType.LONG, label: t('scopes.settingsPanel.types.long') },
    { value: SettingType.DOUBLE, label: t('scopes.settingsPanel.types.double') },
    { value: SettingType.BOOLEAN, label: t('scopes.settingsPanel.types.boolean') },
    { value: SettingType.PASSWORD, label: t('scopes.settingsPanel.types.password') },
]);
// ─── Derived state ───
const selectedGroup = computed(() => {
    const sel = selection.value;
    if (sel.kind !== 'group')
        return null;
    return groupsState.groups.value.find(g => g.name === sel.name) ?? null;
});
const selectedProject = computed(() => {
    const sel = selection.value;
    if (sel.kind !== 'project')
        return null;
    return projectsState.projects.value.find(p => p.name === sel.name) ?? null;
});
const projectsByGroup = computed(() => {
    const map = new Map();
    for (const p of projectsState.projects.value) {
        const key = p.projectGroupId ?? '';
        if (!map.has(key))
            map.set(key, []);
        map.get(key).push(p);
    }
    return map;
});
const ungroupedProjects = computed(() => projectsByGroup.value.get('') ?? []);
const groupSelectOptions = computed(() => [
    { value: '', label: t('scopes.common.noGroup') },
    ...groupsState.groups.value.map(g => ({ value: g.name, label: g.title || g.name })),
]);
const settingsScope = computed(() => {
    if (selection.value.kind === 'tenant' && tenantState.tenant.value) {
        return { type: 'tenant', id: tenantState.tenant.value.name };
    }
    if (selection.value.kind === 'project') {
        return { type: 'project', id: selection.value.name };
    }
    return null;
});
const isReservedGroup = computed(() => selection.value.kind === 'group' && selection.value.name === ARCHIVED_GROUP);
const isArchivedProject = computed(() => selectedProject.value?.status === 'ARCHIVED');
// ─── Lifecycle ───
onMounted(async () => {
    await Promise.all([
        tenantState.reload(),
        groupsState.reload(),
        projectsState.reload(),
    ]);
    // Selection defaults to tenant — populate the form once tenant is loaded.
    applySelectionToForm();
    loadSettingsForSelection();
    loadKitForSelection();
});
watch(selection, () => {
    applySelectionToForm();
    loadSettingsForSelection();
    loadKitForSelection();
});
watch(() => tenantState.tenant.value, () => {
    if (selection.value.kind === 'tenant')
        applySelectionToForm();
});
function applySelectionToForm() {
    const sel = selection.value;
    if (sel.kind === 'tenant') {
        const t = tenantState.tenant.value;
        form.title = t?.title ?? '';
        form.enabled = t?.enabled ?? true;
        form.projectGroupId = null;
    }
    else if (sel.kind === 'group') {
        const g = selectedGroup.value;
        form.title = g?.title ?? '';
        form.enabled = g?.enabled ?? true;
        form.projectGroupId = null;
    }
    else {
        const p = selectedProject.value;
        form.title = p?.title ?? '';
        form.enabled = p?.enabled ?? true;
        form.projectGroupId = p?.projectGroupId ?? null;
    }
}
function loadSettingsForSelection() {
    const scope = settingsScope.value;
    resetSettingEditor();
    if (!scope) {
        settingsState.clear();
        return;
    }
    void settingsState.load(scope.type, scope.id);
}
function loadKitForSelection() {
    if (selection.value.kind !== 'project') {
        kitState.clear();
        return;
    }
    void kitState.load(selection.value.name);
}
function resetSettingEditor() {
    newSettingKey.value = '';
    newSettingType.value = SettingType.STRING;
    newSettingValue.value = '';
    newSettingDescription.value = '';
    editingKey.value = null;
    editValue.value = '';
    editDescription.value = '';
}
// ─── Selection actions ───
function selectTenant() {
    selection.value = { kind: 'tenant' };
}
function selectGroup(name) {
    selection.value = { kind: 'group', name };
}
function selectProject(name) {
    selection.value = { kind: 'project', name };
}
// ─── Detail-form submits ───
async function saveTenant() {
    banner.value = null;
    try {
        await tenantState.save({
            title: form.title,
            enabled: form.enabled,
        });
        banner.value = t('scopes.tenant.saved');
    }
    catch {
        /* error already in tenantState.error */
    }
}
async function saveGroup() {
    if (selection.value.kind !== 'group')
        return;
    banner.value = null;
    try {
        await groupsState.update(selection.value.name, {
            title: form.title,
            enabled: form.enabled,
        });
        banner.value = t('scopes.group.saved');
    }
    catch {
        /* state.error */
    }
}
async function deleteGroup() {
    if (selection.value.kind !== 'group')
        return;
    if (!confirm(t('scopes.group.confirmDelete', { name: selection.value.name })))
        return;
    const name = selection.value.name;
    try {
        await groupsState.remove(name);
        selectTenant();
        banner.value = t('scopes.group.deleted', { name });
    }
    catch {
        /* state.error */
    }
}
async function saveProject() {
    if (selection.value.kind !== 'project')
        return;
    banner.value = null;
    const targetGroup = form.projectGroupId ?? '';
    try {
        await projectsState.update(selection.value.name, {
            title: form.title,
            enabled: form.enabled,
            projectGroupId: targetGroup === '' ? undefined : targetGroup,
            clearProjectGroup: targetGroup === '',
        });
        banner.value = t('scopes.project.saved');
    }
    catch {
        /* state.error */
    }
}
async function archiveProject() {
    if (selection.value.kind !== 'project')
        return;
    if (!confirm(t('scopes.project.confirmArchive', { name: selection.value.name })))
        return;
    try {
        await projectsState.archive(selection.value.name);
        banner.value = t('scopes.project.archived');
        // Stay on the project — its data is still there, just status=ARCHIVED.
        applySelectionToForm();
    }
    catch {
        /* state.error */
    }
}
// ─── Create modals ───
function openCreateGroup() {
    newGroupName.value = '';
    newGroupTitle.value = '';
    showCreateGroup.value = true;
}
async function submitCreateGroup() {
    const name = newGroupName.value.trim();
    if (!name)
        return;
    try {
        await groupsState.create({
            name,
            title: newGroupTitle.value.trim() || undefined,
        });
        showCreateGroup.value = false;
        selectGroup(name);
        banner.value = t('scopes.group.created', { name });
    }
    catch {
        /* state.error */
    }
}
function openCreateProject() {
    newProjectName.value = '';
    newProjectTitle.value = '';
    newProjectGroupId.value = selection.value.kind === 'group' ? selection.value.name : null;
    showCreateProject.value = true;
}
async function submitCreateProject() {
    const name = newProjectName.value.trim();
    if (!name)
        return;
    try {
        await projectsState.create({
            name,
            title: newProjectTitle.value.trim() || undefined,
            projectGroupId: newProjectGroupId.value || undefined,
            teamIds: [],
        });
        showCreateProject.value = false;
        selectProject(name);
        banner.value = t('scopes.project.created', { name });
    }
    catch {
        /* state.error */
    }
}
// ─── Kit actions ───
const kitDialogTitle = computed(() => {
    switch (kitDialogMode.value) {
        case 'install': return t('scopes.kit.dialog.installTitle');
        case 'update': return t('scopes.kit.dialog.updateTitle');
        case 'apply': return t('scopes.kit.dialog.applyTitle');
        case 'export': return t('scopes.kit.dialog.exportTitle');
    }
});
const kitDialogSubmitLabel = computed(() => {
    switch (kitDialogMode.value) {
        case 'install': return t('scopes.kit.dialog.submitInstall');
        case 'update': return t('scopes.kit.dialog.submitUpdate');
        case 'apply': return t('scopes.kit.dialog.submitApply');
        case 'export': return t('scopes.kit.dialog.submitExport');
    }
});
const kitNeedsUrl = computed(() => kitDialogMode.value === 'install' || kitDialogMode.value === 'apply');
function openKitDialog(mode) {
    kitDialogMode.value = mode;
    kitForm.url = '';
    kitForm.path = '';
    kitForm.branch = '';
    kitForm.commit = '';
    kitForm.token = '';
    kitForm.vaultPassword = '';
    kitForm.prune = false;
    kitForm.keepPasswords = false;
    kitForm.commitMessage = '';
    // Pre-fill from manifest origin when available (update / export).
    const m = kitState.manifest.value;
    if (m && (mode === 'update' || mode === 'export')) {
        kitForm.url = m.origin?.url ?? '';
        kitForm.path = m.origin?.path ?? '';
        kitForm.branch = m.origin?.branch ?? '';
    }
    showKitDialog.value = true;
}
async function submitKitDialog() {
    if (selection.value.kind !== 'project')
        return;
    const projectId = selection.value.name;
    banner.value = null;
    try {
        if (kitDialogMode.value === 'export') {
            const request = {
                projectId,
                url: kitForm.url || undefined,
                path: kitForm.path || undefined,
                branch: kitForm.branch || undefined,
                token: kitForm.token || undefined,
                vaultPassword: kitForm.vaultPassword || undefined,
                commitMessage: kitForm.commitMessage || undefined,
            };
            await kitState.export(projectId, request);
            banner.value = t('scopes.kit.exported_msg');
        }
        else {
            const request = {
                projectId,
                source: {
                    url: kitForm.url,
                    path: kitForm.path || undefined,
                    branch: kitForm.branch || undefined,
                    commit: kitForm.commit || undefined,
                },
                token: kitForm.token || undefined,
                vaultPassword: kitForm.vaultPassword || undefined,
                // Real mode is forced server-side via the URL verb; this is just
                // a placeholder so the DTO type is satisfied.
                mode: KitImportMode.INSTALL,
                prune: kitForm.prune,
                keepPasswords: kitForm.keepPasswords,
            };
            if (kitDialogMode.value === 'install') {
                await kitState.install(projectId, request);
                banner.value = t('scopes.kit.installed_msg');
            }
            else if (kitDialogMode.value === 'update') {
                await kitState.update(projectId, request);
                banner.value = t('scopes.kit.updated_msg');
            }
            else {
                await kitState.apply(projectId, request);
                banner.value = t('scopes.kit.applied_msg');
            }
        }
        showKitDialog.value = false;
    }
    catch {
        /* error already in kitState.error */
    }
}
// ─── Settings actions ───
async function addSetting() {
    const scope = settingsScope.value;
    const key = newSettingKey.value.trim();
    if (!scope || !key)
        return;
    try {
        await settingsState.upsert(scope.type, scope.id, key, newSettingValue.value === '' ? null : newSettingValue.value, newSettingType.value, newSettingDescription.value.trim() || null);
        resetSettingEditor();
    }
    catch {
        /* state.error */
    }
}
function startEditSetting(s) {
    editingKey.value = s.key;
    // Password values come back masked as "[set]" — clear the edit field so the
    // operator types a fresh password instead of editing the mask.
    editValue.value = s.type === SettingType.PASSWORD ? '' : (s.value ?? '');
    editDescription.value = s.description ?? '';
}
async function saveEditSetting(s) {
    const scope = settingsScope.value;
    if (!scope)
        return;
    try {
        await settingsState.upsert(scope.type, scope.id, s.key, editValue.value === '' && s.type === SettingType.PASSWORD ? null : editValue.value, s.type, editDescription.value || null);
        editingKey.value = null;
    }
    catch {
        /* state.error */
    }
}
function cancelEditSetting() {
    editingKey.value = null;
}
async function deleteSetting(s) {
    const scope = settingsScope.value;
    if (!scope)
        return;
    if (!confirm(t('scopes.settingsPanel.confirmDelete', { key: s.key })))
        return;
    try {
        await settingsState.remove(scope.type, scope.id, s.key);
    }
    catch {
        /* state.error */
    }
}
// ─── Helpers for the template ───
function groupTitle(name) {
    const g = groupsState.groups.value.find(x => x.name === name);
    return g?.title || g?.name || name;
}
function isSelected(s) {
    const cur = selection.value;
    if (cur.kind !== s.kind)
        return false;
    if (cur.kind === 'tenant')
        return true;
    if (cur.kind === 'group' && s.kind === 'group')
        return cur.name === s.name;
    if (cur.kind === 'project' && s.kind === 'project')
        return cur.name === s.name;
    return false;
}
const breadcrumbs = computed(() => {
    const tenantLabel = tenantState.tenant.value?.title || tenantState.tenant.value?.name || '';
    const sel = selection.value;
    if (sel.kind === 'tenant')
        return [tenantLabel];
    if (sel.kind === 'group') {
        return [tenantLabel, t('scopes.breadcrumbs.groupPrefix', { name: groupTitle(sel.name) })];
    }
    return [
        tenantLabel,
        t('scopes.breadcrumbs.projectPrefix', { name: selectedProject.value?.title || sel.name }),
    ];
});
const combinedError = computed(() => tenantState.error.value
    || groupsState.error.value
    || projectsState.error.value
    || settingsState.error.value
    || kitState.error.value);
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['sidebar-item']} */ ;
// CSS variable injection 
// CSS variable injection end 
const __VLS_0 = {}.EditorShell;
/** @type {[typeof __VLS_components.EditorShell, typeof __VLS_components.EditorShell, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    title: (__VLS_ctx.$t('scopes.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    wideRightPanel: true,
}));
const __VLS_2 = __VLS_1({
    title: (__VLS_ctx.$t('scopes.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    wideRightPanel: true,
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
var __VLS_4 = {};
__VLS_3.slots.default;
{
    const { sidebar: __VLS_thisSlot } = __VLS_3.slots;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.nav, __VLS_intrinsicElements.nav)({
        ...{ class: "flex flex-col gap-1 p-2" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.selectTenant) },
        ...{ class: "sidebar-item" },
        ...{ class: ({ 'sidebar-item--active': __VLS_ctx.isSelected({ kind: 'tenant' }) }) },
        type: "button",
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "opacity-50 mr-1" },
    });
    (__VLS_ctx.$t('scopes.sidebar.tenant'));
    if (__VLS_ctx.tenantState.tenant.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "opacity-60" },
        });
        (__VLS_ctx.tenantState.tenant.value.name);
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "mt-3 flex items-center justify-between px-2" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-xs uppercase opacity-50" },
    });
    (__VLS_ctx.$t('scopes.sidebar.projectGroups'));
    const __VLS_5 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_6 = __VLS_asFunctionalComponent(__VLS_5, new __VLS_5({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }));
    const __VLS_7 = __VLS_6({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }, ...__VLS_functionalComponentArgsRest(__VLS_6));
    let __VLS_9;
    let __VLS_10;
    let __VLS_11;
    const __VLS_12 = {
        onClick: (__VLS_ctx.openCreateGroup)
    };
    __VLS_8.slots.default;
    (__VLS_ctx.$t('scopes.sidebar.addGroup'));
    var __VLS_8;
    for (const [group] of __VLS_getVForSourceType((__VLS_ctx.groupsState.groups.value))) {
        ('g-' + group.name);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    __VLS_ctx.selectGroup(group.name);
                } },
            ...{ class: "sidebar-item" },
            ...{ class: ({ 'sidebar-item--active': __VLS_ctx.isSelected({ kind: 'group', name: group.name }) }) },
            type: "button",
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "opacity-50 mr-1" },
        });
        (group.title || group.name);
        if (!group.enabled) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "opacity-60 text-xs" },
            });
            (__VLS_ctx.$t('scopes.common.disabled'));
        }
        for (const [p] of __VLS_getVForSourceType((__VLS_ctx.projectsByGroup.get(group.name) ?? []))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        __VLS_ctx.selectProject(p.name);
                    } },
                key: ('p-' + p.name),
                ...{ class: "sidebar-item sidebar-item--child" },
                ...{ class: ({ 'sidebar-item--active': __VLS_ctx.isSelected({ kind: 'project', name: p.name }) }) },
                type: "button",
            });
            (p.title || p.name);
            if (p.status === 'ARCHIVED') {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "opacity-60 text-xs" },
                });
                (__VLS_ctx.$t('scopes.common.archived'));
            }
        }
    }
    if (__VLS_ctx.ungroupedProjects.length > 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "mt-3 px-2 text-xs uppercase opacity-50" },
        });
        (__VLS_ctx.$t('scopes.sidebar.ungroupedProjects'));
    }
    for (const [p] of __VLS_getVForSourceType((__VLS_ctx.ungroupedProjects))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    __VLS_ctx.selectProject(p.name);
                } },
            key: ('pu-' + p.name),
            ...{ class: "sidebar-item sidebar-item--child" },
            ...{ class: ({ 'sidebar-item--active': __VLS_ctx.isSelected({ kind: 'project', name: p.name }) }) },
            type: "button",
        });
        (p.title || p.name);
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "mt-3 px-2" },
    });
    const __VLS_13 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_14 = __VLS_asFunctionalComponent(__VLS_13, new __VLS_13({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        block: true,
    }));
    const __VLS_15 = __VLS_14({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        block: true,
    }, ...__VLS_functionalComponentArgsRest(__VLS_14));
    let __VLS_17;
    let __VLS_18;
    let __VLS_19;
    const __VLS_20 = {
        onClick: (__VLS_ctx.openCreateProject)
    };
    __VLS_16.slots.default;
    (__VLS_ctx.$t('scopes.sidebar.addProject'));
    var __VLS_16;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "p-6 max-w-2xl flex flex-col gap-3" },
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
if (__VLS_ctx.selection.kind === 'tenant') {
    const __VLS_29 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_30 = __VLS_asFunctionalComponent(__VLS_29, new __VLS_29({
        title: (__VLS_ctx.$t('scopes.tenant.cardTitle')),
    }));
    const __VLS_31 = __VLS_30({
        title: (__VLS_ctx.$t('scopes.tenant.cardTitle')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_30));
    __VLS_32.slots.default;
    if (!__VLS_ctx.tenantState.tenant.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "opacity-70" },
        });
        (__VLS_ctx.$t('scopes.loading'));
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex flex-col gap-3" },
        });
        const __VLS_33 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_34 = __VLS_asFunctionalComponent(__VLS_33, new __VLS_33({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.tenantState.tenant.value.name),
            label: (__VLS_ctx.$t('scopes.common.name')),
            disabled: true,
            help: (__VLS_ctx.$t('scopes.tenant.nameImmutable')),
        }));
        const __VLS_35 = __VLS_34({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.tenantState.tenant.value.name),
            label: (__VLS_ctx.$t('scopes.common.name')),
            disabled: true,
            help: (__VLS_ctx.$t('scopes.tenant.nameImmutable')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_34));
        let __VLS_37;
        let __VLS_38;
        let __VLS_39;
        const __VLS_40 = {
            'onUpdate:modelValue': (() => { })
        };
        var __VLS_36;
        const __VLS_41 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_42 = __VLS_asFunctionalComponent(__VLS_41, new __VLS_41({
            modelValue: (__VLS_ctx.form.title),
            label: (__VLS_ctx.$t('scopes.common.title')),
        }));
        const __VLS_43 = __VLS_42({
            modelValue: (__VLS_ctx.form.title),
            label: (__VLS_ctx.$t('scopes.common.title')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_42));
        const __VLS_45 = {}.VCheckbox;
        /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
        // @ts-ignore
        const __VLS_46 = __VLS_asFunctionalComponent(__VLS_45, new __VLS_45({
            modelValue: (__VLS_ctx.form.enabled),
            label: (__VLS_ctx.$t('scopes.common.enabled')),
        }));
        const __VLS_47 = __VLS_46({
            modelValue: (__VLS_ctx.form.enabled),
            label: (__VLS_ctx.$t('scopes.common.enabled')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_46));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex justify-end" },
        });
        const __VLS_49 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_50 = __VLS_asFunctionalComponent(__VLS_49, new __VLS_49({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.tenantState.saving.value),
        }));
        const __VLS_51 = __VLS_50({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.tenantState.saving.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_50));
        let __VLS_53;
        let __VLS_54;
        let __VLS_55;
        const __VLS_56 = {
            onClick: (__VLS_ctx.saveTenant)
        };
        __VLS_52.slots.default;
        (__VLS_ctx.$t('scopes.common.save'));
        var __VLS_52;
    }
    var __VLS_32;
}
else if (__VLS_ctx.selection.kind === 'group') {
    const __VLS_57 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_58 = __VLS_asFunctionalComponent(__VLS_57, new __VLS_57({
        title: (__VLS_ctx.$t('scopes.group.cardTitle', { name: __VLS_ctx.selection.name })),
    }));
    const __VLS_59 = __VLS_58({
        title: (__VLS_ctx.$t('scopes.group.cardTitle', { name: __VLS_ctx.selection.name })),
    }, ...__VLS_functionalComponentArgsRest(__VLS_58));
    __VLS_60.slots.default;
    if (__VLS_ctx.isReservedGroup) {
        const __VLS_61 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_62 = __VLS_asFunctionalComponent(__VLS_61, new __VLS_61({
            variant: "info",
            ...{ class: "mb-3" },
        }));
        const __VLS_63 = __VLS_62({
            variant: "info",
            ...{ class: "mb-3" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_62));
        __VLS_64.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.$t('scopes.group.reservedNote'));
        var __VLS_64;
    }
    if (!__VLS_ctx.selectedGroup) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "opacity-70" },
        });
        (__VLS_ctx.$t('scopes.loading'));
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex flex-col gap-3" },
        });
        const __VLS_65 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_66 = __VLS_asFunctionalComponent(__VLS_65, new __VLS_65({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.selectedGroup.name),
            label: (__VLS_ctx.$t('scopes.common.name')),
            disabled: true,
            help: (__VLS_ctx.$t('scopes.group.nameImmutable')),
        }));
        const __VLS_67 = __VLS_66({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.selectedGroup.name),
            label: (__VLS_ctx.$t('scopes.common.name')),
            disabled: true,
            help: (__VLS_ctx.$t('scopes.group.nameImmutable')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_66));
        let __VLS_69;
        let __VLS_70;
        let __VLS_71;
        const __VLS_72 = {
            'onUpdate:modelValue': (() => { })
        };
        var __VLS_68;
        const __VLS_73 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_74 = __VLS_asFunctionalComponent(__VLS_73, new __VLS_73({
            modelValue: (__VLS_ctx.form.title),
            label: (__VLS_ctx.$t('scopes.common.title')),
        }));
        const __VLS_75 = __VLS_74({
            modelValue: (__VLS_ctx.form.title),
            label: (__VLS_ctx.$t('scopes.common.title')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_74));
        const __VLS_77 = {}.VCheckbox;
        /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
        // @ts-ignore
        const __VLS_78 = __VLS_asFunctionalComponent(__VLS_77, new __VLS_77({
            modelValue: (__VLS_ctx.form.enabled),
            label: (__VLS_ctx.$t('scopes.common.enabled')),
        }));
        const __VLS_79 = __VLS_78({
            modelValue: (__VLS_ctx.form.enabled),
            label: (__VLS_ctx.$t('scopes.common.enabled')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_78));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex justify-between" },
        });
        const __VLS_81 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_82 = __VLS_asFunctionalComponent(__VLS_81, new __VLS_81({
            ...{ 'onClick': {} },
            variant: "danger",
            disabled: (__VLS_ctx.isReservedGroup),
            loading: (__VLS_ctx.groupsState.busy.value),
        }));
        const __VLS_83 = __VLS_82({
            ...{ 'onClick': {} },
            variant: "danger",
            disabled: (__VLS_ctx.isReservedGroup),
            loading: (__VLS_ctx.groupsState.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_82));
        let __VLS_85;
        let __VLS_86;
        let __VLS_87;
        const __VLS_88 = {
            onClick: (__VLS_ctx.deleteGroup)
        };
        __VLS_84.slots.default;
        (__VLS_ctx.$t('scopes.group.delete'));
        var __VLS_84;
        const __VLS_89 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_90 = __VLS_asFunctionalComponent(__VLS_89, new __VLS_89({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.groupsState.busy.value),
        }));
        const __VLS_91 = __VLS_90({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.groupsState.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_90));
        let __VLS_93;
        let __VLS_94;
        let __VLS_95;
        const __VLS_96 = {
            onClick: (__VLS_ctx.saveGroup)
        };
        __VLS_92.slots.default;
        (__VLS_ctx.$t('scopes.common.save'));
        var __VLS_92;
    }
    var __VLS_60;
}
else if (__VLS_ctx.selection.kind === 'project') {
    const __VLS_97 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_98 = __VLS_asFunctionalComponent(__VLS_97, new __VLS_97({
        title: (__VLS_ctx.$t('scopes.project.cardTitle', { name: __VLS_ctx.selection.name })),
    }));
    const __VLS_99 = __VLS_98({
        title: (__VLS_ctx.$t('scopes.project.cardTitle', { name: __VLS_ctx.selection.name })),
    }, ...__VLS_functionalComponentArgsRest(__VLS_98));
    __VLS_100.slots.default;
    if (__VLS_ctx.isArchivedProject) {
        const __VLS_101 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_102 = __VLS_asFunctionalComponent(__VLS_101, new __VLS_101({
            variant: "warning",
            ...{ class: "mb-3" },
        }));
        const __VLS_103 = __VLS_102({
            variant: "warning",
            ...{ class: "mb-3" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_102));
        __VLS_104.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.$t('scopes.project.archivedNote'));
        var __VLS_104;
    }
    if (!__VLS_ctx.selectedProject) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "opacity-70" },
        });
        (__VLS_ctx.$t('scopes.loading'));
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex flex-col gap-3" },
        });
        const __VLS_105 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_106 = __VLS_asFunctionalComponent(__VLS_105, new __VLS_105({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.selectedProject.name),
            label: (__VLS_ctx.$t('scopes.common.name')),
            disabled: true,
            help: (__VLS_ctx.$t('scopes.project.nameImmutable')),
        }));
        const __VLS_107 = __VLS_106({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.selectedProject.name),
            label: (__VLS_ctx.$t('scopes.common.name')),
            disabled: true,
            help: (__VLS_ctx.$t('scopes.project.nameImmutable')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_106));
        let __VLS_109;
        let __VLS_110;
        let __VLS_111;
        const __VLS_112 = {
            'onUpdate:modelValue': (() => { })
        };
        var __VLS_108;
        const __VLS_113 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_114 = __VLS_asFunctionalComponent(__VLS_113, new __VLS_113({
            modelValue: (__VLS_ctx.form.title),
            label: (__VLS_ctx.$t('scopes.common.title')),
        }));
        const __VLS_115 = __VLS_114({
            modelValue: (__VLS_ctx.form.title),
            label: (__VLS_ctx.$t('scopes.common.title')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_114));
        const __VLS_117 = {}.VSelect;
        /** @type {[typeof __VLS_components.VSelect, ]} */ ;
        // @ts-ignore
        const __VLS_118 = __VLS_asFunctionalComponent(__VLS_117, new __VLS_117({
            modelValue: (__VLS_ctx.form.projectGroupId),
            label: (__VLS_ctx.$t('scopes.project.groupLabel')),
            options: (__VLS_ctx.groupSelectOptions),
        }));
        const __VLS_119 = __VLS_118({
            modelValue: (__VLS_ctx.form.projectGroupId),
            label: (__VLS_ctx.$t('scopes.project.groupLabel')),
            options: (__VLS_ctx.groupSelectOptions),
        }, ...__VLS_functionalComponentArgsRest(__VLS_118));
        const __VLS_121 = {}.VCheckbox;
        /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
        // @ts-ignore
        const __VLS_122 = __VLS_asFunctionalComponent(__VLS_121, new __VLS_121({
            modelValue: (__VLS_ctx.form.enabled),
            label: (__VLS_ctx.$t('scopes.common.enabled')),
        }));
        const __VLS_123 = __VLS_122({
            modelValue: (__VLS_ctx.form.enabled),
            label: (__VLS_ctx.$t('scopes.common.enabled')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_122));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dl, __VLS_intrinsicElements.dl)({
            ...{ class: "grid grid-cols-2 gap-x-4 gap-y-1 text-sm opacity-80" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
            ...{ class: "opacity-60" },
        });
        (__VLS_ctx.$t('scopes.project.statusLabel'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
        (__VLS_ctx.selectedProject.status);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
            ...{ class: "opacity-60" },
        });
        (__VLS_ctx.$t('scopes.project.podLabel'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
        (__VLS_ctx.selectedProject.podIp ?? __VLS_ctx.$t('scopes.common.none'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
            ...{ class: "opacity-60" },
        });
        (__VLS_ctx.$t('scopes.project.claimedLabel'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
        (__VLS_ctx.selectedProject.claimedAt ?? __VLS_ctx.$t('scopes.common.none'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
            ...{ class: "opacity-60" },
        });
        (__VLS_ctx.$t('scopes.project.createdLabel'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
        (__VLS_ctx.selectedProject.createdAt ?? __VLS_ctx.$t('scopes.common.none'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex justify-between" },
        });
        const __VLS_125 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_126 = __VLS_asFunctionalComponent(__VLS_125, new __VLS_125({
            ...{ 'onClick': {} },
            variant: "danger",
            disabled: (__VLS_ctx.isArchivedProject),
            loading: (__VLS_ctx.projectsState.busy.value),
        }));
        const __VLS_127 = __VLS_126({
            ...{ 'onClick': {} },
            variant: "danger",
            disabled: (__VLS_ctx.isArchivedProject),
            loading: (__VLS_ctx.projectsState.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_126));
        let __VLS_129;
        let __VLS_130;
        let __VLS_131;
        const __VLS_132 = {
            onClick: (__VLS_ctx.archiveProject)
        };
        __VLS_128.slots.default;
        (__VLS_ctx.$t('scopes.project.archive'));
        var __VLS_128;
        const __VLS_133 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_134 = __VLS_asFunctionalComponent(__VLS_133, new __VLS_133({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.projectsState.busy.value),
        }));
        const __VLS_135 = __VLS_134({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.projectsState.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_134));
        let __VLS_137;
        let __VLS_138;
        let __VLS_139;
        const __VLS_140 = {
            onClick: (__VLS_ctx.saveProject)
        };
        __VLS_136.slots.default;
        (__VLS_ctx.$t('scopes.common.save'));
        var __VLS_136;
    }
    var __VLS_100;
}
if (__VLS_ctx.selection.kind === 'project' && __VLS_ctx.selectedProject) {
    const __VLS_141 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_142 = __VLS_asFunctionalComponent(__VLS_141, new __VLS_141({
        title: (__VLS_ctx.$t('scopes.kit.cardTitle')),
    }));
    const __VLS_143 = __VLS_142({
        title: (__VLS_ctx.$t('scopes.kit.cardTitle')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_142));
    __VLS_144.slots.default;
    if (__VLS_ctx.kitState.loading.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "opacity-70 text-sm" },
        });
        (__VLS_ctx.$t('scopes.kit.loading'));
    }
    else if (__VLS_ctx.kitState.manifest.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex flex-col gap-2 text-sm" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-baseline justify-between" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "font-semibold" },
        });
        (__VLS_ctx.kitState.manifest.value.kit.name);
        if (__VLS_ctx.kitState.manifest.value.kit.version) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "opacity-60 text-xs" },
            });
            (__VLS_ctx.$t('scopes.kit.versionPrefix', { version: __VLS_ctx.kitState.manifest.value.kit.version }));
        }
        if (__VLS_ctx.kitState.manifest.value.kit.description) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "opacity-80" },
            });
            (__VLS_ctx.kitState.manifest.value.kit.description);
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dl, __VLS_intrinsicElements.dl)({
            ...{ class: "grid grid-cols-2 gap-x-4 gap-y-1 text-xs opacity-80" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
            ...{ class: "opacity-60" },
        });
        (__VLS_ctx.$t('scopes.kit.origin'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
            ...{ class: "break-all font-mono" },
        });
        (__VLS_ctx.kitState.manifest.value.origin.url);
        if (__VLS_ctx.kitState.manifest.value.origin.path) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            (__VLS_ctx.$t('scopes.kit.path'));
        }
        if (__VLS_ctx.kitState.manifest.value.origin.path) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.kitState.manifest.value.origin.path);
        }
        if (__VLS_ctx.kitState.manifest.value.origin.branch) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            (__VLS_ctx.$t('scopes.kit.branch'));
        }
        if (__VLS_ctx.kitState.manifest.value.origin.branch) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.kitState.manifest.value.origin.branch);
        }
        if (__VLS_ctx.kitState.manifest.value.origin.commit) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            (__VLS_ctx.$t('scopes.kit.commit'));
        }
        if (__VLS_ctx.kitState.manifest.value.origin.commit) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
                ...{ class: "font-mono" },
            });
            (__VLS_ctx.kitState.manifest.value.origin.commit.slice(0, 12));
        }
        if (__VLS_ctx.kitState.manifest.value.origin.installedAt) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            (__VLS_ctx.$t('scopes.kit.installed'));
        }
        if (__VLS_ctx.kitState.manifest.value.origin.installedAt) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.kitState.manifest.value.origin.installedAt);
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
            ...{ class: "opacity-60" },
        });
        (__VLS_ctx.$t('scopes.kit.documents'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
        (__VLS_ctx.kitState.manifest.value.documents?.length ?? 0);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
            ...{ class: "opacity-60" },
        });
        (__VLS_ctx.$t('scopes.kit.settings'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
        (__VLS_ctx.kitState.manifest.value.settings?.length ?? 0);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
            ...{ class: "opacity-60" },
        });
        (__VLS_ctx.$t('scopes.kit.tools'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
        (__VLS_ctx.kitState.manifest.value.tools?.length ?? 0);
        if ((__VLS_ctx.kitState.manifest.value.resolvedInherits?.length ?? 0) > 0) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            (__VLS_ctx.$t('scopes.kit.inherits'));
        }
        if ((__VLS_ctx.kitState.manifest.value.resolvedInherits?.length ?? 0) > 0) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.kitState.manifest.value.resolvedInherits.join(', '));
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex flex-wrap justify-end gap-2 pt-2" },
        });
        const __VLS_145 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_146 = __VLS_asFunctionalComponent(__VLS_145, new __VLS_145({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
        }));
        const __VLS_147 = __VLS_146({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
        }, ...__VLS_functionalComponentArgsRest(__VLS_146));
        let __VLS_149;
        let __VLS_150;
        let __VLS_151;
        const __VLS_152 = {
            onClick: (...[$event]) => {
                if (!(__VLS_ctx.selection.kind === 'project' && __VLS_ctx.selectedProject))
                    return;
                if (!!(__VLS_ctx.kitState.loading.value))
                    return;
                if (!(__VLS_ctx.kitState.manifest.value))
                    return;
                __VLS_ctx.openKitDialog('apply');
            }
        };
        __VLS_148.slots.default;
        (__VLS_ctx.$t('scopes.kit.apply'));
        var __VLS_148;
        const __VLS_153 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_154 = __VLS_asFunctionalComponent(__VLS_153, new __VLS_153({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
        }));
        const __VLS_155 = __VLS_154({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
        }, ...__VLS_functionalComponentArgsRest(__VLS_154));
        let __VLS_157;
        let __VLS_158;
        let __VLS_159;
        const __VLS_160 = {
            onClick: (...[$event]) => {
                if (!(__VLS_ctx.selection.kind === 'project' && __VLS_ctx.selectedProject))
                    return;
                if (!!(__VLS_ctx.kitState.loading.value))
                    return;
                if (!(__VLS_ctx.kitState.manifest.value))
                    return;
                __VLS_ctx.openKitDialog('export');
            }
        };
        __VLS_156.slots.default;
        (__VLS_ctx.$t('scopes.kit.export'));
        var __VLS_156;
        const __VLS_161 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_162 = __VLS_asFunctionalComponent(__VLS_161, new __VLS_161({
            ...{ 'onClick': {} },
            variant: "primary",
            size: "sm",
            loading: (__VLS_ctx.kitState.busy.value),
        }));
        const __VLS_163 = __VLS_162({
            ...{ 'onClick': {} },
            variant: "primary",
            size: "sm",
            loading: (__VLS_ctx.kitState.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_162));
        let __VLS_165;
        let __VLS_166;
        let __VLS_167;
        const __VLS_168 = {
            onClick: (...[$event]) => {
                if (!(__VLS_ctx.selection.kind === 'project' && __VLS_ctx.selectedProject))
                    return;
                if (!!(__VLS_ctx.kitState.loading.value))
                    return;
                if (!(__VLS_ctx.kitState.manifest.value))
                    return;
                __VLS_ctx.openKitDialog('update');
            }
        };
        __VLS_164.slots.default;
        (__VLS_ctx.$t('scopes.kit.update'));
        var __VLS_164;
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex flex-col gap-2 text-sm" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "opacity-70" },
        });
        (__VLS_ctx.$t('scopes.kit.none'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex flex-wrap justify-end gap-2 pt-2" },
        });
        const __VLS_169 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_170 = __VLS_asFunctionalComponent(__VLS_169, new __VLS_169({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
        }));
        const __VLS_171 = __VLS_170({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
        }, ...__VLS_functionalComponentArgsRest(__VLS_170));
        let __VLS_173;
        let __VLS_174;
        let __VLS_175;
        const __VLS_176 = {
            onClick: (...[$event]) => {
                if (!(__VLS_ctx.selection.kind === 'project' && __VLS_ctx.selectedProject))
                    return;
                if (!!(__VLS_ctx.kitState.loading.value))
                    return;
                if (!!(__VLS_ctx.kitState.manifest.value))
                    return;
                __VLS_ctx.openKitDialog('apply');
            }
        };
        __VLS_172.slots.default;
        (__VLS_ctx.$t('scopes.kit.apply'));
        var __VLS_172;
        const __VLS_177 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_178 = __VLS_asFunctionalComponent(__VLS_177, new __VLS_177({
            ...{ 'onClick': {} },
            variant: "primary",
            size: "sm",
            loading: (__VLS_ctx.kitState.busy.value),
        }));
        const __VLS_179 = __VLS_178({
            ...{ 'onClick': {} },
            variant: "primary",
            size: "sm",
            loading: (__VLS_ctx.kitState.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_178));
        let __VLS_181;
        let __VLS_182;
        let __VLS_183;
        const __VLS_184 = {
            onClick: (...[$event]) => {
                if (!(__VLS_ctx.selection.kind === 'project' && __VLS_ctx.selectedProject))
                    return;
                if (!!(__VLS_ctx.kitState.loading.value))
                    return;
                if (!!(__VLS_ctx.kitState.manifest.value))
                    return;
                __VLS_ctx.openKitDialog('install');
            }
        };
        __VLS_180.slots.default;
        (__VLS_ctx.$t('scopes.kit.install'));
        var __VLS_180;
    }
    if (__VLS_ctx.kitState.lastResult.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "mt-3 border-t border-base-300 pt-2 text-xs opacity-80" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "font-semibold opacity-90 mb-1" },
        });
        (__VLS_ctx.$t('scopes.kit.lastOperation', { mode: __VLS_ctx.kitState.lastResult.value.mode }));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
            ...{ class: "flex flex-col gap-0.5" },
        });
        if ((__VLS_ctx.kitState.lastResult.value.documentsAdded?.length ?? 0) > 0) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
            (__VLS_ctx.$t('scopes.kit.docsAdded', { count: __VLS_ctx.kitState.lastResult.value.documentsAdded.length }));
        }
        if ((__VLS_ctx.kitState.lastResult.value.documentsUpdated?.length ?? 0) > 0) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
            (__VLS_ctx.$t('scopes.kit.docsUpdated', { count: __VLS_ctx.kitState.lastResult.value.documentsUpdated.length }));
        }
        if ((__VLS_ctx.kitState.lastResult.value.documentsRemoved?.length ?? 0) > 0) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
            (__VLS_ctx.$t('scopes.kit.docsRemoved', { count: __VLS_ctx.kitState.lastResult.value.documentsRemoved.length }));
        }
        if ((__VLS_ctx.kitState.lastResult.value.settingsAdded?.length ?? 0) > 0
            || (__VLS_ctx.kitState.lastResult.value.settingsUpdated?.length ?? 0) > 0) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
            (__VLS_ctx.$t('scopes.kit.settingsTouched', {
                count: (__VLS_ctx.kitState.lastResult.value.settingsAdded?.length ?? 0)
                    + (__VLS_ctx.kitState.lastResult.value.settingsUpdated?.length ?? 0),
            }));
        }
        if ((__VLS_ctx.kitState.lastResult.value.toolsAdded?.length ?? 0) > 0
            || (__VLS_ctx.kitState.lastResult.value.toolsUpdated?.length ?? 0) > 0) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
            (__VLS_ctx.$t('scopes.kit.toolsTouched', {
                count: (__VLS_ctx.kitState.lastResult.value.toolsAdded?.length ?? 0)
                    + (__VLS_ctx.kitState.lastResult.value.toolsUpdated?.length ?? 0),
            }));
        }
        if ((__VLS_ctx.kitState.lastResult.value.skippedPasswords?.length ?? 0) > 0) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                ...{ class: "opacity-90" },
            });
            (__VLS_ctx.$t('scopes.kit.passwordsSkipped', {
                count: __VLS_ctx.kitState.lastResult.value.skippedPasswords.length,
            }));
        }
        for (const [w, i] of __VLS_getVForSourceType(((__VLS_ctx.kitState.lastResult.value.warnings ?? [])))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                key: ('kw-' + i),
                ...{ class: "opacity-90" },
            });
            (w);
        }
    }
    var __VLS_144;
}
if (__VLS_ctx.settingsScope) {
    {
        const { 'right-panel': __VLS_thisSlot } = __VLS_3.slots;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "p-4 flex flex-col gap-3" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({
            ...{ class: "font-semibold text-sm uppercase opacity-60" },
        });
        (__VLS_ctx.$t('scopes.settingsPanel.title', {
            type: __VLS_ctx.settingsScope.type,
            id: __VLS_ctx.settingsScope.id,
        }));
        if (!__VLS_ctx.settingsState.loading.value && __VLS_ctx.settingsState.settings.value.length === 0) {
            const __VLS_185 = {}.VEmptyState;
            /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
            // @ts-ignore
            const __VLS_186 = __VLS_asFunctionalComponent(__VLS_185, new __VLS_185({
                headline: (__VLS_ctx.$t('scopes.settingsPanel.noSettingsHeadline')),
                body: (__VLS_ctx.$t('scopes.settingsPanel.noSettingsBody')),
            }));
            const __VLS_187 = __VLS_186({
                headline: (__VLS_ctx.$t('scopes.settingsPanel.noSettingsHeadline')),
                body: (__VLS_ctx.$t('scopes.settingsPanel.noSettingsBody')),
            }, ...__VLS_functionalComponentArgsRest(__VLS_186));
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
            ...{ class: "flex flex-col divide-y divide-base-300" },
        });
        for (const [s] of __VLS_getVForSourceType((__VLS_ctx.settingsState.settings.value))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                key: (s.key),
                ...{ class: "setting-row" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex items-center justify-between gap-2" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "font-mono text-sm truncate" },
            });
            (s.key);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "opacity-60 text-xs" },
            });
            (s.type);
            if (__VLS_ctx.editingKey === s.key) {
                if (s.type !== __VLS_ctx.SettingType.PASSWORD) {
                    const __VLS_189 = {}.VInput;
                    /** @type {[typeof __VLS_components.VInput, ]} */ ;
                    // @ts-ignore
                    const __VLS_190 = __VLS_asFunctionalComponent(__VLS_189, new __VLS_189({
                        modelValue: (__VLS_ctx.editValue),
                        label: (__VLS_ctx.$t('scopes.settingsPanel.valueLabel')),
                    }));
                    const __VLS_191 = __VLS_190({
                        modelValue: (__VLS_ctx.editValue),
                        label: (__VLS_ctx.$t('scopes.settingsPanel.valueLabel')),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_190));
                }
                else {
                    const __VLS_193 = {}.VInput;
                    /** @type {[typeof __VLS_components.VInput, ]} */ ;
                    // @ts-ignore
                    const __VLS_194 = __VLS_asFunctionalComponent(__VLS_193, new __VLS_193({
                        modelValue: (__VLS_ctx.editValue),
                        type: "password",
                        label: (__VLS_ctx.$t('scopes.settingsPanel.newPasswordLabel')),
                        placeholder: (__VLS_ctx.$t('scopes.settingsPanel.passwordEmptyToClear')),
                    }));
                    const __VLS_195 = __VLS_194({
                        modelValue: (__VLS_ctx.editValue),
                        type: "password",
                        label: (__VLS_ctx.$t('scopes.settingsPanel.newPasswordLabel')),
                        placeholder: (__VLS_ctx.$t('scopes.settingsPanel.passwordEmptyToClear')),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_194));
                }
                const __VLS_197 = {}.VTextarea;
                /** @type {[typeof __VLS_components.VTextarea, ]} */ ;
                // @ts-ignore
                const __VLS_198 = __VLS_asFunctionalComponent(__VLS_197, new __VLS_197({
                    modelValue: (__VLS_ctx.editDescription),
                    label: (__VLS_ctx.$t('scopes.settingsPanel.descriptionLabel')),
                    rows: (2),
                }));
                const __VLS_199 = __VLS_198({
                    modelValue: (__VLS_ctx.editDescription),
                    label: (__VLS_ctx.$t('scopes.settingsPanel.descriptionLabel')),
                    rows: (2),
                }, ...__VLS_functionalComponentArgsRest(__VLS_198));
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "flex justify-end gap-2 mt-1" },
                });
                const __VLS_201 = {}.VButton;
                /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
                // @ts-ignore
                const __VLS_202 = __VLS_asFunctionalComponent(__VLS_201, new __VLS_201({
                    ...{ 'onClick': {} },
                    variant: "ghost",
                    size: "sm",
                }));
                const __VLS_203 = __VLS_202({
                    ...{ 'onClick': {} },
                    variant: "ghost",
                    size: "sm",
                }, ...__VLS_functionalComponentArgsRest(__VLS_202));
                let __VLS_205;
                let __VLS_206;
                let __VLS_207;
                const __VLS_208 = {
                    onClick: (__VLS_ctx.cancelEditSetting)
                };
                __VLS_204.slots.default;
                (__VLS_ctx.$t('scopes.common.cancel'));
                var __VLS_204;
                const __VLS_209 = {}.VButton;
                /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
                // @ts-ignore
                const __VLS_210 = __VLS_asFunctionalComponent(__VLS_209, new __VLS_209({
                    ...{ 'onClick': {} },
                    variant: "primary",
                    size: "sm",
                    loading: (__VLS_ctx.settingsState.busy.value),
                }));
                const __VLS_211 = __VLS_210({
                    ...{ 'onClick': {} },
                    variant: "primary",
                    size: "sm",
                    loading: (__VLS_ctx.settingsState.busy.value),
                }, ...__VLS_functionalComponentArgsRest(__VLS_210));
                let __VLS_213;
                let __VLS_214;
                let __VLS_215;
                const __VLS_216 = {
                    onClick: (...[$event]) => {
                        if (!(__VLS_ctx.settingsScope))
                            return;
                        if (!(__VLS_ctx.editingKey === s.key))
                            return;
                        __VLS_ctx.saveEditSetting(s);
                    }
                };
                __VLS_212.slots.default;
                (__VLS_ctx.$t('scopes.common.save'));
                var __VLS_212;
            }
            else {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "text-sm break-words" },
                });
                if (s.type === __VLS_ctx.SettingType.PASSWORD) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "opacity-70" },
                    });
                    (s.value ?? __VLS_ctx.$t('scopes.common.empty'));
                }
                else {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                    (s.value ?? __VLS_ctx.$t('scopes.common.empty'));
                }
                if (s.description) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: "text-xs opacity-60" },
                    });
                    (s.description);
                }
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "flex justify-end gap-2 mt-1" },
                });
                const __VLS_217 = {}.VButton;
                /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
                // @ts-ignore
                const __VLS_218 = __VLS_asFunctionalComponent(__VLS_217, new __VLS_217({
                    ...{ 'onClick': {} },
                    variant: "ghost",
                    size: "sm",
                }));
                const __VLS_219 = __VLS_218({
                    ...{ 'onClick': {} },
                    variant: "ghost",
                    size: "sm",
                }, ...__VLS_functionalComponentArgsRest(__VLS_218));
                let __VLS_221;
                let __VLS_222;
                let __VLS_223;
                const __VLS_224 = {
                    onClick: (...[$event]) => {
                        if (!(__VLS_ctx.settingsScope))
                            return;
                        if (!!(__VLS_ctx.editingKey === s.key))
                            return;
                        __VLS_ctx.startEditSetting(s);
                    }
                };
                __VLS_220.slots.default;
                (__VLS_ctx.$t('scopes.settingsPanel.edit'));
                var __VLS_220;
                const __VLS_225 = {}.VButton;
                /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
                // @ts-ignore
                const __VLS_226 = __VLS_asFunctionalComponent(__VLS_225, new __VLS_225({
                    ...{ 'onClick': {} },
                    variant: "ghost",
                    size: "sm",
                }));
                const __VLS_227 = __VLS_226({
                    ...{ 'onClick': {} },
                    variant: "ghost",
                    size: "sm",
                }, ...__VLS_functionalComponentArgsRest(__VLS_226));
                let __VLS_229;
                let __VLS_230;
                let __VLS_231;
                const __VLS_232 = {
                    onClick: (...[$event]) => {
                        if (!(__VLS_ctx.settingsScope))
                            return;
                        if (!!(__VLS_ctx.editingKey === s.key))
                            return;
                        __VLS_ctx.deleteSetting(s);
                    }
                };
                __VLS_228.slots.default;
                (__VLS_ctx.$t('scopes.settingsPanel.deleteLabel'));
                var __VLS_228;
            }
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "border-t border-base-300 pt-3 mt-2 flex flex-col gap-2" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.h4, __VLS_intrinsicElements.h4)({
            ...{ class: "text-xs uppercase opacity-60" },
        });
        (__VLS_ctx.$t('scopes.settingsPanel.addTitle'));
        const __VLS_233 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_234 = __VLS_asFunctionalComponent(__VLS_233, new __VLS_233({
            modelValue: (__VLS_ctx.newSettingKey),
            label: (__VLS_ctx.$t('scopes.settingsPanel.keyLabel')),
            placeholder: (__VLS_ctx.$t('scopes.settingsPanel.keyPlaceholder')),
        }));
        const __VLS_235 = __VLS_234({
            modelValue: (__VLS_ctx.newSettingKey),
            label: (__VLS_ctx.$t('scopes.settingsPanel.keyLabel')),
            placeholder: (__VLS_ctx.$t('scopes.settingsPanel.keyPlaceholder')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_234));
        const __VLS_237 = {}.VSelect;
        /** @type {[typeof __VLS_components.VSelect, ]} */ ;
        // @ts-ignore
        const __VLS_238 = __VLS_asFunctionalComponent(__VLS_237, new __VLS_237({
            modelValue: (__VLS_ctx.newSettingType),
            label: (__VLS_ctx.$t('scopes.settingsPanel.typeLabel')),
            options: (__VLS_ctx.settingTypeOptions),
        }));
        const __VLS_239 = __VLS_238({
            modelValue: (__VLS_ctx.newSettingType),
            label: (__VLS_ctx.$t('scopes.settingsPanel.typeLabel')),
            options: (__VLS_ctx.settingTypeOptions),
        }, ...__VLS_functionalComponentArgsRest(__VLS_238));
        if (__VLS_ctx.newSettingType !== __VLS_ctx.SettingType.PASSWORD) {
            const __VLS_241 = {}.VInput;
            /** @type {[typeof __VLS_components.VInput, ]} */ ;
            // @ts-ignore
            const __VLS_242 = __VLS_asFunctionalComponent(__VLS_241, new __VLS_241({
                modelValue: (__VLS_ctx.newSettingValue),
                label: (__VLS_ctx.$t('scopes.settingsPanel.valueLabel')),
            }));
            const __VLS_243 = __VLS_242({
                modelValue: (__VLS_ctx.newSettingValue),
                label: (__VLS_ctx.$t('scopes.settingsPanel.valueLabel')),
            }, ...__VLS_functionalComponentArgsRest(__VLS_242));
        }
        else {
            const __VLS_245 = {}.VInput;
            /** @type {[typeof __VLS_components.VInput, ]} */ ;
            // @ts-ignore
            const __VLS_246 = __VLS_asFunctionalComponent(__VLS_245, new __VLS_245({
                modelValue: (__VLS_ctx.newSettingValue),
                type: "password",
                label: (__VLS_ctx.$t('scopes.settingsPanel.passwordLabel')),
            }));
            const __VLS_247 = __VLS_246({
                modelValue: (__VLS_ctx.newSettingValue),
                type: "password",
                label: (__VLS_ctx.$t('scopes.settingsPanel.passwordLabel')),
            }, ...__VLS_functionalComponentArgsRest(__VLS_246));
        }
        const __VLS_249 = {}.VTextarea;
        /** @type {[typeof __VLS_components.VTextarea, ]} */ ;
        // @ts-ignore
        const __VLS_250 = __VLS_asFunctionalComponent(__VLS_249, new __VLS_249({
            modelValue: (__VLS_ctx.newSettingDescription),
            label: (__VLS_ctx.$t('scopes.settingsPanel.descriptionOptional')),
            rows: (2),
        }));
        const __VLS_251 = __VLS_250({
            modelValue: (__VLS_ctx.newSettingDescription),
            label: (__VLS_ctx.$t('scopes.settingsPanel.descriptionOptional')),
            rows: (2),
        }, ...__VLS_functionalComponentArgsRest(__VLS_250));
        const __VLS_253 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_254 = __VLS_asFunctionalComponent(__VLS_253, new __VLS_253({
            ...{ 'onClick': {} },
            variant: "primary",
            size: "sm",
            disabled: (!__VLS_ctx.newSettingKey.trim()),
            loading: (__VLS_ctx.settingsState.busy.value),
        }));
        const __VLS_255 = __VLS_254({
            ...{ 'onClick': {} },
            variant: "primary",
            size: "sm",
            disabled: (!__VLS_ctx.newSettingKey.trim()),
            loading: (__VLS_ctx.settingsState.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_254));
        let __VLS_257;
        let __VLS_258;
        let __VLS_259;
        const __VLS_260 = {
            onClick: (__VLS_ctx.addSetting)
        };
        __VLS_256.slots.default;
        (__VLS_ctx.$t('scopes.settingsPanel.add'));
        var __VLS_256;
    }
}
const __VLS_261 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_262 = __VLS_asFunctionalComponent(__VLS_261, new __VLS_261({
    modelValue: (__VLS_ctx.showCreateGroup),
    title: (__VLS_ctx.$t('scopes.createGroup.title')),
}));
const __VLS_263 = __VLS_262({
    modelValue: (__VLS_ctx.showCreateGroup),
    title: (__VLS_ctx.$t('scopes.createGroup.title')),
}, ...__VLS_functionalComponentArgsRest(__VLS_262));
__VLS_264.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
const __VLS_265 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_266 = __VLS_asFunctionalComponent(__VLS_265, new __VLS_265({
    modelValue: (__VLS_ctx.newGroupName),
    label: (__VLS_ctx.$t('scopes.common.name')),
    required: true,
    help: (__VLS_ctx.$t('scopes.createGroup.nameHelp')),
}));
const __VLS_267 = __VLS_266({
    modelValue: (__VLS_ctx.newGroupName),
    label: (__VLS_ctx.$t('scopes.common.name')),
    required: true,
    help: (__VLS_ctx.$t('scopes.createGroup.nameHelp')),
}, ...__VLS_functionalComponentArgsRest(__VLS_266));
const __VLS_269 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_270 = __VLS_asFunctionalComponent(__VLS_269, new __VLS_269({
    modelValue: (__VLS_ctx.newGroupTitle),
    label: (__VLS_ctx.$t('scopes.common.title')),
}));
const __VLS_271 = __VLS_270({
    modelValue: (__VLS_ctx.newGroupTitle),
    label: (__VLS_ctx.$t('scopes.common.title')),
}, ...__VLS_functionalComponentArgsRest(__VLS_270));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex justify-end gap-2" },
});
const __VLS_273 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_274 = __VLS_asFunctionalComponent(__VLS_273, new __VLS_273({
    ...{ 'onClick': {} },
    variant: "ghost",
}));
const __VLS_275 = __VLS_274({
    ...{ 'onClick': {} },
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_274));
let __VLS_277;
let __VLS_278;
let __VLS_279;
const __VLS_280 = {
    onClick: (...[$event]) => {
        __VLS_ctx.showCreateGroup = false;
    }
};
__VLS_276.slots.default;
(__VLS_ctx.$t('scopes.common.cancel'));
var __VLS_276;
const __VLS_281 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_282 = __VLS_asFunctionalComponent(__VLS_281, new __VLS_281({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.newGroupName.trim()),
    loading: (__VLS_ctx.groupsState.busy.value),
}));
const __VLS_283 = __VLS_282({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.newGroupName.trim()),
    loading: (__VLS_ctx.groupsState.busy.value),
}, ...__VLS_functionalComponentArgsRest(__VLS_282));
let __VLS_285;
let __VLS_286;
let __VLS_287;
const __VLS_288 = {
    onClick: (__VLS_ctx.submitCreateGroup)
};
__VLS_284.slots.default;
(__VLS_ctx.$t('scopes.common.create'));
var __VLS_284;
var __VLS_264;
const __VLS_289 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_290 = __VLS_asFunctionalComponent(__VLS_289, new __VLS_289({
    modelValue: (__VLS_ctx.showKitDialog),
    title: (__VLS_ctx.kitDialogTitle),
    closeOnBackdrop: (false),
}));
const __VLS_291 = __VLS_290({
    modelValue: (__VLS_ctx.showKitDialog),
    title: (__VLS_ctx.kitDialogTitle),
    closeOnBackdrop: (false),
}, ...__VLS_functionalComponentArgsRest(__VLS_290));
__VLS_292.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
if (__VLS_ctx.kitState.error.value) {
    const __VLS_293 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_294 = __VLS_asFunctionalComponent(__VLS_293, new __VLS_293({
        variant: "error",
    }));
    const __VLS_295 = __VLS_294({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_294));
    __VLS_296.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.kitState.error.value);
    var __VLS_296;
}
const __VLS_297 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_298 = __VLS_asFunctionalComponent(__VLS_297, new __VLS_297({
    modelValue: (__VLS_ctx.kitForm.url),
    label: (__VLS_ctx.$t('scopes.kit.dialog.repoUrl')),
    required: (__VLS_ctx.kitNeedsUrl),
    help: (__VLS_ctx.kitDialogMode === 'update' || __VLS_ctx.kitDialogMode === 'export'
        ? __VLS_ctx.$t('scopes.kit.dialog.repoUrlReuseHelp')
        : __VLS_ctx.$t('scopes.kit.dialog.repoUrlHelp')),
}));
const __VLS_299 = __VLS_298({
    modelValue: (__VLS_ctx.kitForm.url),
    label: (__VLS_ctx.$t('scopes.kit.dialog.repoUrl')),
    required: (__VLS_ctx.kitNeedsUrl),
    help: (__VLS_ctx.kitDialogMode === 'update' || __VLS_ctx.kitDialogMode === 'export'
        ? __VLS_ctx.$t('scopes.kit.dialog.repoUrlReuseHelp')
        : __VLS_ctx.$t('scopes.kit.dialog.repoUrlHelp')),
}, ...__VLS_functionalComponentArgsRest(__VLS_298));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "grid grid-cols-2 gap-3" },
});
const __VLS_301 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_302 = __VLS_asFunctionalComponent(__VLS_301, new __VLS_301({
    modelValue: (__VLS_ctx.kitForm.path),
    label: (__VLS_ctx.$t('scopes.kit.dialog.subPath')),
    help: (__VLS_ctx.$t('scopes.kit.dialog.subPathHelp')),
}));
const __VLS_303 = __VLS_302({
    modelValue: (__VLS_ctx.kitForm.path),
    label: (__VLS_ctx.$t('scopes.kit.dialog.subPath')),
    help: (__VLS_ctx.$t('scopes.kit.dialog.subPathHelp')),
}, ...__VLS_functionalComponentArgsRest(__VLS_302));
const __VLS_305 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_306 = __VLS_asFunctionalComponent(__VLS_305, new __VLS_305({
    modelValue: (__VLS_ctx.kitForm.branch),
    label: (__VLS_ctx.$t('scopes.kit.dialog.branchLabel')),
    help: (__VLS_ctx.$t('scopes.kit.dialog.branchHelp')),
}));
const __VLS_307 = __VLS_306({
    modelValue: (__VLS_ctx.kitForm.branch),
    label: (__VLS_ctx.$t('scopes.kit.dialog.branchLabel')),
    help: (__VLS_ctx.$t('scopes.kit.dialog.branchHelp')),
}, ...__VLS_functionalComponentArgsRest(__VLS_306));
if (__VLS_ctx.kitDialogMode !== 'export') {
    const __VLS_309 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_310 = __VLS_asFunctionalComponent(__VLS_309, new __VLS_309({
        modelValue: (__VLS_ctx.kitForm.commit),
        label: (__VLS_ctx.$t('scopes.kit.dialog.commitSha')),
        help: (__VLS_ctx.$t('scopes.kit.dialog.commitShaHelp')),
    }));
    const __VLS_311 = __VLS_310({
        modelValue: (__VLS_ctx.kitForm.commit),
        label: (__VLS_ctx.$t('scopes.kit.dialog.commitSha')),
        help: (__VLS_ctx.$t('scopes.kit.dialog.commitShaHelp')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_310));
}
const __VLS_313 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_314 = __VLS_asFunctionalComponent(__VLS_313, new __VLS_313({
    modelValue: (__VLS_ctx.kitForm.token),
    type: "password",
    label: (__VLS_ctx.$t('scopes.kit.dialog.authToken')),
    help: (__VLS_ctx.$t('scopes.kit.dialog.authTokenHelp')),
}));
const __VLS_315 = __VLS_314({
    modelValue: (__VLS_ctx.kitForm.token),
    type: "password",
    label: (__VLS_ctx.$t('scopes.kit.dialog.authToken')),
    help: (__VLS_ctx.$t('scopes.kit.dialog.authTokenHelp')),
}, ...__VLS_functionalComponentArgsRest(__VLS_314));
const __VLS_317 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_318 = __VLS_asFunctionalComponent(__VLS_317, new __VLS_317({
    modelValue: (__VLS_ctx.kitForm.vaultPassword),
    type: "password",
    label: (__VLS_ctx.$t('scopes.kit.dialog.vaultPassword')),
    help: (__VLS_ctx.kitDialogMode === 'export'
        ? __VLS_ctx.$t('scopes.kit.dialog.vaultPasswordExportHelp')
        : __VLS_ctx.$t('scopes.kit.dialog.vaultPasswordImportHelp')),
}));
const __VLS_319 = __VLS_318({
    modelValue: (__VLS_ctx.kitForm.vaultPassword),
    type: "password",
    label: (__VLS_ctx.$t('scopes.kit.dialog.vaultPassword')),
    help: (__VLS_ctx.kitDialogMode === 'export'
        ? __VLS_ctx.$t('scopes.kit.dialog.vaultPasswordExportHelp')
        : __VLS_ctx.$t('scopes.kit.dialog.vaultPasswordImportHelp')),
}, ...__VLS_functionalComponentArgsRest(__VLS_318));
if (__VLS_ctx.kitDialogMode === 'export') {
    const __VLS_321 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_322 = __VLS_asFunctionalComponent(__VLS_321, new __VLS_321({
        modelValue: (__VLS_ctx.kitForm.commitMessage),
        label: (__VLS_ctx.$t('scopes.kit.dialog.commitMessage')),
        help: (__VLS_ctx.$t('scopes.kit.dialog.commitMessageHelp')),
    }));
    const __VLS_323 = __VLS_322({
        modelValue: (__VLS_ctx.kitForm.commitMessage),
        label: (__VLS_ctx.$t('scopes.kit.dialog.commitMessage')),
        help: (__VLS_ctx.$t('scopes.kit.dialog.commitMessageHelp')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_322));
}
if (__VLS_ctx.kitDialogMode === 'update') {
    const __VLS_325 = {}.VCheckbox;
    /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
    // @ts-ignore
    const __VLS_326 = __VLS_asFunctionalComponent(__VLS_325, new __VLS_325({
        modelValue: (__VLS_ctx.kitForm.prune),
        label: (__VLS_ctx.$t('scopes.kit.dialog.prune')),
        help: (__VLS_ctx.$t('scopes.kit.dialog.pruneHelp')),
    }));
    const __VLS_327 = __VLS_326({
        modelValue: (__VLS_ctx.kitForm.prune),
        label: (__VLS_ctx.$t('scopes.kit.dialog.prune')),
        help: (__VLS_ctx.$t('scopes.kit.dialog.pruneHelp')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_326));
}
if (__VLS_ctx.kitDialogMode === 'apply') {
    const __VLS_329 = {}.VCheckbox;
    /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
    // @ts-ignore
    const __VLS_330 = __VLS_asFunctionalComponent(__VLS_329, new __VLS_329({
        modelValue: (__VLS_ctx.kitForm.keepPasswords),
        label: (__VLS_ctx.$t('scopes.kit.dialog.keepPasswords')),
        help: (__VLS_ctx.$t('scopes.kit.dialog.keepPasswordsHelp')),
    }));
    const __VLS_331 = __VLS_330({
        modelValue: (__VLS_ctx.kitForm.keepPasswords),
        label: (__VLS_ctx.$t('scopes.kit.dialog.keepPasswords')),
        help: (__VLS_ctx.$t('scopes.kit.dialog.keepPasswordsHelp')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_330));
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex justify-end gap-2 pt-2" },
});
const __VLS_333 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_334 = __VLS_asFunctionalComponent(__VLS_333, new __VLS_333({
    ...{ 'onClick': {} },
    variant: "ghost",
}));
const __VLS_335 = __VLS_334({
    ...{ 'onClick': {} },
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_334));
let __VLS_337;
let __VLS_338;
let __VLS_339;
const __VLS_340 = {
    onClick: (...[$event]) => {
        __VLS_ctx.showKitDialog = false;
    }
};
__VLS_336.slots.default;
(__VLS_ctx.$t('scopes.common.cancel'));
var __VLS_336;
const __VLS_341 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_342 = __VLS_asFunctionalComponent(__VLS_341, new __VLS_341({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (__VLS_ctx.kitNeedsUrl && !__VLS_ctx.kitForm.url.trim()),
    loading: (__VLS_ctx.kitState.busy.value),
}));
const __VLS_343 = __VLS_342({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (__VLS_ctx.kitNeedsUrl && !__VLS_ctx.kitForm.url.trim()),
    loading: (__VLS_ctx.kitState.busy.value),
}, ...__VLS_functionalComponentArgsRest(__VLS_342));
let __VLS_345;
let __VLS_346;
let __VLS_347;
const __VLS_348 = {
    onClick: (__VLS_ctx.submitKitDialog)
};
__VLS_344.slots.default;
(__VLS_ctx.kitDialogSubmitLabel);
var __VLS_344;
var __VLS_292;
const __VLS_349 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_350 = __VLS_asFunctionalComponent(__VLS_349, new __VLS_349({
    modelValue: (__VLS_ctx.showCreateProject),
    title: (__VLS_ctx.$t('scopes.createProject.title')),
}));
const __VLS_351 = __VLS_350({
    modelValue: (__VLS_ctx.showCreateProject),
    title: (__VLS_ctx.$t('scopes.createProject.title')),
}, ...__VLS_functionalComponentArgsRest(__VLS_350));
__VLS_352.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
const __VLS_353 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_354 = __VLS_asFunctionalComponent(__VLS_353, new __VLS_353({
    modelValue: (__VLS_ctx.newProjectName),
    label: (__VLS_ctx.$t('scopes.common.name')),
    required: true,
    help: (__VLS_ctx.$t('scopes.createProject.nameHelp')),
}));
const __VLS_355 = __VLS_354({
    modelValue: (__VLS_ctx.newProjectName),
    label: (__VLS_ctx.$t('scopes.common.name')),
    required: true,
    help: (__VLS_ctx.$t('scopes.createProject.nameHelp')),
}, ...__VLS_functionalComponentArgsRest(__VLS_354));
const __VLS_357 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_358 = __VLS_asFunctionalComponent(__VLS_357, new __VLS_357({
    modelValue: (__VLS_ctx.newProjectTitle),
    label: (__VLS_ctx.$t('scopes.common.title')),
}));
const __VLS_359 = __VLS_358({
    modelValue: (__VLS_ctx.newProjectTitle),
    label: (__VLS_ctx.$t('scopes.common.title')),
}, ...__VLS_functionalComponentArgsRest(__VLS_358));
const __VLS_361 = {}.VSelect;
/** @type {[typeof __VLS_components.VSelect, ]} */ ;
// @ts-ignore
const __VLS_362 = __VLS_asFunctionalComponent(__VLS_361, new __VLS_361({
    modelValue: (__VLS_ctx.newProjectGroupId),
    label: (__VLS_ctx.$t('scopes.project.groupLabel')),
    options: (__VLS_ctx.groupSelectOptions),
}));
const __VLS_363 = __VLS_362({
    modelValue: (__VLS_ctx.newProjectGroupId),
    label: (__VLS_ctx.$t('scopes.project.groupLabel')),
    options: (__VLS_ctx.groupSelectOptions),
}, ...__VLS_functionalComponentArgsRest(__VLS_362));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex justify-end gap-2" },
});
const __VLS_365 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_366 = __VLS_asFunctionalComponent(__VLS_365, new __VLS_365({
    ...{ 'onClick': {} },
    variant: "ghost",
}));
const __VLS_367 = __VLS_366({
    ...{ 'onClick': {} },
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_366));
let __VLS_369;
let __VLS_370;
let __VLS_371;
const __VLS_372 = {
    onClick: (...[$event]) => {
        __VLS_ctx.showCreateProject = false;
    }
};
__VLS_368.slots.default;
(__VLS_ctx.$t('scopes.common.cancel'));
var __VLS_368;
const __VLS_373 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_374 = __VLS_asFunctionalComponent(__VLS_373, new __VLS_373({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.newProjectName.trim()),
    loading: (__VLS_ctx.projectsState.busy.value),
}));
const __VLS_375 = __VLS_374({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.newProjectName.trim()),
    loading: (__VLS_ctx.projectsState.busy.value),
}, ...__VLS_functionalComponentArgsRest(__VLS_374));
let __VLS_377;
let __VLS_378;
let __VLS_379;
const __VLS_380 = {
    onClick: (__VLS_ctx.submitCreateProject)
};
__VLS_376.slots.default;
(__VLS_ctx.$t('scopes.common.create'));
var __VLS_376;
var __VLS_352;
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item--active']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['mr-1']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item--active']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['mr-1']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item--child']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item--active']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item--child']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item--active']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['p-6']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-2xl']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-2']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-4']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-y-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-baseline']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-2']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-4']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-y-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['break-all']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['pt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['pt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['border-t']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['pt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-90']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-90']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-90']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['divide-y']} */ ;
/** @type {__VLS_StyleScopedClasses['divide-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['setting-row']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['break-words']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['border-t']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['pt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-2']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['pt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
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
            SettingType: SettingType,
            tenantState: tenantState,
            groupsState: groupsState,
            projectsState: projectsState,
            settingsState: settingsState,
            kitState: kitState,
            selection: selection,
            banner: banner,
            form: form,
            showCreateGroup: showCreateGroup,
            newGroupName: newGroupName,
            newGroupTitle: newGroupTitle,
            showCreateProject: showCreateProject,
            newProjectName: newProjectName,
            newProjectTitle: newProjectTitle,
            newProjectGroupId: newProjectGroupId,
            showKitDialog: showKitDialog,
            kitDialogMode: kitDialogMode,
            kitForm: kitForm,
            newSettingKey: newSettingKey,
            newSettingType: newSettingType,
            newSettingValue: newSettingValue,
            newSettingDescription: newSettingDescription,
            editingKey: editingKey,
            editValue: editValue,
            editDescription: editDescription,
            settingTypeOptions: settingTypeOptions,
            selectedGroup: selectedGroup,
            selectedProject: selectedProject,
            projectsByGroup: projectsByGroup,
            ungroupedProjects: ungroupedProjects,
            groupSelectOptions: groupSelectOptions,
            settingsScope: settingsScope,
            isReservedGroup: isReservedGroup,
            isArchivedProject: isArchivedProject,
            selectTenant: selectTenant,
            selectGroup: selectGroup,
            selectProject: selectProject,
            saveTenant: saveTenant,
            saveGroup: saveGroup,
            deleteGroup: deleteGroup,
            saveProject: saveProject,
            archiveProject: archiveProject,
            openCreateGroup: openCreateGroup,
            submitCreateGroup: submitCreateGroup,
            openCreateProject: openCreateProject,
            submitCreateProject: submitCreateProject,
            kitDialogTitle: kitDialogTitle,
            kitDialogSubmitLabel: kitDialogSubmitLabel,
            kitNeedsUrl: kitNeedsUrl,
            openKitDialog: openKitDialog,
            submitKitDialog: submitKitDialog,
            addSetting: addSetting,
            startEditSetting: startEditSetting,
            saveEditSetting: saveEditSetting,
            cancelEditSetting: cancelEditSetting,
            deleteSetting: deleteSetting,
            isSelected: isSelected,
            breadcrumbs: breadcrumbs,
            combinedError: combinedError,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=ScopesApp.vue.js.map