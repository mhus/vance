<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  type Crumb,
  EditorShell,
  type FocusZone,
  ProjectListSidebar,
  VAlert,
  VButton,
  VCard,
  VCheckbox,
  VDataList,
  VEmptyState,
  VFileInput,
  VInput,
  VModal,
  VPagination,
  VSelect,
  VTextarea,
  CodeEditor,
  MarkdownView,
} from '@/components';
import { useDocuments } from '@/composables/useDocuments';
import { useHelp } from '@/composables/useHelp';
import { useTenantProjects } from '@/composables/useTenantProjects';
import { brainFetch, documentContentUrl } from '@vance/shared';
import type { FollowUpRequestDto, FollowUpResponseDto } from '@vance/generated';
import type { FollowUpExtensionOptions } from '@/components';
import { consumeDocumentDraft } from '@/platform';
import DocumentPreview from './DocumentPreview.vue';
import DocumentIcon from './DocumentIcon.vue';
import DocumentArchives from './DocumentArchives.vue';
import ListView from './ListView.vue';
import TreeView from './TreeView.vue';
// Checklist editor — ships when the user opens a kind:checklist
// document. Lazy-loaded so the documents bundle stays slim.
const ChecklistView = defineAsyncComponent(() => import('./ChecklistView.vue'));
import MindmapView from './MindmapView.vue';
import RecordsView from './RecordsView.vue';
import GraphView from './GraphView.vue';
// ECharts (~600 KB modular) ships only when a chart document is
// opened — keep the documents bundle lean.
const ChartView = defineAsyncComponent(() => import('./ChartView.vue'));
import SheetView from './SheetView.vue';
// Marpit is only used for `kind: slides` — keep it out of the
// initial bundle.
const SlidesView = defineAsyncComponent(() => import('./SlidesView.vue'));
// Mermaid (~700 KB minified gzipped) ships only when a diagram
// document is opened — keep the documents bundle lean.
const DiagramView = defineAsyncComponent(() => import('./DiagramView.vue'));
// ONLYOFFICE / Collabora editor — only relevant for DOCX/XLSX
// documents when a tenant has configured `office.*`. Pulled in
// lazily so the documents bundle isn't paying for it.
const OfficeEditor = defineAsyncComponent(() => import('./OfficeEditor.vue'));
import {
  isListMime,
  parseList,
  serializeList,
  ListCodecError,
  type ListDocument,
} from './listItemsCodec';
import {
  isChecklistMime,
  parseChecklist,
  serializeChecklist,
  ChecklistCodecError,
  type ChecklistDocument,
} from './checklistCodec';
import {
  isTreeMime,
  parseTree,
  serializeTree,
  TreeCodecError,
  type TreeDocument,
} from './treeItemsCodec';
import {
  isRecordsMime,
  parseRecords,
  serializeRecords,
  RecordsCodecError,
  type RecordsDocument,
} from './recordsCodec';
import {
  isGraphMime,
  parseGraph,
  serializeGraph,
  GraphCodecError,
  type GraphDocument,
} from './graphCodec';
import {
  isChartMime,
  parseChart,
  serializeChart,
  ChartCodecError,
  type ChartDocument,
} from './chartCodec';
import {
  isSheetMime,
  parseSheet,
  serializeSheet,
  SheetCodecError,
  type SheetDocument,
} from './sheetCodec';
import {
  isSlidesMime,
  parseSlides,
  SlidesCodecError,
  type SlidesDocument,
} from './slidesCodec';
import {
  isDiagramMime,
  parseDiagram,
  DiagramCodecError,
  type DiagramDocument,
} from './diagramCodec';
import { resolveKind, type KindEntry } from '@vance/kind-registry';
import type {
  DocumentDto,
  DocumentSummary,
  DocumentUpdateRequest,
  ProjectSummary,
} from '@vance/generated';

const PAGE_SIZE = 20;

const { t } = useI18n();
const projectsState = useTenantProjects();
const docsState = useDocuments(PAGE_SIZE);

const selectedProjectId = ref<string | null>(null);

const editTitle = ref('');
const editPath = ref('');
const editMimeType = ref('');
const editInlineText = ref('');
const editAutoSummary = ref(false);
const editSummaryDirty = ref(false);
// Direct edit on `document.summary` — single-field write through
// the dedicated /summary endpoint. Important for binaries (images,
// PDFs) where the auto-summary scheduler doesn't run and the user /
// LLM needs to author the caption manually.
const editSummary = ref('');
const summarySaving = ref(false);
const summarySaveMessage = ref<string | null>(null);
/**
 * Tri-state for the project-RAG inclusion override. {@code 'auto'} (default
 * — null in the DTO) follows the project rule "documents/** + textual mime";
 * {@code 'on'} forces inclusion, {@code 'off'} excludes the document.
 */
const editRagEnabled = ref<'auto' | 'on' | 'off'>('auto');
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

// Unsaved-changes guard. Mirrors the diff logic in {@link apply}: a
// document is considered dirty when any editable field — title, path,
// mime type, inline body, auto-summary toggles, RAG override —
// differs from the currently loaded server-side document. Used to
// gate `backToList` (modal) and the page-unload event (browser
// prompt). The kind-specific tabs (list/checklist/tree/…) all funnel
// their mutations through `editInlineText`, so the inline-body
// comparison covers them too.
const isDirty = computed<boolean>(() => {
  const sel = docsState.selected.value;
  if (!sel) return false;
  if (editTitle.value !== (sel.title ?? '')) return true;
  const newPath = editPath.value.trim();
  if (newPath && newPath !== sel.path) return true;
  const newMime = editMimeType.value.trim();
  if (newMime && newMime !== (sel.mimeType ?? '')) return true;
  if (sel.inline && editInlineText.value !== (sel.inlineText ?? '')) return true;
  if (editAutoSummary.value !== (sel.autoSummary ?? false)) return true;
  if (editSummaryDirty.value !== (sel.summaryDirty ?? false)) return true;
  const currentRag: 'auto' | 'on' | 'off' =
      sel.ragEnabled == null ? 'auto'
    : sel.ragEnabled ? 'on'
    : 'off';
  if (editRagEnabled.value !== currentRag) return true;
  return false;
});

// Discard-confirm modal — gates `backToList` (and the Cancel
// button) when there are unsaved edits. Three actions: Save (apply +
// leave), Discard (just leave), Cancel (stay on detail view).
const showDiscardModal = ref(false);

// Revert-confirm modal — gates the footer's "discard changes" button.
// Two actions: Confirm (re-fetch from server, drop local edits) or
// Cancel (close modal, keep edits). Only reachable while {@link isDirty}
// is true, so the body always assumes there's something to lose.
const showRevertModal = ref(false);

// Collapsed-state for the detail-view properties panel — wraps front
// matter, auto-summary, version archive, and the title/path/mime
// fields. The user gave feedback that the editor surface sits too
// far below the fold; the toggle defaults to collapsed and the state
// is persisted in `sessionStorage` so the user's preference survives
// route changes within the session (cleared per browser tab).
const PROPS_COLLAPSED_KEY = 'documents:propsCollapsed';
const propsCollapsed = ref<boolean>(loadPropsCollapsed());

function loadPropsCollapsed(): boolean {
  try {
    const raw = sessionStorage.getItem(PROPS_COLLAPSED_KEY);
    // Default collapsed — the body editor is what the user actually
    // came here to look at, not the metadata.
    if (raw == null) return true;
    return raw === '1';
  } catch {
    return true;
  }
}

watch(propsCollapsed, (v) => {
  try {
    sessionStorage.setItem(PROPS_COLLAPSED_KEY, v ? '1' : '0');
  } catch {
    // sessionStorage may be unavailable (private mode, locked-down
    // browser); silently ignore — the state still works for the
    // current view, it just doesn't persist.
  }
});

function togglePropsCollapsed(): void {
  propsCollapsed.value = !propsCollapsed.value;
}

// Archive count surfaced from <DocumentArchives> so we can render a
// badge in the always-visible metadata strip — even when the
// properties panel is collapsed and the archive list itself is
// hidden. The child component owns the loading; we just mirror the
// count for the badge.
const archiveCount = ref(0);

function onArchiveCount(n: number): void {
  archiveCount.value = n;
}
const createMode = ref<CreateMode>('inline');
/** Folder the new document will land in (read-only in the dialog —
 *  derived from the current browser path or a prefill). Always ends
 *  with {@code /} or is empty for the project root. */
const createPath = ref('');
/** Filename portion the user types in the dialog. Joined with
 *  {@link createPath} on submit to form the full document path. */
const createName = ref('');
const createTitle = ref('');
const createTagsRaw = ref('');
const createMime = ref('text/markdown');
const createContent = ref('');
const createKind = ref<string>('');
const createFiles = ref<File[]>([]);
const createError = ref<string | null>(null);
const creating = ref(false);

// Tracks the most recently auto-generated stub. Only when the
// user-visible content matches this value (or is empty) do we
// overwrite it on subsequent kind/mime changes — anything the user
// has typed manually stays untouched.
let lastGeneratedStub = '';

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
// Group labels are localised per `documents.mime.*`; the option
// labels themselves are filename-bound (`Markdown (.md)`) and stay
// untranslated — they're recognisable across languages.
const createMimeOptions = computed(() => {
  const docGroup = t('documents.mime.groupDoc');
  const codeGroup = t('documents.mime.groupCode');
  const webGroup = t('documents.mime.groupWeb');
  return [
    { value: 'text/markdown', label: 'Markdown (.md)', group: docGroup },
    { value: 'text/plain', label: 'Plain text (.txt)', group: docGroup },
    { value: 'application/json', label: 'JSON', group: docGroup },
    { value: 'application/yaml', label: 'YAML', group: docGroup },
    { value: 'application/xml', label: 'XML', group: docGroup },
    { value: 'application/javascript', label: 'JavaScript (.js)', group: codeGroup },
    { value: 'application/typescript', label: 'TypeScript (.ts)', group: codeGroup },
    { value: 'text/x-python', label: 'Python (.py)', group: codeGroup },
    { value: 'application/x-sh', label: 'Bash / Shell (.sh)', group: codeGroup },
    { value: 'text/x-r', label: 'R (.r)', group: codeGroup },
    { value: 'text/x-java-source', label: 'Java (.java)', group: codeGroup },
    { value: 'application/sql', label: 'SQL', group: codeGroup },
    { value: 'text/html', label: 'HTML', group: webGroup },
    { value: 'text/css', label: 'CSS', group: webGroup },
  ];
});

// Mime-type options used by the editor's "change mime type" dropdown.
// Same list as the create form plus a sticky entry for the current
// value when it falls outside the canonical set (e.g. application/pdf
// for an upload, or a custom mime someone set via the API). Without
// this fall-back the dropdown would silently swap the value to the
// first option on render — a footgun for binary docs.
const editMimeOptions = computed(() => {
  const base = createMimeOptions.value;
  const current = editMimeType.value;
  if (!current) return base;
  if (base.some(o => o.value === current)) return base;
  const otherGroup = t('documents.mime.groupOther');
  return [...base, { value: current, label: current, group: otherGroup }];
});

/** Default landing scope: every new project opens inside the
 *  `documents/` folder (mirrors DocumentService.DOCUMENTS_FOLDER_PREFIX
 *  on the server). Trash and system folders (`_bin/`, `_vance/`,
 *  `_chatbox/`, `_slart/`) stay out of the way unless the user clicks
 *  "All" or types a `_*` prefix into the path input. */
const DEFAULT_PATH_PREFIX = 'documents/';

onMounted(async () => {
  await projectsState.reload();
  // Restore deep-link state from the URL. URL is the source of truth
  // (reload-friendly without storage keys). On a fresh tab with no
  // hints we fall back to the first project + DEFAULT_PATH_PREFIX.
  const params = new URLSearchParams(window.location.search);
  const queryProject = params.get('projectId');
  const queryPath = params.get('path');
  const queryDoc = params.get('documentId');
  if (queryProject && projectsState.projects.value.some((p) => p.name === queryProject)) {
    selectedProjectId.value = queryProject;
  } else if (projectsState.projects.value.length > 0) {
    selectedProjectId.value = projectsState.projects.value[0].name;
  }
  if (selectedProjectId.value) {
    docsState.pathPrefix.value = queryPath ?? DEFAULT_PATH_PREFIX;
    await Promise.all([
      docsState.loadPage(selectedProjectId.value, 0, docsState.pathPrefix.value),
      docsState.loadFolders(selectedProjectId.value),
      docsState.loadKinds(selectedProjectId.value),
    ]);
  }
  if (queryDoc) {
    await docsState.loadOne(queryDoc);
    fillEditor();
  }
  window.addEventListener('popstate', onPopstate);
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

watch(selectedProjectId, async (next, prev) => {
  if (!next) return;
  // Initial bind in {@link onMounted}: project comes from the URL
  // and the loader there has already honored {@code ?path=…} and
  // {@code ?documentId=…}. Re-running this watcher would clobber
  // those query params with {@link DEFAULT_PATH_PREFIX} — which is
  // exactly what made browser-Back from /app.html drop the user
  // back into the project root instead of the folder they came
  // from. Only run the reset logic for user-initiated project
  // switches (prev was a non-null project name).
  if (prev == null) return;
  pushQueryParams({
    projectId: next,
    path: DEFAULT_PATH_PREFIX,
    documentId: null,
  });
  docsState.clearSelection();
  // Reset filters on project switch — folder/kind lists belong to
  // the new project and the previous filters won't match anyway.
  // Land inside documents/ by default so the user-content view is
  // the first thing they see.
  docsState.pathPrefix.value = DEFAULT_PATH_PREFIX;
  docsState.kindFilter.value = '';
  await Promise.all([
    docsState.loadPage(next, 0, DEFAULT_PATH_PREFIX, ''),
    docsState.loadFolders(next),
    docsState.loadKinds(next),
  ]);
});

/**
 * Apply the path-filter input. Debounced via a small timeout so
 * typing into the combobox doesn't fire one request per keystroke;
 * pressing Enter or selecting a datalist option commits immediately.
 *
 * <p>Pushes the new path to browser history (pushState) so back/
 * forward step through folder navigation. The selected document is
 * cleared at the same time — moving to a different folder while a
 * doc is open would otherwise leave the URL with a stale documentId.
 */
let filterTimer: ReturnType<typeof setTimeout> | null = null;
function applyPathFilter(prefix: string, immediate = false): void {
  const project = selectedProjectId.value;
  if (!project) return;
  if (filterTimer) clearTimeout(filterTimer);
  const fire = () => {
    docsState.clearSelection();
    pushQueryParams({
      path: prefix || null,
      documentId: null,
    });
    void docsState.loadPage(project, 0, prefix);
  };
  if (immediate) fire();
  else filterTimer = setTimeout(fire, 300);
}

/**
 * Sync internal state from the current URL. Triggered by browser
 * back/forward (popstate). Compares against the live state and only
 * touches what changed, so popstate doesn't cascade into another
 * pushState via the regular watchers (their dedup checks handle the
 * residual no-op as well).
 */
async function onPopstate(): Promise<void> {
  const params = new URLSearchParams(window.location.search);
  const urlProjectId = params.get('projectId');
  const urlPath = params.get('path') ?? '';
  const urlDocumentId = params.get('documentId');

  // Project switch from URL.
  if (urlProjectId && urlProjectId !== selectedProjectId.value
      && projectsState.projects.value.some((p) => p.name === urlProjectId)) {
    selectedProjectId.value = urlProjectId;
    // The selectedProjectId watcher takes care of loading; but it
    // also overrides pathPrefix to DEFAULT_PATH_PREFIX, so we set
    // the URL-driven path back after a microtask. Plus close any
    // open document — the project-switch watcher clears it too but
    // we want to be deterministic.
    docsState.clearSelection();
    // Wait for the project-switch watcher to finish before applying
    // the path. The watcher is async so a microtask isn't enough —
    // schedule via setTimeout to land after the load chain settles.
    setTimeout(() => {
      const project = selectedProjectId.value;
      if (project && urlPath !== docsState.pathPrefix.value) {
        docsState.pathPrefix.value = urlPath;
        void docsState.loadPage(project, 0, urlPath);
      }
      if (urlDocumentId && urlDocumentId !== docsState.selected.value?.id) {
        void docsState.loadOne(urlDocumentId);
      }
    }, 0);
    return;
  }

  // Same project, possibly different path.
  if (urlPath !== docsState.pathPrefix.value && selectedProjectId.value) {
    docsState.pathPrefix.value = urlPath;
    docsState.clearSelection();
    await docsState.loadPage(selectedProjectId.value, 0, urlPath);
  }

  // Document selection.
  if (urlDocumentId && urlDocumentId !== docsState.selected.value?.id) {
    await docsState.loadOne(urlDocumentId);
    fillEditor();
  } else if (!urlDocumentId && docsState.selected.value) {
    docsState.clearSelection();
  }
}

watch(
  () => docsState.selected.value?.id ?? null,
  (id) => {
    pushQueryParams({ documentId: id });
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
      : t('documents.ungrouped');
    return {
      value: p.name,
      label: p.title?.trim() || p.name,
      group: groupLabel,
    };
  });
});

// ──────────────── Project sidebar ────────────────
//
// The grouped, filterable project list now lives in the shared
// {@link ProjectListSidebar} component — see template below for
// wiring. Only the focus-zone ref stays here; everything else
// (filter state, grouping, sort) is internal to the component.

const focusZone = ref<FocusZone>('main');

/**
 * Reload the tenant projects list after a successful create from
 * the sidebar (Add Group / Add Project). For new projects we also
 * select them so the user lands right in the freshly created
 * workspace; groups don't have a selection in the documents view.
 */
async function onProjectListDataChanged(
  payload: { kind: 'group' | 'project'; name: string },
): Promise<void> {
  await projectsState.reload();
  if (payload.kind === 'project') {
    selectedProjectId.value = payload.name;
  }
}

// ──────────────── Main sub-header: search + back ────────────────

/**
 * Free-text search needle — forwarded to the folder REST endpoint
 * as the {@code search} query param. Server-side filter against
 * file path/title and folder names (case-insensitive substring).
 * Debounced so each keystroke doesn't fire a request.
 */
const documentFilter = ref('');

let searchDebounceTimer: ReturnType<typeof setTimeout> | null = null;
watch(documentFilter, (next) => {
  if (searchDebounceTimer !== null) clearTimeout(searchDebounceTimer);
  searchDebounceTimer = setTimeout(() => {
    const project = selectedProjectId.value;
    if (!project) return;
    void docsState.loadPage(project, 0, undefined, undefined, next);
  }, 250);
});

/**
 * Walk one path segment up: {@code documents/notes/foo/} →
 * {@code documents/notes/} → {@code documents/} → {@code ''}. Stops
 * at empty (button is disabled at that point).
 */
function pathSegmentBack(): void {
  const current = docsState.pathPrefix.value;
  if (!current) return;
  const noSlash = current.endsWith('/') ? current.slice(0, -1) : current;
  const lastSlash = noSlash.lastIndexOf('/');
  const next = lastSlash >= 0 ? noSlash.slice(0, lastSlash + 1) : '';
  applyPathFilter(next, true);
}

/**
 * Descend one level: append the clicked folder name to the current
 * pathPrefix (with the trailing slash that the server expects).
 */
function navigateIntoFolder(folder: string): void {
  const base = docsState.pathPrefix.value;
  const baseSlashed = base === '' || base.endsWith('/') ? base : base + '/';
  applyPathFilter(baseSlashed + folder + '/', true);
}

// ──────────────── New-folder dialog ────────────────
//
// Folders aren't first-class entities in storage — the server derives
// them from document paths. "Add folder" therefore just navigates the
// browser into the chosen subpath; the folder materialises in Mongo
// as soon as the user creates the first file in it. Multi-segment
// inputs like {@code foo/bar} are supported (lands two levels deep).

const showNewFolderModal = ref(false);
const newFolderName = ref('');
const newFolderError = ref<string | null>(null);

function openNewFolderModal(): void {
  newFolderName.value = '';
  newFolderError.value = null;
  showNewFolderModal.value = true;
}

function submitNewFolder(): void {
  const raw = newFolderName.value.trim();
  if (!raw) {
    newFolderError.value = t('documents.newFolderDialog.nameRequired');
    return;
  }
  // Normalise: strip leading/trailing slashes, collapse double
  // slashes. Multi-segment input ("foo/bar") jumps two levels deep
  // in one go.
  const normalised = raw.replace(/^\/+|\/+$/g, '').replace(/\/+/g, '/');
  if (!normalised) {
    newFolderError.value = t('documents.newFolderDialog.nameRequired');
    return;
  }
  const base = docsState.pathPrefix.value;
  const baseSlashed = base === '' || base.endsWith('/') ? base : base + '/';
  showNewFolderModal.value = false;
  applyPathFilter(baseSlashed + normalised + '/', true);
}

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

/** {@code true} for documents that the picker would route to a
 *  dedicated app editor (Kanban, Calendar, …) instead of the
 *  generic file editor. Used to surface the "edit as file" escape
 *  hatch in the list row. */
function isAppDocument(doc: DocumentSummary): boolean {
  return doc.kind === 'application' && !!doc.path?.endsWith('/_app.yaml');
}

async function openDocument(doc: DocumentSummary): Promise<void> {
  if (!doc.id) return;
  // App-manifest files redirect to the dedicated app editor — the
  // Kanban board / Calendar planner / etc. don't render in the
  // generic Document viewer at all.
  if (isAppDocument(doc)) {
    window.location.assign(`/app.html?documentId=${encodeURIComponent(doc.id)}`);
    return;
  }
  await openDocumentInEditor(doc);
}

/** Bypass the app-editor redirect and load the document into the
 *  generic file editor. Wired to the per-row "edit as file" button
 *  for {@link isAppDocument}-matching rows so the user can still
 *  hand-edit a malformed {@code _app.yaml}. */
async function openDocumentInEditor(doc: DocumentSummary): Promise<void> {
  if (!doc.id) return;
  await docsState.loadOne(doc.id);
  fillEditor();
}

function fillEditor(): void {
  const sel = docsState.selected.value;
  editTitle.value = sel?.title ?? '';
  editPath.value = sel?.path ?? '';
  editMimeType.value = sel?.mimeType ?? '';
  editInlineText.value = sel?.inlineText ?? '';
  editAutoSummary.value = sel?.autoSummary ?? false;
  editSummaryDirty.value = sel?.summaryDirty ?? false;
  editSummary.value = sel?.summary ?? '';
  summarySaveMessage.value = null;
  editRagEnabled.value = sel?.ragEnabled == null ? 'auto' : (sel.ragEnabled ? 'on' : 'off');
  editError.value = null;
  // Switching documents resets the editor mode to the kind-aware
  // default — `sheet`, `graph`, `records`, `mindmap`, `list`,
  // `tree`, then `raw` for everything else.
  if (isSheetDocument.value) contentTab.value = 'sheet';
  else if (isChartDocument.value) contentTab.value = 'chart';
  else if (isGraphDocument.value) contentTab.value = 'graph';
  else if (isRecordsDocument.value) contentTab.value = 'records';
  else if (isListDocument.value) contentTab.value = 'list';
  else if (isChecklistDocument.value) contentTab.value = 'checklist';
  else if (isMindmapDocument.value) contentTab.value = 'mindmap';
  else if (isTreeDocument.value) contentTab.value = 'tree';
  else if (isSlidesDocument.value) contentTab.value = 'slides';
  else if (isDiagramDocument.value) contentTab.value = 'diagram';
  else if (isCalendarDocument.value) contentTab.value = 'calendar';
  // Markdown lands on Preview first — the user opened a doc to read
  // it, editing is one click away on the Raw tab.
  else if (isMarkdownDocument.value) contentTab.value = 'preview';
  else contentTab.value = 'raw';
}

function onArchiveRestored(restored: DocumentDto): void {
  docsState.selected.value = restored;
  const idx = docsState.items.value.findIndex((d) => d.id === restored.id);
  if (idx >= 0) {
    docsState.items.value[idx] = {
      ...docsState.items.value[idx],
      title: restored.title,
      tags: restored.tags,
      size: restored.size,
      path: restored.path,
      name: restored.name,
    };
  }
  fillEditor();
}

// ─── List-document tab toggle ───────────────────────────────────────
//
// Documents whose `kind` is `list` and whose mime is one of md / json /
// yaml get an additional "List" tab on top of the inline-text editor.
// In v1 the list tab is read-only; the user keeps editing through the
// raw editor. The tab is in-memory only — switching does not hit the
// server, the body just gets reparsed each time.

type ContentTab =
  | 'raw'
  | 'preview'
  | 'list'
  | 'checklist'
  | 'tree'
  | 'mindmap'
  | 'records'
  | 'graph'
  | 'chart'
  | 'sheet'
  | 'slides'
  | 'diagram'
  | 'calendar'
  | 'office';
const contentTab = ref<ContentTab>('raw');

// Counter bumped whenever the user comes BACK from the office-edit
// tab. Used as a :key on the office-document preview so the browser
// remounts and re-fetches the content — the office-server's save
// callback has run by the time the user switches tabs, but the
// preview pane otherwise has no way to know its bytes are stale.
const previewReloadCounter = ref(0);
watch(contentTab, (next, prev) => {
  if (prev === 'office' && next !== 'office') {
    previewReloadCounter.value += 1;
  }
});

const isListDocument = computed<boolean>(() => {
  const sel = docsState.selected.value;
  if (!sel?.inline) return false;
  if ((sel.kind ?? '').toLowerCase() !== 'list') return false;
  return isListMime(sel.mimeType);
});

const isChecklistDocument = computed<boolean>(() => {
  const sel = docsState.selected.value;
  if (!sel?.inline) return false;
  if ((sel.kind ?? '').toLowerCase() !== 'checklist') return false;
  return isChecklistMime(sel.mimeType);
});

const isTreeDocument = computed<boolean>(() => {
  const sel = docsState.selected.value;
  if (!sel?.inline) return false;
  if ((sel.kind ?? '').toLowerCase() !== 'tree') return false;
  return isTreeMime(sel.mimeType);
});

// Mindmap documents reuse the tree codec — same mime types, same
// per-item shape — and add a Mindmap render tab on top of the
// tree editor. See specification/doc-kind-mindmap.md §5.
const isMindmapDocument = computed<boolean>(() => {
  const sel = docsState.selected.value;
  if (!sel?.inline) return false;
  if ((sel.kind ?? '').toLowerCase() !== 'mindmap') return false;
  return isTreeMime(sel.mimeType);
});

const isRecordsDocument = computed<boolean>(() => {
  const sel = docsState.selected.value;
  if (!sel?.inline) return false;
  if ((sel.kind ?? '').toLowerCase() !== 'records') return false;
  return isRecordsMime(sel.mimeType);
});

// Graph documents: kind: graph + json/yaml only — markdown bodies
// fall back to the Raw editor (spec doc-kind-graph.md §3.3).
const isGraphDocument = computed<boolean>(() => {
  const sel = docsState.selected.value;
  if (!sel?.inline) return false;
  if ((sel.kind ?? '').toLowerCase() !== 'graph') return false;
  return isGraphMime(sel.mimeType);
});

// Chart documents: kind: chart + json/yaml only — markdown bodies
// fall back to the Raw editor (spec doc-kind-chart.md §3.3).
const isChartDocument = computed<boolean>(() => {
  const sel = docsState.selected.value;
  if (!sel?.inline) return false;
  if ((sel.kind ?? '').toLowerCase() !== 'chart') return false;
  return isChartMime(sel.mimeType);
});

// Sheet documents: kind: sheet + json/yaml only — markdown bodies
// fall back to the Raw editor (spec doc-kind-sheet.md §3.3).
const isSheetDocument = computed<boolean>(() => {
  const sel = docsState.selected.value;
  if (!sel?.inline) return false;
  if ((sel.kind ?? '').toLowerCase() !== 'sheet') return false;
  return isSheetMime(sel.mimeType);
});

// Slides documents: kind: slides + md/json/yaml. v1 is read-only —
// edit happens in the Raw tab (spec doc-kind-slides.md §5.3).
const isSlidesDocument = computed<boolean>(() => {
  const sel = docsState.selected.value;
  if (!sel?.inline) return false;
  if ((sel.kind ?? '').toLowerCase() !== 'slides') return false;
  return isSlidesMime(sel.mimeType);
});

// Diagram documents: kind: diagram + md/json/yaml. Renderer is Mermaid;
// edit happens in the Raw tab (spec doc-kind-diagram.md §5).
const isDiagramDocument = computed<boolean>(() => {
  const sel = docsState.selected.value;
  if (!sel?.inline) return false;
  if ((sel.kind ?? '').toLowerCase() !== 'diagram') return false;
  return isDiagramMime(sel.mimeType);
});

// Calendar documents: kind: calendar + json/yaml. v1 is read-only
// (month + agenda); edits go through the Raw tab. Spec doc-kind-calendar.md.
//
// Registry-driven: the Kind comes from `@vance/kind-registry` (host
// built-in for now, addon-contributed once Calendar moves). The host
// looks up the entry once and stays generic — no Calendar-specific
// imports in this file.
const calendarKind = computed<KindEntry | undefined>(() => resolveKind('calendar'));
const isCalendarDocument = computed<boolean>(() => {
  const sel = docsState.selected.value;
  const kind = calendarKind.value;
  if (!sel?.inline || !kind) return false;
  return kind.matches(sel.kind, sel.mimeType);
});

// Markdown documents get a Preview / Raw tab pair. Preview goes
// through {@code MarkdownView} (same renderer as chat bubbles,
// inbox previews, help drawer) so all the inline-kind-box dispatch
// and `vance:` link handling come along for free. Raw stays the
// {@code CodeEditor} fallback. Other kinded markdown docs (list,
// tree, mindmap, …) are caught by their own branches above; this
// covers everything else with a markdown mime type.
// Office-editable documents — DOCX / XLSX served as binary from
// storage. The OfficeEditor component asks the brain whether
// office.* is actually configured (server-side check) and renders
// a "not-configured" placeholder when not, so this flag only
// gates the *tab* visibility, not the actual editor availability.
const isOfficeEditableDocument = computed<boolean>(() => {
  const sel = docsState.selected.value;
  if (!sel) return false;
  const mime = (sel.mimeType ?? '').toLowerCase();
  return mime === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
      || mime === 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
});

const isMarkdownDocument = computed<boolean>(() => {
  const sel = docsState.selected.value;
  if (!sel?.inline) return false;
  const mime = (sel.mimeType ?? '').toLowerCase();
  if (mime !== 'text/markdown' && mime !== 'text/x-markdown') return false;
  // Don't claim the doc when a kind-specific branch will handle it —
  // those have their own tab pairs and shouldn't double up.
  return !isListDocument.value
    && !isChecklistDocument.value
    && !isTreeDocument.value
    && !isMindmapDocument.value
    && !isRecordsDocument.value
    && !isGraphDocument.value
    && !isChartDocument.value
    && !isSheetDocument.value
    && !isSlidesDocument.value
    && !isDiagramDocument.value;
});

/**
 * Follow-up extension options for the Markdown editor. The CodeEditor
 * reads {@code followUp} once at construction time; we recompute when
 * the project changes so a fresh editor mounts with the right binding
 * (the {@code v-if="isMarkdownDocument"} branch in the template
 * re-mounts whenever the selection toggles between Markdown and
 * non-Markdown). For everything else this stays {@code null}.
 *
 * <p>The fetch callback wraps {@code POST /brain/{tenant}/follow-up/
 * {project}} in edit mode (cursor set) — the server returns at most
 * one suggestion (we ask for {@code count: 1}); we surface its text
 * to the CodeMirror tooltip. Errors are swallowed: the ghost
 * suggestion is a nicety, not a blocking feature.
 */
const markdownFollowUp = computed<FollowUpExtensionOptions | null>(() => {
  const project = selectedProjectId.value;
  if (!project) return null;
  return {
    acceptHint: t('documents.followUp.acceptHint'),
    fetch: async (text, cursor) => {
      try {
        const body: FollowUpRequestDto = {
          text,
          cursor,
          count: 1,
          mode: 'text-editor',
        };
        const resp = await brainFetch<FollowUpResponseDto>(
          'POST',
          `follow-up/${encodeURIComponent(project)}`,
          { body },
        );
        const first = resp.suggestions?.[0]?.text?.trim() ?? '';
        return first.length > 0 ? first : null;
      } catch {
        return null;
      }
    },
  };
});

// Trash convention: documents under `_bin/` are already in the
// project's trash folder (mirrors DocumentService.TRASH_FOLDER_PREFIX
// on the server). The DELETE endpoint dispatches on this — first
// click moves regular docs to the bin, a second click on the bin
// entry hard-deletes it. The UI swaps button label and confirmation
// text to match.
const TRASH_PREFIX = '_bin/';
const isSelectedInTrash = computed<boolean>(() =>
  (docsState.selected.value?.path ?? '').startsWith(TRASH_PREFIX),
);

interface ParsedList {
  doc: ListDocument | null;
  error: string | null;
}

const parsedList = computed<ParsedList>(() => {
  if (!isListDocument.value) return { doc: null, error: null };
  try {
    const sel = docsState.selected.value;
    const doc = parseList(editInlineText.value, sel?.mimeType ?? '');
    return { doc, error: null };
  } catch (e) {
    if (e instanceof ListCodecError) {
      return { doc: null, error: e.message };
    }
    return { doc: null, error: e instanceof Error ? e.message : String(e) };
  }
});

interface ParsedChecklist {
  doc: ChecklistDocument | null;
  error: string | null;
}

const parsedChecklist = computed<ParsedChecklist>(() => {
  if (!isChecklistDocument.value) return { doc: null, error: null };
  try {
    const sel = docsState.selected.value;
    const doc = parseChecklist(editInlineText.value, sel?.mimeType ?? '');
    return { doc, error: null };
  } catch (e) {
    if (e instanceof ChecklistCodecError) {
      return { doc: null, error: e.message };
    }
    return { doc: null, error: e instanceof Error ? e.message : String(e) };
  }
});

interface ParsedTree {
  doc: TreeDocument | null;
  error: string | null;
}

const parsedTree = computed<ParsedTree>(() => {
  if (!isTreeDocument.value && !isMindmapDocument.value) {
    return { doc: null, error: null };
  }
  try {
    const sel = docsState.selected.value;
    const doc = parseTree(editInlineText.value, sel?.mimeType ?? '');
    return { doc, error: null };
  } catch (e) {
    if (e instanceof TreeCodecError) {
      return { doc: null, error: e.message };
    }
    return { doc: null, error: e instanceof Error ? e.message : String(e) };
  }
});

interface ParsedRecords {
  doc: RecordsDocument | null;
  error: string | null;
}

const parsedRecords = computed<ParsedRecords>(() => {
  if (!isRecordsDocument.value) return { doc: null, error: null };
  try {
    const sel = docsState.selected.value;
    const doc = parseRecords(editInlineText.value, sel?.mimeType ?? '');
    return { doc, error: null };
  } catch (e) {
    if (e instanceof RecordsCodecError) {
      return { doc: null, error: e.message };
    }
    return { doc: null, error: e instanceof Error ? e.message : String(e) };
  }
});

interface ParsedGraph {
  doc: GraphDocument | null;
  error: string | null;
}

const parsedGraph = computed<ParsedGraph>(() => {
  if (!isGraphDocument.value) return { doc: null, error: null };
  try {
    const sel = docsState.selected.value;
    const doc = parseGraph(editInlineText.value, sel?.mimeType ?? '');
    return { doc, error: null };
  } catch (e) {
    if (e instanceof GraphCodecError) {
      return { doc: null, error: e.message };
    }
    return { doc: null, error: e instanceof Error ? e.message : String(e) };
  }
});

interface ParsedChart {
  doc: ChartDocument | null;
  error: string | null;
}

const parsedChart = computed<ParsedChart>(() => {
  if (!isChartDocument.value) return { doc: null, error: null };
  try {
    const sel = docsState.selected.value;
    const doc = parseChart(editInlineText.value, sel?.mimeType ?? '');
    return { doc, error: null };
  } catch (e) {
    if (e instanceof ChartCodecError) {
      return { doc: null, error: e.message };
    }
    return { doc: null, error: e instanceof Error ? e.message : String(e) };
  }
});

interface ParsedSheet {
  doc: SheetDocument | null;
  error: string | null;
}

const parsedSheet = computed<ParsedSheet>(() => {
  if (!isSheetDocument.value) return { doc: null, error: null };
  try {
    const sel = docsState.selected.value;
    const doc = parseSheet(editInlineText.value, sel?.mimeType ?? '');
    return { doc, error: null };
  } catch (e) {
    if (e instanceof SheetCodecError) {
      return { doc: null, error: e.message };
    }
    return { doc: null, error: e instanceof Error ? e.message : String(e) };
  }
});

interface ParsedSlides {
  doc: SlidesDocument | null;
  error: string | null;
}

const parsedSlides = computed<ParsedSlides>(() => {
  if (!isSlidesDocument.value) return { doc: null, error: null };
  try {
    const sel = docsState.selected.value;
    const doc = parseSlides(editInlineText.value, sel?.mimeType ?? '');
    return { doc, error: null };
  } catch (e) {
    if (e instanceof SlidesCodecError) {
      return { doc: null, error: e.message };
    }
    return { doc: null, error: e instanceof Error ? e.message : String(e) };
  }
});

interface ParsedDiagram {
  doc: DiagramDocument | null;
  error: string | null;
}

const parsedDiagram = computed<ParsedDiagram>(() => {
  if (!isDiagramDocument.value) return { doc: null, error: null };
  try {
    const sel = docsState.selected.value;
    const doc = parseDiagram(editInlineText.value, sel?.mimeType ?? '');
    return { doc, error: null };
  } catch (e) {
    if (e instanceof DiagramCodecError) {
      return { doc: null, error: e.message };
    }
    return { doc: null, error: e instanceof Error ? e.message : String(e) };
  }
});

interface ParsedCalendar {
  doc: unknown | null;
  error: string | null;
}

const parsedCalendar = computed<ParsedCalendar>(() => {
  const kind = calendarKind.value;
  if (!isCalendarDocument.value || !kind?.parse) return { doc: null, error: null };
  try {
    const sel = docsState.selected.value;
    const doc = kind.parse(editInlineText.value, sel?.mimeType ?? '');
    return { doc, error: null };
  } catch (e) {
    const isCodecErr = kind.isParseError ? kind.isParseError(e) : true;
    if (isCodecErr) {
      return { doc: null, error: e instanceof Error ? e.message : String(e) };
    }
    return { doc: null, error: e instanceof Error ? e.message : String(e) };
  }
});

/**
 * Bridge from the typed list editor back to the raw body. Each
 * mutation in {@code <ListView>} emits a fresh {@link ListDocument};
 * we serialise it in the document's mime type and overwrite the raw
 * editor's text. The existing Save / Apply buttons then write the
 * canonical body to the server unchanged.
 *
 * The Raw editor's text becomes the canonical re-emit on the very
 * first list edit — small whitespace differences against the
 * originally loaded body are expected and intentional.
 */
function onListChanged(updated: ListDocument): void {
  const sel = docsState.selected.value;
  if (!sel?.mimeType) return;
  try {
    editInlineText.value = serializeList(updated, sel.mimeType);
    editError.value = null;
  } catch (e) {
    editError.value = e instanceof Error ? e.message : String(e);
  }
}

/**
 * Bridge from the typed checklist editor back to the raw body. Same
 * pattern as {@link onListChanged} — the editor emits a fresh
 * {@link ChecklistDocument} on every mutation; we serialise into the
 * document's mime type so the existing Save / Apply pathway picks up
 * the canonical body unchanged.
 */
function onChecklistChanged(updated: ChecklistDocument): void {
  const sel = docsState.selected.value;
  if (!sel?.mimeType) return;
  try {
    editInlineText.value = serializeChecklist(updated, sel.mimeType);
    editError.value = null;
  } catch (e) {
    editError.value = e instanceof Error ? e.message : String(e);
  }
}

/**
 * Same bridge as {@link onListChanged} but for tree documents.
 * The {@code <TreeView>} component owns the editor state internally
 * and emits a fresh {@link TreeDocument} on every mutation; we
 * re-serialise into the document's mime type so the existing Save
 * pathway picks up the canonical form unchanged.
 */
function onTreeChanged(updated: TreeDocument): void {
  const sel = docsState.selected.value;
  if (!sel?.mimeType) return;
  try {
    editInlineText.value = serializeTree(updated, sel.mimeType);
    editError.value = null;
  } catch (e) {
    editError.value = e instanceof Error ? e.message : String(e);
  }
}

/**
 * Bridge from the typed records editor back to the raw body. Each
 * mutation in {@code <RecordsView>} emits a fresh
 * {@link RecordsDocument}; we serialise it in the document's mime
 * type and overwrite the raw editor's text. The Save / Apply path
 * then writes the canonical body unchanged.
 */
function onRecordsChanged(updated: RecordsDocument): void {
  const sel = docsState.selected.value;
  if (!sel?.mimeType) return;
  try {
    editInlineText.value = serializeRecords(updated, sel.mimeType);
    editError.value = null;
  } catch (e) {
    editError.value = e instanceof Error ? e.message : String(e);
  }
}

/**
 * Bridge from the typed graph editor back to the raw body. Same
 * pattern as records — the editor emits a fresh
 * {@link GraphDocument}, we serialise into the document's mime
 * type and write into the raw editor's text.
 */
function onGraphChanged(updated: GraphDocument): void {
  const sel = docsState.selected.value;
  if (!sel?.mimeType) return;
  try {
    editInlineText.value = serializeGraph(updated, sel.mimeType);
    editError.value = null;
  } catch (e) {
    editError.value = e instanceof Error ? e.message : String(e);
  }
}

function onSheetChanged(updated: SheetDocument): void {
  const sel = docsState.selected.value;
  if (!sel?.mimeType) return;
  try {
    editInlineText.value = serializeSheet(updated, sel.mimeType);
    editError.value = null;
  } catch (e) {
    editError.value = e instanceof Error ? e.message : String(e);
  }
}

function onChartChanged(updated: ChartDocument): void {
  const sel = docsState.selected.value;
  if (!sel?.mimeType) return;
  try {
    editInlineText.value = serializeChart(updated, sel.mimeType);
    editError.value = null;
  } catch (e) {
    editError.value = e instanceof Error ? e.message : String(e);
  }
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
  { prefix: '_vance/recipes/', resource: 'recipe-field-docs.md' },
  { prefix: '_vance/strategies/', resource: 'strategy-field-docs.md' },
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
 * Wrap {@link backToList} with a dirty-state check. When the user
 * has unsaved edits, open the discard-confirm modal instead of
 * leaving immediately. The modal's three actions resolve into
 * apply-and-leave / discard / stay.
 */
function requestBackToList(): void {
  if (isDirty.value) {
    showDiscardModal.value = true;
    return;
  }
  backToList();
}

/** Discard-modal action: drop the edits and return to the list. */
function discardAndBack(): void {
  showDiscardModal.value = false;
  backToList();
}

/**
 * Footer "discard changes" action: open the revert-confirm modal
 * (no-op when there are no unsaved edits — the button is hidden in
 * that case but guard anyway).
 */
function requestRevert(): void {
  if (!isDirty.value) return;
  showRevertModal.value = true;
}

/**
 * Re-fetches the selected document from the server and resets every
 * edit field via {@link fillEditor}. Used as the confirmed action
 * of the revert modal. On error keeps the modal closed and lets the
 * inline {@code docsState.error} surface the failure.
 */
async function revertChanges(): Promise<void> {
  showRevertModal.value = false;
  const sel = docsState.selected.value;
  if (!sel) return;
  await docsState.loadOne(sel.id);
  fillEditor();
}

/** Discard-modal action: persist the edits, then return to the list
 *  if the server accepted them. On error stay on the detail view so
 *  the user can read the message (mirrors {@link save}). */
async function saveAndBack(): Promise<void> {
  const ok = await apply();
  if (ok) {
    showDiscardModal.value = false;
    backToList();
  } else {
    // Apply failed — close the modal so the user can see the inline
    // error and retry; the edits are still in the form.
    showDiscardModal.value = false;
  }
}

/**
 * Browser-level page-unload guard. The browser ignores any custom
 * text these days (Chrome / Firefox / Safari all show a generic
 * "leave site?" prompt), but the listener still has to call
 * {@code preventDefault} and set {@code returnValue} to opt in.
 * Only attached while there is an active dirty selection — saves
 * the user from seeing the prompt after they've already left the
 * editor.
 */
function onBeforeUnload(event: BeforeUnloadEvent): void {
  if (!isDirty.value) return;
  event.preventDefault();
  // Legacy requirement: setting returnValue triggers the prompt in
  // older browsers that don't yet listen for preventDefault alone.
  event.returnValue = '';
}
onMounted(() => window.addEventListener('beforeunload', onBeforeUnload));
onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', onBeforeUnload);
  window.removeEventListener('popstate', onPopstate);
});

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
  // Path stays read-only in the dialog. If a prefill brings a
  // slash-bearing path (e.g. inbox→document handover) we split it
  // into folder + filename so the user still sees the destination.
  // Without prefill we land on the current browse path.
  const prefillPath = prefill?.path ?? '';
  const lastSlash = prefillPath.lastIndexOf('/');
  if (prefillPath && lastSlash >= 0) {
    createPath.value = prefillPath.substring(0, lastSlash + 1);
    createName.value = prefillPath.substring(lastSlash + 1);
  } else if (prefillPath) {
    createPath.value = docsState.pathPrefix.value;
    createName.value = prefillPath;
  } else {
    createPath.value = docsState.pathPrefix.value;
    createName.value = '';
  }
  createTitle.value = prefill?.title ?? '';
  createTagsRaw.value = '';
  createMime.value = prefill?.mimeType ?? 'text/markdown';
  createContent.value = prefill?.content ?? '';
  createKind.value = '';
  lastGeneratedStub = '';
  createFiles.value = [];
  createError.value = null;
  uploadProgress.value = [];
  showCreateModal.value = true;
}

/**
 * Kinds the user can pick when creating a new inline document. Each
 * one optionally pre-fills a starter body in the chosen mime type.
 * Schema-less kinds (mindmap/graph/...) only seed the {@code kind:}
 * header so the document is recognised by the server's
 * {@code DocumentHeader} pipeline; the body is left blank for the
 * user to fill in.
 *
 * Order picked to surface the kinds we already render specialised
 * editors for first (`list`, later `tree`). Labels stay literal —
 * they are domain tokens, not localisable noise.
 */
const KIND_CREATE_OPTIONS = [
  'list', 'checklist', 'tree', 'text', 'mindmap', 'graph', 'chart', 'sheet', 'slides', 'diagram', 'calendar', 'application', 'data', 'records', 'schema',
] as const;

const kindCreateOptions = computed(() => [
  { value: '', label: t('documents.create.kindNone') },
  ...KIND_CREATE_OPTIONS.map((k) => ({ value: k, label: k })),
]);

/**
 * Generate a starter body for the chosen {@code kind} in the chosen
 * {@code mime} type. Returns an empty string when no template applies
 * — caller treats that as „leave the editor blank".
 *
 * For kinds with a defined schema (currently only {@code list}, soon
 * {@code tree}) we emit a minimal example so the user can see the
 * expected shape. For schema-less kinds we emit just the header so
 * the server still mirrors {@code kind} on save.
 */
function buildKindStub(kind: string, mime: string): string {
  if (!kind) return '';
  const isMd = mime === 'text/markdown' || mime === 'text/x-markdown';
  const isJson = mime === 'application/json';
  const isYaml = mime === 'application/yaml'
    || mime === 'application/x-yaml'
    || mime === 'text/yaml';

  if (kind === 'list') {
    if (isMd) return '---\nkind: list\n---\n- item 1\n- item 2\n';
    if (isJson) return '{\n  "$meta": { "kind": "list" },\n  "items": [\n    { "text": "item 1" },\n    { "text": "item 2" }\n  ]\n}\n';
    if (isYaml) return '$meta:\n  kind: list\nitems:\n  - text: item 1\n  - text: item 2\n';
  }
  if (kind === 'checklist') {
    if (isMd) return '---\nkind: checklist\n---\n- [ ] open task\n- [x] done task\n- [~] in progress task\n';
    if (isJson) return '{\n  "$meta": { "kind": "checklist" },\n  "items": [\n    { "text": "open task" },\n    { "text": "done task", "status": "done" },\n    { "text": "in progress task", "status": "in_progress" }\n  ]\n}\n';
    if (isYaml) return '$meta:\n  kind: checklist\nitems:\n  - text: open task\n  - text: done task\n    status: done\n  - text: in progress task\n    status: in_progress\n';
  }
  if (kind === 'tree') {
    if (isMd) return '---\nkind: tree\n---\n- parent\n  - child\n';
    if (isJson) return '{\n  "$meta": { "kind": "tree" },\n  "items": [\n    { "text": "parent", "children": [\n      { "text": "child", "children": [] }\n    ]}\n  ]\n}\n';
    if (isYaml) return '$meta:\n  kind: tree\nitems:\n  - text: parent\n    children:\n      - text: child\n        children: []\n';
  }
  if (kind === 'mindmap') {
    if (isMd) return '---\nkind: mindmap\n---\n- root topic\n  - branch one\n  - branch two\n';
    if (isJson) return '{\n  "$meta": { "kind": "mindmap" },\n  "items": [\n    { "text": "root topic", "children": [\n      { "text": "branch one", "children": [] },\n      { "text": "branch two", "children": [] }\n    ]}\n  ]\n}\n';
    if (isYaml) return '$meta:\n  kind: mindmap\nitems:\n  - text: root topic\n    children:\n      - text: branch one\n        children: []\n      - text: branch two\n        children: []\n';
  }
  if (kind === 'records') {
    if (isMd) return '---\nkind: records\nschema: name, email, role\n---\n- Alice, alice@example.com, admin\n- Bob, bob@example.com, user\n';
    if (isJson) return '{\n  "$meta": { "kind": "records" },\n  "schema": ["name", "email", "role"],\n  "items": [\n    { "name": "Alice", "email": "alice@example.com", "role": "admin" },\n    { "name": "Bob", "email": "bob@example.com", "role": "user" }\n  ]\n}\n';
    if (isYaml) return '$meta:\n  kind: records\nschema: [name, email, role]\nitems:\n  - name: Alice\n    email: alice@example.com\n    role: admin\n  - name: Bob\n    email: bob@example.com\n    role: user\n';
  }
  if (kind === 'graph') {
    // Markdown isn't supported for graphs (spec §3.3); fall through
    // to the schema-less branch below if md is picked, so the user
    // gets just the header and a hint to switch mime type via Raw.
    if (isJson) return '{\n  "$meta": { "kind": "graph" },\n  "graph": { "directed": true },\n  "nodes": [\n    { "id": "alice", "label": "Alice" },\n    { "id": "bob", "label": "Bob" }\n  ],\n  "edges": [\n    { "source": "alice", "target": "bob" }\n  ]\n}\n';
    if (isYaml) return '$meta:\n  kind: graph\ngraph:\n  directed: true\nnodes:\n  - id: alice\n    label: Alice\n  - id: bob\n    label: Bob\nedges:\n  - source: alice\n    target: bob\n';
  }
  if (kind === 'chart') {
    // Markdown isn't supported for charts (spec §3.3) — same fallback
    // behaviour as graph.
    if (isJson) return '{\n  "$meta": { "kind": "chart" },\n  "chart": { "chartType": "line", "title": "New Chart" },\n  "xAxis": { "type": "category" },\n  "yAxis": { "type": "value" },\n  "series": [\n    { "name": "Series 1", "data": [\n      { "x": "A", "y": 10 },\n      { "x": "B", "y": 20 },\n      { "x": "C", "y": 15 }\n    ] }\n  ]\n}\n';
    if (isYaml) return '$meta:\n  kind: chart\nchart:\n  chartType: line\n  title: New Chart\nxAxis:\n  type: category\nyAxis:\n  type: value\nseries:\n  - name: Series 1\n    data:\n      - { x: A, y: 10 }\n      - { x: B, y: 20 }\n      - { x: C, y: 15 }\n';
  }
  if (kind === 'slides') {
    if (isMd) return '---\nkind: slides\nslides:\n  theme: default\n  aspect: "16:9"\n  paginate: true\n---\n\n# First slide\n\nWelcome to your deck.\n\n---\n\n## Second slide\n\n- bullet one\n- bullet two\n';
    if (isJson) return '{\n  "$meta": { "kind": "slides" },\n  "slides": { "theme": "default", "aspect": "16:9", "paginate": true },\n  "items": [\n    "# First slide\\n\\nWelcome to your deck.",\n    "## Second slide\\n\\n- bullet one\\n- bullet two"\n  ]\n}\n';
    if (isYaml) return '$meta:\n  kind: slides\nslides:\n  theme: default\n  aspect: "16:9"\n  paginate: true\nitems:\n  - |\n    # First slide\n\n    Welcome to your deck.\n  - |\n    ## Second slide\n\n    - bullet one\n    - bullet two\n';
  }
  if (kind === 'diagram') {
    // Mermaid is the default dialect; markdown is canonical (one
    // fenced ```mermaid block). JSON/YAML hold the source as a string.
    if (isMd) return '---\nkind: diagram\n---\n\n```mermaid\nflowchart TD\n  A[Start] --> B{Decision}\n  B -->|yes| C[Do it]\n  B -->|no| D[Skip]\n```\n';
    if (isJson) return '{\n  "$meta": { "kind": "diagram" },\n  "source": "flowchart TD\\n  A[Start] --> B{Decision}\\n  B -->|yes| C[Do it]\\n  B -->|no| D[Skip]\\n"\n}\n';
    if (isYaml) return '$meta:\n  kind: diagram\nsource: |\n  flowchart TD\n    A[Start] --> B{Decision}\n    B -->|yes| C[Do it]\n    B -->|no| D[Skip]\n';
  }
  if (kind === 'calendar') {
    // Markdown isn't supported for calendars (spec §3) — JSON / YAML
    // only. MD falls through to the schema-less branch.
    if (isJson) return '{\n  "$meta": { "kind": "calendar" },\n  "events": [\n    {\n      "id": "ev-1",\n      "title": "Sprint Planning",\n      "start": "2026-06-12T09:00",\n      "end": "2026-06-12T11:00",\n      "location": "Büro"\n    },\n    {\n      "id": "ev-2",\n      "title": "Urlaub",\n      "start": "2026-07-15",\n      "end": "2026-07-28",\n      "allDay": true,\n      "tags": ["private"]\n    }\n  ]\n}\n';
    if (isYaml) return '$meta:\n  kind: calendar\nevents:\n  - id: ev-1\n    title: Sprint Planning\n    start: "2026-06-12T09:00"\n    end: "2026-06-12T11:00"\n    location: Büro\n  - id: ev-2\n    title: Urlaub\n    start: "2026-07-15"\n    end: "2026-07-28"\n    allDay: true\n    tags: [private]\n';
  }
  if (kind === 'application') {
    // _app.yaml — turns a folder into a Vance application bundle.
    // Default stub uses app=calendar (the v1 reference type); the
    // user edits `app:` if they want a different (future) app
    // face. Markdown isn't supported for manifests.
    if (isJson) return '{\n  "$meta": { "kind": "application", "app": "calendar" },\n  "title": "My Calendar App",\n  "description": "Planning suite — one calendar per lane.",\n  "calendar": {\n    "window": { "from": "2026-06-01", "until": "2026-09-30" },\n    "lanes": {\n      "design":  { "title": "Design",  "color": "blue",   "order": 1 },\n      "backend": { "title": "Backend", "color": "green",  "order": 2 }\n    },\n    "gantt":     { "outputPath": "_gantt.md", "includeRecurring": false },\n    "conflicts": { "outputPath": "_conflicts.yaml", "ignoreWithinTags": ["private"] }\n  }\n}\n';
    if (isYaml) return '$meta:\n  kind: application\n  app: calendar\ntitle: My Calendar App\ndescription: Planning suite — one calendar per lane.\ncalendar:\n  window:\n    from: "2026-06-01"\n    until: "2026-09-30"\n  lanes:\n    design:  { title: Design,  color: blue,  order: 1 }\n    backend: { title: Backend, color: green, order: 2 }\n  gantt:\n    outputPath: _gantt.md\n    includeRecurring: false\n  conflicts:\n    outputPath: _conflicts.yaml\n    ignoreWithinTags: [private]\n';
  }
  if (kind === 'sheet') {
    // Markdown not supported for sheets (spec §3.3) — falls through
    // to the schema-less branch below if md is picked.
    if (isJson) return '{\n  "$meta": { "kind": "sheet" },\n  "schema": ["A", "B", "C"],\n  "rows": 5,\n  "cells": [\n    { "field": "A1", "data": "Item" },\n    { "field": "B1", "data": "Qty" },\n    { "field": "C1", "data": "Total" },\n    { "field": "A2", "data": "Apples" },\n    { "field": "B2", "data": "10" },\n    { "field": "C2", "data": "=B2*1.5" }\n  ]\n}\n';
    if (isYaml) return '$meta:\n  kind: sheet\nschema: [A, B, C]\nrows: 5\ncells:\n  - field: A1\n    data: Item\n  - field: B1\n    data: Qty\n  - field: C1\n    data: Total\n  - field: A2\n    data: Apples\n  - field: B2\n    data: "10"\n  - field: C2\n    data: "=B2*1.5"\n';
  }
  // Schema-less kinds — header only, body stays empty.
  if (isMd) return `---\nkind: ${kind}\n---\n`;
  if (isJson) return `{\n  "$meta": { "kind": "${kind}" }\n}\n`;
  if (isYaml) return `$meta:\n  kind: ${kind}\n`;
  return '';
}

// Auto-fill the body when the user picks a kind (or switches the
// mime type while a kind is set). Only writes when the editor is
// empty or still holds the last auto-generated stub — anything the
// user has typed by hand stays untouched.
watch([createKind, createMime], ([kind, mime]) => {
  if (!showCreateModal.value) return;
  if (createMode.value !== 'inline') return;
  const editorEmpty = createContent.value === '' || createContent.value === lastGeneratedStub;
  if (!editorEmpty) return;
  const stub = buildKindStub(kind, mime);
  createContent.value = stub;
  lastGeneratedStub = stub;
});

function setCreateMode(mode: CreateMode): void {
  createMode.value = mode;
  createError.value = null;
  uploadProgress.value = [];
}

// Auto-fill the optional path override when exactly one file is picked and
// the user hasn't typed anything. With multiple files, path-override would
// have to apply per-file (it doesn't), so we leave it blank.
watch(createFiles, (files) => {
  if (files.length === 1 && !createName.value.trim()) {
    createName.value = files[0].name;
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
      if (!createName.value.trim()) { createError.value = t('documents.create.nameRequired'); return; }
      const fullPath = (createPath.value + createName.value).trim();
      const created = await docsState.create(selectedProjectId.value, {
        path: fullPath,
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
    if (files.length === 0) { createError.value = t('documents.create.pickAtLeastOneFile'); return; }

    if (files.length === 1) {
      const fullPath = (createPath.value + createName.value).trim();
      const created = await docsState.upload(selectedProjectId.value, {
        file: files[0],
        path: fullPath || undefined,
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
        uploadProgress.value[i].message = docsState.error.value ?? t('documents.create.uploadFailed');
      }
    }

    if (okCount === files.length) {
      // All good — close modal and refresh the list.
      showCreateModal.value = false;
    } else {
      createError.value = t('documents.create.multiUploadFailed', {
        failed: files.length - okCount,
        total: files.length,
      });
    }
  } finally {
    creating.value = false;
  }
}

/**
 * Persist the user-edited summary independently of the larger apply()
 * path. Single-field write through PUT .../summary — leaves title,
 * tags, inlineText, mime untouched even when those have unsaved
 * changes in the editor. Important for binary docs (image, PDF) where
 * the user only wants to author a caption.
 */
async function saveSummary(): Promise<void> {
  const sel = docsState.selected.value;
  if (!sel?.id) return;
  summarySaving.value = true;
  summarySaveMessage.value = null;
  try {
    const updated = await docsState.setSummary(sel.id, editSummary.value);
    if (updated) {
      summarySaveMessage.value = updated.summary ? 'Saved.' : 'Cleared.';
    }
  } catch (e) {
    summarySaveMessage.value =
      e instanceof Error ? e.message : 'Failed to save summary.';
  } finally {
    summarySaving.value = false;
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
    // Mime-type change — only send when actually different. Blank
    // drafts are treated as "no change" rather than "clear", because
    // the schema doesn't allow null mime types and the user can't
    // pick the empty option anyway (the dropdown's first entry is
    // always a concrete type).
    const newMime = editMimeType.value.trim();
    if (newMime && newMime !== (sel.mimeType ?? '')) {
      body.mimeType = newMime;
    }
    // Auto-summary toggles — only send when changed so we don't
    // churn the document on every save.
    if (editAutoSummary.value !== (sel.autoSummary ?? false)) {
      body.autoSummary = editAutoSummary.value;
    }
    if (editSummaryDirty.value !== (sel.summaryDirty ?? false)) {
      body.summaryDirty = editSummaryDirty.value;
    }
    // RAG tri-state — server takes "auto" / "on" / "off"; the document
    // exposes ragEnabled as boolean|null, mapping is identical to the
    // editor's radio value, so we just need to detect whether the user
    // actually changed it before sending.
    const currentRag: 'auto' | 'on' | 'off' =
        sel.ragEnabled == null ? 'auto'
      : sel.ragEnabled ? 'on'
      : 'off';
    if (editRagEnabled.value !== currentRag) {
      body.ragEnabled = editRagEnabled.value;
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

/**
 * Multi-key URL update with {@code pushState} semantics — used for
 * navigation steps (folder descent, document open, project switch)
 * so browser back/forward walks through them. Dedup against the
 * current URL: identical state is a no-op (avoids stacking duplicate
 * history entries when the same effect fires from multiple paths,
 * e.g. a watcher echoing a popstate-driven change).
 */
function pushQueryParams(updates: Record<string, string | null>): void {
  const url = new URL(window.location.href);
  for (const [k, v] of Object.entries(updates)) {
    if (v === null || v === '') url.searchParams.delete(k);
    else url.searchParams.set(k, v);
  }
  if (url.toString() !== window.location.href) {
    window.history.pushState(null, '', url.toString());
  }
}

/**
 * Front-matter rows for the read-only table in the editor. Iterating
 * `Record<string,string>` yields key/value pairs in insertion order;
 * the server returns them in source order so this matches what the
 * user typed.
 */
const headerEntries = computed<{ key: string; value: string }[]>(() => {
  const headers = docsState.selected.value?.headers;
  if (!headers) return [];
  return Object.entries(headers).map(([key, value]) => ({ key, value }));
});

function selectedProjectTitle(): string | null {
  const id = selectedProjectId.value;
  if (!id) return null;
  const p = projectsState.projects.value.find((x: ProjectSummary) => x.name === id);
  return p?.title?.trim() || id;
}

const breadcrumbs = computed<Crumb[]>(() => {
  if (!selectedProjectId.value) return [];
  const crumbs: Crumb[] = [];

  // Project root → clickable, navigates back to project root (drops
  // any path filter and any open document).
  crumbs.push({
    text: selectedProjectTitle() ?? '',
    onClick: () => applyPathFilter('', true),
  });

  // Path segments. Each clickable except the last (the user is
  // already there). {@code documents/} default is included as a
  // clickable segment so the user can pop back to the project root
  // from inside it.
  const path = docsState.pathPrefix.value;
  if (path) {
    const segments = path.split('/').filter((s) => s.length > 0);
    let acc = '';
    for (let i = 0; i < segments.length; i++) {
      acc += segments[i] + '/';
      const isLast = i === segments.length - 1 && !docsState.selected.value;
      if (isLast) {
        crumbs.push(segments[i]);
      } else {
        const target = acc;
        crumbs.push({
          text: segments[i],
          onClick: () => applyPathFilter(target, true),
        });
      }
    }
  }

  // Open document → final, non-clickable crumb (you're already
  // viewing it).
  const sel = docsState.selected.value;
  if (sel) {
    crumbs.push(sel.title?.trim() || sel.name);
  }

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
    v-model:focus-zone="focusZone"
    :title="$t('documents.pageTitle')"
    :breadcrumbs="breadcrumbs"
    :wide-right-panel="!!helpResource"
    :full-height="true"
    focus-model="auto"
    :show-sidebar="true"
    :show-right-panel="!!helpResource"
    :show-footer="!!docsState.selected.value"
    title-clickable
    @title-click="focusZone = 'sidebar'"
  >
    <!-- ─── Sidebar: project picker + folder navigation ──────────────
         Project list at top uses the shared {@link ProjectListSidebar}
         component (same instance backs chat). Folder navigation below
         is the existing tree; it'll later move into the main area, but
         for now it stays in the sidebar to preserve functionality. ─── -->
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

    <div class="h-full min-h-0 flex flex-col">
      <!-- Full-width sub-header, picker-style: visible only when a
           project is selected and we're showing the list (not the
           detail view). Back-button strips one path segment until
           empty; path is text-only; search filters the visible page
           client-side; {@code +} opens the create-document modal. -->
      <div
        v-if="selectedProjectId && !docsState.selected.value && projectOptions.length > 0"
        class="px-6 pt-4 pb-3 border-b border-base-300 bg-base-100 flex items-center gap-3"
      >
        <VButton
          variant="ghost"
          size="sm"
          :disabled="!docsState.pathPrefix.value"
          :title="$t('documents.pathBack')"
          @click="pathSegmentBack"
        >←</VButton>
        <div class="flex-1 min-w-0 font-mono text-sm opacity-70 truncate">
          {{ docsState.pathPrefix.value || '/' }}
        </div>
        <div class="w-[150px] shrink-0">
          <VInput
            v-model="documentFilter"
            :placeholder="$t('documents.searchPlaceholder')"
          />
        </div>
        <VButton
          variant="ghost"
          size="sm"
          :title="$t('documents.newFolder')"
          @click="openNewFolderModal"
        >📁+</VButton>
        <VButton
          variant="primary"
          size="sm"
          :title="$t('documents.newDocument')"
          @click="openCreateModal()"
        >+</VButton>
      </div>

      <!-- Full-width sub-header for the detail view: mirrors the
           picker sub-header (back-button + identity strip). Back
           returns to the list; the right side carries size, MIME
           and creator — the basic identity tuple the user expects
           at the top of every document. Status badges (kind, dirty,
           versions) and the props toggle stay on the card's
           metadata row below.

           Layout uses {@code flex-wrap}: when there's not enough
           horizontal room (long name, narrow main zone) the filename
           and/or the metadata strip wrap to a second row instead of
           clipping. The back-button + icon + title group is a
           single flex child, so the wrap never splits the title
           away from the back-button. The path collapses to its
           filename (basename) — full path can be inspected via
           the props panel. -->
      <div
        v-else-if="selectedProjectId && docsState.selected.value"
        class="px-6 pt-4 pb-3 border-b border-base-300 bg-base-100 flex items-center gap-x-3 gap-y-1 flex-wrap"
      >
        <div class="flex items-center gap-3 min-w-0 flex-1 basis-[16rem]">
          <VButton
            variant="ghost"
            size="sm"
            :title="$t('documents.backToList')"
            @click="requestBackToList"
          >←</VButton>
          <DocumentIcon
            :path="docsState.selected.value.path"
            :mime-type="docsState.selected.value.mimeType"
            :kind="docsState.selected.value.kind"
          />
          <span class="font-semibold truncate">
            {{ docsState.selected.value.title || docsState.selected.value.name }}
          </span>
        </div>
        <span class="font-mono text-sm opacity-60 truncate shrink-0 max-w-full">
          {{ docsState.selected.value.name }}
        </span>
        <div class="text-xs opacity-70 flex items-center gap-3 shrink-0">
          <span>{{ formatBytes(docsState.selected.value.size) }}</span>
          <span v-if="docsState.selected.value.mimeType">
            {{ docsState.selected.value.mimeType }}
          </span>
          <span v-if="docsState.selected.value.createdBy">
            {{ $t('documents.detail.sizeBy', { user: docsState.selected.value.createdBy }) }}
          </span>
        </div>
      </div>

      <!-- Scrollable content area — each branch (empty states, detail,
           list) lives here. The sub-header above stays put. -->
      <div class="flex-1 min-h-0 overflow-y-auto">
        <div class="container mx-auto px-4 py-4 max-w-5xl">
      <VAlert v-if="projectsState.error.value" variant="error" class="mb-4">
        <span>{{ projectsState.error.value }}</span>
      </VAlert>

      <VEmptyState
        v-if="!projectsState.loading.value && projectOptions.length === 0"
        :headline="$t('documents.noProjectsHeadline')"
        :body="$t('documents.noProjectsBody')"
      />

      <VEmptyState
        v-else-if="!selectedProjectId"
        :headline="$t('documents.pickAProjectHeadline')"
        :body="$t('documents.pickAProjectBody')"
      />

      <!-- Detail / edit view. Identity (back, name, path, size,
           MIME, creator) lives in the full-width sub-header above;
           the card here carries only status badges and content. -->
      <template v-else-if="docsState.selected.value">
        <VCard>
          <div class="text-xs opacity-60 flex flex-wrap gap-3 items-center">
            <span
              v-if="docsState.selected.value.kind"
              class="badge badge-info badge-sm"
              :title="$t('documents.detail.kindBadgeTooltip')"
            >{{ $t('documents.detail.kindLabel', { kind: docsState.selected.value.kind }) }}</span>
            <span
              v-if="isDirty"
              class="badge badge-warning badge-sm"
              :title="$t('documents.detail.changedBadgeTooltip')"
            >{{ $t('documents.detail.changedBadge') }}</span>
            <button
              v-if="archiveCount > 0"
              type="button"
              class="badge badge-ghost badge-sm cursor-pointer"
              :title="$t('documents.detail.versionsBadgeTooltip')"
              @click="propsCollapsed = false"
            >{{ $t('documents.detail.versionsBadge', { count: archiveCount }) }}</button>
            <!-- Spacer pushes the props toggle to the right end of
                 the metadata strip. -->
            <span class="grow"></span>
            <button
              type="button"
              class="props-toggle text-xs font-medium opacity-70 hover:opacity-100"
              :title="propsCollapsed
                ? $t('documents.detail.propsExpandHint')
                : $t('documents.detail.propsCollapseHint')"
              :aria-expanded="!propsCollapsed"
              @click="togglePropsCollapsed"
            >
              <span class="props-toggle__chevron" aria-hidden="true">
                {{ propsCollapsed ? '▸' : '▾' }}
              </span>
              {{ $t('documents.detail.propsToggleLabel') }}
            </button>
          </div>

          <!-- ─── Front-matter table — only when the markdown body
               carries a recognised header. Read-only: the content is
               authoritative, this just mirrors what the parser saw.
               Hidden when the user collapses the properties panel. ─── -->
          <div
            v-if="!propsCollapsed && headerEntries.length > 0"
            class="mt-3 border border-base-300 rounded-md overflow-hidden"
          >
            <div class="px-3 py-2 bg-base-200 text-xs uppercase opacity-70">
              {{ $t('documents.detail.frontMatter') }}
            </div>
            <table class="table table-xs">
              <tbody>
                <tr v-for="entry in headerEntries" :key="entry.key">
                  <td class="font-mono opacity-70 w-1/3">{{ entry.key }}</td>
                  <td class="font-mono break-all">{{ entry.value }}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <!-- ─── Auto-summary panel — read-only summary text plus the
               two editable flags. Visible regardless of mime type so
               users can disable the scheduler on a doc that shouldn't
               be summarised, or force a re-run on demand. Hidden when
               the user collapses the properties panel. ─── -->
          <div
            v-show="!propsCollapsed"
            class="mt-3 border border-base-300 rounded-md overflow-hidden"
          >
            <div class="px-3 py-2 bg-base-200 text-xs uppercase opacity-70">
              {{ $t('documents.detail.summary.heading') }}
            </div>
            <div class="p-3 flex flex-col gap-3">
              <div>
                <div class="text-xs opacity-70 mb-1">
                  {{ $t('documents.detail.summary.summaryLabel') }}
                </div>
                <p
                  v-if="docsState.selected.value.summary"
                  class="text-sm whitespace-pre-wrap"
                >{{ docsState.selected.value.summary }}</p>
                <p v-else class="text-sm italic opacity-60">
                  {{ $t('documents.detail.summary.summaryEmpty') }}
                </p>
                <p class="text-xs opacity-60 mt-1">
                  {{
                    docsState.selected.value.summarizedAtMs
                      ? $t('documents.detail.summary.summarizedAt', {
                          when: new Date(docsState.selected.value.summarizedAtMs).toLocaleString(),
                        })
                      : $t('documents.detail.summary.summarizedNever')
                  }}
                </p>
              </div>
              <VCheckbox
                v-model="editAutoSummary"
                :label="$t('documents.detail.summary.autoSummaryLabel')"
                :help="$t('documents.detail.summary.autoSummaryHelp')"
                :disabled="saving"
              />
              <VCheckbox
                v-model="editSummaryDirty"
                :label="$t('documents.detail.summary.summaryDirtyLabel')"
                :help="$t('documents.detail.summary.summaryDirtyHelp')"
                :disabled="saving"
              />
              <div class="flex flex-col gap-1">
                <label class="text-xs opacity-70">Project RAG</label>
                <div class="flex gap-4 text-sm">
                  <label class="flex items-center gap-1.5 cursor-pointer">
                    <input
                      type="radio"
                      v-model="editRagEnabled"
                      value="auto"
                      :disabled="saving"
                    />
                    <span>Auto</span>
                  </label>
                  <label class="flex items-center gap-1.5 cursor-pointer">
                    <input
                      type="radio"
                      v-model="editRagEnabled"
                      value="on"
                      :disabled="saving"
                    />
                    <span>Always index</span>
                  </label>
                  <label class="flex items-center gap-1.5 cursor-pointer">
                    <input
                      type="radio"
                      v-model="editRagEnabled"
                      value="off"
                      :disabled="saving"
                    />
                    <span>Never index</span>
                  </label>
                </div>
                <p class="text-xs opacity-60">
                  Auto = include if the document lives under
                  <code>documents/</code> and has a textual mime-type.
                  Changes take effect on the next RAG indexer tick.
                </p>
              </div>
            </div>
          </div>

          <DocumentArchives
            v-show="!propsCollapsed"
            :document="docsState.selected.value"
            @restored="onArchiveRestored"
            @update:count="onArchiveCount"
          />

          <VAlert v-if="!docsState.selected.value.inline" variant="info" class="mt-3">
            <span>{{ $t('documents.detail.readOnlyNote') }}</span>
          </VAlert>

          <VAlert v-if="editError" variant="error" class="mt-3">
            <span>{{ editError }}</span>
          </VAlert>

          <div class="flex flex-col gap-3 mt-3">
            <!-- Title / Path / MIME — part of the collapsible
                 properties panel. The editor and action bar below
                 stay visible regardless of the collapsed state. -->
            <template v-if="!propsCollapsed">
              <VInput v-model="editTitle" :label="$t('documents.detail.titleLabel')" :disabled="saving" />
              <VInput
                v-model="editPath"
                :label="$t('documents.detail.pathLabel')"
                :disabled="saving"
                :help="$t('documents.detail.pathHelp')"
              />
              <VSelect
                v-model="editMimeType"
                :options="editMimeOptions"
                :label="$t('documents.detail.mimeTypeLabel')"
                :disabled="saving"
                :help="$t('documents.detail.mimeTypeHelp')"
              />
              <!-- Summary / caption — saved through a dedicated
                   PUT .../summary endpoint so it doesn't interact
                   with title/path/inlineText edits. Auto-summary
                   scheduler also writes here when enabled. -->
              <div class="flex flex-col gap-1">
                <VTextarea
                  v-model="editSummary"
                  :label="$t('documents.detail.summaryEditorLabel')"
                  :rows="3"
                  :disabled="summarySaving"
                  :help="$t('documents.detail.summaryEditorHelp')"
                />
                <div class="flex items-center gap-2 mt-1">
                  <VButton
                    size="sm"
                    variant="ghost"
                    :disabled="summarySaving || editSummary === (docsState.selected.value.summary ?? '')"
                    @click="saveSummary"
                  >{{ $t('documents.detail.summaryEditorSave') }}</VButton>
                  <span v-if="summarySaveMessage" class="text-xs text-base-content/70">
                    {{ summarySaveMessage }}
                  </span>
                </div>
              </div>
            </template>
            <template v-if="docsState.selected.value.inline">
              <!-- Tab bar appears for list/tree-kind documents in a
                   supported mime type. Other docs jump straight to
                   the raw editor as before. -->
              <div v-if="isListDocument" class="content-tabs">
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'list' }"
                  @click="contentTab = 'list'"
                >{{ $t('documents.detail.tabList') }}</button>
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'raw' }"
                  @click="contentTab = 'raw'"
                >{{ $t('documents.detail.tabRaw') }}</button>
              </div>
              <div v-else-if="isChecklistDocument" class="content-tabs">
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'checklist' }"
                  @click="contentTab = 'checklist'"
                >{{ $t('documents.detail.tabChecklist') }}</button>
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'raw' }"
                  @click="contentTab = 'raw'"
                >{{ $t('documents.detail.tabRaw') }}</button>
              </div>
              <div v-else-if="isSheetDocument" class="content-tabs">
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'sheet' }"
                  @click="contentTab = 'sheet'"
                >{{ $t('documents.detail.tabSheet') }}</button>
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'raw' }"
                  @click="contentTab = 'raw'"
                >{{ $t('documents.detail.tabRaw') }}</button>
              </div>
              <div v-else-if="isGraphDocument" class="content-tabs">
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'graph' }"
                  @click="contentTab = 'graph'"
                >{{ $t('documents.detail.tabGraph') }}</button>
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'raw' }"
                  @click="contentTab = 'raw'"
                >{{ $t('documents.detail.tabRaw') }}</button>
              </div>
              <div v-else-if="isChartDocument" class="content-tabs">
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'chart' }"
                  @click="contentTab = 'chart'"
                >{{ $t('documents.detail.tabChart') }}</button>
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'raw' }"
                  @click="contentTab = 'raw'"
                >{{ $t('documents.detail.tabRaw') }}</button>
              </div>
              <div v-else-if="isRecordsDocument" class="content-tabs">
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'records' }"
                  @click="contentTab = 'records'"
                >{{ $t('documents.detail.tabRecords') }}</button>
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'raw' }"
                  @click="contentTab = 'raw'"
                >{{ $t('documents.detail.tabRaw') }}</button>
              </div>
              <div v-else-if="isMindmapDocument" class="content-tabs">
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'mindmap' }"
                  @click="contentTab = 'mindmap'"
                >{{ $t('documents.detail.tabMindmap') }}</button>
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'tree' }"
                  @click="contentTab = 'tree'"
                >{{ $t('documents.detail.tabTree') }}</button>
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'raw' }"
                  @click="contentTab = 'raw'"
                >{{ $t('documents.detail.tabRaw') }}</button>
              </div>
              <div v-else-if="isSlidesDocument" class="content-tabs">
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'slides' }"
                  @click="contentTab = 'slides'"
                >{{ $t('documents.detail.tabSlides') }}</button>
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'raw' }"
                  @click="contentTab = 'raw'"
                >{{ $t('documents.detail.tabRaw') }}</button>
              </div>
              <div v-else-if="isDiagramDocument" class="content-tabs">
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'diagram' }"
                  @click="contentTab = 'diagram'"
                >{{ $t('documents.detail.tabDiagram') }}</button>
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'raw' }"
                  @click="contentTab = 'raw'"
                >{{ $t('documents.detail.tabRaw') }}</button>
              </div>
              <div v-else-if="isCalendarDocument && calendarKind" class="content-tabs">
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'calendar' }"
                  @click="contentTab = 'calendar'"
                >{{ $t(calendarKind.tabLabelKey ?? 'documents.detail.tabCalendar') }}</button>
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'raw' }"
                  @click="contentTab = 'raw'"
                >{{ $t('documents.detail.tabRaw') }}</button>
              </div>
              <div v-else-if="isTreeDocument" class="content-tabs">
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'tree' }"
                  @click="contentTab = 'tree'"
                >{{ $t('documents.detail.tabTree') }}</button>
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'raw' }"
                  @click="contentTab = 'raw'"
                >{{ $t('documents.detail.tabRaw') }}</button>
              </div>
              <div v-else-if="isMarkdownDocument" class="content-tabs">
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'preview' }"
                  @click="contentTab = 'preview'"
                >{{ $t('documents.detail.tabPreview') }}</button>
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'raw' }"
                  @click="contentTab = 'raw'"
                >{{ $t('documents.detail.tabRaw') }}</button>
              </div>

              <template v-if="isListDocument && contentTab === 'list'">
                <VAlert v-if="parsedList.error" variant="warning">
                  <span>{{ $t('documents.detail.listParseError', { message: parsedList.error }) }}</span>
                </VAlert>
                <ListView
                  v-else-if="parsedList.doc"
                  :doc="parsedList.doc"
                  @update:doc="onListChanged"
                />
              </template>

              <template v-else-if="isChecklistDocument && contentTab === 'checklist'">
                <VAlert v-if="parsedChecklist.error" variant="warning">
                  <span>{{ $t('documents.detail.checklistParseError', { message: parsedChecklist.error }) }}</span>
                </VAlert>
                <ChecklistView
                  v-else-if="parsedChecklist.doc"
                  :doc="parsedChecklist.doc"
                  @update:doc="onChecklistChanged"
                />
              </template>

              <template v-else-if="isSheetDocument && contentTab === 'sheet'">
                <VAlert v-if="parsedSheet.error" variant="warning">
                  <span>{{ $t('documents.detail.sheetParseError', { message: parsedSheet.error }) }}</span>
                </VAlert>
                <SheetView
                  v-else-if="parsedSheet.doc"
                  :doc="parsedSheet.doc"
                  @update:doc="onSheetChanged"
                />
              </template>

              <template v-else-if="isGraphDocument && contentTab === 'graph'">
                <VAlert v-if="parsedGraph.error" variant="warning">
                  <span>{{ $t('documents.detail.graphParseError', { message: parsedGraph.error }) }}</span>
                </VAlert>
                <GraphView
                  v-else-if="parsedGraph.doc"
                  :doc="parsedGraph.doc"
                  @update:doc="onGraphChanged"
                />
              </template>

              <template v-else-if="isChartDocument && contentTab === 'chart'">
                <VAlert v-if="parsedChart.error" variant="warning">
                  <span>{{ $t('documents.detail.chartParseError', { message: parsedChart.error }) }}</span>
                </VAlert>
                <ChartView
                  v-else-if="parsedChart.doc"
                  :doc="parsedChart.doc"
                  @update:doc="onChartChanged"
                />
              </template>

              <template v-else-if="isRecordsDocument && contentTab === 'records'">
                <VAlert v-if="parsedRecords.error" variant="warning">
                  <span>{{ $t('documents.detail.recordsParseError', { message: parsedRecords.error }) }}</span>
                </VAlert>
                <RecordsView
                  v-else-if="parsedRecords.doc"
                  :doc="parsedRecords.doc"
                  @update:doc="onRecordsChanged"
                />
              </template>

              <template v-else-if="isMindmapDocument && contentTab === 'mindmap'">
                <VAlert v-if="parsedTree.error" variant="warning">
                  <span>{{ $t('documents.detail.mindmapParseError', { message: parsedTree.error }) }}</span>
                </VAlert>
                <MindmapView v-else-if="parsedTree.doc" :doc="parsedTree.doc" />
              </template>

              <template v-else-if="isSlidesDocument && contentTab === 'slides'">
                <VAlert v-if="parsedSlides.error" variant="warning">
                  <span>{{ $t('documents.detail.slidesParseError', { message: parsedSlides.error }) }}</span>
                </VAlert>
                <SlidesView v-else-if="parsedSlides.doc" :doc="parsedSlides.doc" />
              </template>

              <template v-else-if="isDiagramDocument && contentTab === 'diagram'">
                <VAlert v-if="parsedDiagram.error" variant="warning">
                  <span>{{ $t('documents.detail.diagramParseError', { message: parsedDiagram.error }) }}</span>
                </VAlert>
                <DiagramView v-else-if="parsedDiagram.doc" :doc="parsedDiagram.doc" />
              </template>

              <template v-else-if="isCalendarDocument && contentTab === 'calendar' && calendarKind">
                <VAlert v-if="parsedCalendar.error" variant="warning">
                  <span>{{ $t(calendarKind.parseErrorKey ?? 'documents.detail.calendarParseError', { message: parsedCalendar.error }) }}</span>
                </VAlert>
                <component
                  :is="calendarKind.view"
                  v-else-if="parsedCalendar.doc"
                  mode="embedded"
                  :doc="parsedCalendar.doc"
                />
              </template>

              <template v-else-if="(isTreeDocument || isMindmapDocument) && contentTab === 'tree'">
                <VAlert v-if="parsedTree.error" variant="warning">
                  <span>{{ $t('documents.detail.treeParseError', { message: parsedTree.error }) }}</span>
                </VAlert>
                <TreeView
                  v-else-if="parsedTree.doc"
                  :doc="parsedTree.doc"
                  @update:doc="onTreeChanged"
                />
              </template>

              <template v-else-if="isMarkdownDocument && contentTab === 'preview'">
                <div class="markdown-preview-pane">
                  <MarkdownView :source="editInlineText" />
                </div>
              </template>

              <CodeEditor
                v-else
                v-model="editInlineText"
                :label="$t('documents.detail.contentLabel')"
                :rows="20"
                :disabled="saving"
                :mime-type="docsState.selected.value.mimeType"
                :follow-up="isMarkdownDocument ? markdownFollowUp : null"
              />
            </template>
            <!-- Binary-document branch (DOCX/XLSX/PDF/images/…).
                 Office-editable docs get a Preview / Office-Edit
                 tab pair; everything else falls through to the
                 plain DocumentPreview. -->
            <template v-else-if="isOfficeEditableDocument">
              <div class="content-tabs">
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'preview' }"
                  @click="contentTab = 'preview'"
                >{{ $t('documents.detail.tabPreview') }}</button>
                <button
                  type="button"
                  class="content-tab"
                  :class="{ 'content-tab--active': contentTab === 'office' }"
                  @click="contentTab = 'office'"
                >{{ $t('documents.detail.tabOfficeEdit') }}</button>
              </div>
              <OfficeEditor
                v-if="contentTab === 'office'"
                :document-id="docsState.selected.value.id"
                :mime-type="docsState.selected.value.mimeType"
              />
              <DocumentPreview
                v-else
                :key="`office-preview-${docsState.selected.value.id}-${previewReloadCounter}`"
                :document-id="docsState.selected.value.id"
                :mime-type="docsState.selected.value.mimeType"
                :inline="false"
              />
            </template>
            <DocumentPreview
              v-else
              :document-id="docsState.selected.value.id"
              :mime-type="docsState.selected.value.mimeType"
              :inline="false"
            />
          </div>

        </VCard>
      </template>

      <!-- List view: the sub-header sits above the scroll container
           in the section root; this branch only renders the list
           content (alerts, empty state, data list, pagination). -->
      <template v-else>
        <VAlert v-if="docsState.error.value" variant="error" class="mb-4">
          <span>{{ docsState.error.value }}</span>
        </VAlert>

        <VEmptyState
          v-if="!docsState.loading.value
            && docsState.items.value.length === 0
            && docsState.subFolders.value.length === 0"
          :headline="$t('documents.noDocumentsHeadline')"
          :body="$t('documents.noDocumentsBody')"
        >
          <template #action>
            <VButton variant="primary" @click="openCreateModal()">
              {{ $t('documents.createFirstDocument') }}
            </VButton>
          </template>
        </VEmptyState>

        <template v-else>
          <!-- Sub-folders — clickable rows that descend into that
               folder. Listed first, alphabetically (sorted server-
               side). Files follow below. -->
          <ul
            v-if="docsState.subFolders.value.length > 0"
            class="flex flex-col gap-1 mb-3"
          >
            <li
              v-for="folder in docsState.subFolders.value"
              :key="folder"
              class="folder-row"
              @click="navigateIntoFolder(folder)"
            >
              <span class="text-lg leading-none">📁</span>
              <span class="font-medium">{{ folder }}/</span>
            </li>
          </ul>

        <VDataList
          v-if="docsState.items.value.length > 0"
          :items="docsState.items.value"
          selectable
          @select="openDocument"
        >
          <template #default="{ item }">
            <div class="flex items-center gap-3">
              <DocumentIcon
                :path="item.path"
                :mime-type="item.mimeType"
                :kind="item.kind"
              />
              <div class="min-w-0 flex-1">
                <div class="font-semibold truncate flex items-center gap-2">
                  <span class="truncate">{{ item.title?.trim() || item.name }}</span>
                  <span
                    v-if="item.kind"
                    class="badge badge-info badge-sm shrink-0"
                    :title="`kind: ${item.kind}`"
                  >{{ item.kind }}</span>
                </div>
                <div class="text-xs opacity-60 truncate font-mono">{{ item.name }}</div>
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
                <div v-if="!item.inline" class="text-warning">
                  {{ $t('documents.storedNote') }}
                </div>
              </div>
              <!-- App-manifest escape hatch: clicking the row would
                   redirect into the dedicated app face (Kanban,
                   Calendar, …). This button opens the same document
                   in the generic file editor so the user can still
                   tweak / fix the {@code _app.yaml} directly.
                   {@code @click.stop} keeps the row's
                   {@code @select} from also firing. -->
              <VButton
                v-if="isAppDocument(item)"
                variant="ghost"
                size="sm"
                class="shrink-0"
                :title="$t('documents.editAsFile')"
                @click.stop="openDocumentInEditor(item)"
              >✏️</VButton>
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
      </template>
        </div>
      </div>
    </div>

    <!-- Delete confirmation. Two flavours — soft-trash (default) and
         permanent (when the doc already lives in `_bin/`). The
         server picks the actual operation from the same path; the UI
         just relabels the prompt so the user understands the impact.
         See specification/web-ui.md §7.7.1 (destructive actions). -->
    <VModal
      v-model="showDeleteModal"
      :title="isSelectedInTrash
        ? $t('documents.delete.titlePermanent')
        : $t('documents.delete.title')"
      :close-on-backdrop="!deleting"
    >
      <p>
        {{ isSelectedInTrash
          ? $t('documents.delete.bodyPermanent', { path: docsState.selected.value?.path ?? '' })
          : $t('documents.delete.body', {
              path: docsState.selected.value?.path ?? '',
              bin: TRASH_PREFIX,
            }) }}
      </p>
      <template #actions>
        <VButton
          variant="ghost"
          :disabled="deleting"
          @click="showDeleteModal = false"
        >{{ $t('documents.delete.cancel') }}</VButton>
        <VButton variant="danger" :loading="deleting" @click="confirmDelete">
          {{ isSelectedInTrash
            ? $t('documents.delete.confirmPermanent')
            : $t('documents.delete.confirm') }}
        </VButton>
      </template>
    </VModal>

    <!-- Add-folder modal: virtual folder creation. Submitting just
         navigates into the new path; the folder materialises in
         storage when the user creates a file there. -->
    <VModal
      v-model="showNewFolderModal"
      :title="$t('documents.newFolderDialog.title')"
    >
      <form class="flex flex-col gap-3" @submit.prevent="submitNewFolder">
        <VAlert v-if="newFolderError" variant="error">
          <span>{{ newFolderError }}</span>
        </VAlert>
        <div class="text-xs opacity-70 font-mono">
          {{ docsState.pathPrefix.value || '/' }}
        </div>
        <VInput
          v-model="newFolderName"
          :label="$t('documents.newFolderDialog.nameLabel')"
          :placeholder="$t('documents.newFolderDialog.namePlaceholder')"
          :help="$t('documents.newFolderDialog.nameHelp')"
          required
        />
      </form>
      <template #actions>
        <VButton variant="ghost" @click="showNewFolderModal = false">
          {{ $t('common.cancel') }}
        </VButton>
        <VButton variant="primary" @click="submitNewFolder">
          {{ $t('documents.newFolderDialog.create') }}
        </VButton>
      </template>
    </VModal>

    <!-- Unsaved-changes modal: opens when the user tries to leave the
         detail view (Back / Cancel) with unsaved edits. Three actions
         — save & leave, discard & leave, stay. -->
    <VModal
      v-model="showDiscardModal"
      :title="$t('documents.discard.title')"
      :close-on-backdrop="!saving"
    >
      <p>{{ $t('documents.discard.body') }}</p>
      <template #actions>
        <VButton
          variant="ghost"
          :disabled="saving"
          @click="showDiscardModal = false"
        >{{ $t('documents.discard.cancel') }}</VButton>
        <VButton
          variant="danger"
          :disabled="saving"
          @click="discardAndBack"
        >{{ $t('documents.discard.discard') }}</VButton>
        <VButton
          variant="primary"
          :loading="saving"
          @click="saveAndBack"
        >{{ $t('documents.discard.save') }}</VButton>
      </template>
    </VModal>

    <!-- Revert-confirm modal: opened by the footer's "Discard changes"
         button. Confirming re-fetches the document from the server and
         drops all local edits. -->
    <VModal
      v-model="showRevertModal"
      :title="$t('documents.revertConfirm.title')"
      :close-on-backdrop="!docsState.loading.value"
    >
      <p>{{ $t('documents.revertConfirm.body') }}</p>
      <template #actions>
        <VButton
          variant="ghost"
          :disabled="docsState.loading.value"
          @click="showRevertModal = false"
        >{{ $t('documents.revertConfirm.cancel') }}</VButton>
        <VButton
          variant="danger"
          :loading="docsState.loading.value"
          @click="revertChanges"
        >{{ $t('documents.revertConfirm.confirm') }}</VButton>
      </template>
    </VModal>

    <!-- Create modal: lives outside the list/detail branches so it stays
         mounted across view switches and its open-state is independent. -->
    <VModal
      v-model="showCreateModal"
      :title="$t('documents.create.newDocument')"
      :close-on-backdrop="false"
    >
      <div class="flex gap-2 mb-4">
        <VButton
          :variant="createMode === 'inline' ? 'primary' : 'ghost'"
          size="sm"
          :disabled="creating"
          @click="setCreateMode('inline')"
        >{{ $t('documents.create.typeContent') }}</VButton>
        <VButton
          :variant="createMode === 'upload' ? 'primary' : 'ghost'"
          size="sm"
          :disabled="creating"
          @click="setCreateMode('upload')"
        >{{ $t('documents.create.uploadFile') }}</VButton>
      </div>

      <form class="flex flex-col gap-3" @submit.prevent="submitCreate">
        <VAlert v-if="createError" variant="error">
          <span>{{ createError }}</span>
        </VAlert>

        <template v-if="createMode === 'inline'">
          <!-- Path is fixed to the current folder; only the filename
               is editable. Path on top so the destination context
               reads top-down, name as the primary input below. -->
          <VInput
            :model-value="createPath || '/'"
            :label="$t('documents.create.pathLabel')"
            disabled
            readonly
          />
          <VInput
            v-model="createName"
            :label="$t('documents.create.nameLabel')"
            :placeholder="$t('documents.create.namePlaceholder')"
            required
            :disabled="creating"
            @keydown.enter.prevent="submitCreate"
          />
          <VInput
            v-model="createTitle"
            :label="$t('documents.create.titleLabel')"
            :placeholder="$t('documents.create.titlePlaceholder')"
            :disabled="creating"
          />
          <VInput
            v-model="createTagsRaw"
            :label="$t('documents.create.tagsLabel')"
            :placeholder="$t('documents.create.tagsPlaceholder')"
            :disabled="creating"
            :help="$t('documents.create.tagsHelp')"
          />
          <VSelect
            v-model="createMime"
            :options="createMimeOptions"
            :label="$t('documents.create.typeLabel')"
            :disabled="creating"
          />
          <VSelect
            v-model="createKind"
            :options="kindCreateOptions"
            :label="$t('documents.create.kindLabel')"
            :help="$t('documents.create.kindHelp')"
            :disabled="creating"
          />
          <CodeEditor
            v-model="createContent"
            :label="$t('documents.create.contentLabel')"
            :rows="14"
            :disabled="creating"
            :mime-type="createMime"
          />
        </template>

        <template v-else>
          <VFileInput
            v-model="createFiles"
            :label="$t('documents.create.filesLabel')"
            multiple
            :disabled="creating"
            :help="$t('documents.create.filesHelp')"
          />

          <!-- Path and title only make sense for a single file — they would
               apply ambiguously to a batch. With multiple files, each file's
               name becomes its path and the server-side `createdBy` is set
               from the JWT. -->
          <template v-if="createFiles.length <= 1">
            <VInput
              :model-value="createPath || '/'"
              :label="$t('documents.create.pathLabel')"
              disabled
              readonly
            />
            <VInput
              v-model="createName"
              :label="$t('documents.create.nameLabel')"
              :placeholder="$t('documents.create.namePlaceholderUpload')"
              :disabled="creating"
              :help="$t('documents.create.nameHelpUpload')"
              @keydown.enter.prevent="submitCreate"
            />
            <VInput
              v-model="createTitle"
              :label="$t('documents.create.titleLabel')"
              :placeholder="$t('documents.create.titlePlaceholder')"
              :disabled="creating"
            />
          </template>

          <VInput
            v-model="createTagsRaw"
            :label="$t('documents.create.tagsLabel')"
            :placeholder="$t('documents.create.tagsPlaceholder')"
            :disabled="creating"
            :help="createFiles.length > 1
              ? $t('documents.create.tagsHelpMulti')
              : $t('documents.create.tagsHelp')"
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
        >{{ $t('documents.create.cancel') }}</VButton>
        <VButton
          variant="primary"
          :loading="creating"
          @click="submitCreate"
        >{{ createMode === 'upload'
          ? $t('documents.create.submitUpload')
          : $t('documents.create.submitCreate') }}</VButton>
      </template>
    </VModal>

    <!-- ─── Contextual help — shown only when the selected document
         lives under a known path prefix (e.g. _vance/recipes/,
         _vance/strategies/). The slot is registered unconditionally
         so Vue's slot-presence detection sees it; the {@code v-if}
         lives on the content and the {@code show-right-panel} prop
         on EditorShell hides the rail when no help applies. ─── -->
    <template #right-panel>
      <div v-if="helpResource" class="p-4 flex flex-col gap-4">
        <h3 class="text-xs uppercase opacity-60 mb-2">
          {{ $t('documents.help.title') }}
        </h3>
        <div v-if="help.loading.value" class="text-xs opacity-60">
          {{ $t('documents.help.loading') }}
        </div>
        <div v-else-if="help.error.value" class="text-xs opacity-60">
          {{ $t('documents.help.unavailable', { error: help.error.value }) }}
        </div>
        <div v-else-if="!help.content.value" class="text-xs opacity-60">
          {{ $t('documents.help.empty') }}
        </div>
        <MarkdownView v-else :source="help.content.value" />
      </div>
    </template>

    <!-- Footer rail: document-detail actions. Trash is pulled to the
         far left (destructive separator), Download / Apply / Save
         cluster on the right. Cancel is intentionally omitted —
         the sub-header's back-button already serves that role. -->
    <template #footer>
      <div
        v-if="docsState.selected.value"
        class="px-6 py-3 flex items-center gap-2 bg-base-100"
      >
        <VButton
          variant="danger"
          :disabled="saving || deleting"
          @click="openDeleteModal"
        >{{ isSelectedInTrash
          ? $t('documents.detail.deletePermanent')
          : $t('documents.detail.delete') }}</VButton>
        <span class="flex-1"></span>
        <VButton
          variant="ghost"
          :href="downloadUrl(docsState.selected.value)"
          :download="docsState.selected.value.name || 'document'"
        >{{ $t('documents.detail.download') }}</VButton>
        <!-- "Discard changes" replaces the old Cancel button while the
             document is dirty. Clicking opens a confirmation modal so
             the unsaved work isn't dropped accidentally. -->
        <VButton
          v-if="isDirty"
          variant="ghost"
          :disabled="saving"
          @click="requestRevert"
        >{{ $t('documents.detail.revert') }}</VButton>
        <VButton variant="secondary" :loading="saving" @click="apply">
          {{ $t('documents.detail.apply') }}
        </VButton>
        <VButton variant="primary" :loading="saving" @click="save">
          {{ $t('documents.detail.save') }}
        </VButton>
      </div>
    </template>
  </EditorShell>
</template>

<style scoped>
/* Sub-folder row in the main file list — clickable, descends into
 * that folder when activated. Sized to match the {@code <VDataList>}
 * row underneath visually. */
.folder-row {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem 1rem;
  border-radius: 0.5rem;
  cursor: pointer;
  border: 1px solid transparent;
  transition: background 0.1s, border-color 0.1s;
}
.folder-row:hover {
  background: rgba(127, 127, 127, 0.06);
  border-color: rgba(127, 127, 127, 0.18);
}

/* Tab bar above the inline-text editor for kind-aware view modes
   (List / Raw). Two simple buttons; reuses the active-pill look from
   the insights editor's tab bar. */
.content-tabs {
  display: flex;
  gap: 0.25rem;
  border-bottom: 1px solid hsl(var(--bc) / 0.15);
  padding-bottom: 0.25rem;
  margin-top: 0.25rem;
}
.content-tab {
  padding: 0.3rem 0.85rem;
  border-radius: 0.375rem;
  background: transparent;
  border: 1px solid transparent;
  font-size: 0.85rem;
  cursor: pointer;
}
.content-tab:hover {
  background: hsl(var(--bc) / 0.06);
}
.content-tab--active {
  background: hsl(var(--p) / 0.12);
  border-color: hsl(var(--p) / 0.3);
  font-weight: 600;
}

/* Markdown preview pane — visual frame that matches the CodeEditor's
   bordered card, so swapping tabs doesn't shift the surrounding layout. */
.markdown-preview-pane {
  border: 1px solid hsl(var(--bc) / 0.18);
  border-radius: 0.5rem;
  padding: 1rem 1.25rem;
  background: hsl(var(--b1));
  min-height: 16rem;
  max-height: 70vh;
  overflow-y: auto;
}

/* Properties-panel toggle — slim button in the metadata strip that
   expands / collapses the front-matter, summary, archive, and
   title/path/mime fields together. The chevron rotates only via the
   character swap (▸ vs ▾); a CSS transform would need a single
   character + rotation, but emoji-style chars render more reliably
   across themes when swapped directly. */
.props-toggle {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  background: transparent;
  border: 1px solid oklch(var(--bc) / 0.18);
  border-radius: 0.3rem;
  padding: 0.15rem 0.55rem;
  cursor: pointer;
  font: inherit;
  color: inherit;
}
.props-toggle:hover {
  background: oklch(var(--bc) / 0.06);
  border-color: oklch(var(--bc) / 0.3);
}
.props-toggle__chevron {
  display: inline-block;
  width: 0.7em;
  text-align: center;
  font-size: 0.9em;
}
</style>
