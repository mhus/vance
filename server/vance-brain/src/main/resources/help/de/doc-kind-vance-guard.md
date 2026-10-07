# Guard-Skript

Eine Datei unter `_vance/guards/<name>.js` — ein einzelnes
Shooty-Guard-Skript. Das Recipe verdrahtet es per Pfad:

```yaml
guard:
  - script: _vance/guards/<name>.js
    trigger: stop          # start | command | stop | terminate
    params: { … }         # im Skript als vance.params.*
    maxRounds: 2          # Cap am yield-Punkt
```

## Die Fläche

- `vance.guard.continueWith(prompt)` — stop/terminate: nochmal
  weiterarbeiten lassen (runden-gedeckelt).
- `vance.guard.activateSkill(name)` — jeden Punkt: Skill aktivieren.
- `vance.guard.setTurnPrompt(text)` — nur start: den System-Prompt
  des Turns ersetzen.
- `vance.guard.deny(reason)` — nur command: den Command verweigern.
- `vance.guard.loopValues` / `sessionValues` — Scratch-Speicher
  über Runden bzw. Sessions.
- `vance.llm`, `vance.tools`, `vance.log` — Umgebung je nach
  `allowTools`.

Ohne Aktion enden = der Guard ist durchgelassen.

## Fallstricke

- **Kein Top-Level-`return`** (GraalJS-Syntaxfehler, fail-open
  schluckt ihn) — if/else statt Early-Return.
- stop/terminate/start sind fail-open, command fail-closed.
- Das Skript-Timout (30 s) ist der Backstop an start und command.
