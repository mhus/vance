<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  VAlert, VButton, VCard, VEmptyState, VInput, VSelect, VTextarea,
} from '@vance/components';
import {
  applyVendor, claimDomain, createKit, loadDeveloper, loadProjects, loadVendorMoney,
  openCreditNotePdf, publish, renewPublishing, setPayoutAccount, verifyDomain,
} from './api';
import type {
  CreditNote, DeveloperView, Publishing, ReleaseRequest, Vendor, VendorMoneyView,
} from './types';

const props = defineProps<{ projectId: string; sourceId: string }>();

const view = ref<DeveloperView | null>(null);
const loading = ref(false);
const error = ref('');
const notice = ref('');

// Applying asks for the store password again — the same line as buying.
// A link token is this machine's credential; entering an agreement is not
// something a machine does on somebody's behalf.
const applying = ref(false);
const email = ref('');
const password = ref('');
const vendorName = ref('');
const vendorDisplayName = ref('');
const homepage = ref('');
const termsAccepted = ref(false);

const creatingKit = ref(false);
const kitVendor = ref('');
const kitId = ref('');
const kitDisplayName = ref('');
const kitDescription = ref('');
const kitPrice = ref('0');
// Free text, comma-separated. The store normalises and bounds it — what a
// kit is *for* nobody can derive, so this is the one part a vendor says.
const kitTopics = ref('');

const publishing = ref('');
const version = ref('');
const vaultPassword = ref('');

/**
 * Which project is exported when publishing.
 *
 * Named here rather than taken from the page: the addon host mounts an
 * area without saying which project is open, and "publish" would
 * otherwise depend on how somebody arrived at this screen. A kit source
 * is a deliberate choice anyway — most projects are not one.
 */
const projects = ref<{ name: string; title?: string }[]>([]);
const sourceProject = ref(props.projectId);

const approvedVendors = computed(
  () => (view.value?.vendors ?? []).filter((v) => v.status === 'APPROVED'),
);

/**
 * Whose kit this is — chosen, never typed.
 *
 * The handle is claimed once when applying and the store rejects a kit
 * under somebody else's, so a free text field here could only produce a
 * value that fails. With one approved vendor there is no choice to make
 * and the name is simply stated; with several it is a picker.
 */
/**
 * Publishing right per handle.
 *
 * Only shown where the store asks for one — a library that hands out
 * publishing for nothing should not display an empty obligation.
 */
const publishingRights = computed(() => (view.value?.publishing ?? [])
  .filter((right) => right.standing !== 'NOT_REQUIRED'));

/** Whole days from now to a date, negative once it has passed. */
function daysUntil(when: string): number {
  return Math.round((new Date(when).getTime() - Date.now()) / 86_400_000);
}

function publishingLabel(entry: Publishing): string {
  const until = entry.paidUntil ? new Date(entry.paidUntil).toLocaleDateString() : null;
  // The date alone makes somebody count. The count is the thing they came
  // for, and it is the difference between "noted" and "acted on".
  const left = entry.paidUntil ? daysUntil(entry.paidUntil) : 0;
  if (entry.standing === 'VALID') return `may publish until ${until} — ${left} day(s) left`;
  if (entry.standing === 'GRACE') {
    return `ran out on ${until} — grace period, ${-left} day(s) ago`;
  }
  return until ? `ran out on ${until}` : 'never renewed';
}

/** Near the end, but not past it — worth a colour before it is a problem. */
function endingSoon(entry: Publishing): boolean {
  return entry.standing === 'VALID' && !!entry.paidUntil && daysUntil(entry.paidUntil) <= 30;
}

/**
 * A vendor's own money, per handle.
 *
 * Loaded for the first approved handle rather than all of them: a vendor
 * with several is rare, and a screen that fetched every one on open would
 * pay for that rarity on every visit. The picker changes which.
 */
async function openNote(note: CreditNote): Promise<void> {
  error.value = '';
  try {
    await openCreditNotePdf(props.projectId, props.sourceId, moneyVendor.value, note.number);
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not render the credit note.';
  }
}

/** Every action ends with a reload: the screen shows state, not hope. */
async function act(run: () => Promise<void>): Promise<void> {
  error.value = '';
  notice.value = '';
  loading.value = true;
  try {
    await run();
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'That did not work.';
  } finally {
    loading.value = false;
  }
}

/** Claiming a domain is per handle, so the open form names one. */
const claimingDomain = ref('');
const domainInput = ref('');

function openDomain(vendor: Vendor): void {
  claimingDomain.value = vendor.name;
  domainInput.value = vendor.domain ?? '';
}

async function claim(vendor: Vendor): Promise<void> {
  await act(async () => {
    const updated = await claimDomain(
      props.projectId, props.sourceId, vendor.name, domainInput.value.trim());
    notice.value = updated.domainVerifiedAt
      ? `${updated.domain} is verified.`
      : `Publish the record shown, then check.`;
  });
}

async function verify(vendor: Vendor): Promise<void> {
  await act(async () => {
    const updated = await verifyDomain(props.projectId, props.sourceId, vendor.name);
    notice.value = `${updated.domain} is verified.`;
  });
}

const moneyVendor = ref('');
const money = ref<VendorMoneyView | null>(null);

const payoutHandle = ref('');
const payoutHolder = ref('');
const payoutCountry = ref('DE');
const payoutVatId = ref('');
const editingAccount = ref(false);

async function loadMoneyFor(vendorName: string): Promise<void> {
  moneyVendor.value = vendorName;
  try {
    money.value = await loadVendorMoney(props.projectId, props.sourceId, vendorName);
    if (money.value?.problem) error.value = money.value.problem;
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not load your payouts.';
  }
}

async function saveAccount(): Promise<void> {
  error.value = '';
  notice.value = '';
  loading.value = true;
  try {
    await setPayoutAccount(
      props.projectId, props.sourceId, moneyVendor.value, 'paypal', payoutHandle.value,
      payoutHolder.value || undefined, payoutCountry.value || undefined,
      payoutVatId.value || undefined,
    );
    // The country and VAT id are not decoration: they decide how the store's
    // own invoice for your work is taxed.
    notice.value = 'Payout account saved.';
    editingAccount.value = false;
    await loadMoneyFor(moneyVendor.value);
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not save the payout account.';
  } finally {
    loading.value = false;
  }
}

function euro(cents: number, currency?: string | null): string {
  return `${(cents / 100).toFixed(2)} ${currency ?? 'EUR'}`.trim();
}

const renewing = ref('');
const renewEmail = ref('');
const renewPassword = ref('');

async function submitRenewal(vendorName: string): Promise<void> {
  error.value = '';
  notice.value = '';
  loading.value = true;
  try {
    const order = await renewPublishing(
      props.projectId, props.sourceId, vendorName, renewEmail.value, renewPassword.value,
    );
    if (order.redirectUrl) {
      notice.value = 'Continue the payment in the window that just opened.';
      window.open(order.redirectUrl, '_blank', 'noopener');
    } else {
      notice.value = `${vendorName} may publish again.`;
    }
    renewing.value = '';
    renewPassword.value = '';
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not renew publishing.';
  } finally {
    loading.value = false;
  }
}

const vendorOptions = computed(
  () => approvedVendors.value.map((v) => ({ value: v.name, label: v.displayName
    ? `${v.displayName} (${v.name})` : v.name })),
);

async function load(): Promise<void> {
  error.value = '';
  loading.value = true;
  try {
    view.value = await loadDeveloper(props.projectId, props.sourceId);
    if (projects.value.length === 0) projects.value = await loadProjects();
    if (view.value.problem) error.value = view.value.problem;
    // Also when the current pick is no longer approved: a handle that was
    // withdrawn between two loads must not stay selected, because the only
    // thing it can still do is fail on create.
    const stillApproved = approvedVendors.value.some((v) => v.name === kitVendor.value);
    if (!stillApproved && approvedVendors.value.length) {
      kitVendor.value = approvedVendors.value[0].name;
    }
    if (!moneyVendor.value && approvedVendors.value.length) {
      await loadMoneyFor(approvedVendors.value[0].name);
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not load the developer view.';
  } finally {
    loading.value = false;
  }
}

async function submitApplication(): Promise<void> {
  error.value = '';
  notice.value = '';
  const terms = view.value?.terms;
  if (!terms) return;
  loading.value = true;
  try {
    await applyVendor(
      props.projectId, props.sourceId, email.value, password.value,
      vendorName.value, vendorDisplayName.value, terms.version,
      homepage.value || undefined,
    );
    notice.value = 'Applied. You can prepare kits now; publishing waits for the store.';
    applying.value = false;
    password.value = '';
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not apply.';
  } finally {
    loading.value = false;
  }
}

async function submitKit(): Promise<void> {
  error.value = '';
  notice.value = '';
  loading.value = true;
  try {
    const cents = Math.round(Number(kitPrice.value) * 100);
    await createKit(
      props.projectId, props.sourceId, kitVendor.value, kitId.value,
      kitDisplayName.value, kitDescription.value || undefined,
      Number.isFinite(cents) ? cents : 0,
      cents > 0 ? 'EUR' : undefined,
      kitTopics.value.split(',').map((tag) => tag.trim()).filter(Boolean),
    );
    notice.value = `${kitDisplayName.value} is in the catalogue. Publish a version next.`;
    creatingKit.value = false;
    kitId.value = '';
    kitDisplayName.value = '';
    kitDescription.value = '';
    kitTopics.value = '';
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not create the kit.';
  } finally {
    loading.value = false;
  }
}

async function submitRelease(vendor: string, kit: string): Promise<void> {
  error.value = '';
  notice.value = '';
  loading.value = true;
  try {
    await publish(
      sourceProject.value, props.sourceId, vendor, kit, version.value,
      vaultPassword.value || undefined,
    );
    notice.value = `${kit} ${version.value} submitted — it waits for the store to look at it.`;
    publishing.value = '';
    version.value = '';
    vaultPassword.value = '';
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not publish.';
  } finally {
    loading.value = false;
  }
}

function statusLabel(vendor: Vendor): string {
  if (vendor.status === 'PENDING') return 'waiting for the store';
  if (vendor.status === 'REJECTED') return `refused: ${vendor.rejectionReason ?? ''}`;
  return 'approved';
}

/** The last thing that happened, which is what somebody wants to read first. */
function lastRoundOf(request: ReleaseRequest): string {
  const round = request.rounds[request.rounds.length - 1];
  if (!round) return '';
  const said = round.message ? ` — ${round.message}` : '';
  return `${round.verdict.toLowerCase()}${said}`;
}

function priceOf(cents: number, currency?: string | null): string {
  if (cents <= 0) return 'Free';
  return `${(cents / 100).toFixed(2)} ${currency ?? ''}`.trim();
}

watch(() => [props.projectId, props.sourceId], load, { immediate: true });
</script>

<template>
  <div class="flex flex-col gap-4">
    <VAlert v-if="error" variant="error">{{ error }}</VAlert>
    <VAlert v-if="notice" variant="info">{{ notice }}</VAlert>

    <!-- What it costs to sell here, before anybody signs anything. -->
    <VCard v-if="view?.fees">
      <div class="font-semibold mb-1">What this store keeps</div>
      <div class="text-sm">
        {{ view.fees.percent }} % of each sale, at least
        {{ (view.fees.minimumFeeCents / 100).toFixed(2) }} —
        nothing at all on a free kit.
        Smallest price that can be charged:
        {{ (view.fees.minimumPriceCents / 100).toFixed(2) }}.
      </div>
    </VCard>

    <!-- ── vendor profiles ── -->
    <VCard>
      <div class="flex items-start justify-between gap-4">
        <div class="font-semibold">Vendor</div>
        <VButton v-if="!applying" size="sm" @click="applying = true">Apply</VButton>
      </div>

      <div v-for="vendor in view?.vendors ?? []" :key="vendor.name" class="mt-2 text-sm">
        <div>
          <span class="font-mono">{{ vendor.name }}</span>
          · {{ vendor.displayName }}
          · <span class="opacity-70">{{ statusLabel(vendor) }}</span>
          <span v-if="vendor.domainVerifiedAt" class="text-success">
            · ✓ {{ vendor.domain }}
          </span>
        </div>

        <!--
          A badge, never the handle: the handle sits in every kit
          coordinate and every signature payload and cannot follow a domain
          that is sold or lapses.
        -->
        <div v-if="vendor.status === 'APPROVED'" class="mt-1">
          <VButton
            v-if="claimingDomain !== vendor.name"
            size="sm"
            variant="secondary"
            outline
            @click="openDomain(vendor)"
          >{{ vendor.domainVerifiedAt ? 'Change domain' : 'Prove a domain' }}</VButton>

          <div v-else class="flex flex-col gap-2 mt-1">
            <VInput
              v-model="domainInput"
              label="Domain"
              help="Just the name, e.g. example.com — the badge next to your display
                    name. Your handle does not change."
            />
            <div class="flex gap-2">
              <VButton :disabled="loading || !domainInput.trim()" @click="claim(vendor)">
                Claim
              </VButton>
              <VButton
                v-if="vendor.domain"
                variant="secondary"
                outline
                :disabled="loading"
                @click="verify(vendor)"
              >Check now</VButton>
              <VButton variant="secondary" outline @click="claimingDomain = ''">Cancel</VButton>
            </div>
            <div v-if="vendor.domainRecord" class="text-xs">
              <div class="opacity-70">
                Publish this as a TXT record at {{ vendor.domain }}, then check:
              </div>
              <code class="font-mono break-all">{{ vendor.domainRecord }}</code>
            </div>
          </div>
        </div>
      </div>
      <VEmptyState
        v-if="!applying && (view?.vendors ?? []).length === 0"
        headline="Not a vendor yet"
        body="Apply to publish kits here. Applying grants nothing on its own — you can
              prepare kits straight away, and publishing waits for the store."
      />

      <div v-if="applying" class="mt-3 flex flex-col gap-2">
        <VInput v-model="email" label="Store email" type="email" autocomplete="username" />
        <VInput
          v-model="password"
          label="Store password"
          type="password"
          autocomplete="current-password"
          help="Used once and discarded, exactly as when signing in."
        />
        <VInput
          v-model="vendorName"
          label="Vendor handle"
          help="Lowercase, and part of every kit coordinate. It must not claim an
                affiliation you do not have — that is the one thing a person checks."
        />
        <VInput v-model="vendorDisplayName" label="Display name" />
        <VInput v-model="homepage" label="Homepage (optional)" />

        <div v-if="view?.terms" class="mt-2">
          <div class="text-sm font-semibold mb-1">
            Vendor terms (version {{ view.terms.version }})
          </div>
          <pre class="text-xs whitespace-pre-wrap opacity-80 max-h-48 overflow-y-auto">{{
            view.terms.text
          }}</pre>
          <!--
            Never pre-ticked. A pre-ticked box is not an agreement, and an
            agreement that is not one proves nothing later.
          -->
          <label class="flex gap-2 items-start text-sm mt-2">
            <input v-model="termsAccepted" type="checkbox" class="mt-1" />
            <span>I accept these vendor terms.</span>
          </label>
        </div>

        <div class="flex gap-2">
          <VButton :disabled="loading || !termsAccepted" @click="submitApplication">
            Apply
          </VButton>
          <VButton variant="secondary" outline @click="applying = false">Cancel</VButton>
        </div>
      </div>
    </VCard>

    <!-- ── publishing right ── -->
    <VCard v-if="publishingRights.length">
      <div class="font-semibold">Publishing</div>
      <p class="text-sm opacity-70">
        Renewed once a year, per handle. Without it: no new kits and nothing
        paid — free kits stay in the catalogue and may still receive versions.
      </p>

      <div v-for="right in publishingRights" :key="right.vendorName" class="py-2 border-t">
        <div class="flex items-start justify-between gap-4">
          <div class="min-w-0">
            <div class="font-medium">{{ right.vendorName }}</div>
            <div
              class="text-sm"
              :class="right.standing === 'EXPIRED' ? 'text-error'
                : right.standing === 'GRACE' || endingSoon(right) ? 'text-warning'
                : 'opacity-70'"
            >{{ publishingLabel(right) }}</div>
          </div>
          <VButton
            v-if="renewing !== right.vendorName"
            size="sm"
            @click="renewing = right.vendorName"
          >Renew — {{ (right.renewalPriceCents / 100).toFixed(2) }} {{ right.currency }}</VButton>
        </div>

        <div v-if="renewing === right.vendorName" class="mt-2 flex flex-col gap-2">
          <VInput v-model="renewEmail" label="Store email" type="email" autocomplete="username" />
          <VInput
            v-model="renewPassword"
            label="Store password"
            type="password"
            autocomplete="current-password"
            help="Used once and discarded, exactly as when buying a kit."
          />
          <div class="flex gap-2">
            <VButton :disabled="loading" @click="submitRenewal(right.vendorName)">
              Confirm
            </VButton>
            <VButton variant="secondary" outline @click="renewing = ''">Cancel</VButton>
          </div>
        </div>
      </div>
    </VCard>

    <!-- ── your money ── -->
    <VCard v-if="approvedVendors.length && money">
      <div class="flex items-start justify-between gap-4">
        <div>
          <div class="font-semibold">Your money</div>
          <p class="text-sm opacity-70">
            Paid out per handle. A sale waits out the buyer's window before
            its share can leave.
          </p>
        </div>
        <VSelect
          v-if="vendorOptions.length > 1"
          :model-value="moneyVendor"
          :options="vendorOptions"
          @update:model-value="(v: string | null) => v && loadMoneyFor(v)"
        />
      </div>

      <div v-if="money.due" class="py-2 border-t">
        <div class="flex items-baseline justify-between gap-4">
          <div class="text-sm opacity-70">
            {{ money.due.orderCount }} sale(s) · earned
            {{ euro(money.due.earnedCents, money.due.currency) }}
            <span v-if="money.due.clawbackCents">
              · refunds {{ euro(money.due.clawbackCents, money.due.currency) }}
            </span>
            <span v-if="money.due.disputedCents">
              · disputed {{ euro(money.due.disputedCents, money.due.currency) }}
            </span>
          </div>
          <div class="font-medium">{{ euro(money.due.amountCents, money.due.currency) }}</div>
        </div>
        <div v-if="money.due.blockedReason" class="text-sm text-warning">
          {{ money.due.blockedReason }}
        </div>
      </div>

      <!-- where it goes -->
      <div class="py-2 border-t">
        <div class="flex items-center justify-between gap-4">
          <div class="text-sm font-semibold">Payout account</div>
          <VButton
            v-if="!editingAccount"
            size="sm"
            variant="secondary"
            outline
            @click="editingAccount = true"
          >Change</VButton>
        </div>
        <div v-if="editingAccount" class="mt-2 flex flex-col gap-2">
          <VInput
            v-model="payoutHandle"
            label="PayPal address"
            help="This store pays out via PayPal."
          />
          <VInput v-model="payoutHolder" label="Account holder (optional)" />
          <VInput
            v-model="payoutCountry"
            label="Country"
            help="Decides how the store's own invoice for your work is taxed."
          />
          <VInput v-model="payoutVatId" label="VAT id (optional)" />
          <div class="flex gap-2">
            <VButton :disabled="loading || !payoutHandle" @click="saveAccount">Save</VButton>
            <VButton variant="secondary" outline @click="editingAccount = false">Cancel</VButton>
          </div>
        </div>
      </div>

      <!-- what was sent -->
      <div v-for="payout in money.payouts" :key="payout.payoutName" class="py-2 border-t text-sm">
        <div class="flex justify-between gap-4">
          <span>{{ payout.payoutName }} · {{ payout.orderCount }} sale(s)</span>
          <span>{{ euro(payout.amountCents, payout.currency) }} · {{ payout.status }}</span>
        </div>
        <div v-if="payout.failureReason" class="text-error">{{ payout.failureReason }}</div>
      </div>

      <!-- what to book against -->
      <div v-if="money.creditNotes.length" class="py-2 border-t">
        <div class="text-sm font-semibold mb-1">Credit notes</div>
        <div v-for="note in money.creditNotes" :key="note.number" class="text-sm flex gap-3">
          <span class="w-40">{{ note.number }}</span>
          <span class="w-24 text-right">{{ euro(note.grossCents, note.currency) }}</span>
          <span class="opacity-70">
            {{ note.kind === 'CORRECTION' ? `corrects ${note.correctsNumber}` : note.treatment }}
          </span>
          <!-- A vendor books this document; booking needs the document. -->
          <button class="underline opacity-70" @click="openNote(note)">PDF</button>
        </div>
      </div>
    </VCard>

    <!-- ── catalogue entries ── -->
    <VCard v-if="approvedVendors.length">
      <div class="flex items-start justify-between gap-4">
        <div class="font-semibold">My kits</div>
        <VButton v-if="!creatingKit" size="sm" @click="creatingKit = true">New kit</VButton>
      </div>

      <div
        v-for="kit in view?.kits ?? []"
        :key="`${kit.vendorName}/${kit.kitId}`"
        class="py-2 border-t"
      >
        <div class="flex items-start justify-between gap-4">
          <div class="min-w-0">
            <div class="font-medium truncate">{{ kit.displayName }}</div>
            <div class="text-sm opacity-70">
              {{ kit.vendorName }}/{{ kit.kitId }} · {{ priceOf(kit.priceCents, kit.currency) }}
              <span v-if="kit.version"> · published {{ kit.version }}</span>
            </div>
          </div>
          <VButton
            v-if="publishing !== `${kit.vendorName}/${kit.kitId}`"
            size="sm"
            @click="publishing = `${kit.vendorName}/${kit.kitId}`"
          >
            Publish this project
          </VButton>
        </div>

        <div
          v-if="publishing === `${kit.vendorName}/${kit.kitId}`"
          class="mt-2 flex flex-col gap-2"
        >
          <label class="text-sm">
            Project to export
            <select v-model="sourceProject" class="ml-2">
              <option v-for="project in projects" :key="project.name" :value="project.name">
                {{ project.title || project.name }}
              </option>
            </select>
          </label>
          <VInput
            v-model="version"
            label="Version"
            help="A published version is never overwritten. Corrections before approval
                  reuse it; anything already live needs a new one."
          />
          <VInput
            v-model="vaultPassword"
            label="Vault password (only with encrypted settings)"
            type="password"
          />
          <div class="text-xs opacity-70">
            This exports the chosen project as a kit and uploads it. The project has
            to be a kit source — that is, carry an authoring manifest.
          </div>
          <div class="flex gap-2">
            <VButton
              size="sm"
              :disabled="loading || !version"
              @click="submitRelease(kit.vendorName, kit.kitId)"
            >
              {{ loading ? '…' : 'Export and submit' }}
            </VButton>
            <VButton size="sm" variant="secondary" outline @click="publishing = ''">
              Cancel
            </VButton>
          </div>
        </div>
      </div>

      <div v-if="creatingKit" class="mt-3 flex flex-col gap-2 border-t pt-3">
        <VSelect
          v-if="vendorOptions.length > 1"
          v-model="kitVendor"
          :options="vendorOptions"
          label="Vendor"
        />
        <div v-else class="text-sm">
          <span class="opacity-70">Vendor</span>
          <div class="font-medium">{{ kitVendor }}</div>
        </div>
        <VInput v-model="kitId" label="Kit id" help="Part of the address. It cannot change later." />
        <VInput v-model="kitDisplayName" label="Display name" />
        <VTextarea v-model="kitDescription" placeholder="What this kit is for." />
        <VInput
          v-model="kitTopics"
          label="Topics"
          help="What this kit is for — comma separated, e.g. security, onboarding.
                What it contains is read off the release; you do not tag that."
        />
        <VInput
          v-model="kitPrice"
          label="Price"
          help="0 is free. A store with no payment provider takes free kits only."
        />
        <div class="flex gap-2">
          <VButton :disabled="loading" @click="submitKit">Create</VButton>
          <VButton variant="secondary" outline @click="creatingKit = false">Cancel</VButton>
        </div>
      </div>
    </VCard>

    <!-- ── release requests ── -->
    <VCard v-if="(view?.requests ?? []).length">
      <div class="font-semibold mb-2">Submissions</div>
      <div v-for="request in view?.requests ?? []" :key="request.requestId" class="py-2 border-t">
        <div class="flex items-baseline justify-between gap-4">
          <div class="font-medium">
            {{ request.vendorName }}/{{ request.kitId }} {{ request.version }}
          </div>
          <div class="text-xs opacity-70">{{ request.status }}</div>
        </div>
        <div class="text-sm">{{ lastRoundOf(request) }}</div>
        <!--
          The whole thread, not just the newest verdict: in round three you
          should still be able to read what round one objected to.
        -->
        <details v-if="request.rounds.length > 1" class="mt-1">
          <summary class="text-xs cursor-pointer opacity-70">
            {{ request.rounds.length }} rounds
          </summary>
          <div v-for="round in request.rounds" :key="round.no" class="text-xs mt-1 opacity-80">
            {{ round.no }}. {{ round.verdict }} ({{ round.source.toLowerCase() }})
            <span v-if="round.message">— {{ round.message }}</span>
          </div>
        </details>
      </div>
    </VCard>

    <VEmptyState
      v-if="view && !view.connected"
      headline="Not signed in"
      body="Sign in to this store on the Store tab first. The terms and the fees above
            are readable without it."
    />
  </div>
</template>
