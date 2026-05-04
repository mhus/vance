import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { EditorShell, MarkdownView, VAlert, VButton, VCard, VCheckbox, VEmptyState, VInput, VModal, VSelect, VTextarea, } from '@/components';
import { useAdminUsers } from '@/composables/useAdminUsers';
import { useAdminTeams } from '@/composables/useAdminTeams';
import { useScopeSettings } from '@/composables/useScopeSettings';
import { useHelp } from '@/composables/useHelp';
import { getUsername } from '@vance/shared';
import { SettingType, } from '@vance/generated';
const { t } = useI18n();
const usersState = useAdminUsers();
const teamsState = useAdminTeams();
const settingsState = useScopeSettings();
const help = useHelp();
const currentUsername = getUsername() ?? '';
// SettingType labels are the wire-enum values themselves — they're
// recognisable across UI languages, no translation needed.
const settingTypeOptions = [
    { value: SettingType.STRING, label: 'STRING' },
    { value: SettingType.INT, label: 'INT' },
    { value: SettingType.LONG, label: 'LONG' },
    { value: SettingType.DOUBLE, label: 'DOUBLE' },
    { value: SettingType.BOOLEAN, label: 'BOOLEAN' },
    { value: SettingType.PASSWORD, label: 'PASSWORD' },
];
const editingKey = ref(null);
const editValue = ref('');
const editDescription = ref('');
const newSettingKey = ref('');
const newSettingValue = ref('');
const newSettingType = ref(SettingType.STRING);
const newSettingDescription = ref('');
const selection = ref(null);
const banner = ref(null);
const formError = ref(null);
// ─── User form state ────────────────────────────────────────────────────
const userForm = reactive({
    title: '',
    email: '',
    status: 'ACTIVE',
});
const userStatusOptions = [
    { value: 'ACTIVE', label: 'ACTIVE' },
    { value: 'DISABLED', label: 'DISABLED' },
    { value: 'PENDING', label: 'PENDING' },
];
// ─── Team form state ────────────────────────────────────────────────────
const teamForm = reactive({
    title: '',
    enabled: true,
    membersText: '',
});
// ─── Modals ─────────────────────────────────────────────────────────────
const showCreateUser = ref(false);
const newUserName = ref('');
const newUserTitle = ref('');
const newUserEmail = ref('');
const newUserPassword = ref('');
const newUserError = ref(null);
const showCreateTeam = ref(false);
const newTeamName = ref('');
const newTeamTitle = ref('');
const newTeamMembersText = ref('');
const newTeamError = ref(null);
const showSetPassword = ref(false);
const passwordPlaintext = ref('');
const passwordPlaintextRepeat = ref('');
const passwordError = ref(null);
const NAME_PATTERN_USER = /^[a-z0-9][a-z0-9_.-]*$/;
const NAME_PATTERN_TEAM = /^[a-z0-9][a-z0-9_-]*$/;
// ─── Derived ────────────────────────────────────────────────────────────
const selectedUser = computed(() => {
    const sel = selection.value;
    if (sel?.kind !== 'user')
        return null;
    return usersState.users.value.find(u => u.name === sel.name) ?? null;
});
const selectedTeam = computed(() => {
    const sel = selection.value;
    if (sel?.kind !== 'team')
        return null;
    return teamsState.teams.value.find(t => t.name === sel.name) ?? null;
});
const isOwnAccount = computed(() => selectedUser.value?.name === currentUsername);
const breadcrumbs = computed(() => {
    const sel = selection.value;
    if (!sel)
        return [];
    return [
        sel.kind === 'user'
            ? t('users.breadcrumbs.userPrefix', { name: sel.name })
            : t('users.breadcrumbs.teamPrefix', { name: sel.name }),
    ];
});
const combinedError = computed(() => usersState.error.value || teamsState.error.value || settingsState.error.value);
// ─── Lifecycle ──────────────────────────────────────────────────────────
onMounted(async () => {
    await Promise.all([
        usersState.reload(),
        teamsState.reload(),
        help.load('user-team-admin.md'),
    ]);
});
watch(selection, async (sel) => {
    banner.value = null;
    formError.value = null;
    resetSettingEditor();
    populateForm();
    if (sel?.kind === 'user') {
        await settingsState.load('user', sel.name);
    }
    else {
        settingsState.clear();
    }
});
function resetSettingEditor() {
    editingKey.value = null;
    editValue.value = '';
    editDescription.value = '';
    newSettingKey.value = '';
    newSettingValue.value = '';
    newSettingType.value = SettingType.STRING;
    newSettingDescription.value = '';
}
async function addUserSetting() {
    if (selection.value?.kind !== 'user')
        return;
    const key = newSettingKey.value.trim();
    if (!key)
        return;
    try {
        await settingsState.upsert('user', selection.value.name, key, newSettingValue.value === '' ? null : newSettingValue.value, newSettingType.value, newSettingDescription.value.trim() || null);
        resetSettingEditor();
    }
    catch {
        /* error in settingsState.error */
    }
}
function startEditUserSetting(s) {
    editingKey.value = s.key;
    editValue.value = s.type === SettingType.PASSWORD ? '' : (s.value ?? '');
    editDescription.value = s.description ?? '';
}
function cancelEditUserSetting() {
    editingKey.value = null;
}
async function saveEditUserSetting(s) {
    if (selection.value?.kind !== 'user')
        return;
    try {
        await settingsState.upsert('user', selection.value.name, s.key, editValue.value === '' && s.type === SettingType.PASSWORD ? null : editValue.value, s.type, editDescription.value || null);
        editingKey.value = null;
    }
    catch {
        /* error */
    }
}
async function deleteUserSetting(s) {
    if (selection.value?.kind !== 'user')
        return;
    if (!confirm(t('users.user.settings.confirmDelete', { key: s.key })))
        return;
    try {
        await settingsState.remove('user', selection.value.name, s.key);
    }
    catch {
        /* error */
    }
}
watch(() => selectedUser.value, () => {
    if (selection.value?.kind === 'user')
        populateForm();
});
watch(() => selectedTeam.value, () => {
    if (selection.value?.kind === 'team')
        populateForm();
});
function populateForm() {
    if (selectedUser.value) {
        userForm.title = selectedUser.value.title ?? '';
        userForm.email = selectedUser.value.email ?? '';
        userForm.status = selectedUser.value.status;
    }
    else if (selectedTeam.value) {
        teamForm.title = selectedTeam.value.title ?? '';
        teamForm.enabled = selectedTeam.value.enabled;
        teamForm.membersText = selectedTeam.value.members.join('\n');
    }
}
// ─── Selection ──────────────────────────────────────────────────────────
function selectUser(name) {
    selection.value = { kind: 'user', name };
}
function selectTeam(name) {
    selection.value = { kind: 'team', name };
}
function isSelectedUser(u) {
    return selection.value?.kind === 'user' && selection.value.name === u.name;
}
function isSelectedTeam(t) {
    return selection.value?.kind === 'team' && selection.value.name === t.name;
}
// ─── Save / Delete ──────────────────────────────────────────────────────
async function saveUser() {
    if (selection.value?.kind !== 'user')
        return;
    formError.value = null;
    banner.value = null;
    if (userForm.status === 'DISABLED' && selection.value.name === currentUsername) {
        formError.value = t('users.user.cantDisableSelf');
        return;
    }
    try {
        await usersState.update(selection.value.name, {
            title: userForm.title,
            email: userForm.email,
            status: userForm.status,
        });
        banner.value = t('users.user.saved');
    }
    catch {
        /* error in usersState.error */
    }
}
async function deleteUser() {
    if (selection.value?.kind !== 'user')
        return;
    if (selection.value.name === currentUsername) {
        formError.value = t('users.user.cantDeleteSelf');
        return;
    }
    if (!confirm(t('users.user.confirmDelete', { name: selection.value.name })))
        return;
    const name = selection.value.name;
    try {
        await usersState.remove(name);
        selection.value = null;
        banner.value = t('users.user.deleted', { name });
    }
    catch { /* state.error */ }
}
async function saveTeam() {
    if (selection.value?.kind !== 'team')
        return;
    formError.value = null;
    banner.value = null;
    try {
        await teamsState.update(selection.value.name, {
            title: teamForm.title,
            enabled: teamForm.enabled,
            members: splitLines(teamForm.membersText),
        });
        banner.value = t('users.team.saved');
    }
    catch { /* state.error */ }
}
async function deleteTeam() {
    if (selection.value?.kind !== 'team')
        return;
    if (!confirm(t('users.team.confirmDelete', { name: selection.value.name })))
        return;
    const name = selection.value.name;
    try {
        await teamsState.remove(name);
        selection.value = null;
        banner.value = t('users.team.deleted', { name });
    }
    catch { /* state.error */ }
}
// ─── Create modals ──────────────────────────────────────────────────────
function openCreateUser() {
    newUserName.value = '';
    newUserTitle.value = '';
    newUserEmail.value = '';
    newUserPassword.value = '';
    newUserError.value = null;
    showCreateUser.value = true;
}
async function submitCreateUser() {
    newUserError.value = null;
    const name = newUserName.value.trim();
    if (!name || !NAME_PATTERN_USER.test(name)) {
        newUserError.value = t('users.createUser.nameInvalid');
        return;
    }
    if (usersState.users.value.some(u => u.name === name)) {
        newUserError.value = t('users.createUser.alreadyExists', { name });
        return;
    }
    try {
        await usersState.create({
            name,
            title: newUserTitle.value.trim() || undefined,
            email: newUserEmail.value.trim() || undefined,
            password: newUserPassword.value || undefined,
        });
        showCreateUser.value = false;
        selectUser(name);
        banner.value = t('users.createUser.created', { name });
    }
    catch (e) {
        newUserError.value = e instanceof Error ? e.message : t('users.createUser.createFailed');
    }
}
function openCreateTeam() {
    newTeamName.value = '';
    newTeamTitle.value = '';
    newTeamMembersText.value = '';
    newTeamError.value = null;
    showCreateTeam.value = true;
}
async function submitCreateTeam() {
    newTeamError.value = null;
    const name = newTeamName.value.trim();
    if (!name || !NAME_PATTERN_TEAM.test(name)) {
        newTeamError.value = t('users.createTeam.nameInvalid');
        return;
    }
    if (teamsState.teams.value.some(team => team.name === name)) {
        newTeamError.value = t('users.createTeam.alreadyExists', { name });
        return;
    }
    try {
        await teamsState.create({
            name,
            title: newTeamTitle.value.trim() || undefined,
            members: splitLines(newTeamMembersText.value),
        });
        showCreateTeam.value = false;
        selectTeam(name);
        banner.value = t('users.createTeam.created', { name });
    }
    catch (e) {
        newTeamError.value = e instanceof Error ? e.message : t('users.createTeam.createFailed');
    }
}
// ─── Set password ───────────────────────────────────────────────────────
function openSetPassword() {
    passwordPlaintext.value = '';
    passwordPlaintextRepeat.value = '';
    passwordError.value = null;
    showSetPassword.value = true;
}
async function submitSetPassword() {
    if (selection.value?.kind !== 'user')
        return;
    passwordError.value = null;
    const pw = passwordPlaintext.value;
    if (!pw) {
        passwordError.value = t('users.setPassword.required');
        return;
    }
    if (pw !== passwordPlaintextRepeat.value) {
        passwordError.value = t('users.setPassword.mismatch');
        return;
    }
    try {
        await usersState.setPassword(selection.value.name, pw);
        showSetPassword.value = false;
        banner.value = t('users.setPassword.updated', { name: selection.value.name });
    }
    catch (e) {
        passwordError.value = e instanceof Error ? e.message : t('users.setPassword.failed');
    }
}
// ─── Helpers ────────────────────────────────────────────────────────────
function splitLines(s) {
    return s
        .split(/[\n,]/)
        .map(x => x.trim())
        .filter(x => x.length > 0);
}
function fmt(value) {
    if (value == null)
        return '—';
    if (value instanceof Date)
        return value.toISOString();
    return String(value);
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['row-item']} */ ;
// CSS variable injection 
// CSS variable injection end 
const __VLS_0 = {}.EditorShell;
/** @type {[typeof __VLS_components.EditorShell, typeof __VLS_components.EditorShell, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    title: (__VLS_ctx.$t('users.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    wideRightPanel: true,
}));
const __VLS_2 = __VLS_1({
    title: (__VLS_ctx.$t('users.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    wideRightPanel: true,
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
var __VLS_4 = {};
__VLS_3.slots.default;
{
    const { sidebar: __VLS_thisSlot } = __VLS_3.slots;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.nav, __VLS_intrinsicElements.nav)({
        ...{ class: "flex flex-col gap-3 p-2" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center justify-between px-2 mb-1" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-xs uppercase opacity-50" },
    });
    (__VLS_ctx.$t('users.sidebar.usersTitle'));
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
        onClick: (__VLS_ctx.openCreateUser)
    };
    __VLS_8.slots.default;
    (__VLS_ctx.$t('users.sidebar.addUser'));
    var __VLS_8;
    if (__VLS_ctx.usersState.loading.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "px-2 text-xs opacity-60" },
        });
        (__VLS_ctx.$t('users.loading'));
    }
    for (const [u] of __VLS_getVForSourceType((__VLS_ctx.usersState.users.value))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    __VLS_ctx.selectUser(u.name);
                } },
            key: ('u-' + u.name),
            type: "button",
            ...{ class: "row-item" },
            ...{ class: ({ 'row-item--active': __VLS_ctx.isSelectedUser(u) }) },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center justify-between gap-2" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "font-mono text-sm truncate" },
        });
        (u.name);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-xs px-1.5 py-0.5 rounded" },
            ...{ class: ({
                    'badge-active': u.status === 'ACTIVE',
                    'badge-disabled': u.status === 'DISABLED',
                    'badge-pending': u.status === 'PENDING',
                }) },
        });
        (u.status?.toLowerCase());
        if (u.title || u.email) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs opacity-60 truncate" },
            });
            (u.title);
            if (u.title && u.email) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            }
            (u.email);
        }
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center justify-between px-2 mb-1" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-xs uppercase opacity-50" },
    });
    (__VLS_ctx.$t('users.sidebar.teamsTitle'));
    const __VLS_13 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_14 = __VLS_asFunctionalComponent(__VLS_13, new __VLS_13({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }));
    const __VLS_15 = __VLS_14({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }, ...__VLS_functionalComponentArgsRest(__VLS_14));
    let __VLS_17;
    let __VLS_18;
    let __VLS_19;
    const __VLS_20 = {
        onClick: (__VLS_ctx.openCreateTeam)
    };
    __VLS_16.slots.default;
    (__VLS_ctx.$t('users.sidebar.addTeam'));
    var __VLS_16;
    if (__VLS_ctx.teamsState.loading.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "px-2 text-xs opacity-60" },
        });
        (__VLS_ctx.$t('users.loading'));
    }
    for (const [team] of __VLS_getVForSourceType((__VLS_ctx.teamsState.teams.value))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    __VLS_ctx.selectTeam(team.name);
                } },
            key: ('t-' + team.name),
            type: "button",
            ...{ class: "row-item" },
            ...{ class: ({ 'row-item--active': __VLS_ctx.isSelectedTeam(team) }) },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center justify-between gap-2" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "font-mono text-sm truncate" },
        });
        (team.name);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-xs opacity-60" },
        });
        (team.members.length === 1
            ? __VLS_ctx.$t('users.sidebar.memberCountSingular', { count: team.members.length })
            : __VLS_ctx.$t('users.sidebar.memberCountPlural', { count: team.members.length }));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60 truncate" },
        });
        (team.title);
        if (!team.enabled) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            (' ' + __VLS_ctx.$t('users.sidebar.disabledSuffix'));
        }
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "p-6 flex flex-col gap-3 max-w-3xl" },
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
if (!__VLS_ctx.selection) {
    const __VLS_33 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_34 = __VLS_asFunctionalComponent(__VLS_33, new __VLS_33({
        headline: (__VLS_ctx.$t('users.empty.headline')),
        body: (__VLS_ctx.$t('users.empty.body')),
    }));
    const __VLS_35 = __VLS_34({
        headline: (__VLS_ctx.$t('users.empty.headline')),
        body: (__VLS_ctx.$t('users.empty.body')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_34));
}
else if (__VLS_ctx.selection.kind === 'user') {
    if (!__VLS_ctx.selectedUser) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "opacity-70" },
        });
        (__VLS_ctx.$t('users.loading'));
    }
    else {
        const __VLS_37 = {}.VCard;
        /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
        // @ts-ignore
        const __VLS_38 = __VLS_asFunctionalComponent(__VLS_37, new __VLS_37({
            title: (__VLS_ctx.$t('users.user.cardTitle', { name: __VLS_ctx.selectedUser.name })),
        }));
        const __VLS_39 = __VLS_38({
            title: (__VLS_ctx.$t('users.user.cardTitle', { name: __VLS_ctx.selectedUser.name })),
        }, ...__VLS_functionalComponentArgsRest(__VLS_38));
        __VLS_40.slots.default;
        if (__VLS_ctx.isOwnAccount) {
            const __VLS_41 = {}.VAlert;
            /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
            // @ts-ignore
            const __VLS_42 = __VLS_asFunctionalComponent(__VLS_41, new __VLS_41({
                variant: "info",
                ...{ class: "mb-3" },
            }));
            const __VLS_43 = __VLS_42({
                variant: "info",
                ...{ class: "mb-3" },
            }, ...__VLS_functionalComponentArgsRest(__VLS_42));
            __VLS_44.slots.default;
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            (__VLS_ctx.$t('users.user.ownAccountNote'));
            var __VLS_44;
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex flex-col gap-3" },
        });
        const __VLS_45 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_46 = __VLS_asFunctionalComponent(__VLS_45, new __VLS_45({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.selectedUser.name),
            label: (__VLS_ctx.$t('users.user.nameLabel')),
            disabled: true,
            help: (__VLS_ctx.$t('users.user.nameImmutable')),
        }));
        const __VLS_47 = __VLS_46({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.selectedUser.name),
            label: (__VLS_ctx.$t('users.user.nameLabel')),
            disabled: true,
            help: (__VLS_ctx.$t('users.user.nameImmutable')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_46));
        let __VLS_49;
        let __VLS_50;
        let __VLS_51;
        const __VLS_52 = {
            'onUpdate:modelValue': (() => { })
        };
        var __VLS_48;
        const __VLS_53 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_54 = __VLS_asFunctionalComponent(__VLS_53, new __VLS_53({
            modelValue: (__VLS_ctx.userForm.title),
            label: (__VLS_ctx.$t('users.user.titleLabel')),
        }));
        const __VLS_55 = __VLS_54({
            modelValue: (__VLS_ctx.userForm.title),
            label: (__VLS_ctx.$t('users.user.titleLabel')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_54));
        const __VLS_57 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_58 = __VLS_asFunctionalComponent(__VLS_57, new __VLS_57({
            modelValue: (__VLS_ctx.userForm.email),
            label: (__VLS_ctx.$t('users.user.emailLabel')),
            type: "email",
        }));
        const __VLS_59 = __VLS_58({
            modelValue: (__VLS_ctx.userForm.email),
            label: (__VLS_ctx.$t('users.user.emailLabel')),
            type: "email",
        }, ...__VLS_functionalComponentArgsRest(__VLS_58));
        const __VLS_61 = {}.VSelect;
        /** @type {[typeof __VLS_components.VSelect, ]} */ ;
        // @ts-ignore
        const __VLS_62 = __VLS_asFunctionalComponent(__VLS_61, new __VLS_61({
            modelValue: (__VLS_ctx.userForm.status),
            options: (__VLS_ctx.userStatusOptions),
            label: (__VLS_ctx.$t('users.user.statusLabel')),
        }));
        const __VLS_63 = __VLS_62({
            modelValue: (__VLS_ctx.userForm.status),
            options: (__VLS_ctx.userStatusOptions),
            label: (__VLS_ctx.$t('users.user.statusLabel')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_62));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dl, __VLS_intrinsicElements.dl)({
            ...{ class: "grid grid-cols-2 gap-x-4 gap-y-1 text-sm opacity-80" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
            ...{ class: "opacity-60" },
        });
        (__VLS_ctx.$t('users.user.createdLabel'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
        (__VLS_ctx.fmt(__VLS_ctx.selectedUser.createdAt));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex justify-between" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex gap-2" },
        });
        const __VLS_65 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_66 = __VLS_asFunctionalComponent(__VLS_65, new __VLS_65({
            ...{ 'onClick': {} },
            variant: "danger",
            disabled: (__VLS_ctx.isOwnAccount),
            loading: (__VLS_ctx.usersState.busy.value),
        }));
        const __VLS_67 = __VLS_66({
            ...{ 'onClick': {} },
            variant: "danger",
            disabled: (__VLS_ctx.isOwnAccount),
            loading: (__VLS_ctx.usersState.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_66));
        let __VLS_69;
        let __VLS_70;
        let __VLS_71;
        const __VLS_72 = {
            onClick: (__VLS_ctx.deleteUser)
        };
        __VLS_68.slots.default;
        (__VLS_ctx.$t('users.user.delete'));
        var __VLS_68;
        const __VLS_73 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_74 = __VLS_asFunctionalComponent(__VLS_73, new __VLS_73({
            ...{ 'onClick': {} },
            variant: "ghost",
        }));
        const __VLS_75 = __VLS_74({
            ...{ 'onClick': {} },
            variant: "ghost",
        }, ...__VLS_functionalComponentArgsRest(__VLS_74));
        let __VLS_77;
        let __VLS_78;
        let __VLS_79;
        const __VLS_80 = {
            onClick: (__VLS_ctx.openSetPassword)
        };
        __VLS_76.slots.default;
        (__VLS_ctx.$t('users.user.setPassword'));
        var __VLS_76;
        const __VLS_81 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_82 = __VLS_asFunctionalComponent(__VLS_81, new __VLS_81({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.usersState.busy.value),
        }));
        const __VLS_83 = __VLS_82({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.usersState.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_82));
        let __VLS_85;
        let __VLS_86;
        let __VLS_87;
        const __VLS_88 = {
            onClick: (__VLS_ctx.saveUser)
        };
        __VLS_84.slots.default;
        (__VLS_ctx.$t('users.user.save'));
        var __VLS_84;
        var __VLS_40;
        const __VLS_89 = {}.VCard;
        /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
        // @ts-ignore
        const __VLS_90 = __VLS_asFunctionalComponent(__VLS_89, new __VLS_89({
            title: (__VLS_ctx.$t('users.user.settings.cardTitle')),
        }));
        const __VLS_91 = __VLS_90({
            title: (__VLS_ctx.$t('users.user.settings.cardTitle')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_90));
        __VLS_92.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "text-xs opacity-70 mb-3" },
        });
        (__VLS_ctx.$t('users.user.settings.intro', { name: __VLS_ctx.selectedUser.name }));
        if (!__VLS_ctx.settingsState.loading.value && __VLS_ctx.settingsState.settings.value.length === 0) {
            const __VLS_93 = {}.VEmptyState;
            /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
            // @ts-ignore
            const __VLS_94 = __VLS_asFunctionalComponent(__VLS_93, new __VLS_93({
                headline: (__VLS_ctx.$t('users.user.settings.noSettingsHeadline')),
                body: (__VLS_ctx.$t('users.user.settings.noSettingsBody')),
            }));
            const __VLS_95 = __VLS_94({
                headline: (__VLS_ctx.$t('users.user.settings.noSettingsHeadline')),
                body: (__VLS_ctx.$t('users.user.settings.noSettingsBody')),
            }, ...__VLS_functionalComponentArgsRest(__VLS_94));
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
                    const __VLS_97 = {}.VInput;
                    /** @type {[typeof __VLS_components.VInput, ]} */ ;
                    // @ts-ignore
                    const __VLS_98 = __VLS_asFunctionalComponent(__VLS_97, new __VLS_97({
                        modelValue: (__VLS_ctx.editValue),
                        label: (__VLS_ctx.$t('users.user.settings.valueLabel')),
                    }));
                    const __VLS_99 = __VLS_98({
                        modelValue: (__VLS_ctx.editValue),
                        label: (__VLS_ctx.$t('users.user.settings.valueLabel')),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_98));
                }
                else {
                    const __VLS_101 = {}.VInput;
                    /** @type {[typeof __VLS_components.VInput, ]} */ ;
                    // @ts-ignore
                    const __VLS_102 = __VLS_asFunctionalComponent(__VLS_101, new __VLS_101({
                        modelValue: (__VLS_ctx.editValue),
                        type: "password",
                        label: (__VLS_ctx.$t('users.user.settings.newPasswordLabel')),
                        placeholder: (__VLS_ctx.$t('users.user.settings.passwordEmptyToClear')),
                    }));
                    const __VLS_103 = __VLS_102({
                        modelValue: (__VLS_ctx.editValue),
                        type: "password",
                        label: (__VLS_ctx.$t('users.user.settings.newPasswordLabel')),
                        placeholder: (__VLS_ctx.$t('users.user.settings.passwordEmptyToClear')),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_102));
                }
                const __VLS_105 = {}.VTextarea;
                /** @type {[typeof __VLS_components.VTextarea, ]} */ ;
                // @ts-ignore
                const __VLS_106 = __VLS_asFunctionalComponent(__VLS_105, new __VLS_105({
                    modelValue: (__VLS_ctx.editDescription),
                    label: (__VLS_ctx.$t('users.user.settings.descriptionLabel')),
                    rows: (2),
                }));
                const __VLS_107 = __VLS_106({
                    modelValue: (__VLS_ctx.editDescription),
                    label: (__VLS_ctx.$t('users.user.settings.descriptionLabel')),
                    rows: (2),
                }, ...__VLS_functionalComponentArgsRest(__VLS_106));
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "flex justify-end gap-2 mt-1" },
                });
                const __VLS_109 = {}.VButton;
                /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
                // @ts-ignore
                const __VLS_110 = __VLS_asFunctionalComponent(__VLS_109, new __VLS_109({
                    ...{ 'onClick': {} },
                    variant: "ghost",
                    size: "sm",
                }));
                const __VLS_111 = __VLS_110({
                    ...{ 'onClick': {} },
                    variant: "ghost",
                    size: "sm",
                }, ...__VLS_functionalComponentArgsRest(__VLS_110));
                let __VLS_113;
                let __VLS_114;
                let __VLS_115;
                const __VLS_116 = {
                    onClick: (__VLS_ctx.cancelEditUserSetting)
                };
                __VLS_112.slots.default;
                (__VLS_ctx.$t('users.user.settings.cancel'));
                var __VLS_112;
                const __VLS_117 = {}.VButton;
                /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
                // @ts-ignore
                const __VLS_118 = __VLS_asFunctionalComponent(__VLS_117, new __VLS_117({
                    ...{ 'onClick': {} },
                    variant: "primary",
                    size: "sm",
                    loading: (__VLS_ctx.settingsState.busy.value),
                }));
                const __VLS_119 = __VLS_118({
                    ...{ 'onClick': {} },
                    variant: "primary",
                    size: "sm",
                    loading: (__VLS_ctx.settingsState.busy.value),
                }, ...__VLS_functionalComponentArgsRest(__VLS_118));
                let __VLS_121;
                let __VLS_122;
                let __VLS_123;
                const __VLS_124 = {
                    onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.selection))
                            return;
                        if (!(__VLS_ctx.selection.kind === 'user'))
                            return;
                        if (!!(!__VLS_ctx.selectedUser))
                            return;
                        if (!(__VLS_ctx.editingKey === s.key))
                            return;
                        __VLS_ctx.saveEditUserSetting(s);
                    }
                };
                __VLS_120.slots.default;
                (__VLS_ctx.$t('users.user.settings.save'));
                var __VLS_120;
            }
            else {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "text-sm break-words" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "opacity-70" },
                });
                (s.value ?? __VLS_ctx.$t('users.user.settings.empty'));
                if (s.description) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: "text-xs opacity-60" },
                    });
                    (s.description);
                }
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "flex justify-end gap-2 mt-1" },
                });
                const __VLS_125 = {}.VButton;
                /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
                // @ts-ignore
                const __VLS_126 = __VLS_asFunctionalComponent(__VLS_125, new __VLS_125({
                    ...{ 'onClick': {} },
                    variant: "ghost",
                    size: "sm",
                }));
                const __VLS_127 = __VLS_126({
                    ...{ 'onClick': {} },
                    variant: "ghost",
                    size: "sm",
                }, ...__VLS_functionalComponentArgsRest(__VLS_126));
                let __VLS_129;
                let __VLS_130;
                let __VLS_131;
                const __VLS_132 = {
                    onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.selection))
                            return;
                        if (!(__VLS_ctx.selection.kind === 'user'))
                            return;
                        if (!!(!__VLS_ctx.selectedUser))
                            return;
                        if (!!(__VLS_ctx.editingKey === s.key))
                            return;
                        __VLS_ctx.startEditUserSetting(s);
                    }
                };
                __VLS_128.slots.default;
                (__VLS_ctx.$t('users.user.settings.edit'));
                var __VLS_128;
                const __VLS_133 = {}.VButton;
                /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
                // @ts-ignore
                const __VLS_134 = __VLS_asFunctionalComponent(__VLS_133, new __VLS_133({
                    ...{ 'onClick': {} },
                    variant: "ghost",
                    size: "sm",
                }));
                const __VLS_135 = __VLS_134({
                    ...{ 'onClick': {} },
                    variant: "ghost",
                    size: "sm",
                }, ...__VLS_functionalComponentArgsRest(__VLS_134));
                let __VLS_137;
                let __VLS_138;
                let __VLS_139;
                const __VLS_140 = {
                    onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.selection))
                            return;
                        if (!(__VLS_ctx.selection.kind === 'user'))
                            return;
                        if (!!(!__VLS_ctx.selectedUser))
                            return;
                        if (!!(__VLS_ctx.editingKey === s.key))
                            return;
                        __VLS_ctx.deleteUserSetting(s);
                    }
                };
                __VLS_136.slots.default;
                (__VLS_ctx.$t('users.user.settings.delete'));
                var __VLS_136;
            }
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "border-t border-base-300 pt-3 mt-2 flex flex-col gap-2" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.h4, __VLS_intrinsicElements.h4)({
            ...{ class: "text-xs uppercase opacity-60" },
        });
        (__VLS_ctx.$t('users.user.settings.addTitle'));
        const __VLS_141 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_142 = __VLS_asFunctionalComponent(__VLS_141, new __VLS_141({
            modelValue: (__VLS_ctx.newSettingKey),
            label: (__VLS_ctx.$t('users.user.settings.keyLabel')),
            placeholder: (__VLS_ctx.$t('users.user.settings.keyPlaceholder')),
        }));
        const __VLS_143 = __VLS_142({
            modelValue: (__VLS_ctx.newSettingKey),
            label: (__VLS_ctx.$t('users.user.settings.keyLabel')),
            placeholder: (__VLS_ctx.$t('users.user.settings.keyPlaceholder')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_142));
        const __VLS_145 = {}.VSelect;
        /** @type {[typeof __VLS_components.VSelect, ]} */ ;
        // @ts-ignore
        const __VLS_146 = __VLS_asFunctionalComponent(__VLS_145, new __VLS_145({
            modelValue: (__VLS_ctx.newSettingType),
            label: (__VLS_ctx.$t('users.user.settings.typeLabel')),
            options: (__VLS_ctx.settingTypeOptions),
        }));
        const __VLS_147 = __VLS_146({
            modelValue: (__VLS_ctx.newSettingType),
            label: (__VLS_ctx.$t('users.user.settings.typeLabel')),
            options: (__VLS_ctx.settingTypeOptions),
        }, ...__VLS_functionalComponentArgsRest(__VLS_146));
        if (__VLS_ctx.newSettingType !== __VLS_ctx.SettingType.PASSWORD) {
            const __VLS_149 = {}.VInput;
            /** @type {[typeof __VLS_components.VInput, ]} */ ;
            // @ts-ignore
            const __VLS_150 = __VLS_asFunctionalComponent(__VLS_149, new __VLS_149({
                modelValue: (__VLS_ctx.newSettingValue),
                label: (__VLS_ctx.$t('users.user.settings.valueLabel')),
            }));
            const __VLS_151 = __VLS_150({
                modelValue: (__VLS_ctx.newSettingValue),
                label: (__VLS_ctx.$t('users.user.settings.valueLabel')),
            }, ...__VLS_functionalComponentArgsRest(__VLS_150));
        }
        else {
            const __VLS_153 = {}.VInput;
            /** @type {[typeof __VLS_components.VInput, ]} */ ;
            // @ts-ignore
            const __VLS_154 = __VLS_asFunctionalComponent(__VLS_153, new __VLS_153({
                modelValue: (__VLS_ctx.newSettingValue),
                type: "password",
                label: (__VLS_ctx.$t('users.user.settings.passwordLabel')),
            }));
            const __VLS_155 = __VLS_154({
                modelValue: (__VLS_ctx.newSettingValue),
                type: "password",
                label: (__VLS_ctx.$t('users.user.settings.passwordLabel')),
            }, ...__VLS_functionalComponentArgsRest(__VLS_154));
        }
        const __VLS_157 = {}.VTextarea;
        /** @type {[typeof __VLS_components.VTextarea, ]} */ ;
        // @ts-ignore
        const __VLS_158 = __VLS_asFunctionalComponent(__VLS_157, new __VLS_157({
            modelValue: (__VLS_ctx.newSettingDescription),
            label: (__VLS_ctx.$t('users.user.settings.descriptionOptional')),
            rows: (2),
        }));
        const __VLS_159 = __VLS_158({
            modelValue: (__VLS_ctx.newSettingDescription),
            label: (__VLS_ctx.$t('users.user.settings.descriptionOptional')),
            rows: (2),
        }, ...__VLS_functionalComponentArgsRest(__VLS_158));
        const __VLS_161 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_162 = __VLS_asFunctionalComponent(__VLS_161, new __VLS_161({
            ...{ 'onClick': {} },
            variant: "primary",
            size: "sm",
            disabled: (!__VLS_ctx.newSettingKey.trim()),
            loading: (__VLS_ctx.settingsState.busy.value),
        }));
        const __VLS_163 = __VLS_162({
            ...{ 'onClick': {} },
            variant: "primary",
            size: "sm",
            disabled: (!__VLS_ctx.newSettingKey.trim()),
            loading: (__VLS_ctx.settingsState.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_162));
        let __VLS_165;
        let __VLS_166;
        let __VLS_167;
        const __VLS_168 = {
            onClick: (__VLS_ctx.addUserSetting)
        };
        __VLS_164.slots.default;
        (__VLS_ctx.$t('users.user.settings.add'));
        var __VLS_164;
        var __VLS_92;
    }
}
else if (__VLS_ctx.selection.kind === 'team') {
    if (!__VLS_ctx.selectedTeam) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "opacity-70" },
        });
        (__VLS_ctx.$t('users.loading'));
    }
    else {
        const __VLS_169 = {}.VCard;
        /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
        // @ts-ignore
        const __VLS_170 = __VLS_asFunctionalComponent(__VLS_169, new __VLS_169({
            title: (__VLS_ctx.$t('users.team.cardTitle', { name: __VLS_ctx.selectedTeam.name })),
        }));
        const __VLS_171 = __VLS_170({
            title: (__VLS_ctx.$t('users.team.cardTitle', { name: __VLS_ctx.selectedTeam.name })),
        }, ...__VLS_functionalComponentArgsRest(__VLS_170));
        __VLS_172.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex flex-col gap-3" },
        });
        const __VLS_173 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_174 = __VLS_asFunctionalComponent(__VLS_173, new __VLS_173({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.selectedTeam.name),
            label: (__VLS_ctx.$t('users.team.nameLabel')),
            disabled: true,
            help: (__VLS_ctx.$t('users.team.nameImmutable')),
        }));
        const __VLS_175 = __VLS_174({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.selectedTeam.name),
            label: (__VLS_ctx.$t('users.team.nameLabel')),
            disabled: true,
            help: (__VLS_ctx.$t('users.team.nameImmutable')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_174));
        let __VLS_177;
        let __VLS_178;
        let __VLS_179;
        const __VLS_180 = {
            'onUpdate:modelValue': (() => { })
        };
        var __VLS_176;
        const __VLS_181 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_182 = __VLS_asFunctionalComponent(__VLS_181, new __VLS_181({
            modelValue: (__VLS_ctx.teamForm.title),
            label: (__VLS_ctx.$t('users.team.titleLabel')),
        }));
        const __VLS_183 = __VLS_182({
            modelValue: (__VLS_ctx.teamForm.title),
            label: (__VLS_ctx.$t('users.team.titleLabel')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_182));
        const __VLS_185 = {}.VCheckbox;
        /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
        // @ts-ignore
        const __VLS_186 = __VLS_asFunctionalComponent(__VLS_185, new __VLS_185({
            modelValue: (__VLS_ctx.teamForm.enabled),
            label: (__VLS_ctx.$t('users.team.enabledLabel')),
        }));
        const __VLS_187 = __VLS_186({
            modelValue: (__VLS_ctx.teamForm.enabled),
            label: (__VLS_ctx.$t('users.team.enabledLabel')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_186));
        const __VLS_189 = {}.VTextarea;
        /** @type {[typeof __VLS_components.VTextarea, ]} */ ;
        // @ts-ignore
        const __VLS_190 = __VLS_asFunctionalComponent(__VLS_189, new __VLS_189({
            modelValue: (__VLS_ctx.teamForm.membersText),
            label: (__VLS_ctx.$t('users.team.membersLabel')),
            placeholder: (__VLS_ctx.$t('users.team.membersPlaceholder')),
            rows: (6),
            help: (__VLS_ctx.splitLines(__VLS_ctx.teamForm.membersText).length === 1
                ? __VLS_ctx.$t('users.team.memberHelpSingular', { count: __VLS_ctx.splitLines(__VLS_ctx.teamForm.membersText).length })
                : __VLS_ctx.$t('users.team.memberHelpPlural', { count: __VLS_ctx.splitLines(__VLS_ctx.teamForm.membersText).length })),
        }));
        const __VLS_191 = __VLS_190({
            modelValue: (__VLS_ctx.teamForm.membersText),
            label: (__VLS_ctx.$t('users.team.membersLabel')),
            placeholder: (__VLS_ctx.$t('users.team.membersPlaceholder')),
            rows: (6),
            help: (__VLS_ctx.splitLines(__VLS_ctx.teamForm.membersText).length === 1
                ? __VLS_ctx.$t('users.team.memberHelpSingular', { count: __VLS_ctx.splitLines(__VLS_ctx.teamForm.membersText).length })
                : __VLS_ctx.$t('users.team.memberHelpPlural', { count: __VLS_ctx.splitLines(__VLS_ctx.teamForm.membersText).length })),
        }, ...__VLS_functionalComponentArgsRest(__VLS_190));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dl, __VLS_intrinsicElements.dl)({
            ...{ class: "grid grid-cols-2 gap-x-4 gap-y-1 text-sm opacity-80" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
            ...{ class: "opacity-60" },
        });
        (__VLS_ctx.$t('users.team.createdLabel'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
        (__VLS_ctx.fmt(__VLS_ctx.selectedTeam.createdAt));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex justify-between" },
        });
        const __VLS_193 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_194 = __VLS_asFunctionalComponent(__VLS_193, new __VLS_193({
            ...{ 'onClick': {} },
            variant: "danger",
            loading: (__VLS_ctx.teamsState.busy.value),
        }));
        const __VLS_195 = __VLS_194({
            ...{ 'onClick': {} },
            variant: "danger",
            loading: (__VLS_ctx.teamsState.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_194));
        let __VLS_197;
        let __VLS_198;
        let __VLS_199;
        const __VLS_200 = {
            onClick: (__VLS_ctx.deleteTeam)
        };
        __VLS_196.slots.default;
        (__VLS_ctx.$t('users.team.delete'));
        var __VLS_196;
        const __VLS_201 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_202 = __VLS_asFunctionalComponent(__VLS_201, new __VLS_201({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.teamsState.busy.value),
        }));
        const __VLS_203 = __VLS_202({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.teamsState.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_202));
        let __VLS_205;
        let __VLS_206;
        let __VLS_207;
        const __VLS_208 = {
            onClick: (__VLS_ctx.saveTeam)
        };
        __VLS_204.slots.default;
        (__VLS_ctx.$t('users.team.save'));
        var __VLS_204;
        var __VLS_172;
    }
}
{
    const { 'right-panel': __VLS_thisSlot } = __VLS_3.slots;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "p-4 flex flex-col gap-4" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({
        ...{ class: "text-xs uppercase opacity-60 mb-2" },
    });
    (__VLS_ctx.$t('users.helpPanel.title'));
    if (__VLS_ctx.help.loading.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60" },
        });
        (__VLS_ctx.$t('users.helpPanel.loading'));
    }
    else if (__VLS_ctx.help.error.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60" },
        });
        (__VLS_ctx.$t('users.helpPanel.unavailable', { error: __VLS_ctx.help.error.value }));
    }
    else if (!__VLS_ctx.help.content.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60" },
        });
        (__VLS_ctx.$t('users.helpPanel.empty'));
    }
    else {
        const __VLS_209 = {}.MarkdownView;
        /** @type {[typeof __VLS_components.MarkdownView, ]} */ ;
        // @ts-ignore
        const __VLS_210 = __VLS_asFunctionalComponent(__VLS_209, new __VLS_209({
            source: (__VLS_ctx.help.content.value),
        }));
        const __VLS_211 = __VLS_210({
            source: (__VLS_ctx.help.content.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_210));
    }
}
const __VLS_213 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_214 = __VLS_asFunctionalComponent(__VLS_213, new __VLS_213({
    modelValue: (__VLS_ctx.showCreateUser),
    title: (__VLS_ctx.$t('users.createUser.title')),
}));
const __VLS_215 = __VLS_214({
    modelValue: (__VLS_ctx.showCreateUser),
    title: (__VLS_ctx.$t('users.createUser.title')),
}, ...__VLS_functionalComponentArgsRest(__VLS_214));
__VLS_216.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
if (__VLS_ctx.newUserError) {
    const __VLS_217 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_218 = __VLS_asFunctionalComponent(__VLS_217, new __VLS_217({
        variant: "error",
    }));
    const __VLS_219 = __VLS_218({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_218));
    __VLS_220.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.newUserError);
    var __VLS_220;
}
const __VLS_221 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_222 = __VLS_asFunctionalComponent(__VLS_221, new __VLS_221({
    modelValue: (__VLS_ctx.newUserName),
    label: (__VLS_ctx.$t('users.createUser.nameLabel')),
    required: true,
    help: (__VLS_ctx.$t('users.createUser.nameHelp')),
}));
const __VLS_223 = __VLS_222({
    modelValue: (__VLS_ctx.newUserName),
    label: (__VLS_ctx.$t('users.createUser.nameLabel')),
    required: true,
    help: (__VLS_ctx.$t('users.createUser.nameHelp')),
}, ...__VLS_functionalComponentArgsRest(__VLS_222));
const __VLS_225 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_226 = __VLS_asFunctionalComponent(__VLS_225, new __VLS_225({
    modelValue: (__VLS_ctx.newUserTitle),
    label: (__VLS_ctx.$t('users.createUser.titleLabel')),
}));
const __VLS_227 = __VLS_226({
    modelValue: (__VLS_ctx.newUserTitle),
    label: (__VLS_ctx.$t('users.createUser.titleLabel')),
}, ...__VLS_functionalComponentArgsRest(__VLS_226));
const __VLS_229 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_230 = __VLS_asFunctionalComponent(__VLS_229, new __VLS_229({
    modelValue: (__VLS_ctx.newUserEmail),
    label: (__VLS_ctx.$t('users.createUser.emailLabel')),
    type: "email",
}));
const __VLS_231 = __VLS_230({
    modelValue: (__VLS_ctx.newUserEmail),
    label: (__VLS_ctx.$t('users.createUser.emailLabel')),
    type: "email",
}, ...__VLS_functionalComponentArgsRest(__VLS_230));
const __VLS_233 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_234 = __VLS_asFunctionalComponent(__VLS_233, new __VLS_233({
    modelValue: (__VLS_ctx.newUserPassword),
    label: (__VLS_ctx.$t('users.createUser.passwordLabel')),
    type: "password",
    help: (__VLS_ctx.$t('users.createUser.passwordHelp')),
}));
const __VLS_235 = __VLS_234({
    modelValue: (__VLS_ctx.newUserPassword),
    label: (__VLS_ctx.$t('users.createUser.passwordLabel')),
    type: "password",
    help: (__VLS_ctx.$t('users.createUser.passwordHelp')),
}, ...__VLS_functionalComponentArgsRest(__VLS_234));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex justify-end gap-2" },
});
const __VLS_237 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_238 = __VLS_asFunctionalComponent(__VLS_237, new __VLS_237({
    ...{ 'onClick': {} },
    variant: "ghost",
}));
const __VLS_239 = __VLS_238({
    ...{ 'onClick': {} },
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_238));
let __VLS_241;
let __VLS_242;
let __VLS_243;
const __VLS_244 = {
    onClick: (...[$event]) => {
        __VLS_ctx.showCreateUser = false;
    }
};
__VLS_240.slots.default;
(__VLS_ctx.$t('users.createUser.cancel'));
var __VLS_240;
const __VLS_245 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_246 = __VLS_asFunctionalComponent(__VLS_245, new __VLS_245({
    ...{ 'onClick': {} },
    variant: "primary",
    loading: (__VLS_ctx.usersState.busy.value),
}));
const __VLS_247 = __VLS_246({
    ...{ 'onClick': {} },
    variant: "primary",
    loading: (__VLS_ctx.usersState.busy.value),
}, ...__VLS_functionalComponentArgsRest(__VLS_246));
let __VLS_249;
let __VLS_250;
let __VLS_251;
const __VLS_252 = {
    onClick: (__VLS_ctx.submitCreateUser)
};
__VLS_248.slots.default;
(__VLS_ctx.$t('users.createUser.create'));
var __VLS_248;
var __VLS_216;
const __VLS_253 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_254 = __VLS_asFunctionalComponent(__VLS_253, new __VLS_253({
    modelValue: (__VLS_ctx.showCreateTeam),
    title: (__VLS_ctx.$t('users.createTeam.title')),
}));
const __VLS_255 = __VLS_254({
    modelValue: (__VLS_ctx.showCreateTeam),
    title: (__VLS_ctx.$t('users.createTeam.title')),
}, ...__VLS_functionalComponentArgsRest(__VLS_254));
__VLS_256.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
if (__VLS_ctx.newTeamError) {
    const __VLS_257 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_258 = __VLS_asFunctionalComponent(__VLS_257, new __VLS_257({
        variant: "error",
    }));
    const __VLS_259 = __VLS_258({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_258));
    __VLS_260.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.newTeamError);
    var __VLS_260;
}
const __VLS_261 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_262 = __VLS_asFunctionalComponent(__VLS_261, new __VLS_261({
    modelValue: (__VLS_ctx.newTeamName),
    label: (__VLS_ctx.$t('users.createTeam.nameLabel')),
    required: true,
    help: (__VLS_ctx.$t('users.createTeam.nameHelp')),
}));
const __VLS_263 = __VLS_262({
    modelValue: (__VLS_ctx.newTeamName),
    label: (__VLS_ctx.$t('users.createTeam.nameLabel')),
    required: true,
    help: (__VLS_ctx.$t('users.createTeam.nameHelp')),
}, ...__VLS_functionalComponentArgsRest(__VLS_262));
const __VLS_265 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_266 = __VLS_asFunctionalComponent(__VLS_265, new __VLS_265({
    modelValue: (__VLS_ctx.newTeamTitle),
    label: (__VLS_ctx.$t('users.createTeam.titleLabel')),
}));
const __VLS_267 = __VLS_266({
    modelValue: (__VLS_ctx.newTeamTitle),
    label: (__VLS_ctx.$t('users.createTeam.titleLabel')),
}, ...__VLS_functionalComponentArgsRest(__VLS_266));
const __VLS_269 = {}.VTextarea;
/** @type {[typeof __VLS_components.VTextarea, ]} */ ;
// @ts-ignore
const __VLS_270 = __VLS_asFunctionalComponent(__VLS_269, new __VLS_269({
    modelValue: (__VLS_ctx.newTeamMembersText),
    label: (__VLS_ctx.$t('users.createTeam.membersLabel')),
    placeholder: (__VLS_ctx.$t('users.createTeam.membersPlaceholder')),
    rows: (4),
}));
const __VLS_271 = __VLS_270({
    modelValue: (__VLS_ctx.newTeamMembersText),
    label: (__VLS_ctx.$t('users.createTeam.membersLabel')),
    placeholder: (__VLS_ctx.$t('users.createTeam.membersPlaceholder')),
    rows: (4),
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
        __VLS_ctx.showCreateTeam = false;
    }
};
__VLS_276.slots.default;
(__VLS_ctx.$t('users.createTeam.cancel'));
var __VLS_276;
const __VLS_281 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_282 = __VLS_asFunctionalComponent(__VLS_281, new __VLS_281({
    ...{ 'onClick': {} },
    variant: "primary",
    loading: (__VLS_ctx.teamsState.busy.value),
}));
const __VLS_283 = __VLS_282({
    ...{ 'onClick': {} },
    variant: "primary",
    loading: (__VLS_ctx.teamsState.busy.value),
}, ...__VLS_functionalComponentArgsRest(__VLS_282));
let __VLS_285;
let __VLS_286;
let __VLS_287;
const __VLS_288 = {
    onClick: (__VLS_ctx.submitCreateTeam)
};
__VLS_284.slots.default;
(__VLS_ctx.$t('users.createTeam.create'));
var __VLS_284;
var __VLS_256;
const __VLS_289 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_290 = __VLS_asFunctionalComponent(__VLS_289, new __VLS_289({
    modelValue: (__VLS_ctx.showSetPassword),
    title: (__VLS_ctx.$t('users.setPassword.title')),
}));
const __VLS_291 = __VLS_290({
    modelValue: (__VLS_ctx.showSetPassword),
    title: (__VLS_ctx.$t('users.setPassword.title')),
}, ...__VLS_functionalComponentArgsRest(__VLS_290));
__VLS_292.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
if (__VLS_ctx.passwordError) {
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
    (__VLS_ctx.passwordError);
    var __VLS_296;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
    ...{ class: "text-sm opacity-80" },
});
(__VLS_ctx.$t('users.setPassword.intro', {
    name: __VLS_ctx.selection?.kind === 'user' ? __VLS_ctx.selection.name : '',
}));
const __VLS_297 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_298 = __VLS_asFunctionalComponent(__VLS_297, new __VLS_297({
    modelValue: (__VLS_ctx.passwordPlaintext),
    label: (__VLS_ctx.$t('users.setPassword.newPasswordLabel')),
    type: "password",
    required: true,
    autocomplete: "new-password",
}));
const __VLS_299 = __VLS_298({
    modelValue: (__VLS_ctx.passwordPlaintext),
    label: (__VLS_ctx.$t('users.setPassword.newPasswordLabel')),
    type: "password",
    required: true,
    autocomplete: "new-password",
}, ...__VLS_functionalComponentArgsRest(__VLS_298));
const __VLS_301 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_302 = __VLS_asFunctionalComponent(__VLS_301, new __VLS_301({
    modelValue: (__VLS_ctx.passwordPlaintextRepeat),
    label: (__VLS_ctx.$t('users.setPassword.repeatPasswordLabel')),
    type: "password",
    required: true,
    autocomplete: "new-password",
}));
const __VLS_303 = __VLS_302({
    modelValue: (__VLS_ctx.passwordPlaintextRepeat),
    label: (__VLS_ctx.$t('users.setPassword.repeatPasswordLabel')),
    type: "password",
    required: true,
    autocomplete: "new-password",
}, ...__VLS_functionalComponentArgsRest(__VLS_302));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex justify-end gap-2" },
});
const __VLS_305 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_306 = __VLS_asFunctionalComponent(__VLS_305, new __VLS_305({
    ...{ 'onClick': {} },
    variant: "ghost",
}));
const __VLS_307 = __VLS_306({
    ...{ 'onClick': {} },
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_306));
let __VLS_309;
let __VLS_310;
let __VLS_311;
const __VLS_312 = {
    onClick: (...[$event]) => {
        __VLS_ctx.showSetPassword = false;
    }
};
__VLS_308.slots.default;
(__VLS_ctx.$t('users.setPassword.cancel'));
var __VLS_308;
const __VLS_313 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_314 = __VLS_asFunctionalComponent(__VLS_313, new __VLS_313({
    ...{ 'onClick': {} },
    variant: "primary",
    loading: (__VLS_ctx.usersState.busy.value),
}));
const __VLS_315 = __VLS_314({
    ...{ 'onClick': {} },
    variant: "primary",
    loading: (__VLS_ctx.usersState.busy.value),
}, ...__VLS_functionalComponentArgsRest(__VLS_314));
let __VLS_317;
let __VLS_318;
let __VLS_319;
const __VLS_320 = {
    onClick: (__VLS_ctx.submitSetPassword)
};
__VLS_316.slots.default;
(__VLS_ctx.$t('users.setPassword.submit'));
var __VLS_316;
var __VLS_292;
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
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
/** @type {__VLS_StyleScopedClasses['row-item']} */ ;
/** @type {__VLS_StyleScopedClasses['row-item--active']} */ ;
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
/** @type {__VLS_StyleScopedClasses['badge-active']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-disabled']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-pending']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
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
/** @type {__VLS_StyleScopedClasses['row-item']} */ ;
/** @type {__VLS_StyleScopedClasses['row-item--active']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['p-6']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-3xl']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
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
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
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
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
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
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
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
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            EditorShell: EditorShell,
            MarkdownView: MarkdownView,
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
            usersState: usersState,
            teamsState: teamsState,
            settingsState: settingsState,
            help: help,
            settingTypeOptions: settingTypeOptions,
            editingKey: editingKey,
            editValue: editValue,
            editDescription: editDescription,
            newSettingKey: newSettingKey,
            newSettingValue: newSettingValue,
            newSettingType: newSettingType,
            newSettingDescription: newSettingDescription,
            selection: selection,
            banner: banner,
            formError: formError,
            userForm: userForm,
            userStatusOptions: userStatusOptions,
            teamForm: teamForm,
            showCreateUser: showCreateUser,
            newUserName: newUserName,
            newUserTitle: newUserTitle,
            newUserEmail: newUserEmail,
            newUserPassword: newUserPassword,
            newUserError: newUserError,
            showCreateTeam: showCreateTeam,
            newTeamName: newTeamName,
            newTeamTitle: newTeamTitle,
            newTeamMembersText: newTeamMembersText,
            newTeamError: newTeamError,
            showSetPassword: showSetPassword,
            passwordPlaintext: passwordPlaintext,
            passwordPlaintextRepeat: passwordPlaintextRepeat,
            passwordError: passwordError,
            selectedUser: selectedUser,
            selectedTeam: selectedTeam,
            isOwnAccount: isOwnAccount,
            breadcrumbs: breadcrumbs,
            combinedError: combinedError,
            addUserSetting: addUserSetting,
            startEditUserSetting: startEditUserSetting,
            cancelEditUserSetting: cancelEditUserSetting,
            saveEditUserSetting: saveEditUserSetting,
            deleteUserSetting: deleteUserSetting,
            selectUser: selectUser,
            selectTeam: selectTeam,
            isSelectedUser: isSelectedUser,
            isSelectedTeam: isSelectedTeam,
            saveUser: saveUser,
            deleteUser: deleteUser,
            saveTeam: saveTeam,
            deleteTeam: deleteTeam,
            openCreateUser: openCreateUser,
            submitCreateUser: submitCreateUser,
            openCreateTeam: openCreateTeam,
            submitCreateTeam: submitCreateTeam,
            openSetPassword: openSetPassword,
            submitSetPassword: submitSetPassword,
            splitLines: splitLines,
            fmt: fmt,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=UsersApp.vue.js.map