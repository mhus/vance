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
  <label class="form-control w-full">
    <div v-if="label" class="label">
      <span class="label-text">{{ label }}</span>
    </div>
    <textarea
      :value="modelValue"
      :placeholder="placeholder"
      :rows="rows"
      :required="required"
      :disabled="disabled"
      :class="['textarea', 'textarea-bordered', 'w-full', 'font-mono', { 'textarea-error': !!error }]"
      @input="(e) => $emit('update:modelValue', (e.target as HTMLTextAreaElement).value)"
    />
    <div v-if="error || help" class="label">
      <span :class="['label-text-alt', error ? 'text-error' : 'opacity-70']">
        {{ error || help }}
      </span>
    </div>
  </label>
</template>
