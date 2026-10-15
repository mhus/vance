<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { VanceAccountWebView } from '@vance/facelift-account-webview';
import { getAccount, updateAccount } from '@/accounts/accountStore';
import { verifyVanceUrl } from '@/accounts/verifyVanceUrl';
import { isDesktop } from '@/platform';
import {
  VAlert,
  VButton,
  VGroup,
  VGroupRow,
  VIconButton,
  VInput,
  VModal,
  VSelect,
  VToggle,
} from '@vance/components';
import type { AgentPolicy, AgentPolicyRules } from '@vance/facelift-account-webview';

const route = useRoute();
const router = useRouter();

const accountId = ref<string>('');
const faceUrl = ref<string>('');
const displayName = ref<string>('');
const submitting = ref(false);
const error = ref<string | null>(null);
const notFound = ref(false);

// Desktop only: the per-account working directory of the agent tools
// (planning/desktop-agent-tools.md §9). Shell-side config like the
// release button — the hosted web UI never sees this section.
const isDesktopApp = isDesktop();
const workdir = ref('');
const confined = ref(false);
// Pack home (planning/desktop-pack-home-per-project.md): default + per
// `tenant/project`, plus the last reported project for the live row.
const packHome = ref<string | null>(null);
// Pack credentials (planning/desktop-pack-secrets.md §4): one row per
// `${secret:NAME}` the account's packs reference; values are write-only.
const packSecrets = ref<{ name: string; packs: string[]; set: boolean }[]>([]);
const secretDraft = ref<{ name: string; value: string } | null>(null);
const secretInput = ref<HTMLInputElement | null>(null);
watch(secretDraft, (draft) => {
  if (draft !== null) void nextTick(() => secretInput.value?.focus());
});
const packHomeByProject = ref<Record<string, string>>({});
const currentProject = ref<string | null>(null);
const currentTenant = ref<string | null>(null);
const policy = ref<AgentPolicy | null>(null);
const addDomain = ref<'paths' | 'commands' | 'delete'>('paths');
const sandboxArmed = ref(false);
const packsStatus = ref<string | null>(null);
const sandboxDisarm = ref<(() => void) | null>(null);
const addList = ref<'allow' | 'deny'>('deny');
const addRule = ref('');

onMounted(async () => {
  const id = String(route.params.id ?? '');
  if (!id) {
    notFound.value = true;
    return;
  }
  const account = await getAccount(id);
  if (account === null) {
    notFound.value = true;
    return;
  }
  accountId.value = account.id;
  faceUrl.value = account.faceUrl;
  displayName.value = account.displayName;
  if (isDesktopApp) {
    const bridge = window.faceliftDesktop;
    workdir.value = (await bridge?.workdirGet({ accountId: account.id })) ?? '';
    confined.value = (await bridge?.confineGet({ accountId: account.id })) ?? false;
    await refreshPackHome();
    await refreshPackSecrets();
    policy.value = (await bridge?.policyGet({ accountId: account.id })) ?? null;
  }
});

/** Open the native directory chooser and persist the pick. The main
 *  process validates the path; changes apply with the next agent
 *  request (the config is read per invoke). */
async function chooseWorkdir(): Promise<void> {
  if (!isDesktopApp || accountId.value === '') return;
  const picked = await window.faceliftDesktop?.workdirPick({ accountId: accountId.value });
  if (picked === null || picked === undefined) return;
  await window.faceliftDesktop?.workdirSet({ accountId: accountId.value, workdir: picked });
  workdir.value = picked;
}

/** Confinement: paths outside the workdir deny instead of asking. */
async function toggleConfined(): Promise<void> {
  if (!isDesktopApp || accountId.value === '') return;
  const next = !confined.value;
  await window.faceliftDesktop?.confineSet({ accountId: accountId.value, confined: next });
  confined.value = next;
}

async function refreshPackHome(): Promise<void> {
  if (!isDesktopApp || accountId.value === '') return;
  const config = await window.faceliftDesktop?.packHomeGet({ accountId: accountId.value });
  if (config === null || config === undefined) return;
  packHome.value = config.packHome;
  packHomeByProject.value = { ...config.packHomeByProject };
  currentProject.value = config.current.project;
  currentTenant.value = config.current.tenant;
}

/** The live row: the reported project, keyed `tenant/project`. */
const currentKey = computed(() => {
  const entries = Object.keys(packHomeByProject.value);
  if (currentProject.value === null) return null;
  return (
    entries.find((k) => k.endsWith(`/${currentProject.value}`)) ?? null
  );
});

async function choosePackHome(): Promise<void> {
  if (!isDesktopApp || accountId.value === '') return;
  const picked = await window.faceliftDesktop?.packHomePick({ accountId: accountId.value });
  if (picked === null || picked === undefined) return;
  await window.faceliftDesktop?.packHomeSet({ accountId: accountId.value, home: picked });
  packHome.value = picked;
}

async function clearPackHome(): Promise<void> {
  if (!isDesktopApp || accountId.value === '') return;
  await window.faceliftDesktop?.packHomeSet({ accountId: accountId.value, home: null });
  packHome.value = null;
}

async function choosePackHomeFor(key: string): Promise<void> {
  if (!isDesktopApp || accountId.value === '') return;
  const picked = await window.faceliftDesktop?.packHomePick({ accountId: accountId.value });
  if (picked === null || picked === undefined) return;
  await window.faceliftDesktop?.packHomeByProjectSet({ accountId: accountId.value, key, home: picked });
  packHomeByProject.value = { ...packHomeByProject.value, [key]: picked };
}

async function addPackHomeForCurrentProject(): Promise<void> {
  if (!isDesktopApp || accountId.value === '' || currentProject.value === null) return;
  const picked = await window.faceliftDesktop?.packHomePick({ accountId: accountId.value });
  if (picked === null || picked === undefined) return;
  const key = `${currentTenant.value ?? ''}/${currentProject.value}`;
  await window.faceliftDesktop?.packHomeByProjectSet({ accountId: accountId.value, key, home: picked });
  packHomeByProject.value = { ...packHomeByProject.value, [key]: picked };
}

async function refreshPackSecrets(): Promise<void> {
  if (!isDesktopApp || accountId.value === '') return;
  packSecrets.value = (await window.faceliftDesktop?.packSecretsGet({ accountId: accountId.value })) ?? [];
}

function askSecret(name: string): void {
  secretDraft.value = { name, value: '' };
}

async function submitSecret(): Promise<void> {
  if (secretDraft.value === null || secretDraft.value.value.length === 0) return;
  const { name, value } = secretDraft.value;
  secretDraft.value = null;
  await window.faceliftDesktop?.packSecretSet({ accountId: accountId.value, name, value });
  await refreshPackSecrets();
}

async function clearSecret(name: string): Promise<void> {
  await window.faceliftDesktop?.packSecretClear({ accountId: accountId.value, name });
  await refreshPackSecrets();
}

async function removePackHomeFor(key: string): Promise<void> {
  if (!isDesktopApp || accountId.value === '') return;
  await window.faceliftDesktop?.packHomeByProjectRemove({ accountId: accountId.value, key });
  const next = { ...packHomeByProject.value };
  delete next[key];
  packHomeByProject.value = next;
}

async function addPolicyRule(): Promise<void> {
  if (!isDesktopApp || accountId.value === '' || addRule.value.trim() === '') return;
  policy.value =
    (await window.faceliftDesktop?.policyAddRule({
      accountId: accountId.value,
      domain: addDomain.value,
      list: addList.value,
      rule: addRule.value.trim(),
    })) ?? policy.value;
  addRule.value = '';
}

/** Revoke one rule (an "Allow always" or a hand-added deny). */
async function revokePolicyRule(
  domain: 'paths' | 'commands' | 'delete',
  list: 'allow' | 'deny',
  rule: string,
): Promise<void> {
  if (!isDesktopApp || accountId.value === '') return;
  policy.value =
    (await window.faceliftDesktop?.policyRemoveRule({
      accountId: accountId.value,
      domain,
      list,
      rule,
    })) ?? policy.value;
}

/** Delete the policy file — everything asks again. */
async function resetPolicy(): Promise<void> {
  if (!isDesktopApp || accountId.value === '') return;
  await window.faceliftDesktop?.policyReset({ accountId: accountId.value });
  policy.value = (await window.faceliftDesktop?.policyGet({ accountId: accountId.value })) ?? null;
}

/** Disable sandbox is destructive: first click arms (auto-disarms after
 *  5 s), second click confirms. */
function armDisableSandbox(): void {
  disarmSandbox();
  sandboxArmed.value = true;
  const timer = setTimeout(() => {
    sandboxArmed.value = false;
    sandboxDisarm.value = null;
  }, 5000);
  sandboxDisarm.value = () => clearTimeout(timer);
}

function disarmSandbox(): void {
  sandboxDisarm.value?.();
  sandboxDisarm.value = null;
  sandboxArmed.value = false;
}

/** Tool packs (MCP): drop the cache and reconnect — mcp.json edits and
 *  restarted servers become visible without an app restart. The next
 *  session bind registers the fresh tool set. */
async function reloadPacks(): Promise<void> {
  if (!isDesktopApp || accountId.value === '') return;
  const result = await window.faceliftDesktop?.packsReload({ accountId: accountId.value });
  packsStatus.value =
    result === null || result === undefined
      ? '—'
      : `${result.servers} server(s), ${result.tools} tool(s)`
        + (result.errors > 0 ? `, ${result.errors} failed` : '');
}

async function setSandbox(sandbox: boolean): Promise<void> {
  if (!isDesktopApp || accountId.value === '') return;
  disarmSandbox();
  policy.value =
    (await window.faceliftDesktop?.policySetSandbox({
      accountId: accountId.value,
      sandbox,
    })) ?? policy.value;
}

/** Non-empty rule lists of the loaded policy, for rendering. */
const policyGroups = computed(() => {
  const p = policy.value;
  if (p === null) return [];
  const groups: { domain: 'paths' | 'commands' | 'delete'; list: 'allow' | 'deny'; label: string; rules: string[] }[] = [];
  const seen: [AgentPolicyRules, 'paths' | 'commands' | 'delete'][] = [
    [p.paths, 'paths'],
    [p.commands, 'commands'],
    [p.delete, 'delete'],
  ];
  for (const [rules, domain] of seen) {
    if (rules.allow.length > 0) {
      groups.push({ domain, list: 'allow', label: `${domain} — allow`, rules: rules.allow });
    }
    if (rules.deny.length > 0) {
      groups.push({ domain, list: 'deny', label: `${domain} — deny`, rules: rules.deny });
    }
  }
  return groups;
});

async function onSubmit(): Promise<void> {
  if (submitting.value) return;
  error.value = null;
  const url = faceUrl.value.trim();
  if (url.length === 0) {
    error.value = 'URL is required.';
    return;
  }
  try {

    new URL(url);
  } catch {
    error.value = 'Not a valid URL.';
    return;
  }
  submitting.value = true;
  try {
    // Verify the new URL really is a Vance instance — but only
    // when it actually changed; renaming the displayName alone
    // shouldn't round-trip to the server.
    const current = await getAccount(accountId.value);
    if (current === null) {
      error.value = 'Account no longer exists.';
      return;
    }
    if (url !== current.faceUrl) {
      const verify = await verifyVanceUrl(url);
      if (!verify.ok) {
        error.value = `Not a Vancetope instance (${verify.reason ?? 'unknown'})`;
        return;
      }
    }
    const result = await updateAccount(accountId.value, {
      faceUrl: url,
      displayName: displayName.value,
    });
    if (result === null) {
      error.value = 'Account no longer exists.';
      return;
    }
    if (result.faceUrlChanged) {
      // Wipe the cached native WebView + its persistent data store —
      // a new origin needs a clean cookie jar and the cached WebView
      // is still pointing at the old URL.
      await VanceAccountWebView.remove({ accountId: accountId.value });
    }
    void router.replace({ name: 'manage' });
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to save.';
  } finally {
    submitting.value = false;
  }
}

onBeforeUnmount(() => {
  disarmSandbox();
});

function onCancel(): void {
  void router.back();
}
</script>

<template>
  <div class="flex h-full flex-col bg-(--v-bg) text-(--v-label)">
    <header
      class="shrink-0 px-4"
      style="padding-top: max(0.75rem, env(safe-area-inset-top)); padding-bottom: 0.5rem"
    >
      <h1 class="text-[22px] leading-7 font-semibold">Edit account</h1>
    </header>
    <div v-if="notFound" class="flex-1 px-4 pt-2">
      <div class="mx-auto max-w-[720px]">
        <VAlert variant="error">Account not found.</VAlert>
      </div>
    </div>
    <form v-else class="flex-1 overflow-y-auto px-4 pt-2 pb-6" @submit.prevent="onSubmit">
      <div class="mx-auto flex max-w-[720px] flex-col gap-6">
        <div class="flex flex-col gap-4">
          <!-- A raw field rather than VInput: the URL needs spellcheck and
               inputmode on the <input> itself, which VInput does not pass
               through. Same markup and look as VInput. -->
          <label class="flex w-full flex-col gap-1">
            <span class="text-xs font-semibold text-(--v-label-secondary)">URL</span>
            <input
              v-model="faceUrl"
              type="url"
              autocomplete="off"
              autocapitalize="none"
              spellcheck="false"
              inputmode="url"
              class="input w-full [--size:2rem]"
            />
            <span class="text-xs text-(--v-label-secondary)">
              Changing the URL wipes this account's local session — you will need to sign in again.
            </span>
          </label>
          <VInput v-model="displayName" label="Label" autocomplete="off" />
        </div>

        <!-- Desktop only: agent tools of this account. -->
        <template v-if="isDesktopApp">
          <VGroup label="Agent tools">
            <VGroupRow label="Working directory">
              <template #description>
                <code class="font-mono" :title="workdir">{{ workdir || '—' }}</code>
              </template>
              <VButton variant="secondary" size="sm" @click="chooseWorkdir">
                Choose…
              </VButton>
            </VGroupRow>
            <VGroupRow
              label="Restrict to workdir"
              description="Paths outside the working directory are denied without asking. Explicit policy rules below still win."
            >
              <VToggle
                :model-value="confined"
                title="Restrict to workdir"
                @update:model-value="toggleConfined"
              />
            </VGroupRow>
            <VGroupRow :description="packsStatus ?? 'MCP servers from ~/.vancetope/mcp.json — reload after editing.'">
              <template #label>Tool packs</template>
              <VButton variant="secondary" size="sm" icon="refresh" @click="reloadPacks">
                Reload tool packs
              </VButton>
            </VGroupRow>
            <template #note>
              Working directory for the agent tools — relative paths and the session
              environment resolve against it. Changes apply with the next agent request.
            </template>
          </VGroup>

          <VGroup label="Pack home">
            <VGroupRow label="Default">
              <template #description>
                <code class="font-mono" :title="packHome ?? ''">{{ packHome || '— (your real home)' }}</code>
              </template>
              <VButton variant="secondary" size="sm" @click="choosePackHome">
                Choose…
              </VButton>
              <VButton v-if="packHome" variant="danger" size="sm" @click="clearPackHome">
                Reset
              </VButton>
            </VGroupRow>
            <VGroupRow v-for="(home, key) in packHomeByProject" :key="key">
              <template #label>
                <code
                  class="truncate font-mono text-xs"
                  :class="key === currentKey ? 'text-(--v-accent-text)' : ''"
                >{{ key }}</code>
              </template>
              <template #description>
                <code class="font-mono" :title="home">{{ home }}</code>
              </template>
              <VIconButton
                icon="folder-open"
                label="Choose…"
                tooltip="left"
                @click="choosePackHomeFor(String(key))"
              />
              <VIconButton
                icon="delete"
                label="Remove"
                tooltip="left"
                danger
                @click="removePackHomeFor(String(key))"
              />
            </VGroupRow>
            <VGroupRow v-if="currentProject !== null">
              <template #label>
                <VButton variant="plain" size="sm" icon="add" @click="addPackHomeForCurrentProject">
                  Add home for “{{ currentProject }}”…
                </VButton>
              </template>
            </VGroupRow>
            <template #note>
              HOME for MCP pack processes (profiles, session data) — the npm cache
              stays on your real home. The current project
              <template v-if="currentProject">is <strong class="font-semibold">{{ currentProject }}</strong></template>
              <template v-else>reports here once you select one in the app</template>.
            </template>
          </VGroup>

          <VGroup label="Pack credentials">
            <VGroupRow v-if="packSecrets.length === 0">
              <template #label>
                <span class="text-(--v-label-secondary)">
                  None of your tool packs needs a login. Packs that do list their credentials here.
                </span>
              </template>
            </VGroupRow>
            <VGroupRow
              v-for="sec in packSecrets"
              :key="sec.name"
            >
              <template #label>
                <code class="truncate font-mono text-xs">{{ sec.name }}</code>
              </template>
              <template #description>
                <span :title="sec.packs.join(', ')">{{ sec.packs.join(', ') }}</span>
              </template>
              <!-- Status as text in its meaning colour (corporate-design §3.3). -->
              <span
                class="shrink-0 text-xs font-medium"
                :class="sec.set ? 'text-(--v-success)' : 'text-(--v-warning)'"
              >
                {{ sec.set ? 'set' : 'missing' }}
              </span>
              <VButton variant="secondary" size="sm" @click="askSecret(sec.name)">
                {{ sec.set ? 'Change…' : 'Set…' }}
              </VButton>
              <VButton v-if="sec.set" variant="danger" size="sm" @click="clearSecret(sec.name)">
                Clear
              </VButton>
            </VGroupRow>
          </VGroup>

          <div class="flex flex-col gap-4">
            <VGroup label="Sandbox policy">
              <VGroupRow v-if="policyGroups.length === 0">
                <template #label>
                  <span class="text-(--v-label-secondary)">
                    No rules yet — everything outside the deny floor asks. "Allow always" answers
                    from the dialogs collect here.
                  </span>
                </template>
              </VGroupRow>
              <template v-for="group in policyGroups" :key="group.label">
                <VGroupRow
                  v-for="rule in group.rules"
                  :key="`${group.label}:${rule}`"
                  :description="group.label"
                >
                  <template #label>
                    <code class="truncate font-mono text-xs">{{ rule }}</code>
                  </template>
                  <VButton
                    variant="danger"
                    size="sm"
                    @click="revokePolicyRule(group.domain, group.list, rule)"
                  >
                    Revoke
                  </VButton>
                </VGroupRow>
              </template>
            </VGroup>
            <p class="px-4 text-xs text-(--v-label-secondary)">
              Always denied (not removable):
              {{ policy?.denyFloor?.join(', ') }}
            </p>

            <div class="flex flex-wrap items-end gap-2">
              <div class="w-32">
                <VSelect
                  :model-value="addDomain"
                  :options="[
                    { value: 'paths', label: 'paths' },
                    { value: 'commands', label: 'commands' },
                    { value: 'delete', label: 'delete' },
                  ]"
                  @update:model-value="(v) => { if (v !== null) addDomain = v }"
                />
              </div>
              <div class="w-24">
                <VSelect
                  :model-value="addList"
                  :options="[
                    { value: 'deny', label: 'deny' },
                    { value: 'allow', label: 'allow' },
                  ]"
                  @update:model-value="(v) => { if (v !== null) addList = v }"
                />
              </div>
              <div class="min-w-40 flex-1">
                <VInput
                  v-model="addRule"
                  placeholder="e.g. ~/Documents/** or ^rm .*"
                  @keyup.enter="addPolicyRule"
                />
              </div>
              <VButton variant="secondary" icon="add" @click="addPolicyRule">
                Add rule
              </VButton>
              <VButton
                v-if="policyGroups.length > 0"
                variant="danger"
                @click="resetPolicy"
              >
                Reset policy
              </VButton>
            </div>
            <p class="px-4 text-xs text-(--v-label-secondary)">
              Path rules are globs, command rules are regular expressions.
            </p>

            <VGroup>
              <VGroupRow>
                <template #label>
                  <span>
                    Sandbox:
                    <span
                      class="font-medium"
                      :class="policy?.sandbox ? 'text-(--v-success)' : 'text-(--v-danger)'"
                    >
                      {{ policy?.sandbox ? 'on' : 'OFF' }}
                    </span>
                  </span>
                </template>
                <VButton
                  v-if="policy?.sandbox && !sandboxArmed"
                  variant="danger"
                  size="sm"
                  @click="armDisableSandbox"
                >
                  Disable sandbox…
                </VButton>
                <VButton
                  v-if="policy?.sandbox && sandboxArmed"
                  variant="danger"
                  filled
                  size="sm"
                  @click="setSandbox(false)"
                >
                  Confirm: disable sandbox
                </VButton>
                <VButton
                  v-if="policy && !policy.sandbox"
                  variant="secondary"
                  size="sm"
                  @click="setSandbox(true)"
                >
                  Enable sandbox
                </VButton>
              </VGroupRow>
            </VGroup>
            <VAlert v-if="policy && !policy.sandbox" variant="error">
              The sandbox is disabled: every file operation and shell command runs
              without confirmation — the deny floor included. Only for accounts
              you fully trust.
            </VAlert>
          </div>
        </template>
        <VAlert v-if="error" variant="error">{{ error }}</VAlert>
      </div>
    </form>

    <footer
      class="border-t border-(--v-separator) px-4 pt-3"
      style="padding-bottom: max(1rem, env(safe-area-inset-bottom))"
    >
      <div class="mx-auto flex max-w-[720px] justify-end gap-2">
        <VButton variant="secondary" @click="onCancel">
          Cancel
        </VButton>
        <VButton
          variant="primary"
          :disabled="submitting || notFound"
          @click="onSubmit"
        >
          Save
        </VButton>
      </div>
    </footer>

    <!-- Credential entry: the value goes to the main via IPC and never
         back. Enter submits, Escape cancels. -->
    <VModal
      :model-value="secretDraft !== null"
      size="sm"
      title="Set credential"
      :description="secretDraft ? `${secretDraft.name} — stored encrypted in your OS keychain.` : undefined"
      :close-on-backdrop="false"
      @update:model-value="(open: boolean) => { if (!open) secretDraft = null; }"
    >
      <form v-if="secretDraft" id="secret-form" @submit.prevent="submitSecret">
        <input
          ref="secretInput"
          v-model="secretDraft.value"
          type="password"
          autocomplete="off"
          class="input w-full [--size:2rem]"
        />
      </form>
      <template #actions>
        <VButton variant="secondary" @click="secretDraft = null">Cancel</VButton>
        <VButton variant="primary" type="submit" form="secret-form">Save</VButton>
      </template>
    </VModal>
  </div>
</template>
