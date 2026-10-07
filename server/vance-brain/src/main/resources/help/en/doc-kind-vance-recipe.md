# Recipe

One file under `_vance/recipes/<name>.yaml` — a named configuration
bundle on top of an engine. The file's name is the name spawns refer
to (`recipe: <name>` in prompts, tools and scheduler definitions).

## Core fields

- `title` — display name.
- `description` — **required.** One sentence on what the recipe does.
- `engine` — **required.** The engine driving it (`arthur`, `ford`,
  `vogon`, `marvin`, …). An unknown engine makes the recipe silently
  unspawnable.
- `listed: true` — shows the recipe in pickers; `false` hides it
  while it stays spawnable.
- `params` — parameter schema (map).
- `promptPrefix` — Pebble template prepended to the engine prompt.
  Syntax errors break every spawn.

## Cascade

Project overrides tenant overrides classpath: same name wins from the
inside. Overriding a bundled recipe here is the way to adapt bundled
behavior locally without touching the original.
