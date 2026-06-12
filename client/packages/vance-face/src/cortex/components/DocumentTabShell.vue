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
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue';
import { CodeEditor, MarkdownView } from '@/components';
import type { DocumentDto } from '@vance/generated';
import ImageView from '@/document/ImageView.vue';
import DocumentPreview from '@/document/DocumentPreview.vue';
import type { CortexDocument } from '../types';
import { useCortexStore } from '../stores/cortexStore';
import { resolveBinding } from '../docTypeRegistry';
import { resolveRunAdapter } from '../runners/runnerRegistry';
import type { RunHandle } from '../runners/types';
import CortexValidateDialog from './CortexValidateDialog.vue';
import CortexHactarDialog from './CortexHactarDialog.vue';

interface Props {
  document: CortexDocument;
  /** Chat session id — Hactar binds its think-process to this session. */
  sessionId?: string | null;
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

// Derive a language hint for CodeEditor. Path extension wins over the
// server-supplied mime: a file named {@code foo.js} should highlight
// as JS whether the server returns {@code text/javascript},
// {@code application/octet-stream} or nothing at all. Server mime is
// only consulted when the extension doesn't pin a language.
const effectiveMimeType = computed<string>(() => {
  const lower = props.document.path.toLowerCase();
  if (lower.endsWith('.md') || lower.endsWith('.markdown')) return 'text/markdown';
  if (lower.endsWith('.json')) return 'application/json';
  if (lower.endsWith('.yaml') || lower.endsWith('.yml')) return 'application/yaml';
  if (
    lower.endsWith('.js')
    || lower.endsWith('.mjs')
    || lower.endsWith('.mjsh')
    || lower.endsWith('.cjs')
  ) return 'text/javascript';
  if (lower.endsWith('.ts') || lower.endsWith('.tsx')) return 'text/typescript';
  if (lower.endsWith('.py')) return 'text/x-python';
  if (lower.endsWith('.sh') || lower.endsWith('.bash')) return 'text/x-shellscript';
  if (lower.endsWith('.r')) return 'text/x-r';
  if (lower.endsWith('.java')) return 'text/x-java';
  if (lower.endsWith('.html') || lower.endsWith('.htm')) return 'text/html';
  if (lower.endsWith('.css')) return 'text/css';
  if (lower.endsWith('.xml')) return 'application/xml';
  if (lower.endsWith('.sql')) return 'application/sql';
  const explicit = props.document.mimeType;
  return explicit && explicit.trim() ? explicit : 'text/plain';
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

// Markdown gets the same view/edit toggle as the kind-registry modes:
// rendered MarkdownView in 'view', raw CodeEditor in 'edit'. The
// catch-all 'code' binding resolves these documents (no dedicated
// Markdown binding in the registry) so the toggle gates inside the
// 'code' branch below rather than the typed-model / kind-registry
// template.
const isMarkdownDocument = computed<boolean>(() => {
  if (binding.value.mode !== 'code') return false;
  const lower = props.document.path.toLowerCase();
  if (lower.endsWith('.md') || lower.endsWith('.markdown')) return true;
  return (props.document.mimeType ?? '').toLowerCase().startsWith('text/markdown');
});

const showToggle = computed<boolean>(
  () => isViewMode.value || isMarkdownDocument.value,
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

// ─── Run adapter (orthogonal capability) ─────────────────────────
//
// resolveRunAdapter is independent of the doc-type binding — a JS
// file might be 'code' mode (catch-all), a Python file might one day
// be a typed-model view, both can be runnable. The shell composes
// view + run UI.

const runAdapter = computed(() => resolveRunAdapter(props.document));
// shallowRef preserves the RunHandle's internal Ref<T> shape — the
// template + script access {@code .state.value} / {@code .logLines.value}
// directly, no deep-unwrap surprises.
const runHandle = shallowRef<RunHandle | null>(null);
const argsJson = ref('{}');
const argsError = ref<string | null>(null);
const runStarting = ref(false);

const runState = computed(() => runHandle.value?.state.value ?? 'idle');
const isRunning = computed(
  () => runStarting.value
    || runState.value === 'starting'
    || runState.value === 'running',
);

async function onRun(): Promise<void> {
  if (!runAdapter.value || !store.projectId) return;
  if (isRunning.value) return;
  // Parse args before starting so a typo doesn't kick off a no-op
  // execution. {} is the implicit default for an empty input.
  let parsedArgs: Record<string, unknown> = {};
  argsError.value = null;
  const raw = argsJson.value.trim();
  if (raw && raw !== '{}') {
    try {
      const v = JSON.parse(raw);
      if (v === null || typeof v !== 'object' || Array.isArray(v)) {
        throw new Error('args must be a JSON object');
      }
      parsedArgs = v as Record<string, unknown>;
    } catch (e) {
      argsError.value = e instanceof Error ? e.message : 'Invalid JSON';
      return;
    }
  }
  // Detach the previous handle to free its WS / poll listeners
  // before allocating a new one for the same tab.
  if (runHandle.value) {
    runHandle.value.detach();
    runHandle.value = null;
  }
  runStarting.value = true;
  try {
    // Backend's scripts/execute loads the document body server-side
    // via scriptId — if our local buffer has uncommitted edits the
    // 2s auto-save debounce wouldn't have flushed yet, so the run
    // would silently execute the previous version. Flush now to
    // guarantee server sees what the user just typed.
    if (props.document.dirty) {
      await store.saveTab(props.document.id);
    }
    const handle = await runAdapter.value.execute({
      doc: props.document,
      projectId: store.projectId,
      args: parsedArgs,
    });
    runHandle.value = handle;
  } catch (e) {
    argsError.value = e instanceof Error ? e.message : 'Run failed';
  } finally {
    runStarting.value = false;
  }
}

async function onCancel(): Promise<void> {
  if (!runHandle.value) return;
  await runHandle.value.cancel();
}

function onCloseLogPanel(): void {
  if (runHandle.value) {
    // Terminal-state handle: detach is fine. Mid-run: detach also
    // OK — we keep the backend execution running, just stop
    // listening. The user can navigate back via the next Run.
    runHandle.value.detach();
    runHandle.value = null;
  }
}

// Tab switch: drop the previous tab's handle so its WS listeners
// don't leak. The new tab starts with no handle until the user hits
// Run.
watch(
  () => props.document.id,
  () => {
    if (runHandle.value) {
      runHandle.value.detach();
      runHandle.value = null;
    }
    argsJson.value = '{}';
    argsError.value = null;
  },
);

// Final cleanup when the shell unmounts (Cortex closed).
onBeforeUnmount(() => {
  if (runHandle.value) {
    runHandle.value.detach();
    runHandle.value = null;
  }
});

// ─── Validate + Hactar dialogs (JS-only for V1) ─────────────────
//
// Both gated by {@code runAdapter.id === 'js'} — Python / Shell
// runners later get their own (or none); the per-language gating
// keeps the toolbar from showing buttons whose endpoint would 404.

const showValidate = ref(false);
const showHactar = ref(false);

const isJsLanguage = computed<boolean>(() => runAdapter.value?.id === 'js');

function onHactarApply(code: string): void {
  emit('update', code);
}

function fmtResult(v: unknown): string {
  if (v === null || v === undefined) return '(no return value)';
  if (typeof v === 'string') return v;
  try {
    return JSON.stringify(v, null, 2);
  } catch {
    return String(v);
  }
}

function fmtDuration(ms: number | null): string {
  if (ms == null) return '';
  if (ms < 1000) return `${ms} ms`;
  return `${(ms / 1000).toFixed(2)} s`;
}
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
        v-if="showToggle"
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
      <!-- Run controls — only when an adapter matches this doc. The
           args input stays inline; an empty / '{}' string is the
           implicit default so the user can just hit Run. -->
      <template v-if="runAdapter">
        <button
          v-if="!isRunning"
          type="button"
          class="text-xs px-2 py-0.5 rounded border border-base-300 hover:bg-base-200"
          :title="`${runAdapter.label} — execute the document`"
          @click="onRun"
        >▶ {{ runAdapter.label }}</button>
        <button
          v-else
          type="button"
          class="text-xs px-2 py-0.5 rounded border border-warning/40 bg-warning/10 text-warning hover:bg-warning/20"
          title="Cancel the running execution"
          @click="onCancel"
        >■ Cancel</button>
        <input
          v-model="argsJson"
          type="text"
          spellcheck="false"
          class="text-xs font-mono px-2 py-0.5 rounded border w-32"
          :class="argsError ? 'border-error' : 'border-base-300'"
          :title="argsError ?? 'JSON args object, default `{}`'"
          placeholder="{}"
        />
      </template>
      <!-- JS-only side actions. Hactar generates / improves a script
           via LLM; Validate runs quick (parse) + deep (LLM review). -->
      <template v-if="isJsLanguage">
        <button
          type="button"
          class="text-xs px-2 py-0.5 rounded border border-base-300 hover:bg-base-200"
          title="Validate (quick + deep)"
          @click="showValidate = true"
        >✓ Validate</button>
        <button
          type="button"
          class="text-xs px-2 py-0.5 rounded border border-base-300 hover:bg-base-200"
          title="Hactar — generate or improve this script"
          @click="showHactar = true"
        >✨ Hactar</button>
      </template>
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

    <!-- Code mode: CodeEditor on the raw text. Markdown takes the same
         branch but adds a rendered Preview/Edit toggle on top. -->
    <div
      v-if="binding.mode === 'code' && isMarkdownDocument && viewEditMode === 'view'"
      class="flex-1 min-h-0 overflow-auto px-4 py-2"
    >
      <MarkdownView :source="document.inlineText" />
    </div>
    <div
      v-else-if="binding.mode === 'code'"
      class="flex-1 min-h-0 overflow-hidden cortex-code-host"
    >
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

    <!-- Preview mode: binary docs that should NEVER hit the CodeEditor
         (PDF/DOCX/XLSX/archive/audio/video/…). DocumentPreview handles
         the renderable subset (PDF via pdfjs, DOCX via mammoth, XLSX
         via SheetJS) and falls back to a "binary file" placeholder for
         the rest. The metadata strip at top gives the user a visible
         confirmation of which file is open and what we know about it
         even when no preview is available. -->
    <template v-else-if="binding.mode === 'preview'">
      <div class="px-4 py-2 bg-base-200/40 border-b border-base-300 text-xs flex flex-wrap gap-x-4 gap-y-1 shrink-0">
        <span class="opacity-60">Path:</span>
        <span class="font-mono">{{ document.path }}</span>
        <span class="opacity-60">MIME:</span>
        <span class="font-mono">{{ document.mimeType ?? '—' }}</span>
        <span class="opacity-60 italic">read-only</span>
      </div>
      <div class="flex-1 min-h-0 overflow-auto p-4 bg-base-200/40">
        <DocumentPreview
          :document-id="document.id"
          :mime-type="document.mimeType ?? null"
        />
      </div>
    </template>

    <!-- typed-model + kind-registry share the same render contract. -->
    <template v-else-if="isViewMode">
      <!-- User flipped the View/Edit toggle to 'edit' — raw source on
           inlineText with the same CodeEditor the catch-all uses. -->
      <div
        v-if="showRawEditor"
        class="flex-1 min-h-0 overflow-hidden cortex-code-host"
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
        <div class="flex-1 min-h-0 overflow-hidden cortex-code-host">
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

    <!-- Run log panel — collapses the editor area when a run is
         active or just finished. Closes with the ✕ button; future
         runs reopen it. -->
    <div
      v-if="runHandle"
      class="flex-none border-t border-base-300 flex flex-col overflow-hidden"
      style="max-height: 45%; min-height: 8rem;"
    >
      <div class="flex items-center gap-2 px-3 py-1 bg-base-200 text-xs font-mono border-b border-base-300">
        <span
          class="px-1.5 py-0.5 rounded uppercase tracking-wide"
          :class="{
            'bg-info/15 text-info': runState === 'running' || runState === 'starting',
            'bg-success/15 text-success': runState === 'finished',
            'bg-error/15 text-error': runState === 'failed',
            'bg-base-300': runState === 'cancelled' || runState === 'idle',
          }"
        >{{ runState }}</span>
        <span v-if="runHandle.durationMs.value != null" class="opacity-70">
          {{ fmtDuration(runHandle.durationMs.value) }}
        </span>
        <span class="flex-1" />
        <button
          type="button"
          class="opacity-60 hover:opacity-100 hover:bg-base-300 rounded px-1"
          title="Close log panel"
          @click="onCloseLogPanel"
        >✕</button>
      </div>

      <div
        v-if="runHandle.error.value"
        class="px-3 py-1.5 bg-error/10 text-error text-xs font-mono whitespace-pre-wrap border-b border-error/30"
      >{{ runHandle.error.value }}</div>

      <div class="flex-1 min-h-0 overflow-y-auto font-mono text-xs p-2 leading-tight">
        <div
          v-for="(line, i) in runHandle.logLines.value"
          :key="i"
          class="whitespace-pre-wrap"
        >{{ line }}</div>
        <div v-if="runHandle.logLines.value.length === 0" class="opacity-50">
          (no log output yet)
        </div>
      </div>

      <div
        v-if="runState === 'finished' && runHandle.result.value !== null"
        class="border-t border-base-300 px-3 py-1.5 bg-base-200/40 font-mono text-xs whitespace-pre-wrap max-h-32 overflow-y-auto"
      >
        <div class="opacity-60 mb-1">result:</div>
        {{ fmtResult(runHandle.result.value) }}
      </div>
    </div>

    <CortexValidateDialog
      v-if="showValidate"
      :document="document"
      @close="showValidate = false"
    />
    <CortexHactarDialog
      v-if="showHactar && store.projectId"
      :document="document"
      :project-id="store.projectId"
      :session-id="sessionId ?? null"
      @close="showHactar = false"
      @apply="onHactarApply"
    />
  </div>
</template>

<style scoped>
/* CodeEditor renders a <label class="form-control"> with an inner
 * .code-editor div that only carries a min-height — by default the
 * label/.code-editor pair is content-sized and grows past the
 * Cortex-bounded parent, so CodeMirror's internal scroller never
 * activates. Force the host chain into flex-fill so the editor
 * matches the bounded parent and CodeMirror handles its own scroll. */
.cortex-code-host {
  display: flex;
  flex-direction: column;
}
.cortex-code-host :deep(.form-control) {
  flex: 1 1 0;
  min-height: 0;
}
.cortex-code-host :deep(.code-editor) {
  flex: 1 1 0;
  min-height: 0;
}
</style>
