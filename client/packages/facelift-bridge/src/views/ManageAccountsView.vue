<script setup lang="ts">
import { onMounted, computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { VanceAccountWebView } from '@vance/facelift-account-webview';
import {
  type Account,
  getActiveAccountId,
  listAccounts,
  removeAccount,
} from '@/accounts/accountStore';
import {
  isBiometricEnabled,
  setBiometricEnabled,
} from '@/lock/lockStore';
import { isBiometricSupported, tryBiometricUnlock } from '@/lock/biometric';

const router = useRouter();
const accounts = ref<Account[]>([]);
const activeId = ref<string | null>(null);
const loading = ref(true);

onMounted(async () => {
  await refresh();
  await refreshSecurity();
  loading.value = false;
});

async function refresh(): Promise<void> {
  accounts.value = sortByLastUsed(await listAccounts());
  activeId.value = await getActiveAccountId();
}

function sortByLastUsed(list: Account[]): Account[] {
  return [...list].sort((a, b) => b.lastUsedAt - a.lastUsedAt);
}

function onEdit(id: string): void {
  void router.push({ name: 'edit', params: { id } });
}

const biometricSupported = ref(false);
const biometricEnabled = ref(false);

async function refreshSecurity(): Promise<void> {
  biometricSupported.value = await isBiometricSupported();
  biometricEnabled.value = await isBiometricEnabled();
}

async function onToggleBiometric(): Promise<void> {
  const desired = !biometricEnabled.value;
  if (desired) {
    // Verify the user actually has biometric enrolled + can pass it
    // before flipping the pref. Otherwise we'd be in a "Face-ID
    // enabled but the user has no face enrolled" state where the
    // unlock screen offers a button that always fails.
    const ok = await tryBiometricUnlock();
    if (!ok) return;
  }
  await setBiometricEnabled(desired);
  biometricEnabled.value = desired;
}

function onChangePin(): void {
  // For v1 this just re-runs the setup flow which overwrites the
  // stored hash. v2 should add a "verify current PIN" step before
  // accepting the new one. The app being unlocked is the current
  // implicit authority for the change.
  void router.push({ name: 'lock-setup', query: { next: '/manage' } });
}

async function onRemove(a: Account): Promise<void> {
  if (!confirm(`Remove "${a.displayName}"?`)) return;
  await removeAccount(a.id);
  // Tear down the cached native WebView for this account and wipe
  // its persistent WKWebsiteDataStore. A future re-add of the same
  // faceUrl starts with a clean cookie jar.
  await VanceAccountWebView.remove({ accountId: a.id });
  await refresh();
}

function goAdd(): void {
  void router.push({ name: 'add' });
}

function goBack(): void {
  // Only meaningful when there *is* an active account to return to.
  // The router guard handles "no accounts" by staying on /manage.
  void router.replace({ name: 'shell' });
}

const hasAccounts = computed(() => accounts.value.length > 0);
</script>

<template>
  <div class="flex h-full flex-col">
    <header
      class="flex shrink-0 items-center gap-3 border-b border-gray-800 bg-gray-900 px-3"
      style="padding-top: env(safe-area-inset-top); padding-bottom: 0.5rem"
    >
      <button
        v-if="hasAccounts"
        type="button"
        class="px-1 py-2 text-sm text-blue-400"
        @click="goBack"
      >
        Done
      </button>
      <h1 class="flex-1 text-sm font-semibold">Accounts</h1>
    </header>
    <div class="flex-1 overflow-y-auto p-4">
      <p v-if="loading" class="text-sm text-gray-400">Loading…</p>
      <p v-else-if="accounts.length === 0" class="text-sm text-gray-400">
        No accounts yet.
      </p>
      <ul v-else class="space-y-2">
        <li
          v-for="a in accounts"
          :key="a.id"
          class="flex items-center justify-between rounded border border-gray-800 bg-gray-900 p-3"
          :class="a.id === activeId ? 'ring-1 ring-blue-500' : ''"
        >
          <button
            type="button"
            class="min-w-0 flex-1 text-left"
            @click="onEdit(a.id)"
          >
            <p class="truncate font-medium">{{ a.displayName }}</p>
            <p class="mt-1 truncate text-xs text-gray-500">{{ a.faceUrl }}</p>
            <p v-if="a.id === activeId" class="mt-1 text-xs text-blue-400">Active</p>
          </button>
          <button
            type="button"
            class="ml-3 text-xs text-red-400"
            @click="onRemove(a)"
          >
            Remove
          </button>
        </li>
      </ul>
    </div>
    <section class="border-t border-gray-800 px-4 py-3">
      <h2 class="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-400">
        Security
      </h2>
      <button
        type="button"
        class="flex w-full items-center justify-between rounded border border-gray-800 bg-gray-900 px-3 py-2 text-left text-sm"
        @click="onChangePin"
      >
        <span>Change PIN</span>
        <span class="text-gray-500">›</span>
      </button>
      <label
        v-if="biometricSupported"
        class="mt-2 flex w-full items-center justify-between rounded border border-gray-800 bg-gray-900 px-3 py-2 text-sm"
      >
        <span>Use Face ID / Touch ID</span>
        <input
          type="checkbox"
          :checked="biometricEnabled"
          class="h-5 w-5"
          @change="onToggleBiometric"
        />
      </label>
    </section>

    <footer
      class="border-t border-gray-800 p-4"
      style="padding-bottom: max(1rem, env(safe-area-inset-bottom))"
    >
      <button
        type="button"
        class="w-full rounded bg-blue-500 px-3 py-2 font-medium text-white"
        @click="goAdd"
      >
        Add account
      </button>
    </footer>
  </div>
</template>
