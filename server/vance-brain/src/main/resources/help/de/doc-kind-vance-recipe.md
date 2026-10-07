# Recipe

Eine Datei unter `_vance/recipes/<name>.yaml` — ein benanntes
Konfigurationsbündel über einer Engine. Der Name der Datei ist der
Name, unter dem gespawnt wird (`recipe: <name>` in Prompts, Tools und
Scheduler-Definitionen).

## Kernfelder

- `title` — Anzeigename.
- `description` — **Pflicht.** Ein Satz, was das Recipe tut.
- `engine` — **Pflicht.** Die Engine, die es antreibt (`arthur`,
  `ford`, `vogon`, `marvin`, …). Eine unbekannte Engine macht das
  Recipe still nicht spawnbar.
- `listed: true` — zeigt das Recipe in der Auswahl; `false` versteckt
  es, spawnt aber weiter.
- `params` — Parameter-Schema (Map).
- `promptPrefix` — Pebble-Template, das dem Engine-Prompt vorangestellt
  wird. Syntaxfehler brechen jeden Spawn.

## Kaskade

Projekt überschreibt Mandant überschreibt Classpath: gleicher Name
gewinnt von innen. Ein Recipe hier zu überschreiben ist der Weg,
gebündeltes Verhalten projekt-lokal anzupassen, ohne das Original zu
ändern.
