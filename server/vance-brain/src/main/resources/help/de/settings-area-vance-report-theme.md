# Bereich: Report-Themes

Die Report-Themes unter `_vance/report-themes/` — **eine CSS-Datei
pro Theme** für den PDF-Export (openhtmltopdf). Gewählt wird ein
Theme über den `theme`-Key im Frontmatter des Dokuments bzw. die
Recipe-/Export-Einstellungen.

## Rohtext, bewusst

Ein Theme ist CSS — Rohtext-Editor mit CSS-Highlighting, gleiche
Namens-Grammatik wie die Chat-Themes (`[a-z0-9-]+`).

## Achtung: das ist Druck-CSS

Der PDF-Renderer versteht ein **Teil-Subset** von CSS — keine
Animationen, kein JavaScript, kein flexbox-Feintuning. Schrift,
Rand, Farbe, Tabellen: das sind die Werkzeuge. Was der Renderer
nicht kennt, wird still ignoriert (fail-open auf `default.css`).
Deshalb gibt es hier bewusst keinen Server-Validator.

## Schichten

`default` → Theme → `css:`-Ref im Dokument — spätere Schichten
gewinnen. Projekt überschreibt Mandant überschreibt gebündelte
Themes.
