# Vogon Strategy Recipe — Shape

This manual describes what a Vogon Strategy Recipe is structurally.
Slartibartfast's GATHERING ingests it as engine-bundled evidence
so DECOMPOSING can tie subgoals to concrete claims even when no
project-specific kit is installed.

## What is a Vogon Strategy

A Vogon Strategy is a **strict sequential workflow** — phases run
in order, each phase has a worker recipe, gates between phases
enforce completion, scorers can branch on quality, loops can
re-run phases until a threshold is met. The recipe is a one-shot
plan for *this specific deliverable*; the strategy IS the mission.

## Mandatory recipe fields

A Vogon recipe MUST declare:

- `name`: kebab-case identifier.
- `engine: vogon`
- `params.strategyPlanYaml`: an inline YAML string containing the
  strategy specification (parsed by `StrategyResolver.parseStrategy`).

## Strategy-yaml shape

The `params.strategyPlanYaml` value parses as:

- `name`: strategy name (can differ from the recipe name).
- `version: "1"`
- `phases`: a non-empty list of phase specifications.

Each phase declares:

- `name`: phase identifier (kebab-case).
- `worker`: name of an existing project recipe — typically `ford`,
  `marvin-worker`, `analyze`, `code-read`, or a task-specific
  recipe from the project. Tool names (e.g. `doc_write_text`) are
  NOT valid worker values; the validator rejects them.
- `workerInput`: the prompt the worker receives.
- `gate.requires`: list of completion markers that must be set
  before this phase runs.

Optional per-phase:
- `scorer`: branch decision based on output quality.
- `loop`: repeat the phase until a condition is met.
- `postActions`: tool calls run after the worker (typically
  `doc_create_text` / `doc_write_text` to persist artefacts).

## Phase chaining

Before each phase's worker runs, Vogon injects a discovery block
listing every completed predecessor with its draft-path
(`_vogon-drafts/<process>/<phase>.md`). Workers read predecessors
via `doc_read` (full) or `doc_summary` (1-3 sentence recap).

## When a Vogon strategy fits

- **Linear pipelines**: research → outline → chapters →
  consolidation. Each phase consumes the previous, the order is
  data-driven, the deliverable is the final artefact.
- **One-shot missions** with a clear endpoint.
- **Quality gates**: scorer-driven retry loops (Lector pattern).

## When a Vogon strategy does NOT fit

- **Multi-perspective evaluation**: use a Zaphod council instead.
- **Reusable consultation**: Vogon strategies are one-shot — for
  panels you consult repeatedly, use a council.
- **Dynamic task decomposition**: when the plan shape can't be
  fixed in advance, use Marvin instead.
