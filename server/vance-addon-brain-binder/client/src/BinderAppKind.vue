<script setup lang="ts">
import { computed, inject, onMounted, ref, watch, type Component } from 'vue';
import { cortexDeepLink, VAlert, VButton, VInput } from '@vance/components';
import DocPicker from './DocPicker.vue';
import {
  addEntry,
  reorderBinder,
  removeEntry,
  scanBinder,
  setEntrySection,
  setLanding,
  rebuildBinder,
} from './api';
import type { BinderView } from './generated/binder/BinderView';
import type { BinderEntryView } from './generated/binder/BinderEntryView';
import { useT } from './i18n';

/**
 * Mount for an `app: binder` folder. Left: a section-grouped, drag-and-drop
 * list of anchored document references with CRUD. Right: the selected entry
 * rendered per-kind read-only via the host embed component, plus a deep-link
 * into Cortex for editing. The binder never edits what it references.
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

const view = ref<BinderView | null>(null);
const entries = computed<BinderEntryView[]>(() => view.value?.entries ?? []);
const activeRef = ref<string | null>(null);
const error = ref<string | null>(null);
const busy = ref(false);
const filter = ref('');
const openMenuRef = ref<string | null>(null);
const draggedRef = ref<string | null>(null);
const picker = ref<{ open: (pid: string) => Promise<{ path: string; kind?: string } | null> } | null>(
  null,
);

const embedComponent = inject<Component | null>('vance:embed-component', null);
// Bind the chat to the open referenced document (like workbook/canvasbook).
const reportActiveSubDoc = inject<
  ((sub: { appDocId: string; documentId: string; path: string } | null) => void) | null
>('vance:report-active-subdoc', null);

const activeEntry = computed(
  () => entries.value.find((e) => e.ref === activeRef.value) ?? null,
);

const filtered = computed<BinderEntryView[]>(() => {
  const q = filter.value.trim().toLowerCase();
  if (!q) return entries.value;
  return entries.value.filter(
    (e) =>
      e.title.toLowerCase().includes(q) ||
      e.path.toLowerCase().includes(q) ||
      (e.section ?? '').toLowerCase().includes(q),
  );
});

type Group = { section: string; items: BinderEntryView[] };
const groups = computed<Group[]>(() => groupBy(filtered.value));

function groupBy(list: BinderEntryView[]): Group[] {
  const map = new Map<string, BinderEntryView[]>();
  const order: string[] = [];
  for (const e of list) {
    const s = e.section ?? '';
    if (!map.has(s)) {
      map.set(s, []);
      order.push(s);
    }
    map.get(s)!.push(e);
  }
  const sections = ['', ...order.filter((s) => s !== '')].filter((s) => map.has(s));
  return sections.map((s) => ({ section: s, items: map.get(s)! }));
}

/** Full ordered ref list, grouped-contiguous, "" section first. */
function flattenedRefs(list: BinderEntryView[]): string[] {
  return groupBy(list).flatMap((g) => g.items.map((i) => i.ref));
}

watch(activeEntry, (e) => {
  if (!reportActiveSubDoc) return;
  const appId = props.document.id;
  if (!appId || !e || !e.exists || !e.id) {
    reportActiveSubDoc(null);
    return;
  }
  reportActiveSubDoc({ appDocId: appId, documentId: e.id, path: e.path });
}, { immediate: true });

onMounted(load);

async function load(): Promise<void> {
  error.value = null;
  try {
    const v = await scanBinder(props.document.projectId, folder.value);
    apply(v);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
}

function apply(v: BinderView): void {
  view.value = v;
  const refs = v.entries.map((e) => e.ref);
  if (activeRef.value && refs.includes(activeRef.value)) return;
  activeRef.value = v.landingRef && refs.includes(v.landingRef) ? v.landingRef : refs[0] ?? null;
}

function select(e: BinderEntryView): void {
  activeRef.value = e.ref;
  openMenuRef.value = null;
}

async function run(fn: () => Promise<BinderView>): Promise<void> {
  busy.value = true;
  error.value = null;
  try {
    apply(await fn());
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
    openMenuRef.value = null;
  }
}

async function onAdd(): Promise<void> {
  const picked = await picker.value?.open(props.document.projectId);
  if (!picked) return;
  await run(() => addEntry(props.document.projectId, folder.value, `vance:/${picked.path}`));
}

async function onRemove(e: BinderEntryView): Promise<void> {
  if (!window.confirm(t('binder.app.confirmRemove', { title: e.title }))) return;
  await run(() => removeEntry(props.document.projectId, folder.value, e.ref));
}

async function onSetLanding(e: BinderEntryView): Promise<void> {
  const next = view.value?.landingRef === e.ref ? null : e.ref;
  await run(() => setLanding(props.document.projectId, folder.value, next));
}

async function onRename(e: BinderEntryView): Promise<void> {
  const next = window.prompt(t('binder.app.promptTitle'), e.title);
  if (next === null) return;
  await run(() =>
    setEntrySection(props.document.projectId, folder.value, e.ref, e.section ?? null, next),
  );
}

async function onMoveSection(e: BinderEntryView): Promise<void> {
  const next = window.prompt(t('binder.app.promptSection'), e.section ?? '');
  if (next === null) return;
  await run(() => setEntrySection(props.document.projectId, folder.value, e.ref, next, null));
}

async function onRebuild(): Promise<void> {
  busy.value = true;
  try {
    await rebuildBinder(props.document.projectId, folder.value);
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
}

// ── drag & drop reorder + cross-section move ───────────────────────

function onDragStart(e: BinderEntryView): void {
  draggedRef.value = e.ref;
}

async function onDropOnEntry(target: BinderEntryView): Promise<void> {
  const dragged = draggedRef.value;
  draggedRef.value = null;
  if (!dragged || dragged === target.ref) return;
  await moveTo(dragged, target.ref, target.section ?? '');
}

async function onDropOnSection(section: string): Promise<void> {
  const dragged = draggedRef.value;
  draggedRef.value = null;
  if (!dragged) return;
  await moveTo(dragged, null, section);
}

async function moveTo(
  draggedRefVal: string,
  beforeRef: string | null,
  section: string,
): Promise<void> {
  const list = entries.value.map((e) => ({ ...e }));
  const from = list.findIndex((e) => e.ref === draggedRefVal);
  if (from < 0) return;
  const [moved] = list.splice(from, 1);
  const sectionChanged = (moved.section ?? '') !== section;
  moved.section = section || undefined;
  if (beforeRef) {
    const to = list.findIndex((e) => e.ref === beforeRef);
    list.splice(to < 0 ? list.length : to, 0, moved);
  } else {
    list.push(moved);
  }
  const orderedRefs = flattenedRefs(list);
  busy.value = true;
  error.value = null;
  try {
    if (sectionChanged) {
      await setEntrySection(props.document.projectId, folder.value, draggedRefVal, section, null);
    }
    apply(await reorderBinder(props.document.projectId, folder.value, orderedRefs));
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
}

function openInCortex(e: BinderEntryView): void {
  if (!e.id) return;
  const url = cortexDeepLink({ project: props.document.projectId, documentId: e.id });
  window.open(url, '_blank', 'noopener');
}

function kindIcon(kind?: string | null): string {
  switch ((kind ?? '').toLowerCase()) {
    case 'finance-tree': return '💰';
    case 'sheet': return '▦';
    case 'chart': return '📈';
    case 'markdown': case 'workpage': return '📄';
    case 'canvas': return '🗺';
    case 'image': case 'svg': return '🖼';
    default: return '•';
  }
}
</script>

<template>
  <div class="flex h-full min-h-0 w-full">
    <!-- Sidebar -->
    <aside class="flex w-72 shrink-0 flex-col border-r border-base-300">
      <div class="flex items-center justify-between gap-2 border-b border-base-300 p-2">
        <div class="truncate font-semibold" :title="view?.title ?? ''">
          {{ view?.title || t('binder.app.fallbackTitle') }}
        </div>
        <div class="flex gap-1">
          <VButton
            size="sm"
            variant="ghost"
            :disabled="busy"
            :title="t('binder.app.rebuildIndex')"
            @click="onRebuild"
          >↻</VButton>
          <VButton size="sm" variant="primary" :disabled="busy" @click="onAdd">
            + {{ t('binder.app.pin') }}
          </VButton>
        </div>
      </div>
      <div class="p-2">
        <VInput v-model="filter" :placeholder="t('binder.app.filterPlaceholder')" />
      </div>

      <div class="min-h-0 flex-1 overflow-auto">
        <div v-if="entries.length === 0" class="p-4 text-sm opacity-60">
          {{ t('binder.app.empty') }}
        </div>

        <template v-for="g in groups" :key="g.section || '__none__'">
          <div
            v-if="g.section"
            class="px-3 pt-3 pb-1 text-xs font-semibold uppercase tracking-wide opacity-50"
            @dragover.prevent
            @drop="onDropOnSection(g.section)"
          >
            {{ g.section }}
          </div>
          <ul>
            <li
              v-for="e in g.items"
              :key="e.ref"
              draggable="true"
              class="group relative flex cursor-pointer items-center gap-2 px-3 py-1.5 text-sm hover:bg-base-200"
              :class="{ 'bg-base-200 font-medium': e.ref === activeRef, 'opacity-50': !e.exists }"
              @click="select(e)"
              @dragstart="onDragStart(e)"
              @dragover.prevent
              @drop="onDropOnEntry(e)"
            >
              <span class="w-4 shrink-0 text-center">{{ e.exists ? kindIcon(e.kind) : '⚠' }}</span>
              <span class="truncate" :title="e.path">{{ e.title }}</span>
              <span
                v-if="view?.landingRef === e.ref"
                class="ml-auto shrink-0"
                :title="t('binder.app.landing')"
              >📌</span>
              <button
                class="ml-auto hidden shrink-0 px-1 opacity-60 hover:opacity-100 group-hover:block"
                :class="{ '!ml-1': view?.landingRef === e.ref }"
                :title="t('binder.app.actions')"
                @click.stop="openMenuRef = openMenuRef === e.ref ? null : e.ref"
              >⋯</button>

              <div
                v-if="openMenuRef === e.ref"
                class="absolute right-2 top-8 z-10 w-44 rounded border border-base-300 bg-base-100 py-1 shadow-lg"
                @click.stop
              >
                <button
                  class="block w-full px-3 py-1.5 text-left text-sm hover:bg-base-200"
                  @click="onMoveSection(e)"
                >{{ t('binder.app.changeSection') }}</button>
                <button
                  class="block w-full px-3 py-1.5 text-left text-sm hover:bg-base-200"
                  @click="onRename(e)"
                >{{ t('binder.app.rename') }}</button>
                <button class="block w-full px-3 py-1.5 text-left text-sm hover:bg-base-200" @click="onSetLanding(e)">
                  {{ view?.landingRef === e.ref
                    ? t('binder.app.removeLanding')
                    : t('binder.app.setLanding') }}
                </button>
                <button
                  class="block w-full px-3 py-1.5 text-left text-sm text-error hover:bg-base-200"
                  @click="onRemove(e)"
                >{{ t('binder.app.remove') }}</button>
              </div>
            </li>
          </ul>
        </template>
      </div>
    </aside>

    <!-- Detail -->
    <section class="flex min-h-0 min-w-0 flex-1 flex-col">
      <VAlert v-if="error" variant="error" class="m-2">{{ error }}</VAlert>

      <template v-if="activeEntry">
        <div class="flex items-center gap-2 border-b border-base-300 px-3 py-2">
          <span class="truncate text-sm font-medium" :title="activeEntry.path">{{ activeEntry.title }}</span>
          <span class="truncate text-xs opacity-50">{{ activeEntry.path }}</span>
          <div class="ml-auto flex gap-1">
            <VButton size="sm" variant="ghost" :title="t('binder.app.reload')" @click="load">↻</VButton>
            <VButton
              size="sm"
              variant="primary"
              :disabled="!activeEntry.exists || !activeEntry.id"
              @click="openInCortex(activeEntry)"
            >{{ t('binder.app.editInCortex') }} ↗</VButton>
          </div>
        </div>
        <div class="min-h-0 flex-1 overflow-auto p-3">
          <div v-if="!activeEntry.exists" class="text-sm opacity-70">
            {{ t('binder.app.goneTitle') }} (<code>{{ activeEntry.path }}</code>).
            {{ t('binder.app.goneHint') }}
          </div>
          <component
            :is="embedComponent"
            v-else-if="embedComponent"
            :key="activeEntry.ref"
            :uri="activeEntry.ref"
          />
          <div v-else class="text-sm opacity-60">{{ t('binder.app.noRenderer') }}</div>
        </div>
      </template>

      <div v-else class="flex flex-1 items-center justify-center p-8 text-center text-sm opacity-60">
        {{ t('binder.app.pickOne') }}
      </div>
    </section>

    <DocPicker ref="picker" />
  </div>
</template>
