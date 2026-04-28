<script setup lang="ts">
import { ref } from 'vue';

interface Props {
  modelValue: File | null;
  label?: string;
  /** Comma-separated MIME types or extensions, e.g. `"image/*,.pdf"`. */
  accept?: string;
  help?: string;
  error?: string;
  required?: boolean;
  disabled?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  required: false,
  disabled: false,
});

const emit = defineEmits<{ (e: 'update:modelValue', file: File | null): void }>();

const inputRef = ref<HTMLInputElement | null>(null);
const dragActive = ref(false);

function onChange(event: Event): void {
  const input = event.target as HTMLInputElement;
  const file = input.files && input.files.length > 0 ? input.files[0] : null;
  emit('update:modelValue', file);
}

function onDragEnter(event: DragEvent): void {
  if (props.disabled) return;
  event.preventDefault();
  dragActive.value = true;
}

function onDragOver(event: DragEvent): void {
  if (props.disabled) return;
  // dragover must call preventDefault for the drop event to fire.
  event.preventDefault();
  dragActive.value = true;
}

function onDragLeave(event: DragEvent): void {
  // dragleave fires for child elements too; only deactivate when the pointer
  // actually leaves the drop zone (relatedTarget is outside it).
  const related = event.relatedTarget as Node | null;
  if (related && (event.currentTarget as Node).contains(related)) return;
  dragActive.value = false;
}

function onDrop(event: DragEvent): void {
  event.preventDefault();
  dragActive.value = false;
  if (props.disabled) return;
  const files = event.dataTransfer?.files;
  if (!files || files.length === 0) return;
  // Single-file component — take the first dropped item and ignore the rest.
  emit('update:modelValue', files[0]);
}

function clear(): void {
  emit('update:modelValue', null);
  if (inputRef.value) inputRef.value.value = '';
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}
</script>

<template>
  <div class="form-control w-full">
    <div v-if="label" class="label">
      <span class="label-text">{{ label }}</span>
    </div>

    <label
      :class="[
        'flex flex-col items-center justify-center text-center',
        'border-2 border-dashed rounded-lg px-6 py-8 transition-colors',
        disabled ? 'opacity-60 cursor-not-allowed' : 'cursor-pointer',
        dragActive
          ? 'border-primary bg-primary/10'
          : (error ? 'border-error' : 'border-base-300'),
        !disabled && !dragActive ? 'hover:border-primary hover:bg-base-200' : '',
      ]"
      @dragenter="onDragEnter"
      @dragover="onDragOver"
      @dragleave="onDragLeave"
      @drop="onDrop"
    >
      <input
        ref="inputRef"
        type="file"
        class="hidden"
        :accept="accept"
        :required="required"
        :disabled="disabled"
        @change="onChange"
      />

      <template v-if="modelValue">
        <span class="text-2xl mb-2" aria-hidden="true">📄</span>
        <span class="font-mono text-sm break-all">{{ modelValue.name }}</span>
        <span class="text-xs opacity-60 mt-1">{{ formatSize(modelValue.size) }}</span>
        <button
          type="button"
          class="btn btn-ghost btn-xs mt-3"
          :disabled="disabled"
          @click.stop.prevent="clear"
        >Remove</button>
      </template>

      <template v-else>
        <span class="text-2xl mb-2 opacity-60" aria-hidden="true">⬆</span>
        <span class="text-sm">
          Drop a file here, or
          <span class="link link-primary">browse</span>
        </span>
        <span v-if="accept" class="text-xs opacity-60 mt-1">
          Accepted: {{ accept }}
        </span>
      </template>
    </label>

    <div v-if="error || help" class="label">
      <span :class="['label-text-alt', error ? 'text-error' : 'opacity-70']">
        {{ error || help }}
      </span>
    </div>
  </div>
</template>
