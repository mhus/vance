<script setup lang="ts">
// Thin wrapper over DaisyUI's `range` slider so editors don't hardcode the
// DaisyUI component class (web-ui §7). v-model'able; same classes as before.
interface Props {
  modelValue?: number;
  min?: number;
  max?: number;
  step?: number;
  size?: 'xs' | 'sm' | 'md';
  disabled?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  modelValue: 0,
  min: 0,
  max: 100,
  step: 1,
  size: 'md',
  disabled: false,
});

const emit = defineEmits<{
  (e: 'update:modelValue', value: number): void;
  (e: 'input', value: number): void;
}>();

function onInput(event: Event): void {
  const value = Number((event.target as HTMLInputElement).value);
  emit('update:modelValue', value);
  emit('input', value);
}

const sizeClass = props.size === 'md' ? '' : `range-${props.size}`;
</script>

<template>
  <input
    type="range"
    :class="['range', sizeClass]"
    :min="min"
    :max="max"
    :step="step"
    :value="modelValue"
    :disabled="disabled"
    @input="onInput"
  />
</template>
