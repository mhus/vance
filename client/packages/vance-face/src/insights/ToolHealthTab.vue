<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue';
import { getTenantId } from '@vance/shared';
import { ToolHealthScope } from '@vance/generated';
import type { ToolHealthCooldownDto, ToolHealthEntryDto } from '@vance/generated';
import { VAlert, VButton, VCheckbox, VEmptyState, VInput, VSelect } from '@/components';
import { useToolHealth } from '@/composables/useProjectInsights';

/**
 * Health-first view on the tool-health records of one scope.
 *
 * The Health column in the Tools tab joins records onto the effective
 * tool list — which silently drops every record whose subject is not a
 * tool. Provider gates write exactly those: `centauri:<instance>`
 * (feed sources) and `research:<instance>:<MODALITY>` (search
 * providers). This tab lists the records themselves, so a cooldown is
 * reachable regardless of whether a tool of that name exists.
 */
const props = defineProps<{ projectId: string | null }>();

const health = useToolHealth();

// ─── Scope ─────────────────────────────────────────────────────────────
// PROJECT is what the provider gates write; TENANT is where Agrajag
// escalates a tool it considers broken beyond one project.
const scope = ref<ToolHealthScope>(ToolHealthScope.PROJECT);
const SCOPE_OPTIONS = [
  { value: ToolHealthScope.PROJECT, label: 'Project' },
  { value: ToolHealthScope.TENANT, label: 'Tenant' },
];

/** The `scopeId` the current scope needs — project name or tenant id. */
const scopeId = computed<string | null>(() =>
  scope.value === ToolHealthScope.TENANT ? getTenantId() : props.projectId,
);

watch(
  [scopeId, scope],
  ([id, sc]) => {
    if (id) health.load(id, sc);
    else health.clear();
  },
  { immediate: true },
);

function reload(): void {
  if (scopeId.value) health.load(scopeId.value, scope.value);
}

// ─── Live countdown ────────────────────────────────────────────────────
// Re-evaluated every 10s so a "27m" cooldown stays honest without a
// reload. Same cadence as the Tools tab.
const nowMs = ref(Date.now());
const nowTimer =
  typeof window === 'undefined'
    ? null
    : setInterval(() => {
        nowMs.value = Date.now();
      }, 10_000);
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

function formatTimestamp(iso: string | null | undefined): string {
  if (!iso) return '—';
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return iso;
  return new Date(t).toLocaleString();
}

// ─── Subject kind ──────────────────────────────────────────────────────
// A health record's name is a cooldown *subject*, not necessarily a tool
// name. The two known non-tool namespaces are worth labelling, because
// they are precisely the records the Tools tab cannot show.
type SubjectKind = 'feed' | 'search' | 'tool';

function subjectKind(toolName: string): SubjectKind {
  if (toolName.startsWith('centauri:')) return 'feed';
  if (toolName.startsWith('research:')) return 'search';
  return 'tool';
}

function subjectKindLabel(kind: SubjectKind): string {
  switch (kind) {
    case 'feed':
      return 'feed source';
    case 'search':
      return 'search provider';
    default:
      return 'tool';
  }
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

function cooldownCount(entry: ToolHealthEntryDto): number {
  return entry.activeCooldowns?.length ?? 0;
}

// ─── Filter / sort ─────────────────────────────────────────────────────
type SortKey = 'toolName' | 'status';
const search = ref('');
const cooldownOnly = ref(false);
const sortKey = ref<SortKey>('toolName');
const sortAsc = ref(true);

function toggleSort(key: SortKey): void {
  if (sortKey.value === key) sortAsc.value = !sortAsc.value;
  else {
    sortKey.value = key;
    sortAsc.value = true;
  }
}

function arrow(key: SortKey): string {
  if (sortKey.value !== key) return '';
  return sortAsc.value ? ' ▲' : ' ▼';
}

const filtered = computed<ToolHealthEntryDto[]>(() => {
  const q = search.value.trim().toLowerCase();
  const out = health.entries.value.filter((e) => {
    if (cooldownOnly.value && cooldownCount(e) === 0) return false;
    if (q.length === 0) return true;
    return (
      e.toolName.toLowerCase().includes(q)
      || (e.note ?? '').toLowerCase().includes(q)
      || (e.classification ?? '').toLowerCase().includes(q)
      || (e.activeCooldowns ?? []).some((cd) =>
        cd.errorSignature.toLowerCase().includes(q),
      )
    );
  });
  const dir = sortAsc.value ? 1 : -1;
  return [...out].sort(
    (a, b) => ((a[sortKey.value] ?? '') as string).localeCompare(
      (b[sortKey.value] ?? '') as string,
    ) * dir,
  );
});

const totalCooldowns = computed(() =>
  health.entries.value.reduce((sum, e) => sum + cooldownCount(e), 0),
);

// ─── Expand / act ──────────────────────────────────────────────────────
// Records with an active cooldown start expanded — that is the row the
// operator came for, and hiding its Clear button behind a click is one
// step too many. Everything else starts collapsed and opens on click.
//
// Two sets, not one: deriving "expanded" from the cooldown count made the
// chevron a no-op for every OK and DEGRADED row, because toggling one only
// ever removed it from `collapsed`, which it was never in. Those rows have a
// detail block — expectedRecoveryAt, "No active cooldowns" — that could not
// be reached at all. `expanded` records the clicks that open, `collapsed` the
// clicks that close, and the cooldown count only decides the starting state.
const collapsed = ref<Set<string>>(new Set());
const expanded = ref<Set<string>>(new Set());

function isExpanded(entry: ToolHealthEntryDto): boolean {
  if (collapsed.value.has(entry.toolName)) return false;
  if (expanded.value.has(entry.toolName)) return true;
  return cooldownCount(entry) > 0;
}

function toggleExpand(entry: ToolHealthEntryDto): void {
  const nextCollapsed = new Set(collapsed.value);
  const nextExpanded = new Set(expanded.value);
  if (isExpanded(entry)) {
    nextCollapsed.add(entry.toolName);
    nextExpanded.delete(entry.toolName);
  } else {
    nextExpanded.add(entry.toolName);
    nextCollapsed.delete(entry.toolName);
  }
  collapsed.value = nextCollapsed;
  expanded.value = nextExpanded;
}

async function onClearCooldown(
  toolName: string,
  cd: ToolHealthCooldownDto,
): Promise<void> {
  if (!scopeId.value) return;
  await health.clearCooldown(
    scopeId.value,
    toolName,
    cd.errorSignature,
    cd.userId ?? null,
    scope.value,
  );
}
</script>

<template>
  <div class="flex flex-col gap-3 p-4">
    <!-- ─── Toolbar ─── -->
    <div class="flex flex-wrap items-end gap-3 text-sm">
      <div class="w-40">
        <VSelect v-model="scope" :options="SCOPE_OPTIONS" label="Scope" />
      </div>

      <div class="flex-1 min-w-48">
        <VInput
          v-model="search"
          label="Search"
          placeholder="subject, signature, note…"
        />
      </div>

      <div class="flex flex-col gap-1">
        <span class="text-xs opacity-70">Filter</span>
        <VCheckbox v-model="cooldownOnly" label="active cooldown only" />
      </div>

      <VButton
        variant="neutral"
        size="sm"
        :outline="true"
        :disabled="!scopeId || health.loading.value"
        @click="reload"
      >
        Reload
      </VButton>

      <div class="text-xs opacity-60 ml-auto">
        {{ filtered.length }} / {{ health.entries.value.length }} records ·
        {{ totalCooldowns }} active cooldown{{ totalCooldowns === 1 ? '' : 's' }}
      </div>
    </div>

    <div v-if="!scopeId" class="opacity-60 text-sm">
      Pick a project in the sidebar to see its tool health.
    </div>

    <div v-else-if="health.loading.value" class="text-sm opacity-60">
      Loading tool health…
    </div>

    <VAlert v-else-if="health.error.value" variant="error">
      {{ health.error.value }}
    </VAlert>

    <VEmptyState
      v-else-if="health.entries.value.length === 0"
      :headline="'No health records'"
      :body="
        scope === 'TENANT'
          ? 'Nothing has been recorded as degraded or cooling down for this tenant.'
          : 'Nothing has been recorded as degraded or cooling down for this project.'
      "
    />

    <table v-else class="table table-sm">
      <thead>
        <tr>
          <th class="cursor-pointer select-none" @click="toggleSort('toolName')">
            Subject{{ arrow('toolName') }}
          </th>
          <th class="w-28">Kind</th>
          <th class="w-28 cursor-pointer select-none" @click="toggleSort('status')">
            Status{{ arrow('status') }}
          </th>
          <th class="w-40">Classification</th>
          <th class="w-24">Cooldowns</th>
          <th class="w-44">Since</th>
          <th>Note</th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="filtered.length === 0">
          <td colspan="7" class="opacity-60 text-center py-4">
            No records match the current filters.
          </td>
        </tr>
        <template v-for="e in filtered" :key="e.scope + '|' + e.toolName">
          <tr>
            <td class="font-mono">
              <button
                type="button"
                class="cursor-pointer text-left hover:underline"
                @click="toggleExpand(e)"
              >
                {{ isExpanded(e) ? '▾' : '▸' }} {{ e.toolName }}
              </button>
            </td>
            <td class="text-xs">
              <span :class="'badge-kind badge-kind--' + subjectKind(e.toolName)">
                {{ subjectKindLabel(subjectKind(e.toolName)) }}
              </span>
            </td>
            <td class="text-xs">
              <span :class="statusBadgeClass(e.status)">{{ e.status }}</span>
            </td>
            <td class="text-xs opacity-80">{{ e.classification ?? '—' }}</td>
            <td class="text-xs">
              <span v-if="cooldownCount(e) > 0" class="text-warning">
                ⏳ {{ cooldownCount(e) }}
              </span>
              <span v-else class="opacity-40">—</span>
            </td>
            <td class="text-xs opacity-80">{{ formatTimestamp(e.statusSince) }}</td>
            <td class="text-xs opacity-80">{{ e.note ?? '—' }}</td>
          </tr>

          <tr v-if="isExpanded(e)" class="health-detail-row">
            <td colspan="7" class="p-3">
              <div class="bg-base-200 rounded p-3 space-y-2 text-xs">
                <div v-if="e.expectedRecoveryAt" class="opacity-80">
                  <span class="opacity-60">recovery:</span>
                  {{ formatTimestamp(e.expectedRecoveryAt) }}
                  (in {{ formatCountdown(e.expectedRecoveryAt) }})
                </div>

                <div v-if="cooldownCount(e) === 0" class="opacity-60">
                  No active cooldowns — nothing is gating this subject right now.
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
                      v-for="cd in (e.activeCooldowns ?? [])"
                      :key="cd.errorSignature + '|' + (cd.userId ?? '*')"
                    >
                      <td class="font-mono">{{ cd.errorSignature }}</td>
                      <td>{{ cd.lastClassification ?? '—' }}</td>
                      <td>{{ cd.hits }}</td>
                      <td>{{ cd.userId ?? '*' }}</td>
                      <td class="opacity-80">{{ cd.note ?? '—' }}</td>
                      <td>
                        {{ formatCountdown(cd.nextSpawnAllowedAt) }}
                        <span class="opacity-50">
                          ({{ formatTimestamp(cd.nextSpawnAllowedAt) }})
                        </span>
                      </td>
                      <td>
                        <VButton
                          variant="neutral"
                          size="xs"
                          :outline="true"
                          title="Clear this cooldown — the subject can fire again immediately"
                          @click="onClearCooldown(e.toolName, cd)"
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
  </div>
</template>

<style scoped>
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
.badge-kind {
  display: inline-block;
  padding: 0.05rem 0.4rem;
  border-radius: 0.25rem;
  font-size: 0.7rem;
  font-weight: 500;
}
.badge-kind--tool {
  background: var(--color-base-300);
  color: color-mix(in oklab, var(--color-base-content) 70%, transparent);
}
.badge-kind--feed {
  background: color-mix(in oklab, var(--color-primary) 18%, transparent);
  color: var(--color-primary);
}
.badge-kind--search {
  background: color-mix(in oklab, var(--color-secondary) 18%, transparent);
  color: var(--color-secondary);
}
.health-detail-row > td {
  background: transparent;
}
</style>
