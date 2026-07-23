<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  EditorShell,
  type FocusZone,
  MarkdownView,
  VAlert,
  VButton,
  VCard,
  VEmptyState,
  VInput,
  VSelect,
  type Crumb,
} from '@/components';
import { useTenantProjects } from '@/composables/useTenantProjects';
import {
  downloadSessionExport,
  useInsightsSessions,
  useSessionProcesses,
  useProcessDetail,
  useProcessChat,
  useProcessMemory,
  useMarvinTree,
  useProcessPrakRuns,
} from '@/composables/useInsights';
import {
  useInsightsNavigation,
  type InsightsTopTab,
} from '@/composables/useInsightsNavigation';
import { useHelp } from '@/composables/useHelp';
import MarvinTreeItem, { type MarvinTreeNode } from './MarvinTreeItem.vue';
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
import {
  ChatRole,
  type MarvinNodeInsightsDto,
  type SessionInsightsDto,
  type ThinkProcessInsightsDto,
} from '@vance/generated';

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
const exportError = ref<string | null>(null);

async function onExportSession(sessionId: string): Promise<void> {
  exportLoading.value = true;
  exportError.value = null;
  try {
    await downloadSessionExport(sessionId);
  } catch (e) {
    exportError.value = e instanceof Error ? e.message : t('insights.session.exportFailed');
  } finally {
    exportLoading.value = false;
  }
}
const help = useHelp();

// Focus zone — sidebar (filter + sessions tree), main (the
// selected session/process detail), right (help / docs). Driven by
// user clicks via @pointerdown on sidebar rows / main pane.
const focusZone = ref<FocusZone>('main');

// ─── Filter state ───────────────────────────────────────────────────────
const filterProjectId = ref<string | null>(null);
const filterUserId = ref<string>('');
const filterStatus = ref<string | null>(null);

// ─── Navigation state (URL/history-bound) ──────────────────────────────
// topTab (project-level pane), selection (session/process drill-down), and
// activeTab (sub-tab) are mirrored to the URL and pushed to the browser
// history by useInsightsNavigation, so Back steps up one drill-down level
// and a reload restores the view instead of dropping to the empty state.
const { topTab, selection, activeTab } = useInsightsNavigation();
type TopTab = InsightsTopTab;

// Top-tab inventory. Order is significant: tabs to the left stay
// visible in the bar, tabs to the right are the first to spill into
// the "More ▾" dropdown when the row would overflow. Most-used tabs
// (Sessions, Recipes, Tools) live left so they're always reachable
// without an extra click.
const ALL_TABS: ReadonlyArray<{ key: TopTab; label: string }> = [
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

const tabBarRef = ref<HTMLDivElement | null>(null);
const tabPhantomRef = ref<HTMLDivElement | null>(null);
const moreBtnRef = ref<HTMLButtonElement | null>(null);
const moreDropdownRef = ref<HTMLDivElement | null>(null);
const moreOpen = ref(false);
// Teleported dropdown lives in <body>, so it needs its own viewport
// coordinates. Recomputed every time the menu opens + on scroll/resize
// while open so it tracks the More button.
const moreMenuPos = ref<{ top: number; right: number }>({ top: 0, right: 0 });
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
    const active = ALL_TABS.find((t) => t.key === topTab.value)!;
    return [...visible.slice(0, -1), active];
  }
  return visible;
});

const overflowTopTabs = computed(() => {
  const visibleKeys = new Set(visibleTopTabs.value.map((t) => t.key));
  return ALL_TABS.filter((t) => !visibleKeys.has(t.key));
});

function recalcVisibleTabs(): void {
  const container = tabBarRef.value;
  const phantom = tabPhantomRef.value;
  if (!container || !phantom) return;

  const containerWidth = container.clientWidth;
  if (containerWidth <= 0) return;

  // Reserve room for the "More ▾" button — measure from phantom,
  // fall back to a conservative width if it hasn't rendered yet.
  const moreEl = phantom.querySelector<HTMLElement>('[data-more-phantom]');
  const moreWidth = moreEl?.offsetWidth ?? 80;
  const gap = 4;

  const tabEls = phantom.querySelectorAll<HTMLElement>('[data-tab-phantom]');
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
    if (used + w > budget) break;
    used += w;
    count++;
  }
  visibleCount.value = Math.max(1, count);
}

function selectTopTab(key: TopTab): void {
  topTab.value = key;
  moreOpen.value = false;
}

function toggleMoreMenu(): void {
  if (moreOpen.value) {
    moreOpen.value = false;
    return;
  }
  updateMoreMenuPosition();
  moreOpen.value = true;
}

/** Snap the teleported menu under the More button (right-aligned). */
function updateMoreMenuPosition(): void {
  const btn = moreBtnRef.value;
  if (!btn) return;
  const r = btn.getBoundingClientRect();
  moreMenuPos.value = {
    top: r.bottom + 4,
    right: Math.max(8, window.innerWidth - r.right),
  };
}

function handleMoreOutsideClick(e: MouseEvent): void {
  if (!moreOpen.value) return;
  const target = e.target as Node | null;
  if (target && moreBtnRef.value?.contains(target)) return;
  if (target && moreDropdownRef.value?.contains(target)) return;
  moreOpen.value = false;
}

function handleMoreReposition(): void {
  if (moreOpen.value) updateMoreMenuPosition();
}

let tabBarObserver: ResizeObserver | null = null;
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

// Status filter: blank hides CLOSED sessions (the default active view),
// "all" shows every status, "CLOSED" narrows to closed ones. The server
// interprets these values (see InsightsAdminController.listSessions).
const statusOptions = computed(() => [
  { value: '', label: t('insights.filters.active') },
  { value: 'all', label: t('insights.filters.all') },
  { value: 'CLOSED', label: t('insights.filters.closed') },
]);

// ─── Selection state ────────────────────────────────────────────────────
// `selection` + `activeTab` are provided by useInsightsNavigation above.

/** Sessions whose processes-list is open in the sidebar. Loaded lazily. */
const expanded = ref<Set<string>>(new Set());

/** Per-sessionId cache of processes — populated as sessions expand. */
const processesBySession = ref<Record<string, ThinkProcessInsightsDto[]>>({});

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

// Load the data behind a selection. The sub-tab reset to 'overview' lives
// in useInsightsNavigation (synchronous + restore-aware); here we only load.
watch(selection, async (sel) => {
  if (!sel) return;
  if (sel.kind === 'session') {
    // Make sure the processes for this session are loaded — they
    // populate the "Processes" tab.
    await ensureProcessesLoaded(sel.id);
  } else {
    await processDetailState.load(sel.id);
    chatState.clear();
    memoryState.clear();
    treeState.clear();
    prakRunsState.clear();
  }
});

watch(activeTab, (tab) => {
  if (!selection.value || selection.value.kind !== 'process') return;
  const id = selection.value.id;
  if (tab === 'chat' && chatState.messages.value.length === 0) {
    void chatState.load(id);
  } else if (tab === 'memory' && memoryState.entries.value.length === 0) {
    void memoryState.load(id);
  } else if (tab === 'tree' && treeState.nodes.value.length === 0) {
    void treeState.load(id);
  } else if (tab === 'prak-runs' && prakRunsState.runs.value.length === 0) {
    void prakRunsState.load(id);
  }
});

async function reloadSessions(): Promise<void> {
  await sessionsState.reload({
    projectId: filterProjectId.value,
    userId: filterUserId.value.trim() || null,
    status: filterStatus.value,
  });
}

async function ensureProcessesLoaded(sessionId: string): Promise<void> {
  if (processesBySession.value[sessionId]) return;
  await processesState.load(sessionId);
  // useSessionProcesses is single-shot per call; capture into per-session map.
  processesBySession.value = {
    ...processesBySession.value,
    [sessionId]: [...processesState.processes.value],
  };
}

// ─── Sidebar interactions ───────────────────────────────────────────────

async function toggleExpand(sessionId: string): Promise<void> {
  if (expanded.value.has(sessionId)) {
    expanded.value.delete(sessionId);
    expanded.value = new Set(expanded.value);
  } else {
    await ensureProcessesLoaded(sessionId);
    expanded.value.add(sessionId);
    expanded.value = new Set(expanded.value);
  }
}

function selectSession(s: SessionInsightsDto): void {
  selection.value = { kind: 'session', id: s.sessionId };
}

function selectProcess(p: ThinkProcessInsightsDto): void {
  selection.value = { kind: 'process', id: p.id };
}

function isSelectedSession(s: SessionInsightsDto): boolean {
  const sel = selection.value;
  return sel?.kind === 'session' && sel.id === s.sessionId;
}

function isSelectedProcess(p: ThinkProcessInsightsDto): boolean {
  const sel = selection.value;
  return sel?.kind === 'process' && sel.id === p.id;
}

// ─── Derived ────────────────────────────────────────────────────────────

const selectedSession = computed<SessionInsightsDto | null>(() => {
  const sel = selection.value;
  if (sel?.kind !== 'session') return null;
  return sessionsState.sessions.value.find(s => s.sessionId === sel.id) ?? null;
});

const selectedProcess = computed<ThinkProcessInsightsDto | null>(() =>
  processDetailState.process.value);

const sessionProcessesForTab = computed<ThinkProcessInsightsDto[]>(() => {
  const sel = selection.value;
  if (sel?.kind !== 'session') return [];
  return processesBySession.value[sel.id] ?? [];
});

const isMarvin = computed(() =>
  (selectedProcess.value?.thinkEngine ?? '').toLowerCase() === 'marvin');

/**
 * Project id the Recipes / Tools / Workspace top-tabs should show.
 * A picked session (or process) overrides the sidebar filter so the
 * user can pivot from a session straight to its project's effective
 * config without re-selecting the project. Falls back to the explicit
 * sidebar filter when nothing is selected.
 */
const effectiveProjectId = computed<string | null>(() => {
  const sel = selection.value;
  if (sel?.kind === 'session') {
    const s = sessionsState.sessions.value.find(x => x.sessionId === sel.id);
    if (s?.projectId) return s.projectId;
  } else if (sel?.kind === 'process') {
    const p = selectedProcess.value;
    if (p) {
      const s = sessionsState.sessions.value.find(x => x.sessionId === p.sessionId);
      if (s?.projectId) return s.projectId;
    }
  }
  return filterProjectId.value;
});

/** Source label for the project-context hint shown above project-tabs. */
const projectContextSource = computed<{ kind: 'session' | 'process' | 'filter'; label: string } | null>(() => {
  const sel = selection.value;
  if (sel?.kind === 'session') {
    const s = sessionsState.sessions.value.find(x => x.sessionId === sel.id);
    if (s) return { kind: 'session', label: s.firstUserMessage || s.sessionId };
  } else if (sel?.kind === 'process') {
    const p = selectedProcess.value;
    if (p) return { kind: 'process', label: p.name };
  }
  return null;
});

function sessionLabel(sessionId: string): string {
  // Prefer the denormalised topic when we already have it cached;
  // otherwise fall back to the raw id.
  const s = sessionsState.sessions.value.find(x => x.sessionId === sessionId);
  const topic = s?.firstUserMessage;
  const label =
    topic && topic.length > 0
      ? topic.length > 60
        ? topic.slice(0, 59) + '…'
        : topic
      : sessionId;
  return t('insights.breadcrumbs.sessionPrefix', { label });
}

/** Label for a process sub-tab, used as the breadcrumb leaf. */
function processSubTabLabel(tab: string): string {
  switch (tab) {
    case 'chat': return t('insights.tabs.chat');
    case 'memory': return t('insights.tabs.memory');
    case 'tree': return t('insights.tabs.tree');
    case 'llm-traces': return t('insights.tabs.llmTrace');
    case 'cache-stats': return t('insights.tabs.cacheStats');
    case 'prak-runs': return t('insights.tabs.prakRuns');
    default: return t('insights.tabs.overview');
  }
}

const breadcrumbs = computed<Crumb[]>(() => {
  // Non-session top tabs are standalone project-scoped panes — surface the
  // tab name so the crumb bar reflects the pane actually on screen.
  if (topTab.value !== 'sessions') {
    return [ALL_TABS.find(tt => tt.key === topTab.value)?.label ?? topTab.value];
  }

  const sel = selection.value;
  if (!sel) return [];

  // Root crumb walks back to the sessions list (clears the selection).
  const root: Crumb = {
    text: t('insights.breadcrumbs.sessionsRoot'),
    onClick: () => { selection.value = null; },
  };

  if (sel.kind === 'session') return [root, sessionLabel(sel.id)];

  const p = selectedProcess.value;
  if (!p) return [root, t('insights.breadcrumbs.processFallback')];

  // Session crumb navigates back to the owning session — the most common
  // "go up one level" gesture.
  const sessionCrumb: Crumb = {
    text: sessionLabel(p.sessionId),
    onClick: () => { selection.value = { kind: 'session', id: p.sessionId }; },
  };
  const processLabel = t('insights.breadcrumbs.processPrefix', { name: p.name });

  // A deep sub-tab (Memory, Chat, Tree, …) becomes the leaf crumb so the
  // user always sees a labelled path back out; the process crumb turns
  // clickable to hop to its Overview.
  if (activeTab.value !== 'overview') {
    return [
      root,
      sessionCrumb,
      { text: processLabel, onClick: () => { activeTab.value = 'overview'; } },
      processSubTabLabel(activeTab.value),
    ];
  }
  return [root, sessionCrumb, processLabel];
});

const combinedError = computed<string | null>(() =>
  sessionsState.error.value
  || processesState.error.value
  || processDetailState.error.value
  || chatState.error.value
  || memoryState.error.value
  || treeState.error.value
  || prakRunsState.error.value);

// ─── Memory tab — Prak-aware helpers ───────────────────────────────────

function isPrakMemory(meta: Record<string, unknown> | undefined): boolean {
  return meta?.['generatedBy'] === 'prak';
}

interface PrakMemoryMeta {
  type?: string;
  importance?: number;
  confidence?: number;
  labels?: string[];
  decay?: string;
  why?: string;
  runId?: string;
}

function extractPrakMeta(meta: Record<string, unknown>): PrakMemoryMeta {
  const labels = meta['prakLabels'];
  return {
    type: typeof meta['prakType'] === 'string' ? meta['prakType'] as string : undefined,
    importance: typeof meta['prakImportance'] === 'number' ? meta['prakImportance'] as number : undefined,
    confidence: typeof meta['prakConfidence'] === 'number' ? meta['prakConfidence'] as number : undefined,
    labels: Array.isArray(labels) ? labels.filter((x): x is string => typeof x === 'string') : undefined,
    decay: typeof meta['prakDecay'] === 'string' ? meta['prakDecay'] as string : undefined,
    why: typeof meta['prakWhy'] === 'string' ? meta['prakWhy'] as string : undefined,
    runId: typeof meta['prakRunId'] === 'string' ? meta['prakRunId'] as string : undefined,
  };
}

const filteredMemoryEntries = computed(() => {
  const all = memoryState.entries.value;
  if (!memoryPrakOnly.value) return all;
  return all.filter(m => isPrakMemory(m.metadata as Record<string, unknown> | undefined));
});

// ─── Chat tab — STRENGTH:* tag helpers ────────────────────────────────

const STRENGTH_PREFIX = 'STRENGTH:';

function strengthTag(tags: string[] | undefined): string | null {
  if (!tags) return null;
  for (const t of tags) {
    if (t.startsWith(STRENGTH_PREFIX)) return t.substring(STRENGTH_PREFIX.length);
  }
  return null;
}

function otherTags(tags: string[] | undefined): string[] {
  if (!tags) return [];
  return tags.filter(t => !t.startsWith(STRENGTH_PREFIX));
}

// ─── Marvin tree → nested rendering ─────────────────────────────────────

const marvinTree = computed<MarvinTreeNode[]>(() => {
  const all = treeState.nodes.value;
  const byParent: Record<string, MarvinNodeInsightsDto[]> = {};
  for (const n of all) {
    const key = n.parentId ?? '';
    (byParent[key] ??= []).push(n);
  }
  for (const list of Object.values(byParent)) {
    list.sort((a, b) => a.position - b.position);
  }
  function build(parentId: string | null): MarvinTreeNode[] {
    return (byParent[parentId ?? ''] ?? []).map(doc => ({
      doc,
      children: build(doc.id),
    }));
  }
  return build(null);
});

// ─── Helpers ────────────────────────────────────────────────────────────

function fmt(value: unknown): string {
  if (value == null) return '—';
  if (value instanceof Date) return value.toISOString();
  return String(value);
}

function asJson(obj: unknown): string {
  if (obj == null) return '';
  try {
    return JSON.stringify(obj, null, 2);
  } catch {
    return String(obj);
  }
}

function clickProcessByMongoId(id: string | undefined | null): void {
  if (!id) return;
  selection.value = { kind: 'process', id };
}
</script>

<template>
  <EditorShell
    v-model:focus-zone="focusZone"
    :title="$t('insights.pageTitle')"
    :breadcrumbs="breadcrumbs"
    :full-height="true"
    :show-sidebar="true"
    :show-right-panel="true"
    focus-model="auto"
    title-clickable
    wide-right-panel
    @title-click="focusZone = 'sidebar'"
  >
    <!-- ─── Sidebar: filter + sessions tree ─── -->
    <template #sidebar>
      <div class="flex flex-col gap-3 p-2">
        <div class="flex flex-col gap-2">
          <VSelect
            :model-value="filterProjectId ?? ''"
            :options="projectFilterOptions"
            :label="$t('insights.filters.project')"
            @update:model-value="(v) => filterProjectId = (v ? String(v) : null)"
          />
          <VInput
            v-model="filterUserId"
            :label="$t('insights.filters.user')"
            :placeholder="$t('insights.filters.userPlaceholder')"
          />
          <VSelect
            :model-value="filterStatus ?? ''"
            :options="statusOptions"
            :label="$t('insights.filters.status')"
            @update:model-value="(v) => filterStatus = (v ? String(v) : null)"
          />
        </div>

        <div v-if="sessionsState.loading.value" class="text-xs opacity-60 px-2">
          {{ $t('insights.sidebar.loadingSessions') }}
        </div>
        <VEmptyState
          v-else-if="sessionsState.sessions.value.length === 0"
          :headline="$t('insights.sidebar.noSessionsHeadline')"
          :body="$t('insights.sidebar.noSessionsBody')"
        />

        <nav class="flex flex-col gap-1">
          <template v-for="s in sessionsState.sessions.value" :key="s.id">
            <div class="session-row">
              <button
                type="button"
                class="chev"
                @click.stop="toggleExpand(s.sessionId)"
              >{{ expanded.has(s.sessionId) ? '▾' : '▸' }}</button>
              <button
                type="button"
                class="session-label"
                :class="{ 'session-label--active': isSelectedSession(s) }"
                :title="s.firstUserMessage ?? s.sessionId"
                @pointerdown.stop="focusZone = 'main'"
                @click="selectSession(s)"
              >
                <div class="flex items-center justify-between gap-2">
                  <span class="session-topic truncate">
                    {{ s.firstUserMessage || s.sessionId }}
                  </span>
                  <span
                    class="text-xs px-1.5 py-0.5 rounded shrink-0"
                    :class="s.status === 'OPEN' ? 'badge-open' : 'badge-closed'"
                  >{{ s.status?.toLowerCase() }}</span>
                </div>
                <div class="text-xs opacity-60 truncate">
                  {{ s.userId }} · {{ s.projectId }}
                  <span v-if="s.processCount != null">· {{ s.processCount }} proc</span>
                </div>
                <div
                  v-if="s.lastMessagePreview"
                  class="text-xs opacity-60 truncate mt-0.5"
                  :title="s.lastMessagePreview"
                >
                  <span class="opacity-70">{{ s.lastMessageRole?.toLowerCase() }}:</span>
                  {{ s.lastMessagePreview }}
                </div>
              </button>
            </div>
            <div v-if="expanded.has(s.sessionId)" class="session-children">
              <button
                v-for="p in (processesBySession[s.sessionId] ?? [])"
                :key="p.id"
                type="button"
                class="process-row"
                :class="{ 'process-row--active': isSelectedProcess(p) }"
                @click="selectProcess(p)"
              >
                <div class="flex items-center justify-between gap-2">
                  <span class="font-mono text-sm truncate">{{ p.name }}</span>
                  <span class="text-xs opacity-60">{{ p.thinkEngine }}</span>
                </div>
                <div class="text-xs opacity-60 truncate">
                  {{ p.status?.toLowerCase() }}
                  <span v-if="p.recipeName">· {{ p.recipeName }}</span>
                </div>
              </button>
            </div>
          </template>
        </nav>

        <VButton
          v-if="sessionsState.hasMore.value"
          variant="ghost"
          size="sm"
          class="self-center mt-1"
          :loading="sessionsState.loadingMore.value"
          :disabled="sessionsState.loadingMore.value"
          @click="sessionsState.loadMore()"
        >
          {{ $t('insights.sidebar.loadMore') }}
        </VButton>
      </div>
    </template>

    <!-- ─── Main pane ───
         {@code full-height} on EditorShell pins the main cell to
         the viewport; the long timeline / chat tabs need their own
         scroll container so they don't push past the bottom edge. -->
    <div class="h-full min-h-0 overflow-y-auto">
      <div class="p-6 flex flex-col gap-3 max-w-5xl">
      <VAlert v-if="combinedError" variant="error">
        <span>{{ combinedError }}</span>
      </VAlert>

      <!-- Project-level top tab bar — Sessions stays the default
           selection-driven view; Recipes / Tools take the active
           filter project and render the cascade-resolved list.
           Overflow handling: tabs that don't fit horizontally
           collapse into a "More ▾" dropdown driven by ResizeObserver
           (see recalcVisibleTabs). -->
      <div
        ref="tabBarRef"
        class="tab-bar tab-bar--overflow mb-1"
      >
        <button
          v-for="tab in visibleTopTabs"
          :key="tab.key"
          class="tab"
          :class="{ 'tab--active': topTab === tab.key }"
          @click="selectTopTab(tab.key)"
        >{{ tab.label }}</button>
        <div
          v-if="overflowTopTabs.length > 0"
          class="tab-more"
        >
          <button
            ref="moreBtnRef"
            class="tab tab-more__btn"
            :class="{ 'tab--active': overflowTopTabs.some((t) => t.key === topTab) }"
            @click.stop="toggleMoreMenu"
          >More ▾</button>
        </div>
      </div>

      <!-- Dropdown teleported to body so the parent .tab-bar's
           overflow: hidden (needed to keep the row clean during
           recalc) doesn't clip the menu. Positioned via fixed coords
           recomputed from moreBtnRef's bounding rect on open + on
           scroll/resize. -->
      <Teleport to="body">
        <div
          v-if="moreOpen && overflowTopTabs.length > 0"
          ref="moreDropdownRef"
          class="tab-more__menu"
          :style="{ top: `${moreMenuPos.top}px`, right: `${moreMenuPos.right}px` }"
        >
          <button
            v-for="tab in overflowTopTabs"
            :key="tab.key"
            class="tab-more__item"
            :class="{ 'tab-more__item--active': topTab === tab.key }"
            @click="selectTopTab(tab.key)"
          >{{ tab.label }}</button>
        </div>
      </Teleport>

      <!-- Hidden measurement strip — carries every tab + the More
           button at their natural width so recalcVisibleTabs() can
           decide how many fit in the real bar. Position absolute
           keeps it out of layout; aria-hidden + tabindex stop screen
           readers and keyboard focus from reaching it. -->
      <div
        ref="tabPhantomRef"
        class="tab-bar tab-bar--phantom"
        aria-hidden="true"
      >
        <button
          v-for="tab in ALL_TABS"
          :key="`phantom-${tab.key}`"
          class="tab"
          tabindex="-1"
          data-tab-phantom
        >{{ tab.label }}</button>
        <button
          class="tab tab-more__btn"
          tabindex="-1"
          data-more-phantom
        >More ▾</button>
      </div>

      <div
        v-if="topTab !== 'sessions' && topTab !== 'cluster' && topTab !== 'addons' && topTab !== 'usage' && projectContextSource"
        class="text-xs opacity-70 -mt-1 mb-1"
      >
        Showing
        <span class="font-mono">{{ effectiveProjectId ?? '—' }}</span>
        <span class="opacity-60">
          (from {{ projectContextSource.kind }}
          <span class="italic">{{ projectContextSource.label }}</span>)
        </span>
      </div>

      <RecipesTab v-if="topTab === 'recipes'" :project-id="effectiveProjectId" />
      <ProjectToolsTab v-else-if="topTab === 'tools'" :project-id="effectiveProjectId" />
      <WorkspaceTab v-else-if="topTab === 'workspace'" :project-id="effectiveProjectId" />
      <ExecutionsTab v-else-if="topTab === 'executions'" :project-id="effectiveProjectId" />
      <WorkflowsTab v-else-if="topTab === 'workflows'" :project-id="effectiveProjectId" />
      <EventsTab v-else-if="topTab === 'events'" :project-id="effectiveProjectId" />
      <SchedulerTab v-else-if="topTab === 'scheduler'" :project-id="effectiveProjectId" />
      <UrsahooksTab v-else-if="topTab === 'ursahooks'" :project-id="effectiveProjectId" />
      <RagTab v-else-if="topTab === 'rag'" :project-id="effectiveProjectId" />
      <ZarniwoopTab v-else-if="topTab === 'research'" :project-id="effectiveProjectId" />
      <ClusterTab v-else-if="topTab === 'cluster'" />
      <AddonsTab v-else-if="topTab === 'addons'" />
      <UsageCostTab v-else-if="topTab === 'usage'" />

      <template v-else-if="topTab === 'sessions'">
        <VEmptyState
          v-if="!selection"
          :headline="$t('insights.emptyMain.headline')"
          :body="$t('insights.emptyMain.body')"
        />

        <!-- ─── Session view ─── -->
        <template v-else-if="selection.kind === 'session'">
        <div v-if="!selectedSession" class="opacity-70">{{ $t('insights.loading') }}</div>
        <template v-else>
          <!-- Session header — always visible across Overview / Processes /
               Timeline tabs so the user keeps the "what session am I in"
               context after switching. -->
          <header class="session-header">
            <div class="flex items-baseline gap-2 flex-wrap justify-between">
              <div class="flex items-baseline gap-2 flex-wrap">
                <span class="font-mono text-sm opacity-70">{{ selectedSession.sessionId }}</span>
                <span
                  class="text-xs px-1.5 py-0.5 rounded"
                  :class="selectedSession.status === 'OPEN' ? 'badge-open' : 'badge-closed'"
                >{{ selectedSession.status?.toLowerCase() }}</span>
                <span class="text-xs opacity-60">
                  {{ selectedSession.userId }} · {{ selectedSession.projectId }}
                </span>
              </div>
              <VButton
                variant="ghost"
                size="sm"
                :loading="exportLoading"
                :disabled="exportLoading"
                :title="$t('insights.session.exportTooltip')"
                @click="onExportSession(selectedSession.sessionId)"
              >
                {{ $t('insights.session.exportButton') }}
              </VButton>
            </div>
            <VAlert v-if="exportError" variant="error" class="mt-2">
              <span>{{ exportError }}</span>
            </VAlert>
            <h2 v-if="selectedSession.firstUserMessage" class="session-topic-title">
              {{ selectedSession.firstUserMessage }}
            </h2>
            <div v-if="selectedSession.lastMessagePreview" class="text-xs opacity-70 mt-1">
              <span class="opacity-70">{{ $t('insights.session.lastLabel') }}</span>
              <span v-if="selectedSession.lastMessageRole">
                · {{ selectedSession.lastMessageRole.toLowerCase() }}
              </span>
              <span v-if="selectedSession.lastMessageAt">
                · {{ fmt(selectedSession.lastMessageAt) }}
              </span>
              <span class="block opacity-90 truncate" :title="selectedSession.lastMessagePreview">
                {{ selectedSession.lastMessagePreview }}
              </span>
            </div>
          </header>

          <div class="tab-bar">
            <button
              class="tab"
              :class="{ 'tab--active': activeTab === 'overview' }"
              @click="activeTab = 'overview'"
            >{{ $t('insights.tabs.overview') }}</button>
            <button
              class="tab"
              :class="{ 'tab--active': activeTab === 'processes' }"
              @click="activeTab = 'processes'"
            >{{ $t('insights.tabs.processes', { count: sessionProcessesForTab.length }) }}</button>
            <button
              class="tab"
              :class="{ 'tab--active': activeTab === 'timeline' }"
              @click="activeTab = 'timeline'"
            >{{ $t('insights.tabs.timeline') }}</button>
            <button
              class="tab"
              :class="{ 'tab--active': activeTab === 'live-tools' }"
              @click="activeTab = 'live-tools'"
            >Live Tools</button>
          </div>

          <VCard v-if="activeTab === 'overview'" :title="$t('insights.session.detailsTitle')">
            <dl class="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
              <dt class="opacity-60">{{ $t('insights.session.mongoId') }}</dt>
              <dd class="font-mono">{{ selectedSession.id }}</dd>
              <dt class="opacity-60">{{ $t('insights.session.user') }}</dt>
              <dd>{{ selectedSession.userId }}</dd>
              <dt class="opacity-60">{{ $t('insights.session.project') }}</dt>
              <dd>{{ selectedSession.projectId }}</dd>
              <dt class="opacity-60">{{ $t('insights.session.status') }}</dt>
              <dd>{{ selectedSession.status }}</dd>
              <dt class="opacity-60">{{ $t('insights.session.client') }}</dt>
              <dd>{{ selectedSession.profile }} {{ selectedSession.clientVersion }}</dd>
              <dt class="opacity-60">{{ $t('insights.session.boundConn') }}</dt>
              <dd class="font-mono text-xs">{{ fmt(selectedSession.boundConnectionId) }}</dd>
              <dt class="opacity-60">{{ $t('insights.session.chatProcess') }}</dt>
              <dd>
                <button
                  v-if="selectedSession.chatProcessId"
                  class="link"
                  @click="clickProcessByMongoId(selectedSession.chatProcessId)"
                >{{ selectedSession.chatProcessId }}</button>
                <span v-else>—</span>
              </dd>
              <dt class="opacity-60">{{ $t('insights.session.created') }}</dt>
              <dd>{{ fmt(selectedSession.createdAt) }}</dd>
              <dt class="opacity-60">{{ $t('insights.session.lastActivity') }}</dt>
              <dd>{{ fmt(selectedSession.lastActivityAt) }}</dd>
            </dl>
          </VCard>

          <VCard v-if="activeTab === 'processes'" :title="$t('insights.session.processesTitle')">
            <div v-if="sessionProcessesForTab.length === 0" class="opacity-70">
              {{ $t('insights.session.noProcesses') }}
            </div>
            <ul v-else class="flex flex-col divide-y divide-base-300">
              <li
                v-for="p in sessionProcessesForTab"
                :key="p.id"
                class="py-2 flex items-center justify-between gap-3 cursor-pointer hover:bg-base-200/40 px-2 rounded"
                @click="selectProcess(p)"
              >
                <div class="flex flex-col">
                  <span class="font-mono text-sm">{{ p.name }}</span>
                  <span class="text-xs opacity-60">
                    {{ p.thinkEngine }}
                    <span v-if="p.recipeName">· {{ p.recipeName }}</span>
                    <span v-if="p.title">· {{ p.title }}</span>
                  </span>
                </div>
                <span class="text-xs opacity-70">{{ p.status }}</span>
              </li>
            </ul>
          </VCard>

          <SessionTimelineTab
            v-if="activeTab === 'timeline'"
            :processes="sessionProcessesForTab"
            @select-process="clickProcessByMongoId"
          />

          <LiveToolsTab
            v-if="activeTab === 'live-tools'"
            :session-id="selectedSession.sessionId"
          />
        </template>
      </template>

      <!-- ─── Process view ─── -->
      <template v-else-if="selection.kind === 'process'">
        <div v-if="!selectedProcess" class="opacity-70">{{ $t('insights.loading') }}</div>
        <template v-else>
          <div class="tab-bar">
            <button
              class="tab"
              :class="{ 'tab--active': activeTab === 'overview' }"
              @click="activeTab = 'overview'"
            >{{ $t('insights.tabs.overview') }}</button>
            <button
              class="tab"
              :class="{ 'tab--active': activeTab === 'chat' }"
              @click="activeTab = 'chat'"
            >{{ $t('insights.tabs.chat') }}</button>
            <button
              class="tab"
              :class="{ 'tab--active': activeTab === 'memory' }"
              @click="activeTab = 'memory'"
            >{{ $t('insights.tabs.memory') }}</button>
            <button
              v-if="isMarvin"
              class="tab"
              :class="{ 'tab--active': activeTab === 'tree' }"
              @click="activeTab = 'tree'"
            >{{ $t('insights.tabs.tree') }}</button>
            <button
              class="tab"
              :class="{ 'tab--active': activeTab === 'llm-traces' }"
              @click="activeTab = 'llm-traces'"
            >{{ $t('insights.tabs.llmTrace') }}</button>
            <button
              class="tab"
              :class="{ 'tab--active': activeTab === 'cache-stats' }"
              @click="activeTab = 'cache-stats'"
            >{{ $t('insights.tabs.cacheStats') }}</button>
            <button
              class="tab"
              :class="{ 'tab--active': activeTab === 'prak-runs' }"
              @click="activeTab = 'prak-runs'"
            >{{ $t('insights.tabs.prakRuns') }}</button>
          </div>

          <!-- Overview -->
          <template v-if="activeTab === 'overview'">
            <VCard :title="$t('insights.process.titlePrefix', { name: selectedProcess.name })">
              <dl class="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
                <dt class="opacity-60">{{ $t('insights.process.mongoId') }}</dt>
                <dd class="font-mono text-xs">{{ selectedProcess.id }}</dd>
                <dt class="opacity-60">{{ $t('insights.process.session') }}</dt>
                <dd>{{ selectedProcess.sessionId }}</dd>
                <dt class="opacity-60">{{ $t('insights.process.engine') }}</dt>
                <dd>{{ selectedProcess.thinkEngine }} <span class="opacity-60">{{ selectedProcess.thinkEngineVersion }}</span></dd>
                <dt class="opacity-60">{{ $t('insights.process.recipe') }}</dt>
                <dd>{{ fmt(selectedProcess.recipeName) }}</dd>
                <dt class="opacity-60">{{ $t('insights.process.status') }}</dt>
                <dd>{{ selectedProcess.status }}</dd>
                <dt class="opacity-60">{{ $t('insights.process.parent') }}</dt>
                <dd>
                  <button
                    v-if="selectedProcess.parentProcessId"
                    class="link"
                    @click="clickProcessByMongoId(selectedProcess.parentProcessId)"
                  >{{ selectedProcess.parentProcessId }}</button>
                  <span v-else>—</span>
                </dd>
                <dt class="opacity-60">{{ $t('insights.process.goal') }}</dt>
                <dd>{{ fmt(selectedProcess.goal) }}</dd>
                <dt class="opacity-60">{{ $t('insights.process.created') }}</dt>
                <dd>{{ fmt(selectedProcess.createdAt) }}</dd>
                <dt class="opacity-60">{{ $t('insights.process.updated') }}</dt>
                <dd>{{ fmt(selectedProcess.updatedAt) }}</dd>
              </dl>
            </VCard>

            <VCard :title="$t('insights.process.engineParams')">
              <pre class="json-block">{{ asJson(selectedProcess.engineParams) }}</pre>
            </VCard>

            <VCard :title="$t('insights.process.activeSkills')">
              <div v-if="selectedProcess.activeSkills.length === 0" class="opacity-70">
                {{ $t('insights.process.noneActive') }}
              </div>
              <ul v-else class="flex flex-col divide-y divide-base-300">
                <li
                  v-for="(a, idx) in selectedProcess.activeSkills"
                  :key="'skl-' + idx"
                  class="py-2 flex items-center justify-between gap-2"
                >
                  <span class="font-mono text-sm">{{ a.name }}</span>
                  <span class="text-xs opacity-70">
                    {{ a.resolvedFromScope }}
                    <span v-if="a.fromRecipe">· {{ $t('insights.process.fromRecipe') }}</span>
                    <span v-if="a.oneShot">· {{ $t('insights.process.oneShot') }}</span>
                  </span>
                </li>
              </ul>
            </VCard>

            <VCard :title="$t('insights.process.pendingQueue')">
              <div v-if="selectedProcess.pendingMessages.length === 0" class="opacity-70">
                {{ $t('insights.process.drained') }}
              </div>
              <ul v-else class="flex flex-col divide-y divide-base-300">
                <li
                  v-for="(m, idx) in selectedProcess.pendingMessages"
                  :key="'pm-' + idx"
                  class="py-2"
                >
                  <div class="flex items-center justify-between gap-2 text-sm">
                    <span class="font-mono">{{ m.type }}</span>
                    <span class="opacity-60 text-xs">{{ fmt(m.at) }}</span>
                  </div>
                  <pre class="json-block">{{ asJson(m.payload) }}</pre>
                </li>
              </ul>
            </VCard>
          </template>

          <!-- Chat -->
          <template v-else-if="activeTab === 'chat'">
            <div v-if="chatState.loading.value" class="opacity-70">
              {{ $t('insights.process.chatLoading') }}
            </div>
            <VEmptyState
              v-else-if="chatState.messages.value.length === 0"
              :headline="$t('insights.process.chatEmptyHeadline')"
              :body="$t('insights.process.chatEmptyBody')"
            />
            <ul v-else class="flex flex-col gap-3">
              <li
                v-for="m in chatState.messages.value"
                :key="m.id"
                class="chat-msg"
                :class="{
                  'chat-msg--user': m.role === ChatRole.USER,
                  'chat-msg--assistant': m.role === ChatRole.ASSISTANT,
                  'chat-msg--system': m.role === ChatRole.SYSTEM,
                  'chat-msg--archived': m.archivedInMemoryId,
                  [`chat-msg--strength-${strengthTag(m.tags) ?? 'none'}`]: true,
                }"
              >
                <div class="flex items-center justify-between gap-2 text-xs opacity-60 mb-1">
                  <span class="flex items-center gap-2">
                    <span class="font-mono">{{ m.role }}</span>
                    <span
                      v-if="strengthTag(m.tags)"
                      class="badge"
                      :class="`badge--strength-${strengthTag(m.tags)}`"
                    >{{ strengthTag(m.tags) }}</span>
                  </span>
                  <span>
                    {{ fmt(m.createdAt) }}
                    <span v-if="m.archivedInMemoryId" class="ml-2 opacity-80">
                      {{ $t('insights.process.archivedToMemory', { id: m.archivedInMemoryId }) }}
                    </span>
                  </span>
                </div>
                <MarkdownView :source="m.content" />
                <div
                  v-if="otherTags(m.tags).length > 0"
                  class="mt-2 flex flex-wrap gap-1 text-xs opacity-70"
                >
                  <span
                    v-for="t in otherTags(m.tags)"
                    :key="t"
                    class="badge badge--secondary font-mono"
                  >{{ t }}</span>
                </div>
              </li>
            </ul>
          </template>

          <!-- Memory -->
          <template v-else-if="activeTab === 'memory'">
            <div v-if="memoryState.loading.value" class="opacity-70">
              {{ $t('insights.process.memoryLoading') }}
            </div>
            <VEmptyState
              v-else-if="memoryState.entries.value.length === 0"
              :headline="$t('insights.process.memoryEmptyHeadline')"
              :body="$t('insights.process.memoryEmptyBody')"
            />
            <div v-else class="flex flex-col gap-3">
              <label class="flex items-center gap-2 text-sm opacity-80">
                <input v-model="memoryPrakOnly" type="checkbox" />
                {{ $t('insights.process.prakOnlyToggle') }}
              </label>
              <VCard
                v-for="m in filteredMemoryEntries"
                :key="m.id"
                :title="m.title || m.kind"
              >
                <div class="text-xs opacity-60 mb-2 flex flex-wrap gap-x-3 gap-y-1 items-center">
                  <span class="font-mono">{{ m.kind }}</span>
                  <span
                    v-if="isPrakMemory(m.metadata)"
                    class="badge badge--prak"
                  >{{ $t('insights.process.prakBadge') }}</span>
                  <span>{{ fmt(m.createdAt) }}</span>
                  <span v-if="m.supersededByMemoryId">
                    {{ $t('insights.process.supersededBy', { id: m.supersededByMemoryId }) }}
                  </span>
                  <span v-if="m.sourceRefs.length > 0">
                    {{ $t('insights.process.sources', { count: m.sourceRefs.length }) }}
                  </span>
                </div>
                <!-- Prak-structured meta row (importance / confidence / labels / decay) -->
                <div
                  v-if="isPrakMemory(m.metadata)"
                  class="prak-meta-row text-xs flex flex-wrap gap-x-3 gap-y-1 mb-2"
                >
                  <template v-for="(v, k) in [extractPrakMeta(m.metadata)]" :key="k">
                    <span v-if="v.type" class="badge badge--secondary font-mono">{{ v.type }}</span>
                    <span v-if="v.importance != null">
                      importance: <strong>{{ v.importance }}</strong>/5
                    </span>
                    <span v-if="v.confidence != null">
                      confidence: <strong>{{ Math.round(v.confidence * 100) }}%</strong>
                    </span>
                    <span v-if="v.decay">decay: <strong>{{ v.decay }}</strong></span>
                    <span
                      v-for="label in (v.labels || [])"
                      :key="`lbl-${label}`"
                      class="badge badge--secondary"
                    >{{ label }}</span>
                  </template>
                </div>
                <p
                  v-if="isPrakMemory(m.metadata) && extractPrakMeta(m.metadata).why"
                  class="text-xs opacity-70 italic mb-2"
                >“{{ extractPrakMeta(m.metadata).why }}”</p>
                <MarkdownView :source="m.content" />
                <details v-if="Object.keys(m.metadata).length > 0" class="mt-3">
                  <summary class="text-xs opacity-70 cursor-pointer">
                    {{ $t('insights.process.metadata') }}
                  </summary>
                  <pre class="json-block">{{ asJson(m.metadata) }}</pre>
                </details>
              </VCard>
            </div>
          </template>

          <!-- Marvin tree -->
          <template v-else-if="activeTab === 'tree' && isMarvin">
            <div v-if="treeState.loading.value" class="opacity-70">
              {{ $t('insights.process.treeLoading') }}
            </div>
            <VEmptyState
              v-else-if="treeState.nodes.value.length === 0"
              :headline="$t('insights.process.treeEmptyHeadline')"
              :body="$t('insights.process.treeEmptyBody')"
            />
            <VCard v-else :title="$t('insights.process.marvinTreeTitle')">
              <ul class="marvin-tree">
                <li v-for="root in marvinTree" :key="root.doc.id">
                  <MarvinTreeItem :node="root" @select-process="clickProcessByMongoId" />
                </li>
              </ul>
            </VCard>
          </template>

          <!-- LLM Trace — persistent record of every LLM round-trip
               for this process when tracing.llm was on. Self-contained
               component; loads its own data the first time the user
               switches to this tab and on subsequent process changes. -->
          <template v-else-if="activeTab === 'llm-traces'">
            <LlmTraceTab :process-id="selectedProcess.id" />
          </template>

          <!-- Cache Stats — aggregated Anthropic prompt-cache hit rate
               for this process, summed over every OUTPUT trace row.
               Driving question: "is prompt caching paying off here?". -->
          <template v-else-if="activeTab === 'cache-stats'">
            <CacheStatsTab :process-id="selectedProcess.id" />
          </template>

          <!-- Prak Runs — one row per successful side-channel pass with
               sanitize/strength/promotion breakdown + duration.
               Empty when vance.prak.sideChannelEnabled is off. -->
          <template v-else-if="activeTab === 'prak-runs'">
            <div v-if="prakRunsState.loading.value" class="opacity-70">
              {{ $t('insights.process.prakRunsLoading') }}
            </div>
            <VEmptyState
              v-else-if="prakRunsState.runs.value.length === 0"
              :headline="$t('insights.process.prakRunsEmptyHeadline')"
              :body="$t('insights.process.prakRunsEmptyBody')"
            />
            <div v-else class="flex flex-col gap-3">
              <VCard
                v-for="r in prakRunsState.runs.value"
                :key="r.id"
                :title="r.trigger"
              >
                <div class="text-xs opacity-60 mb-3 flex flex-wrap gap-x-3 gap-y-1">
                  <span>{{ fmt(r.createdAt) }}</span>
                  <span>{{ $t('insights.process.prakRunDuration', { ms: r.durationMs }) }}</span>
                  <span>{{ $t('insights.process.prakRunSpan', { count: r.windowMessages }) }}</span>
                  <span v-if="r.model" class="font-mono">{{ r.model }}</span>
                  <span class="font-mono opacity-50">{{ r.runId }}</span>
                </div>

                <div class="grid grid-cols-1 md:grid-cols-3 gap-3 text-sm">
                  <!-- Sanitize -->
                  <div>
                    <h4 class="text-xs uppercase opacity-60 mb-1">{{ $t('insights.process.prakRunSanitize') }}</h4>
                    <dl class="grid grid-cols-2 gap-x-3 gap-y-0.5">
                      <dt class="opacity-60">{{ $t('insights.process.prakRunRaw') }}</dt>
                      <dd>{{ r.rawItemCount }}</dd>
                      <dt class="opacity-60">{{ $t('insights.process.prakRunFinal') }}</dt>
                      <dd><strong>{{ r.finalItemCount }}</strong></dd>
                      <dt class="opacity-60">{{ $t('insights.process.prakRunDropped') }}</dt>
                      <dd>
                        ev:{{ r.droppedNoEvidence }} ·
                        conf:{{ r.droppedLowConfidence }} ·
                        sup:{{ r.droppedBySupersedeWithinBatch }}
                      </dd>
                      <dt class="opacity-60">{{ $t('insights.process.prakRunDedup') }}</dt>
                      <dd>{{ r.duplicatesMerged }}</dd>
                      <dt class="opacity-60">{{ $t('insights.process.prakRunHardCap') }}</dt>
                      <dd>{{ r.hardCapTriggered ? '⚠ yes' : 'no' }}</dd>
                      <dt class="opacity-60">{{ $t('insights.process.prakRunCoverage') }}</dt>
                      <dd :class="{ 'text-warning': r.lowCoverage }">
                        {{ Math.round(r.evidenceCoverage * 100) }}%
                      </dd>
                    </dl>
                  </div>
                  <!-- Strength -->
                  <div>
                    <h4 class="text-xs uppercase opacity-60 mb-1">{{ $t('insights.process.prakRunStrength') }}</h4>
                    <dl class="grid grid-cols-2 gap-x-3 gap-y-0.5">
                      <dt class="opacity-60">{{ $t('insights.process.prakRunOverrides') }}</dt>
                      <dd>{{ r.strengthOverrides }}</dd>
                      <dt class="opacity-60">{{ $t('insights.process.prakRunTagsModified') }}</dt>
                      <dd>{{ r.strengthTagsModified }}</dd>
                    </dl>
                  </div>
                  <!-- Promotion -->
                  <div>
                    <h4 class="text-xs uppercase opacity-60 mb-1">{{ $t('insights.process.prakRunPromotion') }}</h4>
                    <dl class="grid grid-cols-2 gap-x-3 gap-y-0.5">
                      <dt class="opacity-60">{{ $t('insights.process.prakRunPromoted') }}</dt>
                      <dd><strong>{{ r.promoted }}</strong></dd>
                      <dt class="opacity-60">{{ $t('insights.process.prakRunInboxOffered') }}</dt>
                      <dd>{{ r.inboxOffered }}</dd>
                      <dt class="opacity-60">{{ $t('insights.process.prakRunSkipped') }}</dt>
                      <dd>{{ r.skipped }}</dd>
                      <dt class="opacity-60">{{ $t('insights.process.prakRunRefreshed') }}</dt>
                      <dd>{{ r.refreshed }}</dd>
                      <dt class="opacity-60">{{ $t('insights.process.prakRunAffectsDeferred') }}</dt>
                      <dd>{{ r.affectsDeferred }}</dd>
                    </dl>
                  </div>
                </div>

                <details v-if="r.persistedMemoryIds.length > 0" class="mt-3">
                  <summary class="text-xs opacity-70 cursor-pointer">
                    {{ $t('insights.process.prakRunPersistedMemories') }}
                    ({{ r.persistedMemoryIds.length }})
                  </summary>
                  <ul class="mt-1 text-xs font-mono opacity-80 list-disc list-inside">
                    <li v-for="mid in r.persistedMemoryIds" :key="mid">{{ mid }}</li>
                  </ul>
                </details>
              </VCard>
            </div>
          </template>
        </template>
      </template>
      </template>
      </div>
    </div>

    <!-- ─── Right panel: help ─── -->
    <template #right-panel>
      <div class="p-4 flex flex-col gap-4">
        <h3 class="text-xs uppercase opacity-60 mb-2">{{ $t('insights.help.title') }}</h3>
        <div v-if="help.loading.value" class="text-xs opacity-60">
          {{ $t('insights.help.loading') }}
        </div>
        <div v-else-if="help.error.value" class="text-xs opacity-60">
          {{ $t('insights.help.unavailable', { error: help.error.value }) }}
        </div>
        <div v-else-if="!help.content.value" class="text-xs opacity-60">
          {{ $t('insights.help.empty') }}
        </div>
        <MarkdownView v-else :source="help.content.value" />
      </div>
    </template>
  </EditorShell>
</template>

<style scoped>
.session-row {
  display: grid;
  grid-template-columns: 1.25rem 1fr;
  gap: 0.25rem;
  align-items: start;
}
.chev {
  font-size: 0.85rem;
  background: transparent;
  cursor: pointer;
  padding: 0.4rem 0.2rem;
}
.session-label {
  display: block;
  text-align: left;
  padding: 0.4rem 0.6rem;
  border-radius: 0.375rem;
  background: transparent;
  cursor: pointer;
  width: 100%;
  border: 1px solid transparent;
}
.session-label:hover {
  background: hsl(var(--bc) / 0.06);
}
.session-label--active {
  background: hsl(var(--p) / 0.12);
  border-color: hsl(var(--p) / 0.3);
}
.session-topic {
  font-size: 0.875rem;
  font-weight: 500;
}

.session-header {
  border: 1px solid hsl(var(--bc) / 0.12);
  border-radius: 0.5rem;
  padding: 0.75rem 1rem;
  background: hsl(var(--b1));
}
.session-topic-title {
  font-size: 1.05rem;
  font-weight: 600;
  margin-top: 0.4rem;
  line-height: 1.3;
}

.session-children {
  margin-left: 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  padding-bottom: 0.25rem;
}
.process-row {
  display: block;
  text-align: left;
  padding: 0.35rem 0.6rem;
  border-radius: 0.375rem;
  background: transparent;
  cursor: pointer;
  border: 1px solid transparent;
}
.process-row:hover {
  background: hsl(var(--bc) / 0.06);
}
.process-row--active {
  background: hsl(var(--p) / 0.12);
  border-color: hsl(var(--p) / 0.3);
}

.badge-open {
  background: hsl(var(--su) / 0.18);
  color: hsl(var(--suc));
}
.badge-closed {
  background: hsl(var(--bc) / 0.1);
  color: hsl(var(--bc) / 0.6);
}

.tab-bar {
  display: flex;
  gap: 0.25rem;
  border-bottom: 1px solid hsl(var(--bc) / 0.15);
  padding-bottom: 0.25rem;
}
/* Overflow variant: kept on a single row; ResizeObserver in
   InsightsApp decides which tabs collapse into "More ▾". The
   container clips so a momentary layout glitch (1-frame mismatch
   between recalc and paint) never spills below the border. */
.tab-bar--overflow {
  flex-wrap: nowrap;
  overflow: hidden;
  align-items: center;
}
/* Hidden measurement strip — must stay rendered (so offsetWidth is
   accurate) but out of the visual flow. */
.tab-bar--phantom {
  position: absolute;
  top: -9999px;
  left: -9999px;
  visibility: hidden;
  pointer-events: none;
  border-bottom: none;
}
.tab {
  padding: 0.35rem 0.85rem;
  border-radius: 0.375rem;
  background: transparent;
  border: 1px solid transparent;
  font-size: 0.875rem;
  cursor: pointer;
  white-space: nowrap;
}
.tab-more {
  position: relative;
}
.tab-more__btn {
  /* Slight visual hint that this opens something different from a
     plain tab — softer outline, same hit area. */
  font-variant: tabular-nums;
}
/* Tab-more__menu styles are in the un-scoped <style> block below —
   the menu is teleported to <body>, so Vue's scoped-attribute
   selectors would not match it. */
.tab:hover { background: hsl(var(--bc) / 0.06); }
.tab--active {
  background: hsl(var(--p) / 0.12);
  border-color: hsl(var(--p) / 0.3);
  font-weight: 600;
}

.json-block {
  background: hsl(var(--bc) / 0.05);
  padding: 0.5rem 0.75rem;
  border-radius: 0.375rem;
  font-size: 0.8125rem;
  white-space: pre-wrap;
  word-break: break-word;
}

.link {
  color: hsl(var(--p));
  text-decoration: underline;
  background: transparent;
  cursor: pointer;
  font-family: ui-monospace, monospace;
  font-size: 0.85rem;
}

.chat-msg {
  border-left: 3px solid transparent;
  padding: 0.5rem 0.75rem;
  border-radius: 0.375rem;
  background: hsl(var(--bc) / 0.04);
}
.chat-msg--user      { border-left-color: hsl(var(--p)); }
.chat-msg--assistant { border-left-color: hsl(var(--su)); }
.chat-msg--system    { border-left-color: hsl(var(--wa)); }
.chat-msg--archived  { opacity: 0.55; }
.chat-msg--strength-weak { background: hsl(var(--bc) / 0.02); opacity: 0.7; }
.chat-msg--strength-strong { box-shadow: inset 0 0 0 1px hsl(var(--in) / 0.4); }
.chat-msg--strength-pinned { box-shadow: inset 0 0 0 1px hsl(var(--p) / 0.6); }

.badge {
  display: inline-block;
  padding: 0.05rem 0.4rem;
  border-radius: 0.25rem;
  font-size: 0.7rem;
  line-height: 1.25;
  background: hsl(var(--bc) / 0.08);
  border: 1px solid hsl(var(--bc) / 0.12);
}
.badge--secondary  { opacity: 0.75; }
.badge--prak       { background: hsl(var(--in) / 0.18); border-color: hsl(var(--in) / 0.4); }
.badge--strength-weak   { background: hsl(var(--bc) / 0.10); opacity: 0.7; }
.badge--strength-normal { background: hsl(var(--bc) / 0.12); }
.badge--strength-strong { background: hsl(var(--in) / 0.20); border-color: hsl(var(--in) / 0.5); font-weight: 600; }
.badge--strength-pinned { background: hsl(var(--p) / 0.20); border-color: hsl(var(--p) / 0.6); font-weight: 600; }

.prak-meta-row { padding: 0.25rem 0; opacity: 0.85; }

.marvin-tree { list-style: none; padding-left: 0; }
.marvin-children { list-style: none; padding-left: 1.25rem; border-left: 1px dashed hsl(var(--bc) / 0.2); }
.marvin-node-head { display: flex; gap: 0.5rem; align-items: baseline; padding: 0.25rem 0; }
.marvin-kind { font-family: ui-monospace, monospace; font-size: 0.75rem; opacity: 0.7; }
.marvin-status { font-size: 0.75rem; opacity: 0.6; }
.marvin-goal { font-size: 0.875rem; }
.marvin-failure { font-size: 0.75rem; color: hsl(var(--er)); padding-left: 1rem; }
</style>

<!-- Un-scoped styles: the More-button dropdown is teleported to
     <body>, which puts it outside this component's scoped-CSS attribute
     reach. Anything that needs to style a teleported element must live
     here. Keep class names component-prefixed (tab-more__*) so they
     stay specific. -->
<style>
/* DaisyUI v4 publishes theme variables as oklch triplets
   (e.g. --b1: 100% 0 0) — they must be wrapped in oklch(), not
   hsl(). The --fallback-* sibling is a hex pre-computed by daisyui
   for browsers that can't resolve oklch (matches daisyui's own
   convention seen on :root in compiled CSS). */
.tab-more__menu {
  position: fixed;
  min-width: 180px;
  z-index: 100;
  display: flex;
  flex-direction: column;
  padding: 0.25rem;
  background: var(--fallback-b1, oklch(var(--b1) / 1));
  border: 1px solid oklch(var(--bc) / 0.15);
  border-radius: 0.5rem;
  box-shadow: 0 8px 24px oklch(var(--bc) / 0.12);
  color: var(--fallback-bc, oklch(var(--bc) / 1));
}
.tab-more__item {
  padding: 0.4rem 0.75rem;
  border-radius: 0.25rem;
  background: transparent;
  border: none;
  font-size: 0.875rem;
  cursor: pointer;
  text-align: left;
  white-space: nowrap;
  color: inherit;
}
.tab-more__item:hover { background: oklch(var(--bc) / 0.08); }
.tab-more__item--active {
  background: oklch(var(--p) / 0.12);
  font-weight: 600;
}
</style>
