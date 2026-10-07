# Area: Prompts

The engine prompt overrides under `_vance/prompts/` — **one file per
overridden engine prompt**. Create is the **override workflow**: the
name (e.g. `arthur-prompt`) shadows the bundled classpath prompt by
name — same file, own layer. Every spawn of the scope then gets your
text instead of the original.

## Raw text, deliberately

Engine prompts are Markdown with Pebble variables — the raw editor
(Markdown highlighting) is the honest tool here.

## Pitfalls

- The name must match the bundled prompt **exactly** — a typo creates
  a prompt no engine will ever read.
- Pebble syntax errors **break the turn**, they do not fall back.
  The server compiles the body on save and reports the error before
  the first spawn.
- An **empty** override is allowed and reported: the resolver
  filters blank content, the bundled version stays in charge.
