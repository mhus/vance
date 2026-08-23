<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { MegadodoEventDto } from '@vance/generated';
import { MegadodoRefType } from '@vance/generated';
import { VAlert, VButton, VEmptyState, VInput, VSelect } from '@/components';
import { useMegadodo, type MegadodoOperation } from '@/composables/useMegadodo';

/**
 * Megadodo — what happened in this project, and above all what broke.
 *
 * The reader is the project owner, not an operator: they never see the
 * server logs and should not have to. So the tab answers "is everything
 * running?" before it answers anything else — failures are highlighted
 * and reachable with one switch, not with an assembled filter.
 *
 * Rows are folded per `traceId`: a scheduler run emits START and END, and
 * the collapsed line carries the duration and the outcome. Clicking it
 * shows the individual rows.
 */
const props = defineProps<{ projectId: string | null }>();

const state = useMegadodo();
const expanded = ref<Set<string>>(new Set());

const ACTION_OPTIONS = [
  { value: '', label: 'All activity' },
  { value: 'scheduler.', label: 'Scheduler' },
  { value: 'hook.', label: 'Hooks' },
  { value: 'event.', label: 'Events' },
  { value: 'tool.', label: 'Tools' },
  { value: 'session.', label: 'Sessions' },
  { value: 'user.', label: 'Users' },
  { value: 'project.', label: 'Projects' },
];

watch(
  () => props.projectId,
  (next) => {
    expanded.value = new Set();
    if (next) void state.load(next);
    else state.clear();
  },
  { immediate: true },
);

function reload(): void {
  expanded.value = new Set();
  void state.load(props.projectId);
}

function toggleErrors(): void {
  state.filters.value.onlyErrors = !state.filters.value.onlyErrors;
  reload();
}

function onActionChange(value: string | number | null): void {
  state.filters.value.action = value === null ? '' : String(value);
  reload();
}

function toggle(traceId: string): void {
  const next = new Set(expanded.value);
  if (next.has(traceId)) next.delete(traceId);
  else next.add(traceId);
  expanded.value = next;
}

const failureCount = computed(() => state.operations.value.filter((o) => o.failed).length);
const openCount = computed(() => state.operations.value.filter((o) => o.open).length);

function formatTime(value: Date | string | undefined): string {
  if (!value) return '—';
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString();
}

function formatDuration(ms: number | null): string {
  if (ms === null || ms < 0) return '';
  if (ms < 1000) return `${ms} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  const minutes = Math.floor(ms / 60_000);
  const seconds = Math.round((ms % 60_000) / 1000);
  return `${minutes} min ${seconds} s`;
}

/** Colour of the left rail — the "is anything wrong" signal. */
function railClass(op: MegadodoOperation): string {
  if (op.failed) return 'bg-error';
  if (op.open) return 'bg-warning/60';
  if (op.tail?.outcome === 'skipped') return 'bg-warning/40';
  return 'bg-success/50';
}

function statusLabel(op: MegadodoOperation): string {
  if (op.failed) return 'failed';
  if (op.open) return 'running';
  return op.tail?.outcome ?? 'done';
}

/**
 * Deep link for the thing a row points at. Hard-wired per ref type —
 * where a session is best shown is a decision of this view, not of
 * whoever emitted the event.
 */
function linkFor(row: MegadodoEventDto): string | null {
  if (!row.refId) return null;
  const project = row.projectId ?? props.projectId ?? '';
  switch (row.refType) {
    case MegadodoRefType.SESSION:
      return `/insights.html?project=${encodeURIComponent(project)}&sel=session:${encodeURIComponent(row.refId)}`;
    case MegadodoRefType.PROCESS:
      return `/insights.html?project=${encodeURIComponent(project)}&sel=process:${encodeURIComponent(row.refId)}`;
    case MegadodoRefType.SCHEDULER:
      return `/insights.html?project=${encodeURIComponent(project)}&tab=scheduler`;
    case MegadodoRefType.HOOK:
      return `/insights.html?project=${encodeURIComponent(project)}&tab=ursahooks`;
    case MegadodoRefType.EVENT:
      return `/insights.html?project=${encodeURIComponent(project)}&tab=events`;
    case MegadodoRefType.TOOL:
      return `/insights.html?project=${encodeURIComponent(project)}&tab=health`;
    case MegadodoRefType.USER:
      return `/users.html`;
    default:
      return null;
  }
}

function logLink(row: MegadodoEventDto): string | null {
  if (!row.logPath) return null;
  const project = row.projectId ?? props.projectId ?? '';
  return `/cortex.html?project=${encodeURIComponent(project)}&path=${encodeURIComponent(row.logPath)}`;
}

/** The row that carries the log link — the END knows it, else the head. */
function logRow(op: MegadodoOperation): MegadodoEventDto | null {
  return op.rows.find((r) => r.logPath) ?? null;
}
</script>

<template>
  <div class="flex flex-col gap-3 p-3">
    <header class="flex flex-wrap items-center gap-2">
      <VButton
        size="sm"
        :variant="state.filters.value.onlyErrors ? 'danger' : 'ghost'"
        @click="toggleErrors"
      >
        {{ state.filters.value.onlyErrors ? 'Showing failures only' : 'Only failures' }}
      </VButton>

      <div class="w-44">
        <VSelect
          :model-value="state.filters.value.action"
          :options="ACTION_OPTIONS"
          @update:model-value="onActionChange"
        />
      </div>

      <div class="w-56">
        <VInput
          v-model="state.filters.value.text"
          size="sm"
          placeholder="Search message…"
          @keyup.enter="reload"
        />
      </div>

      <VButton size="sm" variant="ghost" @click="reload">Refresh</VButton>

      <span class="text-xs opacity-60 ml-auto">
        <template v-if="failureCount > 0">
          <span class="text-error font-medium">{{ failureCount }} failed</span> ·
        </template>
        <template v-if="openCount > 0">{{ openCount }} running · </template>
        {{ state.operations.value.length }} operation{{
          state.operations.value.length === 1 ? '' : 's'
        }}
      </span>
    </header>

    <VAlert v-if="state.error.value" variant="error">{{ state.error.value }}</VAlert>

    <VEmptyState v-if="!props.projectId" headline="Pick a project to see its activity." />

    <div v-else-if="state.loading.value" class="text-sm opacity-60">Loading…</div>

    <VEmptyState
      v-else-if="state.operations.value.length === 0"
      :headline="
        state.filters.value.onlyErrors
          ? 'Nothing failed in the retained window.'
          : 'No activity recorded yet.'
      "
    />

    <template v-else>
      <div class="flex flex-col gap-1">
        <article
          v-for="op in state.operations.value"
          :key="op.traceId"
          class="border border-base-content/10 rounded-lg overflow-hidden"
        >
          <header
            class="flex items-stretch gap-0 cursor-pointer hover:bg-base-200/40"
            @click="toggle(op.traceId)"
          >
            <span class="w-1 shrink-0" :class="railClass(op)"></span>

            <div class="flex flex-wrap items-baseline gap-x-3 gap-y-1 px-3 py-2 flex-1 min-w-0">
              <span class="text-sm truncate" :class="op.failed ? 'text-error' : ''">
                {{ op.tail?.message ?? op.head.message ?? op.head.action }}
              </span>

              <span
                class="text-[0.7rem] uppercase tracking-wide px-1.5 py-0.5 rounded"
                :class="
                  op.failed
                    ? 'bg-error/15 text-error'
                    : op.open
                      ? 'bg-warning/15 text-warning'
                      : 'bg-base-content/10 opacity-70'
                "
              >
                {{ statusLabel(op) }}
              </span>

              <span v-if="op.durationMs !== null" class="text-xs opacity-50 tabular-nums">
                {{ formatDuration(op.durationMs) }}
              </span>

              <span v-if="op.head.actor" class="text-xs opacity-50">{{ op.head.actor }}</span>

              <span class="text-xs opacity-50 ml-auto whitespace-nowrap">
                {{ formatTime(op.rows[0]?.timestamp) }}
              </span>
              <span class="text-xs opacity-40 w-3 text-right">
                {{ expanded.has(op.traceId) ? '▾' : '▸' }}
              </span>
            </div>
          </header>

          <div
            v-if="expanded.has(op.traceId)"
            class="border-t border-base-content/5 px-4 py-2 flex flex-col gap-2"
          >
            <div class="flex flex-wrap gap-3 text-xs">
              <a
                v-if="linkFor(op.head)"
                :href="linkFor(op.head)!"
                class="link link-hover text-primary"
              >
                {{ op.head.refType?.toLowerCase() }}: {{ op.head.refId }} ↗
              </a>
              <a
                v-if="logRow(op) && logLink(logRow(op)!)"
                :href="logLink(logRow(op)!)!"
                class="link link-hover text-primary"
              >
                Open run log ↗
              </a>
              <span class="opacity-40 font-mono">{{ op.traceId }}</span>
            </div>

            <table class="w-full text-xs">
              <tbody>
                <tr
                  v-for="row in [...op.rows].reverse()"
                  :key="row.id"
                  class="border-t border-base-content/5"
                >
                  <td class="py-1 pr-3 opacity-60 whitespace-nowrap w-44">
                    {{ formatTime(row.timestamp) }}
                  </td>
                  <td class="py-1 pr-3 opacity-60 w-16">{{ row.phase.toLowerCase() }}</td>
                  <td class="py-1 pr-3 font-mono opacity-60 w-40">{{ row.action }}</td>
                  <td class="py-1" :class="row.outcome === 'failure' ? 'text-error' : ''">
                    {{ row.message ?? '—' }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </article>
      </div>

      <div v-if="state.hasMore.value" class="flex justify-center pt-1">
        <VButton
          size="sm"
          variant="ghost"
          :disabled="state.loadingMore.value"
          @click="state.loadMore(props.projectId)"
        >
          {{ state.loadingMore.value ? 'Loading…' : 'Load older' }}
        </VButton>
      </div>
    </template>
  </div>
</template>
