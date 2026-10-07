# Report theme

One CSS file under `_vance/report-themes/<name>.css` — the PDF
export's styling, picked via the document's `theme` key. The name
follows the resolver grammar (`[a-z0-9-]+`).

## Print CSS

The renderer (openhtmltopdf) interprets a deliberate subset: page
margins (`@page`), font families and sizes, colors, table styling,
headings. No JS, no animations, no viewport layout — paper has no
window.

## Layers

1. the renderer's `default.css`,
2. the theme chosen here,
3. a `css:` ref in the document itself.

Later layers win. Whatever a layer does not define falls through.
Invalid CSS gets silently ignored — checking means rendering a test
PDF.
