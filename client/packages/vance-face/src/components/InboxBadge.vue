<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue';
import { useI18n } from 'vue-i18n';
import { VBadge } from '@vance/components';
import {
  inboxCountLoaded,
  inboxUnread,
  inboxUnreadRequiresAction,
  inboxPending,
  refreshInboxCount,
} from '@/inbox/inboxCountStore';

/**
 * Topbar badge with the number of pending inbox items, and the link into
 * the inbox editor. Sits next to {@code ProcessCountsBadge} so "someone
 * needs something from me" is visible from every editor page instead of
 * only after opening the inbox.
 *
 * <p>Self-hiding at zero — unlike the process badge (which stays visible
 * because it is the only door into the process list), the inbox already
 * has a permanent entry on the landing page, so an empty inbox costs no
 * topbar space.
 *
 * <p>Refresh points: page mount, and tab-refocus. The second one is what
 * makes a long-open editor tab catch up — without it a badge fetched at
 * 9:00 would still claim "0" at 17:00. Live push is deliberately not used;
 * see {@code inboxCountStore} for why.
 */

const { t } = useI18n();

/** The badge shows unread, not the stock: it is an alarm, not an inventory. */
const unread = computed<number>(() => inboxUnread.value);
const visible = computed<boolean>(() => inboxCountLoaded.value && unread.value > 0);
/** Unread threads that are an open ask for me — the one state worth colour. */
const attention = computed<boolean>(() => inboxUnreadRequiresAction.value > 0);

const tooltip = computed<string>(() =>
  attention.value
    ? t('inboxBadge.actionableTooltip', {
        n: unread.value,
        a: inboxUnreadRequiresAction.value,
        p: inboxPending.value,
      })
    : t('inboxBadge.tooltip', { n: unread.value, p: inboxPending.value }));

function onVisibilityChange(): void {
  if (document.visibilityState === 'visible') void refreshInboxCount();
}

onMounted(() => {
  void refreshInboxCount();
  document.addEventListener('visibilitychange', onVisibilityChange);
});

onUnmounted(() => {
  document.removeEventListener('visibilitychange', onVisibilityChange);
});
</script>

<template>
  <a
    v-if="visible"
    href="/inbox.html"
    class="no-underline"
    :title="tooltip"
    :aria-label="tooltip"
  >
    <VBadge :variant="attention ? 'warning' : 'neutral'" size="sm" :outline="!attention">
      <span class="mr-1" aria-hidden="true">✉</span>
      {{ unread }}
    </VBadge>
  </a>
</template>
