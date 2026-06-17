// Map-tile configuration. The values come from the brain's
// project → `_vance` tenant cascade via `/settings/cascade`. The
// SPA caches per-project so a project switch loads fresh tile URL
// + attribution without a re-login.
//
// When the cascade returns nothing for a key (no setting at any
// scope), the Web-UI uses the OpenStreetMap public tile server
// with proper attribution. The OSM tile policy is met by the
// browser's Referer header (vance domain) for normal interactive
// viewing; large or commercial deployments should set
// `maps.tile.url` to a MapTiler / Stadia / self-hosted endpoint.
//
// See `specification/doc-kind-map.md` §5.4 / §5.5.

import { loadProjectCascadeSettings } from './projectSettings';

const KEY_TILE_URL = 'maps.tile.url';
const KEY_TILE_ATTRIBUTION = 'maps.tile.attribution';

const CASCADE_KEYS = [KEY_TILE_URL, KEY_TILE_ATTRIBUTION];

export const DEFAULT_TILE_URL =
  'https://tile.openstreetmap.org/{z}/{x}/{y}.png';

export const DEFAULT_TILE_ATTRIBUTION =
  '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors';

export interface MapConfig {
  tileUrl: string;
  attribution: string;
}

/**
 * Resolve the active map config for the given project. Returns
 * the OSM defaults when {@code projectId} is empty or the cascade
 * carries no override. Cached per project for the page lifetime.
 */
export async function loadMapConfig(projectId: string): Promise<MapConfig> {
  if (!projectId) {
    return {
      tileUrl: DEFAULT_TILE_URL,
      attribution: DEFAULT_TILE_ATTRIBUTION,
    };
  }
  const settings = await loadProjectCascadeSettings(projectId, CASCADE_KEYS);
  return {
    tileUrl: settings[KEY_TILE_URL] && settings[KEY_TILE_URL].length > 0
      ? settings[KEY_TILE_URL]
      : DEFAULT_TILE_URL,
    attribution: settings[KEY_TILE_ATTRIBUTION] && settings[KEY_TILE_ATTRIBUTION].length > 0
      ? settings[KEY_TILE_ATTRIBUTION]
      : DEFAULT_TILE_ATTRIBUTION,
  };
}
