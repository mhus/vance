<script setup lang="ts">
import { ref } from 'vue';

interface Props {
  /**
   * Currently picked files. Always an array — single-mode just keeps it
   * 1-element. Empty array means nothing picked yet.
   */
  modelValue: File[];
  label?: string;
  /** Comma-separated MIME types or extensions, e.g. `"image/*,.pdf"`. */
  accept?: string;
  /** Allow multiple files via the picker and via drop. */
  multiple?: boolean;
  help?: string;
  error?: string;
  required?: boolean;
  disabled?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  multiple: false,
  required: false,
  disabled: false,
});

const emit = defineEmits<{ (e: 'update:modelValue', files: File[]): void }>();

const inputRef = ref<HTMLInputElement | null>(null);
const dragActive = ref(false);

function setFiles(picked: FileList | File[] | null): void {
  if (!picked) {
    emit('update:modelValue', []);
    return;
  }
  const incoming = Array.from(picked);
  if (!props.multiple) {
    emit('update:modelValue', incoming.slice(0, 1));
    return;
  }
  // Multi-mode: append to existing selection so subsequent drops or picker
  // re-opens stack rather than replace. Dedupe by name + size + lastModified
  // so dragging the same file twice doesn't show twice.
  const merged = props.modelValue.slice();
  const fingerprint = (f: File) => `${f.name}|${f.size}|${f.lastModified}`;
  const seen = new Set(merged.map(fingerprint));
  for (const f of incoming) {
    const key = fingerprint(f);
    if (!seen.has(key)) {
      merged.push(f);
      seen.add(key);
    }
  }
  emit('update:modelValue', merged);
}

function onChange(event: Event): void {
  const input = event.target as HTMLInputElement;
  setFiles(input.files);
  // Reset so the same file picked twice in a row still fires `change` —
  // matters in multi-mode where "add more" opens the picker again.
  input.value = '';
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
  setFiles(event.dataTransfer?.files ?? null);
}

function clearAll(): void {
  emit('update:modelValue', []);
  if (inputRef.value) inputRef.value.value = '';
}

function removeAt(index: number): void {
  const next = props.modelValue.slice();
  next.splice(index, 1);
  emit('update:modelValue', next);
  if (next.length === 0 && inputRef.value) inputRef.value.value = '';
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
        'border-2 border-dashed rounded-lg px-6 py-6 transition-colors',
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
        :multiple="multiple"
        :required="required"
        :disabled="disabled"
        @change="onChange"
      />

      <template v-if="modelValue.length === 0">
        <span class="text-2xl mb-2 opacity-60" aria-hidden="true">⬆</span>
        <span class="text-sm">
          {{ multiple ? 'Drop files here, or' : 'Drop a file here, or' }}
          <span class="link link-primary">browse</span>
        </span>
        <span v-if="accept" class="text-xs opacity-60 mt-1">
          Accepted: {{ accept }}
        </span>
      </template>

      <template v-else>
        <ul class="w-full flex flex-col gap-1.5 text-left">
          <li
            v-for="(file, idx) in modelValue"
            :key="`${file.name}-${idx}`"
            class="flex items-center gap-2 text-sm"
          >
            <span aria-hidden="true">📄</span>
            <span class="font-mono truncate flex-1">{{ file.name }}</span>
            <span class="text-xs opacity-60 shrink-0">{{ formatSize(file.size) }}</span>
            <button
              type="button"
              class="btn btn-ghost btn-xs"
              :disabled="disabled"
              aria-label="Remove file"
              @click.stop.prevent="removeAt(idx)"
            >✕</button>
          </li>
        </ul>
        <div class="mt-3 text-xs opacity-70">
          <template v-if="multiple">
            {{ modelValue.length }} file{{ modelValue.length === 1 ? '' : 's' }} ready —
            <span class="link link-primary">add more</span>
            ·
          </template>
          <button
            type="button"
            class="link link-error"
            :disabled="disabled"
            @click.stop.prevent="clearAll"
          >clear</button>
        </div>
      </template>
    </label>

    <div v-if="error || help" class="label">
      <span :class="['label-text-alt', error ? 'text-error' : 'opacity-70']">
        {{ error || help }}
      </span>
    </div>
  </div>
</template>
