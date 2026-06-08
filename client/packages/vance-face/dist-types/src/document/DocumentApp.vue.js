import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { EditorShell, ProjectListSidebar, VAlert, VButton, VCard, VCheckbox, VDataList, VEmptyState, VFileInput, VInput, VModal, VPagination, VSelect, VTextarea, CodeEditor, MarkdownView, } from '@/components';
import { useDocuments } from '@/composables/useDocuments';
import { useHelp } from '@/composables/useHelp';
import { useTenantProjects } from '@/composables/useTenantProjects';
import { brainFetch, documentContentUrl } from '@vance/shared';
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
import { isListMime, parseList, serializeList, ListCodecError, } from './listItemsCodec';
import { isChecklistMime, parseChecklist, serializeChecklist, ChecklistCodecError, } from './checklistCodec';
import { isTreeMime, parseTree, serializeTree, TreeCodecError, } from './treeItemsCodec';
import { isRecordsMime, parseRecords, serializeRecords, RecordsCodecError, } from './recordsCodec';
import { isGraphMime, parseGraph, serializeGraph, GraphCodecError, } from './graphCodec';
import { isChartMime, parseChart, serializeChart, ChartCodecError, } from './chartCodec';
import { isSheetMime, parseSheet, serializeSheet, SheetCodecError, } from './sheetCodec';
import { isSlidesMime, parseSlides, SlidesCodecError, } from './slidesCodec';
import { isDiagramMime, parseDiagram, DiagramCodecError, } from './diagramCodec';
import { resolveKind } from '@vance/kind-registry';
const PAGE_SIZE = 20;
const { t } = useI18n();
const projectsState = useTenantProjects();
const docsState = useDocuments(PAGE_SIZE);
const selectedProjectId = ref(null);
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
const summarySaveMessage = ref(null);
/**
 * Tri-state for the project-RAG inclusion override. {@code 'auto'} (default
 * — null in the DTO) follows the project rule "documents/** + textual mime";
 * {@code 'on'} forces inclusion, {@code 'off'} excludes the document.
 */
const editRagEnabled = ref('auto');
const editError = ref(null);
const saving = ref(false);
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
const isDirty = computed(() => {
    const sel = docsState.selected.value;
    if (!sel)
        return false;
    if (editTitle.value !== (sel.title ?? ''))
        return true;
    const newPath = editPath.value.trim();
    if (newPath && newPath !== sel.path)
        return true;
    const newMime = editMimeType.value.trim();
    if (newMime && newMime !== (sel.mimeType ?? ''))
        return true;
    if (sel.inline && editInlineText.value !== (sel.inlineText ?? ''))
        return true;
    if (editAutoSummary.value !== (sel.autoSummary ?? false))
        return true;
    if (editSummaryDirty.value !== (sel.summaryDirty ?? false))
        return true;
    const currentRag = sel.ragEnabled == null ? 'auto'
        : sel.ragEnabled ? 'on'
            : 'off';
    if (editRagEnabled.value !== currentRag)
        return true;
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
const propsCollapsed = ref(loadPropsCollapsed());
function loadPropsCollapsed() {
    try {
        const raw = sessionStorage.getItem(PROPS_COLLAPSED_KEY);
        // Default collapsed — the body editor is what the user actually
        // came here to look at, not the metadata.
        if (raw == null)
            return true;
        return raw === '1';
    }
    catch {
        return true;
    }
}
watch(propsCollapsed, (v) => {
    try {
        sessionStorage.setItem(PROPS_COLLAPSED_KEY, v ? '1' : '0');
    }
    catch {
        // sessionStorage may be unavailable (private mode, locked-down
        // browser); silently ignore — the state still works for the
        // current view, it just doesn't persist.
    }
});
function togglePropsCollapsed() {
    propsCollapsed.value = !propsCollapsed.value;
}
// Archive count surfaced from <DocumentArchives> so we can render a
// badge in the always-visible metadata strip — even when the
// properties panel is collapsed and the archive list itself is
// hidden. The child component owns the loading; we just mirror the
// count for the badge.
const archiveCount = ref(0);
function onArchiveCount(n) {
    archiveCount.value = n;
}
const createMode = ref('inline');
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
const createKind = ref('');
const createFiles = ref([]);
const createError = ref(null);
const creating = ref(false);
// Tracks the most recently auto-generated stub. Only when the
// user-visible content matches this value (or is empty) do we
// overwrite it on subsequent kind/mime changes — anything the user
// has typed manually stays untouched.
let lastGeneratedStub = '';
const uploadProgress = ref([]);
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
    if (!current)
        return base;
    if (base.some(o => o.value === current))
        return base;
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
    }
    else if (projectsState.projects.value.length > 0) {
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
    if (!next)
        return;
    // Initial bind in {@link onMounted}: project comes from the URL
    // and the loader there has already honored {@code ?path=…} and
    // {@code ?documentId=…}. Re-running this watcher would clobber
    // those query params with {@link DEFAULT_PATH_PREFIX} — which is
    // exactly what made browser-Back from /app.html drop the user
    // back into the project root instead of the folder they came
    // from. Only run the reset logic for user-initiated project
    // switches (prev was a non-null project name).
    if (prev == null)
        return;
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
let filterTimer = null;
function applyPathFilter(prefix, immediate = false) {
    const project = selectedProjectId.value;
    if (!project)
        return;
    if (filterTimer)
        clearTimeout(filterTimer);
    const fire = () => {
        docsState.clearSelection();
        pushQueryParams({
            path: prefix || null,
            documentId: null,
        });
        void docsState.loadPage(project, 0, prefix);
    };
    if (immediate)
        fire();
    else
        filterTimer = setTimeout(fire, 300);
}
/**
 * Sync internal state from the current URL. Triggered by browser
 * back/forward (popstate). Compares against the live state and only
 * touches what changed, so popstate doesn't cascade into another
 * pushState via the regular watchers (their dedup checks handle the
 * residual no-op as well).
 */
async function onPopstate() {
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
    }
    else if (!urlDocumentId && docsState.selected.value) {
        docsState.clearSelection();
    }
}
watch(() => docsState.selected.value?.id ?? null, (id) => {
    pushQueryParams({ documentId: id });
});
const projectOptions = computed(() => {
    const groupNameById = new Map();
    for (const g of projectsState.groups.value) {
        groupNameById.set(g.name, g.title?.trim() || g.name);
    }
    return projectsState.projects.value.map((p) => {
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
const focusZone = ref('main');
/**
 * Reload the tenant projects list after a successful create from
 * the sidebar (Add Group / Add Project). For new projects we also
 * select them so the user lands right in the freshly created
 * workspace; groups don't have a selection in the documents view.
 */
async function onProjectListDataChanged(payload) {
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
let searchDebounceTimer = null;
watch(documentFilter, (next) => {
    if (searchDebounceTimer !== null)
        clearTimeout(searchDebounceTimer);
    searchDebounceTimer = setTimeout(() => {
        const project = selectedProjectId.value;
        if (!project)
            return;
        void docsState.loadPage(project, 0, undefined, undefined, next);
    }, 250);
});
/**
 * Walk one path segment up: {@code documents/notes/foo/} →
 * {@code documents/notes/} → {@code documents/} → {@code ''}. Stops
 * at empty (button is disabled at that point).
 */
function pathSegmentBack() {
    const current = docsState.pathPrefix.value;
    if (!current)
        return;
    const noSlash = current.endsWith('/') ? current.slice(0, -1) : current;
    const lastSlash = noSlash.lastIndexOf('/');
    const next = lastSlash >= 0 ? noSlash.slice(0, lastSlash + 1) : '';
    applyPathFilter(next, true);
}
/**
 * Descend one level: append the clicked folder name to the current
 * pathPrefix (with the trailing slash that the server expects).
 */
function navigateIntoFolder(folder) {
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
const newFolderError = ref(null);
function openNewFolderModal() {
    newFolderName.value = '';
    newFolderError.value = null;
    showNewFolderModal.value = true;
}
function submitNewFolder() {
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
async function changePage(p) {
    if (!selectedProjectId.value)
        return;
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
function isAppDocument(doc) {
    return doc.kind === 'application' && !!doc.path?.endsWith('/_app.yaml');
}
async function openDocument(doc) {
    if (!doc.id)
        return;
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
async function openDocumentInEditor(doc) {
    if (!doc.id)
        return;
    await docsState.loadOne(doc.id);
    fillEditor();
}
function fillEditor() {
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
    if (isSheetDocument.value)
        contentTab.value = 'sheet';
    else if (isChartDocument.value)
        contentTab.value = 'chart';
    else if (isGraphDocument.value)
        contentTab.value = 'graph';
    else if (isRecordsDocument.value)
        contentTab.value = 'records';
    else if (isListDocument.value)
        contentTab.value = 'list';
    else if (isChecklistDocument.value)
        contentTab.value = 'checklist';
    else if (isMindmapDocument.value)
        contentTab.value = 'mindmap';
    else if (isTreeDocument.value)
        contentTab.value = 'tree';
    else if (isSlidesDocument.value)
        contentTab.value = 'slides';
    else if (isDiagramDocument.value)
        contentTab.value = 'diagram';
    else if (isCalendarDocument.value)
        contentTab.value = 'calendar';
    // Markdown lands on Preview first — the user opened a doc to read
    // it, editing is one click away on the Raw tab.
    else if (isMarkdownDocument.value)
        contentTab.value = 'preview';
    else
        contentTab.value = 'raw';
}
function onArchiveRestored(restored) {
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
const contentTab = ref('raw');
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
const isListDocument = computed(() => {
    const sel = docsState.selected.value;
    if (!sel?.inline)
        return false;
    if ((sel.kind ?? '').toLowerCase() !== 'list')
        return false;
    return isListMime(sel.mimeType);
});
const isChecklistDocument = computed(() => {
    const sel = docsState.selected.value;
    if (!sel?.inline)
        return false;
    if ((sel.kind ?? '').toLowerCase() !== 'checklist')
        return false;
    return isChecklistMime(sel.mimeType);
});
const isTreeDocument = computed(() => {
    const sel = docsState.selected.value;
    if (!sel?.inline)
        return false;
    if ((sel.kind ?? '').toLowerCase() !== 'tree')
        return false;
    return isTreeMime(sel.mimeType);
});
// Mindmap documents reuse the tree codec — same mime types, same
// per-item shape — and add a Mindmap render tab on top of the
// tree editor. See specification/doc-kind-mindmap.md §5.
const isMindmapDocument = computed(() => {
    const sel = docsState.selected.value;
    if (!sel?.inline)
        return false;
    if ((sel.kind ?? '').toLowerCase() !== 'mindmap')
        return false;
    return isTreeMime(sel.mimeType);
});
const isRecordsDocument = computed(() => {
    const sel = docsState.selected.value;
    if (!sel?.inline)
        return false;
    if ((sel.kind ?? '').toLowerCase() !== 'records')
        return false;
    return isRecordsMime(sel.mimeType);
});
// Graph documents: kind: graph + json/yaml only — markdown bodies
// fall back to the Raw editor (spec doc-kind-graph.md §3.3).
const isGraphDocument = computed(() => {
    const sel = docsState.selected.value;
    if (!sel?.inline)
        return false;
    if ((sel.kind ?? '').toLowerCase() !== 'graph')
        return false;
    return isGraphMime(sel.mimeType);
});
// Chart documents: kind: chart + json/yaml only — markdown bodies
// fall back to the Raw editor (spec doc-kind-chart.md §3.3).
const isChartDocument = computed(() => {
    const sel = docsState.selected.value;
    if (!sel?.inline)
        return false;
    if ((sel.kind ?? '').toLowerCase() !== 'chart')
        return false;
    return isChartMime(sel.mimeType);
});
// Sheet documents: kind: sheet + json/yaml only — markdown bodies
// fall back to the Raw editor (spec doc-kind-sheet.md §3.3).
const isSheetDocument = computed(() => {
    const sel = docsState.selected.value;
    if (!sel?.inline)
        return false;
    if ((sel.kind ?? '').toLowerCase() !== 'sheet')
        return false;
    return isSheetMime(sel.mimeType);
});
// Slides documents: kind: slides + md/json/yaml. v1 is read-only —
// edit happens in the Raw tab (spec doc-kind-slides.md §5.3).
const isSlidesDocument = computed(() => {
    const sel = docsState.selected.value;
    if (!sel?.inline)
        return false;
    if ((sel.kind ?? '').toLowerCase() !== 'slides')
        return false;
    return isSlidesMime(sel.mimeType);
});
// Diagram documents: kind: diagram + md/json/yaml. Renderer is Mermaid;
// edit happens in the Raw tab (spec doc-kind-diagram.md §5).
const isDiagramDocument = computed(() => {
    const sel = docsState.selected.value;
    if (!sel?.inline)
        return false;
    if ((sel.kind ?? '').toLowerCase() !== 'diagram')
        return false;
    return isDiagramMime(sel.mimeType);
});
// Calendar documents: kind: calendar + json/yaml. v1 is read-only
// (month + agenda); edits go through the Raw tab. Spec doc-kind-calendar.md.
//
// Registry-driven: the Kind comes from `@vance/kind-registry` (host
// built-in for now, addon-contributed once Calendar moves). The host
// looks up the entry once and stays generic — no Calendar-specific
// imports in this file.
const calendarKind = computed(() => resolveKind('calendar'));
const isCalendarDocument = computed(() => {
    const sel = docsState.selected.value;
    const kind = calendarKind.value;
    if (!sel?.inline || !kind)
        return false;
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
const isOfficeEditableDocument = computed(() => {
    const sel = docsState.selected.value;
    if (!sel)
        return false;
    const mime = (sel.mimeType ?? '').toLowerCase();
    return mime === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
        || mime === 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
});
const isMarkdownDocument = computed(() => {
    const sel = docsState.selected.value;
    if (!sel?.inline)
        return false;
    const mime = (sel.mimeType ?? '').toLowerCase();
    if (mime !== 'text/markdown' && mime !== 'text/x-markdown')
        return false;
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
const markdownFollowUp = computed(() => {
    const project = selectedProjectId.value;
    if (!project)
        return null;
    return {
        acceptHint: t('documents.followUp.acceptHint'),
        fetch: async (text, cursor) => {
            try {
                const body = {
                    text,
                    cursor,
                    count: 1,
                    mode: 'text-editor',
                };
                const resp = await brainFetch('POST', `follow-up/${encodeURIComponent(project)}`, { body });
                const first = resp.suggestions?.[0]?.text?.trim() ?? '';
                return first.length > 0 ? first : null;
            }
            catch {
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
const isSelectedInTrash = computed(() => (docsState.selected.value?.path ?? '').startsWith(TRASH_PREFIX));
const parsedList = computed(() => {
    if (!isListDocument.value)
        return { doc: null, error: null };
    try {
        const sel = docsState.selected.value;
        const doc = parseList(editInlineText.value, sel?.mimeType ?? '');
        return { doc, error: null };
    }
    catch (e) {
        if (e instanceof ListCodecError) {
            return { doc: null, error: e.message };
        }
        return { doc: null, error: e instanceof Error ? e.message : String(e) };
    }
});
const parsedChecklist = computed(() => {
    if (!isChecklistDocument.value)
        return { doc: null, error: null };
    try {
        const sel = docsState.selected.value;
        const doc = parseChecklist(editInlineText.value, sel?.mimeType ?? '');
        return { doc, error: null };
    }
    catch (e) {
        if (e instanceof ChecklistCodecError) {
            return { doc: null, error: e.message };
        }
        return { doc: null, error: e instanceof Error ? e.message : String(e) };
    }
});
const parsedTree = computed(() => {
    if (!isTreeDocument.value && !isMindmapDocument.value) {
        return { doc: null, error: null };
    }
    try {
        const sel = docsState.selected.value;
        const doc = parseTree(editInlineText.value, sel?.mimeType ?? '');
        return { doc, error: null };
    }
    catch (e) {
        if (e instanceof TreeCodecError) {
            return { doc: null, error: e.message };
        }
        return { doc: null, error: e instanceof Error ? e.message : String(e) };
    }
});
const parsedRecords = computed(() => {
    if (!isRecordsDocument.value)
        return { doc: null, error: null };
    try {
        const sel = docsState.selected.value;
        const doc = parseRecords(editInlineText.value, sel?.mimeType ?? '');
        return { doc, error: null };
    }
    catch (e) {
        if (e instanceof RecordsCodecError) {
            return { doc: null, error: e.message };
        }
        return { doc: null, error: e instanceof Error ? e.message : String(e) };
    }
});
const parsedGraph = computed(() => {
    if (!isGraphDocument.value)
        return { doc: null, error: null };
    try {
        const sel = docsState.selected.value;
        const doc = parseGraph(editInlineText.value, sel?.mimeType ?? '');
        return { doc, error: null };
    }
    catch (e) {
        if (e instanceof GraphCodecError) {
            return { doc: null, error: e.message };
        }
        return { doc: null, error: e instanceof Error ? e.message : String(e) };
    }
});
const parsedChart = computed(() => {
    if (!isChartDocument.value)
        return { doc: null, error: null };
    try {
        const sel = docsState.selected.value;
        const doc = parseChart(editInlineText.value, sel?.mimeType ?? '');
        return { doc, error: null };
    }
    catch (e) {
        if (e instanceof ChartCodecError) {
            return { doc: null, error: e.message };
        }
        return { doc: null, error: e instanceof Error ? e.message : String(e) };
    }
});
const parsedSheet = computed(() => {
    if (!isSheetDocument.value)
        return { doc: null, error: null };
    try {
        const sel = docsState.selected.value;
        const doc = parseSheet(editInlineText.value, sel?.mimeType ?? '');
        return { doc, error: null };
    }
    catch (e) {
        if (e instanceof SheetCodecError) {
            return { doc: null, error: e.message };
        }
        return { doc: null, error: e instanceof Error ? e.message : String(e) };
    }
});
const parsedSlides = computed(() => {
    if (!isSlidesDocument.value)
        return { doc: null, error: null };
    try {
        const sel = docsState.selected.value;
        const doc = parseSlides(editInlineText.value, sel?.mimeType ?? '');
        return { doc, error: null };
    }
    catch (e) {
        if (e instanceof SlidesCodecError) {
            return { doc: null, error: e.message };
        }
        return { doc: null, error: e instanceof Error ? e.message : String(e) };
    }
});
const parsedDiagram = computed(() => {
    if (!isDiagramDocument.value)
        return { doc: null, error: null };
    try {
        const sel = docsState.selected.value;
        const doc = parseDiagram(editInlineText.value, sel?.mimeType ?? '');
        return { doc, error: null };
    }
    catch (e) {
        if (e instanceof DiagramCodecError) {
            return { doc: null, error: e.message };
        }
        return { doc: null, error: e instanceof Error ? e.message : String(e) };
    }
});
const parsedCalendar = computed(() => {
    const kind = calendarKind.value;
    if (!isCalendarDocument.value || !kind?.parse)
        return { doc: null, error: null };
    try {
        const sel = docsState.selected.value;
        const doc = kind.parse(editInlineText.value, sel?.mimeType ?? '');
        return { doc, error: null };
    }
    catch (e) {
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
function onListChanged(updated) {
    const sel = docsState.selected.value;
    if (!sel?.mimeType)
        return;
    try {
        editInlineText.value = serializeList(updated, sel.mimeType);
        editError.value = null;
    }
    catch (e) {
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
function onChecklistChanged(updated) {
    const sel = docsState.selected.value;
    if (!sel?.mimeType)
        return;
    try {
        editInlineText.value = serializeChecklist(updated, sel.mimeType);
        editError.value = null;
    }
    catch (e) {
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
function onTreeChanged(updated) {
    const sel = docsState.selected.value;
    if (!sel?.mimeType)
        return;
    try {
        editInlineText.value = serializeTree(updated, sel.mimeType);
        editError.value = null;
    }
    catch (e) {
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
function onRecordsChanged(updated) {
    const sel = docsState.selected.value;
    if (!sel?.mimeType)
        return;
    try {
        editInlineText.value = serializeRecords(updated, sel.mimeType);
        editError.value = null;
    }
    catch (e) {
        editError.value = e instanceof Error ? e.message : String(e);
    }
}
/**
 * Bridge from the typed graph editor back to the raw body. Same
 * pattern as records — the editor emits a fresh
 * {@link GraphDocument}, we serialise into the document's mime
 * type and write into the raw editor's text.
 */
function onGraphChanged(updated) {
    const sel = docsState.selected.value;
    if (!sel?.mimeType)
        return;
    try {
        editInlineText.value = serializeGraph(updated, sel.mimeType);
        editError.value = null;
    }
    catch (e) {
        editError.value = e instanceof Error ? e.message : String(e);
    }
}
function onSheetChanged(updated) {
    const sel = docsState.selected.value;
    if (!sel?.mimeType)
        return;
    try {
        editInlineText.value = serializeSheet(updated, sel.mimeType);
        editError.value = null;
    }
    catch (e) {
        editError.value = e instanceof Error ? e.message : String(e);
    }
}
function onChartChanged(updated) {
    const sel = docsState.selected.value;
    if (!sel?.mimeType)
        return;
    try {
        editInlineText.value = serializeChart(updated, sel.mimeType);
        editError.value = null;
    }
    catch (e) {
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
const HELP_RULES = [
    { prefix: '_vance/recipes/', resource: 'recipe-field-docs.md' },
    { prefix: '_vance/strategies/', resource: 'strategy-field-docs.md' },
];
const helpResource = computed(() => {
    const path = docsState.selected.value?.path ?? '';
    if (!path)
        return null;
    const match = HELP_RULES.find((rule) => path.startsWith(rule.prefix));
    return match ? match.resource : null;
});
watch(helpResource, (resource) => {
    if (resource) {
        help.load(resource);
    }
    else {
        help.content.value = null;
        help.error.value = null;
    }
}, { immediate: true });
function backToList() {
    docsState.clearSelection();
    editError.value = null;
}
/**
 * Wrap {@link backToList} with a dirty-state check. When the user
 * has unsaved edits, open the discard-confirm modal instead of
 * leaving immediately. The modal's three actions resolve into
 * apply-and-leave / discard / stay.
 */
function requestBackToList() {
    if (isDirty.value) {
        showDiscardModal.value = true;
        return;
    }
    backToList();
}
/** Discard-modal action: drop the edits and return to the list. */
function discardAndBack() {
    showDiscardModal.value = false;
    backToList();
}
/**
 * Footer "discard changes" action: open the revert-confirm modal
 * (no-op when there are no unsaved edits — the button is hidden in
 * that case but guard anyway).
 */
function requestRevert() {
    if (!isDirty.value)
        return;
    showRevertModal.value = true;
}
/**
 * Re-fetches the selected document from the server and resets every
 * edit field via {@link fillEditor}. Used as the confirmed action
 * of the revert modal. On error keeps the modal closed and lets the
 * inline {@code docsState.error} surface the failure.
 */
async function revertChanges() {
    showRevertModal.value = false;
    const sel = docsState.selected.value;
    if (!sel)
        return;
    await docsState.loadOne(sel.id);
    fillEditor();
}
/** Discard-modal action: persist the edits, then return to the list
 *  if the server accepted them. On error stay on the detail view so
 *  the user can read the message (mirrors {@link save}). */
async function saveAndBack() {
    const ok = await apply();
    if (ok) {
        showDiscardModal.value = false;
        backToList();
    }
    else {
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
function onBeforeUnload(event) {
    if (!isDirty.value)
        return;
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
function downloadUrl(doc) {
    return documentContentUrl(doc.id, true);
}
function openCreateModal(prefill) {
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
    }
    else if (prefillPath) {
        createPath.value = docsState.pathPrefix.value;
        createName.value = prefillPath;
    }
    else {
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
];
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
function buildKindStub(kind, mime) {
    if (!kind)
        return '';
    const isMd = mime === 'text/markdown' || mime === 'text/x-markdown';
    const isJson = mime === 'application/json';
    const isYaml = mime === 'application/yaml'
        || mime === 'application/x-yaml'
        || mime === 'text/yaml';
    if (kind === 'list') {
        if (isMd)
            return '---\nkind: list\n---\n- item 1\n- item 2\n';
        if (isJson)
            return '{\n  "$meta": { "kind": "list" },\n  "items": [\n    { "text": "item 1" },\n    { "text": "item 2" }\n  ]\n}\n';
        if (isYaml)
            return '$meta:\n  kind: list\nitems:\n  - text: item 1\n  - text: item 2\n';
    }
    if (kind === 'checklist') {
        if (isMd)
            return '---\nkind: checklist\n---\n- [ ] open task\n- [x] done task\n- [~] in progress task\n';
        if (isJson)
            return '{\n  "$meta": { "kind": "checklist" },\n  "items": [\n    { "text": "open task" },\n    { "text": "done task", "status": "done" },\n    { "text": "in progress task", "status": "in_progress" }\n  ]\n}\n';
        if (isYaml)
            return '$meta:\n  kind: checklist\nitems:\n  - text: open task\n  - text: done task\n    status: done\n  - text: in progress task\n    status: in_progress\n';
    }
    if (kind === 'tree') {
        if (isMd)
            return '---\nkind: tree\n---\n- parent\n  - child\n';
        if (isJson)
            return '{\n  "$meta": { "kind": "tree" },\n  "items": [\n    { "text": "parent", "children": [\n      { "text": "child", "children": [] }\n    ]}\n  ]\n}\n';
        if (isYaml)
            return '$meta:\n  kind: tree\nitems:\n  - text: parent\n    children:\n      - text: child\n        children: []\n';
    }
    if (kind === 'mindmap') {
        if (isMd)
            return '---\nkind: mindmap\n---\n- root topic\n  - branch one\n  - branch two\n';
        if (isJson)
            return '{\n  "$meta": { "kind": "mindmap" },\n  "items": [\n    { "text": "root topic", "children": [\n      { "text": "branch one", "children": [] },\n      { "text": "branch two", "children": [] }\n    ]}\n  ]\n}\n';
        if (isYaml)
            return '$meta:\n  kind: mindmap\nitems:\n  - text: root topic\n    children:\n      - text: branch one\n        children: []\n      - text: branch two\n        children: []\n';
    }
    if (kind === 'records') {
        if (isMd)
            return '---\nkind: records\nschema: name, email, role\n---\n- Alice, alice@example.com, admin\n- Bob, bob@example.com, user\n';
        if (isJson)
            return '{\n  "$meta": { "kind": "records" },\n  "schema": ["name", "email", "role"],\n  "items": [\n    { "name": "Alice", "email": "alice@example.com", "role": "admin" },\n    { "name": "Bob", "email": "bob@example.com", "role": "user" }\n  ]\n}\n';
        if (isYaml)
            return '$meta:\n  kind: records\nschema: [name, email, role]\nitems:\n  - name: Alice\n    email: alice@example.com\n    role: admin\n  - name: Bob\n    email: bob@example.com\n    role: user\n';
    }
    if (kind === 'graph') {
        // Markdown isn't supported for graphs (spec §3.3); fall through
        // to the schema-less branch below if md is picked, so the user
        // gets just the header and a hint to switch mime type via Raw.
        if (isJson)
            return '{\n  "$meta": { "kind": "graph" },\n  "graph": { "directed": true },\n  "nodes": [\n    { "id": "alice", "label": "Alice" },\n    { "id": "bob", "label": "Bob" }\n  ],\n  "edges": [\n    { "source": "alice", "target": "bob" }\n  ]\n}\n';
        if (isYaml)
            return '$meta:\n  kind: graph\ngraph:\n  directed: true\nnodes:\n  - id: alice\n    label: Alice\n  - id: bob\n    label: Bob\nedges:\n  - source: alice\n    target: bob\n';
    }
    if (kind === 'chart') {
        // Markdown isn't supported for charts (spec §3.3) — same fallback
        // behaviour as graph.
        if (isJson)
            return '{\n  "$meta": { "kind": "chart" },\n  "chart": { "chartType": "line", "title": "New Chart" },\n  "xAxis": { "type": "category" },\n  "yAxis": { "type": "value" },\n  "series": [\n    { "name": "Series 1", "data": [\n      { "x": "A", "y": 10 },\n      { "x": "B", "y": 20 },\n      { "x": "C", "y": 15 }\n    ] }\n  ]\n}\n';
        if (isYaml)
            return '$meta:\n  kind: chart\nchart:\n  chartType: line\n  title: New Chart\nxAxis:\n  type: category\nyAxis:\n  type: value\nseries:\n  - name: Series 1\n    data:\n      - { x: A, y: 10 }\n      - { x: B, y: 20 }\n      - { x: C, y: 15 }\n';
    }
    if (kind === 'slides') {
        if (isMd)
            return '---\nkind: slides\nslides:\n  theme: default\n  aspect: "16:9"\n  paginate: true\n---\n\n# First slide\n\nWelcome to your deck.\n\n---\n\n## Second slide\n\n- bullet one\n- bullet two\n';
        if (isJson)
            return '{\n  "$meta": { "kind": "slides" },\n  "slides": { "theme": "default", "aspect": "16:9", "paginate": true },\n  "items": [\n    "# First slide\\n\\nWelcome to your deck.",\n    "## Second slide\\n\\n- bullet one\\n- bullet two"\n  ]\n}\n';
        if (isYaml)
            return '$meta:\n  kind: slides\nslides:\n  theme: default\n  aspect: "16:9"\n  paginate: true\nitems:\n  - |\n    # First slide\n\n    Welcome to your deck.\n  - |\n    ## Second slide\n\n    - bullet one\n    - bullet two\n';
    }
    if (kind === 'diagram') {
        // Mermaid is the default dialect; markdown is canonical (one
        // fenced ```mermaid block). JSON/YAML hold the source as a string.
        if (isMd)
            return '---\nkind: diagram\n---\n\n```mermaid\nflowchart TD\n  A[Start] --> B{Decision}\n  B -->|yes| C[Do it]\n  B -->|no| D[Skip]\n```\n';
        if (isJson)
            return '{\n  "$meta": { "kind": "diagram" },\n  "source": "flowchart TD\\n  A[Start] --> B{Decision}\\n  B -->|yes| C[Do it]\\n  B -->|no| D[Skip]\\n"\n}\n';
        if (isYaml)
            return '$meta:\n  kind: diagram\nsource: |\n  flowchart TD\n    A[Start] --> B{Decision}\n    B -->|yes| C[Do it]\n    B -->|no| D[Skip]\n';
    }
    if (kind === 'calendar') {
        // Markdown isn't supported for calendars (spec §3) — JSON / YAML
        // only. MD falls through to the schema-less branch.
        if (isJson)
            return '{\n  "$meta": { "kind": "calendar" },\n  "events": [\n    {\n      "id": "ev-1",\n      "title": "Sprint Planning",\n      "start": "2026-06-12T09:00",\n      "end": "2026-06-12T11:00",\n      "location": "Büro"\n    },\n    {\n      "id": "ev-2",\n      "title": "Urlaub",\n      "start": "2026-07-15",\n      "end": "2026-07-28",\n      "allDay": true,\n      "tags": ["private"]\n    }\n  ]\n}\n';
        if (isYaml)
            return '$meta:\n  kind: calendar\nevents:\n  - id: ev-1\n    title: Sprint Planning\n    start: "2026-06-12T09:00"\n    end: "2026-06-12T11:00"\n    location: Büro\n  - id: ev-2\n    title: Urlaub\n    start: "2026-07-15"\n    end: "2026-07-28"\n    allDay: true\n    tags: [private]\n';
    }
    if (kind === 'application') {
        // _app.yaml — turns a folder into a Vance application bundle.
        // Default stub uses app=calendar (the v1 reference type); the
        // user edits `app:` if they want a different (future) app
        // face. Markdown isn't supported for manifests.
        if (isJson)
            return '{\n  "$meta": { "kind": "application", "app": "calendar" },\n  "title": "My Calendar App",\n  "description": "Planning suite — one calendar per lane.",\n  "calendar": {\n    "window": { "from": "2026-06-01", "until": "2026-09-30" },\n    "lanes": {\n      "design":  { "title": "Design",  "color": "blue",   "order": 1 },\n      "backend": { "title": "Backend", "color": "green",  "order": 2 }\n    },\n    "gantt":     { "outputPath": "_gantt.md", "includeRecurring": false },\n    "conflicts": { "outputPath": "_conflicts.yaml", "ignoreWithinTags": ["private"] }\n  }\n}\n';
        if (isYaml)
            return '$meta:\n  kind: application\n  app: calendar\ntitle: My Calendar App\ndescription: Planning suite — one calendar per lane.\ncalendar:\n  window:\n    from: "2026-06-01"\n    until: "2026-09-30"\n  lanes:\n    design:  { title: Design,  color: blue,  order: 1 }\n    backend: { title: Backend, color: green, order: 2 }\n  gantt:\n    outputPath: _gantt.md\n    includeRecurring: false\n  conflicts:\n    outputPath: _conflicts.yaml\n    ignoreWithinTags: [private]\n';
    }
    if (kind === 'sheet') {
        // Markdown not supported for sheets (spec §3.3) — falls through
        // to the schema-less branch below if md is picked.
        if (isJson)
            return '{\n  "$meta": { "kind": "sheet" },\n  "schema": ["A", "B", "C"],\n  "rows": 5,\n  "cells": [\n    { "field": "A1", "data": "Item" },\n    { "field": "B1", "data": "Qty" },\n    { "field": "C1", "data": "Total" },\n    { "field": "A2", "data": "Apples" },\n    { "field": "B2", "data": "10" },\n    { "field": "C2", "data": "=B2*1.5" }\n  ]\n}\n';
        if (isYaml)
            return '$meta:\n  kind: sheet\nschema: [A, B, C]\nrows: 5\ncells:\n  - field: A1\n    data: Item\n  - field: B1\n    data: Qty\n  - field: C1\n    data: Total\n  - field: A2\n    data: Apples\n  - field: B2\n    data: "10"\n  - field: C2\n    data: "=B2*1.5"\n';
    }
    // Schema-less kinds — header only, body stays empty.
    if (isMd)
        return `---\nkind: ${kind}\n---\n`;
    if (isJson)
        return `{\n  "$meta": { "kind": "${kind}" }\n}\n`;
    if (isYaml)
        return `$meta:\n  kind: ${kind}\n`;
    return '';
}
// Auto-fill the body when the user picks a kind (or switches the
// mime type while a kind is set). Only writes when the editor is
// empty or still holds the last auto-generated stub — anything the
// user has typed by hand stays untouched.
watch([createKind, createMime], ([kind, mime]) => {
    if (!showCreateModal.value)
        return;
    if (createMode.value !== 'inline')
        return;
    const editorEmpty = createContent.value === '' || createContent.value === lastGeneratedStub;
    if (!editorEmpty)
        return;
    const stub = buildKindStub(kind, mime);
    createContent.value = stub;
    lastGeneratedStub = stub;
});
function setCreateMode(mode) {
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
async function submitCreate() {
    if (!selectedProjectId.value)
        return;
    creating.value = true;
    createError.value = null;
    try {
        const tags = createTagsRaw.value
            .split(',')
            .map((t) => t.trim())
            .filter((t) => t.length > 0);
        if (createMode.value === 'inline') {
            if (!createName.value.trim()) {
                createError.value = t('documents.create.nameRequired');
                return;
            }
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
            }
            else if (docsState.error.value) {
                createError.value = docsState.error.value;
            }
            return;
        }
        // Upload mode — one or many files.
        const files = createFiles.value;
        if (files.length === 0) {
            createError.value = t('documents.create.pickAtLeastOneFile');
            return;
        }
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
            }
            else if (docsState.error.value) {
                createError.value = docsState.error.value;
            }
            return;
        }
        // Multi-upload: sequential — keeps server load predictable and lets the
        // user see per-file progress. Each file gets its own slot in
        // `uploadProgress`; failures don't abort the rest.
        uploadProgress.value = files.map((f) => ({
            fileName: f.name,
            status: 'pending',
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
            }
            else {
                uploadProgress.value[i].status = 'error';
                uploadProgress.value[i].message = docsState.error.value ?? t('documents.create.uploadFailed');
            }
        }
        if (okCount === files.length) {
            // All good — close modal and refresh the list.
            showCreateModal.value = false;
        }
        else {
            createError.value = t('documents.create.multiUploadFailed', {
                failed: files.length - okCount,
                total: files.length,
            });
        }
    }
    finally {
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
async function saveSummary() {
    const sel = docsState.selected.value;
    if (!sel?.id)
        return;
    summarySaving.value = true;
    summarySaveMessage.value = null;
    try {
        const updated = await docsState.setSummary(sel.id, editSummary.value);
        if (updated) {
            summarySaveMessage.value = updated.summary ? 'Saved.' : 'Cleared.';
        }
    }
    catch (e) {
        summarySaveMessage.value =
            e instanceof Error ? e.message : 'Failed to save summary.';
    }
    finally {
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
async function apply() {
    const sel = docsState.selected.value;
    if (!sel?.id)
        return false;
    saving.value = true;
    editError.value = null;
    try {
        const body = { title: editTitle.value };
        if (sel.inline)
            body.inlineText = editInlineText.value;
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
        const currentRag = sel.ragEnabled == null ? 'auto'
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
    }
    finally {
        saving.value = false;
    }
}
/**
 * Save-and-close — applies the edits and returns to the list when
 * the server accepted them. On error, stays on the detail view so
 * the user can inspect the message and retry. See
 * specification/web-ui.md §7.7.
 */
async function save() {
    const ok = await apply();
    if (ok)
        backToList();
}
/**
 * Open the delete-confirmation modal. Actual deletion runs through
 * {@link confirmDelete} after the user confirms.
 */
function openDeleteModal() {
    editError.value = null;
    showDeleteModal.value = true;
}
/**
 * User confirmed — call the API and, on success, close the modal,
 * leave the detail view, and refresh the folder list (a deleted
 * document may have been the last in its folder).
 */
async function confirmDelete() {
    const sel = docsState.selected.value;
    if (!sel?.id)
        return;
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
    }
    finally {
        deleting.value = false;
    }
}
function syncQueryParam(key, value) {
    const url = new URL(window.location.href);
    if (value === null) {
        url.searchParams.delete(key);
    }
    else {
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
function pushQueryParams(updates) {
    const url = new URL(window.location.href);
    for (const [k, v] of Object.entries(updates)) {
        if (v === null || v === '')
            url.searchParams.delete(k);
        else
            url.searchParams.set(k, v);
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
const headerEntries = computed(() => {
    const headers = docsState.selected.value?.headers;
    if (!headers)
        return [];
    return Object.entries(headers).map(([key, value]) => ({ key, value }));
});
function selectedProjectTitle() {
    const id = selectedProjectId.value;
    if (!id)
        return null;
    const p = projectsState.projects.value.find((x) => x.name === id);
    return p?.title?.trim() || id;
}
const breadcrumbs = computed(() => {
    if (!selectedProjectId.value)
        return [];
    const crumbs = [];
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
            }
            else {
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
const formatBytes = (n) => {
    if (n < 1024)
        return `${n} B`;
    if (n < 1024 * 1024)
        return `${(n / 1024).toFixed(1)} KB`;
    return `${(n / (1024 * 1024)).toFixed(2)} MB`;
};
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['folder-row']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['props-toggle']} */ ;
// CSS variable injection 
// CSS variable injection end 
const __VLS_0 = {}.EditorShell;
/** @type {[typeof __VLS_components.EditorShell, typeof __VLS_components.EditorShell, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    ...{ 'onTitleClick': {} },
    focusZone: (__VLS_ctx.focusZone),
    title: (__VLS_ctx.$t('documents.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    wideRightPanel: (!!__VLS_ctx.helpResource),
    fullHeight: (true),
    focusModel: "auto",
    showSidebar: (true),
    showRightPanel: (!!__VLS_ctx.helpResource),
    showFooter: (!!__VLS_ctx.docsState.selected.value),
    titleClickable: true,
}));
const __VLS_2 = __VLS_1({
    ...{ 'onTitleClick': {} },
    focusZone: (__VLS_ctx.focusZone),
    title: (__VLS_ctx.$t('documents.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    wideRightPanel: (!!__VLS_ctx.helpResource),
    fullHeight: (true),
    focusModel: "auto",
    showSidebar: (true),
    showRightPanel: (!!__VLS_ctx.helpResource),
    showFooter: (!!__VLS_ctx.docsState.selected.value),
    titleClickable: true,
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
let __VLS_4;
let __VLS_5;
let __VLS_6;
const __VLS_7 = {
    onTitleClick: (...[$event]) => {
        __VLS_ctx.focusZone = 'sidebar';
    }
};
var __VLS_8 = {};
__VLS_3.slots.default;
{
    const { sidebar: __VLS_thisSlot } = __VLS_3.slots;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-2" },
    });
    const __VLS_9 = {}.ProjectListSidebar;
    /** @type {[typeof __VLS_components.ProjectListSidebar, typeof __VLS_components.ProjectListSidebar, ]} */ ;
    // @ts-ignore
    const __VLS_10 = __VLS_asFunctionalComponent(__VLS_9, new __VLS_9({
        ...{ 'onFocusMain': {} },
        ...{ 'onDataChanged': {} },
        selectedProject: (__VLS_ctx.selectedProjectId),
        groups: (__VLS_ctx.projectsState.groups.value),
        projects: (__VLS_ctx.projectsState.projects.value),
        loading: (__VLS_ctx.projectsState.loading.value),
        error: (__VLS_ctx.projectsState.error.value),
        heading: (__VLS_ctx.$t('documents.projectsTitle')),
        filterPlaceholder: (__VLS_ctx.$t('documents.projectFilterPlaceholder')),
        ungroupedLabel: (__VLS_ctx.$t('documents.ungrouped')),
        editEnabled: true,
    }));
    const __VLS_11 = __VLS_10({
        ...{ 'onFocusMain': {} },
        ...{ 'onDataChanged': {} },
        selectedProject: (__VLS_ctx.selectedProjectId),
        groups: (__VLS_ctx.projectsState.groups.value),
        projects: (__VLS_ctx.projectsState.projects.value),
        loading: (__VLS_ctx.projectsState.loading.value),
        error: (__VLS_ctx.projectsState.error.value),
        heading: (__VLS_ctx.$t('documents.projectsTitle')),
        filterPlaceholder: (__VLS_ctx.$t('documents.projectFilterPlaceholder')),
        ungroupedLabel: (__VLS_ctx.$t('documents.ungrouped')),
        editEnabled: true,
    }, ...__VLS_functionalComponentArgsRest(__VLS_10));
    let __VLS_13;
    let __VLS_14;
    let __VLS_15;
    const __VLS_16 = {
        onFocusMain: (...[$event]) => {
            __VLS_ctx.focusZone = 'main';
        }
    };
    const __VLS_17 = {
        onDataChanged: (__VLS_ctx.onProjectListDataChanged)
    };
    __VLS_12.slots.default;
    {
        const { loading: __VLS_thisSlot } = __VLS_12.slots;
        (__VLS_ctx.$t('chat.picker.loading'));
    }
    {
        const { 'filter-no-match': __VLS_thisSlot } = __VLS_12.slots;
        const [{ filter }] = __VLS_getSlotParams(__VLS_thisSlot);
        (__VLS_ctx.$t('documents.projectFilterNoMatch', { filter }));
    }
    var __VLS_12;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "h-full min-h-0 flex flex-col" },
});
if (__VLS_ctx.selectedProjectId && !__VLS_ctx.docsState.selected.value && __VLS_ctx.projectOptions.length > 0) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "px-6 pt-4 pb-3 border-b border-base-300 bg-base-100 flex items-center gap-3" },
    });
    const __VLS_18 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_19 = __VLS_asFunctionalComponent(__VLS_18, new __VLS_18({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        disabled: (!__VLS_ctx.docsState.pathPrefix.value),
        title: (__VLS_ctx.$t('documents.pathBack')),
    }));
    const __VLS_20 = __VLS_19({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        disabled: (!__VLS_ctx.docsState.pathPrefix.value),
        title: (__VLS_ctx.$t('documents.pathBack')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_19));
    let __VLS_22;
    let __VLS_23;
    let __VLS_24;
    const __VLS_25 = {
        onClick: (__VLS_ctx.pathSegmentBack)
    };
    __VLS_21.slots.default;
    var __VLS_21;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 min-w-0 font-mono text-sm opacity-70 truncate" },
    });
    (__VLS_ctx.docsState.pathPrefix.value || '/');
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "w-[150px] shrink-0" },
    });
    const __VLS_26 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_27 = __VLS_asFunctionalComponent(__VLS_26, new __VLS_26({
        modelValue: (__VLS_ctx.documentFilter),
        placeholder: (__VLS_ctx.$t('documents.searchPlaceholder')),
    }));
    const __VLS_28 = __VLS_27({
        modelValue: (__VLS_ctx.documentFilter),
        placeholder: (__VLS_ctx.$t('documents.searchPlaceholder')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_27));
    const __VLS_30 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_31 = __VLS_asFunctionalComponent(__VLS_30, new __VLS_30({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        title: (__VLS_ctx.$t('documents.newFolder')),
    }));
    const __VLS_32 = __VLS_31({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        title: (__VLS_ctx.$t('documents.newFolder')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_31));
    let __VLS_34;
    let __VLS_35;
    let __VLS_36;
    const __VLS_37 = {
        onClick: (__VLS_ctx.openNewFolderModal)
    };
    __VLS_33.slots.default;
    var __VLS_33;
    const __VLS_38 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_39 = __VLS_asFunctionalComponent(__VLS_38, new __VLS_38({
        ...{ 'onClick': {} },
        variant: "primary",
        size: "sm",
        title: (__VLS_ctx.$t('documents.newDocument')),
    }));
    const __VLS_40 = __VLS_39({
        ...{ 'onClick': {} },
        variant: "primary",
        size: "sm",
        title: (__VLS_ctx.$t('documents.newDocument')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_39));
    let __VLS_42;
    let __VLS_43;
    let __VLS_44;
    const __VLS_45 = {
        onClick: (...[$event]) => {
            if (!(__VLS_ctx.selectedProjectId && !__VLS_ctx.docsState.selected.value && __VLS_ctx.projectOptions.length > 0))
                return;
            __VLS_ctx.openCreateModal();
        }
    };
    __VLS_41.slots.default;
    var __VLS_41;
}
else if (__VLS_ctx.selectedProjectId && __VLS_ctx.docsState.selected.value) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "px-6 pt-4 pb-3 border-b border-base-300 bg-base-100 flex items-center gap-x-3 gap-y-1 flex-wrap" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center gap-3 min-w-0 flex-1 basis-[16rem]" },
    });
    const __VLS_46 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_47 = __VLS_asFunctionalComponent(__VLS_46, new __VLS_46({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        title: (__VLS_ctx.$t('documents.backToList')),
    }));
    const __VLS_48 = __VLS_47({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
        title: (__VLS_ctx.$t('documents.backToList')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_47));
    let __VLS_50;
    let __VLS_51;
    let __VLS_52;
    const __VLS_53 = {
        onClick: (__VLS_ctx.requestBackToList)
    };
    __VLS_49.slots.default;
    var __VLS_49;
    /** @type {[typeof DocumentIcon, ]} */ ;
    // @ts-ignore
    const __VLS_54 = __VLS_asFunctionalComponent(DocumentIcon, new DocumentIcon({
        path: (__VLS_ctx.docsState.selected.value.path),
        mimeType: (__VLS_ctx.docsState.selected.value.mimeType),
        kind: (__VLS_ctx.docsState.selected.value.kind),
    }));
    const __VLS_55 = __VLS_54({
        path: (__VLS_ctx.docsState.selected.value.path),
        mimeType: (__VLS_ctx.docsState.selected.value.mimeType),
        kind: (__VLS_ctx.docsState.selected.value.kind),
    }, ...__VLS_functionalComponentArgsRest(__VLS_54));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "font-semibold truncate" },
    });
    (__VLS_ctx.docsState.selected.value.title || __VLS_ctx.docsState.selected.value.name);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "font-mono text-sm opacity-60 truncate shrink-0 max-w-full" },
    });
    (__VLS_ctx.docsState.selected.value.name);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-xs opacity-70 flex items-center gap-3 shrink-0" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.formatBytes(__VLS_ctx.docsState.selected.value.size));
    if (__VLS_ctx.docsState.selected.value.mimeType) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.docsState.selected.value.mimeType);
    }
    if (__VLS_ctx.docsState.selected.value.createdBy) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.$t('documents.detail.sizeBy', { user: __VLS_ctx.docsState.selected.value.createdBy }));
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex-1 min-h-0 overflow-y-auto" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "container mx-auto px-4 py-4 max-w-5xl" },
});
if (__VLS_ctx.projectsState.error.value) {
    const __VLS_57 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_58 = __VLS_asFunctionalComponent(__VLS_57, new __VLS_57({
        variant: "error",
        ...{ class: "mb-4" },
    }));
    const __VLS_59 = __VLS_58({
        variant: "error",
        ...{ class: "mb-4" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_58));
    __VLS_60.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.projectsState.error.value);
    var __VLS_60;
}
if (!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0) {
    const __VLS_61 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_62 = __VLS_asFunctionalComponent(__VLS_61, new __VLS_61({
        headline: (__VLS_ctx.$t('documents.noProjectsHeadline')),
        body: (__VLS_ctx.$t('documents.noProjectsBody')),
    }));
    const __VLS_63 = __VLS_62({
        headline: (__VLS_ctx.$t('documents.noProjectsHeadline')),
        body: (__VLS_ctx.$t('documents.noProjectsBody')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_62));
}
else if (!__VLS_ctx.selectedProjectId) {
    const __VLS_65 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_66 = __VLS_asFunctionalComponent(__VLS_65, new __VLS_65({
        headline: (__VLS_ctx.$t('documents.pickAProjectHeadline')),
        body: (__VLS_ctx.$t('documents.pickAProjectBody')),
    }));
    const __VLS_67 = __VLS_66({
        headline: (__VLS_ctx.$t('documents.pickAProjectHeadline')),
        body: (__VLS_ctx.$t('documents.pickAProjectBody')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_66));
}
else if (__VLS_ctx.docsState.selected.value) {
    const __VLS_69 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_70 = __VLS_asFunctionalComponent(__VLS_69, new __VLS_69({}));
    const __VLS_71 = __VLS_70({}, ...__VLS_functionalComponentArgsRest(__VLS_70));
    __VLS_72.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-xs opacity-60 flex flex-wrap gap-3 items-center" },
    });
    if (__VLS_ctx.docsState.selected.value.kind) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "badge badge-info badge-sm" },
            title: (__VLS_ctx.$t('documents.detail.kindBadgeTooltip')),
        });
        (__VLS_ctx.$t('documents.detail.kindLabel', { kind: __VLS_ctx.docsState.selected.value.kind }));
    }
    if (__VLS_ctx.isDirty) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "badge badge-warning badge-sm" },
            title: (__VLS_ctx.$t('documents.detail.changedBadgeTooltip')),
        });
        (__VLS_ctx.$t('documents.detail.changedBadge'));
    }
    if (__VLS_ctx.archiveCount > 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                        return;
                    if (!!(!__VLS_ctx.selectedProjectId))
                        return;
                    if (!(__VLS_ctx.docsState.selected.value))
                        return;
                    if (!(__VLS_ctx.archiveCount > 0))
                        return;
                    __VLS_ctx.propsCollapsed = false;
                } },
            type: "button",
            ...{ class: "badge badge-ghost badge-sm cursor-pointer" },
            title: (__VLS_ctx.$t('documents.detail.versionsBadgeTooltip')),
        });
        (__VLS_ctx.$t('documents.detail.versionsBadge', { count: __VLS_ctx.archiveCount }));
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "grow" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (__VLS_ctx.togglePropsCollapsed) },
        type: "button",
        ...{ class: "props-toggle text-xs font-medium opacity-70 hover:opacity-100" },
        title: (__VLS_ctx.propsCollapsed
            ? __VLS_ctx.$t('documents.detail.propsExpandHint')
            : __VLS_ctx.$t('documents.detail.propsCollapseHint')),
        'aria-expanded': (!__VLS_ctx.propsCollapsed),
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "props-toggle__chevron" },
        'aria-hidden': "true",
    });
    (__VLS_ctx.propsCollapsed ? '▸' : '▾');
    (__VLS_ctx.$t('documents.detail.propsToggleLabel'));
    if (!__VLS_ctx.propsCollapsed && __VLS_ctx.headerEntries.length > 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "mt-3 border border-base-300 rounded-md overflow-hidden" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "px-3 py-2 bg-base-200 text-xs uppercase opacity-70" },
        });
        (__VLS_ctx.$t('documents.detail.frontMatter'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.table, __VLS_intrinsicElements.table)({
            ...{ class: "table table-xs" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.tbody, __VLS_intrinsicElements.tbody)({});
        for (const [entry] of __VLS_getVForSourceType((__VLS_ctx.headerEntries))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.tr, __VLS_intrinsicElements.tr)({
                key: (entry.key),
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "font-mono opacity-70 w-1/3" },
            });
            (entry.key);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.td, __VLS_intrinsicElements.td)({
                ...{ class: "font-mono break-all" },
            });
            (entry.value);
        }
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "mt-3 border border-base-300 rounded-md overflow-hidden" },
    });
    __VLS_asFunctionalDirective(__VLS_directives.vShow)(null, { ...__VLS_directiveBindingRestFields, value: (!__VLS_ctx.propsCollapsed) }, null, null);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "px-3 py-2 bg-base-200 text-xs uppercase opacity-70" },
    });
    (__VLS_ctx.$t('documents.detail.summary.heading'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "p-3 flex flex-col gap-3" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-xs opacity-70 mb-1" },
    });
    (__VLS_ctx.$t('documents.detail.summary.summaryLabel'));
    if (__VLS_ctx.docsState.selected.value.summary) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "text-sm whitespace-pre-wrap" },
        });
        (__VLS_ctx.docsState.selected.value.summary);
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "text-sm italic opacity-60" },
        });
        (__VLS_ctx.$t('documents.detail.summary.summaryEmpty'));
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "text-xs opacity-60 mt-1" },
    });
    (__VLS_ctx.docsState.selected.value.summarizedAtMs
        ? __VLS_ctx.$t('documents.detail.summary.summarizedAt', {
            when: new Date(__VLS_ctx.docsState.selected.value.summarizedAtMs).toLocaleString(),
        })
        : __VLS_ctx.$t('documents.detail.summary.summarizedNever'));
    const __VLS_73 = {}.VCheckbox;
    /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
    // @ts-ignore
    const __VLS_74 = __VLS_asFunctionalComponent(__VLS_73, new __VLS_73({
        modelValue: (__VLS_ctx.editAutoSummary),
        label: (__VLS_ctx.$t('documents.detail.summary.autoSummaryLabel')),
        help: (__VLS_ctx.$t('documents.detail.summary.autoSummaryHelp')),
        disabled: (__VLS_ctx.saving),
    }));
    const __VLS_75 = __VLS_74({
        modelValue: (__VLS_ctx.editAutoSummary),
        label: (__VLS_ctx.$t('documents.detail.summary.autoSummaryLabel')),
        help: (__VLS_ctx.$t('documents.detail.summary.autoSummaryHelp')),
        disabled: (__VLS_ctx.saving),
    }, ...__VLS_functionalComponentArgsRest(__VLS_74));
    const __VLS_77 = {}.VCheckbox;
    /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
    // @ts-ignore
    const __VLS_78 = __VLS_asFunctionalComponent(__VLS_77, new __VLS_77({
        modelValue: (__VLS_ctx.editSummaryDirty),
        label: (__VLS_ctx.$t('documents.detail.summary.summaryDirtyLabel')),
        help: (__VLS_ctx.$t('documents.detail.summary.summaryDirtyHelp')),
        disabled: (__VLS_ctx.saving),
    }));
    const __VLS_79 = __VLS_78({
        modelValue: (__VLS_ctx.editSummaryDirty),
        label: (__VLS_ctx.$t('documents.detail.summary.summaryDirtyLabel')),
        help: (__VLS_ctx.$t('documents.detail.summary.summaryDirtyHelp')),
        disabled: (__VLS_ctx.saving),
    }, ...__VLS_functionalComponentArgsRest(__VLS_78));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-1" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.label, __VLS_intrinsicElements.label)({
        ...{ class: "text-xs opacity-70" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex gap-4 text-sm" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.label, __VLS_intrinsicElements.label)({
        ...{ class: "flex items-center gap-1.5 cursor-pointer" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
        type: "radio",
        value: "auto",
        disabled: (__VLS_ctx.saving),
    });
    (__VLS_ctx.editRagEnabled);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.label, __VLS_intrinsicElements.label)({
        ...{ class: "flex items-center gap-1.5 cursor-pointer" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
        type: "radio",
        value: "on",
        disabled: (__VLS_ctx.saving),
    });
    (__VLS_ctx.editRagEnabled);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.label, __VLS_intrinsicElements.label)({
        ...{ class: "flex items-center gap-1.5 cursor-pointer" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
        type: "radio",
        value: "off",
        disabled: (__VLS_ctx.saving),
    });
    (__VLS_ctx.editRagEnabled);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "text-xs opacity-60" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.code, __VLS_intrinsicElements.code)({});
    /** @type {[typeof DocumentArchives, ]} */ ;
    // @ts-ignore
    const __VLS_81 = __VLS_asFunctionalComponent(DocumentArchives, new DocumentArchives({
        ...{ 'onRestored': {} },
        ...{ 'onUpdate:count': {} },
        document: (__VLS_ctx.docsState.selected.value),
    }));
    const __VLS_82 = __VLS_81({
        ...{ 'onRestored': {} },
        ...{ 'onUpdate:count': {} },
        document: (__VLS_ctx.docsState.selected.value),
    }, ...__VLS_functionalComponentArgsRest(__VLS_81));
    let __VLS_84;
    let __VLS_85;
    let __VLS_86;
    const __VLS_87 = {
        onRestored: (__VLS_ctx.onArchiveRestored)
    };
    const __VLS_88 = {
        'onUpdate:count': (__VLS_ctx.onArchiveCount)
    };
    __VLS_asFunctionalDirective(__VLS_directives.vShow)(null, { ...__VLS_directiveBindingRestFields, value: (!__VLS_ctx.propsCollapsed) }, null, null);
    var __VLS_83;
    if (!__VLS_ctx.docsState.selected.value.inline) {
        const __VLS_89 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_90 = __VLS_asFunctionalComponent(__VLS_89, new __VLS_89({
            variant: "info",
            ...{ class: "mt-3" },
        }));
        const __VLS_91 = __VLS_90({
            variant: "info",
            ...{ class: "mt-3" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_90));
        __VLS_92.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.$t('documents.detail.readOnlyNote'));
        var __VLS_92;
    }
    if (__VLS_ctx.editError) {
        const __VLS_93 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_94 = __VLS_asFunctionalComponent(__VLS_93, new __VLS_93({
            variant: "error",
            ...{ class: "mt-3" },
        }));
        const __VLS_95 = __VLS_94({
            variant: "error",
            ...{ class: "mt-3" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_94));
        __VLS_96.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.editError);
        var __VLS_96;
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-3 mt-3" },
    });
    if (!__VLS_ctx.propsCollapsed) {
        const __VLS_97 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_98 = __VLS_asFunctionalComponent(__VLS_97, new __VLS_97({
            modelValue: (__VLS_ctx.editTitle),
            label: (__VLS_ctx.$t('documents.detail.titleLabel')),
            disabled: (__VLS_ctx.saving),
        }));
        const __VLS_99 = __VLS_98({
            modelValue: (__VLS_ctx.editTitle),
            label: (__VLS_ctx.$t('documents.detail.titleLabel')),
            disabled: (__VLS_ctx.saving),
        }, ...__VLS_functionalComponentArgsRest(__VLS_98));
        const __VLS_101 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_102 = __VLS_asFunctionalComponent(__VLS_101, new __VLS_101({
            modelValue: (__VLS_ctx.editPath),
            label: (__VLS_ctx.$t('documents.detail.pathLabel')),
            disabled: (__VLS_ctx.saving),
            help: (__VLS_ctx.$t('documents.detail.pathHelp')),
        }));
        const __VLS_103 = __VLS_102({
            modelValue: (__VLS_ctx.editPath),
            label: (__VLS_ctx.$t('documents.detail.pathLabel')),
            disabled: (__VLS_ctx.saving),
            help: (__VLS_ctx.$t('documents.detail.pathHelp')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_102));
        const __VLS_105 = {}.VSelect;
        /** @type {[typeof __VLS_components.VSelect, ]} */ ;
        // @ts-ignore
        const __VLS_106 = __VLS_asFunctionalComponent(__VLS_105, new __VLS_105({
            modelValue: (__VLS_ctx.editMimeType),
            options: (__VLS_ctx.editMimeOptions),
            label: (__VLS_ctx.$t('documents.detail.mimeTypeLabel')),
            disabled: (__VLS_ctx.saving),
            help: (__VLS_ctx.$t('documents.detail.mimeTypeHelp')),
        }));
        const __VLS_107 = __VLS_106({
            modelValue: (__VLS_ctx.editMimeType),
            options: (__VLS_ctx.editMimeOptions),
            label: (__VLS_ctx.$t('documents.detail.mimeTypeLabel')),
            disabled: (__VLS_ctx.saving),
            help: (__VLS_ctx.$t('documents.detail.mimeTypeHelp')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_106));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex flex-col gap-1" },
        });
        const __VLS_109 = {}.VTextarea;
        /** @type {[typeof __VLS_components.VTextarea, ]} */ ;
        // @ts-ignore
        const __VLS_110 = __VLS_asFunctionalComponent(__VLS_109, new __VLS_109({
            modelValue: (__VLS_ctx.editSummary),
            label: (__VLS_ctx.$t('documents.detail.summaryEditorLabel')),
            rows: (3),
            disabled: (__VLS_ctx.summarySaving),
            help: (__VLS_ctx.$t('documents.detail.summaryEditorHelp')),
        }));
        const __VLS_111 = __VLS_110({
            modelValue: (__VLS_ctx.editSummary),
            label: (__VLS_ctx.$t('documents.detail.summaryEditorLabel')),
            rows: (3),
            disabled: (__VLS_ctx.summarySaving),
            help: (__VLS_ctx.$t('documents.detail.summaryEditorHelp')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_110));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center gap-2 mt-1" },
        });
        const __VLS_113 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_114 = __VLS_asFunctionalComponent(__VLS_113, new __VLS_113({
            ...{ 'onClick': {} },
            size: "sm",
            variant: "ghost",
            disabled: (__VLS_ctx.summarySaving || __VLS_ctx.editSummary === (__VLS_ctx.docsState.selected.value.summary ?? '')),
        }));
        const __VLS_115 = __VLS_114({
            ...{ 'onClick': {} },
            size: "sm",
            variant: "ghost",
            disabled: (__VLS_ctx.summarySaving || __VLS_ctx.editSummary === (__VLS_ctx.docsState.selected.value.summary ?? '')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_114));
        let __VLS_117;
        let __VLS_118;
        let __VLS_119;
        const __VLS_120 = {
            onClick: (__VLS_ctx.saveSummary)
        };
        __VLS_116.slots.default;
        (__VLS_ctx.$t('documents.detail.summaryEditorSave'));
        var __VLS_116;
        if (__VLS_ctx.summarySaveMessage) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-xs text-base-content/70" },
            });
            (__VLS_ctx.summarySaveMessage);
        }
    }
    if (__VLS_ctx.docsState.selected.value.inline) {
        if (__VLS_ctx.isListDocument) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "content-tabs" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                            return;
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value.inline))
                            return;
                        if (!(__VLS_ctx.isListDocument))
                            return;
                        __VLS_ctx.contentTab = 'list';
                    } },
                type: "button",
                ...{ class: "content-tab" },
                ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'list' }) },
            });
            (__VLS_ctx.$t('documents.detail.tabList'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                            return;
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value.inline))
                            return;
                        if (!(__VLS_ctx.isListDocument))
                            return;
                        __VLS_ctx.contentTab = 'raw';
                    } },
                type: "button",
                ...{ class: "content-tab" },
                ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'raw' }) },
            });
            (__VLS_ctx.$t('documents.detail.tabRaw'));
        }
        else if (__VLS_ctx.isChecklistDocument) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "content-tabs" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                            return;
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value.inline))
                            return;
                        if (!!(__VLS_ctx.isListDocument))
                            return;
                        if (!(__VLS_ctx.isChecklistDocument))
                            return;
                        __VLS_ctx.contentTab = 'checklist';
                    } },
                type: "button",
                ...{ class: "content-tab" },
                ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'checklist' }) },
            });
            (__VLS_ctx.$t('documents.detail.tabChecklist'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                            return;
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value.inline))
                            return;
                        if (!!(__VLS_ctx.isListDocument))
                            return;
                        if (!(__VLS_ctx.isChecklistDocument))
                            return;
                        __VLS_ctx.contentTab = 'raw';
                    } },
                type: "button",
                ...{ class: "content-tab" },
                ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'raw' }) },
            });
            (__VLS_ctx.$t('documents.detail.tabRaw'));
        }
        else if (__VLS_ctx.isSheetDocument) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "content-tabs" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                            return;
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value.inline))
                            return;
                        if (!!(__VLS_ctx.isListDocument))
                            return;
                        if (!!(__VLS_ctx.isChecklistDocument))
                            return;
                        if (!(__VLS_ctx.isSheetDocument))
                            return;
                        __VLS_ctx.contentTab = 'sheet';
                    } },
                type: "button",
                ...{ class: "content-tab" },
                ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'sheet' }) },
            });
            (__VLS_ctx.$t('documents.detail.tabSheet'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                            return;
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value.inline))
                            return;
                        if (!!(__VLS_ctx.isListDocument))
                            return;
                        if (!!(__VLS_ctx.isChecklistDocument))
                            return;
                        if (!(__VLS_ctx.isSheetDocument))
                            return;
                        __VLS_ctx.contentTab = 'raw';
                    } },
                type: "button",
                ...{ class: "content-tab" },
                ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'raw' }) },
            });
            (__VLS_ctx.$t('documents.detail.tabRaw'));
        }
        else if (__VLS_ctx.isGraphDocument) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "content-tabs" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                            return;
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value.inline))
                            return;
                        if (!!(__VLS_ctx.isListDocument))
                            return;
                        if (!!(__VLS_ctx.isChecklistDocument))
                            return;
                        if (!!(__VLS_ctx.isSheetDocument))
                            return;
                        if (!(__VLS_ctx.isGraphDocument))
                            return;
                        __VLS_ctx.contentTab = 'graph';
                    } },
                type: "button",
                ...{ class: "content-tab" },
                ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'graph' }) },
            });
            (__VLS_ctx.$t('documents.detail.tabGraph'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                            return;
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value.inline))
                            return;
                        if (!!(__VLS_ctx.isListDocument))
                            return;
                        if (!!(__VLS_ctx.isChecklistDocument))
                            return;
                        if (!!(__VLS_ctx.isSheetDocument))
                            return;
                        if (!(__VLS_ctx.isGraphDocument))
                            return;
                        __VLS_ctx.contentTab = 'raw';
                    } },
                type: "button",
                ...{ class: "content-tab" },
                ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'raw' }) },
            });
            (__VLS_ctx.$t('documents.detail.tabRaw'));
        }
        else if (__VLS_ctx.isChartDocument) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "content-tabs" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                            return;
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value.inline))
                            return;
                        if (!!(__VLS_ctx.isListDocument))
                            return;
                        if (!!(__VLS_ctx.isChecklistDocument))
                            return;
                        if (!!(__VLS_ctx.isSheetDocument))
                            return;
                        if (!!(__VLS_ctx.isGraphDocument))
                            return;
                        if (!(__VLS_ctx.isChartDocument))
                            return;
                        __VLS_ctx.contentTab = 'chart';
                    } },
                type: "button",
                ...{ class: "content-tab" },
                ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'chart' }) },
            });
            (__VLS_ctx.$t('documents.detail.tabChart'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                            return;
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value.inline))
                            return;
                        if (!!(__VLS_ctx.isListDocument))
                            return;
                        if (!!(__VLS_ctx.isChecklistDocument))
                            return;
                        if (!!(__VLS_ctx.isSheetDocument))
                            return;
                        if (!!(__VLS_ctx.isGraphDocument))
                            return;
                        if (!(__VLS_ctx.isChartDocument))
                            return;
                        __VLS_ctx.contentTab = 'raw';
                    } },
                type: "button",
                ...{ class: "content-tab" },
                ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'raw' }) },
            });
            (__VLS_ctx.$t('documents.detail.tabRaw'));
        }
        else if (__VLS_ctx.isRecordsDocument) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "content-tabs" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                            return;
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value.inline))
                            return;
                        if (!!(__VLS_ctx.isListDocument))
                            return;
                        if (!!(__VLS_ctx.isChecklistDocument))
                            return;
                        if (!!(__VLS_ctx.isSheetDocument))
                            return;
                        if (!!(__VLS_ctx.isGraphDocument))
                            return;
                        if (!!(__VLS_ctx.isChartDocument))
                            return;
                        if (!(__VLS_ctx.isRecordsDocument))
                            return;
                        __VLS_ctx.contentTab = 'records';
                    } },
                type: "button",
                ...{ class: "content-tab" },
                ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'records' }) },
            });
            (__VLS_ctx.$t('documents.detail.tabRecords'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                            return;
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value.inline))
                            return;
                        if (!!(__VLS_ctx.isListDocument))
                            return;
                        if (!!(__VLS_ctx.isChecklistDocument))
                            return;
                        if (!!(__VLS_ctx.isSheetDocument))
                            return;
                        if (!!(__VLS_ctx.isGraphDocument))
                            return;
                        if (!!(__VLS_ctx.isChartDocument))
                            return;
                        if (!(__VLS_ctx.isRecordsDocument))
                            return;
                        __VLS_ctx.contentTab = 'raw';
                    } },
                type: "button",
                ...{ class: "content-tab" },
                ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'raw' }) },
            });
            (__VLS_ctx.$t('documents.detail.tabRaw'));
        }
        else if (__VLS_ctx.isMindmapDocument) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "content-tabs" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                            return;
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value.inline))
                            return;
                        if (!!(__VLS_ctx.isListDocument))
                            return;
                        if (!!(__VLS_ctx.isChecklistDocument))
                            return;
                        if (!!(__VLS_ctx.isSheetDocument))
                            return;
                        if (!!(__VLS_ctx.isGraphDocument))
                            return;
                        if (!!(__VLS_ctx.isChartDocument))
                            return;
                        if (!!(__VLS_ctx.isRecordsDocument))
                            return;
                        if (!(__VLS_ctx.isMindmapDocument))
                            return;
                        __VLS_ctx.contentTab = 'mindmap';
                    } },
                type: "button",
                ...{ class: "content-tab" },
                ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'mindmap' }) },
            });
            (__VLS_ctx.$t('documents.detail.tabMindmap'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                            return;
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value.inline))
                            return;
                        if (!!(__VLS_ctx.isListDocument))
                            return;
                        if (!!(__VLS_ctx.isChecklistDocument))
                            return;
                        if (!!(__VLS_ctx.isSheetDocument))
                            return;
                        if (!!(__VLS_ctx.isGraphDocument))
                            return;
                        if (!!(__VLS_ctx.isChartDocument))
                            return;
                        if (!!(__VLS_ctx.isRecordsDocument))
                            return;
                        if (!(__VLS_ctx.isMindmapDocument))
                            return;
                        __VLS_ctx.contentTab = 'tree';
                    } },
                type: "button",
                ...{ class: "content-tab" },
                ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'tree' }) },
            });
            (__VLS_ctx.$t('documents.detail.tabTree'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                            return;
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value.inline))
                            return;
                        if (!!(__VLS_ctx.isListDocument))
                            return;
                        if (!!(__VLS_ctx.isChecklistDocument))
                            return;
                        if (!!(__VLS_ctx.isSheetDocument))
                            return;
                        if (!!(__VLS_ctx.isGraphDocument))
                            return;
                        if (!!(__VLS_ctx.isChartDocument))
                            return;
                        if (!!(__VLS_ctx.isRecordsDocument))
                            return;
                        if (!(__VLS_ctx.isMindmapDocument))
                            return;
                        __VLS_ctx.contentTab = 'raw';
                    } },
                type: "button",
                ...{ class: "content-tab" },
                ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'raw' }) },
            });
            (__VLS_ctx.$t('documents.detail.tabRaw'));
        }
        else if (__VLS_ctx.isSlidesDocument) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "content-tabs" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                            return;
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value.inline))
                            return;
                        if (!!(__VLS_ctx.isListDocument))
                            return;
                        if (!!(__VLS_ctx.isChecklistDocument))
                            return;
                        if (!!(__VLS_ctx.isSheetDocument))
                            return;
                        if (!!(__VLS_ctx.isGraphDocument))
                            return;
                        if (!!(__VLS_ctx.isChartDocument))
                            return;
                        if (!!(__VLS_ctx.isRecordsDocument))
                            return;
                        if (!!(__VLS_ctx.isMindmapDocument))
                            return;
                        if (!(__VLS_ctx.isSlidesDocument))
                            return;
                        __VLS_ctx.contentTab = 'slides';
                    } },
                type: "button",
                ...{ class: "content-tab" },
                ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'slides' }) },
            });
            (__VLS_ctx.$t('documents.detail.tabSlides'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                            return;
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value.inline))
                            return;
                        if (!!(__VLS_ctx.isListDocument))
                            return;
                        if (!!(__VLS_ctx.isChecklistDocument))
                            return;
                        if (!!(__VLS_ctx.isSheetDocument))
                            return;
                        if (!!(__VLS_ctx.isGraphDocument))
                            return;
                        if (!!(__VLS_ctx.isChartDocument))
                            return;
                        if (!!(__VLS_ctx.isRecordsDocument))
                            return;
                        if (!!(__VLS_ctx.isMindmapDocument))
                            return;
                        if (!(__VLS_ctx.isSlidesDocument))
                            return;
                        __VLS_ctx.contentTab = 'raw';
                    } },
                type: "button",
                ...{ class: "content-tab" },
                ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'raw' }) },
            });
            (__VLS_ctx.$t('documents.detail.tabRaw'));
        }
        else if (__VLS_ctx.isDiagramDocument) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "content-tabs" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                            return;
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value.inline))
                            return;
                        if (!!(__VLS_ctx.isListDocument))
                            return;
                        if (!!(__VLS_ctx.isChecklistDocument))
                            return;
                        if (!!(__VLS_ctx.isSheetDocument))
                            return;
                        if (!!(__VLS_ctx.isGraphDocument))
                            return;
                        if (!!(__VLS_ctx.isChartDocument))
                            return;
                        if (!!(__VLS_ctx.isRecordsDocument))
                            return;
                        if (!!(__VLS_ctx.isMindmapDocument))
                            return;
                        if (!!(__VLS_ctx.isSlidesDocument))
                            return;
                        if (!(__VLS_ctx.isDiagramDocument))
                            return;
                        __VLS_ctx.contentTab = 'diagram';
                    } },
                type: "button",
                ...{ class: "content-tab" },
                ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'diagram' }) },
            });
            (__VLS_ctx.$t('documents.detail.tabDiagram'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                            return;
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value.inline))
                            return;
                        if (!!(__VLS_ctx.isListDocument))
                            return;
                        if (!!(__VLS_ctx.isChecklistDocument))
                            return;
                        if (!!(__VLS_ctx.isSheetDocument))
                            return;
                        if (!!(__VLS_ctx.isGraphDocument))
                            return;
                        if (!!(__VLS_ctx.isChartDocument))
                            return;
                        if (!!(__VLS_ctx.isRecordsDocument))
                            return;
                        if (!!(__VLS_ctx.isMindmapDocument))
                            return;
                        if (!!(__VLS_ctx.isSlidesDocument))
                            return;
                        if (!(__VLS_ctx.isDiagramDocument))
                            return;
                        __VLS_ctx.contentTab = 'raw';
                    } },
                type: "button",
                ...{ class: "content-tab" },
                ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'raw' }) },
            });
            (__VLS_ctx.$t('documents.detail.tabRaw'));
        }
        else if (__VLS_ctx.isCalendarDocument && __VLS_ctx.calendarKind) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "content-tabs" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                            return;
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value.inline))
                            return;
                        if (!!(__VLS_ctx.isListDocument))
                            return;
                        if (!!(__VLS_ctx.isChecklistDocument))
                            return;
                        if (!!(__VLS_ctx.isSheetDocument))
                            return;
                        if (!!(__VLS_ctx.isGraphDocument))
                            return;
                        if (!!(__VLS_ctx.isChartDocument))
                            return;
                        if (!!(__VLS_ctx.isRecordsDocument))
                            return;
                        if (!!(__VLS_ctx.isMindmapDocument))
                            return;
                        if (!!(__VLS_ctx.isSlidesDocument))
                            return;
                        if (!!(__VLS_ctx.isDiagramDocument))
                            return;
                        if (!(__VLS_ctx.isCalendarDocument && __VLS_ctx.calendarKind))
                            return;
                        __VLS_ctx.contentTab = 'calendar';
                    } },
                type: "button",
                ...{ class: "content-tab" },
                ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'calendar' }) },
            });
            (__VLS_ctx.$t(__VLS_ctx.calendarKind.tabLabelKey ?? 'documents.detail.tabCalendar'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                            return;
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value.inline))
                            return;
                        if (!!(__VLS_ctx.isListDocument))
                            return;
                        if (!!(__VLS_ctx.isChecklistDocument))
                            return;
                        if (!!(__VLS_ctx.isSheetDocument))
                            return;
                        if (!!(__VLS_ctx.isGraphDocument))
                            return;
                        if (!!(__VLS_ctx.isChartDocument))
                            return;
                        if (!!(__VLS_ctx.isRecordsDocument))
                            return;
                        if (!!(__VLS_ctx.isMindmapDocument))
                            return;
                        if (!!(__VLS_ctx.isSlidesDocument))
                            return;
                        if (!!(__VLS_ctx.isDiagramDocument))
                            return;
                        if (!(__VLS_ctx.isCalendarDocument && __VLS_ctx.calendarKind))
                            return;
                        __VLS_ctx.contentTab = 'raw';
                    } },
                type: "button",
                ...{ class: "content-tab" },
                ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'raw' }) },
            });
            (__VLS_ctx.$t('documents.detail.tabRaw'));
        }
        else if (__VLS_ctx.isTreeDocument) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "content-tabs" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                            return;
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value.inline))
                            return;
                        if (!!(__VLS_ctx.isListDocument))
                            return;
                        if (!!(__VLS_ctx.isChecklistDocument))
                            return;
                        if (!!(__VLS_ctx.isSheetDocument))
                            return;
                        if (!!(__VLS_ctx.isGraphDocument))
                            return;
                        if (!!(__VLS_ctx.isChartDocument))
                            return;
                        if (!!(__VLS_ctx.isRecordsDocument))
                            return;
                        if (!!(__VLS_ctx.isMindmapDocument))
                            return;
                        if (!!(__VLS_ctx.isSlidesDocument))
                            return;
                        if (!!(__VLS_ctx.isDiagramDocument))
                            return;
                        if (!!(__VLS_ctx.isCalendarDocument && __VLS_ctx.calendarKind))
                            return;
                        if (!(__VLS_ctx.isTreeDocument))
                            return;
                        __VLS_ctx.contentTab = 'tree';
                    } },
                type: "button",
                ...{ class: "content-tab" },
                ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'tree' }) },
            });
            (__VLS_ctx.$t('documents.detail.tabTree'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                            return;
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value.inline))
                            return;
                        if (!!(__VLS_ctx.isListDocument))
                            return;
                        if (!!(__VLS_ctx.isChecklistDocument))
                            return;
                        if (!!(__VLS_ctx.isSheetDocument))
                            return;
                        if (!!(__VLS_ctx.isGraphDocument))
                            return;
                        if (!!(__VLS_ctx.isChartDocument))
                            return;
                        if (!!(__VLS_ctx.isRecordsDocument))
                            return;
                        if (!!(__VLS_ctx.isMindmapDocument))
                            return;
                        if (!!(__VLS_ctx.isSlidesDocument))
                            return;
                        if (!!(__VLS_ctx.isDiagramDocument))
                            return;
                        if (!!(__VLS_ctx.isCalendarDocument && __VLS_ctx.calendarKind))
                            return;
                        if (!(__VLS_ctx.isTreeDocument))
                            return;
                        __VLS_ctx.contentTab = 'raw';
                    } },
                type: "button",
                ...{ class: "content-tab" },
                ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'raw' }) },
            });
            (__VLS_ctx.$t('documents.detail.tabRaw'));
        }
        else if (__VLS_ctx.isMarkdownDocument) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "content-tabs" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                            return;
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value.inline))
                            return;
                        if (!!(__VLS_ctx.isListDocument))
                            return;
                        if (!!(__VLS_ctx.isChecklistDocument))
                            return;
                        if (!!(__VLS_ctx.isSheetDocument))
                            return;
                        if (!!(__VLS_ctx.isGraphDocument))
                            return;
                        if (!!(__VLS_ctx.isChartDocument))
                            return;
                        if (!!(__VLS_ctx.isRecordsDocument))
                            return;
                        if (!!(__VLS_ctx.isMindmapDocument))
                            return;
                        if (!!(__VLS_ctx.isSlidesDocument))
                            return;
                        if (!!(__VLS_ctx.isDiagramDocument))
                            return;
                        if (!!(__VLS_ctx.isCalendarDocument && __VLS_ctx.calendarKind))
                            return;
                        if (!!(__VLS_ctx.isTreeDocument))
                            return;
                        if (!(__VLS_ctx.isMarkdownDocument))
                            return;
                        __VLS_ctx.contentTab = 'preview';
                    } },
                type: "button",
                ...{ class: "content-tab" },
                ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'preview' }) },
            });
            (__VLS_ctx.$t('documents.detail.tabPreview'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                            return;
                        if (!!(!__VLS_ctx.selectedProjectId))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value))
                            return;
                        if (!(__VLS_ctx.docsState.selected.value.inline))
                            return;
                        if (!!(__VLS_ctx.isListDocument))
                            return;
                        if (!!(__VLS_ctx.isChecklistDocument))
                            return;
                        if (!!(__VLS_ctx.isSheetDocument))
                            return;
                        if (!!(__VLS_ctx.isGraphDocument))
                            return;
                        if (!!(__VLS_ctx.isChartDocument))
                            return;
                        if (!!(__VLS_ctx.isRecordsDocument))
                            return;
                        if (!!(__VLS_ctx.isMindmapDocument))
                            return;
                        if (!!(__VLS_ctx.isSlidesDocument))
                            return;
                        if (!!(__VLS_ctx.isDiagramDocument))
                            return;
                        if (!!(__VLS_ctx.isCalendarDocument && __VLS_ctx.calendarKind))
                            return;
                        if (!!(__VLS_ctx.isTreeDocument))
                            return;
                        if (!(__VLS_ctx.isMarkdownDocument))
                            return;
                        __VLS_ctx.contentTab = 'raw';
                    } },
                type: "button",
                ...{ class: "content-tab" },
                ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'raw' }) },
            });
            (__VLS_ctx.$t('documents.detail.tabRaw'));
        }
        if (__VLS_ctx.isListDocument && __VLS_ctx.contentTab === 'list') {
            if (__VLS_ctx.parsedList.error) {
                const __VLS_121 = {}.VAlert;
                /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
                // @ts-ignore
                const __VLS_122 = __VLS_asFunctionalComponent(__VLS_121, new __VLS_121({
                    variant: "warning",
                }));
                const __VLS_123 = __VLS_122({
                    variant: "warning",
                }, ...__VLS_functionalComponentArgsRest(__VLS_122));
                __VLS_124.slots.default;
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.$t('documents.detail.listParseError', { message: __VLS_ctx.parsedList.error }));
                var __VLS_124;
            }
            else if (__VLS_ctx.parsedList.doc) {
                /** @type {[typeof ListView, ]} */ ;
                // @ts-ignore
                const __VLS_125 = __VLS_asFunctionalComponent(ListView, new ListView({
                    ...{ 'onUpdate:doc': {} },
                    doc: (__VLS_ctx.parsedList.doc),
                }));
                const __VLS_126 = __VLS_125({
                    ...{ 'onUpdate:doc': {} },
                    doc: (__VLS_ctx.parsedList.doc),
                }, ...__VLS_functionalComponentArgsRest(__VLS_125));
                let __VLS_128;
                let __VLS_129;
                let __VLS_130;
                const __VLS_131 = {
                    'onUpdate:doc': (__VLS_ctx.onListChanged)
                };
                var __VLS_127;
            }
        }
        else if (__VLS_ctx.isChecklistDocument && __VLS_ctx.contentTab === 'checklist') {
            if (__VLS_ctx.parsedChecklist.error) {
                const __VLS_132 = {}.VAlert;
                /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
                // @ts-ignore
                const __VLS_133 = __VLS_asFunctionalComponent(__VLS_132, new __VLS_132({
                    variant: "warning",
                }));
                const __VLS_134 = __VLS_133({
                    variant: "warning",
                }, ...__VLS_functionalComponentArgsRest(__VLS_133));
                __VLS_135.slots.default;
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.$t('documents.detail.checklistParseError', { message: __VLS_ctx.parsedChecklist.error }));
                var __VLS_135;
            }
            else if (__VLS_ctx.parsedChecklist.doc) {
                const __VLS_136 = {}.ChecklistView;
                /** @type {[typeof __VLS_components.ChecklistView, ]} */ ;
                // @ts-ignore
                const __VLS_137 = __VLS_asFunctionalComponent(__VLS_136, new __VLS_136({
                    ...{ 'onUpdate:doc': {} },
                    doc: (__VLS_ctx.parsedChecklist.doc),
                }));
                const __VLS_138 = __VLS_137({
                    ...{ 'onUpdate:doc': {} },
                    doc: (__VLS_ctx.parsedChecklist.doc),
                }, ...__VLS_functionalComponentArgsRest(__VLS_137));
                let __VLS_140;
                let __VLS_141;
                let __VLS_142;
                const __VLS_143 = {
                    'onUpdate:doc': (__VLS_ctx.onChecklistChanged)
                };
                var __VLS_139;
            }
        }
        else if (__VLS_ctx.isSheetDocument && __VLS_ctx.contentTab === 'sheet') {
            if (__VLS_ctx.parsedSheet.error) {
                const __VLS_144 = {}.VAlert;
                /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
                // @ts-ignore
                const __VLS_145 = __VLS_asFunctionalComponent(__VLS_144, new __VLS_144({
                    variant: "warning",
                }));
                const __VLS_146 = __VLS_145({
                    variant: "warning",
                }, ...__VLS_functionalComponentArgsRest(__VLS_145));
                __VLS_147.slots.default;
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.$t('documents.detail.sheetParseError', { message: __VLS_ctx.parsedSheet.error }));
                var __VLS_147;
            }
            else if (__VLS_ctx.parsedSheet.doc) {
                /** @type {[typeof SheetView, ]} */ ;
                // @ts-ignore
                const __VLS_148 = __VLS_asFunctionalComponent(SheetView, new SheetView({
                    ...{ 'onUpdate:doc': {} },
                    doc: (__VLS_ctx.parsedSheet.doc),
                }));
                const __VLS_149 = __VLS_148({
                    ...{ 'onUpdate:doc': {} },
                    doc: (__VLS_ctx.parsedSheet.doc),
                }, ...__VLS_functionalComponentArgsRest(__VLS_148));
                let __VLS_151;
                let __VLS_152;
                let __VLS_153;
                const __VLS_154 = {
                    'onUpdate:doc': (__VLS_ctx.onSheetChanged)
                };
                var __VLS_150;
            }
        }
        else if (__VLS_ctx.isGraphDocument && __VLS_ctx.contentTab === 'graph') {
            if (__VLS_ctx.parsedGraph.error) {
                const __VLS_155 = {}.VAlert;
                /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
                // @ts-ignore
                const __VLS_156 = __VLS_asFunctionalComponent(__VLS_155, new __VLS_155({
                    variant: "warning",
                }));
                const __VLS_157 = __VLS_156({
                    variant: "warning",
                }, ...__VLS_functionalComponentArgsRest(__VLS_156));
                __VLS_158.slots.default;
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.$t('documents.detail.graphParseError', { message: __VLS_ctx.parsedGraph.error }));
                var __VLS_158;
            }
            else if (__VLS_ctx.parsedGraph.doc) {
                /** @type {[typeof GraphView, ]} */ ;
                // @ts-ignore
                const __VLS_159 = __VLS_asFunctionalComponent(GraphView, new GraphView({
                    ...{ 'onUpdate:doc': {} },
                    doc: (__VLS_ctx.parsedGraph.doc),
                }));
                const __VLS_160 = __VLS_159({
                    ...{ 'onUpdate:doc': {} },
                    doc: (__VLS_ctx.parsedGraph.doc),
                }, ...__VLS_functionalComponentArgsRest(__VLS_159));
                let __VLS_162;
                let __VLS_163;
                let __VLS_164;
                const __VLS_165 = {
                    'onUpdate:doc': (__VLS_ctx.onGraphChanged)
                };
                var __VLS_161;
            }
        }
        else if (__VLS_ctx.isChartDocument && __VLS_ctx.contentTab === 'chart') {
            if (__VLS_ctx.parsedChart.error) {
                const __VLS_166 = {}.VAlert;
                /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
                // @ts-ignore
                const __VLS_167 = __VLS_asFunctionalComponent(__VLS_166, new __VLS_166({
                    variant: "warning",
                }));
                const __VLS_168 = __VLS_167({
                    variant: "warning",
                }, ...__VLS_functionalComponentArgsRest(__VLS_167));
                __VLS_169.slots.default;
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.$t('documents.detail.chartParseError', { message: __VLS_ctx.parsedChart.error }));
                var __VLS_169;
            }
            else if (__VLS_ctx.parsedChart.doc) {
                const __VLS_170 = {}.ChartView;
                /** @type {[typeof __VLS_components.ChartView, ]} */ ;
                // @ts-ignore
                const __VLS_171 = __VLS_asFunctionalComponent(__VLS_170, new __VLS_170({
                    ...{ 'onUpdate:doc': {} },
                    doc: (__VLS_ctx.parsedChart.doc),
                }));
                const __VLS_172 = __VLS_171({
                    ...{ 'onUpdate:doc': {} },
                    doc: (__VLS_ctx.parsedChart.doc),
                }, ...__VLS_functionalComponentArgsRest(__VLS_171));
                let __VLS_174;
                let __VLS_175;
                let __VLS_176;
                const __VLS_177 = {
                    'onUpdate:doc': (__VLS_ctx.onChartChanged)
                };
                var __VLS_173;
            }
        }
        else if (__VLS_ctx.isRecordsDocument && __VLS_ctx.contentTab === 'records') {
            if (__VLS_ctx.parsedRecords.error) {
                const __VLS_178 = {}.VAlert;
                /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
                // @ts-ignore
                const __VLS_179 = __VLS_asFunctionalComponent(__VLS_178, new __VLS_178({
                    variant: "warning",
                }));
                const __VLS_180 = __VLS_179({
                    variant: "warning",
                }, ...__VLS_functionalComponentArgsRest(__VLS_179));
                __VLS_181.slots.default;
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.$t('documents.detail.recordsParseError', { message: __VLS_ctx.parsedRecords.error }));
                var __VLS_181;
            }
            else if (__VLS_ctx.parsedRecords.doc) {
                /** @type {[typeof RecordsView, ]} */ ;
                // @ts-ignore
                const __VLS_182 = __VLS_asFunctionalComponent(RecordsView, new RecordsView({
                    ...{ 'onUpdate:doc': {} },
                    doc: (__VLS_ctx.parsedRecords.doc),
                }));
                const __VLS_183 = __VLS_182({
                    ...{ 'onUpdate:doc': {} },
                    doc: (__VLS_ctx.parsedRecords.doc),
                }, ...__VLS_functionalComponentArgsRest(__VLS_182));
                let __VLS_185;
                let __VLS_186;
                let __VLS_187;
                const __VLS_188 = {
                    'onUpdate:doc': (__VLS_ctx.onRecordsChanged)
                };
                var __VLS_184;
            }
        }
        else if (__VLS_ctx.isMindmapDocument && __VLS_ctx.contentTab === 'mindmap') {
            if (__VLS_ctx.parsedTree.error) {
                const __VLS_189 = {}.VAlert;
                /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
                // @ts-ignore
                const __VLS_190 = __VLS_asFunctionalComponent(__VLS_189, new __VLS_189({
                    variant: "warning",
                }));
                const __VLS_191 = __VLS_190({
                    variant: "warning",
                }, ...__VLS_functionalComponentArgsRest(__VLS_190));
                __VLS_192.slots.default;
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.$t('documents.detail.mindmapParseError', { message: __VLS_ctx.parsedTree.error }));
                var __VLS_192;
            }
            else if (__VLS_ctx.parsedTree.doc) {
                /** @type {[typeof MindmapView, ]} */ ;
                // @ts-ignore
                const __VLS_193 = __VLS_asFunctionalComponent(MindmapView, new MindmapView({
                    doc: (__VLS_ctx.parsedTree.doc),
                }));
                const __VLS_194 = __VLS_193({
                    doc: (__VLS_ctx.parsedTree.doc),
                }, ...__VLS_functionalComponentArgsRest(__VLS_193));
            }
        }
        else if (__VLS_ctx.isSlidesDocument && __VLS_ctx.contentTab === 'slides') {
            if (__VLS_ctx.parsedSlides.error) {
                const __VLS_196 = {}.VAlert;
                /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
                // @ts-ignore
                const __VLS_197 = __VLS_asFunctionalComponent(__VLS_196, new __VLS_196({
                    variant: "warning",
                }));
                const __VLS_198 = __VLS_197({
                    variant: "warning",
                }, ...__VLS_functionalComponentArgsRest(__VLS_197));
                __VLS_199.slots.default;
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.$t('documents.detail.slidesParseError', { message: __VLS_ctx.parsedSlides.error }));
                var __VLS_199;
            }
            else if (__VLS_ctx.parsedSlides.doc) {
                const __VLS_200 = {}.SlidesView;
                /** @type {[typeof __VLS_components.SlidesView, ]} */ ;
                // @ts-ignore
                const __VLS_201 = __VLS_asFunctionalComponent(__VLS_200, new __VLS_200({
                    doc: (__VLS_ctx.parsedSlides.doc),
                }));
                const __VLS_202 = __VLS_201({
                    doc: (__VLS_ctx.parsedSlides.doc),
                }, ...__VLS_functionalComponentArgsRest(__VLS_201));
            }
        }
        else if (__VLS_ctx.isDiagramDocument && __VLS_ctx.contentTab === 'diagram') {
            if (__VLS_ctx.parsedDiagram.error) {
                const __VLS_204 = {}.VAlert;
                /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
                // @ts-ignore
                const __VLS_205 = __VLS_asFunctionalComponent(__VLS_204, new __VLS_204({
                    variant: "warning",
                }));
                const __VLS_206 = __VLS_205({
                    variant: "warning",
                }, ...__VLS_functionalComponentArgsRest(__VLS_205));
                __VLS_207.slots.default;
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.$t('documents.detail.diagramParseError', { message: __VLS_ctx.parsedDiagram.error }));
                var __VLS_207;
            }
            else if (__VLS_ctx.parsedDiagram.doc) {
                const __VLS_208 = {}.DiagramView;
                /** @type {[typeof __VLS_components.DiagramView, ]} */ ;
                // @ts-ignore
                const __VLS_209 = __VLS_asFunctionalComponent(__VLS_208, new __VLS_208({
                    doc: (__VLS_ctx.parsedDiagram.doc),
                }));
                const __VLS_210 = __VLS_209({
                    doc: (__VLS_ctx.parsedDiagram.doc),
                }, ...__VLS_functionalComponentArgsRest(__VLS_209));
            }
        }
        else if (__VLS_ctx.isCalendarDocument && __VLS_ctx.contentTab === 'calendar' && __VLS_ctx.calendarKind) {
            if (__VLS_ctx.parsedCalendar.error) {
                const __VLS_212 = {}.VAlert;
                /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
                // @ts-ignore
                const __VLS_213 = __VLS_asFunctionalComponent(__VLS_212, new __VLS_212({
                    variant: "warning",
                }));
                const __VLS_214 = __VLS_213({
                    variant: "warning",
                }, ...__VLS_functionalComponentArgsRest(__VLS_213));
                __VLS_215.slots.default;
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.$t(__VLS_ctx.calendarKind.parseErrorKey ?? 'documents.detail.calendarParseError', { message: __VLS_ctx.parsedCalendar.error }));
                var __VLS_215;
            }
            else if (__VLS_ctx.parsedCalendar.doc) {
                const __VLS_216 = ((__VLS_ctx.calendarKind.view));
                // @ts-ignore
                const __VLS_217 = __VLS_asFunctionalComponent(__VLS_216, new __VLS_216({
                    mode: "embedded",
                    doc: (__VLS_ctx.parsedCalendar.doc),
                }));
                const __VLS_218 = __VLS_217({
                    mode: "embedded",
                    doc: (__VLS_ctx.parsedCalendar.doc),
                }, ...__VLS_functionalComponentArgsRest(__VLS_217));
            }
        }
        else if ((__VLS_ctx.isTreeDocument || __VLS_ctx.isMindmapDocument) && __VLS_ctx.contentTab === 'tree') {
            if (__VLS_ctx.parsedTree.error) {
                const __VLS_220 = {}.VAlert;
                /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
                // @ts-ignore
                const __VLS_221 = __VLS_asFunctionalComponent(__VLS_220, new __VLS_220({
                    variant: "warning",
                }));
                const __VLS_222 = __VLS_221({
                    variant: "warning",
                }, ...__VLS_functionalComponentArgsRest(__VLS_221));
                __VLS_223.slots.default;
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.$t('documents.detail.treeParseError', { message: __VLS_ctx.parsedTree.error }));
                var __VLS_223;
            }
            else if (__VLS_ctx.parsedTree.doc) {
                /** @type {[typeof TreeView, ]} */ ;
                // @ts-ignore
                const __VLS_224 = __VLS_asFunctionalComponent(TreeView, new TreeView({
                    ...{ 'onUpdate:doc': {} },
                    doc: (__VLS_ctx.parsedTree.doc),
                }));
                const __VLS_225 = __VLS_224({
                    ...{ 'onUpdate:doc': {} },
                    doc: (__VLS_ctx.parsedTree.doc),
                }, ...__VLS_functionalComponentArgsRest(__VLS_224));
                let __VLS_227;
                let __VLS_228;
                let __VLS_229;
                const __VLS_230 = {
                    'onUpdate:doc': (__VLS_ctx.onTreeChanged)
                };
                var __VLS_226;
            }
        }
        else if (__VLS_ctx.isMarkdownDocument && __VLS_ctx.contentTab === 'preview') {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "markdown-preview-pane" },
            });
            const __VLS_231 = {}.MarkdownView;
            /** @type {[typeof __VLS_components.MarkdownView, ]} */ ;
            // @ts-ignore
            const __VLS_232 = __VLS_asFunctionalComponent(__VLS_231, new __VLS_231({
                source: (__VLS_ctx.editInlineText),
            }));
            const __VLS_233 = __VLS_232({
                source: (__VLS_ctx.editInlineText),
            }, ...__VLS_functionalComponentArgsRest(__VLS_232));
        }
        else {
            const __VLS_235 = {}.CodeEditor;
            /** @type {[typeof __VLS_components.CodeEditor, ]} */ ;
            // @ts-ignore
            const __VLS_236 = __VLS_asFunctionalComponent(__VLS_235, new __VLS_235({
                modelValue: (__VLS_ctx.editInlineText),
                label: (__VLS_ctx.$t('documents.detail.contentLabel')),
                rows: (20),
                disabled: (__VLS_ctx.saving),
                mimeType: (__VLS_ctx.docsState.selected.value.mimeType),
                followUp: (__VLS_ctx.isMarkdownDocument ? __VLS_ctx.markdownFollowUp : null),
            }));
            const __VLS_237 = __VLS_236({
                modelValue: (__VLS_ctx.editInlineText),
                label: (__VLS_ctx.$t('documents.detail.contentLabel')),
                rows: (20),
                disabled: (__VLS_ctx.saving),
                mimeType: (__VLS_ctx.docsState.selected.value.mimeType),
                followUp: (__VLS_ctx.isMarkdownDocument ? __VLS_ctx.markdownFollowUp : null),
            }, ...__VLS_functionalComponentArgsRest(__VLS_236));
        }
    }
    else if (__VLS_ctx.isOfficeEditableDocument) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "content-tabs" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                        return;
                    if (!!(!__VLS_ctx.selectedProjectId))
                        return;
                    if (!(__VLS_ctx.docsState.selected.value))
                        return;
                    if (!!(__VLS_ctx.docsState.selected.value.inline))
                        return;
                    if (!(__VLS_ctx.isOfficeEditableDocument))
                        return;
                    __VLS_ctx.contentTab = 'preview';
                } },
            type: "button",
            ...{ class: "content-tab" },
            ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'preview' }) },
        });
        (__VLS_ctx.$t('documents.detail.tabPreview'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                        return;
                    if (!!(!__VLS_ctx.selectedProjectId))
                        return;
                    if (!(__VLS_ctx.docsState.selected.value))
                        return;
                    if (!!(__VLS_ctx.docsState.selected.value.inline))
                        return;
                    if (!(__VLS_ctx.isOfficeEditableDocument))
                        return;
                    __VLS_ctx.contentTab = 'office';
                } },
            type: "button",
            ...{ class: "content-tab" },
            ...{ class: ({ 'content-tab--active': __VLS_ctx.contentTab === 'office' }) },
        });
        (__VLS_ctx.$t('documents.detail.tabOfficeEdit'));
        if (__VLS_ctx.contentTab === 'office') {
            const __VLS_239 = {}.OfficeEditor;
            /** @type {[typeof __VLS_components.OfficeEditor, ]} */ ;
            // @ts-ignore
            const __VLS_240 = __VLS_asFunctionalComponent(__VLS_239, new __VLS_239({
                documentId: (__VLS_ctx.docsState.selected.value.id),
                mimeType: (__VLS_ctx.docsState.selected.value.mimeType),
            }));
            const __VLS_241 = __VLS_240({
                documentId: (__VLS_ctx.docsState.selected.value.id),
                mimeType: (__VLS_ctx.docsState.selected.value.mimeType),
            }, ...__VLS_functionalComponentArgsRest(__VLS_240));
        }
        else {
            /** @type {[typeof DocumentPreview, ]} */ ;
            // @ts-ignore
            const __VLS_243 = __VLS_asFunctionalComponent(DocumentPreview, new DocumentPreview({
                key: (`office-preview-${__VLS_ctx.docsState.selected.value.id}-${__VLS_ctx.previewReloadCounter}`),
                documentId: (__VLS_ctx.docsState.selected.value.id),
                mimeType: (__VLS_ctx.docsState.selected.value.mimeType),
                inline: (false),
            }));
            const __VLS_244 = __VLS_243({
                key: (`office-preview-${__VLS_ctx.docsState.selected.value.id}-${__VLS_ctx.previewReloadCounter}`),
                documentId: (__VLS_ctx.docsState.selected.value.id),
                mimeType: (__VLS_ctx.docsState.selected.value.mimeType),
                inline: (false),
            }, ...__VLS_functionalComponentArgsRest(__VLS_243));
        }
    }
    else {
        /** @type {[typeof DocumentPreview, ]} */ ;
        // @ts-ignore
        const __VLS_246 = __VLS_asFunctionalComponent(DocumentPreview, new DocumentPreview({
            documentId: (__VLS_ctx.docsState.selected.value.id),
            mimeType: (__VLS_ctx.docsState.selected.value.mimeType),
            inline: (false),
        }));
        const __VLS_247 = __VLS_246({
            documentId: (__VLS_ctx.docsState.selected.value.id),
            mimeType: (__VLS_ctx.docsState.selected.value.mimeType),
            inline: (false),
        }, ...__VLS_functionalComponentArgsRest(__VLS_246));
    }
    var __VLS_72;
}
else {
    if (__VLS_ctx.docsState.error.value) {
        const __VLS_249 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_250 = __VLS_asFunctionalComponent(__VLS_249, new __VLS_249({
            variant: "error",
            ...{ class: "mb-4" },
        }));
        const __VLS_251 = __VLS_250({
            variant: "error",
            ...{ class: "mb-4" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_250));
        __VLS_252.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.docsState.error.value);
        var __VLS_252;
    }
    if (!__VLS_ctx.docsState.loading.value
        && __VLS_ctx.docsState.items.value.length === 0
        && __VLS_ctx.docsState.subFolders.value.length === 0) {
        const __VLS_253 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_254 = __VLS_asFunctionalComponent(__VLS_253, new __VLS_253({
            headline: (__VLS_ctx.$t('documents.noDocumentsHeadline')),
            body: (__VLS_ctx.$t('documents.noDocumentsBody')),
        }));
        const __VLS_255 = __VLS_254({
            headline: (__VLS_ctx.$t('documents.noDocumentsHeadline')),
            body: (__VLS_ctx.$t('documents.noDocumentsBody')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_254));
        __VLS_256.slots.default;
        {
            const { action: __VLS_thisSlot } = __VLS_256.slots;
            const __VLS_257 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_258 = __VLS_asFunctionalComponent(__VLS_257, new __VLS_257({
                ...{ 'onClick': {} },
                variant: "primary",
            }));
            const __VLS_259 = __VLS_258({
                ...{ 'onClick': {} },
                variant: "primary",
            }, ...__VLS_functionalComponentArgsRest(__VLS_258));
            let __VLS_261;
            let __VLS_262;
            let __VLS_263;
            const __VLS_264 = {
                onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                        return;
                    if (!!(!__VLS_ctx.selectedProjectId))
                        return;
                    if (!!(__VLS_ctx.docsState.selected.value))
                        return;
                    if (!(!__VLS_ctx.docsState.loading.value
                        && __VLS_ctx.docsState.items.value.length === 0
                        && __VLS_ctx.docsState.subFolders.value.length === 0))
                        return;
                    __VLS_ctx.openCreateModal();
                }
            };
            __VLS_260.slots.default;
            (__VLS_ctx.$t('documents.createFirstDocument'));
            var __VLS_260;
        }
        var __VLS_256;
    }
    else {
        if (__VLS_ctx.docsState.subFolders.value.length > 0) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
                ...{ class: "flex flex-col gap-1 mb-3" },
            });
            for (const [folder] of __VLS_getVForSourceType((__VLS_ctx.docsState.subFolders.value))) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                    ...{ onClick: (...[$event]) => {
                            if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                                return;
                            if (!!(!__VLS_ctx.selectedProjectId))
                                return;
                            if (!!(__VLS_ctx.docsState.selected.value))
                                return;
                            if (!!(!__VLS_ctx.docsState.loading.value
                                && __VLS_ctx.docsState.items.value.length === 0
                                && __VLS_ctx.docsState.subFolders.value.length === 0))
                                return;
                            if (!(__VLS_ctx.docsState.subFolders.value.length > 0))
                                return;
                            __VLS_ctx.navigateIntoFolder(folder);
                        } },
                    key: (folder),
                    ...{ class: "folder-row" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "text-lg leading-none" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "font-medium" },
                });
                (folder);
            }
        }
        if (__VLS_ctx.docsState.items.value.length > 0) {
            const __VLS_265 = {}.VDataList;
            /** @type {[typeof __VLS_components.VDataList, typeof __VLS_components.VDataList, ]} */ ;
            // @ts-ignore
            const __VLS_266 = __VLS_asFunctionalComponent(__VLS_265, new __VLS_265({
                ...{ 'onSelect': {} },
                items: (__VLS_ctx.docsState.items.value),
                selectable: true,
            }));
            const __VLS_267 = __VLS_266({
                ...{ 'onSelect': {} },
                items: (__VLS_ctx.docsState.items.value),
                selectable: true,
            }, ...__VLS_functionalComponentArgsRest(__VLS_266));
            let __VLS_269;
            let __VLS_270;
            let __VLS_271;
            const __VLS_272 = {
                onSelect: (__VLS_ctx.openDocument)
            };
            __VLS_268.slots.default;
            {
                const { default: __VLS_thisSlot } = __VLS_268.slots;
                const [{ item }] = __VLS_getSlotParams(__VLS_thisSlot);
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "flex items-center gap-3" },
                });
                /** @type {[typeof DocumentIcon, ]} */ ;
                // @ts-ignore
                const __VLS_273 = __VLS_asFunctionalComponent(DocumentIcon, new DocumentIcon({
                    path: (item.path),
                    mimeType: (item.mimeType),
                    kind: (item.kind),
                }));
                const __VLS_274 = __VLS_273({
                    path: (item.path),
                    mimeType: (item.mimeType),
                    kind: (item.kind),
                }, ...__VLS_functionalComponentArgsRest(__VLS_273));
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "min-w-0 flex-1" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "font-semibold truncate flex items-center gap-2" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "truncate" },
                });
                (item.title?.trim() || item.name);
                if (item.kind) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        ...{ class: "badge badge-info badge-sm shrink-0" },
                        title: (`kind: ${item.kind}`),
                    });
                    (item.kind);
                }
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "text-xs opacity-60 truncate font-mono" },
                });
                (item.name);
                if (item.tags && item.tags.length) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: "mt-1 flex gap-1 flex-wrap" },
                    });
                    for (const [tag] of __VLS_getVForSourceType((item.tags))) {
                        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                            key: (tag),
                            ...{ class: "badge badge-ghost badge-sm" },
                        });
                        (tag);
                    }
                }
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "text-right text-xs opacity-60 shrink-0" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
                (__VLS_ctx.formatBytes(item.size));
                if (!item.inline) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                        ...{ class: "text-warning" },
                    });
                    (__VLS_ctx.$t('documents.storedNote'));
                }
                if (__VLS_ctx.isAppDocument(item)) {
                    const __VLS_276 = {}.VButton;
                    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
                    // @ts-ignore
                    const __VLS_277 = __VLS_asFunctionalComponent(__VLS_276, new __VLS_276({
                        ...{ 'onClick': {} },
                        variant: "ghost",
                        size: "sm",
                        ...{ class: "shrink-0" },
                        title: (__VLS_ctx.$t('documents.editAsFile')),
                    }));
                    const __VLS_278 = __VLS_277({
                        ...{ 'onClick': {} },
                        variant: "ghost",
                        size: "sm",
                        ...{ class: "shrink-0" },
                        title: (__VLS_ctx.$t('documents.editAsFile')),
                    }, ...__VLS_functionalComponentArgsRest(__VLS_277));
                    let __VLS_280;
                    let __VLS_281;
                    let __VLS_282;
                    const __VLS_283 = {
                        onClick: (...[$event]) => {
                            if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                                return;
                            if (!!(!__VLS_ctx.selectedProjectId))
                                return;
                            if (!!(__VLS_ctx.docsState.selected.value))
                                return;
                            if (!!(!__VLS_ctx.docsState.loading.value
                                && __VLS_ctx.docsState.items.value.length === 0
                                && __VLS_ctx.docsState.subFolders.value.length === 0))
                                return;
                            if (!(__VLS_ctx.docsState.items.value.length > 0))
                                return;
                            if (!(__VLS_ctx.isAppDocument(item)))
                                return;
                            __VLS_ctx.openDocumentInEditor(item);
                        }
                    };
                    __VLS_279.slots.default;
                    var __VLS_279;
                }
            }
            var __VLS_268;
        }
        if (__VLS_ctx.docsState.totalCount.value > 0) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "mt-4" },
            });
            const __VLS_284 = {}.VPagination;
            /** @type {[typeof __VLS_components.VPagination, ]} */ ;
            // @ts-ignore
            const __VLS_285 = __VLS_asFunctionalComponent(__VLS_284, new __VLS_284({
                ...{ 'onUpdate:page': {} },
                page: (__VLS_ctx.docsState.page.value),
                pageSize: (__VLS_ctx.docsState.pageSize.value),
                totalCount: (__VLS_ctx.docsState.totalCount.value),
            }));
            const __VLS_286 = __VLS_285({
                ...{ 'onUpdate:page': {} },
                page: (__VLS_ctx.docsState.page.value),
                pageSize: (__VLS_ctx.docsState.pageSize.value),
                totalCount: (__VLS_ctx.docsState.totalCount.value),
            }, ...__VLS_functionalComponentArgsRest(__VLS_285));
            let __VLS_288;
            let __VLS_289;
            let __VLS_290;
            const __VLS_291 = {
                'onUpdate:page': (__VLS_ctx.changePage)
            };
            var __VLS_287;
        }
    }
}
const __VLS_292 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_293 = __VLS_asFunctionalComponent(__VLS_292, new __VLS_292({
    modelValue: (__VLS_ctx.showDeleteModal),
    title: (__VLS_ctx.isSelectedInTrash
        ? __VLS_ctx.$t('documents.delete.titlePermanent')
        : __VLS_ctx.$t('documents.delete.title')),
    closeOnBackdrop: (!__VLS_ctx.deleting),
}));
const __VLS_294 = __VLS_293({
    modelValue: (__VLS_ctx.showDeleteModal),
    title: (__VLS_ctx.isSelectedInTrash
        ? __VLS_ctx.$t('documents.delete.titlePermanent')
        : __VLS_ctx.$t('documents.delete.title')),
    closeOnBackdrop: (!__VLS_ctx.deleting),
}, ...__VLS_functionalComponentArgsRest(__VLS_293));
__VLS_295.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({});
(__VLS_ctx.isSelectedInTrash
    ? __VLS_ctx.$t('documents.delete.bodyPermanent', { path: __VLS_ctx.docsState.selected.value?.path ?? '' })
    : __VLS_ctx.$t('documents.delete.body', {
        path: __VLS_ctx.docsState.selected.value?.path ?? '',
        bin: __VLS_ctx.TRASH_PREFIX,
    }));
{
    const { actions: __VLS_thisSlot } = __VLS_295.slots;
    const __VLS_296 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_297 = __VLS_asFunctionalComponent(__VLS_296, new __VLS_296({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.deleting),
    }));
    const __VLS_298 = __VLS_297({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.deleting),
    }, ...__VLS_functionalComponentArgsRest(__VLS_297));
    let __VLS_300;
    let __VLS_301;
    let __VLS_302;
    const __VLS_303 = {
        onClick: (...[$event]) => {
            __VLS_ctx.showDeleteModal = false;
        }
    };
    __VLS_299.slots.default;
    (__VLS_ctx.$t('documents.delete.cancel'));
    var __VLS_299;
    const __VLS_304 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_305 = __VLS_asFunctionalComponent(__VLS_304, new __VLS_304({
        ...{ 'onClick': {} },
        variant: "danger",
        loading: (__VLS_ctx.deleting),
    }));
    const __VLS_306 = __VLS_305({
        ...{ 'onClick': {} },
        variant: "danger",
        loading: (__VLS_ctx.deleting),
    }, ...__VLS_functionalComponentArgsRest(__VLS_305));
    let __VLS_308;
    let __VLS_309;
    let __VLS_310;
    const __VLS_311 = {
        onClick: (__VLS_ctx.confirmDelete)
    };
    __VLS_307.slots.default;
    (__VLS_ctx.isSelectedInTrash
        ? __VLS_ctx.$t('documents.delete.confirmPermanent')
        : __VLS_ctx.$t('documents.delete.confirm'));
    var __VLS_307;
}
var __VLS_295;
const __VLS_312 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_313 = __VLS_asFunctionalComponent(__VLS_312, new __VLS_312({
    modelValue: (__VLS_ctx.showNewFolderModal),
    title: (__VLS_ctx.$t('documents.newFolderDialog.title')),
}));
const __VLS_314 = __VLS_313({
    modelValue: (__VLS_ctx.showNewFolderModal),
    title: (__VLS_ctx.$t('documents.newFolderDialog.title')),
}, ...__VLS_functionalComponentArgsRest(__VLS_313));
__VLS_315.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.form, __VLS_intrinsicElements.form)({
    ...{ onSubmit: (__VLS_ctx.submitNewFolder) },
    ...{ class: "flex flex-col gap-3" },
});
if (__VLS_ctx.newFolderError) {
    const __VLS_316 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_317 = __VLS_asFunctionalComponent(__VLS_316, new __VLS_316({
        variant: "error",
    }));
    const __VLS_318 = __VLS_317({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_317));
    __VLS_319.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.newFolderError);
    var __VLS_319;
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "text-xs opacity-70 font-mono" },
});
(__VLS_ctx.docsState.pathPrefix.value || '/');
const __VLS_320 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_321 = __VLS_asFunctionalComponent(__VLS_320, new __VLS_320({
    modelValue: (__VLS_ctx.newFolderName),
    label: (__VLS_ctx.$t('documents.newFolderDialog.nameLabel')),
    placeholder: (__VLS_ctx.$t('documents.newFolderDialog.namePlaceholder')),
    help: (__VLS_ctx.$t('documents.newFolderDialog.nameHelp')),
    required: true,
}));
const __VLS_322 = __VLS_321({
    modelValue: (__VLS_ctx.newFolderName),
    label: (__VLS_ctx.$t('documents.newFolderDialog.nameLabel')),
    placeholder: (__VLS_ctx.$t('documents.newFolderDialog.namePlaceholder')),
    help: (__VLS_ctx.$t('documents.newFolderDialog.nameHelp')),
    required: true,
}, ...__VLS_functionalComponentArgsRest(__VLS_321));
{
    const { actions: __VLS_thisSlot } = __VLS_315.slots;
    const __VLS_324 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_325 = __VLS_asFunctionalComponent(__VLS_324, new __VLS_324({
        ...{ 'onClick': {} },
        variant: "ghost",
    }));
    const __VLS_326 = __VLS_325({
        ...{ 'onClick': {} },
        variant: "ghost",
    }, ...__VLS_functionalComponentArgsRest(__VLS_325));
    let __VLS_328;
    let __VLS_329;
    let __VLS_330;
    const __VLS_331 = {
        onClick: (...[$event]) => {
            __VLS_ctx.showNewFolderModal = false;
        }
    };
    __VLS_327.slots.default;
    (__VLS_ctx.$t('common.cancel'));
    var __VLS_327;
    const __VLS_332 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_333 = __VLS_asFunctionalComponent(__VLS_332, new __VLS_332({
        ...{ 'onClick': {} },
        variant: "primary",
    }));
    const __VLS_334 = __VLS_333({
        ...{ 'onClick': {} },
        variant: "primary",
    }, ...__VLS_functionalComponentArgsRest(__VLS_333));
    let __VLS_336;
    let __VLS_337;
    let __VLS_338;
    const __VLS_339 = {
        onClick: (__VLS_ctx.submitNewFolder)
    };
    __VLS_335.slots.default;
    (__VLS_ctx.$t('documents.newFolderDialog.create'));
    var __VLS_335;
}
var __VLS_315;
const __VLS_340 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_341 = __VLS_asFunctionalComponent(__VLS_340, new __VLS_340({
    modelValue: (__VLS_ctx.showDiscardModal),
    title: (__VLS_ctx.$t('documents.discard.title')),
    closeOnBackdrop: (!__VLS_ctx.saving),
}));
const __VLS_342 = __VLS_341({
    modelValue: (__VLS_ctx.showDiscardModal),
    title: (__VLS_ctx.$t('documents.discard.title')),
    closeOnBackdrop: (!__VLS_ctx.saving),
}, ...__VLS_functionalComponentArgsRest(__VLS_341));
__VLS_343.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({});
(__VLS_ctx.$t('documents.discard.body'));
{
    const { actions: __VLS_thisSlot } = __VLS_343.slots;
    const __VLS_344 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_345 = __VLS_asFunctionalComponent(__VLS_344, new __VLS_344({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.saving),
    }));
    const __VLS_346 = __VLS_345({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.saving),
    }, ...__VLS_functionalComponentArgsRest(__VLS_345));
    let __VLS_348;
    let __VLS_349;
    let __VLS_350;
    const __VLS_351 = {
        onClick: (...[$event]) => {
            __VLS_ctx.showDiscardModal = false;
        }
    };
    __VLS_347.slots.default;
    (__VLS_ctx.$t('documents.discard.cancel'));
    var __VLS_347;
    const __VLS_352 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_353 = __VLS_asFunctionalComponent(__VLS_352, new __VLS_352({
        ...{ 'onClick': {} },
        variant: "danger",
        disabled: (__VLS_ctx.saving),
    }));
    const __VLS_354 = __VLS_353({
        ...{ 'onClick': {} },
        variant: "danger",
        disabled: (__VLS_ctx.saving),
    }, ...__VLS_functionalComponentArgsRest(__VLS_353));
    let __VLS_356;
    let __VLS_357;
    let __VLS_358;
    const __VLS_359 = {
        onClick: (__VLS_ctx.discardAndBack)
    };
    __VLS_355.slots.default;
    (__VLS_ctx.$t('documents.discard.discard'));
    var __VLS_355;
    const __VLS_360 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_361 = __VLS_asFunctionalComponent(__VLS_360, new __VLS_360({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.saving),
    }));
    const __VLS_362 = __VLS_361({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.saving),
    }, ...__VLS_functionalComponentArgsRest(__VLS_361));
    let __VLS_364;
    let __VLS_365;
    let __VLS_366;
    const __VLS_367 = {
        onClick: (__VLS_ctx.saveAndBack)
    };
    __VLS_363.slots.default;
    (__VLS_ctx.$t('documents.discard.save'));
    var __VLS_363;
}
var __VLS_343;
const __VLS_368 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_369 = __VLS_asFunctionalComponent(__VLS_368, new __VLS_368({
    modelValue: (__VLS_ctx.showRevertModal),
    title: (__VLS_ctx.$t('documents.revertConfirm.title')),
    closeOnBackdrop: (!__VLS_ctx.docsState.loading.value),
}));
const __VLS_370 = __VLS_369({
    modelValue: (__VLS_ctx.showRevertModal),
    title: (__VLS_ctx.$t('documents.revertConfirm.title')),
    closeOnBackdrop: (!__VLS_ctx.docsState.loading.value),
}, ...__VLS_functionalComponentArgsRest(__VLS_369));
__VLS_371.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({});
(__VLS_ctx.$t('documents.revertConfirm.body'));
{
    const { actions: __VLS_thisSlot } = __VLS_371.slots;
    const __VLS_372 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_373 = __VLS_asFunctionalComponent(__VLS_372, new __VLS_372({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.docsState.loading.value),
    }));
    const __VLS_374 = __VLS_373({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.docsState.loading.value),
    }, ...__VLS_functionalComponentArgsRest(__VLS_373));
    let __VLS_376;
    let __VLS_377;
    let __VLS_378;
    const __VLS_379 = {
        onClick: (...[$event]) => {
            __VLS_ctx.showRevertModal = false;
        }
    };
    __VLS_375.slots.default;
    (__VLS_ctx.$t('documents.revertConfirm.cancel'));
    var __VLS_375;
    const __VLS_380 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_381 = __VLS_asFunctionalComponent(__VLS_380, new __VLS_380({
        ...{ 'onClick': {} },
        variant: "danger",
        loading: (__VLS_ctx.docsState.loading.value),
    }));
    const __VLS_382 = __VLS_381({
        ...{ 'onClick': {} },
        variant: "danger",
        loading: (__VLS_ctx.docsState.loading.value),
    }, ...__VLS_functionalComponentArgsRest(__VLS_381));
    let __VLS_384;
    let __VLS_385;
    let __VLS_386;
    const __VLS_387 = {
        onClick: (__VLS_ctx.revertChanges)
    };
    __VLS_383.slots.default;
    (__VLS_ctx.$t('documents.revertConfirm.confirm'));
    var __VLS_383;
}
var __VLS_371;
const __VLS_388 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_389 = __VLS_asFunctionalComponent(__VLS_388, new __VLS_388({
    modelValue: (__VLS_ctx.showCreateModal),
    title: (__VLS_ctx.$t('documents.create.newDocument')),
    closeOnBackdrop: (false),
}));
const __VLS_390 = __VLS_389({
    modelValue: (__VLS_ctx.showCreateModal),
    title: (__VLS_ctx.$t('documents.create.newDocument')),
    closeOnBackdrop: (false),
}, ...__VLS_functionalComponentArgsRest(__VLS_389));
__VLS_391.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex gap-2 mb-4" },
});
const __VLS_392 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_393 = __VLS_asFunctionalComponent(__VLS_392, new __VLS_392({
    ...{ 'onClick': {} },
    variant: (__VLS_ctx.createMode === 'inline' ? 'primary' : 'ghost'),
    size: "sm",
    disabled: (__VLS_ctx.creating),
}));
const __VLS_394 = __VLS_393({
    ...{ 'onClick': {} },
    variant: (__VLS_ctx.createMode === 'inline' ? 'primary' : 'ghost'),
    size: "sm",
    disabled: (__VLS_ctx.creating),
}, ...__VLS_functionalComponentArgsRest(__VLS_393));
let __VLS_396;
let __VLS_397;
let __VLS_398;
const __VLS_399 = {
    onClick: (...[$event]) => {
        __VLS_ctx.setCreateMode('inline');
    }
};
__VLS_395.slots.default;
(__VLS_ctx.$t('documents.create.typeContent'));
var __VLS_395;
const __VLS_400 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_401 = __VLS_asFunctionalComponent(__VLS_400, new __VLS_400({
    ...{ 'onClick': {} },
    variant: (__VLS_ctx.createMode === 'upload' ? 'primary' : 'ghost'),
    size: "sm",
    disabled: (__VLS_ctx.creating),
}));
const __VLS_402 = __VLS_401({
    ...{ 'onClick': {} },
    variant: (__VLS_ctx.createMode === 'upload' ? 'primary' : 'ghost'),
    size: "sm",
    disabled: (__VLS_ctx.creating),
}, ...__VLS_functionalComponentArgsRest(__VLS_401));
let __VLS_404;
let __VLS_405;
let __VLS_406;
const __VLS_407 = {
    onClick: (...[$event]) => {
        __VLS_ctx.setCreateMode('upload');
    }
};
__VLS_403.slots.default;
(__VLS_ctx.$t('documents.create.uploadFile'));
var __VLS_403;
__VLS_asFunctionalElement(__VLS_intrinsicElements.form, __VLS_intrinsicElements.form)({
    ...{ onSubmit: (__VLS_ctx.submitCreate) },
    ...{ class: "flex flex-col gap-3" },
});
if (__VLS_ctx.createError) {
    const __VLS_408 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_409 = __VLS_asFunctionalComponent(__VLS_408, new __VLS_408({
        variant: "error",
    }));
    const __VLS_410 = __VLS_409({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_409));
    __VLS_411.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.createError);
    var __VLS_411;
}
if (__VLS_ctx.createMode === 'inline') {
    const __VLS_412 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_413 = __VLS_asFunctionalComponent(__VLS_412, new __VLS_412({
        modelValue: (__VLS_ctx.createPath || '/'),
        label: (__VLS_ctx.$t('documents.create.pathLabel')),
        disabled: true,
        readonly: true,
    }));
    const __VLS_414 = __VLS_413({
        modelValue: (__VLS_ctx.createPath || '/'),
        label: (__VLS_ctx.$t('documents.create.pathLabel')),
        disabled: true,
        readonly: true,
    }, ...__VLS_functionalComponentArgsRest(__VLS_413));
    const __VLS_416 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_417 = __VLS_asFunctionalComponent(__VLS_416, new __VLS_416({
        ...{ 'onKeydown': {} },
        modelValue: (__VLS_ctx.createName),
        label: (__VLS_ctx.$t('documents.create.nameLabel')),
        placeholder: (__VLS_ctx.$t('documents.create.namePlaceholder')),
        required: true,
        disabled: (__VLS_ctx.creating),
    }));
    const __VLS_418 = __VLS_417({
        ...{ 'onKeydown': {} },
        modelValue: (__VLS_ctx.createName),
        label: (__VLS_ctx.$t('documents.create.nameLabel')),
        placeholder: (__VLS_ctx.$t('documents.create.namePlaceholder')),
        required: true,
        disabled: (__VLS_ctx.creating),
    }, ...__VLS_functionalComponentArgsRest(__VLS_417));
    let __VLS_420;
    let __VLS_421;
    let __VLS_422;
    const __VLS_423 = {
        onKeydown: (__VLS_ctx.submitCreate)
    };
    var __VLS_419;
    const __VLS_424 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_425 = __VLS_asFunctionalComponent(__VLS_424, new __VLS_424({
        modelValue: (__VLS_ctx.createTitle),
        label: (__VLS_ctx.$t('documents.create.titleLabel')),
        placeholder: (__VLS_ctx.$t('documents.create.titlePlaceholder')),
        disabled: (__VLS_ctx.creating),
    }));
    const __VLS_426 = __VLS_425({
        modelValue: (__VLS_ctx.createTitle),
        label: (__VLS_ctx.$t('documents.create.titleLabel')),
        placeholder: (__VLS_ctx.$t('documents.create.titlePlaceholder')),
        disabled: (__VLS_ctx.creating),
    }, ...__VLS_functionalComponentArgsRest(__VLS_425));
    const __VLS_428 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_429 = __VLS_asFunctionalComponent(__VLS_428, new __VLS_428({
        modelValue: (__VLS_ctx.createTagsRaw),
        label: (__VLS_ctx.$t('documents.create.tagsLabel')),
        placeholder: (__VLS_ctx.$t('documents.create.tagsPlaceholder')),
        disabled: (__VLS_ctx.creating),
        help: (__VLS_ctx.$t('documents.create.tagsHelp')),
    }));
    const __VLS_430 = __VLS_429({
        modelValue: (__VLS_ctx.createTagsRaw),
        label: (__VLS_ctx.$t('documents.create.tagsLabel')),
        placeholder: (__VLS_ctx.$t('documents.create.tagsPlaceholder')),
        disabled: (__VLS_ctx.creating),
        help: (__VLS_ctx.$t('documents.create.tagsHelp')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_429));
    const __VLS_432 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_433 = __VLS_asFunctionalComponent(__VLS_432, new __VLS_432({
        modelValue: (__VLS_ctx.createMime),
        options: (__VLS_ctx.createMimeOptions),
        label: (__VLS_ctx.$t('documents.create.typeLabel')),
        disabled: (__VLS_ctx.creating),
    }));
    const __VLS_434 = __VLS_433({
        modelValue: (__VLS_ctx.createMime),
        options: (__VLS_ctx.createMimeOptions),
        label: (__VLS_ctx.$t('documents.create.typeLabel')),
        disabled: (__VLS_ctx.creating),
    }, ...__VLS_functionalComponentArgsRest(__VLS_433));
    const __VLS_436 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_437 = __VLS_asFunctionalComponent(__VLS_436, new __VLS_436({
        modelValue: (__VLS_ctx.createKind),
        options: (__VLS_ctx.kindCreateOptions),
        label: (__VLS_ctx.$t('documents.create.kindLabel')),
        help: (__VLS_ctx.$t('documents.create.kindHelp')),
        disabled: (__VLS_ctx.creating),
    }));
    const __VLS_438 = __VLS_437({
        modelValue: (__VLS_ctx.createKind),
        options: (__VLS_ctx.kindCreateOptions),
        label: (__VLS_ctx.$t('documents.create.kindLabel')),
        help: (__VLS_ctx.$t('documents.create.kindHelp')),
        disabled: (__VLS_ctx.creating),
    }, ...__VLS_functionalComponentArgsRest(__VLS_437));
    const __VLS_440 = {}.CodeEditor;
    /** @type {[typeof __VLS_components.CodeEditor, ]} */ ;
    // @ts-ignore
    const __VLS_441 = __VLS_asFunctionalComponent(__VLS_440, new __VLS_440({
        modelValue: (__VLS_ctx.createContent),
        label: (__VLS_ctx.$t('documents.create.contentLabel')),
        rows: (14),
        disabled: (__VLS_ctx.creating),
        mimeType: (__VLS_ctx.createMime),
    }));
    const __VLS_442 = __VLS_441({
        modelValue: (__VLS_ctx.createContent),
        label: (__VLS_ctx.$t('documents.create.contentLabel')),
        rows: (14),
        disabled: (__VLS_ctx.creating),
        mimeType: (__VLS_ctx.createMime),
    }, ...__VLS_functionalComponentArgsRest(__VLS_441));
}
else {
    const __VLS_444 = {}.VFileInput;
    /** @type {[typeof __VLS_components.VFileInput, ]} */ ;
    // @ts-ignore
    const __VLS_445 = __VLS_asFunctionalComponent(__VLS_444, new __VLS_444({
        modelValue: (__VLS_ctx.createFiles),
        label: (__VLS_ctx.$t('documents.create.filesLabel')),
        multiple: true,
        disabled: (__VLS_ctx.creating),
        help: (__VLS_ctx.$t('documents.create.filesHelp')),
    }));
    const __VLS_446 = __VLS_445({
        modelValue: (__VLS_ctx.createFiles),
        label: (__VLS_ctx.$t('documents.create.filesLabel')),
        multiple: true,
        disabled: (__VLS_ctx.creating),
        help: (__VLS_ctx.$t('documents.create.filesHelp')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_445));
    if (__VLS_ctx.createFiles.length <= 1) {
        const __VLS_448 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_449 = __VLS_asFunctionalComponent(__VLS_448, new __VLS_448({
            modelValue: (__VLS_ctx.createPath || '/'),
            label: (__VLS_ctx.$t('documents.create.pathLabel')),
            disabled: true,
            readonly: true,
        }));
        const __VLS_450 = __VLS_449({
            modelValue: (__VLS_ctx.createPath || '/'),
            label: (__VLS_ctx.$t('documents.create.pathLabel')),
            disabled: true,
            readonly: true,
        }, ...__VLS_functionalComponentArgsRest(__VLS_449));
        const __VLS_452 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_453 = __VLS_asFunctionalComponent(__VLS_452, new __VLS_452({
            ...{ 'onKeydown': {} },
            modelValue: (__VLS_ctx.createName),
            label: (__VLS_ctx.$t('documents.create.nameLabel')),
            placeholder: (__VLS_ctx.$t('documents.create.namePlaceholderUpload')),
            disabled: (__VLS_ctx.creating),
            help: (__VLS_ctx.$t('documents.create.nameHelpUpload')),
        }));
        const __VLS_454 = __VLS_453({
            ...{ 'onKeydown': {} },
            modelValue: (__VLS_ctx.createName),
            label: (__VLS_ctx.$t('documents.create.nameLabel')),
            placeholder: (__VLS_ctx.$t('documents.create.namePlaceholderUpload')),
            disabled: (__VLS_ctx.creating),
            help: (__VLS_ctx.$t('documents.create.nameHelpUpload')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_453));
        let __VLS_456;
        let __VLS_457;
        let __VLS_458;
        const __VLS_459 = {
            onKeydown: (__VLS_ctx.submitCreate)
        };
        var __VLS_455;
        const __VLS_460 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_461 = __VLS_asFunctionalComponent(__VLS_460, new __VLS_460({
            modelValue: (__VLS_ctx.createTitle),
            label: (__VLS_ctx.$t('documents.create.titleLabel')),
            placeholder: (__VLS_ctx.$t('documents.create.titlePlaceholder')),
            disabled: (__VLS_ctx.creating),
        }));
        const __VLS_462 = __VLS_461({
            modelValue: (__VLS_ctx.createTitle),
            label: (__VLS_ctx.$t('documents.create.titleLabel')),
            placeholder: (__VLS_ctx.$t('documents.create.titlePlaceholder')),
            disabled: (__VLS_ctx.creating),
        }, ...__VLS_functionalComponentArgsRest(__VLS_461));
    }
    const __VLS_464 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_465 = __VLS_asFunctionalComponent(__VLS_464, new __VLS_464({
        modelValue: (__VLS_ctx.createTagsRaw),
        label: (__VLS_ctx.$t('documents.create.tagsLabel')),
        placeholder: (__VLS_ctx.$t('documents.create.tagsPlaceholder')),
        disabled: (__VLS_ctx.creating),
        help: (__VLS_ctx.createFiles.length > 1
            ? __VLS_ctx.$t('documents.create.tagsHelpMulti')
            : __VLS_ctx.$t('documents.create.tagsHelp')),
    }));
    const __VLS_466 = __VLS_465({
        modelValue: (__VLS_ctx.createTagsRaw),
        label: (__VLS_ctx.$t('documents.create.tagsLabel')),
        placeholder: (__VLS_ctx.$t('documents.create.tagsPlaceholder')),
        disabled: (__VLS_ctx.creating),
        help: (__VLS_ctx.createFiles.length > 1
            ? __VLS_ctx.$t('documents.create.tagsHelpMulti')
            : __VLS_ctx.$t('documents.create.tagsHelp')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_465));
    if (__VLS_ctx.uploadProgress.length > 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
            ...{ class: "flex flex-col gap-1.5 text-sm border border-base-300 rounded-md p-3 bg-base-200" },
        });
        for (const [item] of __VLS_getVForSourceType((__VLS_ctx.uploadProgress))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                key: (item.fileName),
                ...{ class: "flex items-center gap-2" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "font-mono w-4 text-center" },
                'aria-hidden': "true",
            });
            if (item.status === 'pending') {
            }
            else if (item.status === 'uploading') {
            }
            else if (item.status === 'ok') {
            }
            else {
            }
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "font-mono text-xs truncate flex-1" },
            });
            (item.fileName);
            if (item.message) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "text-xs text-error truncate" },
                    title: (item.message),
                });
                (item.message);
            }
        }
    }
}
{
    const { actions: __VLS_thisSlot } = __VLS_391.slots;
    const __VLS_468 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_469 = __VLS_asFunctionalComponent(__VLS_468, new __VLS_468({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.creating),
    }));
    const __VLS_470 = __VLS_469({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.creating),
    }, ...__VLS_functionalComponentArgsRest(__VLS_469));
    let __VLS_472;
    let __VLS_473;
    let __VLS_474;
    const __VLS_475 = {
        onClick: (...[$event]) => {
            __VLS_ctx.showCreateModal = false;
        }
    };
    __VLS_471.slots.default;
    (__VLS_ctx.$t('documents.create.cancel'));
    var __VLS_471;
    const __VLS_476 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_477 = __VLS_asFunctionalComponent(__VLS_476, new __VLS_476({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.creating),
    }));
    const __VLS_478 = __VLS_477({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.creating),
    }, ...__VLS_functionalComponentArgsRest(__VLS_477));
    let __VLS_480;
    let __VLS_481;
    let __VLS_482;
    const __VLS_483 = {
        onClick: (__VLS_ctx.submitCreate)
    };
    __VLS_479.slots.default;
    (__VLS_ctx.createMode === 'upload'
        ? __VLS_ctx.$t('documents.create.submitUpload')
        : __VLS_ctx.$t('documents.create.submitCreate'));
    var __VLS_479;
}
var __VLS_391;
{
    const { 'right-panel': __VLS_thisSlot } = __VLS_3.slots;
    if (__VLS_ctx.helpResource) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "p-4 flex flex-col gap-4" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({
            ...{ class: "text-xs uppercase opacity-60 mb-2" },
        });
        (__VLS_ctx.$t('documents.help.title'));
        if (__VLS_ctx.help.loading.value) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs opacity-60" },
            });
            (__VLS_ctx.$t('documents.help.loading'));
        }
        else if (__VLS_ctx.help.error.value) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs opacity-60" },
            });
            (__VLS_ctx.$t('documents.help.unavailable', { error: __VLS_ctx.help.error.value }));
        }
        else if (!__VLS_ctx.help.content.value) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs opacity-60" },
            });
            (__VLS_ctx.$t('documents.help.empty'));
        }
        else {
            const __VLS_484 = {}.MarkdownView;
            /** @type {[typeof __VLS_components.MarkdownView, ]} */ ;
            // @ts-ignore
            const __VLS_485 = __VLS_asFunctionalComponent(__VLS_484, new __VLS_484({
                source: (__VLS_ctx.help.content.value),
            }));
            const __VLS_486 = __VLS_485({
                source: (__VLS_ctx.help.content.value),
            }, ...__VLS_functionalComponentArgsRest(__VLS_485));
        }
    }
}
{
    const { footer: __VLS_thisSlot } = __VLS_3.slots;
    if (__VLS_ctx.docsState.selected.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "px-6 py-3 flex items-center gap-2 bg-base-100" },
        });
        const __VLS_488 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_489 = __VLS_asFunctionalComponent(__VLS_488, new __VLS_488({
            ...{ 'onClick': {} },
            variant: "danger",
            disabled: (__VLS_ctx.saving || __VLS_ctx.deleting),
        }));
        const __VLS_490 = __VLS_489({
            ...{ 'onClick': {} },
            variant: "danger",
            disabled: (__VLS_ctx.saving || __VLS_ctx.deleting),
        }, ...__VLS_functionalComponentArgsRest(__VLS_489));
        let __VLS_492;
        let __VLS_493;
        let __VLS_494;
        const __VLS_495 = {
            onClick: (__VLS_ctx.openDeleteModal)
        };
        __VLS_491.slots.default;
        (__VLS_ctx.isSelectedInTrash
            ? __VLS_ctx.$t('documents.detail.deletePermanent')
            : __VLS_ctx.$t('documents.detail.delete'));
        var __VLS_491;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "flex-1" },
        });
        const __VLS_496 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_497 = __VLS_asFunctionalComponent(__VLS_496, new __VLS_496({
            variant: "ghost",
            href: (__VLS_ctx.downloadUrl(__VLS_ctx.docsState.selected.value)),
            download: (__VLS_ctx.docsState.selected.value.name || 'document'),
        }));
        const __VLS_498 = __VLS_497({
            variant: "ghost",
            href: (__VLS_ctx.downloadUrl(__VLS_ctx.docsState.selected.value)),
            download: (__VLS_ctx.docsState.selected.value.name || 'document'),
        }, ...__VLS_functionalComponentArgsRest(__VLS_497));
        __VLS_499.slots.default;
        (__VLS_ctx.$t('documents.detail.download'));
        var __VLS_499;
        if (__VLS_ctx.isDirty) {
            const __VLS_500 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_501 = __VLS_asFunctionalComponent(__VLS_500, new __VLS_500({
                ...{ 'onClick': {} },
                variant: "ghost",
                disabled: (__VLS_ctx.saving),
            }));
            const __VLS_502 = __VLS_501({
                ...{ 'onClick': {} },
                variant: "ghost",
                disabled: (__VLS_ctx.saving),
            }, ...__VLS_functionalComponentArgsRest(__VLS_501));
            let __VLS_504;
            let __VLS_505;
            let __VLS_506;
            const __VLS_507 = {
                onClick: (__VLS_ctx.requestRevert)
            };
            __VLS_503.slots.default;
            (__VLS_ctx.$t('documents.detail.revert'));
            var __VLS_503;
        }
        const __VLS_508 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_509 = __VLS_asFunctionalComponent(__VLS_508, new __VLS_508({
            ...{ 'onClick': {} },
            variant: "secondary",
            loading: (__VLS_ctx.saving),
        }));
        const __VLS_510 = __VLS_509({
            ...{ 'onClick': {} },
            variant: "secondary",
            loading: (__VLS_ctx.saving),
        }, ...__VLS_functionalComponentArgsRest(__VLS_509));
        let __VLS_512;
        let __VLS_513;
        let __VLS_514;
        const __VLS_515 = {
            onClick: (__VLS_ctx.apply)
        };
        __VLS_511.slots.default;
        (__VLS_ctx.$t('documents.detail.apply'));
        var __VLS_511;
        const __VLS_516 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_517 = __VLS_asFunctionalComponent(__VLS_516, new __VLS_516({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.saving),
        }));
        const __VLS_518 = __VLS_517({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.saving),
        }, ...__VLS_functionalComponentArgsRest(__VLS_517));
        let __VLS_520;
        let __VLS_521;
        let __VLS_522;
        const __VLS_523 = {
            onClick: (__VLS_ctx.save)
        };
        __VLS_519.slots.default;
        (__VLS_ctx.$t('documents.detail.save'));
        var __VLS_519;
    }
}
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['h-full']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['px-6']} */ ;
/** @type {__VLS_StyleScopedClasses['pt-4']} */ ;
/** @type {__VLS_StyleScopedClasses['pb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['w-[150px]']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['px-6']} */ ;
/** @type {__VLS_StyleScopedClasses['pt-4']} */ ;
/** @type {__VLS_StyleScopedClasses['pb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['border-b']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-3']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-y-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['basis-[16rem]']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-h-0']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['container']} */ ;
/** @type {__VLS_StyleScopedClasses['mx-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['py-4']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-5xl']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-4']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['badge']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-info']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['badge']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-warning']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['badge']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-ghost']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['grow']} */ ;
/** @type {__VLS_StyleScopedClasses['props-toggle']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:opacity-100']} */ ;
/** @type {__VLS_StyleScopedClasses['props-toggle__chevron']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-md']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['table']} */ ;
/** @type {__VLS_StyleScopedClasses['table-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['w-1/3']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['break-all']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-md']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['whitespace-pre-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['italic']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-base-content/70']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tabs']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tabs']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tabs']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tabs']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tabs']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tabs']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tabs']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tabs']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tabs']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tabs']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tabs']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tabs']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['markdown-preview-pane']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tabs']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab--active']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['folder-row']} */ ;
/** @type {__VLS_StyleScopedClasses['text-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['leading-none']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['badge']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-info']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['badge']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-ghost']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-right']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['text-warning']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-md']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['w-4']} */ ;
/** @type {__VLS_StyleScopedClasses['text-center']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-error']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['px-6']} */ ;
/** @type {__VLS_StyleScopedClasses['py-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            EditorShell: EditorShell,
            ProjectListSidebar: ProjectListSidebar,
            VAlert: VAlert,
            VButton: VButton,
            VCard: VCard,
            VCheckbox: VCheckbox,
            VDataList: VDataList,
            VEmptyState: VEmptyState,
            VFileInput: VFileInput,
            VInput: VInput,
            VModal: VModal,
            VPagination: VPagination,
            VSelect: VSelect,
            VTextarea: VTextarea,
            CodeEditor: CodeEditor,
            MarkdownView: MarkdownView,
            DocumentPreview: DocumentPreview,
            DocumentIcon: DocumentIcon,
            DocumentArchives: DocumentArchives,
            ListView: ListView,
            TreeView: TreeView,
            ChecklistView: ChecklistView,
            MindmapView: MindmapView,
            RecordsView: RecordsView,
            GraphView: GraphView,
            ChartView: ChartView,
            SheetView: SheetView,
            SlidesView: SlidesView,
            DiagramView: DiagramView,
            OfficeEditor: OfficeEditor,
            projectsState: projectsState,
            docsState: docsState,
            selectedProjectId: selectedProjectId,
            editTitle: editTitle,
            editPath: editPath,
            editMimeType: editMimeType,
            editInlineText: editInlineText,
            editAutoSummary: editAutoSummary,
            editSummaryDirty: editSummaryDirty,
            editSummary: editSummary,
            summarySaving: summarySaving,
            summarySaveMessage: summarySaveMessage,
            editRagEnabled: editRagEnabled,
            editError: editError,
            saving: saving,
            showCreateModal: showCreateModal,
            showDeleteModal: showDeleteModal,
            deleting: deleting,
            isDirty: isDirty,
            showDiscardModal: showDiscardModal,
            showRevertModal: showRevertModal,
            propsCollapsed: propsCollapsed,
            togglePropsCollapsed: togglePropsCollapsed,
            archiveCount: archiveCount,
            onArchiveCount: onArchiveCount,
            createMode: createMode,
            createPath: createPath,
            createName: createName,
            createTitle: createTitle,
            createTagsRaw: createTagsRaw,
            createMime: createMime,
            createContent: createContent,
            createKind: createKind,
            createFiles: createFiles,
            createError: createError,
            creating: creating,
            uploadProgress: uploadProgress,
            createMimeOptions: createMimeOptions,
            editMimeOptions: editMimeOptions,
            projectOptions: projectOptions,
            focusZone: focusZone,
            onProjectListDataChanged: onProjectListDataChanged,
            documentFilter: documentFilter,
            pathSegmentBack: pathSegmentBack,
            navigateIntoFolder: navigateIntoFolder,
            showNewFolderModal: showNewFolderModal,
            newFolderName: newFolderName,
            newFolderError: newFolderError,
            openNewFolderModal: openNewFolderModal,
            submitNewFolder: submitNewFolder,
            changePage: changePage,
            isAppDocument: isAppDocument,
            openDocument: openDocument,
            openDocumentInEditor: openDocumentInEditor,
            onArchiveRestored: onArchiveRestored,
            contentTab: contentTab,
            previewReloadCounter: previewReloadCounter,
            isListDocument: isListDocument,
            isChecklistDocument: isChecklistDocument,
            isTreeDocument: isTreeDocument,
            isMindmapDocument: isMindmapDocument,
            isRecordsDocument: isRecordsDocument,
            isGraphDocument: isGraphDocument,
            isChartDocument: isChartDocument,
            isSheetDocument: isSheetDocument,
            isSlidesDocument: isSlidesDocument,
            isDiagramDocument: isDiagramDocument,
            calendarKind: calendarKind,
            isCalendarDocument: isCalendarDocument,
            isOfficeEditableDocument: isOfficeEditableDocument,
            isMarkdownDocument: isMarkdownDocument,
            markdownFollowUp: markdownFollowUp,
            TRASH_PREFIX: TRASH_PREFIX,
            isSelectedInTrash: isSelectedInTrash,
            parsedList: parsedList,
            parsedChecklist: parsedChecklist,
            parsedTree: parsedTree,
            parsedRecords: parsedRecords,
            parsedGraph: parsedGraph,
            parsedChart: parsedChart,
            parsedSheet: parsedSheet,
            parsedSlides: parsedSlides,
            parsedDiagram: parsedDiagram,
            parsedCalendar: parsedCalendar,
            onListChanged: onListChanged,
            onChecklistChanged: onChecklistChanged,
            onTreeChanged: onTreeChanged,
            onRecordsChanged: onRecordsChanged,
            onGraphChanged: onGraphChanged,
            onSheetChanged: onSheetChanged,
            onChartChanged: onChartChanged,
            help: help,
            helpResource: helpResource,
            requestBackToList: requestBackToList,
            discardAndBack: discardAndBack,
            requestRevert: requestRevert,
            revertChanges: revertChanges,
            saveAndBack: saveAndBack,
            downloadUrl: downloadUrl,
            openCreateModal: openCreateModal,
            kindCreateOptions: kindCreateOptions,
            setCreateMode: setCreateMode,
            submitCreate: submitCreate,
            saveSummary: saveSummary,
            apply: apply,
            save: save,
            openDeleteModal: openDeleteModal,
            confirmDelete: confirmDelete,
            headerEntries: headerEntries,
            breadcrumbs: breadcrumbs,
            formatBytes: formatBytes,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=DocumentApp.vue.js.map