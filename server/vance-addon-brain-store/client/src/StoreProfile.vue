<script setup lang="ts">
/**
 * The store's tab on the profile screen.
 *
 * Everything about *belonging* to a store lives here: which stores are
 * configured, where they are, whether they answered, who this
 * installation is signed in as, and which roles that account holds. Those
 * are properties of a person, and the profile is where a person's
 * properties belong.
 *
 * The store area itself shows none of it — somebody browsing kits picks by
 * the name of the place, not by its address, and an error they cannot act
 * on from there is furniture.
 */
import { computed, onMounted, ref } from 'vue';
import { VAlert, VButton, VCard, VEmptyState, VInput } from '@vance/components';
import {
  applyVendor, connect, disconnect, loadConnections, loadDeveloper, loadReceipts,
  openInvoicePdf,
} from './api';
import type { Connection, Receipt, VendorTerms } from './types';
import { useT } from './i18n';

/** The profile is per person, so its store connections hang on `_tenant`. */
const PROJECT = '_tenant';

const t = useT();

const connections = ref<Connection[]>([]);
const loading = ref(false);
const error = ref('');
const notice = ref('');

const signingIn = ref('');
const email = ref('');
const password = ref('');
const label = ref('');

// Applying as a developer asks for the password again: accepting terms is
// a decision by a person, and this installation's link must not enter an
// agreement on their behalf.
const applying = ref('');
const terms = ref<VendorTerms | null>(null);
const termsAccepted = ref(false);
const vendorName = ref('');
const vendorDisplayName = ref('');

const anyConfigured = computed(() => connections.value.length > 0);

async function load(): Promise<void> {
  error.value = '';
  loading.value = true;
  try {
    connections.value = await loadConnections(PROJECT);
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('store.profile.error.connections');
  } finally {
    loading.value = false;
  }
}

function openSignIn(entry: Connection): void {
  signingIn.value = entry.sourceId;
  email.value = '';
  password.value = '';
  label.value = window.location.hostname;
}

async function submitSignIn(entry: Connection): Promise<void> {
  error.value = '';
  notice.value = '';
  loading.value = true;
  try {
    const result = await connect(
      PROJECT, entry.sourceId, email.value, password.value, label.value || undefined,
    );
    notice.value = t('store.profile.signedInNotice', { store: entry.title, account: result.accountId });
    signingIn.value = '';
    password.value = '';
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('store.profile.error.signIn');
  } finally {
    loading.value = false;
  }
}

async function signOut(entry: Connection): Promise<void> {
  error.value = '';
  notice.value = '';
  try {
    await disconnect(PROJECT, entry.sourceId);
    notice.value = t('store.profile.signedOutNotice');
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('store.profile.error.signOut');
  }
}

async function openApply(entry: Connection): Promise<void> {
  error.value = '';
  applying.value = entry.sourceId;
  terms.value = null;
  termsAccepted.value = false;
  vendorName.value = '';
  vendorDisplayName.value = '';
  password.value = '';
  // The email is deliberately left alone: applying signs in again, and the
  // form used to clear the very field it signs in with — which sent an
  // empty address and produced "the store returned HTTP 400 when signing
  // in", a sentence about the wrong thing entirely.
  try {
    terms.value = (await loadDeveloper(PROJECT, entry.sourceId)).terms ?? null;
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('store.profile.error.terms');
    applying.value = '';
  }
}

async function submitApply(entry: Connection): Promise<void> {
  if (!terms.value) return;
  if (!email.value.trim() || !password.value) {
    error.value = t('store.profile.credentialsNeeded');
    return;
  }
  error.value = '';
  notice.value = '';
  loading.value = true;
  try {
    await applyVendor(
      PROJECT, entry.sourceId, email.value, password.value,
      vendorName.value, vendorDisplayName.value, terms.value.version,
    );
    notice.value = t('store.profile.appliedNotice');
    applying.value = '';
    password.value = '';
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('store.profile.error.apply');
  } finally {
    loading.value = false;
  }
}

/** What this account may do at a store, in words rather than flags. */
/** Receipts are per account, so the open list names one connection. */
const receiptsFor = ref('');
const receipts = ref<Receipt[]>([]);

async function showReceipts(entry: Connection): Promise<void> {
  error.value = '';
  loading.value = true;
  try {
    receipts.value = await loadReceipts(PROJECT, entry.sourceId);
    receiptsFor.value = entry.sourceId;
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('store.profile.error.receipts');
  } finally {
    loading.value = false;
  }
}

async function openReceipt(entry: Connection, receipt: Receipt): Promise<void> {
  error.value = '';
  try {
    await openInvoicePdf(PROJECT, entry.sourceId, receipt.orderName);
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('store.profile.error.receipt');
  }
}

function rolesOf(entry: Connection): string {
  const roles: string[] = [];
  if (entry.developer) roles.push(t('store.profile.role.developer'));
  if (entry.operator) roles.push(t('store.profile.role.operator'));
  return roles.length ? roles.join(' · ') : t('store.profile.role.buyer');
}

onMounted(load);
</script>

<template>
  <div class="flex flex-col gap-4">
    <VAlert v-if="error" variant="error">{{ error }}</VAlert>
    <VAlert v-if="notice" variant="info">{{ notice }}</VAlert>

    <VEmptyState
      v-if="!loading && !anyConfigured"
      :headline="t('store.profile.noStoreHeadline')"
      :body="t('store.profile.noStoreBody')"
    />

    <VCard v-for="entry in connections" :key="entry.sourceId">
      <div class="flex items-start justify-between gap-4">
        <div class="min-w-0">
          <div class="font-semibold">{{ entry.title }}</div>
          <!-- The address belongs here and nowhere else: this is the one
               screen where somebody can do something about it. -->
          <div class="text-sm opacity-60 font-mono truncate">{{ entry.url }}</div>
          <div v-if="entry.accountId" class="text-sm mt-1">
            {{ t('store.profile.signedInAs') }}
            <span class="font-mono">{{ entry.accountId }}</span>
            <span class="opacity-70"> · {{ rolesOf(entry) }}</span>
          </div>
          <div v-else class="text-sm mt-1 opacity-70">{{ t('store.profile.notSignedIn') }}</div>
        </div>
        <div class="flex gap-2 shrink-0">
          <VButton v-if="entry.accountId" size="sm" @click="signOut(entry)">{{ t('store.profile.signOut') }}</VButton>
          <VButton
            v-else-if="signingIn !== entry.sourceId"
            size="sm"
            @click="openSignIn(entry)"
          >
            {{ t('store.profile.signIn') }}
          </VButton>
          <VButton
            v-if="entry.accountId && !entry.developer && applying !== entry.sourceId"
            size="sm"
            variant="secondary"
            outline
            @click="openApply(entry)"
          >
            {{ t('store.profile.becomeDeveloper') }}
          </VButton>
        </div>
      </div>

      <VAlert v-if="!entry.reachable" variant="warning" class="mt-3">
        {{ t('store.profile.unreachable', { problem: entry.problem }) }}
      </VAlert>

      <!--
        Receipts belong to the account, so they live where the account
        does. A buyer files these; a list that only showed a number would
        be a list nobody can use.
      -->
      <div v-if="entry.accountId" class="mt-3">
        <VButton
          v-if="receiptsFor !== entry.sourceId"
          size="sm"
          variant="secondary"
          outline
          :disabled="loading"
          @click="showReceipts(entry)"
        >{{ t('store.profile.receipts') }}</VButton>

        <div v-else class="flex flex-col gap-1">
          <div class="text-sm font-semibold">{{ t('store.profile.receipts') }}</div>
          <div v-if="!receipts.length" class="text-sm opacity-70">
            {{ t('store.profile.nothingBought') }}
          </div>
          <div v-for="receipt in receipts" :key="receipt.number" class="text-sm flex gap-3">
            <span class="w-40 font-mono">{{ receipt.number }}</span>
            <span class="w-24 text-right">
              {{ (receipt.grossCents / 100).toFixed(2) }} {{ receipt.currency ?? 'EUR' }}
            </span>
            <span class="opacity-70 truncate">
              {{ receipt.kitDisplayName ?? `${receipt.vendorName}/${receipt.kitId}` }}
            </span>
            <button class="underline opacity-70" @click="openReceipt(entry, receipt)">{{ t('store.common.pdf') }}</button>
          </div>
          <div>
            <VButton size="sm" variant="secondary" outline @click="receiptsFor = ''">
              {{ t('store.common.hide') }}
            </VButton>
          </div>
        </div>
      </div>

      <!-- ── sign in ── -->
      <div v-if="signingIn === entry.sourceId" class="mt-3 flex flex-col gap-2">
        <VInput v-model="email" :label="t('store.profile.email')" type="email" autocomplete="username" />
        <VInput
          v-model="password"
          :label="t('store.profile.password')"
          type="password"
          autocomplete="current-password"
        />
        <VInput
          v-model="label"
          :label="t('store.profile.brainName')"
          :help="t('store.profile.brainNameHelp')"
        />
        <div class="flex gap-2">
          <VButton :disabled="loading" @click="submitSignIn(entry)">{{ t('store.profile.signIn') }}</VButton>
          <VButton variant="secondary" outline @click="signingIn = ''">{{ t('store.common.cancel') }}</VButton>
        </div>
      </div>

      <!-- ── become a developer ── -->
      <div v-if="applying === entry.sourceId && terms" class="mt-3 flex flex-col gap-2">
        <VInput
          v-model="email"
          :label="t('store.area.storeEmail')"
          type="email"
          autocomplete="username"
          :help="t('store.profile.applyEmailHelp')"
        />
        <VInput
          v-model="vendorName"
          :label="t('store.profile.vendorHandle')"
          :help="t('store.profile.vendorHandleHelp')"
        />
        <VInput v-model="vendorDisplayName" :label="t('store.profile.displayName')" />
        <VInput
          v-model="password"
          :label="t('store.area.storePassword')"
          type="password"
          autocomplete="current-password"
          :help="t('store.profile.passwordAgainHelp')"
        />
        <div class="text-sm font-semibold mt-1">
          {{ t('store.profile.terms', { version: terms.version }) }}
        </div>
        <pre class="text-xs whitespace-pre-wrap opacity-80 max-h-48 overflow-y-auto">{{
          terms.text
        }}</pre>
        <!-- Never pre-ticked: a pre-ticked box is not an agreement, and an
             agreement that is not one proves nothing later. -->
        <label class="flex gap-2 items-start text-sm">
          <input v-model="termsAccepted" type="checkbox" class="mt-1" />
          <span>{{ t('store.profile.acceptTerms') }}</span>
        </label>
        <div class="flex gap-2">
          <VButton
            :disabled="loading || !termsAccepted || !vendorName || !password || !email"
            @click="submitApply(entry)"
          >
            {{ t('store.common.apply') }}
          </VButton>
          <VButton variant="secondary" outline @click="applying = ''">{{ t('store.common.cancel') }}</VButton>
        </div>
      </div>
    </VCard>
  </div>
</template>
