<script setup lang="ts">
// An action shown as an icon alone (corporate-design.md §6.1): toolbars and
// row actions. `label` is required — it becomes the tooltip AND the
// aria-label, so an icon-only control is never unnamed.
import VIcon from './VIcon.vue';
import type { IconName } from './icons';

interface Props {
  icon: IconName;
  /** What the action does, short ("Delete", "Open in Cortex"). Tooltip + aria-label. */
  label: string;
  /** 28 px default, 24 px in dense rows. */
  size?: 'md' | 'sm';
  /** Toggled-on state (a pinned pin, an open panel). */
  active?: boolean;
  disabled?: boolean;
  /** Destructive action: icon turns red on hover. */
  danger?: boolean;
  tooltip?: 'bottom' | 'top' | 'left' | 'right';
  /** A small count next to the icon ("3 notes"); hidden when empty or 0. */
  badge?: number | string;
}

withDefaults(defineProps<Props>(), {
  size: 'md',
  active: false,
  disabled: false,
  danger: false,
  tooltip: 'bottom',
});

defineEmits<{ (e: 'click', event: MouseEvent): void }>();
</script>

<template>
  <button
    type="button"
    :aria-label="label"
    :aria-pressed="active ? 'true' : undefined"
    :data-tip="label"
    :disabled="disabled"
    :class="[
      'v-icon-btn tooltip',
      `tooltip-${tooltip}`,
      size === 'sm' ? 'v-icon-btn-sm' : '',
      { 'v-icon-btn-active': active, 'v-icon-btn-danger': danger },
    ]"
    @click="(e) => $emit('click', e)"
  >
    <VIcon :name="icon" :size="16" />
    <span v-if="badge !== undefined && badge !== 0 && badge !== ''" class="v-icon-btn-badge">{{ badge }}</span>
  </button>
</template>

<style scoped>
.v-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: var(--v-radius-control);
  background: transparent;
  color: var(--v-label-secondary);
  cursor: pointer;
  transition: background-color var(--v-duration) ease-out, color var(--v-duration) ease-out;
}
.v-icon-btn-sm { width: 24px; height: 24px; }
.v-icon-btn:hover:not(:disabled) { background: var(--v-fill); color: var(--v-label); }
.v-icon-btn:focus-visible { outline: none; box-shadow: 0 0 0 3px var(--v-focus-ring); }
.v-icon-btn-active { background: var(--v-selection); color: var(--v-accent-text); }
.v-icon-btn-danger:hover:not(:disabled) { color: var(--v-danger); }
.v-icon-btn:disabled { opacity: 0.4; cursor: default; }
/* With a badge the button widens to fit the count. */
.v-icon-btn:has(.v-icon-btn-badge) { width: auto; gap: 4px; padding: 0 6px; }
.v-icon-btn-badge { font-size: 12px; font-variant-numeric: tabular-nums; }
</style>
