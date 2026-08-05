---
triggers: completion guard, guard script, vance.guard, are you really done, keep working until, nudge until done, did you build, did you run tests, ask once, guardRounds, //guard, guard inline, guard script, guard status, loopValues, sessionValues, completion guard schreiben, guard einrichten, wirklich fertig, erst bauen dann fertig, nachhaken bis fertig
summary: How to write a completion-guard script — a JS script that runs at an engine's yield point and, via vance.guard.*, can inject a follow-up so the engine keeps working instead of yielding ("are you really done?"). Plus how to wire it (recipe guard: block or //guard command).
---
# Writing a completion guard — `vance.guard.*`

A **completion guard** runs at the point an engine would *finish and
yield*. The guard is a JS script: it decides whether the work is really
done and, if not, calls `vance.guard.continueWith(prompt)` to inject a
follow-up into the engine's own queue — the engine works on that instead
of yielding. Present only in guard runs; `vance.guard` is `null` elsewhere.

## The surface

```js
// read-only yield context
vance.guard.task         // first user message of the task
vance.guard.output       // the final output the engine would deliver
vance.guard.round        // guard fires so far (0 on the first yield)
vance.guard.maxRounds    // hard cap — continueWith refuses past it
vance.guard.naturalStop  // true = natural stop, false = explicit terminate

// action — inject a follow-up + keep the engine running
const injected = vance.guard.continueWith("run the build and report the result");
// → boolean: false when the round cap is reached (nothing injected).
// Named continueWith because `continue` is a JS reserved word.

// transient scratch — survives the re-entrant guard runs of this loop/session
vance.guard.loopValues     // per process/loop (reset on a real user turn)
vance.guard.sessionValues  // per session
//   .get()            whole map (read-only copy)   → lv.get().askedTests
//   .get(key) / .set(key, value) / .has(key) / .remove(key)
```

A guard that never calls `continueWith` just does a side effect (e.g.
notify) and lets the engine yield normally.

## Patterns

**Ask once, then trust** — the loop is re-entrant, so dedup with the scratch:

```js
if (!vance.guard.loopValues.get('askedTests')) {
  vance.guard.loopValues.set('askedTests', true);
  if (!/tests? (pass|green)/i.test(vance.guard.output))
    vance.guard.continueWith("Please write and run the tests, then report.");
}
```

**LLM judge + nudge** — ask a model whether it's done (see `manual_read('vance-script-llm')`):

```js
const v = vance.llm.callForJson("completion-guard", "Evaluate the guard condition.", {
  judge: "Has the user's development task been completed?",
  task: vance.guard.task, output: vance.guard.output });
if (v && v.fire) vance.guard.continueWith("Did you also run the build and update the spec?");
```

**Deterministic check** — verify a fact instead of guessing (needs `allowTools: true`):

```js
const r = vance.tools.call("work_exec_run", { command: "mvn -q -DskipTests compile" });
if ((r.exitCode ?? 1) !== 0) vance.guard.continueWith("The build fails — fix it:\n" + r.stdout);
```

**Message the client** — `vance.process.notify(text, "WARN")` / `vance.process.progress(text)`.
**Async follow-up work** — `vance.process.spawn({...})` (don't block the lane with a long guard).

## Loop-safety (don't fight it)

`continueWith` is **cap-aware**: the persistent `guardRounds` counter is
incremented and, past `maxRounds`, `continueWith` returns `false` and
injects nothing. That is the backstop against an endless nudge loop — a
buggy script cannot loop forever. Use `loopValues` to ask each concern
*once*; use `maxRounds` as the hard ceiling.

## Wiring a guard

**Recipe** (`guard:` block — spawn default):

```yaml
guard:
  - script: _vance/guards/llm-judge.js   # cascade path OR inline scriptBody
    params: { judge: "Done?", prompt: "Build + update the spec?" }
    trigger: stop        # stop | terminate | both (default stop)
    maxRounds: 2
    allowTools: false    # false = supervisor surface (llm/documents/process);
                         # true  = full process tools (exec/file)
```

Exactly one of `script` / `scriptBody`. The bundled `_vance/guards/llm-judge.js`
is a ready-made "LLM judge + fixed prompt" guard — configure it via `params`,
no JS needed.

**Runtime** (`//guard` command, e.g. from a skill `activate:`):

```
//guard script _vance/guards/dev-done.js     # a script document
//guard inline vance.process.notify('hi!');  # an inline body
//guard clear                                # drop the runtime guard
//guard status [session] [set <k> <v> | del <k> | clear]   # inspect/edit the scratch
```

Script paths follow the document-ref grammar: `foo` = next to the referrer,
`/foo` = project root, `//project/foo` = another project.

## See also

- `manual_read('scripts')` — the general `vance.*` script surface.
- `manual_read('vance-script-llm')` — `vance.llm` for the LLM-judge pattern.
- Spec: `specification/public/completion-guard.md` (full mechanism),
  `specification/public/script-document-api.md` §7a (`vance.guard.*` reference),
  `specification/public/document-refs.md` (the `script:` path grammar).
