<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { brainFetchText, useDocumentPrefixReaction } from '@vance/shared';
import { BlockView, parseDocument } from '@vance/block-editor';
import { scanWorkspace, rebuildWorkspace } from './api';
import type { WorkspaceView } from './generated/workspace/WorkspaceView';
import type { WorkspacePageView } from './generated/workspace/WorkspacePageView';

/**
 * Workspace view — Master-Detail layout.
 *
 *  ┌────────────┬─────────────────────────────────────┐
 *  │            │                                     │
 *  │  Sidebar   │   Selected page (BlockView)         │
 *  │  page list │                                     │
 *  │            │                                     │
 *  └────────────┴─────────────────────────────────────┘
 *
 *  - Sidebar: page-tree grouped by section. Click swaps the right
 *    pane, no new tab.
 *  - Right pane: read-only BlockView of the currently selected page
 *    (v1). Editing is the next sprint — when added, the editor will
 *    drop in here without changing the layout contract.
 *  - Initial selection: `landingPage` from the manifest if set,
 *    else the auto-generated `_index.md`, else empty-state.
 *  - Toolbar: rebuild trigger; future sprint adds "new page" etc.
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

const activePageId = ref<string | null>(null);
const activePageView = ref<WorkspacePageView | null>(null);
const activeMarkdown = ref<string | null>(null);
const pageLoading = ref(false);
const pageError = ref<string | null>(null);

async function loadWorkspace() {
  loading.value = true;
  error.value = null;
  try {
    view.value = await scanWorkspace(projectId.value, folder.value);
    // Pick the initial selection if we don't have one yet.
    if (activePageId.value == null) {
      pickInitialPage();
    } else {
      // Refresh metadata for the currently selected page from the scan.
      const matched =
        view.value.pages.find((p) => p.id === activePageId.value) ?? null;
      activePageView.value = matched;
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not scan workspace.';
    view.value = null;
  } finally {
    loading.value = false;
  }
}

function pickInitialPage() {
  const v = view.value;
  if (!v) return;
  if (v.landingPageId) {
    selectPage(v.landingPageId, null);
    return;
  }
  if (v.indexPageId) {
    selectPage(v.indexPageId, null);
    return;
  }
  if (v.pages.length > 0) {
    selectPage(v.pages[0].id, v.pages[0]);
  }
}

async function selectPage(id: string, page: WorkspacePageView | null) {
  activePageId.value = id;
  activePageView.value = page ?? findPageById(id);
  await loadActivePageContent();
}

function findPageById(id: string): WorkspacePageView | null {
  const v = view.value;
  if (!v) return null;
  return v.pages.find((p) => p.id === id) ?? null;
}

async function loadActivePageContent() {
  if (!activePageId.value) {
    activeMarkdown.value = null;
    return;
  }
  pageLoading.value = true;
  pageError.value = null;
  try {
    activeMarkdown.value = await brainFetchText(
      `documents/${encodeURIComponent(activePageId.value)}/content`,
    );
  } catch (e) {
    pageError.value = e instanceof Error ? e.message : 'Could not load page.';
    activeMarkdown.value = null;
  } finally {
    pageLoading.value = false;
  }
}

const parsedPage = computed(() =>
  activeMarkdown.value == null ? null : parseDocument(activeMarkdown.value),
);

async function rebuild() {
  rebuilding.value = true;
  try {
    await rebuildWorkspace(projectId.value, folder.value);
    await loadWorkspace();
    // Reload current page in case its inlineText changed (e.g. _index.md
    // is the current selection).
    await loadActivePageContent();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Rebuild failed.';
  } finally {
    rebuilding.value = false;
  }
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
  onRemoteChange: async () => {
    await loadWorkspace();
    await loadActivePageContent();
  },
});

watch(folder, () => {
  // Folder switch — drop selection and let pickInitialPage pick again
  // after the next scan.
  activePageId.value = null;
  activePageView.value = null;
  activeMarkdown.value = null;
  loadWorkspace();
});

onMounted(() => loadWorkspace());
</script>

<template>
  <div class="workspace-app">
    <aside class="workspace-app__sidebar">
      <header class="workspace-app__title">
        <div class="workspace-app__title-text">{{ title }}</div>
        <button
          class="workspace-app__rebuild-btn"
          :disabled="rebuilding"
          :title="rebuilding ? 'Rebuilding…' : 'Rebuild _index.md'"
          @click="rebuild"
        >
          {{ rebuilding ? '…' : '↻' }}
        </button>
      </header>

      <div v-if="error" class="workspace-app__error">{{ error }}</div>

      <div v-if="view" class="workspace-app__tree">
        <template v-if="view.indexPageId">
          <div class="workspace-app__section-label">Workspace</div>
          <button
            class="workspace-app__page-link"
            :class="{
              'workspace-app__page-link--active': activePageId === view.indexPageId,
            }"
            @click="selectPage(view.indexPageId!, null)"
          >
            <span class="workspace-app__page-link-icon">⌂</span>
            Index
          </button>
        </template>

        <template v-for="[section, pages] in pagesBySection()" :key="section">
          <div class="workspace-app__section-label">
            {{ section || 'Pages' }}
          </div>
          <button
            v-for="p in pages"
            :key="p.id"
            class="workspace-app__page-link"
            :class="{
              'workspace-app__page-link--active': activePageId === p.id,
            }"
            @click="selectPage(p.id, p)"
            :title="p.description ?? p.path"
          >
            {{ p.title }}
          </button>
        </template>
      </div>

      <div v-if="view && view.pages.length === 0 && !view.indexPageId" class="workspace-app__empty">
        Noch keine Pages.
      </div>
    </aside>

    <main class="workspace-app__main">
      <div v-if="loading" class="workspace-app__main-empty">Lade Workspace…</div>

      <template v-else-if="activePageId">
        <header v-if="activePageView" class="workspace-app__page-header">
          <h1 class="workspace-app__page-title">{{ activePageView.title }}</h1>
          <div v-if="activePageView.section" class="workspace-app__page-section">
            {{ activePageView.section }}
          </div>
          <p v-if="activePageView.description" class="workspace-app__page-description">
            {{ activePageView.description }}
          </p>
        </header>

        <div v-if="pageLoading" class="workspace-app__main-empty">Lade Page…</div>
        <div v-else-if="pageError" class="workspace-app__error">{{ pageError }}</div>
        <BlockView
          v-else-if="parsedPage"
          :blocks="parsedPage.blocks"
        />
      </template>

      <div v-else class="workspace-app__main-empty">
        Wähle eine Page aus der Sidebar.
      </div>
    </main>
  </div>
</template>

<style scoped>
.workspace-app {
  display: grid;
  grid-template-columns: 260px 1fr;
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
  gap: 0.125rem;
  background: var(--color-sidebar-bg, #fafafa);
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
  font-size: 0.95rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.workspace-app__rebuild-btn {
  font-size: 0.9rem;
  padding: 0.125rem 0.5rem;
  border: 1px solid var(--color-border, #d1d5db);
  border-radius: 0.25rem;
  background: var(--color-button-bg, #fff);
  cursor: pointer;
  flex-shrink: 0;
}
.workspace-app__rebuild-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.workspace-app__error {
  background: #fef2f2;
  color: #991b1b;
  font-size: 0.85rem;
  padding: 0.5rem;
  border-radius: 0.25rem;
  margin: 0.5rem;
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
  display: flex;
  align-items: center;
  gap: 0.4em;
}
.workspace-app__page-link:hover {
  background: var(--color-button-bg, #f3f4f6);
}
.workspace-app__page-link--active {
  background: var(--color-active-bg, #e0e7ff);
  color: var(--color-active-fg, #3730a3);
  font-weight: 500;
}
.workspace-app__page-link-icon {
  font-size: 0.85em;
  opacity: 0.7;
}
.workspace-app__empty {
  color: var(--color-text-muted, #6b7280);
  font-size: 0.85rem;
  padding: 0.5rem;
}
.workspace-app__main {
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}
.workspace-app__main-empty {
  color: var(--color-text-muted, #6b7280);
  font-style: italic;
  padding: 2rem;
}
.workspace-app__page-header {
  max-width: 760px;
  margin: 0 auto;
  padding: 1.5rem 2rem 0;
  width: 100%;
  box-sizing: border-box;
}
.workspace-app__page-title {
  font-size: 1.875rem;
  font-weight: 700;
  margin: 0 0 0.25em;
}
.workspace-app__page-section {
  font-size: 0.75rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--color-text-muted, #6b7280);
  margin-bottom: 0.25rem;
}
.workspace-app__page-description {
  color: var(--color-text-muted, #6b7280);
  margin: 0 0 1rem;
}
</style>
