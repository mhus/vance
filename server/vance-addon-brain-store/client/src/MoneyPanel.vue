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
import { useT } from './i18n';

const props = defineProps<{ projectId: string; sourceId: string }>();

const t = useT();

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
    error.value = e instanceof Error ? e.message : t('store.money.error.load');
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
      ? t('store.money.payoutFailed', {
        payout: payout.payoutName,
        reason: payout.failureReason ?? t('store.money.noReasonGiven'),
      })
      : t('store.money.payoutStatus', {
        payout: payout.payoutName,
        amount: money(payout.amountCents, payout.currency),
        status: payout.status.toLowerCase(),
      });
  });
}

async function release(payoutName: string): Promise<void> {
  await act(async () => {
    await releasePayout(props.projectId, props.sourceId, payoutName);
    notice.value = t('store.money.released', { payout: payoutName });
  });
}

async function reconcile(): Promise<void> {
  await act(async () => {
    const result = await reconcilePayouts(props.projectId, props.sourceId);
    notice.value = t('store.money.reconciled', {
      asked: result.asked,
      arrived: result.arrived,
      failed: result.failed,
      open: result.stillOpen,
    });
  });
}

async function refund(order: SaleRow): Promise<void> {
  await act(async () => {
    const result = await refundOrder(props.projectId, props.sourceId, order.orderId,
      refundReason.value, refundAlreadyReturned.value);
    // Says all three things a refund did, because "refunded" alone leaves
    // the two that matter later unsaid.
    notice.value = t('store.money.refunded', {
      order: result.orderName,
      entitlement: result.entitlementRevoked
        ? t('store.money.entitlementRevoked')
        : t('store.money.entitlementGone'),
      share: result.vendorShare === 'CLAWED_BACK'
        ? t('store.money.shareClawedBack')
        : t('store.money.shareNeverPaid'),
    });
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
    notice.value = t('store.money.classified', {
      order: classified.orderId,
      treatment: classified.vatTreatment,
    });
    classifying.value = '';
    classifyCountry.value = '';
    classifyVatId.value = '';
  });
}

/** Not an edit: the old note is reversed in full and a new one written. */
async function reissue(payoutName: string): Promise<void> {
  await act(async () => {
    const note = await reissueCreditNote(props.projectId, props.sourceId, payoutName);
    notice.value = t('store.money.reissued', { number: note.number });
  });
}

async function runReport(): Promise<void> {
  error.value = '';
  loading.value = true;
  try {
    report.value = await loadTaxReport(props.projectId, props.sourceId,
      `${from.value}T00:00:00Z`, `${to.value}T00:00:00Z`);
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('store.money.error.report');
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
    error.value = e instanceof Error ? e.message : t('store.money.error.render');
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
    error.value = e instanceof Error ? e.message : t('store.money.error.generic');
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
          <div class="font-semibold">{{ t('store.money.owed') }}</div>
          <p class="text-sm opacity-70">
            {{ t('store.money.owedHint') }}
          </p>
        </div>
        <VButton size="sm" variant="secondary" outline :disabled="loading" @click="load">
          {{ t('store.common.refresh') }}
        </VButton>
      </div>

      <VEmptyState
        v-if="!due.length"
        :headline="t('store.money.nothingToPayHeadline')"
        :body="t('store.money.nothingToPayBody')"
      />

      <div v-for="entry in due" :key="entry.vendorName" class="py-2 border-t">
        <div class="flex items-start justify-between gap-4">
          <div class="min-w-0">
            <div class="font-medium">{{ entry.vendorName }}</div>
            <div class="text-sm opacity-70">
              {{ t('store.money.sales', { count: entry.orderCount }) }}
              · {{ t('store.money.earned', { amount: money(entry.earnedCents, entry.currency) }) }}
              <span v-if="entry.clawbackCents">
                · {{ t('store.money.refunds', { amount: money(entry.clawbackCents, entry.currency) }) }}
              </span>
              <span v-if="entry.disputedCents">
                · {{ t('store.money.disputed', { amount: money(entry.disputedCents, entry.currency) }) }}
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
            >{{ t('store.money.pay') }}</VButton>
          </div>
        </div>
      </div>
    </VCard>

    <!-- ── in flight ── -->
    <VCard>
      <div class="flex items-start justify-between gap-4">
        <div>
          <div class="font-semibold">{{ t('store.money.openPayouts') }}</div>
          <p class="text-sm opacity-70">
            {{ t('store.money.openPayoutsHint') }}
          </p>
        </div>
        <VButton size="sm" variant="secondary" outline :disabled="loading" @click="reconcile">
          {{ t('store.money.askRail') }}
        </VButton>
      </div>

      <VEmptyState
        v-if="!open.length"
        :headline="t('store.money.nothingOutstandingHeadline')"
        :body="t('store.money.nothingOutstandingBody')"
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
          >{{ t('store.money.release') }}</VButton>
        </div>
      </div>
    </VCard>

    <!-- ── giving a sale back ── -->
    <VCard>
      <div class="font-semibold">{{ t('store.money.refundsHeading') }}</div>
      <p class="text-sm opacity-70">
        {{ t('store.money.refundsHint') }}
      </p>

      <VEmptyState
        v-if="!refundable.length"
        :headline="t('store.money.nothingToRefundHeadline')"
        :body="t('store.money.nothingToRefundBody')"
      />

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
          >{{ t('store.money.refund') }}</VButton>
        </div>

        <div v-if="refunding === order.orderId" class="mt-2 flex flex-col gap-2">
          <VInput
            v-model="refundReason"
            :label="t('store.common.reason')"
            :help="t('store.money.refundReasonHelp')"
          />
          <label class="flex gap-2 items-start text-sm">
            <input v-model="refundAlreadyReturned" type="checkbox" class="mt-1" />
            <span>{{ t('store.money.chargeback') }}</span>
          </label>
          <div class="flex gap-2">
            <VButton :disabled="loading" @click="refund(order)">{{ t('store.money.confirmRefund') }}</VButton>
            <VButton variant="secondary" outline @click="refunding = ''">{{ t('store.common.cancel') }}</VButton>
          </div>
        </div>
      </div>
    </VCard>

    <!-- ── what nobody could classify ── -->
    <VCard v-if="unclassifiedOrders.length || unclassifiedNotes.length">
      <div class="font-semibold">{{ t('store.money.classification') }}</div>
      <p class="text-sm opacity-70">
        {{ t('store.money.classificationHint') }}
      </p>

      <div v-for="order in unclassifiedOrders" :key="order.orderId" class="py-2 border-t">
        <div class="flex items-start justify-between gap-4">
          <div class="min-w-0">
            <div class="font-medium truncate">{{ order.vendorName }}/{{ order.kitId }}</div>
            <div class="text-sm opacity-70">
              {{ order.orderId }} · {{ money(order.amountCents, order.currency) }} ·
              {{ order.billingCountry || t('store.money.noCountry') }}
              <span v-if="order.vatTreatment"> · {{ order.vatTreatment }}</span>
            </div>
          </div>
          <VButton
            v-if="classifying !== order.orderId"
            size="sm"
            variant="secondary"
            outline
            @click="classifying = order.orderId; classifyCountry = order.billingCountry ?? ''"
          >{{ t('store.money.classify') }}</VButton>
        </div>

        <div v-if="classifying === order.orderId" class="mt-2 flex flex-col gap-2">
          <VInput
            v-model="classifyCountry"
            :label="t('store.money.buyerCountry')"
            :help="t('store.money.buyerCountryHelp')"
          />
          <VInput
            v-model="classifyVatId"
            :label="t('store.money.vatId')"
            :help="t('store.money.vatIdHelp')"
          />
          <p class="text-sm opacity-60">
            {{ t('store.money.rateDerived') }}
          </p>
          <div class="flex gap-2">
            <VButton :disabled="loading || !classifyCountry.trim()" @click="classify(order)">
              {{ t('store.common.apply') }}
            </VButton>
            <VButton variant="secondary" outline @click="classifying = ''">{{ t('store.common.cancel') }}</VButton>
          </div>
        </div>
      </div>

      <div v-for="note in unclassifiedNotes" :key="note.number" class="py-2 border-t">
        <div class="flex items-start justify-between gap-4">
          <div class="min-w-0">
            <div class="font-medium">{{ note.number }} · {{ note.vendorName }}</div>
            <div class="text-sm opacity-70">
              {{ money(note.grossCents, note.currency) }} · {{ note.payoutName }} ·
              {{ t('store.money.noteNeedsVendor') }}
            </div>
          </div>
          <VButton
            size="sm"
            variant="secondary"
            outline
            :disabled="loading"
            @click="reissue(note.payoutName)"
          >{{ t('store.money.writeAgain') }}</VButton>
        </div>
      </div>
    </VCard>

    <!-- ── what is owed in tax ── -->
    <VCard>
      <div class="font-semibold">{{ t('store.money.tax') }}</div>
      <p class="text-sm opacity-70">
        {{ t('store.money.taxHint') }}
      </p>

      <div class="flex gap-2 items-end mt-2">
        <!-- Each field in its own box: two w-full inputs in one flex row
             fight each other and the buttons beside them. -->
        <div class="w-40">
          <VInput v-model="from" :label="t('store.money.from')" :help="t('store.money.dateHelp')" />
        </div>
        <div class="w-40">
          <VInput v-model="to" :label="t('store.money.to')" :help="t('store.money.dateHelp')" />
        </div>
        <VButton size="sm" :disabled="loading" @click="runReport">{{ t('store.money.build') }}</VButton>
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
          { key: 'domestic', label: t('store.money.section.domestic'), rows: lines(report.domestic) },
          { key: 'oss', label: t('store.money.section.oss'), rows: lines(report.oss) },
          { key: 'reverse', label: t('store.money.section.reverse'), rows: lines(report.reverseCharge) },
          { key: 'refunded', label: t('store.money.section.refunded'), rows: lines(report.refunded) },
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
            <span class="w-28 text-right">{{ t('store.money.net', { amount: money(line.netCents) }) }}</span>
            <span class="w-28 text-right">{{ t('store.money.taxLine', { amount: money(line.taxCents) }) }}</span>
            <span class="opacity-60">{{ t('store.money.sales', { count: line.orderCount }) }}</span>
          </div>
        </div>

        <div class="text-sm border-t pt-2">
          <span class="font-semibold">{{ t('store.money.taxTotal', { amount: money(report.totalTaxCents) }) }}</span>
          <span v-if="report.unclear" class="text-warning">
            · {{ t('store.money.unclear', { count: report.unclear }) }}
          </span>
        </div>
      </div>
    </VCard>
  </div>
</template>
