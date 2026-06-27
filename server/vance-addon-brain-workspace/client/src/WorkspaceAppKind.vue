<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import {
  brainFetch,
  brainFetchText,
  brainSendRaw,
  documentContentUrl,
  useDocumentPrefixReaction,
} from '@vance/shared';
import { CanvasEditor, parseDocument } from '@vance/block-editor';
import { scanWorkspace, rebuildWorkspace, createWorkspacePage } from './api';
import AssetPickerModal from './AssetPickerModal.vue';
import type { WorkspaceView } from './generated/workspace/WorkspaceView';
import type { WorkspacePageView } from './generated/workspace/WorkspacePageView';

/**
 * Workspace view — Master-Detail with inplace editing (Notion-style).
 *
 *  ┌────────────┬─────────────────────────────────────┐
 *  │            │                                     │
 *  │  Sidebar   │   Active page (CanvasEditor, live)  │
 *  │  page list │                                     │
 *  │            │                                     │
 *  └────────────┴─────────────────────────────────────┘
 *
 *  - Sidebar: page-tree grouped by section. Click swaps the active
 *    page. Pending edits are flushed before the switch.
 *  - Right pane: Tiptap CanvasEditor mounted with the page's Markdown
 *    source. Auto-saves debounced (~800ms after last keystroke) via
 *    PUT documents/{id}/content.
 *  - Initial selection: `landingPage` → `_index.md` → first page.
 *  - Save status indicator in the page header: "Saving…" / "Saved".
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

// Parsed front-matter of the active page — drives the header
// (icon, cover, title override, description override). Updated
// whenever activeMarkdown changes.
const parsedPage = computed(() =>
  activeMarkdown.value == null ? null : parseDocument(activeMarkdown.value),
);
const pageIcon = computed(() => parsedPage.value?.icon ?? null);
const pageCover = computed(() => parsedPage.value?.cover ?? null);
const pageDisplayTitle = computed(
  () => parsedPage.value?.title ?? activePageView.value?.title ?? '',
);
const pageDisplayDescription = computed(
  () => parsedPage.value?.description ?? activePageView.value?.description ?? null,
);

type SaveStatus = 'idle' | 'dirty' | 'saving' | 'saved' | 'error';
const saveStatus = ref<SaveStatus>('idle');
const lastSaveError = ref<string | null>(null);

const editorRef = ref<{
  save: () => void;
  flush: () => boolean;
  insertImage: (src: string, alt: string) => void;
} | null>(null);

const assetPickerOpen = ref(false);
function openAssetPicker() {
  assetPickerOpen.value = true;
}
function onAssetPick(src: string, alt: string) {
  editorRef.value?.insertImage(src, alt);
  assetPickerOpen.value = false;
}

// Self-write tracker — the last body we PUT for the active page. The
// server's editorId-based filter should already prevent the
// documents.changed broadcast from echoing back to us, but in practice
// (server may normalise whitespace, ID header may be missing on a stale
// session, frame ordering may interleave) we can't rely on that alone.
const lastSavedBodies = ref<Map<string, string>>(new Map());

// Quiet-window after a save during which we suppress remote-content
// reloads for the active page. Without this, the WS echo of our own
// write rebuilds the ProseMirror doc → cursor jumps. 3 s is long
// enough to cover RTT + broadcast lag, short enough that genuine
// cross-user edits land quickly.
const SELF_WRITE_QUIET_MS = 3000;
const lastSelfWriteAt = ref<Map<string, number>>(new Map());

function withinSelfWriteWindow(id: string): boolean {
  const t = lastSelfWriteAt.value.get(id);
  return t != null && Date.now() - t < SELF_WRITE_QUIET_MS;
}

const creating = ref(false);
const newPageOpen = ref(false);
const newPageTitle = ref('');
const newPageSection = ref('');
const newPageError = ref<string | null>(null);
const newPageTitleInputRef = ref<HTMLInputElement | null>(null);

const existingSections = computed<string[]>(() => {
  const v = view.value;
  if (!v) return [];
  const set = new Set<string>();
  for (const p of v.pages) {
    if (p.section) set.add(p.section);
  }
  return [...set].sort();
});

async function openNewPage() {
  newPageOpen.value = true;
  newPageTitle.value = '';
  newPageError.value = null;
  // Pre-fill section from the currently selected page so the new page
  // lands next to it — most natural workflow.
  newPageSection.value = activePageView.value?.section ?? '';
  await nextTick();
  newPageTitleInputRef.value?.focus();
}

function closeNewPage() {
  newPageOpen.value = false;
  newPageError.value = null;
  creating.value = false;
}

async function submitNewPage() {
  const title = newPageTitle.value.trim();
  if (!title) {
    newPageError.value = 'Title required';
    return;
  }
  creating.value = true;
  newPageError.value = null;
  try {
    const section = newPageSection.value.trim();
    const page = await createWorkspacePage(projectId.value, folder.value, {
      title,
      ...(section ? { section } : {}),
    });
    closeNewPage();
    await loadWorkspace();
    await selectPage(page.id, page);
  } catch (e) {
    newPageError.value = e instanceof Error ? e.message : 'Could not create page.';
    creating.value = false;
  }
}

async function loadWorkspace() {
  loading.value = true;
  error.value = null;
  try {
    view.value = await scanWorkspace(projectId.value, folder.value);
    if (activePageId.value == null) {
      pickInitialPage();
    } else {
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
    void selectPage(v.landingPageId, null);
    return;
  }
  if (v.indexPageId) {
    void selectPage(v.indexPageId, null);
    return;
  }
  if (v.pages.length > 0) {
    void selectPage(v.pages[0].id, v.pages[0]);
  }
}

async function selectPage(id: string, page: WorkspacePageView | null) {
  if (id === activePageId.value) return;
  // Flush pending edits on the current page before switching.
  if (editorRef.value?.flush()) {
    // Give the @save handler one tick to fire + the PUT to start.
    await nextTick();
  }
  activePageId.value = id;
  activePageView.value = page ?? findPageById(id);
  saveStatus.value = 'idle';
  lastSaveError.value = null;
  await loadActivePageContent();
}

function findPageById(id: string): WorkspacePageView | null {
  const v = view.value;
  if (!v) return null;
  return v.pages.find((p) => p.id === id) ?? null;
}

async function loadActivePageContent(options: { force?: boolean } = {}) {
  if (!activePageId.value) {
    activeMarkdown.value = null;
    return;
  }
  pageLoading.value = true;
  pageError.value = null;
  try {
    const id = activePageId.value;
    if (!options.force && withinSelfWriteWindow(id)) {
      return;
    }
    const fresh = await brainFetchText(
      `documents/${encodeURIComponent(id)}/content`,
    );
    const ours = lastSavedBodies.value.get(id);
    if (!options.force && ours != null && fresh === ours) {
      return;
    }
    activeMarkdown.value = fresh;
  } catch (e) {
    pageError.value = e instanceof Error ? e.message : 'Could not load page.';
    activeMarkdown.value = null;
  } finally {
    pageLoading.value = false;
  }
}

async function onEditorSave(body: string) {
  if (!activePageId.value) return;
  const id = activePageId.value;
  saveStatus.value = 'saving';
  lastSavedBodies.value.set(id, body);
  lastSelfWriteAt.value.set(id, Date.now());
  try {
    await brainSendRaw<unknown>(
      'PUT',
      `documents/${encodeURIComponent(id)}/content`,
      body,
      'text/markdown',
    );
    // Refresh the timestamp on successful save — the WS echo arrives
    // *after* the PUT response, not before, so the suppression window
    // is measured from response-time.
    lastSelfWriteAt.value.set(id, Date.now());
    // Only update status. DO NOT touch activeMarkdown — the editor
    // already holds the right content; rewriting it would trip the
    // `watch(() => props.source, …)` and rebuild the ProseMirror doc,
    // dropping the cursor.
    if (id === activePageId.value) {
      saveStatus.value = 'saved';
      lastSaveError.value = null;
    }
  } catch (e) {
    if (id === activePageId.value) {
      saveStatus.value = 'error';
      lastSaveError.value = e instanceof Error ? e.message : 'Save failed.';
    }
  }
}

/**
 * Upload a dropped/pasted image into `<workspace>/assets/`. Returns
 * the streaming-content URL so the editor can embed it as
 * `<img src>` immediately. Path collisions are avoided by
 * timestamp-prefix; the sanitised filename keeps URLs readable.
 */
async function uploadImage(file: File): Promise<string | null> {
  const assetsFolder = `${folder.value}/assets`;
  const ts = Date.now();
  const safe = file.name
    .toLowerCase()
    .replace(/[^a-z0-9._-]+/g, '_')
    .replace(/^_+|_+$/g, '');
  const path = `${assetsFolder}/${ts}-${safe || 'image'}`;

  const form = new FormData();
  form.append('file', file);
  form.append('projectId', projectId.value);
  form.append('path', path);
  if (file.type) form.append('mimeType', file.type);

  try {
    const dto = await brainFetch<{ id: string }>(
      'POST',
      'documents/upload',
      { body: form },
    );
    return documentContentUrl(dto.id);
  } catch (e) {
    console.error('[Workspace] image upload failed', e);
    return null;
  }
}

function onEditorDirty(dirty: boolean) {
  if (dirty) {
    saveStatus.value = 'dirty';
    // Start (or refresh) the self-write quiet window the moment the
    // user types — without this an auto-save round-trip is the only
    // thing keeping the editor immune to WS echoes, but a WS push that
    // arrives BEFORE the first save would still clobber the cursor.
    if (activePageId.value) {
      lastSelfWriteAt.value.set(activePageId.value, Date.now());
    }
  }
}

async function rebuild() {
  rebuilding.value = true;
  try {
    if (editorRef.value?.flush()) {
      await nextTick();
    }
    await rebuildWorkspace(projectId.value, folder.value);
    await loadWorkspace();
    // Rebuild may have regenerated _index.md or another page the user
    // is viewing — force the reload so the fresh server content lands
    // even if it byte-matches our last write.
    await loadActivePageContent({ force: true });
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
  onRemoteChange: async (paths) => {
    const activeId = activePageId.value;
    if (activeId != null && withinSelfWriteWindow(activeId)) {
      // Self-echo of our own write. Skip the tree reload too — even
      // though setContent on the editor is guarded by the quiet
      // window, re-running scanWorkspace replaces `view.value` and
      // forces a parent re-render that interacts badly with the
      // Tiptap editor lifecycle (focus / cursor get lost).
      return;
    }
    // Refresh tree first; then reload the active page only if it was
    // among the changed paths.
    await loadWorkspace();
    const v = view.value;
    if (!v || !activePageId.value) return;
    const active = v.pages.find((p) => p.id === activePageId.value);
    const activePath = active?.path
      ?? (activePageId.value === v.indexPageId ? v.indexPagePath : null)
      ?? (activePageId.value === v.landingPageId ? v.landingPagePath : null);
    if (activePath && paths.includes(activePath)) {
      await loadActivePageContent();
    }
  },
});

watch(folder, () => {
  activePageId.value = null;
  activePageView.value = null;
  activeMarkdown.value = null;
  void loadWorkspace();
});

onMounted(() => loadWorkspace());

const saveStatusLabel = computed<string | null>(() => {
  switch (saveStatus.value) {
    case 'dirty': return 'Edited';
    case 'saving': return 'Saving…';
    case 'saved': return 'Saved';
    case 'error': return lastSaveError.value ?? 'Save failed';
    default: return null;
  }
});

// Force the CanvasEditor to remount when the active page changes so the
// initial source is loaded cleanly (Tiptap doesn't track per-document
// state — it just owns one ProseMirror instance per mount).
const editorKey = computed(() => activePageId.value ?? 'empty');
</script>

<template>
  <div class="workspace-app">
    <aside class="workspace-app__sidebar">
      <header class="workspace-app__title">
        <div class="workspace-app__title-text">{{ title }}</div>
        <div class="workspace-app__title-actions">
          <button
            class="workspace-app__icon-btn"
            :disabled="creating"
            title="New page"
            @click="openNewPage"
          >+</button>
          <button
            class="workspace-app__icon-btn"
            :disabled="rebuilding"
            :title="rebuilding ? 'Rebuilding…' : 'Rebuild _index.md'"
            @click="rebuild"
          >
            {{ rebuilding ? '…' : '↻' }}
          </button>
        </div>
      </header>

      <form
        v-if="newPageOpen"
        class="workspace-app__new-page"
        @submit.prevent="submitNewPage"
      >
        <input
          ref="newPageTitleInputRef"
          v-model="newPageTitle"
          type="text"
          class="workspace-app__new-page-input"
          placeholder="Page title"
          :disabled="creating"
          @keydown.escape="closeNewPage"
        />
        <input
          v-model="newPageSection"
          type="text"
          class="workspace-app__new-page-input"
          placeholder="Section (optional)"
          list="workspace-sections"
          :disabled="creating"
        />
        <datalist id="workspace-sections">
          <option v-for="s in existingSections" :key="s" :value="s" />
        </datalist>
        <div v-if="newPageError" class="workspace-app__error">{{ newPageError }}</div>
        <div class="workspace-app__new-page-actions">
          <button
            type="submit"
            class="workspace-app__new-page-btn workspace-app__new-page-btn--primary"
            :disabled="creating || !newPageTitle.trim()"
          >
            {{ creating ? 'Creating…' : 'Create' }}
          </button>
          <button
            type="button"
            class="workspace-app__new-page-btn"
            :disabled="creating"
            @click="closeNewPage"
          >Cancel</button>
        </div>
      </form>

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

      <div
        v-if="view && view.pages.length === 0 && !view.indexPageId"
        class="workspace-app__empty"
      >
        Noch keine Pages.
      </div>
    </aside>

    <main class="workspace-app__main">
      <div v-if="loading" class="workspace-app__main-empty">Lade Workspace…</div>

      <template v-else-if="activePageId">
        <img
          v-if="pageCover"
          :src="pageCover"
          alt=""
          class="workspace-app__page-cover"
        />
        <header v-if="activePageView" class="workspace-app__page-header">
          <div class="workspace-app__page-header-row">
            <h1 class="workspace-app__page-title">
              <span v-if="pageIcon" class="workspace-app__page-icon">{{ pageIcon }}</span>
              {{ pageDisplayTitle }}
            </h1>
            <span
              v-if="saveStatusLabel"
              class="workspace-app__save-status"
              :class="`workspace-app__save-status--${saveStatus}`"
            >
              {{ saveStatusLabel }}
            </span>
          </div>
          <div v-if="activePageView.section" class="workspace-app__page-section">
            {{ activePageView.section }}
          </div>
          <p v-if="pageDisplayDescription" class="workspace-app__page-description">
            {{ pageDisplayDescription }}
          </p>
        </header>

        <div v-if="pageLoading" class="workspace-app__main-empty">Lade Page…</div>
        <div v-else-if="pageError" class="workspace-app__error">{{ pageError }}</div>
        <CanvasEditor
          v-else-if="activeMarkdown != null && activePageView"
          :key="editorKey"
          ref="editorRef"
          :document="{
            id: activePageId,
            path: activePageView.path,
            projectId,
            title: activePageView.title,
            inlineText: activeMarkdown,
            mimeType: 'text/markdown',
          }"
          :source="activeMarkdown"
          :upload-image="uploadImage"
          :open-asset-picker="openAssetPicker"
          @save="onEditorSave"
          @dirty="onEditorDirty"
        />
        <AssetPickerModal
          v-if="assetPickerOpen"
          :project-id="projectId"
          :workspace-folder="folder"
          :upload-image="uploadImage"
          @pick="onAssetPick"
          @close="assetPickerOpen = false"
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
.workspace-app__title-actions {
  display: flex;
  gap: 0.25rem;
}
.workspace-app__icon-btn {
  font-size: 0.95rem;
  width: 1.75rem;
  height: 1.75rem;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--color-border, #d1d5db);
  border-radius: 0.25rem;
  background: var(--color-button-bg, #fff);
  cursor: pointer;
  flex-shrink: 0;
  line-height: 1;
}
.workspace-app__icon-btn:hover:not(:disabled) {
  background: var(--color-button-hover-bg, #f3f4f6);
}
.workspace-app__icon-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.workspace-app__new-page {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
  margin: 0 0.25rem 0.5rem;
  padding: 0.5rem;
  background: var(--color-button-bg, #fff);
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 0.375rem;
}
.workspace-app__new-page-input {
  padding: 0.25rem 0.5rem;
  border: 1px solid var(--color-border, #d1d5db);
  border-radius: 0.25rem;
  font-size: 0.85rem;
  background: var(--color-bg, #fff);
}
.workspace-app__new-page-actions {
  display: flex;
  gap: 0.25rem;
  justify-content: flex-end;
}
.workspace-app__new-page-btn {
  padding: 0.25rem 0.625rem;
  border: 1px solid var(--color-border, #d1d5db);
  border-radius: 0.25rem;
  background: var(--color-button-bg, #fff);
  cursor: pointer;
  font-size: 0.85rem;
}
.workspace-app__new-page-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.workspace-app__new-page-btn--primary {
  background: var(--color-active-bg, #4f46e5);
  color: #fff;
  border-color: var(--color-active-bg, #4f46e5);
}
.workspace-app__new-page-btn--primary:hover:not(:disabled) {
  filter: brightness(0.95);
}
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
  min-height: 0;
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
.workspace-app__page-header-row {
  display: flex;
  align-items: baseline;
  gap: 1rem;
  flex-wrap: wrap;
}
.workspace-app__page-cover {
  display: block;
  width: 100%;
  max-height: 14rem;
  object-fit: cover;
  background: var(--color-button-bg, #f3f4f6);
}
.workspace-app__page-icon {
  font-size: 1.2em;
  line-height: 1;
  margin-right: 0.25em;
}
.workspace-app__page-title {
  font-size: 1.875rem;
  font-weight: 700;
  margin: 0 0 0.25em;
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 0.4em;
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
.workspace-app__save-status {
  font-size: 0.8rem;
  padding: 0.125rem 0.5rem;
  border-radius: 9999px;
  font-weight: 500;
}
.workspace-app__save-status--dirty {
  background: var(--color-button-bg, #fef3c7);
  color: #92400e;
}
.workspace-app__save-status--saving {
  background: var(--color-button-bg, #dbeafe);
  color: #1e40af;
}
.workspace-app__save-status--saved {
  background: var(--color-button-bg, #d1fae5);
  color: #065f46;
}
.workspace-app__save-status--error {
  background: #fee2e2;
  color: #991b1b;
}
</style>
