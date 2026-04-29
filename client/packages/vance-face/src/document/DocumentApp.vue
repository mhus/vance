<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import {
  EditorShell,
  VAlert,
  VBackButton,
  VButton,
  VCard,
  VDataList,
  VEmptyState,
  VFileInput,
  VInput,
  VModal,
  VPagination,
  VSelect,
  CodeEditor,
  MarkdownView,
} from '@/components';
import { useDocuments } from '@/composables/useDocuments';
import { useHelp } from '@/composables/useHelp';
import { useTenantProjects } from '@/composables/useTenantProjects';
import { consumeDocumentDraft, documentContentUrl } from '@vance/shared';
import DocumentPreview from './DocumentPreview.vue';
import type {
  DocumentSummary,
  DocumentUpdateRequest,
  ProjectSummary,
} from '@vance/generated';

const PAGE_SIZE = 20;

const projectsState = useTenantProjects();
const docsState = useDocuments(PAGE_SIZE);

const selectedProjectId = ref<string | null>(null);

const editTitle = ref('');
const editPath = ref('');
const editInlineText = ref('');
const editError = ref<string | null>(null);
const saving = ref(false);

// Create-modal state. The modal is its own form, kept independent from the
// edit form so cancelling the create doesn't disturb a half-open detail view.
type CreateMode = 'inline' | 'upload';

const showCreateModal = ref(false);

// Delete-confirm modal — destructive action gets an explicit
// confirmation step. See specification/web-ui.md §7.7.1.
const showDeleteModal = ref(false);
const deleting = ref(false);
const createMode = ref<CreateMode>('inline');
const createPath = ref('');
const createTitle = ref('');
const createTagsRaw = ref('');
const createMime = ref('text/markdown');
const createContent = ref('');
const createFiles = ref<File[]>([]);
const createError = ref<string | null>(null);
const creating = ref(false);

// Per-file upload outcome shown beneath the picker. Only populated while a
// multi-upload is running and after it finishes — kept until the user closes
// or reopens the modal.
type UploadStatus = 'pending' | 'uploading' | 'ok' | 'error';
interface UploadProgressItem {
  fileName: string;
  status: UploadStatus;
  message?: string;
}
const uploadProgress = ref<UploadProgressItem[]>([]);

// Document-content mime-types the inline editor handles. The
// `group` field drives `<optgroup>`-style separation in VSelect
// (see VSelect interface — adjacent items with the same `group`
// land under one `<optgroup>`). Order roughly "most common first".
// CodeEditor picks the matching syntax-highlighting language from
// these mime-types — see CodeEditor.languageFor.
const createMimeOptions = [
  { value: 'text/markdown', label: 'Markdown (.md)', group: 'Doc & config' },
  { value: 'text/plain', label: 'Plain text (.txt)', group: 'Doc & config' },
  { value: 'application/json', label: 'JSON', group: 'Doc & config' },
  { value: 'application/yaml', label: 'YAML', group: 'Doc & config' },
  { value: 'application/xml', label: 'XML', group: 'Doc & config' },
  { value: 'application/javascript', label: 'JavaScript (.js)', group: 'Code' },
  { value: 'application/typescript', label: 'TypeScript (.ts)', group: 'Code' },
  { value: 'text/x-python', label: 'Python (.py)', group: 'Code' },
  { value: 'application/x-sh', label: 'Bash / Shell (.sh)', group: 'Code' },
  { value: 'text/x-r', label: 'R (.r)', group: 'Code' },
  { value: 'text/x-java-source', label: 'Java (.java)', group: 'Code' },
  { value: 'application/sql', label: 'SQL', group: 'Code' },
  { value: 'text/html', label: 'HTML', group: 'Web' },
  { value: 'text/css', label: 'CSS', group: 'Web' },
];

onMounted(async () => {
  await projectsState.reload();
  // Restore last selection from the URL, if any. URL is the source of truth
  // for deep-links — reload-friendly without extra storage keys.
  const params = new URLSearchParams(window.location.search);
  const queryProject = params.get('projectId');
  const queryDoc = params.get('documentId');
  if (queryProject && projectsState.projects.value.some((p) => p.name === queryProject)) {
    selectedProjectId.value = queryProject;
  } else if (projectsState.projects.value.length > 0) {
    selectedProjectId.value = projectsState.projects.value[0].name;
  }
  if (selectedProjectId.value) {
    await Promise.all([
      docsState.loadPage(selectedProjectId.value, 0),
      docsState.loadFolders(selectedProjectId.value),
    ]);
  }
  if (queryDoc) {
    await docsState.loadOne(queryDoc);
    fillEditor();
  }
  // One-shot draft handed over by another editor (Inbox "To
  // Document"). Read-and-clear via consumeDocumentDraft so a refresh
  // doesn't re-trigger the prefill. Requires a project to be selected
  // — without one, the draft is silently dropped (rare; the user
  // can re-trigger from the Inbox after picking a project).
  if (params.get('createDraft') === '1') {
    const draft = consumeDocumentDraft();
    // Strip the URL-flag so a refresh starts clean.
    syncQueryParam('createDraft', null);
    if (draft && selectedProjectId.value) {
      openCreateModal({
        title: draft.title,
        path: draft.path,
        content: draft.content,
        mimeType: draft.mimeType,
      });
    }
  }
});

watch(selectedProjectId, async (next) => {
  if (!next) return;
  syncQueryParam('projectId', next);
  syncQueryParam('documentId', null);
  docsState.clearSelection();
  // Reset filter on project switch — folder list belongs to the
  // new project and the previous prefix won't match anyway.
  docsState.pathPrefix.value = '';
  await Promise.all([
    docsState.loadPage(next, 0, ''),
    docsState.loadFolders(next),
  ]);
});

/**
 * Apply the path-filter input. Debounced via a small timeout so
 * typing into the combobox doesn't fire one request per keystroke;
 * pressing Enter or selecting a datalist option commits immediately.
 */
let filterTimer: ReturnType<typeof setTimeout> | null = null;
function applyPathFilter(prefix: string, immediate = false): void {
  const project = selectedProjectId.value;
  if (!project) return;
  if (filterTimer) clearTimeout(filterTimer);
  const fire = () => {
    void docsState.loadPage(project, 0, prefix);
  };
  if (immediate) fire();
  else filterTimer = setTimeout(fire, 300);
}

watch(
  () => docsState.selected.value?.id ?? null,
  (id) => {
    syncQueryParam('documentId', id);
  },
);

const projectOptions = computed<{ value: string; label: string; group?: string }[]>(() => {
  const groupNameById = new Map<string, string>();
  for (const g of projectsState.groups.value) {
    groupNameById.set(g.name, g.title?.trim() || g.name);
  }
  return projectsState.projects.value.map((p: ProjectSummary) => {
    const groupLabel = p.projectGroupId
      ? groupNameById.get(p.projectGroupId) ?? p.projectGroupId
      : 'Ungrouped';
    return {
      value: p.name,
      label: p.title?.trim() || p.name,
      group: groupLabel,
    };
  });
});

async function changePage(p: number): Promise<void> {
  if (!selectedProjectId.value) return;
  await docsState.loadPage(selectedProjectId.value, p);
}

// ─── Folder navigation (sidebar) ────────────────────────────────────────
//
// Top-level folders are shown in the left sidebar; clicking one filters
// the main file list to that prefix. The sidebar highlight tracks the
// pathPrefix bidirectionally — the path-input field and the sidebar
// stay in sync regardless of which one the user touched.

/** First-level folders only — `recipes` yes, `recipes/sub` no. */
const topLevelFolders = computed<string[]>(() =>
  docsState.folders.value.filter((f) => !f.includes('/')),
);

/**
 * Sidebar selection key derived from the current `pathPrefix`.
 * - `''` → "All" entry highlighted (no filter)
 * - `<folder>` → that top-level folder entry highlighted
 * - `null` → free-form prefix typed in the input, nothing highlighted
 */
const selectedFolderKey = computed<string | null>(() => {
  const p = docsState.pathPrefix.value.trim();
  if (!p) return '';
  if (p.endsWith('/')) {
    const stripped = p.slice(0, -1);
    return stripped.includes('/') ? null : stripped;
  }
  return null;
});

function selectFolder(folder: string | null): void {
  // null === "All" (clear filter), otherwise the folder name without
  // trailing slash. We always commit to pathPrefix with the slash so
  // the prefix-match on the server doesn't accidentally span sibling
  // folders that happen to share a prefix (e.g. "rec" matching
  // "recipes" and "records").
  applyPathFilter(folder == null ? '' : folder + '/', true);
}

async function openDocument(doc: DocumentSummary): Promise<void> {
  if (!doc.id) return;
  await docsState.loadOne(doc.id);
  fillEditor();
}

function fillEditor(): void {
  const sel = docsState.selected.value;
  editTitle.value = sel?.title ?? '';
  editPath.value = sel?.path ?? '';
  editInlineText.value = sel?.inlineText ?? '';
  editError.value = null;
}

// ─── Contextual help ────────────────────────────────────────────────────
//
// When the selected document sits under one of these path prefixes we
// load a matching field-reference Markdown into the right panel. Empty
// when no prefix matches — the right panel is then suppressed entirely.
//
// Add new mappings here; the `_vance` prefix is implicit in the project
// scope (the user editing a document in their `_vance` project sees
// `recipes/foo.yaml`, not `_vance/recipes/foo.yaml`).
const help = useHelp();

interface HelpRule {
  /** Path prefix relative to the project root, e.g. `recipes/`. */
  prefix: string;
  /** Help resource under `help/<lang>/`. */
  resource: string;
}

const HELP_RULES: HelpRule[] = [
  { prefix: 'recipes/', resource: 'recipe-field-docs.md' },
  { prefix: 'strategies/', resource: 'strategy-field-docs.md' },
];

const helpResource = computed<string | null>(() => {
  const path = docsState.selected.value?.path ?? '';
  if (!path) return null;
  const match = HELP_RULES.find((rule) => path.startsWith(rule.prefix));
  return match ? match.resource : null;
});

watch(
  helpResource,
  (resource) => {
    if (resource) {
      help.load(resource);
    } else {
      help.content.value = null;
      help.error.value = null;
    }
  },
  { immediate: true },
);

function backToList(): void {
  docsState.clearSelection();
  editError.value = null;
}

/**
 * Build a `Content-Disposition: attachment` URL for a document.
 * The {@code documentContentUrl} helper appends the JWT as
 * `?token=…` so an `<a download>` link works without a header.
 */
function downloadUrl(doc: { id: string }): string {
  return documentContentUrl(doc.id, true);
}

interface CreateModalPrefill {
  title?: string;
  path?: string;
  content?: string;
  mimeType?: string;
}

function openCreateModal(prefill?: CreateModalPrefill): void {
  createMode.value = 'inline';
  createPath.value = prefill?.path ?? '';
  createTitle.value = prefill?.title ?? '';
  createTagsRaw.value = '';
  createMime.value = prefill?.mimeType ?? 'text/markdown';
  createContent.value = prefill?.content ?? '';
  createFiles.value = [];
  createError.value = null;
  uploadProgress.value = [];
  showCreateModal.value = true;
}

function setCreateMode(mode: CreateMode): void {
  createMode.value = mode;
  createError.value = null;
  uploadProgress.value = [];
}

// Auto-fill the optional path override when exactly one file is picked and
// the user hasn't typed anything. With multiple files, path-override would
// have to apply per-file (it doesn't), so we leave it blank.
watch(createFiles, (files) => {
  if (files.length === 1 && !createPath.value.trim()) {
    createPath.value = files[0].name;
  }
});

async function submitCreate(): Promise<void> {
  if (!selectedProjectId.value) return;
  creating.value = true;
  createError.value = null;
  try {
    const tags = createTagsRaw.value
      .split(',')
      .map((t) => t.trim())
      .filter((t) => t.length > 0);

    if (createMode.value === 'inline') {
      if (!createPath.value.trim()) { createError.value = 'Path is required.'; return; }
      if (!createContent.value) { createError.value = 'Content is required.'; return; }
      const created = await docsState.create(selectedProjectId.value, {
        path: createPath.value.trim(),
        title: createTitle.value.trim() || undefined,
        tags: tags.length > 0 ? tags : undefined,
        mimeType: createMime.value,
        inlineText: createContent.value,
      });
      if (created) {
        showCreateModal.value = false;
        await docsState.loadOne(created.id);
        fillEditor();
      } else if (docsState.error.value) {
        createError.value = docsState.error.value;
      }
      return;
    }

    // Upload mode — one or many files.
    const files = createFiles.value;
    if (files.length === 0) { createError.value = 'Pick at least one file.'; return; }

    if (files.length === 1) {
      const created = await docsState.upload(selectedProjectId.value, {
        file: files[0],
        path: createPath.value.trim() || undefined,
        title: createTitle.value.trim() || undefined,
        tags: tags.length > 0 ? tags : undefined,
      });
      if (created) {
        showCreateModal.value = false;
        await docsState.loadOne(created.id);
        fillEditor();
      } else if (docsState.error.value) {
        createError.value = docsState.error.value;
      }
      return;
    }

    // Multi-upload: sequential — keeps server load predictable and lets the
    // user see per-file progress. Each file gets its own slot in
    // `uploadProgress`; failures don't abort the rest.
    uploadProgress.value = files.map((f) => ({
      fileName: f.name,
      status: 'pending' as UploadStatus,
    }));

    let okCount = 0;
    for (let i = 0; i < files.length; i++) {
      uploadProgress.value[i].status = 'uploading';
      const created = await docsState.upload(selectedProjectId.value, {
        file: files[i],
        tags: tags.length > 0 ? tags : undefined,
      });
      if (created) {
        uploadProgress.value[i].status = 'ok';
        okCount++;
      } else {
        uploadProgress.value[i].status = 'error';
        uploadProgress.value[i].message = docsState.error.value ?? 'Upload failed.';
      }
    }

    if (okCount === files.length) {
      // All good — close modal and refresh the list.
      showCreateModal.value = false;
    } else {
      createError.value = `${files.length - okCount} of ${files.length} files failed. See list below.`;
    }
  } finally {
    creating.value = false;
  }
}

/**
 * Persist current edits without leaving the detail view. Conventional
 * "Apply" semantic — see specification/web-ui.md §7.7.
 *
 * @returns `true` when the update succeeded with no error,
 *          `false` if the server rejected (e.g. path conflict). The
 *          caller can chain this for save-and-close behaviour.
 */
async function apply(): Promise<boolean> {
  const sel = docsState.selected.value;
  if (!sel?.id) return false;
  saving.value = true;
  editError.value = null;
  try {
    const body: DocumentUpdateRequest = { title: editTitle.value };
    if (sel.inline) body.inlineText = editInlineText.value;
    // Path-change (move/rename) — only send when actually changed.
    // Server-side normalisation makes minor whitespace/leading-slash
    // diffs idempotent, so we compare verbatim and let the server
    // be the source of truth.
    const newPath = editPath.value.trim();
    if (newPath && newPath !== sel.path) {
      body.newPath = newPath;
    }
    await docsState.update(sel.id, body);
    if (docsState.error.value) {
      editError.value = docsState.error.value;
      return false;
    }
    if (body.newPath && docsState.selected.value) {
      // Server normalised the path; reflect that back into the
      // editor so the field shows the canonical form.
      editPath.value = docsState.selected.value.path;
    }
    return true;
  } finally {
    saving.value = false;
  }
}

/**
 * Save-and-close — applies the edits and returns to the list when
 * the server accepted them. On error, stays on the detail view so
 * the user can inspect the message and retry. See
 * specification/web-ui.md §7.7.
 */
async function save(): Promise<void> {
  const ok = await apply();
  if (ok) backToList();
}

/**
 * Open the delete-confirmation modal. Actual deletion runs through
 * {@link confirmDelete} after the user confirms.
 */
function openDeleteModal(): void {
  editError.value = null;
  showDeleteModal.value = true;
}

/**
 * User confirmed — call the API and, on success, close the modal,
 * leave the detail view, and refresh the folder list (a deleted
 * document may have been the last in its folder).
 */
async function confirmDelete(): Promise<void> {
  const sel = docsState.selected.value;
  if (!sel?.id) return;
  deleting.value = true;
  try {
    const ok = await docsState.remove(sel.id);
    if (!ok) {
      editError.value = docsState.error.value;
      return;
    }
    showDeleteModal.value = false;
    backToList();
    if (selectedProjectId.value) {
      // Folder list cheap to reload — keeps the path-filter
      // dropdown honest after an empty folder disappears.
      void docsState.loadFolders(selectedProjectId.value);
    }
  } finally {
    deleting.value = false;
  }
}

function syncQueryParam(key: string, value: string | null): void {
  const url = new URL(window.location.href);
  if (value === null) {
    url.searchParams.delete(key);
  } else {
    url.searchParams.set(key, value);
  }
  window.history.replaceState(null, '', url.toString());
}

const breadcrumbs = computed<string[]>(() => {
  const crumbs: string[] = ['Documents'];
  if (selectedProjectId.value) crumbs.push(selectedProjectId.value);
  if (docsState.selected.value) crumbs.push(docsState.selected.value.path);
  return crumbs;
});

const formatBytes = (n: number): string => {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(2)} MB`;
};
</script>

<template>
  <EditorShell
    title="Documents"
    :breadcrumbs="breadcrumbs"
    :wide-right-panel="!!helpResource"
  >
    <template #topbar-extra>
      <div class="w-64">
        <VSelect
          v-model="selectedProjectId"
          :options="projectOptions"
          placeholder="Select a project"
          :disabled="projectsState.loading.value || projectOptions.length === 0"
        />
      </div>
    </template>

    <!-- ─── Folder navigation ──────────────────────────────────────────
         Top-level folders only; clicking one applies the path-prefix
         filter to the main list. The sidebar is hidden until a project
         is picked (matches the empty-state in the main panel). ─── -->
    <template v-if="selectedProjectId" #sidebar>
      <nav class="p-3 flex flex-col gap-1">
        <h3 class="text-xs uppercase opacity-60 mb-2 px-2">Folders</h3>
        <button
          type="button"
          class="folder-item"
          :class="{ 'folder-item--active': selectedFolderKey === '' }"
          @click="selectFolder(null)"
        >
          <span>All</span>
          <span class="folder-count">{{ docsState.totalCount.value }}</span>
        </button>
        <button
          v-for="folder in topLevelFolders"
          :key="folder"
          type="button"
          class="folder-item"
          :class="{ 'folder-item--active': selectedFolderKey === folder }"
          @click="selectFolder(folder)"
        >
          <span>{{ folder }}/</span>
        </button>
        <p
          v-if="topLevelFolders.length === 0"
          class="text-xs opacity-60 italic mt-2 px-2"
        >
          No folders yet — create a document with a path like
          <code>notes/foo.md</code> to nest it.
        </p>
      </nav>
    </template>

    <div class="container mx-auto px-4 py-6 max-w-5xl">
      <VAlert v-if="projectsState.error.value" variant="error" class="mb-4">
        <span>{{ projectsState.error.value }}</span>
      </VAlert>

      <VEmptyState
        v-if="!projectsState.loading.value && projectOptions.length === 0"
        headline="No projects in this tenant"
        body="Ask an administrator to create a project before you can browse documents."
      />

      <VEmptyState
        v-else-if="!selectedProjectId"
        headline="Pick a project"
        body="Choose a project from the dropdown above to load its documents."
      />

      <!-- Detail / edit view -->
      <template v-else-if="docsState.selected.value">
        <div class="mb-4">
          <VBackButton label="Back to list" @click="backToList" />
        </div>

        <VCard>
          <template #header>
            <span class="font-mono text-sm">{{ docsState.selected.value.path }}</span>
          </template>

          <div class="text-xs opacity-60 flex flex-wrap gap-3">
            <span>{{ formatBytes(docsState.selected.value.size) }}</span>
            <span v-if="docsState.selected.value.mimeType">
              {{ docsState.selected.value.mimeType }}
            </span>
            <span v-if="docsState.selected.value.createdBy">
              by {{ docsState.selected.value.createdBy }}
            </span>
          </div>

          <VAlert v-if="!docsState.selected.value.inline" variant="info" class="mt-3">
            <span>Stored content — read-only in v1. Use the download button to save a local copy.</span>
          </VAlert>

          <VAlert v-if="editError" variant="error" class="mt-3">
            <span>{{ editError }}</span>
          </VAlert>

          <div class="flex flex-col gap-3 mt-3">
            <VInput v-model="editTitle" label="Title" :disabled="saving" />
            <VInput
              v-model="editPath"
              label="Path"
              :disabled="saving"
              help="Move or rename this document. Path is unique within the project; conflicts are rejected."
            />
            <CodeEditor
              v-if="docsState.selected.value.inline"
              v-model="editInlineText"
              label="Content"
              :rows="20"
              :disabled="saving"
              :mime-type="docsState.selected.value.mimeType"
            />
            <DocumentPreview
              v-else
              :document-id="docsState.selected.value.id"
              :mime-type="docsState.selected.value.mimeType"
              :inline="false"
            />
          </div>

          <template #actions>
            <!-- Destructive action separated to the left edge.
                 `mr-auto` overrides the `justify-end` inherited from
                 .card-actions and pushes Delete fully left, the rest
                 stay clustered right. -->
            <VButton
              class="mr-auto"
              variant="danger"
              :disabled="saving || deleting"
              @click="openDeleteModal"
            >Delete</VButton>
            <VButton
              variant="ghost"
              :href="downloadUrl(docsState.selected.value)"
              :download="docsState.selected.value.name || 'document'"
            >Download</VButton>
            <VButton variant="ghost" :disabled="saving" @click="backToList">Cancel</VButton>
            <VButton variant="secondary" :loading="saving" @click="apply">Apply</VButton>
            <VButton variant="primary" :loading="saving" @click="save">Save</VButton>
          </template>
        </VCard>
      </template>

      <!-- List view -->
      <template v-else>
        <!-- Path filter — HTML5 combobox: free-text input plus a
             folder dropdown derived from server-side projection. -->
        <div class="flex items-center gap-3 mb-3">
          <div class="flex-1 min-w-0">
            <input
              v-model="docsState.pathPrefix.value"
              type="text"
              placeholder="Filter by folder or path prefix… (e.g. notes/, archive/2026)"
              list="folder-list"
              class="input input-bordered input-sm w-full"
              @input="applyPathFilter(docsState.pathPrefix.value)"
              @change="applyPathFilter(docsState.pathPrefix.value, true)"
              @keydown.enter.prevent="applyPathFilter(docsState.pathPrefix.value, true)"
            />
            <datalist id="folder-list">
              <option
                v-for="folder in docsState.folders.value"
                :key="folder"
                :value="folder"
              />
            </datalist>
          </div>
          <button
            v-if="docsState.pathPrefix.value"
            type="button"
            class="btn btn-ghost btn-sm"
            @click="applyPathFilter('', true)"
          >Clear filter</button>
          <VButton variant="primary" size="sm" @click="openCreateModal()">+ New document</VButton>
        </div>

        <VAlert v-if="docsState.error.value" variant="error" class="mb-4">
          <span>{{ docsState.error.value }}</span>
        </VAlert>

        <VEmptyState
          v-if="!docsState.loading.value && docsState.items.value.length === 0"
          headline="No documents"
          body="This project has no documents yet."
        >
          <template #action>
            <VButton variant="primary" @click="openCreateModal()">Create first document</VButton>
          </template>
        </VEmptyState>

        <VDataList
          v-else
          :items="docsState.items.value"
          selectable
          @select="openDocument"
        >
          <template #default="{ item }">
            <div class="flex items-center justify-between gap-4">
              <div class="min-w-0 flex-1">
                <div class="font-semibold truncate">
                  {{ item.title?.trim() || item.name }}
                </div>
                <div class="text-xs opacity-60 truncate font-mono">{{ item.path }}</div>
                <div v-if="item.tags && item.tags.length" class="mt-1 flex gap-1 flex-wrap">
                  <span
                    v-for="tag in item.tags"
                    :key="tag"
                    class="badge badge-ghost badge-sm"
                  >{{ tag }}</span>
                </div>
              </div>
              <div class="text-right text-xs opacity-60 shrink-0">
                <div>{{ formatBytes(item.size) }}</div>
                <div v-if="!item.inline" class="text-warning">stored</div>
              </div>
            </div>
          </template>
        </VDataList>

        <div v-if="docsState.totalCount.value > 0" class="mt-4">
          <VPagination
            :page="docsState.page.value"
            :page-size="docsState.pageSize.value"
            :total-count="docsState.totalCount.value"
            @update:page="changePage"
          />
        </div>
      </template>
    </div>

    <!-- Delete confirmation. Single-shot Cancel | Delete pattern —
         see specification/web-ui.md §7.7.1 (destructive actions). -->
    <VModal
      v-model="showDeleteModal"
      title="Delete document"
      :close-on-backdrop="!deleting"
    >
      <p>
        Delete
        <span class="font-mono">{{ docsState.selected.value?.path }}</span>?
        This removes the document and its stored content. The action
        cannot be undone.
      </p>
      <template #actions>
        <VButton
          variant="ghost"
          :disabled="deleting"
          @click="showDeleteModal = false"
        >Cancel</VButton>
        <VButton variant="danger" :loading="deleting" @click="confirmDelete">Delete</VButton>
      </template>
    </VModal>

    <!-- Create modal: lives outside the list/detail branches so it stays
         mounted across view switches and its open-state is independent. -->
    <VModal
      v-model="showCreateModal"
      title="New document"
      :close-on-backdrop="false"
    >
      <div class="flex gap-2 mb-4">
        <VButton
          :variant="createMode === 'inline' ? 'primary' : 'ghost'"
          size="sm"
          :disabled="creating"
          @click="setCreateMode('inline')"
        >Type content</VButton>
        <VButton
          :variant="createMode === 'upload' ? 'primary' : 'ghost'"
          size="sm"
          :disabled="creating"
          @click="setCreateMode('upload')"
        >Upload file</VButton>
      </div>

      <form class="flex flex-col gap-3" @submit.prevent="submitCreate">
        <VAlert v-if="createError" variant="error">
          <span>{{ createError }}</span>
        </VAlert>

        <template v-if="createMode === 'inline'">
          <VInput
            v-model="createPath"
            label="Path"
            placeholder="notes/example.md"
            required
            :disabled="creating"
            help="Virtual path inside the project. Must be unique."
          />
          <VInput
            v-model="createTitle"
            label="Title"
            placeholder="Optional display title"
            :disabled="creating"
          />
          <VInput
            v-model="createTagsRaw"
            label="Tags"
            placeholder="comma, separated, tags"
            :disabled="creating"
            help="Optional, separated by commas."
          />
          <VSelect
            v-model="createMime"
            :options="createMimeOptions"
            label="Type"
            :disabled="creating"
          />
          <CodeEditor
            v-model="createContent"
            label="Content"
            :rows="14"
            :disabled="creating"
            :mime-type="createMime"
          />
          <p class="text-xs opacity-70 -mt-1">
            Inline content, up to 4 KB. For larger or binary files use the upload tab.
          </p>
        </template>

        <template v-else>
          <VFileInput
            v-model="createFiles"
            label="Files"
            multiple
            :disabled="creating"
            help="Drop one or more files. Server picks inline vs. storage automatically per file."
          />

          <!-- Path and title only make sense for a single file — they would
               apply ambiguously to a batch. With multiple files, each file's
               name becomes its path and the server-side `createdBy` is set
               from the JWT. -->
          <template v-if="createFiles.length <= 1">
            <VInput
              v-model="createPath"
              label="Path"
              placeholder="(defaults to file name)"
              :disabled="creating"
              help="Override the destination path inside the project. Optional."
            />
            <VInput
              v-model="createTitle"
              label="Title"
              placeholder="Optional display title"
              :disabled="creating"
            />
          </template>

          <VInput
            v-model="createTagsRaw"
            label="Tags"
            placeholder="comma, separated, tags"
            :disabled="creating"
            :help="createFiles.length > 1
              ? 'Applied to every uploaded file.'
              : 'Optional, separated by commas.'"
          />

          <!-- Per-file progress — only populated during/after a multi-upload. -->
          <ul
            v-if="uploadProgress.length > 0"
            class="flex flex-col gap-1.5 text-sm border border-base-300 rounded-md p-3 bg-base-200"
          >
            <li
              v-for="item in uploadProgress"
              :key="item.fileName"
              class="flex items-center gap-2"
            >
              <span class="font-mono w-4 text-center" aria-hidden="true">
                <template v-if="item.status === 'pending'">·</template>
                <template v-else-if="item.status === 'uploading'">…</template>
                <template v-else-if="item.status === 'ok'">✓</template>
                <template v-else>✕</template>
              </span>
              <span class="font-mono text-xs truncate flex-1">{{ item.fileName }}</span>
              <span
                v-if="item.message"
                class="text-xs text-error truncate"
                :title="item.message"
              >{{ item.message }}</span>
            </li>
          </ul>
        </template>
      </form>

      <template #actions>
        <VButton
          variant="ghost"
          :disabled="creating"
          @click="showCreateModal = false"
        >Cancel</VButton>
        <VButton
          variant="primary"
          :loading="creating"
          @click="submitCreate"
        >{{ createMode === 'upload' ? 'Upload' : 'Create' }}</VButton>
      </template>
    </VModal>

    <!-- ─── Contextual help — shown only when the selected document
         lives under a known path prefix (e.g. recipes/, strategies/).
         Vue 3 renders the slot only when the v-if passes, so the
         right aside disappears completely when no help applies. ─── -->
    <template v-if="helpResource" #right-panel>
      <div class="p-4 flex flex-col gap-4">
        <h3 class="text-xs uppercase opacity-60 mb-2">Field reference</h3>
        <div v-if="help.loading.value" class="text-xs opacity-60">
          Loading…
        </div>
        <div v-else-if="help.error.value" class="text-xs opacity-60">
          Help unavailable: {{ help.error.value }}
        </div>
        <div v-else-if="!help.content.value" class="text-xs opacity-60">
          No help content for this resource.
        </div>
        <MarkdownView v-else :source="help.content.value" />
      </div>
    </template>
  </EditorShell>
</template>

<style scoped>
.folder-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  padding: 0.4rem 0.6rem;
  border-radius: 0.375rem;
  font-size: 0.875rem;
  text-align: left;
  color: inherit;
  background: transparent;
  border: 1px solid transparent;
  cursor: pointer;
  transition: background 0.1s;
}
.folder-item:hover {
  background: rgba(127, 127, 127, 0.08);
}
.folder-item--active {
  background: rgba(127, 127, 127, 0.14);
  font-weight: 600;
}
.folder-count {
  font-size: 0.7rem;
  opacity: 0.6;
}
</style>
