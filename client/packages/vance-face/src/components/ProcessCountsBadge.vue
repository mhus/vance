<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { processCounts } from '@/process/processCountsStore';

/**
 * Topbar badge with the session's think-process counts — the trigger
 * information for "something is running besides my chat". Hidden entirely
 * while no process exists, so editors without a bound session (and quiet
 * sessions) show nothing.
 *
 * <p>Phase A is display-only: the badge carries the numbers and a tooltip.
 * The click-through into a process detail view comes with Phase B; until it
 * exists this deliberately renders as a plain indicator and not a button, so
 * there is no dead affordance.
 *
 * <p>Requirement: planning/process-visibility.md §4.A
 */

const { t } = useI18n();

const counts = computed(() => processCounts.value);
const visible = computed(() => counts.value.total > 0);
/** Blocked means a process waits on the user — the one state worth colour. */
const attention = computed(() => counts.value.blocked > 0);

const tooltip = computed(() => {
  const base = t('processCounts.tooltip', {
    running: counts.value.running,
    waiting: counts.value.waiting,
    blocked: counts.value.blocked,
  });
  return attention.value
    ? `${base} — ${t('processCounts.blockedHint', { n: counts.value.blocked })}`
    : base;
});
</script>

<template>
  <span
    v-if="visible"
    class="badge badge-sm gap-1"
    :class="attention ? 'badge-warning' : 'badge-ghost'"
    :title="tooltip"
    :aria-label="tooltip"
  >
    <span
      class="inline-block w-1.5 h-1.5 rounded-full"
      :class="counts.running > 0 ? 'bg-success animate-pulse' : 'bg-base-content/40'"
    />
    {{ counts.total }}
  </span>
</template>
