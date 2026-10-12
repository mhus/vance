<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { VanceAccountWebView } from '@vance/facelift-account-webview';
import { getAccount, updateAccount } from '@/accounts/accountStore';
import { verifyVanceUrl } from '@/accounts/verifyVanceUrl';
import { isDesktop } from '@/platform';
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
  <div class="flex h-full flex-col">
    <header
      class="flex shrink-0 items-center gap-3 border-b border-gray-800 bg-gray-900 px-3"
      style="padding-top: env(safe-area-inset-top); padding-bottom: 0.5rem"
    >
      <button
        type="button"
        class="px-1 py-2 text-sm text-blue-400"
        @click="onCancel"
      >
        Cancel
      </button>
      <h1 class="flex-1 text-sm font-semibold">Edit account</h1>
      <button
        type="button"
        :disabled="submitting || notFound"
        class="px-1 py-2 text-sm font-medium text-blue-400 disabled:opacity-50"
        @click="onSubmit"
      >
        Save
      </button>
    </header>
    <div v-if="notFound" class="flex-1 p-4 text-sm text-red-400">
      Account not found.
    </div>
    <form v-else class="flex-1 space-y-4 overflow-y-auto p-4" @submit.prevent="onSubmit">
      <label class="block">
        <span class="mb-1 block text-xs uppercase tracking-wide text-gray-400">URL</span>
        <input
          v-model="faceUrl"
          type="url"
          autocomplete="off"
          autocapitalize="none"
          spellcheck="false"
          inputmode="url"
          class="w-full rounded border border-gray-700 bg-gray-800 px-3 py-2 outline-none focus:border-blue-400"
        />
        <p class="mt-1 text-xs text-gray-500">
          Changing the URL wipes this account's local session — you will need to sign in again.
        </p>
      </label>
      <label class="block">
        <span class="mb-1 block text-xs uppercase tracking-wide text-gray-400">Label</span>
        <input
          v-model="displayName"
          type="text"
          autocomplete="off"
          class="w-full rounded border border-gray-700 bg-gray-800 px-3 py-2 outline-none focus:border-blue-400"
        />
      </label>
      <div
        v-if="isDesktopApp"
        class="block rounded border border-gray-800 p-3"
      >
        <span class="mb-1 block text-xs uppercase tracking-wide text-gray-400">Agent tools</span>
        <div class="flex items-center gap-2">
          <code
            class="min-w-0 flex-1 truncate rounded bg-gray-800 px-2 py-2 text-sm text-gray-300"
            :title="workdir"
          >
            {{ workdir || '—' }}
          </code>
          <button
            type="button"
            class="shrink-0 rounded bg-gray-800 px-3 py-2 text-sm text-blue-400"
            @click="chooseWorkdir"
          >
            Choose…
          </button>
        </div>
        <p class="mt-1 text-xs text-gray-500">
          Working directory for the agent tools — relative paths and the session
          environment resolve against it. Changes apply with the next agent request.
        </p>
        <div class="mt-4 border-t border-gray-800 pt-3">
          <span class="mb-1 block text-xs uppercase tracking-wide text-gray-400">Pack home</span>
          <div class="flex items-center gap-2">
            <code
              class="min-w-0 flex-1 truncate rounded bg-gray-800 px-2 py-2 text-sm text-gray-300"
              :title="packHome ?? ''"
            >
              {{ packHome || '— (your real home)' }}
            </code>
            <button
              type="button"
              class="shrink-0 rounded bg-gray-800 px-3 py-2 text-sm text-blue-400"
              @click="choosePackHome"
            >
              Choose…
            </button>
            <button
              v-if="packHome"
              type="button"
              class="shrink-0 rounded bg-gray-800 px-3 py-2 text-xs text-gray-400"
              @click="clearPackHome"
            >
              Reset
            </button>
          </div>
          <p class="mt-1 text-xs text-gray-500">
            HOME for MCP pack processes (profiles, session data) — the npm cache
            stays on your real home. The current project
            <template v-if="currentProject">is <strong>{{ currentProject }}</strong></template>
            <template v-else>reports here once you select one in the app</template>.
          </p>
          <div v-if="Object.keys(packHomeByProject).length > 0" class="mt-2 space-y-1">
            <div
              v-for="(home, key) in packHomeByProject"
              :key="key"
              class="flex items-center gap-2"
            >
              <code class="w-40 shrink-0 truncate rounded bg-gray-800 px-2 py-1 text-xs" :class="key === currentKey ? 'text-blue-400' : 'text-gray-400'">
                {{ key }}
              </code>
              <code class="min-w-0 flex-1 truncate rounded bg-gray-800 px-2 py-1 text-xs text-gray-300" :title="home">
                {{ home }}
              </code>
              <button
                type="button"
                class="shrink-0 rounded bg-gray-800 px-2 py-1 text-xs text-blue-400"
                @click="choosePackHomeFor(String(key))"
              >
                …
              </button>
              <button
                type="button"
                class="shrink-0 rounded bg-gray-800 px-2 py-1 text-xs text-red-400"
                @click="removePackHomeFor(String(key))"
              >
                ✕
              </button>
            </div>
          </div>
          <button
            v-if="currentProject !== null"
            type="button"
            class="mt-2 rounded bg-gray-800 px-3 py-1.5 text-xs text-blue-400"
            @click="addPackHomeForCurrentProject"
          >
            Add home for “{{ currentProject }}”…
          </button>
        </div>
        <div class="mt-3 flex items-center gap-2">
          <button
            type="button"
            class="rounded bg-gray-800 px-3 py-1.5 text-xs text-blue-400"
            @click="reloadPacks"
          >
            Reload tool packs
          </button>
          <span v-if="packsStatus" class="text-xs text-gray-400">{{ packsStatus }}</span>
          <span v-else class="text-xs text-gray-500">
            MCP servers from ~/.vancetope/mcp.json — reload after editing.
          </span>
        </div>
        <label class="mt-3 flex items-start gap-2">
          <input
            type="checkbox"
            class="mt-1"
            :checked="confined"
            @change="toggleConfined"
          />
          <span>
            <span class="text-sm text-gray-300">Restrict to workdir</span>
            <span class="block text-xs text-gray-500">
              Paths outside the working directory are denied without asking. Explicit
              policy rules below still win.
            </span>
          </span>
        </label>
      </div>

      <div
        v-if="isDesktopApp"
        class="block rounded border border-gray-800 p-3"
      >
        <span class="mb-1 block text-xs uppercase tracking-wide text-gray-400">Sandbox policy</span>
        <div v-if="policyGroups.length === 0" class="text-xs text-gray-500">
          No rules yet — everything outside the deny floor asks. "Allow always" answers
          from the dialogs collect here.
        </div>
        <div v-else class="space-y-2">
          <div v-for="group in policyGroups" :key="group.label">
            <span class="text-xs text-gray-400">{{ group.label }}</span>
            <ul class="mt-0.5 space-y-1">
              <li v-for="rule in group.rules" :key="rule" class="flex items-center gap-2">
                <code class="min-w-0 flex-1 truncate rounded bg-gray-800 px-2 py-1 text-xs text-gray-300">{{ rule }}</code>
                <button
                  type="button"
                  class="shrink-0 rounded bg-gray-800 px-2 py-1 text-xs text-red-400"
                  @click="revokePolicyRule(group.domain, group.list, rule)"
                >
                  Revoke
                </button>
              </li>
            </ul>
          </div>
        </div>
        <p class="mt-2 text-xs text-gray-600">
          Always denied (not removable):
          {{ policy?.denyFloor?.join(', ') }}
        </p>
        <div class="mt-3 flex flex-wrap items-center gap-2">
          <select
            v-model="addDomain"
            class="rounded border border-gray-700 bg-gray-800 px-2 py-1 text-xs"
          >
            <option value="paths">paths</option>
            <option value="commands">commands</option>
            <option value="delete">delete</option>
          </select>
          <select
            v-model="addList"
            class="rounded border border-gray-700 bg-gray-800 px-2 py-1 text-xs"
          >
            <option value="deny">deny</option>
            <option value="allow">allow</option>
          </select>
          <input
            v-model="addRule"
            type="text"
            placeholder="e.g. ~/Documents/** or ^rm .*"
            class="min-w-0 flex-1 rounded border border-gray-700 bg-gray-800 px-2 py-1 text-xs"
            @keyup.enter="addPolicyRule"
          />
          <button
            type="button"
            class="rounded bg-gray-800 px-3 py-1 text-xs text-blue-400"
            @click="addPolicyRule"
          >
            Add rule
          </button>
          <button
            v-if="policyGroups.length > 0"
            type="button"
            class="rounded bg-gray-800 px-3 py-1 text-xs text-red-400"
            @click="resetPolicy"
          >
            Reset policy
          </button>
        </div>
        <p class="mt-1 text-xs text-gray-500">
          Path rules are globs, command rules are regular expressions.
        </p>

        <div class="mt-3 flex items-center gap-2 border-t border-gray-800 pt-3">
          <span class="text-xs text-gray-500">
            Sandbox:
            <span :class="policy?.sandbox ? 'text-green-400' : 'text-red-400'">
              {{ policy?.sandbox ? 'on' : 'OFF' }}
            </span>
          </span>
          <button
            v-if="policy?.sandbox && !sandboxArmed"
            type="button"
            class="rounded bg-gray-800 px-3 py-1 text-xs text-red-400"
            @click="armDisableSandbox"
          >
            Disable sandbox…
          </button>
          <button
            v-if="policy?.sandbox && sandboxArmed"
            type="button"
            class="rounded bg-red-900 px-3 py-1 text-xs text-red-200"
            @click="setSandbox(false)"
          >
            Confirm: disable sandbox
          </button>
          <button
            v-if="policy && !policy.sandbox"
            type="button"
            class="rounded bg-gray-800 px-3 py-1 text-xs text-green-400"
            @click="setSandbox(true)"
          >
            Enable sandbox
          </button>
        </div>
        <p
          v-if="policy && !policy.sandbox"
          class="mt-1 rounded border border-red-900 bg-red-950 px-2 py-1 text-xs text-red-400"
        >
          The sandbox is disabled: every file operation and shell command runs
          without confirmation — the deny floor included. Only for accounts
          you fully trust.
        </p>
      </div>
      <p v-if="error" class="text-sm text-red-400">{{ error }}</p>
    </form>
  </div>
</template>
