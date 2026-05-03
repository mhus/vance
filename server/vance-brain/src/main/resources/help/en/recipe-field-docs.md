# Recipe field reference

A recipe is the configuration bundle a process is spawned with. It picks
the engine, sets default parameters, may add a system-prompt fragment,
and adjusts the engine's tool whitelist. Tenant and project recipes are
copy-on-write overrides on top of the bundled catalog.

## Required

### `description`
*string, required* — one-line human description shown in selectors and
admin UI.

### `engine`
*string, required* — name of the engine that runs this recipe. Common
values: `ford`, `arthur`, `marvin`, `vogon`, `zaphod`. Must be a
registered engine on the brain.

### `promptMode`
*enum (`APPEND` | `OVERWRITE`), required, default `APPEND`* — how
`promptPrefix` is combined with the engine's built-in system prompt.
- `APPEND`: engine prompt first, recipe prefix appended after a
  separator. Engine hard-rules stay binding; the recipe specialises a
  role on top.
- `OVERWRITE`: engine prompt is dropped entirely; the recipe carries
  the whole system prompt. Use only when you intend to replace the
  engine's built-in behaviour wholesale — engine hard-rules must be
  re-stated by the recipe itself.

## Defaults & flags

### `params`
*map, optional, default `{}`* — engine-specific defaults applied when a
process is spawned via this recipe. Caller-supplied params override
per-key, unless `locked` is `true`.

Common keys (engine-dependent):
- `model`: model alias like `default:fast`, `default:analyze`, or a
  direct `<provider>:<model>` reference.
- `maxIterations`: per-engine tool/loop budget.
- `validation`: per-engine validator toggles.

### `locked`
*boolean, optional, default `false`* — when `true`, caller params are
ignored on spawn. Use for compliance-critical recipes whose runtime
behaviour must be deterministic.

### `tags`
*list of strings, optional* — free-form discovery tags shown in the
recipe selector. Examples: `research`, `code`, `web`, `analysis`.

## Tool adjustments

### `allowedToolsAdd`
*list of strings, optional* — tool names added to the engine's default
allow-list. Useful when a specialised recipe needs an extra capability
(e.g. `web_search` for a research recipe).

### `allowedToolsRemove`
*list of strings, optional* — tool names subtracted from the engine's
default allow-list. Use when a recipe should run with strictly fewer
capabilities than the engine default.

## System-prompt overrides

### `promptPrefix`
*string, optional* — system-prompt fragment carried into the spawned
process. Combined with the engine prompt according to `promptMode`.

### `promptPrefixSmall`
*string, optional* — variant for `SMALL` model class (Haiku-/Flash-
style). When the recipe is resolved to a small model, the engine uses
this string instead of `promptPrefix`. `null` means "use `promptPrefix`
for all sizes".

## Validator overrides

### `dataRelayCorrection`
*string, optional* — override for the engine's data-relay-gap
validator message (fires when tools returned a lot of data but the
reply is brief, indicating the engine swallowed the tool result).
`null` keeps the engine's hard-coded default. The intent-without-
action heuristic was retired in favour of the structured `respond`
tool — see `specification/structured-engine-output.md`.
