<script setup lang="ts">
/**
 * Form view for `vance-server-tool` documents — one server tool under
 * `_vance/server-tools/<name>.yaml` (spec `specification/public/server-tools.md`).
 *
 * The model is the whole YAML map (see `serverToolCodec.ts`): the form owns
 * type, description, flags, parameters, labels and prompt hint; every other
 * key passes through untouched. Edits bubble up as a fresh map through
 * `update:doc`; the shell serialises and saves through the ordinary document
 * pipeline (writing `_vance/server-tools/` requires project admin, the
 * `vance-server-tool` kind handler reports what the loader would reject).
 *
 * An `mcp_server` tool gets the structured server form (with a JSON escape
 * hatch for keys the form does not know), the login and the connection
 * test. Login and test act on the **saved** tool — the server resolves it
 * by name — so the view says so next to them.
 */
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  CodeEditor,
  VAlert,
  VBadge,
  VButton,
  VCheckbox,
  VGroup,
  VGroupRow,
  VIconButton,
  VSegment,
  VSelect,
  VTextarea,
  VToggle,
  useConfirm,
} from '@/components';
import type { ServerToolOAuthDto, ServerToolTestResultDto } from '@vance/generated';
import McpServerForm from './McpServerForm.vue';
import {
  MCP_SERVER_TYPE,
  applyFields,
  fieldsFromDoc,
  toolNameFromPath,
  type ServerToolDoc,
  type ServerToolFields,
} from './serverToolCodec';
import { useAdminServerTools } from '@/composables/useAdminServerTools';

defineOptions({ name: 'ServerToolFormView' });

const props = defineProps<{
  /** Parsed model — the whole YAML map. */
  doc: ServerToolDoc;
  /** Supplied by the shell for location-aware views. */
  projectId?: string;
  docPath?: string;
  /** Shell contract: a read-only binding greys its controls out. */
  readOnly?: boolean;
}>();

const emit = defineEmits<{
  (event: 'update:doc', doc: ServerToolDoc): void;
}>();

const { t } = useI18n();
const confirmAction = useConfirm();
const tools = useAdminServerTools();

/** Tenant-default project — tool overrides here are tenant-wide. */
const TENANT_PROJECT = '_tenant';
/** Query flag the MCP login's returnTo carries back. */
const LOGIN_RETURN_PARAM = 'mcpLogin';

// ── Form state ──────────────────────────────────────────────────────────

const form = reactive({
  type: '',
  description: '',
  enabled: true,
  primary: false,
  defaultDeferred: false,
  disabledSubToolsText: '',
  labelsText: '',
  promptHint: '',
});
/** Source of truth for the parameters; the JSON text below mirrors it. */
const parameters = ref<Record<string, unknown>>({});
const parametersJson = ref('{}');
const paramsError = ref<string | null>(null);

type ParamsMode = 'form' | 'json';
const paramsMode = ref<ParamsMode>('form');
/** Re-mounts the MCP form whenever its parameters are replaced from outside. */
const mcpFormKey = ref(0);

/** Guards the re-derivation against echoing the doc back as an edit. */
let suspend = false;
/** The last model this view emitted — its echo must not reset the form. */
let lastEmitted: ServerToolDoc | null = null;

function derive(doc: ServerToolDoc): void {
  suspend = true;
  const f = fieldsFromDoc(doc);
  form.type = f.type;
  form.description = f.description;
  form.enabled = f.enabled;
  form.primary = f.primary;
  form.defaultDeferred = f.defaultDeferred;
  form.disabledSubToolsText = f.disabledSubTools.join('\n');
  form.labelsText = f.labels.join('\n');
  form.promptHint = f.promptHint;
  parameters.value = f.parameters;
  parametersJson.value = formatJson(f.parameters);
  paramsError.value = null;
  mcpFormKey.value++;
  void nextTick(() => {
    suspend = false;
  });
}

derive(props.doc);

watch(
  () => props.doc,
  (doc) => {
    if (doc === lastEmitted) return;
    // A new model from outside — typically the state just saved, which
    // is also what login and test now act on.
    derive(doc);
    void refreshOperational();
  },
);

function buildFields(): ServerToolFields {
  return {
    type: form.type.trim(),
    description: form.description,
    parameters: parameters.value,
    labels: splitLines(form.labelsText),
    enabled: form.enabled,
    primary: form.primary,
    disabledSubTools: splitLines(form.disabledSubToolsText),
    defaultDeferred: form.defaultDeferred,
    promptHint: form.promptHint,
  };
}

watch(
  [form, parameters],
  () => {
    if (suspend) return;
    const out = applyFields(props.doc, buildFields());
    lastEmitted = out;
    emit('update:doc', out);
  },
  { deep: true },
);

// Typing in the JSON editor: only a parseable object replaces the parameters.
watch(parametersJson, (text) => {
  if (suspend) return;
  const parsed = parseParameters(text);
  if (parsed) parameters.value = parsed;
});

// Switching to or from MCP: the structured form re-reads the parameters.
watch(() => form.type, (type, previous) => {
  if (suspend || type === previous) return;
  if (type === MCP_SERVER_TYPE) {
    paramsMode.value = 'form';
    mcpFormKey.value++;
  } else {
    parametersJson.value = formatJson(parameters.value);
  }
});

const disabled = computed(() => props.readOnly === true);
const toolName = computed<string | null>(() => toolNameFromPath(props.docPath));
const isMcp = computed<boolean>(() => form.type === MCP_SERVER_TYPE);
const isTenantLayer = computed<boolean>(() => props.projectId === TENANT_PROJECT);

/**
 * A tool document outside `_vance/server-tools/` is the same kind but
 * inert — the loader only reads that tree. The form still edits it; the
 * banner says why it does nothing.
 */
const isDraft = computed<boolean>(() => !!props.docPath && toolName.value === null);

// ── Types ───────────────────────────────────────────────────────────────

const typeOptions = computed(() => {
  const ids = tools.types.value.map((x) => x.typeId);
  // A type this brain does not know stays selectable — it is what the
  // document says, and the kind handler reports it.
  if (form.type && !ids.includes(form.type)) ids.unshift(form.type);
  return ids.map((id) => ({ value: id, label: id }));
});

const typeSchema = computed<string | null>(() => {
  const match = tools.types.value.find((x) => x.typeId === form.type);
  return match ? JSON.stringify(match.parametersSchema, null, 2) : null;
});

const paramsModeOptions = computed(() => [
  { value: 'form' as ParamsMode, label: t('tools.mcp.modeForm') },
  { value: 'json' as ParamsMode, label: t('tools.mcp.modeJson') },
]);

/** Switches the MCP parameter editor between form and raw JSON. */
function switchParamsMode(mode: ParamsMode): void {
  if (mode === paramsMode.value) return;
  if (mode === 'json') {
    parametersJson.value = formatJson(parameters.value);
    paramsMode.value = 'json';
    return;
  }
  const parsed = parseParameters(parametersJson.value);
  if (!parsed) return;
  parameters.value = parsed;
  mcpFormKey.value++;
  paramsMode.value = 'form';
}

function parseParameters(text: string): Record<string, unknown> | null {
  try {
    const parsed: unknown = JSON.parse(text || '{}');
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      paramsError.value = t('tools.errors.parametersMustBeObject');
      return null;
    }
    paramsError.value = null;
    return parsed as Record<string, unknown>;
  } catch (e) {
    paramsError.value = t('tools.errors.parametersInvalidJson', {
      message: e instanceof Error ? e.message : 'parse error',
    });
    return null;
  }
}

function formatJson(obj: unknown): string {
  try {
    return JSON.stringify(obj, null, 2);
  } catch {
    return '{}';
  }
}

function splitLines(s: string): string[] {
  return s
    .split(/[\n,]/)
    .map((x) => x.trim())
    .filter((x) => x.length > 0);
}

// ── MCP login + connection test (saved state) ───────────────────────────

const banner = ref<string | null>(null);
const testResult = ref<ServerToolTestResultDto | null>(null);
const oauth = computed<ServerToolOAuthDto | null>(() => tools.current.value?.oauth ?? null);
/** Login and test need a saved tool in the tool tree of a project. */
const canOperate = computed<boolean>(() => !!props.projectId && toolName.value !== null);

function loginStatusVariant(state: ServerToolOAuthDto): 'success' | 'warning' | 'neutral' {
  if (state.status === 'CONNECTED') return 'success';
  if (state.status === 'EXPIRED') return 'warning';
  return 'neutral';
}

async function refreshOperational(): Promise<void> {
  if (!canOperate.value || !isMcp.value) return;
  await tools.loadOne(props.projectId!, toolName.value!);
}

async function login(): Promise<void> {
  if (!canOperate.value) return;
  // Back to exactly this view — the settings entry or the Cortex tab.
  const url = new URL(window.location.href);
  url.searchParams.set(LOGIN_RETURN_PARAM, '1');
  try {
    await tools.login(props.projectId!, toolName.value!, `${url.pathname}${url.search}`);
  } catch {
    /* error in tools.error */
  }
}

async function logout(): Promise<void> {
  if (!canOperate.value) return;
  if (!await confirmAction({
    message: t('tools.mcp.confirmLogout', { name: toolName.value }),
    confirmLabel: t('tools.mcp.logout'),
  })) return;
  try {
    await tools.logout(props.projectId!, toolName.value!);
    banner.value = t('tools.mcp.loggedOut');
  } catch {
    /* error in tools.error */
  }
}

async function runTest(): Promise<void> {
  if (!canOperate.value) return;
  testResult.value = null;
  try {
    testResult.value = await tools.test(props.projectId!, toolName.value!);
  } catch {
    /* error in tools.error */
  }
}

/** Sub-tools the server lists that are not switched off. */
function isSubToolEnabled(name: string): boolean {
  return !splitLines(form.disabledSubToolsText).includes(name);
}

function setSubToolEnabled(name: string, enabled: boolean): void {
  const off = splitLines(form.disabledSubToolsText).filter((x) => x !== name);
  if (!enabled) off.push(name);
  form.disabledSubToolsText = off.join('\n');
}

watch(isMcp, () => { void refreshOperational(); });

onMounted(async () => {
  // Coming back from an MCP login: the returnTo carried the flag.
  const url = new URL(window.location.href);
  if (url.searchParams.get(LOGIN_RETURN_PARAM) === '1') {
    url.searchParams.delete(LOGIN_RETURN_PARAM);
    window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}${url.hash}`);
    banner.value = t('tools.mcp.loggedIn');
  }
  await Promise.all([tools.loadTypes(), refreshOperational()]);
});
</script>

<template>
  <div class="max-w-3xl mx-auto p-4 space-y-5">
    <VAlert v-if="isDraft" variant="info">{{ t('tools.detail.draftHint') }}</VAlert>
    <VAlert v-else-if="isTenantLayer" variant="info">{{ t('tools.detail.tenantNote') }}</VAlert>
    <VAlert v-if="tools.error.value" variant="error">{{ tools.error.value }}</VAlert>
    <VAlert v-if="banner" variant="success">{{ banner }}</VAlert>

    <!-- ── Identity ──────────────────────────────────────────────── -->
    <section class="space-y-3">
      <h3 class="text-sm font-semibold text-base-content/70 uppercase tracking-wide">
        {{ t('tools.cards.identityTitle') }}
      </h3>
      <VSelect
        :model-value="form.type"
        :options="typeOptions"
        :label="t('tools.fields.type')"
        required
        :disabled="disabled"
        @update:model-value="(v: string | null) => { form.type = v ?? ''; }"
      />
      <VTextarea
        v-model="form.description"
        :label="t('tools.fields.description')"
        :help="t('tools.fields.descriptionHelp')"
        :rows="3"
        :mono="false"
        required
        :disabled="disabled"
      />
      <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <VCheckbox v-model="form.enabled" :label="t('tools.fields.enabled')" :disabled="disabled" />
        <VCheckbox v-model="form.primary" :label="t('tools.fields.primary')" :disabled="disabled" />
      </div>
    </section>

    <!-- ── MCP server: login, connection, test ───────────────────── -->
    <template v-if="isMcp">
      <VGroup
        v-if="oauth"
        :label="t('tools.mcp.loginTitle')"
        :note="t('tools.mcp.loginNote.' + oauth.loginScope)"
      >
        <VGroupRow
          :label="t('tools.mcp.status.' + oauth.status)"
          :meta="oauth.providerId"
          :description="t('tools.mcp.loginScopeShort.' + oauth.loginScope)"
        >
          <VBadge :variant="loginStatusVariant(oauth)" size="sm">
            {{ t('tools.mcp.status.' + oauth.status) }}
          </VBadge>
          <template v-if="oauth.canConnect">
            <VButton
              v-if="oauth.status !== 'CONNECTED'"
              variant="secondary"
              size="sm"
              icon="sign-in"
              :loading="tools.busy.value"
              @click="login"
            >{{ oauth.status === 'EXPIRED' ? t('tools.mcp.relogin') : t('tools.mcp.login') }}</VButton>
            <VIconButton
              v-else
              icon="sign-out"
              size="sm"
              :label="t('tools.mcp.logout')"
              @click="logout"
            />
          </template>
        </VGroupRow>
      </VGroup>

      <section class="space-y-3">
        <div class="flex items-center gap-3">
          <h3 class="flex-1 text-sm font-semibold text-base-content/70 uppercase tracking-wide">
            {{ t('tools.mcp.serverTitle') }}
          </h3>
          <VSegment
            :model-value="paramsMode"
            :options="paramsModeOptions"
            :aria-label="t('tools.mcp.modeLabel')"
            @update:model-value="switchParamsMode"
          />
        </div>
        <p class="text-xs text-(--v-label-secondary)">{{ t('tools.mcp.serverHelp') }}</p>
        <McpServerForm
          v-if="paramsMode === 'form'"
          :key="mcpFormKey"
          v-model="parameters"
        />
        <template v-else>
          <VAlert v-if="paramsError" variant="warning">{{ paramsError }}</VAlert>
          <CodeEditor v-model="parametersJson" mime-type="application/json" :rows="14" />
        </template>
      </section>

      <section class="space-y-3">
        <div class="flex items-center gap-3">
          <h3 class="flex-1 text-sm font-semibold text-base-content/70 uppercase tracking-wide">
            {{ t('tools.mcp.testTitle') }}
          </h3>
          <VButton
            variant="secondary"
            size="sm"
            icon="play"
            :disabled="!canOperate"
            :loading="tools.busy.value"
            @click="runTest"
          >{{ t('tools.mcp.testRun') }}</VButton>
        </div>
        <p class="text-xs text-(--v-label-secondary)">
          {{ t('tools.mcp.testHelp') }} {{ t('tools.mcp.savedStateNote') }}
        </p>
        <template v-if="testResult">
          <template v-if="testResult.ok">
            <VAlert variant="success">{{ t('tools.mcp.testOk', { n: testResult.tools.length }) }}</VAlert>
            <VGroup :note="t('tools.mcp.testToolsNote')">
              <VGroupRow v-for="name in testResult.tools" :key="name" :label="name">
                <VToggle
                  :model-value="isSubToolEnabled(name)"
                  :title="t('tools.mcp.subToolToggle', { name })"
                  :disabled="disabled"
                  @update:model-value="(v: boolean) => setSubToolEnabled(name, v)"
                />
              </VGroupRow>
            </VGroup>
          </template>
          <template v-else>
            <VAlert :variant="testResult.authRequired && testResult.issuer ? 'info' : 'error'">
              {{ testResult.error }}
            </VAlert>
            <VGroup v-if="testResult.issuer" :label="t('tools.mcp.discoveryTitle')">
              <VGroupRow :label="t('tools.mcp.discoveryIssuer')" :value="testResult.issuer" />
              <VGroupRow
                :label="t('tools.mcp.discoveryRegistration')"
                :value="t('tools.mcp.registration.' + (testResult.registration ?? 'none'))"
              />
              <VGroupRow
                v-if="testResult.scopes.length"
                :label="t('tools.mcp.scopes')"
                :value="testResult.scopes.join(' ')"
              />
            </VGroup>
          </template>
        </template>
      </section>
    </template>

    <!-- ── Other types: raw parameters + the type's schema ───────── -->
    <section v-else class="space-y-3">
      <h3 class="text-sm font-semibold text-base-content/70 uppercase tracking-wide">
        {{ t('tools.cards.parametersTitle') }}
      </h3>
      <p class="text-xs text-(--v-label-secondary)">{{ t('tools.cards.parametersHelp') }}</p>
      <VAlert v-if="paramsError" variant="warning">{{ paramsError }}</VAlert>
      <CodeEditor v-model="parametersJson" mime-type="application/json" :rows="14" />
      <details v-if="typeSchema" class="text-xs">
        <summary class="cursor-pointer text-(--v-label-secondary)">{{ t('tools.cards.typeSchemaTitle') }}</summary>
        <pre class="mt-2 whitespace-pre-wrap break-words bg-(--v-fill) p-2 rounded">{{ typeSchema }}</pre>
      </details>
    </section>

    <!-- ── Pack defaults ─────────────────────────────────────────── -->
    <section class="space-y-3">
      <h3 class="text-sm font-semibold text-base-content/70 uppercase tracking-wide">
        {{ t('tools.cards.packTitle') }}
      </h3>
      <p class="text-xs text-(--v-label-secondary)">{{ t('tools.cards.packHelp') }}</p>
      <VCheckbox
        v-model="form.defaultDeferred"
        :label="t('tools.fields.defaultDeferred')"
        :help="t('tools.fields.defaultDeferredHelp')"
        :disabled="disabled"
      />
      <VTextarea
        v-model="form.disabledSubToolsText"
        :label="t('tools.fields.disabledSubTools')"
        :help="t('tools.fields.disabledSubToolsHelp')"
        :rows="4"
        :disabled="disabled"
      />
    </section>

    <!-- ── Labels + prompt hint ──────────────────────────────────── -->
    <section class="space-y-3">
      <h3 class="text-sm font-semibold text-base-content/70 uppercase tracking-wide">
        {{ t('tools.cards.labelsTitle') }}
      </h3>
      <VTextarea
        v-model="form.labelsText"
        :label="t('tools.fields.labels')"
        :help="t('tools.fields.labelsHelp')"
        :rows="3"
        :disabled="disabled"
      />
      <VTextarea
        v-model="form.promptHint"
        :label="t('tools.fields.promptHint')"
        :help="t('tools.fields.promptHintHelp')"
        :rows="3"
        :mono="false"
        :disabled="disabled"
      />
    </section>
  </div>
</template>
