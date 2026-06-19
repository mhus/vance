<script setup lang="ts">
/**
 * Collapsible Properties panel that lives between the document header
 * (path + Run/Validate/Slart buttons) and the body in DocumentTabShell.
 *
 * v1 surfaces:
 *  - Editable: title, tags (comma-separated)
 *  - Read-only: path, name, mime, kind, size, createdAt, createdBy,
 *    summary
 *  - Nested: {@link DocumentArchives} for the versions list (uses the
 *    existing component as-is; restores re-fetch the doc into the tab).
 */
import { computed, ref, watch } from 'vue';
import { VAlert, VButton, VInput } from '@/components';
import type { DocumentDto } from '@vance/generated';
import DocumentArchives from '@/document/DocumentArchives.vue';
import type { CortexDocument } from '../types';
import { useCortexStore } from '../stores/cortexStore';

const props = defineProps<{
  document: CortexDocument;
}>();

const store = useCortexStore();

const editTitle = ref('');
const editTags = ref('');
const saving = ref(false);
const error = ref<string | null>(null);

// Seed editable fields whenever the document changes (tab switch or
// after a refresh). User edits in progress get overwritten — caller
// is expected to either save or close the panel before switching.
watch(
  () => props.document.id,
  () => {
    editTitle.value = props.document.title ?? '';
    editTags.value = (props.document.tags ?? []).join(', ');
    error.value = null;
  },
  { immediate: true },
);

const isDirty = computed<boolean>(() => {
  const titleNow = props.document.title ?? '';
  const tagsNow = (props.document.tags ?? []).join(', ');
  return editTitle.value !== titleNow || editTags.value !== tagsNow;
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
    await store.updateMeta(props.document.id, {
      title: editTitle.value.trim() || null,
      tags,
    });
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
  mimeType: props.document.mimeType ?? undefined,
  size: props.document.size ?? 0,
  tags: props.document.tags ?? [],
  createdAtMs: props.document.createdAtMs ?? undefined,
  createdBy: props.document.createdBy ?? undefined,
  inline: !!props.document.inlineText,
  inlineText: props.document.inlineText || undefined,
  kind: props.document.kind ?? undefined,
  headers: {},
  autoSummary: props.document.autoSummary ?? false,
  summaryDirty: props.document.summaryDirty ?? false,
  summary: props.document.summary ?? undefined,
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
      </div>
      <dl class="grid grid-cols-[max-content_1fr] gap-x-3 gap-y-1 text-xs">
        <dt class="opacity-60">Path</dt>
        <dd class="font-mono break-all">{{ document.path }}</dd>
        <dt class="opacity-60">Name</dt>
        <dd class="font-mono break-all">{{ document.name }}</dd>
        <dt class="opacity-60">MIME</dt>
        <dd class="font-mono">{{ document.mimeType ?? '—' }}</dd>
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
