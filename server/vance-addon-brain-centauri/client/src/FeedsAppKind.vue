<script setup lang="ts">
import { computed, inject, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  VAlert, VButton, VCard, VCheckbox, VEmptyState, VInput, VModal, VSelect, VShareButton,
  VTextarea, VToggle, useAppEntry, useLocale, vanceRef,
} from '@vance/components';
import { RestError, safeUrl } from '@vance/shared';
import {
  clipItem, listSources, loadConfig, loadFacetValues, loadItem, loadPage, saveConfig, sendSignal,
} from './api';
import type { FeedConfigView } from './generated/centauri/FeedConfigView';
import type { FeedFacetValueView } from './generated/centauri/FeedFacetValueView';
import type { FeedFacetView } from './generated/centauri/FeedFacetView';
import type { FeedItemView } from './generated/centauri/FeedItemView';
import type { FeedNoteView } from './generated/centauri/FeedNoteView';
import type { FeedSourceView } from './generated/centauri/FeedSourceView';
import { useT } from './i18n';

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
const t = useT();
const locale = useLocale();

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
 * The address of one entry as it travels outside this component: in a
 * `vance:…?entry=` link, in the persisted selection reference, and as the
 * `data-entry` attribute a jump scrolls to.
 *
 * <p>Deliberately not {@link entryKey}: that one joins with a NUL byte,
 * which is fine for a Map key and impossible in a URL or a CSS selector.
 * The slash form is also exactly what `feed_item(sourceId, itemId)` takes,
 * so the address a reader clicks and the one the model reads are one string.
 */
function handleOf(item: FeedItemView): string {
  return `${item.sourceId}/${item.id}`;
}

/**
 * The marked entry, or none.
 *
 * <p>One at a time: the mark is „what I am looking at", and a set of them
 * would need a second gesture to say which one the detail belongs to. A
 * second click on the same card clears it.
 */
const marked = ref<string | null>(null);

/**
 * The marked entry, told to the chat as this app tab's selection.
 *
 * <p>Not a client tool, and the difference matters. A tool would block the
 * brain's sampling loop until this browser answers — up to its timeout if the
 * tab is asleep — and would cost a slot in the per-call tool budget for
 * something that belongs in every turn anyway. The same reasoning already
 * moved the text selection off the client-tool channel; this rides the same
 * per-turn hint (`ProcessSteerRequest.activeApp.selection`), which the app's
 * own `promptInject` phrases.
 *
 * <p>What travels is the identity, not the entry: source and id, so the model
 * can name what the reader is looking at and fetch the rest with `feed_item`
 * when it actually needs the text.
 *
 * <p>`ref` is the durable half of the same thing. `selection` is a phrase for
 * this turn and is forgotten with it; `ref` is persisted on the message the
 * reader sends, so "the entry I marked" still has a referent tomorrow, when
 * the entry itself has long scrolled out of the stream. Both addresses go
 * along because they die differently: the `vance:` one stops resolving when
 * the source can no longer serve the item, the article URL when the publisher
 * moves it — and the label outlives both.
 */
const reportAppSelection = inject<
  ((sel: {
    appDocId: string;
    selection: string;
    ref?: { label: string; vanceUri?: string; url?: string } | null;
  } | null) => void) | null
>('vance:report-app-selection', null);

watch(marked, (key) => {
  if (!reportAppSelection) return;
  const appId = props.document.id;
  if (!appId || !key) {
    reportAppSelection(null);
    return;
  }
  const item = items.value.find((i) => entryKey(i) === key);
  if (!item) {
    reportAppSelection(null);
    return;
  }
  // Title included: the id alone would make the prompt line unreadable, and
  // the model would have to spend a tool call to learn what it is looking at.
  reportAppSelection({
    appDocId: appId,
    selection: `${item.sourceId}/${item.id} — ${item.title}`,
    ref: {
      label: item.title,
      // The handle is `<sourceId>/<itemId>` — the same pair `feed_item` takes,
      // so the address the reader can click and the address the model reads
      // are one string, not two conventions.
      vanceUri: vanceRef({ path: `${folder.value}/_app.yaml`, entry: handleOf(item) }),
      url: safeUrl(item.url) ?? undefined,
    },
  });
});

onBeforeUnmount(() => reportAppSelection?.(null));

/**
 * The place open inside this app tab — the other half of the reference the
 * chat persists. A link that carries `?entry=<sourceId>/<itemId>` marks that
 * entry and scrolls to it; marking one by hand writes it back, so the address
 * bar keeps the position across F5 and a shared link reproduces it.
 *
 * <p><b>Late binding.</b> The entry a link names may not be on this page:
 * the stream has moved on, or the source no longer serves it. Then the app
 * opens and stays where it is — never an error, never an empty screen. That
 * is what makes it safe to hand such a link out at all.
 */
const appEntry = useAppEntry(() => props.document.id);

watch([() => appEntry.entry.value, items], () => {
  const want = appEntry.entry.value;
  if (!want) return;
  const item = items.value.find((i) => handleOf(i) === want);
  if (!item || marked.value === entryKey(item)) return;
  void toggleMark(item);
  void nextTick(() => {
    const sel = typeof CSS !== 'undefined' && CSS.escape ? CSS.escape(want) : want;
    document
      .querySelector(`[data-entry="${sel}"]`)
      ?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  });
}, { immediate: true });

// The mark IS this app's sub-position, so it is what the host records.
// `replace`: marking is a gesture, and one history entry per click would
// turn the back button into an undo-my-reading-log.
watch(marked, (key) => {
  const item = key ? items.value.find((i) => entryKey(i) === key) : null;
  appEntry.report(item ? handleOf(item) : null, 'replace');
});

/**
 * The full entry per card, once fetched.
 *
 * <p>A page entry is a teaser — what is cheap to produce twenty times. The
 * detail is one lookup and carries the body plus whatever the source puts in
 * `extras`. Cached per entry: marking, unmarking and marking again is a
 * gesture, not a reason to ask the source twice.
 */
const details = ref<Record<string, FeedItemView>>({});
const detailLoading = ref<string | null>(null);

function isMarked(item: FeedItemView): boolean {
  return marked.value === entryKey(item);
}

/** What to render for a card: the detail when we have it, the teaser until then. */
function shown(item: FeedItemView): FeedItemView {
  return details.value[entryKey(item)] ?? item;
}

/**
 * What sharing an entry hands over. The summary the source shipped, else the
 * body it shipped instead — whichever exists is the quote; nothing is fetched
 * for this. Everything after that is Milliways logic.
 */
function shareSubject(item: FeedItemView) {
  const it = shown(item);
  return { title: it.title, link: it.url, snippet: it.summary ?? it.body ?? undefined };
}

async function toggleMark(item: FeedItemView): Promise<void> {
  const key = entryKey(item);
  if (marked.value === key) {
    marked.value = null;
    return;
  }
  marked.value = key;
  if (details.value[key] || detailLoading.value === key) return;
  // `carriesFullBody` means „a list entry is everything there is". Asking such
  // a source for the entry again is a round trip that can only 404, and it
  // lands in `vance.centauri.item{outcome=unknown}` — the metric that is
  // supposed to count entries that fell out of the stream, drowned out by
  // sources that never had a body to fetch.
  if (carriesFullBody(item.sourceId)) return;
  detailLoading.value = key;
  try {
    const full = await loadItem(props.document.projectId, item.sourceId, item.id);
    // Null means the source no longer knows this entry — it aged out between
    // the page and the click. The teaser stays on screen; it is still true.
    if (full) details.value = { ...details.value, [key]: full };
  } catch (e) {
    error.value = String(e);
  } finally {
    detailLoading.value = null;
  }
}

/**
 * The one `extras` key this reader knows by name.
 *
 * <p>A conventional slot, not a source's vocabulary — which is the difference
 * that makes it allowed here. `originPlace` means something at a news archive
 * and nothing at a Mastodon instance, so hardcoding it failed the second
 * source; `context` has no domain meaning at all. Every source fills it with
 * whatever its own domain makes of "one short line worth reading above the
 * headline" — the archive puts where the publisher sits, an instance could put
 * the instance — and a source with nothing to say leaves it out. Same rule as
 * everything else here: empty means „do not offer it", not „guess".
 *
 * <p>Deliberately not part of the contract. `extras` is already free and passes
 * through every hop unfiltered, so this costs no released version on either
 * side; the price is that a misspelled key gets silence rather than an error.
 */
const CONTEXT_KEY = 'context';

/** Room for a line that shares a row with source, date and language. */
const CONTEXT_MAX = 80;

/**
 * The source's own one-liner for the header, or null.
 *
 * <p>Truncated here and not at the source: how much room this row has is the
 * reader's business, and a source cannot know it. Arrays are joined rather than
 * refused — a source that put a list here meant all of it.
 */
function contextLine(item: FeedItemView): string | null {
  const raw = (shown(item).extras ?? {})[CONTEXT_KEY];
  if (raw === undefined || raw === null || raw === '') return null;
  const text = (Array.isArray(raw) ? raw.join(', ') : String(raw)).trim();
  if (!text) return null;
  return text.length > CONTEXT_MAX ? `${text.slice(0, CONTEXT_MAX - 1)}…` : text;
}

/**
 * The extras worth putting in front of a person — declared by the source that
 * wrote them, in the order it named them.
 *
 * <p>This list used to live here, and it did not survive the second source:
 * `originPlace` and `translationModel` are one archive's vocabulary, and a
 * Mastodon instance would have shown nothing while its own fields went
 * unrendered. A source that declares none shows none, which is the same rule
 * the signal buttons follow — empty means „do not offer it", not „guess".
 */
function extraRows(item: FeedItemView): { label: string; value: string }[] {
  const declared = sources.value
    .find((s) => s.id === item.sourceId)?.capabilities?.extraFields ?? [];
  const extras = shown(item).extras ?? {};
  const out: { label: string; value: string }[] = [];
  for (const field of declared) {
    // Skipped even when declared: it is already in the header, and a source
    // that declares it too would have it twice — once unlabelled above, once
    // labelled below.
    if (field.key === CONTEXT_KEY) continue;
    const raw = extras[field.key];
    if (raw === undefined || raw === null || raw === '') continue;
    out.push({ label: field.label, value: Array.isArray(raw) ? raw.join(', ') : String(raw) });
  }
  return out;
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

// Ids only; the labels are looked up per render so a language switch reaches
// them. A module-level array of literals could not follow one.
const REPORT_REASON_IDS = [
  'WRONG_CATEGORY',
  'WRONG_LANGUAGE',
  'BROKEN_LINK',
  'DUPLICATE',
  'SPAM',
] as const;

const reportReasons = computed(() =>
  REPORT_REASON_IDS.map((id) => ({ value: id, label: t(`feeds.reason.${id}`) })),
);

const report = ref<{ item: FeedItemView; reason: string; note: string } | null>(null);
const reportOpen = ref(false);

const sentinel = ref<HTMLElement | null>(null);
let observer: IntersectionObserver | null = null;

const configuredStreams = computed(() => config.value?.streams ?? []);

/**
 * Sources the server could not describe. `capabilities === null` is the
 * server's way of saying "listed, but unusable" — it keeps the row rather than
 * dropping it, because a source missing from the list reads as one that was
 * never configured. Nothing rendered that, so a wrong API key looked like an
 * empty feed with no explanation anywhere.
 */
const unusableSources = computed(() => sources.value.filter((s) => !s.capabilities));

/** Source picker options, with the unusable ones named as such. */
const sourceOptions = computed(() =>
  sources.value.map((s) => ({
    value: s.id,
    label: s.capabilities ? s.displayName : `${s.displayName} (unavailable)`,
  })),
);

/**
 * Facets offered by the configured sources.
 *
 * Keyed per source, because a facet key is only as shared as its value system.
 * Two sources may both declare `subject-topic` and mean different vocabularies,
 * so their values are never merged into one list.
 *
 * Reactive to the *edited* stream list, not the saved one: adding a source in
 * the form should offer its dimensions before anything is stored, or the
 * configuration reads as if the source had none.
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

/**
 * Selectable values, keyed by source, facet and parent level.
 *
 * Two shapes end up here. A facet that shipped its values with the
 * declaration is sliced locally by `parentId`; one that declared
 * `lazyChildren` is fetched a level at a time. The cache key carries the
 * parent, so both look the same to the picker.
 */
const facetValues = ref<Record<string, FeedFacetValueView[]>>({});

function facetKeyOf(sourceId: string, key: string, parent?: string | null): string {
  return `${sourceId}\u0000${key}\u0000${parent ?? ''}`;
}

/**
 * Labels for ids we have seen, so a stored selection can be shown as words.
 *
 * A selection survives in the manifest; the tree it came from does not.
 * Without this, reopening a feed shows `medtop:15000000` where it once said
 * „sport" — the id is the truth on the wire and the wrong thing to read.
 */
const facetLabels = ref<Record<string, string>>({});

function labelOf(id: string): string {
  return facetLabels.value[id] ?? id;
}

/**
 * One level of a facet's tree, cached.
 *
 * Loaded eagerly for the top level of every offered facet, so a control never
 * opens on an empty list — „nothing here" and „not asked yet" look identical
 * to a reader, and only one of them is true.
 */
async function levelOf(
  sourceId: string,
  facet: FeedFacetView,
  parent: string | null,
): Promise<FeedFacetValueView[]> {
  const cacheKey = facetKeyOf(sourceId, facet.key, parent);
  const cached = facetValues.value[cacheKey];
  if (cached) return cached;

  let values: FeedFacetValueView[];
  if (!facet.lazyChildren) {
    // Everything travelled with the declaration; a level is a filter on it.
    values = facet.values.filter((v) => (v.parentId ?? null) === parent);
  } else {
    try {
      values = await loadFacetValues(
        props.document.projectId, sourceId, facet.key, parent ?? undefined);
    } catch (e) {
      error.value = String(e);
      values = [];
    }
  }
  facetValues.value = { ...facetValues.value, [cacheKey]: values };
  const labels = { ...facetLabels.value };
  for (const v of values) labels[v.id] = v.label;
  facetLabels.value = labels;
  return values;
}

async function loadAllFacetValues(): Promise<void> {
  facetValues.value = {};
  for (const entry of offeredFacets.value) {
    // A facet that ships its values inline knows every label already, at any
    // depth. Remembering only the level we happened to load left a stored
    // selection reading „iso:PL" — an id the source had spelled out for us in
    // the same response.
    //
    // Merged into the live ref one facet at a time, never through a snapshot
    // taken before the loop: levelOf() writes the labels it fetched into the
    // same ref, and assigning a pre-loop copy afterwards threw exactly those
    // away — so a lazyChildren facet, whose values are empty here and only
    // arrive through levelOf, lost every label it had just been given.
    const inline: Record<string, string> = {};
    for (const value of entry.facet.values) inline[value.id] = value.label;
    facetLabels.value = { ...facetLabels.value, ...inline };
    await levelOf(entry.sourceId, entry.facet, null);
  }
}

// ── the picker ───────────────────────────────────────────────────────

/**
 * The open facet picker: which facet, where in its tree, what is listed.
 *
 * One dialog for two jobs, because they are the same gesture on two targets:
 * a row's chevron goes deeper, its checkbox selects. A value at any depth is
 * a legitimate choice — „Asia" filters as well as „Singapore" — so nothing
 * here insists on leaves.
 */
const picker = ref<{
  sourceId: string;
  sourceName: string;
  facet: FeedFacetView;
  /** Ancestors of the level on screen, outermost first. */
  path: FeedFacetValueView[];
  level: FeedFacetValueView[];
  loading: boolean;
} | null>(null);

const pickerOpen = computed({
  get: () => picker.value !== null,
  set: (open: boolean) => {
    if (!open) picker.value = null;
  },
});

/**
 * Counts navigations inside the picker, so a slow level cannot overwrite a
 * faster one that came after it.
 *
 * A counter rather than comparing the path we set: `ref` is deeply reactive,
 * so reading `picker.value.path` hands back a *proxy* of the array. An
 * identity check against the raw array is then never true, and the dialog sits
 * at „Loading…" forever with the data already in hand.
 */
let pickerNavigation = 0;

async function showLevel(parent: FeedFacetValueView | null, path: FeedFacetValueView[]) {
  const open = picker.value;
  if (!open) return;
  const navigation = ++pickerNavigation;
  picker.value = { ...open, path, level: [], loading: true };
  const level = await levelOf(open.sourceId, open.facet, parent?.id ?? null);
  if (picker.value && navigation === pickerNavigation) {
    picker.value = { ...picker.value, level, loading: false };
  }
}

async function openPicker(entry: {
  sourceId: string; sourceName: string; facet: FeedFacetView;
}): Promise<void> {
  picker.value = { ...entry, path: [], level: [], loading: true };
  await showLevel(null, []);
}

async function drillInto(value: FeedFacetValueView): Promise<void> {
  const open = picker.value;
  if (!open) return;
  await showLevel(value, [...open.path, value]);
}

/** Back to an ancestor; index -1 is the root. */
async function breadcrumbTo(index: number): Promise<void> {
  const open = picker.value;
  if (!open) return;
  const path = open.path.slice(0, index + 1);
  await showLevel(path.length === 0 ? null : path[path.length - 1], path);
}

function selectedFacetValues(key: string): string[] {
  return config.value?.filter?.facets?.[key] ?? [];
}

/**
 * Add or remove one value.
 *
 * Several values of one key are an „or" on the wire, which is what a list of
 * places or topics means to a reader: „Asia or Europe", not both at once.
 * Across keys it stays an „and".
 */
function toggleFacetValue(key: string, id: string): void {
  if (!config.value) return;
  const current = selectedFacetValues(key);
  const next = current.includes(id)
    ? current.filter((v) => v !== id)
    : [...current, id];
  const facets = { ...(config.value.filter?.facets ?? {}) };
  if (next.length > 0) facets[key] = next;
  else delete facets[key];
  config.value = { ...config.value, filter: { ...config.value.filter, facets } };
}

function clearFacet(key: string): void {
  if (!config.value) return;
  const facets = { ...(config.value.filter?.facets ?? {}) };
  delete facets[key];
  config.value = { ...config.value, filter: { ...config.value.filter, facets } };
}

/** What the closed control shows: the chosen labels, or „Any". */
function facetSummary(key: string): string {
  const chosen = selectedFacetValues(key);
  return chosen.length === 0 ? t('feeds.any') : chosen.map(labelOf).join(', ');
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

onBeforeUnmount(() => {
  observer?.disconnect();
  if (autoRefreshTimer) clearInterval(autoRefreshTimer);
});

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
  await loadAllFacetValues();
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

// ── refreshing ───────────────────────────────────────────────────────

/**
 * How often the stream reloads itself when the switch is on.
 *
 * <p>Thirty seconds is a compromise between a timeline that feels live and
 * one that costs a page fetch per source every few breaths. Each tick is a
 * real request to every configured source, and some of them meter it.
 */
const AUTO_REFRESH_INTERVAL_MS = 30_000;

/**
 * How far the stream has to be dragged past its top before it reloads.
 *
 * <p>Generous on purpose: the cost of overshooting is a refresh nobody asked
 * for, which throws away the reader's position in a list they were reading.
 */
const PULL_THRESHOLD_PX = 140;

/**
 * How much a wheel over-scroll counts towards the pull.
 *
 * <p>A trackpad reports far more travel than a finger for the same movement,
 * and its momentum keeps reporting after the list has stopped. Damped like
 * the touch path, or a normal flick to the top of the list arrives at the
 * threshold on its own.
 */
const WHEEL_DAMPING = 3;

/** A gap this long between wheel events starts a new gesture. */
const GESTURE_GAP_MS = 250;

const autoRefresh = ref(false);
const pullDistance = ref(0);
const refreshing = ref(false);
const scroller = ref<HTMLElement | null>(null);

let autoRefreshTimer: ReturnType<typeof setInterval> | null = null;
let pullStartY: number | null = null;
let lastWheelAt = 0;
/**
 * Whether the gesture in progress began with the list already at the top.
 *
 * <p>This is the whole difference between a pull and an ordinary scroll. A
 * flick from halfway down ends at the top and then keeps delivering momentum
 * the browser cannot apply — indistinguishable from a deliberate pull unless
 * you remember where the gesture started.
 */
let gestureStartedAtTop = false;

async function refreshNow(): Promise<void> {
  if (refreshing.value || loading.value) return;
  refreshing.value = true;
  try {
    await restart();
  } finally {
    refreshing.value = false;
    pullDistance.value = 0;
  }
}

/**
 * One automatic reload, or a good reason not to.
 *
 * <p>Two things are skipped rather than done: a hidden tab, because nobody is
 * reading it and every source still gets asked; and a stream scrolled away
 * from the top, because a reload jumps back to the newest entry and moving the
 * page under someone who is reading is worse than being a little stale.
 */
async function autoRefreshTick(): Promise<void> {
  if (document.hidden) return;
  if ((scroller.value?.scrollTop ?? 0) > 0) return;
  await refreshNow();
}

watch(autoRefresh, (on) => {
  if (autoRefreshTimer) {
    clearInterval(autoRefreshTimer);
    autoRefreshTimer = null;
  }
  if (on) autoRefreshTimer = setInterval(() => void autoRefreshTick(), AUTO_REFRESH_INTERVAL_MS);
});

/**
 * Pull-to-refresh, from a wheel as well as a finger.
 *
 * <p>The wheel path is the one that matters on a desktop: a trackpad at the
 * top of a list keeps sending scroll events that the browser has nowhere to
 * apply, and that over-scroll is exactly the gesture. Waiting for a „release"
 * would mean waiting for an event a wheel never sends, so the wheel fires at
 * the threshold and the finger on lift.
 */
function onWheel(event: WheelEvent): void {
  const now = Date.now();
  const atTop = (scroller.value?.scrollTop ?? 0) <= 0;
  if (now - lastWheelAt > GESTURE_GAP_MS) {
    gestureStartedAtTop = atTop;
    pullDistance.value = 0;
  }
  lastWheelAt = now;

  // Not a pull: still scrolling, scrolling down, mid-refresh, or riding the
  // momentum of a gesture that began further down the list.
  if (refreshing.value || !atTop || !gestureStartedAtTop || event.deltaY >= 0) {
    if (pullDistance.value !== 0 && event.deltaY >= 0) pullDistance.value = 0;
    return;
  }

  pullDistance.value = Math.min(
    pullDistance.value - event.deltaY / WHEEL_DAMPING,
    PULL_THRESHOLD_PX * 1.5);
  if (pullDistance.value >= PULL_THRESHOLD_PX) {
    // The gesture is spent: without this its remaining momentum triggers the
    // next refresh the moment this one finishes.
    gestureStartedAtTop = false;
    void refreshNow();
  }
}

/** Any real scrolling ends a pull — the gesture only exists at the top. */
function onScroll(): void {
  if (pullDistance.value !== 0 && (scroller.value?.scrollTop ?? 0) > 0) {
    pullDistance.value = 0;
  }
}

function onTouchStart(event: TouchEvent): void {
  pullStartY = (scroller.value?.scrollTop ?? 0) <= 0 ? event.touches[0].clientY : null;
}

function onTouchMove(event: TouchEvent): void {
  if (pullStartY === null || refreshing.value) return;
  const delta = event.touches[0].clientY - pullStartY;
  // Halved: a pull that follows the finger exactly feels like a broken scroll.
  pullDistance.value = Math.max(0, Math.min(delta / 2, PULL_THRESHOLD_PX * 1.5));
}

function onTouchEnd(): void {
  pullStartY = null;
  if (pullDistance.value >= PULL_THRESHOLD_PX) void refreshNow();
  else pullDistance.value = 0;
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
      // The filter as it stands in the form, which is not always the stored
      // one: toggling a facet value edits `config.filter` and „Save as filter"
      // is what makes it permanent. Sending it means browsing a facet works
      // before saving — the server overrides the stored filter with this when
      // it is present, and falls back to the stored one when it is not.
      filter: config.value?.filter,
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
  // The entry's own id is part of the path, not just its headline. Wikipedia
  // recent-changes names every entry after the article, so clipping two edits
  // to „Berlin" produced one path twice — the second one 409ed and the card
  // said „Clipped", pointing at somebody else's change. Two sources with the
  // same headline collided the same way.
  const target = `${folder.value}/clips/${slug(item.title)}-${slug(item.id)}`;
  // The detail, when the reader opened the card: the body was fetched, is on
  // screen, and is exactly the part a source with `carriesFullBody: false`
  // does not put in the teaser. Clipping the teaser instead throws away the
  // thing the reader wanted to keep, and it was already in the browser.
  const it = shown(item);
  try {
    const result = await clipItem(props.document.projectId, {
      targetPath: target,
      title: it.title,
      url: it.url,
      publishedAt: it.publishedAt,
      summary: it.summary,
      body: it.body ?? undefined,
      author: it.author,
      language: it.language,
      sourceId: it.sourceId,
    });
    clipped.value = { ...clipped.value, [entryKey(item)]: result.path };
  } catch (e) {
    // 409 means this exact entry is already in the folder — an outcome, not a
    // failure. The path carries the entry id, so a conflict really is the same
    // entry and not a headline that happens to match.
    if (e instanceof RestError && e.status === 409) {
      clipped.value = { ...clipped.value, [entryKey(item)]: target };
      return;
    }
    error.value = String(e);
  }
}

/**
 * Whether this source declared that a list entry is already the whole thing.
 * Unknown source → false, so an undescribable source is asked rather than
 * silently treated as complete.
 */
function carriesFullBody(sourceId: string): boolean {
  return sources.value.find((s) => s.id === sourceId)?.capabilities?.carriesFullBody === true;
}

/** Which signals this entry's source declared. Empty = the buttons stay hidden. */
function signalsFor(sourceId: string): string[] {
  return sources.value.find((s) => s.id === sourceId)?.capabilities?.signalsAccepted ?? [];
}

function openReport(item: FeedItemView): void {
  report.value = { item, reason: REPORT_REASON_IDS[0], note: '' };
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
    signalled.value = {
      ...signalled.value, [entryKey(pending.item)]: outcomeText(result.outcome),
    };
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
    signalled.value = { ...signalled.value, [entryKey(item)]: outcomeText(result.outcome) };
  } catch (e) {
    error.value = String(e);
  }
}

function outcomeText(outcome: string): string {
  switch (outcome) {
    case 'ACCEPTED':
      return t('feeds.outcome.accepted');
    case 'UNSUPPORTED':
      return t('feeds.outcome.unsupported');
    default:
      return t('feeds.outcome.declined');
  }
}

/** Display name of the source a note would travel to — the reader should know. */
function sourceName(sourceId: string): string {
  return sources.value.find((s) => s.id === sourceId)?.displayName ?? sourceId;
}

async function persist(): Promise<void> {
  if (!config.value) return;
  // Clear first: a save can now be refused (a free-text selector the source
  // cannot read), and without this the rejection stayed on screen after the
  // corrected save succeeded — reading as if the feed below it were broken.
  error.value = null;
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
  /** Appends the source's own words, when it supplied any. */
  const withDetail = (message: string) =>
    note.detail ? t('feeds.note.withDetail', { message, detail: note.detail }) : message;
  switch (note.kind) {
    case 'UNKNOWN_SOURCE':
      return t('feeds.note.unknownSource', { what });
    case 'DISABLED':
      return t('feeds.note.disabled', { what });
    case 'COOLING_DOWN':
      return t('feeds.note.coolingDown', { what });
    case 'TIMED_OUT':
      return t('feeds.note.timedOut', { what });
    case 'INVALID_SELECTOR':
      // The source itself said why, in words meant for a person — showing that
      // beats "failed", which is what this used to look like when a mistyped
      // hashtag came back empty.
      return withDetail(t('feeds.note.invalidSelector', { what }));
    case 'MISSING_FACET':
      // Not a failure: the source was never asked, because it does not offer
      // the dimension that was selected. Saying so beats a quietly shorter
      // timeline.
      return note.detail
        ? t('feeds.note.missingFacetDetail', { what, detail: note.detail })
        : t('feeds.note.missingFacet', { what });
    default:
      return withDetail(t('feeds.note.failed', { what }));
  }
}

function when(iso: string): string {
  const then = new Date(iso).getTime();
  const minutes = Math.round((Date.now() - then) / 60000);
  if (minutes < 1) return t('feeds.when.justNow');
  if (minutes < 60) return t('feeds.when.minutes', { n: minutes });
  const hours = Math.round(minutes / 60);
  if (hours < 24) return t('feeds.when.hours', { n: hours });
  return new Date(iso).toLocaleDateString(locale.value);
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
        {{ t('feeds.tabStream') }}
      </VButton>
      <VButton :variant="tab === 'config' ? 'primary' : 'ghost'" @click="tab = 'config'">
        {{ t('feeds.tabConfig') }}
      </VButton>
      <div class="flex-1"></div>
      <!-- Only on the stream: an interval that reloads a form nobody is
           looking at would throw away what is being typed into it. -->
      <VToggle
        v-if="tab === 'stream'"
        :model-value="autoRefresh"
        :title="t('feeds.autoRefresh', { seconds: AUTO_REFRESH_INTERVAL_MS / 1000 })"
        @update:model-value="(v: boolean) => (autoRefresh = v)"
      />
      <VButton variant="ghost" :disabled="loading || refreshing" @click="refreshNow()">
        {{ t('feeds.refresh') }}
      </VButton>
    </div>

    <VAlert v-if="error" variant="error">{{ error }}</VAlert>

    <VAlert v-for="note in notes" :key="note.sourceId + note.selector" variant="warning">
      {{ noteText(note) }}
    </VAlert>

    <!-- Stream -->
    <div
      v-if="tab === 'stream'"
      ref="scroller"
      class="flex-1 overflow-y-auto"
      @scroll="onScroll"
      @wheel="onWheel"
      @touchstart="onTouchStart"
      @touchmove="onTouchMove"
      @touchend="onTouchEnd"
    >
      <!-- Grows with the pull and holds the spinner while it reloads. Height
           rather than opacity, so the gesture has something to push against. -->
      <div
        class="flex items-center justify-center overflow-hidden text-sm opacity-70"
        :style="{ height: `${refreshing ? PULL_THRESHOLD_PX : pullDistance}px` }"
      >
        <span v-if="refreshing" class="flex items-center gap-2">
          <span
            class="inline-block h-4 w-4 animate-spin rounded-full border-2 border-current
                   border-t-transparent"
          ></span>
          {{ t('feeds.refreshing') }}
        </span>
        <span v-else-if="pullDistance >= PULL_THRESHOLD_PX">{{ t('feeds.releaseToRefresh') }}</span>
        <!-- Only once there is room for a line of text. Below that the label
             is clipped by the very box that is supposed to be growing, which
             reads as a rendering fault rather than as a gesture. -->
        <span v-else-if="pullDistance >= 24">{{ t('feeds.pullToRefresh') }}</span>
      </div>
      <VEmptyState
        v-if="configuredStreams.length === 0"
        :headline="t('feeds.noStreamsHeadline')"
        :body="t('feeds.noStreamsBody')"
      />
      <VEmptyState
        v-else-if="items.length === 0 && !loading && !hasMore"
        :headline="t('feeds.nothingToReadHeadline')"
        :body="t('feeds.nothingToReadBody')"
      />

      <!-- Same bound as the configuration: at full window width a summary runs
           to ~250 characters a line, about three times what reads comfortably,
           and the marked entry's full text is worse. -->
      <div class="mx-auto flex w-full max-w-3xl flex-col gap-3">
        <VCard
          v-for="item in items"
          :key="entryKey(item)"
          :data-entry="handleOf(item)"
          :class="[
            'cursor-pointer transition-all',
            isMarked(item) ? 'ring-2 ring-primary shadow-lg' : 'hover:ring-1 hover:ring-base-300',
          ]"
          @click="toggleMark(item)"
        >
          <div class="flex gap-3">
            <img
              v-if="link(shown(item).imageUrl)"
              :src="link(shown(item).imageUrl)!"
              alt=""
              referrerpolicy="no-referrer"
              :class="[
                'flex-none rounded object-cover transition-all',
                isMarked(item) ? 'h-40 w-56' : 'h-24 w-32',
              ]"
            />
            <div class="flex min-w-0 flex-1 flex-col gap-1">
              <div class="flex items-center gap-2 text-xs opacity-70">
                <span>{{ item.sourceDisplayName }}</span>
                <span v-if="item.selector">· {{ item.selector }}</span>
                <span>· {{ when(item.publishedAt) }}</span>
                <span v-if="item.language">· {{ item.language }}</span>
                <span v-if="item.author">· {{ item.author }}</span>
                <!-- One key by name, and only this one: `context` is a
                     conventional slot every source fills in its own terms, not
                     a source's field (see CONTEXT_KEY). Anything domain-specific
                     goes through `extraFields` into the detail block instead —
                     hardcoding originPlace here is what failed the second
                     source. extraRows skips this key so it cannot appear twice. -->
                <span v-if="contextLine(item)" class="min-w-0 truncate font-medium"
                  >· {{ contextLine(item) }}</span
                >
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
              <p
                v-if="shown(item).summary"
                :class="isMarked(item) ? 'text-sm opacity-80' : 'line-clamp-3 text-sm opacity-80'"
              >
                {{ shown(item).summary }}
              </p>

              <!-- Marked: what a single lookup could add that a page of twenty
                   cannot afford. -->
              <template v-if="isMarked(item)">
                <p v-if="detailLoading === entryKey(item)" class="text-xs opacity-60">
                  {{ t('feeds.loadingEntry') }}
                </p>

                <dl
                  v-if="extraRows(item).length > 0"
                  class="mt-1 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 text-xs opacity-70"
                >
                  <template v-for="row in extraRows(item)" :key="row.label">
                    <dt class="font-medium">{{ row.label }}</dt>
                    <dd class="min-w-0 break-words">{{ row.value }}</dd>
                  </template>
                </dl>

                <div v-if="shown(item).tags?.length" class="mt-1 flex flex-wrap gap-1">
                  <span
                    v-for="tag in shown(item).tags"
                    :key="tag"
                    class="rounded bg-base-200 px-1.5 py-0.5 text-xs opacity-70"
                  >
                    {{ tag }}
                  </span>
                </div>

                <!-- No inner scroll box: a scroll area inside a scrolling
                     stream fights the wheel, and the card was opened on
                     purpose — letting it grow is the answer it deserves. -->
                <p
                  v-if="shown(item).body"
                  class="mt-2 whitespace-pre-wrap text-sm"
                >
                  {{ shown(item).body }}
                </p>
                <p
                  v-else-if="detailLoading !== entryKey(item) && details[entryKey(item)]"
                  class="mt-1 text-xs opacity-60"
                >
                  {{ t('feeds.noFullText') }}
                </p>
              </template>
              <!-- The card itself toggles the mark; the controls must not. -->
              <div class="mt-1 flex items-center gap-2" @click.stop>
                <VShareButton :subject="shareSubject(item)" />
                <VButton
                  size="sm"
                  variant="ghost"
                  :disabled="!!clipped[entryKey(item)]"
                  @click="clip(item)"
                >
                  {{ clipped[entryKey(item)] ? t('feeds.clipped') : t('feeds.clip') }}
                </VButton>
                <VButton
                  v-if="signalsFor(item.sourceId).includes('REPORT') && !signalled[entryKey(item)]"
                  size="sm"
                  variant="ghost"
                  @click="openReport(item)"
                >
                  {{ t('feeds.report') }}
                </VButton>
                <VButton
                  v-if="signalsFor(item.sourceId).includes('REQUEST') && !signalled[entryKey(item)]"
                  size="sm"
                  variant="ghost"
                  @click="requestKind(item, 'TRANSLATION')"
                >
                  {{ t('feeds.askTranslation') }}
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
                  {{ t('feeds.openInSource') }}
                </a>
                <span v-if="clipped[entryKey(item)]" class="text-xs opacity-70">
                  → {{ clipped[entryKey(item)] }}
                </span>
              </div>
            </div>
          </div>
        </VCard>

        <div ref="sentinel" class="h-8"></div>
        <p v-if="loading" class="p-2 text-center text-sm opacity-70">{{ t('feeds.loading') }}</p>
        <!-- The observer only fires when the sentinel's visibility changes, and
             a round that appends nothing changes nothing. This is the way
             forward that does not depend on that. -->
        <div v-else-if="hasMore && items.length > 0" class="p-2 text-center">
          <VButton size="sm" variant="ghost" @click="nextPage()">{{ t('feeds.loadMore') }}</VButton>
        </div>
        <p
          v-else-if="!hasMore && items.length > 0"
          class="p-2 text-center text-sm opacity-50"
        >
          {{ t('feeds.endOfStream') }}
        </p>
      </div>
    </div>

    <!-- Configuration -->
    <div v-else class="flex-1 overflow-y-auto">
      <!-- Bounded: at full window width a label sits at one edge and its
           control at the other, and nothing reads as belonging together. -->
      <div v-if="config" class="mx-auto flex w-full max-w-3xl flex-col gap-4">
        <VCard>
          <h3 class="mb-1 border-b border-base-300 pb-1 text-base font-bold">{{ t('feeds.streams') }}</h3>
          <p class="mb-3 text-xs opacity-60">
            {{ t('feeds.streamsHint') }}
          </p>
          <div v-if="sources.length === 0" class="flex flex-col items-center gap-2">
            <VEmptyState
              :headline="t('feeds.noSourcesHeadline')"
              :body="t('feeds.noSourcesBody')"
            />
            <VButton variant="ghost" @click="reloadSources(true)">{{ t('feeds.reloadSources') }}</VButton>
          </div>
          <div v-else class="flex flex-col gap-2">
            <!-- A source that could not be described is reported, not hidden:
                 the server keeps it in the list precisely so this form can say
                 why it is unusable. Without this the reader sees a selectable
                 option with an empty selector dropdown and an empty feed. -->
            <VAlert v-for="s in unusableSources" :key="s.id" variant="warning">
              <b>{{ s.displayName }}</b> {{ t('feeds.unusablePre') }}
              {{ s.error ?? t('feeds.unusableFallback') }}.
              {{ t('feeds.unusableCheckPre') }}
              <code>centauri.endpoint.{{ s.id }}.*</code>{{ t('feeds.unusableCheck') }}
            </VAlert>
            <div
              v-for="(stream, index) in config.streams"
              :key="index"
              class="flex items-end gap-2 rounded border border-base-300 p-2"
            >
              <VSelect
                :model-value="stream.source"
                :options="sourceOptions"
                @update:model-value="(v: string | null) => changeSource(stream, v ?? '')"
              />
              <VInput
                v-if="isFreeform(stream.source)"
                :model-value="stream.selector ?? ''"
                :placeholder="t('feeds.selectorPlaceholder')"
                @update:model-value="(v: string) => (stream.selector = v)"
              />
              <VSelect
                v-else
                :model-value="stream.selector ?? ''"
                :options="selectorsFor(stream.source)"
                @update:model-value="(v: string | null) => (stream.selector = v ?? '')"
              />
              <VButton size="sm" variant="ghost" @click="removeStream(index)">{{ t('feeds.remove') }}</VButton>
            </div>
            <VButton size="sm" variant="ghost" @click="addStream()">{{ t('feeds.addStream') }}</VButton>
          </div>
        </VCard>

        <VCard>
          <h3 class="mb-1 border-b border-base-300 pb-1 text-base font-bold">{{ t('feeds.filter') }}</h3>
          <p class="mb-3 text-xs opacity-60">
            {{ t('feeds.filterHint') }}
          </p>
          <div class="flex flex-col gap-2">
            <VInput
              :model-value="config.filter.text ?? ''"
              :label="t('feeds.text')"
              :placeholder="t('feeds.textPlaceholder')"
              @update:model-value="(v: string) => (config!.filter.text = v)"
            />
            <VInput
              :model-value="config.filter.languages.join(', ')"
              :label="t('feeds.languages')"
              :placeholder="t('feeds.languagesPlaceholder')"
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
              :label="t('feeds.exclude')"
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
              :label="t('feeds.since')"
              :placeholder="t('feeds.sincePlaceholder')"
              @update:model-value="(v: string) => (config!.filter.since = v)"
            />
            <!--
              Facets. One control per dimension a configured source declares —
              per source and never merged, because the same key can carry
              different value systems at two sources, and offering a value only
              one of them answers would silence the other.
            -->
            <div
              v-for="entry in offeredFacets"
              :key="entry.sourceId + '/' + entry.facet.key"
              class="flex flex-col gap-1 rounded border border-base-300 p-2"
            >
              <span class="text-sm font-medium">
                {{ entry.facet.label }}
                <span class="font-normal opacity-60">· {{ entry.sourceName }}</span>
              </span>
              <!-- Value and controls adjacent, not spread to the edges: the
                   button belongs to the value, and a gap the width of the
                   window says otherwise. -->
              <div class="flex flex-wrap items-center gap-2">
                <span class="max-w-md truncate text-sm opacity-80">
                  {{ facetSummary(entry.facet.key) }}
                </span>
                <VButton size="sm" variant="ghost" @click="openPicker(entry)">{{ t('feeds.choose') }}</VButton>
                <VButton
                  v-if="selectedFacetValues(entry.facet.key).length > 0"
                  size="sm"
                  variant="ghost"
                  @click="clearFacet(entry.facet.key)"
                >
                  {{ t('feeds.clear') }}
                </VButton>
              </div>
            </div>
          </div>
        </VCard>

        <div class="flex gap-2">
          <VButton variant="primary" @click="persist()">{{ t('feeds.save') }}</VButton>
          <VButton variant="ghost" @click="reload()">{{ t('feeds.discard') }}</VButton>
        </div>
      </div>
    </div>
    <!-- Facet picker: drill with the chevron, choose with the checkbox -->
    <VModal
      v-if="picker"
      v-model="pickerOpen"
      :title="`${picker.facet.label} · ${picker.sourceName}`"
      size="lg"
    >
      <div class="flex flex-col gap-3">
        <!-- Breadcrumb. Present even at the root, so the control looks the
             same wherever you are in the tree. -->
        <div v-if="picker.facet.hierarchical" class="flex flex-wrap items-center gap-1 text-sm">
          <button class="hover:underline" @click="breadcrumbTo(-1)">{{ t('feeds.all') }}</button>
          <template v-for="(node, i) in picker.path" :key="node.id">
            <span class="opacity-50">›</span>
            <button class="hover:underline" @click="breadcrumbTo(i)">{{ node.label }}</button>
          </template>
        </div>

        <!-- What is chosen, across every level. Removable here, because the
             value that needs removing is rarely on the level you are on. -->
        <div v-if="selectedFacetValues(picker.facet.key).length > 0" class="flex flex-wrap gap-1">
          <button
            v-for="id in selectedFacetValues(picker.facet.key)"
            :key="id"
            class="rounded bg-base-200 px-2 py-0.5 text-xs hover:line-through"
            @click="toggleFacetValue(picker.facet.key, id)"
          >
            {{ labelOf(id) }} ✕
          </button>
        </div>

        <p v-if="picker.loading" class="text-sm opacity-60">{{ t('feeds.loading') }}</p>
        <VEmptyState
          v-else-if="picker.level.length === 0"
          :headline="t('feeds.nothingBelowHeadline')"
          :body="t('feeds.nothingBelowBody')"
        />

        <div v-else class="max-h-96 overflow-y-auto">
          <div
            v-for="value in picker.level"
            :key="value.id"
            class="flex items-center gap-2 border-b border-base-200 py-1 last:border-0"
          >
            <VCheckbox
              :model-value="selectedFacetValues(picker.facet.key).includes(value.id)"
              :label="value.label"
              @update:model-value="toggleFacetValue(picker.facet.key, value.id)"
            />
            <div class="flex-1"></div>
            <!-- Any node is selectable, so the chevron is a separate target:
                 going deeper and choosing are different intentions. -->
            <VButton
              v-if="picker.facet.hierarchical"
              size="sm"
              variant="ghost"
              @click="drillInto(value)"
            >
              ›
            </VButton>
          </div>
        </div>
      </div>

      <template #actions>
        <VButton variant="ghost" @click="clearFacet(picker.facet.key)">{{ t('feeds.clearAll') }}</VButton>
        <VButton variant="primary" @click="pickerOpen = false">{{ t('feeds.done') }}</VButton>
      </template>
    </VModal>

    <VModal v-model="reportOpen" :title="t('feeds.reportTitle')">
      <div v-if="report" class="flex flex-col gap-3">
        <p class="text-sm opacity-70">{{ report.item.title }}</p>
        <VSelect
          :model-value="report.reason"
          :options="reportReasons"
          :label="t('feeds.reportReason')"
          @update:model-value="(v: string | null) => (report!.reason = v ?? 'SPAM')"
        />
        <VTextarea
          :model-value="report.note"
          :rows="3"
          :label="t('feeds.reportNote')"
          @update:model-value="(v: string) => (report!.note = v)"
        />
        <!-- The reader is looking at a form in their own workspace and has no
             reason to suspect the text leaves the house. So it says so. -->
        <p class="text-xs opacity-60">
          {{ t('feeds.reportDisclaimerPre') }}
          <strong>{{ sourceName(report.item.sourceId) }}</strong>.
          {{ t('feeds.reportDisclaimerPost') }}
        </p>
      </div>
      <template #actions>
        <VButton variant="ghost" @click="reportOpen = false">{{ t('feeds.cancel') }}</VButton>
        <VButton variant="primary" @click="submitReport()">{{ t('feeds.send') }}</VButton>
      </template>
    </VModal>
  </div>
</template>
