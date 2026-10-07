# Area: Chat themes

The chat themes under `_vance/chat-themes/` — **one CSS file per
theme** for the chat transcript. A theme is picked via the recipe
field `webTheme` (no in-chat switch): the recipe that spawns carries
the theme name, the session renders with it.

## Raw text, deliberately

A theme is CSS — raw editor with CSS highlighting. The name check on
create follows the resolver's grammar: lowercase, digits and hyphens
only (`[a-z0-9-]+`).

## Behavior

- **Dark mode is a contract**: the layers honor the shell's dark-mode
  classes — a theme that ignores them glows at night.
- The pipeline is **fail-open**: an invalid theme silently falls back
  to `default.css`. That is why there is deliberately no server
  validator here — no finding could say anything the runtime has not
  already absorbed.
- Theme files carry **no kind marker** (CSS has nowhere to put one)
  — the identity lives in the file name and the recipe's `webTheme`
  field.
