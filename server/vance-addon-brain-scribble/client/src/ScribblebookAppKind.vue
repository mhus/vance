<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { VAlert, VButton, useAppEntry, useDocumentPrefixReaction } from '@vance/components';
import ScribbleEditor from './ScribbleEditor.vue';
import InputDialog from './InputDialog.vue';
import { useT } from './i18n';
import {
  createScribblePage,
  getSheet,
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
    // A sheet the URL asks for wins over the landing page — that is what
    // makes a deep link a deep link. An unknown handle falls through to
    // the default instead of showing nothing.
    const target = select
      ?? pathForHandle(appEntry.entry.value)
      ?? view.value.landingPagePath
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
            :class="{ 'font-semibold': p.path === activePath }"
            @click="openSheet(p.path)"
          >
            {{ p.title }}
          </button>
          <div v-if="pages.length === 0" class="px-3 py-2 text-sm opacity-60">
            {{ t('scribble.book.noSheets') }}
          </div>
        </div>
      </div>
      <VButton size="sm" variant="ghost" @click="addSheet">+ {{ t('scribble.book.addSheet') }}</VButton>
      <VButton size="sm" variant="ghost" @click="rebuild">↻ {{ t('scribble.book.rebuildIndex') }}</VButton>
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

    <div class="min-h-0 flex-1">
      <ScribbleEditor
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
