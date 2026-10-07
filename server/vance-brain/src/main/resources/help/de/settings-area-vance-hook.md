# Bereich: Hooks

Die UrsaHooks-Ereignis-Hooks unter `_vance/hooks/` — **eine Datei pro
Hook**, und das **Event ist Teil des Pfades**:
`_vance/hooks/<event>/<name>.yaml`. Die Registry listet pro Event; ein
Hook existiert nur unter einem bekannten Event.

## Namen tragen Struktur

Der Create-Dialog nimmt `event/hook-name` — z.B.
`process.completed/notify-owner`. Event und Hook-Name werden
geprüft: das Event gegen die sieben bekannten Wire-Namen
(`process.completed`, `process.failed`, `inbox.item.created`,
`session.suspended`, `session.resumed`, `insight.saved`,
`relation.created`), der Hook-Name gegen die Loader-Grammatik
(klein, `_` und `-`, max 64 Zeichen).

## Rohtext, bewusst

Ein Hook-Body ist **genau eine TriggerAction** — `recipe:`, `script:`
oder `workflow:` — plus Lifecycle-Keys (`description`, `enabled`,
`timeout`, `tags`). Kein Formular, Rohtext-Editor.

## Fallstricke

- Das alte Schema (`type: js|llm`, `prompt:`/`model:`) wird
  **abgelehnt** — der Server meldet beim Speichern, was der Loader
  sonst still übergehen würde.
- Hooks **registrieren sich mit Projekt-Aktivierung**: der Bereich
  schreibt das Dokument, die Registry lädt es beim Bootstrap bzw.
  Refresh des Projekts.
