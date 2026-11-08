<script setup lang="ts">
import { watch } from 'vue';
import type { WorkflowTaskRunInsightsDto } from '@vance/generated';
import { VAlert, VButton, VEmptyState } from '@/components';
import { useWorkflowTaskLog } from '@/composables/useWorkflowTaskLog';

const props = defineProps<{ projectId: string | null }>();

const state = useWorkflowTaskLog();

watch(
  () => props.projectId,
  (next) => {
    state.clear();
    if (next) {
      void state.load(next);
    }
  },
  { immediate: true },
);

function refresh(): void {
  if (props.projectId) {
    void state.load(props.projectId);
  }
}

function fmtTime(value: string | Date | undefined | null): string {
  if (value == null) return '—';
  const iso = value instanceof Date ? value.toISOString() : String(value);
  return iso.replace('T', ' ').slice(0, 19);
}

function fmtMs(ms: number | undefined | null): string {
  if (ms == null) return '—';
  if (ms < 1000) return `${ms} ms`;
  const s = ms / 1000;
  if (s < 60) return `${s.toFixed(1)} s`;
  const m = Math.floor(s / 60);
  return `${m} min ${Math.round(s - m * 60)} s`;
}

function outcomeClass(row: WorkflowTaskRunInsightsDto): string {
  switch (row.outcome) {
    case 'running': return 'text-info';
    case 'success': return 'opacity-80';
    case 'restarted': return 'opacity-50';
    default: return 'text-error';
  }
}

function shortId(id: string): string {
  return id.length > 8 ? id.slice(0, 8) : id;
}
</script>

<template>
  <div class="flex flex-col gap-3 p-4">
    <div v-if="!projectId" class="opacity-60 text-sm">
      {{ $t('insights.taskLog.pickProject') }}
    </div>

    <template v-else>
      <div class="flex flex-wrap items-center gap-3 text-sm">
        <span class="text-xs opacity-70 flex-1 min-w-48">{{ $t('insights.taskLog.help') }}</span>
        <VButton variant="plain" size="sm" icon="refresh" @click="refresh">
          {{ $t('insights.taskLog.refresh') }}
        </VButton>
      </div>

      <div v-if="state.loading.value && state.tasks.value.length === 0" class="text-sm opacity-60">
        {{ $t('insights.taskLog.loading') }}
      </div>

      <VAlert v-else-if="state.error.value" variant="error">
        {{ state.error.value }}
      </VAlert>

      <VEmptyState
        v-else-if="state.tasks.value.length === 0"
        :headline="$t('insights.taskLog.emptyHeadline')"
        :body="$t('insights.taskLog.emptyBody')"
      />

      <table v-else class="table table-sm">
        <thead>
          <tr>
            <th>{{ $t('insights.taskLog.colWhat') }}</th>
            <th class="w-28">{{ $t('insights.taskLog.colType') }}</th>
            <th class="w-40">{{ $t('insights.taskLog.colPod') }}</th>
            <th class="w-44">{{ $t('insights.taskLog.colStarted') }}</th>
            <th class="w-28">{{ $t('insights.taskLog.colDuration') }}</th>
            <th class="w-28">{{ $t('insights.taskLog.colOutcome') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in state.tasks.value" :key="row.workflowRunId + '/' + row.state + '/' + row.startedAt">
            <td class="text-xs">
              <span class="font-semibold">{{ row.workflowName ?? '?' }}</span>
              <span class="opacity-60"> / </span>
              <span class="font-mono">{{ row.state }}</span>
              <span class="opacity-40 font-mono ml-2" :title="row.workflowRunId">{{ shortId(row.workflowRunId) }}</span>
            </td>
            <td class="text-xs opacity-80">{{ row.taskType }}</td>
            <td class="font-mono text-xs opacity-80 truncate max-w-[10rem]" :title="row.podId ?? ''">
              {{ row.podId ?? '—' }}
            </td>
            <td class="text-xs opacity-70">{{ fmtTime(row.startedAt) }}</td>
            <td class="text-xs">{{ fmtMs(row.durationMs) }}</td>
            <td class="text-xs" :class="outcomeClass(row)">{{ row.outcome }}</td>
          </tr>
        </tbody>
      </table>
    </template>
  </div>
</template>
