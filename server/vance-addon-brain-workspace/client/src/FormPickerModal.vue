<script setup lang="ts">
/**
 * Form picker — lists app-local data documents the typed form can edit
 * (kinds {@code records} / {@code list} / {@code data}). The picked
 * document's {@code $meta.form} carries (or will carry, via design mode)
 * the field schema. Scoped to the current app folder via the
 * {@code pathPrefix} search param.
 *
 * On pick it emits a current-project {@code vance:} URI
 * ({@code vance:/<path>?kind=records}).
 */
import { onMounted, ref, watch } from 'vue';
import { brainFetch } from '@vance/shared';

const props = defineProps<{
  projectId: string;
  folder: string;
}>();

const emit = defineEmits<{
  (e: 'pick', configUri: string): void;
  (e: 'close'): void;
}>();

interface DocSummary {
  id: string;
  path: string;
  kind: string | null;
  title: string | null;
  mimeType: string | null;
}

// Data-holding kinds a typed form can edit (one-file model, §13).
const FORM_KINDS = new Set(['records', 'list', 'data']);

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
    if (props.folder && props.folder.trim()) {
      params.set('pathPrefix', `${props.folder}/`);
    }
    if (q) params.set('query', q);
    params.set('size', '100');
    const resp = await brainFetch<{ items: DocSummary[]; total: number }>(
      'GET',
      `addon/workspace/documents/search?${params}`,
    );
    results.value = (resp.items ?? []).filter(
      (d) => FORM_KINDS.has((d.kind ?? '').toLowerCase()),
    );
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
  const kind = (doc.kind ?? 'records').toLowerCase();
  const uri = `vance:/${encodeURI(doc.path)}?kind=${encodeURIComponent(kind)}`;
  emit('pick', uri);
}

// ── Create a new edit-config in the app folder ────────────────────
const newName = ref('');
const creating = ref(false);
const createError = ref<string | null>(null);

async function createForm() {
  const name = newName.value.trim();
  if (!name || creating.value) return;
  creating.value = true;
  createError.value = null;
  try {
    const params = new URLSearchParams();
    params.set('projectId', props.projectId);
    params.set('folder', props.folder ?? '');
    const resp = await brainFetch<{ configPath: string }>(
      'POST',
      `addon/workspace/form/create?${params}`,
      { body: { name } },
    );
    emit('pick', `vance:/${encodeURI(resp.configPath)}?kind=records`);
  } catch (e) {
    createError.value = e instanceof Error ? e.message : 'Create failed';
  } finally {
    creating.value = false;
  }
}

function close() { emit('close'); }
function onBackdrop(e: MouseEvent) {
  if (e.target === e.currentTarget) close();
}

onMounted(() => { void search(''); });
watch(() => [props.projectId, props.folder], () => search(query.value.trim()));
</script>

<template>
  <div class="form-picker" @click="onBackdrop">
    <div class="form-picker__panel">
      <header class="form-picker__header">
        <span>Insert form</span>
        <button class="form-picker__close" type="button" @click="close">×</button>
      </header>

      <div class="form-picker__actions">
        <input
          v-model="query"
          type="search"
          class="form-picker__search-input"
          placeholder="Search data documents in this app…"
          autofocus
          @input="scheduleSearch"
          @keydown.escape="close"
        />
      </div>

      <div v-if="error" class="form-picker__error">{{ error }}</div>
      <div v-if="loading" class="form-picker__loading">Suche…</div>
      <div v-else-if="results.length === 0" class="form-picker__empty">
        Keine Daten-Dokumente in dieser App gefunden.
        <div class="form-picker__hint">
          Lege unten ein neues an — es entsteht ein <code>kind: records</code>
          Dokument mit Schema unter <code>$meta.form.fields</code>.
        </div>
      </div>
      <div v-else class="form-picker__list">
        <button
          v-for="d in results"
          :key="d.id"
          type="button"
          class="form-picker__list-item"
          @click="pick(d)"
        >
          <span class="form-picker__list-title">{{ d.title || d.path }}</span>
          <span class="form-picker__list-path">{{ d.path }}</span>
        </button>
      </div>

      <div class="form-picker__create">
        <div v-if="createError" class="form-picker__create-error">{{ createError }}</div>
        <div class="form-picker__create-row">
          <input
            v-model="newName"
            type="text"
            class="form-picker__search-input"
            placeholder="New form name…"
            :disabled="creating"
            @keydown.enter.prevent="createForm"
            @keydown.escape="close"
          />
          <button
            type="button"
            class="form-picker__create-btn"
            :disabled="creating || !newName.trim()"
            @click="createForm"
          >{{ creating ? '…' : 'Create' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.form-picker {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 2rem;
}
.form-picker__panel {
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
.form-picker__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.75rem 1rem;
  font-weight: 600;
  border-bottom: 1px solid oklch(var(--bc) / 0.18);
}
.form-picker__close {
  background: none;
  border: none;
  font-size: 1.4rem;
  line-height: 1;
  cursor: pointer;
  color: oklch(var(--bc) / 0.65);
  padding: 0 0.25rem;
}
.form-picker__actions {
  padding: 0.5rem 1rem;
  border-bottom: 1px solid oklch(var(--bc) / 0.18);
}
.form-picker__search-input {
  width: 100%;
  padding: 0.4rem 0.6rem;
  font-size: 0.9rem;
  border: 1px solid oklch(var(--bc) / 0.18);
  border-radius: 0.25rem;
  background: oklch(var(--b1));
  box-sizing: border-box;
}
.form-picker__error {
  background: oklch(var(--er) / 0.12);
  color: oklch(var(--er));
  font-size: 0.85rem;
  padding: 0.5rem 1rem;
}
.form-picker__loading,
.form-picker__empty {
  padding: 1.5rem;
  color: oklch(var(--bc) / 0.65);
  text-align: center;
  font-size: 0.9rem;
}
.form-picker__hint {
  margin-top: 0.5rem;
  font-size: 0.78rem;
  color: oklch(var(--bc) / 0.5);
}
.form-picker__hint code {
  font-family: monospace;
  background: oklch(var(--bc) / 0.1);
  padding: 0 0.25em;
  border-radius: 0.2em;
}
.form-picker__list {
  flex: 1;
  overflow-y: auto;
  padding: 0.5rem;
}
.form-picker__list-item {
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
.form-picker__list-item:hover {
  background: oklch(var(--bc) / 0.06);
}
.form-picker__list-title {
  font-size: 0.9rem;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.form-picker__list-path {
  font-family: monospace;
  font-size: 0.75rem;
  color: oklch(var(--bc) / 0.65);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.form-picker__create {
  border-top: 1px solid oklch(var(--bc) / 0.18);
  padding: 0.6rem 1rem;
}
.form-picker__create-row {
  display: flex;
  gap: 0.5rem;
}
.form-picker__create-btn {
  border: 1px solid oklch(var(--p));
  background: oklch(var(--p));
  color: oklch(var(--pc));
  border-radius: 0.25rem;
  padding: 0.4rem 0.9rem;
  font-size: 0.85rem;
  cursor: pointer;
  white-space: nowrap;
}
.form-picker__create-btn:disabled { opacity: 0.5; cursor: default; }
.form-picker__create-error {
  color: oklch(var(--er));
  font-size: 0.8rem;
  margin-bottom: 0.4rem;
}
</style>
