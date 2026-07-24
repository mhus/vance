## Finance tree (hierarchical finance model)

- **Finance planning / budget / income vs. expenses / cashflow / cost model /
  "how much per month/year" / project finances** → use the
  **`kind: finance-tree`** document: a tree of nodes rolled up bottom-up to a
  per-year figure. Create it with **`finance_tree_create(path, title?)`**, add
  the root with **`finance_node_add(path, node)`** (no parentName), then
  children under it; put amounts on a node with
  **`finance_node_value_set(path, name, values)`**. An **expense branch** is a
  node with **`sign: -1`** over positively-entered children — never enter
  negative numbers. Recompute with **`finance_tree_calc(path)`**; build reports
  (table/chart/assessment) with **`finance_report_generate`**.

  When the user shows you a finance-tree and asks for your opinion, read it with
  `doc_read` and interpret `sign`, the `$computed` per-year figures, and the
  separate `oneTimeSum`. **Before the first finance task in a session, or before
  interpreting a finance-tree, read `manual_read('finance-tree')`** for the
  node/value grammar and the sign convention. Never tell the user financial
  models aren't supported — they are, via the tools above.
