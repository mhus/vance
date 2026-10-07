# Hook

One file under `_vance/hooks/<event>/<name>.yaml` — an event hook.
The event comes from the path (not the body), the hook name from the
file name.

## Body

**Exactly one TriggerAction** at the top level:

- `recipe: <name>` — spawns a ThinkProcess.
- `workflow: <name>` — starts a Magrathea workflow.
- `script: { … }` — runs a JS script (source/path per the
  trigger-action schema).

Plus optional: `description`, `enabled` (default true), `timeout`
(seconds, default 5, max 30), `tags`.

## Behavior

- The registry lists **per event** — a path under an unknown event is
  invisible without the body ever being read.
- a body that does not parse is **silently skipped** (WARN log): the
  hook is simply missing. The server reports the same errors on
  save — see it early instead of hunting later.
- the legacy schema (`type: js|llm`) is refused with a migration
  hint.
