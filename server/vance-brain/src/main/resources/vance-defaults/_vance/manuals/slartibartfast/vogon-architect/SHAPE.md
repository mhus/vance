--- kind: shape
    for: vogon-architect
    topic: what a Vogon plan is, structurally
---

# Vogon Plan — Shape

Slartibartfast's GATHERING reads this as engine-bundled evidence, so
DECOMPOSING can tie subgoals to concrete plan constructs even when no
project kit is installed.

## What a Vogon plan is

A **state machine that runs on behalf of a person.** Same grammar as a
workflow — one plan grammar exists — but written for a different job:
where an automation is a sequence of things that must happen, a Vogon
plan is a sequence of **judgements**. A worker produces something, another
reads it, the plan branches on how good it was, and goes round again if it
has to.

Because it belongs to the session it was started in, it can ask the person
questions while it runs, and its result comes back into the conversation.

## Skeleton

```yaml
start: <state name>
states:
  <name>:
    type: agent_task | gate_task | condition_task | tool_task |
          shell_task | script_task | timer_task | workflow_task | terminal
    on:    { <outcome>: <state> }
    catch: { <error kind>: <state> }
```

Nothing is forbidden by type — a plan may run a script, a workflow may
score an answer. What follows is what a *thinking* plan is usually made
of.

## Carrying work forward

`storeAs:` names a variable holding a state's output; a later state reads
it with `${state.<key>}` in its prompt, body, or params. That is the whole
dataflow — there is no other channel between states.

## Judging an answer

An `agent_task` can declare how its answer should be read. The reading
becomes the **outcome**, so `on:` routes it like any other.

```yaml
review:
  type: agent_task
  recipe: ford
  params: { prompt: "Assess ... Answer with JSON: score, summary, issues." }
  score:
    bands:
      - { atLeast: 0.7, outcome: good }
      - { atLeast: 0.2, outcome: revise }
      - { default: true, outcome: reject }
    maxCorrections: 2
  storeAs: review
  on: { good: publish, revise: writer, reject: escalate }
```

```yaml
classify:
  type: agent_task
  recipe: ford
  params: { prompt: "Is the outline unambiguous, ambiguous, or contradictory?" }
  decide:
    options: [unambiguous, ambiguous, contradictory]
  storeAs: clarity
  on: { unambiguous: write, ambiguous: ask_human, contradictory: rebuild }
```

The score scale is fixed at **0.0–1.0**. An answer outside it is sent back
rather than mapped, so a threshold means the same thing in every plan.

An answer that does not fit the requested shape is re-asked up to
`maxCorrections` times (default 2) in the same process, so the model sees
its own attempt. Only then does the state end as `agent_error`.

## Looping deliberately

A revise-branch that routes back is a loop, and a loop needs a bound.
Counters are ordinary variables:

```yaml
writer:
  type: agent_task
  enterCounter: rounds          # +1 on every entry into this state
  ...
check_rounds:
  type: condition_task
  transitions:
    - if: "#state['rounds'] >= 4"
      to: ask_human
    - else: writer
```

Put `resetCounters: [rounds]` on the state that **begins** the section.
Without it a second pass through the section inherits the first pass's
count and gives up after one round — silently.

## Asking the person

```yaml
approve:
  type: gate_task
  inbox:
    kind: APPROVAL              # APPROVAL | DECISION | FEEDBACK
    title: "Ready to implement?"
    body: "${state.plan}"
  on: { approved: implement, rejected: planning }
  catch: { timeout: abandoned }
```

The question always waits in the inbox. When the plan runs for a person,
it additionally surfaces in their conversation and can be answered there
in plain words. Ask when the judgement is genuinely theirs — a direction,
an approval before something irreversible — not to confirm what the plan
can decide itself.

## Giving a worker the conversation

`inheritContext: summary` (or `all`, `last:<n>`, `strength:<min>`) puts the
owning conversation in front of the worker's prompt. Use it where the step
would otherwise be blind to what was already discussed. It needs an owning
process, so a plan using it cannot be started headless — that is checked
when the run starts, not halfway through.

## Ending

Every path must reach a `terminal`:

```yaml
done:
  type: terminal
  outcome: success              # success | failure
  result:
    summary: "${state.review}"
    document: "${state.draftPath}"
```

The `result:` block is what the person gets back. Name the things they
would want; do not restate the whole transcript.

## Rules

- `start:` must name a declared state.
- Every `on:` / `catch:` / `transitions` target must be a declared state.
- Every `agent_task.recipe` must be a real recipe; `ford` is the
  generalist default.
- One judgement per state: `score:` or `decide:`, never both.
- If a task has stages, it has states. A single giant prompt is not a plan.
