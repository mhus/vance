# Guard script

One file under `_vance/guards/<name>.js` — a single Shooty guard
script. The recipe wires it by path:

```yaml
guard:
  - script: _vance/guards/<name>.js
    trigger: stop          # start | command | stop | terminate
    params: { … }         # in the script as vance.params.*
    maxRounds: 2          # cap at the yield points
```

## The surface

- `vance.guard.continueWith(prompt)` — stop/terminate: keep the engine
  working (round-capped).
- `vance.guard.activateSkill(name)` — any point: activate a skill.
- `vance.guard.setTurnPrompt(text)` — start only: replace the turn's
  system prompt.
- `vance.guard.deny(reason)` — command only: veto the command.
- `vance.guard.loopValues` / `sessionValues` — scratch stores across
  rounds and sessions.
- `vance.llm`, `vance.tools`, `vance.log` — environment, depending on
  `allowTools`.

Ending without an action = the guard passes.

## Pitfalls

- **No top-level `return`** (a GraalJS syntax error that fail-open
  swallows) — use if/else instead of early returns.
- stop/terminate/start are fail-open, command is fail-closed.
- The script timeout (30 s) is the backstop at start and command.
