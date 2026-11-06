<script setup lang="ts">
/** One control for one tool parameter: a pick list for an enum, a text area for nested YAML, a line of text otherwise. */
import { VCombobox, VInput, VTextarea } from '@/components';
import type { ParamField } from './toolParamFields';

defineOptions({ name: 'ParamControl' });

defineProps<{
  field: ParamField;
  modelValue: string;
  label: string;
  hint?: string;
  /** The text is no legal value; the hint is then shown as the error. */
  invalid?: boolean;
}>();
defineEmits<{ (e: 'update:modelValue', text: string): void }>();
</script>

<template>
  <VCombobox
    v-if="field.kind === 'enum'"
    :model-value="modelValue"
    :options="field.options.map((value) => ({ value, label: value }))"
    :label="label"
    :help="hint"
    @update:model-value="$emit('update:modelValue', $event ?? '')"
  />
  <VTextarea
    v-else-if="field.kind === 'yaml'"
    :model-value="modelValue"
    :label="label"
    :help="hint"
    :error="invalid ? hint : undefined"
    :rows="3"
    @update:model-value="$emit('update:modelValue', $event)"
  />
  <VInput
    v-else
    :model-value="modelValue"
    :label="label"
    :help="hint"
    :error="invalid ? hint : undefined"
    @update:model-value="$emit('update:modelValue', $event)"
  />
</template>
