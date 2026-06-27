<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import mermaid from 'mermaid';
import { VAlert, VButton, VEmptyState, VInput, VModal, VSelect } from '@vance/components';
import CalendarEventDetail from './CalendarEventDetail.vue';
import {
  createCalendarEvent,
  deleteCalendarEvent,
  getCalendarPlanner,
  rebuildCalendarPlanner,
  updateCalendarEvent,
} from './api';
import type { CalendarConflictView } from './generated/calendar/CalendarConflictView';
import type { CalendarEventCreateRequest } from './generated/calendar/CalendarEventCreateRequest';
import type { CalendarEventUpdateRequest } from './generated/calendar/CalendarEventUpdateRequest';
import type { CalendarEventView } from './generated/calendar/CalendarEventView';
import type { CalendarPlannerView } from './generated/calendar/CalendarPlannerView';

const props = defineProps<{
  projectId: string;
  folder: string;
  title?: string;
}>();

const OVERVIEW = '__overview__';

const planner = ref<CalendarPlannerView | null>(null);
const loading = ref(true);
const error = ref<string | null>(null);
const activeTab = ref<string>(OVERVIEW);
const selectedEventId = ref<string | null>(null);
const showCreateModal = ref(false);
const newEventForm = ref<CalendarEventCreateRequest>({
  title: '',
  start: '',
  lane: '',
  allDay: false,
  attendees: [],
  tags: [],
});
const ganttSvg = ref<string>('');
const ganttError = ref<string | null>(null);
let mermaidInitialized = false;

const eventsForTab = computed<CalendarEventView[]>(() => {
  if (!planner.value || activeTab.value === OVERVIEW) return [];
  return planner.value.events
    .filter((e) => e.lane === activeTab.value)
    .slice()
    .sort((a, b) => a.start.localeCompare(b.start));
});

const selectedEvent = computed<CalendarEventView | null>(() => {
  if (!planner.value || !selectedEventId.value) return null;
  return planner.value.events.find((e) => e.id === selectedEventId.value) ?? null;
});

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    planner.value = await getCalendarPlanner(props.projectId, props.folder);
    if (
      activeTab.value !== OVERVIEW &&
      !planner.value.lanes.some((l) => l.name === activeTab.value)
    ) {
      activeTab.value = OVERVIEW;
    }
    await renderGantt();
  } catch (e) {
    error.value = `Could not load planner: ${(e as Error).message}`;
  } finally {
    loading.value = false;
  }
}

function initMermaid(): void {
  if (mermaidInitialized) return;
  mermaid.initialize({
    startOnLoad: false,
    securityLevel: 'strict',
    theme: 'default',
  });
  mermaidInitialized = true;
}

async function renderGantt(): Promise<void> {
  if (!planner.value) return;
  const gantt = planner.value.artefacts.find((a) => a.name === 'gantt');
  if (!gantt?.body) {
    ganttSvg.value = '';
    ganttError.value = null;
    return;
  }
  const source = extractMermaidFence(gantt.body);
  if (!source) {
    ganttSvg.value = '';
    ganttError.value = 'Could not extract Mermaid source from _gantt.md.';
    return;
  }
  try {
    initMermaid();
    const id = `gantt-${Date.now()}`;
    const out = await mermaid.render(id, source);
    ganttSvg.value = out.svg;
    ganttError.value = null;
  } catch (e) {
    ganttSvg.value = '';
    ganttError.value = (e as Error).message;
  }
}

function extractMermaidFence(body: string): string | null {
  const match = body.match(/```mermaid\s*\n([\s\S]*?)\n```/);
  return match ? match[1] : null;
}

async function rebuild(): Promise<void> {
  try {
    await rebuildCalendarPlanner(props.projectId, props.folder);
    await load();
  } catch (e) {
    error.value = `Rebuild failed: ${(e as Error).message}`;
  }
}

function openCreateModal(lane: string): void {
  newEventForm.value = {
    title: '',
    start: new Date().toISOString().slice(0, 10),
    lane,
    allDay: false,
    attendees: [],
    tags: [],
  };
  showCreateModal.value = true;
}

async function submitCreate(): Promise<void> {
  if (!newEventForm.value.title.trim() || !newEventForm.value.start.trim()) return;
  try {
    const created = await createCalendarEvent(
      props.projectId,
      props.folder,
      newEventForm.value,
    );
    if (planner.value) planner.value.events.push(created);
    showCreateModal.value = false;
    selectedEventId.value = created.id;
    activeTab.value = created.lane;
  } catch (e) {
    error.value = `Create failed: ${(e as Error).message}`;
  }
}

async function onEventUpdate(id: string, patch: CalendarEventUpdateRequest): Promise<void> {
  try {
    const updated = await updateCalendarEvent(props.projectId, props.folder, id, patch);
    if (!planner.value) return;
    const idx = planner.value.events.findIndex((e) => e.id === id);
    if (idx >= 0) planner.value.events[idx] = updated;
    selectedEventId.value = updated.id;
    if (patch.targetLane && updated.lane !== activeTab.value) {
      activeTab.value = updated.lane;
    }
  } catch (e) {
    error.value = `Update failed: ${(e as Error).message}`;
  }
}

async function onEventDelete(id: string): Promise<void> {
  try {
    await deleteCalendarEvent(props.projectId, props.folder, id);
    if (planner.value) {
      planner.value.events = planner.value.events.filter((e) => e.id !== id);
    }
    selectedEventId.value = null;
  } catch (e) {
    error.value = `Delete failed: ${(e as Error).message}`;
  }
}

watch(activeTab, () => {
  if (activeTab.value === OVERVIEW) {
    void renderGantt();
  }
});

function formatDateRange(ev: CalendarEventView): string {
  if (!ev.end) return ev.start;
  if (ev.allDay && ev.start === ev.end) return ev.start;
  return `${ev.start} → ${ev.end}`;
}

function rangeLabel(c: CalendarConflictView): string {
  return `${c.overlapStart.replace('T', ' ')} – ${c.overlapEnd.replace('T', ' ')}`;
}

onMounted(load);

// Exposed so the CalendarAppKind wrapper can drive reloads in response
// to documents.changed pushes — see useDocumentPrefixReaction wire-up
// in the wrapper. Apps-side WS subscriptions stay in the wrapper; the
// Planner itself remains WS-free and trivially mountable from REST
// fixtures in tests.
defineExpose({ reload: load });
</script>

<template>
  <div class="flex flex-col h-full">
    <div class="flex items-center justify-between p-4 border-b border-base-300">
      <div>
        <h1 class="text-xl font-semibold">{{ title ?? folder }}</h1>
        <div class="text-sm text-base-content/60 mt-0.5">
          {{ folder }}
          <template v-if="planner?.windowFrom || planner?.windowUntil">
            · {{ planner?.windowFrom ?? '?' }} → {{ planner?.windowUntil ?? '?' }}
          </template>
        </div>
      </div>
      <div class="flex gap-2 items-center">
        <span v-if="planner" class="text-sm text-base-content/60">
          {{ planner.events.length }} events · {{ planner.lanes.length }} lanes
          <template v-if="planner.conflicts.length > 0">
            · <span class="text-error">⚠ {{ planner.conflicts.length }} conflicts</span>
          </template>
        </span>
        <VButton size="sm" variant="ghost" @click="load">Reload</VButton>
        <VButton size="sm" variant="ghost" @click="rebuild">Rebuild artefacts</VButton>
      </div>
    </div>

    <VAlert v-if="error" variant="error" class="m-4">{{ error }}</VAlert>

    <div v-if="loading" class="p-8 text-base-content/70">Loading planner…</div>

    <div v-else class="flex-1 flex overflow-hidden">
      <!-- Lane sidebar -->
      <div class="w-56 flex-shrink-0 border-r border-base-300 bg-base-200/40 overflow-y-auto">
        <button
          class="w-full text-left px-4 py-3 hover:bg-base-200 transition-colors"
          :class="activeTab === OVERVIEW ? 'bg-base-100 border-l-4 border-primary' : ''"
          @click="activeTab = OVERVIEW; selectedEventId = null"
        >
          <div class="font-medium">Overview</div>
          <div class="text-xs text-base-content/60 mt-0.5">Gantt + conflicts</div>
        </button>
        <div class="border-t border-base-300 mt-1 pt-1">
          <button
            v-for="lane in planner?.lanes ?? []"
            :key="lane.name"
            class="w-full text-left px-4 py-2 hover:bg-base-200 transition-colors flex items-center justify-between"
            :class="activeTab === lane.name ? 'bg-base-100 border-l-4 border-primary' : ''"
            @click="activeTab = lane.name; selectedEventId = null"
          >
            <div>
              <div class="font-medium text-sm">{{ lane.title ?? lane.name }}</div>
              <div v-if="!lane.declared" class="text-xs text-warning">⚠ undeclared</div>
            </div>
            <span class="text-xs text-base-content/60">{{ lane.eventCount }}</span>
          </button>
        </div>
      </div>

      <!-- Main area -->
      <div class="flex-1 overflow-y-auto">
        <!-- Overview -->
        <div v-if="activeTab === OVERVIEW" class="p-4 flex flex-col gap-4">
          <section v-if="planner?.artefacts.find((a) => a.name === 'gantt')">
            <h2 class="text-lg font-semibold mb-2">Gantt</h2>
            <VAlert v-if="ganttError" variant="error">
              Could not render Gantt: {{ ganttError }}
            </VAlert>
            <div
              v-else-if="ganttSvg"
              class="bg-base-100 border border-base-300 rounded p-4 overflow-x-auto"
              v-html="ganttSvg"
            />
            <VEmptyState
              v-else
              headline="No Gantt yet"
              body="Click 'Rebuild artefacts' to generate the Mermaid Gantt diagram."
            />
          </section>

          <section>
            <h2 class="text-lg font-semibold mb-2">
              Conflicts <span v-if="planner" class="text-base-content/60 text-sm">({{ planner.conflicts.length }})</span>
            </h2>
            <VEmptyState
              v-if="!planner || planner.conflicts.length === 0"
              headline="No conflicts"
              body="No two events overlap in the current window."
            />
            <div v-else class="border border-base-300 rounded overflow-hidden">
              <table class="w-full text-sm">
                <thead class="bg-base-200">
                  <tr>
                    <th class="text-left px-3 py-2">Event A</th>
                    <th class="text-left px-3 py-2">Event B</th>
                    <th class="text-left px-3 py-2">Overlap</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="(c, idx) in planner.conflicts"
                    :key="idx"
                    class="border-t border-base-300"
                  >
                    <td class="px-3 py-2">
                      <div class="font-medium">{{ c.titleA }}</div>
                      <div class="text-xs text-base-content/60">{{ c.laneA }}</div>
                    </td>
                    <td class="px-3 py-2">
                      <div class="font-medium">{{ c.titleB }}</div>
                      <div class="text-xs text-base-content/60">{{ c.laneB }}</div>
                    </td>
                    <td class="px-3 py-2 text-xs text-base-content/70">{{ rangeLabel(c) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
        </div>

        <!-- Lane events -->
        <div v-else class="flex flex-col h-full">
          <div class="p-4 flex items-center justify-between border-b border-base-300">
            <h2 class="text-lg font-semibold">
              {{ planner?.lanes.find((l) => l.name === activeTab)?.title ?? activeTab }}
            </h2>
            <VButton size="sm" variant="primary" @click="openCreateModal(activeTab)">
              + Add event
            </VButton>
          </div>
          <div class="flex-1 overflow-y-auto p-4">
            <VEmptyState
              v-if="eventsForTab.length === 0"
              headline="No events in this lane"
              body="Add the first event to get started."
            />
            <div v-else class="flex flex-col gap-2">
              <div
                v-for="ev in eventsForTab"
                :key="ev.id"
                class="bg-base-100 border border-base-300 rounded p-3 cursor-pointer hover:border-primary transition-colors"
                :class="selectedEventId === ev.id ? 'border-primary ring-1 ring-primary/30' : ''"
                @click="selectedEventId = ev.id"
              >
                <div class="flex items-start justify-between gap-3">
                  <div class="flex-1 min-w-0">
                    <div class="font-medium">{{ ev.title }}</div>
                    <div class="text-xs text-base-content/70 mt-0.5">
                      {{ formatDateRange(ev) }}
                      <span v-if="ev.allDay" class="ml-1 text-base-content/50">· all-day</span>
                      <span v-if="ev.recurrence" class="ml-1 text-info">· recurring</span>
                    </div>
                  </div>
                  <div class="flex gap-1">
                    <a
                      v-if="ev.googleUrl"
                      :href="ev.googleUrl"
                      target="_blank"
                      rel="noopener"
                      class="text-xs bg-base-200 hover:bg-base-300 rounded px-2 py-1"
                      title="Add to Google Calendar"
                      @click.stop
                    >Google</a>
                    <a
                      v-if="ev.outlookUrl"
                      :href="ev.outlookUrl"
                      target="_blank"
                      rel="noopener"
                      class="text-xs bg-base-200 hover:bg-base-300 rounded px-2 py-1"
                      title="Add to Outlook"
                      @click.stop
                    >Outlook</a>
                  </div>
                </div>
                <div v-if="ev.location" class="text-xs text-base-content/60 mt-1">
                  📍 {{ ev.location }}
                </div>
                <div v-if="ev.tags.length > 0" class="flex flex-wrap gap-1 mt-1">
                  <span
                    v-for="tag in ev.tags"
                    :key="tag"
                    class="text-xs bg-info/15 text-info rounded px-1.5 py-0.5"
                  >{{ tag }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Right panel: event detail -->
      <CalendarEventDetail
        v-if="selectedEvent && planner"
        :event="selectedEvent"
        :lanes="planner.lanes"
        class="w-96 flex-shrink-0 border-l border-base-300 bg-base-100 overflow-y-auto"
        @close="selectedEventId = null"
        @update="(patch) => onEventUpdate(selectedEvent!.id, patch)"
        @delete="onEventDelete(selectedEvent!.id)"
      />
    </div>

    <!-- New-event modal -->
    <VModal v-model="showCreateModal" title="New event">
      <div class="flex flex-col gap-3">
        <VInput v-model="newEventForm.title" placeholder="Event title" />
        <VInput v-model="newEventForm.start" placeholder="Start (YYYY-MM-DD or YYYY-MM-DDTHH:mm)" />
        <VInput
          :model-value="newEventForm.end ?? ''"
          placeholder="End (optional)"
          @update:model-value="(v) => newEventForm.end = v"
        />
        <VSelect
          :model-value="newEventForm.lane ?? ''"
          :options="planner?.lanes.map((l) => ({ value: l.name, label: l.title ?? l.name })) ?? []"
          @update:model-value="(v) => newEventForm.lane = (v as string | null) ?? ''"
        />
        <div class="flex justify-end gap-2 pt-2">
          <VButton variant="ghost" @click="showCreateModal = false">Cancel</VButton>
          <VButton
            variant="primary"
            :disabled="!newEventForm.title.trim() || !newEventForm.start.trim()"
            @click="submitCreate"
          >Create</VButton>
        </div>
      </div>
    </VModal>
  </div>
</template>
