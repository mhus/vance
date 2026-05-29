---
triggers: transform, transformer, document konvertieren, convert document, document export, dokument in andere format, save as different format, records als excel, markdown als pdf, markdown als word, format conversion
summary: Convert an existing Vance document into a different format (xlsx, pdf, docx) and store the result as a new document.
---
# Tool — `transform_document`

The general-purpose document-format converter. Take an existing
Vance document, hand it to a transformer that knows the (source-
shape → target-format) pair, write the result back as a new
Vance document. Returns the new document plus `markdownLink` for
embedding in your reply.

## When to use this

- The user has a Vance document and wants a different format
  saved alongside ("speicher das als Excel", "make a PDF of this
  document", "convert to Word").
- Both the source and the target are persistent Vance documents
  — when you have inline data, the format-specific tools are
  cheaper:
  - `report_from_markdown(markdown="…", format="pdf"|"docx")` —
    fresh markdown content → PDF/DOCX
  - `xlsx_from_records(schema=[…], items=[…])` — fresh inline
    records data → XLSX
- Use `transform_document` when **both** source and target are
  files-in-Vance, not freshly-generated content.

## Supported conversions

| From | To | Notes |
|---|---|---|
| `kind:records` (md/json/yaml) | `xlsx` | Header row from schema, one body row per record |
| `kind:records` | `pdf` | Records table embedded in an A4 PDF — same theme as `report_from_markdown` |
| `kind:records` | `docx` | Records as a Word table, editable locally |
| `kind:records` | `csv` | RFC-4180 quoting, UTF-8 with BOM so Excel detects the encoding on direct-open |
| markdown document with GFM-table | `xlsx` | First Markdown-table in the body becomes the sheet. Multi-table docs export only the first table (multi-sheet support is on the roadmap). Inline formatting is stripped to plain text. |
| markdown document | `pdf` | A4, Times serif, page numbers — same theme as `report_from_markdown` |
| markdown document | `docx` | POI XWPF, editable locally in Word/Pages/LibreOffice |

More pairs (chart→pdf, mindmap→pdf, …) will be added as
`DocumentTransformer` beans on the brain side.

## Parameters

| Param | Type | Required | Notes |
|---|---|---|---|
| `fromDocument` | string | yes | Path or id of the source document |
| `toDocument` | string | no | Path for the new document. Extension is used to infer `format`. Default: `reports/<source-slug>-<timestamp>.<ext>` |
| `format` | string | no | Explicit target format key (`pdf`, `docx`, `xlsx`). Inferred from `toDocument` extension when omitted; required when neither path nor explicit format determines the target |
| `projectId` | string | no | Source project name; defaults to active project |
| `title` | string | no | Title used by the renderer (sheet name / PDF cover-page) and the new document's `title` field. Falls back to source title |

## Returns

```
{
  from:         "records/inventory.yaml",
  to:           "reports/inventory-2026-05-29-120304.xlsx",
  format:       "xlsx",
  size:         12345,
  elapsedMs:    87,
  vanceUri:     "vance:/reports/...",
  markdownLink: "[inventory-...](vance:/reports/...)"
}
```

Embed `markdownLink` verbatim in your reply.

## Examples

### Records → Excel (path-only, format inferred)

```
transform_document(
  fromDocument="records/inventory.yaml",
  toDocument="reports/inventory-q3.xlsx"
)
```

Inferred `format=xlsx` from the extension. Done.

### Markdown report → PDF, auto-output-path

```
transform_document(
  fromDocument="reports/chapter-3.md",
  format="pdf"
)
```

No `toDocument` → default `reports/chapter-3-<timestamp>.pdf`.

### Markdown → DOCX for local polish

```
transform_document(
  fromDocument="thesis/abstract.md",
  toDocument="reports/abstract-editable.docx"
)
```

User opens the docx locally in Word, polishes typography, sends
to their advisor.

## Anti-patterns

- **Don't use this for fresh inline data.** If the LLM is
  generating the records / markdown right now, call the dedicated
  inline tools (`xlsx_from_records(schema=…, items=…)`,
  `report_from_markdown(markdown=…, format=…)`). They save a
  round trip through the storage layer.
- **Don't pass both `format` and a `toDocument` with conflicting
  extension.** When both are given, `format` wins, but the
  toDocument extension then misleads the user — keep them
  consistent.
- **Don't expect arbitrary conversions.** Only the pairs listed
  above work. For anything else the tool throws a clear
  "no transformer can convert" error.

## Failure modes

| Symptom | Likely cause | Recovery |
|---|---|---|
| "Source document … not found" | Wrong path/id | List documents to find the right reference |
| "Cannot determine target format" | Neither `format` nor a recognised `toDocument` extension | Pass `format` explicitly |
| "Unsupported target format" | Typo / format not yet wired in | Use one of `pdf`, `docx`, `xlsx` |
| "No transformer can convert …" | Source shape doesn't match any (from→to) pair | The error names the source mime / kind — check whether you need a different source format, or whether the pair simply isn't supported yet |
