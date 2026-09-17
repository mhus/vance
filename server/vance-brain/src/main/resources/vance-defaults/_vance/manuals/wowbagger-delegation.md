---
triggers: wowbagger, bulk, batch, many records, classify all, jsonl, csv conversion, dataset, parallel workers, bulk processing, records
summary: When to delegate bulk record processing to Wowbagger instead of doing it yourself or a worker — and what a good Wowbagger task looks like.
requires-tools: process_spawn
---

# Delegating to Wowbagger (bulk record processing)

Wowbagger (`recipe: wowbagger`) is a **bulk engine** for large, uniform
record sets — not another chat worker. A chat agent steers a mechanical
worker pool: every record flows through the same small, tool-less model
call, results are published chunk by chunk (crash-safe, visible
progress) and merged into one ordered result document at the end.

## When to pick Wowbagger

Pick Wowbagger when **all** of these hold:

- **Many records, one task**: hundreds to millions of uniform items —
  classify, label, extract, translate, judge, reformat. The SAME
  per-record instruction applies to every record, with no record
  depending on another.
- **The work per record is small**: a fast model can do it in one call
  (a few hundred to a few thousand tokens per record). If a record
  needs tool calls, research or multi-step reasoning, that is not
  Wowbagger work.
- **Line-shaped data**: the source is (or converts to) one record per
  line — JSONL is canonical. Any other shape (CSV, exports): a small
  conversion script produces JSONL first — do that yourself or let the
  Wowbagger agent do it in its session.

Do NOT pick Wowbagger when:

- Only a handful of records — process them directly; a pool for 20
  items is overhead.
- The task needs cross-record context (aggregate, sort-by-meaning,
  summarize a corpus) — that is analysis, not bulk.
- The work is per-record MULTI-step (read a doc, then decide, then
  act) — delegate to a worker per item instead.

## How to delegate

Spawn with `engine: wowbagger` (or recipe `wowbagger`) and put the
**per-record task** into the spawn goal — the Wowbagger agent will
converse with the user in its own session to settle the details
(source, formats, chunk size, threads, model approval). Two things are
worth saying up front:

- **The worker model is gated**: only models on the operator's
  `wowbagger.allowed-models` allowlist run. If your tenant has none
  configured, the run refuses at configure/start — that is by design
  (cost safety), not a bug to work around.
- **The result is a document**: the merged output lands at
  `_wowbagger/<run>/result.jsonl` (or the configured output doc);
  the Wowbagger agent reports the path, counts and token cost when
  finished.

## What comes back

The DONE report carries the run summary: records done/total, failed
chunks, the token cost, and the result document path. Failed chunks are
never silently dropped — they sit in a ledger and the agent re-runs
them (`wowbagger_start reRunFailed`) after fixing the cause. For the
raw numbers at any time, the user can ask the run's process for
`//wowbagger state` (one-liner) or `//wowbagger info` (full picture).
