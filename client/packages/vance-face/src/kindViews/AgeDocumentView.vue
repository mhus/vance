<script setup lang="ts">
/**
 * Locked-state view for age-encrypted documents (kind {@code age},
 * registered in {@code document/builtInKinds.ts}).
 *
 * <p>Modes (same contract as PdfView):
 * <ul>
 * <li>{@code editor} — the Cortex tab body: what an age document looks
 *     like before a key fits. Explanation, unlock form, cipher preview.
 *     The editor itself never renders here — once a key decrypts, the
 *     store's transform flips the tab's kind/mime to the inner document
 *     and the binding re-resolves to the inner editor, unmounting this
 *     view (see {@code cortex/ageDocument.ts}).</li>
 * <li>{@code embedded} — a chat/embed card. No decryption in embeds
 *     (planning/age-encryption.md §5.5): the card states the fact,
 *     unlocking happens in Cortex.</li>
 * </ul>
 */
import { computed, ref } from 'vue';
import { useI18n } from 'vue-i18n';

import { VAlert, VButton, VFileInput, VInput, VTextarea } from '@/components';
import { useAgeKeyStore } from '@/cortex/stores/ageKeyStore';
import { useCortexStore } from '@/cortex/stores/cortexStore';
import type { DocumentDto } from '@vance/generated';

interface Props {
  mode?: 'editor' | 'embedded';
  document?: DocumentDto;
}

const props = withDefaults(defineProps<Props>(), { mode: 'embedded' });

const { t } = useI18n();
const cortex = useCortexStore();
const ageKeys = useAgeKeyStore();

// The shell passes a DTO snapshot, but the error state lives on the
// reactive tab — resolve it back for live feedback after a failed attempt.
const tab = computed(() => cortex.openTabs.find((x) => x.id === props.document?.id) ?? null);
const ageState = computed(() => tab.value?.age ?? null);

const identityText = ref('');
const identityFiles = ref<File[]>([]);
const passphrase = ref('');
const unlockBusy = ref(false);
const unlockError = ref<string | null>(null);

/** First lines of the armored body — proof of what is stored, not a text to edit. */
const cipherPreview = computed<string>(() => {
  const armored = ageState.value?.armored ?? props.document?.inlineText ?? '';
  return armored.split('\n').slice(0, 8).join('\n');
});

async function onImportIdentityFile(): Promise<void> {
  const file = identityFiles.value[0];
  if (!file) return;
  try {
    identityText.value = await file.text();
  } catch {
    // Keep whatever was pasted before — the file read failed, the form
    // still works, and a modal for a rare IO hiccup would be noise.
  }
}

/**
 * Consume the form (identity and/or passphrase) into the session key
 * store, then retry the decrypt. Importing a key that does not fit this
 * document still succeeds — it stays for the next encrypted document.
 */
async function onUnlock(): Promise<void> {
  if (unlockBusy.value) return;
  unlockError.value = null;
  let addedSecret = false;
  const text = identityText.value.trim();
  if (text) {
    try {
      ageKeys.addIdentity(text);
      addedSecret = true;
      identityText.value = '';
    } catch (e) {
      unlockError.value = e instanceof Error ? e.message : String(e);
      return;
    }
  }
  if (passphrase.value) {
    ageKeys.addPassphrase(passphrase.value);
    addedSecret = true;
    passphrase.value = '';
  }
  if (!addedSecret && !ageKeys.hasSecrets) {
    unlockError.value = t('cortex.age.emptyForm');
    return;
  }
  if (!props.document?.id) return;
  unlockBusy.value = true;
  try {
    await cortex.unlockAgeTab(props.document.id);
    if (ageState.value?.locked) {
      unlockError.value = t(
        ageState.value.error === 'notArmored'
          ? 'cortex.age.notArmored'
          : 'cortex.age.wrongKey',
      );
    }
    // Unlocked: the binding flipped to the inner editor and unmounted us.
  } finally {
    unlockBusy.value = false;
  }
}

function onForget(): void {
  // A dirty age tab cannot save without a key — say so before wiping.
  const dirtyUnlocked = cortex.openTabs.some((x) => x.age && !x.age.locked && x.dirty);
  if (dirtyUnlocked) {
    const ok = window.confirm(t('cortex.age.forgetDirtyWarning'));
    if (!ok) return;
  }
  ageKeys.lock();
}
</script>

<template>
  <!-- Embedded card: the fact, not the ciphertext (planning §5.5). -->
  <div
    v-if="mode === 'embedded'"
    class="age-view age-view--card flex items-center gap-2 rounded border border-base-300 px-3 py-2 text-sm opacity-80"
  >
    <span aria-hidden="true">🔐</span>
    <span>{{ $t('cortex.age.embedded') }}</span>
  </div>

  <div v-else class="age-view mx-auto max-w-2xl space-y-4 overflow-y-auto p-4">
    <VAlert variant="info">
      <span class="font-medium">🔐 {{ $t('cortex.age.lockedTitle') }}</span>
    </VAlert>
    <p class="text-sm opacity-80">{{ $t('cortex.age.lockedBody') }}</p>

    <VAlert v-if="unlockError" variant="error">{{ unlockError }}</VAlert>
    <VAlert v-else-if="ageState?.error === 'notArmored'" variant="error">
      {{ $t('cortex.age.notArmored') }}
    </VAlert>

    <div class="space-y-3 rounded border border-base-300 p-4">
      <h3 class="font-medium">{{ $t('cortex.age.unlockTitle') }}</h3>
      <VTextarea
        v-model="identityText"
        :rows="2"
        :label="$t('cortex.age.identityLabel')"
        :placeholder="'AGE-SECRET-KEY-1…'"
      />
      <VFileInput
        v-model="identityFiles"
        class="max-w-md"
        :accept="'.txt,text/plain'"
        :label="$t('cortex.age.identityFileLabel')"
        @update:model-value="onImportIdentityFile"
      />
      <VInput
        v-model="passphrase"
        type="password"
        :label="$t('cortex.age.passphraseLabel')"
        autocomplete="off"
      />
      <div class="flex flex-wrap items-center gap-2">
        <VButton size="sm" :loading="unlockBusy" @click="onUnlock">
          {{ $t('cortex.age.unlock') }}
        </VButton>
        <VButton v-if="ageKeys.hasSecrets" size="sm" variant="ghost" @click="onUnlock">
          {{ $t('cortex.age.tryAgain') }}
        </VButton>
        <VButton v-if="ageKeys.hasSecrets" size="sm" variant="ghost" @click="onForget">
          {{ $t('cortex.age.forget') }}
        </VButton>
        <span
          v-if="ageKeys.hasSecrets"
          class="text-xs opacity-60"
        >{{ $t('cortex.age.keyCount', {
          identities: ageKeys.identities.length,
          passphrases: ageKeys.passphrases.length,
        }) }}</span>
      </div>
      <p class="text-xs opacity-60">{{ $t('cortex.age.memoryHint') }}</p>
    </div>

    <details class="rounded border border-base-300">
      <summary class="cursor-pointer px-3 py-2 text-sm">
        {{ $t('cortex.age.cipherPreview') }}
      </summary>
      <pre class="overflow-x-auto px-3 pb-3 font-mono text-xs whitespace-pre-wrap">{{ cipherPreview }}</pre>
    </details>
  </div>
</template>
