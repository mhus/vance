# Bereich: Prompts

Die Engine-Prompt-Overrides unter `_vance/prompts/` — **eine Datei
pro überschriebenem Engine-Prompt**. Create ist der
**Override-Workflow**: der Name (z.B. `arthur-prompt`) schattiert den
gebündelten Classpath-Prompt by Name — gleiche Datei, eigene Ebene.
Jeder Spawn des Scopes bekommt dann deinen Text statt des
Originals.

## Rohtext, bewusst

Engine-Prompts sind Markdown mit Pebble-Variablen — der Rohtext-Editor
(Markdown-Highlighting) ist hier das ehrliche Werkzeug.

## Fallstricke

- Der Name muss **exakt** dem gebündelten Prompt entsprechen — ein
  Tippfehler erzeugt einen Prompt, den keine Engine je liest.
- Pebble-Syntaxfehler **brechen den Turn**, sie fallen nicht auf einen
  Fallback zurück. Der Server kompiliert den Body beim Speichern und
  meldet den Fehler vor dem ersten Spawn.
- Ein **leeres** Override ist erlaubt und gemeldet: der Resolver
  filtert leeren Inhalt, die gebündelte Version bleibt am Zug.
