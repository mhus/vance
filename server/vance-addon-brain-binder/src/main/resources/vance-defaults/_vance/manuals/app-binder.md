--- 
name: app-binder
summary: The binder app — an ordered, section-grouped list of references to project documents.
---

# Binder App (`app: binder`)

A **binder** is a lightweight *reference* app. Its `_app.yaml` manifest holds an
ordered list of `vance:` refs to arbitrary documents that live **anywhere** in
the project. The binder does **not** hold the documents — it points at them. Each
entry is rendered per-kind (read-only) in the app, with a deep-link into Cortex
for editing.

Use a binder to gather a curated working set: e.g. a `finance-tree` plus its
exported `sheet`/`chart`/`markdown` reports, or a spec plus its supporting notes.

## Manifest

```yaml
$meta: { kind: application, app: binder }
title: "Finanzplanung 2026"
binder:
  landingRef: "vance:/finance/plan.finance-tree.yaml"   # optional default entry
  entries:
    - ref: "vance:/finance/plan.finance-tree.yaml"
    - { ref: "vance:/reports/q1.sheet.yaml", section: "Reports" }
    - { ref: "vance:/reports/q1-verlauf.chart.yaml", section: "Reports", title: "Q1 Verlauf" }
  index: { outputPath: "_index.md" }
```

- `ref` — the target document (canonically `vance:/<path>`). Required.
- `section` — optional sidebar grouping label. Purely organisational — **not a
  scope**, no permissions or cascade. Blank ⇒ the lead ("without section") group.
- `title` — optional display override; falls back to the target's own title.
- Order = array order. A ref whose target was deleted/moved shows as **missing**
  (⚠) — it is not auto-pruned.

## Tools

- `binder_app_create(folder, title?, description?, entries?, landingRef?)` —
  bootstrap the manifest (+ optional initial entries + `_index.md`).
- `binder_entry_add(folder, ref, section?, title?)` — anchor a document
  (idempotent on the target path).
- `binder_entry_remove(folder, ref)` — detach a reference (target untouched).
- `app_rebuild(folder)` — regenerate `_index.md`.

Reorder, re-section, and landing-pin are UI operations (REST) — there are no
LLM tools for them; add/remove is enough for agentic curation.

**To change a document's content, edit the target document with its own tools.**
The binder never edits what it references.
