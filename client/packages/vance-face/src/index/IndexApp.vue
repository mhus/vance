<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  clearLegacyAuth,
  clearRememberedLogin,
  getRememberedLogin,
  setRememberedLogin,
} from '@vance/shared';
import {
  getSessionData,
  hydrateActiveWebUiSettings,
  hydrateIdentity,
  isAccessAlive,
  isRefreshAlive,
  login,
  LoginError,
  refreshAccessCookie,
} from '@/platform';
import { setUiLocale } from '@/i18n';
import { EditorShell, VAlert, VButton, VCard, VCheckbox, VInput } from '@/components';

const { t } = useI18n();

type Mode = 'login' | 'landing' | 'auto-login';

const mode = ref<Mode>('login');
const tenant = ref('default');
const username = ref('');
const password = ref('');
const submitting = ref(false);
const error = ref<string | null>(null);
const autoLoginNotice = ref<string | null>(null);

// "Remember user" — when checked, the (tenant, username) pair is
// persisted to localStorage and pre-fills the form on the next visit.
// Default-on once a remembered pair exists, default-off on a fresh
// browser. Never persists the password — that one stays out of
// localStorage on principle.
const rememberUser = ref(false);

onMounted(async () => {
  // Drop any stale localStorage tokens from the pre-cookie build.
  // Idempotent — no-op when already cleared.
  clearLegacyAuth();

  // Pre-fill the form from a previous "Remember user" tick. Tick the
  // checkbox by default once we know the user opted in last time —
  // unchecking it on the next login removes the entry.
  const remembered = getRememberedLogin();
  if (remembered) {
    tenant.value = remembered.tenant;
    username.value = remembered.username;
    rememberUser.value = true;
  }

  if (isAccessAlive()) {
    // Already-alive cookie path (user opened a fresh tab while
    // logged in) — mirror the webui.* settings into sessionStorage
    // so editors that consult {@link getActiveLanguage} have a
    // value before the user does anything.
    hydrateActiveWebUiSettings();
    syncUiLocaleFromSession();
    redirectAfterLogin();
    return;
  }

  // Access cookie expired but the refresh cookie may still be alive.
  // Try a silent re-mint — on success, flash a one-second
  // "Sie wurden eingeloggt" notice before redirecting so the user
  // sees that the page loaded fresh.
  if (getSessionData() && isRefreshAlive()) {
    mode.value = 'auto-login';
    autoLoginNotice.value = t('login.autoLoginNotice');
    const ok = await refreshAccessCookie();
    if (ok && isAccessAlive()) {
      // Refresh re-issued the data cookie — push fresh settings
      // into sessionStorage before the redirect mounts the next
      // editor.
      hydrateActiveWebUiSettings();
      hydrateIdentity();
      syncUiLocaleFromSession();
      window.setTimeout(redirectAfterLogin, 1000);
      return;
    }
    // Silent refresh failed — fall through to the login form.
    autoLoginNotice.value = null;
    mode.value = 'login';
    error.value = t('login.autoLoginFailed');
  }
});

/**
 * Pull the language from the just-hydrated sessionStorage / data
 * cookie and feed it into the i18n instance. Called after every
 * successful login or auto-login so the {@code mode === 'landing'}
 * editor list renders in the user's chosen language.
 */
function syncUiLocaleFromSession(): void {
  const lang = getSessionData()?.webUiSettings?.['webui.language'];
  setUiLocale(lang ?? null);
}

async function onSubmit(): Promise<void> {
  error.value = null;
  submitting.value = true;
  const trimmedTenant = tenant.value.trim();
  const trimmedUsername = username.value.trim();
  try {
    await login({
      tenant: trimmedTenant,
      username: trimmedUsername,
      password: password.value,
    });
    // Cookies are now set; mirror the webui.* settings into the
    // tab's sessionStorage so live reads (language, theme) come
    // from there until the user changes them in profile.
    hydrateActiveWebUiSettings();
    syncUiLocaleFromSession();
    // Persist or clear the (tenant, username) hint based on the
    // checkbox. Only a successful login is allowed to write — a
    // failed attempt mustn't leak its inputs into localStorage.
    if (rememberUser.value) {
      setRememberedLogin({ tenant: trimmedTenant, username: trimmedUsername });
    } else {
      clearRememberedLogin();
    }
    redirectAfterLogin();
  } catch (e) {
    if (e instanceof LoginError) {
      error.value = e.status === 401
        ? t('login.invalidCredentials')
        : t('login.loginFailedWithStatus', { status: e.status });
    } else {
      error.value = t('login.loginFailed');
    }
  } finally {
    submitting.value = false;
  }
}

function redirectAfterLogin(): void {
  const next = readNextParam();
  if (next) {
    window.location.replace(next);
    return;
  }
  // Default landing: keep this page mounted in 'landing' mode so the
  // user lands on the editor list rather than bouncing through a
  // separate URL.
  mode.value = 'landing';
}

/**
 * Pull and validate the `next` query parameter. We accept only same-origin
 * relative paths (must start with `/`, must not start with `//` or `\\`,
 * must not be a protocol-relative URL) — anything else is an open-redirect
 * risk and gets rejected to a `null` (which makes the caller fall back to
 * the default landing page).
 */
function readNextParam(): string | null {
  const raw = new URLSearchParams(window.location.search).get('next');
  if (!raw) return null;
  if (!raw.startsWith('/')) return null;
  if (raw.startsWith('//') || raw.startsWith('/\\')) return null;
  return raw;
}
</script>

<template>
  <!-- Login is the one editor that runs *before* a tenant is known, so it
       cannot use <EditorShell> (which renders user/tenant in the topbar).
       The hero layout is the documented exception. -->
  <div v-if="mode === 'auto-login'" class="hero min-h-screen bg-base-200">
    <div class="hero-content flex-col">
      <h1 class="text-3xl font-bold mb-4 font-mono">vance</h1>
      <VCard class="w-full max-w-md">
        <div class="flex items-center gap-3 py-2">
          <span class="loading loading-spinner loading-md" />
          <span>{{ autoLoginNotice }}</span>
        </div>
      </VCard>
    </div>
  </div>

  <div v-else-if="mode === 'login'" class="hero min-h-screen bg-base-200">
    <div class="hero-content w-full max-w-md flex-col">
      <h1 class="text-3xl font-bold mb-4 font-mono">vance</h1>
      <VCard class="w-full">
        <form class="flex flex-col gap-3" @submit.prevent="onSubmit">
          <VAlert v-if="error" variant="error">
            <span>{{ error }}</span>
          </VAlert>
          <VInput
            v-model="tenant"
            :label="$t('login.tenant')"
            required
            autocomplete="organization"
            :disabled="submitting"
          />
          <VInput
            v-model="username"
            :label="$t('login.username')"
            required
            autocomplete="username"
            :disabled="submitting"
          />
          <VInput
            v-model="password"
            type="password"
            :label="$t('login.password')"
            required
            autocomplete="current-password"
            :disabled="submitting"
          />
          <VCheckbox
            v-model="rememberUser"
            :label="$t('login.rememberUser')"
            :disabled="submitting"
          />
          <VButton
            type="submit"
            variant="primary"
            :loading="submitting"
            class="mt-2"
            block
          >
            {{ $t('common.signIn') }}
          </VButton>
        </form>
      </VCard>
    </div>
  </div>

  <EditorShell v-else :title="$t('common.home')">
    <div class="container mx-auto px-4 py-8 max-w-3xl">
      <h2 class="text-lg font-semibold mb-4">{{ $t('index.sectionTitle') }}</h2>
      <VCard>
        <ul class="flex flex-col gap-3">
          <li class="flex items-center justify-between gap-4">
            <div>
              <div class="font-semibold">{{ $t('index.chat.title') }}</div>
              <div class="text-sm opacity-70">{{ $t('index.chat.description') }}</div>
            </div>
            <VButton variant="primary" size="sm" href="/chat.html">
              {{ $t('index.open') }}
            </VButton>
          </li>
          <li class="flex items-center justify-between gap-4">
            <div>
              <div class="font-semibold">{{ $t('index.documents.title') }}</div>
              <div class="text-sm opacity-70">{{ $t('index.documents.description') }}</div>
            </div>
            <VButton variant="primary" size="sm" href="/documents.html">
              {{ $t('index.open') }}
            </VButton>
          </li>
          <li class="flex items-center justify-between gap-4">
            <div>
              <div class="font-semibold">{{ $t('index.inbox.title') }}</div>
              <div class="text-sm opacity-70">{{ $t('index.inbox.description') }}</div>
            </div>
            <VButton variant="primary" size="sm" href="/inbox.html">
              {{ $t('index.open') }}
            </VButton>
          </li>
          <li class="flex items-center justify-between gap-4">
            <div>
              <div class="font-semibold">{{ $t('index.scopes.title') }}</div>
              <div class="text-sm opacity-70">{{ $t('index.scopes.description') }}</div>
            </div>
            <VButton variant="primary" size="sm" href="/scopes.html">
              {{ $t('index.open') }}
            </VButton>
          </li>
          <li class="flex items-center justify-between gap-4">
            <div>
              <div class="font-semibold">{{ $t('index.tools.title') }}</div>
              <div class="text-sm opacity-70">{{ $t('index.tools.description') }}</div>
            </div>
            <VButton variant="primary" size="sm" href="/tools.html">
              {{ $t('index.open') }}
            </VButton>
          </li>
          <li class="flex items-center justify-between gap-4">
            <div>
              <div class="font-semibold">{{ $t('index.insights.title') }}</div>
              <div class="text-sm opacity-70">{{ $t('index.insights.description') }}</div>
            </div>
            <VButton variant="primary" size="sm" href="/insights.html">
              {{ $t('index.open') }}
            </VButton>
          </li>
          <li class="flex items-center justify-between gap-4">
            <div>
              <div class="font-semibold">{{ $t('index.users.title') }}</div>
              <div class="text-sm opacity-70">{{ $t('index.users.description') }}</div>
            </div>
            <VButton variant="primary" size="sm" href="/users.html">
              {{ $t('index.open') }}
            </VButton>
          </li>
        </ul>
      </VCard>
    </div>
  </EditorShell>
</template>
