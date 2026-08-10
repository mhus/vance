<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { VBadge } from '@vance/components';
import { countsAvailable, processCounts } from '@/process/processCountsStore';
import { openProcessPanel } from '@/process/processPanelState';

/**
 * Topbar badge with the session's think-process counts — the trigger
 * information for "something is running besides my chat", and the way into
 * the process panel.
 *
 * <p>Shown whenever the session is bound, including at zero: the panel is the
 * only entry to the process list, and hiding the badge on a quiet session
 * would make the list unreachable exactly when the user wants to look. In
 * editors without a session (documents, tools, …) no frame ever arrives, so
 * nothing renders there.
 *
 * <p>Requirement: planning/process-visibility.md §4.A / §4.B
 */

const { t } = useI18n();

const counts = computed(() => processCounts.value);
const visible = computed(() => countsAvailable.value);
/** Blocked means a process waits on the user — the one state worth colour. */
const attention = computed(() => counts.value.blocked > 0);

const tooltip = computed(() => {
  if (counts.value.total === 0) {
    return t('processCounts.emptyTooltip');
  }
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
