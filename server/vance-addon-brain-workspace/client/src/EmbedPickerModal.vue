<script setup lang="ts">
/**
 * Embed picker — single tab, server-side recursive document search.
 * Mirrors the link-picker's "Project document" tab but the result
 * goes into a {@code vance-embed} block rather than a Markdown link.
 *
 * Media kinds (image / svg / pdf / audio / video) and applications
 * are excluded from the result list — they're either link material
 * (media) or container folders (applications) and don't embed well.
 */
import { onMounted, ref, watch } from 'vue';
import { brainFetch } from '@vance/shared';

const props = defineProps<{
  projectId: string;
}>();

const emit = defineEmits<{
  (e: 'pick', uri: string): void;
  (e: 'close'): void;
}>();

interface DocSummary {
  id: string;
  path: string;
  kind: string | null;
  title: string | null;
  mimeType: string | null;
}

// Same blocklist as VanceEmbedNodeView — duplicated here so the
// picker can filter results client-side. (Server-side filter would
// be cleaner, but the same endpoint is used by the link picker
// which DOES want to see all kinds.)
const EMBED_BLOCKED_KINDS = new Set([
  'image', 'svg', 'pdf', 'audio', 'video',
  'application',
]);
function isEmbeddable(d: DocSummary): boolean {
  if (d.kind && EMBED_BLOCKED_KINDS.has(d.kind.toLowerCase())) return false;
  // Mime-type fallback for kinds the server didn't classify yet:
  // anything `image/*`, `audio/*`, `video/*`, `application/pdf` is
  // blocked. Markdown / yaml / json + custom Vance kinds pass.
  const mime = d.mimeType ?? '';
  if (mime.startsWith('image/')) return false;
  if (mime.startsWith('audio/')) return false;
  if (mime.startsWith('video/')) return false;
  if (mime === 'application/pdf') return false;
  return true;
}

const docQuery = ref('');
const docResults = ref<DocSummary[]>([]);
const docLoading = ref(false);
const docError = ref<string | null>(null);
const docTotal = ref(0);
let docTimer: ReturnType<typeof setTimeout> | null = null;

async function searchDocs(query: string) {
  docLoading.value = true;
  docError.value = null;
  try {
    const params = new URLSearchParams();
    params.set('projectId', props.projectId);
    if (query) params.set('query', query);
    params.set('size', '60');
    const resp = await brainFetch<{ items: DocSummary[]; total: number }>(
      'GET',
      `addon/workspace/documents/search?${params}`,
    );
    docResults.value = (resp.items ?? []).filter(isEmbeddable);
    docTotal.value = resp.total ?? docResults.value.length;
  } catch (e) {
    docError.value = e instanceof Error ? e.message : 'Search failed';
    docResults.value = [];
    docTotal.value = 0;
  } finally {
    docLoading.value = false;
  }
}

function scheduleSearch() {
  if (docTimer != null) clearTimeout(docTimer);
  docTimer = setTimeout(() => {
    docTimer = null;
    void searchDocs(docQuery.value.trim());
  }, 200);
}

function pickDoc(doc: DocSummary) {
  // Build the embedded-channel URI. Same convention as the link
  // picker: `vance:/<path>?kind=<kind>` for current-project refs.
  const params: string[] = [];
  if (doc.kind) params.push(`kind=${encodeURIComponent(doc.kind)}`);
  const uri = `vance:/${encodeURI(doc.path)}${params.length ? '?' + params.join('&') : ''}`;
  emit('pick', uri);
}

function close() { emit('close'); }
function onBackdrop(e: MouseEvent) {
  if (e.target === e.currentTarget) close();
}

onMounted(() => {
  void searchDocs('');
});
watch(() => props.projectId, () => searchDocs(docQuery.value.trim()));
</script>

<template>
  <div class="embed-picker" @click="onBackdrop">
    <div class="embed-picker__panel">
      <header class="embed-picker__header">
        <span>Embed document</span>
        <button class="embed-picker__close" type="button" @click="close">×</button>
      </header>

      <div class="embed-picker__actions">
        <input
          v-model="docQuery"
          type="search"
          class="embed-picker__search-input"
          placeholder="Search documents to embed…"
          autofocus
          @input="scheduleSearch"
          @keydown.escape="close"
        />
      </div>

      <div v-if="docError" class="embed-picker__error">{{ docError }}</div>
      <div v-if="docLoading" class="embed-picker__loading">Suche…</div>
      <div v-else-if="docResults.length === 0" class="embed-picker__empty">
        Keine einbettbaren Documents im Projekt gefunden.
      </div>
      <div v-else class="embed-picker__list">
        <button
          v-for="d in docResults"
          :key="d.id"
          type="button"
          class="embed-picker__list-item"
          @click="pickDoc(d)"
        >
          <span class="embed-picker__list-title">{{ d.title || d.path }}</span>
          <span class="embed-picker__list-meta">
            <span v-if="d.kind" class="embed-picker__list-kind">{{ d.kind }}</span>
            <span class="embed-picker__list-path">{{ d.path }}</span>
          </span>
        </button>
      </div>
      <div
        v-if="docResults.length > 0 && docTotal > docResults.length"
        class="embed-picker__truncated"
      >
        Showing {{ docResults.length }} of {{ docTotal }} — refine the search.
      </div>
    </div>
  </div>
</template>

<style scoped>
.embed-picker {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 2rem;
}
.embed-picker__panel {
  background: var(--color-bg, #fff);
  border-radius: 0.5rem;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.15);
  width: 100%;
  max-width: 36rem;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.embed-picker__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.75rem 1rem;
  font-weight: 600;
  border-bottom: 1px solid var(--color-border, #e5e7eb);
}
.embed-picker__close {
  background: none;
  border: none;
  font-size: 1.4rem;
  line-height: 1;
  cursor: pointer;
  color: var(--color-text-muted, #6b7280);
  padding: 0 0.25rem;
}
.embed-picker__actions {
  padding: 0.5rem 1rem;
  border-bottom: 1px solid var(--color-border, #e5e7eb);
}
.embed-picker__search-input {
  width: 100%;
  padding: 0.4rem 0.6rem;
  font-size: 0.9rem;
  border: 1px solid var(--color-border, #d1d5db);
  border-radius: 0.25rem;
  background: var(--color-bg, #fff);
  box-sizing: border-box;
}
.embed-picker__error {
  background: #fef2f2;
  color: #991b1b;
  font-size: 0.85rem;
  padding: 0.5rem 1rem;
}
.embed-picker__loading,
.embed-picker__empty {
  padding: 2rem;
  color: var(--color-text-muted, #6b7280);
  text-align: center;
  font-size: 0.9rem;
}
.embed-picker__truncated {
  padding: 0.5rem 1rem;
  font-size: 0.75rem;
  color: var(--color-text-muted, #6b7280);
  text-align: center;
  border-top: 1px solid var(--color-border, #e5e7eb);
}
.embed-picker__list {
  flex: 1;
  overflow-y: auto;
  padding: 0.5rem;
}
.embed-picker__list-item {
  width: 100%;
  text-align: left;
  background: none;
  border: 0;
  padding: 0.5rem 0.75rem;
  border-radius: 0.25rem;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  color: var(--color-text, #111827);
}
.embed-picker__list-item:hover {
  background: var(--color-button-bg, #f3f4f6);
}
.embed-picker__list-title {
  font-size: 0.9rem;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.embed-picker__list-meta {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  font-size: 0.7rem;
  color: var(--color-text-muted, #6b7280);
}
.embed-picker__list-kind {
  background: var(--color-border, #e5e7eb);
  color: var(--color-text, #111827);
  border-radius: 999px;
  padding: 0 0.4rem;
  font-family: monospace;
}
.embed-picker__list-path {
  font-family: monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
