# Wizard

Eine Datei unter `_vance/wizards/<name>.yaml` — ein Formular, das
einen Prompt generiert. Der Dateiname ist der Name, unter dem der
Wizard überall auftaucht (Chat-Wizard-Tab, `POST /wizards/<name>/render`).

## Schema

- `title` / `description` — lokalisiert (`de:`/`en:`) oder Plain-String
  (= `en`). Pflicht.
- `icon`, `category` — Anzeige im Wizard-Tab.
- `fields` — **mindestens eines.** Das gemeinsame Feld-Vokabular:
  `name`, `type` (string, textarea, number, choice, …), `label`,
  `help`, `required`, Typ-Extras wie `rows` oder `options`.
- `promptTemplate` — **Pflicht**, Pebble. Rendert die Field-Werte,
  z.B. `{{ subject }}`.
- `validatorPrompt` — optional; LLM-Validierung der Eingaben.
- `suggestedFollowUps` — optionale Folge-Vorschläge.
- `availableIn` — Glob-Liste der Projekte; `!pattern` schließt aus.

## Fallstricke

- Ein fehlendes `promptTemplate`, leere `fields` oder kaputtes Pebble
  lassen den Wizard **still verschwinden** — der Server meldet es
  beim Speichern.
- Gleicher Name gewinnt von innen: `_user` vor Projekt vor `_vance`
  vor Classpath.
