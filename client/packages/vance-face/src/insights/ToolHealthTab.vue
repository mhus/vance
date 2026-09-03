<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue';
import { getTenantId } from '@vance/shared';
import { ToolHealthScope } from '@vance/generated';
import type { ToolHealthCooldownDto, ToolHealthEntryDto } from '@vance/generated';
import { VAlert, VButton, VCheckbox, VEmptyState, VInput, VSelect } from '@/components';
import { useToolHealth } from '@/composables/useProjectInsights';
import { useI18n } from 'vue-i18n';

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
const { t } = useI18n();

// Labels resolved per render so a language switch reaches the picker.
const SCOPE_OPTIONS = computed(() => [
  { value: ToolHealthScope.PROJECT, label: t('insights.toolHealth.scopeProject') },
  { value: ToolHealthScope.TENANT, label: t('insights.toolHealth.scopeTenant') },
]);

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

function formatTimestamp(iso: string | null | undefined): string {
  if (!iso) return '—';
  // `ms`, not `t`: the translator is called `t` in this file now, and a local
  // shadow here would silently take it out of scope.
  const ms = Date.parse(iso);
  if (Number.isNaN(ms)) return iso;
  return new Date(ms).toLocaleString();
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
      return t('insights.toolHealth.kind.feed');
    case 'search':
      return t('insights.toolHealth.kind.search');
    default:
      return t('insights.toolHealth.kind.tool');
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
        <VSelect v-model="scope" :options="SCOPE_OPTIONS" :label="$t('insights.toolHealth.scope')" />
      </div>

      <div class="flex-1 min-w-48">
        <VInput
          v-model="search"
          :label="$t('insights.toolHealth.search')"
          :placeholder="$t('insights.toolHealth.searchPlaceholder')"
        />
      </div>

      <div class="flex flex-col gap-1">
        <span class="text-xs opacity-70">{{ $t('insights.toolHealth.filter') }}</span>
        <VCheckbox v-model="cooldownOnly" :label="$t('insights.toolHealth.cooldownOnly')" />
      </div>

      <VButton
        variant="neutral"
        size="sm"
        :outline="true"
        :disabled="!scopeId || health.loading.value"
        @click="reload"
      >
        {{ $t('insights.toolHealth.reload') }}
      </VButton>

      <div class="text-xs opacity-60 ml-auto">
        {{ $t('insights.toolHealth.recordCount', {
          shown: filtered.length,
          total: health.entries.value.length,
        }) }} ·
        {{ $t('insights.toolHealth.cooldownCount', { n: totalCooldowns }, totalCooldowns) }}
      </div>
    </div>

    <div v-if="!scopeId" class="opacity-60 text-sm">
      {{ $t('insights.toolHealth.pickProject') }}
    </div>

    <div v-else-if="health.loading.value" class="text-sm opacity-60">
      {{ $t('insights.toolHealth.loading') }}
    </div>

    <VAlert v-else-if="health.error.value" variant="error">
      {{ health.error.value }}
    </VAlert>

    <VEmptyState
      v-else-if="health.entries.value.length === 0"
      :headline="$t('insights.toolHealth.emptyHeadline')"
      :body="
        scope === 'TENANT'
          ? $t('insights.toolHealth.emptyBodyTenant')
          : $t('insights.toolHealth.emptyBodyProject')
      "
    />

    <!-- Plain Tailwind, no DaisyUI `table` classes: those are banned outside
         `src/components/` (web-ui.md §7.4). Same shape as ToolUsageTab. -->
    <table v-else class="w-full text-sm">
      <thead class="text-xs opacity-60">
        <tr class="text-left">
          <th
            class="font-normal px-3 py-1 cursor-pointer select-none"
            @click="toggleSort('toolName')"
          >
            {{ $t('insights.toolHealth.colSubject') }}{{ arrow('toolName') }}
          </th>
          <th class="font-normal px-3 py-1 w-28">{{ $t('insights.toolHealth.colKind') }}</th>
          <th
            class="font-normal px-3 py-1 w-28 cursor-pointer select-none"
            @click="toggleSort('status')"
          >
            {{ $t('insights.toolHealth.colStatus') }}{{ arrow('status') }}
          </th>
          <th class="font-normal px-3 py-1 w-40">{{ $t('insights.toolHealth.colClassification') }}</th>
          <th class="font-normal px-3 py-1 w-24">{{ $t('insights.toolHealth.colCooldowns') }}</th>
          <th class="font-normal px-3 py-1 w-44">{{ $t('insights.toolHealth.colSince') }}</th>
          <th class="font-normal px-3 py-1">{{ $t('insights.toolHealth.colNote') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="filtered.length === 0">
          <td colspan="7" class="opacity-60 text-center py-4">
            {{ $t('insights.toolHealth.noMatch') }}
          </td>
        </tr>
        <template v-for="e in filtered" :key="e.scope + '|' + e.toolName">
          <tr class="border-t border-base-content/5">
            <td class="px-3 py-1 font-mono">
              <button
                type="button"
                class="cursor-pointer text-left hover:underline"
                @click="toggleExpand(e)"
              >
                {{ isExpanded(e) ? '▾' : '▸' }} {{ e.toolName }}
              </button>
            </td>
            <td class="px-3 py-1 text-xs">
              <span :class="'badge-kind badge-kind--' + subjectKind(e.toolName)">
                {{ subjectKindLabel(subjectKind(e.toolName)) }}
              </span>
            </td>
            <td class="px-3 py-1 text-xs">
              <span :class="statusBadgeClass(e.status)">{{ e.status }}</span>
            </td>
            <td class="px-3 py-1 text-xs opacity-80">{{ e.classification ?? '—' }}</td>
            <td class="px-3 py-1 text-xs">
              <span v-if="cooldownCount(e) > 0" class="text-warning">
                ⏳ {{ cooldownCount(e) }}
              </span>
              <span v-else class="opacity-40">—</span>
            </td>
            <td class="px-3 py-1 text-xs opacity-80">{{ formatTimestamp(e.statusSince) }}</td>
            <td class="px-3 py-1 text-xs opacity-80">{{ e.note ?? '—' }}</td>
          </tr>

          <tr v-if="isExpanded(e)">
            <td colspan="7" class="p-3">
              <div class="bg-base-200 rounded p-3 space-y-2 text-xs">
                <div v-if="e.expectedRecoveryAt" class="opacity-80">
                  <span class="opacity-60">{{ $t('insights.toolHealth.recovery') }}</span>
                  {{ formatTimestamp(e.expectedRecoveryAt) }}
                  {{ $t('insights.toolHealth.recoveryIn', {
                    countdown: formatCountdown(e.expectedRecoveryAt),
                  }) }}
                </div>

                <div v-if="cooldownCount(e) === 0" class="opacity-60">
                  {{ $t('insights.toolHealth.noCooldowns') }}
                </div>

                <table v-else class="w-full text-xs">
                  <thead class="opacity-60">
                    <tr class="text-left">
                      <th class="font-normal px-2 py-1">{{ $t('insights.toolHealth.colSignature') }}</th>
                      <th class="font-normal px-2 py-1">{{ $t('insights.toolHealth.colClassification') }}</th>
                      <th class="font-normal px-2 py-1">{{ $t('insights.toolHealth.colHits') }}</th>
                      <th class="font-normal px-2 py-1">{{ $t('insights.toolHealth.colUser') }}</th>
                      <th class="font-normal px-2 py-1">{{ $t('insights.toolHealth.colNote') }}</th>
                      <th class="font-normal px-2 py-1">{{ $t('insights.toolHealth.colExpires') }}</th>
                      <th class="font-normal px-2 py-1"></th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr
                      v-for="cd in (e.activeCooldowns ?? [])"
                      :key="cd.errorSignature + '|' + (cd.userId ?? '*')"
                      class="border-t border-base-content/5"
                    >
                      <td class="px-2 py-1 font-mono">{{ cd.errorSignature }}</td>
                      <td class="px-2 py-1">{{ cd.lastClassification ?? '—' }}</td>
                      <td class="px-2 py-1">{{ cd.hits }}</td>
                      <td class="px-2 py-1">{{ cd.userId ?? '*' }}</td>
                      <td class="px-2 py-1 opacity-80">{{ cd.note ?? '—' }}</td>
                      <td class="px-2 py-1">
                        {{ formatCountdown(cd.nextSpawnAllowedAt) }}
                        <span class="opacity-50">
                          ({{ formatTimestamp(cd.nextSpawnAllowedAt) }})
                        </span>
                      </td>
                      <td class="px-2 py-1">
                        <VButton
                          variant="neutral"
                          size="xs"
                          :outline="true"
                          :title="$t('insights.toolHealth.clearTitle')"
                          @click="onClearCooldown(e.toolName, cd)"
                        >
                          {{ $t('insights.toolHealth.clear') }}
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
</style>
