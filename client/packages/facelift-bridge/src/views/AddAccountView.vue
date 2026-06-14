<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { addAccount } from '@/accounts/accountStore';
import { verifyVanceUrl } from '@/accounts/verifyVanceUrl';

const router = useRouter();

const brainUrl = ref('https://');
const displayName = ref('');
const submitting = ref(false);
const error = ref<string | null>(null);

async function onSubmit(): Promise<void> {
  if (submitting.value) return;
  error.value = null;
  const url = brainUrl.value.trim();
  if (url.length === 0 || url === 'https://' || url === 'http://') {
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
    // Confirm the URL actually serves a Vance deployment before
    // persisting — catches typos like "https://google.de" without
    // forcing the user through the WebView round-trip.
    const verify = await verifyVanceUrl(url);
    if (!verify.ok) {
      error.value = `Not a Vance instance (${verify.reason ?? 'unknown'})`;
      return;
    }
    // Prefer the server-declared title as the default display name
    // when the user hasn't typed one. The accountStore further
    // falls back to the URL host if both are empty.
    const finalDisplayName =
      displayName.value.trim().length > 0
        ? displayName.value
        : (verify.config?.title?.trim() ?? '');
    await addAccount({
      brainUrl: url,
      displayName: finalDisplayName,
    });
    // addAccount auto-activates when this is the first entry, so
    // returning straight to the shell makes the new account
    // immediately visible.
    void router.replace({ name: 'shell' });
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to add account.';
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
      <h1 class="flex-1 text-sm font-semibold">Add account</h1>
    </header>
    <form class="flex-1 space-y-4 overflow-y-auto p-4" @submit.prevent="onSubmit">
      <label class="block">
        <span class="mb-1 block text-xs uppercase tracking-wide text-gray-400">Brain URL</span>
        <input
          v-model="brainUrl"
          type="url"
          autocomplete="off"
          autocapitalize="none"
          spellcheck="false"
          inputmode="url"
          placeholder="https://eddie.mhus.de"
          class="w-full rounded border border-gray-700 bg-gray-800 px-3 py-2 outline-none focus:border-blue-400"
        />
      </label>
      <label class="block">
        <span class="mb-1 block text-xs uppercase tracking-wide text-gray-400">Label (optional)</span>
        <input
          v-model="displayName"
          type="text"
          autocomplete="off"
          placeholder="e.g. Private, Work"
          class="w-full rounded border border-gray-700 bg-gray-800 px-3 py-2 outline-none focus:border-blue-400"
        />
        <p class="mt-1 text-xs text-gray-500">
          Defaults to the host part of the URL.
        </p>
      </label>
      <p v-if="error" class="text-sm text-red-400">{{ error }}</p>
      <button
        type="submit"
        :disabled="submitting"
        class="w-full rounded bg-blue-500 px-3 py-2 font-medium text-white disabled:opacity-50"
      >
        {{ submitting ? 'Saving…' : 'Save' }}
      </button>
    </form>
  </div>
</template>
