<script setup lang="ts">
/**
 * Read-only version list for the active wiki page, plus a restore action.
 * Reuses the document-archive REST endpoints (same contract as
 * vance-face's useDocumentArchives composable, which is not importable
 * from an addon). List is read-only; "Restore" POSTs the archive back to
 * live and emits {@code restored} so the host reloads the editor content.
 */
import { ref, watch } from 'vue';
import { brainFetch } from '@vance/shared';
import type {
  DocumentArchiveListResponse,
  DocumentArchiveSummary,
} from '@vance/generated';

const props = defineProps<{
  documentId: string | null;
}>();

const emit = defineEmits<{
  (e: 'restored'): void;
}>();

const items = ref<DocumentArchiveSummary[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);
const busyId = ref<string | null>(null);

async function load(id: string | null): Promise<void> {
  if (!id) {
    items.value = [];
    return;
  }
  loading.value = true;
  error.value = null;
  try {
    const data = await brainFetch<DocumentArchiveListResponse>(
      'GET',
      `documents/${encodeURIComponent(id)}/archives`,
    );
    items.value = data.items ?? [];
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not load versions.';
    items.value = [];
  } finally {
    loading.value = false;
  }
}

watch(() => props.documentId, (id) => void load(id), { immediate: true });

async function restore(archiveId: string): Promise<void> {
  const id = props.documentId;
  if (!id) return;
  if (!window.confirm('Restore this version? The current content is archived first.')) return;
  busyId.value = archiveId;
  error.value = null;
  try {
    await brainFetch<unknown>(
      'POST',
      `documents/${encodeURIComponent(id)}/archives/${encodeURIComponent(archiveId)}/restore`,
    );
    await load(id);
    emit('restored');
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Restore failed.';
  } finally {
    busyId.value = null;
  }
}

function when(ms: number): string {
  if (!ms) return '';
  return new Date(ms).toLocaleString();
}
</script>

<template>
  <div class="wiki-versions">
    <header class="wiki-versions__header">
      <span class="wiki-versions__title">Versions</span>
      <span class="wiki-versions__count">{{ items.length }}</span>
    </header>

    <div v-if="error" class="wiki-versions__error">{{ error }}</div>
    <div v-else-if="loading" class="wiki-versions__hint">Loading…</div>
    <div v-else-if="items.length === 0" class="wiki-versions__hint">
      No archived versions yet — they appear after the next edit.
    </div>

    <ul v-else class="wiki-versions__list">
      <li v-for="a in items" :key="a.id" class="wiki-versions__row">
        <div class="wiki-versions__meta">
          <span class="wiki-versions__ts">{{ when(a.archivedAtMs) }}</span>
          <span v-if="a.createdBy" class="wiki-versions__by">{{ a.createdBy }}</span>
        </div>
        <button
          type="button"
          class="wiki-versions__restore"
          :disabled="busyId === a.id"
          @click="restore(a.id)"
        >{{ busyId === a.id ? '…' : 'Restore' }}</button>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.wiki-versions {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.wiki-versions__header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.75rem;
  border-bottom: 1px solid hsl(var(--bc) / 0.15);
}
.wiki-versions__title { font-size: 0.85rem; font-weight: 600; }
.wiki-versions__count { font-size: 0.75rem; opacity: 0.6; }
.wiki-versions__hint,
.wiki-versions__error {
  padding: 1rem;
  font-size: 0.8rem;
  opacity: 0.7;
  text-align: center;
}
.wiki-versions__error { color: #d33; }
.wiki-versions__list {
  flex: 1;
  overflow-y: auto;
  list-style: none;
  margin: 0;
  padding: 0.25rem;
}
.wiki-versions__row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.4rem 0.5rem;
  border-bottom: 1px solid hsl(var(--bc) / 0.08);
}
.wiki-versions__meta {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}
.wiki-versions__ts { font-size: 0.78rem; }
.wiki-versions__by { font-size: 0.7rem; opacity: 0.6; }
.wiki-versions__restore {
  border: 1px solid hsl(var(--bc) / 0.2);
  border-radius: 4px;
  background: transparent;
  cursor: pointer;
  font-size: 0.75rem;
  padding: 0.15rem 0.5rem;
}
.wiki-versions__restore:hover:not(:disabled) { background: hsl(var(--bc) / 0.08); }
.wiki-versions__restore:disabled { opacity: 0.5; cursor: default; }
</style>
