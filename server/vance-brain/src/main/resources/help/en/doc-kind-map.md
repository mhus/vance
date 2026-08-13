# Map

Geographic features on a map: **markers** (points), **areas**
(polygons) and **routes** (lines through waypoints). Rendered with
OpenStreetMap tiles.

For travel planning, location overviews, route sketches. Not a GIS
tool — no layers, no elevation, no live tracks. Routes are straight
lines between waypoints, **not** road routing.

**JSON and YAML only** — no markdown form.

## Places or coordinates

Every position accepts either form:

- `place: "Hamburg Altona, Germany"` — resolved to coordinates by the
  server. Easy to write, easy for an agent to produce.
- `lat: 53.5570` / `lon: 9.9650` — unambiguous.

Set both and the explicit coordinates win. Use a place name while
drafting, pin the coordinates once the spot matters.

## On disk

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
    description: District in the west
  - name: stpauli
    title: St. Pauli
    lat: 53.5570
    lon: 9.9650
areas:
  - name: hamburg
    title: Hamburg
    points:
      - { lat: 53.60, lon: 9.70 }
      - { lat: 53.60, lon: 10.20 }
      - { lat: 53.45, lon: 10.20 }
routes:
  - name: tour
    title: Walking tour
    points:
      - { place: "Landungsbrücken, Hamburg" }
      - { lat: 53.5570, lon: 9.9650 }
```

`view` sets the initial centre and zoom. `name` is the internal
identity, `title` is what gets labelled.

## Geocoding

Place names are resolved server-side and cached. An unresolvable name
leaves that feature off the map and reports it — check spelling and
add the country, or fall back to coordinates.
