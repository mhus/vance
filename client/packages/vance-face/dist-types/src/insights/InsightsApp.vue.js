import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { EditorShell, MarkdownView, VAlert, VButton, VCard, VEmptyState, VInput, VSelect, } from '@/components';
import { useTenantProjects } from '@/composables/useTenantProjects';
import { downloadSessionExport, useInsightsSessions, useSessionProcesses, useProcessDetail, useProcessChat, useProcessMemory, useMarvinTree, useProcessPrakRuns, } from '@/composables/useInsights';
import { useHelp } from '@/composables/useHelp';
import MarvinTreeItem from './MarvinTreeItem.vue';
import SessionTimelineTab from './SessionTimelineTab.vue';
import LiveToolsTab from './LiveToolsTab.vue';
import LlmTraceTab from './LlmTraceTab.vue';
import CacheStatsTab from './CacheStatsTab.vue';
import RecipesTab from './RecipesTab.vue';
import ProjectToolsTab from './ProjectToolsTab.vue';
import WorkspaceTab from './WorkspaceTab.vue';
import ExecutionsTab from './ExecutionsTab.vue';
import ClusterTab from './ClusterTab.vue';
import AddonsTab from './AddonsTab.vue';
import EventsTab from './EventsTab.vue';
import SchedulerTab from './SchedulerTab.vue';
import UrsahooksTab from './UrsahooksTab.vue';
import WorkflowsTab from './WorkflowsTab.vue';
import RagTab from './RagTab.vue';
import UsageCostTab from './UsageCostTab.vue';
import ZarniwoopTab from './ZarniwoopTab.vue';
import { ChatRole, } from '@vance/generated';
const { t } = useI18n();
const tenantProjects = useTenantProjects();
const sessionsState = useInsightsSessions();
const processesState = useSessionProcesses();
const processDetailState = useProcessDetail();
const chatState = useProcessChat();
const memoryState = useProcessMemory();
const treeState = useMarvinTree();
const prakRunsState = useProcessPrakRuns();
// Client-side filter for the Memory tab: when true, hide everything
// that doesn't carry metadata.generatedBy === 'prak'.
const memoryPrakOnly = ref(false);
// Session-export download state — the request streams the JSONL file
// from the server, so the button stays in a loading state until the
// browser has the blob.
const exportLoading = ref(false);
const exportError = ref(null);
async function onExportSession(sessionId) {
    exportLoading.value = true;
    exportError.value = null;
    try {
        await downloadSessionExport(sessionId);
    }
    catch (e) {
        exportError.value = e instanceof Error ? e.message : t('insights.session.exportFailed');
    }
    finally {
        exportLoading.value = false;
    }
}
const help = useHelp();
// Focus zone — sidebar (filter + sessions tree), main (the
// selected session/process detail), right (help / docs). Driven by
// user clicks via @pointerdown on sidebar rows / main pane.
const focusZone = ref('main');
// ─── Filter state ───────────────────────────────────────────────────────
const filterProjectId = ref(null);
const filterUserId = ref('');
const filterStatus = ref(null);
const topTab = ref('sessions');
// Top-tab inventory. Order is significant: tabs to the left stay
// visible in the bar, tabs to the right are the first to spill into
// the "More ▾" dropdown when the row would overflow. Most-used tabs
// (Sessions, Recipes, Tools) live left so they're always reachable
// without an extra click.
const ALL_TABS = [
    { key: 'sessions', label: 'Sessions' },
    { key: 'recipes', label: 'Recipes' },
    { key: 'tools', label: 'Tools' },
    { key: 'workspace', label: 'Workspace' },
    { key: 'executions', label: 'Executions' },
    { key: 'workflows', label: 'Workflows' },
    { key: 'events', label: 'Events' },
    { key: 'scheduler', label: 'Scheduler' },
    { key: 'ursahooks', label: 'Hooks' },
    { key: 'rag', label: 'RAG' },
    { key: 'research', label: 'Research' },
    { key: 'cluster', label: 'Cluster' },
    { key: 'addons', label: 'Addons' },
    { key: 'usage', label: 'Usage & Cost' },
];
const tabBarRef = ref(null);
const tabPhantomRef = ref(null);
const moreBtnRef = ref(null);
const moreDropdownRef = ref(null);
const moreOpen = ref(false);
// Teleported dropdown lives in <body>, so it needs its own viewport
// coordinates. Recomputed every time the menu opens + on scroll/resize
// while open so it tracks the More button.
const moreMenuPos = ref({ top: 0, right: 0 });
// How many tabs fit inline. Initial value optimistic (everything fits);
// recalcVisibleTabs() narrows it on first paint + on every resize.
const visibleCount = ref(ALL_TABS.length);
const visibleTopTabs = computed(() => {
    const visible = ALL_TABS.slice(0, visibleCount.value);
    const overflow = ALL_TABS.slice(visibleCount.value);
    // If the active tab landed in the overflow, swap it into the last
    // visible slot so the user always sees their current selection in
    // the bar (the "More" button still glows because an overflow item
    // *was* active before the swap).
    if (overflow.some((t) => t.key === topTab.value) && visible.length > 0) {
        const active = ALL_TABS.find((t) => t.key === topTab.value);
        return [...visible.slice(0, -1), active];
    }
    return visible;
});
const overflowTopTabs = computed(() => {
    const visibleKeys = new Set(visibleTopTabs.value.map((t) => t.key));
    return ALL_TABS.filter((t) => !visibleKeys.has(t.key));
});
function recalcVisibleTabs() {
    const container = tabBarRef.value;
    const phantom = tabPhantomRef.value;
    if (!container || !phantom)
        return;
    const containerWidth = container.clientWidth;
    if (containerWidth <= 0)
        return;
    // Reserve room for the "More ▾" button — measure from phantom,
    // fall back to a conservative width if it hasn't rendered yet.
    const moreEl = phantom.querySelector('[data-more-phantom]');
    const moreWidth = moreEl?.offsetWidth ?? 80;
    const gap = 4;
    const tabEls = phantom.querySelectorAll('[data-tab-phantom]');
    // Single pass: try fitting all tabs first (no More button needed).
    let totalAll = 0;
    for (let i = 0; i < tabEls.length; i++) {
        totalAll += tabEls[i].offsetWidth + (i === 0 ? 0 : gap);
    }
    if (totalAll <= containerWidth) {
        visibleCount.value = ALL_TABS.length;
        return;
    }
    // Doesn't all fit — reserve space for More and greedy-fit from left.
    const budget = containerWidth - moreWidth - gap;
    let used = 0;
    let count = 0;
    for (let i = 0; i < tabEls.length; i++) {
        const w = tabEls[i].offsetWidth + (i === 0 ? 0 : gap);
        if (used + w > budget)
            break;
        used += w;
        count++;
    }
    visibleCount.value = Math.max(1, count);
}
function selectTopTab(key) {
    topTab.value = key;
    moreOpen.value = false;
}
function toggleMoreMenu() {
    if (moreOpen.value) {
        moreOpen.value = false;
        return;
    }
    updateMoreMenuPosition();
    moreOpen.value = true;
}
/** Snap the teleported menu under the More button (right-aligned). */
function updateMoreMenuPosition() {
    const btn = moreBtnRef.value;
    if (!btn)
        return;
    const r = btn.getBoundingClientRect();
    moreMenuPos.value = {
        top: r.bottom + 4,
        right: Math.max(8, window.innerWidth - r.right),
    };
}
function handleMoreOutsideClick(e) {
    if (!moreOpen.value)
        return;
    const target = e.target;
    if (target && moreBtnRef.value?.contains(target))
        return;
    if (target && moreDropdownRef.value?.contains(target))
        return;
    moreOpen.value = false;
}
function handleMoreReposition() {
    if (moreOpen.value)
        updateMoreMenuPosition();
}
let tabBarObserver = null;
onMounted(() => {
    nextTick(() => recalcVisibleTabs());
    if (typeof ResizeObserver !== 'undefined' && tabBarRef.value) {
        tabBarObserver = new ResizeObserver(() => {
            recalcVisibleTabs();
            handleMoreReposition();
        });
        tabBarObserver.observe(tabBarRef.value);
    }
    document.addEventListener('click', handleMoreOutsideClick);
    // Keep the teleported menu pinned to the More button under scroll
    // or viewport resize — without these, scrolling the page would
    // leave the menu floating where it was first opened.
    window.addEventListener('scroll', handleMoreReposition, true);
    window.addEventListener('resize', handleMoreReposition);
});
onBeforeUnmount(() => {
    tabBarObserver?.disconnect();
    tabBarObserver = null;
    document.removeEventListener('click', handleMoreOutsideClick);
    window.removeEventListener('scroll', handleMoreReposition, true);
    window.removeEventListener('resize', handleMoreReposition);
});
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
        prakRunsState.clear();
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
    else if (tab === 'prak-runs' && prakRunsState.runs.value.length === 0) {
        void prakRunsState.load(id);
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
/**
 * Project id the Recipes / Tools / Workspace top-tabs should show.
 * A picked session (or process) overrides the sidebar filter so the
 * user can pivot from a session straight to its project's effective
 * config without re-selecting the project. Falls back to the explicit
 * sidebar filter when nothing is selected.
 */
const effectiveProjectId = computed(() => {
    const sel = selection.value;
    if (sel?.kind === 'session') {
        const s = sessionsState.sessions.value.find(x => x.sessionId === sel.id);
        if (s?.projectId)
            return s.projectId;
    }
    else if (sel?.kind === 'process') {
        const p = selectedProcess.value;
        if (p) {
            const s = sessionsState.sessions.value.find(x => x.sessionId === p.sessionId);
            if (s?.projectId)
                return s.projectId;
        }
    }
    return filterProjectId.value;
});
/** Source label for the project-context hint shown above project-tabs. */
const projectContextSource = computed(() => {
    const sel = selection.value;
    if (sel?.kind === 'session') {
        const s = sessionsState.sessions.value.find(x => x.sessionId === sel.id);
        if (s)
            return { kind: 'session', label: s.firstUserMessage || s.sessionId };
    }
    else if (sel?.kind === 'process') {
        const p = selectedProcess.value;
        if (p)
            return { kind: 'process', label: p.name };
    }
    return null;
});
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
    || treeState.error.value
    || prakRunsState.error.value);
// ─── Memory tab — Prak-aware helpers ───────────────────────────────────
function isPrakMemory(meta) {
    return meta?.['generatedBy'] === 'prak';
}
function extractPrakMeta(meta) {
    const labels = meta['prakLabels'];
    return {
        type: typeof meta['prakType'] === 'string' ? meta['prakType'] : undefined,
        importance: typeof meta['prakImportance'] === 'number' ? meta['prakImportance'] : undefined,
        confidence: typeof meta['prakConfidence'] === 'number' ? meta['prakConfidence'] : undefined,
        labels: Array.isArray(labels) ? labels.filter((x) => typeof x === 'string') : undefined,
        decay: typeof meta['prakDecay'] === 'string' ? meta['prakDecay'] : undefined,
        why: typeof meta['prakWhy'] === 'string' ? meta['prakWhy'] : undefined,
        runId: typeof meta['prakRunId'] === 'string' ? meta['prakRunId'] : undefined,
    };
}
const filteredMemoryEntries = computed(() => {
    const all = memoryState.entries.value;
    if (!memoryPrakOnly.value)
        return all;
    return all.filter(m => isPrakMemory(m.metadata));
});
// ─── Chat tab — STRENGTH:* tag helpers ────────────────────────────────
const STRENGTH_PREFIX = 'STRENGTH:';
function strengthTag(tags) {
    if (!tags)
        return null;
    for (const t of tags) {
        if (t.startsWith(STRENGTH_PREFIX))
            return t.substring(STRENGTH_PREFIX.length);
    }
    return null;
}
function otherTags(tags) {
    if (!tags)
        return [];
    return tags.filter(t => !t.startsWith(STRENGTH_PREFIX));
}
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
    ...{ 'onTitleClick': {} },
    focusZone: (__VLS_ctx.focusZone),
    title: (__VLS_ctx.$t('insights.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    fullHeight: (true),
    showSidebar: (true),
    showRightPanel: (true),
    focusModel: "auto",
    titleClickable: true,
    wideRightPanel: true,
}));
const __VLS_2 = __VLS_1({
    ...{ 'onTitleClick': {} },
    focusZone: (__VLS_ctx.focusZone),
    title: (__VLS_ctx.$t('insights.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    fullHeight: (true),
    showSidebar: (true),
    showRightPanel: (true),
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
        ...{ class: "flex flex-col gap-3 p-2" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-2" },
    });
    const __VLS_9 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_10 = __VLS_asFunctionalComponent(__VLS_9, new __VLS_9({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.filterProjectId ?? ''),
        options: (__VLS_ctx.projectFilterOptions),
        label: (__VLS_ctx.$t('insights.filters.project')),
    }));
    const __VLS_11 = __VLS_10({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.filterProjectId ?? ''),
        options: (__VLS_ctx.projectFilterOptions),
        label: (__VLS_ctx.$t('insights.filters.project')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_10));
    let __VLS_13;
    let __VLS_14;
    let __VLS_15;
    const __VLS_16 = {
        'onUpdate:modelValue': ((v) => __VLS_ctx.filterProjectId = (v ? String(v) : null))
    };
    var __VLS_12;
    const __VLS_17 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_18 = __VLS_asFunctionalComponent(__VLS_17, new __VLS_17({
        modelValue: (__VLS_ctx.filterUserId),
        label: (__VLS_ctx.$t('insights.filters.user')),
        placeholder: (__VLS_ctx.$t('insights.filters.userPlaceholder')),
    }));
    const __VLS_19 = __VLS_18({
        modelValue: (__VLS_ctx.filterUserId),
        label: (__VLS_ctx.$t('insights.filters.user')),
        placeholder: (__VLS_ctx.$t('insights.filters.userPlaceholder')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_18));
    const __VLS_21 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_22 = __VLS_asFunctionalComponent(__VLS_21, new __VLS_21({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.filterStatus ?? ''),
        options: (__VLS_ctx.statusOptions),
        label: (__VLS_ctx.$t('insights.filters.status')),
    }));
    const __VLS_23 = __VLS_22({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.filterStatus ?? ''),
        options: (__VLS_ctx.statusOptions),
        label: (__VLS_ctx.$t('insights.filters.status')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_22));
    let __VLS_25;
    let __VLS_26;
    let __VLS_27;
    const __VLS_28 = {
        'onUpdate:modelValue': ((v) => __VLS_ctx.filterStatus = (v ? String(v) : null))
    };
    var __VLS_24;
    if (__VLS_ctx.sessionsState.loading.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60 px-2" },
        });
        (__VLS_ctx.$t('insights.sidebar.loadingSessions'));
    }
    else if (__VLS_ctx.sessionsState.sessions.value.length === 0) {
        const __VLS_29 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_30 = __VLS_asFunctionalComponent(__VLS_29, new __VLS_29({
            headline: (__VLS_ctx.$t('insights.sidebar.noSessionsHeadline')),
            body: (__VLS_ctx.$t('insights.sidebar.noSessionsBody')),
        }));
        const __VLS_31 = __VLS_30({
            headline: (__VLS_ctx.$t('insights.sidebar.noSessionsHeadline')),
            body: (__VLS_ctx.$t('insights.sidebar.noSessionsBody')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_30));
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
            ...{ onPointerdown: (...[$event]) => {
                    __VLS_ctx.focusZone = 'main';
                } },
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
    ...{ class: "h-full min-h-0 overflow-y-auto" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "p-6 flex flex-col gap-3 max-w-5xl" },
});
if (__VLS_ctx.combinedError) {
    const __VLS_33 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_34 = __VLS_asFunctionalComponent(__VLS_33, new __VLS_33({
        variant: "error",
    }));
    const __VLS_35 = __VLS_34({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_34));
    __VLS_36.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.combinedError);
    var __VLS_36;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ref: "tabBarRef",
    ...{ class: "tab-bar tab-bar--overflow mb-1" },
});
/** @type {typeof __VLS_ctx.tabBarRef} */ ;
for (const [tab] of __VLS_getVForSourceType((__VLS_ctx.visibleTopTabs))) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (...[$event]) => {
                __VLS_ctx.selectTopTab(tab.key);
            } },
        key: (tab.key),
        ...{ class: "tab" },
        ...{ class: ({ 'tab--active': __VLS_ctx.topTab === tab.key }) },
    });
    (tab.label);
}
if (__VLS_ctx.overflowTopTabs.length > 0) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "tab-more" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.toggleMoreMenu) },
        ref: "moreBtnRef",
        ...{ class: "tab tab-more__btn" },
        ...{ class: ({ 'tab--active': __VLS_ctx.overflowTopTabs.some((t) => t.key === __VLS_ctx.topTab) }) },
    });
    /** @type {typeof __VLS_ctx.moreBtnRef} */ ;
}
const __VLS_37 = {}.Teleport;
/** @type {[typeof __VLS_components.Teleport, typeof __VLS_components.Teleport, ]} */ ;
// @ts-ignore
const __VLS_38 = __VLS_asFunctionalComponent(__VLS_37, new __VLS_37({
    to: "body",
}));
const __VLS_39 = __VLS_38({
    to: "body",
}, ...__VLS_functionalComponentArgsRest(__VLS_38));
__VLS_40.slots.default;
if (__VLS_ctx.moreOpen && __VLS_ctx.overflowTopTabs.length > 0) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ref: "moreDropdownRef",
        ...{ class: "tab-more__menu" },
        ...{ style: ({ top: `${__VLS_ctx.moreMenuPos.top}px`, right: `${__VLS_ctx.moreMenuPos.right}px` }) },
    });
    /** @type {typeof __VLS_ctx.moreDropdownRef} */ ;
    for (const [tab] of __VLS_getVForSourceType((__VLS_ctx.overflowTopTabs))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!(__VLS_ctx.moreOpen && __VLS_ctx.overflowTopTabs.length > 0))
                        return;
                    __VLS_ctx.selectTopTab(tab.key);
                } },
            key: (tab.key),
            ...{ class: "tab-more__item" },
            ...{ class: ({ 'tab-more__item--active': __VLS_ctx.topTab === tab.key }) },
        });
        (tab.label);
    }
}
var __VLS_40;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ref: "tabPhantomRef",
    ...{ class: "tab-bar tab-bar--phantom" },
    'aria-hidden': "true",
});
/** @type {typeof __VLS_ctx.tabPhantomRef} */ ;
for (const [tab] of __VLS_getVForSourceType((__VLS_ctx.ALL_TABS))) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        key: (`phantom-${tab.key}`),
        ...{ class: "tab" },
        tabindex: "-1",
        'data-tab-phantom': true,
    });
    (tab.label);
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ class: "tab tab-more__btn" },
    tabindex: "-1",
    'data-more-phantom': true,
});
if (__VLS_ctx.topTab !== 'sessions' && __VLS_ctx.topTab !== 'cluster' && __VLS_ctx.topTab !== 'addons' && __VLS_ctx.topTab !== 'usage' && __VLS_ctx.projectContextSource) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-xs opacity-70 -mt-1 mb-1" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "font-mono" },
    });
    (__VLS_ctx.effectiveProjectId ?? '—');
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "opacity-60" },
    });
    (__VLS_ctx.projectContextSource.kind);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "italic" },
    });
    (__VLS_ctx.projectContextSource.label);
}
if (__VLS_ctx.topTab === 'recipes') {
    /** @type {[typeof RecipesTab, ]} */ ;
    // @ts-ignore
    const __VLS_41 = __VLS_asFunctionalComponent(RecipesTab, new RecipesTab({
        projectId: (__VLS_ctx.effectiveProjectId),
    }));
    const __VLS_42 = __VLS_41({
        projectId: (__VLS_ctx.effectiveProjectId),
    }, ...__VLS_functionalComponentArgsRest(__VLS_41));
}
else if (__VLS_ctx.topTab === 'tools') {
    /** @type {[typeof ProjectToolsTab, ]} */ ;
    // @ts-ignore
    const __VLS_44 = __VLS_asFunctionalComponent(ProjectToolsTab, new ProjectToolsTab({
        projectId: (__VLS_ctx.effectiveProjectId),
    }));
    const __VLS_45 = __VLS_44({
        projectId: (__VLS_ctx.effectiveProjectId),
    }, ...__VLS_functionalComponentArgsRest(__VLS_44));
}
else if (__VLS_ctx.topTab === 'workspace') {
    /** @type {[typeof WorkspaceTab, ]} */ ;
    // @ts-ignore
    const __VLS_47 = __VLS_asFunctionalComponent(WorkspaceTab, new WorkspaceTab({
        projectId: (__VLS_ctx.effectiveProjectId),
    }));
    const __VLS_48 = __VLS_47({
        projectId: (__VLS_ctx.effectiveProjectId),
    }, ...__VLS_functionalComponentArgsRest(__VLS_47));
}
else if (__VLS_ctx.topTab === 'executions') {
    /** @type {[typeof ExecutionsTab, ]} */ ;
    // @ts-ignore
    const __VLS_50 = __VLS_asFunctionalComponent(ExecutionsTab, new ExecutionsTab({
        projectId: (__VLS_ctx.effectiveProjectId),
    }));
    const __VLS_51 = __VLS_50({
        projectId: (__VLS_ctx.effectiveProjectId),
    }, ...__VLS_functionalComponentArgsRest(__VLS_50));
}
else if (__VLS_ctx.topTab === 'workflows') {
    /** @type {[typeof WorkflowsTab, ]} */ ;
    // @ts-ignore
    const __VLS_53 = __VLS_asFunctionalComponent(WorkflowsTab, new WorkflowsTab({
        projectId: (__VLS_ctx.effectiveProjectId),
    }));
    const __VLS_54 = __VLS_53({
        projectId: (__VLS_ctx.effectiveProjectId),
    }, ...__VLS_functionalComponentArgsRest(__VLS_53));
}
else if (__VLS_ctx.topTab === 'events') {
    /** @type {[typeof EventsTab, ]} */ ;
    // @ts-ignore
    const __VLS_56 = __VLS_asFunctionalComponent(EventsTab, new EventsTab({
        projectId: (__VLS_ctx.effectiveProjectId),
    }));
    const __VLS_57 = __VLS_56({
        projectId: (__VLS_ctx.effectiveProjectId),
    }, ...__VLS_functionalComponentArgsRest(__VLS_56));
}
else if (__VLS_ctx.topTab === 'scheduler') {
    /** @type {[typeof SchedulerTab, ]} */ ;
    // @ts-ignore
    const __VLS_59 = __VLS_asFunctionalComponent(SchedulerTab, new SchedulerTab({
        projectId: (__VLS_ctx.effectiveProjectId),
    }));
    const __VLS_60 = __VLS_59({
        projectId: (__VLS_ctx.effectiveProjectId),
    }, ...__VLS_functionalComponentArgsRest(__VLS_59));
}
else if (__VLS_ctx.topTab === 'ursahooks') {
    /** @type {[typeof UrsahooksTab, ]} */ ;
    // @ts-ignore
    const __VLS_62 = __VLS_asFunctionalComponent(UrsahooksTab, new UrsahooksTab({
        projectId: (__VLS_ctx.effectiveProjectId),
    }));
    const __VLS_63 = __VLS_62({
        projectId: (__VLS_ctx.effectiveProjectId),
    }, ...__VLS_functionalComponentArgsRest(__VLS_62));
}
else if (__VLS_ctx.topTab === 'rag') {
    /** @type {[typeof RagTab, ]} */ ;
    // @ts-ignore
    const __VLS_65 = __VLS_asFunctionalComponent(RagTab, new RagTab({
        projectId: (__VLS_ctx.effectiveProjectId),
    }));
    const __VLS_66 = __VLS_65({
        projectId: (__VLS_ctx.effectiveProjectId),
    }, ...__VLS_functionalComponentArgsRest(__VLS_65));
}
else if (__VLS_ctx.topTab === 'research') {
    /** @type {[typeof ZarniwoopTab, ]} */ ;
    // @ts-ignore
    const __VLS_68 = __VLS_asFunctionalComponent(ZarniwoopTab, new ZarniwoopTab({
        projectId: (__VLS_ctx.effectiveProjectId),
    }));
    const __VLS_69 = __VLS_68({
        projectId: (__VLS_ctx.effectiveProjectId),
    }, ...__VLS_functionalComponentArgsRest(__VLS_68));
}
else if (__VLS_ctx.topTab === 'cluster') {
    /** @type {[typeof ClusterTab, ]} */ ;
    // @ts-ignore
    const __VLS_71 = __VLS_asFunctionalComponent(ClusterTab, new ClusterTab({}));
    const __VLS_72 = __VLS_71({}, ...__VLS_functionalComponentArgsRest(__VLS_71));
}
else if (__VLS_ctx.topTab === 'addons') {
    /** @type {[typeof AddonsTab, ]} */ ;
    // @ts-ignore
    const __VLS_74 = __VLS_asFunctionalComponent(AddonsTab, new AddonsTab({}));
    const __VLS_75 = __VLS_74({}, ...__VLS_functionalComponentArgsRest(__VLS_74));
}
else if (__VLS_ctx.topTab === 'usage') {
    /** @type {[typeof UsageCostTab, ]} */ ;
    // @ts-ignore
    const __VLS_77 = __VLS_asFunctionalComponent(UsageCostTab, new UsageCostTab({}));
    const __VLS_78 = __VLS_77({}, ...__VLS_functionalComponentArgsRest(__VLS_77));
}
else if (__VLS_ctx.topTab === 'sessions') {
    if (!__VLS_ctx.selection) {
        const __VLS_80 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_81 = __VLS_asFunctionalComponent(__VLS_80, new __VLS_80({
            headline: (__VLS_ctx.$t('insights.emptyMain.headline')),
            body: (__VLS_ctx.$t('insights.emptyMain.body')),
        }));
        const __VLS_82 = __VLS_81({
            headline: (__VLS_ctx.$t('insights.emptyMain.headline')),
            body: (__VLS_ctx.$t('insights.emptyMain.body')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_81));
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
                ...{ class: "flex items-baseline gap-2 flex-wrap justify-between" },
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
            const __VLS_84 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_85 = __VLS_asFunctionalComponent(__VLS_84, new __VLS_84({
                ...{ 'onClick': {} },
                variant: "ghost",
                size: "sm",
                loading: (__VLS_ctx.exportLoading),
                disabled: (__VLS_ctx.exportLoading),
                title: (__VLS_ctx.$t('insights.session.exportTooltip')),
            }));
            const __VLS_86 = __VLS_85({
                ...{ 'onClick': {} },
                variant: "ghost",
                size: "sm",
                loading: (__VLS_ctx.exportLoading),
                disabled: (__VLS_ctx.exportLoading),
                title: (__VLS_ctx.$t('insights.session.exportTooltip')),
            }, ...__VLS_functionalComponentArgsRest(__VLS_85));
            let __VLS_88;
            let __VLS_89;
            let __VLS_90;
            const __VLS_91 = {
                onClick: (...[$event]) => {
                    if (!!(__VLS_ctx.topTab === 'recipes'))
                        return;
                    if (!!(__VLS_ctx.topTab === 'tools'))
                        return;
                    if (!!(__VLS_ctx.topTab === 'workspace'))
                        return;
                    if (!!(__VLS_ctx.topTab === 'executions'))
                        return;
                    if (!!(__VLS_ctx.topTab === 'workflows'))
                        return;
                    if (!!(__VLS_ctx.topTab === 'events'))
                        return;
                    if (!!(__VLS_ctx.topTab === 'scheduler'))
                        return;
                    if (!!(__VLS_ctx.topTab === 'ursahooks'))
                        return;
                    if (!!(__VLS_ctx.topTab === 'rag'))
                        return;
                    if (!!(__VLS_ctx.topTab === 'research'))
                        return;
                    if (!!(__VLS_ctx.topTab === 'cluster'))
                        return;
                    if (!!(__VLS_ctx.topTab === 'addons'))
                        return;
                    if (!!(__VLS_ctx.topTab === 'usage'))
                        return;
                    if (!(__VLS_ctx.topTab === 'sessions'))
                        return;
                    if (!!(!__VLS_ctx.selection))
                        return;
                    if (!(__VLS_ctx.selection.kind === 'session'))
                        return;
                    if (!!(!__VLS_ctx.selectedSession))
                        return;
                    __VLS_ctx.onExportSession(__VLS_ctx.selectedSession.sessionId);
                }
            };
            __VLS_87.slots.default;
            (__VLS_ctx.$t('insights.session.exportButton'));
            var __VLS_87;
            if (__VLS_ctx.exportError) {
                const __VLS_92 = {}.VAlert;
                /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
                // @ts-ignore
                const __VLS_93 = __VLS_asFunctionalComponent(__VLS_92, new __VLS_92({
                    variant: "error",
                    ...{ class: "mt-2" },
                }));
                const __VLS_94 = __VLS_93({
                    variant: "error",
                    ...{ class: "mt-2" },
                }, ...__VLS_functionalComponentArgsRest(__VLS_93));
                __VLS_95.slots.default;
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.exportError);
                var __VLS_95;
            }
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
                        if (!!(__VLS_ctx.topTab === 'recipes'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'tools'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'workspace'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'executions'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'workflows'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'events'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'scheduler'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'ursahooks'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'rag'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'research'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'cluster'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'addons'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'usage'))
                            return;
                        if (!(__VLS_ctx.topTab === 'sessions'))
                            return;
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
                        if (!!(__VLS_ctx.topTab === 'recipes'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'tools'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'workspace'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'executions'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'workflows'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'events'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'scheduler'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'ursahooks'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'rag'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'research'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'cluster'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'addons'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'usage'))
                            return;
                        if (!(__VLS_ctx.topTab === 'sessions'))
                            return;
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
                        if (!!(__VLS_ctx.topTab === 'recipes'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'tools'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'workspace'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'executions'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'workflows'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'events'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'scheduler'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'ursahooks'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'rag'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'research'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'cluster'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'addons'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'usage'))
                            return;
                        if (!(__VLS_ctx.topTab === 'sessions'))
                            return;
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
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(__VLS_ctx.topTab === 'recipes'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'tools'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'workspace'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'executions'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'workflows'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'events'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'scheduler'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'ursahooks'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'rag'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'research'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'cluster'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'addons'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'usage'))
                            return;
                        if (!(__VLS_ctx.topTab === 'sessions'))
                            return;
                        if (!!(!__VLS_ctx.selection))
                            return;
                        if (!(__VLS_ctx.selection.kind === 'session'))
                            return;
                        if (!!(!__VLS_ctx.selectedSession))
                            return;
                        __VLS_ctx.activeTab = 'live-tools';
                    } },
                ...{ class: "tab" },
                ...{ class: ({ 'tab--active': __VLS_ctx.activeTab === 'live-tools' }) },
            });
            if (__VLS_ctx.activeTab === 'overview') {
                const __VLS_96 = {}.VCard;
                /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
                // @ts-ignore
                const __VLS_97 = __VLS_asFunctionalComponent(__VLS_96, new __VLS_96({
                    title: (__VLS_ctx.$t('insights.session.detailsTitle')),
                }));
                const __VLS_98 = __VLS_97({
                    title: (__VLS_ctx.$t('insights.session.detailsTitle')),
                }, ...__VLS_functionalComponentArgsRest(__VLS_97));
                __VLS_99.slots.default;
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
                                if (!!(__VLS_ctx.topTab === 'recipes'))
                                    return;
                                if (!!(__VLS_ctx.topTab === 'tools'))
                                    return;
                                if (!!(__VLS_ctx.topTab === 'workspace'))
                                    return;
                                if (!!(__VLS_ctx.topTab === 'executions'))
                                    return;
                                if (!!(__VLS_ctx.topTab === 'workflows'))
                                    return;
                                if (!!(__VLS_ctx.topTab === 'events'))
                                    return;
                                if (!!(__VLS_ctx.topTab === 'scheduler'))
                                    return;
                                if (!!(__VLS_ctx.topTab === 'ursahooks'))
                                    return;
                                if (!!(__VLS_ctx.topTab === 'rag'))
                                    return;
                                if (!!(__VLS_ctx.topTab === 'research'))
                                    return;
                                if (!!(__VLS_ctx.topTab === 'cluster'))
                                    return;
                                if (!!(__VLS_ctx.topTab === 'addons'))
                                    return;
                                if (!!(__VLS_ctx.topTab === 'usage'))
                                    return;
                                if (!(__VLS_ctx.topTab === 'sessions'))
                                    return;
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
                var __VLS_99;
            }
            if (__VLS_ctx.activeTab === 'processes') {
                const __VLS_100 = {}.VCard;
                /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
                // @ts-ignore
                const __VLS_101 = __VLS_asFunctionalComponent(__VLS_100, new __VLS_100({
                    title: (__VLS_ctx.$t('insights.session.processesTitle')),
                }));
                const __VLS_102 = __VLS_101({
                    title: (__VLS_ctx.$t('insights.session.processesTitle')),
                }, ...__VLS_functionalComponentArgsRest(__VLS_101));
                __VLS_103.slots.default;
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
                                    if (!!(__VLS_ctx.topTab === 'recipes'))
                                        return;
                                    if (!!(__VLS_ctx.topTab === 'tools'))
                                        return;
                                    if (!!(__VLS_ctx.topTab === 'workspace'))
                                        return;
                                    if (!!(__VLS_ctx.topTab === 'executions'))
                                        return;
                                    if (!!(__VLS_ctx.topTab === 'workflows'))
                                        return;
                                    if (!!(__VLS_ctx.topTab === 'events'))
                                        return;
                                    if (!!(__VLS_ctx.topTab === 'scheduler'))
                                        return;
                                    if (!!(__VLS_ctx.topTab === 'ursahooks'))
                                        return;
                                    if (!!(__VLS_ctx.topTab === 'rag'))
                                        return;
                                    if (!!(__VLS_ctx.topTab === 'research'))
                                        return;
                                    if (!!(__VLS_ctx.topTab === 'cluster'))
                                        return;
                                    if (!!(__VLS_ctx.topTab === 'addons'))
                                        return;
                                    if (!!(__VLS_ctx.topTab === 'usage'))
                                        return;
                                    if (!(__VLS_ctx.topTab === 'sessions'))
                                        return;
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
                var __VLS_103;
            }
            if (__VLS_ctx.activeTab === 'timeline') {
                /** @type {[typeof SessionTimelineTab, ]} */ ;
                // @ts-ignore
                const __VLS_104 = __VLS_asFunctionalComponent(SessionTimelineTab, new SessionTimelineTab({
                    ...{ 'onSelectProcess': {} },
                    processes: (__VLS_ctx.sessionProcessesForTab),
                }));
                const __VLS_105 = __VLS_104({
                    ...{ 'onSelectProcess': {} },
                    processes: (__VLS_ctx.sessionProcessesForTab),
                }, ...__VLS_functionalComponentArgsRest(__VLS_104));
                let __VLS_107;
                let __VLS_108;
                let __VLS_109;
                const __VLS_110 = {
                    onSelectProcess: (__VLS_ctx.clickProcessByMongoId)
                };
                var __VLS_106;
            }
            if (__VLS_ctx.activeTab === 'live-tools') {
                /** @type {[typeof LiveToolsTab, ]} */ ;
                // @ts-ignore
                const __VLS_111 = __VLS_asFunctionalComponent(LiveToolsTab, new LiveToolsTab({
                    sessionId: (__VLS_ctx.selectedSession.sessionId),
                }));
                const __VLS_112 = __VLS_111({
                    sessionId: (__VLS_ctx.selectedSession.sessionId),
                }, ...__VLS_functionalComponentArgsRest(__VLS_111));
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
                        if (!!(__VLS_ctx.topTab === 'recipes'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'tools'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'workspace'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'executions'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'workflows'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'events'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'scheduler'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'ursahooks'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'rag'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'research'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'cluster'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'addons'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'usage'))
                            return;
                        if (!(__VLS_ctx.topTab === 'sessions'))
                            return;
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
                        if (!!(__VLS_ctx.topTab === 'recipes'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'tools'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'workspace'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'executions'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'workflows'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'events'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'scheduler'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'ursahooks'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'rag'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'research'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'cluster'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'addons'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'usage'))
                            return;
                        if (!(__VLS_ctx.topTab === 'sessions'))
                            return;
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
                        if (!!(__VLS_ctx.topTab === 'recipes'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'tools'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'workspace'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'executions'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'workflows'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'events'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'scheduler'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'ursahooks'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'rag'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'research'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'cluster'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'addons'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'usage'))
                            return;
                        if (!(__VLS_ctx.topTab === 'sessions'))
                            return;
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
                            if (!!(__VLS_ctx.topTab === 'recipes'))
                                return;
                            if (!!(__VLS_ctx.topTab === 'tools'))
                                return;
                            if (!!(__VLS_ctx.topTab === 'workspace'))
                                return;
                            if (!!(__VLS_ctx.topTab === 'executions'))
                                return;
                            if (!!(__VLS_ctx.topTab === 'workflows'))
                                return;
                            if (!!(__VLS_ctx.topTab === 'events'))
                                return;
                            if (!!(__VLS_ctx.topTab === 'scheduler'))
                                return;
                            if (!!(__VLS_ctx.topTab === 'ursahooks'))
                                return;
                            if (!!(__VLS_ctx.topTab === 'rag'))
                                return;
                            if (!!(__VLS_ctx.topTab === 'research'))
                                return;
                            if (!!(__VLS_ctx.topTab === 'cluster'))
                                return;
                            if (!!(__VLS_ctx.topTab === 'addons'))
                                return;
                            if (!!(__VLS_ctx.topTab === 'usage'))
                                return;
                            if (!(__VLS_ctx.topTab === 'sessions'))
                                return;
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
                        if (!!(__VLS_ctx.topTab === 'recipes'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'tools'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'workspace'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'executions'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'workflows'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'events'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'scheduler'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'ursahooks'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'rag'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'research'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'cluster'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'addons'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'usage'))
                            return;
                        if (!(__VLS_ctx.topTab === 'sessions'))
                            return;
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
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(__VLS_ctx.topTab === 'recipes'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'tools'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'workspace'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'executions'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'workflows'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'events'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'scheduler'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'ursahooks'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'rag'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'research'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'cluster'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'addons'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'usage'))
                            return;
                        if (!(__VLS_ctx.topTab === 'sessions'))
                            return;
                        if (!!(!__VLS_ctx.selection))
                            return;
                        if (!!(__VLS_ctx.selection.kind === 'session'))
                            return;
                        if (!(__VLS_ctx.selection.kind === 'process'))
                            return;
                        if (!!(!__VLS_ctx.selectedProcess))
                            return;
                        __VLS_ctx.activeTab = 'cache-stats';
                    } },
                ...{ class: "tab" },
                ...{ class: ({ 'tab--active': __VLS_ctx.activeTab === 'cache-stats' }) },
            });
            (__VLS_ctx.$t('insights.tabs.cacheStats'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(__VLS_ctx.topTab === 'recipes'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'tools'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'workspace'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'executions'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'workflows'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'events'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'scheduler'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'ursahooks'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'rag'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'research'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'cluster'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'addons'))
                            return;
                        if (!!(__VLS_ctx.topTab === 'usage'))
                            return;
                        if (!(__VLS_ctx.topTab === 'sessions'))
                            return;
                        if (!!(!__VLS_ctx.selection))
                            return;
                        if (!!(__VLS_ctx.selection.kind === 'session'))
                            return;
                        if (!(__VLS_ctx.selection.kind === 'process'))
                            return;
                        if (!!(!__VLS_ctx.selectedProcess))
                            return;
                        __VLS_ctx.activeTab = 'prak-runs';
                    } },
                ...{ class: "tab" },
                ...{ class: ({ 'tab--active': __VLS_ctx.activeTab === 'prak-runs' }) },
            });
            (__VLS_ctx.$t('insights.tabs.prakRuns'));
            if (__VLS_ctx.activeTab === 'overview') {
                const __VLS_114 = {}.VCard;
                /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
                // @ts-ignore
                const __VLS_115 = __VLS_asFunctionalComponent(__VLS_114, new __VLS_114({
                    title: (__VLS_ctx.$t('insights.process.titlePrefix', { name: __VLS_ctx.selectedProcess.name })),
                }));
                const __VLS_116 = __VLS_115({
                    title: (__VLS_ctx.$t('insights.process.titlePrefix', { name: __VLS_ctx.selectedProcess.name })),
                }, ...__VLS_functionalComponentArgsRest(__VLS_115));
                __VLS_117.slots.default;
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
                                if (!!(__VLS_ctx.topTab === 'recipes'))
                                    return;
                                if (!!(__VLS_ctx.topTab === 'tools'))
                                    return;
                                if (!!(__VLS_ctx.topTab === 'workspace'))
                                    return;
                                if (!!(__VLS_ctx.topTab === 'executions'))
                                    return;
                                if (!!(__VLS_ctx.topTab === 'workflows'))
                                    return;
                                if (!!(__VLS_ctx.topTab === 'events'))
                                    return;
                                if (!!(__VLS_ctx.topTab === 'scheduler'))
                                    return;
                                if (!!(__VLS_ctx.topTab === 'ursahooks'))
                                    return;
                                if (!!(__VLS_ctx.topTab === 'rag'))
                                    return;
                                if (!!(__VLS_ctx.topTab === 'research'))
                                    return;
                                if (!!(__VLS_ctx.topTab === 'cluster'))
                                    return;
                                if (!!(__VLS_ctx.topTab === 'addons'))
                                    return;
                                if (!!(__VLS_ctx.topTab === 'usage'))
                                    return;
                                if (!(__VLS_ctx.topTab === 'sessions'))
                                    return;
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
                var __VLS_117;
                const __VLS_118 = {}.VCard;
                /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
                // @ts-ignore
                const __VLS_119 = __VLS_asFunctionalComponent(__VLS_118, new __VLS_118({
                    title: (__VLS_ctx.$t('insights.process.engineParams')),
                }));
                const __VLS_120 = __VLS_119({
                    title: (__VLS_ctx.$t('insights.process.engineParams')),
                }, ...__VLS_functionalComponentArgsRest(__VLS_119));
                __VLS_121.slots.default;
                __VLS_asFunctionalElement(__VLS_intrinsicElements.pre, __VLS_intrinsicElements.pre)({
                    ...{ class: "json-block" },
                });
                (__VLS_ctx.asJson(__VLS_ctx.selectedProcess.engineParams));
                var __VLS_121;
                const __VLS_122 = {}.VCard;
                /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
                // @ts-ignore
                const __VLS_123 = __VLS_asFunctionalComponent(__VLS_122, new __VLS_122({
                    title: (__VLS_ctx.$t('insights.process.activeSkills')),
                }));
                const __VLS_124 = __VLS_123({
                    title: (__VLS_ctx.$t('insights.process.activeSkills')),
                }, ...__VLS_functionalComponentArgsRest(__VLS_123));
                __VLS_125.slots.default;
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
                var __VLS_125;
                const __VLS_126 = {}.VCard;
                /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
                // @ts-ignore
                const __VLS_127 = __VLS_asFunctionalComponent(__VLS_126, new __VLS_126({
                    title: (__VLS_ctx.$t('insights.process.pendingQueue')),
                }));
                const __VLS_128 = __VLS_127({
                    title: (__VLS_ctx.$t('insights.process.pendingQueue')),
                }, ...__VLS_functionalComponentArgsRest(__VLS_127));
                __VLS_129.slots.default;
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
                var __VLS_129;
            }
            else if (__VLS_ctx.activeTab === 'chat') {
                if (__VLS_ctx.chatState.loading.value) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: "opacity-70" },
                    });
                    (__VLS_ctx.$t('insights.process.chatLoading'));
                }
                else if (__VLS_ctx.chatState.messages.value.length === 0) {
                    const __VLS_130 = {}.VEmptyState;
                    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
                    // @ts-ignore
                    const __VLS_131 = __VLS_asFunctionalComponent(__VLS_130, new __VLS_130({
                        headline: (__VLS_ctx.$t('insights.process.chatEmptyHeadline')),
                        body: (__VLS_ctx.$t('insights.process.chatEmptyBody')),
                    }));
                    const __VLS_132 = __VLS_131({
                        headline: (__VLS_ctx.$t('insights.process.chatEmptyHeadline')),
                        body: (__VLS_ctx.$t('insights.process.chatEmptyBody')),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_131));
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
                                    [`chat-msg--strength-${__VLS_ctx.strengthTag(m.tags) ?? 'none'}`]: true,
                                }) },
                        });
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                            ...{ class: "flex items-center justify-between gap-2 text-xs opacity-60 mb-1" },
                        });
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                            ...{ class: "flex items-center gap-2" },
                        });
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                            ...{ class: "font-mono" },
                        });
                        (m.role);
                        if (__VLS_ctx.strengthTag(m.tags)) {
                            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                                ...{ class: "badge" },
                                ...{ class: (`badge--strength-${__VLS_ctx.strengthTag(m.tags)}`) },
                            });
                            (__VLS_ctx.strengthTag(m.tags));
                        }
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                        (__VLS_ctx.fmt(m.createdAt));
                        if (m.archivedInMemoryId) {
                            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                                ...{ class: "ml-2 opacity-80" },
                            });
                            (__VLS_ctx.$t('insights.process.archivedToMemory', { id: m.archivedInMemoryId }));
                        }
                        const __VLS_134 = {}.MarkdownView;
                        /** @type {[typeof __VLS_components.MarkdownView, ]} */ ;
                        // @ts-ignore
                        const __VLS_135 = __VLS_asFunctionalComponent(__VLS_134, new __VLS_134({
                            source: (m.content),
                        }));
                        const __VLS_136 = __VLS_135({
                            source: (m.content),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_135));
                        if (__VLS_ctx.otherTags(m.tags).length > 0) {
                            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                                ...{ class: "mt-2 flex flex-wrap gap-1 text-xs opacity-70" },
                            });
                            for (const [t] of __VLS_getVForSourceType((__VLS_ctx.otherTags(m.tags)))) {
                                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                                    key: (t),
                                    ...{ class: "badge badge--secondary font-mono" },
                                });
                                (t);
                            }
                        }
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
                    const __VLS_138 = {}.VEmptyState;
                    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
                    // @ts-ignore
                    const __VLS_139 = __VLS_asFunctionalComponent(__VLS_138, new __VLS_138({
                        headline: (__VLS_ctx.$t('insights.process.memoryEmptyHeadline')),
                        body: (__VLS_ctx.$t('insights.process.memoryEmptyBody')),
                    }));
                    const __VLS_140 = __VLS_139({
                        headline: (__VLS_ctx.$t('insights.process.memoryEmptyHeadline')),
                        body: (__VLS_ctx.$t('insights.process.memoryEmptyBody')),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_139));
                }
                else {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: "flex flex-col gap-3" },
                    });
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.label, __VLS_intrinsicElements.label)({
                        ...{ class: "flex items-center gap-2 text-sm opacity-80" },
                    });
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
                        type: "checkbox",
                    });
                    (__VLS_ctx.memoryPrakOnly);
                    (__VLS_ctx.$t('insights.process.prakOnlyToggle'));
                    for (const [m] of __VLS_getVForSourceType((__VLS_ctx.filteredMemoryEntries))) {
                        const __VLS_142 = {}.VCard;
                        /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
                        // @ts-ignore
                        const __VLS_143 = __VLS_asFunctionalComponent(__VLS_142, new __VLS_142({
                            key: (m.id),
                            title: (m.title || m.kind),
                        }));
                        const __VLS_144 = __VLS_143({
                            key: (m.id),
                            title: (m.title || m.kind),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_143));
                        __VLS_145.slots.default;
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                            ...{ class: "text-xs opacity-60 mb-2 flex flex-wrap gap-x-3 gap-y-1 items-center" },
                        });
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                            ...{ class: "font-mono" },
                        });
                        (m.kind);
                        if (__VLS_ctx.isPrakMemory(m.metadata)) {
                            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                                ...{ class: "badge badge--prak" },
                            });
                            (__VLS_ctx.$t('insights.process.prakBadge'));
                        }
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
                        if (__VLS_ctx.isPrakMemory(m.metadata)) {
                            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                                ...{ class: "prak-meta-row text-xs flex flex-wrap gap-x-3 gap-y-1 mb-2" },
                            });
                            for (const [v, k] of __VLS_getVForSourceType(([__VLS_ctx.extractPrakMeta(m.metadata)]))) {
                                (k);
                                if (v.type) {
                                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                                        ...{ class: "badge badge--secondary font-mono" },
                                    });
                                    (v.type);
                                }
                                if (v.importance != null) {
                                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                                    __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
                                    (v.importance);
                                }
                                if (v.confidence != null) {
                                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                                    __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
                                    (Math.round(v.confidence * 100));
                                }
                                if (v.decay) {
                                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                                    __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
                                    (v.decay);
                                }
                                for (const [label] of __VLS_getVForSourceType(((v.labels || [])))) {
                                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                                        key: (`lbl-${label}`),
                                        ...{ class: "badge badge--secondary" },
                                    });
                                    (label);
                                }
                            }
                        }
                        if (__VLS_ctx.isPrakMemory(m.metadata) && __VLS_ctx.extractPrakMeta(m.metadata).why) {
                            __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
                                ...{ class: "text-xs opacity-70 italic mb-2" },
                            });
                            (__VLS_ctx.extractPrakMeta(m.metadata).why);
                        }
                        const __VLS_146 = {}.MarkdownView;
                        /** @type {[typeof __VLS_components.MarkdownView, ]} */ ;
                        // @ts-ignore
                        const __VLS_147 = __VLS_asFunctionalComponent(__VLS_146, new __VLS_146({
                            source: (m.content),
                        }));
                        const __VLS_148 = __VLS_147({
                            source: (m.content),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_147));
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
                        var __VLS_145;
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
                    const __VLS_150 = {}.VEmptyState;
                    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
                    // @ts-ignore
                    const __VLS_151 = __VLS_asFunctionalComponent(__VLS_150, new __VLS_150({
                        headline: (__VLS_ctx.$t('insights.process.treeEmptyHeadline')),
                        body: (__VLS_ctx.$t('insights.process.treeEmptyBody')),
                    }));
                    const __VLS_152 = __VLS_151({
                        headline: (__VLS_ctx.$t('insights.process.treeEmptyHeadline')),
                        body: (__VLS_ctx.$t('insights.process.treeEmptyBody')),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_151));
                }
                else {
                    const __VLS_154 = {}.VCard;
                    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
                    // @ts-ignore
                    const __VLS_155 = __VLS_asFunctionalComponent(__VLS_154, new __VLS_154({
                        title: (__VLS_ctx.$t('insights.process.marvinTreeTitle')),
                    }));
                    const __VLS_156 = __VLS_155({
                        title: (__VLS_ctx.$t('insights.process.marvinTreeTitle')),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_155));
                    __VLS_157.slots.default;
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
                        ...{ class: "marvin-tree" },
                    });
                    for (const [root] of __VLS_getVForSourceType((__VLS_ctx.marvinTree))) {
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                            key: (root.doc.id),
                        });
                        /** @type {[typeof MarvinTreeItem, ]} */ ;
                        // @ts-ignore
                        const __VLS_158 = __VLS_asFunctionalComponent(MarvinTreeItem, new MarvinTreeItem({
                            ...{ 'onSelectProcess': {} },
                            node: (root),
                        }));
                        const __VLS_159 = __VLS_158({
                            ...{ 'onSelectProcess': {} },
                            node: (root),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_158));
                        let __VLS_161;
                        let __VLS_162;
                        let __VLS_163;
                        const __VLS_164 = {
                            onSelectProcess: (__VLS_ctx.clickProcessByMongoId)
                        };
                        var __VLS_160;
                    }
                    var __VLS_157;
                }
            }
            else if (__VLS_ctx.activeTab === 'llm-traces') {
                /** @type {[typeof LlmTraceTab, ]} */ ;
                // @ts-ignore
                const __VLS_165 = __VLS_asFunctionalComponent(LlmTraceTab, new LlmTraceTab({
                    processId: (__VLS_ctx.selectedProcess.id),
                }));
                const __VLS_166 = __VLS_165({
                    processId: (__VLS_ctx.selectedProcess.id),
                }, ...__VLS_functionalComponentArgsRest(__VLS_165));
            }
            else if (__VLS_ctx.activeTab === 'cache-stats') {
                /** @type {[typeof CacheStatsTab, ]} */ ;
                // @ts-ignore
                const __VLS_168 = __VLS_asFunctionalComponent(CacheStatsTab, new CacheStatsTab({
                    processId: (__VLS_ctx.selectedProcess.id),
                }));
                const __VLS_169 = __VLS_168({
                    processId: (__VLS_ctx.selectedProcess.id),
                }, ...__VLS_functionalComponentArgsRest(__VLS_168));
            }
            else if (__VLS_ctx.activeTab === 'prak-runs') {
                if (__VLS_ctx.prakRunsState.loading.value) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: "opacity-70" },
                    });
                    (__VLS_ctx.$t('insights.process.prakRunsLoading'));
                }
                else if (__VLS_ctx.prakRunsState.runs.value.length === 0) {
                    const __VLS_171 = {}.VEmptyState;
                    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
                    // @ts-ignore
                    const __VLS_172 = __VLS_asFunctionalComponent(__VLS_171, new __VLS_171({
                        headline: (__VLS_ctx.$t('insights.process.prakRunsEmptyHeadline')),
                        body: (__VLS_ctx.$t('insights.process.prakRunsEmptyBody')),
                    }));
                    const __VLS_173 = __VLS_172({
                        headline: (__VLS_ctx.$t('insights.process.prakRunsEmptyHeadline')),
                        body: (__VLS_ctx.$t('insights.process.prakRunsEmptyBody')),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_172));
                }
                else {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: "flex flex-col gap-3" },
                    });
                    for (const [r] of __VLS_getVForSourceType((__VLS_ctx.prakRunsState.runs.value))) {
                        const __VLS_175 = {}.VCard;
                        /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
                        // @ts-ignore
                        const __VLS_176 = __VLS_asFunctionalComponent(__VLS_175, new __VLS_175({
                            key: (r.id),
                            title: (r.trigger),
                        }));
                        const __VLS_177 = __VLS_176({
                            key: (r.id),
                            title: (r.trigger),
                        }, ...__VLS_functionalComponentArgsRest(__VLS_176));
                        __VLS_178.slots.default;
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                            ...{ class: "text-xs opacity-60 mb-3 flex flex-wrap gap-x-3 gap-y-1" },
                        });
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                        (__VLS_ctx.fmt(r.createdAt));
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                        (__VLS_ctx.$t('insights.process.prakRunDuration', { ms: r.durationMs }));
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                        (__VLS_ctx.$t('insights.process.prakRunSpan', { count: r.windowMessages }));
                        if (r.model) {
                            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                                ...{ class: "font-mono" },
                            });
                            (r.model);
                        }
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                            ...{ class: "font-mono opacity-50" },
                        });
                        (r.runId);
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                            ...{ class: "grid grid-cols-1 md:grid-cols-3 gap-3 text-sm" },
                        });
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.h4, __VLS_intrinsicElements.h4)({
                            ...{ class: "text-xs uppercase opacity-60 mb-1" },
                        });
                        (__VLS_ctx.$t('insights.process.prakRunSanitize'));
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dl, __VLS_intrinsicElements.dl)({
                            ...{ class: "grid grid-cols-2 gap-x-3 gap-y-0.5" },
                        });
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                            ...{ class: "opacity-60" },
                        });
                        (__VLS_ctx.$t('insights.process.prakRunRaw'));
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
                        (r.rawItemCount);
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                            ...{ class: "opacity-60" },
                        });
                        (__VLS_ctx.$t('insights.process.prakRunFinal'));
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
                        (r.finalItemCount);
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                            ...{ class: "opacity-60" },
                        });
                        (__VLS_ctx.$t('insights.process.prakRunDropped'));
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
                        (r.droppedNoEvidence);
                        (r.droppedLowConfidence);
                        (r.droppedBySupersedeWithinBatch);
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                            ...{ class: "opacity-60" },
                        });
                        (__VLS_ctx.$t('insights.process.prakRunDedup'));
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
                        (r.duplicatesMerged);
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                            ...{ class: "opacity-60" },
                        });
                        (__VLS_ctx.$t('insights.process.prakRunHardCap'));
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
                        (r.hardCapTriggered ? '⚠ yes' : 'no');
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                            ...{ class: "opacity-60" },
                        });
                        (__VLS_ctx.$t('insights.process.prakRunCoverage'));
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
                            ...{ class: ({ 'text-warning': r.lowCoverage }) },
                        });
                        (Math.round(r.evidenceCoverage * 100));
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.h4, __VLS_intrinsicElements.h4)({
                            ...{ class: "text-xs uppercase opacity-60 mb-1" },
                        });
                        (__VLS_ctx.$t('insights.process.prakRunStrength'));
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dl, __VLS_intrinsicElements.dl)({
                            ...{ class: "grid grid-cols-2 gap-x-3 gap-y-0.5" },
                        });
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                            ...{ class: "opacity-60" },
                        });
                        (__VLS_ctx.$t('insights.process.prakRunOverrides'));
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
                        (r.strengthOverrides);
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                            ...{ class: "opacity-60" },
                        });
                        (__VLS_ctx.$t('insights.process.prakRunTagsModified'));
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
                        (r.strengthTagsModified);
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.h4, __VLS_intrinsicElements.h4)({
                            ...{ class: "text-xs uppercase opacity-60 mb-1" },
                        });
                        (__VLS_ctx.$t('insights.process.prakRunPromotion'));
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dl, __VLS_intrinsicElements.dl)({
                            ...{ class: "grid grid-cols-2 gap-x-3 gap-y-0.5" },
                        });
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                            ...{ class: "opacity-60" },
                        });
                        (__VLS_ctx.$t('insights.process.prakRunPromoted'));
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
                        (r.promoted);
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                            ...{ class: "opacity-60" },
                        });
                        (__VLS_ctx.$t('insights.process.prakRunInboxOffered'));
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
                        (r.inboxOffered);
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                            ...{ class: "opacity-60" },
                        });
                        (__VLS_ctx.$t('insights.process.prakRunSkipped'));
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
                        (r.skipped);
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                            ...{ class: "opacity-60" },
                        });
                        (__VLS_ctx.$t('insights.process.prakRunRefreshed'));
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
                        (r.refreshed);
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                            ...{ class: "opacity-60" },
                        });
                        (__VLS_ctx.$t('insights.process.prakRunAffectsDeferred'));
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({});
                        (r.affectsDeferred);
                        if (r.persistedMemoryIds.length > 0) {
                            __VLS_asFunctionalElement(__VLS_intrinsicElements.details, __VLS_intrinsicElements.details)({
                                ...{ class: "mt-3" },
                            });
                            __VLS_asFunctionalElement(__VLS_intrinsicElements.summary, __VLS_intrinsicElements.summary)({
                                ...{ class: "text-xs opacity-70 cursor-pointer" },
                            });
                            (__VLS_ctx.$t('insights.process.prakRunPersistedMemories'));
                            (r.persistedMemoryIds.length);
                            __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
                                ...{ class: "mt-1 text-xs font-mono opacity-80 list-disc list-inside" },
                            });
                            for (const [mid] of __VLS_getVForSourceType((r.persistedMemoryIds))) {
                                __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                                    key: (mid),
                                });
                                (mid);
                            }
                        }
                        var __VLS_178;
                    }
                }
            }
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
        const __VLS_179 = {}.MarkdownView;
        /** @type {[typeof __VLS_components.MarkdownView, ]} */ ;
        // @ts-ignore
        const __VLS_180 = __VLS_asFunctionalComponent(__VLS_179, new __VLS_179({
            source: (__VLS_ctx.help.content.value),
        }));
        const __VLS_181 = __VLS_180({
            source: (__VLS_ctx.help.content.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_180));
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
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['p-6']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-5xl']} */ ;
/** @type {__VLS_StyleScopedClasses['tab-bar']} */ ;
/** @type {__VLS_StyleScopedClasses['tab-bar--overflow']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['tab']} */ ;
/** @type {__VLS_StyleScopedClasses['tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['tab-more']} */ ;
/** @type {__VLS_StyleScopedClasses['tab']} */ ;
/** @type {__VLS_StyleScopedClasses['tab-more__btn']} */ ;
/** @type {__VLS_StyleScopedClasses['tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['tab-more__menu']} */ ;
/** @type {__VLS_StyleScopedClasses['tab-more__item']} */ ;
/** @type {__VLS_StyleScopedClasses['tab-more__item--active']} */ ;
/** @type {__VLS_StyleScopedClasses['tab-bar']} */ ;
/** @type {__VLS_StyleScopedClasses['tab-bar--phantom']} */ ;
/** @type {__VLS_StyleScopedClasses['tab']} */ ;
/** @type {__VLS_StyleScopedClasses['tab']} */ ;
/** @type {__VLS_StyleScopedClasses['tab-more__btn']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['-mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['italic']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['session-header']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-baseline']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
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
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
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
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['badge']} */ ;
/** @type {__VLS_StyleScopedClasses['ml-2']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['badge']} */ ;
/** @type {__VLS_StyleScopedClasses['badge--secondary']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-3']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-y-1']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['badge']} */ ;
/** @type {__VLS_StyleScopedClasses['badge--prak']} */ ;
/** @type {__VLS_StyleScopedClasses['prak-meta-row']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-3']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-y-1']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['badge']} */ ;
/** @type {__VLS_StyleScopedClasses['badge--secondary']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['badge']} */ ;
/** @type {__VLS_StyleScopedClasses['badge--secondary']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['italic']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['json-block']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['marvin-tree']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-3']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-y-1']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-1']} */ ;
/** @type {__VLS_StyleScopedClasses['md:grid-cols-3']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-2']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-3']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-y-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-warning']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-2']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-3']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-y-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-2']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-3']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-y-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['list-disc']} */ ;
/** @type {__VLS_StyleScopedClasses['list-inside']} */ ;
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
            VButton: VButton,
            VCard: VCard,
            VEmptyState: VEmptyState,
            VInput: VInput,
            VSelect: VSelect,
            MarvinTreeItem: MarvinTreeItem,
            SessionTimelineTab: SessionTimelineTab,
            LiveToolsTab: LiveToolsTab,
            LlmTraceTab: LlmTraceTab,
            CacheStatsTab: CacheStatsTab,
            RecipesTab: RecipesTab,
            ProjectToolsTab: ProjectToolsTab,
            WorkspaceTab: WorkspaceTab,
            ExecutionsTab: ExecutionsTab,
            ClusterTab: ClusterTab,
            AddonsTab: AddonsTab,
            EventsTab: EventsTab,
            SchedulerTab: SchedulerTab,
            UrsahooksTab: UrsahooksTab,
            WorkflowsTab: WorkflowsTab,
            RagTab: RagTab,
            UsageCostTab: UsageCostTab,
            ZarniwoopTab: ZarniwoopTab,
            ChatRole: ChatRole,
            sessionsState: sessionsState,
            chatState: chatState,
            memoryState: memoryState,
            treeState: treeState,
            prakRunsState: prakRunsState,
            memoryPrakOnly: memoryPrakOnly,
            exportLoading: exportLoading,
            exportError: exportError,
            onExportSession: onExportSession,
            help: help,
            focusZone: focusZone,
            filterProjectId: filterProjectId,
            filterUserId: filterUserId,
            filterStatus: filterStatus,
            topTab: topTab,
            ALL_TABS: ALL_TABS,
            tabBarRef: tabBarRef,
            tabPhantomRef: tabPhantomRef,
            moreBtnRef: moreBtnRef,
            moreDropdownRef: moreDropdownRef,
            moreOpen: moreOpen,
            moreMenuPos: moreMenuPos,
            visibleTopTabs: visibleTopTabs,
            overflowTopTabs: overflowTopTabs,
            selectTopTab: selectTopTab,
            toggleMoreMenu: toggleMoreMenu,
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
            effectiveProjectId: effectiveProjectId,
            projectContextSource: projectContextSource,
            breadcrumbs: breadcrumbs,
            combinedError: combinedError,
            isPrakMemory: isPrakMemory,
            extractPrakMeta: extractPrakMeta,
            filteredMemoryEntries: filteredMemoryEntries,
            strengthTag: strengthTag,
            otherTags: otherTags,
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