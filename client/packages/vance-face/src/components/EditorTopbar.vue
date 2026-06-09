<script setup lang="ts">
import { computed, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { getTenantId, getUsername } from '@vance/shared';
import {
  logout as serverLogout,
  setActiveLanguage,
} from '@/platform';
import { setUiLocale } from '@/i18n';
import FookSupportModal from './FookSupportModal.vue';

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
   * Connection-state dot. Only WS editors set this; REST-only editors
   * omit the prop and the dot is hidden. See `specification/web-ui.md`
   * §6.4 for the state semantics.
   */
  connectionState?: 'connected' | 'idle' | 'occupied';
  /** Optional tooltip override; defaults to a sensible per-state label. */
  connectionTooltip?: string;
  /**
   * When set, the topbar renders a "?" help toggle. The drawer itself
   * is rendered by the parent {@code <EditorShell>} (it reclaims the
   * right-panel area). The topbar only owns the button; the open-state
   * is two-way-bound via {@link helpOpen} + the {@code toggle-help}
   * emit.
   */
  helpPath?: string;
  /** Reflects whether the help drawer is currently open. */
  helpOpen?: boolean;
  /**
   * When true, the page title renders as a clickable element that
   * emits {@code title-click} on activation. Used by editors with a
   * sidebar zone to let users jump back to the navigation. Visually
   * the title gets a pointer cursor + subtle hover effect.
   */
  titleClickable?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  breadcrumbs: () => [],
  helpOpen: false,
  titleClickable: false,
});

const emit = defineEmits<{
  (e: 'toggle-help'): void;
  (e: 'title-click'): void;
}>();

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

function crumbText(c: Crumb): string {
  return typeof c === 'string' ? c : c.text;
}
function crumbOnClick(c: Crumb): (() => void) | null {
  return typeof c === 'string' ? null : (c.onClick ?? null);
}

/**
 * Quick language switcher in the user-menu — flips the active locale
 * for this tab without a server round-trip. Persistence-on-server
 * stays in the profile page; this is the "I want to read this page in
 * the other language right now" shortcut.
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

function onToggleHelp(): void {
  if (!props.helpPath) return;
  emit('toggle-help');
}

// Fook bug/feature submission dialog — reachable from the user
// menu on every editor page. The modal owns its own state, we just
// flip the boolean.
const fookOpen = ref<boolean>(false);
function openFook(): void {
  fookOpen.value = true;
}
</script>

<template>
  <header class="navbar bg-base-100 shadow-sm border-b border-base-300 px-4 gap-2">
    <!-- Logo doubles as a "home" link back to the editor list. -->
    <a
      href="/index.html"
      class="flex-none font-bold text-lg font-mono no-underline hover:opacity-80"
      :title="$t('common.backToHome')"
    >vance</a>

    <div class="flex-1 flex items-center gap-2 text-sm">
      <button
        v-if="titleClickable"
        type="button"
        class="title-link font-semibold"
        @pointerdown.stop
        @click="emit('title-click')"
      >{{ title }}</button>
      <span v-else class="font-semibold">{{ title }}</span>
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
           a helpPath; the drawer itself is rendered by EditorShell. -->
      <button
        v-if="helpPath"
        type="button"
        class="btn btn-ghost btn-sm btn-circle"
        :class="helpOpen ? 'btn-active' : ''"
        :title="$t('header.help.toggle')"
        :aria-pressed="helpOpen"
        @click="onToggleHelp"
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
          <li><a @click="openFook">{{ $t('fook.menuLabel') }}</a></li>
          <li><a href="/profile.html">{{ $t('common.profile') }}</a></li>
          <li><a @click="logout">{{ $t('common.signOut') }}</a></li>
        </ul>
      </div>
    </div>
  </header>

  <FookSupportModal v-model="fookOpen" />
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
.title-link {
  background: transparent;
  border: none;
  padding: 0;
  cursor: pointer;
  color: inherit;
  font-size: inherit;
}
.title-link:hover {
  text-decoration: underline;
}
</style>
