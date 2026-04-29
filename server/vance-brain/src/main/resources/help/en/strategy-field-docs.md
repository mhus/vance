# Strategy field reference

A strategy describes how the **Vogon** engine orchestrates a multi-phase
workflow. Each strategy lives in its own document under
`_vance/strategies/<name>.yaml` (or in the bundled defaults under
`vance-defaults/strategies/`), and Vogon walks its phases in order,
applying gates and checkpoints between them.

Variable substitution (`${params.X}`, `${state.X}`, `${phases.X.…}`) is
applied by Vogon when reading `worker`, `workerInput`, and
`checkpoint.message` — keep these as templates, not pre-rendered text.

## Required

### `name`
*string, required* — unique strategy name within the cascade. Used by
spawners (`engineParams.strategy`) to pick this strategy. Must match the
filename (without `.yaml`) for the bundled lookup to find it.

### `phases`
*list, required, non-empty* — ordered list of phase definitions. Phases
run sequentially; the next phase only starts after the current one's
gate is satisfied. See **Phase fields** below.

## Defaults & metadata

### `description`
*string, optional* — one-line human description shown in selectors and
admin UI.

### `version`
*string, optional, default `"1"`* — strategy schema/format version. Bump
when introducing breaking changes to phase structure.

### `paramDefaults`
*map, optional, default `{}`* — defaults merged into the strategy's
runtime parameters when a process is spawned. Caller-supplied
`engineParams` override these per-key.

Typical contents:
- `workerRecipes`: nested map mapping logical roles to recipe names —
  e.g. `workerRecipes.planning: analyze`. Phases reference these via
  `${params.workerRecipes.planning}`.
- `goal`: a default goal string when a phase template references
  `${params.goal}`.

## Phase fields

Each entry in `phases:` is a map with the following keys.

### `name`
*string, required* — phase identifier within the strategy. Used to
reference the phase's outputs (`${phases.<name>.result}`) and to compose
flag names like `<name>_completed` for gates.

### `worker`
*string, optional* — recipe name to spawn for this phase. Variable
substitution is supported — common pattern is
`worker: ${params.workerRecipes.<role>}`. When omitted, the phase is a
pure checkpoint (e.g. user-only approval) with no worker spawn.

### `workerInput`
*string, optional* — the steer message sent to the spawned worker. Use
multi-line YAML (`|`) for prompts. Common substitutions:
- `${params.goal}` — the strategy's top-level goal
- `${phases.<earlier>.result}` — output of an earlier phase

### `gate`
*map, optional* — condition under which this phase is considered
complete and Vogon may advance to the next. Without a gate, the phase
auto-advances on the worker's `done` event.

Sub-fields:
- `requires`: *list of strings* — all named flags must be set. Common
  flag is `<phase-name>_completed`, raised when the worker reaches
  `done`.
- `requiresAny`: *list of strings* — at least one named flag must be
  set. Use for "either approval or skip" gates.

### `checkpoint`
*map, optional* — pause point that surfaces an inbox item to the user
before the gate is allowed to clear. When the user resolves the inbox
item, Vogon stores the result and re-evaluates the gate.

Sub-fields:
- `type`: *enum, default `APPROVAL`* — kind of inbox item to create.
  Values: `APPROVAL` (yes/no/options), `FEEDBACK` (free-form text),
  `DECISION` (pick from `options`).
- `message`: *string, required for visible checkpoints* — the question
  / prompt body shown to the user. Multi-line and substitution-aware.
- `options`: *list of strings, optional* — for `DECISION` checkpoints,
  the choices the user can pick from.
- `storeAs`: *string, optional* — name of the state key to set with the
  user's answer. Subsequent phases can reference it via
  `${state.<storeAs>}`.
- `criticality`: *enum, optional* — `LOW` / `NORMAL` / `HIGH` /
  `CRITICAL`. Drives notification urgency in the inbox.
- `default`: *value, optional* — fallback when the inbox item is not
  resolved within a timeout (timeouts are inbox-side; this is the
  applied value).
- `tags`: *list of strings, optional* — free-form discovery tags
  attached to the inbox item.
- `payload`: *map, optional* — extra structured data attached to the
  inbox item, available to UI renderers.

## Worked example

```yaml
name: waterfall
description: |
  Sequential plan: planning → implementation → review.
version: "1"
paramDefaults:
  workerRecipes:
    planning: analyze
    implementation: marvin-worker
    review: code-read
phases:

  - name: planning
    worker: ${params.workerRecipes.planning}
    workerInput: |
      Erstelle einen Plan für: ${params.goal}
    gate:
      requires: [planning_completed]
    checkpoint:
      type: approval
      message: |
        Plan steht für '${params.goal}'. Soll ich mit der Umsetzung
        weitermachen?
      storeAs: plan_approved
      criticality: NORMAL

  - name: implementation
    worker: ${params.workerRecipes.implementation}
    workerInput: |
      Setze den Plan aus Phase planning um.

      Plan-Inhalt:
      ${phases.planning.result}
    gate:
      requires: [implementation_completed]
```

## Authoring notes

- **Ein File pro Strategy** unter `_vance/strategies/<name>.yaml`. Tenant
  overrides leben hier; project-spezifische Overrides in
  `<project>/strategies/<name>.yaml`. Cascade-Reihenfolge:
  project → `_vance` → bundled.
- The bundled set ships under `classpath:vance-defaults/strategies/`.
  Tenants override exactly the strategies they want to change; the rest
  fall through.
- Variable substitution stays a Vogon concern — always keep templates
  un-pre-rendered, with `${...}` placeholders intact.
- Phase `result` keys (`${phases.<name>.result}`) are populated by
  Vogon from the spawned worker's `done`-event payload. Make sure your
  worker recipe paste the data the next phase will reference.
