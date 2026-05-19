<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  EditorShell,
  VAlert,
  VButton,
  VCard,
  VEmptyState,
} from '@/components';
import { useOAuthConnectedAccounts } from '@/composables/useOAuthConnectedAccounts';

const { t } = useI18n();
const state = useOAuthConnectedAccounts();

onMounted(state.reload);

const sortedProviders = computed(() =>
  [...state.providers.value].sort((a, b) =>
    a.providerId.localeCompare(b.providerId)));

const banner = computed<string | null>(() => {
  // Surface the return-from-callback signal in the URL — the callback
  // ends with a 302 to returnTo, which the Web-UI sets to this page +
  // a marker query param so the user gets a "Connected ✓" toast.
  if (typeof window === 'undefined') return null;
  const params = new URLSearchParams(window.location.search);
  if (params.get('connected') === '1') {
    const provider = params.get('provider') ?? '';
    return t('connectedAccounts.banner.justConnected', { provider });
  }
  return null;
});

function onConnect(providerId: string): void {
  // Round-trip back to this page with a "connected" marker so the
  // success banner fires after the OAuth dance completes.
  const returnTo = `${window.location.pathname}?connected=1&provider=${encodeURIComponent(providerId)}`;
  state.connect(providerId, returnTo);
}

async function onDisconnect(providerId: string): Promise<void> {
  if (!confirm(t('connectedAccounts.confirmDisconnect', { provider: providerId }))) return;
  await state.disconnect(providerId);
}
</script>

<template>
  <EditorShell :title="$t('connectedAccounts.pageTitle')">
    <div class="p-6 flex flex-col gap-4 max-w-3xl">
      <VAlert v-if="state.error.value" variant="error">
        <span>{{ state.error.value }}</span>
      </VAlert>
      <VAlert v-if="banner" variant="success">
        <span>{{ banner }}</span>
      </VAlert>

      <VCard>
        <p class="text-sm opacity-70">{{ $t('connectedAccounts.intro') }}</p>
      </VCard>

      <div v-if="state.loading.value" class="text-sm opacity-60">
        {{ $t('connectedAccounts.loading') }}
      </div>

      <VEmptyState
        v-else-if="sortedProviders.length === 0"
        :headline="$t('connectedAccounts.empty.headline')"
        :body="$t('connectedAccounts.empty.body')"
      />

      <div v-else class="flex flex-col gap-2">
        <VCard
          v-for="p in sortedProviders"
          :key="p.providerId"
        >
          <div class="flex items-center justify-between gap-3">
            <div class="flex flex-col gap-1">
              <div class="font-mono text-base">{{ p.providerId }}</div>
              <div class="flex items-center gap-2 text-xs opacity-70">
                <span class="badge-type">{{ p.typeId }}</span>
                <span
                  :class="p.connected ? 'badge-connected' : 'badge-unconnected'"
                >
                  {{ p.connected
                    ? $t('connectedAccounts.statusConnected')
                    : $t('connectedAccounts.statusUnconnected') }}
                </span>
              </div>
            </div>
            <div class="flex gap-2">
              <VButton
                v-if="!p.connected"
                variant="primary"
                :loading="state.busy.value"
                @click="onConnect(p.providerId)"
              >
                {{ $t('connectedAccounts.connect') }}
              </VButton>
              <template v-else>
                <VButton
                  variant="ghost"
                  :loading="state.busy.value"
                  @click="onConnect(p.providerId)"
                >
                  {{ $t('connectedAccounts.reconnect') }}
                </VButton>
                <VButton
                  variant="danger"
                  :loading="state.busy.value"
                  @click="onDisconnect(p.providerId)"
                >
                  {{ $t('connectedAccounts.disconnect') }}
                </VButton>
              </template>
            </div>
          </div>
        </VCard>
      </div>
    </div>
  </EditorShell>
</template>

<style scoped>
.badge-type {
  background: hsl(var(--in) / 0.18);
  color: hsl(var(--inc));
  padding: 0 0.4rem;
  border-radius: 0.25rem;
}
.badge-connected {
  background: hsl(var(--su) / 0.18);
  color: hsl(var(--suc));
  padding: 0 0.4rem;
  border-radius: 0.25rem;
}
.badge-unconnected {
  background: hsl(var(--bc) / 0.1);
  color: hsl(var(--bc) / 0.6);
  padding: 0 0.4rem;
  border-radius: 0.25rem;
}
</style>
