<script setup lang="ts">
import { ref } from 'vue';
import { VButton, VInput, VModal } from '@vance/components';
import { searchDocuments } from './api';
import type { CanvasDocItem } from './generated/canvas/CanvasDocItem';

/**
 * Project-document picker for canvas doc-nodes. Imperative API:
 * `const picked = await pickerRef.value.open(projectId)` →
 * `{ path, kind }` on select, or `null` on cancel.
 */
const open = ref(false);
const query = ref('');
const results = ref<CanvasDocItem[]>([]);
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

function pick(item: CanvasDocItem): void {
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
    title="Dokument einbetten"
    :close-on-backdrop="false"
    @update:model-value="onToggle"
  >
    <div class="flex flex-col gap-2">
      <VInput
        v-model="query"
        placeholder="Suche nach Pfad oder Titel …"
        @update:model-value="onQuery"
      />
      <div class="max-h-80 overflow-auto rounded border border-base-300">
        <div v-if="loading" class="p-3 text-sm opacity-60">Suche…</div>
        <button
          v-for="it in results"
          :key="it.id"
          class="flex w-full flex-col items-start gap-0.5 border-b border-base-200 px-3 py-2 text-left hover:bg-base-200"
          @click="pick(it)"
        >
          <span class="text-sm font-medium">{{ it.title || it.path }}</span>
          <span class="text-xs opacity-60">{{ it.kind ? it.kind + ' · ' : '' }}{{ it.path }}</span>
        </button>
        <div v-if="!loading && results.length === 0" class="p-3 text-sm opacity-60">
          Keine Treffer.
        </div>
      </div>
      <div class="flex justify-end">
        <VButton size="sm" variant="ghost" @click="finish(null)">Abbrechen</VButton>
      </div>
    </div>
  </VModal>
</template>
