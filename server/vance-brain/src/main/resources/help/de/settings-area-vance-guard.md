# Bereich: Guards

Die Shooty-Guard-Skripte unter `_vance/guards/` — **eine Datei pro
wiederverwendbarem Guard-Skript**. Das ist die **Bibliothek**, nicht
das Wiring: welches Recipe welchen Guard an welchem Punkt feuert,
steht im `guard:`-Block des Recipes, das den Pfad zitiert.

## Rohtext, bewusst

Ein Guard-Skript ist imperatives JS über der `vance.guard.*`-Fläche —
keine Form. Der Editor zeigt JS-Highlighting.

## Fallstricke

- **Kein Top-Level-`return`** — GraalJS lehnt es als Statement ab.
  An den fail-open-Punkten (stop/terminate/start) würde der
  SyntaxError still geschluckt: der Guard **läuft dann nie**. Early-Exits
  als if/else.
- Die Punkte sind nicht einheitlich: **stop/terminate/start** sind
  fail-open (Skriptfehler wird absorbiert), **command** fail-closed
  (Skriptfehler bricht den Command).
- Der Server prüft beim Speichern mit dem parse-only GraalJS-Check —
  ein Finding ist exakt das, was die Engine nicht evaluieren würde.
  Semantik (Bindings, Laufzeitfehler) bleibt Laufzeit-Thema.
