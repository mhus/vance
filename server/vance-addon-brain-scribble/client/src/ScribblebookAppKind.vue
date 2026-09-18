<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { VAlert, VButton, useAppEntry, useDocumentPrefixReaction } from '@vance/components';
import ScribbleEditor from './ScribbleEditor.vue';
import InputDialog from './InputDialog.vue';
import { useT } from './i18n';
import {
  createScribblePage,
  exportBookPdf,
  getSheet,
  ocrSheet,
  putSheet,
  rebuildScribblebook,
  scanScribblebook,
} from './api';
import type { ScribbleSheetDto } from './generated/scribble/ScribbleSheetDto';
import type { ScribbleSheetView } from './generated/scribble/ScribbleSheetView';
import type { ScribblebookPageView } from './generated/scribble/ScribblebookPageView';
import type { ScribblebookView } from './generated/scribble/ScribblebookView';

/**
 * Editable mount for an `app: scribblebook` folder. A single menu button
 * switches between the handwriting sheets in the folder; the active sheet is
 * edited in the canvas pen surface and auto-saved (debounced) via the addon
 * REST sheet endpoint.
 */
const props = defineProps<{
  document: { id?: string; path: string; projectId: string; title?: string | null };
}>();

const t = useT();

const folder = computed(() => {
  const p = props.document.path;
  const i = p.lastIndexOf('/');
  return i < 0 ? '' : p.slice(0, i);
});

const view = ref<ScribblebookView | null>(null);
const pages = computed<ScribblebookPageView[]>(() => view.value?.pages ?? []);
const activePath = ref<string | null>(null);
const sheet = ref<ScribbleSheetView | null>(null);
const error = ref<string | null>(null);
const menuOpen = ref(false);
const saveState = ref<'saved' | 'dirty' | 'saving'>('saved');

type DialogApi = {
  open: (
    t: string,
    f: { key: string; label: string; placeholder?: string; value?: string }[],
  ) => Promise<Record<string, string> | null>;
};
const dialog = ref<DialogApi | null>(null);

const activeTitle = computed(
  () => pages.value.find((p) => p.path === activePath.value)?.title ?? '—',
);

const saveLabel = computed(() =>
  saveState.value === 'saving'
    ? t('scribble.state.saving')
    : saveState.value === 'dirty'
      ? t('scribble.state.unsaved')
      : t('scribble.state.saved'));

// The editor instance — flag toggles go through its applyFlag() so they
// ride the same debounced save as strokes (one writer, no PUT race).
const editorRef = ref<{ applyFlag: (patch: { enabled?: boolean; defaultSheet?: boolean }) => void } | null>(null);

// Local mirror of the active sheet's book flags for the header toggles:
// instant visuals, authoritative save via the editor.
const activeEnabled = ref(true);
const activeDefault = ref(false);

// Bind the chat to the open sheet instead of the app manifest — the app's
// promptInject phrases "the sheet the user currently has open", and this
// binding is what makes that sentence true. appDocId = this app tab's own
// doc id, so the host scopes the report to the active app tab.
const reportActiveSubDoc = inject<
  ((sub: { appDocId: string; documentId: string; path: string } | null) => void) | null
>('vance:report-active-subdoc', null);

watch(activePath, (path) => {
  if (!reportActiveSubDoc) return;
  const appId = props.document.id;
  const pageId = path ? pages.value.find((p) => p.path === path)?.id : undefined;
  if (!appId || !path || !pageId) {
    reportActiveSubDoc(null);
    return;
  }
  reportActiveSubDoc({ appDocId: appId, documentId: pageId, path });
}, { immediate: true });

// ── Sheet position in the URL ──────────────────────────────────
// The open sheet is per-tab state the host carries in `?entry=`, so F5,
// back/forward and a bookmark land on the same sheet. The handle is the
// sheet's document **id**, not its path: a rename moves the path, and a
// link stored last month should still resolve.
const appEntry = useAppEntry(() => props.document.id);

function pathForHandle(handle: string | null): string | null {
  if (!handle) return null;
  return pages.value.find((p) => p.id === handle)?.path ?? null;
}

function handleForPath(path: string | null): string | null {
  if (!path) return null;
  return pages.value.find((p) => p.path === path)?.id ?? null;
}

// A link clicked while this tab is already open only changes the host's
// entry — nothing remounts, so the jump has to happen here.
watch(() => appEntry.entry.value, (handle) => {
  const path = pathForHandle(handle);
  if (path && path !== activePath.value) void openSheet(path, 'none');
});

async function refreshScan(select?: string): Promise<void> {
  error.value = null;
  try {
    view.value = await scanScribblebook(props.document.projectId, folder.value);
    // Pick order: an explicit selection (add/rebuild) > the sheet the URL
    // asks for (deep link) > the manifest's landing page > the sheet the
    // user marked as the book's default > the first sheet. An unknown
    // handle falls through instead of showing nothing.
    const target = select
      ?? pathForHandle(appEntry.entry.value)
      ?? view.value.landingPagePath
      ?? view.value.pages.find((p) => p.defaultSheet)?.path
      ?? (view.value.pages.length > 0 ? view.value.pages[0].path : null);
    if (target) await openSheet(target, 'replace');
    else {
      activePath.value = null;
      sheet.value = null;
      appEntry.report(null);
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
}

/**
 * @param history how the host should record the move. `replace` on restore,
 *                `none` when the host asked for it (it already knows), `push`
 *                for a sheet switch the user made.
 */
async function openSheet(
  path: string,
  history: 'push' | 'replace' | 'none' = 'push',
): Promise<void> {
  menuOpen.value = false;
  flushPending();
  activePath.value = path;
  sheet.value = null;
  if (history !== 'none') appEntry.report(handleForPath(path), history);
  try {
    sheet.value = await getSheet(props.document.projectId, path);
    activeEnabled.value = sheet.value.sheet.enabled ?? true;
    activeDefault.value = sheet.value.sheet.defaultSheet ?? false;
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
}

// ── Debounced save ────────────────────────────────────────────

const SAVE_DELAY_MS = 2000;
let timer: ReturnType<typeof setTimeout> | null = null;
let pending: ScribbleSheetDto | null = null;
let lastSelfWriteAt = 0;

function onEditorChange(s: ScribbleSheetDto): void {
  // Do NOT feed s back into `sheet` — that would reset the editor's local
  // stroke state mid-edit. The editor is authoritative locally; we only
  // persist.
  pending = s;
  saveState.value = 'dirty';
  if (timer) clearTimeout(timer);
  timer = setTimeout(flushPending, SAVE_DELAY_MS);
}

async function flushPending(): Promise<void> {
  if (timer) {
    clearTimeout(timer);
    timer = null;
  }
  const s = pending;
  const path = activePath.value;
  pending = null;
  if (!s || !path) return;
  saveState.value = 'saving';
  try {
    await putSheet(props.document.projectId, path, s);
    lastSelfWriteAt = Date.now();
    saveState.value = 'saved';
  } catch (e) {
    // A failed save is surfaced, the sheet stays dirty; the next stroke
    // re-arms the debounce and retries.
    error.value = e instanceof Error ? e.message : String(e);
    saveState.value = 'dirty';
  }
}

async function addSheet(): Promise<void> {
  const v = await dialog.value?.open(t('scribble.book.newSheet'), [
    { key: 'title', label: t('scribble.common.title'), value: t('scribble.book.newSheet') },
  ]);
  if (!v || !v.title) return;
  try {
    const created = await createScribblePage(props.document.projectId, folder.value, {
      title: v.title,
    });
    await refreshScan(created.path);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
}

async function rebuild(): Promise<void> {
  try {
    await rebuildScribblebook(props.document.projectId, folder.value);
    await refreshScan(activePath.value ?? undefined);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
}


// ── Book flags of the active sheet ──────────────────────────────

function toggleEnabled(): void {
  const next = !activeEnabled.value;
  activeEnabled.value = next;
  setLocalFlag(activePath.value, { enabled: next });
  editorRef.value?.applyFlag({ enabled: next });
}

/**
 * Only one sheet per book carries the default flag — setting a new one
 * clears the old (plan §2.2, client-side). The scan mirror in `pages` is
 * stale after the first star (it dates from the last scan), so the local
 * entries are written along: the next toggle sees the previous default,
 * and the menu/star visuals stay true without a re-scan. A stale second
 * default from a lost race is cosmetic — the start pick tolerates it.
 */
async function toggleDefault(): Promise<void> {
  const next = !activeDefault.value;
  if (next) {
    const prev = pages.value.find((p) => p.defaultSheet && p.path !== activePath.value);
    if (prev) {
      await clearDefaultFlag(prev.path);
      setLocalFlag(prev.path, { defaultSheet: false });
    }
  }
  activeDefault.value = next;
  setLocalFlag(activePath.value, { defaultSheet: next });
  editorRef.value?.applyFlag({ defaultSheet: next });
}

async function clearDefaultFlag(path: string): Promise<void> {
  try {
    const prevSheet = await getSheet(props.document.projectId, path);
    await putSheet(props.document.projectId, path, { ...prevSheet.sheet, defaultSheet: false });
  } catch {
    /* keep going — see toggleDefault */
  }
}

function setLocalFlag(path: string | null, patch: { enabled?: boolean; defaultSheet?: boolean }): void {
  if (!path) return;
  const page = view.value?.pages.find((p) => p.path === path);
  if (page) Object.assign(page, patch);
}

// ── OCR + PDF export (server-side, via the addon REST) ──────────
// Both run while the button spins — OCR takes seconds (vision model), the
// PDF assembly is fast. The flush first makes sure the export/OCR sees
// the strokes on disk, not the ones still in the debounce.

const busy = ref<'ocr' | 'pdf' | null>(null);
const notice = ref<string | null>(null);

async function runOcr(): Promise<void> {
  if (!activePath.value || busy.value) return;
  busy.value = 'ocr';
  error.value = null;
  notice.value = null;
  try {
    await flushPending();
    const res = await ocrSheet(props.document.projectId, activePath.value);
    notice.value = t('scribble.book.ocrDone') + ' ' + res.mdPath;
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = null;
  }
}

async function runBookPdf(): Promise<void> {
  if (!folder.value || busy.value) return;
  busy.value = 'pdf';
  error.value = null;
  notice.value = null;
  try {
    await flushPending();
    const res = await exportBookPdf(props.document.projectId, folder.value);
    notice.value =
      t('scribble.book.pdfDone', { path: res.pdfPath, count: res.pageCount })
      + (res.skippedSheets.length > 0 ? ' — ' + t('scribble.book.pdfSkipped') + ': ' + res.skippedSheets.join(', ') : '');
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = null;
  }
}
// ── Live document updates (documents channel) ─────────────────
// A sheet is a document, so remote saves fire `documents.changed`. Reload
// the active sheet when it changes elsewhere. Own echoes are skipped via a
// self-write window; local unsaved edits are never clobbered.
useDocumentPrefixReaction({
  prefix: computed(() => (folder.value ? `${folder.value}/` : null)),
  onRemoteChange: async (paths) => {
    const ap = activePath.value;
    if (!ap) return;
    const relevant = paths.includes(ap) || paths.includes(`${folder.value}/`);
    if (!relevant) return;
    if (Date.now() - lastSelfWriteAt < 2500) return; // our own save echo
    if (saveState.value !== 'saved' || pending) return; // don't drop local edits
    try {
      sheet.value = await getSheet(props.document.projectId, ap);
      activeEnabled.value = sheet.value.sheet.enabled ?? true;
      activeDefault.value = sheet.value.sheet.defaultSheet ?? false;
    } catch {
      /* transient — next change or reconnect retries */
    }
  },
});

onMounted(() => refreshScan());
onBeforeUnmount(() => {
  flushPending();
  reportActiveSubDoc?.(null);
});
</script>

<template>
  <div class="flex h-full w-full flex-col">
    <div class="no-print flex items-center gap-2 border-b border-slate-200 p-2">
      <div class="relative">
        <VButton size="sm" @click="menuOpen = !menuOpen">
          ☰ {{ activeTitle }} ▾
        </VButton>
        <div
          v-if="menuOpen"
          class="absolute left-0 top-full z-20 mt-1 max-h-80 w-64 overflow-auto rounded border border-slate-200 bg-white shadow-lg"
        >
          <button
            v-for="p in pages"
            :key="p.id"
            class="block w-full px-3 py-2 text-left text-sm hover:bg-slate-100"
            :class="{ 'font-semibold': p.path === activePath, 'opacity-50': !p.enabled }"
            @click="openSheet(p.path)"
          >
            {{ p.defaultSheet ? '★ ' : '' }}{{ p.title }}
          </button>
          <div v-if="pages.length === 0" class="px-3 py-2 text-sm opacity-60">
            {{ t('scribble.book.noSheets') }}
          </div>
        </div>
      </div>
      <VButton size="sm" variant="ghost" @click="addSheet">+ {{ t('scribble.book.addSheet') }}</VButton>
      <VButton size="sm" variant="ghost" @click="rebuild">↻ {{ t('scribble.book.rebuildIndex') }}</VButton>
      <VButton
        size="sm"
        variant="ghost"
        :class="activeEnabled ? '' : 'opacity-50'"
        :title="t('scribble.book.toggleEnabled')"
        :aria-label="t('scribble.book.toggleEnabled')"
        @click="toggleEnabled"
      >
        {{ activeEnabled ? '⏻' : '⏼' }}
      </VButton>
      <VButton
        size="sm"
        variant="ghost"
        :class="activeDefault ? 'text-amber-500' : 'text-slate-300'"
        :title="t('scribble.book.toggleDefault')"
        :aria-label="t('scribble.book.toggleDefault')"
        @click="toggleDefault"
      >
        {{ activeDefault ? '★' : '☆' }}
      </VButton>
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
        @click="runBookPdf"
      >
        {{ busy === 'pdf' ? '…' : '⤓' }}
      </VButton>
      <span class="ml-auto flex items-center gap-1.5 text-xs text-slate-500">
        <span
          class="inline-block h-2 w-2 rounded-full"
          :class="{
            'bg-green-500': saveState === 'saved',
            'bg-amber-500': saveState === 'dirty',
            'bg-blue-500 animate-pulse': saveState === 'saving',
          }"
        ></span>
        <span>{{ saveLabel }}</span>
      </span>
    </div>

    <VAlert v-if="error" variant="error" class="no-print">{{ error }}</VAlert>
    <VAlert v-if="notice" variant="success" class="no-print">{{ notice }}</VAlert>

    <div class="min-h-0 flex-1">
      <ScribbleEditor
        ref="editorRef"
        v-if="sheet"
        :key="activePath ?? ''"
        :sheet="sheet.sheet"
        :editable="true"
        @change="onEditorChange"
      />
      <div v-else class="p-4 text-sm text-slate-500">
        {{ pages.length === 0 ? t('scribble.book.empty') : t('scribble.book.pick') }}
      </div>
    </div>

    <InputDialog ref="dialog" />
  </div>
</template>
