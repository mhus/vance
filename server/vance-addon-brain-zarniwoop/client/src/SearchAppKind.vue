<script setup lang="ts">
import { computed, onBeforeUnmount, ref, onMounted } from 'vue';
import { VAlert, VButton, VCard, VEmptyState, VInput, VSelect } from '@vance/components';
import type { ZarniwoopInsightsDto } from '@vance/generated';
import { investigate, listProviders, loadConfig, saveConfig, search, loadContentBlob } from './api';
import type { InvestigateResultView } from './generated/search/InvestigateResultView';
import type { SearchConfigView } from './generated/search/SearchConfigView';
import type { SearchHitView } from './generated/search/SearchHitView';
import type { SearchResultView } from './generated/search/SearchResultView';

/**
 * Mount for an `app: search` manifest — a search surface for people.
 *
 * Three rules shape everything here, and all three are about not lying to the
 * person in front of it:
 *
 *  1. **Only offer what a provider serves.** The modality tabs come from the
 *     provider inventory, so a project without a Serper key has no image tab
 *     rather than an image tab that always fails.
 *  2. **Never spend quota by accident.** Submitting is explicit; there is no
 *     search-as-you-type. `Investigate` is its own button because it costs LLM
 *     tokens on top of quota.
 *  3. **Only offer a full text where one exists.** Each hit says whether its
 *     body is here, fetchable, or absent — the button follows that field instead
 *     of appearing everywhere and failing most places.
 */
const props = defineProps<{
  document: { id?: string; path: string; projectId: string; title?: string | null };
}>();

const folder = computed(() => {
  const p = props.document.path;
  const i = p.lastIndexOf('/');
  return i < 0 ? '' : p.slice(0, i);
});

/** Modalities with a per-modality rendering, in the order the tabs appear. */
const MODALITY_ORDER = [
  'web',
  'news',
  'image',
  'video',
  'pdf',
  'academic',
  'encyclopedia',
  'book',
  'code',
  'internal_doc',
  'map',
  'rag',
] as const;

const MODALITY_LABEL: Record<string, string> = {
  web: 'Web',
  news: 'News',
  image: 'Images',
  video: 'Videos',
  pdf: 'PDFs',
  academic: 'Papers',
  encyclopedia: 'Encyclopedia',
  book: 'Books',
  code: 'Code',
  internal_doc: 'Documents',
  map: 'Maps',
  rag: 'Knowledge base',
};

/** Grid rather than list — these are looked at, not read. */
const GRID_MODALITIES = new Set(['image', 'video', 'book']);

const providers = ref<ZarniwoopInsightsDto[]>([]);
const config = ref<SearchConfigView | null>(null);

const query = ref('');
const modality = ref<string>('web');
const tier = ref<'normal' | 'expert'>('normal');
const pinned = ref<string>('');
const num = ref(10);

const result = ref<SearchResultView | null>(null);
const curated = ref<InvestigateResultView | null>(null);
const selected = ref<SearchHitView | null>(null);
const fullText = ref<string | null>(null);
const fullTextUrl = ref<string | null>(null);
const loadingBody = ref(false);

const loading = ref(false);
const error = ref<string | null>(null);
const configTab = ref(false);

/**
 * Modalities at least one usable endpoint serves.
 *
 * A `READY` filter on purpose: an endpoint sitting in a cooldown or switched off
 * cannot answer, and offering its modality would produce a tab that fails for a
 * reason the person cannot see from here. The provider panel says why.
 */
const available = computed<string[]>(() => {
  const set = new Set<string>();
  for (const p of providers.value) {
    if (p.availability !== 'READY') continue;
    for (const m of p.modalities ?? []) set.add(m.toLowerCase());
  }
  return MODALITY_ORDER.filter((m) => set.has(m));
});

/** Endpoints that can serve the current modality — the pin list for expert tier. */
const pinnable = computed(() =>
  providers.value
    .filter((p) => (p.modalities ?? []).some((m) => m.toLowerCase() === modality.value))
    .filter((p) => (p.tiers ?? []).some((t) => t.toUpperCase() === 'EXPERT'))
    .map((p) => ({ value: p.id, label: p.displayName ?? p.id })),
);

const expertPossible = computed(() => pinnable.value.length > 0);

/** What the operator would want to see before spending: quota and trouble. */
const providerLines = computed(() =>
  providers.value.map((p) => ({
    id: p.id,
    label: p.displayName ?? p.id,
    availability: p.availability ?? '',
    status: p.statusText ?? null,
    ready: p.availability === 'READY',
  })),
);

const gridLayout = computed(() => GRID_MODALITIES.has(modality.value));

onMounted(async () => {
  try {
    const [loadedProviders, loadedConfig] = await Promise.all([
      listProviders(props.document.projectId),
      loadConfig(props.document.projectId, folder.value),
    ]);
    providers.value = loadedProviders;
    config.value = loadedConfig;
    num.value = loadedConfig.defaultNum || 10;
    // The manifest default only wins if something actually serves it — otherwise
    // the surface would open on a tab that cannot answer.
    const preferred = loadedConfig.defaultModality?.toLowerCase() ?? 'web';
    modality.value = available.value.includes(preferred)
      ? preferred
      : (available.value[0] ?? 'web');
  } catch (e) {
    error.value = message(e);
  }
});

onBeforeUnmount(() => releaseBody());

async function reloadProviders(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    // Forced: the point of the button is to bypass the five-minute cache.
    providers.value = await listProviders(props.document.projectId, true);
  } catch (e) {
    error.value = message(e);
  } finally {
    loading.value = false;
  }
}

async function submit(): Promise<void> {
  if (!query.value.trim() || loading.value) return;
  loading.value = true;
  error.value = null;
  curated.value = null;
  clearSelection();
  try {
    result.value = await search(props.document.projectId, folder.value, {
      query: query.value.trim(),
      modality: modality.value,
      tier: tier.value,
      num: num.value,
      instance: tier.value === 'expert' && pinned.value ? pinned.value : undefined,
    });
  } catch (e) {
    error.value = message(e);
    result.value = null;
  } finally {
    loading.value = false;
  }
}

/**
 * The curated pipeline. Separate button, separate label, because it spends LLM
 * tokens on top of provider quota and takes seconds.
 */
async function runInvestigate(): Promise<void> {
  if (!query.value.trim() || loading.value) return;
  loading.value = true;
  error.value = null;
  result.value = null;
  clearSelection();
  try {
    curated.value = await investigate(props.document.projectId, query.value.trim());
  } catch (e) {
    error.value = message(e);
  } finally {
    loading.value = false;
  }
}

function selectModality(m: string): void {
  if (modality.value === m) return;
  modality.value = m;
  pinned.value = '';
  if (!expertPossible.value) tier.value = 'normal';
  // Deliberately no automatic re-search: switching a tab would otherwise spend
  // quota on a query the person may be about to change.
  result.value = null;
  clearSelection();
}

function open(hit: SearchHitView): void {
  clearSelection();
  selected.value = hit;
}

/**
 * Fetch the body of the selected hit. Only reachable when the hit said
 * `on-demand` — of the built-in providers none serves this yet, so in practice
 * this is for `ode` sources until a later phase adds Wikipedia and PubMed.
 */
async function loadBody(): Promise<void> {
  const hit = selected.value;
  if (!hit?.contentId || !result.value) return;
  loadingBody.value = true;
  error.value = null;
  try {
    const blob = await loadContentBlob(props.document.projectId, {
      instanceId: result.value.providerInstanceId,
      contentId: hit.contentId,
      mimeType: hit.mimeType ?? undefined,
    });
    if ((hit.mimeType ?? '').startsWith('text/') || !hit.mimeType) {
      fullText.value = await blob.text();
    } else {
      // A PDF or an image: hand it to the browser's own viewer rather than
      // trying to render bytes we do not understand.
      fullTextUrl.value = URL.createObjectURL(blob);
    }
  } catch (e) {
    error.value = message(e);
  } finally {
    loadingBody.value = false;
  }
}

function clearSelection(): void {
  releaseBody();
  selected.value = null;
}

/** An object URL is a live handle; leaving it behind leaks the whole blob. */
function releaseBody(): void {
  if (fullTextUrl.value) URL.revokeObjectURL(fullTextUrl.value);
  fullTextUrl.value = null;
  fullText.value = null;
}

async function persist(): Promise<void> {
  if (!config.value) return;
  loading.value = true;
  error.value = null;
  try {
    config.value = await saveConfig(props.document.projectId, folder.value, {
      ...config.value,
      defaultModality: modality.value,
      defaultNum: num.value,
    });
  } catch (e) {
    error.value = message(e);
  } finally {
    loading.value = false;
  }
}

/** Save the current query so it can be re-run without retyping. */
async function saveCurrent(): Promise<void> {
  if (!config.value || !query.value.trim()) return;
  const name = query.value.trim().slice(0, 40);
  const next: SearchConfigView = {
    ...config.value,
    savedSearches: [
      ...(config.value.savedSearches ?? []).filter((s) => s.name !== name),
      {
        name,
        query: query.value.trim(),
        modality: modality.value,
        tier: tier.value,
        instance: tier.value === 'expert' && pinned.value ? pinned.value : undefined,
      },
    ],
  };
  loading.value = true;
  try {
    config.value = await saveConfig(props.document.projectId, folder.value, next);
  } catch (e) {
    error.value = message(e);
  } finally {
    loading.value = false;
  }
}

async function removeSaved(name: string): Promise<void> {
  if (!config.value) return;
  const next: SearchConfigView = {
    ...config.value,
    savedSearches: (config.value.savedSearches ?? []).filter((s) => s.name !== name),
  };
  loading.value = true;
  try {
    config.value = await saveConfig(props.document.projectId, folder.value, next);
  } catch (e) {
    error.value = message(e);
  } finally {
    loading.value = false;
  }
}

function runSaved(saved: { query: string; modality: string; tier: string; instance?: string }): void {
  query.value = saved.query;
  modality.value = saved.modality;
  tier.value = saved.tier === 'expert' ? 'expert' : 'normal';
  pinned.value = saved.instance ?? '';
  configTab.value = false;
  void submit();
}

// ── per-modality bits ──────────────────────────────────────────────

function thumbnail(hit: SearchHitView): string | null {
  const extras = hit.extras ?? {};
  const thumb = extras.thumbnailUrl ?? extras.coverThumbnailUrl ?? extras.imageUrl;
  return typeof thumb === 'string' ? thumb : null;
}

/** The file for an image hit; `url` is the page it sits on, which is not the same. */
function imageFile(hit: SearchHitView): string | null {
  const raw = (hit.extras ?? {}).imageUrl;
  return typeof raw === 'string' ? raw : null;
}

function extraText(hit: SearchHitView, key: string): string | null {
  const raw = (hit.extras ?? {})[key];
  return raw === undefined || raw === null ? null : String(raw);
}

/** Metadata worth a line under the title, per modality. */
function metaLine(hit: SearchHitView): string {
  const bits: string[] = [];
  const push = (key: string, prefix = '') => {
    const v = extraText(hit, key);
    if (v) bits.push(prefix + v);
  };
  push('authors');
  push('author');
  push('venue');
  push('publicationYear');
  push('citedByCount', 'cited ');
  push('channel');
  push('duration');
  push('points', '▲ ');
  push('comments', '💬 ');
  push('publisher');
  push('firstPublishYear');
  if (hit.source) bits.unshift(hit.source);
  return bits.join(' · ');
}

function message(e: unknown): string {
  if (e && typeof e === 'object' && 'message' in e) return String((e as Error).message);
  return String(e);
}
</script>

<template>
  <div class="flex h-full flex-col gap-3 p-3">
    <!-- Query row -->
    <div class="flex items-center gap-2">
      <VInput
        v-model="query"
        class="flex-1"
        placeholder="What are you looking for?"
        @keyup.enter="submit()"
      />
      <VButton variant="primary" :disabled="loading || !query.trim()" @click="submit()">
        Search
      </VButton>
      <VButton
        variant="ghost"
        :disabled="loading || !query.trim()"
        title="Plans several searches and has a model rank the results — costs tokens as well as quota"
        @click="runInvestigate()"
      >
        Investigate
      </VButton>
      <VButton variant="ghost" @click="configTab = !configTab">
        {{ configTab ? 'Back' : 'Settings' }}
      </VButton>
    </div>

    <VAlert v-if="error" variant="error">{{ error }}</VAlert>

    <!-- Modality tabs: only what a ready endpoint serves -->
    <div v-if="!configTab && available.length > 0" class="flex flex-wrap items-center gap-1">
      <VButton
        v-for="m in available"
        :key="m"
        size="sm"
        :variant="modality === m ? 'primary' : 'ghost'"
        @click="selectModality(m)"
      >
        {{ MODALITY_LABEL[m] ?? m }}
      </VButton>
      <div class="flex-1"></div>
      <VSelect
        v-if="expertPossible"
        :model-value="tier"
        :options="[
          { value: 'normal', label: 'Normal' },
          { value: 'expert', label: 'Expert' },
        ]"
        @update:model-value="(v: string | null) => (tier = v === 'expert' ? 'expert' : 'normal')"
      />
      <VSelect
        v-if="tier === 'expert' && pinnable.length > 0"
        :model-value="pinned"
        :options="[{ value: '', label: 'Any endpoint' }, ...pinnable]"
        @update:model-value="(v: string | null) => (pinned = v ?? '')"
      />
    </div>

    <VEmptyState
      v-if="!configTab && available.length === 0"
      headline="No search provider configured"
      body="Set research.endpoint.&lt;id&gt;.protocol and its key in the settings first.
            Already done? The inventory is cached for five minutes — reload it under Settings."
    />

    <!-- Settings -->
    <div v-if="configTab" class="flex-1 overflow-y-auto">
      <div class="flex flex-col gap-4">
        <VCard>
          <h3 class="mb-2 font-semibold">Providers</h3>
          <div v-if="providerLines.length === 0" class="text-sm opacity-70">
            Nothing configured in this project.
          </div>
          <div v-else class="flex flex-col gap-1">
            <div
              v-for="p in providerLines"
              :key="p.id"
              class="flex items-center gap-2 text-sm"
            >
              <span class="font-mono text-xs opacity-60">{{ p.id }}</span>
              <span class="flex-1 truncate">{{ p.label }}</span>
              <!-- A source that cannot be asked is shown with its reason, not
                   hidden: a missing row reads as "never configured". -->
              <span :class="p.ready ? 'text-xs opacity-60' : 'text-xs text-warning'">
                {{ p.availability }}{{ p.status ? ` · ${p.status}` : '' }}
              </span>
            </div>
          </div>
          <VButton class="mt-2" size="sm" variant="ghost" :disabled="loading"
                   @click="reloadProviders()">
            Reload providers
          </VButton>
        </VCard>

        <VCard v-if="config">
          <h3 class="mb-2 font-semibold">Defaults</h3>
          <div class="flex items-center gap-2">
            <VSelect
              :model-value="modality"
              :options="available.map((m) => ({ value: m, label: MODALITY_LABEL[m] ?? m }))"
              @update:model-value="(v: string | null) => (modality = v ?? 'web')"
            />
            <VInput
              :model-value="String(num)"
              type="number"
              class="w-24"
              @update:model-value="(v: string) => (num = Number(v) || 10)"
            />
            <VButton size="sm" variant="ghost" :disabled="loading" @click="persist()">
              Save defaults
            </VButton>
          </div>
        </VCard>

        <VCard v-if="config">
          <h3 class="mb-2 font-semibold">Saved searches</h3>
          <div v-if="(config.savedSearches ?? []).length === 0" class="text-sm opacity-70">
            None yet — run a search and press “Save search”.
          </div>
          <div v-else class="flex flex-col gap-1">
            <div
              v-for="saved in config.savedSearches"
              :key="saved.name"
              class="flex items-center gap-2 text-sm"
            >
              <span class="flex-1 truncate">{{ saved.name }}</span>
              <span class="text-xs opacity-60">{{ saved.modality }}</span>
              <VButton size="sm" variant="ghost" @click="runSaved(saved)">Run</VButton>
              <VButton size="sm" variant="ghost" @click="removeSaved(saved.name)">
                Remove
              </VButton>
            </div>
          </div>
        </VCard>
      </div>
    </div>

    <!-- Results + detail -->
    <div v-else class="flex min-h-0 flex-1 gap-3">
      <div class="min-w-0 flex-1 overflow-y-auto">
        <p v-if="loading" class="p-2 text-sm opacity-70">Searching…</p>

        <!-- A dispatcher-level refusal is about this tab, not the app -->
        <VAlert v-if="result?.error" variant="warning">{{ result.error }}</VAlert>
        <VAlert v-else-if="result?.note" variant="info">{{ result.note }}</VAlert>

        <VEmptyState
          v-if="!loading && !result && !curated"
          headline="Nothing searched yet"
          body="Type a query and press Search."
        />
        <VEmptyState
          v-else-if="result && result.hits.length === 0 && !result.error"
          headline="Nothing found"
          :body="
            result.droppedCount > 0
              ? `${result.droppedCount} result(s) were withheld by the source.`
              : 'The provider returned no results for this query.'
          "
        />

        <!-- Grid: images, videos, books -->
        <div v-if="result && gridLayout" class="grid grid-cols-2 gap-2 md:grid-cols-3">
          <button
            v-for="(hit, i) in result.hits"
            :key="hit.url + i"
            class="flex flex-col gap-1 rounded p-1 text-left hover:bg-base-200"
            @click="open(hit)"
          >
            <img
              v-if="thumbnail(hit)"
              :src="thumbnail(hit)!"
              alt=""
              loading="lazy"
              referrerpolicy="no-referrer"
              class="h-32 w-full rounded object-cover"
            />
            <div v-else class="h-32 w-full rounded bg-base-200"></div>
            <span class="line-clamp-2 text-xs">{{ hit.title }}</span>
            <span v-if="metaLine(hit)" class="truncate text-xs opacity-60">
              {{ metaLine(hit) }}
            </span>
          </button>
        </div>

        <!-- List: everything else -->
        <div v-else-if="result" class="flex flex-col gap-2">
          <VCard v-for="(hit, i) in result.hits" :key="hit.url + i">
            <div class="flex flex-col gap-1">
              <a
                :href="hit.url"
                target="_blank"
                rel="noopener noreferrer"
                class="font-semibold hover:underline"
              >
                {{ hit.title }}
              </a>
              <span v-if="metaLine(hit)" class="text-xs opacity-60">{{ metaLine(hit) }}</span>
              <p v-if="hit.snippet" class="line-clamp-3 text-sm opacity-80">{{ hit.snippet }}</p>
              <div class="flex items-center gap-2">
                <VButton size="sm" variant="ghost" @click="open(hit)">Details</VButton>
                <span v-if="hit.contentState === 'embedded'" class="text-xs opacity-50">
                  full text included
                </span>
                <span v-else-if="hit.contentState === 'on-demand'" class="text-xs opacity-50">
                  full text on request
                </span>
              </div>
            </div>
          </VCard>
        </div>

        <!-- Curated results -->
        <div v-if="curated" class="flex flex-col gap-2">
          <VAlert v-if="curated.gaps.length > 0" variant="info">
            <!-- What it could NOT answer is the useful part, and a summary
                 would swallow it. -->
            <span class="font-semibold">Gaps:</span> {{ curated.gaps.join(' · ') }}
          </VAlert>
          <p class="text-xs opacity-60">
            {{ curated.hits.length }} kept, {{ curated.droppedCount }} rejected · sources:
            {{ curated.instancesUsed.join(', ') }}
          </p>
          <VCard v-for="(hit, i) in curated.hits" :key="hit.url + i">
            <div class="flex flex-col gap-1">
              <a
                :href="hit.url"
                target="_blank"
                rel="noopener noreferrer"
                class="font-semibold hover:underline"
              >
                {{ hit.title }}
              </a>
              <span class="text-xs opacity-60">
                {{ hit.providerInstanceId }} · score {{ hit.finalScore.toFixed(2) }}
              </span>
              <p v-if="hit.relevanceNote" class="text-sm opacity-80">{{ hit.relevanceNote }}</p>
              <p v-else-if="hit.snippet" class="line-clamp-3 text-sm opacity-80">
                {{ hit.snippet }}
              </p>
            </div>
          </VCard>
        </div>
      </div>

      <!-- Detail panel -->
      <div v-if="selected" class="flex w-96 flex-none flex-col gap-2 overflow-y-auto border-l pl-3">
        <div class="flex items-start gap-2">
          <h3 class="flex-1 font-semibold">{{ selected.title }}</h3>
          <VButton size="sm" variant="ghost" @click="clearSelection()">Close</VButton>
        </div>
        <span v-if="metaLine(selected)" class="text-xs opacity-60">
          {{ metaLine(selected) }}
        </span>

        <img
          v-if="imageFile(selected)"
          :src="imageFile(selected)!"
          alt=""
          referrerpolicy="no-referrer"
          class="max-h-64 w-full rounded object-contain"
        />

        <p v-if="selected.snippet" class="text-sm opacity-80">{{ selected.snippet }}</p>

        <!-- The ladder: here, fetchable, or neither -->
        <p v-if="selected.body" class="whitespace-pre-wrap text-sm">{{ selected.body }}</p>
        <template v-else-if="selected.contentState === 'on-demand'">
          <VButton
            v-if="!fullText && !fullTextUrl"
            size="sm"
            variant="ghost"
            :disabled="loadingBody"
            @click="loadBody()"
          >
            {{ loadingBody ? 'Loading…' : 'Load full text' }}
            <span v-if="selected.sizeBytes" class="opacity-60">
              ({{ Math.round(selected.sizeBytes / 1024) }} kB)
            </span>
          </VButton>
          <p v-if="fullText" class="whitespace-pre-wrap text-sm">{{ fullText }}</p>
          <iframe v-if="fullTextUrl" :src="fullTextUrl" class="h-96 w-full rounded border"></iframe>
        </template>

        <div class="mt-2 flex flex-col gap-1 text-sm">
          <a
            :href="selected.url"
            target="_blank"
            rel="noopener noreferrer"
            class="hover:underline"
          >
            Open source page ↗
          </a>
          <!-- Two different links for an image, on purpose: the page carries the
               context and the attribution, the file is just the pixels. -->
          <a
            v-if="imageFile(selected) && imageFile(selected) !== selected.url"
            :href="imageFile(selected)!"
            target="_blank"
            rel="noopener noreferrer"
            class="hover:underline"
          >
            Open image file ↗
          </a>
          <a
            v-if="extraText(selected, 'videoUrl')"
            :href="extraText(selected, 'videoUrl')!"
            target="_blank"
            rel="noopener noreferrer"
            class="hover:underline"
          >
            Watch video ↗
          </a>
          <a
            v-if="extraText(selected, 'pdfUrl')"
            :href="extraText(selected, 'pdfUrl')!"
            target="_blank"
            rel="noopener noreferrer"
            class="hover:underline"
          >
            Open PDF ↗
          </a>
          <a
            v-if="extraText(selected, 'hnDiscussion')"
            :href="extraText(selected, 'hnDiscussion')!"
            target="_blank"
            rel="noopener noreferrer"
            class="hover:underline"
          >
            Open discussion ↗
          </a>
        </div>

        <VButton
          v-if="query.trim()"
          class="mt-2"
          size="sm"
          variant="ghost"
          :disabled="loading"
          @click="saveCurrent()"
        >
          Save search
        </VButton>
      </div>
    </div>
  </div>
</template>
