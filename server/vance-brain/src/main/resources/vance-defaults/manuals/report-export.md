---
triggers: report, Bericht, abgabe, submission, PDF generieren, PDF erzeugen, generate pdf, create pdf, docx, Word document, word datei, export as pdf, export as docx, hausarbeit, thesis, paper, abgabe als pdf, downloadable report
summary: Render a markdown report into PDF (final, for submission) or DOCX (editable, for local polish in Word/Pages/LibreOffice), auto-imported as a Vance Document.
---
# Tool — `report_from_markdown`

Turn a markdown source into a downloadable report file — **PDF**
for final submission, **DOCX** for local nachbessern. The result
is auto-imported as a Vance Document; you embed the
`markdownLink` in your answer and the user can download with one
click.

## When to use this

- "Mach mir das als PDF" / "Generate a PDF of this".
- "Ich brauche das als Word-Dokument zum Bearbeiten" /
  "Export this as docx so I can edit it locally".
- "Schreib mir die Abgabe / Hausarbeit / Thesis-Kapitel".
- "Save my analysis as a downloadable report".

## Format choice — PDF vs. DOCX

| Format | Best for | Editable? |
|---|---|---|
| `pdf` | final submissions, hand-in versions, archive | not really |
| `docx` | drafts the user wants to polish locally in Word / Pages / LibreOffice | yes |

The user often wants **both**: a DOCX to clean up and a PDF after.
Do that as two calls — same `markdown`, different `format`. Each
becomes its own Vance Document, both download links can go in the
same chat reply.

## Parameters

| Param | Type | Required | Notes |
|---|---|---|---|
| `format` | string | yes | `"pdf"` or `"docx"` |
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
Hier ist die Abgabe:

- [thesis-final.pdf](vance:/reports/...)  ← die Endversion
- [thesis-draft.docx](vance:/reports/...) ← falls du noch was anpassen willst
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
- Dezent code blocks with light grey background
- Tables with grid borders and header shading

Templates (Thesis / Letter / Compact) are planned for a later
iteration; for now there's one well-tuned default.

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
