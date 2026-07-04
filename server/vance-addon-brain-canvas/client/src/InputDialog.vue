<script setup lang="ts">
import { ref } from 'vue';
import { VButton, VInput, VModal } from '@vance/components';

/**
 * Reusable Vue input dialog (replaces window.prompt). Imperative API:
 * `const v = await dialogRef.value.open('Title', [{ key, label, ... }])`.
 * Resolves to a `{ key: value }` map on OK, or `null` on cancel/close.
 */
export interface DialogField {
  key: string;
  label: string;
  placeholder?: string;
  value?: string;
}

interface FieldState {
  key: string;
  label: string;
  placeholder?: string;
  value: string;
}

const open = ref(false);
const title = ref('');
const fields = ref<FieldState[]>([]);
let resolver: ((v: Record<string, string> | null) => void) | null = null;

function openDialog(
  t: string,
  f: DialogField[],
): Promise<Record<string, string> | null> {
  title.value = t;
  fields.value = f.map((x) => ({
    key: x.key,
    label: x.label,
    placeholder: x.placeholder,
    value: x.value ?? '',
  }));
  open.value = true;
  return new Promise((res) => {
    resolver = res;
  });
}

function finish(values: Record<string, string> | null): void {
  open.value = false;
  const r = resolver;
  resolver = null;
  r?.(values);
}

function submit(): void {
  finish(Object.fromEntries(fields.value.map((f) => [f.key, (f.value ?? '').trim()])));
}

function onToggle(v: boolean): void {
  if (!v && resolver) finish(null);
}

defineExpose({ open: openDialog });
</script>

<template>
  <VModal
    :model-value="open"
    :title="title"
    :close-on-backdrop="false"
    @update:model-value="onToggle"
  >
    <div class="flex flex-col gap-3">
      <VInput
        v-for="f in fields"
        :key="f.key"
        v-model="f.value"
        :label="f.label"
        :placeholder="f.placeholder"
        @keyup.enter="submit"
      />
      <div class="mt-1 flex justify-end gap-2">
        <VButton size="sm" variant="ghost" @click="finish(null)">Abbrechen</VButton>
        <VButton size="sm" variant="primary" @click="submit">OK</VButton>
      </div>
    </div>
  </VModal>
</template>
