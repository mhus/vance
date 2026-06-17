export declare const DEFAULT_TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
export declare const DEFAULT_TILE_ATTRIBUTION = "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors";
export interface MapConfig {
    tileUrl: string;
    attribution: string;
}
/**
 * Resolve the active map config for the given project. Returns
 * the OSM defaults when {@code projectId} is empty or the cascade
 * carries no override. Cached per project for the page lifetime.
 */
export declare function loadMapConfig(projectId: string): Promise<MapConfig>;
//# sourceMappingURL=mapSettings.d.ts.map