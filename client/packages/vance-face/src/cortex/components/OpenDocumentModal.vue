<script setup lang="ts">
/**
 * Quick-open — the "Open…" entry in the Cortex File menu (⌘O, aliased ⌘P).
 *
 * A single search field over {@code GET documents/search}, the same route the
 * link picker uses: project-wide recursive match on path and title, `_vance`
 * included, only `.trash` excluded. Nothing here is a new backend surface.
 *
 * <p><b>Keyboard contract</b> — the quick-open pattern every editor uses,
 * and the reason this is a modal rather than another tree interaction:
 *
 * <ul>
 *   <li>focus never leaves the search field; the highlighted row is a visual
 *       selection, not a focused element</li>
 *   <li>↑/↓ move the selection (wrapping), Enter opens it, Esc closes the
 *       dialog — the last one for free: VModal is a native {@code <dialog>},
 *       whose {@code cancel} event already syncs {@code update:open}</li>
 *   <li>the first result is selected after every search, so a unique hit like
 *       an exact path paste is open-and-done with a bare Enter</li>
 * </ul>
 *
 * <p>Stale responses are discarded by sequence number, not by a guard flag:
 * a slow earlier answer can still land after a faster later one, and "loading"
 * must clear exactly when the newest request finishes — a boolean would say
 * so twice or never, the sequence comparison says so once.
 */
import { nextTick, ref, watch } from 'vue';
import { brainFetch } from '@vance/shared';
import type { DocumentSearchItem, DocumentSearchResponse } from '@vance/generated';
import { VAlert, VInput, VModal } from '@/components';

const props = defineProps<{
  open: boolean;
  projectId: string;
}>();

const emit = defineEmits<{
  (e: 'update:open', open: boolean): void;
  (e: 'open', id: string): void;
}>();
// Results cap. The server clamps to 200; 100 keeps the list scrollable
// rather than a second page of results nobody asked for.
const RESULT_SIZE = 100;
// Same debounce the link picker uses — one search per keystroke burst.
const SEARCH_DEBOUNCE_MS = 200;

const query = ref('');
const results = ref<DocumentSearchItem[]>([]);
const total = ref(0);
const loading = ref(false);
const error = ref<string | null>(null);
const selectedIndex = ref(0);

// VModal's header carries the ✕ button, and showModal() focuses the first
// focusable element — which is that button, not the search field. So the
// field is focused explicitly; the ref reaches through VInput's exposed
// `input`, the only way in ($attrs land on the wrapping label).
const searchInput = ref<InstanceType<typeof VInput> | null>(null);

let searchTimer: ReturnType<typeof setTimeout> | null = null;
let searchSeq = 0;

// Row elements for scroll-into-view. Function refs, not a reactive array:
// Vue calls them with `null` on unmount and with the element on (re)mount,
// and the map keys on the render index — the same index the selection uses.
const itemEls = new Map<number, HTMLElement>();
function itemRef(index: number): (el: unknown) => void {
  return (el) => {
    if (el) itemEls.set(index, el as HTMLElement);
    else itemEls.delete(index);
  };
}

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    query.value = '';
    results.value = [];
    total.value = 0;
    error.value = null;
    loading.value = false;
    selectedIndex.value = 0;
    void runSearch('');
    nextTick(() => searchInput.value?.input?.focus());
  },
  // The modal can mount already open (v-if host gate lifted late, or a
  // test) — without `immediate` that first open never initialises. Same
  // gap VModal closes with its own onMounted special case.
  { immediate: true },
);

/**
 * Debounced input → search. An empty query is a valid state: it lists the
 * project's documents alphabetically, so the dialog is useful the instant
 * it opens — same call the link picker makes on mount.
 */
function onQueryInput(value: string): void {
  query.value = value;
  if (searchTimer != null) clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    searchTimer = null;
    void runSearch(query.value.trim());
  }, SEARCH_DEBOUNCE_MS);
}

async function runSearch(q: string): Promise<void> {
  const seq = ++searchSeq;
  loading.value = true;
  error.value = null;
  try {
    const params = new URLSearchParams();
    params.set('projectId', props.projectId);
    if (q) params.set('query', q);
    params.set('size', String(RESULT_SIZE));
    const resp = await brainFetch<DocumentSearchResponse>('GET', `documents/search?${params}`);
    if (seq !== searchSeq) return;
    results.value = resp.items ?? [];
    total.value = resp.total ?? results.value.length;
    // First hit selected after every search — the "paste a path, press
    // Enter" flow is the whole point of this dialog.
    selectedIndex.value = 0;
    scrollSelectedIntoView();
  } catch (e) {
    if (seq !== searchSeq) return;
    error.value = e instanceof Error ? e.message : 'Search failed';
    results.value = [];
    total.value = 0;
    selectedIndex.value = 0;
  } finally {
    if (seq === searchSeq) loading.value = false;
  }
}

/** ↑/↓/Enter on the search field. Esc is the dialog's own `cancel`. */
function onSearchKeydown(e: KeyboardEvent): void {
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault();
    if (results.value.length === 0) return;
    const delta = e.key === 'ArrowDown' ? 1 : -1;
    selectedIndex.value =
      (selectedIndex.value + delta + results.value.length) % results.value.length;
    scrollSelectedIntoView();
    return;
  }
  if (e.key === 'Enter') {
    e.preventDefault();
    const doc = results.value[selectedIndex.value];
    if (doc) pick(doc);
  }
}

function scrollSelectedIntoView(): void {
  // nextTick: the selection may have just changed what Vue renders as
  // highlighted; scroll to what the reader sees, not what was there before.
  nextTick(() => itemEls.get(selectedIndex.value)?.scrollIntoView({ block: 'nearest' }));
}

function pick(doc: DocumentSearchItem): void {
  emit('open', doc.id);
  emit('update:open', false);
}
</script>

<template>
  <VModal
    :model-value="open"
    :title="$t('cortex.openDocument.title')"
    @update:model-value="(v: boolean) => emit('update:open', v)"
  >
    <div class="space-y-2 p-2">
      <VInput
        ref="searchInput"
        :model-value="query"
        :placeholder="$t('cortex.openDocument.searchPlaceholder')"
        autocomplete="off"
        @update:model-value="onQueryInput"
        @keydown="onSearchKeydown"
      />
      <VAlert v-if="error" variant="error">{{ error }}</VAlert>
      <div v-if="loading" class="py-4 text-center text-sm opacity-60">
        {{ $t('cortex.openDocument.searching') }}
      </div>
      <div v-else-if="results.length === 0" class="py-4 text-center text-sm opacity-60">
        {{ $t('cortex.openDocument.noResults') }}
      </div>
      <ul v-else class="max-h-80 overflow-y-auto text-sm">
        <li
          v-for="(doc, i) in results"
          :key="doc.id"
          :ref="itemRef(i)"
          class="flex cursor-pointer flex-col gap-0.5 rounded px-2 py-1.5"
          :class="i === selectedIndex ? 'bg-primary/15' : 'hover:bg-base-200'"
          @click="pick(doc)"
          @mouseenter="selectedIndex = i"
        >
          <span class="truncate">{{ doc.title || doc.path }}</span>
          <span class="truncate text-xs opacity-60">{{ doc.path }}</span>
        </li>
      </ul>
      <div v-if="results.length > 0 && total > results.length" class="text-xs opacity-60">
        {{ $t('cortex.openDocument.truncated', { shown: results.length, total }) }}
      </div>
    </div>
  </VModal>
</template>
