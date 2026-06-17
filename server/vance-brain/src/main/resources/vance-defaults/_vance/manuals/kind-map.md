---
triggers: kind map, kind:map, geographic map, geo map, map document, OpenStreetMap, OSM, leaflet, lat lon, lat-lon, latitude longitude, WGS84, marker on map, geocoding, polygon area, polyline route, route between cities, Hamburg Berlin map, location pin, geographic visualization, geografische Karte, Landkarte, Karte mit Markern, Stadtteil, Stadtgrenze, Wegpunkte, "map" (geographic, NOT mindmap), Vance map kind
summary: Geographic map document with markers (points), areas (polygons) and routes (polylines) rendered with Leaflet on OpenStreetMap tiles. Distinct from kind mindmap which is a radial bullet hierarchy — this kind shows actual geography (Earth), with lat/lon coordinates and place names.
---
# Document kind — `map`

> **Not** `mindmap`. This kind renders a real geographic map (Earth,
> OpenStreetMap tiles, lat/lon) — markers, polygon areas, polyline
> routes. If the user said "Mindmap" / "brainstorm" / "radial outline"
> they want `manual_read('kind-mindmap')`. If the user said "Karte"
> / "map" / "Hamburg auf einer Karte" / "Route von X nach Y" they
> want **this** kind.

Three feature types cover the "show me a small map with some
annotations" use case:

- **markers** — single points (city, landmark, office)
- **areas** — closed polygons (district, region, zone)
- **routes** — ordered polylines (Hamburg → Berlin, walking tour)

Routes are **straight lines** between waypoints, **not road
routing**.

## Two storage forms

The data shape is identical; the wrapper decides whether Vance
renders the map inline in the chat or as a clickable document tab.

| Did the user ask for a saved file / document? | Use form |
|---|---|
| YES — "create a map document", "speicher die Karte", "save the map" | **Stored** (below) |
| NO — "show me a map", "zeig mir eine Karte von Hamburg", "map this out" | **Inline** (further below) |

### Inline in chat — fence-wrapped YAML

When the user wants to *see* the map right now in the assistant's
reply (no save, no `doc_create`), **emit a single
```` ```map ```` fence in the chat message** with YAML body.

````
```map
view:
  place: Hamburg
  zoom: 11
markers:
  - title: Altona
    lat: 53.5510
    lon: 9.9180
  - title: St. Pauli
    lat: 53.5570
    lon: 9.9650
routes:
  - title: "Hamburg → Berlin"
    waypoints:
      - place: Hamburg
      - place: Berlin
```
````

The reply must CONTAIN this fence verbatim — narrating "Hier ist
die Karte…" without the actual fenced block leaves the user with
no render.

### Stored document — YAML/JSON body, NO fence

When the user wants to *save* the map via
`doc_create(kind="map", path="<…>.yaml", content=<raw>)`, the content
is the raw YAML/JSON body — **no `` ```map `` fence wrapper**
(that's the inline-chat shape only).

```yaml
$meta:
  kind: map
view:
  place: Hamburg
  zoom: 11
markers:
  - name: altona
    title: Altona
    place: "Hamburg Altona, Germany"
    color: "#3b82f6"
areas:
  - name: hamburg
    title: Hamburg
    points:
      - { lat: 53.60, lon: 9.70 }
      - { lat: 53.60, lon: 10.30 }
      - { lat: 53.40, lon: 10.30 }
      - { lat: 53.40, lon: 9.70 }
    color: "#3b82f6"
    fillOpacity: 0.15
routes:
  - name: hh-berlin
    title: "Hamburg → Berlin"
    waypoints:
      - place: Hamburg
      - place: Berlin
    color: "#ef4444"
    width: 4
```

JSON form works identically, wrapped as
`{ "$meta": { "kind": "map" }, "view": {...}, "markers": [...], ... }`.
Markdown form is **not supported** — the codec rejects it.

## Specifying locations

Every position can be given two ways:

| Form | Use when |
|---|---|
| `place: "<name>"` | A well-known city / address / landmark. Server geocodes via Nominatim. |
| `lat: X, lon: Y` | You already have WGS84 coordinates. |

If both are set, explicit coords win. Unresolvable `place:` entries
get skipped with a warning under the map — the rest still renders.

**Prefer `place:` over invented coordinates.** Guessing coords for a
city you can name lands the marker in a random field. Use the place
name and let the geocoder do its job.

**Be specific in place names.** `"Hamburg"` returns Hamburg, the
city. `"Hamburg Altona"` returns the district. `"Hamburg, NJ"`
returns a place in New Jersey. Include `, <country>` if the name
is ambiguous.

## Field reference

**Marker** (`markers[]`):
- `name` — stable technical id; auto-derived from title if omitted
- `title` — display name in popups (alias: `label`)
- `place` OR (`lat` + `lon`) — required
- `color` — HTML hex (`#3b82f6`); enables circle-marker rendering
- `description` — single-line popup text

**Area** (`areas[]`):
- `name` — auto-derived if omitted
- `title` (alias: `label`)
- `points` (req, ≥ 3) — list of `{ place }` or `{ lat, lon }` objects
- `color`, `fillOpacity` (0–1, default 0.2)

**Route** (`routes[]`):
- `name` — auto-derived if omitted
- `title` (alias: `label`)
- `waypoints` (req, ≥ 2) — same shape as area points
- `color`, `width` (integer pixels, default 3)

**View** (`view`, optional, top-level):
- `place` OR `lat`+`lon` for initial center
- `zoom` integer 0–19 (0=world, 19=street)
- When omitted, the renderer fits to all feature bounds.

**Convention:** prefer `title:` over `label:`. The codec accepts
both (LLM ergonomics) but `title:` is the canonical field that
survives re-serialisation.

## `map` vs. `mindmap` — disambiguation

Common confusion because both contain "map" in the name. They are
**completely different** document kinds:

| | `kind: map` | `kind: mindmap` |
|---|---|---|
| What it shows | Earth — real geography with OpenStreetMap tiles | Radial bullet hierarchy |
| Data shape | Markers + Areas + Routes with lat/lon or place names | Tree of `items[]` with `text` + `children` |
| User intent | "show me Hamburg on a map", "route from X to Y" | "brainstorm ideas about X", "structure this radially" |
| Fence | ` ```map ` with YAML view/markers/areas/routes | ` ```mindmap ` with bullet markdown |
| Library | Leaflet + OpenStreetMap | markmap |

If the user says **"Karte"**, **"map"**, **"OpenStreetMap"**,
**"Route"**, **"Hamburg auf einer Karte"** → `kind: map` (this manual).

If the user says **"Mindmap"**, **"brainstorm"**, **"radial"**,
**"big picture"**, **"Ideenkarte"** → `kind: mindmap`
(`manual_read('kind-mindmap')`).

## `map` vs. `image`

For a one-shot screenshot of a map ("zeig ein Bild von Hamburg")
where the user does not need to interact (pan/zoom), use image
generation or a static URL — not this kind. `kind: map` is for
when the user wants an actual interactive map (or a saved,
editable artefact).

## Anti-patterns

- **Inventing coordinates for known cities.** Use `place: Hamburg`, not `lat: 53.5, lon: 9.9`. The geocoder is precise; your memory of city coords usually isn't.
- **Wrapping the stored body in a ```` ```map ```` fence.** That is the inline-chat form only. When saving via `doc_create`, the body is raw YAML/JSON — never fence-wrapped.
- **Using `route` for road navigation.** Routes are straight lines. "Drive Munich → Berlin via Leipzig" as a 3-waypoint route shows three line segments through forests, not the actual roads.
- **Markdown form.** Bodies with `kind: map` and a Markdown mime type are rejected by the codec; the Web-UI offers only the Raw editor. Use YAML or JSON.
- **Building areas with < 3 points.** Polygons need ≥ 3 vertices. If you want a single spot, use a marker.
- **Long `description` text.** Popups are one or two lines. For paragraphs, link to a separate document.
- **Confusing this with mindmap.** When in doubt: does the user expect to see Earth/geography? → map. Does the user expect to see a radial tree of ideas? → mindmap.

## When to graduate from inline to stored

- The map is meant to be edited later (the document editor lives on the saved doc, not on the chat fence).
- Multiple maps that belong together.
- The user explicitly said "speichern" / "save" / "create a document".

Then call `doc_create(kind="map", path="maps/<name>.yaml",
content=<raw YAML, NO fence>)` and embed the returned
`markdownLink`.

## Related

- Spec: `specification/doc-kind-map.md`
- `manual_read('kind-mindmap')` — radial bullet hierarchy (the other "map")
- `manual_read('kind-graph')` — abstract node/edge networks (no geography)
- `manual_read('doc-tools')` — generic document CRUD tools
