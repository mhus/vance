<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  applyTheme,
  type WebUiLevel,
  type WebUiTheme,
} from '@/platform';
import { setUiLocale } from '@/i18n';
import { EditorShell, VAlert, VButton, VCard, VInput, VSelect } from '@components/index';
import { useProfile } from '@composables/useProfile';

const { t } = useI18n();
const { profile, loading, error, load, saveIdentity, saveSetting, deleteSetting } = useProfile();

const titleDraft = ref('');
const emailDraft = ref('');
const languageDraft = ref<string>('');
const chatLanguageDraft = ref<string>('');
const themeDraft = ref<WebUiTheme>('auto');
const uiLevelDraft = ref<WebUiLevel>('standard');
const identitySaved = ref<string | null>(null);
const languageSaved = ref<string | null>(null);
const chatLanguageSaved = ref<string | null>(null);
const themeSaved = ref<string | null>(null);
const uiLevelSaved = ref<string | null>(null);

const LANGUAGE_KEY = 'webui.language';
const CHAT_LANGUAGE_KEY = 'chat.language';
const THEME_KEY = 'webui.theme';
const UI_LEVEL_KEY = 'webui.uiLevel';

function asTheme(value: string | undefined | null): WebUiTheme {
  // Accept anything stored on the server but normalise unknown
  // values back to "auto" rather than rendering an empty selector.
  return value === 'light' || value === 'dark' ? value : 'auto';
}

function asUiLevel(value: string | undefined | null): WebUiLevel {
  return value === 'expert' || value === 'admin' ? value : 'standard';
}

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

// Chat language ("not set" → no user-level value → cascade falls
// through to the tenant or to LanguageResolver.DEFAULT_LANGUAGE).
// The native language names match the webui.language dropdown so
// users get the same picker pattern across both fields.
const chatLanguageOptions = computed(() => [
  { value: '', label: t('profile.preferences.chatLanguageNotSet') },
  { value: 'de', label: 'Deutsch' },
  { value: 'en', label: 'English' },
  { value: 'fr', label: 'Français' },
  { value: 'es', label: 'Español' },
  { value: 'it', label: 'Italiano' },
]);

const themeOptions = computed(() => [
  { value: 'auto', label: t('profile.preferences.themeAuto') },
  { value: 'light', label: t('profile.preferences.themeLight') },
  { value: 'dark', label: t('profile.preferences.themeDark') },
]);

const uiLevelOptions = computed(() => [
  { value: 'standard', label: t('profile.preferences.uiLevelStandard') },
  { value: 'expert', label: t('profile.preferences.uiLevelExpert') },
  { value: 'admin', label: t('profile.preferences.uiLevelAdmin') },
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
  chatLanguageDraft.value = current.webUiSettings?.[CHAT_LANGUAGE_KEY] ?? '';
  themeDraft.value = asTheme(current.webUiSettings?.[THEME_KEY]);
  uiLevelDraft.value = asUiLevel(current.webUiSettings?.[UI_LEVEL_KEY]);
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
    // The PUT response refreshes the data cookie server-side, so
    // {@link getActiveLanguage} sees the new value on the next read.
    // Switch the live i18n locale here so the page re-renders in the
    // newly chosen language right away.
    setUiLocale(next === '' ? null : next);
    languageSaved.value = t('profile.preferences.languageSaved');
  }
}

async function onChatLanguageChanged(value: string | null): Promise<void> {
  chatLanguageSaved.value = null;
  const next = value ?? '';
  chatLanguageDraft.value = next;
  // "Not set" → DELETE the user-scope setting so the cascade falls
  // through to the tenant default (or LanguageResolver.DEFAULT_LANGUAGE).
  // Any concrete code is a PUT — same path as the other prefs.
  if (next === '') {
    await deleteSetting(CHAT_LANGUAGE_KEY).catch(() => undefined);
  } else {
    await saveSetting(CHAT_LANGUAGE_KEY, next).catch(() => undefined);
  }
  if (!error.value) {
    chatLanguageSaved.value = t('profile.preferences.chatLanguageSaved');
  }
}

async function onThemeChanged(value: string | null): Promise<void> {
  themeSaved.value = null;
  const next = asTheme(value);
  themeDraft.value = next;
  // "auto" is encoded server-side as the absence of the setting —
  // pass null so the brain DELETEs it. light / dark are stored as-is.
  await saveSetting(THEME_KEY, next === 'auto' ? null : next).catch(() => undefined);
  if (!error.value) {
    // PUT refreshes the data cookie; flip the DOM theme here so the
    // change is visible without waiting for a page reload.
    applyTheme(next);
    themeSaved.value = t('profile.preferences.themeSaved');
  }
}

async function onUiLevelChanged(value: string | null): Promise<void> {
  uiLevelSaved.value = null;
  const next = asUiLevel(value);
  uiLevelDraft.value = next;
  // "standard" is the default and stored as the absence of the
  // setting — same convention as theme=auto / language="".
  await saveSetting(UI_LEVEL_KEY, next === 'standard' ? null : next).catch(() => undefined);
  if (!error.value) {
    // Index-page tile filtering reads {@link getActiveUiLevel} from
    // the data cookie on its next mount, which the PUT response just
    // refreshed.
    uiLevelSaved.value = t('profile.preferences.uiLevelSaved');
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
            <VSelect
              :model-value="chatLanguageDraft"
              :options="chatLanguageOptions"
              :label="$t('profile.preferences.chatLanguage')"
              :disabled="loading"
              @update:model-value="onChatLanguageChanged"
            />
            <p class="text-xs opacity-60 -mt-2">
              {{ $t('profile.preferences.chatLanguageDescription') }}
            </p>
            <span v-if="chatLanguageSaved" class="text-success text-sm">
              {{ chatLanguageSaved }}
            </span>
            <VSelect
              :model-value="themeDraft"
              :options="themeOptions"
              :label="$t('profile.preferences.theme')"
              :disabled="loading"
              @update:model-value="onThemeChanged"
            />
            <span v-if="themeSaved" class="text-success text-sm">
              {{ themeSaved }}
            </span>
            <VSelect
              :model-value="uiLevelDraft"
              :options="uiLevelOptions"
              :label="$t('profile.preferences.uiLevel')"
              :disabled="loading"
              @update:model-value="onUiLevelChanged"
            />
            <p class="text-xs opacity-60 -mt-2">
              {{ $t('profile.preferences.uiLevelDescription') }}
            </p>
            <span v-if="uiLevelSaved" class="text-success text-sm">
              {{ uiLevelSaved }}
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
