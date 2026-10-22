<script setup lang="ts">
/**
 * The "mint a backup token" entry point for the Face UI.
 *
 * <p>Why this lives next to {@link ConnectedAppsSection}:
 * the post-install step of an applied vancetope-backup tool template
 * (see {@code settings/ToolTemplatesSection.vue} → openConnectedAccounts)
 * lands on this page. The user has just clicked "Apply" in Settings,
 * expects to be guided through minting a token for the new tool,
 * and lands here looking for "Backup-Zugang erstellen". Putting the
 * section above the existing "Verbundene Apps" list keeps the flow
 * linear: mint → see the freshly-minted token in the list → done.
 *
 * <p>The minted token is shown exactly once. Closing the dialog without
 * copying means revoking + re-minting — by design (planning §7.5).
 */
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import type {
  IntegrationScopeProfileDto,
  IntegrationTokenDto,
} from '@vance/generated';
import {
  createIntegrationToken,
  listScopeProfiles,
} from '@vance/shared';
import {
  VAlert,
  VButton,
  VCard,
  VEmptyState,
  VInput,
  VSelect,
} from '@/components';

const { t } = useI18n();
const emit = defineEmits<{
  /** Token minted — parent refreshes its list (ConnectedAppsSection). */
  (e: 'minted'): void;
}>();

const profiles = ref<IntegrationScopeProfileDto[]>([]);
const loadingProfiles = ref(false);
const profileError = ref<string | null>(null);

const selectedProfileId = ref<string | null>(null);
const label = ref('');
// VInput emits strings; parsed in idleDaysClamped / idleDaysError.
const idleDays = ref('30');
const submitting = ref(false);
const formError = ref<string | null>(null);

const mintedToken = ref<IntegrationTokenDto | null>(null);
const copied = ref(false);

const BACKUP_PROFILE_ID = 'backup';
const IDLE_DAYS_MAX = 365;

const profileOptions = computed(() =>
  profiles.value.map((p) => ({ value: p.id, label: `${p.label} (${p.id})` })));

const selectedProfile = computed<IntegrationScopeProfileDto | null>(() =>
  profiles.value.find((p) => p.id === selectedProfileId.value) ?? null);

const idleDaysClamped = computed<number>(() => {
  const v = Math.floor(Number(idleDays.value) || 0);
  if (v < 1) return 1;
  if (v > IDLE_DAYS_MAX) return IDLE_DAYS_MAX;
  return v;
});

const idleDaysError = computed<string | null>(() => {
  const v = Number(idleDays.value);
  if (!Number.isFinite(v) || v < 1 || v > IDLE_DAYS_MAX) {
    return t('connectedApps.backup.idleDaysRange', { min: 1, max: IDLE_DAYS_MAX });
  }
  return null;
});

const canSubmit = computed<boolean>(() =>
  selectedProfileId.value !== null
  && label.value.trim().length > 0
  && idleDaysError.value === null
  && !submitting.value);

onMounted(async () => {
  loadingProfiles.value = true;
  profileError.value = null;
  try {
    const list = await listScopeProfiles();
    profiles.value = list;
    // Default-select the backup profile if it exists. It's the
    // reason this section exists at all.
    const backup = list.find((p) => p.id === BACKUP_PROFILE_ID);
    selectedProfileId.value = backup?.id ?? list[0]?.id ?? null;
  } catch {
    profileError.value = t('connectedApps.backup.profilesLoadFailed');
  } finally {
    loadingProfiles.value = false;
  }
});

async function onSubmit(): Promise<void> {
  if (!canSubmit.value) return;
  submitting.value = true;
  formError.value = null;
  try {
    const result = await createIntegrationToken({
      scopeProfiles: [selectedProfileId.value!],
      label: label.value.trim(),
      // A sliding token has no fixed end: it lives idleDays past its last
      // successful run (planning §7.6). expiresInDays only applies to
      // fixed-lifetime profiles; 0 asks for the server default.
      expiresInDays: 0,
      idleDays: idleDaysClamped.value,
    });
    mintedToken.value = result;
    copied.value = false;
    emit('minted');
  } catch (err) {
    formError.value = (err instanceof Error && err.message)
      ? err.message
      : t('connectedApps.backup.mintFailed');
  } finally {
    submitting.value = false;
  }
}

async function onCopy(): Promise<void> {
  if (!mintedToken.value?.token) return;
  try {
    await navigator.clipboard.writeText(mintedToken.value.token);
    copied.value = true;
  } catch {
    // Fallback for older browsers / non-secure contexts: a hidden
    // textarea + execCommand('copy'). Best-effort.
    const ta = document.createElement('textarea');
    ta.value = mintedToken.value.token;
    ta.style.position = 'fixed';
    ta.style.left = '-9999px';
    document.body.appendChild(ta);
    ta.select();
    try {
      document.execCommand('copy');
      copied.value = true;
    } catch {
      // Last resort: nothing to copy. The token is still on screen.
    }
    document.body.removeChild(ta);
  }
}

function onDismiss(): void {
  mintedToken.value = null;
  copied.value = false;
  // Reset the form so the next mint starts clean.
  label.value = '';
  idleDays.value = '30';
}
</script>

<template>
  <div class="flex flex-col gap-3">
    <h2 class="text-lg font-semibold mt-2">{{ $t('connectedApps.backup.title') }}</h2>
    <p class="text-sm opacity-70">{{ $t('connectedApps.backup.intro') }}</p>

    <VAlert v-if="profileError" variant="error">
      <span>{{ profileError }}</span>
    </VAlert>

    <!-- The freshly-minted token. Lives outside the form so closing
         the form can't accidentally hide the token the user came
         here to copy. -->
    <VCard v-if="mintedToken" variant="highlight">
      <div class="flex flex-col gap-2">
        <div class="text-sm font-semibold">
          {{ $t('connectedApps.backup.tokenTitle') }}
        </div>
        <VAlert variant="warning">
          <span>{{ $t('connectedApps.backup.oneTimeWarning') }}</span>
        </VAlert>
        <pre class="font-mono text-xs whitespace-pre-wrap break-all bg-base-200 p-3 rounded">{{ mintedToken.token }}</pre>
        <div class="flex items-center gap-2 flex-wrap">
          <VButton variant="primary" @click="onCopy">
            <span v-if="!copied">{{ $t('connectedApps.backup.copy') }}</span>
            <span v-else>{{ $t('connectedApps.backup.copied') }}</span>
          </VButton>
          <VButton variant="plain" @click="onDismiss">
            {{ $t('connectedApps.backup.dismiss') }}
          </VButton>
        </div>
        <div class="text-xs opacity-70 mt-2">
          {{ $t('connectedApps.backup.usage') }}
        </div>
      </div>
    </VCard>

    <!-- The mint form. Hidden once a token is minted — the user
         closes the dialog to come back to the form for the next one. -->
    <VCard v-else>
      <div v-if="loadingProfiles" class="text-sm opacity-60">
        {{ $t('connectedApps.backup.loadingProfiles') }}
      </div>
      <VEmptyState
        v-else-if="profiles.length === 0"
        :headline="$t('connectedApps.backup.noProfiles.headline')"
        :body="$t('connectedApps.backup.noProfiles.body')"
      />
      <div v-else class="flex flex-col gap-3">
        <div class="flex flex-col gap-1">
          <div class="max-w-md">
            <VSelect
              v-model="selectedProfileId"
              :options="profileOptions"
              :label="$t('connectedApps.backup.profile')"
            />
          </div>
          <div v-if="selectedProfile" class="text-xs opacity-70">
            {{ $t('connectedApps.backup.profileSurfaces') }}
            <ul class="list-disc list-inside">
              <li v-for="s in selectedProfile.surfaces" :key="s" class="font-mono text-xs">{{ s }}</li>
            </ul>
          </div>
        </div>

        <div class="max-w-md">
          <VInput
            v-model="label"
            size="sm"
            :label="$t('connectedApps.backup.labelLabel')"
            :placeholder="$t('connectedApps.backup.labelPlaceholder')"
          />
        </div>

        <div class="max-w-md">
          <VInput
            v-model="idleDays"
            type="number"
            size="sm"
            :label="$t('connectedApps.backup.idleDaysLabel')"
            :disabled="!selectedProfile?.slidingLifetime"
            :help="selectedProfile?.slidingLifetime
              ? $t('connectedApps.backup.idleDaysHelp', { days: idleDaysClamped })
              : $t('connectedApps.backup.idleDaysFixed')"
            :error="idleDaysError ?? undefined"
          />
        </div>

        <VAlert v-if="formError" variant="error">
          <span>{{ formError }}</span>
        </VAlert>

        <div class="flex items-center gap-2">
          <VButton
            variant="primary"
            :disabled="!canSubmit"
            :loading="submitting"
            @click="onSubmit"
          >
            {{ $t('connectedApps.backup.submit') }}
          </VButton>
        </div>
      </div>
    </VCard>
  </div>
</template>
