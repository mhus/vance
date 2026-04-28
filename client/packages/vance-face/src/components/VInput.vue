<script setup lang="ts">
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
}

withDefaults(defineProps<Props>(), {
  type: 'text',
  required: false,
  disabled: false,
});

defineEmits<{ (e: 'update:modelValue', value: string): void }>();
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
      :class="['input', 'input-bordered', 'w-full', { 'input-error': !!error }]"
      @input="(e) => $emit('update:modelValue', (e.target as HTMLInputElement).value)"
    />
    <div v-if="error || help" class="label">
      <span :class="['label-text-alt', error ? 'text-error' : 'opacity-70']">
        {{ error || help }}
      </span>
    </div>
  </label>
</template>
