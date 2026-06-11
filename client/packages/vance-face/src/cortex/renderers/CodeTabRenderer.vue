<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { CodeEditor } from '@/components';
import type { CortexDocument } from '../types';
import { useCortexStore } from '../stores/cortexStore';

interface Props {
  document: CortexDocument;
}

const props = defineProps<Props>();

const emit = defineEmits<{
  (e: 'update', text: string): void;
}>();

const store = useCortexStore();

interface RawSelection {
  from: number;
  to: number;
  text: string;
}

/**
 * Mirror the editor's current text selection into the cortex store so
 * the {@code cortex_get_selection} client tool can surface it to the
 * agent. A zero-length selection (caret only) is treated as "nothing
 * selected" — the user didn't highlight anything, the position alone
 * isn't useful to the LLM.
 */
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

// Tab close / switch unmounts this renderer; the selection it was
// tracking is no longer meaningful. Clear so the next tool call
// doesn't return a stale highlight from a tab that's gone.
onBeforeUnmount(() => {
  store.clearSelection();
});

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

/**
 * Derive a language hint from the document's mime-type, falling back to
 * the path extension when the server didn't store one. CodeEditor's own
 * {@code languageFor} mapping interprets common mime-types directly, but
 * extension-derived mime-types give nicer defaults for files the server
 * left as {@code null} (e.g. uploaded snippets, markdown notes created
 * via API without explicit mime).
 */
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
      <span v-if="document.dirty" class="opacity-60">●</span>
      <span class="flex-1" />
      <span class="opacity-50 text-xs font-mono">{{ effectiveMimeType }}</span>
    </div>
    <div class="flex-1 min-h-0 overflow-hidden">
      <CodeEditor
        :model-value="document.inlineText"
        :mime-type="effectiveMimeType"
        @update:model-value="(v: string) => emit('update', v)"
        @selection-changed="onSelectionChanged"
      />
    </div>
  </div>
</template>
