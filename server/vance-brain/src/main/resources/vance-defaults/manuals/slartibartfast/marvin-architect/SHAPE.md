# Marvin Recipe — Shape

This manual describes what a Marvin Recipe is structurally.
Slartibartfast's GATHERING ingests it as engine-bundled evidence
so DECOMPOSING can tie subgoals to concrete claims even when no
project-specific kit is installed.

## What is a Marvin Recipe

A Marvin Recipe defines a **task-decomposition pipeline**: a
PLAN node receives a goal, emits N children of declared kinds
(WORKER / EXPAND_FROM_DOC / AGGREGATE), each child can in turn
trigger further sub-decompositions. The recipe constrains what
the PLAN-LLM may emit so the runtime stays bounded.

## Mandatory recipe fields

A Marvin recipe MUST declare:

- `name`: kebab-case identifier.
- `engine: marvin`
- `params.rootTaskKind: PLAN`
- `promptPrefix`: a non-blank string — the PLAN-LLM instruction.
  This is a Pebble template (tier/model/mode variables available).

## Params block — constraint knobs

The `params` block carries Marvin's runtime constraints:

- `allowedSubTaskRecipes`: list of recipe names the PLAN may
  spawn as WORKER children. Every entry MUST resolve to a real
  project recipe (the validator rejects inventions).
- `recipesOnlyViaExpand`: recipes that appear ONLY inside an
  EXPAND_FROM_DOC `childTemplate` (typical: chapter-loop).
- `allowedExpandDocumentRefPaths`: document paths the
  EXPAND_FROM_DOC may iterate over.
- `disallowedTaskKinds`: set `[AGGREGATE]` when the plan needs a
  WORKER-style aggregator (instead of Marvin's built-in summary).
- `defaultExecutionMode`: `SEQUENTIAL` for pipelines that build
  on each other, `PARALLEL` for independent phases.
- `maxPlanCorrections`: default 2 — the PLAN-LLM gets that many
  re-prompts to fix validation failures.

## promptPrefix — KIND blocks

The `promptPrefix` carries one KIND block per recipe in
`allowedSubTaskRecipes`. Each KIND block:

- Names which recipe the child uses.
- Contains a literal JSON skeleton for the child's spawn.
- Describes what the child produces.

The number of KIND blocks MUST equal the size of
`allowedSubTaskRecipes`. The PLAN-LLM is instructed to emit
EXACTLY that many children in KIND order.

## Completeness requirement

A Marvin recipe MUST drive the user's request to a *final
deliverable*. If outline → chapters → aggregator phases are all
needed, the recipe MUST wire all three. Stopping mid-pipeline is
a hard failure.

## When a Marvin recipe fits

- **Task decomposition with dynamic size**: "write a chapter per
  outline item" — chapter count emerges from the outline.
- **Plan-then-execute** missions where the PLAN-LLM benefits from
  a structured template (KIND blocks) over free-form planning.

## When a Marvin recipe does NOT fit

- **Strictly linear pipelines with fixed phases**: use Vogon.
- **Multi-perspective evaluation**: use Zaphod council.
- **Single-shot conversation**: use a plain Ford recipe.
