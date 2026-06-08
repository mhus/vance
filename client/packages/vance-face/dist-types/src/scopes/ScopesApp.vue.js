import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { EditorShell, ProjectListSidebar, SettingFormView, VAlert, VButton, VCard, VCheckbox, VEmptyState, VInput, VModal, VSelect, VTextarea, } from '@/components';
import { listSettingForms, RestError } from '@vance/shared';
import { useAdminTenant } from '@/composables/useAdminTenant';
import { useAdminProjectGroups } from '@/composables/useAdminProjectGroups';
import { useAdminProjects } from '@/composables/useAdminProjects';
import { useProjectKitsCatalog } from '@/composables/useProjectKitsCatalog';
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
const projectKitsCatalog = useProjectKitsCatalog();
const selection = ref({ kind: 'tenant' });
const banner = ref(null);
// Focus zone driven by user interaction. Sidebar clicks land in
// 'main' so the detail card grows; right-panel interactions move
// focus to 'right' for inspect/edit work. Mirrors DocumentApp /
// ChatApp wiring — see specification/web-ui.md §7.2.1.
const focusZone = ref('main');
// ─── Detail-form state ───
// One blob keyed off the selection — re-populated on every selection change.
const form = reactive({
    title: '',
    enabled: true,
    projectGroupId: null,
});
// ─── Modals ───
// Create-group / create-project modals now live inside the shared
// {@link ProjectListSidebar} component — see template. Scopes only
// reacts to the resulting {@code @data-changed} event to reload its
// own admin composables.
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
    // Spec kits.md §10: "Manifest schreiben?" checkbox. On (default) ⇒
    // install/update — files are tracked in _vance/kit-manifest.yaml.
    // Off ⇒ apply (one-off splat without tracking) — used for tunings,
    // e.g. extra tools that should not be bound to the active kit.
    trackManifest: true,
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
const rightTab = ref('settings');
const settingFormsList = ref([]);
const settingFormsLoading = ref(false);
const settingFormsError = ref(null);
const selectedSettingForm = ref(null);
const settingFormsReloadKey = ref(0);
/**
 * Setting Forms only make sense in project-scoped views — the
 * bundled forms carry {@code availableIn: ["!_*"]} so they're hidden
 * from the tenant context anyway. We still load the listing when a
 * tenant is selected so the empty-state can explain why it's empty.
 */
const settingFormsProjectId = computed(() => {
    if (settingsScope.value?.type === 'project')
        return settingsScope.value.id;
    return undefined;
});
async function loadSettingForms() {
    if (!settingsScope.value) {
        settingFormsList.value = [];
        return;
    }
    settingFormsLoading.value = true;
    settingFormsError.value = null;
    try {
        const res = await listSettingForms(settingFormsProjectId.value);
        settingFormsList.value = res.forms ?? [];
        // Drop a stale selection if the form is no longer in the listing
        // (different project, scope-restricted, …).
        if (selectedSettingForm.value
            && !settingFormsList.value.some((f) => f.name === selectedSettingForm.value)) {
            selectedSettingForm.value = null;
        }
    }
    catch (err) {
        settingFormsError.value = err instanceof RestError ? err.message : String(err);
        settingFormsList.value = [];
    }
    finally {
        settingFormsLoading.value = false;
    }
}
function selectSettingForm(name) {
    selectedSettingForm.value = name;
}
function backToSettingFormsList() {
    selectedSettingForm.value = null;
}
function onSettingFormApplied() {
    // Re-fetch the listing so a fresh apply that touches `availableIn`-relevant
    // settings (e.g. a kit install changing project metadata) re-resolves, and
    // bump the key so the active form refreshes its currentValue display.
    settingFormsReloadKey.value += 1;
    void loadSettingForms();
}
const groupedSettingForms = computed(() => {
    const groups = new Map();
    for (const f of settingFormsList.value) {
        const cat = f.category ?? '';
        if (!groups.has(cat))
            groups.set(cat, []);
        groups.get(cat).push(f);
    }
    for (const list of groups.values()) {
        list.sort((a, b) => a.title.localeCompare(b.title));
    }
    return [...groups.entries()].sort((a, b) => a[0].localeCompare(b[0]));
});
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
        // Kit catalog feeds the create-project modal's kit-dropdown
        // (rendered inside the shared {@link ProjectListSidebar}).
        // Background load — the modal is rarely opened immediately
        // after mount, so this lands well before it's needed.
        projectKitsCatalog.load(),
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
    // Drop the active Setting-Form when the scope changes — the cascade
    // context shifts, the live values would be misleading.
    selectedSettingForm.value = null;
    if (!scope) {
        settingsState.clear();
        settingFormsList.value = [];
        return;
    }
    void settingsState.load(scope.type, scope.id);
    void loadSettingForms();
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
//
// Tenant click stays here because the tenant row lives outside the
// {@link ProjectListSidebar}. Group/project clicks go through the
// shared component which writes back via {@code pickerSelectedNode}.
function selectTenant() {
    selection.value = { kind: 'tenant' };
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
// ─── Create modals / picker bridge ───
//
// The shared {@link ProjectListSidebar} component owns the create-
// group / create-project modals, the drag-and-drop move, and all the
// {@code admin/*} POST/PUT calls. Scopes only:
//   1. wires its tree selection through a writable computed v-model
//      so the picker can flip between group/project rows while the
//      tenant row (which lives outside the picker) maps to "no
//      picker selection";
//   2. exposes its kit catalog as options for the create-project
//      modal so admins can pick a kit at creation time;
//   3. reloads the admin composables on {@code @data-changed} and
//      auto-selects the freshly created entry.
const pickerSelectedNode = computed({
    get: () => {
        const s = selection.value;
        if (s.kind === 'tenant')
            return null;
        return { kind: s.kind, name: s.name };
    },
    set: (v) => {
        if (v == null)
            selection.value = { kind: 'tenant' };
        else
            selection.value = v;
    },
});
const pickerKitOptions = computed(() => [
    { value: '', label: t('common.projectPicker.createProject.kitNone') },
    ...(projectKitsCatalog.catalog.value?.kits ?? []).map(entry => ({
        value: entry.name,
        label: entry.title || entry.name,
    })),
]);
async function onPickerDataChanged(payload) {
    if (payload.kind === 'group') {
        await groupsState.reload();
        selection.value = { kind: 'group', name: payload.name };
        banner.value = t('scopes.group.created', { name: payload.name });
    }
    else {
        await projectsState.reload();
        selection.value = { kind: 'project', name: payload.name };
        banner.value = t('scopes.project.created', { name: payload.name });
    }
}
// ─── Kit actions ───
const kitDialogTitle = computed(() => {
    switch (kitDialogMode.value) {
        case 'install': return t('scopes.kit.dialog.installTitle');
        case 'update': return t('scopes.kit.dialog.updateTitle');
        case 'export': return t('scopes.kit.dialog.exportTitle');
    }
});
const kitDialogSubmitLabel = computed(() => {
    switch (kitDialogMode.value) {
        case 'install': return t('scopes.kit.dialog.submitInstall');
        case 'update': return t('scopes.kit.dialog.submitUpdate');
        case 'export': return t('scopes.kit.dialog.submitExport');
    }
});
const kitNeedsUrl = computed(() => kitDialogMode.value === 'install');
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
    kitForm.trackManifest = true;
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
            // trackManifest=false ⇒ the user opted out of manifest tracking,
            // which is exactly what `apply` does server-side (no manifest,
            // no diff, no update path). Spec kits.md §10.
            if (!kitForm.trackManifest) {
                await kitState.apply(projectId, request);
                banner.value = t('scopes.kit.applied_msg');
            }
            else if (kitDialogMode.value === 'install') {
                await kitState.install(projectId, request);
                banner.value = t('scopes.kit.installed_msg');
            }
            else {
                await kitState.update(projectId, request);
                banner.value = t('scopes.kit.updated_msg');
            }
        }
        showKitDialog.value = false;
    }
    catch {
        /* error already in kitState.error */
    }
}
// ─── Project-level language pickers ───
//
// Two settings, both surfaced via dedicated dropdowns on the project
// card so users don't have to know the key names (chat.language /
// content.language) and don't have to bother with the type=STRING
// row in the generic settings panel. Behaviour:
//
//   "Not set" (empty value) → DELETE the setting → cascade falls
//     through to the tenant (then LanguageResolver.DEFAULT_LANGUAGE).
//   Any concrete code → upsert as STRING.
//
// Cascade scopes (see LanguageResolver):
//   chat.language    : project → user → tenant
//   content.language : project → tenant
//
// The user-level chat.language lives on the profile page; this is
// the project override on top of it.
const LANGUAGE_OPTIONS_KEYS = ['de', 'en', 'fr', 'es', 'it'];
const LANGUAGE_OPTIONS_LABELS = {
    de: 'Deutsch',
    en: 'English',
    fr: 'Français',
    es: 'Español',
    it: 'Italiano',
};
const projectLanguageOptions = computed(() => [
    { value: '', label: t('scopes.project.languageNotSet') },
    ...LANGUAGE_OPTIONS_KEYS.map(k => ({ value: k, label: LANGUAGE_OPTIONS_LABELS[k] })),
]);
function settingValueByKey(key) {
    const hit = settingsState.settings.value.find(s => s.key === key);
    return hit?.value ?? '';
}
const projectChatLanguage = computed(() => settingValueByKey('chat.language'));
const projectContentLanguage = computed(() => settingValueByKey('content.language'));
async function setProjectLanguageSetting(key, value) {
    const scope = settingsScope.value;
    if (!scope || scope.type !== 'project')
        return;
    try {
        if (value === null || value === '') {
            // No-op when there's nothing to delete — saves a 404 round-trip
            // and a spurious error in {@link useScopeSettings.remove}.
            if (!settingsState.settings.value.some(s => s.key === key))
                return;
            await settingsState.remove(scope.type, scope.id, key);
        }
        else {
            await settingsState.upsert(scope.type, scope.id, key, value, SettingType.STRING, null);
        }
    }
    catch {
        /* settingsState.error already surfaces via the panel error banner */
    }
}
function onProjectChatLanguageChanged(value) {
    void setProjectLanguageSetting('chat.language', value);
}
function onProjectContentLanguageChanged(value) {
    void setProjectLanguageSetting('content.language', value);
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
    ...{ 'onTitleClick': {} },
    focusZone: (__VLS_ctx.focusZone),
    title: (__VLS_ctx.$t('scopes.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    fullHeight: (true),
    showSidebar: (true),
    showRightPanel: (!!__VLS_ctx.settingsScope),
    focusModel: "auto",
    titleClickable: true,
    wideRightPanel: true,
}));
const __VLS_2 = __VLS_1({
    ...{ 'onTitleClick': {} },
    focusZone: (__VLS_ctx.focusZone),
    title: (__VLS_ctx.$t('scopes.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    fullHeight: (true),
    showSidebar: (true),
    showRightPanel: (!!__VLS_ctx.settingsScope),
    focusModel: "auto",
    titleClickable: true,
    wideRightPanel: true,
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
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.nav, __VLS_intrinsicElements.nav)({
        ...{ class: "flex flex-col gap-1 p-2" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onPointerdown: (...[$event]) => {
                __VLS_ctx.focusZone = 'main';
            } },
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
    const __VLS_9 = {}.ProjectListSidebar;
    /** @type {[typeof __VLS_components.ProjectListSidebar, typeof __VLS_components.ProjectListSidebar, ]} */ ;
    // @ts-ignore
    const __VLS_10 = __VLS_asFunctionalComponent(__VLS_9, new __VLS_9({
        ...{ 'onFocusMain': {} },
        ...{ 'onDataChanged': {} },
        selectedNode: (__VLS_ctx.pickerSelectedNode),
        groups: (__VLS_ctx.groupsState.groups.value),
        projects: (__VLS_ctx.projectsState.projects.value),
        loading: (__VLS_ctx.groupsState.loading.value || __VLS_ctx.projectsState.loading.value),
        error: (__VLS_ctx.groupsState.error.value || __VLS_ctx.projectsState.error.value),
        heading: (__VLS_ctx.$t('scopes.sidebar.projectGroups')),
        ungroupedLabel: (__VLS_ctx.$t('scopes.sidebar.ungroupedProjects')),
        kitOptions: (__VLS_ctx.pickerKitOptions),
        searchEnabled: (false),
        editEnabled: true,
        showGroupRows: true,
    }));
    const __VLS_11 = __VLS_10({
        ...{ 'onFocusMain': {} },
        ...{ 'onDataChanged': {} },
        selectedNode: (__VLS_ctx.pickerSelectedNode),
        groups: (__VLS_ctx.groupsState.groups.value),
        projects: (__VLS_ctx.projectsState.projects.value),
        loading: (__VLS_ctx.groupsState.loading.value || __VLS_ctx.projectsState.loading.value),
        error: (__VLS_ctx.groupsState.error.value || __VLS_ctx.projectsState.error.value),
        heading: (__VLS_ctx.$t('scopes.sidebar.projectGroups')),
        ungroupedLabel: (__VLS_ctx.$t('scopes.sidebar.ungroupedProjects')),
        kitOptions: (__VLS_ctx.pickerKitOptions),
        searchEnabled: (false),
        editEnabled: true,
        showGroupRows: true,
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
        onDataChanged: (__VLS_ctx.onPickerDataChanged)
    };
    __VLS_12.slots.default;
    {
        const { 'row-suffix': __VLS_thisSlot } = __VLS_12.slots;
        const [{ kind, item }] = __VLS_getSlotParams(__VLS_thisSlot);
        if (kind === 'group' && !item.enabled) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "opacity-60 text-xs" },
            });
            (__VLS_ctx.$t('scopes.common.disabled'));
        }
        else if (kind === 'project' && item.status === 'ARCHIVED') {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "opacity-60 text-xs" },
            });
            (__VLS_ctx.$t('scopes.common.archived'));
        }
    }
    var __VLS_12;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "h-full min-h-0 overflow-y-auto" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "p-6 max-w-2xl flex flex-col gap-3" },
});
if (__VLS_ctx.combinedError) {
    const __VLS_18 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_19 = __VLS_asFunctionalComponent(__VLS_18, new __VLS_18({
        variant: "error",
    }));
    const __VLS_20 = __VLS_19({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_19));
    __VLS_21.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.combinedError);
    var __VLS_21;
}
if (__VLS_ctx.banner) {
    const __VLS_22 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_23 = __VLS_asFunctionalComponent(__VLS_22, new __VLS_22({
        variant: "success",
    }));
    const __VLS_24 = __VLS_23({
        variant: "success",
    }, ...__VLS_functionalComponentArgsRest(__VLS_23));
    __VLS_25.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.banner);
    var __VLS_25;
}
if (__VLS_ctx.selection.kind === 'tenant') {
    const __VLS_26 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_27 = __VLS_asFunctionalComponent(__VLS_26, new __VLS_26({
        title: (__VLS_ctx.$t('scopes.tenant.cardTitle')),
    }));
    const __VLS_28 = __VLS_27({
        title: (__VLS_ctx.$t('scopes.tenant.cardTitle')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_27));
    __VLS_29.slots.default;
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
        const __VLS_30 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_31 = __VLS_asFunctionalComponent(__VLS_30, new __VLS_30({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.tenantState.tenant.value.name),
            label: (__VLS_ctx.$t('scopes.common.name')),
            disabled: true,
            help: (__VLS_ctx.$t('scopes.tenant.nameImmutable')),
        }));
        const __VLS_32 = __VLS_31({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.tenantState.tenant.value.name),
            label: (__VLS_ctx.$t('scopes.common.name')),
            disabled: true,
            help: (__VLS_ctx.$t('scopes.tenant.nameImmutable')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_31));
        let __VLS_34;
        let __VLS_35;
        let __VLS_36;
        const __VLS_37 = {
            'onUpdate:modelValue': (() => { })
        };
        var __VLS_33;
        const __VLS_38 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_39 = __VLS_asFunctionalComponent(__VLS_38, new __VLS_38({
            modelValue: (__VLS_ctx.form.title),
            label: (__VLS_ctx.$t('scopes.common.title')),
        }));
        const __VLS_40 = __VLS_39({
            modelValue: (__VLS_ctx.form.title),
            label: (__VLS_ctx.$t('scopes.common.title')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_39));
        const __VLS_42 = {}.VCheckbox;
        /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
        // @ts-ignore
        const __VLS_43 = __VLS_asFunctionalComponent(__VLS_42, new __VLS_42({
            modelValue: (__VLS_ctx.form.enabled),
            label: (__VLS_ctx.$t('scopes.common.enabled')),
        }));
        const __VLS_44 = __VLS_43({
            modelValue: (__VLS_ctx.form.enabled),
            label: (__VLS_ctx.$t('scopes.common.enabled')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_43));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex justify-end" },
        });
        const __VLS_46 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_47 = __VLS_asFunctionalComponent(__VLS_46, new __VLS_46({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.tenantState.saving.value),
        }));
        const __VLS_48 = __VLS_47({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.tenantState.saving.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_47));
        let __VLS_50;
        let __VLS_51;
        let __VLS_52;
        const __VLS_53 = {
            onClick: (__VLS_ctx.saveTenant)
        };
        __VLS_49.slots.default;
        (__VLS_ctx.$t('scopes.common.save'));
        var __VLS_49;
    }
    var __VLS_29;
}
else if (__VLS_ctx.selection.kind === 'group') {
    const __VLS_54 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_55 = __VLS_asFunctionalComponent(__VLS_54, new __VLS_54({
        title: (__VLS_ctx.$t('scopes.group.cardTitle', { name: __VLS_ctx.selection.name })),
    }));
    const __VLS_56 = __VLS_55({
        title: (__VLS_ctx.$t('scopes.group.cardTitle', { name: __VLS_ctx.selection.name })),
    }, ...__VLS_functionalComponentArgsRest(__VLS_55));
    __VLS_57.slots.default;
    if (__VLS_ctx.isReservedGroup) {
        const __VLS_58 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_59 = __VLS_asFunctionalComponent(__VLS_58, new __VLS_58({
            variant: "info",
            ...{ class: "mb-3" },
        }));
        const __VLS_60 = __VLS_59({
            variant: "info",
            ...{ class: "mb-3" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_59));
        __VLS_61.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.$t('scopes.group.reservedNote'));
        var __VLS_61;
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
        const __VLS_62 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_63 = __VLS_asFunctionalComponent(__VLS_62, new __VLS_62({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.selectedGroup.name),
            label: (__VLS_ctx.$t('scopes.common.name')),
            disabled: true,
            help: (__VLS_ctx.$t('scopes.group.nameImmutable')),
        }));
        const __VLS_64 = __VLS_63({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.selectedGroup.name),
            label: (__VLS_ctx.$t('scopes.common.name')),
            disabled: true,
            help: (__VLS_ctx.$t('scopes.group.nameImmutable')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_63));
        let __VLS_66;
        let __VLS_67;
        let __VLS_68;
        const __VLS_69 = {
            'onUpdate:modelValue': (() => { })
        };
        var __VLS_65;
        const __VLS_70 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_71 = __VLS_asFunctionalComponent(__VLS_70, new __VLS_70({
            modelValue: (__VLS_ctx.form.title),
            label: (__VLS_ctx.$t('scopes.common.title')),
        }));
        const __VLS_72 = __VLS_71({
            modelValue: (__VLS_ctx.form.title),
            label: (__VLS_ctx.$t('scopes.common.title')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_71));
        const __VLS_74 = {}.VCheckbox;
        /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
        // @ts-ignore
        const __VLS_75 = __VLS_asFunctionalComponent(__VLS_74, new __VLS_74({
            modelValue: (__VLS_ctx.form.enabled),
            label: (__VLS_ctx.$t('scopes.common.enabled')),
        }));
        const __VLS_76 = __VLS_75({
            modelValue: (__VLS_ctx.form.enabled),
            label: (__VLS_ctx.$t('scopes.common.enabled')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_75));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex justify-between" },
        });
        const __VLS_78 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_79 = __VLS_asFunctionalComponent(__VLS_78, new __VLS_78({
            ...{ 'onClick': {} },
            variant: "danger",
            disabled: (__VLS_ctx.isReservedGroup),
            loading: (__VLS_ctx.groupsState.busy.value),
        }));
        const __VLS_80 = __VLS_79({
            ...{ 'onClick': {} },
            variant: "danger",
            disabled: (__VLS_ctx.isReservedGroup),
            loading: (__VLS_ctx.groupsState.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_79));
        let __VLS_82;
        let __VLS_83;
        let __VLS_84;
        const __VLS_85 = {
            onClick: (__VLS_ctx.deleteGroup)
        };
        __VLS_81.slots.default;
        (__VLS_ctx.$t('scopes.group.delete'));
        var __VLS_81;
        const __VLS_86 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_87 = __VLS_asFunctionalComponent(__VLS_86, new __VLS_86({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.groupsState.busy.value),
        }));
        const __VLS_88 = __VLS_87({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.groupsState.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_87));
        let __VLS_90;
        let __VLS_91;
        let __VLS_92;
        const __VLS_93 = {
            onClick: (__VLS_ctx.saveGroup)
        };
        __VLS_89.slots.default;
        (__VLS_ctx.$t('scopes.common.save'));
        var __VLS_89;
    }
    var __VLS_57;
}
else if (__VLS_ctx.selection.kind === 'project') {
    const __VLS_94 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_95 = __VLS_asFunctionalComponent(__VLS_94, new __VLS_94({
        title: (__VLS_ctx.$t('scopes.project.cardTitle', { name: __VLS_ctx.selection.name })),
    }));
    const __VLS_96 = __VLS_95({
        title: (__VLS_ctx.$t('scopes.project.cardTitle', { name: __VLS_ctx.selection.name })),
    }, ...__VLS_functionalComponentArgsRest(__VLS_95));
    __VLS_97.slots.default;
    if (__VLS_ctx.isArchivedProject) {
        const __VLS_98 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_99 = __VLS_asFunctionalComponent(__VLS_98, new __VLS_98({
            variant: "warning",
            ...{ class: "mb-3" },
        }));
        const __VLS_100 = __VLS_99({
            variant: "warning",
            ...{ class: "mb-3" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_99));
        __VLS_101.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.$t('scopes.project.archivedNote'));
        var __VLS_101;
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
        const __VLS_102 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_103 = __VLS_asFunctionalComponent(__VLS_102, new __VLS_102({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.selectedProject.name),
            label: (__VLS_ctx.$t('scopes.common.name')),
            disabled: true,
            help: (__VLS_ctx.$t('scopes.project.nameImmutable')),
        }));
        const __VLS_104 = __VLS_103({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.selectedProject.name),
            label: (__VLS_ctx.$t('scopes.common.name')),
            disabled: true,
            help: (__VLS_ctx.$t('scopes.project.nameImmutable')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_103));
        let __VLS_106;
        let __VLS_107;
        let __VLS_108;
        const __VLS_109 = {
            'onUpdate:modelValue': (() => { })
        };
        var __VLS_105;
        const __VLS_110 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_111 = __VLS_asFunctionalComponent(__VLS_110, new __VLS_110({
            modelValue: (__VLS_ctx.form.title),
            label: (__VLS_ctx.$t('scopes.common.title')),
        }));
        const __VLS_112 = __VLS_111({
            modelValue: (__VLS_ctx.form.title),
            label: (__VLS_ctx.$t('scopes.common.title')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_111));
        const __VLS_114 = {}.VSelect;
        /** @type {[typeof __VLS_components.VSelect, ]} */ ;
        // @ts-ignore
        const __VLS_115 = __VLS_asFunctionalComponent(__VLS_114, new __VLS_114({
            modelValue: (__VLS_ctx.form.projectGroupId),
            label: (__VLS_ctx.$t('scopes.project.groupLabel')),
            options: (__VLS_ctx.groupSelectOptions),
        }));
        const __VLS_116 = __VLS_115({
            modelValue: (__VLS_ctx.form.projectGroupId),
            label: (__VLS_ctx.$t('scopes.project.groupLabel')),
            options: (__VLS_ctx.groupSelectOptions),
        }, ...__VLS_functionalComponentArgsRest(__VLS_115));
        const __VLS_118 = {}.VCheckbox;
        /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
        // @ts-ignore
        const __VLS_119 = __VLS_asFunctionalComponent(__VLS_118, new __VLS_118({
            modelValue: (__VLS_ctx.form.enabled),
            label: (__VLS_ctx.$t('scopes.common.enabled')),
        }));
        const __VLS_120 = __VLS_119({
            modelValue: (__VLS_ctx.form.enabled),
            label: (__VLS_ctx.$t('scopes.common.enabled')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_119));
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
        (__VLS_ctx.selectedProject.homeNode ?? __VLS_ctx.$t('scopes.common.none'));
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
        const __VLS_122 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_123 = __VLS_asFunctionalComponent(__VLS_122, new __VLS_122({
            ...{ 'onClick': {} },
            variant: "danger",
            disabled: (__VLS_ctx.isArchivedProject),
            loading: (__VLS_ctx.projectsState.busy.value),
        }));
        const __VLS_124 = __VLS_123({
            ...{ 'onClick': {} },
            variant: "danger",
            disabled: (__VLS_ctx.isArchivedProject),
            loading: (__VLS_ctx.projectsState.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_123));
        let __VLS_126;
        let __VLS_127;
        let __VLS_128;
        const __VLS_129 = {
            onClick: (__VLS_ctx.archiveProject)
        };
        __VLS_125.slots.default;
        (__VLS_ctx.$t('scopes.project.archive'));
        var __VLS_125;
        const __VLS_130 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_131 = __VLS_asFunctionalComponent(__VLS_130, new __VLS_130({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.projectsState.busy.value),
        }));
        const __VLS_132 = __VLS_131({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.projectsState.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_131));
        let __VLS_134;
        let __VLS_135;
        let __VLS_136;
        const __VLS_137 = {
            onClick: (__VLS_ctx.saveProject)
        };
        __VLS_133.slots.default;
        (__VLS_ctx.$t('scopes.common.save'));
        var __VLS_133;
    }
    var __VLS_97;
}
if (__VLS_ctx.selection.kind === 'project' && __VLS_ctx.selectedProject) {
    const __VLS_138 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_139 = __VLS_asFunctionalComponent(__VLS_138, new __VLS_138({
        title: (__VLS_ctx.$t('scopes.project.languagesCardTitle')),
    }));
    const __VLS_140 = __VLS_139({
        title: (__VLS_ctx.$t('scopes.project.languagesCardTitle')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_139));
    __VLS_141.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "text-sm opacity-70 mb-3" },
    });
    (__VLS_ctx.$t('scopes.project.languagesDescription'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-3" },
    });
    const __VLS_142 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_143 = __VLS_asFunctionalComponent(__VLS_142, new __VLS_142({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.projectChatLanguage),
        options: (__VLS_ctx.projectLanguageOptions),
        label: (__VLS_ctx.$t('scopes.project.chatLanguageLabel')),
        disabled: (__VLS_ctx.settingsState.busy.value || __VLS_ctx.isArchivedProject),
    }));
    const __VLS_144 = __VLS_143({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.projectChatLanguage),
        options: (__VLS_ctx.projectLanguageOptions),
        label: (__VLS_ctx.$t('scopes.project.chatLanguageLabel')),
        disabled: (__VLS_ctx.settingsState.busy.value || __VLS_ctx.isArchivedProject),
    }, ...__VLS_functionalComponentArgsRest(__VLS_143));
    let __VLS_146;
    let __VLS_147;
    let __VLS_148;
    const __VLS_149 = {
        'onUpdate:modelValue': (__VLS_ctx.onProjectChatLanguageChanged)
    };
    var __VLS_145;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "text-xs opacity-60 -mt-2" },
    });
    (__VLS_ctx.$t('scopes.project.chatLanguageHelp'));
    const __VLS_150 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_151 = __VLS_asFunctionalComponent(__VLS_150, new __VLS_150({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.projectContentLanguage),
        options: (__VLS_ctx.projectLanguageOptions),
        label: (__VLS_ctx.$t('scopes.project.contentLanguageLabel')),
        disabled: (__VLS_ctx.settingsState.busy.value || __VLS_ctx.isArchivedProject),
    }));
    const __VLS_152 = __VLS_151({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.projectContentLanguage),
        options: (__VLS_ctx.projectLanguageOptions),
        label: (__VLS_ctx.$t('scopes.project.contentLanguageLabel')),
        disabled: (__VLS_ctx.settingsState.busy.value || __VLS_ctx.isArchivedProject),
    }, ...__VLS_functionalComponentArgsRest(__VLS_151));
    let __VLS_154;
    let __VLS_155;
    let __VLS_156;
    const __VLS_157 = {
        'onUpdate:modelValue': (__VLS_ctx.onProjectContentLanguageChanged)
    };
    var __VLS_153;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "text-xs opacity-60 -mt-2" },
    });
    (__VLS_ctx.$t('scopes.project.contentLanguageHelp'));
    var __VLS_141;
}
if (__VLS_ctx.selection.kind === 'project' && __VLS_ctx.selectedProject) {
    const __VLS_158 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_159 = __VLS_asFunctionalComponent(__VLS_158, new __VLS_158({
        title: (__VLS_ctx.$t('scopes.kit.cardTitle')),
    }));
    const __VLS_160 = __VLS_159({
        title: (__VLS_ctx.$t('scopes.kit.cardTitle')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_159));
    __VLS_161.slots.default;
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
        const __VLS_162 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_163 = __VLS_asFunctionalComponent(__VLS_162, new __VLS_162({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
        }));
        const __VLS_164 = __VLS_163({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
        }, ...__VLS_functionalComponentArgsRest(__VLS_163));
        let __VLS_166;
        let __VLS_167;
        let __VLS_168;
        const __VLS_169 = {
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
        __VLS_165.slots.default;
        (__VLS_ctx.$t('scopes.kit.export'));
        var __VLS_165;
        const __VLS_170 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_171 = __VLS_asFunctionalComponent(__VLS_170, new __VLS_170({
            ...{ 'onClick': {} },
            variant: "primary",
            size: "sm",
            loading: (__VLS_ctx.kitState.busy.value),
        }));
        const __VLS_172 = __VLS_171({
            ...{ 'onClick': {} },
            variant: "primary",
            size: "sm",
            loading: (__VLS_ctx.kitState.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_171));
        let __VLS_174;
        let __VLS_175;
        let __VLS_176;
        const __VLS_177 = {
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
        __VLS_173.slots.default;
        (__VLS_ctx.$t('scopes.kit.update'));
        var __VLS_173;
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
        const __VLS_178 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_179 = __VLS_asFunctionalComponent(__VLS_178, new __VLS_178({
            ...{ 'onClick': {} },
            variant: "primary",
            size: "sm",
            loading: (__VLS_ctx.kitState.busy.value),
        }));
        const __VLS_180 = __VLS_179({
            ...{ 'onClick': {} },
            variant: "primary",
            size: "sm",
            loading: (__VLS_ctx.kitState.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_179));
        let __VLS_182;
        let __VLS_183;
        let __VLS_184;
        const __VLS_185 = {
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
        __VLS_181.slots.default;
        (__VLS_ctx.$t('scopes.kit.install'));
        var __VLS_181;
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
    var __VLS_161;
}
{
    const { 'right-panel': __VLS_thisSlot } = __VLS_3.slots;
    if (__VLS_ctx.settingsScope) {
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
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            role: "tablist",
            ...{ class: "flex gap-1 border-b border-base-300 -mt-1" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!(__VLS_ctx.settingsScope))
                        return;
                    __VLS_ctx.rightTab = 'settings';
                } },
            type: "button",
            role: "tab",
            ...{ class: "px-3 py-1.5 text-sm font-semibold border-b-2 transition-colors" },
            ...{ class: (__VLS_ctx.rightTab === 'settings'
                    ? 'border-primary text-primary'
                    : 'border-transparent opacity-60 hover:opacity-100') },
        });
        (__VLS_ctx.$t('scopes.settingsPanel.tabRaw'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!(__VLS_ctx.settingsScope))
                        return;
                    __VLS_ctx.rightTab = 'forms';
                } },
            type: "button",
            role: "tab",
            ...{ class: "px-3 py-1.5 text-sm font-semibold border-b-2 transition-colors" },
            ...{ class: (__VLS_ctx.rightTab === 'forms'
                    ? 'border-primary text-primary'
                    : 'border-transparent opacity-60 hover:opacity-100') },
        });
        (__VLS_ctx.$t('scopes.settingsPanel.tabForms'));
        if (__VLS_ctx.settingFormsList.length > 0) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "ml-1 text-xs opacity-70" },
            });
            (__VLS_ctx.settingFormsList.length);
        }
        if (__VLS_ctx.rightTab === 'settings') {
            if (!__VLS_ctx.settingsState.loading.value && __VLS_ctx.settingsState.settings.value.length === 0) {
                const __VLS_186 = {}.VEmptyState;
                /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
                // @ts-ignore
                const __VLS_187 = __VLS_asFunctionalComponent(__VLS_186, new __VLS_186({
                    headline: (__VLS_ctx.$t('scopes.settingsPanel.noSettingsHeadline')),
                    body: (__VLS_ctx.$t('scopes.settingsPanel.noSettingsBody')),
                }));
                const __VLS_188 = __VLS_187({
                    headline: (__VLS_ctx.$t('scopes.settingsPanel.noSettingsHeadline')),
                    body: (__VLS_ctx.$t('scopes.settingsPanel.noSettingsBody')),
                }, ...__VLS_functionalComponentArgsRest(__VLS_187));
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
                        const __VLS_190 = {}.VInput;
                        /** @type {[typeof __VLS_components.VInput, ]} */ ;
                        // @ts-ignore
                        const __VLS_191 = __VLS_asFunctionalComponent(__VLS_190, new __VLS_190({
                            modelValue: (__VLS_ctx.editValue),
                            label: (__VLS_ctx.$t('scopes.settingsPanel.valueLabel')),
                        }));
                        const __VLS_192 = __VLS_191({
                            modelValue: (__VLS_ctx.editValue),
                            label: (__VLS_ctx.$t('scopes.settingsPanel.valueLabel')),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_191));
                    }
                    else {
                        const __VLS_194 = {}.VInput;
                        /** @type {[typeof __VLS_components.VInput, ]} */ ;
                        // @ts-ignore
                        const __VLS_195 = __VLS_asFunctionalComponent(__VLS_194, new __VLS_194({
                            modelValue: (__VLS_ctx.editValue),
                            type: "password",
                            label: (__VLS_ctx.$t('scopes.settingsPanel.newPasswordLabel')),
                            placeholder: (__VLS_ctx.$t('scopes.settingsPanel.passwordEmptyToClear')),
                        }));
                        const __VLS_196 = __VLS_195({
                            modelValue: (__VLS_ctx.editValue),
                            type: "password",
                            label: (__VLS_ctx.$t('scopes.settingsPanel.newPasswordLabel')),
                            placeholder: (__VLS_ctx.$t('scopes.settingsPanel.passwordEmptyToClear')),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_195));
                    }
                    const __VLS_198 = {}.VTextarea;
                    /** @type {[typeof __VLS_components.VTextarea, ]} */ ;
                    // @ts-ignore
                    const __VLS_199 = __VLS_asFunctionalComponent(__VLS_198, new __VLS_198({
                        modelValue: (__VLS_ctx.editDescription),
                        label: (__VLS_ctx.$t('scopes.settingsPanel.descriptionLabel')),
                        rows: (2),
                    }));
                    const __VLS_200 = __VLS_199({
                        modelValue: (__VLS_ctx.editDescription),
                        label: (__VLS_ctx.$t('scopes.settingsPanel.descriptionLabel')),
                        rows: (2),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_199));
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: "flex justify-end gap-2 mt-1" },
                    });
                    const __VLS_202 = {}.VButton;
                    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
                    // @ts-ignore
                    const __VLS_203 = __VLS_asFunctionalComponent(__VLS_202, new __VLS_202({
                        ...{ 'onClick': {} },
                        variant: "ghost",
                        size: "sm",
                    }));
                    const __VLS_204 = __VLS_203({
                        ...{ 'onClick': {} },
                        variant: "ghost",
                        size: "sm",
                    }, ...__VLS_functionalComponentArgsRest(__VLS_203));
                    let __VLS_206;
                    let __VLS_207;
                    let __VLS_208;
                    const __VLS_209 = {
                        onClick: (__VLS_ctx.cancelEditSetting)
                    };
                    __VLS_205.slots.default;
                    (__VLS_ctx.$t('scopes.common.cancel'));
                    var __VLS_205;
                    const __VLS_210 = {}.VButton;
                    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
                    // @ts-ignore
                    const __VLS_211 = __VLS_asFunctionalComponent(__VLS_210, new __VLS_210({
                        ...{ 'onClick': {} },
                        variant: "primary",
                        size: "sm",
                        loading: (__VLS_ctx.settingsState.busy.value),
                    }));
                    const __VLS_212 = __VLS_211({
                        ...{ 'onClick': {} },
                        variant: "primary",
                        size: "sm",
                        loading: (__VLS_ctx.settingsState.busy.value),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_211));
                    let __VLS_214;
                    let __VLS_215;
                    let __VLS_216;
                    const __VLS_217 = {
                        onClick: (...[$event]) => {
                            if (!(__VLS_ctx.settingsScope))
                                return;
                            if (!(__VLS_ctx.rightTab === 'settings'))
                                return;
                            if (!(__VLS_ctx.editingKey === s.key))
                                return;
                            __VLS_ctx.saveEditSetting(s);
                        }
                    };
                    __VLS_213.slots.default;
                    (__VLS_ctx.$t('scopes.common.save'));
                    var __VLS_213;
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
                    const __VLS_218 = {}.VButton;
                    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
                    // @ts-ignore
                    const __VLS_219 = __VLS_asFunctionalComponent(__VLS_218, new __VLS_218({
                        ...{ 'onClick': {} },
                        variant: "ghost",
                        size: "sm",
                    }));
                    const __VLS_220 = __VLS_219({
                        ...{ 'onClick': {} },
                        variant: "ghost",
                        size: "sm",
                    }, ...__VLS_functionalComponentArgsRest(__VLS_219));
                    let __VLS_222;
                    let __VLS_223;
                    let __VLS_224;
                    const __VLS_225 = {
                        onClick: (...[$event]) => {
                            if (!(__VLS_ctx.settingsScope))
                                return;
                            if (!(__VLS_ctx.rightTab === 'settings'))
                                return;
                            if (!!(__VLS_ctx.editingKey === s.key))
                                return;
                            __VLS_ctx.startEditSetting(s);
                        }
                    };
                    __VLS_221.slots.default;
                    (__VLS_ctx.$t('scopes.settingsPanel.edit'));
                    var __VLS_221;
                    const __VLS_226 = {}.VButton;
                    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
                    // @ts-ignore
                    const __VLS_227 = __VLS_asFunctionalComponent(__VLS_226, new __VLS_226({
                        ...{ 'onClick': {} },
                        variant: "ghost",
                        size: "sm",
                    }));
                    const __VLS_228 = __VLS_227({
                        ...{ 'onClick': {} },
                        variant: "ghost",
                        size: "sm",
                    }, ...__VLS_functionalComponentArgsRest(__VLS_227));
                    let __VLS_230;
                    let __VLS_231;
                    let __VLS_232;
                    const __VLS_233 = {
                        onClick: (...[$event]) => {
                            if (!(__VLS_ctx.settingsScope))
                                return;
                            if (!(__VLS_ctx.rightTab === 'settings'))
                                return;
                            if (!!(__VLS_ctx.editingKey === s.key))
                                return;
                            __VLS_ctx.deleteSetting(s);
                        }
                    };
                    __VLS_229.slots.default;
                    (__VLS_ctx.$t('scopes.settingsPanel.deleteLabel'));
                    var __VLS_229;
                }
            }
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "border-t border-base-300 pt-3 mt-2 flex flex-col gap-2" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.h4, __VLS_intrinsicElements.h4)({
                ...{ class: "text-xs uppercase opacity-60" },
            });
            (__VLS_ctx.$t('scopes.settingsPanel.addTitle'));
            const __VLS_234 = {}.VInput;
            /** @type {[typeof __VLS_components.VInput, ]} */ ;
            // @ts-ignore
            const __VLS_235 = __VLS_asFunctionalComponent(__VLS_234, new __VLS_234({
                modelValue: (__VLS_ctx.newSettingKey),
                label: (__VLS_ctx.$t('scopes.settingsPanel.keyLabel')),
                placeholder: (__VLS_ctx.$t('scopes.settingsPanel.keyPlaceholder')),
            }));
            const __VLS_236 = __VLS_235({
                modelValue: (__VLS_ctx.newSettingKey),
                label: (__VLS_ctx.$t('scopes.settingsPanel.keyLabel')),
                placeholder: (__VLS_ctx.$t('scopes.settingsPanel.keyPlaceholder')),
            }, ...__VLS_functionalComponentArgsRest(__VLS_235));
            const __VLS_238 = {}.VSelect;
            /** @type {[typeof __VLS_components.VSelect, ]} */ ;
            // @ts-ignore
            const __VLS_239 = __VLS_asFunctionalComponent(__VLS_238, new __VLS_238({
                modelValue: (__VLS_ctx.newSettingType),
                label: (__VLS_ctx.$t('scopes.settingsPanel.typeLabel')),
                options: (__VLS_ctx.settingTypeOptions),
            }));
            const __VLS_240 = __VLS_239({
                modelValue: (__VLS_ctx.newSettingType),
                label: (__VLS_ctx.$t('scopes.settingsPanel.typeLabel')),
                options: (__VLS_ctx.settingTypeOptions),
            }, ...__VLS_functionalComponentArgsRest(__VLS_239));
            if (__VLS_ctx.newSettingType !== __VLS_ctx.SettingType.PASSWORD) {
                const __VLS_242 = {}.VInput;
                /** @type {[typeof __VLS_components.VInput, ]} */ ;
                // @ts-ignore
                const __VLS_243 = __VLS_asFunctionalComponent(__VLS_242, new __VLS_242({
                    modelValue: (__VLS_ctx.newSettingValue),
                    label: (__VLS_ctx.$t('scopes.settingsPanel.valueLabel')),
                }));
                const __VLS_244 = __VLS_243({
                    modelValue: (__VLS_ctx.newSettingValue),
                    label: (__VLS_ctx.$t('scopes.settingsPanel.valueLabel')),
                }, ...__VLS_functionalComponentArgsRest(__VLS_243));
            }
            else {
                const __VLS_246 = {}.VInput;
                /** @type {[typeof __VLS_components.VInput, ]} */ ;
                // @ts-ignore
                const __VLS_247 = __VLS_asFunctionalComponent(__VLS_246, new __VLS_246({
                    modelValue: (__VLS_ctx.newSettingValue),
                    type: "password",
                    label: (__VLS_ctx.$t('scopes.settingsPanel.passwordLabel')),
                }));
                const __VLS_248 = __VLS_247({
                    modelValue: (__VLS_ctx.newSettingValue),
                    type: "password",
                    label: (__VLS_ctx.$t('scopes.settingsPanel.passwordLabel')),
                }, ...__VLS_functionalComponentArgsRest(__VLS_247));
            }
            const __VLS_250 = {}.VTextarea;
            /** @type {[typeof __VLS_components.VTextarea, ]} */ ;
            // @ts-ignore
            const __VLS_251 = __VLS_asFunctionalComponent(__VLS_250, new __VLS_250({
                modelValue: (__VLS_ctx.newSettingDescription),
                label: (__VLS_ctx.$t('scopes.settingsPanel.descriptionOptional')),
                rows: (2),
            }));
            const __VLS_252 = __VLS_251({
                modelValue: (__VLS_ctx.newSettingDescription),
                label: (__VLS_ctx.$t('scopes.settingsPanel.descriptionOptional')),
                rows: (2),
            }, ...__VLS_functionalComponentArgsRest(__VLS_251));
            const __VLS_254 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_255 = __VLS_asFunctionalComponent(__VLS_254, new __VLS_254({
                ...{ 'onClick': {} },
                variant: "primary",
                size: "sm",
                disabled: (!__VLS_ctx.newSettingKey.trim()),
                loading: (__VLS_ctx.settingsState.busy.value),
            }));
            const __VLS_256 = __VLS_255({
                ...{ 'onClick': {} },
                variant: "primary",
                size: "sm",
                disabled: (!__VLS_ctx.newSettingKey.trim()),
                loading: (__VLS_ctx.settingsState.busy.value),
            }, ...__VLS_functionalComponentArgsRest(__VLS_255));
            let __VLS_258;
            let __VLS_259;
            let __VLS_260;
            const __VLS_261 = {
                onClick: (__VLS_ctx.addSetting)
            };
            __VLS_257.slots.default;
            (__VLS_ctx.$t('scopes.settingsPanel.add'));
            var __VLS_257;
        }
        else if (__VLS_ctx.rightTab === 'forms') {
            if (__VLS_ctx.settingFormsError) {
                const __VLS_262 = {}.VAlert;
                /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
                // @ts-ignore
                const __VLS_263 = __VLS_asFunctionalComponent(__VLS_262, new __VLS_262({
                    variant: "error",
                }));
                const __VLS_264 = __VLS_263({
                    variant: "error",
                }, ...__VLS_functionalComponentArgsRest(__VLS_263));
                __VLS_265.slots.default;
                (__VLS_ctx.settingFormsError);
                var __VLS_265;
            }
            if (!__VLS_ctx.settingFormsLoading
                && __VLS_ctx.settingFormsList.length === 0
                && !__VLS_ctx.selectedSettingForm) {
                const __VLS_266 = {}.VEmptyState;
                /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
                // @ts-ignore
                const __VLS_267 = __VLS_asFunctionalComponent(__VLS_266, new __VLS_266({
                    headline: (__VLS_ctx.$t('scopes.settingFormsPanel.emptyHeadline')),
                    body: (__VLS_ctx.settingFormsProjectId
                        ? __VLS_ctx.$t('scopes.settingFormsPanel.emptyBodyProject')
                        : __VLS_ctx.$t('scopes.settingFormsPanel.emptyBodyTenant')),
                }));
                const __VLS_268 = __VLS_267({
                    headline: (__VLS_ctx.$t('scopes.settingFormsPanel.emptyHeadline')),
                    body: (__VLS_ctx.settingFormsProjectId
                        ? __VLS_ctx.$t('scopes.settingFormsPanel.emptyBodyProject')
                        : __VLS_ctx.$t('scopes.settingFormsPanel.emptyBodyTenant')),
                }, ...__VLS_functionalComponentArgsRest(__VLS_267));
            }
            if (!__VLS_ctx.selectedSettingForm) {
                for (const [[cat, group]] of __VLS_getVForSourceType((__VLS_ctx.groupedSettingForms))) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        key: (cat),
                        ...{ class: "flex flex-col gap-1" },
                    });
                    if (cat) {
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                            ...{ class: "text-[10px] uppercase tracking-wide opacity-50 font-semibold px-1 mt-1" },
                        });
                        (cat);
                    }
                    for (const [f] of __VLS_getVForSourceType((group))) {
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                            ...{ onClick: (...[$event]) => {
                                    if (!(__VLS_ctx.settingsScope))
                                        return;
                                    if (!!(__VLS_ctx.rightTab === 'settings'))
                                        return;
                                    if (!(__VLS_ctx.rightTab === 'forms'))
                                        return;
                                    if (!(!__VLS_ctx.selectedSettingForm))
                                        return;
                                    __VLS_ctx.selectSettingForm(f.name);
                                } },
                            key: (f.name),
                            type: "button",
                            ...{ class: "text-left px-2.5 py-2 text-sm rounded transition-colors bg-base-200 hover:bg-base-300" },
                        });
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                            ...{ class: "flex items-center gap-1.5" },
                        });
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                            ...{ class: "font-semibold truncate" },
                        });
                        (f.title);
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                            ...{ class: "text-xs opacity-70 mt-0.5 line-clamp-2" },
                        });
                        (f.description);
                    }
                }
            }
            else {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "flex items-center gap-2 -mt-1" },
                });
                const __VLS_270 = {}.VButton;
                /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
                // @ts-ignore
                const __VLS_271 = __VLS_asFunctionalComponent(__VLS_270, new __VLS_270({
                    ...{ 'onClick': {} },
                    variant: "ghost",
                    size: "sm",
                }));
                const __VLS_272 = __VLS_271({
                    ...{ 'onClick': {} },
                    variant: "ghost",
                    size: "sm",
                }, ...__VLS_functionalComponentArgsRest(__VLS_271));
                let __VLS_274;
                let __VLS_275;
                let __VLS_276;
                const __VLS_277 = {
                    onClick: (__VLS_ctx.backToSettingFormsList)
                };
                __VLS_273.slots.default;
                (__VLS_ctx.$t('scopes.settingFormsPanel.backToList'));
                var __VLS_273;
                const __VLS_278 = {}.SettingFormView;
                /** @type {[typeof __VLS_components.SettingFormView, ]} */ ;
                // @ts-ignore
                const __VLS_279 = __VLS_asFunctionalComponent(__VLS_278, new __VLS_278({
                    ...{ 'onApplied': {} },
                    name: (__VLS_ctx.selectedSettingForm),
                    projectId: (__VLS_ctx.settingFormsProjectId),
                    reloadKey: (__VLS_ctx.settingFormsReloadKey),
                }));
                const __VLS_280 = __VLS_279({
                    ...{ 'onApplied': {} },
                    name: (__VLS_ctx.selectedSettingForm),
                    projectId: (__VLS_ctx.settingFormsProjectId),
                    reloadKey: (__VLS_ctx.settingFormsReloadKey),
                }, ...__VLS_functionalComponentArgsRest(__VLS_279));
                let __VLS_282;
                let __VLS_283;
                let __VLS_284;
                const __VLS_285 = {
                    onApplied: (__VLS_ctx.onSettingFormApplied)
                };
                var __VLS_281;
            }
        }
    }
}
const __VLS_286 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_287 = __VLS_asFunctionalComponent(__VLS_286, new __VLS_286({
    modelValue: (__VLS_ctx.showKitDialog),
    title: (__VLS_ctx.kitDialogTitle),
    closeOnBackdrop: (false),
}));
const __VLS_288 = __VLS_287({
    modelValue: (__VLS_ctx.showKitDialog),
    title: (__VLS_ctx.kitDialogTitle),
    closeOnBackdrop: (false),
}, ...__VLS_functionalComponentArgsRest(__VLS_287));
__VLS_289.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
if (__VLS_ctx.kitState.error.value) {
    const __VLS_290 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_291 = __VLS_asFunctionalComponent(__VLS_290, new __VLS_290({
        variant: "error",
    }));
    const __VLS_292 = __VLS_291({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_291));
    __VLS_293.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.kitState.error.value);
    var __VLS_293;
}
const __VLS_294 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_295 = __VLS_asFunctionalComponent(__VLS_294, new __VLS_294({
    modelValue: (__VLS_ctx.kitForm.url),
    label: (__VLS_ctx.$t('scopes.kit.dialog.repoUrl')),
    required: (__VLS_ctx.kitNeedsUrl),
    help: (__VLS_ctx.kitDialogMode === 'update' || __VLS_ctx.kitDialogMode === 'export'
        ? __VLS_ctx.$t('scopes.kit.dialog.repoUrlReuseHelp')
        : __VLS_ctx.$t('scopes.kit.dialog.repoUrlHelp')),
}));
const __VLS_296 = __VLS_295({
    modelValue: (__VLS_ctx.kitForm.url),
    label: (__VLS_ctx.$t('scopes.kit.dialog.repoUrl')),
    required: (__VLS_ctx.kitNeedsUrl),
    help: (__VLS_ctx.kitDialogMode === 'update' || __VLS_ctx.kitDialogMode === 'export'
        ? __VLS_ctx.$t('scopes.kit.dialog.repoUrlReuseHelp')
        : __VLS_ctx.$t('scopes.kit.dialog.repoUrlHelp')),
}, ...__VLS_functionalComponentArgsRest(__VLS_295));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "grid grid-cols-2 gap-3" },
});
const __VLS_298 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_299 = __VLS_asFunctionalComponent(__VLS_298, new __VLS_298({
    modelValue: (__VLS_ctx.kitForm.path),
    label: (__VLS_ctx.$t('scopes.kit.dialog.subPath')),
    help: (__VLS_ctx.$t('scopes.kit.dialog.subPathHelp')),
}));
const __VLS_300 = __VLS_299({
    modelValue: (__VLS_ctx.kitForm.path),
    label: (__VLS_ctx.$t('scopes.kit.dialog.subPath')),
    help: (__VLS_ctx.$t('scopes.kit.dialog.subPathHelp')),
}, ...__VLS_functionalComponentArgsRest(__VLS_299));
const __VLS_302 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_303 = __VLS_asFunctionalComponent(__VLS_302, new __VLS_302({
    modelValue: (__VLS_ctx.kitForm.branch),
    label: (__VLS_ctx.$t('scopes.kit.dialog.branchLabel')),
    help: (__VLS_ctx.$t('scopes.kit.dialog.branchHelp')),
}));
const __VLS_304 = __VLS_303({
    modelValue: (__VLS_ctx.kitForm.branch),
    label: (__VLS_ctx.$t('scopes.kit.dialog.branchLabel')),
    help: (__VLS_ctx.$t('scopes.kit.dialog.branchHelp')),
}, ...__VLS_functionalComponentArgsRest(__VLS_303));
if (__VLS_ctx.kitDialogMode !== 'export') {
    const __VLS_306 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_307 = __VLS_asFunctionalComponent(__VLS_306, new __VLS_306({
        modelValue: (__VLS_ctx.kitForm.commit),
        label: (__VLS_ctx.$t('scopes.kit.dialog.commitSha')),
        help: (__VLS_ctx.$t('scopes.kit.dialog.commitShaHelp')),
    }));
    const __VLS_308 = __VLS_307({
        modelValue: (__VLS_ctx.kitForm.commit),
        label: (__VLS_ctx.$t('scopes.kit.dialog.commitSha')),
        help: (__VLS_ctx.$t('scopes.kit.dialog.commitShaHelp')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_307));
}
const __VLS_310 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_311 = __VLS_asFunctionalComponent(__VLS_310, new __VLS_310({
    modelValue: (__VLS_ctx.kitForm.token),
    type: "password",
    label: (__VLS_ctx.$t('scopes.kit.dialog.authToken')),
    help: (__VLS_ctx.$t('scopes.kit.dialog.authTokenHelp')),
}));
const __VLS_312 = __VLS_311({
    modelValue: (__VLS_ctx.kitForm.token),
    type: "password",
    label: (__VLS_ctx.$t('scopes.kit.dialog.authToken')),
    help: (__VLS_ctx.$t('scopes.kit.dialog.authTokenHelp')),
}, ...__VLS_functionalComponentArgsRest(__VLS_311));
const __VLS_314 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_315 = __VLS_asFunctionalComponent(__VLS_314, new __VLS_314({
    modelValue: (__VLS_ctx.kitForm.vaultPassword),
    type: "password",
    label: (__VLS_ctx.$t('scopes.kit.dialog.vaultPassword')),
    help: (__VLS_ctx.kitDialogMode === 'export'
        ? __VLS_ctx.$t('scopes.kit.dialog.vaultPasswordExportHelp')
        : __VLS_ctx.$t('scopes.kit.dialog.vaultPasswordImportHelp')),
}));
const __VLS_316 = __VLS_315({
    modelValue: (__VLS_ctx.kitForm.vaultPassword),
    type: "password",
    label: (__VLS_ctx.$t('scopes.kit.dialog.vaultPassword')),
    help: (__VLS_ctx.kitDialogMode === 'export'
        ? __VLS_ctx.$t('scopes.kit.dialog.vaultPasswordExportHelp')
        : __VLS_ctx.$t('scopes.kit.dialog.vaultPasswordImportHelp')),
}, ...__VLS_functionalComponentArgsRest(__VLS_315));
if (__VLS_ctx.kitDialogMode === 'export') {
    const __VLS_318 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_319 = __VLS_asFunctionalComponent(__VLS_318, new __VLS_318({
        modelValue: (__VLS_ctx.kitForm.commitMessage),
        label: (__VLS_ctx.$t('scopes.kit.dialog.commitMessage')),
        help: (__VLS_ctx.$t('scopes.kit.dialog.commitMessageHelp')),
    }));
    const __VLS_320 = __VLS_319({
        modelValue: (__VLS_ctx.kitForm.commitMessage),
        label: (__VLS_ctx.$t('scopes.kit.dialog.commitMessage')),
        help: (__VLS_ctx.$t('scopes.kit.dialog.commitMessageHelp')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_319));
}
if (__VLS_ctx.kitDialogMode !== 'export') {
    const __VLS_322 = {}.VCheckbox;
    /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
    // @ts-ignore
    const __VLS_323 = __VLS_asFunctionalComponent(__VLS_322, new __VLS_322({
        modelValue: (__VLS_ctx.kitForm.trackManifest),
        label: (__VLS_ctx.$t('scopes.kit.dialog.trackManifest')),
        help: (__VLS_ctx.$t('scopes.kit.dialog.trackManifestHelp')),
    }));
    const __VLS_324 = __VLS_323({
        modelValue: (__VLS_ctx.kitForm.trackManifest),
        label: (__VLS_ctx.$t('scopes.kit.dialog.trackManifest')),
        help: (__VLS_ctx.$t('scopes.kit.dialog.trackManifestHelp')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_323));
}
if (__VLS_ctx.kitDialogMode === 'update' && __VLS_ctx.kitForm.trackManifest) {
    const __VLS_326 = {}.VCheckbox;
    /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
    // @ts-ignore
    const __VLS_327 = __VLS_asFunctionalComponent(__VLS_326, new __VLS_326({
        modelValue: (__VLS_ctx.kitForm.prune),
        label: (__VLS_ctx.$t('scopes.kit.dialog.prune')),
        help: (__VLS_ctx.$t('scopes.kit.dialog.pruneHelp')),
    }));
    const __VLS_328 = __VLS_327({
        modelValue: (__VLS_ctx.kitForm.prune),
        label: (__VLS_ctx.$t('scopes.kit.dialog.prune')),
        help: (__VLS_ctx.$t('scopes.kit.dialog.pruneHelp')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_327));
}
if (__VLS_ctx.kitDialogMode !== 'export' && !__VLS_ctx.kitForm.trackManifest) {
    const __VLS_330 = {}.VCheckbox;
    /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
    // @ts-ignore
    const __VLS_331 = __VLS_asFunctionalComponent(__VLS_330, new __VLS_330({
        modelValue: (__VLS_ctx.kitForm.keepPasswords),
        label: (__VLS_ctx.$t('scopes.kit.dialog.keepPasswords')),
        help: (__VLS_ctx.$t('scopes.kit.dialog.keepPasswordsHelp')),
    }));
    const __VLS_332 = __VLS_331({
        modelValue: (__VLS_ctx.kitForm.keepPasswords),
        label: (__VLS_ctx.$t('scopes.kit.dialog.keepPasswords')),
        help: (__VLS_ctx.$t('scopes.kit.dialog.keepPasswordsHelp')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_331));
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex justify-end gap-2 pt-2" },
});
const __VLS_334 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_335 = __VLS_asFunctionalComponent(__VLS_334, new __VLS_334({
    ...{ 'onClick': {} },
    variant: "ghost",
}));
const __VLS_336 = __VLS_335({
    ...{ 'onClick': {} },
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_335));
let __VLS_338;
let __VLS_339;
let __VLS_340;
const __VLS_341 = {
    onClick: (...[$event]) => {
        __VLS_ctx.showKitDialog = false;
    }
};
__VLS_337.slots.default;
(__VLS_ctx.$t('scopes.common.cancel'));
var __VLS_337;
const __VLS_342 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_343 = __VLS_asFunctionalComponent(__VLS_342, new __VLS_342({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (__VLS_ctx.kitNeedsUrl && !__VLS_ctx.kitForm.url.trim()),
    loading: (__VLS_ctx.kitState.busy.value),
}));
const __VLS_344 = __VLS_343({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (__VLS_ctx.kitNeedsUrl && !__VLS_ctx.kitForm.url.trim()),
    loading: (__VLS_ctx.kitState.busy.value),
}, ...__VLS_functionalComponentArgsRest(__VLS_343));
let __VLS_346;
let __VLS_347;
let __VLS_348;
const __VLS_349 = {
    onClick: (__VLS_ctx.submitKitDialog)
};
__VLS_345.slots.default;
(__VLS_ctx.kitDialogSubmitLabel);
var __VLS_345;
var __VLS_289;
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item--active']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['mr-1']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
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
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['-mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['-mt-2']} */ ;
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
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['-mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b-2']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b-2']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['ml-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
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
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-[10px]']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-left']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['line-clamp-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['-mt-1']} */ ;
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
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            EditorShell: EditorShell,
            ProjectListSidebar: ProjectListSidebar,
            SettingFormView: SettingFormView,
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
            focusZone: focusZone,
            form: form,
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
            rightTab: rightTab,
            settingFormsList: settingFormsList,
            settingFormsLoading: settingFormsLoading,
            settingFormsError: settingFormsError,
            selectedSettingForm: selectedSettingForm,
            settingFormsReloadKey: settingFormsReloadKey,
            settingFormsProjectId: settingFormsProjectId,
            selectSettingForm: selectSettingForm,
            backToSettingFormsList: backToSettingFormsList,
            onSettingFormApplied: onSettingFormApplied,
            groupedSettingForms: groupedSettingForms,
            settingTypeOptions: settingTypeOptions,
            selectedGroup: selectedGroup,
            selectedProject: selectedProject,
            groupSelectOptions: groupSelectOptions,
            settingsScope: settingsScope,
            isReservedGroup: isReservedGroup,
            isArchivedProject: isArchivedProject,
            selectTenant: selectTenant,
            saveTenant: saveTenant,
            saveGroup: saveGroup,
            deleteGroup: deleteGroup,
            saveProject: saveProject,
            archiveProject: archiveProject,
            pickerSelectedNode: pickerSelectedNode,
            pickerKitOptions: pickerKitOptions,
            onPickerDataChanged: onPickerDataChanged,
            kitDialogTitle: kitDialogTitle,
            kitDialogSubmitLabel: kitDialogSubmitLabel,
            kitNeedsUrl: kitNeedsUrl,
            openKitDialog: openKitDialog,
            submitKitDialog: submitKitDialog,
            projectLanguageOptions: projectLanguageOptions,
            projectChatLanguage: projectChatLanguage,
            projectContentLanguage: projectContentLanguage,
            onProjectChatLanguageChanged: onProjectChatLanguageChanged,
            onProjectContentLanguageChanged: onProjectContentLanguageChanged,
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