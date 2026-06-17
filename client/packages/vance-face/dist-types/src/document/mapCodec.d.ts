export interface MapLocation {
    place?: string;
    lat?: number;
    lon?: number;
}
export interface MapMarker {
    name: string;
    title?: string;
    location: MapLocation;
    color?: string;
    description?: string;
    /** Unknown per-marker fields, preserved across round-trip. */
    extra: Record<string, unknown>;
}
export interface MapArea {
    name: string;
    title?: string;
    points: MapLocation[];
    color?: string;
    fillOpacity?: number;
    extra: Record<string, unknown>;
}
export interface MapRoute {
    name: string;
    title?: string;
    waypoints: MapLocation[];
    color?: string;
    width?: number;
    extra: Record<string, unknown>;
}
export interface MapView {
    center?: MapLocation;
    zoom?: number;
}
export interface MapDocument {
    kind: string;
    view?: MapView;
    markers: MapMarker[];
    areas: MapArea[];
    routes: MapRoute[];
    extra: Record<string, unknown>;
}
export declare class MapCodecError extends Error {
    readonly cause?: unknown;
    constructor(message: string, cause?: unknown);
}
export declare function parseMap(body: string, mimeType: string): MapDocument;
export declare function serializeMap(doc: MapDocument, mimeType: string): string;
export declare function isMapMime(mimeType: string | null | undefined): boolean;
/** True when the location resolves directly without geocoding. */
export declare function hasCoords(loc: MapLocation | undefined): boolean;
/** True when the location only carries a place name and needs to be
 *  geocoded before rendering. */
export declare function needsGeocode(loc: MapLocation | undefined): boolean;
//# sourceMappingURL=mapCodec.d.ts.map