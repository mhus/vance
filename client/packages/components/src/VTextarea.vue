<script setup lang="ts">
interface Props {
  modelValue: string;
  label?: string;
  placeholder?: string;
  help?: string;
  error?: string;
  rows?: number;
  required?: boolean;
  disabled?: boolean;
}

withDefaults(defineProps<Props>(), {
  rows: 8,
  required: false,
  disabled: false,
});

defineEmits<{ (e: 'update:modelValue', value: string): void }>();
</script>

<template>
  <!-- See VInput for the `v-field*` hook-class convention. -->
  <label class="v-field flex flex-col gap-1 w-full">
    <span v-if="label" class="v-field-label text-sm">{{ label }}</span>
    <textarea
      :value="modelValue"
      :placeholder="placeholder"
      :rows="rows"
      :required="required"
      :disabled="disabled"
      :class="['textarea', 'w-full', 'font-mono', { 'textarea-error': !!error }]"
      @input="(e) => $emit('update:modelValue', (e.target as HTMLTextAreaElement).value)"
    />
    <span v-if="error || help" :class="['v-field-hint', 'text-xs', error ? 'text-error' : 'opacity-70']">
      {{ error || help }}
    </span>
  </label>
</template>
