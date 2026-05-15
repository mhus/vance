# Plan-Mode and topic-recompaction

Plan-Mode is the structured "propose before doing" workflow. Use it
when a request is multi-step enough that the user benefits from
seeing your plan before you execute it.

## When to enter plan mode

Trigger `START_PLAN` when **any** of these is true:

- The request touches multiple files / multiple components.
- It needs research-then-act (read code, then write code).
- The user explicitly asks for a plan.
- Doing it wrong would be expensive to undo.

When in doubt, propose. Alignment up-front is cheaper than churn.

**Do not** enter plan mode for:

- One-shot factual questions answerable with a single `ANSWER`.
- Simple read operations (`scratch_read`, `doc_read`, ...).
- Trivial single-file edits the user already described in detail.

## The four actions

```text
START_PLAN          → mode flips to EXPLORING
PROPOSE_PLAN        → mode flips to PLANNING, todos persisted
                      (user sees plan + accepts/rejects)
START_EXECUTION     → mode flips to EXECUTING (user accepted)
TODO_UPDATE         → mark one or more todos IN_PROGRESS / COMPLETED
```

Emit them in that order. The process mode is a state machine —
emitting an action out of sequence is rejected by the validator.

### `START_PLAN`

```json
{ "type": "START_PLAN",
  "reason": "Multi-file refactor — proposing a plan before editing." }
```

Flips the process into **EXPLORING**. The tool filter narrows to
read-only tools (`*_read`, `*_list`, `web_search`, ...) so you
investigate without making changes. Use this phase to read files,
search code, query RAG — gather what you need.

### `PROPOSE_PLAN`

```json
{ "type": "PROPOSE_PLAN",
  "reason": "Investigation complete — proposing 3 steps.",
  "plan": "## Plan: rename `foo` → `bar`\n\n1. Update the type definition...\n2. Update all callers...\n3. Run tests.",
  "summary": "Rename foo → bar across the codebase.",
  "todos": [
    { "id": "type", "content": "Update Foo type to Bar",
      "activeForm": "Updating type definition" },
    { "id": "callers", "content": "Update all import sites",
      "activeForm": "Updating callers" },
    { "id": "test", "content": "Run test suite",
      "activeForm": "Running tests" }
  ] }
```

`plan` is the user-facing markdown — keep it scannable, three to
eight todos. `summary` is one line. `todos[*].id` must be unique
within the plan; you reference these ids in `TODO_UPDATE` later.

After `PROPOSE_PLAN` the turn ends and the user decides. Do not
chain into `START_EXECUTION` yourself — wait for the user's reply.

### `START_EXECUTION`

```json
{ "type": "START_EXECUTION",
  "reason": "User approved the plan.",
  "notes": "Going step-by-step, will test after each todo." }
```

Flips the process into **EXECUTING**. Tool filter relaxes back to the
full pool. From here on, the regular action vocabulary (`ANSWER`,
tool calls, ...) is back in play.

### `TODO_UPDATE`

```json
{ "type": "TODO_UPDATE",
  "reason": "Finished step 1, starting step 2.",
  "updates": [
    { "id": "type",    "status": "COMPLETED" },
    { "id": "callers", "status": "IN_PROGRESS" }
  ] }
```

Emit one `TODO_UPDATE` per state transition. The user sees a live
checklist in their client. Statuses: `PENDING`, `IN_PROGRESS`,
`COMPLETED`, `CANCELLED`.

When the **last** todo flips to `COMPLETED`, the plan is done — see
recompaction below.

## History tags Plan-Mode writes

Every Plan-Mode action drops typed markers onto the chat-message
that carries the action's outcome. You can find them later via
`history_search`:

| Tag                          | Set on                                   |
|------------------------------|------------------------------------------|
| `MODE:plan`                  | the `PROPOSE_PLAN` assistant message     |
| `MODE:execute`               | the `START_EXECUTION` turn               |
| `PLAN_STEP_STARTED:<todoId>` | the `TODO_UPDATE` turn flipping it on    |
| `PLAN_STEP_DONE:<todoId>`    | the `TODO_UPDATE` turn flipping it done  |

`history_search tags=MODE:plan` finds your most recent plan; combine
with `text=...` to scope to a topic.

## Topic-recompaction (after the plan completes)

When the last todo flips to `COMPLETED` AND there is substantial
pre-plan history (≥ 2 USER turns before the `MODE:plan` marker),
the system **automatically** posts a `RECOMPACTION_OFFER` inbox
item to the user:

> "Plan abgeschlossen — Topic in Memory rollen?"

If the user accepts (APPROVAL with `approved=true`), the
plan-range (everything from `MODE:plan` to "now") is folded into a
single `ARCHIVED_CHAT` memory entry and a one-message SYSTEM
summary is inserted at the end of the range. The original turns
stay in Mongo (audit-readable via `history` + `history_search`),
but they drop out of the LLM's active-history replay — the next
turn sees the pre-plan Topic-A chitchat, the SYSTEM summary, and
the user's follow-up. The sub-topic stops crowding the prompt.

**What you have to do:** nothing. The hook fires at the end of the
final `TODO_UPDATE`. The listener acts on the user's answer. Don't
try to call `recompact_topic` or similar — there's no such tool;
the trigger is structural, not LLM-driven.

**What you'll notice on the next turn (if the user accepted):**

- Your active history is shorter than you remember writing.
- A SYSTEM message tagged `RECOMPACTION:<topic-label>` summarises
  what happened.
- `history_search tags=RECOMPACTION:*` will surface the summary.
- `history_recall id=<original-message-id>` can fetch any specific
  archived turn if you genuinely need it back — but most of the
  time, trust the summary.

## Examples — when to plan vs. just do

**Plan:** "Refactor the auth middleware to use the new token
format" — multiple files, design choice, user wants to know what
you'll change.

**Don't plan:** "What does function `parseToken` do in
`auth.ts`?" — one read, one answer.

**Plan:** "Walk through this codebase and find every place that
hits the legacy endpoint, then migrate them." — research-then-act.

**Don't plan:** "Change the timeout from 5s to 10s in
`config.yaml`." — single trivial edit.

**Plan:** "Implement feature X end-to-end" — explicit user request
to think before acting.

## Recipe knob — `planMode`

Recipes can set `planMode: disabled` to forbid plan mode entirely
(e.g. for an answer-only assistant). If `START_PLAN` is rejected
with a message about `planMode=disabled`, fall back to direct
action — don't retry.

Default is `planMode: auto` — plan when the heuristics above match.
