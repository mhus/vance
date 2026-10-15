<script setup lang="ts">
// One row of a VGroup (corporate-design.md §7.2): label left, value or
// control right, a chevron when the row leads somewhere. Hairlines between
// rows start at the text edge, like the lists on Apple platforms.
import VIcon from './VIcon.vue';
import type { IconName } from './icons';

interface Props {
  label?: string;
  /** Short mono identifier next to the label — a recipe or template name. */
  meta?: string;
  /** Second line under the label in secondary colour, at most two lines. */
  description?: string;
  /** Optional leading icon in a small navy tile. */
  icon?: IconName;
  /** Read-only value shown on the right, in secondary colour. */
  value?: string;
  /** The row navigates (chevron, hover, emits click). */
  navigates?: boolean;
  /** Label in danger colour — "Sign out", "Delete project…". */
  danger?: boolean;
  /** Shown but not available: tertiary label, does not navigate. */
  disabled?: boolean;
}

const props = withDefaults(defineProps<Props>(), { navigates: false, danger: false, disabled: false });
const active = (): boolean => props.navigates && !props.disabled;

defineEmits<{ (e: 'click', event: MouseEvent | KeyboardEvent): void }>();
</script>

<template>
  <!-- A div with role=button rather than a <button>: a navigating row may
       still hold its own controls (a delete icon, a toggle), and buttons
       cannot nest. -->
  <div
    :role="navigates ? 'button' : undefined"
    :tabindex="active() ? 0 : undefined"
    :aria-disabled="navigates && disabled ? 'true' : undefined"
    :class="['v-group-row', { 'v-group-row-nav': active(), 'v-group-row-icon': icon, 'v-group-row-disabled': disabled }]"
    @click="(e: MouseEvent) => active() && $emit('click', e)"
    @keydown.enter.self="(e: KeyboardEvent) => active() && $emit('click', e)"
    @keydown.space.self.prevent="(e: KeyboardEvent) => active() && $emit('click', e)"
  >
    <span v-if="icon" class="v-group-row-tile"><VIcon :name="icon" :size="16" /></span>
    <span :class="['v-group-row-label', { 'v-group-row-danger': danger }]">
      <span class="v-group-row-title">
        <slot name="label">{{ label }}</slot>
        <code v-if="meta" class="v-group-row-meta">{{ meta }}</code>
      </span>
      <span v-if="description || $slots.description" class="v-group-row-description">
        <slot name="description">{{ description }}</slot>
      </span>
    </span>
    <span v-if="value" class="v-group-row-value">{{ value }}</span>
    <slot />
    <VIcon v-if="active()" name="forward" class="v-group-row-chevron" />
  </div>
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
.v-group-row-label { flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.v-group-row-title { display: flex; align-items: baseline; gap: 8px; min-width: 0; }
.v-group-row-meta {
  font-family: var(--v-font-mono, ui-monospace, monospace);
  font-size: 11.5px;
  color: var(--v-label-tertiary);
}
.v-group-row-description {
  font-size: 12px;
  line-height: 16px;
  color: var(--v-label-secondary);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.v-group-row-danger { color: var(--v-danger); }
.v-group-row-disabled .v-group-row-title { color: var(--v-label-tertiary); }
.v-group-row-value { color: var(--v-label-secondary); }
.v-group-row-chevron { color: var(--v-label-tertiary); }
</style>
