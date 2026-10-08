---
triggers: nutrimat, loop experiment, loop lab, experiment recipe, compare loops, exhausted loop, judge loop, janx, redbull, clubmate, salitos, worker loop variant
summary: How to author a Nutrimat loop-experiment recipe — a worker recipe that pins one loop nature (janx / redbull / clubmate / salitos) with its own model, budget and prompt. Read this before writing a `_vance/recipes/<name>.yaml` experiment document.
---
# Nutrimat loop experiments

**Nutrimat** is Vance's experimental loop laboratory: one engine framework,
several loop types ("natures"). Each nature is a normal worker engine with the
same outside contract (one synchronous reply per turn, usable standalone or as
a spawned worker) — only the *loop policy* differs. A loop experiment is
therefore just a **recipe** that pins one nature plus its knobs.

## The natures

| Recipe / engine | The loop |
|---|---|
| `nutrimat-janx` | Natural-stop tool loop (the Ford baseline). Budget overrun carries the best partial work out as a hard-failure outcome. |
| `nutrimat-redbull` | Hard budget: exhaustion is a visible **error** (`exhausted`) — no continuation, no partial-work rescue. |
| `nutrimat-clubmate` | Exhausted loop **with a judge**: at exhaustion a cheap LLM call decides "fresh budget, keep going" vs. "synthesize the answer". |
| `nutrimat-salitos` | The stop is a **decision**: a judge checks every draft answer and says done vs. continue (`maxDecisions` caps the "continue" rounds). |

Comparisons are single-axis by design: `janx` vs. `redbull` measures the
exhaustion handling, `redbull` vs. `clubmate` measures the judge, `janx` vs.
`salitos` measures the stop decision. Keep everything else identical when you
tune two recipes against each other.

## Writing an experiment recipe

Create a document at `_vance/recipes/<name>.yaml` in the project (the normal
recipe cascade picks it up: project → tenant → bundled). Shape:

```yaml
title: "Loop experiment — <what you compare>"
listed: true
category: experimental
description: |
  One paragraph: which nature, which axis, what the experiment looks for.
engine: nutrimat-clubmate    # one of the four natures above
params:
  model: default:analyze,default:fast
  maxIterations: 10          # the loop's tool-iteration budget
  maxDecisions: 3            # salitos only: judge "continue" rounds per turn
promptPrefix: |
  Optional extra prompt text for THIS experiment (the shared Nutrimat base
  prompt is added automatically).
tags:
  - nutrimat
  - experimental
```

Rules of thumb:

- **One variable per experiment.** Same `model`, same prompt, same budget —
  change only what the experiment is about. Two recipes differing in one line
  are a readable A/B pair.
- **`maxIterations` is the exhaustion dial.** Small values (5–10) make the
  exhausted/judge paths fire quickly; large values (40) test real work.
- **Runtime tuning:** `//nutrimat set maxturns <n>` changes the budget of a
  live process (runtime override — the recipe stays the baseline, and
  `//nutrimat set maxturns` without a value restores it); `//nutrimat status`
  shows the effective budget, where it comes from, and the last turn's loop
  statistics.
- **Judge cost:** `clubmate` and `salitos` fire one cheap LLM call per decision
  (`nutrimat-judge-clubmate` / `nutrimat-judge-salitos`, both `internal: true` —
  never spawn those directly).
- **Spawn by explicit recipe name** — `process_spawn(recipe="<name>", …)` or
  picking the recipe in the session picker. There is no "current nutrimat"
  alias on purpose.
- Before creating, check for an existing experiment
  (`recipe_describe(name=...)`, `doc_list` on `_vance/recipes/`) — update it
  instead of writing a duplicate.

## Scope

Write experiments in the **project** unless the user explicitly wants them
tenant-wide (then the tenant's `_vance` project — that write is ADMIN-gated).
Never touch the bundled recipes (`nutrimat-janx` etc.): they are the fixed
baseline the experiments measure against.