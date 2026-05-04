<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { setActiveLanguage } from '@/platform';
import { setUiLocale } from '@/i18n';
import { EditorShell, VAlert, VButton, VCard, VInput, VSelect } from '@components/index';
import { useProfile } from '@composables/useProfile';

const { t } = useI18n();
const { profile, loading, error, load, saveIdentity, saveSetting } = useProfile();

const titleDraft = ref('');
const emailDraft = ref('');
const languageDraft = ref<string>('');
const identitySaved = ref<string | null>(null);
const languageSaved = ref<string | null>(null);

const LANGUAGE_KEY = 'webui.language';

// "Browser default" is the only label that needs translating; the
// other entries are language names already shown in their native
// form so users can recognise the option independent of the current
// UI language.
const languageOptions = computed(() => [
  { value: '', label: t('profile.preferences.languageBrowserDefault') },
  { value: 'de', label: 'Deutsch' },
  { value: 'en', label: 'English' },
  { value: 'fr', label: 'Français' },
  { value: 'es', label: 'Español' },
  { value: 'it', label: 'Italiano' },
]);

onMounted(load);

// Sync the form drafts whenever the underlying profile object changes —
// happens on initial load and after every successful save (the
// composable replaces the ref with the server response).
watch(profile, (current) => {
  if (!current) return;
  titleDraft.value = current.title ?? '';
  emailDraft.value = current.email ?? '';
  languageDraft.value = current.webUiSettings?.[LANGUAGE_KEY] ?? '';
}, { immediate: true });

async function onSaveIdentity(): Promise<void> {
  identitySaved.value = null;
  await saveIdentity({
    title: titleDraft.value.trim(),
    email: emailDraft.value.trim(),
  }).catch(() => undefined);
  if (!error.value) {
    identitySaved.value = t('profile.identity.saved');
  }
}

async function onLanguageChanged(value: string | null): Promise<void> {
  languageSaved.value = null;
  const next = value ?? '';
  languageDraft.value = next;
  await saveSetting(LANGUAGE_KEY, next === '' ? null : next).catch(() => undefined);
  if (!error.value) {
    // Mirror the new value into sessionStorage so the rest of the
    // app picks it up via {@code getActiveLanguage} immediately —
    // no re-login, no page reload. The data cookie still carries
    // the login-time snapshot; sessionStorage wins for live reads.
    setActiveLanguage(next === '' ? null : next);
    // Switch the live i18n locale too so the page re-renders in the
    // newly chosen language right away.
    setUiLocale(next === '' ? null : next);
    languageSaved.value = t('profile.preferences.languageSaved');
  }
}
</script>

<template>
  <EditorShell :title="$t('profile.pageTitle')">
    <div class="container mx-auto px-4 py-8 max-w-3xl flex flex-col gap-6">
      <VAlert v-if="error" variant="error">{{ error }}</VAlert>

      <div v-if="loading && !profile" class="text-sm opacity-60">
        {{ $t('profile.loading') }}
      </div>

      <template v-else-if="profile">
        <!-- Identity ─────────────────────────────────────────────────────── -->
        <VCard>
          <h2 class="text-lg font-semibold mb-3">{{ $t('profile.identity.title') }}</h2>
          <div class="flex flex-col gap-3">
            <div class="text-sm opacity-70">
              <span class="font-mono">{{ profile.tenantId }}</span>
              <span class="mx-1">·</span>
              <span class="font-mono">{{ profile.name }}</span>
            </div>
            <VInput
              v-model="titleDraft"
              :label="$t('profile.identity.displayName')"
              :disabled="loading"
              :placeholder="$t('profile.identity.displayNamePlaceholder')"
            />
            <VInput
              v-model="emailDraft"
              :label="$t('profile.identity.email')"
              type="email"
              :disabled="loading"
              autocomplete="email"
            />
            <div class="flex items-center gap-3">
              <VButton
                variant="primary"
                :loading="loading"
                @click="onSaveIdentity"
              >
                {{ $t('common.save') }}
              </VButton>
              <span v-if="identitySaved" class="text-success text-sm">
                {{ identitySaved }}
              </span>
            </div>
          </div>
        </VCard>

        <!-- Preferences ──────────────────────────────────────────────────── -->
        <VCard>
          <h2 class="text-lg font-semibold mb-3">{{ $t('profile.preferences.title') }}</h2>
          <p class="text-sm opacity-70 mb-3">
            {{ $t('profile.preferences.description') }}
          </p>
          <div class="flex flex-col gap-3">
            <VSelect
              :model-value="languageDraft"
              :options="languageOptions"
              :label="$t('profile.preferences.language')"
              :disabled="loading"
              @update:model-value="onLanguageChanged"
            />
            <span v-if="languageSaved" class="text-success text-sm">
              {{ languageSaved }}
            </span>
          </div>
        </VCard>

        <!-- Teams ────────────────────────────────────────────────────────── -->
        <VCard>
          <h2 class="text-lg font-semibold mb-3">{{ $t('profile.teams.title') }}</h2>
          <p v-if="profile.teams.length === 0" class="text-sm opacity-70">
            {{ $t('profile.teams.empty') }}
          </p>
          <ul v-else class="flex flex-col gap-2">
            <li
              v-for="team in profile.teams"
              :key="team.id || team.name"
              class="flex items-center justify-between"
            >
              <div>
                <div class="font-semibold">
                  {{ team.title || team.name }}
                </div>
                <div class="text-xs opacity-70 font-mono">{{ team.name }}</div>
              </div>
              <div class="flex items-center gap-2">
                <span class="text-xs opacity-70">
                  {{
                    team.members.length === 1
                      ? $t('profile.teams.memberCountOne', { count: team.members.length })
                      : $t('profile.teams.memberCountOther', { count: team.members.length })
                  }}
                </span>
                <span
                  v-if="!team.enabled"
                  class="badge badge-warning badge-sm"
                  :title="$t('profile.teams.disabledTooltip')"
                >{{ $t('profile.teams.disabled') }}</span>
              </div>
            </li>
          </ul>
        </VCard>
      </template>
    </div>
  </EditorShell>
</template>
