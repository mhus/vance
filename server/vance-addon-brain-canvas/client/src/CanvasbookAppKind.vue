<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { VAlert, VButton } from '@vance/components';
import CanvasEditor from './CanvasEditor.vue';
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

const activeTitle = computed(
  () => pages.value.find((p) => p.path === activePath.value)?.title ?? '—',
);

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
    saveState.value = 'saved';
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
    saveState.value = 'dirty';
  }
}

async function addPage(): Promise<void> {
  const title = window.prompt('Titel der neuen Canvas:', 'Neue Canvas');
  if (title === null) return;
  try {
    const created = await createCanvasPage(props.document.projectId, folder.value, { title });
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

onMounted(() => refreshScan());
onBeforeUnmount(flushPending);
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
        @change="onEditorChange"
      />
      <div v-else class="p-4 text-sm opacity-60">
        {{ pages.length === 0 ? 'Leeres Canvasbook — „+ Canvas" anlegen.' : 'Wähle eine Canvas.' }}
      </div>
    </div>
  </div>
</template>
