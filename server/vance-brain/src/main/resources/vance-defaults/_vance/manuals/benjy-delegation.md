---
triggers: benjy, orchestrate, iterative task, small model, decompose, benjy-coding, multi-step coding
summary: When to delegate an iterative task to Benjy (benjy-coding) instead of a plain coding worker — and what a good Benjy task looks like.
requires-tools: process_spawn
---

# Delegating to Benjy (iterative orchestration)

Benjy (`recipe: benjy-coding`) is an orchestration **engine**, not just
another worker. It decomposes your task into items, delegates each item
to a focused coding worker, verifies results mechanically (build/test
command), evaluates them against acceptance criteria it derives from
your task, and only closes DONE when a final reflection confirms the
goal was achieved.

## When to pick Benjy over a direct coding worker

Pick `benjy-coding` when **most** of these hold:

- The task is **iterative with a verifiable outcome**: "make the tests
  pass", "implement X and verify it builds", "refactor Y without
  breaking the test suite". A check command can decide right/wrong.
- The task has **multiple distinct items** or you cannot see all steps
  up front — Benjy adds items at run time as facts come in.
- You want **structured acceptance** back: the DONE report contains the
  goal, the acceptance criteria with pass/fail evidence, and the items
  worked — not just a worker's prose summary.
- The tenant runs on **small or local models** — Benjy keeps loop
  discipline in the engine, so a weak model can still complete a
  multi-step task.

Prefer a direct `coding` worker (Frankie) when the task is a single
focused change you expect one worker to finish in one or two turns —
a plain worker turn is cheaper than Benjy's controller calls. Prefer
`marvin` when the task needs a deep up-front plan tree rather than
incremental decomposition.

## What a good Benjy task looks like

```
process_spawn(
  recipe='benjy-coding',
  task='Create greet.py with greet(name) returning "Hello, <name>!",
        plus test_greet.py asserting greet("World") == "Hello, World!",
        greet("") == "Hello, !". Run the tests — everything must pass.')
```

- State the **goal**, not the method — Benjy decides the items.
- Include the **acceptance conditions** concretely ("tests pass",
  "the file imports cleanly") — Benjy turns them into checkable
  criteria everything is measured against.
- Name the **verify command** if the project has one (`python3 -m
  pytest`, `mvn test`). The bundled benjy-coding ships without a check
  command on purpose — a project pins `features.check.command` via a
  recipe override, or you pass it in the spawn `params`. Without a
  check command the chain still evaluates per item against criteria,
  but the mechanical ground truth is missing.

## What you get back

- The DONE `ProcessEvent` carries Benjy's **final report**: goal,
  acceptance criteria with evidence, items with status, and run
  counters (`summarizeForParent`). Relay it — it is written for
  consumption, not just forensics.
- While it runs, Benjy's process shows a **journal** (chat entries per
  task, ending in the open worklist) and a **todo list** — you can
  follow progress without steering.
- If Benjy **BLOCKED**s with a question, the report is the question —
  answer via `process_steer` on the Benjy process; it continues from
  exactly there.

## Steering mid-run

You can steer Benjy while it runs (it is `asyncSteer` — your lane is
never blocked): corrections and new directions become queue operations
and are weighed against the current state. A steer like "drop the
second part, focus on the migration" is legitimate input; Benjy
re-routes at the next branch.
