---
triggers: report, Bericht, abgabe, submission, PDF generieren, PDF erzeugen, generate pdf, create pdf, docx, Word document, word datei, export as pdf, export as docx, hausarbeit, thesis, paper, abgabe als pdf, downloadable report
summary: Render a markdown report into PDF (final, for submission) or DOCX (editable, for local polish in Word/Pages/LibreOffice), auto-imported as a Vance Document.
---
# Tool — `report_from_markdown`

Turn a markdown source into a downloadable report file — **PDF**
for final submission, **DOCX** for local touch-ups. The result
is auto-imported as a Vance Document; you embed the
`markdownLink` in your answer and the user can download with one
click.

## When to use this

- "Make this a PDF" / "Generate a PDF of this".
- "I need this as a Word document to edit" /
  "Export this as docx so I can edit it locally".
- "Write the submission / term paper / thesis chapter for me".
- "Save my analysis as a downloadable report".

## Format choice — PDF vs. DOCX

| Format | Best for | Editable? |
|---|---|---|
| `pdf` | final submissions, hand-in versions, archive | not really |
| `docx` | drafts the user wants to polish locally in Word / Pages / LibreOffice | yes |
| `odt` | LibreOffice-native; OpenSource-affine users; same use-case as docx | yes |

The user often wants **both**: a DOCX to clean up and a PDF after.
Do that as two calls — same `markdown`, different `format`. Each
becomes its own Vance Document, both download links can go in the
same chat reply.

## Parameters

| Param | Type | Required | Notes |
|---|---|---|---|
| `format` | string | yes | `"pdf"`, `"docx"`, or `"odt"` |
| `markdown` | string | one of | Inline markdown content |
| `documentRef` | string | one of | Path or id of an existing markdown document to render. Useful when the report has built up over multiple turns as a real Document |
| `projectId` | string | no | Project name for documentRef lookup. Defaults to active project |
| `title` | string | no | Display title — appears on the first page and in the file's metadata. Falls back to a generic name |
| `outputPath` | string | no | Where to file the result. Default: `reports/<title-slug>-<timestamp>.<ext>` |

Exactly one of `markdown` / `documentRef` must be provided.

## Returns

```
{
  format:       "pdf" | "docx",
  path:         "reports/my-report-2026-05-28-201234.pdf",
  size:         123456,
  elapsedMs:    412,
  vanceUri:     "vance:/reports/...",
  markdownLink: "[my-report-...](vance:/reports/...)"
}
```

Embed the `markdownLink` verbatim in your answer to give the user
a downloadable link.

## Examples

### Inline markdown → PDF

```
report_from_markdown(
  format="pdf",
  title="MPG Analysis Q3",
  markdown="# Analysis\n\nThe mean MPG was ...\n\n## Conclusion\n..."
)
```

### From an existing markdown document → DOCX for polish

```
report_from_markdown(
  format="docx",
  title="Bachelor Thesis Chapter 3",
  documentRef="thesis/chapter-3.md"
)
```

### Both formats in one turn

Call the tool twice — once with `format="pdf"`, once with
`format="docx"`. Then in your reply:

```
Here's the submission:

- [thesis-final.pdf](vance:/reports/...)  ← the final version
- [thesis-draft.docx](vance:/reports/...) ← in case you still want to tweak something
```

### Including ggsave/R-output plots

If the markdown source already contains markdown image links
(e.g. plots that `r_script` auto-imported), the PDF and DOCX
embed them as figures automatically:

```
report_from_markdown(format="pdf", title="Sales Q3", markdown="""
# Sales Analysis Q3

## Trend
![sales-by-month.png](vance:/r-outputs/2026-05-28-201115/sales-by-month.png)

## Summary
...
""")
```

## Default look (single theme in this iteration)

- A4 page, 2 cm margins
- Times-style serif body, 11pt, justified, 1.4 line height
- Sans-serif headings
- Page numbers bottom-right
- Subtle code blocks with light grey background
- Tables with grid borders and header shading

The default theme lives at
`_vance/report-themes/default.css` (bundled, overridable via the
document cascade). It is always loaded first; a `theme:` or `css:`
front-matter key adds layers on top — see Report Themes below.

## Report Themes (PDF only)

A markdown report can carry two optional front-matter keys that
select additional stylesheets — **PDF only**, DOCX/ODT ignore them
(they render via a programmatic AST visitor, no CSS path). See the
[`report-themes` manual](report-themes.md) for the operator guide
(how to author and place a theme) and the
[report-themes spec](../../../../../../../../../../specification/public/report-themes.md)
for the architecture contract. Summary below.

```markdown
---
theme: acme
---
# My Report
```

```markdown
---
css: vance:/styles/round-borders.css
---
# My Report
```

Both keys may appear together. Load order (last rule wins the CSS
cascade):

1. `default.css` — always.
2. `_vance/report-themes/<theme>.css` — when `theme:` is set. Resolved
   through the document cascade (project → `_vance` → classpath).
3. The document referenced by `css:` — when set. A `vance:` document ref
   or a bare path, resolved via `DocumentRefResolver` against the
   project root; cross-project via `//authority/`.

### `theme:` — named stylesheet

- Name matches `[a-z0-9-]+` (no slashes, no dots — no path traversal).
- File lives at `_vance/report-themes/<name>.css`.
- Cascade: project overrides `_vance` overrides bundled. A missing theme
  logs a warning and falls back to the default (render never aborts).

### `css:` — per-document override

- A `vance:` document reference (`vance:/styles/round.css`) or a bare
  path (`styles/round.css`) pointing at a CSS document in the caller's
  project (or, with `//authority/`, another project).
- Loads AFTER the theme, so a per-document override beats a per-project
  theme beats the bundled default.
- A missing/unresolvable reference logs a warning and falls back.

### Theme authoring — the one trap

openhtmltopdf parses the HTML document as **XHTML**, including the
`<style>` block. **Do not put angle brackets in CSS comments** —
`/* <name> must … */` breaks the XML parser with a cryptic
"element type name must be terminated" error. Use plain prose in
comments. The bundled `default.css` is the canonical example.

### Front matter is stripped from the body

The renderer strips the `---`-fenced front matter before feeding the
body to commonmark — without this, `theme: acme` would render as a
setext H2 ("theme: acme" as heading text) in the PDF. The `$meta:`
block Vance documents use is stripped by the same pass.

### Bundled examples

- `default.css` — the base theme (always loaded).
- `acme.css` — example override: rounded code blocks + warm accent.

## Anti-patterns

- **Don't render twice when the user wants both formats.** Make
  two tool calls, one per `format`. The MD source is the same; the
  cost is small.
- **Don't include base64-embedded images in the markdown.** Use
  Vance Document links (`![alt](vance:/...)`). The renderers
  resolve them through the storage layer — faster and cleaner.
- **Don't expect Vance kinds inside the report to render visually.**
  Inline ` ```chart`, ` ```mindmap`, ` ```diagram`, ` ```graph`
  blocks come out as styled code blocks in this iteration (Vance-
  Kind server-side rendering is a future iteration). If you want
  charts in the PDF, save them as PNG via `r_script` + `ggsave()`
  first and reference the image.
- **Don't try to format Math/LaTeX yet.** This iteration doesn't
  process inline math (`$…$`) — it'll show up as literal text.
  Math support is planned for a future iteration.
- **Don't bother with `outputPath` unless the user explicitly
  cares.** The default `reports/<slug>-<timestamp>.<ext>` is
  uncluttered and unique.

## Failure modes

| Symptom | Likely cause | Recovery |
|---|---|---|
| "Provide exactly one of 'markdown' or 'documentRef'" | Both or neither were passed | Send exactly one |
| "Unsupported format 'X'" | Typo in format | Use `pdf` or `docx` |
| "Source document '...' not found" | Path/id mismatch | List documents to find the right reference |
| "Report rendering failed" | Markdown syntactically malformed beyond commonmark's recovery | Show the user, sanity-check the source |

## Not in this iteration (see planning/web-office-suite.md)

- ODT (LibreOffice native) — same library family as DOCX, will
  come as a third format.
- XLSX (Excel) — for `kind=records` Documents; Apache POI XSSF is
  already pulled in by the DOCX renderer.
- Template choice (Thesis / Letter / Compact).
- Vance-Kind server-side rendering (chart/mindmap/diagram/graph as
  embedded images instead of code-block fallback).
- Math support (KaTeX/MathJax → SVG/PNG).
- Bibliography / citation handling (BibTeX → CSL).
- Web-office editor embedding (ONLYOFFICE / Collabora) for direct
  in-Vance editing.
- **Web-UI theme preview** — `MarkdownView.vue` strips `<style>` via
  DOMPurify (intentional: untrusted chat/web-fetch content). A live
  theme preview needs a controlled CSS injection point separate from
  the sanitizer, not a sanitizer relaxation. Planned as a separate
  phase. PDF themes work today; the web preview is a future iteration.
