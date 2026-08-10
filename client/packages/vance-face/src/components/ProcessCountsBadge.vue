<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { VBadge } from '@vance/components';
import { processCounts } from '@/process/processCountsStore';
import { openProcessPanel } from '@/process/processPanelState';

/**
 * Topbar badge with the session's think-process counts — the trigger
 * information for "something is running besides my chat", and the way into
 * the process panel. Hidden entirely while no process exists, so editors
 * without a bound session (and quiet sessions) show nothing.
 *
 * <p>Requirement: planning/process-visibility.md §4.A / §4.B
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
  <button
    v-if="visible"
    type="button"
    :title="tooltip"
    :aria-label="tooltip"
    @click="openProcessPanel()"
  >
    <VBadge :variant="attention ? 'warning' : 'neutral'" size="sm" :outline="!attention">
      <span
        class="inline-block w-1.5 h-1.5 rounded-full mr-1"
        :class="counts.running > 0 ? 'bg-success animate-pulse' : 'bg-base-content/40'"
      />
      {{ counts.total }}
    </VBadge>
  </button>
</template>
