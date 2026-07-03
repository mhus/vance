<script setup lang="ts">
/**
 * Input picker — lists app-local text documents a `vance-input` block can
 * bind to (markdown / plain-text kinds), or creates a fresh one. Scoped to
 * the current app folder via the {@code pathPrefix} search param.
 *
 * On pick it emits a current-project {@code vance:} URI
 * ({@code vance:/<path>?kind=text}).
 */
import { onMounted, ref, watch } from 'vue';
import { brainFetch } from '@vance/shared';

const props = defineProps<{
  projectId: string;
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

// Plain-text kinds a single text value can live in.
const TEXT_KINDS = new Set(['text', 'markdown', 'md']);
function isTextDoc(d: DocSummary): boolean {
  if (TEXT_KINDS.has((d.kind ?? '').toLowerCase())) return true;
  const mime = d.mimeType ?? '';
  return mime.startsWith('text/');
}

const query = ref('');
const results = ref<DocSummary[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);
let timer: ReturnType<typeof setTimeout> | null = null;

async function search(q: string) {
  loading.value = true;
  error.value = null;
  try {
    const params = new URLSearchParams();
    params.set('projectId', props.projectId);
    if (props.folder && props.folder.trim()) params.set('pathPrefix', `${props.folder}/`);
    if (q) params.set('query', q);
    params.set('size', '100');
    const resp = await brainFetch<{ items: DocSummary[]; total: number }>(
      'GET',
      `addon/workbook/documents/search?${params}`,
    );
    results.value = (resp.items ?? []).filter(isTextDoc);
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Search failed';
    results.value = [];
  } finally {
    loading.value = false;
  }
}

function scheduleSearch() {
  if (timer != null) clearTimeout(timer);
  timer = setTimeout(() => {
    timer = null;
    void search(query.value.trim());
  }, 200);
}

function pick(doc: DocSummary) {
  emit('pick', `vance:/${encodeURI(doc.path)}?kind=text`);
}

function close() { emit('close'); }
function onBackdrop(e: MouseEvent) {
  if (e.target === e.currentTarget) close();
}

const newName = ref('');
const creating = ref(false);
const createError = ref<string | null>(null);

async function createInput() {
  if (creating.value) return;
  creating.value = true;
  createError.value = null;
  try {
    const params = new URLSearchParams();
    params.set('projectId', props.projectId);
    params.set('folder', props.folder ?? '');
    if (newName.value.trim()) params.set('name', newName.value.trim());
    const resp = await brainFetch<{ path: string }>(
      'POST',
      `addon/workbook/input/create?${params}`,
      { body: {} },
    );
    emit('pick', `vance:/${encodeURI(resp.path)}?kind=text`);
  } catch (e) {
    createError.value = e instanceof Error ? e.message : 'Create failed';
  } finally {
    creating.value = false;
  }
}

onMounted(() => { void search(''); });
watch(() => [props.projectId, props.folder], () => search(query.value.trim()));
</script>

<template>
  <div class="input-picker" @click="onBackdrop">
    <div class="input-picker__panel">
      <header class="input-picker__header">
        <span>Insert text input</span>
        <button class="input-picker__close" type="button" @click="close">×</button>
      </header>

      <div class="input-picker__actions">
        <input
          v-model="query"
          type="search"
          class="input-picker__search-input"
          placeholder="Search text documents in this app…"
          autofocus
          @input="scheduleSearch"
          @keydown.escape="close"
        />
      </div>

      <div v-if="error" class="input-picker__error">{{ error }}</div>
      <div v-if="loading" class="input-picker__loading">Suche…</div>
      <div v-else-if="results.length === 0" class="input-picker__empty">
        Keine Textdokumente in dieser App gefunden — lege unten ein neues an.
      </div>
      <div v-else class="input-picker__list">
        <button
          v-for="d in results"
          :key="d.id"
          type="button"
          class="input-picker__list-item"
          @click="pick(d)"
        >
          <span class="input-picker__list-title">{{ d.title || d.path }}</span>
          <span class="input-picker__list-path">{{ d.path }}</span>
        </button>
      </div>

      <div class="input-picker__create">
        <div v-if="createError" class="input-picker__create-error">{{ createError }}</div>
        <div class="input-picker__create-row">
          <input
            v-model="newName"
            type="text"
            class="input-picker__search-input"
            placeholder="New text name (optional)…"
            :disabled="creating"
            @keydown.enter.prevent="createInput"
            @keydown.escape="close"
          />
          <button
            type="button"
            class="input-picker__create-btn"
            :disabled="creating"
            @click="createInput"
          >{{ creating ? '…' : 'Create' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.input-picker {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 2rem;
}
.input-picker__panel {
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
.input-picker__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.75rem 1rem;
  font-weight: 600;
  border-bottom: 1px solid oklch(var(--bc) / 0.18);
}
.input-picker__close {
  background: none;
  border: none;
  font-size: 1.4rem;
  line-height: 1;
  cursor: pointer;
  color: oklch(var(--bc) / 0.65);
  padding: 0 0.25rem;
}
.input-picker__actions {
  padding: 0.5rem 1rem;
  border-bottom: 1px solid oklch(var(--bc) / 0.18);
}
.input-picker__search-input {
  width: 100%;
  padding: 0.4rem 0.6rem;
  font-size: 0.9rem;
  border: 1px solid oklch(var(--bc) / 0.18);
  border-radius: 0.25rem;
  background: oklch(var(--b1));
  box-sizing: border-box;
}
.input-picker__error {
  background: oklch(var(--er) / 0.12);
  color: oklch(var(--er));
  font-size: 0.85rem;
  padding: 0.5rem 1rem;
}
.input-picker__loading,
.input-picker__empty {
  padding: 1.5rem;
  color: oklch(var(--bc) / 0.65);
  text-align: center;
  font-size: 0.9rem;
}
.input-picker__list {
  flex: 1;
  overflow-y: auto;
  padding: 0.5rem;
}
.input-picker__list-item {
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
.input-picker__list-item:hover {
  background: oklch(var(--bc) / 0.06);
}
.input-picker__list-title {
  font-size: 0.9rem;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.input-picker__list-path {
  font-family: monospace;
  font-size: 0.75rem;
  color: oklch(var(--bc) / 0.65);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.input-picker__create {
  border-top: 1px solid oklch(var(--bc) / 0.18);
  padding: 0.6rem 1rem;
}
.input-picker__create-row {
  display: flex;
  gap: 0.5rem;
}
.input-picker__create-btn {
  border: 1px solid oklch(var(--p));
  background: oklch(var(--p));
  color: oklch(var(--pc));
  border-radius: 0.25rem;
  padding: 0.4rem 0.9rem;
  font-size: 0.85rem;
  cursor: pointer;
  white-space: nowrap;
}
.input-picker__create-btn:disabled { opacity: 0.5; cursor: default; }
.input-picker__create-error {
  color: oklch(var(--er));
  font-size: 0.8rem;
  margin-bottom: 0.4rem;
}
</style>
