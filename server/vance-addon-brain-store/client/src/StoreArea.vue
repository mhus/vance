<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import {
  VAlert, VButton, VCard, VEmptyState, VInput, VSelect, VTextarea,
} from '@vance/components';
import {
  buy, install, loadOverview, loadReviews,
  loadSurfaces, loadWithdrawalNotice, submitReview,
} from './api';
import { COUNTRY_OPTIONS } from './countries';
import DeveloperPanel from './DeveloperPanel.vue';
import MoneyPanel from './MoneyPanel.vue';
import OperatorPanel from './OperatorPanel.vue';
import type {
  EntryState, StoreEntry, StoreReview, StoreSourceView, WithdrawalNotice,
} from './types';

const props = defineProps<{ projectId?: string }>();

/**
 * The four lists are one list with a state per entry — a kit that is
 * owned and installed and updatable would otherwise appear three times.
 * These tabs filter it; they do not fetch separately.
 */
const tabs: { key: 'ALL' | EntryState; label: string }[] = [
  { key: 'ALL', label: 'All' },
  { key: 'OFFERED', label: 'Offered' },
  { key: 'OWNED', label: 'Purchased' },
  { key: 'INSTALLED', label: 'Installed' },
  { key: 'UPDATABLE', label: 'Updatable' },
];

const projectId = computed(() => props.projectId ?? '_tenant');

/**
 * Two rows, in this order: which store, then what you are doing there.
 *
 * The store is the frame — a role is held *at* a store, not in general,
 * and an account can be a developer at one and a plain buyer at another.
 * Making the store the outer choice removes the picker that used to sit
 * inside the developer and operator panels: there is nothing left for it
 * to decide.
 *
 * Both rows disappear when they have nothing to offer. One store is not a
 * choice, and a role strip that only ever says "Store" is furniture.
 */
const mode = ref<'STORE' | 'DEVELOPER' | 'OPERATOR' | 'MONEY'>('STORE');

/**
 * The operator area appears only where this brain is set up to operate
 * that store (`store.operator.<sourceId>`).
 *
 * Hiding it is not security — the store refuses anyone who is not on its
 * own operator list, and that list lives in its config rather than in a
 * database. It is that a visible button puzzles everyone it does not
 * belong to, and invites the rest to try it.
 */
const operatorSources = ref<string[]>([]);
const developerSources = ref<string[]>([]);

/**
 * Switching to a store one does not operate closes the operator view.
 *
 * Only that view: an earlier version reset *any* mode, so picking another
 * store from the Developer tab threw you back to the shop window.
 */
/** Pick a store. A role that does not exist there closes with it. */
function selectSource(sourceId: string): void {
  activeSource.value = sourceId;
  leaveModeIfNotOffered();
}

function leaveModeIfNotOffered(): void {
  const offered = modes.value.some((entry) => entry.key === mode.value);
  if (!offered) mode.value = 'STORE';
}

const modes = computed(() => {
  const entries: { key: 'STORE' | 'DEVELOPER' | 'OPERATOR' | 'MONEY'; label: string }[] = [
    { key: 'STORE', label: 'Store' },
  ];
  // Both roles come from the store, and neither is shown to somebody who
  // does not hold it: a tab that grants nothing puzzles everyone it does
  // not belong to and invites the rest to try it. Someone with neither
  // sees a shop and nothing else.
  if (developerSources.value.includes(activeSource.value)) {
    entries.push({ key: 'DEVELOPER', label: 'Developer' });
  }
  if (operatorSources.value.includes(activeSource.value)) {
    entries.push({ key: 'OPERATOR', label: 'Operator' });
    // Its own tab rather than a section under Operator: moderation and money
    // are done by different people on different days, and a screen mixing a
    // release queue with a payout button would be used for both by whoever
    // happened to have it open.
    entries.push({ key: 'MONEY', label: 'Money' });
  }
  return entries;
});

/** Which store everything on this screen is about. */
const activeSource = ref('');
const activeView = computed(
  () => views.value.find((view) => view.sourceId === activeSource.value) ?? null,
);

/** Shown only when there is something to pick between. */
const showStoreTabs = computed(() => views.value.length > 1);

/** Shown only when this account holds a role beyond buying. */
const showModeTabs = computed(() => modes.value.length > 1);

const views = ref<StoreSourceView[]>([]);
const activeTab = ref<'ALL' | EntryState>('ALL');
const search = ref('');
const loading = ref(false);
const busyPath = ref<string>('');
const error = ref('');
const notice = ref('');

/**
 * A payment is happening in another window.
 *
 * <p>The provider's pages are not ours and cannot tell us anything —
 * {@code noopener} is deliberate, and a real provider's tab could not
 * reach us either. So the signal is the one thing that always arrives:
 * the buyer coming back to this tab. Without it the row still said
 * "offered" after a completed payment until somebody reloaded, which
 * reads as a payment that did not work.
 */
const awaitingPayment = ref(false);

/**
 * Second signal, because the first one can never arrive.
 *
 * A buyer may close the payment tab, or finish on their phone, and then
 * nothing brings them back to this one. Polling while — and only while —
 * a payment is in flight covers that, and it is what checkout pages do.
 * Bounded, because an unanswered payment is a page somebody left open,
 * not a reason to keep asking all afternoon.
 */
const PAYMENT_POLL_MS = 5000;
const PAYMENT_POLL_LIMIT = 24;
let paymentPoll: ReturnType<typeof setInterval> | null = null;
let paymentPollsLeft = 0;

/** Which kit is being paid for, so the poll knows what it is waiting for. */
const awaitingEntry = ref<StoreEntry | null>(null);

function watchForPayment(entry: StoreEntry): void {
  stopWatchingForPayment();
  awaitingPayment.value = true;
  awaitingEntry.value = entry;
  paymentPollsLeft = PAYMENT_POLL_LIMIT;
  paymentPoll = setInterval(async () => {
    if (paymentPollsLeft-- <= 0) {
      // Left open and never paid. The notice stays, because that is still
      // what is true — the payment window is where this ends.
      stopWatchingForPayment();
      awaitingPayment.value = false;
      return;
    }
    await load();
    if (!stillOffered(entry)) settled(entry);
  }, PAYMENT_POLL_MS);
}

/** Fresh from the last load, because the old object is a stale copy. */
function stillOffered(entry: StoreEntry): boolean {
  const current = views.value
    .find((view) => view.sourceId === entry.sourceId)?.entries
    .find((candidate) => candidate.path === entry.path);
  return current == null || current.state === 'OFFERED';
}

/**
 * The payment went through.
 *
 * <p>Replacing the notice matters as much as refreshing the row: an
 * instruction to go and pay, left standing under a kit that is already
 * owned, reads as a payment that did not work.
 */
function settled(entry: StoreEntry): void {
  stopWatchingForPayment();
  awaitingPayment.value = false;
  awaitingEntry.value = null;
  notice.value = `Done — ${entry.displayName} is yours.`;
}

function stopWatchingForPayment(): void {
  if (paymentPoll !== null) {
    clearInterval(paymentPoll);
    paymentPoll = null;
  }
}

/** The buyer came back to this tab — the fastest signal there is. */
async function refreshAfterPayment(): Promise<void> {
  if (!awaitingPayment.value || document.hidden) return;
  const entry = awaitingEntry.value;
  awaitingPayment.value = false;
  stopWatchingForPayment();
  notice.value = '';
  await load();
  if (entry && !stillOffered(entry)) settled(entry);
}

// Buying asks for the store password again — the store takes a link token
// for reviewing and for nothing that spends money.
const buyingPath = ref<string>('');
const buyPassword = ref('');

/**
 * Where the buyer is, and their VAT id if they have one.
 *
 * A kit is taxed where its buyer is, so the store refuses a sale without a
 * country rather than guessing one — a guessed country is a number nobody
 * can defend later. The list is the store's, not this screen's: it sells
 * where it is set up for tax, and nowhere else yet.
 */
const billingCountry = ref('DE');
const buyVatId = ref('');

/** The union, which is the set the store's default covers. */
const countryOptions = COUNTRY_OPTIONS;
const withdrawalNotice = ref<WithdrawalNotice | null>(null);
const withdrawalAccepted = ref(false);

// Review panel, per kit. Only one is open at a time.
const reviewingPath = ref<string>('');
const reviews = ref<StoreReview[]>([]);
/**
 * Optional split by major, never the default.
 *
 * The major is the vendor's number to choose — splitting by it always
 * would hand them a reset button for criticism, and would halve evidence
 * that is thin to begin with. As a filter it costs nothing and answers
 * "does 2.x still have this problem".
 */
const reviewMajor = ref<number | null>(null);

const reviewMajors = computed(() => [...new Set(
  reviews.value.map((review) => review.majorVersion).filter((major): major is number =>
    major != null),
)].sort((a, b) => b - a));

const shownReviews = computed(() => reviewMajor.value == null
  ? reviews.value
  : reviews.value.filter((review) => review.majorVersion === reviewMajor.value));
const reviewStars = ref(5);
const reviewText = ref('');

// Buying asks for the account's own credentials — see openBuy.
const buyerEmail = ref('');

async function load(): Promise<void> {
  error.value = '';
  loading.value = true;
  try {
    views.value = await loadOverview(projectId.value);
    if (!activeSource.value && views.value.length) {
      activeSource.value = views.value[0].sourceId;
    }
    const surfaces = await loadSurfaces(projectId.value);
    operatorSources.value = surfaces.operatorSources;
    developerSources.value = surfaces.developerSources;
    // A tab can disappear under an open panel — a role withdrawn, or a
    // reload on a store where this account holds neither.
    leaveModeIfNotOffered();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not load the store.';
  } finally {
    loading.value = false;
  }
}

/**
 * Filtered by the state tab and by the search box.
 *
 * Client-side: the catalogue is fetched whole anyway, so a round-trip per
 * keystroke would buy nothing and cost the store a request per letter.
 * Searched are the things a person actually remembers about a kit — its
 * name, its address and what it says it is for.
 */
function entriesOf(view: StoreSourceView | null): StoreEntry[] {
  if (!view) return [];
  const byState = activeTab.value === 'ALL'
    ? view.entries
    : view.entries.filter((entry) => entry.state === activeTab.value);

  const needle = search.value.trim().toLowerCase();
  if (!needle) return byState;
  return byState.filter((entry) => [
    entry.displayName, entry.path, entry.description,
    ...entry.topics, ...entry.contains,
  ].some((field) => field?.toLowerCase().includes(needle)));
}

/**
 * A chip is a search, not a second filter mechanism.
 *
 * One box that everything narrows keeps the state of this screen in one
 * place — otherwise a person stares at an empty list wondering which of
 * two filters is hiding it.
 */
function filterBy(tag: string): void {
  search.value = search.value.trim().toLowerCase() === tag ? '' : tag;
}

async function installEntry(entry: StoreEntry): Promise<void> {
  error.value = '';
  notice.value = '';
  busyPath.value = entry.path;
  try {
    const result = await install(projectId.value, entry.sourceId, entry.path);
    // The version is what the person was deciding about — the row said
    // "3.1.0 available", so the confirmation says which one arrived.
    const version = result.version ? ` → ${result.version}` : '';
    notice.value =
      `${result.kitName ?? entry.displayName}: ${result.mode?.toLowerCase() ?? 'done'}${version}.`;
    if (result.warnings?.length) notice.value += ` ${result.warnings.join(' ')}`;
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not install this kit.';
  } finally {
    busyPath.value = '';
  }
}

async function openReviews(entry: StoreEntry): Promise<void> {
  error.value = '';
  if (reviewingPath.value === entry.path) {
    reviewingPath.value = '';
    return;
  }
  reviewingPath.value = entry.path;
  reviews.value = [];
  reviewMajor.value = null;
  reviewStars.value = 5;
  reviewText.value = '';
  try {
    reviews.value = await loadReviews(
      projectId.value, entry.sourceId, entry.vendor, entry.kitId,
    );
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not load the reviews.';
  }
}

async function sendReview(entry: StoreEntry): Promise<void> {
  error.value = '';
  notice.value = '';
  try {
    await submitReview(
      projectId.value, entry.sourceId, entry.vendor, entry.kitId,
      reviewStars.value, reviewText.value || undefined,
    );
    // The star counts at once; the text waits to be read. Saying so beats
    // a screen that looks like the words were dropped.
    notice.value = reviewText.value
      ? 'Thanks — your rating counts now, your text is waiting to be reviewed.'
      : 'Thanks — your rating counts now.';
    reviewText.value = '';
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not send the review.';
  }
}

async function openBuy(entry: StoreEntry): Promise<void> {
  error.value = '';
  buyingPath.value = entry.path;
  buyPassword.value = '';
  withdrawalAccepted.value = false;
  withdrawalNotice.value = null;
  if (entry.priceCents <= 0) return;
  try {
    // Fetched now, not configured here: the wording belongs to whoever
    // sells, and the version confirmed has to be the one in force.
    withdrawalNotice.value = await loadWithdrawalNotice(projectId.value, entry.sourceId);
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not load the store terms.';
    buyingPath.value = '';
  }
}

/** Whether the confirm button may be pressed. */
function buyReady(entry: StoreEntry): boolean {
  if (entry.priceCents <= 0) return true;
  if (!withdrawalNotice.value?.required) return true;
  return withdrawalAccepted.value;
}

async function confirmBuy(entry: StoreEntry): Promise<void> {
  error.value = '';
  notice.value = '';
  busyPath.value = entry.path;
  try {
    const order = await buy(
      projectId.value, entry.sourceId, entry.vendor, entry.kitId,
      buyerEmail.value, buyPassword.value,
      billingCountry.value, buyVatId.value || undefined,
      withdrawalAccepted.value ? withdrawalNotice.value?.version ?? undefined : undefined,
    );
    if (order.redirectUrl) {
      // A priced kit with a real provider. Nothing is owned yet.
      notice.value = 'Continue the payment in the window that just opened.'
        + ' This page updates by itself once it goes through.';
      watchForPayment(entry);
      window.open(order.redirectUrl, '_blank', 'noopener');
    } else {
      notice.value = `Done — ${entry.displayName} is yours.`;
    }
    buyingPath.value = '';
    buyPassword.value = '';
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not complete the order.';
  } finally {
    busyPath.value = '';
  }
}

/** `19.90 EUR`, or `Free`. */
function priceOf(entry: StoreEntry): string {
  if (entry.priceCents <= 0) return 'Free';
  return `${(entry.priceCents / 100).toFixed(2)} ${entry.currency ?? ''}`.trim();
}

/** `★★★★☆` — plain text, so it needs no icon set and copies as what it is. */
function starsOf(count: number): string {
  const filled = Math.round(count);
  return '★'.repeat(filled) + '☆'.repeat(Math.max(0, 5 - filled));
}

/** What the button on a row does, if anything. */
function actionOf(entry: StoreEntry): string | null {
  if (entry.state === 'OWNED') return 'Install';
  if (entry.state === 'UPDATABLE') return 'Update';
  // An offered kit is acquired first; free or not, it becomes a purchase.
  if (entry.state === 'OFFERED') return entry.priceCents > 0 ? 'Buy' : 'Get';
  return null;
}

function expiryOf(entry: StoreEntry): string | null {
  if (!entry.licenseExpiresAt) return null;
  const when = new Date(entry.licenseExpiresAt);
  const lapsed = when.getTime() < Date.now();
  return lapsed
    ? `Licence lapsed on ${when.toLocaleDateString()} — installed kits keep working`
    : `Updates until ${when.toLocaleDateString()}`;
}

onMounted(() => {
  load();
  // Both, because they fire in different situations: switching tabs is a
  // visibility change, returning from another window is a focus event.
  document.addEventListener('visibilitychange', refreshAfterPayment);
  window.addEventListener('focus', refreshAfterPayment);
});

onUnmounted(() => {
  document.removeEventListener('visibilitychange', refreshAfterPayment);
  window.removeEventListener('focus', refreshAfterPayment);
  stopWatchingForPayment();
});
</script>

<template>
  <div class="flex flex-col gap-4">
    <!--
      Which store. First, because everything below is about one: a role is
      held at a store, not in general.
    -->
    <div v-if="showStoreTabs" class="flex gap-2 items-center flex-wrap">
      <VButton
        v-for="view in views"
        :key="view.sourceId"
        size="sm"
        :variant="activeSource === view.sourceId ? 'primary' : 'secondary'"
        :outline="activeSource !== view.sourceId"
        @click="selectSource(view.sourceId)"
      >
        {{ view.title }}
      </VButton>
    </div>

    <!--
      What you are doing there. Hidden when this account holds nothing but
      the right to buy — a strip that only ever says "Store" is furniture.
    -->
    <div v-if="showModeTabs" class="flex gap-2 items-center">
      <VButton
        v-for="entry in modes"
        :key="entry.key"
        size="sm"
        :variant="mode === entry.key ? 'primary' : 'secondary'"
        :outline="mode !== entry.key"
        @click="mode = entry.key"
      >
        {{ entry.label }}
      </VButton>
    </div>

    <DeveloperPanel
      v-if="mode === 'DEVELOPER' && activeSource"
      :project-id="projectId"
      :source-id="activeSource"
    />
    <MoneyPanel
      v-else-if="mode === 'MONEY' && activeSource"
      :project-id="projectId"
      :source-id="activeSource"
    />
    <OperatorPanel
      v-else-if="mode === 'OPERATOR' && activeSource"
      :project-id="projectId"
      :source-id="activeSource"
    />

    <template v-else>
    <VAlert v-if="error" variant="error">{{ error }}</VAlert>
    <VAlert v-if="notice" variant="info">{{ notice }}</VAlert>

    <VEmptyState
      v-if="!loading && views.length === 0"
      headline="No library configured"
      body="Add a library source in _vance/config/kit-sources.yaml of the _tenant project."
    />

    <VCard v-if="activeView" :key="activeView.sourceId">
      <!--
        The name only where the tab strip is not already carrying it. Where
        the store lives, whether it answered and who we are there belong in
        the profile — a person browsing kits picks by name, and an address
        they cannot act on from here is furniture.
      -->
      <div v-if="!showStoreTabs" class="font-semibold">{{ activeView.title }}</div>

      <!--
        Unreachable is not the same as empty: a store that could not be
        asked must not read as a store with nothing for sale. The reason
        is in the profile, where somebody can do something about it.
      -->
      <div v-if="!activeView.reachable" class="text-sm opacity-70 mt-1">
        Not available right now — see the Store tab of your profile.
      </div>
      <div v-else-if="!activeView.accountId" class="text-sm opacity-70 mt-1">
        Sign in to this store in your profile to see what you own.
      </div>

      <div v-if="activeView.reachable" class="mt-4">
        <div class="flex gap-2 mb-3 items-center">
          <VButton
            v-for="tab in tabs"
            :key="tab.key"
            size="sm"
            :variant="activeTab === tab.key ? 'primary' : 'secondary'"
            :outline="activeTab !== tab.key"
            @click="activeTab = tab.key"
          >
            {{ tab.label }}
          </VButton>
          <!-- Narrows whichever list the tabs selected, rather than
               replacing it: "installed, and something about widgets" is a
               question people actually have. -->
          <div class="ml-auto w-56">
            <VInput v-model="search" size="sm" placeholder="Search kits" />
          </div>
        </div>

        <!-- A filtered-empty list must not read as an empty store. -->
        <VEmptyState
          v-if="entriesOf(activeView).length === 0"
          :headline="search.trim() ? 'Nothing matches' : 'Nothing here'"
          :body="search.trim()
            ? `No kit in this list matches “${search.trim()}”.`
            : 'Nothing in this list yet.'"
        />

        <div
          v-for="entry in entriesOf(activeView)"
          :key="entry.path"
          class="flex items-start justify-between gap-4 py-2 border-t"
        >
          <div class="min-w-0">
            <div class="font-medium truncate">{{ entry.displayName }}</div>
            <div class="text-sm opacity-70 truncate">
              {{ entry.path }}
              <!--
                The one line that answers "is this really from them". Next to
                the coordinate, because the coordinate is the thing somebody
                would otherwise read as identity — and a handle is claimed,
                not proven.
              -->
              <span
                v-if="entry.vendorDomain"
                class="text-success"
                :title="`The vendor proved they control ${entry.vendorDomain}`"
              >· ✓ {{ entry.vendorDomain }}</span>
              <span v-if="entry.availableVersion"> · {{ entry.availableVersion }}</span>
              <span v-if="entry.installedVersion && entry.state === 'UPDATABLE'">
                (installed {{ entry.installedVersion }})
              </span>
            </div>
            <div v-if="entry.description" class="text-sm mt-1">{{ entry.description }}</div>

            <!--
              Two kinds of chip, told apart by weight: what the vendor says
              it is for, and what it actually contains. Clicking either one
              searches for it.
            -->
            <div
              v-if="entry.topics.length || entry.contains.length"
              class="flex flex-wrap gap-1 mt-1"
            >
              <button
                v-for="tag in entry.topics"
                :key="`topic-${tag}`"
                class="text-xs px-2 py-0.5 rounded-full border"
                :class="search.trim().toLowerCase() === tag
                  ? 'border-primary text-primary'
                  : 'border-base-300 opacity-80'"
                @click="filterBy(tag)"
              >{{ tag }}</button>
              <button
                v-for="tag in entry.contains"
                :key="`has-${tag}`"
                class="text-xs px-2 py-0.5 rounded-full border border-dashed"
                :class="search.trim().toLowerCase() === tag
                  ? 'border-primary text-primary'
                  : 'border-base-300 opacity-60'"
                :title="`Contains ${tag}`"
                @click="filterBy(tag)"
              >{{ tag }}</button>
            </div>
            <div v-if="expiryOf(entry)" class="text-xs mt-1 opacity-70">
              {{ expiryOf(entry) }}
            </div>
            <div class="text-xs mt-1 opacity-70">
              <span v-if="entry.ratingCount > 0">
                {{ starsOf(entry.averageStars) }}
                {{ entry.averageStars.toFixed(1) }} ({{ entry.ratingCount }})
              </span>
              <span v-else>Not rated yet</span>
              <button class="ml-2 underline" @click="openReviews(entry)">
                {{ reviewingPath === entry.path ? 'Hide reviews' : 'Reviews' }}
              </button>
            </div>

            <!--
              Buying needs the password again. The store takes this
              installation's link token for a review and for nothing that
              spends money.
            -->
            <div v-if="buyingPath === entry.path" class="mt-3 flex flex-col gap-2">
              <VInput v-model="buyerEmail" label="Store email" type="email" />
              <VInput
                v-model="buyPassword"
                label="Store password"
                type="password"
                autocomplete="current-password"
                :help="entry.licenseTermDays
                  ? `Updates for ${entry.licenseTermDays} days. What you install keeps working after that.`
                  : 'Updates without a time limit.'"
              />
              <!--
                Where the buyer is. Asked rather than derived: a kit is
                taxed where its buyer is, and the store refuses a sale
                without a country instead of guessing one.
              -->
              <VSelect
                v-model="billingCountry"
                :options="countryOptions"
                label="Country"
                help="This store sells in the EU. Elsewhere means registering for tax there first."
              />
              <VInput
                v-model="buyVatId"
                label="VAT id (optional)"
                help="For a business buyer. Recorded as given."
              />
              <!--
                The consent that ends the fourteen-day right of withdrawal.
                An active checkbox, never pre-ticked: a pre-ticked one is
                not consent, and a consent that is not consent extends the
                period to twelve months instead of ending it.
              -->
              <label v-if="withdrawalNotice?.required" class="flex gap-2 items-start text-sm">
                <input v-model="withdrawalAccepted" type="checkbox" class="mt-1" />
                <span>
                  I ask for the download to start immediately and I understand
                  that I thereby lose my right of withdrawal.
                </span>
              </label>

              <div class="flex gap-2">
                <VButton
                  size="sm"
                  :disabled="busyPath === entry.path || !buyReady(entry)"
                  @click="confirmBuy(entry)"
                >
                  {{ busyPath === entry.path ? '…' : `Confirm — ${priceOf(entry)}` }}
                </VButton>
                <VButton size="sm" variant="secondary" outline @click="buyingPath = ''">
                  Cancel
                </VButton>
              </div>
            </div>

            <div v-if="reviewingPath === entry.path" class="mt-3 pl-2 border-l">
              <!-- One list across all versions; the split is offered, not imposed. -->
              <div v-if="reviewMajors.length > 1" class="flex gap-1 mb-2 items-center">
                <button
                  class="text-xs px-2 py-0.5 rounded-full border"
                  :class="reviewMajor === null ? 'border-primary text-primary' : 'opacity-70'"
                  @click="reviewMajor = null"
                >all versions</button>
                <button
                  v-for="major in reviewMajors"
                  :key="`major-${major}`"
                  class="text-xs px-2 py-0.5 rounded-full border"
                  :class="reviewMajor === major ? 'border-primary text-primary' : 'opacity-70'"
                  @click="reviewMajor = major"
                >{{ major }}.x</button>
              </div>

              <div v-for="review in shownReviews" :key="review.reviewId" class="mb-2">
                <div class="text-xs opacity-70">
                  {{ starsOf(review.stars) }}
                  <span v-if="review.version"> · {{ review.version }}</span>
                  <span v-if="review.displayName">· {{ review.displayName }}</span>
                </div>
                <!-- A star with a version is a usable row even with no text. -->
                <div v-if="review.text" class="text-sm">{{ review.text }}</div>
              </div>
              <div v-if="shownReviews.length === 0" class="text-sm opacity-70 mb-2">
                {{ reviewMajor === null ? 'No reviews yet.' : `No reviews for ${reviewMajor}.x.` }}
              </div>

              <!--
                Reviewing needs this installation to be signed in: the store
                is asked with the link token, not with anything the browser
                holds.
              -->
              <div v-if="activeView.accountId" class="flex flex-col gap-2 mt-2">
                <div class="flex items-center gap-1">
                  <button
                    v-for="star in 5"
                    :key="star"
                    class="text-lg"
                    :aria-label="`${star} stars`"
                    @click="reviewStars = star"
                  >{{ star <= reviewStars ? '★' : '☆' }}</button>
                </div>
                <VTextarea v-model="reviewText" placeholder="Optional — a text waits for review." />
                <div>
                  <VButton size="sm" @click="sendReview(entry)">Send review</VButton>
                </div>
              </div>
              <div v-else class="text-sm opacity-70 mt-2">
                Sign in to this store to leave a review.
              </div>
            </div>
          </div>
          <div class="flex items-center gap-2 shrink-0">
            <span class="text-xs opacity-60">{{ entry.state }}</span>
            <span class="text-xs">{{ priceOf(entry) }}</span>
            <VButton
              v-if="entry.state === 'OFFERED'"
              size="sm"
              :disabled="busyPath === entry.path || !activeView.accountId"
              @click="openBuy(entry)"
            >
              {{ actionOf(entry) }}
            </VButton>
            <VButton
              v-else-if="actionOf(entry)"
              size="sm"
              :disabled="busyPath === entry.path || !entry.downloadable"
              @click="installEntry(entry)"
            >
              {{ busyPath === entry.path ? '…' : actionOf(entry) }}
            </VButton>
          </div>
        </div>
      </div>
    </VCard>
    </template>
  </div>
</template>
