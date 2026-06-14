<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { VanceAccountWebView } from '@vance/facelift-account-webview';
import { getAccount, updateAccount } from '@/accounts/accountStore';
import { verifyVanceUrl } from '@/accounts/verifyVanceUrl';

const route = useRoute();
const router = useRouter();

const accountId = ref<string>('');
const brainUrl = ref<string>('');
const displayName = ref<string>('');
const submitting = ref(false);
const error = ref<string | null>(null);
const notFound = ref(false);

onMounted(async () => {
  const id = String(route.params.id ?? '');
  if (!id) {
    notFound.value = true;
    return;
  }
  const account = await getAccount(id);
  if (account === null) {
    notFound.value = true;
    return;
  }
  accountId.value = account.id;
  brainUrl.value = account.brainUrl;
  displayName.value = account.displayName;
});

async function onSubmit(): Promise<void> {
  if (submitting.value) return;
  error.value = null;
  const url = brainUrl.value.trim();
  if (url.length === 0) {
    error.value = 'Brain URL is required.';
    return;
  }
  try {
    // eslint-disable-next-line no-new
    new URL(url);
  } catch {
    error.value = 'Brain URL is not a valid URL.';
    return;
  }
  submitting.value = true;
  try {
    // Verify the new URL really is a Vance instance — but only
    // when it actually changed; renaming the displayName alone
    // shouldn't round-trip to the server.
    const current = await getAccount(accountId.value);
    if (current === null) {
      error.value = 'Account no longer exists.';
      return;
    }
    if (url !== current.brainUrl) {
      const verify = await verifyVanceUrl(url);
      if (!verify.ok) {
        error.value = `Not a Vance instance (${verify.reason ?? 'unknown'})`;
        return;
      }
    }
    const result = await updateAccount(accountId.value, {
      brainUrl: url,
      displayName: displayName.value,
    });
    if (result === null) {
      error.value = 'Account no longer exists.';
      return;
    }
    if (result.brainUrlChanged) {
      // Wipe the cached native WebView + its persistent data store —
      // a new origin needs a clean cookie jar and the cached WebView
      // is still pointing at the old URL.
      await VanceAccountWebView.remove({ accountId: accountId.value });
    }
    void router.replace({ name: 'manage' });
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to save.';
  } finally {
    submitting.value = false;
  }
}

function onCancel(): void {
  void router.back();
}
</script>

<template>
  <div class="flex h-full flex-col">
    <header
      class="flex shrink-0 items-center gap-3 border-b border-gray-800 bg-gray-900 px-3"
      style="padding-top: env(safe-area-inset-top); padding-bottom: 0.5rem"
    >
      <button
        type="button"
        class="px-1 py-2 text-sm text-blue-400"
        @click="onCancel"
      >
        Cancel
      </button>
      <h1 class="flex-1 text-sm font-semibold">Edit account</h1>
      <button
        type="button"
        :disabled="submitting || notFound"
        class="px-1 py-2 text-sm font-medium text-blue-400 disabled:opacity-50"
        @click="onSubmit"
      >
        Save
      </button>
    </header>
    <div v-if="notFound" class="flex-1 p-4 text-sm text-red-400">
      Account not found.
    </div>
    <form v-else class="flex-1 space-y-4 overflow-y-auto p-4" @submit.prevent="onSubmit">
      <label class="block">
        <span class="mb-1 block text-xs uppercase tracking-wide text-gray-400">Brain URL</span>
        <input
          v-model="brainUrl"
          type="url"
          autocomplete="off"
          autocapitalize="none"
          spellcheck="false"
          inputmode="url"
          class="w-full rounded border border-gray-700 bg-gray-800 px-3 py-2 outline-none focus:border-blue-400"
        />
        <p class="mt-1 text-xs text-gray-500">
          Changing the URL wipes this account's local session — you will need to sign in again.
        </p>
      </label>
      <label class="block">
        <span class="mb-1 block text-xs uppercase tracking-wide text-gray-400">Label</span>
        <input
          v-model="displayName"
          type="text"
          autocomplete="off"
          class="w-full rounded border border-gray-700 bg-gray-800 px-3 py-2 outline-none focus:border-blue-400"
        />
      </label>
      <p v-if="error" class="text-sm text-red-400">{{ error }}</p>
    </form>
  </div>
</template>
