<script setup lang="ts">
import { computed, reactive, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  VCheckbox,
  VInput,
  VSegment,
  VSelect,
  VTextarea,
} from '@/components';
import {
  hasInlineLease,
  paramsFromState,
  stateFromParams,
  type McpAuthType,
  type McpLoginScope,
  type McpParams,
  type McpTransport,
} from './mcpServerParams';

/**
 * Structured editor for the {@code parameters} of an {@code mcp_server}
 * tool — everything {@code McpConfig} knows, including
 * {@code auth.type: oauth}.
 *
 * <p>The fields are local text state, initialised once from
 * {@code modelValue}; every edit emits freshly built parameters. Text is
 * never re-parsed into the fields while typing (an unfinished
 * {@code KEY=} line would vanish). Keys the form does not know stay as
 * they were — the JSON view shows them. The parent re-mounts the form
 * (`:key`) when it loads other parameters.
 */
const props = defineProps<{ modelValue: McpParams }>();
const emit = defineEmits<{ (e: 'update:modelValue', value: McpParams): void }>();

const { t } = useI18n();

const initial: McpParams = { ...props.modelValue };
const leaseIsInline = hasInlineLease(initial);
const state = reactive(stateFromParams(initial));

watch(state, () => emit('update:modelValue', paramsFromState(initial, state)), { deep: true });

// ─── Options ───────────────────────────────────────────────────────────

const transportOptions = computed(() => [
  { value: 'http' as McpTransport, label: t('tools.mcp.transportHttp') },
  { value: 'stdio' as McpTransport, label: t('tools.mcp.transportStdio') },
]);

const authOptions = computed(() => [
  { value: 'none' as McpAuthType, label: t('tools.mcp.authNone') },
  { value: 'oauth' as McpAuthType, label: t('tools.mcp.authOauth') },
  { value: 'bearer' as McpAuthType, label: t('tools.mcp.authBearer') },
  { value: 'apiKey' as McpAuthType, label: t('tools.mcp.authApiKey') },
  { value: 'basic' as McpAuthType, label: t('tools.mcp.authBasic') },
]);

const loginScopeOptions = computed(() => [
  { value: 'user' as McpLoginScope, label: t('tools.mcp.loginScopeUser') },
  { value: 'project' as McpLoginScope, label: t('tools.mcp.loginScopeProject') },
  { value: 'tenant' as McpLoginScope, label: t('tools.mcp.loginScopeTenant') },
]);
</script>

<template>
  <div class="flex flex-col gap-5">
    <!-- Connection -->
    <section class="flex flex-col gap-3">
      <h4 class="text-xs uppercase text-(--v-label-secondary)">{{ $t('tools.mcp.connection') }}</h4>
      <VSegment v-model="state.transport" :options="transportOptions" :aria-label="$t('tools.mcp.transport')" />
      <template v-if="state.transport === 'http'">
        <VInput
          v-model="state.url"
          type="url"
          :label="$t('tools.mcp.url')"
          :help="$t('tools.mcp.urlHelp')"
          placeholder="https://mcp.example.com/mcp"
          required
        />
      </template>
      <template v-else>
        <VTextarea
          v-model="state.command"
          :label="$t('tools.mcp.command')"
          :help="$t('tools.mcp.commandHelp')"
          :rows="3"
        />
        <VInput v-model="state.cwd" :label="$t('tools.mcp.cwd')" />
        <VTextarea v-model="state.env" :label="$t('tools.mcp.env')" :help="$t('tools.mcp.mapHelp')" :rows="3" />
      </template>
    </section>

    <!-- Authentication (HTTP only) -->
    <section v-if="state.transport === 'http'" class="flex flex-col gap-3">
      <h4 class="text-xs uppercase text-(--v-label-secondary)">{{ $t('tools.mcp.authentication') }}</h4>
      <VSelect v-model="state.authType" :options="authOptions" :label="$t('tools.mcp.authType')" />
      <template v-if="state.authType === 'oauth'">
        <p class="text-xs text-(--v-label-secondary)">{{ $t('tools.mcp.oauthHelp') }}</p>
        <VSelect
          v-model="state.loginScope"
          :options="loginScopeOptions"
          :label="$t('tools.mcp.loginScope')"
          :help="$t('tools.mcp.loginScopeHelp')"
        />
        <VInput v-model="state.scopes" :label="$t('tools.mcp.scopes')" :help="$t('tools.mcp.scopesHelp')" />
        <div class="grid grid-cols-1 md:grid-cols-2 gap-3">
          <div><VInput v-model="state.clientId" :label="$t('tools.mcp.clientId')" :help="$t('tools.mcp.clientIdHelp')" /></div>
          <div><VInput v-model="state.clientSecret" :label="$t('tools.mcp.clientSecret')" :help="$t('tools.mcp.secretHelp')" /></div>
        </div>
      </template>
      <template v-else-if="state.authType === 'bearer'">
        <VInput v-model="state.token" :label="$t('tools.mcp.token')" :help="$t('tools.mcp.secretHelp')" />
      </template>
      <template v-else-if="state.authType === 'apiKey'">
        <div class="grid grid-cols-1 md:grid-cols-2 gap-3">
          <div><VInput v-model="state.headerName" :label="$t('tools.mcp.headerName')" placeholder="X-API-Key" /></div>
          <div><VInput v-model="state.apiKeyValue" :label="$t('tools.mcp.apiKeyValue')" :help="$t('tools.mcp.secretHelp')" /></div>
        </div>
      </template>
      <template v-else-if="state.authType === 'basic'">
        <div class="grid grid-cols-1 md:grid-cols-2 gap-3">
          <div><VInput v-model="state.user" :label="$t('tools.mcp.user')" /></div>
          <div><VInput v-model="state.password" :label="$t('tools.mcp.password')" :help="$t('tools.mcp.secretHelp')" /></div>
        </div>
      </template>
    </section>

    <!-- TLS (HTTP only) -->
    <section v-if="state.transport === 'http'" class="flex flex-col gap-3">
      <h4 class="text-xs uppercase text-(--v-label-secondary)">{{ $t('tools.mcp.tls') }}</h4>
      <VCheckbox
        v-model="state.skipVerification"
        :label="$t('tools.mcp.skipVerification')"
        :help="$t('tools.mcp.skipVerificationHelp')"
      />
      <VTextarea v-model="state.trustedCaPem" :label="$t('tools.mcp.trustedCaPem')" :rows="3" />
    </section>

    <!-- Runtime -->
    <section class="flex flex-col gap-3">
      <h4 class="text-xs uppercase text-(--v-label-secondary)">{{ $t('tools.mcp.runtime') }}</h4>
      <div class="grid grid-cols-1 md:grid-cols-2 gap-3">
        <div><VInput v-model="state.timeoutSeconds" type="number" :label="$t('tools.mcp.timeoutSeconds')" /></div>
        <div><VInput v-model="state.initTimeoutSeconds" type="number" :label="$t('tools.mcp.initTimeoutSeconds')" /></div>
      </div>
      <VTextarea
        v-model="state.defaultArgs"
        :label="$t('tools.mcp.defaultArgs')"
        :help="$t('tools.mcp.defaultArgsHelp')"
        :rows="3"
      />
      <p v-if="leaseIsInline" class="text-xs text-(--v-label-secondary)">{{ $t('tools.mcp.leaseInline') }}</p>
      <VInput v-else v-model="state.lease" :label="$t('tools.mcp.lease')" :help="$t('tools.mcp.leaseHelp')" />
    </section>
  </div>
</template>
