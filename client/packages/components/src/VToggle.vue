<script setup lang="ts">
interface Props {
  modelValue: boolean;
  /**
   * Visible text beside the lever. Optional on purpose: a toggle that sits
   * next to the action it modifies often needs no words, and one that does is
   * usually in a form where the label belongs anyway.
   */
  label?: string;
  /**
   * Tooltip and accessible name. Required when there is no {@link label} —
   * a lever with no text and no title is a control that only its author can
   * read.
   */
  title?: string;
  help?: string;
  disabled?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  disabled: false,
});

const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void }>();

function onChange(event: Event): void {
  emit('update:modelValue', (event.target as HTMLInputElement).checked);
}
</script>

<template>
  <!--
    A switch, not a checkbox. The difference is not decoration: a checkbox
    states what will be true after you submit, a switch takes effect the
    moment it moves. Anything that starts or stops something immediately —
    a poll, a live view — reads wrong as a checkbox.

    See VInput for the `v-field*` hook-class convention.
  -->
  <label class="v-field flex flex-col" :title="props.title ?? props.label">
    <span class="cursor-pointer flex items-center justify-start gap-2 py-1">
      <input
        type="checkbox"
        role="switch"
        class="toggle toggle-sm"
        :checked="modelValue"
        :disabled="disabled"
        :aria-label="props.title ?? props.label"
        @change="onChange"
      />
      <span v-if="label" class="v-field-label text-sm">{{ label }}</span>
    </span>
    <span v-if="help" class="text-xs opacity-70 mt-1">{{ help }}</span>
  </label>
</template>
