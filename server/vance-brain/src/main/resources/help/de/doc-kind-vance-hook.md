# Hook

Eine Datei unter `_vance/hooks/<event>/<name>.yaml` — ein
Ereignis-Hook. Das Event kommt aus dem Pfad (nicht aus dem Body),
der Hook-Name aus dem Dateinamen.

## Body

**Genau eine TriggerAction** am Top-Level:

- `recipe: <name>` — spawnt einen ThinkProcess.
- `workflow: <name>` — startet einen Magrathea-Workflow.
- `script: { … }` — läuft ein JS-Skript (Source/Path nach
  TriggerAction-Schema).

Dazu optional: `description`, `enabled` (Default true), `timeout`
(Sekunden, Default 5, max 30), `tags`.

## Verhalten

- Die Registry listet **pro Event** — ein Pfad unter einem
  unbekannten Event ist unsichtbar, ohne dass der Body je gelesen
  wird.
- Ein Body, der nicht parst, wird **still übersprungen** (WARN-Log):
  der Hook fehlt einfach. Der Server meldet dieselben Fehler beim
  Speichern — eher sehen als später suchen.
- Das alte Schema (`type: js|llm`) wird mit Migrationhinweis
  abgelehnt.
