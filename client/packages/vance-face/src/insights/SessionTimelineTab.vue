<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VEmptyState } from '@/components';
import ProcessTreeBlock, {
  type ProcessEvent,
  type ProcessTreeNode,
} from './ProcessTreeBlock.vue';
import { brainFetch } from '@vance/shared';
import type {
  ChatMessageInsightsDto,
  MarvinNodeInsightsDto,
  MemoryInsightsDto,
  ThinkProcessInsightsDto,
} from '@vance/generated';

const { t } = useI18n();

const props = defineProps<{
  /** All processes in the selected session — already loaded by InsightsApp. */
  processes: ThinkProcessInsightsDto[];
}>();

const emit = defineEmits<{
  (e: 'select-process', id: string): void;
}>();

interface ProcessBundle {
  process: ThinkProcessInsightsDto;
  chat: ChatMessageInsightsDto[];
  memory: MemoryInsightsDto[];
  marvinNodes: MarvinNodeInsightsDto[];
}

const bundles = ref<Map<string, ProcessBundle>>(new Map());
const loading = ref(false);
const error = ref<string | null>(null);

watch(() => props.processes, async (list) => {
  await loadAll(list);
}, { immediate: true });

async function loadAll(list: ThinkProcessInsightsDto[]): Promise<void> {
  if (!list || list.length === 0) {
    bundles.value = new Map();
    return;
  }
  loading.value = true;
  error.value = null;
  try {
    // Fetch chat + memory for every process in parallel; only fetch the
    // marvin tree when the engine carries one — saves a 200-OK round-trip
    // per ford / arthur process.
    const fetched = await Promise.all(list.map(p => loadOne(p)));
    const map = new Map<string, ProcessBundle>();
    for (const b of fetched) map.set(b.process.id, b);
    bundles.value = map;
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('insights.timeline.failedToLoad');
  } finally {
    loading.value = false;
  }
}

async function loadOne(p: ThinkProcessInsightsDto): Promise<ProcessBundle> {
  const isMarvin = (p.thinkEngine ?? '').toLowerCase() === 'marvin';
  const [chat, memory, marvinNodes] = await Promise.all([
    brainFetch<ChatMessageInsightsDto[]>(
      'GET', `admin/processes/${encodeURIComponent(p.id)}/chat`),
    brainFetch<MemoryInsightsDto[]>(
      'GET', `admin/processes/${encodeURIComponent(p.id)}/memory`),
    isMarvin
      ? brainFetch<MarvinNodeInsightsDto[]>(
          'GET', `admin/processes/${encodeURIComponent(p.id)}/marvin-tree`)
      : Promise.resolve([] as MarvinNodeInsightsDto[]),
  ]);
  return { process: p, chat, memory, marvinNodes };
}

// ─── Process tree (parent → children by parentProcessId) ────────────────

const tree = computed<ProcessTreeNode[]>(() => {
  const byParent = new Map<string | null, ThinkProcessInsightsDto[]>();
  for (const p of props.processes) {
    const key = p.parentProcessId ?? null;
    if (!byParent.has(key)) byParent.set(key, []);
    byParent.get(key)!.push(p);
  }
  for (const list of byParent.values()) {
    list.sort((a, b) => byTime(instantStr(a.createdAt), instantStr(b.createdAt)));
  }
  const idsInSession = new Set(props.processes.map(p => p.id));
  function build(parentId: string | null): ProcessTreeNode[] {
    return (byParent.get(parentId) ?? []).map(p => ({
      process: p,
      children: build(p.id),
    }));
  }
  // Orphans: declared parent is not in this session — render at root.
  const orphans = props.processes.filter(p =>
    p.parentProcessId != null && !idsInSession.has(p.parentProcessId));
  return [
    ...build(null),
    ...orphans.map(p => ({ process: p, children: build(p.id) })),
  ];
});

// ─── Per-process event stream ───────────────────────────────────────────

const eventsByProcess = computed<Record<string, ProcessEvent[]>>(() => {
  const out: Record<string, ProcessEvent[]> = {};
  for (const [id, b] of bundles.value.entries()) {
    out[id] = eventsFor(b);
  }
  return out;
});

function eventsFor(bundle: ProcessBundle): ProcessEvent[] {
  const out: ProcessEvent[] = [];

  out.push({
    kind: 'spawn',
    at: instantStr(bundle.process.createdAt),
    id: 'spawn',
    label: t('insights.timeline.eventSpawn', { engine: bundle.process.thinkEngine }),
    tag: bundle.process.recipeName
      ? t('insights.timeline.tagRecipe', { name: bundle.process.recipeName })
      : undefined,
    detail: JSON.stringify({
      name: bundle.process.name,
      engine: bundle.process.thinkEngine,
      recipe: bundle.process.recipeName,
      goal: bundle.process.goal,
      params: bundle.process.engineParams,
      parentProcessId: bundle.process.parentProcessId,
    }, null, 2),
    detailIsMarkdown: false,
  });

  for (const m of bundle.chat) {
    out.push({
      kind: 'chat',
      at: instantStr(m.createdAt),
      id: 'chat:' + m.id,
      label: t('insights.timeline.eventChat', {
        role: m.role,
        preview: truncate(m.content, 90),
      }),
      tag: m.archivedInMemoryId ? t('insights.timeline.tagArchived') : undefined,
      detail: m.content,
      detailIsMarkdown: true,
    });
  }

  for (const mem of bundle.memory) {
    out.push({
      kind: 'memory',
      at: instantStr(mem.createdAt),
      id: 'mem:' + mem.id,
      label: mem.title
        ? t('insights.timeline.eventMemoryWithTitle', { kind: mem.kind, title: mem.title })
        : t('insights.timeline.eventMemory', { kind: mem.kind }),
      tag: mem.supersededByMemoryId ? t('insights.timeline.tagSuperseded') : undefined,
      detail: mem.content,
      detailIsMarkdown: true,
    });
  }

  for (const n of bundle.marvinNodes) {
    out.push({
      kind: 'marvin',
      at: instantStr(n.createdAt),
      id: 'mn:' + n.id,
      label: t('insights.timeline.eventMarvinNode', {
        taskKind: n.taskKind,
        goal: truncate(n.goal || t('insights.timeline.noGoal'), 80),
      }),
      tag: n.status,
      detail: JSON.stringify({
        goal: n.goal,
        taskKind: n.taskKind,
        status: n.status,
        artifacts: n.artifacts,
        spawnedProcessId: n.spawnedProcessId,
        inboxItemId: n.inboxItemId,
        failureReason: n.failureReason,
      }, null, 2),
      detailIsMarkdown: false,
    });
  }

  bundle.process.pendingMessages.forEach((m, idx) => {
    out.push({
      kind: 'pending',
      at: instantStr(m.at),
      id: 'pm:' + idx,
      label: t('insights.timeline.eventPending', { type: m.type }),
      tag: t('insights.timeline.tagQueued'),
      detail: JSON.stringify(m.payload ?? {}, null, 2),
      detailIsMarkdown: false,
    });
  });

  out.sort((a, b) => byTime(a.at, b.at));
  return out;
}

// ─── Expansion state ────────────────────────────────────────────────────

const collapsedProcesses = ref<Set<string>>(new Set());
const expandedEvents = ref<Set<string>>(new Set());

function toggleProcess(id: string): void {
  const next = new Set(collapsedProcesses.value);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  collapsedProcesses.value = next;
}

function toggleEvent(processId: string, eventId: string): void {
  const key = processId + '|' + eventId;
  const next = new Set(expandedEvents.value);
  if (next.has(key)) next.delete(key);
  else next.add(key);
  expandedEvents.value = next;
}

// ─── Helpers ────────────────────────────────────────────────────────────

function instantStr(d: Date | string | null | undefined): string | null {
  if (d == null) return null;
  if (d instanceof Date) return d.toISOString();
  return String(d);
}

function byTime(a: string | null, b: string | null): number {
  if (a == null && b == null) return 0;
  if (a == null) return 1;
  if (b == null) return -1;
  return a < b ? -1 : a > b ? 1 : 0;
}

function truncate(s: string, max: number): string {
  if (!s) return '';
  const trimmed = s.replace(/\s+/g, ' ').trim();
  return trimmed.length > max ? trimmed.slice(0, max - 1) + '…' : trimmed;
}
</script>

<template>
  <div class="flex flex-col gap-3">
    <VAlert v-if="error" variant="error">
      <span>{{ error }}</span>
    </VAlert>

    <div v-if="loading" class="opacity-70">{{ $t('insights.timeline.loading') }}</div>

    <VEmptyState
      v-else-if="processes.length === 0"
      :headline="$t('insights.timeline.noProcessesHeadline')"
      :body="$t('insights.timeline.noProcessesBody')"
    />

    <ul v-else class="timeline-tree">
      <li
        v-for="root in tree"
        :key="root.process.id"
        class="timeline-process"
      >
        <ProcessTreeBlock
          :node="root"
          :events-by-process="eventsByProcess"
          :collapsed-processes="collapsedProcesses"
          :expanded-events="expandedEvents"
          @select-process="(id) => emit('select-process', id)"
          @toggle-process="toggleProcess"
          @toggle-event="toggleEvent"
        />
      </li>
    </ul>
  </div>
</template>

<style scoped>
.timeline-tree {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.timeline-process { list-style: none; }
</style>
