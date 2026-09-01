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
 *   minLevel: expert      # optional, defaults to every level
 * ```
 *
 * **`minLevel`** is the same knob as the landing tile's, for the same
 * reason: the tab and the tile are two doors to one addon, and gating
 * only one of them leaves the addon's name on a screen the reader has no
 * way into. Server-side authorization is untouched — this is a clutter
 * filter.
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
import { addonRemoteEntry, addonRemoteName, loadAddonManifest } from './addonManifest';
import { asUiLevel, getActiveUiLevel, rankOf } from './webUiSession';

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
  // Filtered before the remotes are registered, not after: a tab the reader
  // may not see must not cost the fetch that would render it.
  const level = rankOf(getActiveUiLevel());
  const contributors = (await loadAddonManifest()).filter(
    (a) => a.profile?.label && level >= rankOf(asUiLevel(a.profile.minLevel)),
  );
  if (contributors.length === 0) return [];

  try {
    registerRemotes(
      contributors.map((a) => ({
        name: addonRemoteName(a.name),
        entry: addonRemoteEntry(a.name),
        type: 'module' as const,
      })),
      { force: true },
    );
  } catch (e) {
    console.error('[loadProfileTabs] registerRemotes failed', e);
    return [];
  }

  // In parallel: the tabs are independent, and one slow remote must not add
  // its latency to every remote after it. The sort below restores the order,
  // so settle order carries no meaning.
  const settled = await Promise.allSettled(
    contributors.map(async (addon) => {
      const expose = addon.profile?.expose ?? DEFAULT_EXPOSE;
      const mod = await loadRemote<{ default?: Component } | Component>(
        `${addonRemoteName(addon.name)}/${expose.replace(/^\.\//, '')}`,
      );
      const resolved = (mod as { default?: Component })?.default ?? (mod as Component);
      if (!resolved) return null;
      return {
        id: addon.name,
        label: addon.profile!.label!,
        sortIndex: addon.profile?.sortIndex,
        component: markRaw(resolved),
      } satisfies ProfileTab;
    }),
  );

  const tabs: ProfileTab[] = [];
  settled.forEach((result, index) => {
    if (result.status === 'rejected') {
      // One addon's broken bundle must not cost the whole screen.
      console.warn(
        `[loadProfileTabs] '${contributors[index].name}' contributed no usable tab`,
        result.reason,
      );
      return;
    }
    if (result.value) tabs.push(result.value);
  });

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
