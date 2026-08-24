<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  groupTargets,
  useApplicationPicker,
  VButton,
  VInput,
  VModal,
} from '@vance/components';
import { searchDocuments } from './api';
import type { CanvasDocItem } from './generated/canvas/CanvasDocItem';

/**
 * Reference picker for canvas doc-nodes. Four tabs:
 *
 *   1. **Dokument** — server-side search across the project.
 *   2. **Diese App** — the boards of the canvasbook this editor is running in,
 *      so a board can point at a sibling board. Only present when a host
 *      supplied them (standalone `kind: canvas` has no app around it).
 *   3. **Favoriten** / 4. **Apps** — a foreign application, then optionally a
 *      place inside it. Two steps, shared with the block editor's picker via
 *      {@link useApplicationPicker}.
 *
 * Imperative API: `await pickerRef.value.open(projectId, appTargets)` →
 * `{ path, project?, kind, entry? }` on select, or `null` on cancel. `entry`
 * names a place *inside* the picked document; `project` is set only for a
 * foreign one — the caller turns both into a `vance:` reference
 * (planning/inter-links.md §7).
 *
 * Every tab returns the same shape on purpose: a link into an app is an ordinary
 * `vance:` reference with one more param, not a second kind of pick.
 */
const open = ref(false);
const query = ref('');
const results = ref<CanvasDocItem[]>([]);
const loading = ref(false);
let projectId = '';
let resolver: ((v: Pick_ | null) => void) | null = null;
let timer: ReturnType<typeof setTimeout> | null = null;

type Pick_ = { path: string; project?: string; kind?: string; entry?: string };

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

type TabId = 'doc' | 'app' | 'starred' | 'apps';
const tab = ref<TabId>('doc');
const appTargets = ref<AppTargets | null>(null);
const appQuery = ref('');

// `projectId` is a plain let (set per open()), so the picker reads it lazily.
const apps = useApplicationPicker(() => projectId);

watch(tab, (next) => {
  if (next === 'starred' || next === 'apps') {
    apps.back();
    void apps.load();
  }
});

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

/**
 * A foreign app, optionally at one of its places.
 *
 * Typed structurally on the two fields it uses rather than against
 * `ApplicationEntryDto`: this addon does not depend on `@vance/generated` (it
 * has its own generated types), and naming one DTO is not worth a new package
 * edge. The composable's refs stay fully typed either way.
 */
function pickForeign(app: { project: string; path: string }, handle: string | null): void {
  finish({
    path: app.path,
    // Same project stays relative: the reference then survives the folder being
    // copied into another project.
    project: app.project === projectId ? undefined : app.project,
    kind: 'application',
    entry: handle ?? undefined,
  });
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
      <div class="flex gap-1">
        <VButton
          size="sm"
          :variant="tab === 'doc' ? 'primary' : 'ghost'"
          @click="tab = 'doc'"
        >
          Dokument
        </VButton>
        <VButton
          v-if="appTargets"
          size="sm"
          :variant="tab === 'app' ? 'primary' : 'ghost'"
          @click="tab = 'app'"
        >
          Diese App
        </VButton>
        <VButton
          size="sm"
          :variant="tab === 'starred' ? 'primary' : 'ghost'"
          @click="tab = 'starred'"
        >
          Favoriten
        </VButton>
        <VButton
          size="sm"
          :variant="tab === 'apps' ? 'primary' : 'ghost'"
          @click="tab = 'apps'"
        >
          Apps
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

      <template v-else-if="tab === 'app' && appTargets">
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

      <!-- Favoriten / Apps: App wählen, dann optional einen Ort darin. -->
      <template v-else-if="tab === 'starred' || tab === 'apps'">
        <div v-if="apps.error.value" class="p-3 text-sm text-error">{{ apps.error.value }}</div>
        <div v-else-if="apps.loading.value" class="p-3 text-sm opacity-60">Lade…</div>

        <template v-else-if="!apps.openApp.value">
          <div class="max-h-80 overflow-auto rounded border border-base-300">
            <button
              v-for="a in (tab === 'starred' ? apps.starred.value : apps.project.value)"
              :key="a.project + a.path"
              class="flex w-full flex-col items-start gap-0.5 border-b border-base-200 px-3 py-2 text-left hover:bg-base-200"
              @click="apps.choose(a)"
            >
              <span class="text-sm font-medium">
                <span v-if="a.icon">{{ a.icon }} </span>{{ a.title || a.path }}
              </span>
              <span class="text-xs opacity-60">
                {{ a.app }} · {{ a.project === projectId ? a.path : a.project + '/' + a.path }}
              </span>
            </button>
            <div
              v-if="(tab === 'starred' ? apps.starred.value : apps.project.value).length === 0"
              class="p-3 text-sm opacity-60"
            >
              {{ tab === 'starred' ? 'Keine App in den Favoriten.' : 'Keine App in diesem Projekt.' }}
            </div>
          </div>
        </template>

        <template v-else>
          <div class="flex items-center gap-2">
            <VButton size="sm" variant="ghost" @click="apps.back()">← Zurück</VButton>
            <span class="truncate text-sm opacity-70">
              {{ apps.openApp.value.title || apps.openApp.value.path }}
            </span>
          </div>
          <div v-if="apps.targetsError.value" class="p-3 text-sm text-error">
            {{ apps.targetsError.value }}
          </div>
          <div v-else-if="apps.targetsLoading.value" class="p-3 text-sm opacity-60">Lade…</div>
          <div v-else class="max-h-80 overflow-auto rounded border border-base-300">
            <button
              class="flex w-full flex-col items-start gap-0.5 border-b border-base-200 px-3 py-2 text-left hover:bg-base-200"
              @click="pickForeign(apps.openApp.value, null)"
            >
              <span class="text-sm font-medium">
                {{ apps.openApp.value.title || apps.openApp.value.path }}
              </span>
              <span class="text-xs opacity-60">Die App selbst, ohne bestimmten Ort</span>
            </button>
            <template v-for="g in groupTargets(apps.targets.value)" :key="g.name ?? ''">
              <div v-if="g.name" class="px-3 pt-2 text-xs uppercase tracking-wide opacity-50">
                {{ g.name }}
              </div>
              <button
                v-for="t in g.items"
                :key="t.handle"
                class="flex w-full flex-col items-start gap-0.5 border-b border-base-200 px-3 py-2 text-left hover:bg-base-200"
                @click="pickForeign(apps.openApp.value, t.handle)"
              >
                <span class="text-sm font-medium">{{ t.label }}</span>
              </button>
            </template>
          </div>
        </template>
      </template>

      <div class="flex justify-end">
        <VButton size="sm" variant="ghost" @click="finish(null)">Abbrechen</VButton>
      </div>
    </div>
  </VModal>
</template>
