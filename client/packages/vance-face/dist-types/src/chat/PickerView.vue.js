import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { WebSocketRequestError, } from '@vance/shared';
import { useTenantProjects } from '@composables/useTenantProjects';
import { VAlert, VButton, VEmptyState } from '@components/index';
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
        const response = await props.socket.send('session-list', { projectId: projectName });
        const sorted = (response.sessions ?? []).slice().sort((a, b) => b.lastActivityAt - a.lastActivityAt);
        sessions.value = sorted;
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
    emit('session-picked', session.sessionId);
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
function formatRelativeTime(epochMillis) {
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
    ...{ class: "text-xs uppercase tracking-wide opacity-60 font-semibold" },
});
(__VLS_ctx.$t('chat.picker.projectsTitle'));
if (__VLS_ctx.projectsLoading) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-60" },
    });
    (__VLS_ctx.$t('chat.picker.loading'));
}
else if (__VLS_ctx.projectsError) {
    const __VLS_0 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
        variant: "error",
    }));
    const __VLS_2 = __VLS_1({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_1));
    __VLS_3.slots.default;
    (__VLS_ctx.projectsError);
    var __VLS_3;
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
        const __VLS_4 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
            headline: (__VLS_ctx.$t('chat.picker.noProjects')),
            body: (__VLS_ctx.$t('chat.picker.noProjectsBody')),
        }));
        const __VLS_6 = __VLS_5({
            headline: (__VLS_ctx.$t('chat.picker.noProjects')),
            body: (__VLS_ctx.$t('chat.picker.noProjectsBody')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_5));
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
const __VLS_8 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.selectedProjectName || __VLS_ctx.bootstrapping),
    loading: (__VLS_ctx.bootstrapping),
}));
const __VLS_10 = __VLS_9({
    ...{ 'onClick': {} },
    variant: "primary",
    disabled: (!__VLS_ctx.selectedProjectName || __VLS_ctx.bootstrapping),
    loading: (__VLS_ctx.bootstrapping),
}, ...__VLS_functionalComponentArgsRest(__VLS_9));
let __VLS_12;
let __VLS_13;
let __VLS_14;
const __VLS_15 = {
    onClick: (__VLS_ctx.bootstrapNew)
};
__VLS_11.slots.default;
(__VLS_ctx.$t('chat.picker.newSession'));
var __VLS_11;
if (__VLS_ctx.bootstrapError) {
    const __VLS_16 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
        variant: "error",
    }));
    const __VLS_18 = __VLS_17({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_17));
    __VLS_19.slots.default;
    (__VLS_ctx.bootstrapError);
    var __VLS_19;
}
if (__VLS_ctx.sessionsError) {
    const __VLS_20 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_21 = __VLS_asFunctionalComponent(__VLS_20, new __VLS_20({
        variant: "error",
    }));
    const __VLS_22 = __VLS_21({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_21));
    __VLS_23.slots.default;
    (__VLS_ctx.sessionsError);
    var __VLS_23;
}
if (__VLS_ctx.sessionsLoading) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-60" },
    });
    (__VLS_ctx.$t('chat.picker.sessionsLoading'));
}
else if (!__VLS_ctx.sessionsLoading && __VLS_ctx.sessions.length === 0 && __VLS_ctx.selectedProjectName) {
    const __VLS_24 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_25 = __VLS_asFunctionalComponent(__VLS_24, new __VLS_24({
        headline: (__VLS_ctx.$t('chat.picker.noSessions')),
        body: (__VLS_ctx.$t('chat.picker.noSessionsBody')),
    }));
    const __VLS_26 = __VLS_25({
        headline: (__VLS_ctx.$t('chat.picker.noSessions')),
        body: (__VLS_ctx.$t('chat.picker.noSessionsBody')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_25));
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
            ...{ class: ([
                    'card bg-base-100 shadow-sm border border-base-300',
                    session.bound
                        ? 'opacity-60 cursor-not-allowed'
                        : 'cursor-pointer hover:border-primary',
                ]) },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "card-body p-4 flex flex-row items-center gap-3" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
            ...{ class: "inline-block w-2.5 h-2.5 rounded-full shrink-0" },
            ...{ class: (session.bound ? 'bg-error' : 'bg-base-content/40') },
            title: (session.bound ? __VLS_ctx.$t('chat.picker.occupiedTooltip') : __VLS_ctx.$t('chat.picker.available')),
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex-1 min-w-0" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "font-medium truncate" },
        });
        (session.displayName || session.sessionId);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60 truncate" },
        });
        (session.status);
        (__VLS_ctx.formatRelativeTime(session.lastActivityAt));
        if (session.bound) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-xs text-error" },
            });
            (__VLS_ctx.$t('chat.picker.occupied'));
        }
    }
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
/** @type {__VLS_StyleScopedClasses['card-body']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-row']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['inline-block']} */ ;
/** @type {__VLS_StyleScopedClasses['w-2.5']} */ ;
/** @type {__VLS_StyleScopedClasses['h-2.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-full']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-error']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            VButton: VButton,
            VEmptyState: VEmptyState,
            projects: projects,
            projectsLoading: projectsLoading,
            projectsError: projectsError,
            sessions: sessions,
            selectedProjectName: selectedProjectName,
            sessionsLoading: sessionsLoading,
            sessionsError: sessionsError,
            bootstrapping: bootstrapping,
            bootstrapError: bootstrapError,
            projectsByGroup: projectsByGroup,
            selectProject: selectProject,
            pickSession: pickSession,
            bootstrapNew: bootstrapNew,
            formatRelativeTime: formatRelativeTime,
            projectTitle: projectTitle,
            groupLabel: groupLabel,
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