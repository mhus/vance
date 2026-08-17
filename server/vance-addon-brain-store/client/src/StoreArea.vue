<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { VAlert, VButton, VCard, VEmptyState, VInput, VTextarea } from '@vance/components';
import {
  buy, connect, disconnect, install, loadOverview, loadReviews,
  loadWithdrawalNotice, submitReview,
} from './api';
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

const views = ref<StoreSourceView[]>([]);
const activeTab = ref<'ALL' | EntryState>('ALL');
const loading = ref(false);
const busyPath = ref<string>('');
const error = ref('');
const notice = ref('');

// Buying asks for the store password again — the store takes a link token
// for reviewing and for nothing that spends money.
const buyingPath = ref<string>('');
const buyPassword = ref('');
const withdrawalNotice = ref<WithdrawalNotice | null>(null);
const withdrawalAccepted = ref(false);

// Review panel, per kit. Only one is open at a time.
const reviewingPath = ref<string>('');
const reviews = ref<StoreReview[]>([]);
const reviewStars = ref(5);
const reviewText = ref('');

// Sign-in form, per source. Only one is open at a time.
const signingIn = ref<string>('');
const email = ref('');
const password = ref('');
const label = ref('');

// Reused for buying, so somebody who just signed in does not retype it.
const buyerEmail = ref('');

async function load(): Promise<void> {
  error.value = '';
  loading.value = true;
  try {
    views.value = await loadOverview(projectId.value);
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not load the store.';
  } finally {
    loading.value = false;
  }
}

function entriesOf(view: StoreSourceView): StoreEntry[] {
  if (activeTab.value === 'ALL') return view.entries;
  return view.entries.filter((entry) => entry.state === activeTab.value);
}

function openSignIn(sourceId: string): void {
  signingIn.value = sourceId;
  email.value = '';
  password.value = '';
  // A label the person will recognise in their device list at the store.
  label.value = `${projectId.value}`;
}

async function submitSignIn(sourceId: string): Promise<void> {
  error.value = '';
  notice.value = '';
  loading.value = true;
  try {
    const connection = await connect(
      projectId.value, sourceId, email.value, password.value, label.value || undefined,
    );
    notice.value = `Signed in as ${connection.accountId}.`;
    buyerEmail.value = email.value;
    signingIn.value = '';
    password.value = '';
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not sign in.';
  } finally {
    loading.value = false;
  }
}

async function signOut(sourceId: string): Promise<void> {
  error.value = '';
  notice.value = '';
  try {
    await disconnect(projectId.value, sourceId);
    notice.value = 'Signed out. The link at the store is still there — '
      + 'remove it in your device list if you meant to deauthorise this brain.';
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not sign out.';
  }
}

async function installEntry(entry: StoreEntry): Promise<void> {
  error.value = '';
  notice.value = '';
  busyPath.value = entry.path;
  try {
    const result = await install(projectId.value, entry.sourceId, entry.path);
    notice.value = `${result.kitName ?? entry.displayName}: ${result.mode?.toLowerCase() ?? 'done'}.`;
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
      withdrawalAccepted.value ? withdrawalNotice.value?.version ?? undefined : undefined,
    );
    if (order.redirectUrl) {
      // A priced kit with a real provider. Nothing is owned yet.
      notice.value = 'Continue the payment in the window that just opened.';
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

onMounted(load);
</script>

<template>
  <div class="flex flex-col gap-4">
    <VAlert v-if="error" variant="error">{{ error }}</VAlert>
    <VAlert v-if="notice" variant="info">{{ notice }}</VAlert>

    <VEmptyState
      v-if="!loading && views.length === 0"
      headline="No library configured"
      body="Add a library source in _vance/config/kit-sources.yaml of the _tenant project."
    />

    <VCard v-for="view in views" :key="view.sourceId">
      <div class="flex items-start justify-between gap-4">
        <div>
          <div class="font-semibold">{{ view.sourceId }}</div>
          <div class="text-sm opacity-70">{{ view.url }}</div>
          <div v-if="view.accountId" class="text-sm mt-1">
            Signed in as <span class="font-mono">{{ view.accountId }}</span>
          </div>
          <div v-else class="text-sm mt-1 opacity-70">Not signed in</div>
        </div>
        <div class="flex gap-2">
          <VButton v-if="view.accountId" size="sm" @click="signOut(view.sourceId)">
            Sign out
          </VButton>
          <VButton
            v-else-if="signingIn !== view.sourceId"
            size="sm"
            @click="openSignIn(view.sourceId)"
          >
            Sign in
          </VButton>
        </div>
      </div>

      <!--
        Unreachable is not the same as empty: a store that could not be
        asked must not read as a store with nothing for sale.
      -->
      <VAlert v-if="!view.reachable" variant="warning" class="mt-3">
        This store could not be reached: {{ view.problem }}
      </VAlert>

      <div v-if="signingIn === view.sourceId" class="mt-3 flex flex-col gap-2">
        <VInput v-model="email" label="Email" type="email" autocomplete="username" />
        <VInput
          v-model="password"
          label="Password"
          type="password"
          autocomplete="current-password"
        />
        <VInput
          v-model="label"
          label="Name for this brain"
          help="Shown in your device list at the store, so you can tell your machines apart."
        />
        <div class="flex gap-2">
          <VButton :disabled="loading" @click="submitSignIn(view.sourceId)">Sign in</VButton>
          <VButton variant="secondary" outline @click="signingIn = ''">Cancel</VButton>
        </div>
      </div>

      <div v-if="view.reachable" class="mt-4">
        <div class="flex gap-2 mb-3">
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
        </div>

        <VEmptyState
          v-if="entriesOf(view).length === 0"
          headline="Nothing here"
          :body="view.accountId
            ? 'Nothing in this list yet.'
            : 'Sign in to see what this account owns.'"
        />

        <div
          v-for="entry in entriesOf(view)"
          :key="entry.path"
          class="flex items-start justify-between gap-4 py-2 border-t"
        >
          <div class="min-w-0">
            <div class="font-medium truncate">{{ entry.displayName }}</div>
            <div class="text-sm opacity-70 truncate">
              {{ entry.path }}
              <span v-if="entry.availableVersion"> · {{ entry.availableVersion }}</span>
              <span v-if="entry.installedVersion && entry.state === 'UPDATABLE'">
                (installed {{ entry.installedVersion }})
              </span>
            </div>
            <div v-if="entry.description" class="text-sm mt-1">{{ entry.description }}</div>
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
              <div v-for="review in reviews" :key="review.reviewId" class="mb-2">
                <div class="text-xs opacity-70">
                  {{ starsOf(review.stars) }}
                  <span v-if="review.displayName">· {{ review.displayName }}</span>
                </div>
                <div class="text-sm">{{ review.text }}</div>
              </div>
              <div v-if="reviews.length === 0" class="text-sm opacity-70 mb-2">
                No reviews with text yet.
              </div>

              <!--
                Reviewing needs this installation to be signed in: the store
                is asked with the link token, not with anything the browser
                holds.
              -->
              <div v-if="view.accountId" class="flex flex-col gap-2 mt-2">
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
              :disabled="busyPath === entry.path || !view.accountId"
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
  </div>
</template>
