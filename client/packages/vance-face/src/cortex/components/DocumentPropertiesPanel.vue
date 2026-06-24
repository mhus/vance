<script setup lang="ts">
/**
 * Collapsible Properties panel that lives between the document header
 * (path + Run/Validate/Slart buttons) and the body in DocumentTabShell.
 *
 * v1 surfaces:
 *  - Editable: name (rename — folder stays), title, tags
 *    (comma-separated), color, mime type
 *  - Read-only: path, kind, size, createdAt, createdBy, summary,
 *    front-matter headers
 *  - Nested: {@link DocumentArchives} for the versions list (uses the
 *    existing component as-is; restores re-fetch the doc into the tab).
 */
import { computed, ref, watch } from 'vue';
import { VAlert, VButton, VColorPicker, VInput, VSelect } from '@/components';
import type { AccentColor, DocumentDto } from '@vance/generated';
import { useI18n } from 'vue-i18n';
import DocumentArchives from '@/document/DocumentArchives.vue';
import type { CortexDocument } from '../types';
import { useCortexStore } from '../stores/cortexStore';

const props = defineProps<{
  document: CortexDocument;
}>();

const store = useCortexStore();
const { t } = useI18n();

const editName = ref('');
const editTitle = ref('');
const editTags = ref('');
const editColor = ref<AccentColor | null>(null);
const editMime = ref<string>('');
const saving = ref(false);
const error = ref<string | null>(null);

const mimeOptions = computed(() => {
  const docGroup = t('documents.mime.groupDoc');
  const codeGroup = t('documents.mime.groupCode');
  const webGroup = t('documents.mime.groupWeb');
  const base = [
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
  // Preserve the current mimeType if it's not in the curated list — the
  // user otherwise loses information just by opening the panel.
  const current = editMime.value;
  if (current && !base.some((o) => o.value === current)) {
    base.unshift({ value: current, label: current, group: docGroup });
  }
  return base;
});

// Seed editable fields whenever the document changes (tab switch or
// after a refresh). User edits in progress get overwritten — caller
// is expected to either save or close the panel before switching.
watch(
  () => props.document.id,
  () => {
    editName.value = props.document.name ?? '';
    editTitle.value = props.document.title ?? '';
    editTags.value = (props.document.tags ?? []).join(', ');
    editColor.value = props.document.color ?? null;
    editMime.value = props.document.mimeType ?? '';
    error.value = null;
  },
  { immediate: true },
);

const isDirty = computed<boolean>(() => {
  const nameNow = props.document.name ?? '';
  const titleNow = props.document.title ?? '';
  const tagsNow = (props.document.tags ?? []).join(', ');
  const colorNow = props.document.color ?? null;
  const mimeNow = props.document.mimeType ?? '';
  return (
    editName.value.trim() !== nameNow
    || editTitle.value !== titleNow
    || editTags.value !== tagsNow
    || editColor.value !== colorNow
    || editMime.value !== mimeNow
  );
});

// Replace the trailing segment of the current document path with the
// edited name, keeping the folder unchanged. Returns null when the
// name didn't change, is empty, or contains a slash (the rename input
// is filename-only — moves go through a separate path edit, not this
// panel).
function buildRenamedPath(): string | null {
  const trimmed = editName.value.trim();
  if (!trimmed || trimmed === props.document.name) return null;
  if (trimmed.includes('/')) {
    throw new Error('Name must not contain "/" — use the path field to move the document.');
  }
  const path = props.document.path;
  const slash = path.lastIndexOf('/');
  return slash < 0 ? trimmed : `${path.substring(0, slash + 1)}${trimmed}`;
}

// Read-only front-matter / upload-inferred headers from
// {@code DocumentDocument.headers}. Sorted by key for stable display.
const headerEntries = computed<Array<[string, string]>>(() => {
  const map = props.document.headers ?? {};
  return Object.entries(map).sort(([a], [b]) => a.localeCompare(b));
});

function formatSize(bytes: number | null | undefined): string {
  if (bytes == null) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

function formatDate(ms: number | null | undefined): string {
  if (!ms) return '—';
  return new Date(ms).toLocaleString();
}

async function onSave(): Promise<void> {
  saving.value = true;
  error.value = null;
  try {
    const tags = editTags.value
      .split(',')
      .map((s) => s.trim())
      .filter((s) => s.length > 0);
    const wasColor = props.document.color ?? null;
    const wasMime = props.document.mimeType ?? '';
    const body: import('../stores/cortexStore').MetaUpdateBody = {
      title: editTitle.value.trim() || null,
      tags,
    };
    if (editColor.value !== wasColor) {
      if (editColor.value === null) {
        body.clearColor = true;
      } else {
        body.color = editColor.value;
      }
    }
    if (editMime.value !== wasMime && editMime.value) {
      body.mimeType = editMime.value;
    }
    const renamedPath = buildRenamedPath();
    if (renamedPath !== null) {
      body.newPath = renamedPath;
    }
    await store.updateMeta(props.document.id, body);
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to save properties';
  } finally {
    saving.value = false;
  }
}

// DocumentArchives expects a DocumentDto — build a partial one from
// the CortexDocument. Only the {@code id} field is functionally
// consulted by the archives panel (load + restore go by id).
const dtoForArchives = computed<DocumentDto>(() => ({
  id: props.document.id,
  projectId: store.projectId ?? '',
  path: props.document.path,
  name: props.document.name,
  title: props.document.title ?? undefined,
  color: props.document.color ?? undefined,
  mimeType: props.document.mimeType ?? undefined,
  size: props.document.size ?? 0,
  tags: props.document.tags ?? [],
  createdAtMs: props.document.createdAtMs ?? undefined,
  createdBy: props.document.createdBy ?? undefined,
  inline: !!props.document.inlineText,
  inlineText: props.document.inlineText || undefined,
  kind: props.document.kind ?? undefined,
  headers: props.document.headers ?? {},
  autoSummary: props.document.autoSummary ?? false,
  summaryDirty: props.document.summaryDirty ?? false,
  summary: props.document.summary ?? undefined,
  notes: props.document.notes ?? {},
}));

async function onRestored(): Promise<void> {
  // Re-pull the document so the body + meta in the tab match the
  // restored version.
  await store.reloadTab(props.document.id);
}
</script>

<template>
  <div class="border-b border-base-300 bg-base-100 px-4 py-3 text-sm">
    <div class="grid grid-cols-1 md:grid-cols-2 gap-3">
      <div class="flex flex-col gap-2">
        <VInput
          v-model="editName"
          label="Name"
          placeholder="filename"
          help="Filename only — folder stays. No slashes."
          :disabled="saving"
        />
        <VInput
          v-model="editTitle"
          label="Title"
          placeholder="(no title)"
          :disabled="saving"
        />
        <VInput
          v-model="editTags"
          label="Tags"
          placeholder="comma, separated"
          help="Comma-separated"
          :disabled="saving"
        />
        <VColorPicker
          v-model="editColor"
          label="Color"
          allow-clear
          :disabled="saving"
        />
        <VSelect
          v-model="editMime"
          :options="mimeOptions"
          label="MIME"
          :disabled="saving"
        />
      </div>
      <dl class="grid grid-cols-[max-content_1fr] gap-x-3 gap-y-1 text-xs">
        <dt class="opacity-60">Path</dt>
        <dd class="font-mono break-all">{{ document.path }}</dd>
        <dt class="opacity-60">Kind</dt>
        <dd class="font-mono">{{ document.kind ?? '—' }}</dd>
        <dt class="opacity-60">Size</dt>
        <dd>{{ formatSize(document.size) }}</dd>
        <dt class="opacity-60">Created</dt>
        <dd>{{ formatDate(document.createdAtMs) }}</dd>
        <dt class="opacity-60">By</dt>
        <dd>{{ document.createdBy ?? '—' }}</dd>
      </dl>
    </div>

    <div v-if="headerEntries.length > 0" class="mt-3 text-xs">
      <div class="opacity-60 mb-1">Headers</div>
      <dl class="grid grid-cols-[max-content_1fr] gap-x-3 gap-y-1 bg-base-200 rounded px-2 py-1">
        <template v-for="[k, v] in headerEntries" :key="k">
          <dt class="font-mono opacity-70">{{ k }}</dt>
          <dd class="font-mono break-all whitespace-pre-wrap">{{ v }}</dd>
        </template>
      </dl>
    </div>

    <div v-if="document.summary" class="mt-3 text-xs">
      <div class="opacity-60 mb-1">Summary</div>
      <div class="bg-base-200 rounded px-2 py-1 whitespace-pre-wrap">
        {{ document.summary }}
      </div>
    </div>

    <VAlert v-if="error" variant="error" class="mt-3">{{ error }}</VAlert>

    <div class="mt-3 flex justify-end">
      <VButton
        size="sm"
        variant="primary"
        :disabled="!isDirty"
        :loading="saving"
        @click="onSave"
      >Save properties</VButton>
    </div>

    <DocumentArchives
      :document="dtoForArchives"
      @restored="onRestored"
    />
  </div>
</template>
