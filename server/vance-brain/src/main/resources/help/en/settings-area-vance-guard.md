# Area: Guards

The Shooty guard scripts under `_vance/guards/` — **one file per
reusable guard script**. This is the **library**, not the wiring:
which recipe fires which guard at which point lives in the recipe's
`guard:` block, which cites the path.

## Raw text, deliberately

A guard script is imperative JS over the `vance.guard.*` surface —
nothing to parse into a form. The editor shows JS highlighting.

## Pitfalls

- **No top-level `return`** — GraalJS rejects it as a statement. At
  the fail-open points (stop/terminate/start) the SyntaxError gets
  swallowed: the guard then **never runs**. Use if/else for early
  exits.
- The points disagree on failure: **stop/terminate/start** are
  fail-open (a script error is absorbed), **command** is fail-closed
  (a script error fails the command).
- The server checks on save with the parse-only GraalJS check — a
  finding is exactly what the engine would refuse to evaluate.
  Semantics (bindings, runtime errors) stay a runtime topic.
