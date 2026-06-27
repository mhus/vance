<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useDocumentPrefixReaction } from '@vance/shared';
import { scanWorkspace, rebuildWorkspace } from './api';
import type { WorkspaceView } from './generated/workspace/WorkspaceView';
import type { WorkspacePageView } from './generated/workspace/WorkspacePageView';

/**
 * Workspace view — page-tree sidebar + main pane. Clicking on a page
 * opens it in a new cortex tab (the standard CortexDocument open
 * mechanism), keeping multi-doc state consistent with the rest of the
 * UI. The main pane shows a card overview of pages — no embedded
 * canvas editor in v1 (avoids cross-federation-remote import).
 */
const props = defineProps<{
  document: {
    id: string;
    path: string;
    projectId: string;
    title?: string | null;
  };
}>();

const projectId = computed(() => props.document.projectId);
const folder = computed(() => props.document.path.replace(/\/_app\.yaml$/, ''));
const title = computed(() => props.document.title ?? folder.value);

const view = ref<WorkspaceView | null>(null);
const error = ref<string | null>(null);
const loading = ref(false);
const rebuilding = ref(false);

async function load() {
  loading.value = true;
  error.value = null;
  try {
    view.value = await scanWorkspace(projectId.value, folder.value);
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not scan workspace.';
    view.value = null;
  } finally {
    loading.value = false;
  }
}

async function rebuild() {
  rebuilding.value = true;
  try {
    await rebuildWorkspace(projectId.value, folder.value);
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Rebuild failed.';
  } finally {
    rebuilding.value = false;
  }
}

function openPage(p: WorkspacePageView) {
  // Convention: cortex.html lives at /cortex.html; tab open via query param.
  // The detail is host-shell-internal, but a window-event hook is the
  // lightest cross-shell signal.
  window.dispatchEvent(
    new CustomEvent('vance:cortex:open-document', {
      detail: { path: p.path, projectId: projectId.value },
    }),
  );
  // Fallback for hosts without the event hook: open a fresh tab on the
  // same origin so the user at least gets to the page.
  // (Comment-only — actual fallback would clutter v1 UI; skip.)
}

function pagesBySection(): Map<string, WorkspacePageView[]> {
  const grouped = new Map<string, WorkspacePageView[]>();
  if (!view.value) return grouped;
  for (const p of view.value.pages) {
    const key = p.section ?? '';
    let list = grouped.get(key);
    if (!list) {
      list = [];
      grouped.set(key, list);
    }
    list.push(p);
  }
  return grouped;
}

useDocumentPrefixReaction({
  prefix: computed(() => `${folder.value}/`),
  debounceMs: 250,
  onRemoteChange: () => load(),
});

watch(folder, () => load());
onMounted(() => load());
</script>

<template>
  <div class="workspace-app">
    <aside class="workspace-app__sidebar">
      <header class="workspace-app__title">
        <div class="workspace-app__title-text">{{ title }}</div>
        <button
          class="workspace-app__rebuild-btn"
          :disabled="rebuilding"
          @click="rebuild"
        >
          {{ rebuilding ? '…' : 'Rebuild' }}
        </button>
      </header>

      <div v-if="error" class="workspace-app__error">{{ error }}</div>

      <div v-if="view" class="workspace-app__tree">
        <template v-for="[section, pages] in pagesBySection()" :key="section">
          <div class="workspace-app__section-label">
            {{ section || 'Pages' }}
          </div>
          <button
            v-for="p in pages"
            :key="p.path"
            class="workspace-app__page-link"
            @click="openPage(p)"
            :title="p.description ?? p.path"
          >
            {{ p.title }}
          </button>
        </template>
      </div>

      <div v-if="view && view.pages.length === 0" class="workspace-app__empty">
        Noch keine Pages in diesem Workspace.
      </div>
    </aside>

    <main class="workspace-app__main">
      <div v-if="loading" class="workspace-app__main-empty">Lade Workspace…</div>
      <div v-else-if="view" class="workspace-app__cards">
        <div
          v-for="p in view.pages"
          :key="p.path"
          class="workspace-app__card"
          @click="openPage(p)"
        >
          <div class="workspace-app__card-title">{{ p.title }}</div>
          <div v-if="p.section" class="workspace-app__card-section">
            {{ p.section }}
          </div>
          <div v-if="p.description" class="workspace-app__card-desc">
            {{ p.description }}
          </div>
          <code class="workspace-app__card-path">{{ p.relativePath }}</code>
        </div>
        <div v-if="view.pages.length === 0" class="workspace-app__main-empty">
          Keine Pages. Erstelle eine über das Chat-Tool
          <code>canvas_create</code>, dann „Rebuild" klicken.
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.workspace-app {
  display: grid;
  grid-template-columns: 240px 1fr;
  height: 100%;
  min-height: 0;
  background: var(--color-bg, #fff);
}
.workspace-app__sidebar {
  border-right: 1px solid var(--color-border, #e5e7eb);
  padding: 0.75rem 0.5rem;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.workspace-app__title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 0.5rem 0.75rem;
  border-bottom: 1px solid var(--color-border, #e5e7eb);
  margin-bottom: 0.5rem;
}
.workspace-app__title-text {
  font-weight: 600;
}
.workspace-app__rebuild-btn {
  font-size: 0.75rem;
  padding: 0.125rem 0.5rem;
  border: 1px solid var(--color-border, #d1d5db);
  border-radius: 0.25rem;
  background: var(--color-button-bg, #f9fafb);
  cursor: pointer;
}
.workspace-app__rebuild-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.workspace-app__error {
  background: #fef2f2;
  color: #991b1b;
  font-size: 0.85rem;
  padding: 0.5rem;
  border-radius: 0.25rem;
}
.workspace-app__tree {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}
.workspace-app__section-label {
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--color-text-muted, #6b7280);
  padding: 0.5rem 0.5rem 0.25rem;
}
.workspace-app__page-link {
  text-align: left;
  padding: 0.375rem 0.5rem;
  border-radius: 0.25rem;
  background: none;
  border: none;
  cursor: pointer;
  color: inherit;
  font-size: 0.9rem;
}
.workspace-app__page-link:hover {
  background: var(--color-button-bg, #f3f4f6);
}
.workspace-app__empty {
  color: var(--color-text-muted, #6b7280);
  font-size: 0.85rem;
  padding: 0.5rem;
}
.workspace-app__main {
  padding: 1.5rem;
  overflow-y: auto;
}
.workspace-app__main-empty {
  color: var(--color-text-muted, #6b7280);
  font-style: italic;
}
.workspace-app__cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 1rem;
}
.workspace-app__card {
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 0.5rem;
  padding: 0.75rem 1rem;
  background: var(--color-button-bg, #fafafa);
  cursor: pointer;
  transition: box-shadow 0.15s ease;
}
.workspace-app__card:hover {
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.08);
}
.workspace-app__card-title {
  font-weight: 600;
  margin-bottom: 0.25rem;
}
.workspace-app__card-section {
  font-size: 0.7rem;
  color: var(--color-text-muted, #6b7280);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin-bottom: 0.25rem;
}
.workspace-app__card-desc {
  font-size: 0.85rem;
  color: var(--color-text-muted, #4b5563);
  margin-bottom: 0.5rem;
}
.workspace-app__card-path {
  font-size: 0.7rem;
  color: var(--color-text-muted, #9ca3af);
}
</style>
