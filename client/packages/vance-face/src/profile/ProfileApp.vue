<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  applyTheme,
  type WebUiLevel,
  type WebUiTheme,
} from '@/platform';
import {
  isSpeechSynthesisSupported,
  listVoices,
  onVoicesChanged,
} from '@/platform/speechWeb';
import {
  resolveSpeechLanguage,
} from '@/platform/speechSettings';
import {
  brainFetch,
  DEFAULT_RATE,
  DEFAULT_VOLUME,
  MAX_RATE,
  MAX_VOLUME,
  MIN_RATE,
  MIN_VOLUME,
} from '@vance/shared';
import { setUiLocale } from '@/i18n';
import { EditorShell, VAlert, VButton, VCard, VCheckbox, VInput, VSelect } from '@components/index';
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
const openDocsNewTabDraft = ref<boolean>(true);
const openDocsNewTabSaved = ref<string | null>(null);
const timezoneDraft = ref<string>('');
const timezoneSaved = ref<string | null>(null);
// Guards the one-time "seed the browser timezone when unset" write so
// the watch (immediate + fires on every save) doesn't loop.
let timezoneAutoSeeded = false;

const LANGUAGE_KEY = 'webui.language';
const CHAT_LANGUAGE_KEY = 'chat.language';
const THEME_KEY = 'webui.theme';
const UI_LEVEL_KEY = 'webui.uiLevel';
const OPEN_DOCS_NEW_TAB_KEY = 'webui.document.openInNewTab';
// display.timezone is not a webui.* key — it drives server-side prompt
// date injection, the current_time tool default, and scheduler
// timezone pinning (see TimezoneResolver). Cascade user → tenant → UTC.
const TIMEZONE_KEY = 'display.timezone';

// Small fallback for the rare browser without Intl.supportedValuesOf.
const FALLBACK_TIMEZONES = [
  'UTC', 'Europe/London', 'Europe/Berlin', 'Europe/Paris', 'Europe/Moscow',
  'America/New_York', 'America/Chicago', 'America/Denver', 'America/Los_Angeles',
  'America/Sao_Paulo', 'Asia/Kolkata', 'Asia/Dubai', 'Asia/Shanghai',
  'Asia/Tokyo', 'Asia/Singapore', 'Australia/Sydney', 'Pacific/Auckland',
];

function browserTimezone(): string {
  try {
    const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
    return tz && tz.length > 0 ? tz : 'UTC';
  } catch {
    return 'UTC';
  }
}
const SPEECH_VOICE_KEY = 'webui.speech.voiceUri';
const SPEECH_RATE_KEY = 'webui.speech.rate';
const SPEECH_VOLUME_KEY = 'webui.speech.volume';

// Speech settings — voice depends on browser-provided voices for the
// resolved chat-language, rate + volume are numeric strings (server
// stores them prefixed with webui.speech.). Bridges the same chat.language
// cascade that the ChatComposer uses for speech recognition.
const speechSupported = ref(false);
const speechVoiceDraft = ref<string>('');
const speechRateDraft = ref<number>(DEFAULT_RATE);
const speechVolumeDraft = ref<number>(DEFAULT_VOLUME);
const speechVoiceSaved = ref<string | null>(null);
const speechRateSaved = ref<string | null>(null);
const speechVolumeSaved = ref<string | null>(null);

interface VoiceOption {
  value: string;
  label: string;
}
const voiceOptions = ref<VoiceOption[]>([]);
let voicesUnsubscribe: (() => void) | null = null;

function refreshVoiceOptions(): void {
  if (!speechSupported.value) return;
  const targetLang = resolveSpeechLanguage().toLowerCase().split('-')[0];
  const matching = listVoices()
    .filter((v) => v.lang.toLowerCase().replace('_', '-').split('-')[0] === targetLang)
    .slice()
    .sort((a, b) => a.name.localeCompare(b.name));
  voiceOptions.value = [
    { value: '', label: t('profile.speech.voiceAuto') },
    ...matching.map((v) => ({
      value: v.voiceURI,
      label: `${v.name} (${v.lang})${v.default ? t('profile.speech.voiceDefaultSuffix') : ''}`,
    })),
  ];
}

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

// Full IANA zone list from the browser when available; keeps the
// current value selectable even if the runtime doesn't enumerate it.
const timezoneOptions = computed(() => {
  const intlWithSupported = Intl as typeof Intl & {
    supportedValuesOf?: (key: string) => string[];
  };
  let zones: string[] = [];
  try {
    zones = intlWithSupported.supportedValuesOf?.('timeZone') ?? [];
  } catch {
    zones = [];
  }
  if (zones.length === 0) zones = [...FALLBACK_TIMEZONES];
  if (timezoneDraft.value && !zones.includes(timezoneDraft.value)) {
    zones = [timezoneDraft.value, ...zones];
  }
  return zones.map((z) => ({ value: z, label: z }));
});

onMounted(() => {
  if (isSpeechSynthesisSupported()) {
    speechSupported.value = true;
    voicesUnsubscribe = onVoicesChanged(refreshVoiceOptions);
    refreshVoiceOptions();
  }
  void load();
});

onBeforeUnmount(() => {
  if (voicesUnsubscribe) voicesUnsubscribe();
});

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
  // Default true — only an explicit "false" turns it off; absent / any
  // other value (legacy / typo) stays on the new-tab default.
  openDocsNewTabDraft.value = current.webUiSettings?.[OPEN_DOCS_NEW_TAB_KEY] !== 'false';
  speechVoiceDraft.value = current.webUiSettings?.[SPEECH_VOICE_KEY] ?? '';
  speechRateDraft.value = parseSpeechRate(current.webUiSettings?.[SPEECH_RATE_KEY]);
  speechVolumeDraft.value = parseSpeechVolume(current.webUiSettings?.[SPEECH_VOLUME_KEY]);
  const storedTimezone = current.webUiSettings?.[TIMEZONE_KEY] ?? '';
  if (storedTimezone) {
    timezoneDraft.value = storedTimezone;
  } else if (!timezoneAutoSeeded) {
    // First visit with nothing configured: persist the browser's zone so
    // the server (prompt date block, current_time, scheduler) resolves a
    // real timezone even for headless turns — not just server UTC.
    timezoneAutoSeeded = true;
    const detected = browserTimezone();
    timezoneDraft.value = detected;
    void saveSetting(TIMEZONE_KEY, detected).catch(() => undefined);
  }
  // chat.language may have changed too — re-filter voice options.
  refreshVoiceOptions();
}, { immediate: true });

function parseSpeechRate(raw: string | undefined): number {
  if (!raw) return DEFAULT_RATE;
  const parsed = parseFloat(raw);
  if (!Number.isFinite(parsed)) return DEFAULT_RATE;
  return Math.max(MIN_RATE, Math.min(MAX_RATE, parsed));
}

function parseSpeechVolume(raw: string | undefined): number {
  if (!raw) return DEFAULT_VOLUME;
  const parsed = parseFloat(raw);
  if (!Number.isFinite(parsed)) return DEFAULT_VOLUME;
  return Math.max(MIN_VOLUME, Math.min(MAX_VOLUME, parsed));
}

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

async function onTimezoneChanged(value: string | null): Promise<void> {
  timezoneSaved.value = null;
  const next = value ?? '';
  if (!next) return;
  timezoneDraft.value = next;
  // Always concrete — a blank timezone would drop headless turns back to
  // server UTC, so there is no "unset" option here.
  await saveSetting(TIMEZONE_KEY, next).catch(() => undefined);
  if (!error.value) {
    timezoneSaved.value = t('profile.preferences.timezoneSaved');
  }
}

async function onOpenDocsNewTabChanged(value: boolean): Promise<void> {
  openDocsNewTabSaved.value = null;
  openDocsNewTabDraft.value = value;
  // True is the default — store it as the absence of the setting so
  // the cookie/DB stay tidy. Only the explicit opt-out is persisted.
  if (value) {
    await deleteSetting(OPEN_DOCS_NEW_TAB_KEY).catch(() => undefined);
  } else {
    await saveSetting(OPEN_DOCS_NEW_TAB_KEY, 'false').catch(() => undefined);
  }
  if (!error.value) {
    openDocsNewTabSaved.value = t('profile.preferences.openDocsNewTabSaved');
  }
}

async function onSpeechVoiceChanged(value: string | null): Promise<void> {
  speechVoiceSaved.value = null;
  const next = value ?? '';
  speechVoiceDraft.value = next;
  // Empty value clears the override → resolveVoice() falls back to
  // the first voice in the resolved language.
  if (next === '') {
    await deleteSetting(SPEECH_VOICE_KEY).catch(() => undefined);
  } else {
    await saveSetting(SPEECH_VOICE_KEY, next).catch(() => undefined);
  }
  if (!error.value) {
    speechVoiceSaved.value = t('profile.speech.voiceSaved');
  }
}

async function onSpeechRateInput(event: Event): Promise<void> {
  speechRateSaved.value = null;
  const value = parseFloat((event.target as HTMLInputElement).value);
  if (!Number.isFinite(value)) return;
  const clamped = Math.max(MIN_RATE, Math.min(MAX_RATE, value));
  speechRateDraft.value = clamped;
  if (clamped === DEFAULT_RATE) {
    await deleteSetting(SPEECH_RATE_KEY).catch(() => undefined);
  } else {
    await saveSetting(SPEECH_RATE_KEY, String(clamped)).catch(() => undefined);
  }
  if (!error.value) {
    speechRateSaved.value = t('profile.speech.rateSaved');
  }
}

// ─── Actions section ──────────────────────────────────────────────
// Admin-only triggers for brain-wide caches. The server enforces
// Action.ADMIN on the underlying endpoint; client gating is intentionally
// permissive — a non-admin call surfaces the 403 as `refreshError`.

interface ModelCatalogRefreshResponse {
  refreshedAt: string;
  bundledModelsLoaded: number;
  bundledProvidersLoaded: number;
  overrideScopes: number;
  durationMs: number;
}

const refreshBusy = ref(false);
const refreshResult = ref<string | null>(null);
const refreshError = ref<string | null>(null);

async function onRefreshModelCatalog(): Promise<void> {
  refreshBusy.value = true;
  refreshResult.value = null;
  refreshError.value = null;
  try {
    const result = await brainFetch<ModelCatalogRefreshResponse>(
      'POST',
      'admin/ai-models/refresh',
    );
    refreshResult.value = t('profile.actions.refreshModelCatalogResult', {
      bundled: result.bundledModelsLoaded,
      providers: result.bundledProvidersLoaded,
      scopes: result.overrideScopes,
      ms: result.durationMs,
    });
  } catch (e: unknown) {
    refreshError.value = e instanceof Error ? e.message : String(e);
  } finally {
    refreshBusy.value = false;
  }
}

interface ModelDiscoveryResponse {
  tenantId: string;
  scopesScanned: number;
  instancesScanned: number;
  modelsWritten: number;
  modelsFailed: number;
  skippedInstances: Record<string, string>;
  durationMs: number;
  finishedAt: string;
}

const discoverBusy = ref(false);
const discoverResult = ref<string | null>(null);
const discoverSkipped = ref<Array<{ key: string; reason: string }>>([]);
const discoverError = ref<string | null>(null);

async function onDiscoverModels(): Promise<void> {
  discoverBusy.value = true;
  discoverResult.value = null;
  discoverSkipped.value = [];
  discoverError.value = null;
  try {
    const result = await brainFetch<ModelDiscoveryResponse>(
      'POST',
      'admin/ai-models/discover',
    );
    discoverResult.value = t('profile.actions.discoverModelsResult', {
      written: result.modelsWritten,
      instances: result.instancesScanned,
      scopes: result.scopesScanned,
      ms: result.durationMs,
    });
    discoverSkipped.value = Object.entries(result.skippedInstances ?? {})
      .map(([key, reason]) => ({ key, reason }));
  } catch (e: unknown) {
    discoverError.value = e instanceof Error ? e.message : String(e);
  } finally {
    discoverBusy.value = false;
  }
}

async function onSpeechVolumeInput(event: Event): Promise<void> {
  speechVolumeSaved.value = null;
  const value = parseFloat((event.target as HTMLInputElement).value);
  if (!Number.isFinite(value)) return;
  const clamped = Math.max(MIN_VOLUME, Math.min(MAX_VOLUME, value));
  speechVolumeDraft.value = clamped;
  if (clamped === DEFAULT_VOLUME) {
    await deleteSetting(SPEECH_VOLUME_KEY).catch(() => undefined);
  } else {
    await saveSetting(SPEECH_VOLUME_KEY, String(clamped)).catch(() => undefined);
  }
  if (!error.value) {
    speechVolumeSaved.value = t('profile.speech.volumeSaved');
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
              :model-value="timezoneDraft"
              :options="timezoneOptions"
              :label="$t('profile.preferences.timezone')"
              :disabled="loading"
              @update:model-value="onTimezoneChanged"
            />
            <p class="text-xs opacity-60 -mt-2">
              {{ $t('profile.preferences.timezoneDescription') }}
            </p>
            <span v-if="timezoneSaved" class="text-success text-sm">
              {{ timezoneSaved }}
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
            <VCheckbox
              :model-value="openDocsNewTabDraft"
              :label="$t('profile.preferences.openDocsNewTab')"
              :help="$t('profile.preferences.openDocsNewTabDescription')"
              :disabled="loading"
              @update:model-value="onOpenDocsNewTabChanged"
            />
            <span v-if="openDocsNewTabSaved" class="text-success text-sm">
              {{ openDocsNewTabSaved }}
            </span>
          </div>
        </VCard>

        <!-- Speech & Audio ───────────────────────────────────────────────── -->
        <VCard>
          <h2 class="text-lg font-semibold mb-3">{{ $t('profile.speech.title') }}</h2>
          <p class="text-sm opacity-70 mb-3">
            {{ $t('profile.speech.description') }}
          </p>
          <div class="flex flex-col gap-3">
            <template v-if="speechSupported">
              <VSelect
                :model-value="speechVoiceDraft"
                :options="voiceOptions"
                :label="$t('profile.speech.voice')"
                :disabled="loading"
                @update:model-value="onSpeechVoiceChanged"
              />
              <span v-if="speechVoiceSaved" class="text-success text-sm">
                {{ speechVoiceSaved }}
              </span>
              <div>
                <div class="text-sm font-medium mb-1 flex justify-between">
                  <span>{{ $t('profile.speech.rate') }}</span>
                  <span class="opacity-70">{{ speechRateDraft.toFixed(2) }}×</span>
                </div>
                <input
                  type="range"
                  class="range range-sm w-full"
                  :min="MIN_RATE"
                  :max="MAX_RATE"
                  step="0.05"
                  :value="speechRateDraft"
                  :disabled="loading"
                  @change="onSpeechRateInput"
                />
                <span v-if="speechRateSaved" class="text-success text-sm">
                  {{ speechRateSaved }}
                </span>
              </div>
              <div>
                <div class="text-sm font-medium mb-1 flex justify-between">
                  <span>{{ $t('profile.speech.volume') }}</span>
                  <span class="opacity-70">{{ Math.round(speechVolumeDraft * 100) }}%</span>
                </div>
                <input
                  type="range"
                  class="range range-sm w-full"
                  :min="MIN_VOLUME"
                  :max="MAX_VOLUME"
                  step="0.05"
                  :value="speechVolumeDraft"
                  :disabled="loading"
                  @change="onSpeechVolumeInput"
                />
                <span v-if="speechVolumeSaved" class="text-success text-sm">
                  {{ speechVolumeSaved }}
                </span>
              </div>
            </template>
            <p v-else class="text-sm opacity-60">
              {{ $t('profile.speech.voiceUnsupported') }}
            </p>
          </div>
        </VCard>

        <!-- Actions ─────────────────────────────────────────────────────── -->
        <VCard>
          <h2 class="text-lg font-semibold mb-3">{{ $t('profile.actions.title') }}</h2>
          <p class="text-sm opacity-70 mb-3">
            {{ $t('profile.actions.description') }}
          </p>
          <div class="flex flex-col gap-4">
            <div>
              <div class="flex items-center gap-3">
                <VButton
                  variant="secondary"
                  :loading="refreshBusy"
                  @click="onRefreshModelCatalog"
                >
                  {{ refreshBusy
                    ? $t('profile.actions.refreshModelCatalogBusy')
                    : $t('profile.actions.refreshModelCatalog') }}
                </VButton>
                <span v-if="refreshResult" class="text-success text-sm">
                  {{ refreshResult }}
                </span>
              </div>
              <p class="text-xs opacity-60 mt-1">
                {{ $t('profile.actions.refreshModelCatalogDescription') }}
              </p>
              <VAlert v-if="refreshError" variant="error" class="mt-2">
                {{ refreshError }}
              </VAlert>
            </div>

            <div>
              <div class="flex items-center gap-3">
                <VButton
                  variant="secondary"
                  :loading="discoverBusy"
                  @click="onDiscoverModels"
                >
                  {{ discoverBusy
                    ? $t('profile.actions.discoverModelsBusy')
                    : $t('profile.actions.discoverModels') }}
                </VButton>
                <span v-if="discoverResult" class="text-success text-sm">
                  {{ discoverResult }}
                </span>
              </div>
              <p class="text-xs opacity-60 mt-1">
                {{ $t('profile.actions.discoverModelsDescription') }}
              </p>
              <VAlert
                v-if="discoverSkipped.length > 0"
                variant="warning"
                class="mt-2"
              >
                <div class="font-medium">
                  {{ $t('profile.actions.discoverModelsSkipped', { count: discoverSkipped.length }) }}
                </div>
                <ul class="text-xs mt-1 list-disc pl-4">
                  <li
                    v-for="item in discoverSkipped"
                    :key="item.key"
                    class="font-mono"
                  >
                    {{ item.key }} — {{ item.reason }}
                  </li>
                </ul>
              </VAlert>
              <VAlert v-if="discoverError" variant="error" class="mt-2">
                {{ discoverError }}
              </VAlert>
            </div>
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
