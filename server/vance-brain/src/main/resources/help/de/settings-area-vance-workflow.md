# Bereich: Workflows

Die Magrathea-Workflows unter `_vance/workflows/` — **eine Datei pro
Workflow-Definition**: Zustände, Übergänge, Parameter und Budgets.
Das ist die **erste Verwaltungsfläche** für Definitionen — das
Magrathea-Admin (Insights → Workflows) inspiziert Läufe, nicht das,
was laufen soll.

## Ansicht

Der Eintrag öffnet den **State-Graph** inline: die Zustände als
Knoten, die Übergänge als Kanten, lesbar von oben nach unten (oder
_links nach rechts — Toggle oben rechts). Der Graph ist eine
**Karte**, kein Editor: Workflow-YAML wird als Rohtext editiert —
„Bearbeiten" wechselt in den Quelltext.

## Was eine Definition ausmacht

- `start:` — der Startzustand (muss in `states:` existieren).
- `states:` — mindestens einer; jeder hat einen `type:` (agent_task,
  shell_task, script_task, tool_task, condition, …) und type-
  spezifische Felder.
- `parameters:` — Caller-Parameter, in States als `${params.<key>}`.
- `bounds:` — Budget-Grenzen des Laufs (Kosten, Wanduhr, Spawns).

## Scopes

Projekt überschreibt Mandant überschreibt gebündelte Workflows:
gleicher Name gewinnt von innen. Gestartet wird über den
Workflow-Runner, Recipes oder Scheduler — nie von dieser Seite.
