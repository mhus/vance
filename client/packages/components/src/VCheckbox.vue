<script setup lang="ts">
import { ref, watchEffect } from 'vue';

interface Props {
  modelValue: boolean;
  label?: string;
  help?: string;
  disabled?: boolean;
  /**
   * Tri-state hint for "select all" style checkboxes: renders the
   * dash glyph when some — but not all — items are selected. It is a
   * DOM property (not an attribute), so we set it via a ref.
   */
  indeterminate?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  disabled: false,
  indeterminate: false,
});

const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void }>();

const inputEl = ref<HTMLInputElement | null>(null);
watchEffect(() => {
  if (inputEl.value) inputEl.value.indeterminate = props.indeterminate;
});

function onChange(event: Event): void {
  emit('update:modelValue', (event.target as HTMLInputElement).checked);
}
</script>

<template>
  <label class="form-control">
    <span class="cursor-pointer label justify-start gap-2 py-1">
      <input
        ref="inputEl"
        type="checkbox"
        class="checkbox checkbox-sm"
        :checked="modelValue"
        :disabled="disabled"
        @change="onChange"
      />
      <span v-if="label" class="label-text">{{ label }}</span>
    </span>
    <span v-if="help" class="text-xs opacity-70 mt-1">{{ help }}</span>
  </label>
</template>
