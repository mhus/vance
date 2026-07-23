import { nextTick, onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue';

/**
 * Browser-history binding for the Insights walker.
 *
 * The Insights view drills through three independent navigation axes:
 *   1. `topTab`    — the project-level pane (Sessions / Recipes / Tools / …)
 *   2. `selection` — nothing → a session → a process
 *   3. `activeTab` — the sub-tab within a session/process (Memory, Chat, …)
 *
 * Without this composable all three live as plain refs, so the browser
 * Back button has nothing to step through: one press leaves `insights.html`
 * entirely instead of peeling back a drill-down level, and a reload drops
 * the user back at the empty state. Here we mirror the three axes into the
 * URL query (`?tab=&sel=&view=`), push one history entry per navigation
 * intent, and restore state on `popstate` + initial load — the same pattern
 * cortex/EditorApp and inbox/InboxApp use.
 */

/** Top-level project tab keys — the axis mirrored to `?tab=`. */
export const INSIGHTS_TOP_TABS = [
  'sessions',
  'recipes',
  'tools',
  'workspace',
  'executions',
  'workflows',
  'events',
  'scheduler',
  'ursahooks',
  'rag',
  'research',
  'cluster',
  'addons',
  'usage',
] as const;

export type InsightsTopTab = (typeof INSIGHTS_TOP_TABS)[number];

export type InsightsSelection =
  | { kind: 'session'; id: string } // sessionId (business id)
  | { kind: 'process'; id: string } // process Mongo id
  | null;

const DEFAULT_TAB: InsightsTopTab = 'sessions';
const DEFAULT_VIEW = 'overview';

// URL query keys. Defaults are omitted from the URL so a pristine walker
// keeps a clean `/insights.html`.
const P_TAB = 'tab';
const P_SEL = 'sel';
const P_VIEW = 'view';

export interface InsightsNavigation {
  topTab: Ref<InsightsTopTab>;
  selection: Ref<InsightsSelection>;
  activeTab: Ref<string>;
  /**
   * True while state is being applied from the URL (initial load or a
   * back/forward navigation). Consumers gate side effects that must not
   * re-run or re-push during a restore.
   */
  restoring: Readonly<Ref<boolean>>;
}

function isTopTab(v: string | null): v is InsightsTopTab {
  return v != null && (INSIGHTS_TOP_TABS as readonly string[]).includes(v);
}

export function useInsightsNavigation(): InsightsNavigation {
  const topTab = ref<InsightsTopTab>(DEFAULT_TAB);
  const selection = ref<InsightsSelection>(null);
  const activeTab = ref<string>(DEFAULT_VIEW);
  const restoring = ref(false);

  // Non-reactive guards. `restoringGuard` stops the sub-tab reset from
  // clobbering the URL-provided sub-tab; `suppressPush` stops a restore
  // from writing a new history entry over the one we are navigating to.
  let restoringGuard = false;
  let suppressPush = false;

  // ── URL ⇄ state ────────────────────────────────────────────────────
  function readNav(): {
    topTab: InsightsTopTab;
    selection: InsightsSelection;
    activeTab: string;
  } {
    const params = new URLSearchParams(window.location.search);

    const tab = isTopTab(params.get(P_TAB)) ? (params.get(P_TAB) as InsightsTopTab) : DEFAULT_TAB;

    let sel: InsightsSelection = null;
    const rawSel = params.get(P_SEL);
    if (rawSel) {
      const idx = rawSel.indexOf(':');
      if (idx > 0) {
        const kind = rawSel.slice(0, idx);
        const id = rawSel.slice(idx + 1);
        if (id && (kind === 'session' || kind === 'process')) {
          sel = { kind, id };
        }
      }
    }

    const view = params.get(P_VIEW) || DEFAULT_VIEW;
    return { topTab: tab, selection: sel, activeTab: view };
  }

  function buildUrl(): string {
    const params = new URLSearchParams(window.location.search);

    if (topTab.value !== DEFAULT_TAB) params.set(P_TAB, topTab.value);
    else params.delete(P_TAB);

    if (selection.value) params.set(P_SEL, `${selection.value.kind}:${selection.value.id}`);
    else params.delete(P_SEL);

    // The sub-tab only carries meaning while something is selected.
    if (selection.value && activeTab.value !== DEFAULT_VIEW) params.set(P_VIEW, activeTab.value);
    else params.delete(P_VIEW);

    const query = params.toString();
    return `${window.location.pathname}${query ? `?${query}` : ''}`;
  }

  function currentUrl(): string {
    return `${window.location.pathname}${window.location.search}`;
  }

  function pushNav(): void {
    const next = buildUrl();
    if (next === currentUrl()) return;
    window.history.pushState(null, '', next);
  }

  /**
   * Copy the URL's encoded state onto the refs without letting the
   * side-effect watchers fire. `normalize` rewrites the current entry so a
   * messy deep link (e.g. `?view=memory` with no selection) collapses to a
   * canonical URL on first load.
   */
  function applyFromUrl(normalize: boolean): void {
    const nav = readNav();
    suppressPush = true;
    restoringGuard = true;
    restoring.value = true;
    topTab.value = nav.topTab;
    selection.value = nav.selection;
    activeTab.value = nav.activeTab;
    if (normalize) {
      const canonical = buildUrl();
      if (canonical !== currentUrl()) window.history.replaceState(null, '', canonical);
    }
    // Release the guards only after Vue has flushed every watcher the
    // writes above triggered, so the next genuine navigation pushes again.
    void nextTick().then(() => {
      suppressPush = false;
      restoringGuard = false;
      restoring.value = false;
    });
  }

  function onPopState(): void {
    applyFromUrl(false);
  }

  // Selecting a different session/process drops to the default sub-tab —
  // but not during a restore, where the URL already carries the intended
  // sub-tab. flush:'sync' so this reset lands in the same tick as the
  // selection change and the history-push watcher below coalesces both
  // into a single entry.
  watch(
    selection,
    () => {
      if (restoringGuard) return;
      activeTab.value = DEFAULT_VIEW;
    },
    { flush: 'sync' },
  );

  // One history entry per navigation intent. Multiple synchronous ref
  // changes (selection + its sub-tab reset) collapse into a single fire.
  watch([topTab, selection, activeTab], () => {
    if (suppressPush) return;
    pushNav();
  });

  onMounted(() => {
    applyFromUrl(true);
    window.addEventListener('popstate', onPopState);
  });

  onBeforeUnmount(() => {
    window.removeEventListener('popstate', onPopState);
  });

  return { topTab, selection, activeTab, restoring };
}
