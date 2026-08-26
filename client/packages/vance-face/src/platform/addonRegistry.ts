/**
 * Addon federation: register at boot, load on demand.
 *
 * Two steps that used to be one. {@link ensureAddonRemotesRegistered} fetches
 * `/face/addons` and hands the list to Module Federation's
 * {@code registerRemotes()} — bookkeeping only, no network beyond the manifest
 * itself. {@link ensureKindLoaded} then pulls a single addon's `./register`
 * expose the first time a document of a kind it owns is opened.
 *
 * <p>Why the split: `register()` in every one of our addons does exactly one
 * thing — call `registerKind()` (workbook additionally `registerBlock()`). Every
 * one of those registrations is reachable from the kind string alone, either
 * through a `matches()` predicate that tests it or, for the `application:*`
 * kinds, through an explicit `resolveKind()` lookup whose `matches()` is a flat
 * `() => false`. So the host does not need the addon loaded to know it exists —
 * it needs the *name*, and the manifest carries that.
 *
 * <p>The cost this removes is per navigation, not per session: loading all
 * remotes eagerly meant one conditional round trip per addon on every page
 * load, because `remoteEntry.js` is the one file in the bundle that carries no
 * content hash and therefore must revalidate. Eighteen addons, every click.
 *
 * <p><b>The manifest is a load trigger, not a matching rule.</b> `kinds:` says
 * which remote to fetch; the addon's own predicate still decides whether its
 * entry applies. Declaring a kind the addon rejects costs a round trip.
 * Declaring too little is the failure that matters — which is why an addon
 * whose manifest carries no `kinds:` key at all is loaded eagerly, exactly as
 * before. Forgetting the declaration costs speed, never function; opting out
 * is an explicit empty list.
 */

import { registerRemotes, loadRemote } from '@module-federation/runtime';
import {
  addonRemoteEntry,
  addonRemoteName,
  loadAddonManifest,
  type AddonManifestEntry,
} from './addonManifest';

interface RegisterExpose {
  register?: () => void;
  default?: { register?: () => void };
}

/** kind id (lower-case) → addon id that declares it. */
type KindIndex = Map<string, string>;

let discovery: Promise<KindIndex> | null = null;
/** addon id → the in-flight or settled load of its `./register` expose. */
const loading = new Map<string, Promise<void>>();

/**
 * Register every installed addon as a federation remote and index the kinds
 * they declare. Idempotent and memoised — the manifest is fetched once per
 * page, and every entry point may call this without coordinating.
 *
 * <p>Two kinds of addon are loaded here rather than indexed: those that declare
 * no `kinds:` key (we cannot know what they contribute, so the old eager
 * behaviour applies to them alone) and those that declare `eager: true`
 * (a contribution no kind can trigger — see {@link AddonManifestEntry.eager}).
 */
export function ensureAddonRemotesRegistered(): Promise<KindIndex> {
  if (!discovery) discovery = discover();
  return discovery;
}

/** Boot hook. Registers remotes; loads none except the undeclared ones. */
export async function initAddonRemotes(): Promise<void> {
  await ensureAddonRemotesRegistered();
}

async function discover(): Promise<KindIndex> {
  const index: KindIndex = new Map();
  const addons = await loadAddonManifest();
  if (addons.length === 0) return index;

  // `type: 'module'` makes the host load the remoteEntry via
  // <script type="module"> — without it Vite's `import`-emitting remoteEntry
  // throws "Cannot use import statement outside a module".
  try {
    registerRemotes(
      addons.map((a) => ({
        name: addonRemoteName(a.name),
        entry: addonRemoteEntry(a.name),
        type: 'module' as const,
      })),
      { force: true },
    );
  } catch (e) {
    console.error('[addonRegistry] registerRemotes failed', e);
    return index;
  }

  const upfront: string[] = [];
  for (const addon of addons) {
    // Asked for it: a contribution that no kind can trigger. See
    // AddonDto.eager — the block-editor block is the reason this exists.
    if (addon.eager) upfront.push(addon.name);
    if (!addon.kinds) {
      if (!addon.eager) upfront.push(addon.name);
      continue;
    }
    for (const raw of addon.kinds) {
      const kindId = raw.trim().toLowerCase();
      if (!kindId) continue;
      const owner = index.get(kindId);
      if (owner && owner !== addon.name) {
        // Manifest order decides, deterministically. The eager loader used to
        // settle this by whichever `register()` ran last — a race that only
        // surfaced once two addons claimed the same kind, and then only
        // sometimes. Resolving it in the index closes the question for good.
        console.warn(
          `[addonRegistry] kind "${kindId}" is declared by both "${owner}" and `
            + `"${addon.name}" — keeping "${owner}" (manifest order)`,
        );
        continue;
      }
      index.set(kindId, addon.name);
    }
  }

  if (upfront.length > 0) {
    console.info(`[addonRegistry] loading at boot: ${upfront.join(', ')}`);
    await Promise.all(upfront.map((name) => loadAddon(name)));
  }
  return index;
}

/**
 * Load an addon's `./register` expose and run it, at most once per page.
 *
 * Failures are non-fatal and memoised along with successes: an addon without
 * the expose (an `./area`-only addon whose manifest omitted `kinds:`) must not
 * be retried on every document that misses the index.
 */
function loadAddon(name: string): Promise<void> {
  const pending = loading.get(name);
  if (pending) return pending;

  const started = (async () => {
    try {
      const mod = await loadRemote<RegisterExpose>(`${addonRemoteName(name)}/register`);
      const fn = mod?.register ?? mod?.default?.register;
      if (typeof fn === 'function') fn();
    } catch {
      // `./register` absent or threw — same as before: the addon simply
      // contributes nothing, and the host carries on with built-in kinds.
    }
  })();
  loading.set(name, started);
  return started;
}

/**
 * Make sure the addon owning {@code kindId} has registered, if any does.
 *
 * @returns whether an addon was loaded as a result of this call — the caller
 *   can use it to re-evaluate a binding it resolved before.
 */
export async function ensureKindLoaded(kindId: string | null | undefined): Promise<boolean> {
  const id = (kindId ?? '').trim().toLowerCase();
  if (!id) return false;
  const index = await ensureAddonRemotesRegistered();
  const owner = index.get(id);
  if (!owner || loading.has(owner)) return false;
  await loadAddon(owner);
  return true;
}

/**
 * The kind ids a document could dispatch to, in the same shape
 * `docTypeRegistry.resolveBinding` derives them: an `application` document
 * dispatches on its `app:` discriminator, everything else on its own kind.
 */
export function candidateKindIds(
  kind: string | null | undefined,
  appType: string | null | undefined,
): string[] {
  const k = (kind ?? '').trim().toLowerCase();
  if (!k) return [];
  if (k === 'application') {
    const app = (appType ?? '').trim().toLowerCase();
    return app ? [`application:${app}`] : [];
  }
  return [k];
}

/**
 * Load whatever addon a document needs before its binding is resolved.
 * Call this from an already-async open path, so the synchronous
 * `resolveBinding` that follows sees a populated registry and the reader never
 * sees the raw-YAML fallback flash past.
 */
export async function ensureKindsForDocument(
  kind: string | null | undefined,
  appType: string | null | undefined,
): Promise<boolean> {
  const ids = candidateKindIds(kind, appType);
  if (ids.length === 0) return false;
  const results = await Promise.all(ids.map((id) => ensureKindLoaded(id)));
  return results.some(Boolean);
}
