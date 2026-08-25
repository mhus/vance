/**
 * Bootstrap step that drives addon side-effect registration — fully
 * runtime-driven, no static mappings.
 *
 * 1. Fetch {@code /face/addons} — a JSON list of installed addon ids.
 *    Production: written by the face Docker entrypoint from the brain at
 *    boot. Development: produced by the {@code vanceAddonDevServe}
 *    middleware which path-scans
 *    `server/vance-addon-brain-<id>/client/dist/`.
 * 2. Register each addon as a Module Federation remote via
 *    {@code registerRemotes()}. The remote name is
 *    {@code vance_addon_<id>}, the entry URL is
 *    {@code /addons/<id>/remoteEntry.js} — convention shared with the
 *    dev-server middleware and the production nginx symlink layout.
 * 3. {@code loadRemote('vance_addon_<id>/register')} and invoke the
 *    optional {@code register()} expose. Addons without that expose
 *    (no Kind / Hook contributions) silently skip — the rejection is
 *    non-fatal.
 *
 * No more rebuild-on-new-addon: adding a new addon means dropping its
 * {@code client/dist/} under the right path. The host needs no edit.
 */

import { registerRemotes, loadRemote } from '@module-federation/runtime';
import { addonRemoteEntry, addonRemoteName, loadAddonManifest } from './addonManifest';

interface RegisterExpose {
  register?: () => void;
  default?: { register?: () => void };
}

export async function loadAddonRegistrations(): Promise<void> {
  // An unreachable or malformed manifest reads as "no addons" — carry on with
  // built-in kinds only. The face entrypoint writes the file at boot; if it is
  // missing the brain was down during face boot.
  const addons = await loadAddonManifest();
  if (addons.length === 0) return;

  // Step 1: register each addon as a Module Federation remote.
  // `type: 'module'` makes the host load the remoteEntry via
  // <script type="module"> — without it Vite's `import`-emitting
  // remoteEntry throws "Cannot use import statement outside a module".
  const remotes = addons.map((a) => ({
    name: addonRemoteName(a.name),
    entry: addonRemoteEntry(a.name),
    type: 'module' as const,
  }));
  try {
    registerRemotes(remotes, { force: true });
  } catch (e) {
    console.error('[loadAddonRegistrations] registerRemotes failed', e);
    return;
  }

  // Step 2: pull the optional `./register` expose from each remote and
  // invoke it. Missing exposes are silent — slideshow-style addons that
  // only contribute an editor without Kind registration don't ship one.
  //
  // Fetching is parallel, invoking is not. This used to be one `for` loop
  // with the `await` inside it, which made 21 addons load strictly one after
  // another: each remoteEntry.js plus its register chunk is a round trip, and
  // on a WAN link that measured as 2.3 s of page-switch latency at a request
  // parallelism of 1.1 (browser resource timing on eddie, 2026-08-25). The
  // requests never depended on each other — the serialization was accidental.
  //
  // `register()` still runs in manifest order, deliberately. It writes into
  // shared registries (Kinds, Hooks), so completion order would decide which
  // addon wins a collision — a race that only shows up once two addons claim
  // the same kind, and then only sometimes. Loading concurrently and applying
  // in order costs nothing and keeps that question closed.
  const loaded = await Promise.all(
    addons.map(async (addon) => {
      const exposeId = `${addonRemoteName(addon.name)}/register`;
      try {
        return (await loadRemote<RegisterExpose>(exposeId)) ?? undefined;
      } catch {
        // `./register` expose absent or threw — non-fatal, same as before.
        return undefined;
      }
    }),
  );

  for (const mod of loaded) {
    if (!mod) continue;
    try {
      if (typeof mod.register === 'function') {
        mod.register();
      } else if (typeof mod.default?.register === 'function') {
        mod.default.register();
      }
    } catch {
      // A throwing register() must not take the remaining addons down.
    }
  }
}
