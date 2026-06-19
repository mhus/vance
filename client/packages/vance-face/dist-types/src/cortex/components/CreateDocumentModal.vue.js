import { computed, ref, watch } from 'vue';
import { CodeEditor, VAlert, VButton, VFileInput, VInput, VModal, VSelect, } from '@/components';
import { useI18n } from 'vue-i18n';
import { consumeDocumentDraft } from '@/platform/documentDraft';
const props = withDefaults(defineProps(), {
    initialPath: '',
    consumeDraft: false,
});
const emit = defineEmits();
const { t } = useI18n();
const createMode = ref('inline');
const createPath = ref('');
const createName = ref('');
const createTitle = ref('');
const createTagsRaw = ref('');
const createMime = ref('text/markdown');
const createContent = ref('');
const createKind = ref('');
const createFiles = ref([]);
const createError = ref(null);
const creating = ref(false);
let lastGeneratedStub = '';
// Reset the form whenever the modal transitions to open. Each open
// starts at the prefilled path with an empty name/body — re-using the
// previous attempt's state would leak data across unrelated creates.
const draftSource = ref(null);
watch(() => props.open, (open) => {
    if (!open)
        return;
    const path = (props.initialPath ?? '').replace(/^\/+|\/+$/g, '');
    createPath.value = path ? `${path}/` : '';
    createMode.value = 'inline';
    createName.value = '';
    createTitle.value = '';
    createTagsRaw.value = '';
    createMime.value = 'text/markdown';
    createContent.value = '';
    createKind.value = '';
    createFiles.value = [];
    createError.value = null;
    creating.value = false;
    lastGeneratedStub = '';
    draftSource.value = null;
    if (props.consumeDraft) {
        const draft = consumeDocumentDraft();
        if (draft) {
            if (draft.title)
                createTitle.value = draft.title;
            if (draft.mimeType)
                createMime.value = draft.mimeType;
            if (draft.content) {
                createContent.value = draft.content;
            }
            if (draft.path) {
                // Split the suggested path into directory + filename so
                // both fields show the user where the file will land.
                const idx = draft.path.lastIndexOf('/');
                if (idx >= 0) {
                    createPath.value = `${draft.path.slice(0, idx)}/`;
                    createName.value = draft.path.slice(idx + 1);
                }
                else {
                    createName.value = draft.path;
                }
            }
            draftSource.value = draft.source ?? null;
        }
    }
}, { immediate: true });
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
const EXTENSION_TO_MIME = {
    md: 'text/markdown',
    markdown: 'text/markdown',
    txt: 'text/plain',
    json: 'application/json',
    yaml: 'application/yaml',
    yml: 'application/yaml',
    xml: 'application/xml',
    js: 'application/javascript',
    mjs: 'application/javascript',
    cjs: 'application/javascript',
    ts: 'application/typescript',
    py: 'text/x-python',
    sh: 'application/x-sh',
    bash: 'application/x-sh',
    r: 'text/x-r',
    java: 'text/x-java-source',
    sql: 'application/sql',
    html: 'text/html',
    htm: 'text/html',
    css: 'text/css',
};
function mimeForFilename(name) {
    const dot = name.lastIndexOf('.');
    if (dot < 0 || dot === name.length - 1)
        return null;
    const ext = name.substring(dot + 1).toLowerCase();
    return EXTENSION_TO_MIME[ext] ?? null;
}
const KIND_ALLOWED_MIMES = new Set([
    'text/markdown',
    'text/x-markdown',
    'application/json',
    'application/yaml',
    'application/x-yaml',
    'text/yaml',
]);
const kindAllowed = computed(() => KIND_ALLOWED_MIMES.has(createMime.value));
const KIND_CREATE_OPTIONS = [
    'list', 'checklist', 'tree', 'text', 'mindmap', 'graph', 'chart', 'sheet',
    'slides', 'diagram', 'calendar', 'application', 'data', 'records', 'schema',
];
const kindCreateOptions = computed(() => [
    { value: '', label: t('documents.create.kindNone') },
    ...KIND_CREATE_OPTIONS.map((k) => ({ value: k, label: k })),
]);
/**
 * Generate a starter body for the chosen kind in the chosen mime.
 * Same templates as DocumentApp's previous {@code buildKindStub}.
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
        if (isJson)
            return '{\n  "$meta": { "kind": "graph" },\n  "graph": { "directed": true },\n  "nodes": [\n    { "id": "alice", "label": "Alice" },\n    { "id": "bob", "label": "Bob" }\n  ],\n  "edges": [\n    { "source": "alice", "target": "bob" }\n  ]\n}\n';
        if (isYaml)
            return '$meta:\n  kind: graph\ngraph:\n  directed: true\nnodes:\n  - id: alice\n    label: Alice\n  - id: bob\n    label: Bob\nedges:\n  - source: alice\n    target: bob\n';
    }
    if (kind === 'chart') {
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
        if (isMd)
            return '---\nkind: diagram\n---\n\n```mermaid\nflowchart TD\n  A[Start] --> B{Decision}\n  B -->|yes| C[Do it]\n  B -->|no| D[Skip]\n```\n';
        if (isJson)
            return '{\n  "$meta": { "kind": "diagram" },\n  "source": "flowchart TD\\n  A[Start] --> B{Decision}\\n  B -->|yes| C[Do it]\\n  B -->|no| D[Skip]\\n"\n}\n';
        if (isYaml)
            return '$meta:\n  kind: diagram\nsource: |\n  flowchart TD\n    A[Start] --> B{Decision}\n    B -->|yes| C[Do it]\n    B -->|no| D[Skip]\n';
    }
    if (kind === 'calendar') {
        if (isJson)
            return '{\n  "$meta": { "kind": "calendar" },\n  "events": [\n    {\n      "id": "ev-1",\n      "title": "Sprint Planning",\n      "start": "2026-06-12T09:00",\n      "end": "2026-06-12T11:00",\n      "location": "Büro"\n    },\n    {\n      "id": "ev-2",\n      "title": "Urlaub",\n      "start": "2026-07-15",\n      "end": "2026-07-28",\n      "allDay": true,\n      "tags": ["private"]\n    }\n  ]\n}\n';
        if (isYaml)
            return '$meta:\n  kind: calendar\nevents:\n  - id: ev-1\n    title: Sprint Planning\n    start: "2026-06-12T09:00"\n    end: "2026-06-12T11:00"\n    location: Büro\n  - id: ev-2\n    title: Urlaub\n    start: "2026-07-15"\n    end: "2026-07-28"\n    allDay: true\n    tags: [private]\n';
    }
    if (kind === 'application') {
        if (isJson)
            return '{\n  "$meta": { "kind": "application", "app": "calendar" },\n  "title": "My Calendar App",\n  "description": "Planning suite — one calendar per lane.",\n  "calendar": {\n    "window": { "from": "2026-06-01", "until": "2026-09-30" },\n    "lanes": {\n      "design":  { "title": "Design",  "color": "blue",   "order": 1 },\n      "backend": { "title": "Backend", "color": "green",  "order": 2 }\n    },\n    "gantt":     { "outputPath": "_gantt.md", "includeRecurring": false },\n    "conflicts": { "outputPath": "_conflicts.yaml", "ignoreWithinTags": ["private"] }\n  }\n}\n';
        if (isYaml)
            return '$meta:\n  kind: application\n  app: calendar\ntitle: My Calendar App\ndescription: Planning suite — one calendar per lane.\ncalendar:\n  window:\n    from: "2026-06-01"\n    until: "2026-09-30"\n  lanes:\n    design:  { title: Design,  color: blue,  order: 1 }\n    backend: { title: Backend, color: green, order: 2 }\n  gantt:\n    outputPath: _gantt.md\n    includeRecurring: false\n  conflicts:\n    outputPath: _conflicts.yaml\n    ignoreWithinTags: [private]\n';
    }
    if (kind === 'sheet') {
        if (isJson)
            return '{\n  "$meta": { "kind": "sheet" },\n  "schema": ["A", "B", "C"],\n  "rows": 5,\n  "cells": [\n    { "field": "A1", "data": "Item" },\n    { "field": "B1", "data": "Qty" },\n    { "field": "C1", "data": "Total" },\n    { "field": "A2", "data": "Apples" },\n    { "field": "B2", "data": "10" },\n    { "field": "C2", "data": "=B2*1.5" }\n  ]\n}\n';
        if (isYaml)
            return '$meta:\n  kind: sheet\nschema: [A, B, C]\nrows: 5\ncells:\n  - field: A1\n    data: Item\n  - field: B1\n    data: Qty\n  - field: C1\n    data: Total\n  - field: A2\n    data: Apples\n  - field: B2\n    data: "10"\n  - field: C2\n    data: "=B2*1.5"\n';
    }
    if (isMd)
        return `---\nkind: ${kind}\n---\n`;
    if (isJson)
        return `{\n  "$meta": { "kind": "${kind}" }\n}\n`;
    if (isYaml)
        return `$meta:\n  kind: ${kind}\n`;
    return '';
}
// Auto-fill body when kind / mime changes — only when the editor still
// holds the last auto-generated stub or is empty.
watch([createKind, createMime], ([kind, mime]) => {
    if (!props.open)
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
// Filename drives mime detection.
watch(createName, (name) => {
    if (!props.open)
        return;
    if (createMode.value !== 'inline')
        return;
    const detected = mimeForFilename(name);
    if (detected && detected !== createMime.value) {
        createMime.value = detected;
    }
});
// Kind only applies to md/json/yaml — clear when mime moves out.
watch(createMime, () => {
    if (!kindAllowed.value && createKind.value !== '') {
        createKind.value = '';
    }
});
function setCreateMode(mode) {
    createMode.value = mode;
    createError.value = null;
}
function close() {
    emit('update:open', false);
}
async function submit() {
    if (!props.projectId)
        return;
    creating.value = true;
    createError.value = null;
    try {
        const tags = createTagsRaw.value
            .split(',')
            .map((t) => t.trim())
            .filter((t) => t.length > 0);
        const tagList = tags.length > 0 ? tags : undefined;
        const titleStr = createTitle.value.trim() || undefined;
        if (createMode.value === 'inline') {
            const name = createName.value.trim();
            if (!name) {
                createError.value = t('documents.create.nameRequired');
                return;
            }
            const fullPath = (createPath.value + name).trim().replace(/^\/+/, '');
            const result = {
                kind: 'inline',
                fullPath,
                title: titleStr,
                tags: tagList,
                mimeType: createMime.value,
                inlineText: createContent.value,
            };
            await emit('confirm', result);
        }
        else {
            const files = createFiles.value;
            if (files.length === 0) {
                createError.value = t('documents.create.pickAtLeastOneFile');
                return;
            }
            const targetFolder = createPath.value.replace(/^\/+|\/+$/g, '');
            const result = {
                kind: 'upload',
                files,
                targetFolder,
                title: titleStr,
                tags: tagList,
            };
            await emit('confirm', result);
        }
    }
    catch (e) {
        createError.value = e instanceof Error ? e.message : 'Create failed';
    }
    finally {
        creating.value = false;
    }
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    initialPath: '',
    consumeDraft: false,
});
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
const __VLS_0 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.open),
    title: (__VLS_ctx.$t('documents.create.newDocument')),
    closeOnBackdrop: (false),
}));
const __VLS_2 = __VLS_1({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.open),
    title: (__VLS_ctx.$t('documents.create.newDocument')),
    closeOnBackdrop: (false),
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
let __VLS_4;
let __VLS_5;
let __VLS_6;
const __VLS_7 = {
    'onUpdate:modelValue': ((v) => __VLS_ctx.emit('update:open', v))
};
var __VLS_8 = {};
__VLS_3.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex gap-2 mb-4" },
});
const __VLS_9 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_10 = __VLS_asFunctionalComponent(__VLS_9, new __VLS_9({
    ...{ 'onClick': {} },
    variant: (__VLS_ctx.createMode === 'inline' ? 'primary' : 'ghost'),
    size: "sm",
    disabled: (__VLS_ctx.creating),
}));
const __VLS_11 = __VLS_10({
    ...{ 'onClick': {} },
    variant: (__VLS_ctx.createMode === 'inline' ? 'primary' : 'ghost'),
    size: "sm",
    disabled: (__VLS_ctx.creating),
}, ...__VLS_functionalComponentArgsRest(__VLS_10));
let __VLS_13;
let __VLS_14;
let __VLS_15;
const __VLS_16 = {
    onClick: (...[$event]) => {
        __VLS_ctx.setCreateMode('inline');
    }
};
__VLS_12.slots.default;
(__VLS_ctx.$t('documents.create.typeContent'));
var __VLS_12;
const __VLS_17 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_18 = __VLS_asFunctionalComponent(__VLS_17, new __VLS_17({
    ...{ 'onClick': {} },
    variant: (__VLS_ctx.createMode === 'upload' ? 'primary' : 'ghost'),
    size: "sm",
    disabled: (__VLS_ctx.creating),
}));
const __VLS_19 = __VLS_18({
    ...{ 'onClick': {} },
    variant: (__VLS_ctx.createMode === 'upload' ? 'primary' : 'ghost'),
    size: "sm",
    disabled: (__VLS_ctx.creating),
}, ...__VLS_functionalComponentArgsRest(__VLS_18));
let __VLS_21;
let __VLS_22;
let __VLS_23;
const __VLS_24 = {
    onClick: (...[$event]) => {
        __VLS_ctx.setCreateMode('upload');
    }
};
__VLS_20.slots.default;
(__VLS_ctx.$t('documents.create.uploadFile'));
var __VLS_20;
__VLS_asFunctionalElement(__VLS_intrinsicElements.form, __VLS_intrinsicElements.form)({
    ...{ onSubmit: (__VLS_ctx.submit) },
    ...{ class: "flex flex-col gap-3" },
});
if (__VLS_ctx.createError) {
    const __VLS_25 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_26 = __VLS_asFunctionalComponent(__VLS_25, new __VLS_25({
        variant: "error",
    }));
    const __VLS_27 = __VLS_26({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_26));
    __VLS_28.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.createError);
    var __VLS_28;
}
if (__VLS_ctx.draftSource) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-xs italic opacity-70 bg-base-200 rounded px-2 py-1" },
    });
    (__VLS_ctx.draftSource);
}
if (__VLS_ctx.createMode === 'inline') {
    const __VLS_29 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_30 = __VLS_asFunctionalComponent(__VLS_29, new __VLS_29({
        modelValue: (__VLS_ctx.createPath),
        label: (__VLS_ctx.$t('documents.create.pathLabel')),
        placeholder: "documents/",
        disabled: (__VLS_ctx.creating),
    }));
    const __VLS_31 = __VLS_30({
        modelValue: (__VLS_ctx.createPath),
        label: (__VLS_ctx.$t('documents.create.pathLabel')),
        placeholder: "documents/",
        disabled: (__VLS_ctx.creating),
    }, ...__VLS_functionalComponentArgsRest(__VLS_30));
    const __VLS_33 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_34 = __VLS_asFunctionalComponent(__VLS_33, new __VLS_33({
        ...{ 'onKeydown': {} },
        modelValue: (__VLS_ctx.createName),
        label: (__VLS_ctx.$t('documents.create.nameLabel')),
        placeholder: (__VLS_ctx.$t('documents.create.namePlaceholder')),
        required: true,
        disabled: (__VLS_ctx.creating),
    }));
    const __VLS_35 = __VLS_34({
        ...{ 'onKeydown': {} },
        modelValue: (__VLS_ctx.createName),
        label: (__VLS_ctx.$t('documents.create.nameLabel')),
        placeholder: (__VLS_ctx.$t('documents.create.namePlaceholder')),
        required: true,
        disabled: (__VLS_ctx.creating),
    }, ...__VLS_functionalComponentArgsRest(__VLS_34));
    let __VLS_37;
    let __VLS_38;
    let __VLS_39;
    const __VLS_40 = {
        onKeydown: (__VLS_ctx.submit)
    };
    var __VLS_36;
    const __VLS_41 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_42 = __VLS_asFunctionalComponent(__VLS_41, new __VLS_41({
        modelValue: (__VLS_ctx.createTitle),
        label: (__VLS_ctx.$t('documents.create.titleLabel')),
        placeholder: (__VLS_ctx.$t('documents.create.titlePlaceholder')),
        disabled: (__VLS_ctx.creating),
    }));
    const __VLS_43 = __VLS_42({
        modelValue: (__VLS_ctx.createTitle),
        label: (__VLS_ctx.$t('documents.create.titleLabel')),
        placeholder: (__VLS_ctx.$t('documents.create.titlePlaceholder')),
        disabled: (__VLS_ctx.creating),
    }, ...__VLS_functionalComponentArgsRest(__VLS_42));
    const __VLS_45 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_46 = __VLS_asFunctionalComponent(__VLS_45, new __VLS_45({
        modelValue: (__VLS_ctx.createTagsRaw),
        label: (__VLS_ctx.$t('documents.create.tagsLabel')),
        placeholder: (__VLS_ctx.$t('documents.create.tagsPlaceholder')),
        disabled: (__VLS_ctx.creating),
        help: (__VLS_ctx.$t('documents.create.tagsHelp')),
    }));
    const __VLS_47 = __VLS_46({
        modelValue: (__VLS_ctx.createTagsRaw),
        label: (__VLS_ctx.$t('documents.create.tagsLabel')),
        placeholder: (__VLS_ctx.$t('documents.create.tagsPlaceholder')),
        disabled: (__VLS_ctx.creating),
        help: (__VLS_ctx.$t('documents.create.tagsHelp')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_46));
    const __VLS_49 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_50 = __VLS_asFunctionalComponent(__VLS_49, new __VLS_49({
        modelValue: (__VLS_ctx.createMime),
        options: (__VLS_ctx.createMimeOptions),
        label: (__VLS_ctx.$t('documents.create.typeLabel')),
        disabled: (__VLS_ctx.creating),
    }));
    const __VLS_51 = __VLS_50({
        modelValue: (__VLS_ctx.createMime),
        options: (__VLS_ctx.createMimeOptions),
        label: (__VLS_ctx.$t('documents.create.typeLabel')),
        disabled: (__VLS_ctx.creating),
    }, ...__VLS_functionalComponentArgsRest(__VLS_50));
    const __VLS_53 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_54 = __VLS_asFunctionalComponent(__VLS_53, new __VLS_53({
        modelValue: (__VLS_ctx.createKind),
        options: (__VLS_ctx.kindCreateOptions),
        label: (__VLS_ctx.$t('documents.create.kindLabel')),
        help: (__VLS_ctx.kindAllowed ? __VLS_ctx.$t('documents.create.kindHelp') : __VLS_ctx.$t('documents.create.kindUnsupported')),
        disabled: (__VLS_ctx.creating || !__VLS_ctx.kindAllowed),
    }));
    const __VLS_55 = __VLS_54({
        modelValue: (__VLS_ctx.createKind),
        options: (__VLS_ctx.kindCreateOptions),
        label: (__VLS_ctx.$t('documents.create.kindLabel')),
        help: (__VLS_ctx.kindAllowed ? __VLS_ctx.$t('documents.create.kindHelp') : __VLS_ctx.$t('documents.create.kindUnsupported')),
        disabled: (__VLS_ctx.creating || !__VLS_ctx.kindAllowed),
    }, ...__VLS_functionalComponentArgsRest(__VLS_54));
    const __VLS_57 = {}.CodeEditor;
    /** @type {[typeof __VLS_components.CodeEditor, ]} */ ;
    // @ts-ignore
    const __VLS_58 = __VLS_asFunctionalComponent(__VLS_57, new __VLS_57({
        modelValue: (__VLS_ctx.createContent),
        label: (__VLS_ctx.$t('documents.create.contentLabel')),
        rows: (14),
        disabled: (__VLS_ctx.creating),
        mimeType: (__VLS_ctx.createMime),
    }));
    const __VLS_59 = __VLS_58({
        modelValue: (__VLS_ctx.createContent),
        label: (__VLS_ctx.$t('documents.create.contentLabel')),
        rows: (14),
        disabled: (__VLS_ctx.creating),
        mimeType: (__VLS_ctx.createMime),
    }, ...__VLS_functionalComponentArgsRest(__VLS_58));
}
else {
    const __VLS_61 = {}.VFileInput;
    /** @type {[typeof __VLS_components.VFileInput, ]} */ ;
    // @ts-ignore
    const __VLS_62 = __VLS_asFunctionalComponent(__VLS_61, new __VLS_61({
        modelValue: (__VLS_ctx.createFiles),
        label: (__VLS_ctx.$t('documents.create.filesLabel')),
        multiple: true,
        disabled: (__VLS_ctx.creating),
        help: (__VLS_ctx.$t('documents.create.filesHelp')),
    }));
    const __VLS_63 = __VLS_62({
        modelValue: (__VLS_ctx.createFiles),
        label: (__VLS_ctx.$t('documents.create.filesLabel')),
        multiple: true,
        disabled: (__VLS_ctx.creating),
        help: (__VLS_ctx.$t('documents.create.filesHelp')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_62));
    const __VLS_65 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_66 = __VLS_asFunctionalComponent(__VLS_65, new __VLS_65({
        modelValue: (__VLS_ctx.createPath),
        label: (__VLS_ctx.$t('documents.create.pathLabel')),
        placeholder: "documents/",
        disabled: (__VLS_ctx.creating),
    }));
    const __VLS_67 = __VLS_66({
        modelValue: (__VLS_ctx.createPath),
        label: (__VLS_ctx.$t('documents.create.pathLabel')),
        placeholder: "documents/",
        disabled: (__VLS_ctx.creating),
    }, ...__VLS_functionalComponentArgsRest(__VLS_66));
    if (__VLS_ctx.createFiles.length <= 1) {
        const __VLS_69 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_70 = __VLS_asFunctionalComponent(__VLS_69, new __VLS_69({
            modelValue: (__VLS_ctx.createTitle),
            label: (__VLS_ctx.$t('documents.create.titleLabel')),
            placeholder: (__VLS_ctx.$t('documents.create.titlePlaceholder')),
            disabled: (__VLS_ctx.creating),
        }));
        const __VLS_71 = __VLS_70({
            modelValue: (__VLS_ctx.createTitle),
            label: (__VLS_ctx.$t('documents.create.titleLabel')),
            placeholder: (__VLS_ctx.$t('documents.create.titlePlaceholder')),
            disabled: (__VLS_ctx.creating),
        }, ...__VLS_functionalComponentArgsRest(__VLS_70));
    }
    const __VLS_73 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_74 = __VLS_asFunctionalComponent(__VLS_73, new __VLS_73({
        modelValue: (__VLS_ctx.createTagsRaw),
        label: (__VLS_ctx.$t('documents.create.tagsLabel')),
        placeholder: (__VLS_ctx.$t('documents.create.tagsPlaceholder')),
        disabled: (__VLS_ctx.creating),
        help: (__VLS_ctx.createFiles.length > 1
            ? __VLS_ctx.$t('documents.create.tagsHelpMulti')
            : __VLS_ctx.$t('documents.create.tagsHelp')),
    }));
    const __VLS_75 = __VLS_74({
        modelValue: (__VLS_ctx.createTagsRaw),
        label: (__VLS_ctx.$t('documents.create.tagsLabel')),
        placeholder: (__VLS_ctx.$t('documents.create.tagsPlaceholder')),
        disabled: (__VLS_ctx.creating),
        help: (__VLS_ctx.createFiles.length > 1
            ? __VLS_ctx.$t('documents.create.tagsHelpMulti')
            : __VLS_ctx.$t('documents.create.tagsHelp')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_74));
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex justify-end gap-2 pt-2" },
});
const __VLS_77 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_78 = __VLS_asFunctionalComponent(__VLS_77, new __VLS_77({
    ...{ 'onClick': {} },
    type: "button",
    variant: "ghost",
    disabled: (__VLS_ctx.creating),
}));
const __VLS_79 = __VLS_78({
    ...{ 'onClick': {} },
    type: "button",
    variant: "ghost",
    disabled: (__VLS_ctx.creating),
}, ...__VLS_functionalComponentArgsRest(__VLS_78));
let __VLS_81;
let __VLS_82;
let __VLS_83;
const __VLS_84 = {
    onClick: (__VLS_ctx.close)
};
__VLS_80.slots.default;
var __VLS_80;
const __VLS_85 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_86 = __VLS_asFunctionalComponent(__VLS_85, new __VLS_85({
    type: "submit",
    variant: "primary",
    loading: (__VLS_ctx.creating),
    disabled: (!__VLS_ctx.projectId),
}));
const __VLS_87 = __VLS_86({
    type: "submit",
    variant: "primary",
    loading: (__VLS_ctx.creating),
    disabled: (!__VLS_ctx.projectId),
}, ...__VLS_functionalComponentArgsRest(__VLS_86));
__VLS_88.slots.default;
var __VLS_88;
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['italic']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['py-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['pt-2']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            CodeEditor: CodeEditor,
            VAlert: VAlert,
            VButton: VButton,
            VFileInput: VFileInput,
            VInput: VInput,
            VModal: VModal,
            VSelect: VSelect,
            emit: emit,
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
            draftSource: draftSource,
            createMimeOptions: createMimeOptions,
            kindAllowed: kindAllowed,
            kindCreateOptions: kindCreateOptions,
            setCreateMode: setCreateMode,
            close: close,
            submit: submit,
        };
    },
    __typeEmits: {},
    __typeProps: {},
    props: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeEmits: {},
    __typeProps: {},
    props: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=CreateDocumentModal.vue.js.map