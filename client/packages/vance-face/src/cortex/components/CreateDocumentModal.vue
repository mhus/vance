<script setup lang="ts">
/**
 * Unified create-document modal: handles both inline-content authoring
 * (kind-aware stub generator, mime/path/title/tags) and external file
 * upload (single or multi). Lifted out of DocumentApp.vue so the Cortex
 * + Notepad surfaces can share it.
 *
 * Emits {@code confirm} with a discriminated result; the parent does
 * the actual creation against its store / REST layer (since the two
 * mounts have different store shapes).
 */
import { computed, ref, watch } from 'vue';
import {
  CodeEditor,
  VAlert,
  VButton,
  VFileInput,
  VInput,
  VModal,
  VSelect,
} from '@/components';
import { useI18n } from 'vue-i18n';
import { consumeDocumentDraft, type DocumentDraft } from '@/platform/documentDraft';

interface Props {
  /** v-model:open — two-way visibility flag. */
  open: boolean;
  /** Required to enable the submit button (also serves as the
   *  parent's pre-flight check). */
  projectId: string | null;
  /** Directory the new document lands in. Trailing slash is
   *  normalised. Empty string = project root. */
  initialPath?: string;
  /** When set, the modal calls {@code consumeDocumentDraft()} on
   *  every open and pre-fills name / title / mime / content from
   *  the stored draft (Inbox "To Document" flow). One-shot. */
  consumeDraft?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  initialPath: '',
  consumeDraft: false,
});

export type CreateModalResult =
  | {
      kind: 'inline';
      fullPath: string;
      title: string | undefined;
      tags: string[] | undefined;
      mimeType: string;
      inlineText: string;
    }
  | {
      kind: 'upload';
      files: File[];
      targetFolder: string;
      title: string | undefined;
      tags: string[] | undefined;
    };

const emit = defineEmits<{
  (e: 'update:open', open: boolean): void;
  (e: 'confirm', result: CreateModalResult): Promise<void> | void;
}>();

const { t } = useI18n();

type CreateMode = 'inline' | 'upload';

const createMode = ref<CreateMode>('inline');
const createPath = ref('');
const createName = ref('');
const createTitle = ref('');
const createTagsRaw = ref('');
const createMime = ref('text/markdown');
const createContent = ref('');
const createKind = ref<string>('');
const createFiles = ref<File[]>([]);
const createError = ref<string | null>(null);
const creating = ref(false);

let lastGeneratedStub = '';

// Reset the form whenever the modal transitions to open. Each open
// starts at the prefilled path with an empty name/body — re-using the
// previous attempt's state would leak data across unrelated creates.
const draftSource = ref<string | null>(null);

watch(
  () => props.open,
  (open) => {
    if (!open) return;
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
      const draft: DocumentDraft | null = consumeDocumentDraft();
      if (draft) {
        if (draft.title) createTitle.value = draft.title;
        if (draft.mimeType) createMime.value = draft.mimeType;
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
          } else {
            createName.value = draft.path;
          }
        }
        draftSource.value = draft.source ?? null;
      }
    }
  },
  { immediate: true },
);

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
    { value: 'text/x-tex', label: 'LaTeX (.tex, .sty, .cls, .ltx, .dtx)', group: codeGroup },
    { value: 'text/x-bibtex', label: 'BibTeX (.bib, .bst)', group: codeGroup },
    { value: 'text/html', label: 'HTML', group: webGroup },
    { value: 'text/css', label: 'CSS', group: webGroup },
  ];
});

const EXTENSION_TO_MIME: Record<string, string> = {
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
  tex: 'text/x-tex',
  sty: 'text/x-tex',
  cls: 'text/x-tex',
  ltx: 'text/x-tex',
  dtx: 'text/x-tex',
  bib: 'text/x-bibtex',
  bst: 'text/x-bibtex',
  html: 'text/html',
  htm: 'text/html',
  css: 'text/css',
};

function mimeForFilename(name: string): string | null {
  const dot = name.lastIndexOf('.');
  if (dot < 0 || dot === name.length - 1) return null;
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
] as const;

const kindCreateOptions = computed(() => [
  { value: '', label: t('documents.create.kindNone') },
  ...KIND_CREATE_OPTIONS.map((k) => ({ value: k, label: k })),
]);

/**
 * Generate a starter body for the chosen kind in the chosen mime.
 * Same templates as DocumentApp's previous {@code buildKindStub}.
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
    if (isJson) return '{\n  "$meta": { "kind": "graph" },\n  "graph": { "directed": true },\n  "nodes": [\n    { "id": "alice", "label": "Alice" },\n    { "id": "bob", "label": "Bob" }\n  ],\n  "edges": [\n    { "source": "alice", "target": "bob" }\n  ]\n}\n';
    if (isYaml) return '$meta:\n  kind: graph\ngraph:\n  directed: true\nnodes:\n  - id: alice\n    label: Alice\n  - id: bob\n    label: Bob\nedges:\n  - source: alice\n    target: bob\n';
  }
  if (kind === 'chart') {
    if (isJson) return '{\n  "$meta": { "kind": "chart" },\n  "chart": { "chartType": "line", "title": "New Chart" },\n  "xAxis": { "type": "category" },\n  "yAxis": { "type": "value" },\n  "series": [\n    { "name": "Series 1", "data": [\n      { "x": "A", "y": 10 },\n      { "x": "B", "y": 20 },\n      { "x": "C", "y": 15 }\n    ] }\n  ]\n}\n';
    if (isYaml) return '$meta:\n  kind: chart\nchart:\n  chartType: line\n  title: New Chart\nxAxis:\n  type: category\nyAxis:\n  type: value\nseries:\n  - name: Series 1\n    data:\n      - { x: A, y: 10 }\n      - { x: B, y: 20 }\n      - { x: C, y: 15 }\n';
  }
  if (kind === 'slides') {
    if (isMd) return '---\nkind: slides\nslides:\n  theme: default\n  aspect: "16:9"\n  paginate: true\n---\n\n# First slide\n\nWelcome to your deck.\n\n---\n\n## Second slide\n\n- bullet one\n- bullet two\n';
    if (isJson) return '{\n  "$meta": { "kind": "slides" },\n  "slides": { "theme": "default", "aspect": "16:9", "paginate": true },\n  "items": [\n    "# First slide\\n\\nWelcome to your deck.",\n    "## Second slide\\n\\n- bullet one\\n- bullet two"\n  ]\n}\n';
    if (isYaml) return '$meta:\n  kind: slides\nslides:\n  theme: default\n  aspect: "16:9"\n  paginate: true\nitems:\n  - |\n    # First slide\n\n    Welcome to your deck.\n  - |\n    ## Second slide\n\n    - bullet one\n    - bullet two\n';
  }
  if (kind === 'diagram') {
    if (isMd) return '---\nkind: diagram\n---\n\n```mermaid\nflowchart TD\n  A[Start] --> B{Decision}\n  B -->|yes| C[Do it]\n  B -->|no| D[Skip]\n```\n';
    if (isJson) return '{\n  "$meta": { "kind": "diagram" },\n  "source": "flowchart TD\\n  A[Start] --> B{Decision}\\n  B -->|yes| C[Do it]\\n  B -->|no| D[Skip]\\n"\n}\n';
    if (isYaml) return '$meta:\n  kind: diagram\nsource: |\n  flowchart TD\n    A[Start] --> B{Decision}\n    B -->|yes| C[Do it]\n    B -->|no| D[Skip]\n';
  }
  if (kind === 'calendar') {
    if (isJson) return '{\n  "$meta": { "kind": "calendar" },\n  "events": [\n    {\n      "id": "ev-1",\n      "title": "Sprint Planning",\n      "start": "2026-06-12T09:00",\n      "end": "2026-06-12T11:00",\n      "location": "Büro"\n    },\n    {\n      "id": "ev-2",\n      "title": "Urlaub",\n      "start": "2026-07-15",\n      "end": "2026-07-28",\n      "allDay": true,\n      "tags": ["private"]\n    }\n  ]\n}\n';
    if (isYaml) return '$meta:\n  kind: calendar\nevents:\n  - id: ev-1\n    title: Sprint Planning\n    start: "2026-06-12T09:00"\n    end: "2026-06-12T11:00"\n    location: Büro\n  - id: ev-2\n    title: Urlaub\n    start: "2026-07-15"\n    end: "2026-07-28"\n    allDay: true\n    tags: [private]\n';
  }
  if (kind === 'application') {
    if (isJson) return '{\n  "$meta": { "kind": "application", "app": "calendar" },\n  "title": "My Calendar App",\n  "description": "Planning suite — one calendar per lane.",\n  "calendar": {\n    "window": { "from": "2026-06-01", "until": "2026-09-30" },\n    "lanes": {\n      "design":  { "title": "Design",  "color": "blue",   "order": 1 },\n      "backend": { "title": "Backend", "color": "green",  "order": 2 }\n    },\n    "gantt":     { "outputPath": "_gantt.md", "includeRecurring": false },\n    "conflicts": { "outputPath": "_conflicts.yaml", "ignoreWithinTags": ["private"] }\n  }\n}\n';
    if (isYaml) return '$meta:\n  kind: application\n  app: calendar\ntitle: My Calendar App\ndescription: Planning suite — one calendar per lane.\ncalendar:\n  window:\n    from: "2026-06-01"\n    until: "2026-09-30"\n  lanes:\n    design:  { title: Design,  color: blue,  order: 1 }\n    backend: { title: Backend, color: green, order: 2 }\n  gantt:\n    outputPath: _gantt.md\n    includeRecurring: false\n  conflicts:\n    outputPath: _conflicts.yaml\n    ignoreWithinTags: [private]\n';
  }
  if (kind === 'sheet') {
    if (isJson) return '{\n  "$meta": { "kind": "sheet" },\n  "schema": ["A", "B", "C"],\n  "rows": 5,\n  "cells": [\n    { "field": "A1", "data": "Item" },\n    { "field": "B1", "data": "Qty" },\n    { "field": "C1", "data": "Total" },\n    { "field": "A2", "data": "Apples" },\n    { "field": "B2", "data": "10" },\n    { "field": "C2", "data": "=B2*1.5" }\n  ]\n}\n';
    if (isYaml) return '$meta:\n  kind: sheet\nschema: [A, B, C]\nrows: 5\ncells:\n  - field: A1\n    data: Item\n  - field: B1\n    data: Qty\n  - field: C1\n    data: Total\n  - field: A2\n    data: Apples\n  - field: B2\n    data: "10"\n  - field: C2\n    data: "=B2*1.5"\n';
  }
  if (isMd) return `---\nkind: ${kind}\n---\n`;
  if (isJson) return `{\n  "$meta": { "kind": "${kind}" }\n}\n`;
  if (isYaml) return `$meta:\n  kind: ${kind}\n`;
  return '';
}

// Auto-fill body when kind / mime changes — only when the editor still
// holds the last auto-generated stub or is empty.
watch([createKind, createMime], ([kind, mime]) => {
  if (!props.open) return;
  if (createMode.value !== 'inline') return;
  const editorEmpty = createContent.value === '' || createContent.value === lastGeneratedStub;
  if (!editorEmpty) return;
  const stub = buildKindStub(kind, mime);
  createContent.value = stub;
  lastGeneratedStub = stub;
});

// Filename drives mime detection.
watch(createName, (name) => {
  if (!props.open) return;
  if (createMode.value !== 'inline') return;
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

function setCreateMode(mode: CreateMode): void {
  createMode.value = mode;
  createError.value = null;
}

function close(): void {
  emit('update:open', false);
}

async function submit(): Promise<void> {
  if (!props.projectId) return;
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
      const result: CreateModalResult = {
        kind: 'inline',
        fullPath,
        title: titleStr,
        tags: tagList,
        mimeType: createMime.value,
        inlineText: createContent.value,
      };
      await emit('confirm', result);
    } else {
      const files = createFiles.value;
      if (files.length === 0) {
        createError.value = t('documents.create.pickAtLeastOneFile');
        return;
      }
      const targetFolder = createPath.value.replace(/^\/+|\/+$/g, '');
      const result: CreateModalResult = {
        kind: 'upload',
        files,
        targetFolder,
        title: titleStr,
        tags: tagList,
      };
      await emit('confirm', result);
    }
  } catch (e) {
    createError.value = e instanceof Error ? e.message : 'Create failed';
  } finally {
    creating.value = false;
  }
}
</script>

<template>
  <VModal
    :model-value="open"
    :title="$t('documents.create.newDocument')"
    :close-on-backdrop="false"
    @update:model-value="(v: boolean) => emit('update:open', v)"
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

    <form class="flex flex-col gap-3" @submit.prevent="submit">
      <VAlert v-if="createError" variant="error">
        <span>{{ createError }}</span>
      </VAlert>
      <div
        v-if="draftSource"
        class="text-xs italic opacity-70 bg-base-200 rounded px-2 py-1"
      >
        Prefilled from: {{ draftSource }}
      </div>

      <template v-if="createMode === 'inline'">
        <VInput
          v-model="createPath"
          :label="$t('documents.create.pathLabel')"
          placeholder="documents/"
          :disabled="creating"
        />
        <VInput
          v-model="createName"
          :label="$t('documents.create.nameLabel')"
          :placeholder="$t('documents.create.namePlaceholder')"
          required
          :disabled="creating"
          @keydown.enter.prevent="submit"
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
          :help="kindAllowed ? $t('documents.create.kindHelp') : $t('documents.create.kindUnsupported')"
          :disabled="creating || !kindAllowed"
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

        <VInput
          v-model="createPath"
          :label="$t('documents.create.pathLabel')"
          placeholder="documents/"
          :disabled="creating"
        />

        <VInput
          v-if="createFiles.length <= 1"
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
          :help="createFiles.length > 1
            ? $t('documents.create.tagsHelpMulti')
            : $t('documents.create.tagsHelp')"
        />
      </template>

      <div class="flex justify-end gap-2 pt-2">
        <VButton
          type="button"
          variant="ghost"
          :disabled="creating"
          @click="close"
        >Cancel</VButton>
        <VButton
          type="submit"
          variant="primary"
          :loading="creating"
          :disabled="!projectId"
        >Create</VButton>
      </div>
    </form>
  </VModal>
</template>
