<script setup lang="ts">
import { ref } from 'vue';

interface Props {
  modelValue: string[];
  disabled?: boolean;
  label?: string;
  placeholder?: string;
  /** Hard caps mirroring the backend SessionService constants. */
  maxTags?: number;
  maxTagChars?: number;
}

const props = withDefaults(defineProps<Props>(), {
  disabled: false,
  maxTags: 20,
  maxTagChars: 50,
});

const emit = defineEmits<{
  (e: 'update:modelValue', value: string[]): void;
}>();

const draft = ref('');

function normalise(raw: string): string {
  return raw.trim().toLowerCase().slice(0, props.maxTagChars);
}

function commitDraft(): void {
  if (props.disabled) return;
  const value = normalise(draft.value);
  draft.value = '';
  if (!value) return;
  if (props.modelValue.includes(value)) return;
  if (props.modelValue.length >= props.maxTags) return;
  emit('update:modelValue', [...props.modelValue, value]);
}

function remove(tag: string): void {
  if (props.disabled) return;
  emit('update:modelValue', props.modelValue.filter((t) => t !== tag));
}

function onKey(event: KeyboardEvent): void {
  // Enter / Comma / Tab all commit the current draft; Backspace on an
  // empty draft removes the last chip — same convention as common
  // chip-inputs (GitHub labels, Linear tags).
  if (event.key === 'Enter' || event.key === ',' || event.key === 'Tab') {
    if (draft.value.trim()) {
      event.preventDefault();
      commitDraft();
    }
  } else if (event.key === 'Backspace' && draft.value === '' && props.modelValue.length > 0) {
    event.preventDefault();
    const last = props.modelValue[props.modelValue.length - 1];
    remove(last);
  }
}
</script>

<template>
  <div class="flex flex-col gap-2">
    <span v-if="label" class="text-xs opacity-70">{{ label }}</span>
    <div
      class="flex flex-wrap items-center gap-1 rounded-md border border-base-300 bg-base-100 px-2 py-1.5 min-h-[2.25rem]"
      :class="disabled ? 'opacity-60' : ''"
    >
      <span
        v-for="tag in modelValue"
        :key="tag"
        class="inline-flex items-center gap-1 text-xs px-1.5 py-0.5 rounded bg-base-200"
      >
        {{ tag }}
        <button
          v-if="!disabled"
          type="button"
          class="opacity-60 hover:opacity-100"
          :aria-label="`Remove ${tag}`"
          @click="remove(tag)"
        >×</button>
      </span>
      <input
        v-model="draft"
        type="text"
        class="flex-1 min-w-[6rem] bg-transparent outline-none text-sm py-0.5"
        :placeholder="placeholder ?? ''"
        :disabled="disabled || modelValue.length >= maxTags"
        :maxlength="maxTagChars"
        @keydown="onKey"
        @blur="commitDraft"
      />
    </div>
    <span
      v-if="modelValue.length >= maxTags"
      class="text-[10px] opacity-60"
    >
      {{ modelValue.length }} / {{ maxTags }}
    </span>
  </div>
</template>
