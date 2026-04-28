<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import {
  EditorShell,
  MarkdownView,
  VAlert,
  VCard,
  VEmptyState,
  VInput,
  VSelect,
  type Crumb,
} from '@/components';
import { useTenantProjects } from '@/composables/useTenantProjects';
import {
  useInsightsSessions,
  useSessionProcesses,
  useProcessDetail,
  useProcessChat,
  useProcessMemory,
  useMarvinTree,
} from '@/composables/useInsights';
import { useHelp } from '@/composables/useHelp';
import MarvinTreeItem, { type MarvinTreeNode } from './MarvinTreeItem.vue';
import SessionTimelineTab from './SessionTimelineTab.vue';
import {
  ChatRole,
  type MarvinNodeInsightsDto,
  type SessionInsightsDto,
  type ThinkProcessInsightsDto,
} from '@vance/generated';

const tenantProjects = useTenantProjects();
const sessionsState = useInsightsSessions();
const processesState = useSessionProcesses();
const processDetailState = useProcessDetail();
const chatState = useProcessChat();
const memoryState = useProcessMemory();
const treeState = useMarvinTree();
const help = useHelp();

// ─── Filter state ───────────────────────────────────────────────────────
const filterProjectId = ref<string | null>(null);
const filterUserId = ref<string>('');
const filterStatus = ref<string | null>(null);

const projectFilterOptions = computed(() => [
  { value: '', label: '(all projects)' },
  ...tenantProjects.projects.value.map(p => ({
    value: p.name,
    label: p.title || p.name,
  })),
]);

const statusOptions = [
  { value: '', label: '(all)' },
  { value: 'OPEN', label: 'OPEN' },
  { value: 'CLOSED', label: 'CLOSED' },
];

// ─── Selection state ────────────────────────────────────────────────────
type Selection =
  | { kind: 'session'; id: string }   // sessionId (business id)
  | { kind: 'process'; id: string };  // process Mongo id

const selection = ref<Selection | null>(null);

/** Sessions whose processes-list is open in the sidebar. Loaded lazily. */
const expanded = ref<Set<string>>(new Set());

/** Per-sessionId cache of processes — populated as sessions expand. */
const processesBySession = ref<Record<string, ThinkProcessInsightsDto[]>>({});

const activeTab = ref<string>('overview');

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

function sessionLabel(sessionId: string): string {
  // Prefer the denormalised topic when we already have it cached;
  // otherwise fall back to the raw id.
  const s = sessionsState.sessions.value.find(x => x.sessionId === sessionId);
  const topic = s?.firstUserMessage;
  if (topic && topic.length > 0) {
    const short = topic.length > 60 ? topic.slice(0, 59) + '…' : topic;
    return `Session: ${short}`;
  }
  return `Session: ${sessionId}`;
}

const breadcrumbs = computed<Crumb[]>(() => {
  const sel = selection.value;
  if (!sel) return [];
  if (sel.kind === 'session') return [sessionLabel(sel.id)];
  const p = selectedProcess.value;
  if (!p) return ['Process'];
  // When a process is selected, the session crumb navigates back to the
  // session view — the most common "go up one level" gesture.
  return [
    {
      text: sessionLabel(p.sessionId),
      onClick: () => { selection.value = { kind: 'session', id: p.sessionId }; },
    },
    `Process: ${p.name}`,
  ];
});

const combinedError = computed<string | null>(() =>
  sessionsState.error.value
  || processesState.error.value
  || processDetailState.error.value
  || chatState.error.value
  || memoryState.error.value
  || treeState.error.value);

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
  <EditorShell title="Insights" :breadcrumbs="breadcrumbs" wide-right-panel>
    <!-- ─── Sidebar: filter + sessions tree ─── -->
    <template #sidebar>
      <div class="flex flex-col gap-3 p-2">
        <div class="flex flex-col gap-2">
          <VSelect
            :model-value="filterProjectId ?? ''"
            :options="projectFilterOptions"
            label="Project"
            @update:model-value="(v) => filterProjectId = (v ? String(v) : null)"
          />
          <VInput v-model="filterUserId" label="User" placeholder="(optional)" />
          <VSelect
            :model-value="filterStatus ?? ''"
            :options="statusOptions"
            label="Status"
            @update:model-value="(v) => filterStatus = (v ? String(v) : null)"
          />
        </div>

        <div v-if="sessionsState.loading.value" class="text-xs opacity-60 px-2">
          Loading sessions…
        </div>
        <VEmptyState
          v-else-if="sessionsState.sessions.value.length === 0"
          headline="No sessions"
          body="No sessions match the current filter."
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
      </div>
    </template>

    <!-- ─── Main pane ─── -->
    <div class="p-6 flex flex-col gap-3 max-w-5xl">
      <VAlert v-if="combinedError" variant="error">
        <span>{{ combinedError }}</span>
      </VAlert>

      <VEmptyState
        v-if="!selection"
        headline="Select something"
        body="Pick a session on the left to inspect, or expand to drill into its processes."
      />

      <!-- ─── Session view ─── -->
      <template v-else-if="selection.kind === 'session'">
        <div v-if="!selectedSession" class="opacity-70">Loading…</div>
        <template v-else>
          <div class="tab-bar">
            <button
              class="tab"
              :class="{ 'tab--active': activeTab === 'overview' }"
              @click="activeTab = 'overview'"
            >Overview</button>
            <button
              class="tab"
              :class="{ 'tab--active': activeTab === 'processes' }"
              @click="activeTab = 'processes'"
            >Processes ({{ sessionProcessesForTab.length }})</button>
            <button
              class="tab"
              :class="{ 'tab--active': activeTab === 'timeline' }"
              @click="activeTab = 'timeline'"
            >Timeline</button>
          </div>

          <VCard v-if="activeTab === 'overview'" :title="`Session ${selectedSession.sessionId}`">
            <div v-if="selectedSession.firstUserMessage" class="mb-3">
              <div class="text-xs uppercase opacity-60 mb-1">Topic</div>
              <div class="text-sm">{{ selectedSession.firstUserMessage }}</div>
            </div>
            <div v-if="selectedSession.lastMessagePreview" class="mb-3">
              <div class="text-xs uppercase opacity-60 mb-1">
                Last message
                <span v-if="selectedSession.lastMessageRole" class="opacity-70">
                  · {{ selectedSession.lastMessageRole.toLowerCase() }}
                </span>
                <span v-if="selectedSession.lastMessageAt" class="opacity-70">
                  · {{ fmt(selectedSession.lastMessageAt) }}
                </span>
              </div>
              <div class="text-sm">{{ selectedSession.lastMessagePreview }}</div>
            </div>
            <dl class="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
              <dt class="opacity-60">Mongo id</dt><dd class="font-mono">{{ selectedSession.id }}</dd>
              <dt class="opacity-60">User</dt><dd>{{ selectedSession.userId }}</dd>
              <dt class="opacity-60">Project</dt><dd>{{ selectedSession.projectId }}</dd>
              <dt class="opacity-60">Status</dt><dd>{{ selectedSession.status }}</dd>
              <dt class="opacity-60">Client</dt>
              <dd>{{ selectedSession.clientType }} {{ selectedSession.clientVersion }}</dd>
              <dt class="opacity-60">Bound conn</dt>
              <dd class="font-mono text-xs">{{ fmt(selectedSession.boundConnectionId) }}</dd>
              <dt class="opacity-60">Chat process</dt>
              <dd>
                <button
                  v-if="selectedSession.chatProcessId"
                  class="link"
                  @click="clickProcessByMongoId(selectedSession.chatProcessId)"
                >{{ selectedSession.chatProcessId }}</button>
                <span v-else>—</span>
              </dd>
              <dt class="opacity-60">Created</dt><dd>{{ fmt(selectedSession.createdAt) }}</dd>
              <dt class="opacity-60">Last activity</dt>
              <dd>{{ fmt(selectedSession.lastActivityAt) }}</dd>
            </dl>
          </VCard>

          <VCard v-if="activeTab === 'processes'" title="Processes in this session">
            <div v-if="sessionProcessesForTab.length === 0" class="opacity-70">
              No processes yet.
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
        </template>
      </template>

      <!-- ─── Process view ─── -->
      <template v-else-if="selection.kind === 'process'">
        <div v-if="!selectedProcess" class="opacity-70">Loading…</div>
        <template v-else>
          <div class="tab-bar">
            <button
              class="tab"
              :class="{ 'tab--active': activeTab === 'overview' }"
              @click="activeTab = 'overview'"
            >Overview</button>
            <button
              class="tab"
              :class="{ 'tab--active': activeTab === 'chat' }"
              @click="activeTab = 'chat'"
            >Chat</button>
            <button
              class="tab"
              :class="{ 'tab--active': activeTab === 'memory' }"
              @click="activeTab = 'memory'"
            >Memory</button>
            <button
              v-if="isMarvin"
              class="tab"
              :class="{ 'tab--active': activeTab === 'tree' }"
              @click="activeTab = 'tree'"
            >Tree</button>
          </div>

          <!-- Overview -->
          <template v-if="activeTab === 'overview'">
            <VCard :title="`Process ${selectedProcess.name}`">
              <dl class="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
                <dt class="opacity-60">Mongo id</dt>
                <dd class="font-mono text-xs">{{ selectedProcess.id }}</dd>
                <dt class="opacity-60">Session</dt><dd>{{ selectedProcess.sessionId }}</dd>
                <dt class="opacity-60">Engine</dt>
                <dd>{{ selectedProcess.thinkEngine }} <span class="opacity-60">{{ selectedProcess.thinkEngineVersion }}</span></dd>
                <dt class="opacity-60">Recipe</dt><dd>{{ fmt(selectedProcess.recipeName) }}</dd>
                <dt class="opacity-60">Status</dt><dd>{{ selectedProcess.status }}</dd>
                <dt class="opacity-60">Parent</dt>
                <dd>
                  <button
                    v-if="selectedProcess.parentProcessId"
                    class="link"
                    @click="clickProcessByMongoId(selectedProcess.parentProcessId)"
                  >{{ selectedProcess.parentProcessId }}</button>
                  <span v-else>—</span>
                </dd>
                <dt class="opacity-60">Goal</dt><dd>{{ fmt(selectedProcess.goal) }}</dd>
                <dt class="opacity-60">Created</dt><dd>{{ fmt(selectedProcess.createdAt) }}</dd>
                <dt class="opacity-60">Updated</dt><dd>{{ fmt(selectedProcess.updatedAt) }}</dd>
              </dl>
            </VCard>

            <VCard title="Engine params">
              <pre class="json-block">{{ asJson(selectedProcess.engineParams) }}</pre>
            </VCard>

            <VCard title="Active skills">
              <div v-if="selectedProcess.activeSkills.length === 0" class="opacity-70">
                None active.
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
                    <span v-if="a.fromRecipe">· recipe</span>
                    <span v-if="a.oneShot">· one-shot</span>
                  </span>
                </li>
              </ul>
            </VCard>

            <VCard title="Pending queue">
              <div v-if="selectedProcess.pendingMessages.length === 0" class="opacity-70">
                Drained.
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
            <div v-if="chatState.loading.value" class="opacity-70">Loading chat…</div>
            <VEmptyState
              v-else-if="chatState.messages.value.length === 0"
              headline="No messages"
              body="This process has no chat history."
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
                }"
              >
                <div class="flex items-center justify-between gap-2 text-xs opacity-60 mb-1">
                  <span class="font-mono">{{ m.role }}</span>
                  <span>
                    {{ fmt(m.createdAt) }}
                    <span v-if="m.archivedInMemoryId" class="ml-2 opacity-80">
                      → memory {{ m.archivedInMemoryId }}
                    </span>
                  </span>
                </div>
                <MarkdownView :source="m.content" />
              </li>
            </ul>
          </template>

          <!-- Memory -->
          <template v-else-if="activeTab === 'memory'">
            <div v-if="memoryState.loading.value" class="opacity-70">Loading memory…</div>
            <VEmptyState
              v-else-if="memoryState.entries.value.length === 0"
              headline="No memory"
              body="No engine-side memory entries are scoped to this process."
            />
            <div v-else class="flex flex-col gap-3">
              <VCard
                v-for="m in memoryState.entries.value"
                :key="m.id"
                :title="m.title || m.kind"
              >
                <div class="text-xs opacity-60 mb-2 flex flex-wrap gap-x-3 gap-y-1">
                  <span class="font-mono">{{ m.kind }}</span>
                  <span>{{ fmt(m.createdAt) }}</span>
                  <span v-if="m.supersededByMemoryId">
                    superseded by {{ m.supersededByMemoryId }}
                  </span>
                  <span v-if="m.sourceRefs.length > 0">
                    sources: {{ m.sourceRefs.length }}
                  </span>
                </div>
                <MarkdownView :source="m.content" />
                <details v-if="Object.keys(m.metadata).length > 0" class="mt-3">
                  <summary class="text-xs opacity-70 cursor-pointer">metadata</summary>
                  <pre class="json-block">{{ asJson(m.metadata) }}</pre>
                </details>
              </VCard>
            </div>
          </template>

          <!-- Marvin tree -->
          <template v-else-if="activeTab === 'tree' && isMarvin">
            <div v-if="treeState.loading.value" class="opacity-70">Loading tree…</div>
            <VEmptyState
              v-else-if="treeState.nodes.value.length === 0"
              headline="Empty tree"
              body="No nodes have been planned yet."
            />
            <VCard v-else title="Marvin tree">
              <ul class="marvin-tree">
                <li v-for="root in marvinTree" :key="root.doc.id">
                  <MarvinTreeItem :node="root" @select-process="clickProcessByMongoId" />
                </li>
              </ul>
            </VCard>
          </template>
        </template>
      </template>
    </div>

    <!-- ─── Right panel: help ─── -->
    <template #right-panel>
      <div class="p-4 flex flex-col gap-4">
        <h3 class="text-xs uppercase opacity-60 mb-2">About this view</h3>
        <div v-if="help.loading.value" class="text-xs opacity-60">Loading…</div>
        <div v-else-if="help.error.value" class="text-xs opacity-60">
          Help unavailable: {{ help.error.value }}
        </div>
        <div v-else-if="!help.content.value" class="text-xs opacity-60">
          No help content.
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
.tab {
  padding: 0.35rem 0.85rem;
  border-radius: 0.375rem;
  background: transparent;
  border: 1px solid transparent;
  font-size: 0.875rem;
  cursor: pointer;
}
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

.marvin-tree { list-style: none; padding-left: 0; }
.marvin-children { list-style: none; padding-left: 1.25rem; border-left: 1px dashed hsl(var(--bc) / 0.2); }
.marvin-node-head { display: flex; gap: 0.5rem; align-items: baseline; padding: 0.25rem 0; }
.marvin-kind { font-family: ui-monospace, monospace; font-size: 0.75rem; opacity: 0.7; }
.marvin-status { font-size: 0.75rem; opacity: 0.6; }
.marvin-goal { font-size: 0.875rem; }
.marvin-failure { font-size: 0.75rem; color: hsl(var(--er)); padding-left: 1rem; }
</style>
