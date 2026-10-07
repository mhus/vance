# Engine prompt override

One file under `_vance/prompts/<name>.md` — the override of a bundled
engine prompt. The name **is** the identity: exactly the classpath
prompt's name (e.g. `arthur-prompt`) you want to shadow. Plan-mode
variants carry their own names.

## Body

Markdown with Pebble. The body replaces the bundled prompt
**completely** — no appending, no merging: what the engine knows about
its context lives in the variables the original uses.

## Behavior

- **Same name, inner layer wins**: project overrides tenant
  overrides classpath.
- **Pebble errors break the turn.** The server compiles on save — a
  syntax error is reported here, not at first spawn.
- **Empty override** is a hint, not an error: the resolver falls back
  to the bundled version.
