<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type {
  EffectiveToolDto,
  ToolHealthCooldownDto,
  ToolHealthEntryDto,
} from '@vance/generated';
import { VAlert, VButton, VCheckbox, VEmptyState, VInput } from '@/components';
import { useEffectiveTools, useToolHealth } from '@/composables/useProjectInsights';
import { useI18n } from 'vue-i18n';

const { t } = useI18n();

const props = defineProps<{ projectId: string | null }>();

const state = useEffectiveTools();
const health = useToolHealth();

watch(
  () => props.projectId,
  (next) => {
    if (next) {
      state.load(next);
      health.load(next);
    } else {
      state.clear();
      health.clear();
    }
  },
  { immediate: true },
);

// Map: toolName → health entry. Fast lookup during render.
const healthByTool = computed<Map<string, ToolHealthEntryDto>>(() => {
  const m = new Map<string, ToolHealthEntryDto>();
  for (const h of health.entries.value) m.set(h.toolName, h);
  return m;
});

// Live countdown — re-evaluates every 10s so "in 27 minutes" stays fresh.
const nowMs = ref(Date.now());
let nowTimer: ReturnType<typeof setInterval> | null = null;
if (typeof window !== 'undefined') {
  nowTimer = setInterval(() => {
    nowMs.value = Date.now();
  }, 10_000);
}
import { onUnmounted } from 'vue';
onUnmounted(() => {
  if (nowTimer) clearInterval(nowTimer);
});

function formatCountdown(iso: string | null | undefined): string {
  if (!iso) return '—';
  const target = Date.parse(iso);
  if (Number.isNaN(target)) return iso;
  const diffMs = target - nowMs.value;
  if (diffMs <= 0) return t('insights.projectTools.countdown.expired');
  const sec = Math.floor(diffMs / 1000);
  if (sec < 60) return t('insights.projectTools.countdown.seconds', { n: sec });
  const min = Math.floor(sec / 60);
  if (min < 60) return t('insights.projectTools.countdown.minutes', { n: min });
  const hr = Math.floor(min / 60);
  const remMin = min % 60;
  if (hr < 24) {
    return remMin > 0
      ? t('insights.projectTools.countdown.hoursMinutes', { h: hr, m: remMin })
      : t('insights.projectTools.countdown.hours', { n: hr });
  }
  const days = Math.floor(hr / 24);
  const remHr = hr % 24;
  return remHr > 0
    ? t('insights.projectTools.countdown.daysHours', { d: days, h: remHr })
    : t('insights.projectTools.countdown.days', { n: days });
}

function statusBadgeClass(status: string | undefined): string {
  switch (status) {
    case 'DOWN':
      return 'badge-health badge-health--down';
    case 'DEGRADED':
      return 'badge-health badge-health--degraded';
    case 'OK':
    default:
      return 'badge-health badge-health--ok';
  }
}

const expanded = ref<Set<string>>(new Set());
function toggleExpand(toolName: string): void {
  const next = new Set(expanded.value);
  if (next.has(toolName)) next.delete(toolName);
  else next.add(toolName);
  expanded.value = next;
}

async function onClearCooldown(
  toolName: string,
  cd: ToolHealthCooldownDto,
): Promise<void> {
  if (!props.projectId) return;
  await health.clearCooldown(
    props.projectId,
    toolName,
    cd.errorSignature,
    cd.userId ?? null,
  );
}

function sourceClass(source: string): string {
  switch (source) {
    case 'PROJECT':
      return 'badge-source badge-source--project';
    case 'VANCE':
      return 'badge-source badge-source--vance';
    case 'BUILTIN':
      return 'badge-source badge-source--builtin';
    default:
      return 'badge-source';
  }
}

function sourceLabel(source: string): string {
  switch (source) {
    case 'PROJECT':
      return t('insights.projectTools.sourceProject');
    case 'VANCE':
      return t('insights.projectTools.sourceVance');
    case 'BUILTIN':
      return t('insights.projectTools.sourceBuiltin');
    default:
      return source.toLowerCase();
  }
}

// ─── Filter / sort state ───────────────────────────────────────────────
type SortKey = 'name' | 'source' | 'type';
const search = ref('');
const sortKey = ref<SortKey>('name');
const sortAsc = ref(true);
const showProject = ref(true);
const showVance = ref(true);
const showBuiltin = ref(true);
const primaryOnly = ref(false);
const deferredOnly = ref(false);
const showDisabled = ref(true);

function toggleSort(key: SortKey): void {
  if (sortKey.value === key) {
    sortAsc.value = !sortAsc.value;
  } else {
    sortKey.value = key;
    sortAsc.value = true;
  }
}

function arrow(key: SortKey): string {
  if (sortKey.value !== key) return '';
  return sortAsc.value ? ' ▲' : ' ▼';
}

const filteredTools = computed<EffectiveToolDto[]>(() => {
  const all = state.tools.value;
  const q = search.value.trim().toLowerCase();
  const wanted = new Set<string>();
  if (showProject.value) wanted.add('PROJECT');
  if (showVance.value) wanted.add('VANCE');
  if (showBuiltin.value) wanted.add('BUILTIN');

  const out = all.filter((t) => {
    if (!wanted.has(t.source)) return false;
    if (primaryOnly.value && !t.primary) return false;
    if (deferredOnly.value && !t.deferred) return false;
    if (!showDisabled.value && t.disabledByInnerLayer) return false;
    if (q.length === 0) return true;
    return (
      (t.name ?? '').toLowerCase().includes(q)
      || (t.description ?? '').toLowerCase().includes(q)
      || (t.type ?? '').toLowerCase().includes(q)
      || (t.searchHint ?? '').toLowerCase().includes(q)
      || (t.labels ?? []).some((l) => l.toLowerCase().includes(q))
    );
  });

  const dir = sortAsc.value ? 1 : -1;
  return [...out].sort((a, b) => {
    const av = (a[sortKey.value] ?? '') as string;
    const bv = (b[sortKey.value] ?? '') as string;
    return av.localeCompare(bv) * dir;
  });
});
</script>

<template>
  <div class="flex flex-col gap-3 p-4">
    <div v-if="!projectId" class="opacity-60 text-sm">
      {{ $t('insights.projectTools.pickProject') }}
    </div>

    <div v-else-if="state.loading.value" class="text-sm opacity-60">{{ $t('insights.projectTools.loading') }}</div>

    <VAlert v-else-if="state.error.value" variant="error">
      {{ state.error.value }}
    </VAlert>

    <template v-else-if="state.tools.value.length === 0">
      <VEmptyState
        :headline="$t('insights.projectTools.emptyHeadline')"
        :body="$t('insights.projectTools.emptyBody')"
      />
    </template>

    <template v-else>
      <!-- ─── Toolbar ─── -->
      <div class="flex flex-wrap items-end gap-3 text-sm">
        <div class="flex-1 min-w-48">
          <VInput
            v-model="search"
            :label="$t('insights.projectTools.search')"
            :placeholder="$t('insights.projectTools.searchPlaceholder')"
          />
        </div>

        <div class="flex flex-col gap-1">
          <span class="text-xs opacity-70">{{ $t('insights.projectTools.sources') }}</span>
          <div class="flex gap-2">
            <VCheckbox v-model="showProject" :label="$t('insights.projectTools.sourceProject')" />
            <VCheckbox v-model="showVance" :label="$t('insights.projectTools.sourceVance')" />
            <VCheckbox v-model="showBuiltin" :label="$t('insights.projectTools.sourceBuiltin')" />
          </div>
        </div>

        <div class="flex flex-col gap-1">
          <span class="text-xs opacity-70">{{ $t('insights.projectTools.filter') }}</span>
          <div class="flex gap-2">
            <VCheckbox v-model="primaryOnly" :label="$t('insights.projectTools.primaryOnly')" />
            <VCheckbox v-model="deferredOnly" :label="$t('insights.projectTools.deferredOnly')" />
            <VCheckbox v-model="showDisabled" :label="$t('insights.projectTools.showDisabled')" />
          </div>
        </div>

        <div class="text-xs opacity-60 ml-auto">
          {{ filteredTools.length }} / {{ state.tools.value.length }}
        </div>
      </div>

      <table class="table table-sm">
        <thead>
          <tr>
            <th class="w-40 cursor-pointer select-none" @click="toggleSort('name')">
              {{ $t('insights.projectTools.colName') }}{{ arrow('name') }}
            </th>
            <th class="w-24 cursor-pointer select-none" @click="toggleSort('source')">
              {{ $t('insights.projectTools.colSource') }}{{ arrow('source') }}
            </th>
            <th class="w-20 cursor-pointer select-none" @click="toggleSort('type')">
              {{ $t('insights.projectTools.colType') }}{{ arrow('type') }}
            </th>
            <th>{{ $t('insights.projectTools.colDescription') }}</th>
            <th class="w-24">{{ $t('insights.projectTools.colVisibility') }}</th>
            <th class="w-28">{{ $t('insights.projectTools.colHealth') }}</th>
            <th class="w-32">{{ $t('insights.projectTools.colLabels') }}</th>
            <th class="w-12"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="filteredTools.length === 0">
            <td colspan="8" class="opacity-60 text-center py-4">
              {{ $t('insights.projectTools.noMatch') }}
            </td>
          </tr>
          <template v-for="t in filteredTools" :key="t.name">
            <tr :class="t.disabledByInnerLayer ? 'opacity-50 line-through' : ''">
              <td class="font-mono">{{ t.name }}</td>
              <td>
                <span :class="sourceClass(t.source)">{{ sourceLabel(t.source) }}</span>
              </td>
              <td class="text-xs opacity-80">{{ t.type ?? '—' }}</td>
              <td class="text-xs opacity-80">
                {{ t.description }}
                <div
                  v-if="t.deferred && t.searchHint"
                  class="text-[0.65rem] opacity-60 italic mt-0.5"
                  :title="t.searchHint"
                >
                  {{ $t('insights.projectTools.hint', { text: t.searchHint }) }}
                </div>
              </td>
              <td class="text-xs">
                <span
                  v-if="t.deferred"
                  class="badge-deferred"
                  :title="$t('insights.projectTools.deferredTitle')"
                >{{ $t('insights.projectTools.deferred') }}</span>
                <span v-else-if="t.primary" class="text-success">{{ $t('insights.projectTools.primary') }}</span>
                <span v-else class="opacity-50">{{ $t('insights.projectTools.onDemand') }}</span>
              </td>
              <td class="text-xs">
                <template v-if="healthByTool.get(t.name)">
                  <button
                    type="button"
                    class="inline-flex items-center gap-1.5 cursor-pointer"
                    @click="toggleExpand(t.name)"
                    :title="healthByTool.get(t.name)?.note ?? ''"
                  >
                    <span :class="statusBadgeClass(healthByTool.get(t.name)?.status)">
                      {{ healthByTool.get(t.name)?.status }}
                    </span>
                    <span
                      v-if="(healthByTool.get(t.name)?.activeCooldowns?.length ?? 0) > 0"
                      class="text-warning text-[0.65rem]"
                      :title="$t('insights.projectTools.activeCooldowns', {
                        count: healthByTool.get(t.name)?.activeCooldowns?.length,
                      })"
                    >
                      ⏳ {{ healthByTool.get(t.name)?.activeCooldowns?.length }}
                    </span>
                  </button>
                </template>
                <span v-else class="opacity-40">—</span>
              </td>
              <td class="text-xs">
                <span v-if="t.labels && t.labels.length" class="font-mono opacity-70">
                  {{ t.labels.join(', ') }}
                </span>
                <span v-else class="opacity-50">—</span>
              </td>
              <td>
                <span
                  v-if="t.disabledByInnerLayer"
                  class="text-xs text-error"
                  :title="$t('insights.projectTools.disabledByInner')"
                >
                  ✕
                </span>
              </td>
            </tr>
            <tr
              v-if="expanded.has(t.name) && healthByTool.get(t.name)"
              class="health-detail-row"
            >
              <td colspan="8" class="p-3">
                <div class="bg-base-200 rounded p-3 space-y-2 text-xs">
                  <div v-if="healthByTool.get(t.name)?.note" class="opacity-80">
                    <span class="opacity-60">{{ $t('insights.projectTools.note') }}</span>
                    {{ healthByTool.get(t.name)?.note }}
                  </div>
                  <div
                    v-if="healthByTool.get(t.name)?.expectedRecoveryAt"
                    class="opacity-80"
                  >
                    <span class="opacity-60">{{ $t('insights.projectTools.recovery') }}</span>
                    {{ healthByTool.get(t.name)?.expectedRecoveryAt }}
                    {{ $t('insights.projectTools.recoveryIn', {
                      countdown: formatCountdown(healthByTool.get(t.name)?.expectedRecoveryAt),
                    }) }}
                  </div>
                  <div v-if="(healthByTool.get(t.name)?.activeCooldowns?.length ?? 0) === 0" class="opacity-60">
                    {{ $t('insights.projectTools.noCooldowns') }}
                  </div>
                  <table v-else class="table table-xs">
                    <thead>
                      <tr class="opacity-60">
                        <th>{{ $t('insights.projectTools.colSignature') }}</th>
                        <th>{{ $t('insights.projectTools.colClassification') }}</th>
                        <th>{{ $t('insights.projectTools.colHits') }}</th>
                        <th>{{ $t('insights.projectTools.colUser') }}</th>
                        <th>{{ $t('insights.projectTools.colNote') }}</th>
                        <th>{{ $t('insights.projectTools.colExpires') }}</th>
                        <th></th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr
                        v-for="cd in (healthByTool.get(t.name)?.activeCooldowns ?? [])"
                        :key="cd.errorSignature + '|' + (cd.userId ?? '*')"
                      >
                        <td class="font-mono">{{ cd.errorSignature }}</td>
                        <td>{{ cd.lastClassification ?? '—' }}</td>
                        <td>{{ cd.hits }}</td>
                        <td>{{ cd.userId ?? '*' }}</td>
                        <td class="opacity-80">{{ cd.note ?? '—' }}</td>
                        <td>
                          {{ formatCountdown(cd.nextSpawnAllowedAt) }}
                          <span class="opacity-50">({{ cd.nextSpawnAllowedAt }})</span>
                        </td>
                        <td>
                          <VButton
                            variant="neutral"
                            size="xs"
                            :outline="true"
                            @click="onClearCooldown(t.name, cd)"
                            :title="$t('insights.projectTools.clearTitle')"
                          >
                            {{ $t('insights.projectTools.clear') }}
                          </VButton>
                        </td>
                      </tr>
                    </tbody>
                  </table>
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
.badge-source {
  display: inline-block;
  padding: 0.1rem 0.45rem;
  border-radius: 0.25rem;
  font-size: 0.7rem;
  font-weight: 500;
  text-transform: lowercase;
}
.badge-source--project {
  background: color-mix(in oklab, var(--color-primary) 18%, transparent);
  color: var(--color-primary);
}
.badge-source--vance {
  background: color-mix(in oklab, var(--color-secondary) 18%, transparent);
  color: var(--color-secondary);
}
.badge-source--builtin {
  background: var(--color-base-300);
  color: color-mix(in oklab, var(--color-base-content) 70%, transparent);
}
.badge-deferred {
  display: inline-block;
  padding: 0.05rem 0.4rem;
  border-radius: 0.25rem;
  background: color-mix(in oklab, var(--color-warning) 18%, transparent);
  color: var(--color-warning);
  font-size: 0.7rem;
  font-weight: 500;
}
.badge-health {
  display: inline-block;
  padding: 0.05rem 0.4rem;
  border-radius: 0.25rem;
  font-size: 0.7rem;
  font-weight: 500;
}
.badge-health--ok {
  background: color-mix(in oklab, var(--color-success) 18%, transparent);
  color: var(--color-success);
}
.badge-health--degraded {
  background: color-mix(in oklab, var(--color-warning) 22%, transparent);
  color: var(--color-warning);
}
.badge-health--down {
  background: color-mix(in oklab, var(--color-error) 22%, transparent);
  color: var(--color-error);
}
.health-detail-row > td {
  background: transparent;
}
</style>
