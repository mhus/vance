<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { VAlert, VButton, VCard, VEmptyState, VInput, VSelect } from '@vance/components';
import { clipItem, listSources, loadConfig, loadPage, saveConfig } from './api';
import type { FeedConfigView } from './generated/centauri/FeedConfigView';
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
const clipped = ref<Record<string, string>>({});

const sentinel = ref<HTMLElement | null>(null);
let observer: IntersectionObserver | null = null;

const configuredStreams = computed(() => config.value?.streams ?? []);

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

/** Back to the top of the stream — after a configuration change or a refresh. */
async function restart(): Promise<void> {
  items.value = [];
  notes.value = [];
  cursor.value = null;
  hasMore.value = true;
  await nextPage();
}

async function nextPage(): Promise<void> {
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
      filter: undefined,
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
  config.value = {
    ...config.value,
    streams: [...config.value.streams, { source: first?.id ?? '', selector: '' }],
  };
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
        <VCard v-for="item in items" :key="item.sourceId + item.id">
          <div class="flex gap-3">
            <img
              v-if="item.imageUrl"
              :src="item.imageUrl"
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
              <a
                :href="item.url"
                target="_blank"
                rel="noopener noreferrer"
                class="truncate font-semibold hover:underline"
              >
                {{ item.title }}
              </a>
              <p v-if="item.summary" class="line-clamp-3 text-sm opacity-80">
                {{ item.summary }}
              </p>
              <div class="mt-1 flex items-center gap-2">
                <VButton
                  size="sm"
                  variant="ghost"
                  :disabled="!!clipped[item.id]"
                  @click="clip(item)"
                >
                  {{ clipped[item.id] ? 'Clipped' : 'Clip' }}
                </VButton>
                <a
                  v-if="item.controlUrl"
                  :href="item.controlUrl"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="text-xs hover:underline"
                >
                  Open in source ↗
                </a>
                <span v-if="clipped[item.id]" class="text-xs opacity-70">
                  → {{ clipped[item.id] }}
                </span>
              </div>
            </div>
          </div>
        </VCard>

        <div ref="sentinel" class="h-8"></div>
        <p v-if="loading" class="p-2 text-center text-sm opacity-70">Loading…</p>
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
          <VEmptyState
            v-if="sources.length === 0"
            headline="No sources configured"
            body="Set centauri.endpoint.&lt;id&gt;.protocol and .baseUrl in the settings first."
          />
          <div v-else class="flex flex-col gap-2">
            <div
              v-for="(stream, index) in config.streams"
              :key="index"
              class="flex items-center gap-2"
            >
              <VSelect
                :model-value="stream.source"
                :options="sources.map((s) => ({ value: s.id, label: s.displayName }))"
                @update:model-value="(v: string | null) => (stream.source = v ?? '')"
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
  </div>
</template>
