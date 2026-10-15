<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import type { LeaseCounterInsightsDto, LeaseHolderInsightsDto } from '@vance/generated';
import { VAlert, VBadge, VButton, VEmptyState, VIconButton, useConfirm } from '@/components';
import { useLeaseInsights } from '@/composables/useLeases';
import { useI18n } from 'vue-i18n';

/**
 * Resource leases of this tenant (lease-system.md §9): how full each
 * funnel level is, and who holds a slot. Revoke frees one slot; the
 * brain runs the lease type's cleanup handler (closes the MCP session,
 * stops the compile poll). The global level is not shown — it sums up
 * every tenant.
 */
const { t } = useI18n();
const confirmAction = useConfirm();
const state = useLeaseInsights();

onMounted(() => {
  void state.load();
});

function refresh(): void {
  void state.load();
}

// Re-evaluated every 10s so "idle 40s" stays honest without a reload.
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

function toMs(value: string | Date | undefined | null): number | null {
  if (value == null) return null;
  const ms = value instanceof Date ? value.getTime() : Date.parse(String(value));
  return Number.isNaN(ms) ? null : ms;
}

function ago(value: string | Date | undefined | null): string {
  const ms = toMs(value);
  if (ms === null) return '—';
  const sec = Math.max(0, Math.floor((nowMs.value - ms) / 1000));
  if (sec < 60) return t('insights.leases.age.seconds', { n: sec });
  const min = Math.floor(sec / 60);
  if (min < 60) return t('insights.leases.age.minutes', { n: min });
  const hr = Math.floor(min / 60);
  if (hr < 24) return t('insights.leases.age.hours', { n: hr });
  return t('insights.leases.age.days', { n: Math.floor(hr / 24) });
}

function fmtTime(value: string | Date | undefined | null): string {
  const ms = toMs(value);
  return ms === null ? '' : new Date(ms).toLocaleString();
}

/** Past lastSeen + ttl the reaper takes the lease at its next round. */
function overdue(h: LeaseHolderInsightsDto): boolean {
  const ms = toMs(h.lastSeenAt);
  return ms !== null && nowMs.value - ms > h.ttlSeconds * 1000;
}

function levelLabel(level: string): string {
  switch (level) {
    case 'tenant': return t('insights.leases.level.tenant');
    case 'project': return t('insights.leases.level.project');
    case 'user': return t('insights.leases.level.user');
    default: return level;
  }
}

function drifted(c: LeaseCounterInsightsDto): boolean {
  return c.count !== c.booked;
}

const sortedCounters = computed(() => {
  const order: Record<string, number> = { tenant: 0, project: 1, user: 2 };
  return [...state.counters.value].sort(
    (a, b) => a.resource.localeCompare(b.resource)
      || (order[a.level] ?? 9) - (order[b.level] ?? 9)
      || a.scope.localeCompare(b.scope),
  );
});

const deadPodCount = computed(() => state.holders.value.filter((h) => !h.podAlive).length);

function payloadEntries(h: LeaseHolderInsightsDto): [string, string][] {
  return Object.entries(h.payload ?? {});
}

const revoking = ref<string | null>(null);

async function onRevoke(h: LeaseHolderInsightsDto): Promise<void> {
  if (!await confirmAction({
    message: t('insights.leases.confirmRevoke', { type: h.type, resource: h.resource, ref: h.holderRef }),
    confirmLabel: t('insights.leases.revoke'),
    danger: true,
  })) return;
  revoking.value = h.token;
  try {
    await state.revoke(h.token);
  } finally {
    revoking.value = null;
  }
}
</script>

<template>
  <div class="flex flex-col gap-4 p-4">
    <!-- ─── Toolbar ─── -->
    <div class="flex flex-wrap items-end gap-3 text-sm">
      <VButton variant="secondary" size="sm" icon="refresh" :disabled="state.loading.value" @click="refresh">
        {{ $t('insights.leases.refresh') }}
      </VButton>
      <div class="text-xs opacity-60 ml-auto">
        {{ $t('insights.leases.holderCount', { n: state.holders.value.length }, state.holders.value.length) }}
        <span v-if="deadPodCount > 0" class="text-(--v-danger)">
          · {{ $t('insights.leases.deadPodCount', { n: deadPodCount }, deadPodCount) }}
        </span>
      </div>
    </div>

    <VAlert v-if="state.error.value" variant="error">
      {{ state.error.value }}
    </VAlert>

    <div v-if="state.loading.value && state.holders.value.length === 0" class="text-sm opacity-60">
      {{ $t('insights.leases.loading') }}
    </div>

    <VEmptyState
      v-else-if="!state.error.value && state.holders.value.length === 0 && state.counters.value.length === 0"
      :headline="$t('insights.leases.emptyHeadline')"
      :body="$t('insights.leases.emptyBody')"
    />

    <template v-else>
      <!-- ─── Occupancy per funnel level ─── -->
      <section class="flex flex-col gap-1">
        <h3 class="text-sm font-medium">{{ $t('insights.leases.countersHeading') }}</h3>
        <p class="text-xs opacity-60">{{ $t('insights.leases.countersHint') }}</p>
        <div v-if="sortedCounters.length === 0" class="text-xs opacity-60 py-2">
          {{ $t('insights.leases.noCounters') }}
        </div>
        <!-- Plain Tailwind, no DaisyUI `table` classes (web-ui.md §7.4). -->
        <table v-else class="w-full text-sm">
          <thead class="text-xs opacity-60">
            <tr class="text-left">
              <th class="font-normal px-3 py-1">{{ $t('insights.leases.colResource') }}</th>
              <th class="font-normal px-3 py-1 w-28">{{ $t('insights.leases.colLevel') }}</th>
              <th class="font-normal px-3 py-1">{{ $t('insights.leases.colScope') }}</th>
              <th class="font-normal px-3 py-1 w-24">{{ $t('insights.leases.colInUse') }}</th>
              <th class="font-normal px-3 py-1 w-40">{{ $t('insights.leases.colUpdated') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="c in sortedCounters"
              :key="c.resource + '|' + c.level + '|' + c.scope"
              class="border-t border-base-content/5"
            >
              <td class="px-3 py-1 font-mono">{{ c.resource }}</td>
              <td class="px-3 py-1 text-xs">{{ levelLabel(c.level) }}</td>
              <td class="px-3 py-1 font-mono text-xs">{{ c.scope }}</td>
              <td class="px-3 py-1 text-xs">
                {{ c.count }}
                <VBadge
                  v-if="drifted(c)"
                  variant="warning"
                  size="xs"
                  :title="$t('insights.leases.driftTitle', { booked: c.booked })"
                >{{ $t('insights.leases.drift') }}</VBadge>
              </td>
              <td class="px-3 py-1 text-xs opacity-80" :title="fmtTime(c.updatedAt)">{{ ago(c.updatedAt) }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <!-- ─── Held leases ─── -->
      <section class="flex flex-col gap-1">
        <h3 class="text-sm font-medium">{{ $t('insights.leases.holdersHeading') }}</h3>
        <div v-if="state.holders.value.length === 0" class="text-xs opacity-60 py-2">
          {{ $t('insights.leases.noHolders') }}
        </div>
        <table v-else class="w-full text-sm">
          <thead class="text-xs opacity-60">
            <tr class="text-left">
              <th class="font-normal px-3 py-1 w-20">{{ $t('insights.leases.colType') }}</th>
              <th class="font-normal px-3 py-1">{{ $t('insights.leases.colResource') }}</th>
              <th class="font-normal px-3 py-1">{{ $t('insights.leases.colHolder') }}</th>
              <th class="font-normal px-3 py-1">{{ $t('insights.leases.colProjectUser') }}</th>
              <th class="font-normal px-3 py-1">{{ $t('insights.leases.colPod') }}</th>
              <th class="font-normal px-3 py-1 w-24">{{ $t('insights.leases.colHeld') }}</th>
              <th class="font-normal px-3 py-1 w-32">{{ $t('insights.leases.colLastSeen') }}</th>
              <th class="font-normal px-3 py-1 w-10"></th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="h in state.holders.value"
              :key="h.token"
              class="border-t border-base-content/5 align-top"
            >
              <td class="px-3 py-1 text-xs">
                <VBadge size="xs">{{ h.type }}</VBadge>
                <VBadge
                  v-if="h.state !== 'HELD'"
                  variant="warning"
                  size="xs"
                  :title="$t('insights.leases.pendingTitle')"
                >{{ h.state }}</VBadge>
              </td>
              <td class="px-3 py-1 font-mono">{{ h.resource }}</td>
              <td class="px-3 py-1 text-xs">
                <div class="font-mono break-all">{{ h.holderRef }}</div>
                <div class="font-mono opacity-50" :title="$t('insights.leases.tokenTitle')">{{ h.token }}</div>
                <div v-for="[k, v] in payloadEntries(h)" :key="k" class="opacity-70 break-all">
                  <span class="opacity-60">{{ k }}:</span> {{ v }}
                </div>
              </td>
              <td class="px-3 py-1 text-xs">
                <div class="font-mono">{{ h.projectId ?? '—' }}</div>
                <div class="opacity-70">{{ h.userId ?? '' }}</div>
              </td>
              <td class="px-3 py-1 text-xs">
                <div class="font-mono">{{ h.podNodeName ?? h.pod }}</div>
                <div class="flex gap-1 mt-0.5">
                  <VBadge v-if="!h.podAlive" variant="error" size="xs" :title="$t('insights.leases.podDeadTitle')">
                    {{ $t('insights.leases.podDead') }}
                  </VBadge>
                  <VBadge v-if="h.podBound" size="xs" :title="$t('insights.leases.podBoundTitle')">
                    {{ $t('insights.leases.podBound') }}
                  </VBadge>
                </div>
              </td>
              <td class="px-3 py-1 text-xs opacity-80" :title="fmtTime(h.acquiredAt)">{{ ago(h.acquiredAt) }}</td>
              <td class="px-3 py-1 text-xs" :title="fmtTime(h.lastSeenAt)">
                <span :class="overdue(h) ? 'text-(--v-danger)' : 'opacity-80'">{{ ago(h.lastSeenAt) }}</span>
                <div class="opacity-50">{{ $t('insights.leases.ttl', { n: h.ttlSeconds }) }}</div>
              </td>
              <td class="px-3 py-1">
                <VIconButton
                  icon="stop"
                  :label="$t('insights.leases.revoke')"
                  :disabled="revoking === h.token"
                  @click="onRevoke(h)"
                />
              </td>
            </tr>
          </tbody>
        </table>
      </section>
    </template>
  </div>
</template>
