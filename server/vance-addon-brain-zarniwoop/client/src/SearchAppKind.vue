<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  VAlert, VBadge, VButton, VCard, VEmptyState, VInput, VSelect, VToggle,
} from '@vance/components';
import type {
  FacetInsightsDto, FacetValueInsightsDto, ZarniwoopInsightsDto,
} from '@vance/generated';
import { investigate, listProviders, loadConfig, saveConfig, search, loadContentBlob } from './api';
import HitPicture from './HitPicture.vue';
import SearchHitDetail from './SearchHitDetail.vue';
import { link, metaLine } from './hitView';
import type { InvestigateResultView } from './generated/search/InvestigateResultView';
import type { SearchConfigView } from './generated/search/SearchConfigView';
// The state values come from the record that declares them — one authority
// for what `contentState` can say, on both sides of the wire.
import {
  CONTENT_EMBEDDED,
  CONTENT_ON_DEMAND,
  type SearchHitView,
} from './generated/search/SearchHitView';
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

const providers = ref<ZarniwoopInsightsDto[]>([]);
const config = ref<SearchConfigView | null>(null);

const query = ref('');
const modality = ref<string>('web');
const tier = ref<'normal' | 'expert'>('normal');
const pinned = ref<string>('');
const num = ref(10);

/**
 * The last result per modality.
 *
 * <p>A tab switch used to throw the results away, and that is expensive in a
 * surface where every query costs provider quota and an `investigate` costs
 * tokens on top. Looking at what the news tab found and then glancing at the
 * papers tab should not mean paying for the news again.
 */
const resultsByModality = ref<Record<string, SearchResultView | null>>({});
const result = ref<SearchResultView | null>(null);
const curated = ref<InvestigateResultView | null>(null);
/** Which query the curated block belongs to — it outlives the search box now. */
const curatedQuery = ref<string>('');
const selected = ref<SearchHitView | null>(null);
const selectedKey = ref<string | null>(null);
const fullText = ref<string | null>(null);
const fullTextUrl = ref<string | null>(null);

/**
 * Types that may be handed to the browser's own viewer in an iframe.
 *
 * Mirrors the server's allow-list, and both are allow-lists for the same reason:
 * `image/svg+xml` is an image that carries script, so „is it an image" is not the
 * question. Text is handled separately, as text.
 */
const INLINE_VIEWABLE = new Set([
  'application/pdf',
  'image/png',
  'image/jpeg',
  'image/gif',
  'image/webp',
]);
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

/**
 * Endpoints that can serve the current modality — the pin list for expert tier.
 *
 * `READY` first, like `available` and `offeredFacets`. Without it a source in
 * cooldown still offered its expert switch and its pin entry, and pinning it
 * produced a guaranteed empty result: `resolveProviders` drops an unusable
 * instance, so the tab that had just returned hits answered „no provider
 * instance available". Optional must never mean unreliable.
 */
const pinnable = computed(() =>
  providers.value
    .filter((p) => p.availability === 'READY')
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

/**
 * The reader's facet selection, transient like the query.
 *
 * <p>Not stored in the manifest: a saved search is a query, and a filter that
 * outlives the session it was set in produces empty result lists whose cause
 * is two visits ago.
 */
const facetSelection = ref<Record<string, string[]>>({});

/**
 * Facets offered for the current modality: those declared by a READY endpoint
 * that can serve it.
 *
 * <p>Per endpoint, because a facet key is only as shared as its value system —
 * two endpoints may both declare `subject-topic` over different vocabularies,
 * and merging their values would offer one that only one of them answers. And
 * capability-gated like the modality tabs above: selecting a facet no endpoint
 * declares would leave the search with no provider at all.
 */
const offeredFacets = computed(() => {
  const out: { instanceId: string; instanceName: string; facet: FacetInsightsDto }[] = [];
  for (const p of providers.value) {
    if (p.availability !== 'READY') continue;
    if (!(p.modalities ?? []).some((m) => m.toLowerCase() === modality.value)) continue;
    for (const facet of p.facets ?? []) {
      out.push({ instanceId: p.id, instanceName: p.displayName ?? p.id, facet });
    }
  }
  return out;
});

function selectedFacet(key: string): string {
  return facetSelection.value[key]?.[0] ?? '';
}

/** Setting a facet does not search — spending is an explicit act here. */
function selectFacet(key: string, value: string | null): void {
  const next = { ...facetSelection.value };
  if (value) next[key] = [value];
  else delete next[key];
  facetSelection.value = next;
}

/**
 * Folded away by default.
 *
 * <p>A single source can declare several facets — a news archive offers
 * place, topic and its own curation decision — and unfolded they cost four
 * rows above every result list, for a narrowing most searches never ask
 * for. Whether they are offered at all is the endpoint's business; whether
 * they are in the way is the reader's.
 */
const facetsOpen = ref(false);

/**
 * How many filters are set. This is the reason the fold is safe: a
 * selected facet takes every endpoint that does not offer it out of the
 * search, and hidden behind a collapsed block that would read as a
 * provider having gone missing.
 */
const activeFacetCount = computed(
  () => Object.values(facetSelection.value).filter((v) => v.length > 0).length,
);

function clearFacets(): void {
  facetSelection.value = {};
}

/**
 * Drop selections the new modality does not offer.
 *
 * <p>The selection is sent with every search and the dispatcher skips any
 * provider that has not declared a selected key — so an `origin-place` carried
 * from the news tab into Images, where no endpoint declares it, takes every
 * provider out and the search comes back empty. That state was also
 * unreachable: with nothing offered, both the fold and the count badge are
 * hidden, so the filter could be neither seen nor cleared.
 *
 * <p>Pruned rather than cleared, because a facet both modalities offer is one
 * the reader still means.
 */
function pruneFacetsToModality(): void {
  const offered = new Set(offeredFacets.value.map((e) => e.facet.key));
  const next: Record<string, string[]> = {};
  for (const [key, values] of Object.entries(facetSelection.value)) {
    if (offered.has(key)) next[key] = values;
  }
  facetSelection.value = next;
}

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
  clearSelection();
  try {
    const answer = await search(props.document.projectId, folder.value, {
      query: query.value.trim(),
      modality: modality.value,
      tier: tier.value,
      num: num.value,
      instance: tier.value === 'expert' && pinned.value ? pinned.value : undefined,
      facets: facetSelection.value,
    });
    result.value = answer;
    resultsByModality.value = { ...resultsByModality.value, [modality.value]: answer };
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
  clearSelection();
  try {
    // The plain results stay: an investigate is the expensive one, and losing
    // the cheap list to it helps nobody.
    curatedQuery.value = query.value.trim();
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
  pruneFacetsToModality();
  // Still no automatic re-search — switching a tab would spend quota on a
  // query the person may be about to change. But what this tab found before
  // comes back instead of being dropped: paying twice for the same answer is
  // the worse of the two.
  result.value = resultsByModality.value[m] ?? null;
  clearSelection();
}

/** The identity of one hit on this screen — a search has no ids, so the URL is it. */
function hitKey(hit: SearchHitView, index: number): string {
  return `${hit.url}\u0000${index}`;
}

function isOpen(hit: SearchHitView, index: number): boolean {
  return selectedKey.value === hitKey(hit, index);
}

/**
 * Open a hit in place, or close it again.
 *
 * <p>In the card rather than in a panel at the side: a detail beside the list
 * puts what you are reading and what you were reading in two different places,
 * and on a narrow window the panel takes the list with it. The feed answers
 * the same question the same way, and two surfaces that behave alike are one
 * thing to learn.
 */
function toggleOpen(hit: SearchHitView, index: number): void {
  const key = hitKey(hit, index);
  if (selectedKey.value === key) {
    clearSelection();
    return;
  }
  releaseBody();
  selectedKey.value = key;
  selected.value = hit;
}

/**
 * The opened hit, told to the chat as this app tab's selection.
 *
 * <p>Same channel the feed uses (`ProcessSteerRequest.activeApp.selection`) and
 * for the same reason: a client tool would block the brain's sampling loop on
 * this browser and cost a slot in the per-call tool budget, for something that
 * belongs in every turn anyway.
 *
 * <p>What travels is the title and the <b>URL</b>, and the URL is the point.
 * A search is stateless — a hit has no id on our side to fetch it back by, the
 * way a feed entry has. Its address is the address, and the model already has
 * `web_fetch` for those.
 */
const reportAppSelection = inject<
  ((sel: {
    appDocId: string;
    selection: string;
    ref?: { label: string; vanceUri?: string; url?: string } | null;
  } | null) => void) | null
>('vance:report-app-selection', null);

watch(selected, (hit) => {
  if (!reportAppSelection) return;
  const appId = props.document.id;
  if (!appId || !hit) {
    reportAppSelection(null);
    return;
  }
  reportAppSelection({
    appDocId: appId,
    selection: `${hit.title} — ${hit.url}`,
    // The durable half, persisted on the message the reader sends. No
    // `vanceUri`: for the same reason the prompt line above carries the URL,
    // a hit has no handle on this side — there is no place to come back to,
    // only the address it always was.
    ref: { label: hit.title, url: hit.url },
  });
});

onBeforeUnmount(() => reportAppSelection?.(null));

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
    // Branch on what the RESPONSE says, never on hit.mimeType.
    //
    // Both come from the searched service, but only one of them describes the
    // bytes in hand — and the server clamps that one to a small set of types it
    // is willing to have rendered here. Trusting the declaration in the search
    // result instead meant a hit could announce `application/pdf`, answer with
    // HTML, and get rendered from a blob URL, which inherits this origin: script
    // in the page, next to the session that fetched it.
    const type = (blob.type || '').split(';')[0].trim().toLowerCase();
    if (!type || type.startsWith('text/')) {
      fullText.value = await blob.text();
    } else if (INLINE_VIEWABLE.has(type)) {
      // A PDF or an image: hand it to the browser's own viewer rather than
      // trying to render bytes we do not understand.
      fullTextUrl.value = URL.createObjectURL(blob);
    } else {
      // Anything the server would not vouch for arrives as octet-stream. There
      // is nothing safe to show, so say so instead of guessing.
      error.value =
        'This source returned a body in a format that cannot be displayed here. ' +
        'Use the source link to open it.';
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
  selectedKey.value = null;
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
      <VButton
        v-if="!configTab && query.trim()"
        variant="ghost"
        :disabled="loading"
        title="Keep this query, modality and tier under Settings"
        @click="saveCurrent()"
      >
        Save search
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
      <!--
        A lever, not a dropdown of two. „Normal / Expert" as a select reads as
        a setting with a value to pick; it is a mode you switch on, and it cost
        a row of its own to say so. The endpoint pin only appears once the mode
        is on — it means nothing without it.
      -->
      <!--
        Beside Expert, and for the same reason: both are modes you switch on,
        and both would otherwise cost a row of their own above every result
        list. The count travels with the lever because a folded-away filter
        still narrows — an endpoint that does not offer a selected facet drops
        out of the search, which reads as a provider having gone missing.
      -->
      <VBadge v-if="offeredFacets.length > 0 && activeFacetCount > 0"
              variant="primary" size="sm">
        {{ activeFacetCount }}
      </VBadge>
      <VToggle
        v-if="offeredFacets.length > 0"
        :model-value="facetsOpen"
        title="Filters declared by the endpoints serving this modality"
        label="Filters"
        @update:model-value="(v: boolean) => (facetsOpen = v)"
      />
      <VToggle
        v-if="expertPossible"
        :model-value="tier === 'expert'"
        title="Expert tier — pin an endpoint and pass filters"
        label="Expert"
        @update:model-value="(v: boolean) => (tier = v ? 'expert' : 'normal')"
      />
      <VSelect
        v-if="tier === 'expert' && pinnable.length > 0"
        :model-value="pinned"
        :options="[{ value: '', label: 'Any endpoint' }, ...pinnable]"
        @update:model-value="(v: string | null) => (pinned = v ?? '')"
      />
    </div>

    <!-- Facets: only what a ready endpoint for this modality declares, and
         only while the Filters lever in the tab row is on -->
    <div
      v-if="!configTab && facetsOpen && offeredFacets.length > 0"
      class="flex flex-wrap items-end gap-2"
    >
      <!-- Bounded width on a wrapper, not on the field: VSelect is w-full by
           design, so three of them in a wrap container would be three rows. -->
      <div
        v-for="entry in offeredFacets"
        :key="entry.instanceId + '/' + entry.facet.key"
        class="w-64 max-w-full"
      >
        <VSelect
          :label="`${entry.facet.label} · ${entry.instanceName}`"
          :model-value="selectedFacet(entry.facet.key)"
          :options="[
            { value: '', label: 'Any' },
            ...(entry.facet.values ?? []).map((v: FacetValueInsightsDto) => ({
              value: v.id,
              label: v.label,
            })),
          ]"
          @update:model-value="(v: string | null) => selectFacet(entry.facet.key, v)"
        />
      </div>
      <VButton
        v-if="activeFacetCount > 0"
        class="mb-1"
        size="sm"
        variant="ghost"
        @click="clearFacets()"
      >
        Clear
      </VButton>
      <span class="pb-2 text-xs opacity-60">
        Applies to the next search — an endpoint that does not offer a selected
        filter is skipped.
      </span>
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

    <!-- Results. A hit opens in place — the detail belongs to the result, not
         to a panel beside it. Same column width as the feed: a line of prose
         that runs the full width of a desktop window is not read, it is
         scanned, and these are results to read. -->
    <div v-else class="min-h-0 flex-1 overflow-y-auto">
      <div class="mx-auto flex w-full max-w-3xl flex-col gap-2">
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

        <!-- One list for every modality. A picture result is still a result:
             stacked it keeps its title and source next to it, and a grid of
             thumbnails made the opened one speak from somewhere else entirely.
             Compact until opened, then it grows — see toggleOpen. -->
        <div v-if="result" class="flex flex-col gap-2">
          <VCard
            v-for="(hit, i) in result.hits"
            :key="hitKey(hit, i)"
            :class="[
              'cursor-pointer transition-all',
              isOpen(hit, i)
                ? 'ring-2 ring-primary shadow-lg'
                : 'hover:ring-1 hover:ring-base-300',
            ]"
            @click="toggleOpen(hit, i)"
          >
            <div class="flex flex-col gap-1">
              <div class="flex gap-3">
                <!-- Rides alongside the headline while the card is closed;
                     opening it shows the picture at its own size. -->
                <HitPicture v-if="!isOpen(hit, i)" :hit="hit" />
                <div class="flex min-w-0 flex-1 flex-col gap-1">
                  <!-- Through link(): the URL comes from a foreign provider, and
                       a `javascript:` value would run on this origin. -->
                  <a
                    v-if="link(hit.url)"
                    :href="link(hit.url)!"
                    target="_blank"
                    rel="noopener noreferrer"
                    class="font-semibold hover:underline"
                    @click.stop
                  >
                    {{ hit.title }}
                  </a>
                  <span v-else class="font-semibold">{{ hit.title }}</span>
                  <span v-if="metaLine(hit)" class="text-xs opacity-60">{{ metaLine(hit) }}</span>
                  <p
                    v-if="hit.snippet"
                    :class="isOpen(hit, i) ? 'text-sm opacity-80' : 'line-clamp-2 text-sm opacity-80'"
                  >
                    {{ hit.snippet }}
                  </p>
                  <span v-if="!isOpen(hit, i) && hit.contentState === CONTENT_EMBEDDED"
                        class="text-xs opacity-50">full text included</span>
                  <span v-else-if="!isOpen(hit, i) && hit.contentState === CONTENT_ON_DEMAND"
                        class="text-xs opacity-50">full text on request</span>
                </div>
              </div>

              <SearchHitDetail
                v-if="isOpen(hit, i)"
                :hit="hit"
                :full-text="fullText"
                :full-text-url="fullTextUrl"
                :loading-body="loadingBody"
                @load="loadBody()"
              />
            </div>
          </VCard>
        </div>

        <!-- Curated results -->
        <div v-if="curated" class="flex flex-col gap-2">
          <!-- The question this block answers, because it outlives the search
               box: an investigate stays on screen while the query is retyped,
               and without the line the reader is looking at an answer to
               something else. -->
          <p v-if="curatedQuery" class="text-sm font-semibold">
            Curated answer for: {{ curatedQuery }}
          </p>
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
                v-if="link(hit.url)"
                :href="link(hit.url)!"
                target="_blank"
                rel="noopener noreferrer"
                class="font-semibold hover:underline"
              >
                {{ hit.title }}
              </a>
              <span v-else class="font-semibold">{{ hit.title }}</span>
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
    </div>
  </div>
</template>
