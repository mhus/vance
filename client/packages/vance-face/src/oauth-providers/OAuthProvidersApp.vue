<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  CodeEditor,
  EditorShell,
  VAlert,
  VButton,
  VCard,
  VEmptyState,
  VInput,
  VModal,
} from '@/components';
import { useAdminOAuthProviders } from '@/composables/useAdminOAuthProviders';
import type { OAuthProviderWriteRequest } from '@vance/generated';

const PROVIDER_ID_PATTERN = /^[a-z0-9][a-z0-9_-]*$/;

const { t } = useI18n();
const state = useAdminOAuthProviders();

const selectedProviderId = ref<string | null>(null);
const banner = ref<string | null>(null);

// Form state — the YAML body is the source of truth; clientSecret is
// optional (only sent when the user explicitly types one).
const form = reactive({
  yaml: '',
  newClientSecret: '',
});

const showNewModal = ref(false);
const newProviderId = ref('');
const newProviderError = ref<string | null>(null);

const selected = computed(() => {
  if (!selectedProviderId.value) return null;
  return state.providers.value.find(p => p.providerId === selectedProviderId.value) ?? null;
});

const breadcrumbs = computed<string[]>(() =>
  selectedProviderId.value
    ? [t('oauthProviders.breadcrumbRoot'), selectedProviderId.value]
    : [t('oauthProviders.breadcrumbRoot')]);

onMounted(state.reload);

watch(selectedProviderId, () => {
  banner.value = null;
  if (selected.value) {
    form.yaml = selected.value.yaml ?? '';
    form.newClientSecret = '';
  } else {
    form.yaml = '';
    form.newClientSecret = '';
  }
});

async function save(): Promise<void> {
  if (!selectedProviderId.value) return;
  banner.value = null;
  const body: OAuthProviderWriteRequest = {
    yaml: form.yaml,
  };
  if (form.newClientSecret.length > 0) {
    body.clientSecret = form.newClientSecret;
  }
  try {
    await state.upsert(selectedProviderId.value, body);
    form.newClientSecret = '';
    banner.value = t('oauthProviders.banner.saved');
  } catch {
    /* error captured in state.error */
  }
}

async function removeClientSecret(): Promise<void> {
  if (!selectedProviderId.value) return;
  if (!confirm(t('oauthProviders.confirmRemoveSecret'))) return;
  const body: OAuthProviderWriteRequest = {
    yaml: form.yaml,
    clientSecret: '',
  };
  try {
    await state.upsert(selectedProviderId.value, body);
    banner.value = t('oauthProviders.banner.secretRemoved');
  } catch {
    /* error captured in state.error */
  }
}

async function deleteProvider(): Promise<void> {
  if (!selectedProviderId.value) return;
  if (!confirm(t('oauthProviders.confirmDelete', { id: selectedProviderId.value }))) return;
  try {
    await state.remove(selectedProviderId.value);
    selectedProviderId.value = null;
    banner.value = t('oauthProviders.banner.deleted');
  } catch {
    /* error captured in state.error */
  }
}

function openNewProvider(): void {
  newProviderId.value = '';
  newProviderError.value = null;
  showNewModal.value = true;
}

async function submitNewProvider(): Promise<void> {
  newProviderError.value = null;
  const id = newProviderId.value.trim().toLowerCase();
  if (!id) {
    newProviderError.value = t('oauthProviders.newModal.idRequired');
    return;
  }
  if (!PROVIDER_ID_PATTERN.test(id)) {
    newProviderError.value = t('oauthProviders.newModal.idPattern');
    return;
  }
  if (state.providers.value.some(p => p.providerId === id)) {
    newProviderError.value = t('oauthProviders.newModal.idAlreadyExists', { id });
    return;
  }
  const stub: OAuthProviderWriteRequest = {
    yaml: stubYamlForId(id),
  };
  try {
    await state.upsert(id, stub);
    showNewModal.value = false;
    selectedProviderId.value = id;
    banner.value = t('oauthProviders.banner.created', { id });
  } catch (e) {
    newProviderError.value =
      e instanceof Error ? e.message : t('oauthProviders.newModal.createFailed');
  }
}

function stubYamlForId(id: string): string {
  return [
    `# OAuth provider '${id}' — fill in the fields below and add the`,
    `# tenant PASSWORD setting 'oauth.${id}.client_secret' to complete.`,
    'type: oidc',
    'clientId: "REPLACE-ME"',
    'discoveryUrl: "https://idp.example.com/.well-known/openid-configuration"',
    'scopes:',
    '  - openid',
    '  - profile',
    '  - email',
    '',
  ].join('\n');
}

function selectProvider(providerId: string): void {
  selectedProviderId.value = providerId;
}
</script>

<template>
  <EditorShell :title="$t('oauthProviders.pageTitle')" :breadcrumbs="breadcrumbs">
    <!-- ─── Sidebar ─── -->
    <template #sidebar>
      <nav class="flex flex-col gap-1 p-2">
        <div class="flex items-center justify-between px-2 mb-1">
          <span class="text-xs uppercase opacity-50">
            {{ $t('oauthProviders.sidebar.providersLabel') }}
          </span>
          <VButton variant="ghost" size="sm" @click="openNewProvider">
            {{ $t('oauthProviders.sidebar.addNew') }}
          </VButton>
        </div>

        <div v-if="state.loading.value" class="px-2 text-xs opacity-60">
          {{ $t('oauthProviders.loading') }}
        </div>
        <VEmptyState
          v-else-if="state.providers.value.length === 0"
          :headline="$t('oauthProviders.sidebar.noProvidersHeadline')"
          :body="$t('oauthProviders.sidebar.noProvidersBody')"
        />

        <button
          v-for="p in state.providers.value"
          :key="p.providerId"
          class="provider-item"
          :class="{ 'provider-item--active': selectedProviderId === p.providerId }"
          type="button"
          @click="selectProvider(p.providerId)"
        >
          <div class="flex items-center justify-between gap-2">
            <span class="font-mono text-sm truncate">{{ p.providerId }}</span>
            <span class="text-xs px-1.5 py-0.5 rounded badge-type">{{ p.typeId }}</span>
          </div>
          <div class="flex items-center gap-2 text-xs opacity-60">
            <span
              :class="p.hasClientSecret ? 'badge-secret-set' : 'badge-secret-missing'"
            >
              {{ p.hasClientSecret
                ? $t('oauthProviders.sidebar.secretSet')
                : $t('oauthProviders.sidebar.secretMissing') }}
            </span>
          </div>
        </button>
      </nav>
    </template>

    <!-- ─── Main: form ─── -->
    <div class="p-6 flex flex-col gap-3 max-w-4xl">
      <VAlert v-if="state.error.value" variant="error">
        <span>{{ state.error.value }}</span>
      </VAlert>
      <VAlert v-if="banner" variant="success">
        <span>{{ banner }}</span>
      </VAlert>

      <VEmptyState
        v-if="!selected"
        :headline="$t('oauthProviders.empty.headline')"
        :body="$t('oauthProviders.empty.body')"
      />

      <template v-else>
        <VCard>
          <div class="flex items-center justify-between gap-3">
            <div>
              <div class="font-mono text-lg">{{ selected.providerId }}</div>
              <div class="text-sm opacity-70">
                <span class="badge-type">{{ selected.typeId }}</span>
                <span class="ml-2">
                  {{ $t('oauthProviders.detail.clientIdLabel') }}
                  <strong>{{ selected.clientId }}</strong>
                </span>
              </div>
            </div>
            <div class="flex gap-2">
              <VButton
                variant="danger"
                :loading="state.busy.value"
                @click="deleteProvider"
              >{{ $t('oauthProviders.detail.delete') }}</VButton>
              <VButton
                variant="primary"
                :loading="state.busy.value"
                @click="save"
              >{{ $t('oauthProviders.detail.save') }}</VButton>
            </div>
          </div>
        </VCard>

        <VCard :title="$t('oauthProviders.cards.yamlTitle')">
          <p class="text-xs opacity-70 mb-2">
            {{ $t('oauthProviders.cards.yamlHelp') }}
          </p>
          <CodeEditor
            v-model="form.yaml"
            mime-type="text/yaml"
            :rows="18"
          />
        </VCard>

        <VCard :title="$t('oauthProviders.cards.secretTitle')">
          <p class="text-xs opacity-70 mb-2">
            {{ selected.hasClientSecret
              ? $t('oauthProviders.cards.secretIsSet')
              : $t('oauthProviders.cards.secretIsMissing') }}
          </p>
          <VInput
            v-model="form.newClientSecret"
            type="password"
            :label="$t('oauthProviders.cards.newSecretLabel')"
            :help="$t('oauthProviders.cards.newSecretHelp')"
            autocomplete="new-password"
          />
          <div class="flex gap-2 mt-2">
            <VButton
              v-if="selected.hasClientSecret"
              variant="ghost"
              :loading="state.busy.value"
              @click="removeClientSecret"
            >{{ $t('oauthProviders.cards.removeSecret') }}</VButton>
          </div>
        </VCard>
      </template>
    </div>

    <!-- ─── New-provider modal ─── -->
    <VModal v-model="showNewModal" :title="$t('oauthProviders.newModal.title')">
      <div class="flex flex-col gap-3">
        <VAlert v-if="newProviderError" variant="error">
          <span>{{ newProviderError }}</span>
        </VAlert>
        <VInput
          v-model="newProviderId"
          :label="$t('oauthProviders.newModal.idLabel')"
          :help="$t('oauthProviders.newModal.idHelp')"
          required
        />
        <p class="text-xs opacity-70">
          {{ $t('oauthProviders.newModal.stubInfo') }}
        </p>
        <div class="flex justify-end gap-2">
          <VButton variant="ghost" @click="showNewModal = false">
            {{ $t('oauthProviders.newModal.cancel') }}
          </VButton>
          <VButton
            variant="primary"
            :loading="state.busy.value"
            @click="submitNewProvider"
          >{{ $t('oauthProviders.newModal.create') }}</VButton>
        </div>
      </div>
    </VModal>
  </EditorShell>
</template>

<style scoped>
.provider-item {
  display: block;
  text-align: left;
  padding: 0.5rem 0.6rem;
  border-radius: 0.375rem;
  background: transparent;
  cursor: pointer;
  width: 100%;
  border: 1px solid transparent;
}
.provider-item:hover { background: hsl(var(--bc) / 0.06); }
.provider-item--active {
  background: hsl(var(--p) / 0.12);
  border-color: hsl(var(--p) / 0.3);
}
.badge-type {
  background: hsl(var(--in) / 0.18);
  color: hsl(var(--inc));
  padding: 0 0.4rem;
  border-radius: 0.25rem;
}
.badge-secret-set {
  background: hsl(var(--su) / 0.18);
  color: hsl(var(--suc));
  padding: 0 0.4rem;
  border-radius: 0.25rem;
}
.badge-secret-missing {
  background: hsl(var(--wa) / 0.18);
  color: hsl(var(--wac));
  padding: 0 0.4rem;
  border-radius: 0.25rem;
}
</style>
