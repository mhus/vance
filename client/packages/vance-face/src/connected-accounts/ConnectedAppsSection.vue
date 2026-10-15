<script setup lang="ts">
/**
 * The other direction of "connected": programs that hold access to <em>this</em>
 * account — OAuth grants (an MCP client that signed in through the browser)
 * and integration tokens (minted by hand for a tool). One list, because for
 * the person revoking it is the same question: what outside thing can still
 * act as me? See `specification/public/oauth-server.md` §Verbundene Apps.
 */
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import type { IntegrationTokenDto, OAuthGrantDto } from '@vance/generated';
import {
  integrationTokenIsLive,
  listIntegrationTokens,
  listOAuthGrants,
  revokeIntegrationToken,
  revokeOAuthGrant,
} from '@vance/shared';
import { VAlert, VBadge, VButton, VCard, VEmptyState, useConfirm } from '@/components';

const { t, locale } = useI18n();
const confirmAction = useConfirm();

const grants = ref<OAuthGrantDto[]>([]);
const tokens = ref<IntegrationTokenDto[]>([]);
const loading = ref(false);
const busy = ref(false);
const error = ref<string | null>(null);

const liveGrants = computed(() => grants.value.filter((g) => g.active));
const liveTokens = computed(() => tokens.value.filter(integrationTokenIsLive));

async function reload(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const [g, tk] = await Promise.all([listOAuthGrants(), listIntegrationTokens()]);
    grants.value = g;
    tokens.value = tk;
  } catch {
    error.value = t('connectedApps.loadFailed');
  } finally {
    loading.value = false;
  }
}

onMounted(reload);

function when(ts: number | undefined | null): string {
  if (!ts) return t('connectedApps.never');
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(ts));
}

function scopeText(g: OAuthGrantDto): string {
  switch (g.scope) {
    case 'mcp-project':
      return t('connectedApps.scope.mcpProject', { project: g.projectId ?? '' });
    case 'mcp':
      return t('connectedApps.scope.mcp');
    case 'agents':
      return t('connectedApps.scope.agents');
    default:
      return t('connectedApps.scope.account');
  }
}

async function onRevokeGrant(g: OAuthGrantDto): Promise<void> {
  if (!await confirmAction({
    message: t('connectedApps.confirmRevoke', { name: g.clientName }),
    confirmLabel: t('connectedApps.revoke'),
  })) return;
  busy.value = true;
  try {
    await revokeOAuthGrant(g.grantId);
    await reload();
  } catch {
    error.value = t('connectedApps.revokeFailed');
  } finally {
    busy.value = false;
  }
}

async function onRevokeToken(tk: IntegrationTokenDto): Promise<void> {
  if (!await confirmAction({
    message: t('connectedApps.confirmRevoke', { name: tk.label }),
    confirmLabel: t('connectedApps.revoke'),
  })) return;
  busy.value = true;
  try {
    await revokeIntegrationToken(tk.tokenId);
    await reload();
  } catch {
    error.value = t('connectedApps.revokeFailed');
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="flex flex-col gap-2">
    <h2 class="text-lg font-semibold mt-4">{{ $t('connectedApps.title') }}</h2>
    <p class="text-sm opacity-70">{{ $t('connectedApps.intro') }}</p>

    <VAlert v-if="error" variant="error">
      <span>{{ error }}</span>
    </VAlert>

    <div v-if="loading" class="text-sm opacity-60">{{ $t('connectedApps.loading') }}</div>

    <VEmptyState
      v-else-if="liveGrants.length === 0 && liveTokens.length === 0"
      :headline="$t('connectedApps.empty.headline')"
      :body="$t('connectedApps.empty.body')"
    />

    <template v-else>
      <VCard v-for="g in liveGrants" :key="g.grantId">
        <div class="flex items-center justify-between gap-3">
          <div class="flex flex-col gap-1 min-w-0">
            <div class="flex items-center gap-2">
              <span class="font-medium truncate">{{ g.clientName }}</span>
              <VBadge variant="info" size="sm">OAuth</VBadge>
            </div>
            <div class="text-xs opacity-70">{{ scopeText(g) }}</div>
            <div class="text-xs opacity-60">
              {{ $t('connectedApps.lastUsed', { when: when(g.lastUsedAtTimestamp) }) }}
            </div>
          </div>
          <VButton variant="danger" size="sm" :loading="busy" @click="onRevokeGrant(g)">
            {{ $t('connectedApps.revoke') }}
          </VButton>
        </div>
      </VCard>

      <VCard v-for="tk in liveTokens" :key="tk.tokenId">
        <div class="flex items-center justify-between gap-3">
          <div class="flex flex-col gap-1 min-w-0">
            <div class="flex items-center gap-2">
              <span class="font-medium truncate">{{ tk.label }}</span>
              <VBadge variant="neutral" size="sm">{{ $t('connectedApps.tokenBadge') }}</VBadge>
            </div>
            <div class="text-xs opacity-70">
              {{ tk.scopeProfileLabels.join(', ') }}<span v-if="tk.projectId"> · {{ tk.projectId }}</span>
            </div>
            <div class="text-xs opacity-60">
              {{ $t('connectedApps.lastUsed', { when: when(tk.lastUsedAtTimestamp) }) }}
            </div>
          </div>
          <VButton variant="danger" size="sm" :loading="busy" @click="onRevokeToken(tk)">
            {{ $t('connectedApps.revoke') }}
          </VButton>
        </div>
      </VCard>
    </template>
  </div>
</template>
