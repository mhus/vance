# Report-Theme

Eine CSS-Datei unter `_vance/report-themes/<name>.css` — das Styling
des PDF-Exports, gewählt über den `theme`-Key des Dokuments. Der Name
folgt der Resolver-Grammatik (`[a-z0-9-]+`).

## Druck-CSS

Der Renderer (openhtmltopdf) interpretiert ein bewusstes Subset:
Seitenränder (`@page`), Schriftfamilien und -größen, Farben,
Tabellen-Styling, Überschriften. Kein JS, keine Animationen,
kein viewport-Layout — Papier hat kein Fenster.

## Schichten

1. `default.css` des Renderers,
2. das hier gewählte Theme,
3. ein `css:`-Ref im Dokument selbst.

Spätere Schichten gewinnen. Was eine Schicht nicht definiert, fällt
durch. Invalides CSS wird still ignoriert — gegenprüfen heißt hier:
ein Test-PDF rendern.
