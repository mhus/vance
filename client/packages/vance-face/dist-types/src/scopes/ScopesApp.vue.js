import { computed, onMounted, reactive, ref, watch } from 'vue';
import { EditorShell, VAlert, VButton, VCard, VCheckbox, VEmptyState, VInput, VModal, VSelect, VTextarea, } from '@/components';
import { useAdminTenant } from '@/composables/useAdminTenant';
import { useAdminProjectGroups } from '@/composables/useAdminProjectGroups';
import { useAdminProjects } from '@/composables/useAdminProjects';
import { useScopeSettings } from '@/composables/useScopeSettings';
import { SettingType, } from '@vance/generated';
const ARCHIVED_GROUP = 'archived';
const tenantState = useAdminTenant();
const groupsState = useAdminProjectGroups();
const projectsState = useAdminProjects();
const settingsState = useScopeSettings();
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
// ─── Setting editor state ───
const newSettingKey = ref('');
const newSettingType = ref(SettingType.STRING);
const newSettingValue = ref('');
const newSettingDescription = ref('');
const editingKey = ref(null);
const editValue = ref('');
const editDescription = ref('');
const settingTypeOptions = [
    { value: SettingType.STRING, label: 'String' },
    { value: SettingType.INT, label: 'Int' },
    { value: SettingType.LONG, label: 'Long' },
    { value: SettingType.DOUBLE, label: 'Double' },
    { value: SettingType.BOOLEAN, label: 'Boolean' },
    { value: SettingType.PASSWORD, label: 'Password' },
];
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
    { value: '', label: '(no group)' },
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
});
watch(selection, () => {
    applySelectionToForm();
    loadSettingsForSelection();
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
        banner.value = 'Tenant saved.';
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
        banner.value = 'Group saved.';
    }
    catch {
        /* state.error */
    }
}
async function deleteGroup() {
    if (selection.value.kind !== 'group')
        return;
    if (!confirm(`Delete group "${selection.value.name}"? This is only possible if the group is empty.`))
        return;
    const name = selection.value.name;
    try {
        await groupsState.remove(name);
        selectTenant();
        banner.value = `Group "${name}" deleted.`;
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
        banner.value = 'Project saved.';
    }
    catch {
        /* state.error */
    }
}
async function archiveProject() {
    if (selection.value.kind !== 'project')
        return;
    if (!confirm(`Archive project "${selection.value.name}"? It will be moved to the "archived" group.`))
        return;
    try {
        await projectsState.archive(selection.value.name);
        banner.value = `Project archived.`;
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
        banner.value = `Group "${name}" created.`;
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
        banner.value = `Project "${name}" created.`;
    }
    catch {
        /* state.error */
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
    if (!confirm(`Delete setting "${s.key}"?`))
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
        return [tenantLabel, `Group: ${groupTitle(sel.name)}`];
    }
    return [tenantLabel, `Project: ${selectedProject.value?.title || sel.name}`];
});
const combinedError = computed(() => tenantState.error.value
    || groupsState.error.value
    || projectsState.error.value
    || settingsState.error.value);
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
    title: "Scopes",
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    wideRightPanel: true,
}));
const __VLS_2 = __VLS_1({
    title: "Scopes",
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
            }
        }
    }
    if (__VLS_ctx.ungroupedProjects.length > 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "mt-3 px-2 text-xs uppercase opacity-50" },
        });
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
        title: "Tenant",
    }));
    const __VLS_31 = __VLS_30({
        title: "Tenant",
    }, ...__VLS_functionalComponentArgsRest(__VLS_30));
    __VLS_32.slots.default;
    if (!__VLS_ctx.tenantState.tenant.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "opacity-70" },
        });
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
            label: "Name",
            disabled: true,
            help: "Tenant name is immutable.",
        }));
        const __VLS_35 = __VLS_34({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.tenantState.tenant.value.name),
            label: "Name",
            disabled: true,
            help: "Tenant name is immutable.",
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
            label: "Title",
        }));
        const __VLS_43 = __VLS_42({
            modelValue: (__VLS_ctx.form.title),
            label: "Title",
        }, ...__VLS_functionalComponentArgsRest(__VLS_42));
        const __VLS_45 = {}.VCheckbox;
        /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
        // @ts-ignore
        const __VLS_46 = __VLS_asFunctionalComponent(__VLS_45, new __VLS_45({
            modelValue: (__VLS_ctx.form.enabled),
            label: "Enabled",
        }));
        const __VLS_47 = __VLS_46({
            modelValue: (__VLS_ctx.form.enabled),
            label: "Enabled",
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
        var __VLS_52;
    }
    var __VLS_32;
}
else if (__VLS_ctx.selection.kind === 'group') {
    const __VLS_57 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_58 = __VLS_asFunctionalComponent(__VLS_57, new __VLS_57({
        title: (`Group: ${__VLS_ctx.selection.name}`),
    }));
    const __VLS_59 = __VLS_58({
        title: (`Group: ${__VLS_ctx.selection.name}`),
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
        var __VLS_64;
    }
    if (!__VLS_ctx.selectedGroup) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "opacity-70" },
        });
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
            label: "Name",
            disabled: true,
            help: "Group name is immutable.",
        }));
        const __VLS_67 = __VLS_66({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.selectedGroup.name),
            label: "Name",
            disabled: true,
            help: "Group name is immutable.",
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
            label: "Title",
        }));
        const __VLS_75 = __VLS_74({
            modelValue: (__VLS_ctx.form.title),
            label: "Title",
        }, ...__VLS_functionalComponentArgsRest(__VLS_74));
        const __VLS_77 = {}.VCheckbox;
        /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
        // @ts-ignore
        const __VLS_78 = __VLS_asFunctionalComponent(__VLS_77, new __VLS_77({
            modelValue: (__VLS_ctx.form.enabled),
            label: "Enabled",
        }));
        const __VLS_79 = __VLS_78({
            modelValue: (__VLS_ctx.form.enabled),
            label: "Enabled",
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
        var __VLS_92;
    }
    var __VLS_60;
}
else if (__VLS_ctx.selection.kind === 'project') {
    const __VLS_97 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_98 = __VLS_asFunctionalComponent(__VLS_97, new __VLS_97({
        title: (`Project: ${__VLS_ctx.selection.name}`),
    }));
    const __VLS_99 = __VLS_98({
        title: (`Project: ${__VLS_ctx.selection.name}`),
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
        var __VLS_104;
    }
    if (!__VLS_ctx.selectedProject) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "opacity-70" },
        });
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
            label: "Name",
            disabled: true,
            help: "Project name is immutable.",
        }));
        const __VLS_107 = __VLS_106({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.selectedProject.name),
            label: "Name",
            disabled: true,
            help: "Project name is immutable.",
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
            label: "Title",
        }));
        const __VLS_115 = __VLS_114({
            modelValue: (__VLS_ctx.form.title),
            label: "Title",
        }, ...__VLS_functionalComponentArgsRest(__VLS_114));
        const __VLS_117 = {}.VSelect;
        /** @type {[typeof __VLS_components.VSelect, ]} */ ;
        // @ts-ignore
        const __VLS_118 = __VLS_asFunctionalComponent(__VLS_117, new __VLS_117({
            modelValue: (__VLS_ctx.form.projectGroupId),
            label: "Group",
            options: (__VLS_ctx.groupSelectOptions),
        }));
        const __VLS_119 = __VLS_118({
            modelValue: (__VLS_ctx.form.projectGroupId),
            label: "Group",
            options: (__VLS_ctx.groupSelectOptions),
        }, ...__VLS_functionalComponentArgsRest(__VLS_118));
        const __VLS_121 = {}.VCheckbox;
        /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
        // @ts-ignore
        const __VLS_122 = __VLS_asFunctionalComponent(__VLS_121, new __VLS_121({
            modelValue: (__VLS_ctx.form.enabled),
            label: "Enabled",
        }));
        const __VLS_123 = __VLS_122({
            modelValue: (__VLS_ctx.form.enabled),
            label: "Enabled",
        }, ...__VLS_functionalComponentArgsRest(__VLS_122));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dl, __VLS_intrinsicElements.dl)({
            ...{ class: "grid grid-cols-2 gap-x-4 gap-y-1 text-sm opacity-80" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
            ...{ class: "opacity-60" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
        (__VLS_ctx.selectedProject.status);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
            ...{ class: "opacity-60" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
        (__VLS_ctx.selectedProject.podIp ?? '—');
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
            ...{ class: "opacity-60" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
        (__VLS_ctx.selectedProject.claimedAt ?? '—');
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
            ...{ class: "opacity-60" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
        (__VLS_ctx.selectedProject.createdAt ?? '—');
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
        var __VLS_136;
    }
    var __VLS_100;
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
        (__VLS_ctx.settingsScope.type);
        (__VLS_ctx.settingsScope.id);
        if (!__VLS_ctx.settingsState.loading.value && __VLS_ctx.settingsState.settings.value.length === 0) {
            const __VLS_141 = {}.VEmptyState;
            /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
            // @ts-ignore
            const __VLS_142 = __VLS_asFunctionalComponent(__VLS_141, new __VLS_141({
                headline: "No settings",
                body: "Add a key/value below to configure this scope.",
            }));
            const __VLS_143 = __VLS_142({
                headline: "No settings",
                body: "Add a key/value below to configure this scope.",
            }, ...__VLS_functionalComponentArgsRest(__VLS_142));
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
                    const __VLS_145 = {}.VInput;
                    /** @type {[typeof __VLS_components.VInput, ]} */ ;
                    // @ts-ignore
                    const __VLS_146 = __VLS_asFunctionalComponent(__VLS_145, new __VLS_145({
                        modelValue: (__VLS_ctx.editValue),
                        label: "Value",
                    }));
                    const __VLS_147 = __VLS_146({
                        modelValue: (__VLS_ctx.editValue),
                        label: "Value",
                    }, ...__VLS_functionalComponentArgsRest(__VLS_146));
                }
                else {
                    const __VLS_149 = {}.VInput;
                    /** @type {[typeof __VLS_components.VInput, ]} */ ;
                    // @ts-ignore
                    const __VLS_150 = __VLS_asFunctionalComponent(__VLS_149, new __VLS_149({
                        modelValue: (__VLS_ctx.editValue),
                        type: "password",
                        label: "New password",
                        placeholder: "(leave empty to clear)",
                    }));
                    const __VLS_151 = __VLS_150({
                        modelValue: (__VLS_ctx.editValue),
                        type: "password",
                        label: "New password",
                        placeholder: "(leave empty to clear)",
                    }, ...__VLS_functionalComponentArgsRest(__VLS_150));
                }
                const __VLS_153 = {}.VTextarea;
                /** @type {[typeof __VLS_components.VTextarea, ]} */ ;
                // @ts-ignore
                const __VLS_154 = __VLS_asFunctionalComponent(__VLS_153, new __VLS_153({
                    modelValue: (__VLS_ctx.editDescription),
                    label: "Description",
                    rows: (2),
                }));
                const __VLS_155 = __VLS_154({
                    modelValue: (__VLS_ctx.editDescription),
                    label: "Description",
                    rows: (2),
                }, ...__VLS_functionalComponentArgsRest(__VLS_154));
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "flex justify-end gap-2 mt-1" },
                });
                const __VLS_157 = {}.VButton;
                /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
                // @ts-ignore
                const __VLS_158 = __VLS_asFunctionalComponent(__VLS_157, new __VLS_157({
                    ...{ 'onClick': {} },
                    variant: "ghost",
                    size: "sm",
                }));
                const __VLS_159 = __VLS_158({
                    ...{ 'onClick': {} },
                    variant: "ghost",
                    size: "sm",
                }, ...__VLS_functionalComponentArgsRest(__VLS_158));
                let __VLS_161;
                let __VLS_162;
                let __VLS_163;
                const __VLS_164 = {
                    onClick: (__VLS_ctx.cancelEditSetting)
                };
                __VLS_160.slots.default;
                var __VLS_160;
                const __VLS_165 = {}.VButton;
                /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
                // @ts-ignore
                const __VLS_166 = __VLS_asFunctionalComponent(__VLS_165, new __VLS_165({
                    ...{ 'onClick': {} },
                    variant: "primary",
                    size: "sm",
                    loading: (__VLS_ctx.settingsState.busy.value),
                }));
                const __VLS_167 = __VLS_166({
                    ...{ 'onClick': {} },
                    variant: "primary",
                    size: "sm",
                    loading: (__VLS_ctx.settingsState.busy.value),
                }, ...__VLS_functionalComponentArgsRest(__VLS_166));
                let __VLS_169;
                let __VLS_170;
                let __VLS_171;
                const __VLS_172 = {
                    onClick: (...[$event]) => {
                        if (!(__VLS_ctx.settingsScope))
                            return;
                        if (!(__VLS_ctx.editingKey === s.key))
                            return;
                        __VLS_ctx.saveEditSetting(s);
                    }
                };
                __VLS_168.slots.default;
                var __VLS_168;
            }
            else {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "text-sm break-words" },
                });
                if (s.type === __VLS_ctx.SettingType.PASSWORD) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "opacity-70" },
                    });
                    (s.value ?? '(empty)');
                }
                else {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                    (s.value ?? '(empty)');
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
                const __VLS_173 = {}.VButton;
                /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
                // @ts-ignore
                const __VLS_174 = __VLS_asFunctionalComponent(__VLS_173, new __VLS_173({
                    ...{ 'onClick': {} },
                    variant: "ghost",
                    size: "sm",
                }));
                const __VLS_175 = __VLS_174({
                    ...{ 'onClick': {} },
                    variant: "ghost",
                    size: "sm",
                }, ...__VLS_functionalComponentArgsRest(__VLS_174));
                let __VLS_177;
                let __VLS_178;
                let __VLS_179;
                const __VLS_180 = {
                    onClick: (...[$event]) => {
                        if (!(__VLS_ctx.settingsScope))
                            return;
                        if (!!(__VLS_ctx.editingKey === s.key))
                            return;
                        __VLS_ctx.startEditSetting(s);
                    }
                };
                __VLS_176.slots.default;
                var __VLS_176;
                const __VLS_181 = {}.VButton;
                /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
                // @ts-ignore
                const __VLS_182 = __VLS_asFunctionalComponent(__VLS_181, new __VLS_181({
                    ...{ 'onClick': {} },
                    variant: "ghost",
                    size: "sm",
                }));
                const __VLS_183 = __VLS_182({
                    ...{ 'onClick': {} },
                    variant: "ghost",
                    size: "sm",
                }, ...__VLS_functionalComponentArgsRest(__VLS_182));
                let __VLS_185;
                let __VLS_186;
                let __VLS_187;
                const __VLS_188 = {
                    onClick: (...[$event]) => {
                        if (!(__VLS_ctx.settingsScope))
                            return;
                        if (!!(__VLS_ctx.editingKey === s.key))
                            return;
                        __VLS_ctx.deleteSetting(s);
                    }
                };
                __VLS_184.slots.default;
                var __VLS_184;
            }
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "border-t border-base-300 pt-3 mt-2 flex flex-col gap-2" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.h4, __VLS_intrinsicElements.h4)({
            ...{ class: "text-xs uppercase opacity-60" },
        });
        const __VLS_189 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_190 = __VLS_asFunctionalComponent(__VLS_189, new __VLS_189({
            modelValue: (__VLS_ctx.newSettingKey),
            label: "Key",
            placeholder: "e.g. ai.default.model",
        }));
        const __VLS_191 = __VLS_190({
            modelValue: (__VLS_ctx.newSettingKey),
            label: "Key",
            placeholder: "e.g. ai.default.model",
        }, ...__VLS_functionalComponentArgsRest(__VLS_190));
        const __VLS_193 = {}.VSelect;
        /** @type {[typeof __VLS_components.VSelect, ]} */ ;
        // @ts-ignore
        const __VLS_194 = __VLS_asFunctionalComponent(__VLS_193, new __VLS_193({
            modelValue: (__VLS_ctx.newSettingType),
            label: "Type",
            options: (__VLS_ctx.settingTypeOptions),
        }));
        const __VLS_195 = __VLS_194({
            modelValue: (__VLS_ctx.newSettingType),
            label: "Type",
            options: (__VLS_ctx.settingTypeOptions),
        }, ...__VLS_functionalComponentArgsRest(__VLS_194));
        if (__VLS_ctx.newSettingType !== __VLS_ctx.SettingType.PASSWORD) {
            const __VLS_197 = {}.VInput;
            /** @type {[typeof __VLS_components.VInput, ]} */ ;
            // @ts-ignore
            const __VLS_198 = __VLS_asFunctionalComponent(__VLS_197, new __VLS_197({
                modelValue: (__VLS_ctx.newSettingValue),
                label: "Value",
            }));
            const __VLS_199 = __VLS_198({
                modelValue: (__VLS_ctx.newSettingValue),
                label: "Value",
            }, ...__VLS_functionalComponentArgsRest(__VLS_198));
        }
        else {
            const __VLS_201 = {}.VInput;
            /** @type {[typeof __VLS_components.VInput, ]} */ ;
            // @ts-ignore
            const __VLS_202 = __VLS_asFunctionalComponent(__VLS_201, new __VLS_201({
                modelValue: (__VLS_ctx.newSettingValue),
                type: "password",
                label: "Password",
            }));
            const __VLS_203 = __VLS_202({
                modelValue: (__VLS_ctx.newSettingValue),
                type: "password",
                label: "Password",
            }, ...__VLS_functionalComponentArgsRest(__VLS_202));
        }
        const __VLS_205 = {}.VTextarea;
        /** @type {[typeof __VLS_components.VTextarea, ]} */ ;
        // @ts-ignore
        const __VLS_206 = __VLS_asFunctionalComponent(__VLS_205, new __VLS_205({
            modelValue: (__VLS_ctx.newSettingDescription),
            label: "Description (optional)",
            rows: (2),
        }));
        const __VLS_207 = __VLS_206({
            modelValue: (__VLS_ctx.newSettingDescription),
            label: "Description (optional)",
            rows: (2),
        }, ...__VLS_functionalComponentArgsRest(__VLS_206));
        const __VLS_209 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_210 = __VLS_asFunctionalComponent(__VLS_209, new __VLS_209({
            ...{ 'onClick': {} },
            variant: "primary",
            size: "sm",
            disabled: (!__VLS_ctx.newSettingKey.trim()),
            loading: (__VLS_ctx.settingsState.busy.value),
        }));
        const __VLS_211 = __VLS_210({
            ...{ 'onClick': {} },
            variant: "primary",
            size: "sm",
            disabled: (!__VLS_ctx.newSettingKey.trim()),
            loading: (__VLS_ctx.settingsState.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_210));
        let __VLS_213;
        let __VLS_214;
        let __VLS_215;
        const __VLS_216 = {
            onClick: (__VLS_ctx.addSetting)
        };
        __VLS_212.slots.default;
        var __VLS_212;
    }
}
const __VLS_217 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_218 = __VLS_asFunctionalComponent(__VLS_217, new __VLS_217({
    modelValue: (__VLS_ctx.showCreateGroup),
    title: "New project group",
}));
const __VLS_219 = __VLS_218({
    modelValue: (__VLS_ctx.showCreateGroup),
    title: "New project group",
}, ...__VLS_functionalComponentArgsRest(__VLS_218));
__VLS_220.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
const __VLS_221 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_222 = __VLS_asFunctionalComponent(__VLS_221, new __VLS_221({
    modelValue: (__VLS_ctx.newGroupName),
    label: "Name",
    required: true,
    help: "Lower-case alphanumerics, '-' or '_' allowed.",
}));
const __VLS_223 = __VLS_222({
    modelValue: (__VLS_ctx.newGroupName),
    label: "Name",
    required: true,
    help: "Lower-case alphanumerics, '-' or '_' allowed.",
}, ...__VLS_functionalComponentArgsRest(__VLS_222));
const __VLS_225 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_226 = __VLS_asFunctionalComponent(__VLS_225, new __VLS_225({
    modelValue: (__VLS_ctx.newGroupTitle),
    label: "Title",
}));
const __VLS_227 = __VLS_226({
    modelValue: (__VLS_ctx.newGroupTitle),
    label: "Title",
}, ...__VLS_functionalComponentArgsRest(__VLS_226));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex justify-end gap-2" },
});
const __VLS_229 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_230 = __VLS_asFunctionalComponent(__VLS_229, new __VLS_229({
    ...{ 'onClick': {} },
    variant: "ghost",
}));
const __VLS_231 = __VLS_230({
    ...{ 'onClick': {} },
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_230));
let __VLS_233;
let __VLS_234;
let __VLS_235;
const __VLS_236 = {
    onClick: (...[$event]) => {
        __VLS_ctx.showCreateGroup = false;
    }
};
__VLS_232.slots.default;
var __VLS_232;
const __VLS_237 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_238 = __VLS_asFunctionalComponent(__VLS_237, new __VLS_237({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.newGroupName.trim()),
    loading: (__VLS_ctx.groupsState.busy.value),
}));
const __VLS_239 = __VLS_238({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.newGroupName.trim()),
    loading: (__VLS_ctx.groupsState.busy.value),
}, ...__VLS_functionalComponentArgsRest(__VLS_238));
let __VLS_241;
let __VLS_242;
let __VLS_243;
const __VLS_244 = {
    onClick: (__VLS_ctx.submitCreateGroup)
};
__VLS_240.slots.default;
var __VLS_240;
var __VLS_220;
const __VLS_245 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_246 = __VLS_asFunctionalComponent(__VLS_245, new __VLS_245({
    modelValue: (__VLS_ctx.showCreateProject),
    title: "New project",
}));
const __VLS_247 = __VLS_246({
    modelValue: (__VLS_ctx.showCreateProject),
    title: "New project",
}, ...__VLS_functionalComponentArgsRest(__VLS_246));
__VLS_248.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
const __VLS_249 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_250 = __VLS_asFunctionalComponent(__VLS_249, new __VLS_249({
    modelValue: (__VLS_ctx.newProjectName),
    label: "Name",
    required: true,
    help: "Lower-case alphanumerics, '-' or '_' allowed.",
}));
const __VLS_251 = __VLS_250({
    modelValue: (__VLS_ctx.newProjectName),
    label: "Name",
    required: true,
    help: "Lower-case alphanumerics, '-' or '_' allowed.",
}, ...__VLS_functionalComponentArgsRest(__VLS_250));
const __VLS_253 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_254 = __VLS_asFunctionalComponent(__VLS_253, new __VLS_253({
    modelValue: (__VLS_ctx.newProjectTitle),
    label: "Title",
}));
const __VLS_255 = __VLS_254({
    modelValue: (__VLS_ctx.newProjectTitle),
    label: "Title",
}, ...__VLS_functionalComponentArgsRest(__VLS_254));
const __VLS_257 = {}.VSelect;
/** @type {[typeof __VLS_components.VSelect, ]} */ ;
// @ts-ignore
const __VLS_258 = __VLS_asFunctionalComponent(__VLS_257, new __VLS_257({
    modelValue: (__VLS_ctx.newProjectGroupId),
    label: "Group",
    options: (__VLS_ctx.groupSelectOptions),
}));
const __VLS_259 = __VLS_258({
    modelValue: (__VLS_ctx.newProjectGroupId),
    label: "Group",
    options: (__VLS_ctx.groupSelectOptions),
}, ...__VLS_functionalComponentArgsRest(__VLS_258));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex justify-end gap-2" },
});
const __VLS_261 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_262 = __VLS_asFunctionalComponent(__VLS_261, new __VLS_261({
    ...{ 'onClick': {} },
    variant: "ghost",
}));
const __VLS_263 = __VLS_262({
    ...{ 'onClick': {} },
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_262));
let __VLS_265;
let __VLS_266;
let __VLS_267;
const __VLS_268 = {
    onClick: (...[$event]) => {
        __VLS_ctx.showCreateProject = false;
    }
};
__VLS_264.slots.default;
var __VLS_264;
const __VLS_269 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_270 = __VLS_asFunctionalComponent(__VLS_269, new __VLS_269({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.newProjectName.trim()),
    loading: (__VLS_ctx.projectsState.busy.value),
}));
const __VLS_271 = __VLS_270({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.newProjectName.trim()),
    loading: (__VLS_ctx.projectsState.busy.value),
}, ...__VLS_functionalComponentArgsRest(__VLS_270));
let __VLS_273;
let __VLS_274;
let __VLS_275;
const __VLS_276 = {
    onClick: (__VLS_ctx.submitCreateProject)
};
__VLS_272.slots.default;
var __VLS_272;
var __VLS_248;
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