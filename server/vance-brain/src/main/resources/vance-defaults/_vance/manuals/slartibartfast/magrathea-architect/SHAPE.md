# Magrathea Workflow — Shape

This manual describes what a Magrathea Workflow is structurally.
Slartibartfast's GATHERING ingests it as engine-bundled evidence so
DECOMPOSING can tie subgoals to concrete claims even when no
project-specific kit is installed.

## What is a Magrathea Workflow

A workflow is a **named state machine** — a document under
`_vance/workflows/<name>.yaml`. It is NOT a recipe: there is no
`engine:` field, because Magrathea is a workflow-orchestration
subsystem, not a ThinkEngine. The whole YAML *is* the plan.

A workflow is a **reusable asset**: authored once, started many
times (via the `workflow_start` tool, the scheduler, or REST), each
run with its own `parameters`. The Slart run that authors a workflow
is **author-only** — it validates and persists the YAML and stops.
Running it is a separate step.

Use a workflow (not a Vogon strategy) when the process has
**branching, gates, timers, retries, error-handling, or
sub-workflows** — anything a straight linear pipeline can't express.

## Mandatory fields

- `start`: name of the entry state — MUST exist in `states:`.
- `states`: a map of `<state-name> → state-def`, at least one entry.

## Optional top-level fields

- `description`, `version`, `tags`.
- `parameters`: `<key>: { type, required?, default? }`. Caller
  params are validated against this at start.
- `bounds`: `{ maxTotalCostUsd, maxWallclockSeconds, maxTaskSpawns }`
  — a **hard** stop, not routed through `catch:`.
- `allowedTools`: a tool whitelist for the run.

## State lifecycle — every task produces an outcome

Every state runs a task that terminates with an **outcome string**.
After completion the next state is resolved in this order:

1. **condition_task only** — the first matching `transitions:` branch.
2. `on:` — exact match of the outcome against `<outcome>: <state>`.
3. `catch:` — the outcome interpreted as a `MagratheaErrorKind`.
4. otherwise the run fails.

`retry:` (`{ maxAttempts, on: [<kinds>], backoffSeconds }`) preempts
the transition when the outcome is a listed error-kind and attempts
remain. Common lifecycle fields on any state: `description`,
`timeoutSeconds`, `storeAs` (write the task output into a variable),
`on:`, `catch:`, `retry:`.

Error kinds (for `catch:` / `retry.on:`): `technical_error`,
`business_error`, `agent_error`, `timeout`, `permission_error`,
`human_rejected`, `cancelled`.

## Dataflow — reading params and earlier task output

Any spec string value may embed placeholders that are resolved just
before the task runs:

- `${params.<key>}` — a run parameter declared in `parameters:`.
- `${state.<key>}` — a variable an earlier state wrote via
  `storeAs: <key>`. Nested access works: `${state.review.summary}`.

A missing key resolves to an empty string. This is how tasks thread
data between each other: store one task's output with `storeAs`, then
reference `${state.<key>}` in a **later** task's prompt, tool param,
gate title/body, or terminal result — e.g. `agent_task` with
`storeAs: plan`, then a later `agent_task` prompt
`"Implement: ${state.plan}"`.

This is **distinct** from `condition_task` branching, which uses SpEL
(`#state['k']` / `#params['k']`, see below), not `${…}`.

## Task types

| `type:` | Sync? | Key fields | Outcomes |
|---|---|---|---|
| `agent_task` | no | `recipe:` (must be a known recipe), `params: {prompt, schema}` | success / agent_error |
| `tool_task` | yes | `tool:`, `params:` | success / permission_error / technical_error |
| `shell_task` | yes | `run:`, `dirName?`, `timeoutSeconds?` | success / business_error / timeout / technical_error |
| `script_task` | yes | JS via the unified script executor | success / technical_error |
| `gate_task` | no | `inbox: {kind, title, assignedTo, criticality, options}` | depends on the user answer |
| `timer_task` | no | `duration:` (`7d`/`4h`/`30m`/ISO-8601) | fired |
| `condition_task` | yes | `transitions: [{if:<SpEL>, to}, …, {else}]` | (sets next state directly) |
| `workflow_task` | no | `workflow:`, `params:` | success / failure |
| `terminal` | yes | `outcome: success\|failure`, `result?` | (ends the run) |

Notes:

- `condition_task.transitions` is an **ordered** list; the `else:`
  branch, if present, MUST be last. SpEL reads `#state['k']` and
  `#params['k']`. It has no side effects.
- `agent_task.recipe` names a **recipe**, never a tool. For a single
  direct tool call use a `tool_task`. For a generalist worker use
  `ford`.
- `workflow_task.workflow` names another workflow; it blocks until
  that sub-run reaches a terminal state.

## Rules of thumb

- At least one reachable `terminal` state.
- Every `on:` / `catch:` / `transitions:` target MUST name a declared
  state (the parser rejects dangling targets).
- Guard every loop with a `condition_task` plus a counter/variable or
  a `bounds` limit — an unbounded loop will burn the run's budget.
- Put a `catch: { technical_error: … }` on any state whose failure
  should be recoverable rather than fatal.

## When a workflow does NOT fit

- **One-shot linear pipeline** ("outline → chapters → consolidate"):
  use a Vogon strategy (`slartibartfast`), not a workflow.
- **Deep recursive decomposition with dynamic sub-tasks**: use
  `marvin-architect`.
- **Multi-perspective debate / council**: use `zaphod-architect`.
- **A single agent turn**: just spawn the recipe directly — a
  one-state workflow adds ceremony without value.
