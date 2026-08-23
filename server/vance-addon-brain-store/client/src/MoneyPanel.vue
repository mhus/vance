<script setup lang="ts">
/**
 * The operator's money screen.
 *
 * <p>Four things that are one subject: what is owed, what is in flight, what
 * to give back, and what is owed in tax. Separating them into tabs would
 * make somebody click three times to answer "did that refund come off the
 * payout" — which is the question this screen exists for.
 *
 * <p>Every figure comes from the store; nothing is computed here. A screen
 * that recomputed a total would eventually disagree with the document it is
 * showing.
 */
import { computed, onMounted, ref } from 'vue';
import { VAlert, VButton, VCard, VEmptyState, VInput } from '@vance/components';
import {
  classifyOrder, loadMoney, loadTaxReport, loadUnclassified, openTaxReportPdf, payVendor,
  reconcilePayouts, refundOrder, reissueCreditNote, releasePayout,
} from './api';
import type { MoneyView, SaleRow, TaxLine, TaxReport, Unclassified } from './types';

const props = defineProps<{ projectId: string; sourceId: string }>();

const view = ref<MoneyView | null>(null);
const report = ref<TaxReport | null>(null);
const loading = ref(false);
const error = ref('');
const notice = ref('');

/** Only what can actually be sent gets a button; the rest says why not. */
const due = computed(() => view.value?.due ?? []);
const open = computed(() => view.value?.open ?? []);

/** Refundable sales only — the rest is noise on a refund screen. */
const refundable = computed(() => (view.value?.orders ?? [])
  .filter((order) => order.status === 'FULFILLED' || order.status === 'PAID'));

const unclassified = ref<Unclassified | null>(null);
const classifying = ref('');
const classifyCountry = ref('');
const classifyVatId = ref('');

const unclassifiedOrders = computed(() => unclassified.value?.orders ?? []);
const unclassifiedNotes = computed(() => unclassified.value?.creditNotes ?? []);

const refunding = ref('');
const refundReason = ref('');
const refundAlreadyReturned = ref(false);

// A quarter, because that is the period these are filed for.
//
// The end is exclusive, so it defaults to tomorrow: an end of "today"
// silently drops everything sold today, which is exactly the sale somebody
// opening this screen just made.
const from = ref(quarterStart());
const to = ref(tomorrow());

async function load(): Promise<void> {
  error.value = '';
  loading.value = true;
  try {
    view.value = await loadMoney(props.projectId, props.sourceId);
    if (view.value?.problem) error.value = view.value.problem;
    unclassified.value = await loadUnclassified(props.projectId, props.sourceId);
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not load the money view.';
  } finally {
    loading.value = false;
  }
}

async function pay(vendorName: string): Promise<void> {
  await act(async () => {
    const payout = await payVendor(props.projectId, props.sourceId, vendorName);
    notice.value = payout.status === 'FAILED'
      // A failed payout is not an error of this screen — it is a fact about
      // the rail, and it is on the record now.
      ? `${payout.payoutName} failed: ${payout.failureReason ?? 'no reason given'}`
      : `${payout.payoutName}: ${money(payout.amountCents, payout.currency)} ${payout.status.toLowerCase()}.`;
  });
}

async function release(payoutName: string): Promise<void> {
  await act(async () => {
    await releasePayout(props.projectId, props.sourceId, payoutName);
    notice.value = `${payoutName} released — its sales are due again.`;
  });
}

async function reconcile(): Promise<void> {
  await act(async () => {
    const result = await reconcilePayouts(props.projectId, props.sourceId);
    notice.value = `Asked about ${result.asked}: ${result.arrived} arrived,`
      + ` ${result.failed} failed, ${result.stillOpen} still open.`;
  });
}

async function refund(order: SaleRow): Promise<void> {
  await act(async () => {
    const result = await refundOrder(props.projectId, props.sourceId, order.orderId,
      refundReason.value, refundAlreadyReturned.value);
    // Says all three things a refund did, because "refunded" alone leaves
    // the two that matter later unsaid.
    notice.value = `${result.orderName} refunded — entitlement `
      + `${result.entitlementRevoked ? 'revoked' : 'was already gone'}, vendor share `
      + `${result.vendorShare === 'CLAWED_BACK' ? 'held back from their next payout' : 'never paid'}.`;
    refunding.value = '';
    refundReason.value = '';
    refundAlreadyReturned.value = false;
  });
}

/** The country is supplied; the rate is the store's to derive from it. */
async function classify(order: SaleRow): Promise<void> {
  await act(async () => {
    const classified = await classifyOrder(props.projectId, props.sourceId, order.orderId,
      classifyCountry.value.trim().toUpperCase(), classifyVatId.value.trim());
    notice.value = `${classified.orderId} is now ${classified.vatTreatment}.`;
    classifying.value = '';
    classifyCountry.value = '';
    classifyVatId.value = '';
  });
}

/** Not an edit: the old note is reversed in full and a new one written. */
async function reissue(payoutName: string): Promise<void> {
  await act(async () => {
    const note = await reissueCreditNote(props.projectId, props.sourceId, payoutName);
    notice.value = `${note.number} written — the old one was reversed in full.`;
  });
}

async function runReport(): Promise<void> {
  error.value = '';
  loading.value = true;
  try {
    report.value = await loadTaxReport(props.projectId, props.sourceId,
      `${from.value}T00:00:00Z`, `${to.value}T00:00:00Z`);
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not build the report.';
  } finally {
    loading.value = false;
  }
}

async function downloadReport(): Promise<void> {
  error.value = '';
  loading.value = true;
  try {
    await openTaxReportPdf(props.projectId, props.sourceId,
      `${from.value}T00:00:00Z`, `${to.value}T00:00:00Z`);
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not render the report.';
  } finally {
    loading.value = false;
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

function money(cents: number, currency?: string | null): string {
  return `${(cents / 100).toFixed(2)} ${currency ?? 'EUR'}`.trim();
}

function rate(basisPoints: number): string {
  return `${(basisPoints / 100).toFixed(1)} %`;
}

function tomorrow(): string {
  const now = new Date();
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1))
    .toISOString().slice(0, 10);
}

function quarterStart(): string {
  const now = new Date();
  return new Date(Date.UTC(now.getUTCFullYear(), Math.floor(now.getUTCMonth() / 3) * 3, 1))
    .toISOString().slice(0, 10);
}

function lines(section: TaxLine[] | undefined): TaxLine[] {
  return section ?? [];
}

onMounted(load);
</script>

<template>
  <div class="flex flex-col gap-4">
    <VAlert v-if="error" variant="error">{{ error }}</VAlert>
    <VAlert v-if="notice" variant="success">{{ notice }}</VAlert>

    <!-- ── what is owed to vendors ── -->
    <VCard>
      <div class="flex items-start justify-between gap-4">
        <div>
          <div class="font-semibold">Owed to vendors</div>
          <p class="text-sm opacity-70">
            Money waits out the window in which a buyer can take it back, so a
            fresh sale is not here yet.
          </p>
        </div>
        <VButton size="sm" variant="secondary" outline :disabled="loading" @click="load">
          Refresh
        </VButton>
      </div>

      <VEmptyState v-if="!due.length" headline="Nothing to pay" body="No vendor has earned anything yet." />

      <div v-for="entry in due" :key="entry.vendorName" class="py-2 border-t">
        <div class="flex items-start justify-between gap-4">
          <div class="min-w-0">
            <div class="font-medium">{{ entry.vendorName }}</div>
            <div class="text-sm opacity-70">
              {{ entry.orderCount }} sale(s) · earned {{ money(entry.earnedCents, entry.currency) }}
              <span v-if="entry.clawbackCents">
                · refunds {{ money(entry.clawbackCents, entry.currency) }}
              </span>
              <span v-if="entry.disputedCents">
                · disputed {{ money(entry.disputedCents, entry.currency) }}
              </span>
            </div>
            <div v-if="entry.blockedReason" class="text-sm text-warning">
              {{ entry.blockedReason }}
            </div>
          </div>
          <div class="text-right shrink-0">
            <div class="font-medium">{{ money(entry.amountCents, entry.currency) }}</div>
            <VButton
              v-if="entry.payable"
              size="sm"
              :disabled="loading"
              @click="pay(entry.vendorName)"
            >Pay</VButton>
          </div>
        </div>
      </div>
    </VCard>

    <!-- ── in flight ── -->
    <VCard>
      <div class="flex items-start justify-between gap-4">
        <div>
          <div class="font-semibold">Unfinished payouts</div>
          <p class="text-sm opacity-70">
            Handed to the rail and not yet confirmed — accepted is not
            arrived — and the ones that failed, which are the ones needing a
            decision.
          </p>
        </div>
        <VButton size="sm" variant="secondary" outline :disabled="loading" @click="reconcile">
          Ask the rail
        </VButton>
      </div>

      <VEmptyState
        v-if="!open.length"
        headline="Nothing outstanding"
        body="Every payout has arrived."
      />

      <div v-for="payout in open" :key="payout.payoutName" class="py-2 border-t">
        <div class="flex items-start justify-between gap-4">
          <div class="min-w-0">
            <div class="font-medium">{{ payout.vendorName }} · {{ payout.payoutName }}</div>
            <div class="text-sm opacity-70">
              {{ money(payout.amountCents, payout.currency) }} · {{ payout.status }}
              <span v-if="payout.providerRef"> · {{ payout.providerRef }}</span>
            </div>
            <div v-if="payout.failureReason" class="text-sm text-error">
              {{ payout.failureReason }}
            </div>
          </div>
          <VButton
            v-if="payout.status === 'FAILED'"
            size="sm"
            variant="secondary"
            outline
            :disabled="loading"
            @click="release(payout.payoutName)"
          >Release</VButton>
        </div>
      </div>
    </VCard>

    <!-- ── giving a sale back ── -->
    <VCard>
      <div class="font-semibold">Refunds</div>
      <p class="text-sm opacity-70">
        Three things turn round: the money, the entitlement, and the vendor's
        share. A chargeback has already moved the money — say so, and only the
        rest happens.
      </p>

      <VEmptyState v-if="!refundable.length" headline="Nothing to refund" body="No settled sale." />

      <div v-for="order in refundable" :key="order.orderId" class="py-2 border-t">
        <div class="flex items-start justify-between gap-4">
          <div class="min-w-0">
            <div class="font-medium truncate">
              {{ order.vendorName }}/{{ order.kitId }}
            </div>
            <div class="text-sm opacity-70">
              {{ order.orderId }} · {{ money(order.amountCents, order.currency) }} ·
              {{ order.status }}
            </div>
          </div>
          <VButton
            v-if="refunding !== order.orderId"
            size="sm"
            variant="secondary"
            outline
            @click="refunding = order.orderId"
          >Refund</VButton>
        </div>

        <div v-if="refunding === order.orderId" class="mt-2 flex flex-col gap-2">
          <VInput v-model="refundReason" label="Reason" help="Goes on the record, not to the buyer." />
          <label class="flex gap-2 items-start text-sm">
            <input v-model="refundAlreadyReturned" type="checkbox" class="mt-1" />
            <span>The money is already back with the buyer (a chargeback).</span>
          </label>
          <div class="flex gap-2">
            <VButton :disabled="loading" @click="refund(order)">Confirm refund</VButton>
            <VButton variant="secondary" outline @click="refunding = ''">Cancel</VButton>
          </div>
        </div>
      </div>
    </VCard>

    <!-- ── what nobody could classify ── -->
    <VCard v-if="unclassifiedOrders.length || unclassifiedNotes.length">
      <div class="font-semibold">Needs classification</div>
      <p class="text-sm opacity-70">
        Sales and notes the store could not place under a tax rule. They are
        in the report as a count; here they can be resolved. A sale whose
        receipt is already written cannot be changed — that needs a
        correction, which is a document of its own.
      </p>

      <div v-for="order in unclassifiedOrders" :key="order.orderId" class="py-2 border-t">
        <div class="flex items-start justify-between gap-4">
          <div class="min-w-0">
            <div class="font-medium truncate">{{ order.vendorName }}/{{ order.kitId }}</div>
            <div class="text-sm opacity-70">
              {{ order.orderId }} · {{ money(order.amountCents, order.currency) }} ·
              {{ order.billingCountry || 'no country on record' }}
              <span v-if="order.vatTreatment"> · {{ order.vatTreatment }}</span>
            </div>
          </div>
          <VButton
            v-if="classifying !== order.orderId"
            size="sm"
            variant="secondary"
            outline
            @click="classifying = order.orderId; classifyCountry = order.billingCountry ?? ''"
          >Classify</VButton>
        </div>

        <div v-if="classifying === order.orderId" class="mt-2 flex flex-col gap-2">
          <VInput v-model="classifyCountry" label="Buyer's country" help="Two letters, e.g. DE." />
          <VInput
            v-model="classifyVatId"
            label="VAT id"
            help="Only for a business buyer. Empty means a consumer."
          />
          <p class="text-sm opacity-60">
            The rate is not entered — it follows from the country, by the same
            rules the sale itself would have used.
          </p>
          <div class="flex gap-2">
            <VButton :disabled="loading || !classifyCountry.trim()" @click="classify(order)">
              Apply
            </VButton>
            <VButton variant="secondary" outline @click="classifying = ''">Cancel</VButton>
          </div>
        </div>
      </div>

      <div v-for="note in unclassifiedNotes" :key="note.number" class="py-2 border-t">
        <div class="flex items-start justify-between gap-4">
          <div class="min-w-0">
            <div class="font-medium">{{ note.number }} · {{ note.vendorName }}</div>
            <div class="text-sm opacity-70">
              {{ money(note.grossCents, note.currency) }} · {{ note.payoutName }} ·
              needs the vendor's country and VAT id
            </div>
          </div>
          <VButton
            size="sm"
            variant="secondary"
            outline
            :disabled="loading"
            @click="reissue(note.payoutName)"
          >Write again</VButton>
        </div>
      </div>
    </VCard>

    <!-- ── what is owed in tax ── -->
    <VCard>
      <div class="font-semibold">Tax</div>
      <p class="text-sm opacity-70">
        Counted from what each sale recorded on the day. Three sections
        because they go in three different returns.
      </p>

      <div class="flex gap-2 items-end mt-2">
        <!-- Each field in its own box: two w-full inputs in one flex row
             fight each other and the buttons beside them. -->
        <div class="w-40">
          <VInput v-model="from" label="From" help="YYYY-MM-DD" />
        </div>
        <div class="w-40">
          <VInput v-model="to" label="To (exclusive)" help="YYYY-MM-DD" />
        </div>
        <VButton size="sm" :disabled="loading" @click="runReport">Build</VButton>
        <!--
          The same period as the screen shows, rendered. Not a second
          computation — a printed report that disagreed with this one is
          the copy somebody would file.
        -->
        <VButton size="sm" variant="secondary" outline :disabled="loading" @click="downloadReport">
          PDF
        </VButton>
      </div>

      <div v-if="report" class="mt-3 flex flex-col gap-3">
        <div v-for="section in [
          { key: 'domestic', label: 'Domestic — ordinary return', rows: lines(report.domestic) },
          { key: 'oss', label: 'Other member states — OSS return', rows: lines(report.oss) },
          { key: 'reverse', label: 'Reverse charge — recapitulative statement', rows: lines(report.reverseCharge) },
          { key: 'refunded', label: 'Refunded in this period', rows: lines(report.refunded) },
        ]" :key="section.key">
          <div class="text-sm font-semibold">{{ section.label }}</div>
          <div v-if="!section.rows.length" class="text-sm opacity-60">—</div>
          <div
            v-for="line in section.rows"
            :key="`${section.key}-${line.country}-${line.rateBasisPoints}`"
            class="text-sm flex gap-3"
          >
            <span class="w-8">{{ line.country || '?' }}</span>
            <span class="w-16 text-right">{{ rate(line.rateBasisPoints) }}</span>
            <span class="w-28 text-right">net {{ money(line.netCents) }}</span>
            <span class="w-28 text-right">tax {{ money(line.taxCents) }}</span>
            <span class="opacity-60">{{ line.orderCount }} sale(s)</span>
          </div>
        </div>

        <div class="text-sm border-t pt-2">
          <span class="font-semibold">Tax total {{ money(report.totalTaxCents) }}</span>
          <span v-if="report.unclear" class="text-warning">
            · {{ report.unclear }} sale(s) carry no classification
          </span>
        </div>
      </div>
    </VCard>
  </div>
</template>
