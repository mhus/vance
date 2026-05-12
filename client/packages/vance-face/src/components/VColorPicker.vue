<script setup lang="ts">
import { SessionColor } from '@vance/generated';
import { computed } from 'vue';

function colorName(c: SessionColor): string {
  return SessionColor[c];
}

interface Props {
  modelValue: SessionColor | null | undefined;
  /** When true, an extra "no color" chip is shown so the user can clear the choice. */
  allowClear?: boolean;
  disabled?: boolean;
  /** Optional label rendered above the chip row. */
  label?: string;
}

const props = withDefaults(defineProps<Props>(), {
  allowClear: true,
  disabled: false,
});

const emit = defineEmits<{
  (e: 'update:modelValue', value: SessionColor | null): void;
}>();

// 12 colors map to Tailwind hues. Pick a saturation/lightness pair that
// works against both light and dark backgrounds — `-500` is the safe
// middle ground. Background tinting in the chip uses `/15` opacity so
// the picked chip never overpowers the surrounding card.
const SWATCHES: ReadonlyArray<{ value: SessionColor; bg: string; ring: string }> = [
  { value: SessionColor.SLATE, bg: 'bg-slate-500', ring: 'ring-slate-500' },
  { value: SessionColor.RED, bg: 'bg-red-500', ring: 'ring-red-500' },
  { value: SessionColor.ORANGE, bg: 'bg-orange-500', ring: 'ring-orange-500' },
  { value: SessionColor.AMBER, bg: 'bg-amber-500', ring: 'ring-amber-500' },
  { value: SessionColor.GREEN, bg: 'bg-green-500', ring: 'ring-green-500' },
  { value: SessionColor.TEAL, bg: 'bg-teal-500', ring: 'ring-teal-500' },
  { value: SessionColor.CYAN, bg: 'bg-cyan-500', ring: 'ring-cyan-500' },
  { value: SessionColor.BLUE, bg: 'bg-blue-500', ring: 'ring-blue-500' },
  { value: SessionColor.INDIGO, bg: 'bg-indigo-500', ring: 'ring-indigo-500' },
  { value: SessionColor.PURPLE, bg: 'bg-purple-500', ring: 'ring-purple-500' },
  { value: SessionColor.PINK, bg: 'bg-pink-500', ring: 'ring-pink-500' },
  { value: SessionColor.ROSE, bg: 'bg-rose-500', ring: 'ring-rose-500' },
];

const current = computed<SessionColor | null>(() => props.modelValue ?? null);

function pick(value: SessionColor | null): void {
  if (props.disabled) return;
  emit('update:modelValue', value);
}
</script>

<template>
  <div class="flex flex-col gap-2">
    <span v-if="label" class="text-xs opacity-70">{{ label }}</span>
    <div class="flex flex-wrap gap-2">
      <button
        v-for="swatch in SWATCHES"
        :key="swatch.value"
        type="button"
        :disabled="disabled"
        class="size-6 rounded-full ring-2 ring-offset-2 ring-offset-base-100 transition-opacity"
        :class="[
          swatch.bg,
          swatch.ring,
          current === swatch.value ? 'opacity-100' : 'opacity-60 ring-transparent hover:opacity-100',
          disabled ? 'cursor-not-allowed' : 'cursor-pointer',
        ]"
        :aria-label="colorName(swatch.value)"
        :aria-pressed="current === swatch.value"
        @click="pick(swatch.value)"
      />
      <button
        v-if="allowClear"
        type="button"
        :disabled="disabled"
        class="size-6 rounded-full ring-2 ring-offset-2 ring-offset-base-100 transition-opacity bg-base-200 border border-base-300"
        :class="[
          current === null ? 'opacity-100 ring-base-content' : 'opacity-60 ring-transparent hover:opacity-100',
          disabled ? 'cursor-not-allowed' : 'cursor-pointer',
        ]"
        aria-label="no-color"
        :aria-pressed="current === null"
        @click="pick(null)"
      >
        <span class="text-xs opacity-70">×</span>
      </button>
    </div>
  </div>
</template>
