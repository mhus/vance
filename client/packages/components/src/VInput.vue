<script setup lang="ts">
import { computed } from 'vue';

interface Props {
  modelValue: string;
  label?: string;
  type?: 'text' | 'password' | 'email' | 'number' | 'url';
  placeholder?: string;
  help?: string;
  error?: string;
  required?: boolean;
  disabled?: boolean;
  autocomplete?: string;
  size?: 'xs' | 'sm' | 'md';
}

const props = withDefaults(defineProps<Props>(), {
  type: 'text',
  required: false,
  disabled: false,
  size: 'md',
});

defineEmits<{ (e: 'update:modelValue', value: string): void }>();

const sizeClass = computed<string>(() => {
  switch (props.size) {
    case 'xs': return 'input-xs';
    case 'sm': return 'input-sm';
    default: return '';
  }
});
</script>

<template>
  <label class="form-control w-full">
    <div v-if="label" class="label">
      <span class="label-text">{{ label }}</span>
    </div>
    <input
      :type="type"
      :value="modelValue"
      :placeholder="placeholder"
      :required="required"
      :disabled="disabled"
      :autocomplete="autocomplete"
      :class="['input', 'input-bordered', 'w-full', sizeClass, { 'input-error': !!error }]"
      @input="(e) => $emit('update:modelValue', (e.target as HTMLInputElement).value)"
    />
    <div v-if="error || help" class="label">
      <span :class="['label-text-alt', error ? 'text-error' : 'opacity-70']">
        {{ error || help }}
      </span>
    </div>
  </label>
</template>
