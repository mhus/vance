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
});

onBeforeUnmount(async () => {
  uninstallLayoutListeners();
  if (urlOpenListener !== null) {
    await urlOpenListener.remove();
    urlOpenListener = null;
  }
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
  await VanceAccountWebView.present({
    accountId: active.value.id,
    url: active.value.faceUrl,
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
      class="flex shrink-0 items-center gap-2 border-b border-gray-800 bg-gray-900 px-3 text-white"
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
      <span v-else class="flex-1 py-2 text-sm text-gray-400">Vance</span>
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
          Add a Brain server URL to start using Vance on this device.
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
