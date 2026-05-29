---
triggers: excel, xlsx, exportiere als excel, export as excel, excel datei, spreadsheet, records als tabelle, records as table, save as xlsx, excel runterladen, download excel
summary: Export a kind:records document as a downloadable Excel (.xlsx) file with header row + auto-filter.
---
# Tool — `xlsx_from_records`

Render a `kind: records` document as Excel. Header row gets the
schema fields, each record becomes one row. The result is a real
`.xlsx` (Apache POI XSSF, no external binary) that opens in Excel,
LibreOffice, Pages, or Google Sheets.

## When to use this

- "Mach mir das als Excel" / "Export this records doc as XLSX".
- "Ich will die Tabelle in Excel öffnen / mit Filtern arbeiten /
  Charts daraus bauen".
- Records-Output an Kollegen schicken, die keinen Vance-Account
  haben — sie öffnen die XLSX lokal.

## Parameters

| Param | Type | Required | Notes |
|---|---|---|---|
| `documentRef` | string | yes | Path or id of the source `kind: records` document |
| `projectId` | string | no | Project name; defaults to active project |
| `title` | string | no | Sheet name + file's core-title. Excel hard-caps sheet names at 31 chars (auto-truncated). Falls back to source document title or path leaf |
| `outputPath` | string | no | Where to file the result. Default: `reports/<title-slug>-<timestamp>.xlsx` |

## Returns

```
{
  path:         "reports/sales-q3-2026-05-28-201234.xlsx",
  size:         12345,
  rows:         42,
  columns:      5,
  elapsedMs:    87,
  vanceUri:     "vance:/reports/...",
  markdownLink: "[sales-q3-...](vance:/reports/...)"
}
```

Embed `markdownLink` verbatim in your answer so the user can
download the file directly.

## Examples

### Export a records document

```
xlsx_from_records(documentRef="data/inventory.json")
```

Then in your reply:

```
Hier die Excel-Version:
[inventory-2026-05-28-201234.xlsx](vance:/reports/...)
```

### With a custom title

```
xlsx_from_records(
  documentRef="data/inventory.json",
  title="Inventur Q3 — Vorab"
)
```

The sheet inside Excel is named "Inventur Q3 — Vorab", same string
goes into the document core properties (visible in Excel's File →
Info).

## Sheet shape

- **Row 1**: bold header with the records schema columns
- **Rows 2..N**: one row per record, values in schema order, missing
  fields = blank cells
- **Frozen top row** + **auto-filter** turned on so the user can
  sort / filter without setup
- **Overflow values** (from markdown records that had more values
  than the schema declared) land in extra columns labelled
  `(overflow 1)`, `(overflow 2)`, … so nothing is silently lost
- **Auto-sized columns** capped at ~80 chars wide

## Anti-patterns

- **Don't paste records data inline as a code-block.** The user
  asked for Excel; call the tool. If you need the records first,
  use `doc_create_kind(kind="records", ...)` or `records_add_row`
  to build them, then call `xlsx_from_records`.
- **Don't call this for non-records documents.** The tool rejects
  documents whose codec doesn't support the records shape (i.e.
  wrong mime / missing `schema`). For free-form tables in markdown
  or DOCX, use `report_from_markdown` instead.
- **Don't request schema-less records.** A records document
  without `schema` has no column structure — the tool errors out
  with a clear message instead of guessing.

## Failure modes

| Symptom | Likely cause | Recovery |
|---|---|---|
| "Source document … not found" | Path/id mismatch | List documents to find the right reference |
| "… is not a records document" / "doesn't support" mime | Wrong document type | Convert / re-create as `kind: records` |
| "has no schema — cannot render an empty header row" | Records document is missing the `schema` field | Add `schema: [field1, field2, …]` to the source |
| "XLSX rendering failed" | POI internal — extremely rare, log will have the stack | Show the user honestly, investigate the log |

## Not in this iteration

- **Typed columns** (number / date / boolean). The records-codec
  is all-strings in v1; XLSX cells are written as strings too.
  Spec §6.1 in `doc-kind-records.md` tracks the type-system step.
- **Formulas / cell-formatting / charts** in the output. The
  export is plain data — let the user add those locally in Excel.
- **Multi-sheet** workbooks. One records doc → one sheet. For
  multiple sheets the LLM would call this tool repeatedly and
  the user could combine the resulting files locally; an explicit
  multi-source variant is a future iteration.
- **CSV / TSV alternative formats**. POI doesn't write those; if
  someone asks for CSV specifically, `report_from_markdown` with
  a Markdown-table source approximates it, or wait for the next
  iteration.
