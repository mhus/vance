<script setup lang="ts">
import { computed, ref } from 'vue';
import { VButton, VInput, VModal } from '@vance/components';
import { searchDocuments } from './api';
import type { CanvasDocItem } from './generated/canvas/CanvasDocItem';

/**
 * Reference picker for canvas doc-nodes. Two tabs:
 *
 *   1. **Dokument** — server-side search across the project.
 *   2. **Diese App** — the boards of the canvasbook this editor is running in,
 *      so a board can point at a sibling board. Only present when a host
 *      supplied them (standalone `kind: canvas` has no app around it).
 *
 * Imperative API: `await pickerRef.value.open(projectId, appTargets)` →
 * `{ path, kind, entry }` on select, or `null` on cancel. `entry` is set only
 * for the app tab and names a place *inside* the picked document — the caller
 * turns it into `?entry=` (planning/inter-links.md §7).
 *
 * Both tabs return the same shape on purpose: a link into an app is an ordinary
 * `vance:` reference with one more param, not a second kind of pick.
 */
const open = ref(false);
const query = ref('');
const results = ref<CanvasDocItem[]>([]);
const loading = ref(false);
let projectId = '';
let resolver: ((v: Pick_ | null) => void) | null = null;
let timer: ReturnType<typeof setTimeout> | null = null;

type Pick_ = { path: string; kind?: string; entry?: string };

/** One place inside the surrounding app, as the host describes it. */
export interface AppTarget {
  handle: string;
  label: string;
}

/** The surrounding app: where its manifest lives, and what places it has. */
export interface AppTargets {
  /** Path of the app manifest (`<folder>/_app.yaml`) — the link's document. */
  appPath: string;
  /** Display name of the app, for the tab body. */
  appLabel: string;
  targets: AppTarget[];
}

type TabId = 'doc' | 'app';
const tab = ref<TabId>('doc');
const appTargets = ref<AppTargets | null>(null);
const appQuery = ref('');

const filteredTargets = computed<AppTarget[]>(() => {
  const all = appTargets.value?.targets ?? [];
  const q = appQuery.value.trim().toLowerCase();
  if (!q) return all;
  return all.filter((t) => t.label.toLowerCase().includes(q));
});

function openPicker(pid: string, targets?: AppTargets | null): Promise<Pick_ | null> {
  projectId = pid;
  query.value = '';
  appQuery.value = '';
  results.value = [];
  appTargets.value = targets && targets.targets.length > 0 ? targets : null;
  // Default to the document search: linking within the same book is the
  // narrower case, and starting on the tab that can reach everything avoids a
  // click for the common one.
  tab.value = 'doc';
  open.value = true;
  void runSearch();
  return new Promise((res) => {
    resolver = res;
  });
}

function onQuery(): void {
  if (timer) clearTimeout(timer);
  timer = setTimeout(() => void runSearch(), 250);
}

async function runSearch(): Promise<void> {
  loading.value = true;
  try {
    const r = await searchDocuments(projectId, query.value.trim());
    results.value = r.items;
  } catch {
    results.value = [];
  } finally {
    loading.value = false;
  }
}

function finish(v: Pick_ | null): void {
  open.value = false;
  const r = resolver;
  resolver = null;
  r?.(v);
}

function pick(item: CanvasDocItem): void {
  finish({ path: item.path, kind: item.kind ?? undefined });
}

function pickTarget(target: AppTarget): void {
  const app = appTargets.value;
  if (!app) return;
  finish({ path: app.appPath, kind: 'application', entry: target.handle });
}

/** The app itself, without a board — a link to "the book", not a page in it. */
function pickApp(): void {
  const app = appTargets.value;
  if (!app) return;
  finish({ path: app.appPath, kind: 'application' });
}

function onToggle(v: boolean): void {
  if (!v && resolver) finish(null);
}

defineExpose({ open: openPicker });
</script>

<template>
  <VModal
    :model-value="open"
    title="Referenz einfügen"
    :close-on-backdrop="false"
    @update:model-value="onToggle"
  >
    <div class="flex flex-col gap-2">
      <div v-if="appTargets" class="flex gap-1">
        <VButton
          size="sm"
          :variant="tab === 'doc' ? 'primary' : 'ghost'"
          @click="tab = 'doc'"
        >
          Dokument
        </VButton>
        <VButton
          size="sm"
          :variant="tab === 'app' ? 'primary' : 'ghost'"
          @click="tab = 'app'"
        >
          Diese App
        </VButton>
      </div>

      <template v-if="tab === 'doc'">
        <VInput
          v-model="query"
          placeholder="Suche nach Pfad oder Titel …"
          @update:model-value="onQuery"
        />
        <div class="max-h-80 overflow-auto rounded border border-base-300">
          <div v-if="loading" class="p-3 text-sm opacity-60">Suche…</div>
          <button
            v-for="it in results"
            :key="it.id"
            class="flex w-full flex-col items-start gap-0.5 border-b border-base-200 px-3 py-2 text-left hover:bg-base-200"
            @click="pick(it)"
          >
            <span class="text-sm font-medium">{{ it.title || it.path }}</span>
            <span class="text-xs opacity-60">{{ it.kind ? it.kind + ' · ' : '' }}{{ it.path }}</span>
          </button>
          <div v-if="!loading && results.length === 0" class="p-3 text-sm opacity-60">
            Keine Treffer.
          </div>
        </div>
      </template>

      <template v-else-if="appTargets">
        <VInput
          v-model="appQuery"
          placeholder="Canvas in dieser App filtern …"
        />
        <div class="max-h-80 overflow-auto rounded border border-base-300">
          <button
            class="flex w-full flex-col items-start gap-0.5 border-b border-base-200 px-3 py-2 text-left hover:bg-base-200"
            @click="pickApp()"
          >
            <span class="text-sm font-medium">{{ appTargets.appLabel }}</span>
            <span class="text-xs opacity-60">Die App selbst, ohne bestimmte Canvas</span>
          </button>
          <button
            v-for="t in filteredTargets"
            :key="t.handle"
            class="flex w-full flex-col items-start gap-0.5 border-b border-base-200 px-3 py-2 text-left hover:bg-base-200"
            @click="pickTarget(t)"
          >
            <span class="text-sm font-medium">{{ t.label }}</span>
          </button>
          <div v-if="filteredTargets.length === 0" class="p-3 text-sm opacity-60">
            Keine Canvas passt zum Filter.
          </div>
        </div>
      </template>

      <div class="flex justify-end">
        <VButton size="sm" variant="ghost" @click="finish(null)">Abbrechen</VButton>
      </div>
    </div>
  </VModal>
</template>
