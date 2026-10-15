<script setup lang="ts">
import { ref } from 'vue';
import { VButton, VInput, VModal } from '@vance/components';
import { useT } from './i18n';

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

const t = useT();

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
    size="sm"
    :close-on-backdrop="false"
    @update:model-value="onToggle"
  >
    <form id="canvas-input-dialog-form" class="flex flex-col gap-4" @submit.prevent="submit">
      <VInput
        v-for="f in fields"
        :key="f.key"
        v-model="f.value"
        :label="f.label"
        :placeholder="f.placeholder"
      />
    </form>

    <template #actions>
      <VButton variant="secondary" @click="finish(null)">{{ t('canvas.common.cancel') }}</VButton>
      <VButton type="submit" form="canvas-input-dialog-form" variant="primary">{{ t('canvas.common.ok') }}</VButton>
    </template>
  </VModal>
</template>
