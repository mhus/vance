# Bereich: Chat-Themes

Die Chat-Themes unter `_vance/chat-themes/` — **eine CSS-Datei pro
Theme** für das Chat-Transkript. Gewählt wird ein Theme über das
Recipe-Feld `webTheme` (kein UI-Schalter im Chat selbst): das Recipe,
das spawnt, trägt den Theme-Namen, die Session rendert damit.

## Rohtext, bewusst

Ein Theme ist CSS — Rohtext-Editor mit CSS-Highlighting. Der
Namens-Check beim Anlegen folgt der Resolver-Grammatik: nur
Kleinbuchstaben, Ziffern und Bindestriche (`[a-z0-9-]+`).

## Verhalten

- **Dark Mode ist Vertrag**: die Layer honorieren die
  Dark-Mode-Klassen der Shell — ein Theme, das sie ignoriert,
  leuchtet nachts.
- Die Pipeline ist **fail-open**: ein invalides Theme fällt still auf
  `default.css` zurück. Deshalb gibt es hier bewusst keinen
  Server-Validator — es gibt kein Finding, das die Runtime nicht selbst
  absorbiert hätte.
- Theme-Dateien tragen **keinen Kind-Marker** (CSS hat keinen Ort
  dafür) — die Identität liegt im Dateinamen und im `webTheme`-Feld
  des Recipes.
