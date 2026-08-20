<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  VAlert, VButton, VCard, VEmptyState, VInput, VModal, VSelect, VTextarea,
} from '@vance/components';
import { safeUrl } from '@vance/shared';
import {
  clipItem, listSources, loadConfig, loadFacetValues, loadPage, saveConfig, sendSignal,
} from './api';
import type { FeedConfigView } from './generated/centauri/FeedConfigView';
import type { FeedFacetValueView } from './generated/centauri/FeedFacetValueView';
import type { FeedFacetView } from './generated/centauri/FeedFacetView';
import type { FeedItemView } from './generated/centauri/FeedItemView';
import type { FeedNoteView } from './generated/centauri/FeedNoteView';
import type { FeedSourceView } from './generated/centauri/FeedSourceView';

/**
 * Mount for an `app: feeds` manifest. Two views over one configuration:
 * the stream (endless scroll) and the form that decides what is in it.
 *
 * The manifest holds configuration only — the entries are transient and
 * remote, and the one way they become permanent is clipping.
 */
const props = defineProps<{
  document: { id?: string; path: string; projectId: string; title?: string | null };
}>();

const folder = computed(() => {
  const p = props.document.path;
  const i = p.lastIndexOf('/');
  return i < 0 ? '' : p.slice(0, i);
});

type Tab = 'stream' | 'config';
const tab = ref<Tab>('stream');

const config = ref<FeedConfigView | null>(null);
const sources = ref<FeedSourceView[]>([]);
const items = ref<FeedItemView[]>([]);
const notes = ref<FeedNoteView[]>([]);
const cursor = ref<string | null>(null);
const hasMore = ref(true);
const loading = ref(false);
const error = ref<string | null>(null);
/**
 * Per entry, keyed the same way the cards are.
 *
 * `item.id` alone is not a key here: it is unique within its source, not across
 * the merged stream, so two sources that both count from 1 would share a
 * „clipped" mark. The card's `:key` already says what identity means on this
 * screen — these maps have to agree with it.
 */
const clipped = ref<Record<string, string>>({});
/** Per entry what we told the source. Transient — nothing is stored anywhere. */
const signalled = ref<Record<string, string>>({});

/** The identity of one entry on this screen. Same expression as the card key. */
function entryKey(item: FeedItemView): string {
  return item.sourceId + '\u0000' + item.id;
}

/**
 * A remote URL as an `href` or `src`, or null when it must not become one.
 *
 * Feed entries are written by foreign services. `controlUrl` is already
 * scheme- and host-checked on the server, but `url` and `imageUrl` are not —
 * this is the second line, at the point where the value becomes a link.
 */
function link(raw: string | null | undefined): string | null {
  return safeUrl(raw);
}

const REPORT_REASONS = [
  { value: 'WRONG_CATEGORY', label: 'Wrong category' },
  { value: 'WRONG_LANGUAGE', label: 'Wrong language' },
  { value: 'BROKEN_LINK', label: 'Broken link' },
  { value: 'DUPLICATE', label: 'Duplicate' },
  { value: 'SPAM', label: 'Spam' },
];

const report = ref<{ item: FeedItemView; reason: string; note: string } | null>(null);
const reportOpen = ref(false);

const sentinel = ref<HTMLElement | null>(null);
let observer: IntersectionObserver | null = null;

const configuredStreams = computed(() => config.value?.streams ?? []);

/**
 * The reader's current facet selection — transient, and deliberately not the
 * stored one until „Save as filter" says so. Browsing is not configuring.
 */
const facetSelection = ref<Record<string, string[]>>({});

/**
 * Facets offered above the stream: those a configured source declares.
 *
 * Keyed per source, because a facet key is only as shared as its value system.
 * Two sources may both declare `subject-topic` and mean different vocabularies,
 * so their values are never merged into one list.
 */
const offeredFacets = computed(() => {
  const configured = new Set(configuredStreams.value.map((s) => s.source));
  const out: { sourceId: string; sourceName: string; facet: FeedFacetView }[] = [];
  for (const source of sources.value) {
    if (!configured.has(source.id)) continue;
    for (const facet of source.capabilities?.facets ?? []) {
      out.push({ sourceId: source.id, sourceName: source.displayName, facet });
    }
  }
  return out;
});

/** Values of one facet, including lazily fetched levels. */
const facetValues = ref<Record<string, FeedFacetValueView[]>>({});

function facetKeyOf(sourceId: string, key: string): string {
  return `${sourceId}\u0000${key}`;
}

async function ensureFacetValues(sourceId: string, facet: FeedFacetView): Promise<void> {
  const cacheKey = facetKeyOf(sourceId, facet.key);
  if (facetValues.value[cacheKey]) return;
  // A non-lazy facet already shipped everything with its declaration.
  if (!facet.lazyChildren) {
    facetValues.value = { ...facetValues.value, [cacheKey]: facet.values };
    return;
  }
  try {
    const values = await loadFacetValues(props.document.projectId, sourceId, facet.key);
    facetValues.value = { ...facetValues.value, [cacheKey]: values };
  } catch (e) {
    error.value = String(e);
  }
}

function selectedFacetValue(key: string): string {
  return facetSelection.value[key]?.[0] ?? '';
}

/**
 * One value per key for now. The wire carries a list — several values of one
 * key are an „or" — but a select is what the taxonomy shapes here call for,
 * and a multi-select without a tree widget would be worse than one clear
 * choice.
 */
async function selectFacet(key: string, value: string | null): Promise<void> {
  const next = { ...facetSelection.value };
  if (value) next[key] = [value];
  else delete next[key];
  facetSelection.value = next;
  await restart();
}

async function saveFacetsAsFilter(): Promise<void> {
  if (!config.value) return;
  config.value = { ...config.value, filter: { ...config.value.filter, facets: facetSelection.value } };
  await persist();
}

onMounted(async () => {
  await reload();
  // Endless scroll: the sentinel below the last card asks for the next page as
  // soon as it comes into view.
  observer = new IntersectionObserver((entries) => {
    if (entries.some((e) => e.isIntersecting)) void nextPage();
  });
  if (sentinel.value) observer.observe(sentinel.value);
});

onBeforeUnmount(() => observer?.disconnect());

watch(sentinel, (el) => {
  if (el && observer) observer.observe(el);
});

async function reload(): Promise<void> {
  error.value = null;
  try {
    config.value = await loadConfig(props.document.projectId, folder.value);
    sources.value = await listSources(props.document.projectId);
  } catch (e) {
    error.value = String(e);
    return;
  }
  await restart();
}

/**
 * Re-read the source list, optionally forcing the server past its cache. The
 * force path exists because "no sources" and "your settings have not landed
 * yet" look identical from here.
 */
async function reloadSources(force = false): Promise<void> {
  error.value = null;
  try {
    sources.value = await listSources(props.document.projectId, force);
  } catch (e) {
    error.value = String(e);
  }
}

/** Back to the top of the stream — after a configuration change or a refresh. */
async function restart(): Promise<void> {
  items.value = [];
  notes.value = [];
  cursor.value = null;
  hasMore.value = true;
  await nextPage();
}

/**
 * How many pages that delivered nothing we keep pulling before waiting for the
 * reader.
 *
 * A page can legitimately come back empty with `hasMore` — the filter rejected
 * everything this round — and the cursor still moved, so asking again is
 * progress. But it appends no cards, so the sentinel never changes position and
 * the observer never fires again: without pulling on our own the scroll would
 * dead-end silently. Bounded rather than unbounded so a very selective filter
 * cannot turn one scroll gesture into an unlimited number of requests; past the
 * bound the „Load more" button takes over.
 */
const MAX_EMPTY_ROUNDS = 5;

async function nextPage(emptyRounds = 0): Promise<void> {
  if (loading.value || !hasMore.value) return;
  if (configuredStreams.value.length === 0) {
    hasMore.value = false;
    return;
  }
  loading.value = true;
  try {
    const page = await loadPage(props.document.projectId, {
      folder: folder.value,
      streams: [],
      // Facets only: the stored text/language filter is configuration, and a
      // page request must not quietly rewrite it. The server lays this over
      // what is stored.
      filter: {
        text: undefined,
        languages: [],
        include: [],
        exclude: [],
        since: undefined,
        facets: facetSelection.value,
      },
      pageSize: config.value?.pageSize ?? 20,
      cursor: cursor.value ?? undefined,
      direction: 'older',
    });
    items.value = [...items.value, ...page.items];
    notes.value = page.notes;
    cursor.value = page.nextCursor ?? null;
    // An empty page with hasMore is normal — it means the filter rejected
    // everything this round. Stopping here would cut the scroll short.
    hasMore.value = page.hasMore;
    if (page.items.length === 0 && page.hasMore && emptyRounds < MAX_EMPTY_ROUNDS) {
      // Nothing was appended, so nothing on screen moved and the observer will
      // not fire again by itself. Carry on for the reader.
      loading.value = false;
      await nextPage(emptyRounds + 1);
      return;
    }
  } catch (e) {
    error.value = String(e);
    hasMore.value = false;
  } finally {
    loading.value = false;
  }
}

async function clip(item: FeedItemView): Promise<void> {
  const target = `${folder.value}/clips/${slug(item.title)}`;
  try {
    const result = await clipItem(props.document.projectId, {
      targetPath: target,
      title: item.title,
      url: item.url,
      publishedAt: item.publishedAt,
      summary: item.summary,
      body: undefined,
      author: item.author,
      language: item.language,
      sourceId: item.sourceId,
    });
    clipped.value = { ...clipped.value, [item.id]: result.path };
  } catch (e) {
    error.value = String(e);
  }
}

/** Which signals this entry's source declared. Empty = the buttons stay hidden. */
function signalsFor(sourceId: string): string[] {
  return sources.value.find((s) => s.id === sourceId)?.capabilities?.signalsAccepted ?? [];
}

function openReport(item: FeedItemView): void {
  report.value = { item, reason: REPORT_REASONS[0].value, note: '' };
  reportOpen.value = true;
}

async function submitReport(): Promise<void> {
  const pending = report.value;
  if (!pending) return;
  try {
    const result = await sendSignal(props.document.projectId, {
      sourceId: pending.item.sourceId,
      itemId: pending.item.id,
      signal: 'REPORT',
      reason: pending.reason,
      note: pending.note.trim() ? pending.note.trim() : undefined,
      requestKind: undefined,
    });
    // "reported", never "fixed": what the source does with it is its business.
    signalled.value = { ...signalled.value, [pending.item.id]: outcomeText(result.outcome) };
    reportOpen.value = false;
  } catch (e) {
    error.value = String(e);
  }
}

async function requestKind(item: FeedItemView, kind: string): Promise<void> {
  try {
    const result = await sendSignal(props.document.projectId, {
      sourceId: item.sourceId,
      itemId: item.id,
      signal: 'REQUEST',
      requestKind: kind,
      reason: undefined,
      note: undefined,
    });
    signalled.value = { ...signalled.value, [item.id]: outcomeText(result.outcome) };
  } catch (e) {
    error.value = String(e);
  }
}

function outcomeText(outcome: string): string {
  switch (outcome) {
    case 'ACCEPTED':
      return 'reported';
    case 'UNSUPPORTED':
      return 'source does not accept this';
    default:
      return 'source declined';
  }
}

/** Display name of the source a note would travel to — the reader should know. */
function sourceName(sourceId: string): string {
  return sources.value.find((s) => s.id === sourceId)?.displayName ?? sourceId;
}

async function persist(): Promise<void> {
  if (!config.value) return;
  try {
    config.value = await saveConfig(props.document.projectId, folder.value, config.value);
    tab.value = 'stream';
    await restart();
  } catch (e) {
    error.value = String(e);
  }
}

function addStream(): void {
  if (!config.value) return;
  const first = sources.value[0];
  const sourceId = first?.id ?? '';
  config.value = {
    ...config.value,
    // Not '': the empty string is not one of the offered options, so the select
    // would display the first selector while storing nothing — shown and saved
    // would be different things.
    streams: [...config.value.streams, { source: sourceId, selector: firstSelector(sourceId) }],
  };
}

/** The selector a source should start on — its first, or '' when it has none. */
function firstSelector(sourceId: string): string {
  return sources.value.find((s) => s.id === sourceId)?.selectors?.[0]?.value ?? '';
}

/**
 * Switching the source invalidates the selector: `m4.5` means nothing to a
 * wiki. Reset rather than carry it over.
 */
function changeSource(stream: { source: string; selector?: string }, sourceId: string): void {
  stream.source = sourceId;
  stream.selector = firstSelector(sourceId);
}

function removeStream(index: number): void {
  if (!config.value) return;
  const streams = [...config.value.streams];
  streams.splice(index, 1);
  config.value = { ...config.value, streams };
}

function selectorsFor(sourceId: string): { value: string; label: string }[] {
  const source = sources.value.find((s) => s.id === sourceId);
  return (source?.selectors ?? []).map((s) => ({ value: s.value, label: s.label }));
}

/** Free-form sources have no list to offer, so the form shows a text field. */
function isFreeform(sourceId: string): boolean {
  const source = sources.value.find((s) => s.id === sourceId);
  return source?.capabilities?.selectorMode === 'FREEFORM';
}

function noteText(note: FeedNoteView): string {
  const what = `${note.sourceId}${note.selector ? ` · ${note.selector}` : ''}`;
  switch (note.kind) {
    case 'UNKNOWN_SOURCE':
      return `${what}: not configured in this project`;
    case 'DISABLED':
      return `${what}: switched off`;
    case 'COOLING_DOWN':
      return `${what}: paused after an earlier failure`;
    case 'TIMED_OUT':
      return `${what}: did not answer in time`;
    case 'MISSING_FACET':
      // Not a failure: the source was never asked, because it does not offer
      // the dimension that was selected. Saying so beats a quietly shorter
      // timeline.
      return `${what}: not part of this selection${
        note.detail ? ` — offers no ${note.detail}` : ''
      }`;
    default:
      return `${what}: failed${note.detail ? ` — ${note.detail}` : ''}`;
  }
}

function when(iso: string): string {
  const then = new Date(iso).getTime();
  const minutes = Math.round((Date.now() - then) / 60000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  return new Date(iso).toLocaleDateString();
}

function slug(title: string): string {
  return (
    title
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 60) || 'clip'
  );
}
</script>

<template>
  <div class="flex h-full flex-col gap-3 p-3">
    <div class="flex items-center gap-2">
      <VButton :variant="tab === 'stream' ? 'primary' : 'ghost'" @click="tab = 'stream'">
        Stream
      </VButton>
      <VButton :variant="tab === 'config' ? 'primary' : 'ghost'" @click="tab = 'config'">
        Configuration
      </VButton>
      <div class="flex-1"></div>
      <VButton variant="ghost" :disabled="loading" @click="restart()">Refresh</VButton>
    </div>

    <VAlert v-if="error" variant="error">{{ error }}</VAlert>

    <VAlert v-for="note in notes" :key="note.sourceId + note.selector" variant="warning">
      {{ noteText(note) }}
    </VAlert>

    <!-- Facet bar: only what a configured source declares -->
    <div v-if="tab === 'stream' && offeredFacets.length > 0" class="flex flex-wrap items-end gap-2">
      <div v-for="entry in offeredFacets" :key="facetKeyOf(entry.sourceId, entry.facet.key)">
        <VSelect
          :label="`${entry.facet.label} · ${entry.sourceName}`"
          :model-value="selectedFacetValue(entry.facet.key)"
          :options="[
            { value: '', label: 'Any' },
            ...(facetValues[facetKeyOf(entry.sourceId, entry.facet.key)] ?? []).map((v) => ({
              value: v.id,
              label: v.label,
            })),
          ]"
          @focus="ensureFacetValues(entry.sourceId, entry.facet)"
          @update:model-value="(v: string | null) => selectFacet(entry.facet.key, v)"
        />
      </div>
      <VButton
        v-if="Object.keys(facetSelection).length > 0"
        variant="ghost"
        @click="saveFacetsAsFilter()"
      >
        Save as filter
      </VButton>
    </div>

    <!-- Stream -->
    <div v-if="tab === 'stream'" class="flex-1 overflow-y-auto">
      <VEmptyState
        v-if="configuredStreams.length === 0"
        headline="No streams yet"
        body="Add a stream in the configuration tab."
      />
      <VEmptyState
        v-else-if="items.length === 0 && !loading && !hasMore"
        headline="Nothing to read"
        body="The configured streams returned no entries for this filter."
      />

      <div class="flex flex-col gap-3">
        <VCard v-for="item in items" :key="entryKey(item)">
          <div class="flex gap-3">
            <img
              v-if="link(item.imageUrl)"
              :src="link(item.imageUrl)!"
              alt=""
              referrerpolicy="no-referrer"
              class="h-24 w-32 flex-none rounded object-cover"
            />
            <div class="flex min-w-0 flex-1 flex-col gap-1">
              <div class="flex items-center gap-2 text-xs opacity-70">
                <span>{{ item.sourceDisplayName }}</span>
                <span v-if="item.selector">· {{ item.selector }}</span>
                <span>· {{ when(item.publishedAt) }}</span>
                <span v-if="item.language">· {{ item.language }}</span>
              </div>
              <!-- Through link(): `url` is written by the feed source, and a
                   `javascript:` value would run on this origin the moment the
                   headline is clicked. No link is better than that one. -->
              <a
                v-if="link(item.url)"
                :href="link(item.url)!"
                target="_blank"
                rel="noopener noreferrer"
                class="truncate font-semibold hover:underline"
              >
                {{ item.title }}
              </a>
              <span v-else class="truncate font-semibold">{{ item.title }}</span>
              <p v-if="item.summary" class="line-clamp-3 text-sm opacity-80">
                {{ item.summary }}
              </p>
              <div class="mt-1 flex items-center gap-2">
                <VButton
                  size="sm"
                  variant="ghost"
                  :disabled="!!clipped[entryKey(item)]"
                  @click="clip(item)"
                >
                  {{ clipped[entryKey(item)] ? 'Clipped' : 'Clip' }}
                </VButton>
                <VButton
                  v-if="signalsFor(item.sourceId).includes('REPORT') && !signalled[entryKey(item)]"
                  size="sm"
                  variant="ghost"
                  @click="openReport(item)"
                >
                  Report
                </VButton>
                <VButton
                  v-if="signalsFor(item.sourceId).includes('REQUEST') && !signalled[entryKey(item)]"
                  size="sm"
                  variant="ghost"
                  @click="requestKind(item, 'TRANSLATION')"
                >
                  Ask for translation
                </VButton>
                <span v-if="signalled[entryKey(item)]" class="text-xs opacity-70">
                  {{ signalled[entryKey(item)] }}
                </span>
                <a
                  v-if="link(item.controlUrl)"
                  :href="link(item.controlUrl)!"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="text-xs hover:underline"
                >
                  Open in source ↗
                </a>
                <span v-if="clipped[entryKey(item)]" class="text-xs opacity-70">
                  → {{ clipped[entryKey(item)] }}
                </span>
              </div>
            </div>
          </div>
        </VCard>

        <div ref="sentinel" class="h-8"></div>
        <p v-if="loading" class="p-2 text-center text-sm opacity-70">Loading…</p>
        <!-- The observer only fires when the sentinel's visibility changes, and
             a round that appends nothing changes nothing. This is the way
             forward that does not depend on that. -->
        <div v-else-if="hasMore && items.length > 0" class="p-2 text-center">
          <VButton size="sm" variant="ghost" @click="nextPage()">Load more</VButton>
        </div>
        <p
          v-else-if="!hasMore && items.length > 0"
          class="p-2 text-center text-sm opacity-50"
        >
          End of the stream
        </p>
      </div>
    </div>

    <!-- Configuration -->
    <div v-else class="flex-1 overflow-y-auto">
      <div v-if="config" class="flex flex-col gap-4">
        <VCard>
          <h3 class="mb-2 font-semibold">Streams</h3>
          <div v-if="sources.length === 0" class="flex flex-col items-center gap-2">
            <VEmptyState
              headline="No sources configured"
              body="Set centauri.endpoint.&lt;id&gt;.protocol and .baseUrl in the settings first.
                    Already done? Sources are cached for five minutes — reload them."
            />
            <VButton variant="ghost" @click="reloadSources(true)">Reload sources</VButton>
          </div>
          <div v-else class="flex flex-col gap-2">
            <div
              v-for="(stream, index) in config.streams"
              :key="index"
              class="flex items-center gap-2"
            >
              <VSelect
                :model-value="stream.source"
                :options="sources.map((s) => ({ value: s.id, label: s.displayName }))"
                @update:model-value="(v: string | null) => changeSource(stream, v ?? '')"
              />
              <VInput
                v-if="isFreeform(stream.source)"
                :model-value="stream.selector ?? ''"
                placeholder="hashtag:opensource"
                @update:model-value="(v: string) => (stream.selector = v)"
              />
              <VSelect
                v-else
                :model-value="stream.selector ?? ''"
                :options="selectorsFor(stream.source)"
                @update:model-value="(v: string | null) => (stream.selector = v ?? '')"
              />
              <VButton size="sm" variant="ghost" @click="removeStream(index)">Remove</VButton>
            </div>
            <VButton size="sm" variant="ghost" @click="addStream()">Add stream</VButton>
          </div>
        </VCard>

        <VCard>
          <h3 class="mb-2 font-semibold">Filter</h3>
          <div class="flex flex-col gap-2">
            <VInput
              :model-value="config.filter.text ?? ''"
              label="Text"
              placeholder="optional"
              @update:model-value="(v: string) => (config!.filter.text = v)"
            />
            <VInput
              :model-value="config.filter.languages.join(', ')"
              label="Languages"
              placeholder="de, en"
              @update:model-value="
                (v: string) =>
                  (config!.filter.languages = v
                    .split(',')
                    .map((s) => s.trim())
                    .filter(Boolean))
              "
            />
            <VInput
              :model-value="config.filter.exclude.join(', ')"
              label="Exclude keywords"
              @update:model-value="
                (v: string) =>
                  (config!.filter.exclude = v
                    .split(',')
                    .map((s) => s.trim())
                    .filter(Boolean))
              "
            />
            <VInput
              :model-value="config.filter.since ?? ''"
              label="Since"
              placeholder="-7d"
              @update:model-value="(v: string) => (config!.filter.since = v)"
            />
          </div>
        </VCard>

        <div class="flex gap-2">
          <VButton variant="primary" @click="persist()">Save</VButton>
          <VButton variant="ghost" @click="reload()">Discard</VButton>
        </div>
      </div>
    </div>
    <VModal v-model="reportOpen" title="Report this entry">
      <div v-if="report" class="flex flex-col gap-3">
        <p class="text-sm opacity-70">{{ report.item.title }}</p>
        <VSelect
          :model-value="report.reason"
          :options="REPORT_REASONS"
          label="What is wrong"
          @update:model-value="(v: string | null) => (report!.reason = v ?? 'SPAM')"
        />
        <VTextarea
          :model-value="report.note"
          :rows="3"
          label="Note (optional)"
          @update:model-value="(v: string) => (report!.note = v)"
        />
        <!-- The reader is looking at a form in their own workspace and has no
             reason to suspect the text leaves the house. So it says so. -->
        <p class="text-xs opacity-60">
          This text is sent to <strong>{{ sourceName(report.item.sourceId) }}</strong>.
          The source decides what happens with a report — we can only tell you it
          was delivered.
        </p>
      </div>
      <template #actions>
        <VButton variant="ghost" @click="reportOpen = false">Cancel</VButton>
        <VButton variant="primary" @click="submitReport()">Send</VButton>
      </template>
    </VModal>
  </div>
</template>
