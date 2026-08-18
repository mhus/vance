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
import { applyVendor, connect, disconnect, loadConnections, loadDeveloper } from './api';
import type { Connection, VendorTerms } from './types';

/** The profile is per person, so its store connections hang on `_tenant`. */
const PROJECT = '_tenant';

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
    error.value = e instanceof Error ? e.message : 'Could not read the store connections.';
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
    notice.value = `Signed in to ${entry.title} as ${result.accountId}.`;
    signingIn.value = '';
    password.value = '';
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not sign in.';
  } finally {
    loading.value = false;
  }
}

async function signOut(entry: Connection): Promise<void> {
  error.value = '';
  notice.value = '';
  try {
    await disconnect(PROJECT, entry.sourceId);
    notice.value = 'Signed out here. The link at the store is still listed among its devices — '
      + 'remove it there if you meant to deauthorise this brain.';
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not sign out.';
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
    error.value = e instanceof Error ? e.message : 'Could not read the vendor terms.';
    applying.value = '';
  }
}

async function submitApply(entry: Connection): Promise<void> {
  if (!terms.value) return;
  if (!email.value.trim() || !password.value) {
    error.value = 'Store email and password are needed — applying signs in again.';
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
    notice.value = 'Applied. You can prepare kits now; publishing waits for the store.';
    applying.value = '';
    password.value = '';
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not apply.';
  } finally {
    loading.value = false;
  }
}

/** What this account may do at a store, in words rather than flags. */
function rolesOf(entry: Connection): string {
  const roles: string[] = [];
  if (entry.developer) roles.push('developer');
  if (entry.operator) roles.push('operator');
  return roles.length ? roles.join(' · ') : 'buyer';
}

onMounted(load);
</script>

<template>
  <div class="flex flex-col gap-4">
    <VAlert v-if="error" variant="error">{{ error }}</VAlert>
    <VAlert v-if="notice" variant="info">{{ notice }}</VAlert>

    <VEmptyState
      v-if="!loading && !anyConfigured"
      headline="No store configured"
      body="Add a library source in _vance/config/kit-sources.yaml of the _tenant project."
    />

    <VCard v-for="entry in connections" :key="entry.sourceId">
      <div class="flex items-start justify-between gap-4">
        <div class="min-w-0">
          <div class="font-semibold">{{ entry.title }}</div>
          <!-- The address belongs here and nowhere else: this is the one
               screen where somebody can do something about it. -->
          <div class="text-sm opacity-60 font-mono truncate">{{ entry.url }}</div>
          <div v-if="entry.accountId" class="text-sm mt-1">
            Signed in as <span class="font-mono">{{ entry.accountId }}</span>
            <span class="opacity-70"> · {{ rolesOf(entry) }}</span>
          </div>
          <div v-else class="text-sm mt-1 opacity-70">Not signed in</div>
        </div>
        <div class="flex gap-2 shrink-0">
          <VButton v-if="entry.accountId" size="sm" @click="signOut(entry)">Sign out</VButton>
          <VButton
            v-else-if="signingIn !== entry.sourceId"
            size="sm"
            @click="openSignIn(entry)"
          >
            Sign in
          </VButton>
          <VButton
            v-if="entry.accountId && !entry.developer && applying !== entry.sourceId"
            size="sm"
            variant="secondary"
            outline
            @click="openApply(entry)"
          >
            Become a developer
          </VButton>
        </div>
      </div>

      <VAlert v-if="!entry.reachable" variant="warning" class="mt-3">
        This store could not be reached: {{ entry.problem }}
      </VAlert>

      <!-- ── sign in ── -->
      <div v-if="signingIn === entry.sourceId" class="mt-3 flex flex-col gap-2">
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
          <VButton :disabled="loading" @click="submitSignIn(entry)">Sign in</VButton>
          <VButton variant="secondary" outline @click="signingIn = ''">Cancel</VButton>
        </div>
      </div>

      <!-- ── become a developer ── -->
      <div v-if="applying === entry.sourceId && terms" class="mt-3 flex flex-col gap-2">
        <VInput
          v-model="email"
          label="Store email"
          type="email"
          autocomplete="username"
          help="Applying signs in again, and the brain holds no password."
        />
        <VInput
          v-model="vendorName"
          label="Vendor handle"
          help="Lowercase, and part of every kit coordinate. It must not claim an
                affiliation you do not have — that is the one thing a person checks."
        />
        <VInput v-model="vendorDisplayName" label="Display name" />
        <VInput
          v-model="password"
          label="Store password"
          type="password"
          autocomplete="current-password"
          help="Accepting terms is a decision by a person, so this asks again."
        />
        <div class="text-sm font-semibold mt-1">
          Vendor terms (version {{ terms.version }})
        </div>
        <pre class="text-xs whitespace-pre-wrap opacity-80 max-h-48 overflow-y-auto">{{
          terms.text
        }}</pre>
        <!-- Never pre-ticked: a pre-ticked box is not an agreement, and an
             agreement that is not one proves nothing later. -->
        <label class="flex gap-2 items-start text-sm">
          <input v-model="termsAccepted" type="checkbox" class="mt-1" />
          <span>I accept these vendor terms.</span>
        </label>
        <div class="flex gap-2">
          <VButton
            :disabled="loading || !termsAccepted || !vendorName || !password || !email"
            @click="submitApply(entry)"
          >
            Apply
          </VButton>
          <VButton variant="secondary" outline @click="applying = ''">Cancel</VButton>
        </div>
      </div>
    </VCard>
  </div>
</template>
