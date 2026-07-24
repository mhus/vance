<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { VAlert, VButton, useDocumentPrefixReaction } from '@vance/components';
import CanvasEditor from './CanvasEditor.vue';
import InputDialog from './InputDialog.vue';
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

async function refreshScan(select?: string): Promise<void> {
  error.value = null;
  try {
    view.value = await scanCanvasbook(props.document.projectId, folder.value);
    const target = select
      ?? view.value.landingPagePath
      ?? (view.value.pages.length > 0 ? view.value.pages[0].path : null);
    if (target) await openPage(target);
    else { activePath.value = null; graph.value = null; }
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
}

async function openPage(path: string): Promise<void> {
  menuOpen.value = false;
  flushPending();
  activePath.value = path;
  graph.value = null;
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
  const v = await dialog.value?.open('Neue Canvas', [
    { key: 'title', label: 'Titel', value: 'Neue Canvas' },
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
            Noch keine Canvas
          </div>
        </div>
      </div>
      <VButton size="sm" variant="ghost" @click="addPage">+ Canvas</VButton>
      <VButton size="sm" variant="ghost" @click="rebuild">↻ Index</VButton>
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
          {{ saveState === 'saving' ? 'Speichert…' : saveState === 'dirty' ? 'Nicht gespeichert' : 'Gespeichert' }}
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
        @change="onEditorChange"
        @selection="onCanvasSelection"
      />
      <div v-else class="p-4 text-sm opacity-60">
        {{ pages.length === 0 ? 'Leeres Canvasbook — „+ Canvas" anlegen.' : 'Wähle eine Canvas.' }}
      </div>
    </div>

    <InputDialog ref="dialog" />
  </div>
</template>
