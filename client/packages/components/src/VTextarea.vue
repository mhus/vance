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
  /**
   * Monospace text. Defaults to `true` because this component grew up around
   * code and YAML, where alignment carries meaning — every existing caller
   * expects that and keeps it without changing a line.
   *
   * Pass `false` for **prose**: a teaser, a note, a description. Monospace
   * there reads as "this is data", which is exactly the wrong hint for a
   * sentence somebody is writing in their own words.
   */
  mono?: boolean;
}

withDefaults(defineProps<Props>(), {
  rows: 8,
  required: false,
  disabled: false,
  mono: true,
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
      :class="['textarea', 'w-full', mono ? 'font-mono' : '', { 'textarea-error': !!error }]"
      @input="(e) => $emit('update:modelValue', (e.target as HTMLTextAreaElement).value)"
    />
    <span v-if="error || help" :class="['v-field-hint', 'text-xs', error ? 'text-error' : 'opacity-70']">
      {{ error || help }}
    </span>
  </label>
</template>
