<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  groupTargets,
  useApplicationPicker,
  VButton,
  VGroup,
  VGroupRow,
  VInput,
  VModal,
  VSegment,
} from '@vance/components';
import { searchDocuments } from './api';
import type { CanvasDocItem } from './generated/canvas/CanvasDocItem';
import { useT } from './i18n';

/**
 * Reference picker for canvas doc-nodes. Four tabs:
 *
 *   1. **Document** — server-side search across the project.
 *   2. **This app** — the boards of the canvasbook this editor is running in,
 *      so a board can point at a sibling board. Only present when a host
 *      supplied them (standalone `kind: canvas` has no app around it).
 *   3. **Starred** / 4. **Applications** — a foreign application, then
 *      optionally a place inside it. Two steps, shared with the block editor's
 *      picker via {@link useApplicationPicker}.
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
const t = useT();

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

// "This app" only exists when a host supplied the surrounding app's places.
const tabOptions = computed<{ value: TabId; label: string }[]>(() => [
  { value: 'doc', label: t('canvas.picker.tabDocument') },
  ...(appTargets.value ? [{ value: 'app' as const, label: t('canvas.picker.tabThisApp') }] : []),
  { value: 'starred', label: t('canvas.picker.tabStarred') },
  { value: 'apps', label: t('canvas.picker.tabApps') },
]);

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
    :title="t('canvas.picker.title')"
    :close-on-backdrop="false"
    @update:model-value="onToggle"
  >
    <div class="flex flex-col gap-4">
      <VSegment v-model="tab" :options="tabOptions" />

      <template v-if="tab === 'doc'">
        <VInput
          v-model="query"
          :placeholder="t('canvas.picker.searchPlaceholder')"
          @update:model-value="onQuery"
        />
        <div v-if="loading" class="text-sm text-(--v-label-secondary)">{{ t('canvas.common.searching') }}</div>
        <div v-if="!loading && results.length === 0" class="text-sm text-(--v-label-secondary)">
          {{ t('canvas.picker.noHits') }}
        </div>
        <VGroup v-if="results.length > 0">
          <VGroupRow
            v-for="it in results"
            :key="it.id"
            navigates
            :label="it.title || it.path"
            :meta="it.kind ?? undefined"
            :description="it.path"
            @click="pick(it)"
          />
        </VGroup>
      </template>

      <template v-else-if="tab === 'app' && appTargets">
        <VInput
          v-model="appQuery"
          :placeholder="t('canvas.picker.filterPlaceholder')"
        />
        <VGroup>
          <VGroupRow
            navigates
            :label="appTargets.appLabel"
            :description="t('canvas.picker.appItself')"
            @click="pickApp()"
          />
          <VGroupRow
            v-for="target in filteredTargets"
            :key="target.handle"
            navigates
            :label="target.label"
            @click="pickTarget(target)"
          />
        </VGroup>
        <div v-if="filteredTargets.length === 0" class="text-sm text-(--v-label-secondary)">
          {{ t('canvas.picker.noCanvasMatch') }}
        </div>
      </template>

      <!-- Favourites / apps: pick an app, then optionally a place inside it. -->
      <template v-else-if="tab === 'starred' || tab === 'apps'">
        <div v-if="apps.error.value" class="text-sm text-(--v-danger)">{{ apps.error.value }}</div>
        <div v-else-if="apps.loading.value" class="text-sm text-(--v-label-secondary)">
          {{ t('canvas.common.loading') }}
        </div>

        <template v-else-if="!apps.openApp.value">
          <div
            v-if="(tab === 'starred' ? apps.starred.value : apps.project.value).length === 0"
            class="text-sm text-(--v-label-secondary)"
          >
            {{ tab === 'starred'
              ? t('canvas.picker.noStarredApps')
              : t('canvas.picker.noProjectApps') }}
          </div>
          <VGroup v-else>
            <VGroupRow
              v-for="a in (tab === 'starred' ? apps.starred.value : apps.project.value)"
              :key="a.project + a.path"
              navigates
              :label="(a.icon ? a.icon + ' ' : '') + (a.title || a.path)"
              :meta="a.app"
              :description="a.project === projectId ? a.path : a.project + '/' + a.path"
              @click="apps.choose(a)"
            />
          </VGroup>
        </template>

        <template v-else>
          <div class="flex items-center gap-2">
            <VButton icon="back" size="sm" variant="secondary" @click="apps.back()">{{ t('canvas.common.back') }}</VButton>
            <span class="truncate text-sm text-(--v-label-secondary)">
              {{ apps.openApp.value.title || apps.openApp.value.path }}
            </span>
          </div>
          <div v-if="apps.targetsError.value" class="text-sm text-(--v-danger)">
            {{ apps.targetsError.value }}
          </div>
          <div v-else-if="apps.targetsLoading.value" class="text-sm text-(--v-label-secondary)">
            {{ t('canvas.common.loading') }}
          </div>
          <template v-else>
            <VGroup>
              <VGroupRow
                navigates
                :label="apps.openApp.value.title || apps.openApp.value.path"
                :description="t('canvas.picker.appItselfNoPlace')"
                @click="pickForeign(apps.openApp.value, null)"
              />
            </VGroup>
            <VGroup
              v-for="g in groupTargets(apps.targets.value)"
              :key="g.name ?? ''"
              :label="g.name ?? undefined"
            >
              <VGroupRow
                v-for="target in g.items"
                :key="target.handle"
                navigates
                :label="target.label"
                @click="pickForeign(apps.openApp.value, target.handle)"
              />
            </VGroup>
          </template>
        </template>
      </template>
    </div>

    <template #actions>
      <VButton variant="secondary" @click="finish(null)">{{ t('canvas.common.cancel') }}</VButton>
    </template>
  </VModal>
</template>
