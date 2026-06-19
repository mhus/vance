<script setup lang="ts">
/**
 * Multi-project document Explorer — the new shape of documents.html.
 * Browses projects, folders and file metadata, but never renders a
 * document body: a click on a file row hands off to
 * {@code /notepad.html?project=X&doc=Y}, which mounts the shared
 * Cortex editor surface in notepad-mode (no chat).
 *
 * Editing (title, tags, body, versions, archives) lives in Notepad
 * now — keeping the Explorer focused on structure + bulk actions
 * lets the two views share the dispatch / properties / archives code
 * exactly once.
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  EditorShell,
  ProjectListSidebar,
  VAlert,
  VButton,
  VEmptyState,
  VInput,
  type FocusZone,
} from '@/components';
import { useI18n } from 'vue-i18n';
import { useTenantProjects } from '@composables/useTenantProjects';
import { useDocuments } from '@composables/useDocuments';
import DocumentIcon from './DocumentIcon.vue';

const { t } = useI18n();
const projectsState = useTenantProjects();
const PAGE_SIZE = 50;
const docsState = useDocuments(PAGE_SIZE);

const selectedProjectId = ref<string | null>(null);
const focusZone = ref<FocusZone>('main');
const search = ref('');

const DEFAULT_PATH_PREFIX = 'documents/';

const pendingDraft = ref(false);

onMounted(async () => {
  await projectsState.reload();
  const params = new URLSearchParams(window.location.search);
  const queryProject = params.get('projectId');
  const queryPath = params.get('path');
  pendingDraft.value = params.get('createDraft') === '1';
  if (queryProject && projectsState.projects.value.some((p) => p.name === queryProject)) {
    selectedProjectId.value = queryProject;
  } else if (projectsState.projects.value.length > 0) {
    selectedProjectId.value = projectsState.projects.value[0].name;
  }
  // When the Inbox handed off a draft and the URL pre-selected a
  // project, forward the user straight to Notepad. Otherwise we
  // wait for the user to pick a project from the sidebar.
  if (pendingDraft.value && selectedProjectId.value && queryProject) {
    forwardDraftToNotepad(selectedProjectId.value);
    return;
  }
  if (selectedProjectId.value) {
    docsState.pathPrefix.value = queryPath ?? DEFAULT_PATH_PREFIX;
    await docsState.loadPage(selectedProjectId.value, 0, docsState.pathPrefix.value);
  }
  window.addEventListener('popstate', onPopstate);
});

function forwardDraftToNotepad(projectId: string): void {
  const params = new URLSearchParams();
  params.set('project', projectId);
  params.set('create', '1');
  window.location.href = `/notepad.html?${params.toString()}`;
}

onBeforeUnmount(() => {
  window.removeEventListener('popstate', onPopstate);
});

// URL sync — project + path live in the address bar so browser
// back/forward step through the directory walk and refresh keeps
// position.
function syncUrl(): void {
  const params = new URLSearchParams();
  if (selectedProjectId.value) params.set('projectId', selectedProjectId.value);
  if (docsState.pathPrefix.value) params.set('path', docsState.pathPrefix.value);
  const next = `${window.location.pathname}?${params.toString()}`;
  if (next !== `${window.location.pathname}${window.location.search}`) {
    window.history.pushState({}, '', next);
  }
}

function onPopstate(): void {
  const params = new URLSearchParams(window.location.search);
  const queryProject = params.get('projectId');
  const queryPath = params.get('path') ?? '';
  if (queryProject && queryProject !== selectedProjectId.value) {
    selectedProjectId.value = queryProject;
  }
  if (queryPath !== docsState.pathPrefix.value && selectedProjectId.value) {
    void docsState.loadPage(selectedProjectId.value, 0, queryPath);
  }
}

watch(selectedProjectId, async (next, prev) => {
  if (!next) return;
  // Inbox draft handoff: as soon as the user picks a project, jump
  // to Notepad with create=1 — the modal there consumes the draft.
  if (pendingDraft.value) {
    forwardDraftToNotepad(next);
    return;
  }
  if (prev == null) return; // initial bind handled by onMounted
  docsState.pathPrefix.value = DEFAULT_PATH_PREFIX;
  search.value = '';
  await docsState.loadPage(next, 0, DEFAULT_PATH_PREFIX);
  syncUrl();
});

const breadcrumbSegments = computed<string[]>(() => {
  const prefix = docsState.pathPrefix.value.replace(/\/+$/, '');
  if (!prefix) return [];
  return prefix.split('/');
});

function navigateToSegment(idx: number): void {
  if (!selectedProjectId.value) return;
  const segs = breadcrumbSegments.value.slice(0, idx + 1);
  const newPath = segs.length > 0 ? `${segs.join('/')}/` : '';
  void docsState.loadPage(selectedProjectId.value, 0, newPath);
  syncUrl();
}

function navigateToRoot(): void {
  if (!selectedProjectId.value) return;
  void docsState.loadPage(selectedProjectId.value, 0, '');
  syncUrl();
}

function pathSegmentBack(): void {
  if (!selectedProjectId.value) return;
  const current = docsState.pathPrefix.value;
  if (!current) return;
  const trimmed = current.replace(/\/+$/, '');
  const idx = trimmed.lastIndexOf('/');
  const next = idx >= 0 ? `${trimmed.slice(0, idx)}/` : '';
  void docsState.loadPage(selectedProjectId.value, 0, next);
  syncUrl();
}

function navigateIntoFolder(folder: string): void {
  if (!selectedProjectId.value) return;
  const base = docsState.pathPrefix.value.replace(/\/+$/, '');
  const next = base ? `${base}/${folder}/` : `${folder}/`;
  void docsState.loadPage(selectedProjectId.value, 0, next);
  syncUrl();
}

function openInNotepad(docId: string): void {
  if (!selectedProjectId.value) return;
  const params = new URLSearchParams();
  params.set('project', selectedProjectId.value);
  params.set('doc', docId);
  window.location.href = `/notepad.html?${params.toString()}`;
}

function openCreateInNotepad(): void {
  if (!selectedProjectId.value) return;
  const params = new URLSearchParams();
  params.set('project', selectedProjectId.value);
  params.set('path', docsState.pathPrefix.value.replace(/\/+$/, ''));
  params.set('create', '1');
  window.location.href = `/notepad.html?${params.toString()}`;
}

// Server-side filter through the existing endpoint: re-load on every
// non-trivial change with a small debounce so typing doesn't flood
// the brain. Empty string clears the filter.
let searchTimer: ReturnType<typeof setTimeout> | null = null;
watch(search, (v) => {
  if (searchTimer) clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    if (!selectedProjectId.value) return;
    void docsState.loadPage(
      selectedProjectId.value,
      0,
      docsState.pathPrefix.value,
      undefined,
      v.trim(),
    );
  }, 250);
});

async function onProjectListDataChanged(): Promise<void> {
  await projectsState.reload();
}

function formatSize(bytes: number | null | undefined): string {
  if (bytes == null) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

function formatDate(ms: number | null | undefined): string {
  if (!ms) return '—';
  return new Date(ms).toLocaleDateString();
}

function fileBasename(path: string): string {
  const idx = path.lastIndexOf('/');
  return idx >= 0 ? path.slice(idx + 1) : path;
}

const isEmpty = computed(() =>
  docsState.subFolders.value.length === 0 && docsState.items.value.length === 0,
);

const totalPages = computed(() =>
  Math.max(1, Math.ceil(docsState.totalCount.value / docsState.pageSize.value)),
);

function gotoPage(p: number): void {
  if (!selectedProjectId.value) return;
  if (p < 0 || p >= totalPages.value) return;
  void docsState.loadPage(selectedProjectId.value, p, docsState.pathPrefix.value);
}
</script>

<template>
  <EditorShell
    v-model:focus-zone="focusZone"
    :title="$t('documents.title')"
    :full-height="true"
    focus-model="auto"
    :show-sidebar="true"
  >
    <template #sidebar>
      <div class="flex flex-col gap-2">
        <ProjectListSidebar
          v-model:selected-project="selectedProjectId"
          :groups="projectsState.groups.value"
          :projects="projectsState.projects.value"
          :loading="projectsState.loading.value"
          :error="projectsState.error.value"
          :heading="$t('documents.projectsTitle')"
          :filter-placeholder="$t('documents.projectFilterPlaceholder')"
          :ungrouped-label="$t('documents.ungrouped')"
          edit-enabled
          @focus-main="focusZone = 'main'"
          @data-changed="onProjectListDataChanged"
        >
          <template #loading>
            {{ $t('chat.picker.loading') }}
          </template>
          <template #filter-no-match="{ filter }">
            {{ $t('documents.projectFilterNoMatch', { filter }) }}
          </template>
        </ProjectListSidebar>
      </div>
    </template>

    <div v-if="!selectedProjectId" class="h-full flex items-center justify-center">
      <VEmptyState
        :headline="$t('documents.empty.noProjectHeadline')"
        :body="$t('documents.empty.noProjectBody')"
      />
    </div>

    <div v-else class="h-full min-h-0 flex flex-col">
      <!-- Path crumb + search + actions -->
      <div class="px-6 pt-4 pb-3 border-b border-base-300 bg-base-100 flex items-center gap-3 flex-wrap">
        <VButton
          variant="ghost"
          size="sm"
          :disabled="!docsState.pathPrefix.value"
          :title="$t('documents.pathBack')"
          @click="pathSegmentBack"
        >←</VButton>
        <nav class="flex items-center gap-1 text-sm font-mono opacity-80 flex-1 min-w-0 truncate">
          <button
            type="button"
            class="opacity-70 hover:opacity-100 hover:underline"
            @click="navigateToRoot"
          >/</button>
          <template v-for="(seg, idx) in breadcrumbSegments" :key="idx">
            <span class="opacity-40">/</span>
            <button
              type="button"
              class="opacity-70 hover:opacity-100 hover:underline"
              @click="navigateToSegment(idx)"
            >{{ seg }}</button>
          </template>
        </nav>
        <div class="w-[180px] shrink-0">
          <VInput
            v-model="search"
            :placeholder="$t('documents.searchPlaceholder')"
          />
        </div>
        <VButton
          variant="primary"
          size="sm"
          :title="$t('documents.newDocument')"
          @click="openCreateInNotepad"
        >+ Neu</VButton>
      </div>

      <VAlert v-if="docsState.error.value" variant="error" class="m-4">
        <span>{{ docsState.error.value }}</span>
      </VAlert>

      <div class="flex-1 min-h-0 overflow-y-auto">
        <div v-if="docsState.loading.value" class="p-6 text-sm opacity-60">
          {{ $t('documents.loading') }}
        </div>
        <div v-else-if="isEmpty" class="p-6">
          <VEmptyState
            :headline="$t('documents.empty.folderHeadline')"
            :body="$t('documents.empty.folderBody')"
          />
        </div>
        <table v-else class="w-full text-sm">
          <thead class="text-xs uppercase opacity-60 sticky top-0 bg-base-100 z-[1]">
            <tr>
              <th class="text-left px-4 py-2 w-8"></th>
              <th class="text-left px-2 py-2">Name</th>
              <th class="text-left px-2 py-2 w-24">Kind</th>
              <th class="text-left px-2 py-2 w-32">Tags</th>
              <th class="text-right px-2 py-2 w-20">Size</th>
              <th class="text-left px-2 py-2 w-28">Created</th>
              <th class="text-left px-4 py-2 w-32">By</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="folder in docsState.subFolders.value"
              :key="`f:${folder}`"
              class="border-b border-base-200 hover:bg-base-200/60 cursor-pointer"
              @click="navigateIntoFolder(folder)"
            >
              <td class="px-4 py-1.5">📁</td>
              <td class="px-2 py-1.5 font-medium">{{ folder }}</td>
              <td class="px-2 py-1.5 opacity-50">folder</td>
              <td class="px-2 py-1.5"></td>
              <td class="px-2 py-1.5"></td>
              <td class="px-2 py-1.5"></td>
              <td class="px-4 py-1.5"></td>
            </tr>
            <tr
              v-for="doc in docsState.items.value"
              :key="doc.id"
              class="border-b border-base-200 hover:bg-base-200/60 cursor-pointer"
              @click="openInNotepad(doc.id)"
            >
              <td class="px-4 py-1.5">
                <DocumentIcon :kind="doc.kind ?? null" :mime-type="doc.mimeType ?? null" />
              </td>
              <td class="px-2 py-1.5">
                <div class="font-medium truncate">{{ doc.title || fileBasename(doc.path) }}</div>
                <div class="text-xs opacity-60 font-mono truncate">{{ doc.path }}</div>
              </td>
              <td class="px-2 py-1.5 text-xs opacity-70">{{ doc.kind ?? '—' }}</td>
              <td class="px-2 py-1.5 text-xs opacity-70 truncate">
                {{ (doc.tags ?? []).join(', ') }}
              </td>
              <td class="px-2 py-1.5 text-right text-xs">{{ formatSize(doc.size) }}</td>
              <td class="px-2 py-1.5 text-xs">{{ formatDate(doc.createdAtMs) }}</td>
              <td class="px-4 py-1.5 text-xs opacity-70 truncate">{{ doc.createdBy ?? '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div
        v-if="totalPages > 1"
        class="border-t border-base-300 bg-base-100 px-4 py-2 flex items-center gap-2 text-sm"
      >
        <VButton
          variant="ghost"
          size="sm"
          :disabled="docsState.page.value === 0"
          @click="gotoPage(docsState.page.value - 1)"
        >←</VButton>
        <span class="opacity-70">
          {{ docsState.page.value + 1 }} / {{ totalPages }}
          <span class="opacity-50 ml-2">({{ docsState.totalCount.value }} {{ t('documents.totalItems') }})</span>
        </span>
        <VButton
          variant="ghost"
          size="sm"
          :disabled="docsState.page.value >= totalPages - 1"
          @click="gotoPage(docsState.page.value + 1)"
        >→</VButton>
      </div>
    </div>
  </EditorShell>
</template>
