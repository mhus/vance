<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { VAlert, VButton, useDocumentPrefixReaction } from '@vance/components';
import ScribbleEditor from './ScribbleEditor.vue';
import { exportSheetPdf, getSheet, ocrSheet, putSheet } from './api';
import { useT } from './i18n';
import type { ScribbleSheetDto } from './generated/scribble/ScribbleSheetDto';
import type { ScribbleSheetView } from './generated/scribble/ScribbleSheetView';

/**
 * Editable standalone mount for a `kind: scribble` document. Writing is the
 * primary action, so the sheet opens in the editor directly (workpage
 * paradigm) — there is no read-only view and no authoring app in between.
 *
 * <p>This component owns the debounced save (the editor only emits `change`,
 * it never persists): ~2 s after the last committed stroke, plus a flush on
 * `visibilitychange`/`pagehide`/unmount — on the iPad a backgrounded
 * WKWebView tab can be dropped without further notice, so the flush paths
 * are load-bearing, not polish.
 */
const props = defineProps<{
  document: { id?: string; path: string; projectId: string };
}>();

const t = useT();

const view = ref<ScribbleSheetView | null>(null);
const error = ref<string | null>(null);
const loading = ref(false);
const saveState = ref<'saved' | 'dirty' | 'saving'>('saved');

// ── Load ───────────────────────────────────────────────────────

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  pending = null;
  if (timer) {
    clearTimeout(timer);
    timer = null;
  }
  try {
    view.value = await getSheet(props.document.projectId, props.document.path);
    saveState.value = 'saved';
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
}

onMounted(load);
watch(() => [props.document.projectId, props.document.path], load);

// ── Debounced save ──────────────────────────────────────────────

const SAVE_DELAY_MS = 2000;
let timer: ReturnType<typeof setTimeout> | null = null;
let pending: ScribbleSheetDto | null = null;

function onEditorChange(sheet: ScribbleSheetDto): void {
  // Do NOT feed the sheet back into `view` — that would reset the editor's
  // local stroke state mid-edit. The editor is authoritative locally; we
  // only persist.
  pending = sheet;
  saveState.value = 'dirty';
  if (timer) clearTimeout(timer);
  timer = setTimeout(flush, SAVE_DELAY_MS);
}

let lastSelfWriteAt = 0;

async function flush(): Promise<void> {
  if (timer) {
    clearTimeout(timer);
    timer = null;
  }
  const sheet = pending;
  pending = null;
  if (!sheet) return;
  saveState.value = 'saving';
  try {
    await putSheet(props.document.projectId, props.document.path, sheet);
    lastSelfWriteAt = Date.now();
    saveState.value = 'saved';
  } catch (e) {
    // A failed save must never be silent — the sheet stays dirty and the
    // error is surfaced; the next change re-arms the debounce.
    error.value = e instanceof Error ? e.message : String(e);
    saveState.value = 'dirty';
  }
}

// Flush triggers beyond the debounce: everything that ends this view's
// lifetime without further notice.
function onVisibilityChange(): void {
  if (document.visibilityState === 'hidden') void flush();
}

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibilityChange);
  window.addEventListener('pagehide', onPageHide);
});

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibilityChange);
  window.removeEventListener('pagehide', onPageHide);
  void flush();
});

function onPageHide(): void {
  void flush();
}

// ── Live document updates (documents channel) ─────────────────
// The sheet is a document, so remote saves fire `documents.changed`.
// Reload only when saved and idle — local unsaved strokes are never
// clobbered (planning/scribble.md §11), and our own save echo is
// skipped via a self-write window.
const parentFolder = computed(() => {
  const i = props.document.path.lastIndexOf('/');
  return i < 0 ? '' : props.document.path.slice(0, i);
});

useDocumentPrefixReaction({
  prefix: computed(() => (parentFolder.value ? `${parentFolder.value}/` : null)),
  onRemoteChange: async (paths) => {
    if (!paths.includes(props.document.path)) return;
    if (Date.now() - lastSelfWriteAt < 2500) return; // our own save echo
    if (saveState.value !== 'saved' || pending) return; // don't drop local edits
    try {
      view.value = await getSheet(props.document.projectId, props.document.path);
    } catch {
      /* transient — next change or reconnect retries */
    }
  },
});

const saveLabel = computed(() =>
  saveState.value === 'saving'
    ? t('scribble.state.saving')
    : saveState.value === 'dirty'
      ? t('scribble.state.unsaved')
      : t('scribble.state.saved'));
// ── OCR + PDF export of this single sheet ───────────────────────
// Same pattern as the book header: flush first, then the server does the
// work while the button spins.
const busy = ref<'ocr' | 'pdf' | null>(null);
const notice = ref<string | null>(null);

async function runOcr(): Promise<void> {
  if (busy.value) return;
  busy.value = 'ocr';
  error.value = null;
  notice.value = null;
  try {
    await flush();
    const res = await ocrSheet(props.document.projectId, props.document.path);
    notice.value = t('scribble.book.ocrDone') + ' ' + res.mdPath;
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = null;
  }
}

async function runPdf(): Promise<void> {
  if (busy.value) return;
  busy.value = 'pdf';
  error.value = null;
  notice.value = null;
  try {
    await flush();
    const res = await exportSheetPdf(props.document.projectId, props.document.path);
    notice.value = t('scribble.book.pdfDone', { path: res.pdfPath, count: res.pageCount });
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = null;
  }
}

</script>

<template>
  <div class="flex h-full w-full flex-col">
    <VAlert v-if="error" variant="error">{{ error }}</VAlert>
    <VAlert v-else-if="notice" variant="success">{{ notice }}</VAlert>
    <div v-else-if="loading || !view" class="p-4 text-sm opacity-60">
      {{ t('scribble.common.loading') }}
    </div>
    <template v-else>
      <div class="no-print flex items-center justify-end gap-2 px-3 py-1 text-xs text-slate-500">
        <span
          class="inline-block h-2 w-2 rounded-full"
          :class="{
            'bg-green-500': saveState === 'saved',
            'bg-amber-500': saveState === 'dirty',
            'bg-blue-500 animate-pulse': saveState === 'saving',
          }"
        ></span>
        <span>{{ saveLabel }}</span>
        <VButton
          size="sm"
          variant="ghost"
          :disabled="busy !== null"
          :title="t('scribble.book.ocr')"
          :aria-label="t('scribble.book.ocr')"
          @click="runOcr"
        >
          {{ busy === 'ocr' ? '…' : '📝' }}
        </VButton>
        <VButton
          size="sm"
          variant="ghost"
          :disabled="busy !== null"
          :title="t('scribble.book.pdf')"
          :aria-label="t('scribble.book.pdf')"
          @click="runPdf"
        >
          {{ busy === 'pdf' ? '…' : '⤓' }}
        </VButton>
      </div>
      <div class="min-h-0 flex-1">
        <ScribbleEditor :sheet="view.sheet" :editable="true" @change="onEditorChange" />
      </div>
    </template>
  </div>
</template>
