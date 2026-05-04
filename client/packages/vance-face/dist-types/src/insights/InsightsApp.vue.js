import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { EditorShell, MarkdownView, VAlert, VCard, VEmptyState, VInput, VSelect, } from '@/components';
import { useTenantProjects } from '@/composables/useTenantProjects';
import { useInsightsSessions, useSessionProcesses, useProcessDetail, useProcessChat, useProcessMemory, useMarvinTree, } from '@/composables/useInsights';
import { useHelp } from '@/composables/useHelp';
import MarvinTreeItem from './MarvinTreeItem.vue';
import SessionTimelineTab from './SessionTimelineTab.vue';
import LlmTraceTab from './LlmTraceTab.vue';
import { ChatRole, } from '@vance/generated';
const { t } = useI18n();
const tenantProjects = useTenantProjects();
const sessionsState = useInsightsSessions();
const processesState = useSessionProcesses();
const processDetailState = useProcessDetail();
const chatState = useProcessChat();
const memoryState = useProcessMemory();
const treeState = useMarvinTree();
const help = useHelp();
// ─── Filter state ───────────────────────────────────────────────────────
const filterProjectId = ref(null);
const filterUserId = ref('');
const filterStatus = ref(null);
const projectFilterOptions = computed(() => [
    { value: '', label: t('insights.filters.allProjects') },
    ...tenantProjects.projects.value.map(p => ({
        value: p.name,
        label: p.title || p.name,
    })),
]);
// OPEN / CLOSED labels stay as the literal server enum values — they
// are technical identifiers, recognisable across UI languages.
const statusOptions = computed(() => [
    { value: '', label: t('insights.filters.all') },
    { value: 'OPEN', label: 'OPEN' },
    { value: 'CLOSED', label: 'CLOSED' },
]);
const selection = ref(null);
/** Sessions whose processes-list is open in the sidebar. Loaded lazily. */
const expanded = ref(new Set());
/** Per-sessionId cache of processes — populated as sessions expand. */
const processesBySession = ref({});
const activeTab = ref('overview');
// ─── Lifecycle ──────────────────────────────────────────────────────────
onMounted(async () => {
    await Promise.all([
        tenantProjects.reload(),
        reloadSessions(),
        help.load('insights-overview.md'),
    ]);
});
watch([filterProjectId, filterUserId, filterStatus], () => {
    void reloadSessions();
});
watch(selection, async (sel) => {
    activeTab.value = 'overview';
    if (!sel)
        return;
    if (sel.kind === 'session') {
        // Make sure the processes for this session are loaded — they
        // populate the "Processes" tab.
        await ensureProcessesLoaded(sel.id);
    }
    else {
        await processDetailState.load(sel.id);
        chatState.clear();
        memoryState.clear();
        treeState.clear();
    }
});
watch(activeTab, (tab) => {
    if (!selection.value || selection.value.kind !== 'process')
        return;
    const id = selection.value.id;
    if (tab === 'chat' && chatState.messages.value.length === 0) {
        void chatState.load(id);
    }
    else if (tab === 'memory' && memoryState.entries.value.length === 0) {
        void memoryState.load(id);
    }
    else if (tab === 'tree' && treeState.nodes.value.length === 0) {
        void treeState.load(id);
    }
});
async function reloadSessions() {
    await sessionsState.reload({
        projectId: filterProjectId.value,
        userId: filterUserId.value.trim() || null,
        status: filterStatus.value,
    });
}
async function ensureProcessesLoaded(sessionId) {
    if (processesBySession.value[sessionId])
        return;
    await processesState.load(sessionId);
    // useSessionProcesses is single-shot per call; capture into per-session map.
    processesBySession.value = {
        ...processesBySession.value,
        [sessionId]: [...processesState.processes.value],
    };
}
// ─── Sidebar interactions ───────────────────────────────────────────────
async function toggleExpand(sessionId) {
    if (expanded.value.has(sessionId)) {
        expanded.value.delete(sessionId);
        expanded.value = new Set(expanded.value);
    }
    else {
        await ensureProcessesLoaded(sessionId);
        expanded.value.add(sessionId);
        expanded.value = new Set(expanded.value);
    }
}
function selectSession(s) {
    selection.value = { kind: 'session', id: s.sessionId };
}
function selectProcess(p) {
    selection.value = { kind: 'process', id: p.id };
}
function isSelectedSession(s) {
    const sel = selection.value;
    return sel?.kind === 'session' && sel.id === s.sessionId;
}
function isSelectedProcess(p) {
    const sel = selection.value;
    return sel?.kind === 'process' && sel.id === p.id;
}
// ─── Derived ────────────────────────────────────────────────────────────
const selectedSession = computed(() => {
    const sel = selection.value;
    if (sel?.kind !== 'session')
        return null;
    return sessionsState.sessions.value.find(s => s.sessionId === sel.id) ?? null;
});
const selectedProcess = computed(() => processDetailState.process.value);
const sessionProcessesForTab = computed(() => {
    const sel = selection.value;
    if (sel?.kind !== 'session')
        return [];
    return processesBySession.value[sel.id] ?? [];
});
const isMarvin = computed(() => (selectedProcess.value?.thinkEngine ?? '').toLowerCase() === 'marvin');
function sessionLabel(sessionId) {
    // Prefer the denormalised topic when we already have it cached;
    // otherwise fall back to the raw id.
    const s = sessionsState.sessions.value.find(x => x.sessionId === sessionId);
    const topic = s?.firstUserMessage;
    const label = topic && topic.length > 0
        ? topic.length > 60
            ? topic.slice(0, 59) + '…'
            : topic
        : sessionId;
    return t('insights.breadcrumbs.sessionPrefix', { label });
}
const breadcrumbs = computed(() => {
    const sel = selection.value;
    if (!sel)
        return [];
    if (sel.kind === 'session')
        return [sessionLabel(sel.id)];
    const p = selectedProcess.value;
    if (!p)
        return [t('insights.breadcrumbs.processFallback')];
    // When a process is selected, the session crumb navigates back to the
    // session view — the most common "go up one level" gesture.
    return [
        {
            text: sessionLabel(p.sessionId),
            onClick: () => { selection.value = { kind: 'session', id: p.sessionId }; },
        },
        t('insights.breadcrumbs.processPrefix', { name: p.name }),
    ];
});
const combinedError = computed(() => sessionsState.error.value
    || processesState.error.value
    || processDetailState.error.value
    || chatState.error.value
    || memoryState.error.value
    || treeState.error.value);
// ─── Marvin tree → nested rendering ─────────────────────────────────────
const marvinTree = computed(() => {
    const all = treeState.nodes.value;
    const byParent = {};
    for (const n of all) {
        const key = n.parentId ?? '';
        (byParent[key] ??= []).push(n);
    }
    for (const list of Object.values(byParent)) {
        list.sort((a, b) => a.position - b.position);
    }
    function build(parentId) {
        return (byParent[parentId ?? ''] ?? []).map(doc => ({
            doc,
            children: build(doc.id),
        }));
    }
    return build(null);
});
// ─── Helpers ────────────────────────────────────────────────────────────
function fmt(value) {
    if (value == null)
        return '—';
    if (value instanceof Date)
        return value.toISOString();
    return String(value);
}
function asJson(obj) {
    if (obj == null)
        return '';
    try {
        return JSON.stringify(obj, null, 2);
    }
    catch {
        return String(obj);
    }
}
function clickProcessByMongoId(id) {
    if (!id)
        return;
    selection.value = { kind: 'process', id };
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['session-label']} */ ;
/** @type {__VLS_StyleScopedClasses['process-row']} */ ;
/** @type {__VLS_StyleScopedClasses['tab']} */ ;
// CSS variable injection 
// CSS variable injection end 
const __VLS_0 = {}.EditorShell;
/** @type {[typeof __VLS_components.EditorShell, typeof __VLS_components.EditorShell, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    title: (__VLS_ctx.$t('insights.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    wideRightPanel: true,
}));
const __VLS_2 = __VLS_1({
    title: (__VLS_ctx.$t('insights.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    wideRightPanel: true,
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
var __VLS_4 = {};
__VLS_3.slots.default;
{
    const { sidebar: __VLS_thisSlot } = __VLS_3.slots;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-3 p-2" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-2" },
    });
    const __VLS_5 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_6 = __VLS_asFunctionalComponent(__VLS_5, new __VLS_5({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.filterProjectId ?? ''),
        options: (__VLS_ctx.projectFilterOptions),
        label: (__VLS_ctx.$t('insights.filters.project')),
    }));
    const __VLS_7 = __VLS_6({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.filterProjectId ?? ''),
        options: (__VLS_ctx.projectFilterOptions),
        label: (__VLS_ctx.$t('insights.filters.project')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_6));
    let __VLS_9;
    let __VLS_10;
    let __VLS_11;
    const __VLS_12 = {
        'onUpdate:modelValue': ((v) => __VLS_ctx.filterProjectId = (v ? String(v) : null))
    };
    var __VLS_8;
    const __VLS_13 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_14 = __VLS_asFunctionalComponent(__VLS_13, new __VLS_13({
        modelValue: (__VLS_ctx.filterUserId),
        label: (__VLS_ctx.$t('insights.filters.user')),
        placeholder: (__VLS_ctx.$t('insights.filters.userPlaceholder')),
    }));
    const __VLS_15 = __VLS_14({
        modelValue: (__VLS_ctx.filterUserId),
        label: (__VLS_ctx.$t('insights.filters.user')),
        placeholder: (__VLS_ctx.$t('insights.filters.userPlaceholder')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_14));
    const __VLS_17 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_18 = __VLS_asFunctionalComponent(__VLS_17, new __VLS_17({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.filterStatus ?? ''),
        options: (__VLS_ctx.statusOptions),
        label: (__VLS_ctx.$t('insights.filters.status')),
    }));
    const __VLS_19 = __VLS_18({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.filterStatus ?? ''),
        options: (__VLS_ctx.statusOptions),
        label: (__VLS_ctx.$t('insights.filters.status')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_18));
    let __VLS_21;
    let __VLS_22;
    let __VLS_23;
    const __VLS_24 = {
        'onUpdate:modelValue': ((v) => __VLS_ctx.filterStatus = (v ? String(v) : null))
    };
    var __VLS_20;
    if (__VLS_ctx.sessionsState.loading.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60 px-2" },
        });
        (__VLS_ctx.$t('insights.sidebar.loadingSessions'));
    }
    else if (__VLS_ctx.sessionsState.sessions.value.length === 0) {
        const __VLS_25 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_26 = __VLS_asFunctionalComponent(__VLS_25, new __VLS_25({
            headline: (__VLS_ctx.$t('insights.sidebar.noSessionsHeadline')),
            body: (__VLS_ctx.$t('insights.sidebar.noSessionsBody')),
        }));
        const __VLS_27 = __VLS_26({
            headline: (__VLS_ctx.$t('insights.sidebar.noSessionsHeadline')),
            body: (__VLS_ctx.$t('insights.sidebar.noSessionsBody')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_26));
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.nav, __VLS_intrinsicElements.nav)({
        ...{ class: "flex flex-col gap-1" },
    });
    for (const [s] of __VLS_getVForSourceType((__VLS_ctx.sessionsState.sessions.value))) {
        (s.id);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "session-row" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    __VLS_ctx.toggleExpand(s.sessionId);
                } },
            type: "button",
            ...{ class: "chev" },
        });
        (__VLS_ctx.expanded.has(s.sessionId) ? '▾' : '▸');
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    __VLS_ctx.selectSession(s);
                } },
            type: "button",
            ...{ class: "session-label" },
            ...{ class: ({ 'session-label--active': __VLS_ctx.isSelectedSession(s) }) },
            title: (s.firstUserMessage ?? s.sessionId),
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center justify-between gap-2" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "session-topic truncate" },
        });
        (s.firstUserMessage || s.sessionId);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-xs px-1.5 py-0.5 rounded shrink-0" },
            ...{ class: (s.status === 'OPEN' ? 'badge-open' : 'badge-closed') },
        });
        (s.status?.toLowerCase());
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60 truncate" },
        });
        (s.userId);
        (s.projectId);
        if (s.processCount != null) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            (s.processCount);
        }
        if (s.lastMessagePreview) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs opacity-60 truncate mt-0.5" },
                title: (s.lastMessagePreview),
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "opacity-70" },
            });
            (s.lastMessageRole?.toLowerCase());
            (s.lastMessagePreview);
        }
        if (__VLS_ctx.expanded.has(s.sessionId)) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "session-children" },
            });
            for (const [p] of __VLS_getVForSourceType(((__VLS_ctx.processesBySession[s.sessionId] ?? [])))) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                    ...{ onClick: (...[$event]) => {
                            if (!(__VLS_ctx.expanded.has(s.sessionId)))
                                return;
                            __VLS_ctx.selectProcess(p);
                        } },
                    key: (p.id),
                    type: "button",
                    ...{ class: "process-row" },
                    ...{ class: ({ 'process-row--active': __VLS_ctx.isSelectedProcess(p) }) },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "flex items-center justify-between gap-2" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "font-mono text-sm truncate" },
                });
                (p.name);
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "text-xs opacity-60" },
                });
                (p.thinkEngine);
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "text-xs opacity-60 truncate" },
                });
                (p.status?.toLowerCase());
                if (p.recipeName) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                    (p.recipeName);
                }
            }
        }
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "p-6 flex flex-col gap-3 max-w-5xl" },
});
if (__VLS_ctx.combinedError) {
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
    (__VLS_ctx.combinedError);
    var __VLS_32;
}
if (!__VLS_ctx.selection) {
    const __VLS_33 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_34 = __VLS_asFunctionalComponent(__VLS_33, new __VLS_33({
        headline: (__VLS_ctx.$t('insights.emptyMain.headline')),
        body: (__VLS_ctx.$t('insights.emptyMain.body')),
    }));
    const __VLS_35 = __VLS_34({
        headline: (__VLS_ctx.$t('insights.emptyMain.headline')),
        body: (__VLS_ctx.$t('insights.emptyMain.body')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_34));
}
else if (__VLS_ctx.selection.kind === 'session') {
    if (!__VLS_ctx.selectedSession) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "opacity-70" },
        });
        (__VLS_ctx.$t('insights.loading'));
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.header, __VLS_intrinsicElements.header)({
            ...{ class: "session-header" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-baseline gap-2 flex-wrap" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "font-mono text-sm opacity-70" },
        });
        (__VLS_ctx.selectedSession.sessionId);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-xs px-1.5 py-0.5 rounded" },
            ...{ class: (__VLS_ctx.selectedSession.status === 'OPEN' ? 'badge-open' : 'badge-closed') },
        });
        (__VLS_ctx.selectedSession.status?.toLowerCase());
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-xs opacity-60" },
        });
        (__VLS_ctx.selectedSession.userId);
        (__VLS_ctx.selectedSession.projectId);
        if (__VLS_ctx.selectedSession.firstUserMessage) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.h2, __VLS_intrinsicElements.h2)({
                ...{ class: "session-topic-title" },
            });
            (__VLS_ctx.selectedSession.firstUserMessage);
        }
        if (__VLS_ctx.selectedSession.lastMessagePreview) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs opacity-70 mt-1" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "opacity-70" },
            });
            (__VLS_ctx.$t('insights.session.lastLabel'));
            if (__VLS_ctx.selectedSession.lastMessageRole) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.selectedSession.lastMessageRole.toLowerCase());
            }
            if (__VLS_ctx.selectedSession.lastMessageAt) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.fmt(__VLS_ctx.selectedSession.lastMessageAt));
            }
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "block opacity-90 truncate" },
                title: (__VLS_ctx.selectedSession.lastMessagePreview),
            });
            (__VLS_ctx.selectedSession.lastMessagePreview);
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "tab-bar" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.selection))
                        return;
                    if (!(__VLS_ctx.selection.kind === 'session'))
                        return;
                    if (!!(!__VLS_ctx.selectedSession))
                        return;
                    __VLS_ctx.activeTab = 'overview';
                } },
            ...{ class: "tab" },
            ...{ class: ({ 'tab--active': __VLS_ctx.activeTab === 'overview' }) },
        });
        (__VLS_ctx.$t('insights.tabs.overview'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.selection))
                        return;
                    if (!(__VLS_ctx.selection.kind === 'session'))
                        return;
                    if (!!(!__VLS_ctx.selectedSession))
                        return;
                    __VLS_ctx.activeTab = 'processes';
                } },
            ...{ class: "tab" },
            ...{ class: ({ 'tab--active': __VLS_ctx.activeTab === 'processes' }) },
        });
        (__VLS_ctx.$t('insights.tabs.processes', { count: __VLS_ctx.sessionProcessesForTab.length }));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.selection))
                        return;
                    if (!(__VLS_ctx.selection.kind === 'session'))
                        return;
                    if (!!(!__VLS_ctx.selectedSession))
                        return;
                    __VLS_ctx.activeTab = 'timeline';
                } },
            ...{ class: "tab" },
            ...{ class: ({ 'tab--active': __VLS_ctx.activeTab === 'timeline' }) },
        });
        (__VLS_ctx.$t('insights.tabs.timeline'));
        if (__VLS_ctx.activeTab === 'overview') {
            const __VLS_37 = {}.VCard;
            /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
            // @ts-ignore
            const __VLS_38 = __VLS_asFunctionalComponent(__VLS_37, new __VLS_37({
                title: (__VLS_ctx.$t('insights.session.detailsTitle')),
            }));
            const __VLS_39 = __VLS_38({
                title: (__VLS_ctx.$t('insights.session.detailsTitle')),
            }, ...__VLS_functionalComponentArgsRest(__VLS_38));
            __VLS_40.slots.default;
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dl, __VLS_intrinsicElements.dl)({
                ...{ class: "grid grid-cols-2 gap-x-4 gap-y-1 text-sm" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            (__VLS_ctx.$t('insights.session.mongoId'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
                ...{ class: "font-mono" },
            });
            (__VLS_ctx.selectedSession.id);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            (__VLS_ctx.$t('insights.session.user'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.selectedSession.userId);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            (__VLS_ctx.$t('insights.session.project'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.selectedSession.projectId);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            (__VLS_ctx.$t('insights.session.status'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.selectedSession.status);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            (__VLS_ctx.$t('insights.session.client'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.selectedSession.profile);
            (__VLS_ctx.selectedSession.clientVersion);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            (__VLS_ctx.$t('insights.session.boundConn'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
                ...{ class: "font-mono text-xs" },
            });
            (__VLS_ctx.fmt(__VLS_ctx.selectedSession.boundConnectionId));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            (__VLS_ctx.$t('insights.session.chatProcess'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            if (__VLS_ctx.selectedSession.chatProcessId) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                    ...{ onClick: (...[$event]) => {
                            if (!!(!__VLS_ctx.selection))
                                return;
                            if (!(__VLS_ctx.selection.kind === 'session'))
                                return;
                            if (!!(!__VLS_ctx.selectedSession))
                                return;
                            if (!(__VLS_ctx.activeTab === 'overview'))
                                return;
                            if (!(__VLS_ctx.selectedSession.chatProcessId))
                                return;
                            __VLS_ctx.clickProcessByMongoId(__VLS_ctx.selectedSession.chatProcessId);
                        } },
                    ...{ class: "link" },
                });
                (__VLS_ctx.selectedSession.chatProcessId);
            }
            else {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            }
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            (__VLS_ctx.$t('insights.session.created'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.fmt(__VLS_ctx.selectedSession.createdAt));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            (__VLS_ctx.$t('insights.session.lastActivity'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.fmt(__VLS_ctx.selectedSession.lastActivityAt));
            var __VLS_40;
        }
        if (__VLS_ctx.activeTab === 'processes') {
            const __VLS_41 = {}.VCard;
            /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
            // @ts-ignore
            const __VLS_42 = __VLS_asFunctionalComponent(__VLS_41, new __VLS_41({
                title: (__VLS_ctx.$t('insights.session.processesTitle')),
            }));
            const __VLS_43 = __VLS_42({
                title: (__VLS_ctx.$t('insights.session.processesTitle')),
            }, ...__VLS_functionalComponentArgsRest(__VLS_42));
            __VLS_44.slots.default;
            if (__VLS_ctx.sessionProcessesForTab.length === 0) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "opacity-70" },
                });
                (__VLS_ctx.$t('insights.session.noProcesses'));
            }
            else {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
                    ...{ class: "flex flex-col divide-y divide-base-300" },
                });
                for (const [p] of __VLS_getVForSourceType((__VLS_ctx.sessionProcessesForTab))) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                        ...{ onClick: (...[$event]) => {
                                if (!!(!__VLS_ctx.selection))
                                    return;
                                if (!(__VLS_ctx.selection.kind === 'session'))
                                    return;
                                if (!!(!__VLS_ctx.selectedSession))
                                    return;
                                if (!(__VLS_ctx.activeTab === 'processes'))
                                    return;
                                if (!!(__VLS_ctx.sessionProcessesForTab.length === 0))
                                    return;
                                __VLS_ctx.selectProcess(p);
                            } },
                        key: (p.id),
                        ...{ class: "py-2 flex items-center justify-between gap-3 cursor-pointer hover:bg-base-200/40 px-2 rounded" },
                    });
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: "flex flex-col" },
                    });
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "font-mono text-sm" },
                    });
                    (p.name);
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "text-xs opacity-60" },
                    });
                    (p.thinkEngine);
                    if (p.recipeName) {
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                        (p.recipeName);
                    }
                    if (p.title) {
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                        (p.title);
                    }
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "text-xs opacity-70" },
                    });
                    (p.status);
                }
            }
            var __VLS_44;
        }
        if (__VLS_ctx.activeTab === 'timeline') {
            /** @type {[typeof SessionTimelineTab, ]} */ ;
            // @ts-ignore
            const __VLS_45 = __VLS_asFunctionalComponent(SessionTimelineTab, new SessionTimelineTab({
                ...{ 'onSelectProcess': {} },
                processes: (__VLS_ctx.sessionProcessesForTab),
            }));
            const __VLS_46 = __VLS_45({
                ...{ 'onSelectProcess': {} },
                processes: (__VLS_ctx.sessionProcessesForTab),
            }, ...__VLS_functionalComponentArgsRest(__VLS_45));
            let __VLS_48;
            let __VLS_49;
            let __VLS_50;
            const __VLS_51 = {
                onSelectProcess: (__VLS_ctx.clickProcessByMongoId)
            };
            var __VLS_47;
        }
    }
}
else if (__VLS_ctx.selection.kind === 'process') {
    if (!__VLS_ctx.selectedProcess) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "opacity-70" },
        });
        (__VLS_ctx.$t('insights.loading'));
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "tab-bar" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.selection))
                        return;
                    if (!!(__VLS_ctx.selection.kind === 'session'))
                        return;
                    if (!(__VLS_ctx.selection.kind === 'process'))
                        return;
                    if (!!(!__VLS_ctx.selectedProcess))
                        return;
                    __VLS_ctx.activeTab = 'overview';
                } },
            ...{ class: "tab" },
            ...{ class: ({ 'tab--active': __VLS_ctx.activeTab === 'overview' }) },
        });
        (__VLS_ctx.$t('insights.tabs.overview'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.selection))
                        return;
                    if (!!(__VLS_ctx.selection.kind === 'session'))
                        return;
                    if (!(__VLS_ctx.selection.kind === 'process'))
                        return;
                    if (!!(!__VLS_ctx.selectedProcess))
                        return;
                    __VLS_ctx.activeTab = 'chat';
                } },
            ...{ class: "tab" },
            ...{ class: ({ 'tab--active': __VLS_ctx.activeTab === 'chat' }) },
        });
        (__VLS_ctx.$t('insights.tabs.chat'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.selection))
                        return;
                    if (!!(__VLS_ctx.selection.kind === 'session'))
                        return;
                    if (!(__VLS_ctx.selection.kind === 'process'))
                        return;
                    if (!!(!__VLS_ctx.selectedProcess))
                        return;
                    __VLS_ctx.activeTab = 'memory';
                } },
            ...{ class: "tab" },
            ...{ class: ({ 'tab--active': __VLS_ctx.activeTab === 'memory' }) },
        });
        (__VLS_ctx.$t('insights.tabs.memory'));
        if (__VLS_ctx.isMarvin) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.selection))
                            return;
                        if (!!(__VLS_ctx.selection.kind === 'session'))
                            return;
                        if (!(__VLS_ctx.selection.kind === 'process'))
                            return;
                        if (!!(!__VLS_ctx.selectedProcess))
                            return;
                        if (!(__VLS_ctx.isMarvin))
                            return;
                        __VLS_ctx.activeTab = 'tree';
                    } },
                ...{ class: "tab" },
                ...{ class: ({ 'tab--active': __VLS_ctx.activeTab === 'tree' }) },
            });
            (__VLS_ctx.$t('insights.tabs.tree'));
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.selection))
                        return;
                    if (!!(__VLS_ctx.selection.kind === 'session'))
                        return;
                    if (!(__VLS_ctx.selection.kind === 'process'))
                        return;
                    if (!!(!__VLS_ctx.selectedProcess))
                        return;
                    __VLS_ctx.activeTab = 'llm-traces';
                } },
            ...{ class: "tab" },
            ...{ class: ({ 'tab--active': __VLS_ctx.activeTab === 'llm-traces' }) },
        });
        (__VLS_ctx.$t('insights.tabs.llmTrace'));
        if (__VLS_ctx.activeTab === 'overview') {
            const __VLS_52 = {}.VCard;
            /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
            // @ts-ignore
            const __VLS_53 = __VLS_asFunctionalComponent(__VLS_52, new __VLS_52({
                title: (__VLS_ctx.$t('insights.process.titlePrefix', { name: __VLS_ctx.selectedProcess.name })),
            }));
            const __VLS_54 = __VLS_53({
                title: (__VLS_ctx.$t('insights.process.titlePrefix', { name: __VLS_ctx.selectedProcess.name })),
            }, ...__VLS_functionalComponentArgsRest(__VLS_53));
            __VLS_55.slots.default;
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dl, __VLS_intrinsicElements.dl)({
                ...{ class: "grid grid-cols-2 gap-x-4 gap-y-1 text-sm" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            (__VLS_ctx.$t('insights.process.mongoId'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
                ...{ class: "font-mono text-xs" },
            });
            (__VLS_ctx.selectedProcess.id);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            (__VLS_ctx.$t('insights.process.session'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.selectedProcess.sessionId);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            (__VLS_ctx.$t('insights.process.engine'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.selectedProcess.thinkEngine);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "opacity-60" },
            });
            (__VLS_ctx.selectedProcess.thinkEngineVersion);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            (__VLS_ctx.$t('insights.process.recipe'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.fmt(__VLS_ctx.selectedProcess.recipeName));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            (__VLS_ctx.$t('insights.process.status'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.selectedProcess.status);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            (__VLS_ctx.$t('insights.process.parent'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            if (__VLS_ctx.selectedProcess.parentProcessId) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                    ...{ onClick: (...[$event]) => {
                            if (!!(!__VLS_ctx.selection))
                                return;
                            if (!!(__VLS_ctx.selection.kind === 'session'))
                                return;
                            if (!(__VLS_ctx.selection.kind === 'process'))
                                return;
                            if (!!(!__VLS_ctx.selectedProcess))
                                return;
                            if (!(__VLS_ctx.activeTab === 'overview'))
                                return;
                            if (!(__VLS_ctx.selectedProcess.parentProcessId))
                                return;
                            __VLS_ctx.clickProcessByMongoId(__VLS_ctx.selectedProcess.parentProcessId);
                        } },
                    ...{ class: "link" },
                });
                (__VLS_ctx.selectedProcess.parentProcessId);
            }
            else {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            }
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            (__VLS_ctx.$t('insights.process.goal'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.fmt(__VLS_ctx.selectedProcess.goal));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            (__VLS_ctx.$t('insights.process.created'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.fmt(__VLS_ctx.selectedProcess.createdAt));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60" },
            });
            (__VLS_ctx.$t('insights.process.updated'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
            (__VLS_ctx.fmt(__VLS_ctx.selectedProcess.updatedAt));
            var __VLS_55;
            const __VLS_56 = {}.VCard;
            /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
            // @ts-ignore
            const __VLS_57 = __VLS_asFunctionalComponent(__VLS_56, new __VLS_56({
                title: (__VLS_ctx.$t('insights.process.engineParams')),
            }));
            const __VLS_58 = __VLS_57({
                title: (__VLS_ctx.$t('insights.process.engineParams')),
            }, ...__VLS_functionalComponentArgsRest(__VLS_57));
            __VLS_59.slots.default;
            __VLS_asFunctionalElement(__VLS_intrinsicElements.pre, __VLS_intrinsicElements.pre)({
                ...{ class: "json-block" },
            });
            (__VLS_ctx.asJson(__VLS_ctx.selectedProcess.engineParams));
            var __VLS_59;
            const __VLS_60 = {}.VCard;
            /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
            // @ts-ignore
            const __VLS_61 = __VLS_asFunctionalComponent(__VLS_60, new __VLS_60({
                title: (__VLS_ctx.$t('insights.process.activeSkills')),
            }));
            const __VLS_62 = __VLS_61({
                title: (__VLS_ctx.$t('insights.process.activeSkills')),
            }, ...__VLS_functionalComponentArgsRest(__VLS_61));
            __VLS_63.slots.default;
            if (__VLS_ctx.selectedProcess.activeSkills.length === 0) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "opacity-70" },
                });
                (__VLS_ctx.$t('insights.process.noneActive'));
            }
            else {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
                    ...{ class: "flex flex-col divide-y divide-base-300" },
                });
                for (const [a, idx] of __VLS_getVForSourceType((__VLS_ctx.selectedProcess.activeSkills))) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                        key: ('skl-' + idx),
                        ...{ class: "py-2 flex items-center justify-between gap-2" },
                    });
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "font-mono text-sm" },
                    });
                    (a.name);
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "text-xs opacity-70" },
                    });
                    (a.resolvedFromScope);
                    if (a.fromRecipe) {
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                        (__VLS_ctx.$t('insights.process.fromRecipe'));
                    }
                    if (a.oneShot) {
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                        (__VLS_ctx.$t('insights.process.oneShot'));
                    }
                }
            }
            var __VLS_63;
            const __VLS_64 = {}.VCard;
            /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
            // @ts-ignore
            const __VLS_65 = __VLS_asFunctionalComponent(__VLS_64, new __VLS_64({
                title: (__VLS_ctx.$t('insights.process.pendingQueue')),
            }));
            const __VLS_66 = __VLS_65({
                title: (__VLS_ctx.$t('insights.process.pendingQueue')),
            }, ...__VLS_functionalComponentArgsRest(__VLS_65));
            __VLS_67.slots.default;
            if (__VLS_ctx.selectedProcess.pendingMessages.length === 0) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "opacity-70" },
                });
                (__VLS_ctx.$t('insights.process.drained'));
            }
            else {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
                    ...{ class: "flex flex-col divide-y divide-base-300" },
                });
                for (const [m, idx] of __VLS_getVForSourceType((__VLS_ctx.selectedProcess.pendingMessages))) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                        key: ('pm-' + idx),
                        ...{ class: "py-2" },
                    });
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: "flex items-center justify-between gap-2 text-sm" },
                    });
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "font-mono" },
                    });
                    (m.type);
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "opacity-60 text-xs" },
                    });
                    (__VLS_ctx.fmt(m.at));
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.pre, __VLS_intrinsicElements.pre)({
                        ...{ class: "json-block" },
                    });
                    (__VLS_ctx.asJson(m.payload));
                }
            }
            var __VLS_67;
        }
        else if (__VLS_ctx.activeTab === 'chat') {
            if (__VLS_ctx.chatState.loading.value) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "opacity-70" },
                });
                (__VLS_ctx.$t('insights.process.chatLoading'));
            }
            else if (__VLS_ctx.chatState.messages.value.length === 0) {
                const __VLS_68 = {}.VEmptyState;
                /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
                // @ts-ignore
                const __VLS_69 = __VLS_asFunctionalComponent(__VLS_68, new __VLS_68({
                    headline: (__VLS_ctx.$t('insights.process.chatEmptyHeadline')),
                    body: (__VLS_ctx.$t('insights.process.chatEmptyBody')),
                }));
                const __VLS_70 = __VLS_69({
                    headline: (__VLS_ctx.$t('insights.process.chatEmptyHeadline')),
                    body: (__VLS_ctx.$t('insights.process.chatEmptyBody')),
                }, ...__VLS_functionalComponentArgsRest(__VLS_69));
            }
            else {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
                    ...{ class: "flex flex-col gap-3" },
                });
                for (const [m] of __VLS_getVForSourceType((__VLS_ctx.chatState.messages.value))) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                        key: (m.id),
                        ...{ class: "chat-msg" },
                        ...{ class: ({
                                'chat-msg--user': m.role === __VLS_ctx.ChatRole.USER,
                                'chat-msg--assistant': m.role === __VLS_ctx.ChatRole.ASSISTANT,
                                'chat-msg--system': m.role === __VLS_ctx.ChatRole.SYSTEM,
                                'chat-msg--archived': m.archivedInMemoryId,
                            }) },
                    });
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: "flex items-center justify-between gap-2 text-xs opacity-60 mb-1" },
                    });
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "font-mono" },
                    });
                    (m.role);
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                    (__VLS_ctx.fmt(m.createdAt));
                    if (m.archivedInMemoryId) {
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                            ...{ class: "ml-2 opacity-80" },
                        });
                        (__VLS_ctx.$t('insights.process.archivedToMemory', { id: m.archivedInMemoryId }));
                    }
                    const __VLS_72 = {}.MarkdownView;
                    /** @type {[typeof __VLS_components.MarkdownView, ]} */ ;
                    // @ts-ignore
                    const __VLS_73 = __VLS_asFunctionalComponent(__VLS_72, new __VLS_72({
                        source: (m.content),
                    }));
                    const __VLS_74 = __VLS_73({
                        source: (m.content),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_73));
                }
            }
        }
        else if (__VLS_ctx.activeTab === 'memory') {
            if (__VLS_ctx.memoryState.loading.value) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "opacity-70" },
                });
                (__VLS_ctx.$t('insights.process.memoryLoading'));
            }
            else if (__VLS_ctx.memoryState.entries.value.length === 0) {
                const __VLS_76 = {}.VEmptyState;
                /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
                // @ts-ignore
                const __VLS_77 = __VLS_asFunctionalComponent(__VLS_76, new __VLS_76({
                    headline: (__VLS_ctx.$t('insights.process.memoryEmptyHeadline')),
                    body: (__VLS_ctx.$t('insights.process.memoryEmptyBody')),
                }));
                const __VLS_78 = __VLS_77({
                    headline: (__VLS_ctx.$t('insights.process.memoryEmptyHeadline')),
                    body: (__VLS_ctx.$t('insights.process.memoryEmptyBody')),
                }, ...__VLS_functionalComponentArgsRest(__VLS_77));
            }
            else {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "flex flex-col gap-3" },
                });
                for (const [m] of __VLS_getVForSourceType((__VLS_ctx.memoryState.entries.value))) {
                    const __VLS_80 = {}.VCard;
                    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
                    // @ts-ignore
                    const __VLS_81 = __VLS_asFunctionalComponent(__VLS_80, new __VLS_80({
                        key: (m.id),
                        title: (m.title || m.kind),
                    }));
                    const __VLS_82 = __VLS_81({
                        key: (m.id),
                        title: (m.title || m.kind),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_81));
                    __VLS_83.slots.default;
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: "text-xs opacity-60 mb-2 flex flex-wrap gap-x-3 gap-y-1" },
                    });
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "font-mono" },
                    });
                    (m.kind);
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                    (__VLS_ctx.fmt(m.createdAt));
                    if (m.supersededByMemoryId) {
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                        (__VLS_ctx.$t('insights.process.supersededBy', { id: m.supersededByMemoryId }));
                    }
                    if (m.sourceRefs.length > 0) {
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                        (__VLS_ctx.$t('insights.process.sources', { count: m.sourceRefs.length }));
                    }
                    const __VLS_84 = {}.MarkdownView;
                    /** @type {[typeof __VLS_components.MarkdownView, ]} */ ;
                    // @ts-ignore
                    const __VLS_85 = __VLS_asFunctionalComponent(__VLS_84, new __VLS_84({
                        source: (m.content),
                    }));
                    const __VLS_86 = __VLS_85({
                        source: (m.content),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_85));
                    if (Object.keys(m.metadata).length > 0) {
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.details, __VLS_intrinsicElements.details)({
                            ...{ class: "mt-3" },
                        });
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.summary, __VLS_intrinsicElements.summary)({
                            ...{ class: "text-xs opacity-70 cursor-pointer" },
                        });
                        (__VLS_ctx.$t('insights.process.metadata'));
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.pre, __VLS_intrinsicElements.pre)({
                            ...{ class: "json-block" },
                        });
                        (__VLS_ctx.asJson(m.metadata));
                    }
                    var __VLS_83;
                }
            }
        }
        else if (__VLS_ctx.activeTab === 'tree' && __VLS_ctx.isMarvin) {
            if (__VLS_ctx.treeState.loading.value) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "opacity-70" },
                });
                (__VLS_ctx.$t('insights.process.treeLoading'));
            }
            else if (__VLS_ctx.treeState.nodes.value.length === 0) {
                const __VLS_88 = {}.VEmptyState;
                /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
                // @ts-ignore
                const __VLS_89 = __VLS_asFunctionalComponent(__VLS_88, new __VLS_88({
                    headline: (__VLS_ctx.$t('insights.process.treeEmptyHeadline')),
                    body: (__VLS_ctx.$t('insights.process.treeEmptyBody')),
                }));
                const __VLS_90 = __VLS_89({
                    headline: (__VLS_ctx.$t('insights.process.treeEmptyHeadline')),
                    body: (__VLS_ctx.$t('insights.process.treeEmptyBody')),
                }, ...__VLS_functionalComponentArgsRest(__VLS_89));
            }
            else {
                const __VLS_92 = {}.VCard;
                /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
                // @ts-ignore
                const __VLS_93 = __VLS_asFunctionalComponent(__VLS_92, new __VLS_92({
                    title: (__VLS_ctx.$t('insights.process.marvinTreeTitle')),
                }));
                const __VLS_94 = __VLS_93({
                    title: (__VLS_ctx.$t('insights.process.marvinTreeTitle')),
                }, ...__VLS_functionalComponentArgsRest(__VLS_93));
                __VLS_95.slots.default;
                __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
                    ...{ class: "marvin-tree" },
                });
                for (const [root] of __VLS_getVForSourceType((__VLS_ctx.marvinTree))) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                        key: (root.doc.id),
                    });
                    /** @type {[typeof MarvinTreeItem, ]} */ ;
                    // @ts-ignore
                    const __VLS_96 = __VLS_asFunctionalComponent(MarvinTreeItem, new MarvinTreeItem({
                        ...{ 'onSelectProcess': {} },
                        node: (root),
                    }));
                    const __VLS_97 = __VLS_96({
                        ...{ 'onSelectProcess': {} },
                        node: (root),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_96));
                    let __VLS_99;
                    let __VLS_100;
                    let __VLS_101;
                    const __VLS_102 = {
                        onSelectProcess: (__VLS_ctx.clickProcessByMongoId)
                    };
                    var __VLS_98;
                }
                var __VLS_95;
            }
        }
        else if (__VLS_ctx.activeTab === 'llm-traces') {
            /** @type {[typeof LlmTraceTab, ]} */ ;
            // @ts-ignore
            const __VLS_103 = __VLS_asFunctionalComponent(LlmTraceTab, new LlmTraceTab({
                processId: (__VLS_ctx.selectedProcess.id),
            }));
            const __VLS_104 = __VLS_103({
                processId: (__VLS_ctx.selectedProcess.id),
            }, ...__VLS_functionalComponentArgsRest(__VLS_103));
        }
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
    (__VLS_ctx.$t('insights.help.title'));
    if (__VLS_ctx.help.loading.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60" },
        });
        (__VLS_ctx.$t('insights.help.loading'));
    }
    else if (__VLS_ctx.help.error.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60" },
        });
        (__VLS_ctx.$t('insights.help.unavailable', { error: __VLS_ctx.help.error.value }));
    }
    else if (!__VLS_ctx.help.content.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60" },
        });
        (__VLS_ctx.$t('insights.help.empty'));
    }
    else {
        const __VLS_106 = {}.MarkdownView;
        /** @type {[typeof __VLS_components.MarkdownView, ]} */ ;
        // @ts-ignore
        const __VLS_107 = __VLS_asFunctionalComponent(__VLS_106, new __VLS_106({
            source: (__VLS_ctx.help.content.value),
        }));
        const __VLS_108 = __VLS_107({
            source: (__VLS_ctx.help.content.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_107));
    }
}
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['session-row']} */ ;
/** @type {__VLS_StyleScopedClasses['chev']} */ ;
/** @type {__VLS_StyleScopedClasses['session-label']} */ ;
/** @type {__VLS_StyleScopedClasses['session-label--active']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['session-topic']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['session-children']} */ ;
/** @type {__VLS_StyleScopedClasses['process-row']} */ ;
/** @type {__VLS_StyleScopedClasses['process-row--active']} */ ;
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
/** @type {__VLS_StyleScopedClasses['max-w-5xl']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['session-header']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-baseline']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['session-topic-title']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['block']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-90']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['tab-bar']} */ ;
/** @type {__VLS_StyleScopedClasses['tab']} */ ;
/** @type {__VLS_StyleScopedClasses['tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['tab']} */ ;
/** @type {__VLS_StyleScopedClasses['tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['tab']} */ ;
/** @type {__VLS_StyleScopedClasses['tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-2']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-4']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-y-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['link']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['divide-y']} */ ;
/** @type {__VLS_StyleScopedClasses['divide-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:bg-base-200/40']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['tab-bar']} */ ;
/** @type {__VLS_StyleScopedClasses['tab']} */ ;
/** @type {__VLS_StyleScopedClasses['tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['tab']} */ ;
/** @type {__VLS_StyleScopedClasses['tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['tab']} */ ;
/** @type {__VLS_StyleScopedClasses['tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['tab']} */ ;
/** @type {__VLS_StyleScopedClasses['tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['tab']} */ ;
/** @type {__VLS_StyleScopedClasses['tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-2']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-4']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-y-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['link']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['json-block']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['divide-y']} */ ;
/** @type {__VLS_StyleScopedClasses['divide-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['divide-y']} */ ;
/** @type {__VLS_StyleScopedClasses['divide-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['json-block']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['chat-msg']} */ ;
/** @type {__VLS_StyleScopedClasses['chat-msg--user']} */ ;
/** @type {__VLS_StyleScopedClasses['chat-msg--assistant']} */ ;
/** @type {__VLS_StyleScopedClasses['chat-msg--system']} */ ;
/** @type {__VLS_StyleScopedClasses['chat-msg--archived']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['ml-2']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-3']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-y-1']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['json-block']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['marvin-tree']} */ ;
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
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            EditorShell: EditorShell,
            MarkdownView: MarkdownView,
            VAlert: VAlert,
            VCard: VCard,
            VEmptyState: VEmptyState,
            VInput: VInput,
            VSelect: VSelect,
            MarvinTreeItem: MarvinTreeItem,
            SessionTimelineTab: SessionTimelineTab,
            LlmTraceTab: LlmTraceTab,
            ChatRole: ChatRole,
            sessionsState: sessionsState,
            chatState: chatState,
            memoryState: memoryState,
            treeState: treeState,
            help: help,
            filterProjectId: filterProjectId,
            filterUserId: filterUserId,
            filterStatus: filterStatus,
            projectFilterOptions: projectFilterOptions,
            statusOptions: statusOptions,
            selection: selection,
            expanded: expanded,
            processesBySession: processesBySession,
            activeTab: activeTab,
            toggleExpand: toggleExpand,
            selectSession: selectSession,
            selectProcess: selectProcess,
            isSelectedSession: isSelectedSession,
            isSelectedProcess: isSelectedProcess,
            selectedSession: selectedSession,
            selectedProcess: selectedProcess,
            sessionProcessesForTab: sessionProcessesForTab,
            isMarvin: isMarvin,
            breadcrumbs: breadcrumbs,
            combinedError: combinedError,
            marvinTree: marvinTree,
            fmt: fmt,
            asJson: asJson,
            clickProcessByMongoId: clickProcessByMongoId,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=InsightsApp.vue.js.map