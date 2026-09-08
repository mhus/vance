---
triggers: completion guard, guard script, vance.guard, are you really done, keep working until, nudge until done, did you build, did you run tests, ask once, guardRounds, //guard, guard inline, guard script, guard status, loopValues, sessionValues, completion guard schreiben, guard einrichten, wirklich fertig, erst bauen dann fertig, nachhaken bis fertig, start guard, command guard, shooty, guard point, trigger start, trigger command, activate skill automatically, gate a command, deny a command, command safety
summary: How to write a guard script (Shooty) — a JS script that runs at a guard point (start / command / stop / terminate) via vance.guard.*. Stop guards inject a follow-up so the engine keeps working ("are you really done?"), start guards run per user turn (e.g. auto-activate skills), command guards gate engine commands (deny = hard fail). Plus how to wire it (recipe guard: block or //guard command).
---
# Writing a guard script — `vance.guard.*`

A **guard** is a JS script that runs at a **guard point**. The guard
decides what to do imperatively; the surface (`vance.guard.*`) exposes
the point's context and actions. Present only in guard runs;
`vance.guard` is `null` elsewhere.

| Point (`trigger:`) | Runs when | Actions available | Fails |
|---|---|---|---|
| `stop` (default) | engine would finish and yield | `continueWith(prompt)` | open |
| `terminate` | explicit terminate (e.g. `_terminate`) | `continueWith(prompt)` | open |
| `start` | once per genuine user turn | `activateSkill(...)`, `setTurnPrompt(text)` | open |
| `command` | before an engine command runs | `deny(reason)` | **closed, hard** |

The scratch stores (`loopValues` / `sessionValues`) are shared across all
points of a process — a start guard's flags are readable by its stop guard.

## The surface

```js
// read-only run context
vance.guard.point       // 'start' | 'command' | 'stop' | 'terminate'
vance.guard.task        // first user message (at start: THIS turn's user input)
vance.guard.output      // the final output the engine would deliver ('' at start/command)
vance.guard.round       // guard fires so far (0 on the first yield)
vance.guard.maxRounds   // hard cap — continueWith refuses past it
vance.guard.naturalStop // true = natural stop, false = explicit terminate
                       // (stop/terminate only — fixed sentinel at start/command;
                       //  branch on `point` first)
vance.guard.command     // at point 'command': { name, args } — null elsewhere

// actions (point-specific; unavailable ones throw)
const injected = vance.guard.continueWith("run the build and report the result");
// → boolean: false when the round cap is reached. Stop/terminate only.
vance.guard.deny("this command deletes project data");
// → command fails hard with this reason. Command point only.
const fresh = vance.guard.activateSkill("review-mode", "optional args");
// → sticky skill activation, no separate action turn. Any point.
vance.guard.setTurnPrompt("You are the release auditor. Check every step...");
// → REPLACES this turn's system prompt entirely. Start point only.

// transient scratch — survives the re-entrant guard runs of this loop/session
vance.guard.loopValues     // per process/loop (reset on a real user turn)
vance.guard.sessionValues  // per session (survives the user-turn reset —
                           // use for "did this once per process" flags)
//   .get()            whole map (read-only copy)   → lv.get().askedTests
//   .get(key) / .set(key, value) / .has(key) / .remove(key)
```

A guard that calls no action just does a side effect (e.g. notify) and
returns.

## Patterns

**Ask once, then trust** (stop) — the loop is re-entrant, so dedup with the scratch:

```js
if (!vance.guard.loopValues.get('askedTests')) {
  vance.guard.loopValues.set('askedTests', true);
  if (!/tests? (pass|green)/i.test(vance.guard.output))
    vance.guard.continueWith("Please write and run the tests, then report.");
}
```

**Auto-activate skills from the request** (start) — runs once per genuine
user turn; the skill applies to the very turn that opened:

```js
if (/deploy|release/i.test(vance.guard.task))
  vance.guard.activateSkill('release-checklist');
```

Run-once-per-process logic belongs in `sessionValues` (it survives the
per-turn reset); `loopValues` is wiped on every genuine user turn.

**Replace the turn's system prompt** (start) — full replacement, not
additive: the recipe prompt, skill blocks and date context are gone for the
turn; fold anything you still need (even skills you activated) into the
text. One text per turn (last call wins), computed freely — e.g. from a
LightLlm call or documents read this turn:

```js
if (!vance.guard.sessionValues.get('audited') && /audit/i.test(vance.guard.task)) {
  vance.guard.sessionValues.set('audited', true);
  const v = vance.llm.callForJson("audit-framing", "Frame this audit turn.", { task: vance.guard.task });
  vance.guard.setTurnPrompt(v.prompt);
}
```

By default the prompt is not manipulated: a turn whose start guard sets
nothing gets the normal recipe prompt. A guard-injected follow-up turn
inherits the replacement (same work unit).

**Gate a command with an LLM judge** (command) — a deny fails the command
hard, and so does a script error (fail-closed):

```js
const v = vance.llm.callForJson("command-guard", "Is this command safe?", {
  command: vance.guard.command.name, args: vance.guard.command.args });
if (!v || !v.safe) vance.guard.deny(v.reason || " judged unsafe by the command guard");
```

**Deterministic check** (stop, `allowTools: true`) — verify a fact instead of guessing:

```js
const r = vance.tools.call("work_exec_run", { command: "mvn -q -DskipTests compile" });
if ((r.exitCode ?? 1) !== 0) vance.guard.continueWith("The build fails — fix it:\n" + r.stdout);
```

**LLM judge + nudge** (stop) — see `manual_read('vance-script-llm')`:

```js
const v = vance.llm.callForJson("completion-guard", "Evaluate the guard condition.", {
  judge: "Has the user's development task been completed?",
  task: vance.guard.task, output: vance.guard.output });
if (v && v.fire) vance.guard.continueWith("Did you also run the build and update the spec?");
```

**Message the client** — `vance.process.notify(text, "WARN")` / `vance.process.progress(text)`.
**Async follow-up work** — `vance.process.spawn({...})` (don't block the lane with a long guard).

## Loop-safety and fail-safety (don't fight it)

- `continueWith` is **cap-aware**: the persistent `guardRounds` counter is
  incremented and, past `maxRounds`, `continueWith` returns `false` and
  injects nothing. Use `loopValues` to ask each concern *once*; use
  `maxRounds` as the hard ceiling. `maxRounds` applies to stop/terminate
  only — start runs once per turn, command once per command (the script
  timeout bounds both).
- **Stop/start guards fail open** (a broken script never blocks the
  engine/turn); **command guards fail closed** (a broken script fails the
  command hard). A guard denial also aborts the remaining commands of a
  skill `activate:`/`deactivate:` sequence.
- The **guard never guards itself**: anything fired from inside a guard
  run (skill activation's commands, LLM calls) bypasses the command gate.
  The `guard` verb itself is never gated, so `//guard clear` always works.

## Wiring a guard

**Recipe** (`guard:` block — spawn default):

```yaml
guard:
  - script: _vance/guards/llm-judge.js   # cascade path OR inline scriptBody
    params: { judge: "Done?", prompt: "Build + update the spec?" }
    trigger: stop        # start | command | stop | terminate | both (default stop)
    maxRounds: 2
    allowTools: false    # false = supervisor surface (llm/documents/process);
                         # true  = full process tools (exec/file)
```

Exactly one of `script` / `scriptBody`. The bundled `_vance/guards/llm-judge.js`
is a ready-made "LLM judge + fixed prompt" stop guard — configure it via
`params`, no JS needed.

**Runtime** (`//guard` command, e.g. from a skill `activate:`):

```
//guard script _vance/guards/dev-done.js     # a script document
//guard inline vance.process.notify('hi!');  # an inline body
//guard clear                                # drop the runtime guard
//guard status [session] [set <k> <v> | del <k> | clear]   # inspect/edit the scratch
```

The runtime guard is a **stop** guard. Script paths follow the
document-ref grammar: `foo` = next to the referrer, `/foo` = project
root, `//project/foo` = another project.

## See also

- `manual_read('scripts')` — the general `vance.*` script surface.
- `manual_read('vance-script-llm')` — `vance.llm` for the LLM-judge pattern.
- Spec: `specification/public/shooty.md` (full mechanism),
  `specification/public/script-document-api.md` §7a (`vance.guard.*` reference),
  `specification/public/document-refs.md` (the `script:` path grammar).
