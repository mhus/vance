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
  VTextarea,
} from '@/components';
import { useDocuments } from '@/composables/useDocuments';
import { useTenantProjects } from '@/composables/useTenantProjects';
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
const editInlineText = ref('');
const editError = ref<string | null>(null);
const saving = ref(false);

// Create-modal state. The modal is its own form, kept independent from the
// edit form so cancelling the create doesn't disturb a half-open detail view.
type CreateMode = 'inline' | 'upload';

const showCreateModal = ref(false);
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

const createMimeOptions = [
  { value: 'text/markdown', label: 'Markdown (.md)' },
  { value: 'text/plain', label: 'Plain text (.txt)' },
  { value: 'application/json', label: 'JSON' },
  { value: 'application/yaml', label: 'YAML' },
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
    await docsState.loadPage(selectedProjectId.value, 0);
  }
  if (queryDoc) {
    await docsState.loadOne(queryDoc);
    fillEditor();
  }
});

watch(selectedProjectId, async (next) => {
  if (!next) return;
  syncQueryParam('projectId', next);
  syncQueryParam('documentId', null);
  docsState.clearSelection();
  await docsState.loadPage(next, 0);
});

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

async function openDocument(doc: DocumentSummary): Promise<void> {
  if (!doc.id) return;
  await docsState.loadOne(doc.id);
  fillEditor();
}

function fillEditor(): void {
  const sel = docsState.selected.value;
  editTitle.value = sel?.title ?? '';
  editInlineText.value = sel?.inlineText ?? '';
  editError.value = null;
}

function backToList(): void {
  docsState.clearSelection();
  editError.value = null;
}

function openCreateModal(): void {
  createMode.value = 'inline';
  createPath.value = '';
  createTitle.value = '';
  createTagsRaw.value = '';
  createMime.value = 'text/markdown';
  createContent.value = '';
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

async function save(): Promise<void> {
  const sel = docsState.selected.value;
  if (!sel?.id) return;
  saving.value = true;
  editError.value = null;
  try {
    const body: DocumentUpdateRequest = { title: editTitle.value };
    if (sel.inline) body.inlineText = editInlineText.value;
    await docsState.update(sel.id, body);
    if (docsState.error.value) {
      editError.value = docsState.error.value;
    }
  } finally {
    saving.value = false;
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
  <EditorShell title="Documents" :breadcrumbs="breadcrumbs">
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

          <VAlert v-if="!docsState.selected.value.inline" variant="warning" class="mt-3">
            <span>This document is stored out-of-band and is read-only in v1.</span>
          </VAlert>

          <VAlert v-if="editError" variant="error" class="mt-3">
            <span>{{ editError }}</span>
          </VAlert>

          <div class="flex flex-col gap-3 mt-3">
            <VInput v-model="editTitle" label="Title" :disabled="saving" />
            <VTextarea
              v-if="docsState.selected.value.inline"
              v-model="editInlineText"
              label="Content"
              :rows="20"
              :disabled="saving"
            />
          </div>

          <template #actions>
            <VButton variant="ghost" :disabled="saving" @click="backToList">Cancel</VButton>
            <VButton variant="primary" :loading="saving" @click="save">Save</VButton>
          </template>
        </VCard>
      </template>

      <!-- List view -->
      <template v-else>
        <div class="flex items-center justify-end mb-3">
          <VButton variant="primary" size="sm" @click="openCreateModal">+ New document</VButton>
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
            <VButton variant="primary" @click="openCreateModal">Create first document</VButton>
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
          <VTextarea
            v-model="createContent"
            label="Content"
            :rows="14"
            required
            :disabled="creating"
            help="Inline content, up to 4 KB. For larger or binary files use the upload tab."
          />
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
  </EditorShell>
</template>
