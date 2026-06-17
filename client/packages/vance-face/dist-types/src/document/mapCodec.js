// Codec for `kind: map` documents — parses an on-disk body into a
// typed MapDocument and serializes it back. JSON and YAML only;
// markdown is intentionally not supported.
//
// Mirrors the Java MapCodec under
// `vance-shared/src/main/java/de/mhus/vance/shared/document/kind/MapCodec.java`.
// On-disk format is documented in `specification/doc-kind-map.md`.
//
// Position is carried flat on markers: `place` / `lat` / `lon` at
// the marker level, not nested under a `location:` sub-object. Area
// `points` and route `waypoints` are arrays of the same flat shape.
import { dumpYamlBody, parseYamlBody, unwrapJsonMeta, wrapJsonMeta, } from '@vance/shared';
export class MapCodecError extends Error {
    cause;
    constructor(message, cause) {
        super(message);
        this.cause = cause;
        this.name = 'MapCodecError';
    }
}
// ── MIME helpers ─────────────────────────────────────────────────────
function isJson(mime) {
    return mime === 'application/json';
}
function isYaml(mime) {
    return mime === 'application/yaml'
        || mime === 'application/x-yaml'
        || mime === 'text/yaml'
        || mime === 'text/x-yaml';
}
// ── Public API ───────────────────────────────────────────────────────
export function parseMap(body, mimeType) {
    if (isJson(mimeType))
        return parseMapJson(body);
    if (isYaml(mimeType))
        return parseMapYaml(body);
    throw new MapCodecError(`Unsupported mime type for map: ${mimeType}`);
}
export function serializeMap(doc, mimeType) {
    if (isJson(mimeType))
        return serializeMapJson(doc);
    if (isYaml(mimeType))
        return serializeMapYaml(doc);
    throw new MapCodecError(`Unsupported mime type for map: ${mimeType}`);
}
export function isMapMime(mimeType) {
    if (!mimeType)
        return false;
    return isJson(mimeType) || isYaml(mimeType);
}
/** True when the location resolves directly without geocoding. */
export function hasCoords(loc) {
    if (!loc)
        return false;
    return typeof loc.lat === 'number' && Number.isFinite(loc.lat)
        && typeof loc.lon === 'number' && Number.isFinite(loc.lon);
}
/** True when the location only carries a place name and needs to be
 *  geocoded before rendering. */
export function needsGeocode(loc) {
    if (!loc)
        return false;
    if (hasCoords(loc))
        return false;
    return typeof loc.place === 'string' && loc.place.trim().length > 0;
}
// ── JSON ─────────────────────────────────────────────────────────────
function parseMapJson(body) {
    if (body.trim() === '')
        return emptyDoc();
    let parsed;
    try {
        parsed = JSON.parse(body);
    }
    catch (e) {
        throw new MapCodecError('Invalid JSON: ' + (e instanceof Error ? e.message : String(e)), e);
    }
    if (!isObject(parsed)) {
        throw new MapCodecError('Top-level JSON must be an object');
    }
    return promoteToMapDocument(unwrapJsonMeta(parsed));
}
function serializeMapJson(doc) {
    const body = buildOnDiskBody(doc);
    return JSON.stringify(wrapJsonMeta(doc.kind || 'map', body), null, 2) + '\n';
}
// ── YAML ─────────────────────────────────────────────────────────────
function parseMapYaml(body) {
    if (body.trim() === '')
        return emptyDoc();
    let merged;
    try {
        merged = parseYamlBody(body);
    }
    catch (e) {
        throw new MapCodecError('Invalid YAML: ' + (e instanceof Error ? e.message : String(e)), e);
    }
    return promoteToMapDocument(merged);
}
function serializeMapYaml(doc) {
    return dumpYamlBody(doc.kind || 'map', buildOnDiskBody(doc));
}
// ── Promotion ────────────────────────────────────────────────────────
function emptyDoc() {
    return {
        kind: 'map',
        markers: [],
        areas: [],
        routes: [],
        extra: {},
    };
}
function promoteToMapDocument(obj) {
    const kind = typeof obj.kind === 'string' ? obj.kind : '';
    const view = promoteView(obj.view);
    const markers = promoteMarkers(obj.markers);
    const areas = promoteAreas(obj.areas);
    const routes = promoteRoutes(obj.routes);
    const { kind: _k, view: _v, markers: _m, areas: _a, routes: _r, ...extra } = obj;
    const doc = {
        kind: kind || 'map',
        markers,
        areas,
        routes,
        extra,
    };
    if (view)
        doc.view = view;
    return doc;
}
function promoteView(raw) {
    if (!isObject(raw))
        return undefined;
    const center = promoteLocation(raw);
    const zoom = promoteInt(raw.zoom);
    if (!center && zoom === undefined)
        return undefined;
    const view = {};
    if (center)
        view.center = center;
    if (zoom !== undefined)
        view.zoom = zoom;
    return view;
}
// `label` is accepted as a `title` alias on every feature — that's
// the field LLMs naturally reach for. Likewise, missing `name` is
// auto-derived from the title (slugified) so a feature isn't lost
// when the model only writes a display label.
const MARKER_RESERVED = new Set(['name', 'title', 'label', 'place', 'lat', 'lon', 'color', 'description']);
const AREA_RESERVED = new Set(['name', 'title', 'label', 'points', 'color', 'fillOpacity']);
const ROUTE_RESERVED = new Set(['name', 'title', 'label', 'waypoints', 'color', 'width']);
function readTitle(r) {
    return promoteString(r.title) ?? promoteString(r.label);
}
function autoName(kind, title, oneBasedIndex) {
    if (title && title.trim().length > 0) {
        const slug = title.trim().toLowerCase()
            .replace(/[^a-z0-9]+/g, '-')
            .replace(/^-+|-+$/g, '');
        if (slug.length > 0)
            return slug;
    }
    return `${kind}_${oneBasedIndex}`;
}
function promoteMarkers(raw) {
    if (!Array.isArray(raw))
        return [];
    const out = [];
    let index = 0;
    for (const r of raw) {
        index++;
        if (!isObject(r))
            continue;
        const loc = promoteLocation(r);
        if (!loc)
            continue;
        const title = readTitle(r);
        const name = promoteString(r.name) ?? autoName('marker', title, index);
        const marker = {
            name,
            location: loc,
            extra: {},
        };
        if (title)
            marker.title = title;
        const color = promoteString(r.color);
        if (color)
            marker.color = color;
        const description = promoteString(r.description);
        if (description)
            marker.description = description;
        for (const [k, v] of Object.entries(r)) {
            if (MARKER_RESERVED.has(k))
                continue;
            marker.extra[k] = v;
        }
        out.push(marker);
    }
    return out;
}
function promoteAreas(raw) {
    if (!Array.isArray(raw))
        return [];
    const out = [];
    let index = 0;
    for (const r of raw) {
        index++;
        if (!isObject(r))
            continue;
        const title = readTitle(r);
        const name = promoteString(r.name) ?? autoName('area', title, index);
        const area = {
            name,
            points: promoteLocationList(r.points),
            extra: {},
        };
        if (title)
            area.title = title;
        const color = promoteString(r.color);
        if (color)
            area.color = color;
        const fillOpacity = promoteDouble(r.fillOpacity);
        if (fillOpacity !== undefined)
            area.fillOpacity = fillOpacity;
        for (const [k, v] of Object.entries(r)) {
            if (AREA_RESERVED.has(k))
                continue;
            area.extra[k] = v;
        }
        out.push(area);
    }
    return out;
}
function promoteRoutes(raw) {
    if (!Array.isArray(raw))
        return [];
    const out = [];
    let index = 0;
    for (const r of raw) {
        index++;
        if (!isObject(r))
            continue;
        const title = readTitle(r);
        const name = promoteString(r.name) ?? autoName('route', title, index);
        const route = {
            name,
            waypoints: promoteLocationList(r.waypoints),
            extra: {},
        };
        if (title)
            route.title = title;
        const color = promoteString(r.color);
        if (color)
            route.color = color;
        const width = promoteInt(r.width);
        if (width !== undefined)
            route.width = width;
        for (const [k, v] of Object.entries(r)) {
            if (ROUTE_RESERVED.has(k))
                continue;
            route.extra[k] = v;
        }
        out.push(route);
    }
    return out;
}
function promoteLocationList(raw) {
    if (!Array.isArray(raw))
        return [];
    const out = [];
    for (const r of raw) {
        if (!isObject(r))
            continue;
        const loc = promoteLocation(r);
        if (loc)
            out.push(loc);
    }
    return out;
}
function promoteLocation(map) {
    const place = promoteString(map.place);
    const lat = promoteDouble(map.lat);
    const lon = promoteDouble(map.lon);
    if (!place && (lat === undefined || lon === undefined))
        return undefined;
    const loc = {};
    if (place)
        loc.place = place;
    if (lat !== undefined)
        loc.lat = lat;
    if (lon !== undefined)
        loc.lon = lon;
    return loc;
}
function promoteString(raw) {
    if (typeof raw !== 'string')
        return undefined;
    const trimmed = raw.trim();
    return trimmed.length === 0 ? undefined : trimmed;
}
function promoteDouble(raw) {
    if (typeof raw !== 'number' || !Number.isFinite(raw))
        return undefined;
    return raw;
}
function promoteInt(raw) {
    if (typeof raw !== 'number' || !Number.isFinite(raw))
        return undefined;
    return Math.round(raw);
}
// ── On-disk writer ───────────────────────────────────────────────────
function buildOnDiskBody(doc) {
    const body = {};
    if (doc.view) {
        const view = viewToObject(doc.view);
        if (Object.keys(view).length > 0)
            body.view = view;
    }
    body.markers = doc.markers.map(markerToObject);
    body.areas = doc.areas.map(areaToObject);
    body.routes = doc.routes.map(routeToObject);
    for (const [k, v] of Object.entries(doc.extra)) {
        if (!(k in body))
            body[k] = v;
    }
    return body;
}
function viewToObject(view) {
    const out = {};
    if (view.center)
        writeLocationInto(out, view.center);
    if (view.zoom !== undefined)
        out.zoom = view.zoom;
    return out;
}
function markerToObject(marker) {
    const obj = { name: marker.name };
    if (marker.title !== undefined)
        obj.title = marker.title;
    writeLocationInto(obj, marker.location);
    if (marker.color !== undefined)
        obj.color = marker.color;
    if (marker.description !== undefined)
        obj.description = marker.description;
    for (const [k, v] of Object.entries(marker.extra)) {
        if (!(k in obj))
            obj[k] = v;
    }
    return obj;
}
function areaToObject(area) {
    const obj = { name: area.name };
    if (area.title !== undefined)
        obj.title = area.title;
    obj.points = area.points.map(locationToObject);
    if (area.color !== undefined)
        obj.color = area.color;
    if (area.fillOpacity !== undefined)
        obj.fillOpacity = area.fillOpacity;
    for (const [k, v] of Object.entries(area.extra)) {
        if (!(k in obj))
            obj[k] = v;
    }
    return obj;
}
function routeToObject(route) {
    const obj = { name: route.name };
    if (route.title !== undefined)
        obj.title = route.title;
    obj.waypoints = route.waypoints.map(locationToObject);
    if (route.color !== undefined)
        obj.color = route.color;
    if (route.width !== undefined)
        obj.width = route.width;
    for (const [k, v] of Object.entries(route.extra)) {
        if (!(k in obj))
            obj[k] = v;
    }
    return obj;
}
function locationToObject(loc) {
    const obj = {};
    writeLocationInto(obj, loc);
    return obj;
}
function writeLocationInto(obj, loc) {
    if (loc.place !== undefined)
        obj.place = loc.place;
    if (loc.lat !== undefined && loc.lon !== undefined) {
        obj.lat = loc.lat;
        obj.lon = loc.lon;
    }
}
function isObject(value) {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}
//# sourceMappingURL=mapCodec.js.map