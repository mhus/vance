---
triggers: nutrimat, loop experiment, loop lab, experiment recipe, compare loops, exhausted loop, judge loop, janx, redbull, clubmate, absint, salitos, worker loop variant
summary: How to author a Nutrimat loop-experiment recipe — a worker recipe that pins one loop nature (janx / redbull / clubmate / absint / salitos) with its own model, budget and prompt. Read this before writing a `_vance/recipes/<name>.yaml` experiment document.
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
| `nutrimat-janx` | The reference: the Ford worker loop — natural stop, no routine round cap, safety nets for a stuck loop (repeated tool call, empty reply, model failure, per-turn time limit). A worker that hits a net reports the failure and closes; a chat stops with a "continue" offer. |
| `nutrimat-redbull` | Hard budget: reaching `maxIterations` is a visible **error** (`exhausted`) — no continuation, no partial-work rescue. |
| `nutrimat-clubmate` | Budget **with a judge**: at exhaustion a cheap LLM call looks at the work (text and tool calls) and decides "fresh budget, keep going" vs. "synthesize the answer". |
| `nutrimat-absint` | Every stop is an **account the loop gives itself**: the model must answer with a JSON `{"report": "…"}` — what it did and what came out. The report is the reply, recorded in the loop state and notified to the client. |
| `nutrimat-salitos` | Every stop is a **decision the loop makes itself**: the model must answer with a JSON `{"done": true/false, "reason": "…", "answer": "…"}`; "not done" sends it back to work with its own reason. Endless by design — no round or decision cap. Every decision is recorded and notified. |

Every nature owns its own loop; `janx` is the reference. A comparison
between two natures measures their whole loops — `redbull` vs. `clubmate`
isolates the judge best, `salitos` vs. `absint` the framing (decision vs.
account). Keep everything else identical when you tune two recipes against
each other.

## Writing an experiment recipe

Create a document at `_vance/recipes/<name>.yaml` in the project (the normal
recipe cascade picks it up: project → tenant → bundled). Shape:

```yaml
title: "Loop experiment — <what you compare>"
listed: true
category: experimental
description: |
  One paragraph: which nature, which axis, what the experiment looks for.
engine: nutrimat-clubmate    # one of the five natures above
params:
  model: default:analyze,default:fast
  maxIterations: 10          # redbull/clubmate: the round budget; janx: opt-in cap
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
- **`maxIterations` is the budget natures' exhaustion dial** (redbull,
  clubmate). Small values (5–10) make the exhausted/judge paths fire
  quickly; large values (40) test real work. absint and salitos have no
  cap at all — they run until the model stops (salitos: until it says
  done); ESC always interrupts. For `janx` it is an opt-in hard cap; its
  own knobs are `maxWallclockMinutes` (default 60) and
  `idleStuckThreshold` (default 5).
- **Runtime tuning:** `//nutrimat set maxturns <n>` changes the budget of a
  live budget-nature process (runtime override — the recipe stays the
  baseline, `//nutrimat set maxturns` without a value restores it; a
  budget-less nature rejects the knob); `//nutrimat status` shows the
  effective budget, where it comes from, and the last turn's loop
  statistics.
- **Judge cost:** `clubmate` fires one cheap LLM call per exhaustion
  (`nutrimat-judge-clubmate`, `internal: true` — never spawn it directly).
  `salitos` and `absint` need no judge: the loop's own JSON answer is the
  decision / the account.
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