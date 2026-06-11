<script setup lang="ts">
import { computed, watch } from 'vue';
import type { ZarniwoopInsightsDto } from '@vance/generated';
import { VAlert, VEmptyState } from '@/components';
import { useZarniwoopInsights } from '@/composables/useProjectInsights';

const props = defineProps<{ projectId: string | null }>();

const state = useZarniwoopInsights();

watch(
  () => props.projectId,
  (next) => {
    if (next) state.load(next);
    else state.clear();
  },
  { immediate: true },
);

function reload(): void {
  if (props.projectId) state.load(props.projectId);
}

function availabilityClass(availability: string): string {
  switch (availability) {
    case 'READY':
      return 'badge badge--ok';
    case 'NO_CREDENTIALS':
      return 'badge badge--warning';
    case 'QUOTA_EXHAUSTED':
    case 'COOLDOWN':
      return 'badge badge--error';
    case 'DISABLED':
    default:
      return 'badge badge--muted';
  }
}

function formatTimestamp(iso: string | undefined): string {
  if (!iso) return '—';
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return iso;
  const d = new Date(t);
  return d.toLocaleString();
}

function formatDuration(now: number, iso: string | undefined): string {
  if (!iso) return '';
  const target = Date.parse(iso);
  if (Number.isNaN(target)) return '';
  const ms = target - now;
  if (ms <= 0) return ' (elapsed)';
  const minutes = Math.round(ms / 60_000);
  if (minutes < 60) return ` (in ${minutes}m)`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return ` (in ${hours}h)`;
  return ` (in ${Math.round(hours / 24)}d)`;
}

const sorted = computed<ZarniwoopInsightsDto[]>(() => {
  const out = [...state.instances.value];
  out.sort((a, b) => a.id.localeCompare(b.id));
  return out;
});

const totals = computed(() => {
  let calls = 0;
  let ok = 0;
  let errors = 0;
  for (const inst of state.instances.value) {
    calls += inst.callCount;
    ok += inst.okCount;
    errors += inst.errorCount;
  }
  return { calls, ok, errors };
});

const now = Date.now();
</script>

<template>
  <div class="flex flex-col gap-3 p-4">
    <div v-if="!projectId" class="opacity-60 text-sm">
      Pick a project in the sidebar to see its search providers.
    </div>

    <div v-else-if="state.loading.value" class="text-sm opacity-60">
      Loading search providers…
    </div>

    <VAlert v-else-if="state.error.value" variant="error">
      {{ state.error.value }}
    </VAlert>

    <template v-else-if="state.instances.value.length === 0">
      <VEmptyState
        headline="No search providers configured"
        body="This project has no research.endpoint.* entries. Add one in the settings editor — e.g. research.endpoint.serper-main.protocol = serper."
      />
    </template>

    <template v-else>
      <div class="flex items-end gap-4 text-sm">
        <div class="opacity-70">
          {{ sorted.length }} instance(s)
        </div>
        <div class="opacity-70">
          · {{ totals.calls }} call{{ totals.calls === 1 ? '' : 's' }}
          ({{ totals.ok }} ok / {{ totals.errors }} error)
        </div>
        <button
          class="btn btn-xs ml-auto"
          @click="reload"
          :disabled="state.loading.value"
        >
          Reload
        </button>
      </div>

      <table class="table table-sm">
        <thead>
          <tr>
            <th class="w-40">Instance</th>
            <th class="w-24">Protocol</th>
            <th>Modalities</th>
            <th class="w-32">Availability</th>
            <th>Status</th>
            <th class="w-32 text-right">Calls (ok / err)</th>
            <th class="w-40">Last used</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="inst in sorted" :key="inst.id">
            <td class="font-mono">
              {{ inst.id }}
              <div class="text-xs opacity-60">{{ inst.displayName }}</div>
            </td>
            <td class="font-mono opacity-80">{{ inst.protocol }}</td>
            <td class="text-xs">
              <span
                v-for="m in inst.modalities"
                :key="m"
                class="badge badge--tag mr-1"
              >{{ m.toLowerCase() }}</span>
            </td>
            <td>
              <span :class="availabilityClass(inst.availability)">
                {{ inst.availability }}
              </span>
              <div
                v-if="inst.activeCooldownUntil"
                class="text-xs opacity-60 mt-1"
                :title="inst.activeCooldownSignature ?? ''"
              >
                cooldown until {{ formatTimestamp(inst.activeCooldownUntil) }}
                {{ formatDuration(now, inst.activeCooldownUntil) }}
              </div>
            </td>
            <td class="text-xs opacity-80">
              <span v-if="inst.statusText">{{ inst.statusText }}</span>
              <span v-else class="opacity-50">—</span>
              <div
                v-if="inst.lastErrorMessage"
                class="text-xs text-error mt-1"
                :title="inst.lastErrorAt ?? ''"
              >
                last error: {{ inst.lastErrorMessage }}
              </div>
            </td>
            <td class="text-right text-xs">
              {{ inst.callCount }}
              <span class="opacity-60">({{ inst.okCount }} / {{ inst.errorCount }})</span>
            </td>
            <td class="text-xs opacity-80">
              {{ formatTimestamp(inst.lastUsedAt) }}
            </td>
          </tr>
        </tbody>
      </table>

      <div class="text-xs opacity-50">
        Counters are pod-local and reset when the project is suspended.
        The persistent audit log lives at
        <span class="font-mono">_vance/logs/research/</span>.
      </div>
    </template>
  </div>
</template>
