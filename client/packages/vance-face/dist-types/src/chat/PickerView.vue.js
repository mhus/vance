import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { WebSocketRequestError, listSessions, reactivateSession, } from '@vance/shared';
import { SessionColor, SessionStatus, } from '@vance/generated';
import { useTenantProjects } from '@composables/useTenantProjects';
import { VAlert, VButton, VCheckbox, VEmptyState, VInput, } from '@components/index';
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
 * Free-text filter for the project sidebar — matches project title
 * and technical name (case-insensitive substring). Independent from
 * {@link searchOpen} which drives the session-content search modal.
 */
const projectFilter = ref('');
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
const projectsByGroup = computed(() => {
    const byKey = new Map();
    for (const p of projects.value) {
        const key = p.projectGroupId ?? null;
        const list = byKey.get(key) ?? [];
        list.push(p);
        byKey.set(key, list);
    }
    const groupByName = new Map(groups.value.map((g) => [g.name, g]));
    const result = [];
    for (const [groupName, list] of byKey.entries()) {
        const group = groupName ? groupByName.get(groupName) ?? null : null;
        result.push({ group, projects: list });
    }
    // Stable order: ungrouped first, then groups by name.
    result.sort((a, b) => {
        if (a.group === null && b.group !== null)
            return -1;
        if (a.group !== null && b.group === null)
            return 1;
        if (!a.group || !b.group)
            return 0;
        return a.group.name.localeCompare(b.group.name);
    });
    return result;
});
/**
 * Applies the free-text filter on top of {@link projectsByGroup}.
 * Empty filter passes everything through unchanged. Otherwise each
 * project must match the filter on its title (preferred) or its
 * technical name; groups with zero remaining projects drop out.
 */
const filteredProjectsByGroup = computed(() => {
    const needle = projectFilter.value.trim().toLowerCase();
    if (!needle)
        return projectsByGroup.value;
    const result = [];
    for (const block of projectsByGroup.value) {
        const matching = block.projects.filter((p) => {
            const title = (p.title ?? '').toLowerCase();
            const name = p.name.toLowerCase();
            return title.includes(needle) || name.includes(needle);
        });
        if (matching.length > 0) {
            result.push({ group: block.group, projects: matching });
        }
    }
    return result;
});
const filteredProjectsCount = computed(() => filteredProjectsByGroup.value.reduce((n, b) => n + b.projects.length, 0));
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
function selectProject(projectName) {
    selectedProjectName.value = projectName;
    // The {@code project-resolved} watcher below will fire as a side
    // effect; the explicit pick emit is what drives the URL push in the
    // parent (history entry per user-initiated selection).
    emit('project-pick', { name: projectName, title: projectTitle(projectName) });
}
function pickSession(session) {
    if (session.bound)
        return;
    if (session.status === SessionStatus.ARCHIVED)
        return;
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
async function bootstrapNew() {
    if (!selectedProjectName.value)
        return;
    bootstrapping.value = true;
    bootstrapError.value = null;
    try {
        const response = await props.socket.send('session-bootstrap', { projectId: selectedProjectName.value, processes: [] });
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
function groupLabel(block) {
    if (!block.group)
        return t('chat.picker.ungrouped');
    return block.group.title || block.group.name;
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
    [SessionColor.SLATE]: 'border-l-slate-500',
    [SessionColor.RED]: 'border-l-red-500',
    [SessionColor.ORANGE]: 'border-l-orange-500',
    [SessionColor.AMBER]: 'border-l-amber-500',
    [SessionColor.GREEN]: 'border-l-green-500',
    [SessionColor.TEAL]: 'border-l-teal-500',
    [SessionColor.CYAN]: 'border-l-cyan-500',
    [SessionColor.BLUE]: 'border-l-blue-500',
    [SessionColor.INDIGO]: 'border-l-indigo-500',
    [SessionColor.PURPLE]: 'border-l-purple-500',
    [SessionColor.PINK]: 'border-l-pink-500',
    [SessionColor.ROSE]: 'border-l-rose-500',
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
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "p-4 flex flex-col gap-4" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex items-center justify-between" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "text-xs uppercase tracking-wide opacity-60 font-semibold" },
});
(__VLS_ctx.$t('chat.picker.projectsTitle'));
const __VLS_4 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
    ...{ 'onPointerdown': {} },
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
    title: (__VLS_ctx.$t('chat.picker.searchTooltip')),
}));
const __VLS_6 = __VLS_5({
    ...{ 'onPointerdown': {} },
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
    title: (__VLS_ctx.$t('chat.picker.searchTooltip')),
}, ...__VLS_functionalComponentArgsRest(__VLS_5));
let __VLS_8;
let __VLS_9;
let __VLS_10;
const __VLS_11 = {
    onPointerdown: (...[$event]) => {
        __VLS_ctx.emit('focus-main');
    }
};
const __VLS_12 = {
    onClick: (...[$event]) => {
        __VLS_ctx.searchOpen = true;
    }
};
__VLS_7.slots.default;
var __VLS_7;
if (!__VLS_ctx.projectsLoading && !__VLS_ctx.projectsError && __VLS_ctx.projects.length > 0) {
    const __VLS_13 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_14 = __VLS_asFunctionalComponent(__VLS_13, new __VLS_13({
        modelValue: (__VLS_ctx.projectFilter),
        placeholder: (__VLS_ctx.$t('chat.picker.filterPlaceholder')),
    }));
    const __VLS_15 = __VLS_14({
        modelValue: (__VLS_ctx.projectFilter),
        placeholder: (__VLS_ctx.$t('chat.picker.filterPlaceholder')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_14));
}
if (__VLS_ctx.projectsLoading) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-60" },
    });
    (__VLS_ctx.$t('chat.picker.loading'));
}
else if (__VLS_ctx.projectsError) {
    const __VLS_17 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_18 = __VLS_asFunctionalComponent(__VLS_17, new __VLS_17({
        variant: "error",
    }));
    const __VLS_19 = __VLS_18({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_18));
    __VLS_20.slots.default;
    (__VLS_ctx.projectsError);
    var __VLS_20;
}
else {
    for (const [block] of __VLS_getVForSourceType((__VLS_ctx.filteredProjectsByGroup))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            key: (block.group?.name ?? '_ungrouped'),
            ...{ class: "flex flex-col gap-1" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-50 px-2" },
        });
        (__VLS_ctx.groupLabel(block));
        for (const [p] of __VLS_getVForSourceType((block.projects))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onPointerdown: (...[$event]) => {
                        if (!!(__VLS_ctx.projectsLoading))
                            return;
                        if (!!(__VLS_ctx.projectsError))
                            return;
                        __VLS_ctx.emit('focus-main');
                    } },
                ...{ onClick: (...[$event]) => {
                        if (!!(__VLS_ctx.projectsLoading))
                            return;
                        if (!!(__VLS_ctx.projectsError))
                            return;
                        __VLS_ctx.selectProject(p.name);
                    } },
                key: (p.name),
                type: "button",
                ...{ class: "text-left px-2 py-1.5 rounded text-sm transition-colors" },
                ...{ class: (__VLS_ctx.selectedProjectName === p.name
                        ? 'bg-primary/10 text-primary font-medium'
                        : 'hover:bg-base-200') },
            });
            (p.title || p.name);
        }
    }
    if (__VLS_ctx.projects.length === 0) {
        const __VLS_21 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_22 = __VLS_asFunctionalComponent(__VLS_21, new __VLS_21({
            headline: (__VLS_ctx.$t('chat.picker.noProjects')),
            body: (__VLS_ctx.$t('chat.picker.noProjectsBody')),
        }));
        const __VLS_23 = __VLS_22({
            headline: (__VLS_ctx.$t('chat.picker.noProjects')),
            body: (__VLS_ctx.$t('chat.picker.noProjectsBody')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_22));
    }
    else if (__VLS_ctx.projectFilter && __VLS_ctx.filteredProjectsCount === 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60 px-2" },
        });
        (__VLS_ctx.$t('chat.picker.filterNoMatch', { filter: __VLS_ctx.projectFilter }));
    }
}
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
const __VLS_25 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_26 = __VLS_asFunctionalComponent(__VLS_25, new __VLS_25({
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
    ...{ class: "picker-tools-toggle" },
    title: (__VLS_ctx.pickerToolsOpen ? 'Hide tools' : 'Show tools'),
}));
const __VLS_27 = __VLS_26({
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
    ...{ class: "picker-tools-toggle" },
    title: (__VLS_ctx.pickerToolsOpen ? 'Hide tools' : 'Show tools'),
}, ...__VLS_functionalComponentArgsRest(__VLS_26));
let __VLS_29;
let __VLS_30;
let __VLS_31;
const __VLS_32 = {
    onClick: (...[$event]) => {
        __VLS_ctx.pickerToolsOpen = !__VLS_ctx.pickerToolsOpen;
    }
};
__VLS_28.slots.default;
var __VLS_28;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "picker-tools" },
    ...{ class: ({ 'picker-tools--open': __VLS_ctx.pickerToolsOpen }) },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "w-[150px]" },
});
const __VLS_33 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_34 = __VLS_asFunctionalComponent(__VLS_33, new __VLS_33({
    modelValue: (__VLS_ctx.sessionFilter),
    placeholder: (__VLS_ctx.$t('chat.picker.sessionFilterPlaceholder')),
}));
const __VLS_35 = __VLS_34({
    modelValue: (__VLS_ctx.sessionFilter),
    placeholder: (__VLS_ctx.$t('chat.picker.sessionFilterPlaceholder')),
}, ...__VLS_functionalComponentArgsRest(__VLS_34));
const __VLS_37 = {}.VCheckbox;
/** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
// @ts-ignore
const __VLS_38 = __VLS_asFunctionalComponent(__VLS_37, new __VLS_37({
    modelValue: (__VLS_ctx.showArchived),
    label: (__VLS_ctx.$t('chat.picker.showArchived')),
}));
const __VLS_39 = __VLS_38({
    modelValue: (__VLS_ctx.showArchived),
    label: (__VLS_ctx.$t('chat.picker.showArchived')),
}, ...__VLS_functionalComponentArgsRest(__VLS_38));
const __VLS_41 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_42 = __VLS_asFunctionalComponent(__VLS_41, new __VLS_41({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.selectedProjectName || __VLS_ctx.bootstrapping),
    loading: (__VLS_ctx.bootstrapping),
    title: (__VLS_ctx.$t('chat.picker.newSession')),
}));
const __VLS_43 = __VLS_42({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.selectedProjectName || __VLS_ctx.bootstrapping),
    loading: (__VLS_ctx.bootstrapping),
    title: (__VLS_ctx.$t('chat.picker.newSession')),
}, ...__VLS_functionalComponentArgsRest(__VLS_42));
let __VLS_45;
let __VLS_46;
let __VLS_47;
const __VLS_48 = {
    onClick: (__VLS_ctx.bootstrapNew)
};
__VLS_44.slots.default;
var __VLS_44;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex-1 min-h-0 overflow-y-auto px-6 py-4" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "max-w-3xl mx-auto flex flex-col gap-4" },
});
if (__VLS_ctx.bootstrapError) {
    const __VLS_49 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_50 = __VLS_asFunctionalComponent(__VLS_49, new __VLS_49({
        variant: "error",
    }));
    const __VLS_51 = __VLS_50({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_50));
    __VLS_52.slots.default;
    (__VLS_ctx.bootstrapError);
    var __VLS_52;
}
if (__VLS_ctx.sessionsError) {
    const __VLS_53 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_54 = __VLS_asFunctionalComponent(__VLS_53, new __VLS_53({
        variant: "error",
    }));
    const __VLS_55 = __VLS_54({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_54));
    __VLS_56.slots.default;
    (__VLS_ctx.sessionsError);
    var __VLS_56;
}
if (__VLS_ctx.sessionsLoading) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-60" },
    });
    (__VLS_ctx.$t('chat.picker.sessionsLoading'));
}
else if (!__VLS_ctx.sessionsLoading && __VLS_ctx.sessions.length === 0 && __VLS_ctx.selectedProjectName) {
    const __VLS_57 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_58 = __VLS_asFunctionalComponent(__VLS_57, new __VLS_57({
        headline: (__VLS_ctx.$t('chat.picker.noSessions')),
        body: (__VLS_ctx.$t('chat.picker.noSessionsBody')),
    }));
    const __VLS_59 = __VLS_58({
        headline: (__VLS_ctx.$t('chat.picker.noSessions')),
        body: (__VLS_ctx.$t('chat.picker.noSessionsBody')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_58));
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
                    session.bound
                        ? 'opacity-60'
                        : '',
                    session.status !== __VLS_ctx.SessionStatus.ARCHIVED && !session.bound
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
            ...{ class: "text-xs opacity-60 truncate mt-0.5" },
        });
        (session.status);
        (__VLS_ctx.formatRelativeTime(session.lastActivityAt));
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
        if (session.bound) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-xs text-error" },
            });
            (__VLS_ctx.$t('chat.picker.occupied'));
        }
        if (session.status === __VLS_ctx.SessionStatus.ARCHIVED) {
            const __VLS_61 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_62 = __VLS_asFunctionalComponent(__VLS_61, new __VLS_61({
                ...{ 'onClick': {} },
                variant: "primary",
                size: "sm",
                disabled: (__VLS_ctx.reactivating === session.sessionId),
            }));
            const __VLS_63 = __VLS_62({
                ...{ 'onClick': {} },
                variant: "primary",
                size: "sm",
                disabled: (__VLS_ctx.reactivating === session.sessionId),
            }, ...__VLS_functionalComponentArgsRest(__VLS_62));
            let __VLS_65;
            let __VLS_66;
            let __VLS_67;
            const __VLS_68 = {
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
            __VLS_64.slots.default;
            (__VLS_ctx.$t('chat.sessionHeader.reactivate'));
            var __VLS_64;
        }
    }
}
if (__VLS_ctx.searchOpen) {
    /** @type {[typeof SessionSearchModal, ]} */ ;
    // @ts-ignore
    const __VLS_69 = __VLS_asFunctionalComponent(SessionSearchModal, new SessionSearchModal({
        ...{ 'onClose': {} },
        ...{ 'onPick': {} },
    }));
    const __VLS_70 = __VLS_69({
        ...{ 'onClose': {} },
        ...{ 'onPick': {} },
    }, ...__VLS_functionalComponentArgsRest(__VLS_69));
    let __VLS_72;
    let __VLS_73;
    let __VLS_74;
    const __VLS_75 = {
        onClose: (...[$event]) => {
            if (!(__VLS_ctx.searchOpen))
                return;
            __VLS_ctx.searchOpen = false;
        }
    };
    const __VLS_76 = {
        onPick: (__VLS_ctx.onSearchPick)
    };
    var __VLS_71;
}
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-left']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['transition-colors']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
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
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            SessionStatus: SessionStatus,
            VAlert: VAlert,
            VButton: VButton,
            VCheckbox: VCheckbox,
            VEmptyState: VEmptyState,
            VInput: VInput,
            SessionSearchModal: SessionSearchModal,
            emit: emit,
            selectedProjectName: selectedProjectName,
            teleportReady: teleportReady,
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
            projectFilter: projectFilter,
            sessionFilter: sessionFilter,
            pickerToolsOpen: pickerToolsOpen,
            filteredProjectsByGroup: filteredProjectsByGroup,
            filteredProjectsCount: filteredProjectsCount,
            filteredSessions: filteredSessions,
            selectProject: selectProject,
            pickSession: pickSession,
            reactivateAndOpen: reactivateAndOpen,
            bootstrapNew: bootstrapNew,
            formatRelativeTime: formatRelativeTime,
            projectTitle: projectTitle,
            groupLabel: groupLabel,
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