# Area: Report themes

The report themes under `_vance/report-themes/` — **one CSS file per
theme** for the PDF export (openhtmltopdf). A theme is picked via
the `theme` key in the document's front matter or the export
settings.

## Raw text, deliberately

A theme is CSS — raw editor with CSS highlighting, same name grammar
as the chat themes (`[a-z0-9-]+`).

## Careful: this is print CSS

The PDF renderer understands a **partial subset** of CSS — no
animations, no JavaScript, no flexbox finesse. Font, margin, color,
tables: those are the tools. What the renderer does not know gets
silently ignored (fail-open to `default.css`). That is why there is
deliberately no server validator here.

## Layers

`default` → theme → the document's `css:` ref — later layers win.
Project overrides tenant overrides bundled themes.
