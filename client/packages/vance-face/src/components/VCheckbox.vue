<script setup lang="ts">
interface Props {
  modelValue: boolean;
  label?: string;
  help?: string;
  disabled?: boolean;
}

withDefaults(defineProps<Props>(), { disabled: false });

const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void }>();

function onChange(event: Event): void {
  emit('update:modelValue', (event.target as HTMLInputElement).checked);
}
</script>

<template>
  <label class="form-control">
    <span class="cursor-pointer label justify-start gap-2 py-1">
      <input
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
