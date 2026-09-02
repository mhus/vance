/**
 * Addon-contributed Cortex menu entries: read at boot, loaded on click.
 *
 * The manifest (`/face/addons`) is already fetched for the tiles and the kind
 * index, so turning its `menu:` blocks into registry entries costs nothing —
 * no remote is touched here. The addon's bundle is fetched the first time one
 * of its entries is actually activated.
 *
 * <p>That ordering is the whole design. A menu entry has no document kind to
 * hang a lazy load on, so the alternative was `eager: true` on every
 * contributing addon — one conditional round trip per addon on every page
 * load, which is exactly what `addonRegistry` stopped doing.
 *
 * <p><b>The consequence, stated once:</b> whether an entry is shown has to be
 * answerable before the addon's code exists in the page, so it is declared as
 * data (`kinds:` / `mimes:`) instead of a predicate. When both are declared
 * they are alternatives — the entry shows when the document matches either.
 */

import { loadRemote } from '@module-federation/runtime';
import { addonRemoteName, loadAddonManifest, type AddonMenuItem } from './addonManifest';
import { ensureAddonRemotesRegistered } from './addonRegistry';
import {
  isCortexMenuSlot,
  registerCortexMenuItem,
  type CortexMenuContext,
  type CortexMenuDocument,
} from './cortexMenu';
import { asUiLevel, getActiveUiLevel, rankOf } from './webUiSession';

const DEFAULT_EXPOSE = './menu';
const DEFAULT_HANDLER = 'run';

/** Shape a contributing addon's expose is expected to have. */
type MenuExpose = Record<string, unknown> & {
  default?: Record<string, unknown>;
};

let loaded = false;

/**
 * Register every declared addon menu entry. Idempotent — the workspace boots
 * once, but nothing stops a second caller, and the registry is keyed by id
 * anyway.
 *
 * <p>Never throws: an unreachable manifest means no contributed entries, and
 * the Cortex menu bar is not a surface that may fail to render because an
 * addon list did.
 */
export async function loadCortexMenuContributions(): Promise<void> {
  if (loaded) return;
  loaded = true;

  const level = rankOf(getActiveUiLevel());
  const addons = await loadAddonManifest();
  for (const addon of addons) {
    for (const decl of addon.menu ?? []) {
      if (!decl?.id || !decl.label || !isCortexMenuSlot(decl.slot)) {
        // The brain drops these already; a hand-written dev manifest may not.
        console.warn(`[cortexMenu] '${addon.name}' declares an unusable menu entry`, decl);
        continue;
      }
      if (level < rankOf(asUiLevel(decl.minLevel))) continue;
      registerCortexMenuItem({
        id: `${addon.name}:${decl.id}`,
        slot: decl.slot,
        label: decl.label,
        sortIndex: decl.sortIndex,
        visible: (ctx) => matchesDocument(decl, ctx.document),
        run: (ctx) => activate(addon.name, decl, ctx),
      });
    }
  }
}

/** Test seam — lets a test re-run the loader against a fresh manifest. */
export function resetCortexMenuContributions(): void {
  loaded = false;
}

/**
 * Whether a declaration applies to the active document.
 *
 * Neither list declared → the entry does not depend on the document and is
 * always shown (it may still be disabled by whatever it does). Otherwise a
 * document is required and has to match one of the declared lists.
 */
export function matchesDocument(
  decl: Pick<AddonMenuItem, 'kinds' | 'mimes'>,
  doc: CortexMenuDocument | null,
): boolean {
  const kinds = decl.kinds ?? null;
  const mimes = decl.mimes ?? null;
  if (kinds === null && mimes === null) return true;
  if (!doc) return false;
  const kind = (doc.kind ?? '').trim().toLowerCase();
  if (kinds && kind && kinds.some((k) => k.trim().toLowerCase() === kind)) return true;
  const mime = (doc.mimeType ?? '').trim().toLowerCase();
  if (mimes && mime && mimes.some((m) => mime.startsWith(m.trim().toLowerCase()))) return true;
  return false;
}

/**
 * Load the addon's expose and call its handler.
 *
 * Failures propagate to the caller (the menu bar surfaces them as a notice)
 * rather than being swallowed here: the reader clicked something and nothing
 * happening is the one outcome that cannot be explained.
 */
async function activate(
  addonName: string,
  decl: AddonMenuItem,
  ctx: CortexMenuContext,
): Promise<void> {
  // Memoised, and the reason this works from any entry point: `/cortex` boots
  // through the shell, but a caller that did not register the remotes yet
  // would otherwise fail with an unattributed "remote not found".
  await ensureAddonRemotesRegistered();
  const expose = (decl.expose ?? DEFAULT_EXPOSE).replace(/^\.\//, '');
  const handlerName = decl.handler ?? DEFAULT_HANDLER;
  const mod = await loadRemote<MenuExpose>(`${addonRemoteName(addonName)}/${expose}`);
  const fn = (mod?.[handlerName] ?? mod?.default?.[handlerName]) as
    | ((ctx: CortexMenuContext) => void | Promise<void>)
    | undefined;
  if (typeof fn !== 'function') {
    throw new Error(
      `Addon '${addonName}' exposes no '${handlerName}' in '${decl.expose ?? DEFAULT_EXPOSE}'.`,
    );
  }
  await fn(ctx);
}
