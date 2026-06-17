---
triggers: execute_javascript, JavaScript, JS script, vance.tools.call, GraalJS, scratch javascript, client_javascript, host bindings, vance.*, client.*, multi-tool plumbing
summary: How to call tools from JavaScript on brain (vance.*) or foot (client.*) — execute_javascript, execute_scratch_javascript, client_javascript.
---
# Scripts that call tools

You can drop into JavaScript when arithmetic, sorting, filtering, or
multi-step tool plumbing would be tedious to express turn-by-turn.

Two execution surfaces, picked by which tool you call:

- `execute_javascript code=…` — runs on the **brain**. Host binding:
  `vance.*`. Can call any server tool you're allowed to use.
- `execute_scratch_javascript path=…` — same engine, but loads the
  source from a `.js` file in the project scratch area. Stack traces show
  the file path. Pairs with `scratch_write` for iterate-and-rerun.
- `client_javascript code=…` — runs on the **foot** (user's machine).
  Host binding: `client.*`. Sees only `client_*` tools registered
  locally on that foot.

The last expression's value is the tool's `value` field.

## Brain — `vance.*`

```js
let hits = vance.tools.call("research_search", { query: "graaljs sandboxing" });
hits.results.length;
```

`vance.tools.call(name, params)` goes through the same allow-filter
your LLM tool calls use. If the recipe restricts the tool, the script
hits the same wall.

```js
vance.context.tenantId;       // String
vance.context.projectId;      // String|null
vance.context.sessionId;      // String|null
vance.context.processId;      // your think-process id
vance.context.userId;         // String|null
```

Read-only. Identity comes from the surrounding process — the script
cannot escape its scope by passing different ids to a tool.

```js
vance.log.info("step done", { count: 3 });    // brain log only
vance.log.warn("retrying", { reason: "rate-limit" });
```

```js
let h = vance.process.spawn({
  recipe: "analyze",
  task: "Find contradictions in recent memory entries"
});
h.processId;
```

Convenience wrapper over `vance.tools.call("process_create", ...)`.
Spawn returns immediately — don't synchronously wait on the
sub-process's result; that's a multi-turn LLM job, not one script.

### Reading and writing project documents

JS scripts also see `vance.documents.*` for direct access to the
project's documents (read, write, list, exists, delete, meta). When
you need to read input data or persist output as a document instead
of just returning a value, load `manual_read('script-document-api')`
for the full surface and path conventions.

### Synchronous one-shot LLM calls

`vance.llm.call(recipe, prompt, vars?)` and
`vance.llm.callForJson(recipe, prompt, vars?)` give you a synchronous
LLM verdict inside the script — classification, title-generation,
schema-validated extraction. No process spawn, no lane lock. The
recipe must be marked `internal: true`. Load
`manual_read('vance-script-llm')` for signatures, recipe shape,
schema-loop semantics, and the when-NOT-to-use list.

### Dropping items into a user's inbox

`vance.tools.call('inbox_post', {...})` posts a structured inbox
item. The `type` field is a closed enum (`APPROVAL`, `DECISION`,
`FEEDBACK`, `OUTPUT_TEXT`, ...) — freetext types are rejected. Load
`manual_read('inbox-post')` for the full type list, payload shapes,
and ask-vs-output semantics.

### Reading settings (configurable scripts)

`vance.settings.get(key)`, `getInt`, `getLong`, `getDouble`,
`getBoolean` read from the settings-cascade
`think-process → project → _tenant`. Pairs with Setting-Forms
(Web-UI Workspace tab) so users configure a script without editing
its source. Load `manual_read('vance-script-settings')` for the
typed-accessor list, cascade semantics, and the kit-default +
scheduler-override pattern.

## Foot — `client.*`

```js
let listing = client.tools.call("client_exec_run", {
  command: "ls",
  cwd: "/tmp"
});
listing.exitCode;
```

```js
client.context.requestId;     // UUID — correlates with brain logs
client.context.sessionId;     // String|null
client.context.projectId;     // String|null
```

Only `client_*` tools that the foot registered locally are reachable.
`client.tools.call("research_search", ...)` throws — `research_search`
is a server tool, the foot has no idea about it. `vance` is `undefined`
on the foot.

`client.log.info(...)` / `.warn` / `.error` write to the foot log.
Not auto-mirrored to the brain — return a value from the script if
you want the brain to see it.

## Errors

Tool failures surface as plain JS `Error`s. Catch them:

```js
try {
  let r = vance.tools.call("docs_internal", { id: 42 });
} catch (e) {
  vance.log.warn("docs_internal failed", { msg: e.message });
}
```

If the script itself throws uncaught, the tool returns:

```json
{ "error": "GUEST_EXCEPTION", "message": "Script raised: Error: ..." }
```

Other `error` codes: `TIMEOUT`, `RESOURCE_EXHAUSTED`, `HOST_EXCEPTION`,
`CANCELLED`.

## Limits and what you can't do

- **Timeout** — default 5s wall-clock, max 30s via `timeoutMs`.
- **Statement budget** — ~1M statements before `RESOURCE_EXHAUSTED`.
- **No** Java interop, no `Java.type(...)`, no `java.lang.System`.
- **No** filesystem (use `scratch_*` / `client_file_*` tools).
- **No** network (use `research_search` etc.).
- **No** threads, no `setTimeout` long-poll — script runs straight
  through, then exits.
- **No** module loading (`import`, `require`). One flat source.
- **No** shared globals between runs — every run sees a fresh
  `globalThis`.

## When the surface is missing

If you need something `vance.*` / `client.*` doesn't expose, the answer
is almost always: **build a tool for it**, not extend the host API.
Tools are the only sanctioned bridge between scripts and Vance
internals.

## Patterns

### Crunch a scratch file, write a memory

```js
let raw = vance.tools.call("scratch_read", { path: "data.csv" }).content;
let rows = raw.split("\n").slice(1)
  .filter(l => l.trim())
  .map(l => {
    let [name, count] = l.split(",");
    return { name: name.trim(), count: parseInt(count, 10) };
  });
let top = rows.sort((a, b) => b.count - a.count).slice(0, 10);

vance.tools.call("memory_add", {
  text: "Top-10 by frequency: " + top.map(r => r.name).join(", "),
  tags: ["analysis", "csv"]
});
top;
```

### Fan out across local files (foot)

```js
let entries = client.tools.call("client_file_list", { path: "." }).entries;
let out = [];
for (let f of entries) {
  if (f.endsWith(".log")) {
    let r = client.tools.call("client_exec_run", {
      command: "wc",
      args: ["-l", f]
    });
    out.push({ file: f, lines: r.stdout.trim().split(/\s+/)[0] });
  }
}
out;
```

### Iterate on a scratch script

```text
1. scratch_write path=compute.js content="let x = 2+2; x;"
2. execute_scratch_javascript path=compute.js
   → { "value": 4, "durationMs": 3, "path": "compute.js", ... }
3. scratch_write path=compute.js content="..."   # tweak
4. execute_scratch_javascript path=compute.js    # rerun
```
