# Area: Recipes

The recipes under `_vance/recipes/` — **one file per named
configuration bundle**: engine, parameters, prompts and tool
tweaks. Spawning always happens by recipe name; the file name
(without `.yaml`) is that name. This is the **first management
surface** recipes have ever had — before it, the YAML file itself
was all there was.

## Raw text, deliberately

Recipe YAML is the most complex configuration in the system: prompts
with Pebble variables, parameter schemas, tool adjustments, guards,
profiles. No form could render it without silencing half of it —
hence the raw editor with YAML highlighting.

## What the loader demands

- **`description`** and **`engine`** are required — without both the
  recipe **silently fails to spawn** (the loader logs WARN and skips
  it). The server reports exactly that as an error on save.
- `promptPrefix` is Pebble — a syntax error makes the recipe
  unusable and is reported as an error too.
- `params` must be a map.

## Scopes

Project overrides tenant overrides bundled classpath recipes: same
name wins from the inside. The listing shows only the flat files —
the Slart architect's working trees (`_user/`, `_slart/`) stay out.
