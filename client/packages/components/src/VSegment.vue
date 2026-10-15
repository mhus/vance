<script setup lang="ts" generic="TValue extends string">
// Segmented control (corporate-design.md §6.3): 2–4 mutually exclusive
// options in one pill — view / edit, list / board. Replaces radio groups and
// home-made toggle pairs. The selected segment lifts onto the surface; the
// others sit on the fill.
import VIcon from './VIcon.vue';
import type { IconName } from './icons';

export interface SegmentOption<V extends string> {
  value: V;
  label: string;
  /** Optional leading icon; with `iconOnly` the label becomes tooltip + aria-label. */
  icon?: IconName;
  title?: string;
}

export interface Props<TValue extends string> {
  modelValue: TValue;
  options: SegmentOption<TValue>[];
  /** Accessible name of the group. */
  ariaLabel?: string;
  /** Show icons only (labels move to the tooltip) — for narrow bars. */
  iconOnly?: boolean;
}

defineProps<Props<TValue>>();

defineEmits<{ (e: 'update:modelValue', value: TValue): void }>();
</script>

<template>
  <div class="v-segment" role="group" :aria-label="ariaLabel">
    <button
      v-for="opt in options"
      :key="opt.value"
      type="button"
      :class="['v-segment-item', { 'v-segment-on': opt.value === modelValue }]"
      :aria-pressed="opt.value === modelValue ? 'true' : 'false'"
      :aria-label="iconOnly ? opt.label : undefined"
      :title="opt.title ?? (iconOnly ? opt.label : undefined)"
      @click="$emit('update:modelValue', opt.value)"
    >
      <VIcon v-if="opt.icon" :name="opt.icon" />
      <span v-if="!iconOnly">{{ opt.label }}</span>
    </button>
  </div>
</template>

<style scoped>
.v-segment {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  border-radius: 8px;
  background: var(--v-fill);
}
.v-segment-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 24px;
  padding: 0 10px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--v-label-secondary);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  transition: background-color var(--v-duration) ease-out, color var(--v-duration) ease-out;
}
.v-segment-item:hover { color: var(--v-label); }
.v-segment-item:focus-visible { outline: none; box-shadow: 0 0 0 3px var(--v-focus-ring); }
.v-segment-on {
  background: var(--v-surface-raised);
  color: var(--v-label);
  font-weight: 500;
  box-shadow: 0 1px 2px rgb(0 0 0 / 0.12);
}
</style>
