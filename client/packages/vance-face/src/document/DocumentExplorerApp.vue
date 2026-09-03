<script setup lang="ts">
/**
 * Multi-project document Explorer — the new shape of /documents.
 * Browses projects, folders and file metadata, but never renders a
 * document body: a click on a file row hands off to
 * {@code /cortex?project=X&doc=Y}, which mounts the unified
 * Cortex editor surface (chatless when no sessionId is given).
 *
 * Editing (title, tags, body, versions, archives) lives in Cortex
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
  VCheckbox,
  VEmptyState,
  VInput,
  VModal,
  VSelect,
  RowActionsMenu,
  accentColorDotClass,
  type FocusZone,
  type RowMenuItem,
} from '@/components';
import { useI18n } from 'vue-i18n';
import { useTenantProjects } from '@composables/useTenantProjects';
import { useDocuments } from '@composables/useDocuments';
import { useKitAdmin } from '@composables/useKitAdmin';
import { brainFetch } from '@vance/shared';
import type { DocumentFoldersResponse, DocumentSummary, MountDto } from '@vance/generated';
import { MountSearchOutcome } from '@vance/generated';
import DocumentIcon from './DocumentIcon.vue';
import { navigateTo, pushUrl } from '@/platform/navigate';
import { recallProject, rememberProject } from '@/platform/lastProject';

const { t } = useI18n();
const projectsState = useTenantProjects();
const PAGE_SIZE = 50;
const docsState = useDocuments(PAGE_SIZE);

const selectedProjectId = ref<string | null>(null);
const focusZone = ref<FocusZone>('main');
const search = ref('');

// Root of the project document tree — the explorer opens at '/' unless
// the URL carries an explicit ?path=.
const DEFAULT_PATH_PREFIX = '';

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
    // No project in the URL: the one this tab last worked in, and only
    // then the alphabetically first — which is a project nobody chose.
    selectedProjectId.value = recallProject(projectsState.projects.value.map((p) => p.name))
      ?? projectsState.projects.value[0].name;
  }
  rememberProject(selectedProjectId.value);
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
    // The project-wide folder list is NOT loaded here. Its only consumers are
    // the move/copy dialogs' target suggestions, and it costs a distinct-scan
    // over every document path in the project — with a mount, a quantity with
    // no upper bound. Loaded when one of those dialogs opens instead.
    // Loaded alongside the listing so an empty mounted folder can explain
    // itself; failure is silent and only costs the explanation.
    void docsState.loadMounts(selectedProjectId.value);
    void loadKits();
  }
  window.addEventListener('popstate', onPopstate);
});

function forwardDraftToNotepad(projectId: string): void {
  const params = new URLSearchParams();
  params.set('project', projectId);
  params.set('create', '1');
  navigateTo(`/cortex?${params.toString()}`);
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
    pushUrl(next);
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
  rememberProject(next);
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
  // Folder list intentionally not refetched — see onMounted. It belongs to the
  // previous project now, so drop it and let the next dialog fetch it.
  docsState.folders.value = [];
  void docsState.loadMounts(next);
  kitState.clear();
  void loadKits();
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
  navigateTo(`/cortex?${params.toString()}`);
}

function openCreateInNotepad(): void {
  if (!selectedProjectId.value) return;
  const params = new URLSearchParams();
  params.set('project', selectedProjectId.value);
  params.set('path', docsState.pathPrefix.value.replace(/\/+$/, ''));
  params.set('create', '1');
  navigateTo(`/cortex?${params.toString()}`);
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

/**
 * The mount the current folder belongs to, or null outside `_ext/`.
 *
 * Everything below hangs off this: a mounted folder can be empty for reasons
 * the file list cannot express — the source is unreachable, or a search was
 * never handed to it — and "not found" must not look like "not looked for".
 */
const currentMount = computed(() => {
  const path = docsState.pathPrefix.value ?? '';
  if (!path.startsWith('_ext/')) return null;
  const name = path.slice('_ext/'.length).split('/')[0];
  if (!name) return null;
  return docsState.mounts.value.find((m) => m.name === name) ?? { name } as MountDto;
});

/** Set when the source was asked and answered — the hits are mount-wide. */
const mountSearchDelegated = computed(
  () => docsState.mountSearch.value === MountSearchOutcome.DELEGATED
    && docsState.items.value.length > 0,
);

/**
 * Why this mounted folder is empty, as a headline/body pair — or null when
 * there is nothing special to say and the generic empty state is right.
 */
const mountEmptyReason = computed(() => {
  const mount = currentMount.value;
  if (!mount || !isEmpty.value) return null;
  const name = mount.displayName || mount.name;
  const outcome = docsState.mountSearch.value;
  // First, because it is folder-scoped: a source can answer perfectly and this
  // one folder still be unreadable (above the entry limit, or its last refresh
  // failed). The mount-wide status below would then say nothing is wrong.
  if (docsState.mountFailure.value) {
    return {
      headline: t('documents.empty.mountFolderProblemHeadline'),
      body: t('documents.empty.mountFolderProblemBody', {
        mount: name,
        reason: docsState.mountFailure.value,
      }),
      retryable: true,
    };
  }
  if (outcome === MountSearchOutcome.UNSUPPORTED) {
    // Not retryable: the source will still not be searchable next time.
    return {
      headline: t('documents.empty.searchUnsupportedHeadline'),
      body: t('documents.empty.searchUnsupportedBody', { mount: name }),
      retryable: false,
    };
  }
  if (outcome === MountSearchOutcome.UNAVAILABLE) {
    return {
      headline: t('documents.empty.searchUnavailableHeadline'),
      body: t('documents.empty.searchUnavailableBody', { mount: name }),
      retryable: true,
    };
  }
  if (mount.statusText) {
    return {
      headline: t('documents.empty.mountUnreachableHeadline'),
      body: t('documents.empty.mountUnreachableBody', { mount: name, status: mount.statusText }),
      retryable: true,
    };
  }
  // Reachable and genuinely empty — still worth naming the source, so it is
  // clear the emptiness is theirs and not ours. Nothing to retry.
  return {
    headline: t('documents.empty.folderHeadline'),
    body: t('documents.empty.mountEmptyBody', { mount: name }),
    retryable: false,
  };
});

/**
 * Drop the server-side mount caches and load this folder again.
 *
 * The instance cache holds for five minutes, so a just-corrected setting or a
 * source that has come back would otherwise stay invisible for that long —
 * indistinguishable, from here, from a configuration that is simply wrong.
 */
async function retryMount(): Promise<void> {
  if (!selectedProjectId.value) return;
  await docsState.loadMounts(selectedProjectId.value, /* refresh */ true);
  await docsState.loadPage(
    selectedProjectId.value, 0, docsState.pathPrefix.value, undefined, search.value.trim(),
  );
}

const totalPages = computed(() =>
  Math.max(1, Math.ceil(docsState.totalCount.value / docsState.pageSize.value)),
);

function gotoPage(p: number): void {
  if (!selectedProjectId.value) return;
  if (p < 0 || p >= totalPages.value) return;
  void docsState.loadPage(selectedProjectId.value, p, docsState.pathPrefix.value);
}

// ── Multi-select ────────────────────────────────────────────────
// Selection is scoped to the currently loaded page/folder: any
// reload (folder walk, paging, project switch, search) replaces
// docsState.items with a fresh array, so we clear both sets then.
//
// Documents are tracked by id. Folders have no id (they are virtual
// path prefixes), so they are tracked by their full prefix. All bulk
// actions expand folders server-side (export directly, move/trash via
// the chunk loop).
const selectedIds = ref<Set<string>>(new Set());
const selectedFolders = ref<Set<string>>(new Set());

watch(
  () => docsState.items.value,
  () => {
    selectedIds.value = new Set();
    selectedFolders.value = new Set();
    notice.value = '';
  },
);

function isSelected(id: string): boolean {
  return selectedIds.value.has(id);
}

function toggleDoc(id: string): void {
  const next = new Set(selectedIds.value);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  selectedIds.value = next;
}

/** Full path prefix of a visible subfolder (matches navigateIntoFolder). */
function folderPrefixOf(folder: string): string {
  const base = docsState.pathPrefix.value.replace(/\/+$/, '');
  return base ? `${base}/${folder}/` : `${folder}/`;
}

function isFolderSelected(folder: string): boolean {
  return selectedFolders.value.has(folderPrefixOf(folder));
}

function toggleFolder(folder: string): void {
  const key = folderPrefixOf(folder);
  const next = new Set(selectedFolders.value);
  if (next.has(key)) next.delete(key);
  else next.add(key);
  selectedFolders.value = next;
}

const allSelected = computed<boolean>(() => {
  const docs = docsState.items.value;
  const folders = docsState.subFolders.value;
  if (docs.length === 0 && folders.length === 0) return false;
  return docs.every((d) => selectedIds.value.has(d.id))
    && folders.every((f) => selectedFolders.value.has(folderPrefixOf(f)));
});

const someSelected = computed<boolean>(
  () => selectedIds.value.size + selectedFolders.value.size > 0 && !allSelected.value,
);

function toggleAll(): void {
  if (allSelected.value) {
    selectedIds.value = new Set();
    selectedFolders.value = new Set();
  } else {
    selectedIds.value = new Set(docsState.items.value.map((d) => d.id));
    selectedFolders.value = new Set(docsState.subFolders.value.map((f) => folderPrefixOf(f)));
  }
}

const selectedCount = computed<number>(
  () => selectedIds.value.size + selectedFolders.value.size,
);

function clearSelection(): void {
  selectedIds.value = new Set();
  selectedFolders.value = new Set();
}

// ── Bulk actions ────────────────────────────────────────────────
// Export, move and trash all accept documents (by id) and folders (by
// prefix, expanded server-side). Move and trash run as client-driven,
// server-executed chunk loops (see confirmMove / confirmTrash) so they
// scale, show progress and can be cancelled.
const bulkBusy = ref(false);
const showTrashModal = ref(false);
const showMoveModal = ref(false);
const moveTargetPath = ref('');
const showCopyModal = ref(false);
const copyTargetProject = ref<string>('');
const copyTargetPath = ref('');
const copyFolderSuggestions = ref<string[]>([]);
const copyFoldersLoading = ref(false);
// Off on every open: overwriting is destructive, so it is a per-run decision
// rather than a setting that survives into the next copy.
const copyOverwrite = ref(false);
// Own flag: the cross-project fetch below bypasses the composable state.
const copyFoldersTruncated = ref(false);

const moveFolderSuggestions = computed(() =>
  docsState.folders.value.map((f) => f),
);

async function confirmTrash(): Promise<void> {
  const pid = selectedProjectId.value;
  if (!pid) return;
  const ids = [...selectedIds.value];
  const folders = [...selectedFolders.value];
  bulkBusy.value = true;
  bulkAbort.value = false;
  bulkProgress.value = 0;
  let trashedTotal = 0;
  let skippedTotal = 0;
  let cursor: string | undefined;
  try {
    let done = false;
    while (!done && !bulkAbort.value) {
      const r = await docsState.trashChunk(pid, { ids, folders, limit: MOVE_CHUNK, cursor });
      if (!r) break; // error surfaced via docsState.error
      trashedTotal += r.trashed;
      skippedTotal += r.skipped;
      bulkProgress.value = trashedTotal;
      cursor = r.cursor ?? undefined;
      done = r.done;
    }
    await docsState.loadPage(pid, docsState.page.value, docsState.pathPrefix.value);
    notice.value = t('documents.selection.trashDone', {
      trashed: trashedTotal,
      skipped: skippedTotal,
    });
  } finally {
    bulkBusy.value = false;
    showTrashModal.value = false;
  }
}

// ── Per-row action menu (single-file actions) ───────────────────
// Context-dependent "⋯" menu on every document row. Currently the only
// action is "unpack", offered when the row is a ZIP; future single-file
// actions (rename, duplicate, download, …) slot into rowMenuItems.
const notice = ref('');

// ── Installed kits (project root only) ────────────────────────────────
//
// The kit endpoints require project ADMIN. A reader browsing documents
// gets an error rather than a list, and for them the row simply does not
// exist — hence `kitsVisible` gates on a *successful* load, not on the
// mere attempt.
const kitState = useKitAdmin();
const kitsVisible = computed(() =>
  !docsState.pathPrefix.value
  && !kitState.error.value
  && kitState.installed.value.length > 0);

const kitNames = computed(() =>
  kitState.installed.value.map((record) => record.kit.name).join(', '));

async function loadKits(): Promise<void> {
  if (!selectedProjectId.value) return;
  await kitState.load(selectedProjectId.value);
}

async function updateAllKits(): Promise<void> {
  if (!selectedProjectId.value) return;
  notice.value = '';
  try {
    const results = await kitState.updateAll(selectedProjectId.value, false);
    notice.value = t('documents.kits.updated', { count: results.length });
    // Kits write documents — the listing on screen is stale now.
    await docsState.loadPage(selectedProjectId.value, 0, docsState.pathPrefix.value);
  } catch {
    notice.value = t('documents.kits.updateFailed');
  }
}

function isZip(doc: DocumentSummary): boolean {
  const mime = (doc.mimeType ?? '').toLowerCase();
  return doc.path.toLowerCase().endsWith('.zip')
    || mime === 'application/zip'
    || mime === 'application/x-zip-compressed';
}

function rowMenuItems(doc: DocumentSummary): RowMenuItem[] {
  const items: RowMenuItem[] = [
    { key: 'rename', label: t('documents.rowMenu.rename') },
    { key: 'duplicate', label: t('documents.rowMenu.duplicate') },
  ];
  if (isZip(doc)) items.push({ key: 'unpack', label: t('documents.rowMenu.unpack') });
  items.push({ key: 'delete', label: t('documents.rowMenu.delete'), danger: true });
  return items;
}

const folderMenuItems: RowMenuItem[] = [
  { key: 'rename', label: t('documents.rowMenu.rename') },
];

function onFolderAction(folder: string, key: string): void {
  if (key === 'rename') openRename('folder', folderPrefixOf(folder), folder);
}

async function onRowAction(doc: DocumentSummary, key: string): Promise<void> {
  if (key === 'rename') {
    openRename('file', doc.path, fileBasename(doc.path));
    return;
  }
  if (key === 'delete') {
    pendingDelete.value = doc;
    return;
  }
  if (key === 'duplicate') {
    const pid = selectedProjectId.value;
    if (!pid) return;
    notice.value = '';
    const copy = await docsState.duplicate(pid, doc.id);
    if (!copy) return; // failure already surfaced via docsState.error
    // Reload first — it fires the items watch which clears the notice — then
    // name the copy so the summary survives the refresh.
    await docsState.loadPage(pid, docsState.page.value, docsState.pathPrefix.value);
    notice.value = t('documents.rowMenu.duplicateDone', { name: fileBasename(copy.path) });
    return;
  }
  if (key !== 'unpack') return;
  const pid = selectedProjectId.value;
  if (!pid) return;
  notice.value = '';
  const result = await docsState.unpack(pid, doc.id);
  if (!result) return; // failure already surfaced via docsState.error
  // Reload first — it fires the items watch which clears the notice — then
  // set the summary so it survives the refresh.
  await docsState.loadPage(pid, docsState.page.value, docsState.pathPrefix.value);
  notice.value = t('documents.rowMenu.unpackDone', {
    extracted: result.extracted,
    folder: result.targetFolder,
    skipped: result.skipped.length,
    failed: result.failed.length,
  });
}

// Single-file delete (moves to trash, like the bulk action) with a
// confirm scoped to the one row.
const pendingDelete = ref<DocumentSummary | null>(null);
const deleteBusy = ref(false);

// Rename — single row action for a file (one call) or a folder (chunked,
// cursor loop with progress/abort, per-doc WRITE check).
const renameTarget = ref<{ kind: 'file' | 'folder'; path: string; currentName: string } | null>(null);
const renameName = ref('');
const renameError = ref('');

function openRename(kind: 'file' | 'folder', path: string, currentName: string): void {
  renameTarget.value = { kind, path, currentName };
  renameName.value = currentName;
  renameError.value = '';
}

async function confirmRename(): Promise<void> {
  const target = renameTarget.value;
  const pid = selectedProjectId.value;
  if (!target || !pid) return;
  const newName = renameName.value.trim();
  if (!newName || newName.includes('/')) {
    renameError.value = t('documents.rename.invalid');
    return;
  }
  if (newName === target.currentName) {
    renameTarget.value = null;
    return;
  }
  bulkBusy.value = true;
  bulkAbort.value = false;
  bulkProgress.value = 0;
  let renamedTotal = 0;
  let skippedTotal = 0;
  let cursor: string | undefined;
  try {
    let done = false;
    while (!done && !bulkAbort.value) {
      const r = await docsState.renameChunk(pid, {
        path: target.path,
        newName,
        limit: MOVE_CHUNK,
        cursor,
      });
      if (!r) break; // error surfaced via docsState.error
      renamedTotal += r.renamed;
      skippedTotal += r.skipped;
      bulkProgress.value = renamedTotal;
      cursor = r.cursor ?? undefined;
      done = r.done;
    }
    await docsState.loadPage(pid, docsState.page.value, docsState.pathPrefix.value);
    notice.value = t('documents.rename.done', {
      renamed: renamedTotal,
      skipped: skippedTotal,
    });
  } finally {
    bulkBusy.value = false;
    renameTarget.value = null;
  }
}

async function confirmRowDelete(): Promise<void> {
  const doc = pendingDelete.value;
  if (!doc) return;
  deleteBusy.value = true;
  try {
    const ok = await docsState.remove(doc.id);
    if (ok) {
      // remove() splices the row in place (no items reassign), so also drop
      // it from the selection set by hand.
      const next = new Set(selectedIds.value);
      next.delete(doc.id);
      selectedIds.value = next;
      pendingDelete.value = null;
    }
  } finally {
    deleteBusy.value = false;
  }
}

const exportBusy = ref(false);

async function exportSelected(): Promise<void> {
  const pid = selectedProjectId.value;
  if (!pid || selectedIds.value.size + selectedFolders.value.size === 0) return;
  exportBusy.value = true;
  try {
    const result = await docsState.exportZip(
      pid,
      [...selectedIds.value],
      [...selectedFolders.value],
    );
    if (!result) return;
    // Blob → temporary object URL → programmatic download. The server
    // streamed the archive; the blob only lives briefly in the browser.
    const url = URL.createObjectURL(result.blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = result.filename || 'documents.zip';
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  } finally {
    exportBusy.value = false;
  }
}

function openMoveModal(): void {
  moveTargetPath.value = docsState.pathPrefix.value.replace(/\/+$/, '');
  if (selectedProjectId.value && docsState.folders.value.length === 0) {
    void docsState.loadFolders(selectedProjectId.value);
  }
  showMoveModal.value = true;
}

// ── Copy ────────────────────────────────────────────────────────
// Copy is like move but creates a new document in the target project
// (default: current project) instead of updating the source path. The
// dialog lets the user pick a target project first; switching the
// project reloads the folder list for that project.
const copyProjectOptions = computed(() => [
  { value: '', label: t('documents.selection.copyCurrentProject') },
  ...projectsState.projects.value.map((p) => ({ value: p.name, label: p.title || p.name })),
]);

async function openCopyModal(): Promise<void> {
  copyTargetProject.value = '';
  copyOverwrite.value = false;
  copyTargetPath.value = docsState.pathPrefix.value.replace(/\/+$/, '');
  if (selectedProjectId.value && docsState.folders.value.length === 0) {
    // Awaited, unlike the move dialog's fire-and-forget: the suggestions are
    // copied into a local list right below, so they have to be there.
    await docsState.loadFolders(selectedProjectId.value);
  }
  copyFolderSuggestions.value = docsState.folders.value.map((f) => f);
  copyFoldersTruncated.value = docsState.foldersTruncated.value;
  showCopyModal.value = true;
}

async function onCopyProjectChange(): Promise<void> {
  const targetPid = copyTargetProject.value || selectedProjectId.value;
  if (!targetPid) return;
  copyFoldersLoading.value = true;
  copyTargetPath.value = '';
  try {
    // Use a fresh fetch — docsState.loadFolders would overwrite the
    // current project's folder list in the shared composable state.
    const params = new URLSearchParams({ projectId: targetPid });
    const data = await brainFetch<DocumentFoldersResponse>(
      'GET',
      `documents/folders?${params}`,
    );
    copyFolderSuggestions.value = data.folders ?? [];
    copyFoldersTruncated.value = data.truncated === true;
  } catch {
    copyFolderSuggestions.value = [];
  } finally {
    copyFoldersLoading.value = false;
  }
}

async function confirmCopy(): Promise<void> {
  const pid = selectedProjectId.value;
  if (!pid) return;
  const targetProject = copyTargetProject.value || pid;
  const target = copyTargetPath.value.replace(/\/+$/, '');
  const ids = [...selectedIds.value];
  const folders = [...selectedFolders.value];
  bulkBusy.value = true;
  bulkAbort.value = false;
  bulkProgress.value = 0;
  const overwrite = copyOverwrite.value;
  let copiedTotal = 0;
  let overwrittenTotal = 0;
  let skippedTotal = 0;
  let cursor: string | undefined;
  try {
    let done = false;
    while (!done && !bulkAbort.value) {
      const r = await docsState.copyChunk(pid, {
        ids,
        folders,
        targetProjectId: targetProject,
        targetFolder: target,
        overwrite,
        limit: MOVE_CHUNK,
        cursor,
      });
      if (!r) break; // error surfaced via docsState.error
      copiedTotal += r.copied;
      overwrittenTotal += r.overwritten;
      skippedTotal += r.skipped;
      // Progress counts written documents, new or replaced alike.
      bulkProgress.value = copiedTotal + overwrittenTotal;
      cursor = r.cursor ?? undefined;
      done = r.done;
    }
    // Only mention replacements when they were asked for — an always-present
    // "0 overwritten" would be noise in the common case.
    notice.value = overwrite
      ? t('documents.selection.copyDoneOverwrite', {
          copied: copiedTotal,
          overwritten: overwrittenTotal,
          skipped: skippedTotal,
        })
      : t('documents.selection.copyDone', {
          copied: copiedTotal,
          skipped: skippedTotal,
        });
  } finally {
    bulkBusy.value = false;
    showCopyModal.value = false;
  }
}

// Chunked move: the server executes one bounded chunk per call and skips
// anything it can't move (no permission / collision / cycle); we drive the
// loop, show progress and can cancel between chunks. Passing the cursor back
// makes it O(N) and terminating (stop when the server reports done).
const MOVE_CHUNK = 25;
const bulkProgress = ref(0);
const bulkAbort = ref(false);

async function confirmMove(): Promise<void> {
  const pid = selectedProjectId.value;
  if (!pid) return;
  const target = moveTargetPath.value.replace(/\/+$/, '');
  const ids = [...selectedIds.value];
  const folders = [...selectedFolders.value];
  bulkBusy.value = true;
  bulkAbort.value = false;
  bulkProgress.value = 0;
  let movedTotal = 0;
  let skippedTotal = 0;
  let cursor: string | undefined;
  try {
    let done = false;
    while (!done && !bulkAbort.value) {
      const r = await docsState.moveChunk(pid, {
        ids,
        folders,
        targetFolder: target,
        limit: MOVE_CHUNK,
        cursor,
      });
      if (!r) break; // error surfaced via docsState.error
      movedTotal += r.moved;
      skippedTotal += r.skipped;
      bulkProgress.value = movedTotal;
      cursor = r.cursor ?? undefined;
      done = r.done;
    }
    // Refresh — the items watch clears the selection on the fresh page — then
    // set the summary so it survives the reload.
    await docsState.loadPage(pid, docsState.page.value, docsState.pathPrefix.value);
    notice.value = t('documents.selection.moveDone', {
      moved: movedTotal,
      skipped: skippedTotal,
    });
  } finally {
    bulkBusy.value = false;
    showMoveModal.value = false;
  }
}

// ── External file drop (Finder → current folder) ────────────────
// Same upload path as Cortex's file tree: each OS file is POSTed to
// documents/upload with a path anchored at the folder currently in
// view (docsState.pathPrefix). Dropping into a virtual folder makes
// it real on the first successful upload.
const isDragOver = ref(false);
const uploadBusy = ref(false);
// dragenter/dragleave fire per child element, so a plain boolean
// flickers — count depth and only clear the highlight at zero.
let dragDepth = 0;

function dragHasFiles(e: DragEvent): boolean {
  return Array.from(e.dataTransfer?.types ?? []).includes('Files');
}

function onDragEnter(e: DragEvent): void {
  if (!dragHasFiles(e)) return;
  dragDepth += 1;
  isDragOver.value = true;
}

function onDragOver(e: DragEvent): void {
  if (!dragHasFiles(e)) return;
  e.preventDefault(); // required to allow the drop
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy';
}

function onDragLeave(e: DragEvent): void {
  if (!dragHasFiles(e)) return;
  dragDepth = Math.max(0, dragDepth - 1);
  if (dragDepth === 0) isDragOver.value = false;
}

async function onDrop(e: DragEvent): Promise<void> {
  if (!dragHasFiles(e)) return;
  e.preventDefault();
  dragDepth = 0;
  isDragOver.value = false;
  const pid = selectedProjectId.value;
  const files = Array.from(e.dataTransfer?.files ?? []);
  if (!pid || files.length === 0) return;
  const base = docsState.pathPrefix.value.replace(/\/+$/, '');
  uploadBusy.value = true;
  try {
    for (const file of files) {
      const path = base ? `${base}/${file.name}` : file.name;
      await docsState.upload(pid, { file, path });
    }
  } finally {
    uploadBusy.value = false;
  }
}

// ── Virtual folder ──────────────────────────────────────────────
// The server has no folder entity — folders are just path prefixes on
// documents. "New folder" therefore navigates into a not-yet-existing
// prefix: the list shows empty until the first document is saved there
// (drop, upload, or "+ New"), and leaving the path drops it again.
const showNewFolderModal = ref(false);
const newFolderName = ref('');
const newFolderError = ref('');

function openNewFolder(): void {
  newFolderName.value = '';
  newFolderError.value = '';
  showNewFolderModal.value = true;
}

function confirmNewFolder(): void {
  if (!selectedProjectId.value) return;
  const name = newFolderName.value.trim().replace(/^\/+|\/+$/g, '');
  if (!name) {
    newFolderError.value = t('documents.newFolderDialog.nameRequired');
    return;
  }
  const base = docsState.pathPrefix.value.replace(/\/+$/, '');
  const virtualPath = base ? `${base}/${name}/` : `${name}/`;
  showNewFolderModal.value = false;
  void docsState.loadPage(selectedProjectId.value, 0, virtualPath);
  syncUrl();
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

    <div
      v-else
      class="h-full min-h-0 flex flex-col relative"
      @dragenter="onDragEnter"
      @dragover="onDragOver"
      @dragleave="onDragLeave"
      @drop="onDrop"
    >
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
            <span v-if="idx > 0" class="opacity-40">/</span>
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
            size="sm"
            :placeholder="$t('documents.searchPlaceholder')"
          />
        </div>
        <VButton
          variant="secondary"
          size="sm"
          :title="$t('documents.newFolder')"
          @click="openNewFolder"
        >+ 📁</VButton>
        <VButton
          variant="primary"
          size="sm"
          :title="$t('documents.newDocument')"
          @click="openCreateInNotepad"
        >{{ $t('documents.addNew') }}</VButton>
      </div>

      <!-- Installed kits — only at the project root, where "this project"
           is what the user is looking at. No "update available" badge:
           knowing that would mean fetching every kit's remote on load. -->
      <div
        v-if="kitsVisible"
        class="px-6 py-2 border-b border-base-300 bg-base-200/40 flex items-center gap-3 text-sm"
      >
        <span class="opacity-80">
          {{ $t('documents.kits.installed', { count: kitState.installed.value.length }) }}
        </span>
        <span class="opacity-60 truncate">{{ kitNames }}</span>
        <div class="flex-1"></div>
        <VButton
          variant="ghost"
          size="sm"
          :loading="kitState.busy.value"
          @click="updateAllKits"
        >{{ $t('documents.kits.update') }}</VButton>
      </div>

      <!-- Bulk-action bar — appears once at least one document is selected -->
      <div
        v-if="selectedCount > 0"
        class="px-6 py-2 border-b border-base-300 bg-base-200/50 flex items-center gap-3 text-sm"
      >
        <span class="font-medium">
          {{ $t('documents.selection.count', { count: selectedCount }) }}
        </span>
        <VButton variant="ghost" size="sm" @click="clearSelection">
          {{ $t('documents.selection.clear') }}
        </VButton>
        <div class="flex-1"></div>
        <VButton
          variant="ghost"
          size="sm"
          :loading="exportBusy"
          :disabled="bulkBusy"
          @click="exportSelected"
        >
          {{ $t('documents.selection.export') }}
        </VButton>
        <VButton
          variant="secondary"
          size="sm"
          :disabled="bulkBusy || exportBusy"
          @click="openMoveModal"
        >
          {{ $t('documents.selection.move') }}
        </VButton>
        <VButton
          variant="secondary"
          size="sm"
          :disabled="bulkBusy || exportBusy"
          @click="openCopyModal"
        >
          {{ $t('documents.selection.copy') }}
        </VButton>
        <VButton
          variant="danger"
          size="sm"
          :disabled="bulkBusy || exportBusy"
          @click="showTrashModal = true"
        >
          {{ $t('documents.selection.trash') }}
        </VButton>
      </div>

      <VAlert v-if="docsState.error.value" variant="error" class="m-4">
        <span>{{ docsState.error.value }}</span>
      </VAlert>

      <VAlert v-if="notice" variant="success" class="m-4">
        <span>{{ notice }}</span>
      </VAlert>

      <!-- Delegated hits span the whole mount, not the folder in the
           breadcrumb. Saying so is the difference between a useful result and
           a confusing one — the paths shown may sit anywhere in the source. -->
      <VAlert v-if="mountSearchDelegated" variant="info" class="m-4">
        <span>{{ $t('documents.mountSearchDelegated', {
          mount: currentMount?.displayName || currentMount?.name,
        }) }}</span>
      </VAlert>

      <div class="flex-1 min-h-0 overflow-y-auto">
        <div v-if="docsState.loading.value" class="p-6 text-sm opacity-60">
          {{ $t('documents.loading') }}
        </div>
        <div v-else-if="isEmpty" class="p-6">
          <VEmptyState
            :headline="mountEmptyReason ? mountEmptyReason.headline
              : $t('documents.empty.folderHeadline')"
            :body="mountEmptyReason ? mountEmptyReason.body
              : $t('documents.empty.folderBody')"
          >
            <!-- Offered only where retrying can help: an unreachable source or
                 a search that could not be handed over. The instance cache
                 holds for five minutes, so without this the only way out of a
                 just-fixed misconfiguration is waiting. -->
            <template v-if="mountEmptyReason?.retryable" #action>
              <VButton size="sm" variant="ghost" @click="retryMount">
                {{ $t('documents.empty.mountRetry') }}
              </VButton>
            </template>
          </VEmptyState>
        </div>
        <table v-else class="w-full text-sm">
          <thead class="text-xs uppercase opacity-60 sticky top-0 bg-base-100 z-[1]">
            <tr>
              <th class="text-left pl-4 pr-1 py-2 w-8">
                <VCheckbox
                  :model-value="allSelected"
                  :indeterminate="someSelected"
                  :disabled="docsState.items.value.length === 0 && docsState.subFolders.value.length === 0"
                  @update:model-value="toggleAll"
                />
              </th>
              <th class="text-left px-2 py-2 w-8"></th>
              <th class="text-left px-2 py-2">Name</th>
              <th class="text-left px-2 py-2 w-24">Kind</th>
              <th class="text-left px-2 py-2 w-32">Tags</th>
              <th class="text-right px-2 py-2 w-20">Size</th>
              <th class="text-left px-2 py-2 w-28">Created</th>
              <th class="text-left px-2 py-2 w-32">By</th>
              <th class="w-10 pr-3 py-2"></th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="folder in docsState.subFolders.value"
              :key="`f:${folder}`"
              class="border-b border-base-200 hover:bg-base-200/60 cursor-pointer"
              :class="{ 'bg-primary/5': isFolderSelected(folder) }"
              @click="navigateIntoFolder(folder)"
            >
              <td class="pl-4 pr-1 py-1.5" @click.stop>
                <VCheckbox
                  :model-value="isFolderSelected(folder)"
                  @update:model-value="toggleFolder(folder)"
                />
              </td>
              <td class="px-2 py-1.5">📁</td>
              <td class="px-2 py-1.5 font-medium">{{ folder }}</td>
              <td class="px-2 py-1.5 opacity-50">folder</td>
              <td class="px-2 py-1.5"></td>
              <td class="px-2 py-1.5"></td>
              <td class="px-2 py-1.5"></td>
              <td class="px-2 py-1.5"></td>
              <td class="pr-3 py-1.5 text-right" @click.stop>
                <RowActionsMenu
                  :items="folderMenuItems"
                  :title="$t('documents.rowMenu.title')"
                  @select="(k) => onFolderAction(folder, k)"
                />
              </td>
            </tr>
            <tr
              v-for="doc in docsState.items.value"
              :key="doc.id"
              class="border-b border-base-200 hover:bg-base-200/60 cursor-pointer"
              :class="{ 'bg-primary/5': isSelected(doc.id) }"
              @click="openInNotepad(doc.id)"
            >
              <td class="pl-4 pr-1 py-1.5" @click.stop>
                <VCheckbox
                  :model-value="isSelected(doc.id)"
                  @update:model-value="toggleDoc(doc.id)"
                />
              </td>
              <td class="px-2 py-1.5">
                <DocumentIcon :kind="doc.kind ?? null" :mime-type="doc.mimeType ?? null" />
              </td>
              <td class="px-2 py-1.5">
                <div class="flex items-center gap-2 min-w-0">
                  <span
                    v-if="doc.color"
                    class="size-2 rounded-full flex-shrink-0"
                    :class="accentColorDotClass(doc.color)"
                    :aria-label="`color ${doc.color}`"
                  />
                  <div class="font-medium truncate">{{ doc.title || fileBasename(doc.path) }}</div>
                </div>
                <div class="text-xs opacity-60 font-mono truncate">{{ doc.path }}</div>
              </td>
              <td class="px-2 py-1.5 text-xs opacity-70">{{ doc.kind ?? '—' }}</td>
              <td class="px-2 py-1.5 text-xs opacity-70 truncate">
                {{ (doc.tags ?? []).join(', ') }}
              </td>
              <td class="px-2 py-1.5 text-right text-xs">{{ formatSize(doc.size) }}</td>
              <td class="px-2 py-1.5 text-xs">{{ formatDate(doc.createdAtMs) }}</td>
              <td class="px-2 py-1.5 text-xs opacity-70 truncate">{{ doc.createdBy ?? '—' }}</td>
              <td class="pr-3 py-1.5 text-right" @click.stop>
                <RowActionsMenu
                  :items="rowMenuItems(doc)"
                  :title="$t('documents.rowMenu.title')"
                  :empty-label="$t('documents.rowMenu.empty')"
                  @select="(k) => onRowAction(doc, k)"
                />
              </td>
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

      <!-- External file drop overlay -->
      <div
        v-if="isDragOver"
        class="absolute inset-0 z-10 flex items-center justify-center bg-primary/10 border-2 border-dashed border-primary pointer-events-none"
      >
        <span class="text-sm font-medium text-primary">{{ $t('documents.dropHint') }}</span>
      </div>
    </div>

    <!-- Bulk move to another folder -->
    <VModal
      v-model="showMoveModal"
      :title="$t('documents.selection.moveTitle')"
      :close-on-backdrop="!bulkBusy"
    >
      <p v-if="bulkBusy" class="text-sm mb-3">
        {{ $t('documents.selection.moveRunning', { moved: bulkProgress }) }}
      </p>
      <template v-else>
        <p class="text-sm mb-3 opacity-80">
          {{ $t('documents.selection.moveBody', { count: selectedCount }) }}
        </p>
        <VInput
          v-model="moveTargetPath"
          :label="$t('documents.selection.moveTargetLabel')"
          :placeholder="$t('documents.selection.customPathPlaceholder')"
          :suggestions="moveFolderSuggestions"
        />
        <p v-if="docsState.foldersTruncated.value" class="text-xs opacity-60 mt-1">
          {{ $t('documents.selection.folderSuggestionsTruncated') }}
        </p>
      </template>
      <template #actions>
        <VButton
          v-if="bulkBusy"
          variant="ghost"
          :disabled="bulkAbort"
          @click="bulkAbort = true"
        >
          {{ $t('documents.selection.stop') }}
        </VButton>
        <VButton v-else variant="ghost" @click="showMoveModal = false">
          {{ $t('common.cancel') }}
        </VButton>
        <VButton variant="primary" :loading="bulkBusy" @click="confirmMove">
          {{ $t('documents.selection.moveConfirm') }}
        </VButton>
      </template>
    </VModal>

    <!-- Bulk copy to another project/folder -->
    <VModal
      v-model="showCopyModal"
      :title="$t('documents.selection.copyTitle')"
      :close-on-backdrop="!bulkBusy"
    >
      <p v-if="bulkBusy" class="text-sm mb-3">
        {{ $t('documents.selection.copyRunning', { copied: bulkProgress }) }}
      </p>
      <template v-else>
        <p class="text-sm mb-3 opacity-80">
          {{ $t('documents.selection.copyBody', { count: selectedCount }) }}
        </p>
        <VSelect
          v-model="copyTargetProject"
          :options="copyProjectOptions"
          :label="$t('documents.selection.copyTargetProjectLabel')"
          @update:model-value="onCopyProjectChange"
        />
        <VInput
          v-model="copyTargetPath"
          :label="$t('documents.selection.copyTargetFolderLabel')"
          :placeholder="$t('documents.selection.customPathPlaceholder')"
          :suggestions="copyFolderSuggestions"
          :disabled="copyFoldersLoading"
          class="mt-3"
        />
        <p v-if="copyFoldersTruncated" class="text-xs opacity-60 mt-1">
          {{ $t('documents.selection.folderSuggestionsTruncated') }}
        </p>
        <VCheckbox
          v-model="copyOverwrite"
          :label="$t('documents.selection.copyOverwriteLabel')"
          :help="$t('documents.selection.copyOverwriteHelp')"
          class="mt-3"
        />
      </template>
      <template #actions>
        <VButton
          v-if="bulkBusy"
          variant="ghost"
          :disabled="bulkAbort"
          @click="bulkAbort = true"
        >
          {{ $t('documents.selection.stop') }}
        </VButton>
        <VButton v-else variant="ghost" @click="showCopyModal = false">
          {{ $t('common.cancel') }}
        </VButton>
        <VButton variant="primary" :loading="bulkBusy" @click="confirmCopy">
          {{ $t('documents.selection.copyConfirm') }}
        </VButton>
      </template>
    </VModal>

    <!-- Bulk move to trash -->
    <VModal
      v-model="showTrashModal"
      :title="$t('documents.selection.trashTitle')"
      :close-on-backdrop="!bulkBusy"
    >
      <p v-if="bulkBusy" class="text-sm">
        {{ $t('documents.selection.trashRunning', { trashed: bulkProgress }) }}
      </p>
      <p v-else class="text-sm opacity-80">
        {{ $t('documents.selection.trashBody', { count: selectedCount }) }}
      </p>
      <template #actions>
        <VButton
          v-if="bulkBusy"
          variant="ghost"
          :disabled="bulkAbort"
          @click="bulkAbort = true"
        >
          {{ $t('documents.selection.stop') }}
        </VButton>
        <VButton v-else variant="ghost" @click="showTrashModal = false">
          {{ $t('common.cancel') }}
        </VButton>
        <VButton variant="danger" :loading="bulkBusy" @click="confirmTrash">
          {{ $t('documents.selection.trash') }}
        </VButton>
      </template>
    </VModal>

    <!-- Rename (file: one call, folder: chunked) -->
    <VModal
      :model-value="renameTarget !== null"
      :title="$t('documents.rename.title')"
      :close-on-backdrop="!bulkBusy"
      @update:model-value="(v) => { if (!v && !bulkBusy) renameTarget = null; }"
    >
      <p v-if="bulkBusy" class="text-sm">
        {{ $t('documents.rename.running', { renamed: bulkProgress }) }}
      </p>
      <form v-else @submit.prevent="confirmRename">
        <VInput
          v-model="renameName"
          :label="$t('documents.rename.label')"
          :error="renameError"
        />
      </form>
      <template #actions>
        <VButton
          v-if="bulkBusy"
          variant="ghost"
          :disabled="bulkAbort"
          @click="bulkAbort = true"
        >
          {{ $t('documents.selection.stop') }}
        </VButton>
        <VButton v-else variant="ghost" @click="renameTarget = null">
          {{ $t('common.cancel') }}
        </VButton>
        <VButton variant="primary" :loading="bulkBusy" @click="confirmRename">
          {{ $t('documents.rename.confirm') }}
        </VButton>
      </template>
    </VModal>

    <!-- Single-file delete (→ trash) -->
    <VModal
      :model-value="pendingDelete !== null"
      :title="$t('documents.selection.trashTitle')"
      :close-on-backdrop="!deleteBusy"
      @update:model-value="(v) => { if (!v) pendingDelete = null; }"
    >
      <p class="text-sm opacity-80">
        {{ $t('documents.rowMenu.deleteBody', {
          name: pendingDelete ? (pendingDelete.title || fileBasename(pendingDelete.path)) : '',
        }) }}
      </p>
      <template #actions>
        <VButton variant="ghost" :disabled="deleteBusy" @click="pendingDelete = null">
          {{ $t('common.cancel') }}
        </VButton>
        <VButton variant="danger" :loading="deleteBusy" @click="confirmRowDelete">
          {{ $t('documents.selection.trash') }}
        </VButton>
      </template>
    </VModal>

    <!-- New (virtual) folder -->
    <VModal v-model="showNewFolderModal" :title="$t('documents.newFolderDialog.title')">
      <form @submit.prevent="confirmNewFolder">
        <VInput
          v-model="newFolderName"
          :label="$t('documents.newFolderDialog.nameLabel')"
          :placeholder="$t('documents.newFolderDialog.namePlaceholder')"
          :help="$t('documents.newFolderDialog.nameHelp')"
          :error="newFolderError"
        />
      </form>
      <template #actions>
        <VButton variant="ghost" @click="showNewFolderModal = false">
          {{ $t('common.cancel') }}
        </VButton>
        <VButton variant="primary" @click="confirmNewFolder">
          {{ $t('documents.newFolderDialog.create') }}
        </VButton>
      </template>
    </VModal>
  </EditorShell>
</template>
