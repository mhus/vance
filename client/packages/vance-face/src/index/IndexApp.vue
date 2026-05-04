<script setup lang="ts">
import { onMounted, ref } from 'vue';
import {
  clearLegacyAuth,
  clearRememberedLogin,
  getRememberedLogin,
  getSessionData,
  isAccessAlive,
  isRefreshAlive,
  login,
  LoginError,
  refreshAccessCookie,
  setRememberedLogin,
} from '@vance/shared';
import { EditorShell, VAlert, VButton, VCard, VCheckbox, VInput } from '@/components';

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
    redirectAfterLogin();
    return;
  }

  // Access cookie expired but the refresh cookie may still be alive.
  // Try a silent re-mint — on success, flash a one-second
  // "Sie wurden eingeloggt" notice before redirecting so the user
  // sees that the page loaded fresh.
  if (getSessionData() && isRefreshAlive()) {
    mode.value = 'auto-login';
    autoLoginNotice.value = 'Sie wurden eingeloggt';
    const ok = await refreshAccessCookie();
    if (ok && isAccessAlive()) {
      window.setTimeout(redirectAfterLogin, 1000);
      return;
    }
    // Silent refresh failed — fall through to the login form.
    autoLoginNotice.value = null;
    mode.value = 'login';
    error.value = 'Auto-login failed. Please sign in again.';
  }
});

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
    error.value = e instanceof LoginError ? e.message : 'Login failed.';
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
            label="Tenant"
            required
            autocomplete="organization"
            :disabled="submitting"
          />
          <VInput
            v-model="username"
            label="Username"
            required
            autocomplete="username"
            :disabled="submitting"
          />
          <VInput
            v-model="password"
            type="password"
            label="Password"
            required
            autocomplete="current-password"
            :disabled="submitting"
          />
          <VCheckbox
            v-model="rememberUser"
            label="Remember user"
            :disabled="submitting"
          />
          <VButton
            type="submit"
            variant="primary"
            :loading="submitting"
            class="mt-2"
            block
          >
            Sign in
          </VButton>
        </form>
      </VCard>
    </div>
  </div>

  <EditorShell v-else title="Home">
    <div class="container mx-auto px-4 py-8 max-w-3xl">
      <h2 class="text-lg font-semibold mb-4">Editors</h2>
      <VCard>
        <ul class="flex flex-col gap-3">
          <li class="flex items-center justify-between gap-4">
            <div>
              <div class="font-semibold">Chat</div>
              <div class="text-sm opacity-70">
                Live chat with the brain over WebSocket. Pick an existing
                session or start a new one in any project.
              </div>
            </div>
            <VButton variant="primary" size="sm" href="/chat-editor.html">Open</VButton>
          </li>
          <li class="flex items-center justify-between gap-4">
            <div>
              <div class="font-semibold">Documents</div>
              <div class="text-sm opacity-70">Browse and edit project documents.</div>
            </div>
            <VButton variant="primary" size="sm" href="/document-editor.html">Open</VButton>
          </li>
          <li class="flex items-center justify-between gap-4">
            <div>
              <div class="font-semibold">Inbox</div>
              <div class="text-sm opacity-70">
                Read items from your personal inbox and the team-inbox of every
                team you're in. Reply, archive, delegate.
              </div>
            </div>
            <VButton variant="primary" size="sm" href="/inbox.html">Open</VButton>
          </li>
          <li class="flex items-center justify-between gap-4">
            <div>
              <div class="font-semibold">Scopes</div>
              <div class="text-sm opacity-70">
                Manage the tenant, project groups and projects. Edit settings
                at tenant or project scope.
              </div>
            </div>
            <VButton variant="primary" size="sm" href="/scopes.html">Open</VButton>
          </li>
          <li class="flex items-center justify-between gap-4">
            <div>
              <div class="font-semibold">Server Tools</div>
              <div class="text-sm opacity-70">
                Configure server-side tools per project. Pick a project
                or _vance for tenant-wide defaults.
              </div>
            </div>
            <VButton variant="primary" size="sm" href="/tools.html">Open</VButton>
          </li>
          <li class="flex items-center justify-between gap-4">
            <div>
              <div class="font-semibold">Insights</div>
              <div class="text-sm opacity-70">
                Inspect sessions, think-processes, chat history,
                memory and Marvin trees. Read-only diagnostic view.
              </div>
            </div>
            <VButton variant="primary" size="sm" href="/insights.html">Open</VButton>
          </li>
          <li class="flex items-center justify-between gap-4">
            <div>
              <div class="font-semibold">Users &amp; Teams</div>
              <div class="text-sm opacity-70">
                Manage tenant users (create, password reset, status)
                and teams (members, enabled flag).
              </div>
            </div>
            <VButton variant="primary" size="sm" href="/users.html">Open</VButton>
          </li>
        </ul>
      </VCard>
    </div>
  </EditorShell>
</template>
