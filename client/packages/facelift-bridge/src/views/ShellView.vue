<script setup lang="ts">
import { onBeforeUnmount, onMounted, nextTick, ref, computed, watch } from 'vue';
import { useRouter } from 'vue-router';
import type { PluginListenerHandle } from '@capacitor/core';
import { VanceAccountWebView, type AccountWebViewBounds } from '@vance/facelift-account-webview';
import {
  type Account,
  getAccount,
  getActiveAccountId,
  listAccounts,
  setActiveAccountId,
} from '@/accounts/accountStore';
import AccountSwitcherSheet from '@/components/AccountSwitcherSheet.vue';
import { isDesktop } from '@/platform';

/**
 * The main view of the app — a persistent native header on top of a
 * full-bleed *native* WKWebView (no iframe). The WKWebView is owned
 * by the `VanceAccountWebView` plugin and uses
 * `WKWebsiteDataStore(forIdentifier:)` per account so cookies,
 * IndexedDB and Service-Workers stay isolated even when two accounts
 * point at the same Brain origin.
 *
 * Layout sync: the header sits at the top of the Capacitor main
 * WebView; the native WebView gets positioned just below it via
 * `setBounds`. A ResizeObserver on the header element + a window
 * resize listener keep the frame in sync with rotations / safe-area
 * changes.
 */
const router = useRouter();
const accounts = ref<Account[]>([]);
const activeId = ref<string | null>(null);
const showSwitcher = ref(false);
const headerEl = ref<HTMLElement | null>(null);

const active = computed<Account | null>(() => {
  if (activeId.value === null) return null;
  return accounts.value.find((a) => a.id === activeId.value) ?? null;
});

/**
 * Desktop agent-tools release button (planning/desktop-agent-tools.md
 * §6.2): the switch for the *active* account lives in the app's own
 * frame — remote web content can honor the release but never flip it.
 * Dual-purpose: button background = on/off, icon color = activity
 * (red + pulsing while an invoke is in flight).
 */
const isDesktopApp = isDesktop();
const toolsEnabled = ref(false);
const toolsActive = ref(false);
// Live tabs (planning/desktop-profile-tabs.md): one WebView per
// (account, tab), state in the shell's RAM — nothing persists, a fresh
// app run starts with the single default tab. The native side caches
// views by the same key; switching tabs re-attaches without a reload.
interface TabState {
  id: string;
  title: string;
}
const MAX_TABS = 10;
let tabSeq = 0;
const tabsByAccount = ref(new Map<string, TabState[]>());
const activeTabByAccount = ref(new Map<string, string>());

const tabs = computed<TabState[]>(() =>
  activeId.value !== null ? (tabsByAccount.value.get(activeId.value) ?? []) : [],
);
const activeTabId = computed<string | null>(
  () => (activeId.value !== null ? (activeTabByAccount.value.get(activeId.value) ?? null) : null),
);

function titleFromUrl(url: string): string {
  try {
    const parsed = new URL(url);
    const kinds: Record<string, string> = {
      chat: 'Chat',
      documents: 'Documents',
      cortex: 'Cortex',
      insights: 'Insights',
      settings: 'Settings',
      runs: 'Runs',
      tools: 'Tools',
    };
    const segments = parsed.pathname.split('/').filter((s) => s.length > 0 && !s.includes('.'));
    const kind = segments.length > 0 ? (kinds[segments[segments.length - 1]] ?? null) : null;
    const project = parsed.searchParams.get('project');
    return kind !== null ? (project !== null ? `${kind} · ${project}` : kind) : 'Home';
  } catch {
    return 'Tab';
  }
}

/** The account's tab list, created lazily on first present. */
function ensureTabs(accountId: string): TabState[] {
  const existing = tabsByAccount.value.get(accountId);
  if (existing !== undefined && existing.length > 0) return existing;
  const account = accounts.value.find((a) => a.id === accountId);
  const first: TabState = { id: String(++tabSeq), title: 'Home' };
  void account;
  tabsByAccount.value.set(accountId, [first]);
  activeTabByAccount.value.set(accountId, first.id);
  tabsByAccount.value = new Map(tabsByAccount.value);
  return [first];
}

async function switchTab(tabId: string): Promise<void> {
  if (active.value === null) return;
  activeTabByAccount.value.set(active.value.id, tabId);
  activeTabByAccount.value = new Map(activeTabByAccount.value);
  await presentActive();
}

async function newTab(): Promise<void> {
  if (active.value === null) return;
  const list = ensureTabs(active.value.id);
  if (list.length >= MAX_TABS) return; // cap 10 — the + button also disables
  const tab: TabState = { id: String(++tabSeq), title: 'Home' };
  list.push(tab);
  tabsByAccount.value = new Map(tabsByAccount.value);
  await switchTab(tab.id);
}

async function closeTab(tabId: string): Promise<void> {
  if (active.value === null) return;
  const list = ensureTabs(active.value.id);
  if (list.length <= 1) return; // min 1 — the × hides on the last tab
  const idx = list.findIndex((t) => t.id === tabId);
  if (idx === -1) return;
  list.splice(idx, 1);
  tabsByAccount.value = new Map(tabsByAccount.value);
  await VanceAccountWebView.closeTab({ accountId: active.value.id, tabId });
  if (activeTabId.value === tabId) {
    const next = list[Math.min(idx, list.length - 1)];
    await switchTab(next.id);
  }
}

// Pack context (planning/desktop-pack-home-per-project.md §4): the last
// reported project and the effective pack home, per account — the tooltip
// shows whichever account is on screen.
const packsProjects = ref(new Map<string, { project: string | null; tenant: string | null; home: string | null }>());
let toolsActivityUnsub: (() => void) | null = null;
let packsProjectUnsub: (() => void) | null = null;
let tabUrlUnsub: (() => void) | null = null;

const toolsTitle = computed(() => {
  const context = activeId.value !== null ? packsProjects.value.get(activeId.value) : undefined;
  if (toolsActive.value) return 'Agent is working on your machine…';
  const state = toolsEnabled.value
    ? 'Agent tools: on (click to disable)'
    : 'Agent tools: off (click to enable)';
  if (context === undefined) return state;
  const project = context.project !== null ? context.project : '—';
  const home = context.home !== null ? context.home : '—';
  return `${state} · Project: ${project} · Pack home: ${home}`;
});

async function refreshToolsEnabled(): Promise<void> {
  if (!isDesktopApp || activeId.value === null) {
    toolsEnabled.value = false;
    return;
  }
  toolsEnabled.value =
    (await window.faceliftDesktop?.toolsEnabledGet({
      accountId: activeId.value,
    })) ?? false;
}

async function toggleTools(): Promise<void> {
  if (!isDesktopApp || activeId.value === null) return;
  const next = !toolsEnabled.value;
  await window.faceliftDesktop?.toolsEnabledSet({
    accountId: activeId.value,
    enabled: next,
  });
  toolsEnabled.value = next;
}

watch(activeId, () => {
  void refreshToolsEnabled();
});

let resizeObserver: ResizeObserver | null = null;
let urlOpenListener: PluginListenerHandle | null = null;

onMounted(async () => {
  await refresh();
  // Let Vue flush the post-refresh render so the header reflects the
  // active-account state (account-name button + reload icon visible)
  // *before* we measure offsetHeight. Then wait one animation frame
  // so iOS gets a chance to resolve `env(safe-area-inset-top)` before
  // measurement — cold-start without the rAF can hand back 0 for the
  // safe-area inset, undercounting the header height.
  await nextTick();
  await waitForFrame();
  installLayoutListeners();
  urlOpenListener = await VanceAccountWebView.addListener('urlOpen', (event) => {
    void handleFaceliftUrl(event.url);
  });
  await presentActive();
  // Agent-tools activity: the main process pushes zero-crossings per
  // account; we only render the state of the account on screen.
  if (isDesktopApp) {
    toolsActivityUnsub = window.faceliftDesktop?.onToolsActivity((event) => {
      if (event.accountId === activeId.value) {
        toolsActive.value = event.active;
      }
    }) ?? null;
    tabUrlUnsub = window.faceliftDesktop?.onTabUrl((event) => {
      const list = tabsByAccount.value.get(event.accountId);
      const tab = list?.find((t) => t.id === event.tabId);
      if (tab !== undefined) {
        tab.title = titleFromUrl(event.url);
        tabsByAccount.value = new Map(tabsByAccount.value);
      }
    }) ?? null;
    packsProjectUnsub = window.faceliftDesktop?.onPacksProject((event) => {
      packsProjects.value.set(event.accountId, {
        project: event.project,
        tenant: event.tenant,
        home: event.home,
      });
      packsProjects.value = new Map(packsProjects.value);
    }) ?? null;
    await refreshToolsEnabled();
  }
});

onBeforeUnmount(async () => {
  uninstallLayoutListeners();
  if (urlOpenListener !== null) {
    await urlOpenListener.remove();
    urlOpenListener = null;
  }
  toolsActivityUnsub?.();
  toolsActivityUnsub = null;
  packsProjectUnsub?.();
  packsProjectUnsub = null;
  tabUrlUnsub?.();
  tabUrlUnsub = null;
  await VanceAccountWebView.dismiss();
});

/**
 * Handle a `vance-facelift://*` URL emitted by the active account's
 * WebView. The website navigates to these URLs to ask the wrapper to
 * perform an action; the Swift plugin cancels the underlying
 * navigation and forwards the URL here. The contract is documented in
 * `facelift-bridge/README.md`.
 */
async function handleFaceliftUrl(rawUrl: string): Promise<void> {
  let parsed: URL;
  try {
    parsed = new URL(rawUrl);
  } catch {
    console.warn('[facelift] ignored malformed url', rawUrl);
    return;
  }
  if (parsed.protocol !== 'vance-facelift:') return;
  // `vance-facelift://back-to-picker` parses with host = "back-to-picker";
  // when the website omits the host (e.g. `vance-facelift:back-to-picker`)
  // the action lands in pathname instead — accept both shapes.
  const action = parsed.host || parsed.pathname.replace(/^\//, '');
  switch (action) {
    case 'back-to-picker':
      await VanceAccountWebView.dismiss();
      void router.push({ name: 'manage' });
      break;
    case 'add-account':
      await VanceAccountWebView.dismiss();
      void router.push({ name: 'add' });
      break;
    case 'switch-account':
      showSwitcher.value = true;
      break;
    default:
      console.warn('[facelift] unknown action', action, 'from', rawUrl);
  }
}

watch(active, async (next, prev) => {
  if (next?.id === prev?.id) return;
  await nextTick();
  if (next === null) {
    await VanceAccountWebView.dismiss();
  } else {
    await presentActive();
  }
});

// The bottom-sheet lives in the Vue WebView which sits *behind* the
// native account WebView in UIKit z-order. To let the sheet render on
// top, hide the native WebView while the sheet is open. The cached
// WebView keeps its in-page state — closing the sheet just unhides it
// (no reload, no flash beyond the iOS view transition).
watch(showSwitcher, async (open) => {
  if (open) {
    await VanceAccountWebView.dismiss();
  } else if (active.value !== null) {
    await presentActive();
  }
});

async function refresh(): Promise<void> {
  accounts.value = await listAccounts();
  activeId.value = await getActiveAccountId();
  // Recover from a dangling active pointer (account was removed
  // between sessions) — pick the most-recently-used as a fallback.
  if (activeId.value !== null && (await getAccount(activeId.value)) === null) {
    const fallback = [...accounts.value].sort((a, b) => b.lastUsedAt - a.lastUsedAt)[0];
    activeId.value = fallback?.id ?? null;
    await setActiveAccountId(activeId.value);
  }
}

function currentBounds(): AccountWebViewBounds {
  const measured = headerEl.value?.offsetHeight ?? 0;
  // Defensive floor: at cold start `env(safe-area-inset-top)` can
  // briefly resolve to 0 before iOS settles the WebView's safe-area
  // insets, which would make `offsetHeight` smaller than the header
  // visually occupies once env() kicks in. 110pt is the worst-case
  // total (iPhone 17 Pro safe-area-top ~62pt + header content + a
  // few px of slack). The CSS `min-height: 110px` on the header and
  // an env() fallback of 60px keep the visual header tall enough
  // even on the first frame; this floor is the JS-side belt-and-
  // braces for the value we pass to the native plugin.
  const headerH = Math.max(measured, 110);
  return {
    top: headerH,
    left: 0,
    width: window.innerWidth,
    height: Math.max(0, window.innerHeight - headerH),
  };
}

function waitForFrame(): Promise<void> {
  return new Promise((resolve) => requestAnimationFrame(() => resolve()));
}

async function presentActive(): Promise<void> {
  if (active.value === null) return;
  const list = ensureTabs(active.value.id);
  const tabId = activeTabByAccount.value.get(active.value.id) ?? list[0].id;
  await VanceAccountWebView.present({
    accountId: active.value.id,
    url: active.value.faceUrl,
    tabId,
    ...currentBounds(),
  });
}

function pushBounds(): void {
  if (active.value === null) return;
  void VanceAccountWebView.setBounds(currentBounds());
}

function installLayoutListeners(): void {
  window.addEventListener('resize', pushBounds);
  if (headerEl.value !== null && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => pushBounds());
    resizeObserver.observe(headerEl.value);
  }
}

function uninstallLayoutListeners(): void {
  window.removeEventListener('resize', pushBounds);
  resizeObserver?.disconnect();
  resizeObserver = null;
}

async function onSelect(id: string): Promise<void> {
  showSwitcher.value = false;
  if (id === activeId.value) return;
  await setActiveAccountId(id);
  await refresh();
}

async function onReload(): Promise<void> {
  await VanceAccountWebView.reload();
}

async function onHome(): Promise<void> {
  if (active.value === null) return;
  await VanceAccountWebView.navigateHome({
    accountId: active.value.id,
    url: active.value.faceUrl,
  });
}

async function goManage(): Promise<void> {
  showSwitcher.value = false;
  await VanceAccountWebView.dismiss();
  void router.push({ name: 'manage' });
}

async function goAdd(): Promise<void> {
  showSwitcher.value = false;
  await VanceAccountWebView.dismiss();
  void router.push({ name: 'add' });
}
</script>

<template>
  <div class="flex h-full flex-col">
    <header
      ref="headerEl"
      class="flex shrink-0 flex-wrap items-center gap-x-2 gap-y-1 border-b border-gray-800 bg-gray-900 px-3 text-white"
      style="padding-top: env(safe-area-inset-top, 60px); padding-bottom: 0.5rem; min-height: 110px"
    >
      <button
        v-if="active !== null"
        type="button"
        class="flex min-w-0 flex-1 items-center gap-1 py-2 text-left"
        @click="showSwitcher = true"
      >
        <span class="truncate text-sm font-medium">{{ active.displayName }}</span>
        <span class="text-xs text-gray-400">v</span>
      </button>
      <span v-else class="flex-1 py-2 text-sm text-gray-400">Vancetope</span>
      <!-- Agent-tools release button — dual-purpose: background = on/off,
           icon = activity (red + pulsing while an invoke is in flight).
           Desktop only; acts on the active account. -->
      <button
        v-if="isDesktopApp && active !== null"
        type="button"
        class="flex h-9 w-9 items-center justify-center rounded"
        :class="toolsEnabled ? 'bg-blue-600' : 'bg-gray-800'"
        :title="toolsTitle"
        :aria-label="toolsTitle"
        @click="toggleTools"
      >
        <svg
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
          class="h-5 w-5"
          :class="toolsActive ? 'animate-pulse text-red-400' : toolsEnabled ? 'text-white' : 'text-gray-500'"
        >
          <polyline points="4 17 10 11 4 5" />
          <line x1="12" y1="19" x2="20" y2="19" />
        </svg>
      </button>
      <button
        v-if="active !== null"
        type="button"
        class="flex h-9 w-9 items-center justify-center rounded bg-gray-800 text-gray-200"
        title="Home"
        aria-label="Home"
        @click="onHome"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
             stroke-linecap="round" stroke-linejoin="round" class="h-5 w-5">
          <path d="M3 12L12 3l9 9" />
          <path d="M5 10v10a1 1 0 0 0 1 1h4v-6h4v6h4a1 1 0 0 0 1-1V10" />
        </svg>
      </button>
      <button
        v-if="active !== null"
        type="button"
        class="flex h-9 w-9 items-center justify-center rounded bg-gray-800 text-gray-200"
        title="Reload"
        aria-label="Reload"
        @click="onReload"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
             stroke-linecap="round" stroke-linejoin="round" class="h-5 w-5">
          <path d="M21 12a9 9 0 1 1-2.64-6.36" />
          <path d="M21 3v6h-6" />
        </svg>
      </button>
      <button
        type="button"
        class="flex h-9 w-9 items-center justify-center rounded bg-gray-800 text-gray-200"
        title="Manage accounts"
        aria-label="Manage accounts"
        @click="goManage"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
             stroke-linecap="round" stroke-linejoin="round" class="h-5 w-5">
          <circle cx="12" cy="12" r="3" />
          <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h0a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h0a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v0a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
        </svg>
      </button>

      <!-- Live tabs (planning/desktop-profile-tabs.md): one WebView per
           tab, max 10, RAM-only. The header grows → bounds follow via the
           ResizeObserver. Same markup ships to iOS (stage 2 wires the
           plugin side). -->
      <div
        v-if="active !== null && tabs.length > 0"
        class="flex w-full items-end gap-1 overflow-x-auto px-1 pb-1"
      >
        <button
          v-for="tab in tabs"
          :key="tab.id"
          type="button"
          class="group flex max-w-44 shrink-0 items-center gap-1 rounded-t px-2 py-1 text-xs"
          :class="
            tab.id === activeTabId
              ? 'bg-gray-800 text-white'
              : 'bg-gray-950 text-gray-400 hover:text-gray-200'
          "
          :title="tab.title"
          @click="switchTab(tab.id)"
        >
          <span class="truncate">{{ tab.title }}</span>
          <span
            v-if="tabs.length > 1"
            class="ml-1 hidden rounded px-0.5 text-[10px] text-gray-500 hover:bg-gray-700 hover:text-red-400 group-hover:block"
            @click.stop="closeTab(tab.id)"
          >
            ✕
          </span>
        </button>
        <button
          v-if="tabs.length < MAX_TABS"
          type="button"
          class="shrink-0 rounded bg-gray-800 px-2 py-1 text-xs text-gray-400 hover:text-white"
          title="New tab"
          aria-label="New tab"
          @click="newTab"
        >
          +
        </button>
      </div>
    </header>

    <main class="relative flex-1 overflow-hidden">
      <!-- Empty-state for "no accounts yet". When an account is
           active, this area is visually covered by the native
           WKWebView owned by VanceAccountWebView; we don't render
           any HTML here in that case. -->
      <div
        v-if="active === null"
        class="flex h-full flex-col items-center justify-center gap-4 px-6 text-center"
      >
        <p class="text-lg font-medium">No account yet</p>
        <p class="text-sm text-gray-400">
          Vancetope connects to a Vancetope server you or your
          organisation runs. Add its web address to get started.
        </p>
        <button
          type="button"
          class="rounded bg-blue-500 px-4 py-2 font-medium text-white"
          @click="goAdd"
        >
          Add account
        </button>
      </div>
    </main>

    <AccountSwitcherSheet
      v-if="showSwitcher"
      :accounts="accounts"
      :active-id="activeId"
      @select="onSelect"
      @manage="goManage"
      @add="goAdd"
      @close="showSwitcher = false"
    />
  </div>
</template>
