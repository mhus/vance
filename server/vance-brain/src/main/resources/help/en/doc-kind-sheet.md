# Sheet

A 2D grid with Excel-style cell addresses: `A1`, `B5`, `AB99`. Cells
are stored sparsely — only the ones with content or formatting exist
in the document.

Use it when you need cell references or arithmetic. For a table with
named columns and no addresses, records is
simpler and reads better in markdown.

**JSON and YAML only.** There is no markdown form: a sparse cell list
as a markdown table would be unreadable.

## On disk

```yaml
$meta:
  kind: sheet
schema: [A, B, C]
rows: 5
cells:
  - field: A1
    data: Item
  - field: B1
    data: Qty
  - field: A2
    data: Apples
  - field: B2
    data: "10"
  - field: C2
    data: "=B2*1.5"
```

`field` and `data` are the only required per-cell fields. Addresses are
read case-insensitively and written uppercase; the same address twice
is an error.

Optional per cell: `color`, `background`, `bold`, `italic`, `align`,
`numberFormat`, `borders` (a subset of `trbl`).

## Formulas

A `data` value starting with `=` is a formula. The **string** is what
the document stores — it round-trips exactly as written. Evaluation
happens on the server (Excel-compatible engine) and comes back as a
separate computed overlay, so the source never gets overwritten by a
computed number.

That split is why a formula never silently loses itself: what you
typed stays, what it currently evaluates to is derived.

## In the editor

*View* is a grid: click a cell to edit, formatting via the toolbar,
computed values shown in place with the formula visible on focus.
