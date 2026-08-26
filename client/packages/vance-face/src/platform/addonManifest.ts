/**
 * The one place that knows what `/face/addons` is and what it answers.
 *
 * `/face/addons` is a JSON list of the addons this deployment actually ships.
 * Production: written by the face Docker entrypoint from the brain at boot.
 * Development: produced by the `vanceAddonDevServe` middleware, which
 * path-scans `server/vance-addon-brain-<id>/client/dist/`.
 *
 * It is a static file served by the face host, not a brain endpoint — which is
 * why it goes through plain `fetch` rather than `@vance/shared`'s brain client
 * (no tenant, no JWT, no refresh). That exception is the reason to have exactly
 * one caller: four hand-rolled `fetch` blocks, each re-declaring its own slice
 * of the entry shape, is four chances to get the exception wrong.
 *
 * The single failure mode is "no addons": the file is missing, the host was
 * booted while the brain was down, or the JSON is unusable. Every consumer
 * treats that the same way — carry on with the built-ins — so it is answered
 * here with an empty list rather than an exception each caller must remember to
 * catch.
 */

/** The landing-page tile an addon declares in `vance-addon.yaml` `tile:`. */
export interface AddonTile {
  label?: string;
  description?: string;
  minLevel?: string;
}

/** The profile-page tab an addon declares in `vance-addon.yaml` `profile:`. */
export interface AddonProfile {
  label?: string;
  expose?: string;
  sortIndex?: number;
}

/** One installed addon, as `/face/addons` reports it. */
export interface AddonManifestEntry {
  /** Addon id — the `<id>` in `vance-addon-brain-<id>`. */
  name: string;
  path: string;
  tile?: AddonTile;
  profile?: AddonProfile;
  /**
   * Document-kind ids this addon's `./register` expose contributes, from
   * `vance-addon.yaml` `kinds:`. Absent for addons that contribute none.
   *
   * This is what lets the host skip loading remotes at boot: knowing which
   * addon owns `calendar` is enough to defer the fetch until a document of
   * that kind is opened. It is a load trigger, not a matching rule — the
   * addon's own `matches()` still decides once loaded.
   */
  kinds?: string[];
  /**
   * Load this addon at boot instead of when one of its kinds is opened.
   *
   * The escape hatch for contributions the host cannot trigger from a kind id
   * — a block-editor block is the known case, because the block list is read
   * when the Tiptap editor is *constructed* and the editor is shared across
   * six addons. Absent is the normal case.
   */
  eager?: boolean;
}

/** Module Federation remote name for an addon id. */
export function addonRemoteName(name: string): string {
  return `vance_addon_${name}`;
}

/** Module Federation remote entry URL for an addon id. */
export function addonRemoteEntry(name: string): string {
  return `/addons/${name}/remoteEntry.js`;
}

/**
 * The installed addons, or an empty list when the manifest is unreachable or
 * unusable. Never throws.
 */
export async function loadAddonManifest(): Promise<AddonManifestEntry[]> {
  try {
    const response = await fetch('/face/addons', {
      headers: { Accept: 'application/json' },
    });
    if (!response.ok) return [];
    const parsed = await response.json();
    return Array.isArray(parsed) ? (parsed as AddonManifestEntry[]) : [];
  } catch {
    return [];
  }
}
