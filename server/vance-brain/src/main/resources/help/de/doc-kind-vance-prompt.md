# Engine-Prompt-Override

Eine Datei unter `_vance/prompts/<name>.md` — der Override eines
gebündelten Engine-Prompts. Der Name **ist** die Identität: exakt der
Name des Classpath-Prompts (z.B. `arthur-prompt`), den du schattieren
willst. Plan-Mode-Varianten haben eigene Namen.

## Body

Markdown mit Pebble. Der Body ersetzt den gebündelten Prompt
**vollständig** — kein Anhängen, kein Mischen: was die Engine über
ihren Kontext weiß, steht in den Variablen, die das Original nutzt.

## Verhalten

- **Gleicher Name, innere Ebene gewinnt**: Projekt überschreibt
  Mandant überschreibt Classpath.
- **Pebble-Fehler brechen den Turn.** Der Server kompiliert beim
  Speichern — ein Syntaxfehler wird hier gemeldet, nicht erst beim
  ersten Spawn.
- **Leer überschreiben** ist ein Hinweis, kein Fehler: der Resolver
  fällt auf die gebündelte Version zurück.
