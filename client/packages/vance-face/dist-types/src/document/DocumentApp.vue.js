import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { EditorShell, VAlert, VBackButton, VButton, VCard, VDataList, VEmptyState, VFileInput, VInput, VModal, VPagination, VSelect, CodeEditor, MarkdownView, } from '@/components';
import { useDocuments } from '@/composables/useDocuments';
import { useHelp } from '@/composables/useHelp';
import { useTenantProjects } from '@/composables/useTenantProjects';
import { documentContentUrl } from '@vance/shared';
import { consumeDocumentDraft } from '@/platform';
import DocumentPreview from './DocumentPreview.vue';
import DocumentIcon from './DocumentIcon.vue';
import ListView from './ListView.vue';
import TreeView from './TreeView.vue';
import MindmapView from './MindmapView.vue';
import RecordsView from './RecordsView.vue';
import GraphView from './GraphView.vue';
import SheetView from './SheetView.vue';
import { isListMime, parseList, serializeList, ListCodecError, } from './listItemsCodec';
import { isTreeMime, parseTree, serializeTree, TreeCodecError, } from './treeItemsCodec';
import { isRecordsMime, parseRecords, serializeRecords, RecordsCodecError, } from './recordsCodec';
import { isGraphMime, parseGraph, serializeGraph, GraphCodecError, } from './graphCodec';
import { isSheetMime, parseSheet, serializeSheet, SheetCodecError, } from './sheetCodec';
const PAGE_SIZE = 20;
const { t } = useI18n();
const projectsState = useTenantProjects();
const docsState = useDocuments(PAGE_SIZE);
const selectedProjectId = ref(null);
const editTitle = ref('');
const editPath = ref('');
const editInlineText = ref('');
const editError = ref(null);
const saving = ref(false);
const showCreateModal = ref(false);
// Delete-confirm modal — destructive action gets an explicit
// confirmation step. See specification/web-ui.md §7.7.1.
const showDeleteModal = ref(false);
const deleting = ref(false);
const createMode = ref('inline');
const createPath = ref('');
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
onMounted(async () => {
    await projectsState.reload();
    // Restore last selection from the URL, if any. URL is the source of truth
    // for deep-links — reload-friendly without extra storage keys.
    const params = new URLSearchParams(window.location.search);
    const queryProject = params.get('projectId');
    const queryDoc = params.get('documentId');
    if (queryProject && projectsState.projects.value.some((p) => p.name === queryProject)) {
        selectedProjectId.value = queryProject;
    }
    else if (projectsState.projects.value.length > 0) {
        selectedProjectId.value = projectsState.projects.value[0].name;
    }
    if (selectedProjectId.value) {
        await Promise.all([
            docsState.loadPage(selectedProjectId.value, 0),
            docsState.loadFolders(selectedProjectId.value),
            docsState.loadKinds(selectedProjectId.value),
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
    if (!next)
        return;
    syncQueryParam('projectId', next);
    syncQueryParam('documentId', null);
    docsState.clearSelection();
    // Reset filters on project switch — folder/kind lists belong to
    // the new project and the previous filters won't match anyway.
    docsState.pathPrefix.value = '';
    docsState.kindFilter.value = '';
    await Promise.all([
        docsState.loadPage(next, 0, '', ''),
        docsState.loadFolders(next),
        docsState.loadKinds(next),
    ]);
});
/**
 * Apply the path-filter input. Debounced via a small timeout so
 * typing into the combobox doesn't fire one request per keystroke;
 * pressing Enter or selecting a datalist option commits immediately.
 */
let filterTimer = null;
function applyPathFilter(prefix, immediate = false) {
    const project = selectedProjectId.value;
    if (!project)
        return;
    if (filterTimer)
        clearTimeout(filterTimer);
    const fire = () => {
        void docsState.loadPage(project, 0, prefix);
    };
    if (immediate)
        fire();
    else
        filterTimer = setTimeout(fire, 300);
}
/**
 * Switch the {@code kind} filter. Triggered immediately from the select
 * — no debounce needed (one click per change). Passing `''` clears the
 * filter back to "all kinds".
 */
function applyKindFilter(kind) {
    const project = selectedProjectId.value;
    if (!project)
        return;
    void docsState.loadPage(project, 0, undefined, kind);
}
watch(() => docsState.selected.value?.id ?? null, (id) => {
    syncQueryParam('documentId', id);
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
/** First-level folders only — `recipes` yes, `recipes/sub` no. */
const topLevelFolders = computed(() => docsState.folders.value.filter((f) => !f.includes('/')));
/**
 * Sidebar selection key derived from the current `pathPrefix`.
 * - `''` → "All" entry highlighted (no filter)
 * - `<folder>` → that top-level folder entry highlighted
 * - `null` → free-form prefix typed in the input, nothing highlighted
 */
const selectedFolderKey = computed(() => {
    const p = docsState.pathPrefix.value.trim();
    if (!p)
        return '';
    if (p.endsWith('/')) {
        const stripped = p.slice(0, -1);
        return stripped.includes('/') ? null : stripped;
    }
    return null;
});
function selectFolder(folder) {
    // null === "All" (clear filter), otherwise the folder name without
    // trailing slash. We always commit to pathPrefix with the slash so
    // the prefix-match on the server doesn't accidentally span sibling
    // folders that happen to share a prefix (e.g. "rec" matching
    // "recipes" and "records").
    applyPathFilter(folder == null ? '' : folder + '/', true);
}
async function openDocument(doc) {
    if (!doc.id)
        return;
    await docsState.loadOne(doc.id);
    fillEditor();
}
function fillEditor() {
    const sel = docsState.selected.value;
    editTitle.value = sel?.title ?? '';
    editPath.value = sel?.path ?? '';
    editInlineText.value = sel?.inlineText ?? '';
    editError.value = null;
    // Switching documents resets the editor mode to the kind-aware
    // default — `sheet`, `graph`, `records`, `mindmap`, `list`,
    // `tree`, then `raw` for everything else.
    if (isSheetDocument.value)
        contentTab.value = 'sheet';
    else if (isGraphDocument.value)
        contentTab.value = 'graph';
    else if (isRecordsDocument.value)
        contentTab.value = 'records';
    else if (isListDocument.value)
        contentTab.value = 'list';
    else if (isMindmapDocument.value)
        contentTab.value = 'mindmap';
    else if (isTreeDocument.value)
        contentTab.value = 'tree';
    else
        contentTab.value = 'raw';
}
const contentTab = ref('raw');
const isListDocument = computed(() => {
    const sel = docsState.selected.value;
    if (!sel?.inline)
        return false;
    if ((sel.kind ?? '').toLowerCase() !== 'list')
        return false;
    return isListMime(sel.mimeType);
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
// Trash convention: documents under `_vance/bin/` are already in the
// project's trash folder (mirrors DocumentService.TRASH_FOLDER_PREFIX
// on the server). The DELETE endpoint dispatches on this — first
// click moves regular docs to the bin, a second click on the bin
// entry hard-deletes it. The UI swaps button label and confirmation
// text to match.
const TRASH_PREFIX = '_vance/bin/';
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
    { prefix: 'recipes/', resource: 'recipe-field-docs.md' },
    { prefix: 'strategies/', resource: 'strategy-field-docs.md' },
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
 * Build a `Content-Disposition: attachment` URL for a document.
 * The {@code documentContentUrl} helper appends the JWT as
 * `?token=…` so an `<a download>` link works without a header.
 */
function downloadUrl(doc) {
    return documentContentUrl(doc.id, true);
}
function openCreateModal(prefill) {
    createMode.value = 'inline';
    createPath.value = prefill?.path ?? '';
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
    'list', 'tree', 'text', 'mindmap', 'graph', 'sheet', 'data', 'records', 'schema',
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
            return 'kind: list\n---\nitems:\n  - text: item 1\n  - text: item 2\n';
    }
    if (kind === 'tree') {
        if (isMd)
            return '---\nkind: tree\n---\n- parent\n  - child\n';
        if (isJson)
            return '{\n  "$meta": { "kind": "tree" },\n  "items": [\n    { "text": "parent", "children": [\n      { "text": "child", "children": [] }\n    ]}\n  ]\n}\n';
        if (isYaml)
            return 'kind: tree\n---\nitems:\n  - text: parent\n    children:\n      - text: child\n        children: []\n';
    }
    if (kind === 'mindmap') {
        if (isMd)
            return '---\nkind: mindmap\n---\n- root topic\n  - branch one\n  - branch two\n';
        if (isJson)
            return '{\n  "$meta": { "kind": "mindmap" },\n  "items": [\n    { "text": "root topic", "children": [\n      { "text": "branch one", "children": [] },\n      { "text": "branch two", "children": [] }\n    ]}\n  ]\n}\n';
        if (isYaml)
            return 'kind: mindmap\n---\nitems:\n  - text: root topic\n    children:\n      - text: branch one\n        children: []\n      - text: branch two\n        children: []\n';
    }
    if (kind === 'records') {
        if (isMd)
            return '---\nkind: records\nschema: name, email, role\n---\n- Alice, alice@example.com, admin\n- Bob, bob@example.com, user\n';
        if (isJson)
            return '{\n  "$meta": { "kind": "records" },\n  "schema": ["name", "email", "role"],\n  "items": [\n    { "name": "Alice", "email": "alice@example.com", "role": "admin" },\n    { "name": "Bob", "email": "bob@example.com", "role": "user" }\n  ]\n}\n';
        if (isYaml)
            return 'kind: records\n---\nschema: [name, email, role]\nitems:\n  - name: Alice\n    email: alice@example.com\n    role: admin\n  - name: Bob\n    email: bob@example.com\n    role: user\n';
    }
    if (kind === 'graph') {
        // Markdown isn't supported for graphs (spec §3.3); fall through
        // to the schema-less branch below if md is picked, so the user
        // gets just the header and a hint to switch mime type via Raw.
        if (isJson)
            return '{\n  "$meta": { "kind": "graph" },\n  "graph": { "directed": true },\n  "nodes": [\n    { "id": "alice", "label": "Alice" },\n    { "id": "bob", "label": "Bob" }\n  ],\n  "edges": [\n    { "source": "alice", "target": "bob" }\n  ]\n}\n';
        if (isYaml)
            return 'kind: graph\n---\ngraph:\n  directed: true\nnodes:\n  - id: alice\n    label: Alice\n  - id: bob\n    label: Bob\nedges:\n  - source: alice\n    target: bob\n';
    }
    if (kind === 'sheet') {
        // Markdown not supported for sheets (spec §3.3) — falls through
        // to the schema-less branch below if md is picked.
        if (isJson)
            return '{\n  "$meta": { "kind": "sheet" },\n  "schema": ["A", "B", "C"],\n  "rows": 5,\n  "cells": [\n    { "field": "A1", "data": "Item" },\n    { "field": "B1", "data": "Qty" },\n    { "field": "C1", "data": "Total" },\n    { "field": "A2", "data": "Apples" },\n    { "field": "B2", "data": "10" },\n    { "field": "C2", "data": "=B2*1.5" }\n  ]\n}\n';
        if (isYaml)
            return 'kind: sheet\n---\nschema: [A, B, C]\nrows: 5\ncells:\n  - field: A1\n    data: Item\n  - field: B1\n    data: Qty\n  - field: C1\n    data: Total\n  - field: A2\n    data: Apples\n  - field: B2\n    data: "10"\n  - field: C2\n    data: "=B2*1.5"\n';
    }
    // Schema-less kinds — header only, body stays empty.
    if (isMd)
        return `---\nkind: ${kind}\n---\n`;
    if (isJson)
        return `{\n  "$meta": { "kind": "${kind}" }\n}\n`;
    if (isYaml)
        return `kind: ${kind}\n---\n`;
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
    if (files.length === 1 && !createPath.value.trim()) {
        createPath.value = files[0].name;
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
            if (!createPath.value.trim()) {
                createError.value = t('documents.create.pathRequired');
                return;
            }
            if (!createContent.value) {
                createError.value = t('documents.create.contentRequired');
                return;
            }
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
const kindOptions = computed(() => {
    return [
        { value: '', label: t('documents.allKinds') },
        ...docsState.kinds.value.map((k) => ({ value: k, label: k })),
    ];
});
const breadcrumbs = computed(() => {
    const crumbs = [t('documents.breadcrumbRoot')];
    if (selectedProjectId.value)
        crumbs.push(selectedProjectId.value);
    if (docsState.selected.value)
        crumbs.push(docsState.selected.value.path);
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
/** @type {__VLS_StyleScopedClasses['folder-item']} */ ;
/** @type {__VLS_StyleScopedClasses['content-tab']} */ ;
// CSS variable injection 
// CSS variable injection end 
const __VLS_0 = {}.EditorShell;
/** @type {[typeof __VLS_components.EditorShell, typeof __VLS_components.EditorShell, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    title: (__VLS_ctx.$t('documents.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    wideRightPanel: (!!__VLS_ctx.helpResource),
}));
const __VLS_2 = __VLS_1({
    title: (__VLS_ctx.$t('documents.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    wideRightPanel: (!!__VLS_ctx.helpResource),
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
var __VLS_4 = {};
__VLS_3.slots.default;
{
    const { 'topbar-extra': __VLS_thisSlot } = __VLS_3.slots;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "w-64" },
    });
    const __VLS_5 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_6 = __VLS_asFunctionalComponent(__VLS_5, new __VLS_5({
        modelValue: (__VLS_ctx.selectedProjectId),
        options: (__VLS_ctx.projectOptions),
        placeholder: (__VLS_ctx.$t('documents.selectAProject')),
        disabled: (__VLS_ctx.projectsState.loading.value || __VLS_ctx.projectOptions.length === 0),
    }));
    const __VLS_7 = __VLS_6({
        modelValue: (__VLS_ctx.selectedProjectId),
        options: (__VLS_ctx.projectOptions),
        placeholder: (__VLS_ctx.$t('documents.selectAProject')),
        disabled: (__VLS_ctx.projectsState.loading.value || __VLS_ctx.projectOptions.length === 0),
    }, ...__VLS_functionalComponentArgsRest(__VLS_6));
}
if (__VLS_ctx.selectedProjectId) {
    {
        const { sidebar: __VLS_thisSlot } = __VLS_3.slots;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.nav, __VLS_intrinsicElements.nav)({
            ...{ class: "p-3 flex flex-col gap-1" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({
            ...{ class: "text-xs uppercase opacity-60 mb-2 px-2" },
        });
        (__VLS_ctx.$t('documents.foldersTitle'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!(__VLS_ctx.selectedProjectId))
                        return;
                    __VLS_ctx.selectFolder(null);
                } },
            type: "button",
            ...{ class: "folder-item" },
            ...{ class: ({ 'folder-item--active': __VLS_ctx.selectedFolderKey === '' }) },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.$t('documents.folderAll'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "folder-count" },
        });
        (__VLS_ctx.docsState.totalCount.value);
        for (const [folder] of __VLS_getVForSourceType((__VLS_ctx.topLevelFolders))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!(__VLS_ctx.selectedProjectId))
                            return;
                        __VLS_ctx.selectFolder(folder);
                    } },
                key: (folder),
                type: "button",
                ...{ class: "folder-item" },
                ...{ class: ({ 'folder-item--active': __VLS_ctx.selectedFolderKey === folder }) },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            (folder);
        }
        if (__VLS_ctx.topLevelFolders.length === 0) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
                ...{ class: "text-xs opacity-60 italic mt-2 px-2" },
            });
            (__VLS_ctx.$t('documents.foldersEmptyHint', { example: 'notes/foo.md' }));
        }
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "container mx-auto px-4 py-6 max-w-5xl" },
});
if (__VLS_ctx.projectsState.error.value) {
    const __VLS_9 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_10 = __VLS_asFunctionalComponent(__VLS_9, new __VLS_9({
        variant: "error",
        ...{ class: "mb-4" },
    }));
    const __VLS_11 = __VLS_10({
        variant: "error",
        ...{ class: "mb-4" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_10));
    __VLS_12.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.projectsState.error.value);
    var __VLS_12;
}
if (!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0) {
    const __VLS_13 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_14 = __VLS_asFunctionalComponent(__VLS_13, new __VLS_13({
        headline: (__VLS_ctx.$t('documents.noProjectsHeadline')),
        body: (__VLS_ctx.$t('documents.noProjectsBody')),
    }));
    const __VLS_15 = __VLS_14({
        headline: (__VLS_ctx.$t('documents.noProjectsHeadline')),
        body: (__VLS_ctx.$t('documents.noProjectsBody')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_14));
}
else if (!__VLS_ctx.selectedProjectId) {
    const __VLS_17 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_18 = __VLS_asFunctionalComponent(__VLS_17, new __VLS_17({
        headline: (__VLS_ctx.$t('documents.pickAProjectHeadline')),
        body: (__VLS_ctx.$t('documents.pickAProjectBody')),
    }));
    const __VLS_19 = __VLS_18({
        headline: (__VLS_ctx.$t('documents.pickAProjectHeadline')),
        body: (__VLS_ctx.$t('documents.pickAProjectBody')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_18));
}
else if (__VLS_ctx.docsState.selected.value) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "mb-4" },
    });
    const __VLS_21 = {}.VBackButton;
    /** @type {[typeof __VLS_components.VBackButton, ]} */ ;
    // @ts-ignore
    const __VLS_22 = __VLS_asFunctionalComponent(__VLS_21, new __VLS_21({
        ...{ 'onClick': {} },
        label: (__VLS_ctx.$t('documents.backToList')),
    }));
    const __VLS_23 = __VLS_22({
        ...{ 'onClick': {} },
        label: (__VLS_ctx.$t('documents.backToList')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_22));
    let __VLS_25;
    let __VLS_26;
    let __VLS_27;
    const __VLS_28 = {
        onClick: (__VLS_ctx.backToList)
    };
    var __VLS_24;
    const __VLS_29 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_30 = __VLS_asFunctionalComponent(__VLS_29, new __VLS_29({}));
    const __VLS_31 = __VLS_30({}, ...__VLS_functionalComponentArgsRest(__VLS_30));
    __VLS_32.slots.default;
    {
        const { header: __VLS_thisSlot } = __VLS_32.slots;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "flex items-center gap-2" },
        });
        /** @type {[typeof DocumentIcon, ]} */ ;
        // @ts-ignore
        const __VLS_33 = __VLS_asFunctionalComponent(DocumentIcon, new DocumentIcon({
            path: (__VLS_ctx.docsState.selected.value.path),
            mimeType: (__VLS_ctx.docsState.selected.value.mimeType),
            kind: (__VLS_ctx.docsState.selected.value.kind),
        }));
        const __VLS_34 = __VLS_33({
            path: (__VLS_ctx.docsState.selected.value.path),
            mimeType: (__VLS_ctx.docsState.selected.value.mimeType),
            kind: (__VLS_ctx.docsState.selected.value.kind),
        }, ...__VLS_functionalComponentArgsRest(__VLS_33));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "font-mono text-sm" },
        });
        (__VLS_ctx.docsState.selected.value.path);
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-xs opacity-60 flex flex-wrap gap-3 items-center" },
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
    if (__VLS_ctx.docsState.selected.value.kind) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "badge badge-info badge-sm" },
            title: (__VLS_ctx.$t('documents.detail.kindBadgeTooltip')),
        });
        (__VLS_ctx.$t('documents.detail.kindLabel', { kind: __VLS_ctx.docsState.selected.value.kind }));
    }
    if (__VLS_ctx.headerEntries.length > 0) {
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
    if (!__VLS_ctx.docsState.selected.value.inline) {
        const __VLS_36 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_37 = __VLS_asFunctionalComponent(__VLS_36, new __VLS_36({
            variant: "info",
            ...{ class: "mt-3" },
        }));
        const __VLS_38 = __VLS_37({
            variant: "info",
            ...{ class: "mt-3" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_37));
        __VLS_39.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.$t('documents.detail.readOnlyNote'));
        var __VLS_39;
    }
    if (__VLS_ctx.editError) {
        const __VLS_40 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_41 = __VLS_asFunctionalComponent(__VLS_40, new __VLS_40({
            variant: "error",
            ...{ class: "mt-3" },
        }));
        const __VLS_42 = __VLS_41({
            variant: "error",
            ...{ class: "mt-3" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_41));
        __VLS_43.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.editError);
        var __VLS_43;
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-3 mt-3" },
    });
    const __VLS_44 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_45 = __VLS_asFunctionalComponent(__VLS_44, new __VLS_44({
        modelValue: (__VLS_ctx.editTitle),
        label: (__VLS_ctx.$t('documents.detail.titleLabel')),
        disabled: (__VLS_ctx.saving),
    }));
    const __VLS_46 = __VLS_45({
        modelValue: (__VLS_ctx.editTitle),
        label: (__VLS_ctx.$t('documents.detail.titleLabel')),
        disabled: (__VLS_ctx.saving),
    }, ...__VLS_functionalComponentArgsRest(__VLS_45));
    const __VLS_48 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_49 = __VLS_asFunctionalComponent(__VLS_48, new __VLS_48({
        modelValue: (__VLS_ctx.editPath),
        label: (__VLS_ctx.$t('documents.detail.pathLabel')),
        disabled: (__VLS_ctx.saving),
        help: (__VLS_ctx.$t('documents.detail.pathHelp')),
    }));
    const __VLS_50 = __VLS_49({
        modelValue: (__VLS_ctx.editPath),
        label: (__VLS_ctx.$t('documents.detail.pathLabel')),
        disabled: (__VLS_ctx.saving),
        help: (__VLS_ctx.$t('documents.detail.pathHelp')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_49));
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
                        if (!!(__VLS_ctx.isSheetDocument))
                            return;
                        if (!!(__VLS_ctx.isGraphDocument))
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
                        if (!!(__VLS_ctx.isSheetDocument))
                            return;
                        if (!!(__VLS_ctx.isGraphDocument))
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
                        if (!!(__VLS_ctx.isSheetDocument))
                            return;
                        if (!!(__VLS_ctx.isGraphDocument))
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
                        if (!!(__VLS_ctx.isSheetDocument))
                            return;
                        if (!!(__VLS_ctx.isGraphDocument))
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
                        if (!!(__VLS_ctx.isSheetDocument))
                            return;
                        if (!!(__VLS_ctx.isGraphDocument))
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
                        if (!!(__VLS_ctx.isSheetDocument))
                            return;
                        if (!!(__VLS_ctx.isGraphDocument))
                            return;
                        if (!!(__VLS_ctx.isRecordsDocument))
                            return;
                        if (!!(__VLS_ctx.isMindmapDocument))
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
                        if (!!(__VLS_ctx.isSheetDocument))
                            return;
                        if (!!(__VLS_ctx.isGraphDocument))
                            return;
                        if (!!(__VLS_ctx.isRecordsDocument))
                            return;
                        if (!!(__VLS_ctx.isMindmapDocument))
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
        if (__VLS_ctx.isListDocument && __VLS_ctx.contentTab === 'list') {
            if (__VLS_ctx.parsedList.error) {
                const __VLS_52 = {}.VAlert;
                /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
                // @ts-ignore
                const __VLS_53 = __VLS_asFunctionalComponent(__VLS_52, new __VLS_52({
                    variant: "warning",
                }));
                const __VLS_54 = __VLS_53({
                    variant: "warning",
                }, ...__VLS_functionalComponentArgsRest(__VLS_53));
                __VLS_55.slots.default;
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.$t('documents.detail.listParseError', { message: __VLS_ctx.parsedList.error }));
                var __VLS_55;
            }
            else if (__VLS_ctx.parsedList.doc) {
                /** @type {[typeof ListView, ]} */ ;
                // @ts-ignore
                const __VLS_56 = __VLS_asFunctionalComponent(ListView, new ListView({
                    ...{ 'onUpdate:doc': {} },
                    doc: (__VLS_ctx.parsedList.doc),
                }));
                const __VLS_57 = __VLS_56({
                    ...{ 'onUpdate:doc': {} },
                    doc: (__VLS_ctx.parsedList.doc),
                }, ...__VLS_functionalComponentArgsRest(__VLS_56));
                let __VLS_59;
                let __VLS_60;
                let __VLS_61;
                const __VLS_62 = {
                    'onUpdate:doc': (__VLS_ctx.onListChanged)
                };
                var __VLS_58;
            }
        }
        else if (__VLS_ctx.isSheetDocument && __VLS_ctx.contentTab === 'sheet') {
            if (__VLS_ctx.parsedSheet.error) {
                const __VLS_63 = {}.VAlert;
                /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
                // @ts-ignore
                const __VLS_64 = __VLS_asFunctionalComponent(__VLS_63, new __VLS_63({
                    variant: "warning",
                }));
                const __VLS_65 = __VLS_64({
                    variant: "warning",
                }, ...__VLS_functionalComponentArgsRest(__VLS_64));
                __VLS_66.slots.default;
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.$t('documents.detail.sheetParseError', { message: __VLS_ctx.parsedSheet.error }));
                var __VLS_66;
            }
            else if (__VLS_ctx.parsedSheet.doc) {
                /** @type {[typeof SheetView, ]} */ ;
                // @ts-ignore
                const __VLS_67 = __VLS_asFunctionalComponent(SheetView, new SheetView({
                    ...{ 'onUpdate:doc': {} },
                    doc: (__VLS_ctx.parsedSheet.doc),
                }));
                const __VLS_68 = __VLS_67({
                    ...{ 'onUpdate:doc': {} },
                    doc: (__VLS_ctx.parsedSheet.doc),
                }, ...__VLS_functionalComponentArgsRest(__VLS_67));
                let __VLS_70;
                let __VLS_71;
                let __VLS_72;
                const __VLS_73 = {
                    'onUpdate:doc': (__VLS_ctx.onSheetChanged)
                };
                var __VLS_69;
            }
        }
        else if (__VLS_ctx.isGraphDocument && __VLS_ctx.contentTab === 'graph') {
            if (__VLS_ctx.parsedGraph.error) {
                const __VLS_74 = {}.VAlert;
                /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
                // @ts-ignore
                const __VLS_75 = __VLS_asFunctionalComponent(__VLS_74, new __VLS_74({
                    variant: "warning",
                }));
                const __VLS_76 = __VLS_75({
                    variant: "warning",
                }, ...__VLS_functionalComponentArgsRest(__VLS_75));
                __VLS_77.slots.default;
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.$t('documents.detail.graphParseError', { message: __VLS_ctx.parsedGraph.error }));
                var __VLS_77;
            }
            else if (__VLS_ctx.parsedGraph.doc) {
                /** @type {[typeof GraphView, ]} */ ;
                // @ts-ignore
                const __VLS_78 = __VLS_asFunctionalComponent(GraphView, new GraphView({
                    ...{ 'onUpdate:doc': {} },
                    doc: (__VLS_ctx.parsedGraph.doc),
                }));
                const __VLS_79 = __VLS_78({
                    ...{ 'onUpdate:doc': {} },
                    doc: (__VLS_ctx.parsedGraph.doc),
                }, ...__VLS_functionalComponentArgsRest(__VLS_78));
                let __VLS_81;
                let __VLS_82;
                let __VLS_83;
                const __VLS_84 = {
                    'onUpdate:doc': (__VLS_ctx.onGraphChanged)
                };
                var __VLS_80;
            }
        }
        else if (__VLS_ctx.isRecordsDocument && __VLS_ctx.contentTab === 'records') {
            if (__VLS_ctx.parsedRecords.error) {
                const __VLS_85 = {}.VAlert;
                /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
                // @ts-ignore
                const __VLS_86 = __VLS_asFunctionalComponent(__VLS_85, new __VLS_85({
                    variant: "warning",
                }));
                const __VLS_87 = __VLS_86({
                    variant: "warning",
                }, ...__VLS_functionalComponentArgsRest(__VLS_86));
                __VLS_88.slots.default;
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.$t('documents.detail.recordsParseError', { message: __VLS_ctx.parsedRecords.error }));
                var __VLS_88;
            }
            else if (__VLS_ctx.parsedRecords.doc) {
                /** @type {[typeof RecordsView, ]} */ ;
                // @ts-ignore
                const __VLS_89 = __VLS_asFunctionalComponent(RecordsView, new RecordsView({
                    ...{ 'onUpdate:doc': {} },
                    doc: (__VLS_ctx.parsedRecords.doc),
                }));
                const __VLS_90 = __VLS_89({
                    ...{ 'onUpdate:doc': {} },
                    doc: (__VLS_ctx.parsedRecords.doc),
                }, ...__VLS_functionalComponentArgsRest(__VLS_89));
                let __VLS_92;
                let __VLS_93;
                let __VLS_94;
                const __VLS_95 = {
                    'onUpdate:doc': (__VLS_ctx.onRecordsChanged)
                };
                var __VLS_91;
            }
        }
        else if (__VLS_ctx.isMindmapDocument && __VLS_ctx.contentTab === 'mindmap') {
            if (__VLS_ctx.parsedTree.error) {
                const __VLS_96 = {}.VAlert;
                /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
                // @ts-ignore
                const __VLS_97 = __VLS_asFunctionalComponent(__VLS_96, new __VLS_96({
                    variant: "warning",
                }));
                const __VLS_98 = __VLS_97({
                    variant: "warning",
                }, ...__VLS_functionalComponentArgsRest(__VLS_97));
                __VLS_99.slots.default;
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.$t('documents.detail.mindmapParseError', { message: __VLS_ctx.parsedTree.error }));
                var __VLS_99;
            }
            else if (__VLS_ctx.parsedTree.doc) {
                /** @type {[typeof MindmapView, ]} */ ;
                // @ts-ignore
                const __VLS_100 = __VLS_asFunctionalComponent(MindmapView, new MindmapView({
                    doc: (__VLS_ctx.parsedTree.doc),
                }));
                const __VLS_101 = __VLS_100({
                    doc: (__VLS_ctx.parsedTree.doc),
                }, ...__VLS_functionalComponentArgsRest(__VLS_100));
            }
        }
        else if ((__VLS_ctx.isTreeDocument || __VLS_ctx.isMindmapDocument) && __VLS_ctx.contentTab === 'tree') {
            if (__VLS_ctx.parsedTree.error) {
                const __VLS_103 = {}.VAlert;
                /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
                // @ts-ignore
                const __VLS_104 = __VLS_asFunctionalComponent(__VLS_103, new __VLS_103({
                    variant: "warning",
                }));
                const __VLS_105 = __VLS_104({
                    variant: "warning",
                }, ...__VLS_functionalComponentArgsRest(__VLS_104));
                __VLS_106.slots.default;
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.$t('documents.detail.treeParseError', { message: __VLS_ctx.parsedTree.error }));
                var __VLS_106;
            }
            else if (__VLS_ctx.parsedTree.doc) {
                /** @type {[typeof TreeView, ]} */ ;
                // @ts-ignore
                const __VLS_107 = __VLS_asFunctionalComponent(TreeView, new TreeView({
                    ...{ 'onUpdate:doc': {} },
                    doc: (__VLS_ctx.parsedTree.doc),
                }));
                const __VLS_108 = __VLS_107({
                    ...{ 'onUpdate:doc': {} },
                    doc: (__VLS_ctx.parsedTree.doc),
                }, ...__VLS_functionalComponentArgsRest(__VLS_107));
                let __VLS_110;
                let __VLS_111;
                let __VLS_112;
                const __VLS_113 = {
                    'onUpdate:doc': (__VLS_ctx.onTreeChanged)
                };
                var __VLS_109;
            }
        }
        else {
            const __VLS_114 = {}.CodeEditor;
            /** @type {[typeof __VLS_components.CodeEditor, ]} */ ;
            // @ts-ignore
            const __VLS_115 = __VLS_asFunctionalComponent(__VLS_114, new __VLS_114({
                modelValue: (__VLS_ctx.editInlineText),
                label: (__VLS_ctx.$t('documents.detail.contentLabel')),
                rows: (20),
                disabled: (__VLS_ctx.saving),
                mimeType: (__VLS_ctx.docsState.selected.value.mimeType),
            }));
            const __VLS_116 = __VLS_115({
                modelValue: (__VLS_ctx.editInlineText),
                label: (__VLS_ctx.$t('documents.detail.contentLabel')),
                rows: (20),
                disabled: (__VLS_ctx.saving),
                mimeType: (__VLS_ctx.docsState.selected.value.mimeType),
            }, ...__VLS_functionalComponentArgsRest(__VLS_115));
        }
    }
    else {
        /** @type {[typeof DocumentPreview, ]} */ ;
        // @ts-ignore
        const __VLS_118 = __VLS_asFunctionalComponent(DocumentPreview, new DocumentPreview({
            documentId: (__VLS_ctx.docsState.selected.value.id),
            mimeType: (__VLS_ctx.docsState.selected.value.mimeType),
            inline: (false),
        }));
        const __VLS_119 = __VLS_118({
            documentId: (__VLS_ctx.docsState.selected.value.id),
            mimeType: (__VLS_ctx.docsState.selected.value.mimeType),
            inline: (false),
        }, ...__VLS_functionalComponentArgsRest(__VLS_118));
    }
    {
        const { actions: __VLS_thisSlot } = __VLS_32.slots;
        const __VLS_121 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_122 = __VLS_asFunctionalComponent(__VLS_121, new __VLS_121({
            ...{ 'onClick': {} },
            ...{ class: "mr-auto" },
            variant: "danger",
            disabled: (__VLS_ctx.saving || __VLS_ctx.deleting),
        }));
        const __VLS_123 = __VLS_122({
            ...{ 'onClick': {} },
            ...{ class: "mr-auto" },
            variant: "danger",
            disabled: (__VLS_ctx.saving || __VLS_ctx.deleting),
        }, ...__VLS_functionalComponentArgsRest(__VLS_122));
        let __VLS_125;
        let __VLS_126;
        let __VLS_127;
        const __VLS_128 = {
            onClick: (__VLS_ctx.openDeleteModal)
        };
        __VLS_124.slots.default;
        (__VLS_ctx.isSelectedInTrash
            ? __VLS_ctx.$t('documents.detail.deletePermanent')
            : __VLS_ctx.$t('documents.detail.delete'));
        var __VLS_124;
        const __VLS_129 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_130 = __VLS_asFunctionalComponent(__VLS_129, new __VLS_129({
            variant: "ghost",
            href: (__VLS_ctx.downloadUrl(__VLS_ctx.docsState.selected.value)),
            download: (__VLS_ctx.docsState.selected.value.name || 'document'),
        }));
        const __VLS_131 = __VLS_130({
            variant: "ghost",
            href: (__VLS_ctx.downloadUrl(__VLS_ctx.docsState.selected.value)),
            download: (__VLS_ctx.docsState.selected.value.name || 'document'),
        }, ...__VLS_functionalComponentArgsRest(__VLS_130));
        __VLS_132.slots.default;
        (__VLS_ctx.$t('documents.detail.download'));
        var __VLS_132;
        const __VLS_133 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_134 = __VLS_asFunctionalComponent(__VLS_133, new __VLS_133({
            ...{ 'onClick': {} },
            variant: "ghost",
            disabled: (__VLS_ctx.saving),
        }));
        const __VLS_135 = __VLS_134({
            ...{ 'onClick': {} },
            variant: "ghost",
            disabled: (__VLS_ctx.saving),
        }, ...__VLS_functionalComponentArgsRest(__VLS_134));
        let __VLS_137;
        let __VLS_138;
        let __VLS_139;
        const __VLS_140 = {
            onClick: (__VLS_ctx.backToList)
        };
        __VLS_136.slots.default;
        (__VLS_ctx.$t('documents.detail.cancel'));
        var __VLS_136;
        const __VLS_141 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_142 = __VLS_asFunctionalComponent(__VLS_141, new __VLS_141({
            ...{ 'onClick': {} },
            variant: "secondary",
            loading: (__VLS_ctx.saving),
        }));
        const __VLS_143 = __VLS_142({
            ...{ 'onClick': {} },
            variant: "secondary",
            loading: (__VLS_ctx.saving),
        }, ...__VLS_functionalComponentArgsRest(__VLS_142));
        let __VLS_145;
        let __VLS_146;
        let __VLS_147;
        const __VLS_148 = {
            onClick: (__VLS_ctx.apply)
        };
        __VLS_144.slots.default;
        (__VLS_ctx.$t('documents.detail.apply'));
        var __VLS_144;
        const __VLS_149 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_150 = __VLS_asFunctionalComponent(__VLS_149, new __VLS_149({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.saving),
        }));
        const __VLS_151 = __VLS_150({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.saving),
        }, ...__VLS_functionalComponentArgsRest(__VLS_150));
        let __VLS_153;
        let __VLS_154;
        let __VLS_155;
        const __VLS_156 = {
            onClick: (__VLS_ctx.save)
        };
        __VLS_152.slots.default;
        (__VLS_ctx.$t('documents.detail.save'));
        var __VLS_152;
    }
    var __VLS_32;
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center gap-3 mb-3" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex-1 min-w-0" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
        ...{ onInput: (...[$event]) => {
                if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                    return;
                if (!!(!__VLS_ctx.selectedProjectId))
                    return;
                if (!!(__VLS_ctx.docsState.selected.value))
                    return;
                __VLS_ctx.applyPathFilter(__VLS_ctx.docsState.pathPrefix.value);
            } },
        ...{ onChange: (...[$event]) => {
                if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                    return;
                if (!!(!__VLS_ctx.selectedProjectId))
                    return;
                if (!!(__VLS_ctx.docsState.selected.value))
                    return;
                __VLS_ctx.applyPathFilter(__VLS_ctx.docsState.pathPrefix.value, true);
            } },
        ...{ onKeydown: (...[$event]) => {
                if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                    return;
                if (!!(!__VLS_ctx.selectedProjectId))
                    return;
                if (!!(__VLS_ctx.docsState.selected.value))
                    return;
                __VLS_ctx.applyPathFilter(__VLS_ctx.docsState.pathPrefix.value, true);
            } },
        value: (__VLS_ctx.docsState.pathPrefix.value),
        type: "text",
        placeholder: (__VLS_ctx.$t('documents.pathFilterPlaceholder')),
        list: "folder-list",
        ...{ class: "input input-bordered input-sm w-full" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.datalist, __VLS_intrinsicElements.datalist)({
        id: "folder-list",
    });
    for (const [folder] of __VLS_getVForSourceType((__VLS_ctx.docsState.folders.value))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.option)({
            key: (folder),
            value: (folder),
        });
    }
    if (__VLS_ctx.docsState.kinds.value.length > 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "w-40 shrink-0" },
        });
        const __VLS_157 = {}.VSelect;
        /** @type {[typeof __VLS_components.VSelect, ]} */ ;
        // @ts-ignore
        const __VLS_158 = __VLS_asFunctionalComponent(__VLS_157, new __VLS_157({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.docsState.kindFilter.value),
            options: (__VLS_ctx.kindOptions),
        }));
        const __VLS_159 = __VLS_158({
            ...{ 'onUpdate:modelValue': {} },
            modelValue: (__VLS_ctx.docsState.kindFilter.value),
            options: (__VLS_ctx.kindOptions),
        }, ...__VLS_functionalComponentArgsRest(__VLS_158));
        let __VLS_161;
        let __VLS_162;
        let __VLS_163;
        const __VLS_164 = {
            'onUpdate:modelValue': (...[$event]) => {
                if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                    return;
                if (!!(!__VLS_ctx.selectedProjectId))
                    return;
                if (!!(__VLS_ctx.docsState.selected.value))
                    return;
                if (!(__VLS_ctx.docsState.kinds.value.length > 0))
                    return;
                __VLS_ctx.applyKindFilter($event ?? '');
            }
        };
        var __VLS_160;
    }
    if (__VLS_ctx.docsState.pathPrefix.value || __VLS_ctx.docsState.kindFilter.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                        return;
                    if (!!(!__VLS_ctx.selectedProjectId))
                        return;
                    if (!!(__VLS_ctx.docsState.selected.value))
                        return;
                    if (!(__VLS_ctx.docsState.pathPrefix.value || __VLS_ctx.docsState.kindFilter.value))
                        return;
                    __VLS_ctx.applyPathFilter('', true);
                    __VLS_ctx.applyKindFilter('');
                    ;
                } },
            type: "button",
            ...{ class: "btn btn-ghost btn-sm" },
        });
        (__VLS_ctx.$t('documents.clearFilter'));
    }
    const __VLS_165 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_166 = __VLS_asFunctionalComponent(__VLS_165, new __VLS_165({
        ...{ 'onClick': {} },
        variant: "primary",
        size: "sm",
    }));
    const __VLS_167 = __VLS_166({
        ...{ 'onClick': {} },
        variant: "primary",
        size: "sm",
    }, ...__VLS_functionalComponentArgsRest(__VLS_166));
    let __VLS_169;
    let __VLS_170;
    let __VLS_171;
    const __VLS_172 = {
        onClick: (...[$event]) => {
            if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                return;
            if (!!(!__VLS_ctx.selectedProjectId))
                return;
            if (!!(__VLS_ctx.docsState.selected.value))
                return;
            __VLS_ctx.openCreateModal();
        }
    };
    __VLS_168.slots.default;
    (__VLS_ctx.$t('documents.newDocument'));
    var __VLS_168;
    if (__VLS_ctx.docsState.error.value) {
        const __VLS_173 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_174 = __VLS_asFunctionalComponent(__VLS_173, new __VLS_173({
            variant: "error",
            ...{ class: "mb-4" },
        }));
        const __VLS_175 = __VLS_174({
            variant: "error",
            ...{ class: "mb-4" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_174));
        __VLS_176.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.docsState.error.value);
        var __VLS_176;
    }
    if (!__VLS_ctx.docsState.loading.value && __VLS_ctx.docsState.items.value.length === 0) {
        const __VLS_177 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_178 = __VLS_asFunctionalComponent(__VLS_177, new __VLS_177({
            headline: (__VLS_ctx.$t('documents.noDocumentsHeadline')),
            body: (__VLS_ctx.$t('documents.noDocumentsBody')),
        }));
        const __VLS_179 = __VLS_178({
            headline: (__VLS_ctx.$t('documents.noDocumentsHeadline')),
            body: (__VLS_ctx.$t('documents.noDocumentsBody')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_178));
        __VLS_180.slots.default;
        {
            const { action: __VLS_thisSlot } = __VLS_180.slots;
            const __VLS_181 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_182 = __VLS_asFunctionalComponent(__VLS_181, new __VLS_181({
                ...{ 'onClick': {} },
                variant: "primary",
            }));
            const __VLS_183 = __VLS_182({
                ...{ 'onClick': {} },
                variant: "primary",
            }, ...__VLS_functionalComponentArgsRest(__VLS_182));
            let __VLS_185;
            let __VLS_186;
            let __VLS_187;
            const __VLS_188 = {
                onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                        return;
                    if (!!(!__VLS_ctx.selectedProjectId))
                        return;
                    if (!!(__VLS_ctx.docsState.selected.value))
                        return;
                    if (!(!__VLS_ctx.docsState.loading.value && __VLS_ctx.docsState.items.value.length === 0))
                        return;
                    __VLS_ctx.openCreateModal();
                }
            };
            __VLS_184.slots.default;
            (__VLS_ctx.$t('documents.createFirstDocument'));
            var __VLS_184;
        }
        var __VLS_180;
    }
    else {
        const __VLS_189 = {}.VDataList;
        /** @type {[typeof __VLS_components.VDataList, typeof __VLS_components.VDataList, ]} */ ;
        // @ts-ignore
        const __VLS_190 = __VLS_asFunctionalComponent(__VLS_189, new __VLS_189({
            ...{ 'onSelect': {} },
            items: (__VLS_ctx.docsState.items.value),
            selectable: true,
        }));
        const __VLS_191 = __VLS_190({
            ...{ 'onSelect': {} },
            items: (__VLS_ctx.docsState.items.value),
            selectable: true,
        }, ...__VLS_functionalComponentArgsRest(__VLS_190));
        let __VLS_193;
        let __VLS_194;
        let __VLS_195;
        const __VLS_196 = {
            onSelect: (__VLS_ctx.openDocument)
        };
        __VLS_192.slots.default;
        {
            const { default: __VLS_thisSlot } = __VLS_192.slots;
            const [{ item }] = __VLS_getSlotParams(__VLS_thisSlot);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex items-center gap-3" },
            });
            /** @type {[typeof DocumentIcon, ]} */ ;
            // @ts-ignore
            const __VLS_197 = __VLS_asFunctionalComponent(DocumentIcon, new DocumentIcon({
                path: (item.path),
                mimeType: (item.mimeType),
                kind: (item.kind),
            }));
            const __VLS_198 = __VLS_197({
                path: (item.path),
                mimeType: (item.mimeType),
                kind: (item.kind),
            }, ...__VLS_functionalComponentArgsRest(__VLS_197));
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
            (item.path);
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
        }
        var __VLS_192;
    }
    if (__VLS_ctx.docsState.totalCount.value > 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "mt-4" },
        });
        const __VLS_200 = {}.VPagination;
        /** @type {[typeof __VLS_components.VPagination, ]} */ ;
        // @ts-ignore
        const __VLS_201 = __VLS_asFunctionalComponent(__VLS_200, new __VLS_200({
            ...{ 'onUpdate:page': {} },
            page: (__VLS_ctx.docsState.page.value),
            pageSize: (__VLS_ctx.docsState.pageSize.value),
            totalCount: (__VLS_ctx.docsState.totalCount.value),
        }));
        const __VLS_202 = __VLS_201({
            ...{ 'onUpdate:page': {} },
            page: (__VLS_ctx.docsState.page.value),
            pageSize: (__VLS_ctx.docsState.pageSize.value),
            totalCount: (__VLS_ctx.docsState.totalCount.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_201));
        let __VLS_204;
        let __VLS_205;
        let __VLS_206;
        const __VLS_207 = {
            'onUpdate:page': (__VLS_ctx.changePage)
        };
        var __VLS_203;
    }
}
const __VLS_208 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_209 = __VLS_asFunctionalComponent(__VLS_208, new __VLS_208({
    modelValue: (__VLS_ctx.showDeleteModal),
    title: (__VLS_ctx.isSelectedInTrash
        ? __VLS_ctx.$t('documents.delete.titlePermanent')
        : __VLS_ctx.$t('documents.delete.title')),
    closeOnBackdrop: (!__VLS_ctx.deleting),
}));
const __VLS_210 = __VLS_209({
    modelValue: (__VLS_ctx.showDeleteModal),
    title: (__VLS_ctx.isSelectedInTrash
        ? __VLS_ctx.$t('documents.delete.titlePermanent')
        : __VLS_ctx.$t('documents.delete.title')),
    closeOnBackdrop: (!__VLS_ctx.deleting),
}, ...__VLS_functionalComponentArgsRest(__VLS_209));
__VLS_211.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({});
(__VLS_ctx.isSelectedInTrash
    ? __VLS_ctx.$t('documents.delete.bodyPermanent', { path: __VLS_ctx.docsState.selected.value?.path ?? '' })
    : __VLS_ctx.$t('documents.delete.body', {
        path: __VLS_ctx.docsState.selected.value?.path ?? '',
        bin: __VLS_ctx.TRASH_PREFIX,
    }));
{
    const { actions: __VLS_thisSlot } = __VLS_211.slots;
    const __VLS_212 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_213 = __VLS_asFunctionalComponent(__VLS_212, new __VLS_212({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.deleting),
    }));
    const __VLS_214 = __VLS_213({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.deleting),
    }, ...__VLS_functionalComponentArgsRest(__VLS_213));
    let __VLS_216;
    let __VLS_217;
    let __VLS_218;
    const __VLS_219 = {
        onClick: (...[$event]) => {
            __VLS_ctx.showDeleteModal = false;
        }
    };
    __VLS_215.slots.default;
    (__VLS_ctx.$t('documents.delete.cancel'));
    var __VLS_215;
    const __VLS_220 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_221 = __VLS_asFunctionalComponent(__VLS_220, new __VLS_220({
        ...{ 'onClick': {} },
        variant: "danger",
        loading: (__VLS_ctx.deleting),
    }));
    const __VLS_222 = __VLS_221({
        ...{ 'onClick': {} },
        variant: "danger",
        loading: (__VLS_ctx.deleting),
    }, ...__VLS_functionalComponentArgsRest(__VLS_221));
    let __VLS_224;
    let __VLS_225;
    let __VLS_226;
    const __VLS_227 = {
        onClick: (__VLS_ctx.confirmDelete)
    };
    __VLS_223.slots.default;
    (__VLS_ctx.isSelectedInTrash
        ? __VLS_ctx.$t('documents.delete.confirmPermanent')
        : __VLS_ctx.$t('documents.delete.confirm'));
    var __VLS_223;
}
var __VLS_211;
const __VLS_228 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_229 = __VLS_asFunctionalComponent(__VLS_228, new __VLS_228({
    modelValue: (__VLS_ctx.showCreateModal),
    title: (__VLS_ctx.$t('documents.create.newDocument')),
    closeOnBackdrop: (false),
}));
const __VLS_230 = __VLS_229({
    modelValue: (__VLS_ctx.showCreateModal),
    title: (__VLS_ctx.$t('documents.create.newDocument')),
    closeOnBackdrop: (false),
}, ...__VLS_functionalComponentArgsRest(__VLS_229));
__VLS_231.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex gap-2 mb-4" },
});
const __VLS_232 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_233 = __VLS_asFunctionalComponent(__VLS_232, new __VLS_232({
    ...{ 'onClick': {} },
    variant: (__VLS_ctx.createMode === 'inline' ? 'primary' : 'ghost'),
    size: "sm",
    disabled: (__VLS_ctx.creating),
}));
const __VLS_234 = __VLS_233({
    ...{ 'onClick': {} },
    variant: (__VLS_ctx.createMode === 'inline' ? 'primary' : 'ghost'),
    size: "sm",
    disabled: (__VLS_ctx.creating),
}, ...__VLS_functionalComponentArgsRest(__VLS_233));
let __VLS_236;
let __VLS_237;
let __VLS_238;
const __VLS_239 = {
    onClick: (...[$event]) => {
        __VLS_ctx.setCreateMode('inline');
    }
};
__VLS_235.slots.default;
(__VLS_ctx.$t('documents.create.typeContent'));
var __VLS_235;
const __VLS_240 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_241 = __VLS_asFunctionalComponent(__VLS_240, new __VLS_240({
    ...{ 'onClick': {} },
    variant: (__VLS_ctx.createMode === 'upload' ? 'primary' : 'ghost'),
    size: "sm",
    disabled: (__VLS_ctx.creating),
}));
const __VLS_242 = __VLS_241({
    ...{ 'onClick': {} },
    variant: (__VLS_ctx.createMode === 'upload' ? 'primary' : 'ghost'),
    size: "sm",
    disabled: (__VLS_ctx.creating),
}, ...__VLS_functionalComponentArgsRest(__VLS_241));
let __VLS_244;
let __VLS_245;
let __VLS_246;
const __VLS_247 = {
    onClick: (...[$event]) => {
        __VLS_ctx.setCreateMode('upload');
    }
};
__VLS_243.slots.default;
(__VLS_ctx.$t('documents.create.uploadFile'));
var __VLS_243;
__VLS_asFunctionalElement(__VLS_intrinsicElements.form, __VLS_intrinsicElements.form)({
    ...{ onSubmit: (__VLS_ctx.submitCreate) },
    ...{ class: "flex flex-col gap-3" },
});
if (__VLS_ctx.createError) {
    const __VLS_248 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_249 = __VLS_asFunctionalComponent(__VLS_248, new __VLS_248({
        variant: "error",
    }));
    const __VLS_250 = __VLS_249({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_249));
    __VLS_251.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.createError);
    var __VLS_251;
}
if (__VLS_ctx.createMode === 'inline') {
    const __VLS_252 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_253 = __VLS_asFunctionalComponent(__VLS_252, new __VLS_252({
        modelValue: (__VLS_ctx.createPath),
        label: (__VLS_ctx.$t('documents.create.pathLabel')),
        placeholder: (__VLS_ctx.$t('documents.create.pathPlaceholder')),
        required: true,
        disabled: (__VLS_ctx.creating),
        help: (__VLS_ctx.$t('documents.create.pathHelp')),
    }));
    const __VLS_254 = __VLS_253({
        modelValue: (__VLS_ctx.createPath),
        label: (__VLS_ctx.$t('documents.create.pathLabel')),
        placeholder: (__VLS_ctx.$t('documents.create.pathPlaceholder')),
        required: true,
        disabled: (__VLS_ctx.creating),
        help: (__VLS_ctx.$t('documents.create.pathHelp')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_253));
    const __VLS_256 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_257 = __VLS_asFunctionalComponent(__VLS_256, new __VLS_256({
        modelValue: (__VLS_ctx.createTitle),
        label: (__VLS_ctx.$t('documents.create.titleLabel')),
        placeholder: (__VLS_ctx.$t('documents.create.titlePlaceholder')),
        disabled: (__VLS_ctx.creating),
    }));
    const __VLS_258 = __VLS_257({
        modelValue: (__VLS_ctx.createTitle),
        label: (__VLS_ctx.$t('documents.create.titleLabel')),
        placeholder: (__VLS_ctx.$t('documents.create.titlePlaceholder')),
        disabled: (__VLS_ctx.creating),
    }, ...__VLS_functionalComponentArgsRest(__VLS_257));
    const __VLS_260 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_261 = __VLS_asFunctionalComponent(__VLS_260, new __VLS_260({
        modelValue: (__VLS_ctx.createTagsRaw),
        label: (__VLS_ctx.$t('documents.create.tagsLabel')),
        placeholder: (__VLS_ctx.$t('documents.create.tagsPlaceholder')),
        disabled: (__VLS_ctx.creating),
        help: (__VLS_ctx.$t('documents.create.tagsHelp')),
    }));
    const __VLS_262 = __VLS_261({
        modelValue: (__VLS_ctx.createTagsRaw),
        label: (__VLS_ctx.$t('documents.create.tagsLabel')),
        placeholder: (__VLS_ctx.$t('documents.create.tagsPlaceholder')),
        disabled: (__VLS_ctx.creating),
        help: (__VLS_ctx.$t('documents.create.tagsHelp')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_261));
    const __VLS_264 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_265 = __VLS_asFunctionalComponent(__VLS_264, new __VLS_264({
        modelValue: (__VLS_ctx.createMime),
        options: (__VLS_ctx.createMimeOptions),
        label: (__VLS_ctx.$t('documents.create.typeLabel')),
        disabled: (__VLS_ctx.creating),
    }));
    const __VLS_266 = __VLS_265({
        modelValue: (__VLS_ctx.createMime),
        options: (__VLS_ctx.createMimeOptions),
        label: (__VLS_ctx.$t('documents.create.typeLabel')),
        disabled: (__VLS_ctx.creating),
    }, ...__VLS_functionalComponentArgsRest(__VLS_265));
    const __VLS_268 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_269 = __VLS_asFunctionalComponent(__VLS_268, new __VLS_268({
        modelValue: (__VLS_ctx.createKind),
        options: (__VLS_ctx.kindCreateOptions),
        label: (__VLS_ctx.$t('documents.create.kindLabel')),
        help: (__VLS_ctx.$t('documents.create.kindHelp')),
        disabled: (__VLS_ctx.creating),
    }));
    const __VLS_270 = __VLS_269({
        modelValue: (__VLS_ctx.createKind),
        options: (__VLS_ctx.kindCreateOptions),
        label: (__VLS_ctx.$t('documents.create.kindLabel')),
        help: (__VLS_ctx.$t('documents.create.kindHelp')),
        disabled: (__VLS_ctx.creating),
    }, ...__VLS_functionalComponentArgsRest(__VLS_269));
    const __VLS_272 = {}.CodeEditor;
    /** @type {[typeof __VLS_components.CodeEditor, ]} */ ;
    // @ts-ignore
    const __VLS_273 = __VLS_asFunctionalComponent(__VLS_272, new __VLS_272({
        modelValue: (__VLS_ctx.createContent),
        label: (__VLS_ctx.$t('documents.create.contentLabel')),
        rows: (14),
        disabled: (__VLS_ctx.creating),
        mimeType: (__VLS_ctx.createMime),
    }));
    const __VLS_274 = __VLS_273({
        modelValue: (__VLS_ctx.createContent),
        label: (__VLS_ctx.$t('documents.create.contentLabel')),
        rows: (14),
        disabled: (__VLS_ctx.creating),
        mimeType: (__VLS_ctx.createMime),
    }, ...__VLS_functionalComponentArgsRest(__VLS_273));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "text-xs opacity-70 -mt-1" },
    });
    (__VLS_ctx.$t('documents.create.inlineSizeNote'));
}
else {
    const __VLS_276 = {}.VFileInput;
    /** @type {[typeof __VLS_components.VFileInput, ]} */ ;
    // @ts-ignore
    const __VLS_277 = __VLS_asFunctionalComponent(__VLS_276, new __VLS_276({
        modelValue: (__VLS_ctx.createFiles),
        label: (__VLS_ctx.$t('documents.create.filesLabel')),
        multiple: true,
        disabled: (__VLS_ctx.creating),
        help: (__VLS_ctx.$t('documents.create.filesHelp')),
    }));
    const __VLS_278 = __VLS_277({
        modelValue: (__VLS_ctx.createFiles),
        label: (__VLS_ctx.$t('documents.create.filesLabel')),
        multiple: true,
        disabled: (__VLS_ctx.creating),
        help: (__VLS_ctx.$t('documents.create.filesHelp')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_277));
    if (__VLS_ctx.createFiles.length <= 1) {
        const __VLS_280 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_281 = __VLS_asFunctionalComponent(__VLS_280, new __VLS_280({
            modelValue: (__VLS_ctx.createPath),
            label: (__VLS_ctx.$t('documents.create.pathLabel')),
            placeholder: (__VLS_ctx.$t('documents.create.pathPlaceholderUpload')),
            disabled: (__VLS_ctx.creating),
            help: (__VLS_ctx.$t('documents.create.pathHelpUpload')),
        }));
        const __VLS_282 = __VLS_281({
            modelValue: (__VLS_ctx.createPath),
            label: (__VLS_ctx.$t('documents.create.pathLabel')),
            placeholder: (__VLS_ctx.$t('documents.create.pathPlaceholderUpload')),
            disabled: (__VLS_ctx.creating),
            help: (__VLS_ctx.$t('documents.create.pathHelpUpload')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_281));
        const __VLS_284 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_285 = __VLS_asFunctionalComponent(__VLS_284, new __VLS_284({
            modelValue: (__VLS_ctx.createTitle),
            label: (__VLS_ctx.$t('documents.create.titleLabel')),
            placeholder: (__VLS_ctx.$t('documents.create.titlePlaceholder')),
            disabled: (__VLS_ctx.creating),
        }));
        const __VLS_286 = __VLS_285({
            modelValue: (__VLS_ctx.createTitle),
            label: (__VLS_ctx.$t('documents.create.titleLabel')),
            placeholder: (__VLS_ctx.$t('documents.create.titlePlaceholder')),
            disabled: (__VLS_ctx.creating),
        }, ...__VLS_functionalComponentArgsRest(__VLS_285));
    }
    const __VLS_288 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_289 = __VLS_asFunctionalComponent(__VLS_288, new __VLS_288({
        modelValue: (__VLS_ctx.createTagsRaw),
        label: (__VLS_ctx.$t('documents.create.tagsLabel')),
        placeholder: (__VLS_ctx.$t('documents.create.tagsPlaceholder')),
        disabled: (__VLS_ctx.creating),
        help: (__VLS_ctx.createFiles.length > 1
            ? __VLS_ctx.$t('documents.create.tagsHelpMulti')
            : __VLS_ctx.$t('documents.create.tagsHelp')),
    }));
    const __VLS_290 = __VLS_289({
        modelValue: (__VLS_ctx.createTagsRaw),
        label: (__VLS_ctx.$t('documents.create.tagsLabel')),
        placeholder: (__VLS_ctx.$t('documents.create.tagsPlaceholder')),
        disabled: (__VLS_ctx.creating),
        help: (__VLS_ctx.createFiles.length > 1
            ? __VLS_ctx.$t('documents.create.tagsHelpMulti')
            : __VLS_ctx.$t('documents.create.tagsHelp')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_289));
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
    const { actions: __VLS_thisSlot } = __VLS_231.slots;
    const __VLS_292 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_293 = __VLS_asFunctionalComponent(__VLS_292, new __VLS_292({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.creating),
    }));
    const __VLS_294 = __VLS_293({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.creating),
    }, ...__VLS_functionalComponentArgsRest(__VLS_293));
    let __VLS_296;
    let __VLS_297;
    let __VLS_298;
    const __VLS_299 = {
        onClick: (...[$event]) => {
            __VLS_ctx.showCreateModal = false;
        }
    };
    __VLS_295.slots.default;
    (__VLS_ctx.$t('documents.create.cancel'));
    var __VLS_295;
    const __VLS_300 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_301 = __VLS_asFunctionalComponent(__VLS_300, new __VLS_300({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.creating),
    }));
    const __VLS_302 = __VLS_301({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.creating),
    }, ...__VLS_functionalComponentArgsRest(__VLS_301));
    let __VLS_304;
    let __VLS_305;
    let __VLS_306;
    const __VLS_307 = {
        onClick: (__VLS_ctx.submitCreate)
    };
    __VLS_303.slots.default;
    (__VLS_ctx.createMode === 'upload'
        ? __VLS_ctx.$t('documents.create.submitUpload')
        : __VLS_ctx.$t('documents.create.submitCreate'));
    var __VLS_303;
}
var __VLS_231;
if (__VLS_ctx.helpResource) {
    {
        const { 'right-panel': __VLS_thisSlot } = __VLS_3.slots;
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
            const __VLS_308 = {}.MarkdownView;
            /** @type {[typeof __VLS_components.MarkdownView, ]} */ ;
            // @ts-ignore
            const __VLS_309 = __VLS_asFunctionalComponent(__VLS_308, new __VLS_308({
                source: (__VLS_ctx.help.content.value),
            }));
            const __VLS_310 = __VLS_309({
                source: (__VLS_ctx.help.content.value),
            }, ...__VLS_functionalComponentArgsRest(__VLS_309));
        }
    }
}
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['w-64']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['folder-item']} */ ;
/** @type {__VLS_StyleScopedClasses['folder-item--active']} */ ;
/** @type {__VLS_StyleScopedClasses['folder-count']} */ ;
/** @type {__VLS_StyleScopedClasses['folder-item']} */ ;
/** @type {__VLS_StyleScopedClasses['folder-item--active']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['italic']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['container']} */ ;
/** @type {__VLS_StyleScopedClasses['mx-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['py-6']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-5xl']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-4']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['badge']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-info']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-sm']} */ ;
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
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
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
/** @type {__VLS_StyleScopedClasses['mr-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['input']} */ ;
/** @type {__VLS_StyleScopedClasses['input-bordered']} */ ;
/** @type {__VLS_StyleScopedClasses['input-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['w-40']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-ghost']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-4']} */ ;
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
/** @type {__VLS_StyleScopedClasses['mt-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['-mt-1']} */ ;
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
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            EditorShell: EditorShell,
            VAlert: VAlert,
            VBackButton: VBackButton,
            VButton: VButton,
            VCard: VCard,
            VDataList: VDataList,
            VEmptyState: VEmptyState,
            VFileInput: VFileInput,
            VInput: VInput,
            VModal: VModal,
            VPagination: VPagination,
            VSelect: VSelect,
            CodeEditor: CodeEditor,
            MarkdownView: MarkdownView,
            DocumentPreview: DocumentPreview,
            DocumentIcon: DocumentIcon,
            ListView: ListView,
            TreeView: TreeView,
            MindmapView: MindmapView,
            RecordsView: RecordsView,
            GraphView: GraphView,
            SheetView: SheetView,
            projectsState: projectsState,
            docsState: docsState,
            selectedProjectId: selectedProjectId,
            editTitle: editTitle,
            editPath: editPath,
            editInlineText: editInlineText,
            editError: editError,
            saving: saving,
            showCreateModal: showCreateModal,
            showDeleteModal: showDeleteModal,
            deleting: deleting,
            createMode: createMode,
            createPath: createPath,
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
            applyPathFilter: applyPathFilter,
            applyKindFilter: applyKindFilter,
            projectOptions: projectOptions,
            changePage: changePage,
            topLevelFolders: topLevelFolders,
            selectedFolderKey: selectedFolderKey,
            selectFolder: selectFolder,
            openDocument: openDocument,
            contentTab: contentTab,
            isListDocument: isListDocument,
            isTreeDocument: isTreeDocument,
            isMindmapDocument: isMindmapDocument,
            isRecordsDocument: isRecordsDocument,
            isGraphDocument: isGraphDocument,
            isSheetDocument: isSheetDocument,
            TRASH_PREFIX: TRASH_PREFIX,
            isSelectedInTrash: isSelectedInTrash,
            parsedList: parsedList,
            parsedTree: parsedTree,
            parsedRecords: parsedRecords,
            parsedGraph: parsedGraph,
            parsedSheet: parsedSheet,
            onListChanged: onListChanged,
            onTreeChanged: onTreeChanged,
            onRecordsChanged: onRecordsChanged,
            onGraphChanged: onGraphChanged,
            onSheetChanged: onSheetChanged,
            help: help,
            helpResource: helpResource,
            backToList: backToList,
            downloadUrl: downloadUrl,
            openCreateModal: openCreateModal,
            kindCreateOptions: kindCreateOptions,
            setCreateMode: setCreateMode,
            submitCreate: submitCreate,
            apply: apply,
            save: save,
            openDeleteModal: openDeleteModal,
            confirmDelete: confirmDelete,
            headerEntries: headerEntries,
            kindOptions: kindOptions,
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