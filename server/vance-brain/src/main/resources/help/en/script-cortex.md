# Script Cortex

A small workbench for JavaScript snippets that run inside the brain's
sandboxed GraalJS engine. Useful for one-off computations, throw-away
debugging scripts, and the orchestrator scripts that Deep Thought
emits.

## Files

Script Cortex is a **file explorer over the full project** — it
shows every document, not just a reserved zone. You can drop
JavaScript snippets anywhere in the project tree:

- **`scripts/`** — default bucket for stand-alone scripts.
- **`skills/<name>/scripts/`** — skill-bound scripts that the brain
  mounts as virtual tools.
- Any other path — Script Cortex imposes no structure.

Executability hangs on the **extension**, not the location:

- **`.js`** — executable JavaScript. Has an **Execute** button.
- **`.json`** — static data. Loaded with `vance.script.load(path)` from
  other scripts (planned).
- **`.md`** — Markdown notes. Useful for documenting a script bundle.
- Anything else — opens as plain text, no Execute.

Folders are virtual: a path like `utils/math/sum.js` automatically
creates the `utils/math/` folder in the sidebar. The "+ new" button
pre-fills `scripts/` at root level; type any other project path you
want. Move a file by renaming its path (path-edits land in a
follow-up — v1 keeps the create path stable).

## Writing a script — the empty template

A new `.js` file starts blank. The most common shape is an immediately
invoked function expression (IIFE) that returns a value:

```js
/**
 * @description what this script does
 * @timeout     5s
 */
(function () {
    console.log('hello from script-cortex');
    var result = args.x + args.y;
    return { ok: true, value: result };
})();
```

What you get:

- **`args`** — a global, holds the JSON object you typed into the
  Execute dialog (e.g. `{ "x": 3, "y": 4 }`).
- **`console.log/info/warn/error`** — write debug output. Every call
  shows up live in the Output pane of the Execute dialog. The same
  text is also persisted in the execution log buffer (5 min retention,
  10k-line ring).
- **`vance.log.info/warn/error(message, fields?)`** — structured
  logger. Tees to **both** the SLF4J server log AND the Execute
  dialog. The optional `fields` parameter is a data object appended
  to the message as `{key=value}`.
- **The return value** — last expression value. Primitives stay
  primitives, JS objects become JSON-friendly maps. Shown in the
  green **Result** box once the script finishes.

## Debug-output essentials

```js
console.log('plain string');
console.log('with args:', args);            // prints the args object
console.warn('this is a warning');
console.error('this is an error');
```

All four channels land in the same Output pane, tagged with the
channel name (`[log]`, `[info]`, `[warn]`, `[error]`).

For long-running computations, log progress markers so you can see
the script isn't stuck:

```js
for (var i = 0; i < N; i++) {
    if (i % 100 === 0) console.log('progress', i, '/', N);
    // ...
}
```

## Headers (JSDoc-style, top of the file)

Optional, but useful:

- `@timeout 30s` — wall-clock cap (defaults to 30s, max 1h).
- `@description ...` — shows up in script-listings.

Headers must sit in the **first** JSDoc block of the file. Wrong tag
values fail-fast before evaluation.

## Validate

Two buttons live in the right panel (when help is closed):

- **Quick Validate** — parser-only check. Catches syntax errors,
  unterminated strings, malformed headers. Free, instantaneous.
- **Deep Validate (LLM)** — sends the script to a small model that
  flags suspect patterns: infinite loops, blocking I/O, missing
  returns, header anomalies. Cached per content-hash, so re-running
  on an unchanged script returns the cached result instantly.

A Deep-Review cache survives reloads — when you re-open a file the
banner says either *"matches current"* (green) or *"content has
changed since"* (yellow).

## Execute

The Execute dialog asks for an `args` JSON object (default `{}`).

- **Run** kicks off the script asynchronously. The UI shows the live
  log stream, the state badge cycles `starting → running → finished`
  (or `failed` / `cancelled`).
- **Cancel** interrupts a running script — the GraalJS context is
  closed from outside the worker thread.
- Output above 10 000 lines is ring-truncated; the oldest line falls
  off first.

## Generate / Improve (Deep Thought)

The **🧠 DeepThought** button opens a prompt panel. Type what the
script should do, hit **Generate**. The brain spawns a Deep-Thought
process that drafts and validates the code; on `DONE` you can apply
the result to the active tab.

When *"Include current script as context"* is checked, the existing
file is appended to the prompt — useful for "make this faster" or
"add error-handling here" refactors.

## What v1 does not do

- **No tool dispatch.** `vance.tools.call(...)` is intentionally
  disabled in Script Cortex — the editor is a sandbox, not an agent
  runtime. A future iteration can plumb a scoped tool surface.
- **No cross-script `require`.** Loading another script-cortex file
  from JavaScript will land in a follow-up.
- **No multi-user edit locking.** Last write wins — keep the same
  file open in only one browser tab.
