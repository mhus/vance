import { computed, onMounted, reactive, ref, watch } from 'vue';
import { EditorShell, MarkdownView, VAlert, VButton, VCard, VCheckbox, VEmptyState, VInput, VModal, VSelect, VTextarea, } from '@/components';
import { useAdminUsers } from '@/composables/useAdminUsers';
import { useAdminTeams } from '@/composables/useAdminTeams';
import { useHelp } from '@/composables/useHelp';
import { getUsername } from '@vance/shared';
const usersState = useAdminUsers();
const teamsState = useAdminTeams();
const help = useHelp();
const currentUsername = getUsername() ?? '';
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
    return [sel.kind === 'user' ? `User: ${sel.name}` : `Team: ${sel.name}`];
});
const combinedError = computed(() => usersState.error.value || teamsState.error.value);
// ─── Lifecycle ──────────────────────────────────────────────────────────
onMounted(async () => {
    await Promise.all([
        usersState.reload(),
        teamsState.reload(),
        help.load('user-team-admin.md'),
    ]);
});
watch(selection, () => {
    banner.value = null;
    formError.value = null;
    populateForm();
});
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
        formError.value = 'You cannot disable your own account.';
        return;
    }
    try {
        await usersState.update(selection.value.name, {
            title: userForm.title,
            email: userForm.email,
            status: userForm.status,
        });
        banner.value = 'User saved.';
    }
    catch {
        /* error in usersState.error */
    }
}
async function deleteUser() {
    if (selection.value?.kind !== 'user')
        return;
    if (selection.value.name === currentUsername) {
        formError.value = 'You cannot delete your own account.';
        return;
    }
    if (!confirm(`Delete user "${selection.value.name}"? Memberships in teams are not auto-cleaned.`))
        return;
    const name = selection.value.name;
    try {
        await usersState.remove(name);
        selection.value = null;
        banner.value = `User "${name}" deleted.`;
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
        banner.value = 'Team saved.';
    }
    catch { /* state.error */ }
}
async function deleteTeam() {
    if (selection.value?.kind !== 'team')
        return;
    if (!confirm(`Delete team "${selection.value.name}"?`))
        return;
    const name = selection.value.name;
    try {
        await teamsState.remove(name);
        selection.value = null;
        banner.value = `Team "${name}" deleted.`;
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
        newUserError.value = 'Name must be lower-case alphanumerics with optional ".", "-" or "_".';
        return;
    }
    if (usersState.users.value.some(u => u.name === name)) {
        newUserError.value = `A user named "${name}" already exists.`;
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
        banner.value = `User "${name}" created.`;
    }
    catch (e) {
        newUserError.value = e instanceof Error ? e.message : 'Failed to create user.';
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
        newTeamError.value = 'Name must be lower-case alphanumerics with optional "-" or "_".';
        return;
    }
    if (teamsState.teams.value.some(t => t.name === name)) {
        newTeamError.value = `A team named "${name}" already exists.`;
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
        banner.value = `Team "${name}" created.`;
    }
    catch (e) {
        newTeamError.value = e instanceof Error ? e.message : 'Failed to create team.';
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
        passwordError.value = 'Password is required.';
        return;
    }
    if (pw !== passwordPlaintextRepeat.value) {
        passwordError.value = 'Passwords do not match.';
        return;
    }
    try {
        await usersState.setPassword(selection.value.name, pw);
        showSetPassword.value = false;
        banner.value = `Password updated for "${selection.value.name}".`;
    }
    catch (e) {
        passwordError.value = e instanceof Error ? e.message : 'Failed to set password.';
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
    title: "Users & Teams",
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    wideRightPanel: true,
}));
const __VLS_2 = __VLS_1({
    title: "Users & Teams",
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
    var __VLS_8;
    if (__VLS_ctx.usersState.loading.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "px-2 text-xs opacity-60" },
        });
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
    var __VLS_16;
    if (__VLS_ctx.teamsState.loading.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "px-2 text-xs opacity-60" },
        });
    }
    for (const [t] of __VLS_getVForSourceType((__VLS_ctx.teamsState.teams.value))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    __VLS_ctx.selectTeam(t.name);
                } },
            key: ('t-' + t.name),
            type: "button",
            ...{ class: "row-item" },
            ...{ class: ({ 'row-item--active': __VLS_ctx.isSelectedTeam(t) }) },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center justify-between gap-2" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "font-mono text-sm truncate" },
        });
        (t.name);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-xs opacity-60" },
        });
        (t.members.length);
        if (t.members.length !== 1) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60 truncate" },
        });
        (t.title);
        if (!t.enabled) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
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
        headline: "Pick a user or team",
        body: "Use the lists on the left, or create a new entry with + User / + Team.",
    }));
    const __VLS_35 = __VLS_34({
        headline: "Pick a user or team",
        body: "Use the lists on the left, or create a new entry with + User / + Team.",
    }, ...__VLS_functionalComponentArgsRest(__VLS_34));
}
else if (__VLS_ctx.selection.kind === 'user') {
    if (!__VLS_ctx.selectedUser) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "opacity-70" },
        });
    }
    else {
        const __VLS_37 = {}.VCard;
        /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
        // @ts-ignore
        const __VLS_38 = __VLS_asFunctionalComponent(__VLS_37, new __VLS_37({
            title: (`User: ${__VLS_ctx.selectedUser.name}`),
        }));
        const __VLS_39 = __VLS_38({
            title: (`User: ${__VLS_ctx.selectedUser.name}`),
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
            label: "Name",
            disabled: true,
            help: "User name is immutable.",
        }));
        const __VLS_47 = __VLS_46({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.selectedUser.name),
            label: "Name",
            disabled: true,
            help: "User name is immutable.",
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
            label: "Title",
        }));
        const __VLS_55 = __VLS_54({
            modelValue: (__VLS_ctx.userForm.title),
            label: "Title",
        }, ...__VLS_functionalComponentArgsRest(__VLS_54));
        const __VLS_57 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_58 = __VLS_asFunctionalComponent(__VLS_57, new __VLS_57({
            modelValue: (__VLS_ctx.userForm.email),
            label: "Email",
            type: "email",
        }));
        const __VLS_59 = __VLS_58({
            modelValue: (__VLS_ctx.userForm.email),
            label: "Email",
            type: "email",
        }, ...__VLS_functionalComponentArgsRest(__VLS_58));
        const __VLS_61 = {}.VSelect;
        /** @type {[typeof __VLS_components.VSelect, ]} */ ;
        // @ts-ignore
        const __VLS_62 = __VLS_asFunctionalComponent(__VLS_61, new __VLS_61({
            modelValue: (__VLS_ctx.userForm.status),
            options: (__VLS_ctx.userStatusOptions),
            label: "Status",
        }));
        const __VLS_63 = __VLS_62({
            modelValue: (__VLS_ctx.userForm.status),
            options: (__VLS_ctx.userStatusOptions),
            label: "Status",
        }, ...__VLS_functionalComponentArgsRest(__VLS_62));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dl, __VLS_intrinsicElements.dl)({
            ...{ class: "grid grid-cols-2 gap-x-4 gap-y-1 text-sm opacity-80" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
            ...{ class: "opacity-60" },
        });
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
        var __VLS_84;
        var __VLS_40;
    }
}
else if (__VLS_ctx.selection.kind === 'team') {
    if (!__VLS_ctx.selectedTeam) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "opacity-70" },
        });
    }
    else {
        const __VLS_89 = {}.VCard;
        /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
        // @ts-ignore
        const __VLS_90 = __VLS_asFunctionalComponent(__VLS_89, new __VLS_89({
            title: (`Team: ${__VLS_ctx.selectedTeam.name}`),
        }));
        const __VLS_91 = __VLS_90({
            title: (`Team: ${__VLS_ctx.selectedTeam.name}`),
        }, ...__VLS_functionalComponentArgsRest(__VLS_90));
        __VLS_92.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex flex-col gap-3" },
        });
        const __VLS_93 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_94 = __VLS_asFunctionalComponent(__VLS_93, new __VLS_93({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.selectedTeam.name),
            label: "Name",
            disabled: true,
            help: "Team name is immutable.",
        }));
        const __VLS_95 = __VLS_94({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.selectedTeam.name),
            label: "Name",
            disabled: true,
            help: "Team name is immutable.",
        }, ...__VLS_functionalComponentArgsRest(__VLS_94));
        let __VLS_97;
        let __VLS_98;
        let __VLS_99;
        const __VLS_100 = {
            'onUpdate:modelValue': (() => { })
        };
        var __VLS_96;
        const __VLS_101 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_102 = __VLS_asFunctionalComponent(__VLS_101, new __VLS_101({
            modelValue: (__VLS_ctx.teamForm.title),
            label: "Title",
        }));
        const __VLS_103 = __VLS_102({
            modelValue: (__VLS_ctx.teamForm.title),
            label: "Title",
        }, ...__VLS_functionalComponentArgsRest(__VLS_102));
        const __VLS_105 = {}.VCheckbox;
        /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
        // @ts-ignore
        const __VLS_106 = __VLS_asFunctionalComponent(__VLS_105, new __VLS_105({
            modelValue: (__VLS_ctx.teamForm.enabled),
            label: "Enabled",
        }));
        const __VLS_107 = __VLS_106({
            modelValue: (__VLS_ctx.teamForm.enabled),
            label: "Enabled",
        }, ...__VLS_functionalComponentArgsRest(__VLS_106));
        const __VLS_109 = {}.VTextarea;
        /** @type {[typeof __VLS_components.VTextarea, ]} */ ;
        // @ts-ignore
        const __VLS_110 = __VLS_asFunctionalComponent(__VLS_109, new __VLS_109({
            modelValue: (__VLS_ctx.teamForm.membersText),
            label: "Members",
            placeholder: "One username per line. Removing a line drops the member.",
            rows: (6),
            help: (`${__VLS_ctx.splitLines(__VLS_ctx.teamForm.membersText).length} member${__VLS_ctx.splitLines(__VLS_ctx.teamForm.membersText).length === 1 ? '' : 's'}`),
        }));
        const __VLS_111 = __VLS_110({
            modelValue: (__VLS_ctx.teamForm.membersText),
            label: "Members",
            placeholder: "One username per line. Removing a line drops the member.",
            rows: (6),
            help: (`${__VLS_ctx.splitLines(__VLS_ctx.teamForm.membersText).length} member${__VLS_ctx.splitLines(__VLS_ctx.teamForm.membersText).length === 1 ? '' : 's'}`),
        }, ...__VLS_functionalComponentArgsRest(__VLS_110));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dl, __VLS_intrinsicElements.dl)({
            ...{ class: "grid grid-cols-2 gap-x-4 gap-y-1 text-sm opacity-80" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
            ...{ class: "opacity-60" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
        (__VLS_ctx.fmt(__VLS_ctx.selectedTeam.createdAt));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex justify-between" },
        });
        const __VLS_113 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_114 = __VLS_asFunctionalComponent(__VLS_113, new __VLS_113({
            ...{ 'onClick': {} },
            variant: "danger",
            loading: (__VLS_ctx.teamsState.busy.value),
        }));
        const __VLS_115 = __VLS_114({
            ...{ 'onClick': {} },
            variant: "danger",
            loading: (__VLS_ctx.teamsState.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_114));
        let __VLS_117;
        let __VLS_118;
        let __VLS_119;
        const __VLS_120 = {
            onClick: (__VLS_ctx.deleteTeam)
        };
        __VLS_116.slots.default;
        var __VLS_116;
        const __VLS_121 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_122 = __VLS_asFunctionalComponent(__VLS_121, new __VLS_121({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.teamsState.busy.value),
        }));
        const __VLS_123 = __VLS_122({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.teamsState.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_122));
        let __VLS_125;
        let __VLS_126;
        let __VLS_127;
        const __VLS_128 = {
            onClick: (__VLS_ctx.saveTeam)
        };
        __VLS_124.slots.default;
        var __VLS_124;
        var __VLS_92;
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
    if (__VLS_ctx.help.loading.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60" },
        });
    }
    else if (__VLS_ctx.help.error.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60" },
        });
        (__VLS_ctx.help.error.value);
    }
    else if (!__VLS_ctx.help.content.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60" },
        });
    }
    else {
        const __VLS_129 = {}.MarkdownView;
        /** @type {[typeof __VLS_components.MarkdownView, ]} */ ;
        // @ts-ignore
        const __VLS_130 = __VLS_asFunctionalComponent(__VLS_129, new __VLS_129({
            source: (__VLS_ctx.help.content.value),
        }));
        const __VLS_131 = __VLS_130({
            source: (__VLS_ctx.help.content.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_130));
    }
}
const __VLS_133 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_134 = __VLS_asFunctionalComponent(__VLS_133, new __VLS_133({
    modelValue: (__VLS_ctx.showCreateUser),
    title: "New user",
}));
const __VLS_135 = __VLS_134({
    modelValue: (__VLS_ctx.showCreateUser),
    title: "New user",
}, ...__VLS_functionalComponentArgsRest(__VLS_134));
__VLS_136.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
if (__VLS_ctx.newUserError) {
    const __VLS_137 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_138 = __VLS_asFunctionalComponent(__VLS_137, new __VLS_137({
        variant: "error",
    }));
    const __VLS_139 = __VLS_138({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_138));
    __VLS_140.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.newUserError);
    var __VLS_140;
}
const __VLS_141 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_142 = __VLS_asFunctionalComponent(__VLS_141, new __VLS_141({
    modelValue: (__VLS_ctx.newUserName),
    label: "Name",
    required: true,
    help: "Lower-case alphanumerics with optional '.', '-' or '_'.",
}));
const __VLS_143 = __VLS_142({
    modelValue: (__VLS_ctx.newUserName),
    label: "Name",
    required: true,
    help: "Lower-case alphanumerics with optional '.', '-' or '_'.",
}, ...__VLS_functionalComponentArgsRest(__VLS_142));
const __VLS_145 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_146 = __VLS_asFunctionalComponent(__VLS_145, new __VLS_145({
    modelValue: (__VLS_ctx.newUserTitle),
    label: "Title",
}));
const __VLS_147 = __VLS_146({
    modelValue: (__VLS_ctx.newUserTitle),
    label: "Title",
}, ...__VLS_functionalComponentArgsRest(__VLS_146));
const __VLS_149 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_150 = __VLS_asFunctionalComponent(__VLS_149, new __VLS_149({
    modelValue: (__VLS_ctx.newUserEmail),
    label: "Email",
    type: "email",
}));
const __VLS_151 = __VLS_150({
    modelValue: (__VLS_ctx.newUserEmail),
    label: "Email",
    type: "email",
}, ...__VLS_functionalComponentArgsRest(__VLS_150));
const __VLS_153 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_154 = __VLS_asFunctionalComponent(__VLS_153, new __VLS_153({
    modelValue: (__VLS_ctx.newUserPassword),
    label: "Password (optional)",
    type: "password",
    help: "Empty creates a passwordless account that cannot log in until you set one.",
}));
const __VLS_155 = __VLS_154({
    modelValue: (__VLS_ctx.newUserPassword),
    label: "Password (optional)",
    type: "password",
    help: "Empty creates a passwordless account that cannot log in until you set one.",
}, ...__VLS_functionalComponentArgsRest(__VLS_154));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex justify-end gap-2" },
});
const __VLS_157 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_158 = __VLS_asFunctionalComponent(__VLS_157, new __VLS_157({
    ...{ 'onClick': {} },
    variant: "ghost",
}));
const __VLS_159 = __VLS_158({
    ...{ 'onClick': {} },
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_158));
let __VLS_161;
let __VLS_162;
let __VLS_163;
const __VLS_164 = {
    onClick: (...[$event]) => {
        __VLS_ctx.showCreateUser = false;
    }
};
__VLS_160.slots.default;
var __VLS_160;
const __VLS_165 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_166 = __VLS_asFunctionalComponent(__VLS_165, new __VLS_165({
    ...{ 'onClick': {} },
    variant: "primary",
    loading: (__VLS_ctx.usersState.busy.value),
}));
const __VLS_167 = __VLS_166({
    ...{ 'onClick': {} },
    variant: "primary",
    loading: (__VLS_ctx.usersState.busy.value),
}, ...__VLS_functionalComponentArgsRest(__VLS_166));
let __VLS_169;
let __VLS_170;
let __VLS_171;
const __VLS_172 = {
    onClick: (__VLS_ctx.submitCreateUser)
};
__VLS_168.slots.default;
var __VLS_168;
var __VLS_136;
const __VLS_173 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_174 = __VLS_asFunctionalComponent(__VLS_173, new __VLS_173({
    modelValue: (__VLS_ctx.showCreateTeam),
    title: "New team",
}));
const __VLS_175 = __VLS_174({
    modelValue: (__VLS_ctx.showCreateTeam),
    title: "New team",
}, ...__VLS_functionalComponentArgsRest(__VLS_174));
__VLS_176.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
if (__VLS_ctx.newTeamError) {
    const __VLS_177 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_178 = __VLS_asFunctionalComponent(__VLS_177, new __VLS_177({
        variant: "error",
    }));
    const __VLS_179 = __VLS_178({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_178));
    __VLS_180.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.newTeamError);
    var __VLS_180;
}
const __VLS_181 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_182 = __VLS_asFunctionalComponent(__VLS_181, new __VLS_181({
    modelValue: (__VLS_ctx.newTeamName),
    label: "Name",
    required: true,
    help: "Lower-case alphanumerics with optional '-' or '_'.",
}));
const __VLS_183 = __VLS_182({
    modelValue: (__VLS_ctx.newTeamName),
    label: "Name",
    required: true,
    help: "Lower-case alphanumerics with optional '-' or '_'.",
}, ...__VLS_functionalComponentArgsRest(__VLS_182));
const __VLS_185 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_186 = __VLS_asFunctionalComponent(__VLS_185, new __VLS_185({
    modelValue: (__VLS_ctx.newTeamTitle),
    label: "Title",
}));
const __VLS_187 = __VLS_186({
    modelValue: (__VLS_ctx.newTeamTitle),
    label: "Title",
}, ...__VLS_functionalComponentArgsRest(__VLS_186));
const __VLS_189 = {}.VTextarea;
/** @type {[typeof __VLS_components.VTextarea, ]} */ ;
// @ts-ignore
const __VLS_190 = __VLS_asFunctionalComponent(__VLS_189, new __VLS_189({
    modelValue: (__VLS_ctx.newTeamMembersText),
    label: "Initial members",
    placeholder: "One username per line.",
    rows: (4),
}));
const __VLS_191 = __VLS_190({
    modelValue: (__VLS_ctx.newTeamMembersText),
    label: "Initial members",
    placeholder: "One username per line.",
    rows: (4),
}, ...__VLS_functionalComponentArgsRest(__VLS_190));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex justify-end gap-2" },
});
const __VLS_193 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_194 = __VLS_asFunctionalComponent(__VLS_193, new __VLS_193({
    ...{ 'onClick': {} },
    variant: "ghost",
}));
const __VLS_195 = __VLS_194({
    ...{ 'onClick': {} },
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_194));
let __VLS_197;
let __VLS_198;
let __VLS_199;
const __VLS_200 = {
    onClick: (...[$event]) => {
        __VLS_ctx.showCreateTeam = false;
    }
};
__VLS_196.slots.default;
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
    onClick: (__VLS_ctx.submitCreateTeam)
};
__VLS_204.slots.default;
var __VLS_204;
var __VLS_176;
const __VLS_209 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_210 = __VLS_asFunctionalComponent(__VLS_209, new __VLS_209({
    modelValue: (__VLS_ctx.showSetPassword),
    title: "Set password",
}));
const __VLS_211 = __VLS_210({
    modelValue: (__VLS_ctx.showSetPassword),
    title: "Set password",
}, ...__VLS_functionalComponentArgsRest(__VLS_210));
__VLS_212.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
if (__VLS_ctx.passwordError) {
    const __VLS_213 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_214 = __VLS_asFunctionalComponent(__VLS_213, new __VLS_213({
        variant: "error",
    }));
    const __VLS_215 = __VLS_214({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_214));
    __VLS_216.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.passwordError);
    var __VLS_216;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
    ...{ class: "text-sm opacity-80" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
(__VLS_ctx.selection?.kind === 'user' ? __VLS_ctx.selection.name : '');
const __VLS_217 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_218 = __VLS_asFunctionalComponent(__VLS_217, new __VLS_217({
    modelValue: (__VLS_ctx.passwordPlaintext),
    label: "New password",
    type: "password",
    required: true,
    autocomplete: "new-password",
}));
const __VLS_219 = __VLS_218({
    modelValue: (__VLS_ctx.passwordPlaintext),
    label: "New password",
    type: "password",
    required: true,
    autocomplete: "new-password",
}, ...__VLS_functionalComponentArgsRest(__VLS_218));
const __VLS_221 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_222 = __VLS_asFunctionalComponent(__VLS_221, new __VLS_221({
    modelValue: (__VLS_ctx.passwordPlaintextRepeat),
    label: "Repeat password",
    type: "password",
    required: true,
    autocomplete: "new-password",
}));
const __VLS_223 = __VLS_222({
    modelValue: (__VLS_ctx.passwordPlaintextRepeat),
    label: "Repeat password",
    type: "password",
    required: true,
    autocomplete: "new-password",
}, ...__VLS_functionalComponentArgsRest(__VLS_222));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex justify-end gap-2" },
});
const __VLS_225 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_226 = __VLS_asFunctionalComponent(__VLS_225, new __VLS_225({
    ...{ 'onClick': {} },
    variant: "ghost",
}));
const __VLS_227 = __VLS_226({
    ...{ 'onClick': {} },
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_226));
let __VLS_229;
let __VLS_230;
let __VLS_231;
const __VLS_232 = {
    onClick: (...[$event]) => {
        __VLS_ctx.showSetPassword = false;
    }
};
__VLS_228.slots.default;
var __VLS_228;
const __VLS_233 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_234 = __VLS_asFunctionalComponent(__VLS_233, new __VLS_233({
    ...{ 'onClick': {} },
    variant: "primary",
    loading: (__VLS_ctx.usersState.busy.value),
}));
const __VLS_235 = __VLS_234({
    ...{ 'onClick': {} },
    variant: "primary",
    loading: (__VLS_ctx.usersState.busy.value),
}, ...__VLS_functionalComponentArgsRest(__VLS_234));
let __VLS_237;
let __VLS_238;
let __VLS_239;
const __VLS_240 = {
    onClick: (__VLS_ctx.submitSetPassword)
};
__VLS_236.slots.default;
var __VLS_236;
var __VLS_212;
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
            usersState: usersState,
            teamsState: teamsState,
            help: help,
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