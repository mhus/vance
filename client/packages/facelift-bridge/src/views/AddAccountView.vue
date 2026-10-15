<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { addAccount } from '@/accounts/accountStore';
import { verifyVanceUrl } from '@/accounts/verifyVanceUrl';
import { VAlert, VButton, VInput } from '@vance/components';

const router = useRouter();

const faceUrl = ref('https://');
const displayName = ref('');
const submitting = ref(false);
const error = ref<string | null>(null);

async function onSubmit(): Promise<void> {
  if (submitting.value) return;
  error.value = null;
  const url = faceUrl.value.trim();
  if (url.length === 0 || url === 'https://' || url === 'http://') {
    error.value = 'URL is required.';
    return;
  }
  try {

    new URL(url);
  } catch {
    error.value = 'Not a valid URL.';
    return;
  }
  submitting.value = true;
  try {
    // Confirm the URL actually serves a Vance deployment before
    // persisting — catches typos like "https://google.de" without
    // forcing the user through the WebView round-trip.
    const verify = await verifyVanceUrl(url);
    if (!verify.ok) {
      error.value = `Not a Vancetope instance (${verify.reason ?? 'unknown'})`;
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
      faceUrl: url,
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
  <div class="flex h-full flex-col bg-(--v-bg) text-(--v-label)">
    <header
      class="shrink-0 px-4"
      style="padding-top: max(0.75rem, env(safe-area-inset-top)); padding-bottom: 0.5rem"
    >
      <h1 class="text-[22px] leading-7 font-semibold">Add account</h1>
    </header>
    <form
      id="add-account-form"
      class="flex-1 overflow-y-auto px-4 pt-2 pb-6"
      @submit.prevent="onSubmit"
    >
      <div class="mx-auto flex max-w-[720px] flex-col gap-4">
        <!-- A raw field rather than VInput: the URL needs spellcheck and
             inputmode on the <input> itself, which VInput does not pass
             through. Same markup and look as VInput. -->
        <label class="flex w-full flex-col gap-1">
          <span class="text-xs font-semibold text-(--v-label-secondary)">URL</span>
          <input
            v-model="faceUrl"
            type="url"
            autocomplete="off"
            autocapitalize="none"
            spellcheck="false"
            inputmode="url"
            placeholder="https://eddie.mhus.de"
            class="input w-full [--size:2rem]"
          />
        </label>
        <VInput
          v-model="displayName"
          label="Label (optional)"
          autocomplete="off"
          placeholder="e.g. Private, Work"
          help="Defaults to the host part of the URL."
        />
        <VAlert v-if="error" variant="error">{{ error }}</VAlert>
      </div>
    </form>
    <footer
      class="border-t border-(--v-separator) px-4 pt-3"
      style="padding-bottom: max(1rem, env(safe-area-inset-bottom))"
    >
      <div class="mx-auto flex max-w-[720px] justify-end gap-2">
        <VButton variant="secondary" @click="onCancel">
          Cancel
        </VButton>
        <VButton
          variant="primary"
          type="submit"
          form="add-account-form"
          :disabled="submitting"
        >
          {{ submitting ? 'Saving…' : 'Save' }}
        </VButton>
      </div>
    </footer>
  </div>
</template>
