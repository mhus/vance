<script setup lang="ts">
/**
 * Generic Tab-Shell for the Cortex view. Wraps every doc-type in a
 * shared toolbar (path, reload, properties-deep-link, dirty indicator,
 * mime-type) and dispatches the body to the view component the
 * docTypeRegistry resolved.
 *
 * V1 knows two modes:
 *  - {@code 'code'}  → CodeEditor from `@vance/components`, with
 *    text-selection mirroring into the cortex store so the
 *    {@code cortex_get_selection} client tool can surface it.
 *  - {@code 'image'} → ImageView from `src/document/`, read-only.
 *
 * V2 will add {@code 'typed-model'} for ChecklistView/ListView/TreeView/
 * RecordsView/SheetView/ChartView/GraphView — those views emit a typed
 * model via {@code @update:doc} and need a codec parse/serialize in the
 * store, which is a bigger lift than this V1 wrapper.
 */
import { computed, onBeforeUnmount, ref } from 'vue';
import { CodeEditor } from '@/components';
import { documentContentUrl } from '@vance/shared';
import type { DocumentDto } from '@vance/generated';
import ImageView from '@/document/ImageView.vue';
import type { CortexDocument } from '../types';
import { useCortexStore } from '../stores/cortexStore';
import { resolveBinding } from '../docTypeRegistry';

interface Props {
  document: CortexDocument;
}

const props = defineProps<Props>();

const emit = defineEmits<{
  (e: 'update', text: string): void;
}>();

const store = useCortexStore();
const binding = computed(() => resolveBinding(props.document));

const reloading = ref(false);

async function onReload(): Promise<void> {
  if (reloading.value) return;
  if (props.document.dirty) {
    const ok = window.confirm(
      `Discard unsaved changes in ${props.document.path}?`,
    );
    if (!ok) return;
  }
  reloading.value = true;
  try {
    await store.reloadTab(props.document.id);
  } catch (e) {
    console.warn('Failed to reload document', e);
  } finally {
    reloading.value = false;
  }
}

// External-edit deep-link — full metadata editing lives in
// documents.html; cortex just jumps there in a new tab so the user can
// edit title/path/MIME/tags/RAG-mode/etc. without us reimplementing
// the entire properties panel here.
const propertiesUrl = computed<string | null>(() => {
  const pid = store.projectId;
  if (!pid) return null;
  const params = new URLSearchParams({
    projectId: pid,
    documentId: props.document.id,
  });
  return `/documents.html?${params.toString()}`;
});

// Derive a language hint from the document's mime-type, falling back
// to the path extension when the server didn't store one. CodeEditor's
// own languageFor mapping handles common mime-types directly, but
// extension-derived mime-types give nicer defaults for files the server
// left as null (e.g. uploaded snippets, markdown notes created via API
// without explicit mime).
const effectiveMimeType = computed<string>(() => {
  const explicit = props.document.mimeType;
  if (explicit) return explicit;
  const lower = props.document.path.toLowerCase();
  if (lower.endsWith('.md')) return 'text/markdown';
  if (lower.endsWith('.json')) return 'application/json';
  if (lower.endsWith('.yaml') || lower.endsWith('.yml')) return 'application/yaml';
  if (lower.endsWith('.js') || lower.endsWith('.mjs')) return 'text/javascript';
  if (lower.endsWith('.ts')) return 'text/typescript';
  if (lower.endsWith('.py')) return 'text/x-python';
  if (lower.endsWith('.sh') || lower.endsWith('.bash')) return 'text/x-shellscript';
  return 'text/plain';
});

interface RawSelection {
  from: number;
  to: number;
  text: string;
}

function onSelectionChanged(sel: RawSelection): void {
  if (sel.from === sel.to || !sel.text) {
    store.clearSelection();
    return;
  }
  store.setSelection({
    docId: props.document.id,
    docPath: props.document.path,
    from: sel.from,
    to: sel.to,
    text: sel.text,
  });
}

onBeforeUnmount(() => {
  store.clearSelection();
});

// ImageView consumes a DocumentDto; CortexDocument is a trimmed
// in-memory shape. Build a partial DTO with the fields ImageView
// actually reads (id, projectId, path, mimeType, inline, inlineText,
// title). Required-but-unused DTO fields get inert defaults — they
// don't affect rendering.
const docDtoForImage = computed<DocumentDto>(() => ({
  id: props.document.id,
  projectId: store.projectId ?? '',
  path: props.document.path,
  name: props.document.name,
  title: props.document.title ?? undefined,
  mimeType: props.document.mimeType ?? undefined,
  size: 0,
  tags: [],
  inline: !!props.document.inlineText,
  inlineText: props.document.inlineText || undefined,
  headers: {},
  autoSummary: false,
  summaryDirty: false,
}));

// Reference the import so an unused-symbol lint doesn't strip it; SVG
// rendering currently goes through ImageView's own documentContentUrl
// call but we may switch to a blob-URL path here once typed-model
// images land.
void documentContentUrl;
</script>

<template>
  <div class="h-full flex flex-col min-h-0">
    <div class="flex items-center gap-2 px-3 py-2 border-b border-base-300 bg-base-100 text-sm">
      <button
        type="button"
        class="opacity-60 enabled:hover:opacity-100 enabled:hover:bg-base-200 disabled:cursor-default
               rounded px-1 leading-none"
        :disabled="reloading"
        :title="document.dirty ? 'Reload (discards unsaved changes)' : 'Reload from server'"
        @click="onReload"
      >
        <span :class="reloading ? 'animate-spin inline-block' : ''">⟳</span>
      </button>
      <span class="font-mono opacity-80 truncate">{{ document.path }}</span>
      <a
        v-if="propertiesUrl"
        :href="propertiesUrl"
        target="_blank"
        rel="noopener"
        class="opacity-60 hover:opacity-100 hover:bg-base-200 rounded px-1 leading-none"
        title="Open document properties in a new tab"
      >↗</a>
      <span
        v-if="document.dirty && binding.editLocation === 'client-memory'"
        class="opacity-60"
      >●</span>
      <span class="flex-1" />
      <span class="opacity-50 text-xs font-mono">
        {{ binding.mode === 'code' ? effectiveMimeType : (document.mimeType ?? 'image') }}
      </span>
    </div>

    <div v-if="binding.mode === 'code'" class="flex-1 min-h-0 overflow-hidden">
      <CodeEditor
        :model-value="document.inlineText"
        :mime-type="effectiveMimeType"
        @update:model-value="(v: string) => emit('update', v)"
        @selection-changed="onSelectionChanged"
      />
    </div>

    <div
      v-else-if="binding.mode === 'image'"
      class="flex-1 min-h-0 overflow-auto bg-base-200/40 flex items-start justify-center p-4"
    >
      <ImageView mode="editor" :document="docDtoForImage" />
    </div>
  </div>
</template>
