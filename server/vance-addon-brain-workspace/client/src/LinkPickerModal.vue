<script setup lang="ts">
/**
 * Link picker — two tabs:
 *
 *   1. **Project document** — server-side search across every kind of
 *      document in the current project; emits a {@code vance:/<path>}
 *      URI with the document's kind as a hint.
 *   2. **Direct URL** — paste box for an external URL (or a manual
 *      vance: URI). "Open in new tab" checkbox, defaults by scheme.
 *
 * The picker emits the resolved {@code href} + {@code openInNewTab}
 * back to the host; the host calls {@code editorRef.applyLink(...)}.
 */
import { computed, onMounted, ref, watch } from 'vue';
import { brainFetch } from '@vance/shared';

const props = defineProps<{
  projectId: string;
  /** Currently selected link href, when editing an existing link. */
  initialHref?: string | null;
}>();

const emit = defineEmits<{
  (e: 'pick', href: string, openInNewTab: boolean): void;
  (e: 'clear'): void;
  (e: 'close'): void;
}>();

type TabId = 'project' | 'url';
const tab = ref<TabId>('project');

// ── Tab 1: Project document search ─────────────────────────────────
interface DocSummary {
  id: string;
  path: string;
  kind: string | null;
  title: string | null;
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
    // Recursive project-wide search via the workspace addon's
    // document search endpoint. /documents/folder?search would only
    // hit the root-level files (one folder layer deep), which makes
    // it useless for "find the yaml file somewhere in the project".
    const params = new URLSearchParams();
    params.set('projectId', props.projectId);
    if (query) params.set('query', query);
    params.set('size', '40');
    const resp = await brainFetch<{ items: DocSummary[]; total: number }>(
      'GET',
      `addon/workspace/documents/search?${params}`,
    );
    docResults.value = resp.items ?? [];
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
  const params: string[] = [];
  if (doc.kind) params.push(`kind=${encodeURIComponent(doc.kind)}`);
  const href = `vance:/${encodeURI(doc.path)}${params.length ? '?' + params.join('&') : ''}`;
  emit('pick', href, urlOpensInNewTab.value);
}

// ── Tab 2: Direct URL ──────────────────────────────────────────────
const urlInput = ref('');
const urlOpensInNewTab = ref(true);
const urlInputRef = ref<HTMLInputElement | null>(null);

const urlIsVance = computed(() => urlInput.value.startsWith('vance:'));
// Re-default the "open in new tab" toggle when the URL changes between
// vance: and external — saves the user a click.
watch(urlIsVance, (isVance) => {
  urlOpensInNewTab.value = !isVance;
});

function submitUrl() {
  const trimmed = urlInput.value.trim();
  if (!trimmed) return;
  emit('pick', trimmed, urlOpensInNewTab.value);
}

// ── Lifecycle ─────────────────────────────────────────────────────
function close() { emit('close'); }
function clearLink() { emit('clear'); }
function onBackdrop(e: MouseEvent) {
  if (e.target === e.currentTarget) close();
}

onMounted(() => {
  // If editing an existing link, jump straight to the URL tab with
  // the current href pre-filled — that's the typical "I want to
  // change this link's target" flow.
  if (props.initialHref) {
    urlInput.value = props.initialHref;
    urlOpensInNewTab.value = !props.initialHref.startsWith('vance:');
    tab.value = 'url';
  }
  void searchDocs('');
});

watch(tab, async (next) => {
  if (next === 'url') {
    await Promise.resolve();
    urlInputRef.value?.focus();
  }
});
</script>

<template>
  <div class="link-picker" @click="onBackdrop">
    <div class="link-picker__panel">
      <header class="link-picker__header">
        <span>Insert link</span>
        <button class="link-picker__close" type="button" @click="close">×</button>
      </header>

      <nav class="link-picker__tabs">
        <button
          type="button"
          class="link-picker__tab"
          :class="{ 'link-picker__tab--active': tab === 'project' }"
          @click="tab = 'project'"
        >Project document</button>
        <button
          type="button"
          class="link-picker__tab"
          :class="{ 'link-picker__tab--active': tab === 'url' }"
          @click="tab = 'url'"
        >Direct URL</button>
      </nav>

      <!-- ── Tab: Project document search ────────────────────────── -->
      <template v-if="tab === 'project'">
        <div class="link-picker__actions">
          <input
            v-model="docQuery"
            type="search"
            class="link-picker__search-input"
            placeholder="Search documents in this project…"
            autofocus
            @input="scheduleSearch"
          />
        </div>
        <div v-if="docError" class="link-picker__error">{{ docError }}</div>
        <div v-if="docLoading" class="link-picker__loading">Suche…</div>
        <div v-else-if="docResults.length === 0" class="link-picker__empty">
          Keine Documents gefunden.
        </div>
        <div v-else class="link-picker__list">
          <button
            v-for="d in docResults"
            :key="d.id"
            type="button"
            class="link-picker__list-item"
            @click="pickDoc(d)"
          >
            <span class="link-picker__list-title">{{ d.title || d.path }}</span>
            <span class="link-picker__list-meta">
              <span v-if="d.kind" class="link-picker__list-kind">{{ d.kind }}</span>
              <span class="link-picker__list-path">{{ d.path }}</span>
            </span>
          </button>
        </div>
        <div
          v-if="docResults.length > 0 && docTotal > docResults.length"
          class="link-picker__truncated"
        >
          Showing {{ docResults.length }} of {{ docTotal }} — refine the search.
        </div>
      </template>

      <!-- ── Tab: Direct URL ─────────────────────────────────────── -->
      <template v-else-if="tab === 'url'">
        <form class="link-picker__url-form" @submit.prevent="submitUrl">
          <input
            ref="urlInputRef"
            v-model="urlInput"
            type="url"
            class="link-picker__url-input"
            placeholder="https://example.com or vance:/path/document"
            @keydown.escape="close"
          />
          <label class="link-picker__url-checkbox">
            <input v-model="urlOpensInNewTab" type="checkbox" />
            <span>Open in new tab</span>
          </label>
          <div class="link-picker__url-actions">
            <button
              v-if="initialHref"
              type="button"
              class="link-picker__btn link-picker__btn--danger"
              @click="clearLink"
            >Remove link</button>
            <span class="link-picker__url-spacer" />
            <button
              type="button"
              class="link-picker__btn"
              @click="close"
            >Cancel</button>
            <button
              type="submit"
              class="link-picker__btn link-picker__btn--primary"
              :disabled="!urlInput.trim()"
            >Apply</button>
          </div>
        </form>
      </template>
    </div>
  </div>
</template>

<style scoped>
.link-picker {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 2rem;
}
.link-picker__panel {
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
.link-picker__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.75rem 1rem;
  font-weight: 600;
  border-bottom: 1px solid var(--color-border, #e5e7eb);
}
.link-picker__close {
  background: none;
  border: none;
  font-size: 1.4rem;
  line-height: 1;
  cursor: pointer;
  color: var(--color-text-muted, #6b7280);
  padding: 0 0.25rem;
}
.link-picker__tabs {
  display: flex;
  gap: 0.25rem;
  padding: 0.25rem 0.5rem;
  border-bottom: 1px solid var(--color-border, #e5e7eb);
  background: var(--color-button-bg, #fafafa);
}
.link-picker__tab {
  background: none;
  border: 0;
  padding: 0.4rem 0.8rem;
  border-radius: 0.25rem;
  cursor: pointer;
  font-size: 0.85rem;
  color: var(--color-text-muted, #6b7280);
}
.link-picker__tab:hover { color: var(--color-text, #111827); }
.link-picker__tab--active {
  background: var(--color-bg, #fff);
  color: var(--color-text, #111827);
  font-weight: 600;
}
.link-picker__actions {
  padding: 0.5rem 1rem;
  border-bottom: 1px solid var(--color-border, #e5e7eb);
}
.link-picker__search-input,
.link-picker__url-input {
  width: 100%;
  padding: 0.4rem 0.6rem;
  font-size: 0.9rem;
  border: 1px solid var(--color-border, #d1d5db);
  border-radius: 0.25rem;
  background: var(--color-bg, #fff);
  box-sizing: border-box;
}
.link-picker__error {
  background: #fef2f2;
  color: #991b1b;
  font-size: 0.85rem;
  padding: 0.5rem 1rem;
}
.link-picker__loading,
.link-picker__empty {
  padding: 2rem;
  color: var(--color-text-muted, #6b7280);
  text-align: center;
  font-size: 0.9rem;
}
.link-picker__truncated {
  padding: 0.5rem 1rem;
  font-size: 0.75rem;
  color: var(--color-text-muted, #6b7280);
  text-align: center;
  border-top: 1px solid var(--color-border, #e5e7eb);
}
.link-picker__list {
  flex: 1;
  overflow-y: auto;
  padding: 0.5rem;
}
.link-picker__list-item {
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
.link-picker__list-item:hover {
  background: var(--color-button-bg, #f3f4f6);
}
.link-picker__list-title {
  font-size: 0.9rem;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.link-picker__list-meta {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  font-size: 0.7rem;
  color: var(--color-text-muted, #6b7280);
}
.link-picker__list-kind {
  background: var(--color-border, #e5e7eb);
  color: var(--color-text, #111827);
  border-radius: 999px;
  padding: 0 0.4rem;
  font-family: monospace;
}
.link-picker__list-path {
  font-family: monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.link-picker__url-form {
  padding: 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.link-picker__url-checkbox {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.85rem;
  color: var(--color-text, #111827);
  cursor: pointer;
}
.link-picker__url-actions {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.link-picker__url-spacer { flex: 1; }
.link-picker__btn {
  padding: 0.4rem 0.9rem;
  border: 1px solid var(--color-border, #d1d5db);
  border-radius: 0.25rem;
  background: var(--color-button-bg, #fff);
  cursor: pointer;
  font-size: 0.85rem;
  color: var(--color-text, #111827);
}
.link-picker__btn:hover:not(:disabled) {
  background: var(--color-bg, #fafafa);
}
.link-picker__btn:disabled { opacity: 0.5; cursor: not-allowed; }
.link-picker__btn--primary {
  background: var(--color-link, #3b82f6);
  color: #fff;
  border-color: var(--color-link, #3b82f6);
}
.link-picker__btn--primary:hover:not(:disabled) {
  background: #2563eb;
}
.link-picker__btn--danger {
  background: transparent;
  color: #dc2626;
  border-color: #dc2626;
}
.link-picker__btn--danger:hover {
  background: #fef2f2;
}
</style>
