---
triggers: finance tree, financial model, budget, income and expenses, cashflow, cost planning, project finance, revenue vs cost, financial projection, how much per month/year, finance-tree document
summary: Vance "finance-tree" — a hierarchical income/expense model (kind: finance-tree) computed bottom-up to a canonical per-year rate. Read this to interpret a finance-tree document the user shows you, or before building/editing one with the finance_* tools.
---
# Kind `finance-tree` — hierarchical finance model

A **`kind: finance-tree`** document is a tree of income/expense nodes that the
system rolls up **bottom-up** into a canonical **per-year** figure. Use it for
project finance planning, budgets, cashflow and cost/revenue modelling — a
*thinking / modelling* tool, not bookkeeping.

## The model

**Node** — one card in the tree. Fields: `name` (unique key in the tree),
`title`, `icon`, `color`, `description`, `notesRef` (link to a notes doc), and
`sign` (`+1` / `-1`). A node's **sign flips its whole contribution**:

```
total(node) = sign × ( Σ own values + Σ children totals )
```

So an **expense branch** is a node with `sign: -1` over children whose amounts
you enter as **positive** — the `-1` makes the subtree subtract. (There is no
need to enter negative numbers.)

**Value record** — an additive amount on a node. Two modes:
- `recurring` (default): a **rate** — `value` per `period` (`{count, unit}`,
  unit ∈ day/week/month/year), e.g. `800 / 1 month`. Optional `validFrom` /
  `validTo` (ISO `yyyy-MM-dd`) bound when it applies.
- `one_time`: a **lump** at a date (`validFrom` required); it does **not** enter
  the per-year rate — it is reported separately as `oneTimeSum`.

Optional per-record `sign` (escape hatch) flips just that record. Optional
`interest` `{rate (%), period, basis, compound}` is tracked **separately** from
the base amount (base vs. interest stay distinguishable).

**Fixed year:** 1 year = 365 days = 12 months = 52 weeks (so a month is
365/12 ≈ 30.42 days). Everything normalises onto this.

## Computed values (`$computed`)

Running the snapshot writes a `$computed` block: per node `perYear` /
`perMonth` / `perWeek` / `perDay` (sign-rolled), `base` + `interest` split, and
`oneTimeSum`. **`$computed` is derived** — never edit it; recompute instead.

## Working with it

Never hand-write the YAML — use the tools:
- `finance_tree_create(path, title?)` — new empty tree.
- `finance_node_add(path, node, parentName?)` — add root (no parentName) or a
  child; set `sign: -1` for expense branches.
- `finance_node_value_set(path, name, values)` — set a node's value records.
- `finance_node_update` / `finance_node_remove` — edit / delete.
- `finance_tree_calc(path)` — recompute the snapshot ("reload").
- `finance_report_generate(path, processor, params)` — build a report
  (`table`→sheet, `series`→chart, `assessment`→markdown …); list them with
  `finance_report_processors`.

Read the current state with `doc_read` — the tree is plain YAML. When giving an
opinion, interpret `sign`, the per-year figures under `$computed`, and keep
`oneTimeSum` separate from the recurring rate.
