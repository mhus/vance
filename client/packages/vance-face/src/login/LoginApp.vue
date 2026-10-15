<script setup lang="ts">
/**
 * The door.
 *
 * <p>Split out of the old `IndexApp.vue`, which held this and the launcher in
 * one component behind a `mode` ref. They are now separate entries, and that
 * separation is the point rather than a tidy-up: this is the one surface that
 * has to work when the other one does not. It carries no addon manifest, no
 * WebSocket, no Kind registry and no tenant — so a fault in the workspace
 * bundle cannot lock anyone out of the deployment it happens to run in.
 *
 * <p>On success it goes to `next` when that is a safe same-origin path, and to
 * the launcher otherwise. A `tenant` parameter (the OAuth authorization
 * endpoint sends one) pre-fills the tenant and keeps a live session of a
 * <em>different</em> tenant from short-circuiting the form — otherwise the
 * browser would bounce between this page and the endpoint, which only
 * accepts a session of its own tenant. Deliberately a real navigation, not a mode flip: the
 * workspace shell is another bundle, and it wants a clean boot with the
 * cookies that were just set.
 */
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  clearLegacyAuth,
  clearRememberedLogin,
  getRememberedLogin,
  isFacelift,
  setRememberedLogin,
} from '@vance/shared';
import {
  getSessionData,
  hydrateIdentity,
  isAccessAlive,
  isRefreshAlive,
  discoverSso,
  fetchSsoOptions,
  login,
  LoginError,
  refreshAccessCookie,
  canSignInViaApp,
  signInViaApp,
  ssoStartUrl,
} from '@/platform';
import { pushShareCredentials } from '@/platform/faceliftShareSetup';
import { loadRuntimeConfig, type RuntimeConfig } from '@/platform/runtimeConfig';
import { setUiLocale } from '@/i18n';
// Primitives straight from the package, and VanceLogo from its file. Going
// through vance-face's own `@/components` barrel would pull MarkdownView
// (70 KB) into the door, which renders no markdown — the barrel re-exports it,
// and vance-face cannot declare itself side-effect-free (`@/platform/bootWeb`
// is imported precisely FOR its side effect), so the import dodges it here.
import { VAlert, VButton, VCard, VCheckbox, VIcon, VInput } from '@vance/components';
import VanceLogo from '@/components/VanceLogo.vue';
import { SOURCE_CODE_URL } from '@/platform/sourceCode';
import PanicEasterEgg from './PanicEasterEgg.vue';

const { t } = useI18n();

type Mode = 'login' | 'auto-login';

const mode = ref<Mode>('login');

/**
 * Show the "Open in Vance app" banner only when we're on a mobile
 * browser AND not already in the Facelift wrapper. On Desktop the
 * banner would never lead anywhere; inside Facelift it would be
 * redundant. The custom URL-scheme tap silently no-ops when the
 * app isn't installed.
 */
const showOpenInAppBanner = computed<boolean>(() => {
  if (typeof navigator === 'undefined') return false;
  if (isFacelift()) return false;
  return /iPhone|iPad|iPod/i.test(navigator.userAgent);
});

/**
 * Server-side info from the pod-written `/config.json` — title shown
 * under the "vance" brand, optional backlink to the operator's home
 * page below it. Loaded once on mount; both fields are optional and
 * may be empty strings.
 */
const runtimeCfg = ref<RuntimeConfig | null>(null);
const serverTitle = computed<string>(() => runtimeCfg.value?.title?.trim() ?? '');
const serverBacklink = computed<string>(() => runtimeCfg.value?.backlink?.trim() ?? '');

const tenant = ref('');
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

// SSO (planning/sso-login.md §5.1). `ssoDomains`: some e-mail domain signs in
// via SSO on this installation — ask for the work address. `ssoConsumer`: the
// tenant this page is bound to accepts a private Google account — the button
// works without an address. Both stay false when the server offers neither,
// and the page looks as it always did.
const ssoDomains = ref(false);
const ssoConsumer = ref(false);
const ssoEmail = ref('');
const ssoBusy = ref(false);
const showSso = computed<boolean>(() => ssoDomains.value || ssoConsumer.value);

onMounted(async () => {
  void loadRuntimeConfig().then((cfg) => {
    runtimeCfg.value = cfg;
  });

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
  const requestedTenant = readTenantParam();
  void fetchSsoOptions(requestedTenant).then((options) => {
    ssoDomains.value = options?.domains === true;
    ssoConsumer.value = options?.sso === true;
  });
  const ssoError = new URLSearchParams(window.location.search).get('sso_error');
  if (ssoError) {
    // The server redirects back here with a coarse code only; the reason is
    // in its audit log, never in the URL.
    error.value = ssoError === 'rejected' ? t('login.sso.rejected') : t('login.sso.failed');
  }
  if (requestedTenant && requestedTenant !== tenant.value) {
    tenant.value = requestedTenant;
    username.value = '';
  }
  // A live session only counts when it belongs to the requested tenant.
  const sessionFits = !requestedTenant || getSessionData()?.tenantId === requestedTenant;

  if (sessionFits && isAccessAlive()) {
    // Already-alive cookie path (user opened a fresh tab while logged in, or
    // followed a stale link to the door). Nothing to ask — go where they were
    // heading.
    syncUiLocaleFromSession();
    goOn();
    return;
  }

  // Access cookie expired but the refresh cookie may still be alive.
  // Try a silent re-mint — on success, flash a one-second
  // "Sie wurden eingeloggt" notice before redirecting so the user
  // sees that the page loaded fresh.
  if (sessionFits && getSessionData() && isRefreshAlive()) {
    mode.value = 'auto-login';
    autoLoginNotice.value = t('login.autoLoginNotice');
    const ok = await refreshAccessCookie();
    if (ok && isAccessAlive()) {
      // Refresh re-issued the data cookie — pick the fresh values
      // up before the redirect boots the workspace.
      hydrateIdentity();
      syncUiLocaleFromSession();
      window.setTimeout(goOn, 1000);
      return;
    }
    // Silent refresh failed — fall through to the login form.
    autoLoginNotice.value = null;
    mode.value = 'login';
    error.value = t('login.autoLoginFailed');
  }
});

/**
 * Pull the language from the data cookie and feed it into the i18n
 * instance, so the one-second auto-login notice is in the user's own
 * language rather than the browser's.
 */
function syncUiLocaleFromSession(): void {
  const lang = getSessionData()?.webUiSettings?.['webui.language'];
  void setUiLocale(lang ?? null);
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
    // Cookies are now set. Mirror the fresh tenantId/username into the
    // prefsStore before we leave — the workspace boots from scratch and reads
    // them again, but the auto-login notice and any error rendered here
    // shouldn't see a half-empty session in the meantime.
    hydrateIdentity();
    syncUiLocaleFromSession();
    // Persist or clear the (tenant, username) hint based on the
    // checkbox. Only a successful login is allowed to write — a
    // failed attempt mustn't leak its inputs into localStorage.
    if (rememberUser.value) {
      setRememberedLogin({ tenant: trimmedTenant, username: trimmedUsername });
    } else {
      clearRememberedLogin();
    }
    goOn();
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

/**
 * "Continue with Google". A typed address goes by its domain; without one —
 * or with a Gmail address — only a tenant-bound page whose tenant accepts
 * private accounts can start. The decision is the server's: discover only
 * says whether the domain signs in via SSO, never where to.
 */
async function onSso(): Promise<void> {
  error.value = null;
  const email = ssoEmail.value.trim();
  const boundTenant = readTenantParam();
  ssoBusy.value = true;
  try {
    if (email) {
      const found = await discoverSso(email);
      if (found?.sso) {
        await startSso(email, boundTenant);
        return;
      }
      const isGmail = /@(gmail|googlemail)\.com$/i.test(email);
      if (!(ssoConsumer.value && isGmail)) {
        error.value = t('login.sso.notAvailable');
        return;
      }
    } else if (!ssoConsumer.value) {
      error.value = t('login.sso.emailRequired');
      return;
    }
    await startSso(email || null, boundTenant);
  } finally {
    ssoBusy.value = false;
  }
}

/**
 * In a plain browser: navigate to the start endpoint. Inside the app: the
 * wrapper runs the sign-in in the system browser and this page redeems the
 * handoff — Google refuses embedded WebViews, and the system browser's
 * cookies would not reach this one.
 */
async function startSso(email: string | null, boundTenant: string | null): Promise<void> {
  if (!canSignInViaApp()) {
    window.location.assign(ssoStartUrl({ email, tenant: boundTenant, next: readNextParam() }));
    return;
  }
  const outcome = await signInViaApp({ email, tenant: boundTenant, next: readNextParam() });
  if (outcome.kind === 'ok') {
    hydrateIdentity();
    syncUiLocaleFromSession();
    const session = getSessionData();
    if (session && typeof outcome.response.token === 'string') {
      pushShareCredentials({
        tenant: session.tenantId,
        username: session.username,
        token: outcome.response.token,
        refreshToken: outcome.response.refreshToken,
      });
    }
    goOn();
    return;
  }
  error.value = outcome.kind === 'rejected' ? t('login.sso.rejected') : t('login.sso.failed');
}

/** Leave for `next`, or for the launcher when there is none. */
function goOn(): void {
  window.location.replace(readNextParam() ?? '/');
}

/**
 * The `tenant` query parameter (sent by the OAuth authorization endpoint),
 * accepted only as a plain tenant name.
 */
function readTenantParam(): string | null {
  const raw = new URLSearchParams(window.location.search).get('tenant');
  return raw && /^[A-Za-z0-9_.-]+$/.test(raw) ? raw : null;
}

/**
 * Pull and validate the `next` query parameter. Only a same-origin path is
 * accepted — anything else is an open-redirect risk and gets rejected to
 * `null` (which makes the caller fall back to the launcher). Control
 * characters and backslashes are refused up front: the URL parser treats a
 * backslash like a slash and drops tabs and newlines, so `/\evil.example`
 * or `/` + TAB + `/evil.example` turn protocol-relative — a prefix check
 * alone cannot be trusted. The rest is
 * decided by the URL parser itself: the value must start with `/` and
 * resolve to this page's origin. Returned is the normalised
 * path + query + hash, never the raw input.
 */
function readNextParam(): string | null {
  const raw = new URLSearchParams(window.location.search).get('next');
  if (!raw) return null;
  // eslint-disable-next-line no-control-regex -- refusing control characters is the point
  if (/[\u0000-\u001F\u007F\\]/.test(raw)) return null;
  if (!raw.startsWith('/')) return null;
  let resolved: URL;
  try {
    resolved = new URL(raw, window.location.origin);
  } catch {
    return null;
  }
  if (resolved.origin !== window.location.origin) return null;
  return resolved.pathname + resolved.search + resolved.hash;
}
</script>

<template>
  <!-- Konami-code easter egg (↑↑↓↓←→←→BA). Self-contained overlay,
       armed on this page only. Renders nothing until triggered. -->
  <PanicEasterEgg />

  <!-- "Open in app" banner — only on the login page, only on iOS,
       only when not already running inside the Facelift wrapper.
       Slim, dismissible-by-tap: a single tap fires the
       vance-facelift:// URL scheme. iOS routes to the app if it's
       installed; if not, nothing visible happens (no App Store
       deep-link yet — Vance isn't on the store). -->
  <a
    v-if="showOpenInAppBanner"
    href="vance-facelift://"
    class="block w-full bg-primary text-primary-content no-underline"
  >
    <div class="mx-auto flex max-w-md items-center justify-between px-4 py-2 text-sm">
      <span class="inline-flex items-center gap-2"><VIcon name="open-in-app" />{{ $t('login.openInApp.message') }}</span>
      <span class="inline-flex items-center gap-1 font-semibold underline-offset-2">{{ $t('login.openInApp.action') }}<VIcon name="forward" /></span>
    </div>
  </a>

  <!-- Login runs *before* a tenant is known, so it cannot use <EditorShell>
       (which renders user/tenant in the topbar). The hero layout is the
       documented exception. -->
  <div v-if="mode === 'auto-login'" class="hero min-h-screen bg-base-200">
    <div class="hero-content flex-col">
      <VanceLogo size="xl" class="text-primary mb-2" />
      <h1 class="text-3xl font-bold mb-1 font-mono opacity-60">vancetope</h1>
      <p v-if="serverTitle" class="text-base font-medium opacity-70">{{ serverTitle }}</p>
      <a
        v-if="serverBacklink"
        :href="serverBacklink"
        class="link link-hover mb-4 text-sm opacity-60"
      >{{ serverBacklink }}</a>
      <div v-else class="mb-4"></div>
      <VCard class="w-full max-w-md">
        <div class="flex items-center gap-3 py-2">
          <VIcon name="loading" :size="20" class="animate-spin" />
          <span>{{ autoLoginNotice }}</span>
        </div>
      </VCard>
    </div>
  </div>

  <div v-else class="hero min-h-screen bg-base-200">
    <div class="hero-content w-full max-w-md flex-col">
      <VanceLogo size="xl" class="text-primary mb-2" />
      <h1 class="text-3xl font-bold mb-1 font-mono opacity-60">vancetope</h1>
      <p v-if="serverTitle" class="text-base font-medium opacity-70">{{ serverTitle }}</p>
      <a
        v-if="serverBacklink"
        :href="serverBacklink"
        class="link link-hover mb-4 text-sm opacity-60"
      >{{ serverBacklink }}</a>
      <div v-else class="mb-4"></div>
      <VCard class="w-full">
        <VAlert v-if="error" variant="error" class="mb-3">
          <span>{{ error }}</span>
        </VAlert>
        <form class="flex flex-col gap-3" @submit.prevent="onSubmit">
          <!-- autocapitalize="none" on both: the server compares tenant and
               username byte-for-byte, and iOS capitalises the first letter of
               a text field unless told otherwise. Without this the keyboard
               turns "apple"/"review" into "Apple"/"Review" and the login
               answers 401 — which reads as wrong credentials, not as a
               keyboard doing its job. -->
          <VInput
            v-model="tenant"
            :label="$t('login.tenant')"
            required
            autocomplete="organization"
            autocapitalize="none"
            :disabled="submitting"
          />
          <VInput
            v-model="username"
            :label="$t('login.username')"
            required
            autocomplete="username"
            autocapitalize="none"
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
        <form v-if="showSso" class="mt-3 flex flex-col gap-3" @submit.prevent="onSso">
          <div class="flex items-center gap-3 text-xs opacity-60">
            <span class="h-px flex-1 bg-base-300"></span>
            <span>{{ $t('login.sso.or') }}</span>
            <span class="h-px flex-1 bg-base-300"></span>
          </div>
          <VInput
            v-if="ssoDomains"
            v-model="ssoEmail"
            type="email"
            :label="$t('login.sso.email')"
            autocomplete="email"
            autocapitalize="none"
            :disabled="ssoBusy"
          />
          <VButton type="submit" variant="secondary" :loading="ssoBusy" block>
            {{ $t('login.sso.continueWithGoogle') }}
          </VButton>
        </form>
      </VCard>
      <p class="mt-2 text-xs opacity-60">
        {{ $t('login.license') }} ·
        <a :href="SOURCE_CODE_URL" target="_blank" rel="noopener noreferrer" class="underline">{{ $t('login.sourceCode') }}</a>
      </p>
    </div>
  </div>
</template>
