<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { VAlert, VButton, VCheckbox, VEmptyState, VInput } from '@/components';
import { useExecutions, useExecutionTail } from '@/composables/useExecutions';
import type { ExecutionInsightsDto } from '@vance/generated';

const props = defineProps<{ projectId: string | null }>();

const state = useExecutions();
const tailState = useExecutionTail();

/** Mongo-style id of the row that's currently expanded; null when none. */
const expandedId = ref<string | null>(null);
const expandedStream = ref<'stdout' | 'stderr'>('stdout');
const tailLines = ref(100);

const search = ref('');

watch(
  () => props.projectId,
  (next) => {
    expandedId.value = null;
    tailState.clear();
    if (next) void state.load(next);
    else state.clear();
  },
  { immediate: true },
);

function refresh(): void {
  if (props.projectId) void state.load(props.projectId);
}

async function toggleExpand(row: ExecutionInsightsDto): Promise<void> {
  if (expandedId.value === row.id) {
    expandedId.value = null;
    tailState.clear();
    return;
  }
  expandedId.value = row.id;
  expandedStream.value = 'stdout';
  await loadTail(row.id);
}

async function loadTail(id: string): Promise<void> {
  if (!props.projectId) return;
  await tailState.load(props.projectId, id, tailLines.value, expandedStream.value);
}

async function switchStream(stream: 'stdout' | 'stderr'): Promise<void> {
  expandedStream.value = stream;
  if (expandedId.value) await loadTail(expandedId.value);
}

const filteredRows = computed<ExecutionInsightsDto[]>(() => {
  const q = search.value.trim().toLowerCase();
  if (!q) return state.list.value;
  return state.list.value.filter(r =>
    (r.command ?? '').toLowerCase().includes(q)
    || (r.id ?? '').toLowerCase().includes(q)
    || (r.owner ?? '').toLowerCase().includes(q)
    || (r.sessionId ?? '').toLowerCase().includes(q));
});

function statusClass(status: string): string {
  switch (status) {
    case 'RUNNING':   return 'badge-status badge-status--running';
    case 'COMPLETED': return 'badge-status badge-status--completed';
    case 'FAILED':    return 'badge-status badge-status--failed';
    case 'KILLED':    return 'badge-status badge-status--killed';
    case 'ORPHANED':  return 'badge-status badge-status--orphaned';
    default:          return 'badge-status';
  }
}

function fmtTime(value: string | Date | undefined | null): string {
  if (value == null) return '—';
  if (value instanceof Date) return value.toISOString().replace('T', ' ').slice(0, 19);
  // brainFetch returns ISO strings; trim to date+time for the table.
  return String(value).replace('T', ' ').slice(0, 19);
}

function shortId(id: string): string {
  // Long uuid-style ids get a leading-/trailing-slice render so the table stays narrow.
  return id.length > 14 ? id.slice(0, 6) + '…' + id.slice(-6) : id;
}
</script>

<template>
  <div class="flex flex-col gap-3 p-4">
    <div v-if="!projectId" class="opacity-60 text-sm">
      {{ $t('insights.executions.pickProject') }}
    </div>

    <template v-else>
      <!-- ─── Toolbar ─── -->
      <div class="flex flex-wrap items-end gap-3 text-sm">
        <div class="flex-1 min-w-48">
          <VInput
            v-model="search"
            :label="$t('insights.executions.search')"
            :placeholder="$t('insights.executions.searchPlaceholder')"
          />
        </div>

        <div class="flex flex-col gap-1">
          <span class="text-xs opacity-70">{{ $t('insights.executions.filter') }}</span>
          <div class="flex gap-2 items-center">
            <VCheckbox
              :model-value="state.filters.onlyRunning"
              :label="$t('insights.executions.onlyRunning')"
              @update:model-value="(v: boolean) => { state.filters.onlyRunning = v; refresh(); }"
            />
          </div>
        </div>

        <VButton variant="ghost" size="sm" @click="refresh">
          {{ $t('insights.executions.refresh') }}
        </VButton>

        <div class="text-xs opacity-60 ml-auto">
          {{ filteredRows.length }} / {{ state.list.value.length }}
        </div>
      </div>

      <div v-if="state.loading.value" class="text-sm opacity-60">{{ $t('insights.executions.loading') }}</div>

      <VAlert v-else-if="state.error.value" variant="error">
        {{ state.error.value }}
      </VAlert>

      <VEmptyState
        v-else-if="state.list.value.length === 0"
        :headline="$t('insights.executions.emptyHeadline')"
        :body="$t('insights.executions.emptyBody')"
      />

      <table v-else class="table table-sm">
        <thead>
          <tr>
            <th class="w-32">{{ $t('insights.executions.colId') }}</th>
            <th class="w-24">{{ $t('insights.executions.colOwner') }}</th>
            <th class="w-24">{{ $t('insights.executions.colStatus') }}</th>
            <th>{{ $t('insights.executions.colCommand') }}</th>
            <th class="w-44">{{ $t('insights.executions.colStarted') }}</th>
            <th class="w-44">{{ $t('insights.executions.colLastOutput') }}</th>
            <th class="w-16">{{ $t('insights.executions.colExit') }}</th>
            <th class="w-12"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="filteredRows.length === 0">
            <td colspan="8" class="opacity-60 text-center py-4">
              {{ $t('insights.executions.noMatch') }}
            </td>
          </tr>
          <template v-for="row in filteredRows" :key="row.id">
            <tr
              class="cursor-pointer hover:bg-base-200/40"
              :class="{ 'bg-base-200/40': expandedId === row.id }"
              @click="toggleExpand(row)"
            >
              <td class="font-mono text-xs" :title="row.id">{{ shortId(row.id) }}</td>
              <td class="text-xs opacity-80">{{ row.owner }}</td>
              <td>
                <span :class="statusClass(row.status)">{{ row.status.toLowerCase() }}</span>
              </td>
              <td class="font-mono text-xs truncate max-w-[28rem]" :title="row.command">
                {{ row.command }}
              </td>
              <td class="text-xs opacity-70">{{ fmtTime(row.startedAt) }}</td>
              <td class="text-xs opacity-70">{{ fmtTime(row.lastOutputAt) }}</td>
              <td class="text-xs opacity-80">{{ row.exitCode ?? '—' }}</td>
              <td class="text-xs">{{ expandedId === row.id ? '▾' : '▸' }}</td>
            </tr>

            <tr v-if="expandedId === row.id">
              <td colspan="8" class="bg-base-200/20 py-3">
                <div class="flex flex-col gap-2">
                  <div class="flex flex-wrap gap-x-4 gap-y-1 text-xs">
                    <div><span class="opacity-60">{{ $t('insights.executions.fieldId') }}</span> <span class="font-mono">{{ row.id }}</span></div>
                    <div v-if="row.sessionId">
                      <span class="opacity-60">{{ $t('insights.executions.fieldSession') }}</span>
                      <span class="font-mono">{{ row.sessionId }}</span>
                    </div>
                    <div v-if="row.processId">
                      <span class="opacity-60">{{ $t('insights.executions.fieldProcess') }}</span>
                      <span class="font-mono">{{ row.processId }}</span>
                    </div>
                    <div v-if="row.dirName">
                      <span class="opacity-60">{{ $t('insights.executions.fieldRootDir') }}</span>
                      <span class="font-mono">{{ row.dirName }}</span>
                    </div>
                    <div v-if="row.endedAt">
                      <span class="opacity-60">{{ $t('insights.executions.fieldEnded') }}</span>
                      {{ fmtTime(row.endedAt) }}
                    </div>
                  </div>

                  <div class="flex items-center gap-3 text-xs">
                    <div class="flex gap-1">
                      <button
                        type="button"
                        class="stream-btn"
                        :class="{ 'stream-btn--active': expandedStream === 'stdout' }"
                        @click="switchStream('stdout')"
                      >stdout</button>
                      <button
                        type="button"
                        class="stream-btn"
                        :class="{ 'stream-btn--active': expandedStream === 'stderr' }"
                        @click="switchStream('stderr')"
                      >stderr</button>
                    </div>
                    <label class="flex items-center gap-1">
                      <span class="opacity-70">{{ $t('insights.executions.last') }}</span>
                      <input
                        v-model.number="tailLines"
                        type="number"
                        min="10"
                        max="1000"
                        class="w-16 px-1 py-0.5 rounded border border-base-300 bg-base-100 text-xs"
                        @change="loadTail(row.id)"
                      />
                      <span class="opacity-70">{{ $t('insights.executions.lines') }}</span>
                    </label>
                    <VButton variant="ghost" size="sm" @click="loadTail(row.id)">
                      {{ $t('insights.executions.refreshTail') }}
                    </VButton>
                  </div>

                  <VAlert v-if="tailState.error.value" variant="error">
                    {{ tailState.error.value }}
                  </VAlert>

                  <div
                    v-else-if="tailState.loading.value"
                    class="text-xs opacity-60"
                  >{{ $t('insights.executions.loadingStream', { stream: expandedStream }) }}</div>

                  <pre
                    v-else-if="tailState.tail.value"
                    class="output-pane"
                  >{{ tailState.tail.value.lines.length === 0
                    ? $t('insights.executions.emptyStream')
                    : tailState.tail.value.lines.join('\n') }}</pre>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </template>
  </div>
</template>

<style scoped>
.badge-status {
  display: inline-block;
  padding: 0.1rem 0.45rem;
  border-radius: 0.25rem;
  font-size: 0.7rem;
  font-weight: 500;
  text-transform: lowercase;
}
.badge-status--running   { background: color-mix(in oklab, var(--color-info) 18%, transparent); color: var(--color-info); }
.badge-status--completed { background: color-mix(in oklab, var(--color-success) 18%, transparent); color: var(--color-success); }
.badge-status--failed    { background: color-mix(in oklab, var(--color-error) 18%, transparent); color: var(--color-error); }
.badge-status--killed    { background: color-mix(in oklab, var(--color-warning) 18%, transparent); color: var(--color-warning); }
.badge-status--orphaned  { background: color-mix(in oklab, var(--color-base-content) 10%, transparent); color: color-mix(in oklab, var(--color-base-content) 70%, transparent); }

.stream-btn {
  padding: 0.15rem 0.55rem;
  border-radius: 0.25rem;
  border: 1px solid transparent;
  background: transparent;
  font-family: ui-monospace, monospace;
  cursor: pointer;
}
.stream-btn:hover { background: color-mix(in oklab, var(--color-base-content) 6%, transparent); }
.stream-btn--active {
  background: color-mix(in oklab, var(--color-primary) 12%, transparent);
  border-color: color-mix(in oklab, var(--color-primary) 30%, transparent);
  font-weight: 600;
}

.output-pane {
  background: color-mix(in oklab, var(--color-neutral) 5%, transparent);
  border: 1px solid color-mix(in oklab, var(--color-base-content) 10%, transparent);
  border-radius: 0.375rem;
  padding: 0.5rem 0.75rem;
  font-size: 0.75rem;
  font-family: ui-monospace, monospace;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 24rem;
  overflow: auto;
}
</style>
