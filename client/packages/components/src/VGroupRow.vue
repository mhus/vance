<script setup lang="ts">
// One row of a VGroup (corporate-design.md §7.2): label left, value or
// control right, a chevron when the row leads somewhere. Hairlines between
// rows start at the text edge, like the lists on Apple platforms.
import VIcon from './VIcon.vue';
import type { IconName } from './icons';

interface Props {
  label?: string;
  /** Optional leading icon in a small navy tile. */
  icon?: IconName;
  /** Read-only value shown on the right, in secondary colour. */
  value?: string;
  /** The row navigates (chevron, hover, emits click). */
  navigates?: boolean;
  /** Label in danger colour — "Sign out", "Delete project…". */
  danger?: boolean;
}

withDefaults(defineProps<Props>(), { navigates: false, danger: false });

defineEmits<{ (e: 'click', event: MouseEvent): void }>();
</script>

<template>
  <component
    :is="navigates ? 'button' : 'div'"
    :type="navigates ? 'button' : undefined"
    :class="['v-group-row', { 'v-group-row-nav': navigates, 'v-group-row-icon': icon }]"
    @click="(e: MouseEvent) => navigates && $emit('click', e)"
  >
    <span v-if="icon" class="v-group-row-tile"><VIcon :name="icon" :size="16" /></span>
    <span :class="['v-group-row-label', { 'v-group-row-danger': danger }]">
      <slot name="label">{{ label }}</slot>
    </span>
    <span v-if="value" class="v-group-row-value">{{ value }}</span>
    <slot />
    <VIcon v-if="navigates" name="forward" class="v-group-row-chevron" />
  </component>
</template>

<style scoped>
.v-group-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  min-height: 40px;
  padding: 6px 16px;
  border: 0;
  background: transparent;
  color: var(--v-label);
  font: inherit;
  font-size: 13px;
  text-align: left;
}
.v-group-row + .v-group-row::before {
  content: '';
  position: absolute;
  top: 0;
  left: 16px;
  right: 0;
  height: 1px;
  background: var(--v-separator);
}
.v-group-row-icon + .v-group-row-icon::before { left: 48px; }
.v-group-row-nav { cursor: pointer; }
.v-group-row-nav:hover { background: color-mix(in srgb, var(--v-fill) 50%, transparent); }
.v-group-row-nav:focus-visible { outline: none; box-shadow: inset 0 0 0 2px var(--v-focus-ring); }
.v-group-row-tile {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 5px;
  background: var(--v-accent);
  color: var(--v-accent-on);
  flex-shrink: 0;
}
.v-group-row-tile :deep(.v-icon) { width: 13px; height: 13px; }
.v-group-row-label { flex: 1 1 auto; min-width: 0; }
.v-group-row-danger { color: var(--v-danger); }
.v-group-row-value { color: var(--v-label-secondary); }
.v-group-row-chevron { color: var(--v-label-tertiary); }
</style>
