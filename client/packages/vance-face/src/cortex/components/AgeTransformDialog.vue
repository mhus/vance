<script setup lang="ts">
/**
 * Actions → Encrypt / Decrypt (planning/age-encryption.md §5.4): the
 * durable transformation between a plaintext document and an age-encrypted
 * twin. A NEW document is created at the (editable) target path — the
 * original stays untouched; tidying up is the user's own move in v1.
 *
 * <p>Encrypt reads the active tab's editor buffer (what you see is what
 * gets encrypted) and encrypts to the session identities — the paste /
 * generate / passphrase form makes this the first key touchpoint when none
 * are held yet. Decrypt works on the stored armored body (unsaved tab
 * edits are not part of it — the hint says so) and needs a fitting key
 * like every other reader.
 */
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';

import {
  AGE_MIME_TYPE,
  decryptArmored,
  encryptArmored,
  encryptArmoredWithPassphrase,
  generateKeyPair,
} from '@vance/age';

import { VAlert, VButton, VFileInput, VInput, VModal, VSelect, VTextarea } from '@/components';
import { transformAgeTab } from '@/cortex/ageDocument';
import { useAgeKeyStore } from '@/cortex/stores/ageKeyStore';
import { useCortexStore } from '@/cortex/stores/cortexStore';
import type { CortexDocument } from '@/cortex/types';
import type { AgeKeyPair } from '@vance/age';

const props = defineProps<{
  modelValue: boolean;
  mode: 'encrypt' | 'decrypt';
  document: CortexDocument;
}>();

const emit = defineEmits<{ (e: 'update:modelValue', open: boolean): void }>();

const { t } = useI18n();
const cortex = useCortexStore();
const ageKeys = useAgeKeyStore();

const targetPath = ref('');
const busy = ref(false);
const error = ref<string | null>(null);

// ── Encrypt-side key choice ──────────────────────────────────────────
const keyMode = ref<'identities' | 'passphrase'>('identities');
const identityText = ref('');
const identityFiles = ref<File[]>([]);
const newPassphrase = ref('');
const generated = ref<AgeKeyPair | null>(null);

// ── Decrypt-side key form ───────────────────────────────────────────
const passphraseText = ref('');

const defaultTarget = computed(() =>
  props.mode === 'encrypt'
    ? `${props.document.path}.age`
    : props.document.path.replace(/\.age$/i, ''),
);

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return;
    targetPath.value = defaultTarget.value;
    error.value = null;
    identityText.value = '';
    newPassphrase.value = '';
    passphraseText.value = '';
    generated.value = null;
    keyMode.value = 'identities';
  },
);

const keyModeOptions = computed(() => [
  {
    value: 'identities' as const,
    label: t('cortex.age.transform.keyIdentities', { n: ageKeys.identities.length }),
  },
  { value: 'passphrase' as const, label: t('cortex.age.transform.keyPassphrase') },
]);

const canConfirm = computed(() => {
  if (!targetPath.value.trim() || targetPath.value.trim() === props.document.path) {
    return false;
  }
  if (props.mode === 'encrypt') {
    return keyMode.value === 'identities'
      ? ageKeys.identities.length > 0
      : newPassphrase.value.length > 0;
  }
  return ageKeys.hasSecrets || identityText.value.trim().length > 0 || passphraseText.value.length > 0;
});

function onImportIdentityFile(): void {
  const file = identityFiles.value[0];
  if (!file) return;
  void file.text().then((text) => {
    identityText.value = text;
  });
}

/**
 * Consume the paste box into the key store — shared by both modes' forms.
 * Returns false (with {@link error} set) when the text is not a valid
 * identity, so callers can abort instead of continuing keyless.
 */
function addPastedIdentity(): boolean {
  const text = identityText.value.trim();
  if (!text) return true;
  try {
    ageKeys.addIdentity(text);
    identityText.value = '';
    error.value = null;
    return true;
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
    return false;
  }
}

async function onGenerate(): Promise<void> {
  const pair = await generateKeyPair();
  ageKeys.addIdentity(pair.identity);
  generated.value = pair;
}

function downloadKeyFile(): void {
  if (!generated.value) return;
  const content = `# public key: ${generated.value.recipient}\n${generated.value.identity}\n`;
  const url = URL.createObjectURL(new Blob([content], { type: 'text/plain' }));
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = 'vance-age-key.txt';
  anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

async function onConfirm(): Promise<void> {
  if (busy.value || !canConfirm.value) return;
  busy.value = true;
  error.value = null;
  try {
    if (props.mode === 'encrypt') {
      await confirmEncrypt();
    } else {
      await confirmDecrypt();
    }
    emit('update:modelValue', false);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
}

async function confirmEncrypt(): Promise<void> {
  const plaintext = props.document.inlineText;
  const useIdentities = keyMode.value === 'identities' && ageKeys.identities.length > 0;
  const armored = useIdentities
    ? await encryptArmored(plaintext, await ageKeys.recipients())
    : await encryptArmoredWithPassphrase(plaintext, newPassphrase.value);
  // The passphrase joins the key store too — the new tab then decrypts
  // immediately instead of opening locked.
  if (!useIdentities) {
    ageKeys.addPassphrase(newPassphrase.value);
  }
  const created = await cortex.createFile({
    path: targetPath.value.trim(),
    mimeType: AGE_MIME_TYPE,
    inlineText: armored,
  });
  // createFile seeds the new tab with the armored body — transform it with
  // the key we just encrypted with, so the tab opens decrypted, not locked.
  await transformAgeTab(created, armored, ageKeys.secrets());
}

async function confirmDecrypt(): Promise<void> {
  if (!addPastedIdentity()) return;
  if (passphraseText.value) {
    ageKeys.addPassphrase(passphraseText.value);
  }
  const armored = props.document.age?.armored ?? props.document.inlineText;
  const plaintext = await decryptArmored(armored, ageKeys.secrets());
  await cortex.createFile({ path: targetPath.value.trim(), inlineText: plaintext });
}
</script>

<template>
  <VModal
    :model-value="modelValue"
    :title="mode === 'encrypt'
      ? $t('cortex.age.transform.encryptTitle')
      : $t('cortex.age.transform.decryptTitle')"
    :close-on-backdrop="false"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <div class="space-y-4">
      <p class="text-sm">
        {{ $t('cortex.age.transform.intro') }}
      </p>
      <VInput
        v-model="targetPath"
        :label="$t('cortex.age.transform.targetLabel')"
        :help="$t('cortex.age.transform.targetHelp')"
      />

      <VAlert v-if="mode === 'decrypt' && document.dirty" variant="warning">
        {{ $t('cortex.age.transform.dirtyHint') }}
      </VAlert>

      <!-- ── Encrypt: the key that can decrypt ─────────────────────── -->
      <div v-if="mode === 'encrypt'" class="space-y-3">
        <VSelect
          v-model="keyMode"
          :options="keyModeOptions"
          :label="$t('cortex.age.transform.keySection')"
        />
        <template v-if="keyMode === 'identities'">
          <p v-if="ageKeys.identities.length" class="text-xs opacity-70">
            {{ $t('cortex.age.transform.identitiesInfo', { n: ageKeys.identities.length }) }}
          </p>
          <VTextarea
            v-model="identityText"
            :rows="2"
            :label="$t('cortex.age.identityLabel')"
            :placeholder="'AGE-SECRET-KEY-1…'"
          />
          <div class="flex flex-wrap items-center gap-2">
            <VButton size="sm" variant="ghost" :disabled="!identityText.trim()" @click="addPastedIdentity">
              {{ $t('cortex.age.transform.addIdentity') }}
            </VButton>
            <VFileInput
              v-model="identityFiles"
              class="max-w-xs"
              :accept="'.txt,text/plain'"
              :label="$t('cortex.age.identityFileLabel')"
              @update:model-value="onImportIdentityFile"
            />
          </div>
          <template v-if="!generated">
            <VButton size="sm" outline @click="onGenerate">
              ✨ {{ $t('cortex.age.transform.generate') }}
            </VButton>
          </template>
          <template v-else>
            <VAlert variant="warning">{{ $t('cortex.age.transform.generatedWarning') }}</VAlert>
            <pre class="overflow-x-auto rounded border border-base-300 p-2 font-mono text-xs whitespace-pre-wrap">{{ generated.identity }}</pre>
            <VButton size="sm" @click="downloadKeyFile">
              ⬇ {{ $t('cortex.age.transform.downloadKey') }}
            </VButton>
          </template>
        </template>
        <template v-else>
          <VInput
            v-model="newPassphrase"
            type="password"
            :label="$t('cortex.age.passphraseLabel')"
            autocomplete="off"
          />
          <p class="text-xs opacity-60">{{ $t('cortex.age.transform.passphraseCostHint') }}</p>
        </template>
      </div>

      <!-- ── Decrypt: bring a fitting key ──────────────────────────── -->
      <div v-else class="space-y-3">
        <p v-if="ageKeys.hasSecrets" class="text-xs opacity-70">
          {{ $t('cortex.age.keyCount', {
            identities: ageKeys.identities.length,
            passphrases: ageKeys.passphrases.length,
          }) }}
        </p>
        <VTextarea
          v-model="identityText"
          :rows="2"
          :label="$t('cortex.age.identityLabel')"
          :placeholder="'AGE-SECRET-KEY-1…'"
        />
        <VFileInput
          v-model="identityFiles"
          class="max-w-xs"
          :accept="'.txt,text/plain'"
          :label="$t('cortex.age.identityFileLabel')"
          @update:model-value="onImportIdentityFile"
        />
        <VInput
          v-model="passphraseText"
          type="password"
          :label="$t('cortex.age.passphraseLabel')"
          autocomplete="off"
        />
      </div>

      <VAlert v-if="error" variant="error">{{ error }}</VAlert>
    </div>

    <template #actions>
      <VButton variant="ghost" :disabled="busy" @click="emit('update:modelValue', false)">
        {{ $t('common.cancel') }}
      </VButton>
      <VButton :loading="busy" :disabled="!canConfirm" @click="onConfirm">
        {{ mode === 'encrypt'
          ? $t('cortex.age.transform.confirmEncrypt')
          : $t('cortex.age.transform.confirmDecrypt') }}
      </VButton>
    </template>
  </VModal>
</template>
