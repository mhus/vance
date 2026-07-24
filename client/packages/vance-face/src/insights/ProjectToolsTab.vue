<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type {
  EffectiveToolDto,
  ToolHealthCooldownDto,
  ToolHealthEntryDto,
} from '@vance/generated';
import { VAlert, VButton, VCheckbox, VEmptyState, VInput } from '@/components';
import { useEffectiveTools, useToolHealth } from '@/composables/useProjectInsights';

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
  if (diffMs <= 0) return 'expired';
  const sec = Math.floor(diffMs / 1000);
  if (sec < 60) return `${sec}s`;
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m`;
  const hr = Math.floor(min / 60);
  const remMin = min % 60;
  if (hr < 24) return remMin > 0 ? `${hr}h ${remMin}m` : `${hr}h`;
  const days = Math.floor(hr / 24);
  const remHr = hr % 24;
  return remHr > 0 ? `${days}d ${remHr}h` : `${days}d`;
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
      return 'project';
    case 'VANCE':
      return '_vance';
    case 'BUILTIN':
      return 'built-in';
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
      Pick a project in the sidebar to see its effective tools.
    </div>

    <div v-else-if="state.loading.value" class="text-sm opacity-60">Loading tools…</div>

    <VAlert v-else-if="state.error.value" variant="error">
      {{ state.error.value }}
    </VAlert>

    <template v-else-if="state.tools.value.length === 0">
      <VEmptyState
        :headline="'No tools'"
        :body="'No built-in, tenant or project tools resolve for this project.'"
      />
    </template>

    <template v-else>
      <!-- ─── Toolbar ─── -->
      <div class="flex flex-wrap items-end gap-3 text-sm">
        <div class="flex-1 min-w-48">
          <VInput
            v-model="search"
            label="Search"
            placeholder="name, description, type, label…"
          />
        </div>

        <div class="flex flex-col gap-1">
          <span class="text-xs opacity-70">Sources</span>
          <div class="flex gap-2">
            <VCheckbox v-model="showProject" label="project" />
            <VCheckbox v-model="showVance" label="_vance" />
            <VCheckbox v-model="showBuiltin" label="built-in" />
          </div>
        </div>

        <div class="flex flex-col gap-1">
          <span class="text-xs opacity-70">Filter</span>
          <div class="flex gap-2">
            <VCheckbox v-model="primaryOnly" label="primary only" />
            <VCheckbox v-model="deferredOnly" label="deferred only" />
            <VCheckbox v-model="showDisabled" label="show disabled" />
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
              Name{{ arrow('name') }}
            </th>
            <th class="w-24 cursor-pointer select-none" @click="toggleSort('source')">
              Source{{ arrow('source') }}
            </th>
            <th class="w-20 cursor-pointer select-none" @click="toggleSort('type')">
              Type{{ arrow('type') }}
            </th>
            <th>Description</th>
            <th class="w-24">Visibility</th>
            <th class="w-28">Health</th>
            <th class="w-32">Labels</th>
            <th class="w-12"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="filteredTools.length === 0">
            <td colspan="8" class="opacity-60 text-center py-4">
              No tools match the current filters.
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
                  hint: {{ t.searchHint }}
                </div>
              </td>
              <td class="text-xs">
                <span v-if="t.deferred" class="badge-deferred" title="Hidden from manifest until describe_tool activates it">deferred</span>
                <span v-else-if="t.primary" class="text-success">primary</span>
                <span v-else class="opacity-50">on demand</span>
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
                      :title="(healthByTool.get(t.name)?.activeCooldowns?.length) + ' active cooldown(s)'"
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
                  title="Disabled by an inner-layer document"
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
                    <span class="opacity-60">note:</span>
                    {{ healthByTool.get(t.name)?.note }}
                  </div>
                  <div
                    v-if="healthByTool.get(t.name)?.expectedRecoveryAt"
                    class="opacity-80"
                  >
                    <span class="opacity-60">recovery:</span>
                    {{ healthByTool.get(t.name)?.expectedRecoveryAt }}
                    (in {{ formatCountdown(healthByTool.get(t.name)?.expectedRecoveryAt) }})
                  </div>
                  <div v-if="(healthByTool.get(t.name)?.activeCooldowns?.length ?? 0) === 0" class="opacity-60">
                    No active cooldowns.
                  </div>
                  <table v-else class="table table-xs">
                    <thead>
                      <tr class="opacity-60">
                        <th>Signature</th>
                        <th>Classification</th>
                        <th>Hits</th>
                        <th>User</th>
                        <th>Note</th>
                        <th>Expires</th>
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
                            title="Clear this cooldown — the tool can fire again immediately"
                          >
                            Clear
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
  background: oklch(var(--p) / 0.18);
  color: oklch(var(--p));
}
.badge-source--vance {
  background: oklch(var(--s) / 0.18);
  color: oklch(var(--s));
}
.badge-source--builtin {
  background: oklch(var(--b3));
  color: oklch(var(--bc) / 0.7);
}
.badge-deferred {
  display: inline-block;
  padding: 0.05rem 0.4rem;
  border-radius: 0.25rem;
  background: oklch(var(--wa) / 0.18);
  color: oklch(var(--wa));
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
  background: oklch(var(--su) / 0.18);
  color: oklch(var(--su));
}
.badge-health--degraded {
  background: oklch(var(--wa) / 0.22);
  color: oklch(var(--wa));
}
.badge-health--down {
  background: oklch(var(--er) / 0.22);
  color: oklch(var(--er));
}
.health-detail-row > td {
  background: transparent;
}
</style>
