<script setup lang="ts">
import { ref } from 'vue';
import { VButton, VGroup, VGroupRow, VInput, VModal } from '@vance/components';
import { searchDocuments } from './api';
import type { BinderDocItem } from './generated/binder/BinderDocItem';
import { useT } from './i18n';

/**
 * Project-document picker for anchoring binder entries. Imperative API:
 * `const picked = await pickerRef.value.open(projectId)` →
 * `{ path, kind }` on select, or `null` on cancel.
 */
const t = useT();

const open = ref(false);
const query = ref('');
const results = ref<BinderDocItem[]>([]);
const loading = ref(false);
let projectId = '';
let resolver: ((v: { path: string; kind?: string } | null) => void) | null = null;
let timer: ReturnType<typeof setTimeout> | null = null;

function openPicker(pid: string): Promise<{ path: string; kind?: string } | null> {
  projectId = pid;
  query.value = '';
  results.value = [];
  open.value = true;
  void runSearch();
  return new Promise((res) => {
    resolver = res;
  });
}

function onQuery(): void {
  if (timer) clearTimeout(timer);
  timer = setTimeout(() => void runSearch(), 250);
}

async function runSearch(): Promise<void> {
  loading.value = true;
  try {
    const r = await searchDocuments(projectId, query.value.trim());
    results.value = r.items;
  } catch {
    results.value = [];
  } finally {
    loading.value = false;
  }
}

function finish(v: { path: string; kind?: string } | null): void {
  open.value = false;
  const r = resolver;
  resolver = null;
  r?.(v);
}

function pick(item: BinderDocItem): void {
  finish({ path: item.path, kind: item.kind ?? undefined });
}

function onToggle(v: boolean): void {
  if (!v && resolver) finish(null);
}

defineExpose({ open: openPicker });
</script>

<template>
  <VModal
    :model-value="open"
    :title="t('binder.picker.title')"
    :close-on-backdrop="false"
    @update:model-value="onToggle"
  >
    <div class="flex flex-col gap-4">
      <VInput
        v-model="query"
        :placeholder="t('binder.picker.searchPlaceholder')"
        @update:model-value="onQuery"
      />
      <div v-if="loading" class="text-sm text-(--v-label-secondary)">{{ t('binder.common.searching') }}</div>
      <div v-if="!loading && results.length === 0" class="text-sm text-(--v-label-secondary)">
        {{ t('binder.common.noHits') }}
      </div>
      <VGroup v-if="results.length > 0">
        <VGroupRow
          v-for="it in results"
          :key="it.id"
          navigates
          :label="it.title || it.path"
          :meta="it.kind ?? undefined"
          :description="it.path"
          @click="pick(it)"
        />
      </VGroup>
    </div>

    <template #actions>
      <VButton variant="secondary" @click="finish(null)">{{ t('binder.common.cancel') }}</VButton>
    </template>
  </VModal>
</template>
