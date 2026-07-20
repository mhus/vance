<script setup lang="ts">
/**
 * "What links here" — the inbound-link list for the active wiki page.
 * This is the wiki's backlinks-first navigation: every page that
 * contains a `[[…]]` resolving to the active page shows up here, so the
 * link graph is browsable in both directions. Backed by the server's
 * pre-computed graph (GET addon/wiki/backlinks?path=), which resolves
 * links space-aware and de-duplicated. Clicking a row navigates to that
 * source page. See planning/app-wiki.md §4.
 */
import { ref, watch } from 'vue';
import { wikiBacklinks } from './api';
import type { WikiPageView } from './generated/wiki/WikiPageView';

const props = defineProps<{
  projectId: string;
  folder: string;
  /** Absolute path of the active page (null when none / index synthetic). */
  path: string | null;
}>();

const emit = defineEmits<{
  (e: 'navigate', page: WikiPageView): void;
}>();

const inbound = ref<WikiPageView[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);

async function load(path: string | null): Promise<void> {
  if (!path) {
    inbound.value = [];
    return;
  }
  loading.value = true;
  error.value = null;
  try {
    const data = await wikiBacklinks(props.projectId, props.folder, path);
    inbound.value = data.inbound ?? [];
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not load backlinks.';
    inbound.value = [];
  } finally {
    loading.value = false;
  }
}

watch(() => props.path, (p) => void load(p), { immediate: true });

function open(page: WikiPageView): void {
  emit('navigate', page);
}
</script>

<template>
  <div class="wiki-backlinks">
    <header class="wiki-backlinks__header">
      <span class="wiki-backlinks__title">What links here</span>
      <span class="wiki-backlinks__count">{{ inbound.length }}</span>
    </header>

    <div v-if="error" class="wiki-backlinks__error">{{ error }}</div>
    <div v-else-if="loading" class="wiki-backlinks__hint">Loading…</div>
    <div v-else-if="inbound.length === 0" class="wiki-backlinks__hint">
      No pages link here yet. Link with <code>[[{{ '…' }}]]</code>, then rebuild.
    </div>

    <ul v-else class="wiki-backlinks__list">
      <li
        v-for="p in inbound"
        :key="p.id"
        class="wiki-backlinks__row"
        @click="open(p)"
      >
        <span class="wiki-backlinks__row-title">{{ p.title }}</span>
        <span v-if="p.space" class="wiki-backlinks__row-space">{{ p.space }}</span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.wiki-backlinks {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.wiki-backlinks__header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.75rem;
  border-bottom: 1px solid hsl(var(--bc) / 0.15);
}
.wiki-backlinks__title { font-size: 0.85rem; font-weight: 600; }
.wiki-backlinks__count { font-size: 0.75rem; opacity: 0.6; }
.wiki-backlinks__hint,
.wiki-backlinks__error {
  padding: 1rem;
  font-size: 0.8rem;
  opacity: 0.7;
  text-align: center;
}
.wiki-backlinks__error { color: #d33; }
.wiki-backlinks__list {
  flex: 1;
  overflow-y: auto;
  list-style: none;
  margin: 0;
  padding: 0.25rem;
}
.wiki-backlinks__row {
  display: flex;
  flex-direction: column;
  gap: 0.1rem;
  padding: 0.4rem 0.5rem;
  border-radius: 6px;
  cursor: pointer;
}
.wiki-backlinks__row:hover { background: hsl(var(--bc) / 0.08); }
.wiki-backlinks__row-title { font-size: 0.85rem; font-weight: 500; }
.wiki-backlinks__row-space { font-size: 0.7rem; opacity: 0.55; }
</style>
