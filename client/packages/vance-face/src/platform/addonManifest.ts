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
