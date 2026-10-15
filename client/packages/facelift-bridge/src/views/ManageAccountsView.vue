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
  isPinConfigured,
  setBiometricEnabled,
} from '@/lock/lockStore';
import { isBiometricSupported, tryBiometricUnlock } from '@/lock/biometric';
import { isDesktop } from '@/platform';
import { VButton, VGroup, VGroupRow, VIconButton, VToggle } from '@vance/components';

const router = useRouter();
// Desktop has no app-lock, so the whole Security section (Change PIN +
// Face ID / Touch ID) is hidden there — see platform.ts / router guard.
const isDesktopApp = isDesktop();
const accounts = ref<Account[]>([]);
const activeId = ref<string | null>(null);
const loading = ref(true);

onMounted(async () => {
  await refresh();
  if (!isDesktopApp) await refreshSecurity();
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
// The PIN is optional (see lockStore) — the Security section reads
// differently with and without one: "Set" vs "Change", and Face ID
// stays hidden until there is a PIN behind it, because the unlock
// screen has no other credential to fall back on.
const pinConfigured = ref(false);

async function refreshSecurity(): Promise<void> {
  biometricSupported.value = await isBiometricSupported();
  biometricEnabled.value = await isBiometricEnabled();
  pinConfigured.value = await isPinConfigured();
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
  //
  // Same route for "set" and "change" — the setup screen reads the
  // configured state itself to decide whether it may be declined.
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
  <div class="flex h-full flex-col bg-(--v-bg) text-(--v-label)">
    <header
      class="flex shrink-0 items-center gap-3 px-4"
      style="padding-top: max(0.75rem, env(safe-area-inset-top)); padding-bottom: 0.5rem"
    >
      <h1 class="flex-1 text-[22px] leading-7 font-semibold">Accounts</h1>
      <VButton v-if="hasAccounts" variant="plain" @click="goBack">
        Done
      </VButton>
    </header>
    <div class="flex-1 overflow-y-auto px-4 pt-2 pb-6">
      <div class="mx-auto flex max-w-[720px] flex-col gap-6">
        <p v-if="loading" class="px-4 text-sm text-(--v-label-secondary)">Loading…</p>
        <p v-else-if="accounts.length === 0" class="px-4 text-sm text-(--v-label-secondary)">
          No accounts yet.
        </p>
        <VGroup v-else>
          <VGroupRow
            v-for="a in accounts"
            :key="a.id"
            :label="a.displayName"
            :description="a.faceUrl"
            :value="a.id === activeId ? 'Active' : undefined"
            navigates
            @click="onEdit(a.id)"
          >
            <VIconButton
              icon="delete"
              label="Remove"
              tooltip="left"
              danger
              @click.stop="onRemove(a)"
            />
          </VGroupRow>
        </VGroup>

        <VGroup
          v-if="!isDesktopApp"
          label="Security"
          :note="pinConfigured ? undefined : 'No PIN set — the app opens without asking.'"
        >
          <VGroupRow
            :label="pinConfigured ? 'Change PIN' : 'Set PIN'"
            navigates
            @click="onChangePin"
          />
          <VGroupRow
            v-if="biometricSupported && pinConfigured"
            label="Use Face ID / Touch ID"
          >
            <VToggle
              :model-value="biometricEnabled"
              title="Use Face ID / Touch ID"
              @update:model-value="onToggleBiometric"
            />
          </VGroupRow>
        </VGroup>
      </div>
    </div>

    <footer
      class="border-t border-(--v-separator) px-4 pt-3"
      style="padding-bottom: max(1rem, env(safe-area-inset-bottom))"
    >
      <div class="mx-auto max-w-[720px]">
        <VButton variant="primary" icon="add" block @click="goAdd">
          Add account
        </VButton>
      </div>
    </footer>
  </div>
</template>
