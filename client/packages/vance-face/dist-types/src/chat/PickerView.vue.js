import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { WebSocketRequestError, listProjectRecipes, listSessions, reactivateSession, } from '@vance/shared';
import { AccentColor, SessionStatus, } from '@vance/generated';
import { useTenantProjects } from '@composables/useTenantProjects';
import { ProjectListSidebar, VAlert, VButton, VCheckbox, VEmptyState, VInput, VModal, } from '@components/index';
import SessionSearchModal from './SessionSearchModal.vue';
const { t } = useI18n();
const props = defineProps();
const emit = defineEmits();
const selectedProjectName = defineModel('selectedProject', {
    default: null,
});
/**
 * The {@code Teleport} target div lives in ChatApp's EditorShell
 * sidebar slot. Both this component and the target render in the same
 * Vue mount pass, but during the very first render `document.getElementById`
 * may not see the target yet. We disable the Teleport for that first
 * tick and flip it on after mount.
 */
const teleportReady = ref(false);
const { groups, projects, loading: projectsLoading, error: projectsError, reload: loadProjects } = useTenantProjects();
const sessions = ref([]);
const sessionsLoading = ref(false);
const sessionsError = ref(null);
const bootstrapping = ref(false);
const bootstrapError = ref(null);
const showArchived = ref(false);
const reactivating = ref(null);
const searchOpen = ref(false);
/**
 * Recipe picker — opens on the "+" button. {@code null} entry is the
 * always-present "Default" choice that triggers a bootstrap without
 * {@code chatRecipe}, falling back to the server's default-recipe
 * resolution.
 */
const recipeModalOpen = ref(false);
const recipeOptions = ref([]);
const recipesLoading = ref(false);
const recipesError = ref(null);
/**
 * Free-text filter for the sessions list in the main area — matches
 * against the displayed session title (case-insensitive substring).
 */
const sessionFilter = ref('');
/**
 * Narrow-viewport: the session-filter / archived-toggle / new-session
 * cluster collapses into a single {@code ⋯} button on phones. Toggled
 * open by tapping the button.
 */
const pickerToolsOpen = ref(false);
const filteredSessions = computed(() => {
    const needle = sessionFilter.value.trim().toLowerCase();
    if (!needle)
        return sessions.value;
    return sessions.value.filter((s) => sessionTitle(s).toLowerCase().includes(needle));
});
async function loadSessions(projectName) {
    sessionsLoading.value = true;
    sessionsError.value = null;
    try {
        // REST list is owner-scoped + sorted by pinned, lastActivityAt desc.
        sessions.value = await listSessions({
            projectId: projectName,
            includeArchived: showArchived.value,
        });
    }
    catch (e) {
        sessionsError.value = describeError(e, t('chat.picker.failedToLoadSessions'));
        sessions.value = [];
    }
    finally {
        sessionsLoading.value = false;
    }
}
/**
 * "Hard-blocked" = the user can't open this session at all in the
 * picker. Multi-user-aware: shared sessions are never hard-blocked
 * (they can always be joined), even when another participant currently
 * holds the bind.
 */
function isHardBlocked(session) {
    if (!session.bound)
        return false;
    if (session.allowMultipleClients)
        return false;
    // Private + bound + not mine = can't take over.
    return props.username === null || session.userId !== props.username;
}
function pickSession(session) {
    if (session.status === SessionStatus.ARCHIVED)
        return;
    // Multi-user routing — see planning/multi-user-sessions.md §2.5.
    //  - Bound + shared (allowMultipleClients): always joinable, no
    //    prompt. The owner already declared "anyone may join", we
    //    just attach as a secondary participant.
    //  - Bound + private + owner==me: another tab/device of mine has
    //    the session. Confirm the hijack so the user knows what they're
    //    about to do.
    //  - Bound + private + owner!=me: blocked (legacy "occupied" UX).
    if (session.bound && !session.allowMultipleClients) {
        const mine = props.username !== null && session.userId === props.username;
        if (!mine)
            return;
        if (!window.confirm(t('chat.picker.hijackConfirm')))
            return;
    }
    emit('session-picked', session.sessionId);
}
async function reactivateAndOpen(session) {
    if (!window.confirm(t('chat.sessionHeader.reactivateConfirm')))
        return;
    reactivating.value = session.sessionId;
    try {
        await reactivateSession(session.sessionId);
        emit('session-picked', session.sessionId);
    }
    catch (e) {
        sessionsError.value = describeError(e, t('chat.picker.failedToLoadSessions'));
    }
    finally {
        reactivating.value = null;
    }
}
async function openRecipeModal() {
    if (!selectedProjectName.value)
        return;
    bootstrapError.value = null;
    recipesError.value = null;
    recipeModalOpen.value = true;
    recipesLoading.value = true;
    try {
        recipeOptions.value = await listProjectRecipes(selectedProjectName.value);
    }
    catch (e) {
        recipesError.value = describeError(e, t('chat.picker.recipeLoadFailed'));
        recipeOptions.value = [];
    }
    finally {
        recipesLoading.value = false;
    }
}
async function bootstrapNew(chatRecipe) {
    if (!selectedProjectName.value)
        return;
    bootstrapping.value = true;
    bootstrapError.value = null;
    try {
        const payload = {
            projectId: selectedProjectName.value,
            processes: [],
        };
        if (chatRecipe)
            payload.chatRecipe = chatRecipe;
        const response = await props.socket.send('session-bootstrap', payload);
        recipeModalOpen.value = false;
        emit('session-bootstrapped', response.sessionId);
    }
    catch (e) {
        bootstrapError.value = describeError(e, t('chat.picker.failedToStartSession'));
    }
    finally {
        bootstrapping.value = false;
    }
}
function describeError(e, fallback) {
    if (e instanceof WebSocketRequestError) {
        return `${e.message} (code ${e.errorCode})`;
    }
    return e instanceof Error ? e.message : fallback;
}
function toEpochMillis(d) {
    if (d === undefined || d === null)
        return 0;
    if (d instanceof Date)
        return d.getTime();
    if (typeof d === 'number')
        return d;
    const parsed = new Date(d).getTime();
    return Number.isFinite(parsed) ? parsed : 0;
}
function formatRelativeTime(value) {
    const epochMillis = toEpochMillis(value);
    if (!epochMillis)
        return '';
    const diffMs = Date.now() - epochMillis;
    const seconds = Math.floor(diffMs / 1000);
    if (seconds < 60)
        return t('chat.picker.relativeJustNow');
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60)
        return t('chat.picker.relativeMinutes', { n: minutes });
    const hours = Math.floor(minutes / 60);
    if (hours < 24)
        return t('chat.picker.relativeHours', { n: hours });
    const days = Math.floor(hours / 24);
    if (days < 7)
        return t('chat.picker.relativeDays', { n: days });
    return new Date(epochMillis).toLocaleDateString();
}
function projectTitle(name) {
    const p = projects.value.find((x) => x.name === name);
    return p?.title || p?.name || name;
}
/** Forwarded from {@link ProjectListSidebar} — wraps the v-model
 *  write with the URL-history emit so back/forward steps between
 *  projects (mirrors the old in-PickerView {@code selectProject}). */
function onProjectPick(payload) {
    emit('project-pick', payload);
}
/** {@link ProjectListSidebar} created a new group or project.
 *  Reload {@code useTenantProjects} so the new entry shows up;
 *  for projects, jump straight into the new workspace. */
async function onProjectListDataChanged(payload) {
    await loadProjects();
    if (payload.kind === 'project') {
        selectedProjectName.value = payload.name;
        emit('project-pick', {
            name: payload.name,
            title: projectTitle(payload.name),
        });
    }
}
function sessionTitle(session) {
    if (session.title && session.title.trim().length > 0)
        return session.title;
    if (session.firstUserMessage && session.firstUserMessage.trim().length > 0) {
        return session.firstUserMessage;
    }
    return t('chat.sessionHeader.untitled');
}
const COLOR_BORDER = {
    [AccentColor.SLATE]: 'border-l-slate-500',
    [AccentColor.RED]: 'border-l-red-500',
    [AccentColor.ORANGE]: 'border-l-orange-500',
    [AccentColor.AMBER]: 'border-l-amber-500',
    [AccentColor.GREEN]: 'border-l-green-500',
    [AccentColor.TEAL]: 'border-l-teal-500',
    [AccentColor.CYAN]: 'border-l-cyan-500',
    [AccentColor.BLUE]: 'border-l-blue-500',
    [AccentColor.INDIGO]: 'border-l-indigo-500',
    [AccentColor.PURPLE]: 'border-l-purple-500',
    [AccentColor.PINK]: 'border-l-pink-500',
    [AccentColor.ROSE]: 'border-l-rose-500',
};
function colorBorderClass(session) {
    if (session.color === undefined)
        return 'border-l-base-300';
    return COLOR_BORDER[session.color] ?? 'border-l-base-300';
}
function onSearchPick(sessionId) {
    searchOpen.value = false;
    emit('session-picked', sessionId);
}
onMounted(async () => {
    // Enable the project-list Teleport on the next tick — after the
    // first render flush so ChatApp's sidebar-slot target div is in DOM.
    teleportReady.value = true;
    await loadProjects();
    // Title resolution can only happen once {@link projects} has loaded.
    // Emit resolution for whatever selection arrived via the URL-driven
    // v-model so ChatApp can populate the breadcrumb on first paint.
    if (selectedProjectName.value && projects.value.length > 0) {
        emit('project-resolved', {
            name: selectedProjectName.value,
            title: projectTitle(selectedProjectName.value),
        });
    }
});
// Title resolution on any subsequent change (e.g. URL-driven popstate
// switching to a different project). Catches the case where the
// selection changes after the projects list is already loaded.
watch(selectedProjectName, (name) => {
    if (name && projects.value.length > 0) {
        emit('project-resolved', { name, title: projectTitle(name) });
    }
});
// {@code immediate} ensures sessions also load when the picker mounts
// with a pre-set selection (e.g. after leaveLive from a chat session,
// or on a fresh page load with {@code ?project=}). Without it, the
// watcher only catches subsequent changes and the sessions list stays
// empty until the user re-picks the same project.
watch(selectedProjectName, async (newName) => {
    if (newName)
        await loadSessions(newName);
}, { immediate: true });
watch(showArchived, async () => {
    if (selectedProjectName.value)
        await loadSessions(selectedProjectName.value);
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_defaults = {
    'selectedProject': null,
};
const __VLS_modelEmit = defineEmits();
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['picker-tools-toggle']} */ ;
/** @type {__VLS_StyleScopedClasses['picker-tools']} */ ;
/** @type {__VLS_StyleScopedClasses['picker-tools']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "h-full min-h-0 flex flex-col" },
});
const __VLS_0 = {}.Teleport;
/** @type {[typeof __VLS_components.Teleport, typeof __VLS_components.Teleport, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    to: "#vance-picker-projects-target",
    disabled: (!__VLS_ctx.teleportReady),
}));
const __VLS_2 = __VLS_1({
    to: "#vance-picker-projects-target",
    disabled: (!__VLS_ctx.teleportReady),
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
__VLS_3.slots.default;
const __VLS_4 = {}.ProjectListSidebar;
/** @type {[typeof __VLS_components.ProjectListSidebar, typeof __VLS_components.ProjectListSidebar, ]} */ ;
// @ts-ignore
const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
    ...{ 'onProjectPick': {} },
    ...{ 'onFocusMain': {} },
    ...{ 'onDataChanged': {} },
    selectedProject: (__VLS_ctx.selectedProjectName),
    groups: (__VLS_ctx.groups),
    projects: (__VLS_ctx.projects),
    loading: (__VLS_ctx.projectsLoading),
    error: (__VLS_ctx.projectsError),
    heading: (__VLS_ctx.$t('chat.picker.projectsTitle')),
    filterPlaceholder: (__VLS_ctx.$t('chat.picker.filterPlaceholder')),
    ungroupedLabel: (__VLS_ctx.$t('chat.picker.ungrouped')),
    emptyHeadline: (__VLS_ctx.$t('chat.picker.noProjects')),
    emptyBody: (__VLS_ctx.$t('chat.picker.noProjectsBody')),
    editEnabled: true,
}));
const __VLS_6 = __VLS_5({
    ...{ 'onProjectPick': {} },
    ...{ 'onFocusMain': {} },
    ...{ 'onDataChanged': {} },
    selectedProject: (__VLS_ctx.selectedProjectName),
    groups: (__VLS_ctx.groups),
    projects: (__VLS_ctx.projects),
    loading: (__VLS_ctx.projectsLoading),
    error: (__VLS_ctx.projectsError),
    heading: (__VLS_ctx.$t('chat.picker.projectsTitle')),
    filterPlaceholder: (__VLS_ctx.$t('chat.picker.filterPlaceholder')),
    ungroupedLabel: (__VLS_ctx.$t('chat.picker.ungrouped')),
    emptyHeadline: (__VLS_ctx.$t('chat.picker.noProjects')),
    emptyBody: (__VLS_ctx.$t('chat.picker.noProjectsBody')),
    editEnabled: true,
}, ...__VLS_functionalComponentArgsRest(__VLS_5));
let __VLS_8;
let __VLS_9;
let __VLS_10;
const __VLS_11 = {
    onProjectPick: (__VLS_ctx.onProjectPick)
};
const __VLS_12 = {
    onFocusMain: (...[$event]) => {
        __VLS_ctx.emit('focus-main');
    }
};
const __VLS_13 = {
    onDataChanged: (__VLS_ctx.onProjectListDataChanged)
};
__VLS_7.slots.default;
{
    const { 'header-extra': __VLS_thisSlot } = __VLS_7.slots;
    const __VLS_14 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_15 = __VLS_asFunctionalComponent(__VLS_14, new __VLS_14({
        ...{ 'onPointerdown': {} },
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        title: (__VLS_ctx.$t('chat.picker.searchTooltip')),
    }));
    const __VLS_16 = __VLS_15({
        ...{ 'onPointerdown': {} },
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        title: (__VLS_ctx.$t('chat.picker.searchTooltip')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_15));
    let __VLS_18;
    let __VLS_19;
    let __VLS_20;
    const __VLS_21 = {
        onPointerdown: () => { }
    };
    const __VLS_22 = {
        onClick: (...[$event]) => {
            __VLS_ctx.searchOpen = true;
            __VLS_ctx.emit('focus-main');
        }
    };
    __VLS_17.slots.default;
    var __VLS_17;
}
{
    const { loading: __VLS_thisSlot } = __VLS_7.slots;
    (__VLS_ctx.$t('chat.picker.loading'));
}
{
    const { 'filter-no-match': __VLS_thisSlot } = __VLS_7.slots;
    const [{ filter }] = __VLS_getSlotParams(__VLS_thisSlot);
    (__VLS_ctx.$t('chat.picker.filterNoMatch', { filter }));
}
var __VLS_7;
var __VLS_3;
__VLS_asFunctionalElement(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({
    ...{ class: "flex-1 min-w-0 min-h-0 flex flex-col" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "px-6 pt-4 pb-3 border-b border-base-300 bg-base-100 flex items-center gap-3 relative" },
});
if (__VLS_ctx.selectedProjectName) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 min-w-0 flex items-baseline gap-2" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h2, __VLS_intrinsicElements.h2)({
        ...{ class: "text-lg font-semibold truncate" },
    });
    (__VLS_ctx.projectTitle(__VLS_ctx.selectedProjectName));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "hidden sm:inline text-sm opacity-50 font-mono truncate" },
    });
    (__VLS_ctx.selectedProjectName);
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h2, __VLS_intrinsicElements.h2)({
        ...{ class: "flex-1 min-w-0 text-lg font-semibold" },
    });
    (__VLS_ctx.$t('chat.picker.pickAProject'));
}
const __VLS_23 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_24 = __VLS_asFunctionalComponent(__VLS_23, new __VLS_23({
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
    ...{ class: "picker-tools-toggle" },
    title: (__VLS_ctx.pickerToolsOpen ? 'Hide tools' : 'Show tools'),
}));
const __VLS_25 = __VLS_24({
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
    ...{ class: "picker-tools-toggle" },
    title: (__VLS_ctx.pickerToolsOpen ? 'Hide tools' : 'Show tools'),
}, ...__VLS_functionalComponentArgsRest(__VLS_24));
let __VLS_27;
let __VLS_28;
let __VLS_29;
const __VLS_30 = {
    onClick: (...[$event]) => {
        __VLS_ctx.pickerToolsOpen = !__VLS_ctx.pickerToolsOpen;
    }
};
__VLS_26.slots.default;
var __VLS_26;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "picker-tools" },
    ...{ class: ({ 'picker-tools--open': __VLS_ctx.pickerToolsOpen }) },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "w-[150px]" },
});
const __VLS_31 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_32 = __VLS_asFunctionalComponent(__VLS_31, new __VLS_31({
    modelValue: (__VLS_ctx.sessionFilter),
    placeholder: (__VLS_ctx.$t('chat.picker.sessionFilterPlaceholder')),
}));
const __VLS_33 = __VLS_32({
    modelValue: (__VLS_ctx.sessionFilter),
    placeholder: (__VLS_ctx.$t('chat.picker.sessionFilterPlaceholder')),
}, ...__VLS_functionalComponentArgsRest(__VLS_32));
const __VLS_35 = {}.VCheckbox;
/** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
// @ts-ignore
const __VLS_36 = __VLS_asFunctionalComponent(__VLS_35, new __VLS_35({
    modelValue: (__VLS_ctx.showArchived),
    label: (__VLS_ctx.$t('chat.picker.showArchived')),
}));
const __VLS_37 = __VLS_36({
    modelValue: (__VLS_ctx.showArchived),
    label: (__VLS_ctx.$t('chat.picker.showArchived')),
}, ...__VLS_functionalComponentArgsRest(__VLS_36));
const __VLS_39 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_40 = __VLS_asFunctionalComponent(__VLS_39, new __VLS_39({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.selectedProjectName || __VLS_ctx.bootstrapping),
    loading: (__VLS_ctx.bootstrapping),
    title: (__VLS_ctx.$t('chat.picker.newSession')),
}));
const __VLS_41 = __VLS_40({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.selectedProjectName || __VLS_ctx.bootstrapping),
    loading: (__VLS_ctx.bootstrapping),
    title: (__VLS_ctx.$t('chat.picker.newSession')),
}, ...__VLS_functionalComponentArgsRest(__VLS_40));
let __VLS_43;
let __VLS_44;
let __VLS_45;
const __VLS_46 = {
    onClick: (__VLS_ctx.openRecipeModal)
};
__VLS_42.slots.default;
var __VLS_42;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex-1 min-h-0 overflow-y-auto px-6 py-4" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "max-w-3xl mx-auto flex flex-col gap-4" },
});
if (__VLS_ctx.bootstrapError) {
    const __VLS_47 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_48 = __VLS_asFunctionalComponent(__VLS_47, new __VLS_47({
        variant: "error",
    }));
    const __VLS_49 = __VLS_48({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_48));
    __VLS_50.slots.default;
    (__VLS_ctx.bootstrapError);
    var __VLS_50;
}
if (__VLS_ctx.sessionsError) {
    const __VLS_51 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_52 = __VLS_asFunctionalComponent(__VLS_51, new __VLS_51({
        variant: "error",
    }));
    const __VLS_53 = __VLS_52({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_52));
    __VLS_54.slots.default;
    (__VLS_ctx.sessionsError);
    var __VLS_54;
}
if (__VLS_ctx.sessionsLoading) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-60" },
    });
    (__VLS_ctx.$t('chat.picker.sessionsLoading'));
}
else if (!__VLS_ctx.sessionsLoading && __VLS_ctx.sessions.length === 0 && __VLS_ctx.selectedProjectName) {
    const __VLS_55 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_56 = __VLS_asFunctionalComponent(__VLS_55, new __VLS_55({
        headline: (__VLS_ctx.$t('chat.picker.noSessions')),
        body: (__VLS_ctx.$t('chat.picker.noSessionsBody')),
    }));
    const __VLS_57 = __VLS_56({
        headline: (__VLS_ctx.$t('chat.picker.noSessions')),
        body: (__VLS_ctx.$t('chat.picker.noSessionsBody')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_56));
}
else if (__VLS_ctx.sessions.length > 0 && __VLS_ctx.filteredSessions.length === 0) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-60" },
    });
    (__VLS_ctx.$t('chat.picker.sessionFilterNoMatch', { filter: __VLS_ctx.sessionFilter }));
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
        ...{ class: "flex flex-col gap-2" },
    });
    for (const [session] of __VLS_getVForSourceType((__VLS_ctx.filteredSessions))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
            ...{ onClick: (...[$event]) => {
                    if (!!(__VLS_ctx.sessionsLoading))
                        return;
                    if (!!(!__VLS_ctx.sessionsLoading && __VLS_ctx.sessions.length === 0 && __VLS_ctx.selectedProjectName))
                        return;
                    if (!!(__VLS_ctx.sessions.length > 0 && __VLS_ctx.filteredSessions.length === 0))
                        return;
                    __VLS_ctx.pickSession(session);
                } },
            key: (session.sessionId),
            ...{ class: "card bg-base-100 shadow-sm border border-base-300 border-l-4" },
            ...{ class: ([
                    __VLS_ctx.colorBorderClass(session),
                    __VLS_ctx.isHardBlocked(session) ? 'opacity-60' : '',
                    session.status !== __VLS_ctx.SessionStatus.ARCHIVED && !__VLS_ctx.isHardBlocked(session)
                        ? 'hover:border-primary cursor-pointer'
                        : '',
                    session.status === __VLS_ctx.SessionStatus.ARCHIVED ? 'bg-base-200/40' : '',
                ]) },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "card-body p-4 flex flex-row items-start gap-3" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-2xl leading-none shrink-0 mt-0.5 w-8 text-center" },
        });
        if (session.icon) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            (session.icon);
        }
        else {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "opacity-30" },
            });
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex-1 min-w-0" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center gap-2 min-w-0" },
        });
        if (session.pinned) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "shrink-0 text-xs" },
                title: (__VLS_ctx.$t('chat.sessionHeader.pinTooltip')),
            });
        }
        if (session.allowMultipleClients) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "shrink-0 text-xs" },
                title: (__VLS_ctx.$t('chat.picker.sharedTooltip')),
            });
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "font-medium truncate" },
            title: (__VLS_ctx.sessionTitle(session)),
        });
        (__VLS_ctx.sessionTitle(session));
        if (session.titleAutoGenerated && session.title) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-[10px] uppercase tracking-wide px-1 py-0.5 rounded bg-base-200 opacity-60 shrink-0" },
                title: (__VLS_ctx.$t('chat.sessionHeader.autoTitle')),
            });
        }
        if (session.status === __VLS_ctx.SessionStatus.ARCHIVED) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-xs uppercase tracking-wide px-1.5 py-0.5 rounded bg-warning/15 text-warning border border-warning/30 shrink-0" },
            });
            (__VLS_ctx.$t('chat.sessionHeader.archived'));
        }
        if (session.lastMessagePreview) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs opacity-70 truncate mt-0.5" },
                title: (session.lastMessagePreview),
            });
            (session.lastMessagePreview);
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60 truncate mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-0.5" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (session.status);
        (__VLS_ctx.formatRelativeTime(session.lastActivityAt));
        if (session.chatRecipe) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "font-mono opacity-70" },
                title: (__VLS_ctx.$t('chat.picker.recipeBadgeTooltip')),
            });
            (session.chatRecipe);
        }
        if (session.tags && session.tags.length > 0) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex flex-wrap gap-1 mt-2" },
            });
            for (const [tag] of __VLS_getVForSourceType((session.tags))) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    key: (tag),
                    ...{ class: "text-[10px] px-1.5 py-0.5 rounded bg-base-200" },
                });
                (tag);
            }
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "shrink-0 flex flex-col items-end gap-1" },
        });
        if (__VLS_ctx.isHardBlocked(session)) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-xs text-error" },
            });
            (__VLS_ctx.$t('chat.picker.occupied'));
        }
        else if (session.bound && session.allowMultipleClients) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-xs text-success" },
                title: (__VLS_ctx.$t('chat.picker.sharedTooltip')),
            });
            (__VLS_ctx.$t('chat.picker.joinLive'));
        }
        if (session.status === __VLS_ctx.SessionStatus.ARCHIVED) {
            const __VLS_59 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_60 = __VLS_asFunctionalComponent(__VLS_59, new __VLS_59({
                ...{ 'onClick': {} },
                variant: "primary",
                size: "sm",
                disabled: (__VLS_ctx.reactivating === session.sessionId),
            }));
            const __VLS_61 = __VLS_60({
                ...{ 'onClick': {} },
                variant: "primary",
                size: "sm",
                disabled: (__VLS_ctx.reactivating === session.sessionId),
            }, ...__VLS_functionalComponentArgsRest(__VLS_60));
            let __VLS_63;
            let __VLS_64;
            let __VLS_65;
            const __VLS_66 = {
                onClick: (...[$event]) => {
                    if (!!(__VLS_ctx.sessionsLoading))
                        return;
                    if (!!(!__VLS_ctx.sessionsLoading && __VLS_ctx.sessions.length === 0 && __VLS_ctx.selectedProjectName))
                        return;
                    if (!!(__VLS_ctx.sessions.length > 0 && __VLS_ctx.filteredSessions.length === 0))
                        return;
                    if (!(session.status === __VLS_ctx.SessionStatus.ARCHIVED))
                        return;
                    __VLS_ctx.reactivateAndOpen(session);
                }
            };
            __VLS_62.slots.default;
            (__VLS_ctx.$t('chat.sessionHeader.reactivate'));
            var __VLS_62;
        }
    }
}
if (__VLS_ctx.searchOpen) {
    /** @type {[typeof SessionSearchModal, ]} */ ;
    // @ts-ignore
    const __VLS_67 = __VLS_asFunctionalComponent(SessionSearchModal, new SessionSearchModal({
        ...{ 'onClose': {} },
        ...{ 'onPick': {} },
    }));
    const __VLS_68 = __VLS_67({
        ...{ 'onClose': {} },
        ...{ 'onPick': {} },
    }, ...__VLS_functionalComponentArgsRest(__VLS_67));
    let __VLS_70;
    let __VLS_71;
    let __VLS_72;
    const __VLS_73 = {
        onClose: (...[$event]) => {
            if (!(__VLS_ctx.searchOpen))
                return;
            __VLS_ctx.searchOpen = false;
        }
    };
    const __VLS_74 = {
        onPick: (__VLS_ctx.onSearchPick)
    };
    var __VLS_69;
}
const __VLS_75 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_76 = __VLS_asFunctionalComponent(__VLS_75, new __VLS_75({
    modelValue: (__VLS_ctx.recipeModalOpen),
    title: (__VLS_ctx.$t('chat.picker.recipeModalTitle')),
}));
const __VLS_77 = __VLS_76({
    modelValue: (__VLS_ctx.recipeModalOpen),
    title: (__VLS_ctx.$t('chat.picker.recipeModalTitle')),
}, ...__VLS_functionalComponentArgsRest(__VLS_76));
__VLS_78.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "space-y-3" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
    ...{ class: "text-sm opacity-70" },
});
(__VLS_ctx.$t('chat.picker.recipeModalIntro'));
if (__VLS_ctx.recipesError) {
    const __VLS_79 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_80 = __VLS_asFunctionalComponent(__VLS_79, new __VLS_79({
        variant: "error",
    }));
    const __VLS_81 = __VLS_80({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_80));
    __VLS_82.slots.default;
    (__VLS_ctx.recipesError);
    var __VLS_82;
}
if (__VLS_ctx.bootstrapError) {
    const __VLS_83 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_84 = __VLS_asFunctionalComponent(__VLS_83, new __VLS_83({
        variant: "error",
    }));
    const __VLS_85 = __VLS_84({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_84));
    __VLS_86.slots.default;
    (__VLS_ctx.bootstrapError);
    var __VLS_86;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
    ...{ class: "flex flex-col gap-2 max-h-[60vh] overflow-y-auto" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({});
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (...[$event]) => {
            __VLS_ctx.bootstrapNew(null);
        } },
    type: "button",
    ...{ class: "w-full text-left rounded-lg border border-base-300 hover:border-primary p-3 transition-colors" },
    disabled: (__VLS_ctx.bootstrapping),
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "font-semibold" },
});
(__VLS_ctx.$t('chat.picker.recipeDefaultName'));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "text-xs opacity-70 mt-1" },
});
(__VLS_ctx.$t('chat.picker.recipeDefaultDescription'));
if (__VLS_ctx.recipesLoading) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
        ...{ class: "text-sm opacity-60 px-1" },
    });
    (__VLS_ctx.$t('chat.picker.sessionsLoading'));
}
for (const [recipe] of __VLS_getVForSourceType((__VLS_ctx.recipeOptions))) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
        key: (recipe.name),
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (...[$event]) => {
                __VLS_ctx.bootstrapNew(recipe.name);
            } },
        type: "button",
        ...{ class: "w-full text-left rounded-lg border border-base-300 hover:border-primary p-3 transition-colors" },
        disabled: (__VLS_ctx.bootstrapping),
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-baseline gap-2 min-w-0" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "font-semibold truncate" },
    });
    (recipe.title || recipe.name);
    if (recipe.title) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-xs opacity-50 font-mono truncate" },
        });
        (recipe.name);
    }
    if (recipe.description) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-70 mt-1 whitespace-pre-line line-clamp-3" },
        });
        (recipe.description);
    }
}
{
    const { actions: __VLS_thisSlot } = __VLS_78.slots;
    const __VLS_87 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_88 = __VLS_asFunctionalComponent(__VLS_87, new __VLS_87({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.bootstrapping),
    }));
    const __VLS_89 = __VLS_88({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.bootstrapping),
    }, ...__VLS_functionalComponentArgsRest(__VLS_88));
    let __VLS_91;
    let __VLS_92;
    let __VLS_93;
    const __VLS_94 = {
        onClick: (...[$event]) => {
            __VLS_ctx.recipeModalOpen = false;
        }
    };
    __VLS_90.slots.default;
    (__VLS_ctx.$t('common.cancel'));
    var __VLS_90;
}
var __VLS_78;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
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
/** @type {__VLS_StyleScopedClasses['relative']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-baseline']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['sm:inline']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['text-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['picker-tools-toggle']} */ ;
/** @type {__VLS_StyleScopedClasses['picker-tools']} */ ;
/** @type {__VLS_StyleScopedClasses['picker-tools--open']} */ ;
/** @type {__VLS_StyleScopedClasses['w-[150px]']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['px-6']} */ ;
/** @type {__VLS_StyleScopedClasses['py-4']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-3xl']} */ ;
/** @type {__VLS_StyleScopedClasses['mx-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['card']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['shadow-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['border-l-4']} */ ;
/** @type {__VLS_StyleScopedClasses['card-body']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-row']} */ ;
/** @type {__VLS_StyleScopedClasses['items-start']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-2xl']} */ ;
/** @type {__VLS_StyleScopedClasses['leading-none']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['w-8']} */ ;
/** @type {__VLS_StyleScopedClasses['text-center']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-30']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-[10px]']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-warning/15']} */ ;
/** @type {__VLS_StyleScopedClasses['text-warning']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-warning/30']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-2']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-y-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-[10px]']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['items-end']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-error']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-success']} */ ;
/** @type {__VLS_StyleScopedClasses['space-y-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['max-h-[60vh]']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['text-left']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:border-primary']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['text-left']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:border-primary']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-baseline']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['whitespace-pre-line']} */ ;
/** @type {__VLS_StyleScopedClasses['line-clamp-3']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            SessionStatus: SessionStatus,
            ProjectListSidebar: ProjectListSidebar,
            VAlert: VAlert,
            VButton: VButton,
            VCheckbox: VCheckbox,
            VEmptyState: VEmptyState,
            VInput: VInput,
            VModal: VModal,
            SessionSearchModal: SessionSearchModal,
            emit: emit,
            selectedProjectName: selectedProjectName,
            teleportReady: teleportReady,
            groups: groups,
            projects: projects,
            projectsLoading: projectsLoading,
            projectsError: projectsError,
            sessions: sessions,
            sessionsLoading: sessionsLoading,
            sessionsError: sessionsError,
            bootstrapping: bootstrapping,
            bootstrapError: bootstrapError,
            showArchived: showArchived,
            reactivating: reactivating,
            searchOpen: searchOpen,
            recipeModalOpen: recipeModalOpen,
            recipeOptions: recipeOptions,
            recipesLoading: recipesLoading,
            recipesError: recipesError,
            sessionFilter: sessionFilter,
            pickerToolsOpen: pickerToolsOpen,
            filteredSessions: filteredSessions,
            isHardBlocked: isHardBlocked,
            pickSession: pickSession,
            reactivateAndOpen: reactivateAndOpen,
            openRecipeModal: openRecipeModal,
            bootstrapNew: bootstrapNew,
            formatRelativeTime: formatRelativeTime,
            projectTitle: projectTitle,
            onProjectPick: onProjectPick,
            onProjectListDataChanged: onProjectListDataChanged,
            sessionTitle: sessionTitle,
            colorBorderClass: colorBorderClass,
            onSearchPick: onSearchPick,
        };
    },
    __typeEmits: {},
    __typeProps: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeEmits: {},
    __typeProps: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=PickerView.vue.js.map