<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, useSlots, watch } from 'vue';
import {
  getSessionData,
  isAccessAlive,
  isRefreshAlive,
  refreshAccessCookie,
} from '@/platform';
import { useHelp } from '@/composables/useHelp';
import EditorTopbar, { type Crumb } from './EditorTopbar.vue';
import MarkdownView from './MarkdownView.vue';
import NotificationToasts from '@/notification/NotificationToasts.vue';
import {
  closeConnection as wsCloseConnection,
  ensureConnected as wsEnsureConnected,
  useWsConnection,
} from '@/ws/wsConnectionStore';
import ReconnectOverlay from '@/ws/ReconnectOverlay.vue';
import { useNotificationSubscription } from '@/notification/useNotificationSubscription';

// Re-export the breadcrumb segment type so existing consumers
// (`import { type Crumb } from '@components'`) keep working without
// a path change.
export type { Crumb };

/**
 * The four-zone layout's focus state. Drives column/row sizing,
 * background highlighting, and reclaim handle visibility when
 * {@link Props.focusModel} is {@code 'auto'}. See
 * `specification/web-ui.md` §7.2.1.
 */
export type FocusZone = 'main' | 'sidebar' | 'right' | 'footer';

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
   *
   * <p>Ignored when {@link focusModel} is {@code 'auto'} — focus mode
   * computes the right-panel width from {@link focusZone} instead.
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
  /**
   * Renders the page title as a clickable element that emits the
   * {@code title-click} event. Editors with a sidebar typically wire
   * this to focusing the sidebar; editors with a meaningful "back to
   * entry-point" (e.g. chat → session picker) wire it to that
   * navigation. EditorShell itself does not implement any default
   * behaviour for the click — the parent decides via the emitted event.
   */
  titleClickable?: boolean;
  /**
   * Focus-driven zone resizing. {@code 'off'} (default) keeps the
   * legacy fixed-width layout — sidebar 16rem, right panel 20rem (or
   * 40rem with {@link wideRightPanel}), no footer scaling, no zone
   * highlighting, no reclaim handles. {@code 'auto'} activates the
   * single-focus-zone model: the fokussierte zone bekommt mehr Platz
   * und einen hellen Background; die anderen schrumpfen auf eine
   * Kompakt-Breite und nehmen den Editor-Background an. Reclaim-Handles
   * an den Rändern bleiben klickbar, auch wenn eine Zone auf 0
   * kollabiert ist.
   *
   * <p>See `specification/web-ui.md` §7.2.1 for the full model.
   */
  focusModel?: 'off' | 'auto';
  /**
   * Explicit per-zone visibility overrides. When set to {@code false}
   * the corresponding zone is suppressed even if its slot is filled —
   * useful when the slot content depends on a state that briefly
   * renders empty (e.g. chat's picker mode keeps the {@code #footer}
   * slot template registered but the inner v-if hides the composer;
   * without this prop the editor shows an empty footer rail).
   * Default {@code undefined} = fall back to slot-presence detection.
   */
  showSidebar?: boolean;
  showRightPanel?: boolean;
  showFooter?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  breadcrumbs: () => [],
  wideRightPanel: false,
  fullHeight: false,
  helpOpen: false,
  titleClickable: false,
  focusModel: 'off',
});

const emit = defineEmits<{
  /** Forwarded from EditorTopbar when the title is clicked and
   *  {@link Props.titleClickable} is true. EditorShell does not act
   *  on this itself — the host editor decides what title-click means. */
  'title-click': [];
}>();

/**
 * Currently focused zone. v-model'd so a parent can read it (or
 * preset it) but EditorShell owns the runtime updates via its
 * pointerdown/focusin/escape listeners. Ignored entirely when
 * {@link Props.focusModel} is {@code 'off'}.
 */
const focusZone = defineModel<FocusZone>('focusZone', { default: 'main' });

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

/**
 * Body layout — CSS Grid with up to three columns (sidebar, main,
 * right) and up to two rows (main row, footer row). Columns appear
 * only when their slot is filled (or the help drawer is open, which
 * borrows the right-panel column). Footer is full-width in row 2.
 *
 * <p>Actual track widths live in CSS variables in the scoped
 * {@code <style>} block below — driven by the {@code data-focus} /
 * {@code data-wide-right} / {@code data-help-open} attributes set on
 * the grid container. See `specification/web-ui.md` §7.2.1.
 */
const slots = useSlots();

const hasSidebarSlot = computed<boolean>(() => {
  if (props.showSidebar === false) return false;
  return !!slots.sidebar;
});
const hasRightCell = computed<boolean>(() => {
  if (props.showRightPanel === false && !showHelp.value) return false;
  return showHelp.value || !!slots['right-panel'];
});
const hasFooterSlot = computed<boolean>(() => {
  if (props.showFooter === false) return false;
  return !!slots.footer;
});

const gridTemplateColumns = computed<string>(() => {
  const cols: string[] = [];
  if (hasSidebarSlot.value) cols.push('var(--shell-sidebar-w)');
  cols.push('1fr');
  if (hasRightCell.value) cols.push('var(--shell-right-w)');
  return cols.join(' ');
});

const gridTemplateRows = computed<string>(() => {
  return hasFooterSlot.value ? '1fr var(--shell-footer-h)' : '1fr';
});

/**
 * Encodes the current focus zone as the {@code data-focus} attribute
 * on the grid root — but only when focus-model is active. CSS rules
 * key off this attribute for column widths, backgrounds, and reclaim
 * handle visibility.
 */
const effectiveFocusZone = computed<FocusZone | null>(() =>
  props.focusModel === 'auto' ? (focusZone.value ?? null) : null);

function onZonePointerdown(zone: FocusZone): void {
  if (props.focusModel !== 'auto') return;
  focusZone.value = zone;
}

function onFooterFocusin(): void {
  if (props.focusModel !== 'auto') return;
  focusZone.value = 'footer';
}

/**
 * Escape returns focus to {@code 'main'} — the implicit "home" zone.
 * Only active when focus-model is on, so editors that don't opt in
 * are unaffected by global keyboard captures.
 */
function onGlobalKeydown(ev: KeyboardEvent): void {
  if (props.focusModel !== 'auto') return;
  if (ev.key === 'Escape' && focusZone.value !== 'main') {
    focusZone.value = 'main';
  }
}

/**
 * Pointerdown outside the editor-shell grid (notably the topbar) has
 * no zone it belongs to — default to {@code 'main'} so the main zone
 * is always the implicit "home" when nothing else asked for focus.
 */
function onGlobalPointerdown(ev: PointerEvent): void {
  if (props.focusModel !== 'auto') return;
  const target = ev.target as HTMLElement | null;
  if (!target) return;
  if (!target.closest('.editor-shell-grid')) {
    focusZone.value = 'main';
  }
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

// ──────────────── WebSocket lifecycle ────────────────
//
// The shell owns the tab-singleton WebSocket — every editor that mounts
// it gets a live connection without any per-page boilerplate. Session
// binding stays a consumer concern (ChatApp / CortexChatPanel call
// {@code bindSession} / {@code leaveChat}).
//
// The connection status indicator in the topbar reads from the same
// store, so every editor inherits the green/grey/red dot — including
// REST-only editors like {@code documents.html} that just want to show
// "live" without binding a session.

const { socket: wsSocket, status: wsStatus } = useWsConnection();

// Global notify-toast subscription — every editor inherits the toast
// stack automatically. Composable re-attaches on each socket swap
// (after reconnect / session-rebind).
useNotificationSubscription(wsSocket);

const derivedConnectionState = computed<'connected' | 'idle' | 'occupied'>(() => {
  if (wsStatus.value === 'reconnecting' || wsStatus.value === 'down') return 'occupied';
  // Green = WebSocket up. The session-bound aspect (chat-specific) is
  // surfaced separately by ChatApp via the {@code connectionState}
  // prop override when it wants to show e.g. 'occupied' for a 409.
  if (wsStatus.value === 'connected') return 'connected';
  return 'idle';
});

const effectiveConnectionState = computed<'connected' | 'idle' | 'occupied' | undefined>(
  () => props.connectionState ?? derivedConnectionState.value,
);

onMounted(() => {
  void guardAccessCookie();
  expiryTimer = window.setInterval(() => {
    void guardAccessCookie();
  }, 60_000);
  // Listeners always attached; the handlers themselves no-op when
  // focusModel !== 'auto'. Cheaper than churning add/remove on prop
  // changes and lets a parent flip focusModel at runtime cleanly.
  window.addEventListener('keydown', onGlobalKeydown);
  window.addEventListener('pointerdown', onGlobalPointerdown);
  // Eagerly open the tab-singleton WebSocket — every editor that uses
  // EditorShell gets a live connection regardless of whether it later
  // binds a session. Silent on failure (no tenant before login,
  // network down on initial boot) because the store's reconnect loop
  // and the &lt;ReconnectOverlay&gt; take it from here.
  void wsEnsureConnected().catch(() => { /* surfaced via overlay */ });
});

onBeforeUnmount(() => {
  if (expiryTimer != null) {
    window.clearInterval(expiryTimer);
    expiryTimer = null;
  }
  window.removeEventListener('keydown', onGlobalKeydown);
  window.removeEventListener('pointerdown', onGlobalPointerdown);
  // The shell unmounts on full-page navigation only (MPA pattern), and
  // at that point the browser is destroying the Vue app anyway. Close
  // explicitly so the reconnect loop stops cleanly instead of racing
  // against the browser killing the socket.
  wsCloseConnection();
});
</script>

<template>
  <div class="h-screen h-dvh overflow-hidden flex flex-col bg-base-200">
    <EditorTopbar
      :title="title"
      :breadcrumbs="breadcrumbs"
      :connection-state="effectiveConnectionState"
      :connection-tooltip="connectionTooltip"
      :help-path="helpPath"
      :help-open="showHelp"
      :title-clickable="titleClickable"
      @toggle-help="toggleHelp"
      @title-click="emit('title-click')"
    >
      <!-- Only forward when the parent of EditorShell actually filled
           the slot — an empty <template> would still satisfy a
           {@code $slots['topbar-extra']} check inside EditorTopbar
           and produce extra spacing. -->
      <template v-if="$slots['topbar-extra']" #topbar-extra>
        <slot name="topbar-extra" />
      </template>
    </EditorTopbar>

    <div
      class="editor-shell-grid flex-1 min-h-0"
      :data-focus="effectiveFocusZone"
      :data-wide-right="focusModel === 'off' && wideRightPanel ? '' : null"
      :data-help-open="showHelp ? '' : null"
      :style="{ gridTemplateColumns, gridTemplateRows }"
    >
      <aside
        v-if="hasSidebarSlot"
        class="zone zone-sidebar min-w-0 min-h-0 border-r border-base-300 overflow-y-auto"
        @pointerdown="onZonePointerdown('sidebar')"
      >
        <slot name="sidebar" />
      </aside>

      <main
        :class="[
          'zone zone-main min-w-0 min-h-0',
          fullHeight ? 'overflow-hidden' : 'overflow-y-auto',
        ]"
        @pointerdown="onZonePointerdown('main')"
      >
        <slot />
      </main>

      <aside
        v-if="hasRightCell"
        class="zone zone-right min-w-0 min-h-0 border-l border-base-300 overflow-y-auto"
        @pointerdown="onZonePointerdown('right')"
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

      <footer
        v-if="hasFooterSlot"
        class="zone zone-footer col-span-full min-w-0 min-h-0 border-t border-base-300 overflow-x-clip overflow-y-visible"
        @focusin="onFooterFocusin"
      >
        <slot name="footer" />
      </footer>

      <!-- Reclaim handles — sit at zone boundaries so they stay
           clickable even when the adjacent zone collapses to width 0.
           Only rendered in focus-model 'auto' AND when the zone
           actually exists. The handle for the currently-focused zone
           is fade-hidden so it doesn't double as a noisy chip. -->
      <template v-if="focusModel === 'auto'">
        <button
          v-if="hasSidebarSlot"
          type="button"
          class="reclaim-handle reclaim-handle-sidebar"
          :class="{ 'reclaim-handle--hidden': focusZone === 'sidebar' }"
          aria-label="Expand sidebar"
          @click="focusZone = 'sidebar'"
        >›</button>
        <button
          v-if="hasRightCell"
          type="button"
          class="reclaim-handle reclaim-handle-right"
          :class="{ 'reclaim-handle--hidden': focusZone === 'right' }"
          aria-label="Expand right panel"
          @click="focusZone = 'right'"
        >‹</button>
      </template>
    </div>
    <!-- Global toast overlay for the user-notification side-channel.
         Mounted once at the shell level so every editor gets it for
         free; the store + WebSocket subscription live elsewhere. -->
    <NotificationToasts />
    <!-- Global reconnect overlay — visible whenever the tab-singleton
         WebSocket is reconnecting or down. Renders a blocking modal so
         every editor (chat, cortex, documents, …) automatically
         inherits the "Verbindung verloren / Erneut versuchen" UX. -->
    <ReconnectOverlay />
  </div>
</template>

<style scoped>
/* ──────────────── Editor-shell grid + focus model ────────────────
 *
 * Track widths and row heights live in CSS variables so editors can
 * tune them by overriding the variable on the {@code .editor-shell-grid}
 * selector — no script changes needed. The {@code data-focus} attribute
 * on the grid root carries the currently focused zone (only set when
 * {@code focusModel='auto'}); CSS rules key off it for column widths,
 * backgrounds, and reclaim handle visibility.
 *
 * To make a zone disappear on small viewports, drop its CSS variable
 * to 0 (or {@code clamp(...)}) inside a media query. The reclaim
 * handle stays clickable because it's anchored to the grid container,
 * not the zone itself.
 *
 * Spec: `specification/web-ui.md` §7.2.1.
 */

.editor-shell-grid {
  /* Track sizes per zone. Sidebar and right baseline match so that
   * focus=main / focus=footer render symmetric L/R rails. */
  --shell-sidebar-w: 16rem;
  --shell-right-w: 16rem;
  --shell-footer-h: auto;

  /* Single source of truth for resize + colour transitions. */
  --shell-focus-duration: 200ms;

  position: relative;        /* anchors the absolutely-placed handles */
  display: grid;
  transition:
    grid-template-columns var(--shell-focus-duration) ease-out,
    grid-template-rows var(--shell-focus-duration) ease-out;
}

/* Legacy wide-right-panel mode (focusModel='off' + wideRightPanel). */
.editor-shell-grid[data-wide-right] {
  --shell-right-w: 40rem;
}

/* Focus-driven widths. Applied only when data-focus is set, which
 * happens exclusively in focusModel='auto'. Sidebar expands when
 * focused; right expands further when focused. Footer focus shrinks
 * both rails together so the composer gets horizontal room while the
 * L/R symmetry stays intact. */
.editor-shell-grid[data-focus='sidebar'] { --shell-sidebar-w: 24rem; }
.editor-shell-grid[data-focus='right']   { --shell-right-w: 32rem; }
.editor-shell-grid[data-focus='footer'] {
  --shell-sidebar-w: 14rem;
  --shell-right-w: 14rem;
}

/* Help drawer commandeers the right column at its non-focus baseline
 * (20rem, or 40rem with wideRightPanel). Comes *after* the focus
 * rules so it wins when both attributes are present. */
.editor-shell-grid[data-help-open] {
  --shell-right-w: 20rem;
}
.editor-shell-grid[data-help-open][data-wide-right] {
  --shell-right-w: 40rem;
}

/* Tablet (iPad portrait, ~768–900px): sidebar always collapses to 0
 * unless explicitly focused; right stays available via reclaim handle
 * but is hidden in main/footer focus so the centre zone gets the room.
 * The order matters — generic data-focus rule first, then the focused-
 * zone overrides. */
@media (max-width: 900px) {
  .editor-shell-grid[data-focus]:not([data-help-open]) {
    --shell-sidebar-w: 0;
    --shell-right-w: 0;
  }
  .editor-shell-grid[data-focus='sidebar']:not([data-help-open]) {
    --shell-sidebar-w: 20rem;
  }
  .editor-shell-grid[data-focus='right']:not([data-help-open]) {
    --shell-right-w: 24rem;
  }
}

/* Phone-narrow viewports (≤600px, covers iPhone 17 Pro portrait at
 * 430px). The focused side zone takes the full width; main is reached
 * via Escape or by clicking the reclaim handle. Inherits "both rails 0
 * in main/footer focus" from the 900px breakpoint. */
@media (max-width: 600px) {
  .editor-shell-grid[data-focus='sidebar']:not([data-help-open]) {
    --shell-sidebar-w: 100%;
  }
  .editor-shell-grid[data-focus='right']:not([data-help-open]) {
    --shell-right-w: 100%;
  }
}

/* ──────────────── Zone backgrounds ────────────────
 *
 * Legacy look (focus-model 'off'): sidebar / right / footer are
 * panels (base-100, white), main inherits the editor surround
 * (base-200, grey). Focus mode flips that — all zones default to
 * the surround colour, and only the focused one lifts to white.
 * That makes the active zone visually pop without separating panels
 * via heavy borders.
 *
 * The focus-mode rules have higher specificity ({@code [data-focus]}
 * attribute selector) so they win when both apply.
 */
.editor-shell-grid > .zone-sidebar,
.editor-shell-grid > .zone-right,
.editor-shell-grid > .zone-footer {
  background-color: var(--fallback-b1, oklch(var(--b1) / 1));
  transition: background-color var(--shell-focus-duration) ease-out;
}
.editor-shell-grid[data-focus] > .zone {
  background-color: var(--fallback-b2, oklch(var(--b2) / 1));
}
.editor-shell-grid[data-focus='sidebar'] > .zone-sidebar,
.editor-shell-grid[data-focus='main']    > .zone-main,
.editor-shell-grid[data-focus='right']   > .zone-right,
.editor-shell-grid[data-focus='footer']  > .zone-footer {
  background-color: var(--fallback-b1, oklch(var(--b1) / 1));
}

/* ──────────────── Reclaim handles ────────────────
 *
 * Anchored to the grid container, not to the zone, so they stay
 * clickable when the zone collapses to width 0. Position uses the
 * same CSS variable that drives the grid track, so the handle glides
 * along the boundary as the layout animates.
 */
.reclaim-handle {
  position: absolute;
  z-index: 10;
  padding: 0.5rem 0.25rem;
  background-color: var(--fallback-b3, oklch(var(--b3) / 1));
  color: var(--fallback-bc, oklch(var(--bc) / 0.7));
  font-size: 0.875rem;
  line-height: 1;
  cursor: pointer;
  border: none;
  transition:
    opacity var(--shell-focus-duration) ease-out,
    left var(--shell-focus-duration) ease-out,
    right var(--shell-focus-duration) ease-out,
    background-color 120ms ease-out;
}
.reclaim-handle:hover {
  background-color: var(--fallback-b2, oklch(var(--b2) / 1));
  color: var(--fallback-bc, oklch(var(--bc) / 1));
}
.reclaim-handle--hidden {
  opacity: 0;
  pointer-events: none;
}

.reclaim-handle-sidebar {
  left: var(--shell-sidebar-w);
  top: 50%;
  transform: translateY(-50%);
  border-radius: 0 0.375rem 0.375rem 0;
}
.reclaim-handle-right {
  right: var(--shell-right-w);
  top: 50%;
  transform: translateY(-50%);
  border-radius: 0.375rem 0 0 0.375rem;
}
</style>
