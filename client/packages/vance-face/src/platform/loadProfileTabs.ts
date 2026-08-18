/**
 * The profile screen's extension point.
 *
 * An addon that declares a `profile:` block in its
 * `META-INF/vance-addon.yaml` gets a tab on profile.html:
 *
 * ```yaml
 * profile:
 *   label: Store
 *   expose: ./profile     # optional, defaults to ./profile
 *   sortIndex: 50         # optional
 * ```
 *
 * **Why the manifest and not the remote.** The strip is built from
 * `/face/addons` alone, so the host knows the tabs before loading any
 * federation remote — the same reasoning as the landing tile. A remote
 * that fails to load costs its own tab and nothing else.
 *
 * **What belongs in a profile tab:** what a person owns rather than what
 * a project needs — which store account this installation is signed in
 * to, which roles that account has. Project-scoped surfaces belong in the
 * addon's own area, reached from the landing page.
 */

import { registerRemotes, loadRemote } from '@module-federation/runtime';
import { markRaw, type Component } from 'vue';

interface AddonEntry {
  name: string;
  path: string;
  profile?: { label?: string; expose?: string; sortIndex?: number };
}

/** One addon-contributed tab, ready to render. */
export interface ProfileTab {
  /** Addon id — doubles as the tab id and the URL hash fragment. */
  id: string;
  label: string;
  sortIndex?: number;
  component: Component;
}

const DEFAULT_EXPOSE = './profile';

/**
 * Fetch the addon list and load every contributed profile tab.
 *
 * Failures are per addon and never fatal: an addon that cannot be
 * reached simply contributes no tab, and the profile screen is a page a
 * person needs to be able to open when something else is broken.
 */
export async function loadProfileTabs(): Promise<ProfileTab[]> {
  let addons: AddonEntry[];
  try {
    const response = await fetch('/face/addons', { headers: { Accept: 'application/json' } });
    if (!response.ok) return [];
    addons = (await response.json()) as AddonEntry[];
  } catch {
    return [];
  }

  const contributors = addons.filter((a) => a.profile?.label);
  if (contributors.length === 0) return [];

  try {
    registerRemotes(
      contributors.map((a) => ({
        name: `vance_addon_${a.name}`,
        entry: `/addons/${a.name}/remoteEntry.js`,
        type: 'module' as const,
      })),
      { force: true },
    );
  } catch (e) {
    console.error('[loadProfileTabs] registerRemotes failed', e);
    return [];
  }

  const tabs: ProfileTab[] = [];
  for (const addon of contributors) {
    const expose = addon.profile?.expose ?? DEFAULT_EXPOSE;
    try {
      const mod = await loadRemote<{ default?: Component } | Component>(
        `vance_addon_${addon.name}/${expose.replace(/^\.\//, '')}`,
      );
      const resolved = (mod as { default?: Component })?.default ?? (mod as Component);
      if (!resolved) continue;
      tabs.push({
        id: addon.name,
        label: addon.profile!.label!,
        sortIndex: addon.profile?.sortIndex,
        component: markRaw(resolved),
      });
    } catch (e) {
      // One addon's broken bundle must not cost the whole screen.
      console.warn(`[loadProfileTabs] '${addon.name}' contributed no usable tab`, e);
    }
  }

  // Numbered first and in order, the rest alphabetically after them —
  // so an addon that cares can place itself and one that does not still
  // lands somewhere predictable.
  return tabs.sort((a, b) => {
    if (a.sortIndex != null && b.sortIndex != null) return a.sortIndex - b.sortIndex;
    if (a.sortIndex != null) return -1;
    if (b.sortIndex != null) return 1;
    return a.label.localeCompare(b.label);
  });
}
