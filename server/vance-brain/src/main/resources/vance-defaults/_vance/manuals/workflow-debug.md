---
triggers: workflow debuggen, debug workflow, workflow läuft nicht, workflow hängt, workflow fehlgeschlagen, why did the workflow fail, workflow failed, workflow stuck, step through workflow, breakpoint, haltepunkt, workflow schrittweise, rerun workflow, workflow wiederholen, retry from state, simulate step, workflow_run_get, workflow_control, workflow_restart, workflow_breakpoints, workflow_simulate, workflow_runs
summary: How to investigate and debug a workflow run with the workflow_* tools — read a finished or failed run, restart it in the debugger, set breakpoints, step, and skip a risky step with a simulated result.
---
# Debugging a workflow run

A run is a journal of states. The `workflow_*` tools show it and steer it
the way the run screen does — same rules, same permissions (`Project READ`
to look, `Project WRITE` to steer). Run ids are the `workflowRunId` that
`workflow_start` returned and `workflow_runs` lists.

## Find out what happened (nothing is changed)

```
workflow_runs(workflow = "release", status = "failed")   # which run
workflow_run_get(runId = "…")                            # how it went
workflow_get(name = "release")                           # the definition
```

In `workflow_run_get` read, in this order: `status` and `errorMessage`;
`steps` (each state with `outcome`, `output`, `durationMs`); `variables`
(what `storeAs:` wrote); `children` (sub-runs — open them the same way).
An output cut short says `outputCut` — read one state whole with
`step = "<state>"`. The run's own frozen definition is what ran; the file
may have changed since.

**Never loop on `workflow_run_get`.** A run that waits at a gate waits for
a person; it is not stuck. `waitSeconds` (max 20) exists only to wait for
a run you just released to halt again.

## Run it again, under control

`workflow_restart` starts a **new** run from an ended one; the old journal
stays as evidence.

```
workflow_restart(runId = "…", debug = true)                       # rerun, halted before every state
workflow_restart(runId = "…", fromState = "deploy", debug = true) # retry from a state, with the variables it had then
workflow_restart(runId = "…", breakpoints = ["deploy"])           # run freely, halt before 'deploy'
```

`fromState` must be one of `restartableStates` in the old run's view.
A run that still goes cannot be restarted — `workflow_control(action = "stop")` first.

**Debugging is not a dry run.** From the chosen state on, tools, scripts,
shell commands and agents execute for real — a state that sends a mail
sends it again. Halt *before* the risky state and skip it (below).

A fresh run can be started halted the same way:
`workflow_start(name = …, debug = true)` or `breakpoints = [...]`.

## Drive a halted run

A debug run waits at `status: PAUSED`; `state` is the state it stands
before. `allowedActions` says what it accepts now.

| Want | Call |
|---|---|
| Run exactly this state, halt before the next | `workflow_control(runId, action = "step", waitSeconds = 10)` |
| Go on to the next breakpoint or the end | `workflow_control(runId, action = "resume", waitSeconds = 10)` |
| Change where it halts | `workflow_breakpoints(runId, states = [...])` — the complete set; `[]` clears |
| Skip this state with a result | `workflow_simulate(runId, outcome, output?, errorMessage?)` |
| End a waiting gate / agent / sub-run as `cancelled` | `workflow_control(runId, action = "abort")` |
| End the run | `workflow_control(runId, action = "stop")` |

`workflow_control` performs only what is offered; otherwise the answer says
`applied: false`. Do not retry it — read `allowedActions`.

### Simulating a step

`workflow_simulate` gives the state a result **instead of** executing it:
no tool call, no script, no agent, no waiting. The run then takes the
transition for that outcome, with your `output` available to later states
(and to `storeAs:`), and halts before the next state.

- Valid outcomes are listed in `simulation.outcomes` of the run view (the
  `on:` keys, then the `catch:` error kinds). Anything else is refused.
- Not simulable: `condition_task` (nothing to replace) and terminals.
- It is also how to take a branch on purpose: simulate `error` to see what
  the `catch:` path does.

## Reading a failure

- `no transition for outcome 'x'` — the state ended with an outcome the
  plan has no `on:`/`catch:` for. Compare the step's `outcome` with the
  state's transitions in `workflow_get`.
- A step with `outcome: error` and an `output` — the cause is in the output;
  read it whole with `step = …`.
- Bounds exceeded — the run hit its step/time limit; `errorMessage` says which.
- `cancelled` — somebody aborted the step; a `retry:` block never retries it.

## Related

- `manual_read('plans')` — running a plan normally.
- `manual_read('slartibartfast')` — writing a plan; `kind_validate` checks a `vance-workflow` document.
