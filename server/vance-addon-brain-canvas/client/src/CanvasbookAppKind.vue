<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { VAlert, VButton, useAppEntry, useDocumentPrefixReaction } from '@vance/components';
import CanvasEditor from './CanvasEditor.vue';
import InputDialog from './InputDialog.vue';
import { useT } from './i18n';
import { createCanvasPage, getGraph, putGraph, rebuildCanvasbook, scanCanvasbook } from './api';
import type { CanvasbookView } from './generated/canvas/CanvasbookView';
import type { CanvasbookPageView } from './generated/canvas/CanvasbookPageView';
import type { CanvasGraphDto } from './generated/canvas/CanvasGraphDto';

/**
 * Editable mount for an `app: canvasbook` folder. A single menu button
 * switches between the canvas boards in the folder; the active board is
 * edited in the VueFlow surface and auto-saved (debounced) via the addon
 * REST graph endpoint.
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

const view = ref<CanvasbookView | null>(null);
const pages = computed<CanvasbookPageView[]>(() => view.value?.pages ?? []);
const activePath = ref<string | null>(null);
const graph = ref<CanvasGraphDto | null>(null);
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

// Bind the chat to the open canvas board instead of the app manifest
// (planning/app-chat-context.md, phase 4). appDocId = this app tab's own doc
// id, so the host scopes the report to the active app tab.
const reportActiveSubDoc = inject<
  ((sub: { appDocId: string; documentId: string; path: string } | null) => void) | null
>('vance:report-active-subdoc', null);
// Node selection → chat active-app hint (phase 4b). Freeform string the
// canvas app owns; the brain's canvasbook promptInject phrases it.
const reportAppSelection = inject<
  ((sel: { appDocId: string; selection: string } | null) => void) | null
>('vance:report-app-selection', null);

watch(activePath, (path) => {
  reportAppSelection?.(null); // a board switch invalidates any node selection
  if (!reportActiveSubDoc) return;
  const appId = props.document.id;
  const pageId = path ? pages.value.find((p) => p.path === path)?.id : undefined;
  if (!appId || !path || !pageId) {
    reportActiveSubDoc(null);
    return;
  }
  reportActiveSubDoc({ appDocId: appId, documentId: pageId, path });
}, { immediate: true });

/** Forward the board's selected node id(s) as the chat's active-app selection. */
function onCanvasSelection(nodeIds: string[]): void {
  if (!reportAppSelection) return;
  const appId = props.document.id;
  if (!appId || nodeIds.length === 0) {
    reportAppSelection(null);
    return;
  }
  reportAppSelection({ appDocId: appId, selection: nodeIds.join(', ') });
}

// ── Sub-position in the URL ───────────────────────────────────
// The open board is per-tab state the host carries in `?entry=`, so F5,
// back/forward, a bookmark and an inter-app link all land on the same board
// (planning/inter-links.md §5). The handle is the page's document **id**, not
// its path: a rename moves the path, and a link stored last month should still
// resolve. Without a host the composable degrades to null + no-op — the app
// then works exactly as before, just without URL memory.
const appEntry = useAppEntry(() => props.document.id);

function pathForHandle(handle: string | null): string | null {
  if (!handle) return null;
  return pages.value.find((p) => p.id === handle)?.path ?? null;
}

function handleForPath(path: string | null): string | null {
  if (!path) return null;
  return pages.value.find((p) => p.path === path)?.id ?? null;
}

/**
 * The places inside *this* canvasbook, for the editor's reference picker.
 *
 * Local data, no round trip: the host already holds the scan, and the app is
 * the only thing that knows its own boards. That is the whole difference
 * between linking into the own app and into a foreign one — the stored link is
 * identical either way (planning/inter-links.md §7.3).
 *
 * The board currently open is left out: a board linking to itself is a dead
 * click, and offering it invites one.
 */
const appTargets = computed(() => {
  const manifest = props.document.path;
  if (!manifest || pages.value.length === 0) return null;
  return {
    appPath: manifest,
    appLabel: props.document.title || folder.value || 'Canvasbook',
    targets: pages.value
      .filter((p) => p.path !== activePath.value)
      .map((p) => ({ handle: p.id, label: p.title || p.path })),
  };
});

// A link clicked while this tab is already open only changes the host's entry —
// nothing remounts, so the jump has to happen here.
watch(() => appEntry.entry.value, (handle) => {
  const path = pathForHandle(handle);
  if (path && path !== activePath.value) void openPage(path, 'none');
});

async function refreshScan(select?: string): Promise<void> {
  error.value = null;
  try {
    view.value = await scanCanvasbook(props.document.projectId, folder.value);
    // A board the URL asks for wins over the landing page — that is what makes
    // a deep link a deep link. An unknown handle falls through to the default
    // instead of showing nothing: the board may have been deleted, and the app
    // is still the right place to land (planning/inter-links.md §4).
    const target = select
      ?? pathForHandle(appEntry.entry.value)
      ?? view.value.landingPagePath
      ?? (view.value.pages.length > 0 ? view.value.pages[0].path : null);
    if (target) await openPage(target, 'replace');
    else {
      activePath.value = null;
      graph.value = null;
      appEntry.report(null);
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
}

/**
 * @param history how the host should record the move. `replace` on restore,
 *                `none` when the host asked for it (it already knows), `push`
 *                for a board switch the user made.
 */
async function openPage(
  path: string,
  history: 'push' | 'replace' | 'none' = 'push',
): Promise<void> {
  menuOpen.value = false;
  flushPending();
  activePath.value = path;
  graph.value = null;
  if (history !== 'none') appEntry.report(handleForPath(path), history);
  try {
    graph.value = await getGraph(props.document.projectId, path);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
}

// ── Debounced save ────────────────────────────────────────────
let timer: ReturnType<typeof setTimeout> | null = null;
let pending: CanvasGraphDto | null = null;
let lastSelfWriteAt = 0;

function onEditorChange(g: CanvasGraphDto): void {
  // Do NOT feed g back into `graph` (the editor's :graph prop) — that
  // would reset the editor's local state mid-edit and snap nodes back.
  // The editor is authoritative locally; we only persist.
  pending = g;
  saveState.value = 'dirty';
  if (timer) clearTimeout(timer);
  timer = setTimeout(flushPending, 1000);
}

async function flushPending(): Promise<void> {
  if (timer) { clearTimeout(timer); timer = null; }
  const g = pending;
  const path = activePath.value;
  pending = null;
  if (!g || !path) return;
  saveState.value = 'saving';
  try {
    await putGraph(props.document.projectId, path, g);
    lastSelfWriteAt = Date.now();
    saveState.value = 'saved';
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
    saveState.value = 'dirty';
  }
}

async function addPage(): Promise<void> {
  const v = await dialog.value?.open(t('canvas.book.newCanvas'), [
    { key: 'title', label: t('canvas.common.title'), value: t('canvas.book.newCanvas') },
  ]);
  if (!v || !v.title) return;
  try {
    const created = await createCanvasPage(props.document.projectId, folder.value, { title: v.title });
    await refreshScan(created.path);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
}

async function rebuild(): Promise<void> {
  try {
    await rebuildCanvasbook(props.document.projectId, folder.value);
    await refreshScan(activePath.value ?? undefined);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
}

// ── Live document updates (documents channel) ─────────────────
// A canvas page is a document, so remote saves fire `documents.changed`.
// Reload the active board when it changes elsewhere. Own echoes are
// skipped via a self-write window; local unsaved edits are never clobbered.
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
      graph.value = await getGraph(props.document.projectId, ap);
    } catch {
      /* transient — next change or reconnect retries */
    }
  },
});

onMounted(() => refreshScan());
onBeforeUnmount(() => {
  flushPending();
  reportActiveSubDoc?.(null);
  reportAppSelection?.(null);
});
</script>

<template>
  <div class="flex h-full w-full flex-col">
    <div class="flex items-center gap-2 border-b border-base-300 p-2">
      <div class="relative">
        <VButton size="sm" @click="menuOpen = !menuOpen">
          ☰ {{ activeTitle }} ▾
        </VButton>
        <div
          v-if="menuOpen"
          class="absolute left-0 top-full z-20 mt-1 max-h-80 w-64 overflow-auto rounded border border-base-300 bg-base-100 shadow-lg"
        >
          <button
            v-for="p in pages"
            :key="p.id"
            class="block w-full px-3 py-2 text-left text-sm hover:bg-base-200"
            :class="{ 'font-semibold': p.path === activePath }"
            @click="openPage(p.path)"
          >
            {{ p.title }}
          </button>
          <div v-if="pages.length === 0" class="px-3 py-2 text-sm opacity-60">
            {{ t('canvas.book.noCanvases') }}
          </div>
        </div>
      </div>
      <VButton size="sm" variant="ghost" @click="addPage">+ {{ t('canvas.book.addCanvas') }}</VButton>
      <VButton size="sm" variant="ghost" @click="rebuild">↻ {{ t('canvas.book.rebuildIndex') }}</VButton>
      <span class="ml-auto flex items-center gap-1.5 text-xs">
        <span
          class="inline-block h-2 w-2 rounded-full"
          :class="{
            'bg-green-500': saveState === 'saved',
            'bg-amber-500': saveState === 'dirty',
            'bg-blue-500 animate-pulse': saveState === 'saving',
          }"
        ></span>
        <span class="opacity-60">
          {{ saveState === 'saving'
            ? t('canvas.book.saving')
            : saveState === 'dirty' ? t('canvas.book.unsaved') : t('canvas.book.saved') }}
        </span>
      </span>
    </div>

    <VAlert v-if="error" variant="error">{{ error }}</VAlert>

    <div class="min-h-0 flex-1">
      <CanvasEditor
        v-if="graph"
        :key="activePath ?? ''"
        :graph="graph"
        :editable="true"
        :project-id="document.projectId"
        :path="activePath"
        :app-targets="appTargets"
        :flush="flushPending"
        @change="onEditorChange"
        @selection="onCanvasSelection"
      />
      <div v-else class="p-4 text-sm opacity-60">
        {{ pages.length === 0 ? t('canvas.book.empty') : t('canvas.book.pick') }}
      </div>
    </div>

    <InputDialog ref="dialog" />
  </div>
</template>
