import { computed, onMounted, ref, watch } from 'vue';
import { EditorShell, VAlert, VBackButton, VButton, VCard, VDataList, VEmptyState, VFileInput, VInput, VModal, VPagination, VSelect, CodeEditor, } from '@/components';
import { useDocuments } from '@/composables/useDocuments';
import { useTenantProjects } from '@/composables/useTenantProjects';
import { consumeDocumentDraft, documentContentUrl } from '@vance/shared';
import DocumentPreview from './DocumentPreview.vue';
const PAGE_SIZE = 20;
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
const createFiles = ref([]);
const createError = ref(null);
const creating = ref(false);
const uploadProgress = ref([]);
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
    }
    else if (projectsState.projects.value.length > 0) {
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
    if (!next)
        return;
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
            : 'Ungrouped';
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
}
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
    createFiles.value = [];
    createError.value = null;
    uploadProgress.value = [];
    showCreateModal.value = true;
}
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
                createError.value = 'Path is required.';
                return;
            }
            if (!createContent.value) {
                createError.value = 'Content is required.';
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
            createError.value = 'Pick at least one file.';
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
                uploadProgress.value[i].message = docsState.error.value ?? 'Upload failed.';
            }
        }
        if (okCount === files.length) {
            // All good — close modal and refresh the list.
            showCreateModal.value = false;
        }
        else {
            createError.value = `${files.length - okCount} of ${files.length} files failed. See list below.`;
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
const breadcrumbs = computed(() => {
    const crumbs = ['Documents'];
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
const __VLS_0 = {}.EditorShell;
/** @type {[typeof __VLS_components.EditorShell, typeof __VLS_components.EditorShell, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    title: "Documents",
    breadcrumbs: (__VLS_ctx.breadcrumbs),
}));
const __VLS_2 = __VLS_1({
    title: "Documents",
    breadcrumbs: (__VLS_ctx.breadcrumbs),
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
        placeholder: "Select a project",
        disabled: (__VLS_ctx.projectsState.loading.value || __VLS_ctx.projectOptions.length === 0),
    }));
    const __VLS_7 = __VLS_6({
        modelValue: (__VLS_ctx.selectedProjectId),
        options: (__VLS_ctx.projectOptions),
        placeholder: "Select a project",
        disabled: (__VLS_ctx.projectsState.loading.value || __VLS_ctx.projectOptions.length === 0),
    }, ...__VLS_functionalComponentArgsRest(__VLS_6));
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
        headline: "No projects in this tenant",
        body: "Ask an administrator to create a project before you can browse documents.",
    }));
    const __VLS_15 = __VLS_14({
        headline: "No projects in this tenant",
        body: "Ask an administrator to create a project before you can browse documents.",
    }, ...__VLS_functionalComponentArgsRest(__VLS_14));
}
else if (!__VLS_ctx.selectedProjectId) {
    const __VLS_17 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_18 = __VLS_asFunctionalComponent(__VLS_17, new __VLS_17({
        headline: "Pick a project",
        body: "Choose a project from the dropdown above to load its documents.",
    }));
    const __VLS_19 = __VLS_18({
        headline: "Pick a project",
        body: "Choose a project from the dropdown above to load its documents.",
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
        label: "Back to list",
    }));
    const __VLS_23 = __VLS_22({
        ...{ 'onClick': {} },
        label: "Back to list",
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
            ...{ class: "font-mono text-sm" },
        });
        (__VLS_ctx.docsState.selected.value.path);
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-xs opacity-60 flex flex-wrap gap-3" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.formatBytes(__VLS_ctx.docsState.selected.value.size));
    if (__VLS_ctx.docsState.selected.value.mimeType) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.docsState.selected.value.mimeType);
    }
    if (__VLS_ctx.docsState.selected.value.createdBy) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.docsState.selected.value.createdBy);
    }
    if (!__VLS_ctx.docsState.selected.value.inline) {
        const __VLS_33 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_34 = __VLS_asFunctionalComponent(__VLS_33, new __VLS_33({
            variant: "info",
            ...{ class: "mt-3" },
        }));
        const __VLS_35 = __VLS_34({
            variant: "info",
            ...{ class: "mt-3" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_34));
        __VLS_36.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        var __VLS_36;
    }
    if (__VLS_ctx.editError) {
        const __VLS_37 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_38 = __VLS_asFunctionalComponent(__VLS_37, new __VLS_37({
            variant: "error",
            ...{ class: "mt-3" },
        }));
        const __VLS_39 = __VLS_38({
            variant: "error",
            ...{ class: "mt-3" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_38));
        __VLS_40.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.editError);
        var __VLS_40;
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-3 mt-3" },
    });
    const __VLS_41 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_42 = __VLS_asFunctionalComponent(__VLS_41, new __VLS_41({
        modelValue: (__VLS_ctx.editTitle),
        label: "Title",
        disabled: (__VLS_ctx.saving),
    }));
    const __VLS_43 = __VLS_42({
        modelValue: (__VLS_ctx.editTitle),
        label: "Title",
        disabled: (__VLS_ctx.saving),
    }, ...__VLS_functionalComponentArgsRest(__VLS_42));
    const __VLS_45 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_46 = __VLS_asFunctionalComponent(__VLS_45, new __VLS_45({
        modelValue: (__VLS_ctx.editPath),
        label: "Path",
        disabled: (__VLS_ctx.saving),
        help: "Move or rename this document. Path is unique within the project; conflicts are rejected.",
    }));
    const __VLS_47 = __VLS_46({
        modelValue: (__VLS_ctx.editPath),
        label: "Path",
        disabled: (__VLS_ctx.saving),
        help: "Move or rename this document. Path is unique within the project; conflicts are rejected.",
    }, ...__VLS_functionalComponentArgsRest(__VLS_46));
    if (__VLS_ctx.docsState.selected.value.inline) {
        const __VLS_49 = {}.CodeEditor;
        /** @type {[typeof __VLS_components.CodeEditor, ]} */ ;
        // @ts-ignore
        const __VLS_50 = __VLS_asFunctionalComponent(__VLS_49, new __VLS_49({
            modelValue: (__VLS_ctx.editInlineText),
            label: "Content",
            rows: (20),
            disabled: (__VLS_ctx.saving),
            mimeType: (__VLS_ctx.docsState.selected.value.mimeType),
        }));
        const __VLS_51 = __VLS_50({
            modelValue: (__VLS_ctx.editInlineText),
            label: "Content",
            rows: (20),
            disabled: (__VLS_ctx.saving),
            mimeType: (__VLS_ctx.docsState.selected.value.mimeType),
        }, ...__VLS_functionalComponentArgsRest(__VLS_50));
    }
    else {
        /** @type {[typeof DocumentPreview, ]} */ ;
        // @ts-ignore
        const __VLS_53 = __VLS_asFunctionalComponent(DocumentPreview, new DocumentPreview({
            documentId: (__VLS_ctx.docsState.selected.value.id),
            mimeType: (__VLS_ctx.docsState.selected.value.mimeType),
            inline: (false),
        }));
        const __VLS_54 = __VLS_53({
            documentId: (__VLS_ctx.docsState.selected.value.id),
            mimeType: (__VLS_ctx.docsState.selected.value.mimeType),
            inline: (false),
        }, ...__VLS_functionalComponentArgsRest(__VLS_53));
    }
    {
        const { actions: __VLS_thisSlot } = __VLS_32.slots;
        const __VLS_56 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_57 = __VLS_asFunctionalComponent(__VLS_56, new __VLS_56({
            ...{ 'onClick': {} },
            ...{ class: "mr-auto" },
            variant: "danger",
            disabled: (__VLS_ctx.saving || __VLS_ctx.deleting),
        }));
        const __VLS_58 = __VLS_57({
            ...{ 'onClick': {} },
            ...{ class: "mr-auto" },
            variant: "danger",
            disabled: (__VLS_ctx.saving || __VLS_ctx.deleting),
        }, ...__VLS_functionalComponentArgsRest(__VLS_57));
        let __VLS_60;
        let __VLS_61;
        let __VLS_62;
        const __VLS_63 = {
            onClick: (__VLS_ctx.openDeleteModal)
        };
        __VLS_59.slots.default;
        var __VLS_59;
        const __VLS_64 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_65 = __VLS_asFunctionalComponent(__VLS_64, new __VLS_64({
            variant: "ghost",
            href: (__VLS_ctx.downloadUrl(__VLS_ctx.docsState.selected.value)),
            download: (__VLS_ctx.docsState.selected.value.name || 'document'),
        }));
        const __VLS_66 = __VLS_65({
            variant: "ghost",
            href: (__VLS_ctx.downloadUrl(__VLS_ctx.docsState.selected.value)),
            download: (__VLS_ctx.docsState.selected.value.name || 'document'),
        }, ...__VLS_functionalComponentArgsRest(__VLS_65));
        __VLS_67.slots.default;
        var __VLS_67;
        const __VLS_68 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_69 = __VLS_asFunctionalComponent(__VLS_68, new __VLS_68({
            ...{ 'onClick': {} },
            variant: "ghost",
            disabled: (__VLS_ctx.saving),
        }));
        const __VLS_70 = __VLS_69({
            ...{ 'onClick': {} },
            variant: "ghost",
            disabled: (__VLS_ctx.saving),
        }, ...__VLS_functionalComponentArgsRest(__VLS_69));
        let __VLS_72;
        let __VLS_73;
        let __VLS_74;
        const __VLS_75 = {
            onClick: (__VLS_ctx.backToList)
        };
        __VLS_71.slots.default;
        var __VLS_71;
        const __VLS_76 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_77 = __VLS_asFunctionalComponent(__VLS_76, new __VLS_76({
            ...{ 'onClick': {} },
            variant: "secondary",
            loading: (__VLS_ctx.saving),
        }));
        const __VLS_78 = __VLS_77({
            ...{ 'onClick': {} },
            variant: "secondary",
            loading: (__VLS_ctx.saving),
        }, ...__VLS_functionalComponentArgsRest(__VLS_77));
        let __VLS_80;
        let __VLS_81;
        let __VLS_82;
        const __VLS_83 = {
            onClick: (__VLS_ctx.apply)
        };
        __VLS_79.slots.default;
        var __VLS_79;
        const __VLS_84 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_85 = __VLS_asFunctionalComponent(__VLS_84, new __VLS_84({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.saving),
        }));
        const __VLS_86 = __VLS_85({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.saving),
        }, ...__VLS_functionalComponentArgsRest(__VLS_85));
        let __VLS_88;
        let __VLS_89;
        let __VLS_90;
        const __VLS_91 = {
            onClick: (__VLS_ctx.save)
        };
        __VLS_87.slots.default;
        var __VLS_87;
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
        placeholder: "Filter by folder or path prefix… (e.g. notes/, archive/2026)",
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
    if (__VLS_ctx.docsState.pathPrefix.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.projectsState.loading.value && __VLS_ctx.projectOptions.length === 0))
                        return;
                    if (!!(!__VLS_ctx.selectedProjectId))
                        return;
                    if (!!(__VLS_ctx.docsState.selected.value))
                        return;
                    if (!(__VLS_ctx.docsState.pathPrefix.value))
                        return;
                    __VLS_ctx.applyPathFilter('', true);
                } },
            type: "button",
            ...{ class: "btn btn-ghost btn-sm" },
        });
    }
    const __VLS_92 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_93 = __VLS_asFunctionalComponent(__VLS_92, new __VLS_92({
        ...{ 'onClick': {} },
        variant: "primary",
        size: "sm",
    }));
    const __VLS_94 = __VLS_93({
        ...{ 'onClick': {} },
        variant: "primary",
        size: "sm",
    }, ...__VLS_functionalComponentArgsRest(__VLS_93));
    let __VLS_96;
    let __VLS_97;
    let __VLS_98;
    const __VLS_99 = {
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
    __VLS_95.slots.default;
    var __VLS_95;
    if (__VLS_ctx.docsState.error.value) {
        const __VLS_100 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_101 = __VLS_asFunctionalComponent(__VLS_100, new __VLS_100({
            variant: "error",
            ...{ class: "mb-4" },
        }));
        const __VLS_102 = __VLS_101({
            variant: "error",
            ...{ class: "mb-4" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_101));
        __VLS_103.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.docsState.error.value);
        var __VLS_103;
    }
    if (!__VLS_ctx.docsState.loading.value && __VLS_ctx.docsState.items.value.length === 0) {
        const __VLS_104 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_105 = __VLS_asFunctionalComponent(__VLS_104, new __VLS_104({
            headline: "No documents",
            body: "This project has no documents yet.",
        }));
        const __VLS_106 = __VLS_105({
            headline: "No documents",
            body: "This project has no documents yet.",
        }, ...__VLS_functionalComponentArgsRest(__VLS_105));
        __VLS_107.slots.default;
        {
            const { action: __VLS_thisSlot } = __VLS_107.slots;
            const __VLS_108 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_109 = __VLS_asFunctionalComponent(__VLS_108, new __VLS_108({
                ...{ 'onClick': {} },
                variant: "primary",
            }));
            const __VLS_110 = __VLS_109({
                ...{ 'onClick': {} },
                variant: "primary",
            }, ...__VLS_functionalComponentArgsRest(__VLS_109));
            let __VLS_112;
            let __VLS_113;
            let __VLS_114;
            const __VLS_115 = {
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
            __VLS_111.slots.default;
            var __VLS_111;
        }
        var __VLS_107;
    }
    else {
        const __VLS_116 = {}.VDataList;
        /** @type {[typeof __VLS_components.VDataList, typeof __VLS_components.VDataList, ]} */ ;
        // @ts-ignore
        const __VLS_117 = __VLS_asFunctionalComponent(__VLS_116, new __VLS_116({
            ...{ 'onSelect': {} },
            items: (__VLS_ctx.docsState.items.value),
            selectable: true,
        }));
        const __VLS_118 = __VLS_117({
            ...{ 'onSelect': {} },
            items: (__VLS_ctx.docsState.items.value),
            selectable: true,
        }, ...__VLS_functionalComponentArgsRest(__VLS_117));
        let __VLS_120;
        let __VLS_121;
        let __VLS_122;
        const __VLS_123 = {
            onSelect: (__VLS_ctx.openDocument)
        };
        __VLS_119.slots.default;
        {
            const { default: __VLS_thisSlot } = __VLS_119.slots;
            const [{ item }] = __VLS_getSlotParams(__VLS_thisSlot);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex items-center justify-between gap-4" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "min-w-0 flex-1" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "font-semibold truncate" },
            });
            (item.title?.trim() || item.name);
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
            }
        }
        var __VLS_119;
    }
    if (__VLS_ctx.docsState.totalCount.value > 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "mt-4" },
        });
        const __VLS_124 = {}.VPagination;
        /** @type {[typeof __VLS_components.VPagination, ]} */ ;
        // @ts-ignore
        const __VLS_125 = __VLS_asFunctionalComponent(__VLS_124, new __VLS_124({
            ...{ 'onUpdate:page': {} },
            page: (__VLS_ctx.docsState.page.value),
            pageSize: (__VLS_ctx.docsState.pageSize.value),
            totalCount: (__VLS_ctx.docsState.totalCount.value),
        }));
        const __VLS_126 = __VLS_125({
            ...{ 'onUpdate:page': {} },
            page: (__VLS_ctx.docsState.page.value),
            pageSize: (__VLS_ctx.docsState.pageSize.value),
            totalCount: (__VLS_ctx.docsState.totalCount.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_125));
        let __VLS_128;
        let __VLS_129;
        let __VLS_130;
        const __VLS_131 = {
            'onUpdate:page': (__VLS_ctx.changePage)
        };
        var __VLS_127;
    }
}
const __VLS_132 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_133 = __VLS_asFunctionalComponent(__VLS_132, new __VLS_132({
    modelValue: (__VLS_ctx.showDeleteModal),
    title: "Delete document",
    closeOnBackdrop: (!__VLS_ctx.deleting),
}));
const __VLS_134 = __VLS_133({
    modelValue: (__VLS_ctx.showDeleteModal),
    title: "Delete document",
    closeOnBackdrop: (!__VLS_ctx.deleting),
}, ...__VLS_functionalComponentArgsRest(__VLS_133));
__VLS_135.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "font-mono" },
});
(__VLS_ctx.docsState.selected.value?.path);
{
    const { actions: __VLS_thisSlot } = __VLS_135.slots;
    const __VLS_136 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_137 = __VLS_asFunctionalComponent(__VLS_136, new __VLS_136({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.deleting),
    }));
    const __VLS_138 = __VLS_137({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.deleting),
    }, ...__VLS_functionalComponentArgsRest(__VLS_137));
    let __VLS_140;
    let __VLS_141;
    let __VLS_142;
    const __VLS_143 = {
        onClick: (...[$event]) => {
            __VLS_ctx.showDeleteModal = false;
        }
    };
    __VLS_139.slots.default;
    var __VLS_139;
    const __VLS_144 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_145 = __VLS_asFunctionalComponent(__VLS_144, new __VLS_144({
        ...{ 'onClick': {} },
        variant: "danger",
        loading: (__VLS_ctx.deleting),
    }));
    const __VLS_146 = __VLS_145({
        ...{ 'onClick': {} },
        variant: "danger",
        loading: (__VLS_ctx.deleting),
    }, ...__VLS_functionalComponentArgsRest(__VLS_145));
    let __VLS_148;
    let __VLS_149;
    let __VLS_150;
    const __VLS_151 = {
        onClick: (__VLS_ctx.confirmDelete)
    };
    __VLS_147.slots.default;
    var __VLS_147;
}
var __VLS_135;
const __VLS_152 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_153 = __VLS_asFunctionalComponent(__VLS_152, new __VLS_152({
    modelValue: (__VLS_ctx.showCreateModal),
    title: "New document",
    closeOnBackdrop: (false),
}));
const __VLS_154 = __VLS_153({
    modelValue: (__VLS_ctx.showCreateModal),
    title: "New document",
    closeOnBackdrop: (false),
}, ...__VLS_functionalComponentArgsRest(__VLS_153));
__VLS_155.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex gap-2 mb-4" },
});
const __VLS_156 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_157 = __VLS_asFunctionalComponent(__VLS_156, new __VLS_156({
    ...{ 'onClick': {} },
    variant: (__VLS_ctx.createMode === 'inline' ? 'primary' : 'ghost'),
    size: "sm",
    disabled: (__VLS_ctx.creating),
}));
const __VLS_158 = __VLS_157({
    ...{ 'onClick': {} },
    variant: (__VLS_ctx.createMode === 'inline' ? 'primary' : 'ghost'),
    size: "sm",
    disabled: (__VLS_ctx.creating),
}, ...__VLS_functionalComponentArgsRest(__VLS_157));
let __VLS_160;
let __VLS_161;
let __VLS_162;
const __VLS_163 = {
    onClick: (...[$event]) => {
        __VLS_ctx.setCreateMode('inline');
    }
};
__VLS_159.slots.default;
var __VLS_159;
const __VLS_164 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_165 = __VLS_asFunctionalComponent(__VLS_164, new __VLS_164({
    ...{ 'onClick': {} },
    variant: (__VLS_ctx.createMode === 'upload' ? 'primary' : 'ghost'),
    size: "sm",
    disabled: (__VLS_ctx.creating),
}));
const __VLS_166 = __VLS_165({
    ...{ 'onClick': {} },
    variant: (__VLS_ctx.createMode === 'upload' ? 'primary' : 'ghost'),
    size: "sm",
    disabled: (__VLS_ctx.creating),
}, ...__VLS_functionalComponentArgsRest(__VLS_165));
let __VLS_168;
let __VLS_169;
let __VLS_170;
const __VLS_171 = {
    onClick: (...[$event]) => {
        __VLS_ctx.setCreateMode('upload');
    }
};
__VLS_167.slots.default;
var __VLS_167;
__VLS_asFunctionalElement(__VLS_intrinsicElements.form, __VLS_intrinsicElements.form)({
    ...{ onSubmit: (__VLS_ctx.submitCreate) },
    ...{ class: "flex flex-col gap-3" },
});
if (__VLS_ctx.createError) {
    const __VLS_172 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_173 = __VLS_asFunctionalComponent(__VLS_172, new __VLS_172({
        variant: "error",
    }));
    const __VLS_174 = __VLS_173({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_173));
    __VLS_175.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.createError);
    var __VLS_175;
}
if (__VLS_ctx.createMode === 'inline') {
    const __VLS_176 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_177 = __VLS_asFunctionalComponent(__VLS_176, new __VLS_176({
        modelValue: (__VLS_ctx.createPath),
        label: "Path",
        placeholder: "notes/example.md",
        required: true,
        disabled: (__VLS_ctx.creating),
        help: "Virtual path inside the project. Must be unique.",
    }));
    const __VLS_178 = __VLS_177({
        modelValue: (__VLS_ctx.createPath),
        label: "Path",
        placeholder: "notes/example.md",
        required: true,
        disabled: (__VLS_ctx.creating),
        help: "Virtual path inside the project. Must be unique.",
    }, ...__VLS_functionalComponentArgsRest(__VLS_177));
    const __VLS_180 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_181 = __VLS_asFunctionalComponent(__VLS_180, new __VLS_180({
        modelValue: (__VLS_ctx.createTitle),
        label: "Title",
        placeholder: "Optional display title",
        disabled: (__VLS_ctx.creating),
    }));
    const __VLS_182 = __VLS_181({
        modelValue: (__VLS_ctx.createTitle),
        label: "Title",
        placeholder: "Optional display title",
        disabled: (__VLS_ctx.creating),
    }, ...__VLS_functionalComponentArgsRest(__VLS_181));
    const __VLS_184 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_185 = __VLS_asFunctionalComponent(__VLS_184, new __VLS_184({
        modelValue: (__VLS_ctx.createTagsRaw),
        label: "Tags",
        placeholder: "comma, separated, tags",
        disabled: (__VLS_ctx.creating),
        help: "Optional, separated by commas.",
    }));
    const __VLS_186 = __VLS_185({
        modelValue: (__VLS_ctx.createTagsRaw),
        label: "Tags",
        placeholder: "comma, separated, tags",
        disabled: (__VLS_ctx.creating),
        help: "Optional, separated by commas.",
    }, ...__VLS_functionalComponentArgsRest(__VLS_185));
    const __VLS_188 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_189 = __VLS_asFunctionalComponent(__VLS_188, new __VLS_188({
        modelValue: (__VLS_ctx.createMime),
        options: (__VLS_ctx.createMimeOptions),
        label: "Type",
        disabled: (__VLS_ctx.creating),
    }));
    const __VLS_190 = __VLS_189({
        modelValue: (__VLS_ctx.createMime),
        options: (__VLS_ctx.createMimeOptions),
        label: "Type",
        disabled: (__VLS_ctx.creating),
    }, ...__VLS_functionalComponentArgsRest(__VLS_189));
    const __VLS_192 = {}.CodeEditor;
    /** @type {[typeof __VLS_components.CodeEditor, ]} */ ;
    // @ts-ignore
    const __VLS_193 = __VLS_asFunctionalComponent(__VLS_192, new __VLS_192({
        modelValue: (__VLS_ctx.createContent),
        label: "Content",
        rows: (14),
        disabled: (__VLS_ctx.creating),
        mimeType: (__VLS_ctx.createMime),
    }));
    const __VLS_194 = __VLS_193({
        modelValue: (__VLS_ctx.createContent),
        label: "Content",
        rows: (14),
        disabled: (__VLS_ctx.creating),
        mimeType: (__VLS_ctx.createMime),
    }, ...__VLS_functionalComponentArgsRest(__VLS_193));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
        ...{ class: "text-xs opacity-70 -mt-1" },
    });
}
else {
    const __VLS_196 = {}.VFileInput;
    /** @type {[typeof __VLS_components.VFileInput, ]} */ ;
    // @ts-ignore
    const __VLS_197 = __VLS_asFunctionalComponent(__VLS_196, new __VLS_196({
        modelValue: (__VLS_ctx.createFiles),
        label: "Files",
        multiple: true,
        disabled: (__VLS_ctx.creating),
        help: "Drop one or more files. Server picks inline vs. storage automatically per file.",
    }));
    const __VLS_198 = __VLS_197({
        modelValue: (__VLS_ctx.createFiles),
        label: "Files",
        multiple: true,
        disabled: (__VLS_ctx.creating),
        help: "Drop one or more files. Server picks inline vs. storage automatically per file.",
    }, ...__VLS_functionalComponentArgsRest(__VLS_197));
    if (__VLS_ctx.createFiles.length <= 1) {
        const __VLS_200 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_201 = __VLS_asFunctionalComponent(__VLS_200, new __VLS_200({
            modelValue: (__VLS_ctx.createPath),
            label: "Path",
            placeholder: "(defaults to file name)",
            disabled: (__VLS_ctx.creating),
            help: "Override the destination path inside the project. Optional.",
        }));
        const __VLS_202 = __VLS_201({
            modelValue: (__VLS_ctx.createPath),
            label: "Path",
            placeholder: "(defaults to file name)",
            disabled: (__VLS_ctx.creating),
            help: "Override the destination path inside the project. Optional.",
        }, ...__VLS_functionalComponentArgsRest(__VLS_201));
        const __VLS_204 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_205 = __VLS_asFunctionalComponent(__VLS_204, new __VLS_204({
            modelValue: (__VLS_ctx.createTitle),
            label: "Title",
            placeholder: "Optional display title",
            disabled: (__VLS_ctx.creating),
        }));
        const __VLS_206 = __VLS_205({
            modelValue: (__VLS_ctx.createTitle),
            label: "Title",
            placeholder: "Optional display title",
            disabled: (__VLS_ctx.creating),
        }, ...__VLS_functionalComponentArgsRest(__VLS_205));
    }
    const __VLS_208 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_209 = __VLS_asFunctionalComponent(__VLS_208, new __VLS_208({
        modelValue: (__VLS_ctx.createTagsRaw),
        label: "Tags",
        placeholder: "comma, separated, tags",
        disabled: (__VLS_ctx.creating),
        help: (__VLS_ctx.createFiles.length > 1
            ? 'Applied to every uploaded file.'
            : 'Optional, separated by commas.'),
    }));
    const __VLS_210 = __VLS_209({
        modelValue: (__VLS_ctx.createTagsRaw),
        label: "Tags",
        placeholder: "comma, separated, tags",
        disabled: (__VLS_ctx.creating),
        help: (__VLS_ctx.createFiles.length > 1
            ? 'Applied to every uploaded file.'
            : 'Optional, separated by commas.'),
    }, ...__VLS_functionalComponentArgsRest(__VLS_209));
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
    const { actions: __VLS_thisSlot } = __VLS_155.slots;
    const __VLS_212 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_213 = __VLS_asFunctionalComponent(__VLS_212, new __VLS_212({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.creating),
    }));
    const __VLS_214 = __VLS_213({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.creating),
    }, ...__VLS_functionalComponentArgsRest(__VLS_213));
    let __VLS_216;
    let __VLS_217;
    let __VLS_218;
    const __VLS_219 = {
        onClick: (...[$event]) => {
            __VLS_ctx.showCreateModal = false;
        }
    };
    __VLS_215.slots.default;
    var __VLS_215;
    const __VLS_220 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_221 = __VLS_asFunctionalComponent(__VLS_220, new __VLS_220({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.creating),
    }));
    const __VLS_222 = __VLS_221({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.creating),
    }, ...__VLS_functionalComponentArgsRest(__VLS_221));
    let __VLS_224;
    let __VLS_225;
    let __VLS_226;
    const __VLS_227 = {
        onClick: (__VLS_ctx.submitCreate)
    };
    __VLS_223.slots.default;
    (__VLS_ctx.createMode === 'upload' ? 'Upload' : 'Create');
    var __VLS_223;
}
var __VLS_155;
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['w-64']} */ ;
/** @type {__VLS_StyleScopedClasses['container']} */ ;
/** @type {__VLS_StyleScopedClasses['mx-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['px-4']} */ ;
/** @type {__VLS_StyleScopedClasses['py-6']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-5xl']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-4']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-4']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
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
/** @type {__VLS_StyleScopedClasses['btn']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-ghost']} */ ;
/** @type {__VLS_StyleScopedClasses['btn-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
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
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
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
            DocumentPreview: DocumentPreview,
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
            createFiles: createFiles,
            createError: createError,
            creating: creating,
            uploadProgress: uploadProgress,
            createMimeOptions: createMimeOptions,
            applyPathFilter: applyPathFilter,
            projectOptions: projectOptions,
            changePage: changePage,
            openDocument: openDocument,
            backToList: backToList,
            downloadUrl: downloadUrl,
            openCreateModal: openCreateModal,
            setCreateMode: setCreateMode,
            submitCreate: submitCreate,
            apply: apply,
            save: save,
            openDeleteModal: openDeleteModal,
            confirmDelete: confirmDelete,
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