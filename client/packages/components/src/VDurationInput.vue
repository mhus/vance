<script setup lang="ts">
import { ref, useId, watch } from 'vue';
import {
  DURATION_UNITS,
  bestUnit,
  formatDurationAmount,
  parseDuration,
  type DurationUnit,
} from '@vance/shared';

/**
 * Working-time duration: an amount field with a unit select next to it.
 *
 * <p>The model is a number of <b>hours</b> (`null` = no value) — the unit
 * estimates are stored in, so the field never changes the stored format. The
 * amount field also understands what people type: `1,5h`, `2d`, `30min` or
 * `1d 4h` are recognised when the field is left and the unit select follows
 * the text. A bare number is read in the selected unit. A day counts as
 * 8 hours, a week as 40 (`parseDuration` in `@vance/shared`).
 *
 * <p>The value is taken over when the field is left (or Enter is pressed);
 * invalid text is flagged then and changes nothing.
 */
interface Props {
  modelValue: number | null;
  label?: string;
  placeholder?: string;
  help?: string;
  /** Overrides the built-in invalid-input hint. */
  error?: string;
  disabled?: boolean;
  /** Shown when the typed text is not a duration; pass a translated text. */
  invalidHint?: string;
}

const props = withDefaults(defineProps<Props>(), {
  disabled: false,
  invalidHint: 'Not a duration — try 1.5h, 2d or 30min',
});

const emit = defineEmits<{ (e: 'update:modelValue', value: number | null): void }>();

const fieldId = useId();
const unit = ref<DurationUnit>('h');
const text = ref('');
const invalid = ref(false);

/** The hours the current text + unit stand for; `null` when blank, `undefined` when invalid. */
function currentHours(): number | null | undefined {
  if (text.value.trim() === '') return null;
  return parseDuration(text.value, unit.value)?.hours;
}

function sameHours(a: number | null | undefined, b: number | null): boolean {
  if (a === undefined) return false;
  if (a === null || b === null) return a === b;
  return Math.abs(a - b) < 1e-9;
}

function reseed(hours: number | null): void {
  invalid.value = false;
  if (hours === null) {
    text.value = '';
    return;
  }
  unit.value = bestUnit(hours);
  text.value = formatDurationAmount(hours, unit.value);
}

// Adopt an outside change, but not the echo of what we just emitted.
watch(
  () => props.modelValue,
  (hours) => {
    if (!sameHours(currentHours(), hours)) reseed(hours);
  },
  { immediate: true },
);

function onInput(event: Event): void {
  text.value = (event.target as HTMLInputElement).value;
  invalid.value = false;
}

/**
 * Leaving the field (or Enter): the value is taken over, a typed unit moves
 * the select, invalid text is flagged. Nothing is emitted while typing, so a
 * half-typed or rejected text never leaves a different value behind.
 */
function commit(): void {
  const hours = currentHours();
  if (hours === undefined) {
    invalid.value = true;
    return;
  }
  invalid.value = false;
  if (!sameHours(hours, props.modelValue)) emit('update:modelValue', hours);
  if (hours === null) return;
  const typed = parseDuration(text.value, unit.value)?.typedUnit ?? null;
  if (typed !== null) {
    unit.value = bestUnit(hours);
    text.value = formatDurationAmount(hours, unit.value);
  }
}

/** The number stays, its meaning changes: 2 h becomes 2 d. */
function onUnit(event: Event): void {
  unit.value = (event.target as HTMLSelectElement).value as DurationUnit;
  const hours = currentHours();
  if (hours !== undefined && !sameHours(hours, props.modelValue)) emit('update:modelValue', hours);
}
</script>

<template>
  <!-- See VInput for the `v-field*` hook-class convention. -->
  <div class="v-field flex flex-col gap-1 w-full" role="group" :aria-labelledby="label ? `${fieldId}-label` : undefined">
    <span
      v-if="label"
      :id="`${fieldId}-label`"
      class="v-field-label text-xs font-semibold text-(--v-label-secondary)"
    >{{ label }}</span>
    <div class="join w-full">
      <input
        type="text"
        inputmode="decimal"
        autocomplete="off"
        :value="text"
        :placeholder="placeholder"
        :disabled="disabled"
        :aria-label="label"
        :aria-invalid="invalid || !!error"
        :class="['input', 'join-item', 'flex-1', 'min-w-0', { 'input-error': invalid || !!error }]"
        @input="onInput"
        @blur="commit"
        @keydown.enter="commit"
      />
      <select
        :value="unit"
        :disabled="disabled"
        aria-label="Unit"
        :class="['select', 'join-item', 'w-auto', { 'select-error': invalid || !!error }]"
        @change="onUnit"
      >
        <option v-for="u in DURATION_UNITS" :key="u" :value="u">{{ u }}</option>
      </select>
    </div>
    <span
      v-if="invalid || error || help"
      :class="['v-field-hint', 'text-xs', invalid || error ? 'text-error' : 'opacity-70']"
    >
      {{ error || (invalid ? invalidHint : help) }}
    </span>
  </div>
</template>

<style scoped>
/* Field heights per corporate-design §6.3 (32 px), like VInput and VSelect. */
.input,
.select { --size: 2rem; }
</style>
