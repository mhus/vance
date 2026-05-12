import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { WebSocketRequestError, listSessions, reactivateSession, } from '@vance/shared';
import { SessionColor, SessionStatus, } from '@vance/generated';
import { useTenantProjects } from '@composables/useTenantProjects';
import { VAlert, VButton, VCheckbox, VEmptyState, } from '@components/index';
import SessionSearchModal from './SessionSearchModal.vue';
const { t } = useI18n();
const props = defineProps();
const emit = defineEmits();
const { groups, projects, loading: projectsLoading, error: projectsError, reload: loadProjects } = useTenantProjects();
const sessions = ref([]);
const selectedProjectName = ref(null);
const sessionsLoading = ref(false);
const sessionsError = ref(null);
const bootstrapping = ref(false);
const bootstrapError = ref(null);
const showArchived = ref(false);
const reactivating = ref(null);
const searchOpen = ref(false);
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
    await loadProjects();
    if (!selectedProjectName.value && projects.value.length > 0) {
        selectedProjectName.value = projects.value[0].name;
    }
});
watch(selectedProjectName, async (newName) => {
    if (newName)
        await loadSessions(newName);
});
watch(showArchived, async () => {
    if (selectedProjectName.value)
        await loadSessions(selectedProjectName.value);
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex h-full min-h-0" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.aside, __VLS_intrinsicElements.aside)({
    ...{ class: "w-72 shrink-0 border-r border-base-300 bg-base-100 overflow-y-auto p-4 flex flex-col gap-4" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex items-center justify-between" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "text-xs uppercase tracking-wide opacity-60 font-semibold" },
});
(__VLS_ctx.$t('chat.picker.projectsTitle'));
const __VLS_0 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
    title: (__VLS_ctx.$t('chat.picker.searchTooltip')),
}));
const __VLS_2 = __VLS_1({
    ...{ 'onClick': {} },
    variant: "ghost",
    size: "sm",
    title: (__VLS_ctx.$t('chat.picker.searchTooltip')),
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
let __VLS_4;
let __VLS_5;
let __VLS_6;
const __VLS_7 = {
    onClick: (...[$event]) => {
        __VLS_ctx.searchOpen = true;
    }
};
__VLS_3.slots.default;
var __VLS_3;
if (__VLS_ctx.projectsLoading) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-60" },
    });
    (__VLS_ctx.$t('chat.picker.loading'));
}
else if (__VLS_ctx.projectsError) {
    const __VLS_8 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
        variant: "error",
    }));
    const __VLS_10 = __VLS_9({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_9));
    __VLS_11.slots.default;
    (__VLS_ctx.projectsError);
    var __VLS_11;
}
else {
    for (const [block] of __VLS_getVForSourceType((__VLS_ctx.projectsByGroup))) {
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
        const __VLS_12 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
            headline: (__VLS_ctx.$t('chat.picker.noProjects')),
            body: (__VLS_ctx.$t('chat.picker.noProjectsBody')),
        }));
        const __VLS_14 = __VLS_13({
            headline: (__VLS_ctx.$t('chat.picker.noProjects')),
            body: (__VLS_ctx.$t('chat.picker.noProjectsBody')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_13));
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({
    ...{ class: "flex-1 min-w-0 overflow-y-auto p-6" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "max-w-3xl mx-auto flex flex-col gap-4" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex items-baseline justify-between" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
__VLS_asFunctionalElement(__VLS_intrinsicElements.h2, __VLS_intrinsicElements.h2)({
    ...{ class: "text-lg font-semibold" },
});
(__VLS_ctx.selectedProjectName ? __VLS_ctx.projectTitle(__VLS_ctx.selectedProjectName) : __VLS_ctx.$t('chat.picker.pickAProject'));
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
    ...{ class: "text-sm opacity-60" },
});
if (__VLS_ctx.username) {
    (__VLS_ctx.$t('chat.picker.signedInAs', { username: __VLS_ctx.username }));
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex items-center gap-3" },
});
const __VLS_16 = {}.VCheckbox;
/** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
// @ts-ignore
const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
    modelValue: (__VLS_ctx.showArchived),
    label: (__VLS_ctx.$t('chat.picker.showArchived')),
}));
const __VLS_18 = __VLS_17({
    modelValue: (__VLS_ctx.showArchived),
    label: (__VLS_ctx.$t('chat.picker.showArchived')),
}, ...__VLS_functionalComponentArgsRest(__VLS_17));
const __VLS_20 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_21 = __VLS_asFunctionalComponent(__VLS_20, new __VLS_20({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.selectedProjectName || __VLS_ctx.bootstrapping),
    loading: (__VLS_ctx.bootstrapping),
}));
const __VLS_22 = __VLS_21({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.selectedProjectName || __VLS_ctx.bootstrapping),
    loading: (__VLS_ctx.bootstrapping),
}, ...__VLS_functionalComponentArgsRest(__VLS_21));
let __VLS_24;
let __VLS_25;
let __VLS_26;
const __VLS_27 = {
    onClick: (__VLS_ctx.bootstrapNew)
};
__VLS_23.slots.default;
(__VLS_ctx.$t('chat.picker.newSession'));
var __VLS_23;
if (__VLS_ctx.bootstrapError) {
    const __VLS_28 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_29 = __VLS_asFunctionalComponent(__VLS_28, new __VLS_28({
        variant: "error",
    }));
    const __VLS_30 = __VLS_29({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_29));
    __VLS_31.slots.default;
    (__VLS_ctx.bootstrapError);
    var __VLS_31;
}
if (__VLS_ctx.sessionsError) {
    const __VLS_32 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_33 = __VLS_asFunctionalComponent(__VLS_32, new __VLS_32({
        variant: "error",
    }));
    const __VLS_34 = __VLS_33({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_33));
    __VLS_35.slots.default;
    (__VLS_ctx.sessionsError);
    var __VLS_35;
}
if (__VLS_ctx.sessionsLoading) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-60" },
    });
    (__VLS_ctx.$t('chat.picker.sessionsLoading'));
}
else if (!__VLS_ctx.sessionsLoading && __VLS_ctx.sessions.length === 0 && __VLS_ctx.selectedProjectName) {
    const __VLS_36 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_37 = __VLS_asFunctionalComponent(__VLS_36, new __VLS_36({
        headline: (__VLS_ctx.$t('chat.picker.noSessions')),
        body: (__VLS_ctx.$t('chat.picker.noSessionsBody')),
    }));
    const __VLS_38 = __VLS_37({
        headline: (__VLS_ctx.$t('chat.picker.noSessions')),
        body: (__VLS_ctx.$t('chat.picker.noSessionsBody')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_37));
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
        ...{ class: "flex flex-col gap-2" },
    });
    for (const [session] of __VLS_getVForSourceType((__VLS_ctx.sessions))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
            ...{ onClick: (...[$event]) => {
                    if (!!(__VLS_ctx.sessionsLoading))
                        return;
                    if (!!(!__VLS_ctx.sessionsLoading && __VLS_ctx.sessions.length === 0 && __VLS_ctx.selectedProjectName))
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
            const __VLS_40 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_41 = __VLS_asFunctionalComponent(__VLS_40, new __VLS_40({
                ...{ 'onClick': {} },
                variant: "primary",
                size: "sm",
                disabled: (__VLS_ctx.reactivating === session.sessionId),
            }));
            const __VLS_42 = __VLS_41({
                ...{ 'onClick': {} },
                variant: "primary",
                size: "sm",
                disabled: (__VLS_ctx.reactivating === session.sessionId),
            }, ...__VLS_functionalComponentArgsRest(__VLS_41));
            let __VLS_44;
            let __VLS_45;
            let __VLS_46;
            const __VLS_47 = {
                onClick: (...[$event]) => {
                    if (!!(__VLS_ctx.sessionsLoading))
                        return;
                    if (!!(!__VLS_ctx.sessionsLoading && __VLS_ctx.sessions.length === 0 && __VLS_ctx.selectedProjectName))
                        return;
                    if (!(session.status === __VLS_ctx.SessionStatus.ARCHIVED))
                        return;
                    __VLS_ctx.reactivateAndOpen(session);
                }
            };
            __VLS_43.slots.default;
            (__VLS_ctx.$t('chat.sessionHeader.reactivate'));
            var __VLS_43;
        }
    }
}
if (__VLS_ctx.searchOpen) {
    /** @type {[typeof SessionSearchModal, ]} */ ;
    // @ts-ignore
    const __VLS_48 = __VLS_asFunctionalComponent(SessionSearchModal, new SessionSearchModal({
        ...{ 'onClose': {} },
        ...{ 'onPick': {} },
    }));
    const __VLS_49 = __VLS_48({
        ...{ 'onClose': {} },
        ...{ 'onPick': {} },
    }, ...__VLS_functionalComponentArgsRest(__VLS_48));
    let __VLS_51;
    let __VLS_52;
    let __VLS_53;
    const __VLS_54 = {
        onClose: (...[$event]) => {
            if (!(__VLS_ctx.searchOpen))
                return;
            __VLS_ctx.searchOpen = false;
        }
    };
    const __VLS_55 = {
        onPick: (__VLS_ctx.onSearchPick)
    };
    var __VLS_50;
}
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['w-72']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['border-r']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
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
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['p-6']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-3xl']} */ ;
/** @type {__VLS_StyleScopedClasses['mx-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-baseline']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['text-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
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
            SessionSearchModal: SessionSearchModal,
            projects: projects,
            projectsLoading: projectsLoading,
            projectsError: projectsError,
            sessions: sessions,
            selectedProjectName: selectedProjectName,
            sessionsLoading: sessionsLoading,
            sessionsError: sessionsError,
            bootstrapping: bootstrapping,
            bootstrapError: bootstrapError,
            showArchived: showArchived,
            reactivating: reactivating,
            searchOpen: searchOpen,
            projectsByGroup: projectsByGroup,
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