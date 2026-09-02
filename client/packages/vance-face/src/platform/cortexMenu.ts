/**
 * The Cortex menu bar's extension point.
 *
 * Three of its menus — View, Actions and the new Extras — carry a contribution
 * slot at the bottom, below a separator. Entries land there from two sources
 * that share this one registry:
 *
 *  - the host itself (`cortex/translateMenu.ts` is the first), which registers
 *    a closure directly;
 *  - installed addons, whose declarations arrive from `/face/addons` through
 *    {@link loadCortexMenuContributions} and whose bundle is fetched when the
 *    entry is clicked, not before.
 *
 * <p><b>Why the host's own entries go through here too.</b> An extension point
 * nobody inside uses is an extension point nobody has tried. Translate is a
 * real feature with a dialog, a document to write and a failure path, and it
 * uses exactly the interface an addon gets.
 *
 * <p><b>What stays hard-wired.</b> The existing File / View / Actions / Chat
 * entries keep their place in the template. They carry checkmarks, keyboard
 * hints and disabled states that an item model would have to grow a field for
 * each of — and the point of this registry is to let *new* entries in, not to
 * re-express the ones that already work.
 *
 * <p>Registration is idempotent by id: registering the same id twice replaces
 * the earlier entry rather than showing it twice. The Cortex mounts and
 * unmounts as the workspace routes between /chat and /cortex, and a registry
 * that grew on every mount would multiply every entry.
 */

import { shallowRef } from 'vue';

/** The menus that accept contributions. */
export type CortexMenuSlot = 'view' | 'actions' | 'extras';

const SLOTS: readonly string[] = ['view', 'actions', 'extras'];

/** Whether a string names a menu that accepts contributions. */
export function isCortexMenuSlot(value: string | null | undefined): value is CortexMenuSlot {
  return typeof value === 'string' && SLOTS.includes(value);
}

/** The active document, as a menu entry sees it. */
export interface CortexMenuDocument {
  id: string;
  path: string;
  name: string;
  mimeType: string | null;
  kind: string | null;
  /**
   * The body as the editor currently holds it — which may differ from what is
   * stored, because the tab can be dirty. That is the text the reader is
   * looking at, and therefore the one an entry should act on.
   */
  text: string;
  /** True while the tab has unsaved changes. */
  dirty: boolean;
}

/** The reader's current text selection inside the active document. */
export interface CortexMenuSelection {
  docPath: string;
  from: number;
  to: number;
  text: string;
}

/** What an entry is handed when it runs — and what its predicates see. */
export interface CortexMenuContext {
  projectId: string;
  /** Null when no tab is open. */
  document: CortexMenuDocument | null;
  /** Null when nothing is selected. */
  selection: CortexMenuSelection | null;
  /** Open a document by id as a tab, revealing its folder on the way. */
  openDocument: (id: string) => Promise<void>;
  /** Reveal a path in the file tree without opening it. */
  revealPath: (path: string) => Promise<void>;
}

/** One entry in a Cortex menu's contribution slot. */
export interface CortexMenuItem {
  /** Unique across the registry. Addon entries are `<addon>:<id>`. */
  id: string;
  slot: CortexMenuSlot;
  /**
   * The label, or a function returning it. Host entries pass a function so
   * their translation follows a locale switch — an addon's label comes from
   * its manifest and is a plain string with no locale to follow.
   */
  label: string | (() => string);
  /** Lower first; unset sorts after everything numbered, then by label. */
  sortIndex?: number;
  /** Shown at all? Absent means always. */
  visible?: (ctx: CortexMenuContext) => boolean;
  /** Clickable? Absent means always. A hidden entry is never asked. */
  enabled?: (ctx: CortexMenuContext) => boolean;
  run: (ctx: CortexMenuContext) => void | Promise<void>;
}

/**
 * The registry. A `shallowRef` rather than a plain array so a contribution
 * that arrives after the menu bar rendered — an addon manifest resolving one
 * tick late — still reaches it.
 */
const items = shallowRef<CortexMenuItem[]>([]);

/** Add an entry, replacing any earlier one with the same id. */
export function registerCortexMenuItem(item: CortexMenuItem): void {
  const without = items.value.filter((existing) => existing.id !== item.id);
  items.value = [...without, item];
}

/** Drop an entry by id. No-op when it was never registered. */
export function unregisterCortexMenuItem(id: string): void {
  const without = items.value.filter((existing) => existing.id !== id);
  if (without.length !== items.value.length) items.value = without;
}

/** Everything registered, in registration order. Test seam. */
export function allCortexMenuItems(): CortexMenuItem[] {
  return items.value;
}

/** Forget every entry. Test seam — nothing in the app unregisters wholesale. */
export function resetCortexMenu(): void {
  items.value = [];
}

/**
 * The entries to render in one menu, already filtered by their own `visible`
 * predicate and sorted.
 *
 * <p>A predicate that throws hides its entry rather than taking the menu down
 * with it: the predicate comes from an addon, and the reader still needs the
 * File menu.
 */
export function cortexMenuItemsFor(
  slot: CortexMenuSlot,
  ctx: CortexMenuContext,
): CortexMenuItem[] {
  const matching = items.value.filter((item) => {
    if (item.slot !== slot) return false;
    if (!item.visible) return true;
    try {
      return item.visible(ctx);
    } catch (e) {
      console.warn(`[cortexMenu] '${item.id}' visibility check failed — entry hidden`, e);
      return false;
    }
  });
  return matching.sort((a, b) => {
    if (a.sortIndex != null && b.sortIndex != null) return a.sortIndex - b.sortIndex;
    if (a.sortIndex != null) return -1;
    if (b.sortIndex != null) return 1;
    return cortexMenuItemLabel(a).localeCompare(cortexMenuItemLabel(b));
  });
}

/** The entry's label, resolving the deferred form. Never throws. */
export function cortexMenuItemLabel(item: CortexMenuItem): string {
  if (typeof item.label === 'string') return item.label;
  try {
    return item.label();
  } catch (e) {
    console.warn(`[cortexMenu] '${item.id}' has no usable label`, e);
    return item.id;
  }
}

/** Whether an entry is clickable in this context. Same fail-safe as above. */
export function isCortexMenuItemEnabled(
  item: CortexMenuItem,
  ctx: CortexMenuContext,
): boolean {
  if (!item.enabled) return true;
  try {
    return item.enabled(ctx);
  } catch (e) {
    console.warn(`[cortexMenu] '${item.id}' enablement check failed — entry disabled`, e);
    return false;
  }
}
