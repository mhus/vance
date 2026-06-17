<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';
import type { FenceMeta } from '@/kindRenderers/parseFenceLang';
import {
  type MapArea,
  type MapDocument,
  type MapLocation,
  type MapMarker,
  type MapRoute,
  hasCoords,
  needsGeocode,
  parseMap,
} from './mapCodec';
import { geocodePlace } from './mapGeocode';
import { loadMapConfig } from '@/platform/mapSettings';
import { useDocumentRefStore } from './documentRefStore';

/**
 * Renderer for `kind: map` documents — Leaflet-backed OpenStreetMap
 * viewer that draws markers (points), areas (polygons) and routes
 * (polylines).
 *
 * Three modes:
 *   - `editor`   — same as embedded for v1: full read-only canvas
 *                  inside the document editor. Interactive editing
 *                  (drag-to-place, polygon-draw) is a later layer.
 *   - `inline`   — read-only view from a fence body (YAML/JSON).
 *   - `embedded` — read-only view from a loaded {@link DocumentDto}.
 *
 * `place:`-only entries are resolved lazily via the brain's
 * `/geocode` endpoint; entries that can neither be resolved nor
 * already carry coords are dropped with a warning in the panel.
 *
 * Spec: `specification/doc-kind-map.md`.
 */
defineOptions({ name: 'MapView' });

const props = withDefaults(defineProps<{
  mode?: 'editor' | 'inline' | 'embedded';
  doc?: MapDocument;
  content?: string;
  meta?: FenceMeta;
  document?: DocumentDto;
  embedRef?: EmbedRef;
}>(), {
  mode: 'editor',
  meta: () => ({}),
});

const documentRefStore = useDocumentRefStore();

const resolvedDoc = computed<MapDocument>(() => {
  if (props.mode === 'editor') {
    return props.doc ?? emptyMapDoc();
  }
  if (props.mode === 'inline') {
    const body = props.content ?? '';
    if (!body.trim()) return emptyMapDoc();
    const mime = body.trimStart().startsWith('{')
      ? 'application/json'
      : 'application/yaml';
    try {
      return parseMap(body, mime);
    } catch (e) {
      console.warn('MapView: failed to parse inline content', e);
      return emptyMapDoc();
    }
  }
  const d = props.document;
  if (!d || !d.inlineText) return emptyMapDoc();
  const mime = d.mimeType ?? 'application/json';
  try {
    return parseMap(d.inlineText, mime);
  } catch (e) {
    console.warn('MapView: failed to parse embedded document', e);
    return emptyMapDoc();
  }
});

function emptyMapDoc(): MapDocument {
  return { kind: 'map', markers: [], areas: [], routes: [], extra: {} };
}

// ── Leaflet lifecycle ───────────────────────────────────────────────

const mapContainer = ref<HTMLDivElement | null>(null);
const unresolved = ref<string[]>([]);
let leafletMap: L.Map | null = null;
const featureLayer = L.layerGroup();
/** Generation counter — increments on every resolved-doc change so
 *  late-arriving geocode resolutions can detect they're stale and
 *  skip the redraw. Without this, a slow Nominatim response for a
 *  previous doc could overwrite features of the new doc. */
let renderToken = 0;

onMounted(async () => {
  if (!mapContainer.value) return;
  // Load tile config for the active project before Leaflet boots —
  // a project may override the OSM defaults via `maps.tile.url` and
  // we don't want a quick swap-out flicker after the user already
  // sees the map. The promise is per-project cached, so subsequent
  // map mounts in the same project are instantaneous.
  const projectId = documentRefStore.currentProject;
  const config = await loadMapConfig(projectId);
  if (!mapContainer.value) return; // disposed during await
  leafletMap = L.map(mapContainer.value, {
    zoomControl: true,
    attributionControl: true,
  });
  L.tileLayer(config.tileUrl, {
    maxZoom: 19,
    attribution: config.attribution,
  }).addTo(leafletMap);
  featureLayer.addTo(leafletMap);
  redraw();
});

onBeforeUnmount(() => {
  if (leafletMap) {
    leafletMap.remove();
    leafletMap = null;
  }
});

watch(resolvedDoc, () => {
  redraw();
});

// ── Drawing ─────────────────────────────────────────────────────────

async function redraw(): Promise<void> {
  if (!leafletMap) return;
  const token = ++renderToken;
  featureLayer.clearLayers();
  unresolved.value = [];

  const doc = resolvedDoc.value;
  const bounds = L.latLngBounds([]);

  for (const marker of doc.markers) {
    const coords = await resolveLocation(marker.location);
    if (token !== renderToken) return;
    if (!coords) {
      unresolved.value.push(marker.name);
      continue;
    }
    drawMarker(marker, coords);
    bounds.extend(coords);
  }

  for (const area of doc.areas) {
    const ring = await resolveAll(area.points);
    if (token !== renderToken) return;
    if (ring.length < 3) {
      if (area.points.length > ring.length) {
        unresolved.value.push(area.name);
      }
      continue;
    }
    drawArea(area, ring);
    ring.forEach((p) => bounds.extend(p));
  }

  for (const route of doc.routes) {
    const path = await resolveAll(route.waypoints);
    if (token !== renderToken) return;
    if (path.length < 2) {
      if (route.waypoints.length > path.length) {
        unresolved.value.push(route.name);
      }
      continue;
    }
    drawRoute(route, path);
    path.forEach((p) => bounds.extend(p));
  }

  // Apply initial viewport. Explicit `view.center` wins; otherwise
  // fit to feature bounds; otherwise fall back to a world view so
  // the user sees something rather than a grey panel.
  if (doc.view?.center) {
    const explicitCenter = await resolveLocation(doc.view.center);
    if (token !== renderToken) return;
    if (explicitCenter) {
      leafletMap.setView(explicitCenter, doc.view.zoom ?? 10);
      return;
    }
  }
  if (bounds.isValid()) {
    leafletMap.fitBounds(bounds, { padding: [24, 24], maxZoom: 14 });
  } else {
    leafletMap.setView([20, 0], 2);
  }
}

function drawMarker(marker: MapMarker, coords: L.LatLngTuple): void {
  const m = L.marker(coords);
  const popupLines: string[] = [];
  popupLines.push(`<strong>${escapeHtml(marker.title ?? marker.name)}</strong>`);
  if (marker.description) {
    popupLines.push(escapeHtml(marker.description));
  }
  m.bindPopup(popupLines.join('<br>'));
  if (marker.title) m.bindTooltip(marker.title);
  if (marker.color) {
    // Built-in marker icons are sprite-based — for color tinting we
    // swap to a circleMarker which honours `color`/`fillColor` cheaply.
    const circle = L.circleMarker(coords, {
      radius: 9,
      color: marker.color,
      fillColor: marker.color,
      fillOpacity: 0.85,
      weight: 2,
    });
    circle.bindPopup(popupLines.join('<br>'));
    if (marker.title) circle.bindTooltip(marker.title);
    featureLayer.addLayer(circle);
    return;
  }
  featureLayer.addLayer(m);
}

function drawArea(area: MapArea, ring: L.LatLngTuple[]): void {
  const poly = L.polygon(ring, {
    color: area.color ?? '#3b82f6',
    fillColor: area.color ?? '#3b82f6',
    fillOpacity: area.fillOpacity ?? 0.2,
    weight: 2,
  });
  if (area.title) poly.bindTooltip(area.title);
  poly.bindPopup(`<strong>${escapeHtml(area.title ?? area.name)}</strong>`);
  featureLayer.addLayer(poly);
}

function drawRoute(route: MapRoute, path: L.LatLngTuple[]): void {
  const line = L.polyline(path, {
    color: route.color ?? '#ef4444',
    weight: route.width ?? 3,
    opacity: 0.85,
  });
  if (route.title) line.bindTooltip(route.title);
  line.bindPopup(`<strong>${escapeHtml(route.title ?? route.name)}</strong>`);
  featureLayer.addLayer(line);
}

// ── Location resolution ─────────────────────────────────────────────

async function resolveLocation(loc: MapLocation): Promise<L.LatLngTuple | null> {
  if (hasCoords(loc)) {
    return [loc.lat as number, loc.lon as number];
  }
  if (needsGeocode(loc)) {
    const result = await geocodePlace(loc.place as string);
    if (result) return [result.lat, result.lon];
  }
  return null;
}

async function resolveAll(locations: MapLocation[]): Promise<L.LatLngTuple[]> {
  const out: L.LatLngTuple[] = [];
  for (const loc of locations) {
    const c = await resolveLocation(loc);
    if (c) out.push(c);
  }
  return out;
}

// ── Helpers ─────────────────────────────────────────────────────────

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}
</script>

<template>
  <div :class="['map-view', `map-view--${mode}`]">
    <div ref="mapContainer" class="map-canvas" />
    <p v-if="unresolved.length > 0" class="map-warning">
      Could not resolve location for: {{ unresolved.join(', ') }}
    </p>
  </div>
</template>

<style scoped>
.map-view {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
  width: 100%;
}
.map-canvas {
  width: 100%;
  height: 65vh;
  min-height: 420px;
  border: 1px solid hsl(var(--bc) / 0.15);
  border-radius: 0.5rem;
  overflow: hidden;
  background: hsl(var(--b1));
}
.map-view--inline .map-canvas {
  height: 22rem;
  min-height: 16rem;
}
.map-view--embedded .map-canvas {
  height: 50vh;
  min-height: 22rem;
}
.map-warning {
  font-size: 0.8rem;
  color: hsl(var(--wa));
  margin: 0;
}
</style>

<style>
/* Leaflet's CSS targets `.leaflet-container` globally. Scoping it
   inside `<style scoped>` would prevent Leaflet's runtime DOM (added
   via `L.map(el)`) from picking up the variable substitutions. */
.leaflet-container {
  font-family: inherit;
  font-size: 0.85rem;
}
</style>
