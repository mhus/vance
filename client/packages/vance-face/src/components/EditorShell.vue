<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { getTenantId, getUsername } from '@vance/shared';
import {
  getSessionData,
  isAccessAlive,
  isRefreshAlive,
  logout as serverLogout,
  refreshAccessCookie,
  setActiveLanguage,
} from '@/platform';
import { setUiLocale } from '@/i18n';
import { useHelp } from '@/composables/useHelp';
import MarkdownView from './MarkdownView.vue';

/**
 * A breadcrumb segment. Either a plain string label (immutable, no
 * navigation) or an object with an {@code onClick} handler that turns
 * the segment into a button — used to navigate back to a parent view
 * (e.g. from a process detail back to the owning session).
 */
export type Crumb = string | { text: string; onClick?: () => void };

interface Props {
  /** Page title shown in the topbar. */
  title: string;
  /** Breadcrumb segments left-to-right (e.g. `['Project foo', 'Session bar']`). */
  breadcrumbs?: Crumb[];
  /**
   * Connection-state dot in the topbar. Only WS editors set this; REST-only
   * editors omit the prop and the dot is hidden.
   *
   * Three states (see `specification/web-ui.md` §6.4):
   *  - `connected` (green) — WS bound to a session, live stream active
   *  - `idle`      (grey)  — picker mode, or transient reconnect
   *  - `occupied`  (red)   — last bind attempt was rejected with 409
   *                          (another connection holds the session lock)
   */
  connectionState?: 'connected' | 'idle' | 'occupied';
  /** Optional tooltip override; defaults to a sensible per-state label. */
  connectionTooltip?: string;
  /**
   * Doubles the default width of the right panel (320px → 640px). Use for
   * editors whose right panel hosts forms (e.g. settings editor).
   */
  wideRightPanel?: boolean;
  /**
   * App-style layout: the main slot becomes a fixed-height frame
   * (`overflow-hidden`) instead of the default page-scroll
   * (`overflow-y-auto`). Editors that own internal scroll regions —
   * chat with its message list + progress feed, future canvas
   * editors — opt into this so the page itself never scrolls.
   */
  fullHeight?: boolean;
  /**
   * Help resource path under {@code /brain/{tenant}/help/{lang}/}. When
   * set, EditorShell renders a "?" toggle in the topbar; clicking it
   * loads the markdown via {@link useHelp} and slides a help drawer
   * over the right-panel area. The drawer is closed by default so
   * editors that already render their own help in {@code #right-panel}
   * keep working unchanged — they just don't pass {@code helpPath}.
   *
   * <p>The drawer reclaims the right-panel space rather than adding a
   * fourth column: on smaller windows the cost of always-on help is
   * higher than the cost of one toggle click.
   */
  helpPath?: string;
  /**
   * Initial open-state for the help drawer when {@link helpPath} is
   * set. Default {@code false} — user opts in via the topbar toggle.
   * Editors that want the drawer pre-opened (e.g. the first time the
   * user visits) can flip this and persist the preference themselves.
   */
  helpOpen?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  breadcrumbs: () => [],
  wideRightPanel: false,
  fullHeight: false,
  helpOpen: false,
});

const showHelp = ref<boolean>(false);
const help = useHelp();

watch(
  () => props.helpPath,
  (path) => {
    showHelp.value = props.helpOpen && !!path;
    if (path && showHelp.value && help.content.value == null) {
      void help.load(path);
    }
  },
  { immediate: true },
);

function toggleHelp(): void {
  if (!props.helpPath) return;
  showHelp.value = !showHelp.value;
  if (showHelp.value && help.content.value == null && !help.loading.value) {
    void help.load(props.helpPath);
  }
}

function crumbText(c: Crumb): string {
  return typeof c === 'string' ? c : c.text;
}
function crumbOnClick(c: Crumb): (() => void) | null {
  return typeof c === 'string' ? null : (c.onClick ?? null);
}

const { t, locale } = useI18n();

const tenantId = computed<string | null>(() => getTenantId());
const username = computed<string | null>(() => getUsername());

const defaultConnectionTooltip = computed<string>(() => {
  switch (props.connectionState) {
    case 'connected': return t('header.connection.connected');
    case 'occupied':  return t('header.connection.occupied');
    case 'idle':      return t('header.connection.idle');
    default:          return '';
  }
});

/**
 * Quick language switcher in the user-menu — flips the active locale
 * for this tab without a server round-trip. Persistence-on-server
 * stays in the profile page; this is the "I want to read this page in
 * the other language right now" shortcut.
 *
 * Mirrors the value into sessionStorage via {@link setActiveLanguage}
 * so other components that read {@code getActiveLanguage} pick it up.
 */
interface LanguageOption {
  code: 'en' | 'de';
  label: string;
}
const LANGUAGES: readonly LanguageOption[] = [
  { code: 'en', label: 'English' },
  { code: 'de', label: 'Deutsch' },
];

const currentLocale = computed<string>(() => String(locale.value));

function selectLanguage(code: LanguageOption['code']): void {
  setActiveLanguage(code);
  setUiLocale(code);
}

async function logout(): Promise<void> {
  const tenant = getTenantId();
  if (tenant) {
    await serverLogout(tenant);
  }
  window.location.href = '/index.html';
}

/**
 * Per-page-load access-cookie check. The shell is rendered on every
 * editor (apart from the login page itself), so guarding here is
 * equivalent to "guard on every page load".
 *
 * <p>If the access cookie has expired we try a silent refresh via the
 * still-alive refresh cookie. On failure we redirect to the login
 * page with the current URL as the {@code next} parameter so the user
 * comes back to where they were after re-authenticating.
 *
 * <p>A timer keeps polling every 60 seconds — long-running editor
 * sessions (chat tab left open over the lunch break) get the same
 * guard mid-session, not only on initial mount.
 */
let expiryTimer: number | null = null;

async function guardAccessCookie(): Promise<void> {
  if (isAccessAlive()) return;
  if (getSessionData() && isRefreshAlive()) {
    const ok = await refreshAccessCookie();
    if (ok && isAccessAlive()) return;
  }
  redirectToLogin();
}

function redirectToLogin(): void {
  const currentUrl = window.location.pathname + window.location.search + window.location.hash;
  const next = encodeURIComponent(currentUrl);
  window.location.href = `/index.html?next=${next}`;
}

onMounted(() => {
  void guardAccessCookie();
  expiryTimer = window.setInterval(() => {
    void guardAccessCookie();
  }, 60_000);
});

onBeforeUnmount(() => {
  if (expiryTimer != null) {
    window.clearInterval(expiryTimer);
    expiryTimer = null;
  }
});
</script>

<template>
  <div class="h-screen overflow-hidden flex flex-col bg-base-200">
    <header class="navbar bg-base-100 shadow-sm border-b border-base-300 px-4 gap-2">
      <!-- Logo doubles as a "home" link back to the editor list. -->
      <a
        href="/index.html"
        class="flex-none font-bold text-lg font-mono no-underline hover:opacity-80"
        :title="$t('common.backToHome')"
      >vance</a>

      <div class="flex-1 flex items-center gap-2 text-sm">
        <span class="font-semibold">{{ title }}</span>
        <span v-if="breadcrumbs.length" class="opacity-50">·</span>
        <span class="opacity-70 truncate">
          <template v-for="(crumb, idx) in breadcrumbs" :key="idx">
            <button
              v-if="crumbOnClick(crumb)"
              type="button"
              class="crumb-link"
              @click="crumbOnClick(crumb)?.()"
            >{{ crumbText(crumb) }}</button>
            <span v-else>{{ crumbText(crumb) }}</span>
            <span v-if="idx < breadcrumbs.length - 1" class="mx-1 opacity-40">›</span>
          </template>
        </span>
      </div>

      <div class="flex-none flex items-center gap-3">
        <!-- Editor-specific topbar slot — e.g. the project selector in the
             document editor. Sits between the breadcrumbs and the
             connection/user controls. Keep it compact: a single dropdown
             or short button row. -->
        <div v-if="$slots['topbar-extra']" class="flex items-center">
          <slot name="topbar-extra" />
        </div>

        <span
          v-if="connectionState"
          :class="[
            'inline-block w-2.5 h-2.5 rounded-full',
            connectionState === 'connected' ? 'bg-success' : '',
            connectionState === 'idle' ? 'bg-base-content/40' : '',
            connectionState === 'occupied' ? 'bg-error' : '',
          ]"
          :title="connectionTooltip ?? defaultConnectionTooltip"
        />

        <!-- Help-drawer toggle. Only rendered when the editor supplied
             a helpPath; closed by default, reclaims the right-panel
             space when opened. -->
        <button
          v-if="helpPath"
          type="button"
          class="btn btn-ghost btn-sm btn-circle"
          :class="showHelp ? 'btn-active' : ''"
          :title="$t('header.help.toggle')"
          :aria-pressed="showHelp"
          @click="toggleHelp"
        >?</button>

        <div class="dropdown dropdown-end">
          <div tabindex="0" role="button" class="btn btn-ghost btn-sm">
            <span class="font-mono text-xs opacity-70">{{ tenantId }}</span>
            <span>·</span>
            <span>{{ username }}</span>
          </div>
          <ul
            tabindex="0"
            class="dropdown-content menu bg-base-100 rounded-box z-[1] mt-2 w-48 p-2 shadow"
          >
            <li class="menu-title">
              <span>{{ $t('header.menu.languageHeader') }}</span>
            </li>
            <li v-for="lang in LANGUAGES" :key="lang.code">
              <a
                :class="{ active: currentLocale === lang.code }"
                @click="selectLanguage(lang.code)"
              >
                <span class="font-mono text-xs opacity-50 w-6">{{ lang.code.toUpperCase() }}</span>
                <span>{{ lang.label }}</span>
              </a>
            </li>
            <li class="divider-row"><div class="divider my-1" /></li>
            <li><a href="/profile.html">{{ $t('common.profile') }}</a></li>
            <li><a @click="logout">{{ $t('common.signOut') }}</a></li>
          </ul>
        </div>
      </div>
    </header>

    <div class="flex-1 flex min-h-0">
      <aside
        v-if="$slots.sidebar"
        class="w-64 shrink-0 border-r border-base-300 bg-base-100 overflow-y-auto"
      >
        <slot name="sidebar" />
      </aside>

      <main :class="['flex-1 min-w-0 min-h-0', fullHeight ? 'overflow-hidden' : 'overflow-y-auto']">
        <slot />
      </main>

      <aside
        v-if="showHelp || $slots['right-panel']"
        :class="[
          'shrink-0 border-l border-base-300 bg-base-100 overflow-y-auto',
          wideRightPanel ? 'w-[40rem]' : 'w-80',
        ]"
      >
        <!-- Help drawer reclaims the right-panel space; the editor's
             own right-panel content is hidden while help is shown. -->
        <div v-if="showHelp" class="p-4 flex flex-col gap-3 h-full">
          <div class="flex items-center justify-between">
            <h3 class="text-xs uppercase opacity-60">
              {{ $t('header.help.title') }}
            </h3>
            <button
              type="button"
              class="btn btn-ghost btn-xs btn-circle"
              :title="$t('header.help.close')"
              @click="toggleHelp"
            >✕</button>
          </div>
          <div v-if="help.loading.value" class="text-xs opacity-60">
            {{ $t('header.help.loading') }}
          </div>
          <div v-else-if="help.error.value" class="text-xs opacity-60">
            {{ $t('header.help.unavailable', { error: help.error.value }) }}
          </div>
          <div v-else-if="!help.content.value" class="text-xs opacity-60">
            {{ $t('header.help.empty') }}
          </div>
          <div v-else class="overflow-y-auto pr-2">
            <MarkdownView :source="help.content.value" />
          </div>
        </div>
        <slot v-else name="right-panel" />
      </aside>
    </div>
  </div>
</template>

<style scoped>
.crumb-link {
  background: transparent;
  border: none;
  padding: 0;
  cursor: pointer;
  color: inherit;
  font-size: inherit;
}
.crumb-link:hover {
  text-decoration: underline;
  opacity: 1;
}
</style>
