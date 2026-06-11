<script setup lang="ts">
/**
 * Generic Tab-Shell for the Cortex view. Wraps every doc-type in a
 * shared toolbar (path, reload, properties-deep-link, dirty indicator,
 * mime-type) and dispatches the body to the view component the
 * docTypeRegistry resolved.
 *
 * Modes:
 *  - {@code 'code'}          → CodeEditor from `@vance/components`, with
 *    text-selection mirroring into the cortex store so the
 *    {@code cortex_get_selection} client tool can surface it.
 *  - {@code 'image'}         → ImageView from `src/document/`, read-only.
 *  - {@code 'typed-model'}   → domain view from `src/document/` (Tree,
 *    List, Checklist, Records, Chart, Sheet, Graph, Diagram, Slides,
 *    Mindmap). The shell parses {@code inlineText} via the binding's
 *    codec, passes the parsed model as {@code :doc}, listens for
 *    {@code @update:doc} and serializes back. Parse errors fall back
 *    to a CodeEditor view on the raw text so the user can rescue
 *    malformed documents.
 *  - {@code 'kind-registry'} → view + codec resolved from
 *    {@code @vance/kind-registry} (host built-ins + addon kinds like
 *    Calendar). Same render contract as {@code typed-model}; the host
 *    just sourced the entry differently.
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { CodeEditor } from '@/components';
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

const propertiesUrl = computed<string | null>(() => {
  const pid = store.projectId;
  if (!pid) return null;
  const params = new URLSearchParams({
    projectId: pid,
    documentId: props.document.id,
  });
  return `/documents.html?${params.toString()}`;
});

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

// ImageView and the addon kind views consume a DocumentDto. Build a
// partial DTO from the trimmed CortexDocument shape — required-but-
// unused fields get inert defaults that don't affect rendering.
const docDtoForView = computed<DocumentDto>(() => ({
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
  kind: props.document.kind ?? undefined,
  headers: {},
  autoSummary: false,
  summaryDirty: false,
}));

// ─── Parse / serialize for typed-model + kind-registry modes ─────
//
// Both modes share the same render contract. They differ only in how
// the parse/serialize function pair is sourced — typed-model uses our
// hand-rolled codec, kind-registry uses KindEntry.parse/serialize. The
// shell hides that detail from the view.

interface CodecPair {
  parse?: (body: string, mime: string) => unknown;
  serialize?: (doc: unknown, mime: string) => string;
  isParseError?: (e: unknown) => boolean;
}

const codecPair = computed<CodecPair | null>(() => {
  const b = binding.value;
  if (b.mode === 'typed-model' && b.codec) {
    return {
      parse: b.codec.parse,
      serialize: b.codec.serialize,
    };
  }
  if (b.mode === 'kind-registry' && b.kindEntry) {
    return {
      parse: b.kindEntry.parse as CodecPair['parse'],
      serialize: b.kindEntry.serialize as CodecPair['serialize'],
      isParseError: b.kindEntry.isParseError,
    };
  }
  return null;
});

interface ParseResult {
  model: unknown | null;
  error: string | null;
}

const parseResult = computed<ParseResult>(() => {
  const pair = codecPair.value;
  if (!pair?.parse) {
    return { model: null, error: null };
  }
  const mime = props.document.mimeType ?? '';
  try {
    return { model: pair.parse(props.document.inlineText, mime), error: null };
  } catch (e) {
    // KindEntry.isParseError lets the view distinguish its own codec
    // errors (show the banner) from unrelated bugs (rethrow). Hand-
    // rolled bindings don't provide one — treat every throw as parse.
    const isParseErr = pair.isParseError ? pair.isParseError(e) : true;
    if (!isParseErr) throw e;
    const msg = e instanceof Error ? e.message : String(e);
    return { model: null, error: msg };
  }
});

function onModelUpdate(model: unknown): void {
  const pair = codecPair.value;
  if (!pair?.serialize) return; // read-only view (e.g. mindmap render)
  const mime = props.document.mimeType ?? '';
  let text: string;
  try {
    text = pair.serialize(model, mime);
  } catch (e) {
    console.warn('Codec serialize failed; dropping update', e);
    return;
  }
  if (text !== props.document.inlineText) {
    emit('update', text);
  }
}

// Active view for the 'view-modes' (typed-model + kind-registry).
// KindEntry has both .editor (preferred) and .view (fallback);
// typed-model has just .view.
import type { Component } from 'vue';
const activeView = computed<Component | undefined>(() => {
  const b = binding.value;
  if (b.mode === 'typed-model') return b.view;
  if (b.mode === 'kind-registry') return b.kindEntry?.editor ?? b.kindEntry?.view;
  return undefined;
});

// What gets passed to the view: a parsed model when there's a parser,
// otherwise the DocumentDto so display-only views (PDF-like addons)
// can fetch their own bytes.
const viewBindings = computed<Record<string, unknown>>(() => {
  if (codecPair.value?.parse) {
    return { doc: parseResult.value.model };
  }
  return { document: docDtoForView.value };
});

const isViewMode = computed<boolean>(
  () => binding.value.mode === 'typed-model' || binding.value.mode === 'kind-registry',
);

// ─── View / Edit toggle ──────────────────────────────────────────
//
// For typed-model and kind-registry modes the user can flip between
// the rendered view (Mermaid diagram, Marpit slides, ListView,
// ChecklistView, …) and the raw source in a CodeEditor — mirrors the
// preview / raw tab pair in DocumentApp.vue. Defaults to 'view' and
// resets on tab switch so opening a fresh doc always starts rendered.

type ViewEditMode = 'view' | 'edit';
const viewEditMode = ref<ViewEditMode>('view');

watch(
  () => props.document.id,
  () => {
    viewEditMode.value = 'view';
  },
);

// In a view-capable mode, 'edit' falls back to the same CodeEditor
// the catch-all 'code' mode uses — same selection-tracking, same
// keyboard model.
const showRawEditor = computed<boolean>(
  () => isViewMode.value && viewEditMode.value === 'edit',
);
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
      <div
        v-if="isViewMode"
        class="flex border border-base-300 rounded overflow-hidden text-xs"
        role="group"
        aria-label="View / edit toggle"
      >
        <button
          type="button"
          class="px-2 py-0.5"
          :class="viewEditMode === 'view' ? 'bg-base-300' : 'opacity-60 hover:bg-base-200'"
          title="Rendered view"
          @click="viewEditMode = 'view'"
        >View</button>
        <button
          type="button"
          class="px-2 py-0.5 border-l border-base-300"
          :class="viewEditMode === 'edit' ? 'bg-base-300' : 'opacity-60 hover:bg-base-200'"
          title="Raw source editor"
          @click="viewEditMode = 'edit'"
        >Edit</button>
      </div>
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
      <span
        class="opacity-50 text-xs font-mono"
        :title="`binding=${binding.id} mode=${binding.mode} kind=${document.kind ?? 'null'} mime=${document.mimeType ?? 'null'}`"
      >
        [{{ binding.id }}]
        {{ binding.mode === 'code' ? effectiveMimeType : (document.mimeType ?? binding.mode) }}
      </span>
    </div>

    <!-- Code mode: CodeEditor on the raw text. -->
    <div v-if="binding.mode === 'code'" class="flex-1 min-h-0 overflow-hidden">
      <CodeEditor
        :model-value="document.inlineText"
        :mime-type="effectiveMimeType"
        @update:model-value="(v: string) => emit('update', v)"
        @selection-changed="onSelectionChanged"
      />
    </div>

    <!-- Image mode: ImageView, read-only. -->
    <div
      v-else-if="binding.mode === 'image'"
      class="flex-1 min-h-0 overflow-auto bg-base-200/40 flex items-start justify-center p-4"
    >
      <ImageView mode="editor" :document="docDtoForView" />
    </div>

    <!-- typed-model + kind-registry share the same render contract. -->
    <template v-else-if="isViewMode">
      <!-- User flipped the View/Edit toggle to 'edit' — raw source on
           inlineText with the same CodeEditor the catch-all uses. -->
      <div
        v-if="showRawEditor"
        class="flex-1 min-h-0 overflow-hidden"
      >
        <CodeEditor
          :model-value="document.inlineText"
          :mime-type="effectiveMimeType"
          @update:model-value="(v: string) => emit('update', v)"
          @selection-changed="onSelectionChanged"
        />
      </div>
      <!-- Parse error fallback — show the raw text in a CodeEditor so
           the user can rescue a malformed body. -->
      <div
        v-else-if="parseResult.error"
        class="flex-1 min-h-0 flex flex-col overflow-hidden"
      >
        <div class="px-3 py-2 bg-error/10 text-error text-xs border-b border-error/30">
          Could not parse as {{ document.kind }} — falling back to raw editor.
          {{ parseResult.error }}
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
      <div v-else class="flex-1 min-h-0 overflow-auto">
        <component
          :is="activeView"
          mode="editor"
          v-bind="viewBindings"
          @update:doc="onModelUpdate"
        />
      </div>
    </template>
  </div>
</template>
