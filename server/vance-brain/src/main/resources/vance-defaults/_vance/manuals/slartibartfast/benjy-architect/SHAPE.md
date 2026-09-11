# Benjy Recipe — Shape

This manual describes what a Beny recipe is structurally. Slart's
GATHERING ingests it as engine-bundled evidence so DECOMPOSING can tie
subgoals to concrete claims even when no project-specific kit is
installed.

## What a Benjy recipe is

A Beny recipe is the **outer configuration** of Vance's iterative
orchestration engine for small/local models (`engine: benjy`). The
engine carries the loop discipline — state, verification, retries,
escalation are deterministic code; the model is called only at narrow,
schema-bound edges. The recipe decides which semantic stages are ON
and parameterises them. It has **no `promptPrefix`** — the persona
lives in the referenced profiles, not in the outer recipe.

## A Beny configuration is a suite

The outer recipe alone is not runnable — it references:

- **Doer** (`params.doRecipe`, mandatory): a Ford worker recipe.
  Beny spawns a fresh doer per item; a retry is a new spawn with
  error context, never a re-steer.
- **Controller profiles** (`params.features.*.recipe`): internal
  LightLlm profiles (`internal: true`, engine `jeltz`) for the
  schema-bound single-shot calls — `interpret` (mandatory),
  `route`, `evaluate`, `reflect`.
- **Escalation target** (`params.features.escalation.recipe`,
  optional): a spawnable worker recipe for items the small model
  cannot fix.

The bundled `benjy-interpret` / `-route` / `-evaluate` / `-reflect`
profiles and `benjy-do-coding` / `benjy-do-research` doers cover the
common cases. A Slart-authored recipe **references** them by name; it
does not generate them.

## Feature semantics

- `interpret` — mandatory. Goal → interpreted goal, task type,
  acceptance criteria, first items. One call per task.
- `route` — optional. Decides queue operations at branch points
  (retry / split / revise / escalate / ask_parent / done / reset /
  blocked). OFF (`route: false`) = mechanical fallback policy:
  retry to the item cap → escalation → BLOCKED. Fit for mechanical
  bulk lists.
- `check` — optional. A shell command run mechanically after each
  coding item (`{ command: "mvn -q test" }`). Pin a REAL project
  build/test command or leave it off — a wrong default fails every
  run's checks.
- `evaluate` — optional. Per-item LLM judgement against the
  acceptance criteria.
- `reflect` — optional. Goal-level gate before DONE ("did we achieve
  what the asker meant?"). Without it, DONE arrives as soon as the
  queue is empty.
- `escalation` — optional. Delegation of stuck items to a stronger
  worker. Without a target, escalation ends BLOCKED.

## Task types and chains

`taskTypes` (subset of info / coding / planning / analysis, default:
all four) selects the active chain templates:

- `info`: do → close (no check, no evaluate)
- `coding`: do → check → evaluate → close (stages drop out when off)
- `planning` / `analysis`: do → evaluate → close

A recipe restricted to research work would pin
`taskTypes: [info, planning, analysis]` — no coding items, hence no
check feature.

## Caps are safety nets, not style

`maxStagnation` (tasks without observable progress), `maxReflectNo`,
`maxItemAttempts`, `maxWallclockMinutes`, `maxTokens` (controller
budget), `maxToolCalls` (per-item tool budget handed to the doer) and
`maxInitialItems` (structural cap on items per batch — raise only
when the task text makes the structure evident). Exhaustion is a
checkpoint question to the user/parent, never a silent kill. Keep the
bundled defaults unless the goal justifies a change.

## Work target

`workTarget.kind` is `WORK` (Brain-server workspace, sandboxed) or
`CLIENT` (the user's local files via a connected Foot). Check and
doer share the workspace; artefacts that must survive the run need a
named `workTarget.targetName`.

## Reference kinds are validated

Controller slots (`interpret`/`route`/`evaluate`/`reflect`) must
reference `internal: true` LightLm profiles — they run through the
LightLlmService. `doRecipe` and `escalation` must reference spawnable
worker recipes (NOT internal config profiles). A reference that does
not resolve in the project fails validation with the recipe
inventory as hint.

## Authoring is author-only

The Slart run validates and persists the recipe and stops
(`planOnly`). Running a Beny worker is a separate, long-lived step —
spawn the persisted recipe afterwards. Typical asks: pin a project's
check command on a coding variant, restrict task types, point
escalation at a different worker, or compose a cheap mechanical batch
worker.
