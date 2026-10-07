# Bereich: Recipes

Die Recipes unter `_vance/recipes/` — **eine Datei pro benanntem
Konfigurationsbündel**: Engine, Parameter, Prompts und
Tool-Feinheiten. Gespawnt wird immer per Recipe-Name; der Dateiname
(ohne `.yaml`) ist der Name. Das ist die **erste Verwaltungsfläche**
für Recipes — vor ihr gab es nur die YAML-Datei selbst.

## Rohtext, bewusst

Recipe-YAML ist die komplexeste Konfiguration im System: Prompts mit
Pebble-Variablen, Parameter-Schemata, Tool-Adjustments, Guards,
Profile. Kein Formular könnte das abbilden, ohne die Hälfte zu
verschweigen — deshalb der Rohtext-Editor mit YAML-Highlighting.

## Was der Loader verlangt

- **`description`** und **`engine`** sind Pflicht — ohne beide ist das
  Recipe **still nicht spawmbar** (der Loader loggt WARN und
  übergeht es). Der Server meldet genau das beim Speichern als
  Fehler.
- `promptPrefix` ist Pebble — ein Syntaxfehler macht das Recipe
  unbrauchbar und wird ebenfalls als Fehler gemeldet.
- `params` muss eine Map sein.

## Scopes

Projekt überschreibt Mandant überschreibt gebündelte Classpath-Recipes:
gleiches Name gewinnt von innen. Die Liste zeigt nur die flachen
Dateien — die Arbeitsordner des Slart-Architekten (`_user/`, `_slart/`)
bleiben außen vor.
