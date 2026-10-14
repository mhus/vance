<script setup lang="ts">
/**
 * Consent page of the OAuth server: an outside program (Claude Code, Cursor,
 * …) asked to sign in to one of this tenant's MCP doors, and the logged-in
 * user approves or declines.
 *
 * <p>The page shows what the server can vouch for next to what the program
 * merely claims: the client name is self-asserted (or, for a metadata
 * document client, vouched for by its domain), the redirect host is checked.
 * A loopback redirect gets a warning — any program on this machine could be
 * listening there, so approval only makes sense for a sign-in the user just
 * started. See `specification/public/oauth-server.md`.
 *
 * <p>The page is also shown for the first-party CLI (`/login --browser`) —
 * with its own wording: nothing is "unverified" there, but the access is the
 * whole account and goes to a loopback port.
 *
 * <p>Against (double-)clickjacking the Allow button is armed only once the
 * page is visible and focused, and only after a short delay; losing focus or
 * visibility disarms it again. A click that was aimed at another window
 * cannot land on it the moment this one is swapped in.
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { OAuthConsentRequestDto } from '@vance/generated';
import { decideConsent, getConsentRequest } from '@vance/shared';
import { EditorShell, VAlert, VButton, VCard } from '@/components';
import { getSessionData } from '@/platform';

const { t } = useI18n();

const params = new URLSearchParams(window.location.search);
const requestedTenant = params.get('tenant') ?? '';
const requestId = params.get('request') ?? '';

const request = ref<OAuthConsentRequestDto | null>(null);
const loading = ref(true);
const busy = ref(false);
const error = ref<string | null>(null);

/** The browser is logged in to another tenant than the one the request is for. */
const wrongTenant = computed<boolean>(() => {
  const session = getSessionData();
  return !!requestedTenant && !!session && session.tenantId !== requestedTenant;
});

const loginUrl = computed<string>(() => {
  const next = window.location.pathname + window.location.search;
  return `/login.html?tenant=${encodeURIComponent(requestedTenant)}&next=${encodeURIComponent(next)}`;
});

const doorText = computed<string>(() => {
  const r = request.value;
  if (!r) return '';
  switch (r.scope) {
    case 'mcp-project':
      return t('oauthConsent.door.mcpProject', { project: r.projectId ?? '' });
    case 'mcp':
      return t('oauthConsent.door.mcp', { tenant: r.tenantId });
    case 'agents':
      return t('oauthConsent.door.agents', { tenant: r.tenantId });
    default:
      return t('oauthConsent.door.account', { tenant: r.tenantId });
  }
});

const firstParty = computed<boolean>(() => request.value?.clientKind === 'first-party');

const headline = computed<string>(() => {
  const r = request.value;
  if (!r) return '';
  return firstParty.value
    ? t('oauthConsent.firstParty.headline', { client: r.clientName })
    : t('oauthConsent.headline', { client: r.clientName });
});

const clientNote = computed<string>(() => {
  const r = request.value;
  if (!r) return '';
  // The full metadata-document URL, not just its host: the path is part of
  // the identity (two programs can live on one domain).
  if (r.clientKind === 'cimd') return t('oauthConsent.client.cimd', { url: r.clientId });
  if (r.clientKind === 'first-party') return t('oauthConsent.client.firstParty');
  return t('oauthConsent.client.dcr');
});

/** Delay between the page becoming visible + focused and the Allow button arming. */
const ARM_DELAY_MS = 1000;

const approveArmed = ref(false);
let armTimer: number | undefined;

function pageActive(): boolean {
  return document.visibilityState === 'visible' && document.hasFocus();
}

function disarm(): void {
  approveArmed.value = false;
  if (armTimer !== undefined) {
    window.clearTimeout(armTimer);
    armTimer = undefined;
  }
}

/** Restart the arming delay — counted from the latest moment the page became active. */
function rearm(): void {
  disarm();
  if (!pageActive()) return;
  armTimer = window.setTimeout(() => {
    armTimer = undefined;
    approveArmed.value = pageActive();
  }, ARM_DELAY_MS);
}

function onVisibilityChange(): void {
  if (document.visibilityState === 'visible') rearm();
  else disarm();
}

// The delay also counts from the moment the button appears.
watch(request, rearm);

onBeforeUnmount(() => {
  disarm();
  document.removeEventListener('visibilitychange', onVisibilityChange);
  window.removeEventListener('focus', rearm);
  window.removeEventListener('blur', disarm);
});

onMounted(async () => {
  document.addEventListener('visibilitychange', onVisibilityChange);
  window.addEventListener('focus', rearm);
  window.addEventListener('blur', disarm);

  if (!requestId) {
    error.value = t('oauthConsent.missingRequest');
    loading.value = false;
    return;
  }
  if (wrongTenant.value) {
    loading.value = false;
    return;
  }
  try {
    request.value = await getConsentRequest(requestId);
  } catch {
    error.value = t('oauthConsent.expired');
  } finally {
    loading.value = false;
  }
});

async function decide(approve: boolean): Promise<void> {
  const r = request.value;
  if (!r) return;
  if (approve && !approveArmed.value) return;
  busy.value = true;
  error.value = null;
  try {
    const answer = await decideConsent({
      requestId: r.requestId,
      consentTicket: r.consentTicket,
      approve,
    });
    window.location.assign(answer.redirectUrl);
  } catch {
    error.value = t('oauthConsent.expired');
    busy.value = false;
  }
}
</script>

<template>
  <EditorShell :title="$t('oauthConsent.pageTitle')">
    <div class="p-6 flex flex-col gap-4 max-w-xl mx-auto">
      <VAlert v-if="error" variant="error">
        <span>{{ error }}</span>
      </VAlert>

      <div v-if="loading" class="text-sm opacity-60">{{ $t('oauthConsent.loading') }}</div>

      <VCard v-else-if="wrongTenant">
        <div class="flex flex-col gap-3">
          <p>{{ $t('oauthConsent.wrongTenant', { tenant: requestedTenant }) }}</p>
          <div>
            <VButton :href="loginUrl">{{ $t('oauthConsent.signInToTenant', { tenant: requestedTenant }) }}</VButton>
          </div>
        </div>
      </VCard>

      <VCard v-else-if="request">
        <div class="flex flex-col gap-4">
          <h2 class="text-lg font-semibold">{{ headline }}</h2>
          <p class="text-sm opacity-70">{{ clientNote }}</p>

          <VAlert v-if="firstParty" variant="warning">
            <span>{{ $t('oauthConsent.firstParty.warning') }}</span>
          </VAlert>

          <div class="flex flex-col gap-1">
            <span class="text-xs uppercase opacity-60">{{ $t('oauthConsent.accessLabel') }}</span>
            <span>{{ doorText }}</span>
          </div>

          <div class="flex flex-col gap-1">
            <span class="text-xs uppercase opacity-60">{{ $t('oauthConsent.accountLabel') }}</span>
            <span class="font-mono">{{ request.userId }} @ {{ request.tenantId }}</span>
          </div>

          <div class="flex flex-col gap-1">
            <span class="text-xs uppercase opacity-60">{{ $t('oauthConsent.redirectLabel') }}</span>
            <span class="font-mono break-all">{{ request.redirectHost }}</span>
          </div>

          <VAlert v-if="request.loopbackRedirect && !firstParty" variant="warning">
            <span>{{ $t('oauthConsent.loopbackWarning') }}</span>
          </VAlert>

          <p class="text-sm opacity-70">{{ $t('oauthConsent.revokeHint') }}</p>

          <div class="flex gap-2 justify-end">
            <VButton variant="ghost" :disabled="busy" @click="decide(false)">
              {{ $t('oauthConsent.decline') }}
            </VButton>
            <VButton :loading="busy" :disabled="!approveArmed" @click="decide(true)">
              {{ $t('oauthConsent.approve') }}
            </VButton>
          </div>
          <p v-if="!approveArmed && !busy" class="text-xs opacity-60 text-right">
            {{ $t('oauthConsent.approveArming') }}
          </p>
        </div>
      </VCard>
    </div>
  </EditorShell>
</template>
