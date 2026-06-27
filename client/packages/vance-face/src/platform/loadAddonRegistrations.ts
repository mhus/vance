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

interface AddonEntry {
  name: string;
  path: string;
}

interface RegisterExpose {
  register?: () => void;
  default?: { register?: () => void };
}

export async function loadAddonRegistrations(): Promise<void> {
  let addons: AddonEntry[];
  try {
    const response = await fetch('/face/addons', {
      headers: { Accept: 'application/json' },
    });
    if (!response.ok) return;
    addons = (await response.json()) as AddonEntry[];
  } catch {
    // /face/addons unreachable or malformed — carry on with built-in
    // kinds only. The face entrypoint writes this file at boot; if
    // it's missing the brain was down during face boot.
    return;
  }

  if (addons.length === 0) return;

  // Step 1: register each addon as a Module Federation remote.
  // `type: 'module'` makes the host load the remoteEntry via
  // <script type="module"> — without it Vite's `import`-emitting
  // remoteEntry throws "Cannot use import statement outside a module".
  const remotes = addons.map((a) => ({
    name: `vance_addon_${a.name}`,
    entry: `/addons/${a.name}/remoteEntry.js`,
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
  for (const addon of addons) {
    const exposeId = `vance_addon_${addon.name}/register`;
    try {
      const mod = (await loadRemote<RegisterExpose>(exposeId)) ?? undefined;
      if (!mod) continue;
      if (typeof mod.register === 'function') {
        mod.register();
      } else if (typeof mod.default?.register === 'function') {
        mod.default.register();
      }
    } catch {
      // `./register` expose absent or threw — non-fatal.
    }
  }
}
