<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { ToolUsageEntryInsightsDto, ToolUsageRoleInsightsDto } from '@vance/generated';
import { VAlert, VButton, VEmptyState, VInput } from '@/components';
import { useToolUsageInsights } from '@/composables/useProjectInsights';

/**
 * Measured tool demand per role. The role is the recipe a process ran
 * under (engine name as fallback), because that is the key the counters
 * are written under: a coding worker's file_read flood says nothing about
 * what the chat orchestrator needs, and the tool-surface budget reads
 * them per role for exactly that reason.
 *
 * Two counters per tool, and the second one is the interesting one:
 * `calls` is demand that already cleared every hurdle, `discovery` counts
 * tool_description lookups — demand measured *before* the deferral
 * hurdle. A tool the budget demoted is harder to reach, so calls alone
 * would keep it demoted forever.
 */
const props = defineProps<{ projectId: string | null }>();

const state = useToolUsageInsights();
const filter = ref('');
const collapsed = ref<Set<string>>(new Set());

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

function toggleRole(role: string): void {
  const next = new Set(collapsed.value);
  if (next.has(role)) next.delete(role);
  else next.add(role);
  collapsed.value = next;
}

function matches(tool: ToolUsageEntryInsightsDto, needle: string): boolean {
  if (!needle) return true;
  const n = needle.toLowerCase();
  return (
    tool.toolName.toLowerCase().includes(n) || (tool.family ?? '').toLowerCase().includes(n)
  );
}

/** Roles with the tool filter applied; a role with no match drops out. */
const roles = computed<ToolUsageRoleInsightsDto[]>(() => {
  const needle = filter.value.trim();
  if (!needle) return state.roles.value;
  return state.roles.value
    .map((r) => ({ ...r, tools: r.tools.filter((t) => matches(t, needle)) }))
    .filter((r) => r.tools.length > 0);
});

const totals = computed(() => {
  let calls = 0;
  let discovery = 0;
  const tools = new Set<string>();
  for (const role of state.roles.value) {
    calls += role.calls;
    discovery += role.discoveryHits;
    for (const t of role.tools) tools.add(t.toolName);
  }
  return { calls, discovery, tools: tools.size, roles: state.roles.value.length };
});

function formatTimestamp(value: Date | string | undefined): string {
  if (!value) return '—';
  const t = typeof value === 'string' ? Date.parse(value) : value.getTime();
  if (Number.isNaN(t)) return String(value);
  return new Date(t).toLocaleString();
}

/** Bar width relative to the busiest tool of the same role. */
function barWidth(role: ToolUsageRoleInsightsDto, tool: ToolUsageEntryInsightsDto): string {
  const top = role.tools.reduce((max, t) => Math.max(max, t.demand), 0);
  if (top <= 0) return '0%';
  return `${Math.max(2, Math.round((tool.demand / top) * 100))}%`;
}
</script>

<template>
  <div class="flex flex-col gap-4">
    <div v-if="!projectId" class="opacity-60 text-sm">
      {{ $t('insights.toolUsage.pickProject') }}
    </div>

    <div v-else-if="state.loading.value" class="text-sm opacity-60">
      {{ $t('insights.toolUsage.loading') }}
    </div>

    <VAlert v-else-if="state.error.value" variant="error">
      {{ state.error.value }}
    </VAlert>

    <VEmptyState
      v-else-if="state.roles.value.length === 0"
      :headline="$t('insights.toolUsage.emptyHeadline')"
      :body="$t('insights.toolUsage.emptyBody')"
    >
      <template #action>
        <VButton variant="secondary" size="sm" :disabled="state.loading.value" @click="reload">
          {{ $t('insights.toolUsage.reload') }}
        </VButton>
      </template>
    </VEmptyState>

    <template v-else>
      <div class="flex flex-wrap items-center gap-4 text-sm">
        <div class="opacity-70">
          {{ $t('insights.toolUsage.roleCount', { n: totals.roles }, totals.roles) }} ·
          {{ $t('insights.toolUsage.toolCount', { n: totals.tools }, totals.tools) }} ·
          {{ $t('insights.toolUsage.callCount', { n: totals.calls }, totals.calls) }} ·
          {{ $t('insights.toolUsage.discoveryCount', { n: totals.discovery }, totals.discovery) }}
        </div>
        <!-- Width goes on a wrapper: VInput carries `w-full` itself, and a
             merged `w-56` loses to it at equal specificity. -->
        <div class="w-56">
          <VInput
            v-model="filter"
            size="sm"
            :placeholder="$t('insights.toolUsage.filterPlaceholder')"
          />
        </div>
        <VButton
          variant="neutral"
          size="xs"
          class="ml-auto"
          :disabled="state.loading.value"
          @click="reload"
        >
          {{ $t('insights.toolUsage.reload') }}
        </VButton>
      </div>

      <p class="text-xs opacity-60 max-w-3xl">
        {{ $t('insights.toolUsage.explainPre') }}
        <em>{{ $t('insights.toolUsage.explainInside') }}</em>
        {{ $t('insights.toolUsage.explainPost') }}
      </p>

      <div v-if="roles.length === 0" class="text-sm opacity-60">
        {{ $t('insights.toolUsage.noMatch', { filter }) }}
      </div>

      <section
        v-for="role in roles"
        :key="role.role"
        class="border border-base-content/10 rounded-lg overflow-hidden"
      >
        <header
          class="flex flex-wrap items-center gap-3 px-3 py-2 bg-base-200/40 cursor-pointer"
          @click="toggleRole(role.role)"
        >
          <span class="font-mono text-sm">{{ role.role }}</span>
          <span class="text-xs opacity-70">
            {{ $t('insights.toolUsage.toolCount', { n: role.toolCount }, role.toolCount) }} ·
            {{ $t('insights.toolUsage.callCount', { n: role.calls }, role.calls) }}
            <template v-if="role.discoveryHits > 0">
              · {{ $t('insights.toolUsage.discovery', { n: role.discoveryHits }) }}
            </template>
          </span>
          <span class="text-xs opacity-50 ml-auto">
            {{ $t('insights.toolUsage.lastActivity', {
              when: formatTimestamp(role.lastActivityAt),
            }) }}
          </span>
          <span class="text-xs opacity-50 w-4 text-right">
            {{ collapsed.has(role.role) ? '▸' : '▾' }}
          </span>
        </header>

        <table v-if="!collapsed.has(role.role)" class="w-full text-sm">
          <thead class="text-xs opacity-60">
            <tr class="text-left">
              <th class="font-normal px-3 py-1">{{ $t('insights.toolUsage.colTool') }}</th>
              <th class="font-normal px-3 py-1 w-32">{{ $t('insights.toolUsage.colFamily') }}</th>
              <th class="font-normal px-3 py-1 w-20 text-right">{{ $t('insights.toolUsage.colCalls') }}</th>
              <th class="font-normal px-3 py-1 w-24 text-right">{{ $t('insights.toolUsage.colDiscovery') }}</th>
              <th class="font-normal px-3 py-1 w-40">{{ $t('insights.toolUsage.colDemand') }}</th>
              <th class="font-normal px-3 py-1 w-44">{{ $t('insights.toolUsage.colLastUsed') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="tool in role.tools"
              :key="tool.toolName"
              class="border-t border-base-content/5"
            >
              <td class="px-3 py-1 font-mono text-xs">{{ tool.toolName }}</td>
              <td class="px-3 py-1 text-xs opacity-70">{{ tool.family ?? '—' }}</td>
              <td class="px-3 py-1 text-right tabular-nums">{{ tool.calls }}</td>
              <td class="px-3 py-1 text-right tabular-nums">
                <span :class="tool.discoveryHits > 0 ? '' : 'opacity-40'">
                  {{ tool.discoveryHits }}
                </span>
              </td>
              <td class="px-3 py-1">
                <div class="flex items-center gap-2">
                  <div class="h-1.5 flex-1 bg-base-content/10 rounded">
                    <div
                      class="h-1.5 bg-primary/70 rounded"
                      :style="{ width: barWidth(role, tool) }"
                    ></div>
                  </div>
                  <span class="tabular-nums text-xs w-10 text-right">{{ tool.demand }}</span>
                </div>
              </td>
              <td class="px-3 py-1 text-xs opacity-70">
                {{ formatTimestamp(tool.lastCallAt ?? tool.lastDiscoveryAt) }}
              </td>
            </tr>
          </tbody>
        </table>
      </section>
    </template>
  </div>
</template>
