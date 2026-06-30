<script setup lang="ts">
/**
 * Embed picker — two tabs over the server-side recursive document search.
 *
 * - "App" tab scopes the search to the current application folder
 *   (`pathPrefix=<folder>/`), so app-local data / output documents are
 *   one click away. This is the default tab.
 * - "Project" tab is the full project-wide search (no prefix).
 *
 * Both go into a {@code vance-embed} block. Media kinds (image / svg /
 * pdf / audio / video) and applications are excluded from the result
 * list — they're either link material (media) or container folders
 * (applications) and don't embed well.
 */
import { computed, onMounted, ref, watch } from 'vue';
import { brainFetch } from '@vance/shared';

const props = defineProps<{
  projectId: string;
  /** The current application folder (path of `_app.yaml` minus the file). */
  folder: string;
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

type Tab = 'app' | 'project';

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

const tab = ref<Tab>('app');
const docQuery = ref('');
const docResults = ref<DocSummary[]>([]);
const docLoading = ref(false);
const docError = ref<string | null>(null);
const docTotal = ref(0);
let docTimer: ReturnType<typeof setTimeout> | null = null;

// Prefix the "App" tab constrains the search to. The Project tab
// passes no prefix. A blank folder degrades gracefully to project-wide.
const appPrefix = computed(() =>
  props.folder && props.folder.trim() ? `${props.folder}/` : '');

async function searchDocs(query: string) {
  docLoading.value = true;
  docError.value = null;
  try {
    const params = new URLSearchParams();
    params.set('projectId', props.projectId);
    if (tab.value === 'app' && appPrefix.value) {
      params.set('pathPrefix', appPrefix.value);
    }
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

function setTab(next: Tab) {
  if (tab.value === next) return;
  tab.value = next;
  void searchDocs(docQuery.value.trim());
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
watch(() => [props.projectId, props.folder], () => searchDocs(docQuery.value.trim()));
</script>

<template>
  <div class="embed-picker" @click="onBackdrop">
    <div class="embed-picker__panel">
      <header class="embed-picker__header">
        <span>Embed document</span>
        <button class="embed-picker__close" type="button" @click="close">×</button>
      </header>

      <div class="embed-picker__tabs">
        <button
          type="button"
          :class="['embed-picker__tab', { 'embed-picker__tab--active': tab === 'app' }]"
          @click="setTab('app')"
        >App</button>
        <button
          type="button"
          :class="['embed-picker__tab', { 'embed-picker__tab--active': tab === 'project' }]"
          @click="setTab('project')"
        >Project</button>
      </div>

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
        <template v-if="tab === 'app'">
          Keine einbettbaren Documents in dieser App gefunden.
        </template>
        <template v-else>
          Keine einbettbaren Documents im Projekt gefunden.
        </template>
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
  background: oklch(var(--b1));
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
  border-bottom: 1px solid oklch(var(--bc) / 0.18);
}
.embed-picker__close {
  background: none;
  border: none;
  font-size: 1.4rem;
  line-height: 1;
  cursor: pointer;
  color: oklch(var(--bc) / 0.65);
  padding: 0 0.25rem;
}
.embed-picker__tabs {
  display: flex;
  gap: 0.25rem;
  padding: 0.5rem 1rem 0;
  border-bottom: 1px solid oklch(var(--bc) / 0.18);
}
.embed-picker__tab {
  background: none;
  border: 0;
  border-bottom: 2px solid transparent;
  padding: 0.35rem 0.75rem;
  font-size: 0.85rem;
  cursor: pointer;
  color: oklch(var(--bc) / 0.65);
}
.embed-picker__tab--active {
  color: oklch(var(--bc));
  border-bottom-color: oklch(var(--p));
  font-weight: 600;
}
.embed-picker__actions {
  padding: 0.5rem 1rem;
  border-bottom: 1px solid oklch(var(--bc) / 0.18);
}
.embed-picker__search-input {
  width: 100%;
  padding: 0.4rem 0.6rem;
  font-size: 0.9rem;
  border: 1px solid oklch(var(--bc) / 0.18);
  border-radius: 0.25rem;
  background: oklch(var(--b1));
  box-sizing: border-box;
}
.embed-picker__error {
  background: oklch(var(--er) / 0.12);
  color: oklch(var(--er));
  font-size: 0.85rem;
  padding: 0.5rem 1rem;
}
.embed-picker__loading,
.embed-picker__empty {
  padding: 2rem;
  color: oklch(var(--bc) / 0.65);
  text-align: center;
  font-size: 0.9rem;
}
.embed-picker__truncated {
  padding: 0.5rem 1rem;
  font-size: 0.75rem;
  color: oklch(var(--bc) / 0.65);
  text-align: center;
  border-top: 1px solid oklch(var(--bc) / 0.18);
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
  color: oklch(var(--bc));
}
.embed-picker__list-item:hover {
  background: oklch(var(--bc) / 0.06);
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
  color: oklch(var(--bc) / 0.65);
}
.embed-picker__list-kind {
  background: oklch(var(--bc) / 0.18);
  color: oklch(var(--bc));
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
