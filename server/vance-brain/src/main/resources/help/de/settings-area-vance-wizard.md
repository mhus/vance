# Bereich: Wizards

Die Wizard-Definitionen unter `_vance/wizards/` — **eine Datei pro
Formular**, das einen Prompt generiert. Der **einzige Bereich mit
User-Layer**: die Cascade ist `project → _user_<login> → _vance →
classpath`, und der User-Scope des Panels ist genau die `_user_`-Ebene
— hier baust du **deine eigenen** Wizards, ohne den Mandanten zu
berühren.

## Rohtext, bewusst

Ein Wizard ist lokalisiertes `title`/`description`, eine
Field-Liste (das gemeinsame Form-Feld-Vokabular), ein Pebble-
`promptTemplate` und optional `validatorPrompt` und
`suggestedFollowUps`. Rohtext-Editor mit YAML-Highlighting.

## Was der Loader verlangt

- `title` und `description` — lokalisiert (oder Plain-String, der
  als `en` zählt).
- **mindestens ein** Feld in `fields`.
- `promptTemplate` — Pflicht, Pebble-kompilierend.
- `availableIn` — optionale Glob-Liste; `!pattern` schließt aus.

Ein Wizard, der das nicht erfüllt, verschwindet **still aus jeder
Fläche** (WARN im Log) — der Server meldet dasselbe beim Speichern.

## Namens-Kaskade

Gleicher Name gewinnt von innen: dein `_user_`-Wizard schattiert den
Mandanten-Wizard, der Projekt-Wizard schattiert beide.
