<script setup lang="ts">
import { computed, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  getTenantId,
  getUsername,
  isFacelift,
  requestAddAccount,
  requestBackToPicker,
  requestSwitchAccount,
} from '@vance/shared';
import {
  logout as serverLogout,
} from '@/platform';
import { editorHref } from '@/platform/editorHref';
import { SOURCE_CODE_URL } from '@/platform/sourceCode';
import { VIcon, VIconButton, type IconName } from '@vance/components';
import { setUiLocale, UI_LOCALES, type Locale } from '@/i18n';
import InboxBadge from './InboxBadge.vue';
import ProcessCountsBadge from './ProcessCountsBadge.vue';
import WorkingProjectBadge from './WorkingProjectBadge.vue';
import FookSupportModal from './FookSupportModal.vue';
import TenantLogo from './TenantLogo.vue';
import { loadRuntimeConfig, type RuntimeConfig } from '@/platform/runtimeConfig';
import { handleShellLinkClick } from '@/platform/navigate';

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
   * The editor's own right-panel toggle, rendered as the segment left
   * of the "?" so the two form one control. Both switch what the right
   * panel shows, so they belong together rather than sitting at
   * opposite ends of the topbar; the editor keeps ownership of what its
   * segment means (a session picker, an outline, …) and of making the
   * two mutually exclusive. Omit it and the "?" stands alone.
   */
  panelToggle?: { icon: IconName; title: string; active: boolean };
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
  (e: 'toggle-panel'): void;
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
const LANGUAGES = UI_LOCALES;

const currentLocale = computed<string>(() => String(locale.value));
const currentLanguageLabel = computed<string>(
  () => LANGUAGES.find((lang) => lang.code === currentLocale.value)?.label ?? currentLocale.value,
);
const languageMenu = ref<HTMLDetailsElement | null>(null);

function selectLanguage(code: Locale): void {
  if (languageMenu.value) languageMenu.value.open = false;
  // Transient switch for the current page only. The user's persistent
  // default lives on the profile page; switching here reverts on the
  // next page reload because the data cookie is the source of truth.
  void setUiLocale(code);
}

async function logout(): Promise<void> {
  const tenant = getTenantId();
  if (tenant) {
    await serverLogout(tenant);
  }
  // Deliberately NOT navigateTo: a route change would leave the shell mounted
  // with a session that no longer exists, and the landing would render into a
  // wall of 401s. A real page load is the point of logging out — the boot
  // guard in index.html sees no cookie and sends the reader to the door.
  window.location.href = '/';
}

/** The logo link — a route inside the shell, a page load everywhere else. */
function onHomeClick(event: MouseEvent): void {
  handleShellLinkClick(event, '/');
}

// Facelift wrapper bridges — these are no-ops in a plain browser
// (see @vance/shared/facelift). The menu only renders these entries
// when `isFacelift()` is true.
const inFacelift = computed<boolean>(() => isFacelift());

// Server-declared title / backlink from the pod-written
// /config.json. Both are optional; the title is rendered next to the
// "vance" wordmark when set, the backlink as a small link below it.
const runtimeCfg = ref<RuntimeConfig | null>(null);
const serverTitle = computed<string>(() => runtimeCfg.value?.title?.trim() ?? '');
const serverBacklink = computed<string>(() => runtimeCfg.value?.backlink?.trim() ?? '');
void loadRuntimeConfig().then((cfg) => { runtimeCfg.value = cfg; });

function onSwitchAccount(): void {
  requestSwitchAccount();
}

function onManageAccounts(): void {
  requestBackToPicker();
}

function onAddAccount(): void {
  requestAddAccount();
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
  <header class="v-topbar">
    <!-- Logo doubles as a "home" link back to the editor list.
         Mark (mathematical italic v) + wordmark; the mark carries the
         brand on small viewports where horizontal room is tight. -->
    <!-- Stays a real anchor on purpose: middle-click, cmd-click and "copy
         link" all keep working, and on the standalone entries (profile,
         scopes, an addon area) there is no router to route with. Inside the
         shell the click handler takes over and the landing becomes a route
         change instead of a page load. -->
    <a
      href="/"
      class="flex-none flex items-center gap-1.5 no-underline hover:opacity-80"
      :title="$t('common.backToHome')"
      @click="onHomeClick"
    >
      <TenantLogo />
      <span class="v-wordmark">vancetope</span>
    </a>
    <!-- Server identity from the pod-written /config.json. The title
         renders as a clickable link to the backlink URL when both are
         set; otherwise it falls back to plain text (title-only) or a
         link with the URL as its label (backlink-only). Vanilla
         deployments with neither set keep the minimal wordmark. -->
    <a
      v-if="serverTitle && serverBacklink"
      :href="serverBacklink"
      class="flex-none link link-hover text-sm font-medium opacity-70"
      @click.stop
    >{{ serverTitle }}</a>
    <span
      v-else-if="serverTitle"
      class="flex-none text-sm font-medium opacity-70"
    >{{ serverTitle }}</span>
    <a
      v-else-if="serverBacklink"
      :href="serverBacklink"
      class="flex-none link link-hover text-xs opacity-50"
      @click.stop
    >{{ serverBacklink }}</a>

    <!-- Single-line, ellipsizing region: {@code min-w-0} lets this flex
         child shrink below its content and {@code truncate} clips the
         overflow with an ellipsis, so a long document/session title can
         never wrap the topbar onto a second row. Children are inline so
         title + breadcrumbs flow on one line and clip at the end. -->
    <div class="flex-1 min-w-0 truncate text-sm">
      <button
        v-if="titleClickable"
        type="button"
        class="title-link font-semibold align-middle"
        @pointerdown.stop
        @click="emit('title-click')"
      >{{ title }}</button>
      <span v-else class="font-semibold align-middle">{{ title }}</span>
      <VIcon v-if="breadcrumbs.length" name="forward" class="v-crumb-sep" />
      <span class="opacity-70 align-middle">
        <template v-for="(crumb, idx) in breadcrumbs" :key="idx">
          <!-- pointerdown.stop like the title: the shell resets the focus
               zone on any pointerdown outside the editor grid, which would
               undo a crumb handler that focuses a zone. -->
          <button
            v-if="crumbOnClick(crumb)"
            type="button"
            class="crumb-link"
            @pointerdown.stop
            @click="crumbOnClick(crumb)?.()"
          >{{ crumbText(crumb) }}</button>
          <span v-else>{{ crumbText(crumb) }}</span>
          <VIcon v-if="idx < breadcrumbs.length - 1" name="forward" class="v-crumb-sep" />
        </template>
      </span>
    </div>

    <div class="flex-none flex items-center gap-2">
      <!-- Pending-inbox badge — self-hiding at zero, present on every
           editor page so a waiting question is visible without opening
           the inbox. REST-pulled on mount + tab-refocus. -->
      <InboxBadge />

      <!-- Session process badge — self-hiding when nothing runs, so it
           costs no space in the common case. Fed by the process-counts
           push (see planning/process-visibility.md §4.A). -->
      <ProcessCountsBadge />

      <!-- Working-project badge — which project the hub's chat-process
           currently coordinates (Eddie's spot). Self-hiding when no spot
           is set. Fed by the working-project-changed push. -->
      <WorkingProjectBadge />

      <!-- Editor-specific topbar slot — e.g. the project selector in the
           document editor. Sits between the breadcrumbs and the
           connection/user controls. Keep it compact: a single dropdown
           or short button row. -->
      <div v-if="$slots['topbar-extra']" class="flex items-center">
        <slot name="topbar-extra" />
      </div>

      <span
        v-if="connectionState"
        :class="['v-status-dot', `v-status-${connectionState}`]"
        :title="connectionTooltip ?? defaultConnectionTooltip"
      />

      <!-- Right-panel switcher: the editor's own toggle and the help
           toggle as one segmented control, because both decide what the
           right panel shows. Either segment may be absent — with only
           one, the group reads as a single button. The drawer itself is
           rendered by EditorShell. -->
      <VIconButton
        v-if="panelToggle"
        :icon="panelToggle.icon"
        :label="panelToggle.title"
        :active="panelToggle.active"
        @click="emit('toggle-panel')"
      />
      <VIconButton
        v-if="helpPath"
        icon="help"
        :label="$t('header.help.toggle')"
        :active="helpOpen"
        @click="onToggleHelp"
      />

      <div class="dropdown dropdown-end">
        <div tabindex="0" role="button" class="v-user-button">
          <span class="v-tenant">{{ tenantId }}</span>
          <span class="v-user">{{ username }}</span>
          <VIcon name="expand" class="v-user-chevron" />
        </div>
        <ul
          tabindex="0"
          class="dropdown-content menu v-menu z-[1] mt-2 w-56 p-1"
        >
          <!-- Collapsed by default: the full locale list is too long to
               sit open above every other entry. -->
          <li>
            <details ref="languageMenu">
              <summary>
                <VIcon name="language" />
                <span>{{ $t('header.menu.languageHeader') }}</span>
                <span class="v-menu-value">{{ currentLanguageLabel }}</span>
              </summary>
              <ul class="v-language-list">
                <li v-for="lang in LANGUAGES" :key="lang.code">
                  <a
                    :class="{ active: currentLocale === lang.code }"
                    @click="selectLanguage(lang.code)"
                  >
                    <span class="font-mono text-xs opacity-50 w-6">{{ lang.code.toUpperCase() }}</span>
                    <span>{{ lang.label }}</span>
                  </a>
                </li>
              </ul>
            </details>
          </li>
          <li class="divider-row"><div class="divider my-1" /></li>
          <template v-if="inFacelift">
            <li><a @click="onSwitchAccount"><VIcon name="swap" />{{ $t('header.menu.switchAccount') }}</a></li>
            <li><a @click="onManageAccounts"><VIcon name="team" />{{ $t('header.menu.manageAccounts') }}</a></li>
            <li><a @click="onAddAccount"><VIcon name="add" />{{ $t('header.menu.addAccount') }}</a></li>
            <li class="divider-row"><div class="divider my-1" /></li>
          </template>
          <li><a href="/profile.html"><VIcon name="user" />{{ $t('common.profile') }}</a></li>
          <li><a :href="editorHref('settings', { scope: 'user' })"><VIcon name="settings" />{{ $t('common.settings') }}</a></li>
          <li><a @click="openFook"><VIcon name="report-bug" />{{ $t('fook.menuLabel') }}</a></li>
          <li>
            <a :href="SOURCE_CODE_URL" target="_blank" rel="noopener noreferrer"><VIcon name="code" />{{ $t('header.menu.sourceCode') }}</a>
          </li>
          <li class="divider-row"><div class="divider my-1" /></li>
          <li><a @click="logout"><VIcon name="sign-out" />{{ $t('common.signOut') }}</a></li>
        </ul>
      </div>
    </div>
  </header>

  <FookSupportModal v-model="fookOpen" />
</template>

<style scoped>
/* Corporate design §7.1: the window ground with a hairline, no shadow; 44 px. */
.v-topbar {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 44px;
  padding: 0 12px;
  background: var(--v-bg);
  border-bottom: 1px solid var(--v-separator);
  color: var(--v-label);
}
.v-wordmark { font-size: 15px; font-weight: 600; letter-spacing: -0.02em; }
.v-crumb-sep { display: inline-block; vertical-align: middle; margin: 0 4px; color: var(--v-label-tertiary); }
.v-status-dot { display: inline-block; width: 8px; height: 8px; border-radius: 4px; margin: 0 4px; }
.v-status-connected { background: var(--v-success); }
.v-status-idle { background: var(--v-label-tertiary); }
.v-status-occupied { background: var(--v-danger); }
.v-user-button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 8px;
  border-radius: var(--v-radius-control);
  font-size: 13px;
  cursor: pointer;
}
.v-user-button:hover { background: var(--v-fill); }
.v-tenant { color: var(--v-label-secondary); }
.v-user { font-weight: 500; }
.v-user-chevron { color: var(--v-label-tertiary); }
.v-menu {
  background: var(--v-surface-raised);
  border-radius: 10px;
  box-shadow: var(--v-shadow-float);
}
.v-menu :deep(.v-icon) { color: var(--v-label-secondary); }
.v-menu-value { margin-left: auto; color: var(--v-label-secondary); }
.v-language-list { max-height: 240px; overflow-y: auto; }
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
