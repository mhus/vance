<script setup lang="ts">
import { computed, useId } from 'vue';

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
  /**
   * Whether the on-screen keyboard capitalises for this field.
   *
   * <p>Needed because iOS capitalises the first letter of a text field by
   * default, and `autocomplete="username"` does not switch that off. For a
   * value the server compares byte-for-byte — a login name, a tenant, a
   * host — the keyboard silently turns the right input into a wrong one,
   * and the person sees a rejected credential rather than a typo.
   *
   * <p>Not passed through `$attrs`: those land on the wrapping `<label>`,
   * and relying on the attribute's spec-inherited behaviour there is a
   * quieter contract than binding it where it belongs.
   */
  autocapitalize?: 'off' | 'none' | 'on' | 'sentences' | 'words' | 'characters';
  size?: 'xs' | 'sm' | 'md';
  /** Optional autocomplete suggestions rendered via a native <datalist>. */
  suggestions?: string[];
}

const props = withDefaults(defineProps<Props>(), {
  type: 'text',
  required: false,
  disabled: false,
  size: 'md',
  suggestions: () => [] as string[],
});

defineEmits<{ (e: 'update:modelValue', value: string): void }>();

const sizeClass = computed<string>(() => {
  switch (props.size) {
    case 'xs': return 'input-xs';
    case 'sm': return 'input-sm';
    default: return '';
  }
});

const datalistId = useId();
</script>

<template>
  <!-- `v-field` / `v-field-label` / `v-field-hint` carry no styles of their own —
       they are stable hooks so hosts can retarget this markup via :deep().
       DaisyUI 5 dropped `form-control` and `label-text`, so the layout is
       expressed with utilities instead. -->
  <label class="v-field flex flex-col gap-1 w-full">
    <span v-if="label" class="v-field-label text-sm">{{ label }}</span>
    <input
      :type="type"
      :value="modelValue"
      :placeholder="placeholder"
      :required="required"
      :disabled="disabled"
      :autocomplete="autocomplete"
      :autocapitalize="autocapitalize"
      :list="suggestions.length ? datalistId : undefined"
      :class="['input', 'w-full', sizeClass, { 'input-error': !!error }]"
      @input="(e) => $emit('update:modelValue', (e.target as HTMLInputElement).value)"
    />
    <datalist v-if="suggestions.length" :id="datalistId">
      <option v-for="s in suggestions" :key="s" :value="s" />
    </datalist>
    <span v-if="error || help" :class="['v-field-hint', 'text-xs', error ? 'text-error' : 'opacity-70']">
      {{ error || help }}
    </span>
  </label>
</template>
