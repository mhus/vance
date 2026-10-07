# Chat theme

One CSS file under `_vance/chat-themes/<name>.css` — the chat
transcript styling for the recipe with `webTheme: <name>`. The name
is at the same time the recipe key — lowercase, digits, hyphens
only.

## Shape

Plain CSS, layered on top of the transcript's default styling. You
override what you want different; everything unmentioned stays. The
shell's dark-mode switch applies here too — write your colors so they
work in both modes (or override the dark classes explicitly).

## Behavior

- **Fail-open:** invalid CSS silently falls back to `default.css` —
  no validator, no error, just missing styling. When in doubt, check
  against the browser.
- Project overrides tenant overrides bundled themes: same name wins
  from the inside.
