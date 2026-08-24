---
name: views
summary: The widget tree of a view document — every widget, the one binding, and the handler grammar.
triggers: you are writing or changing a view of a custom application, adding a widget, or wiring a button to the program
---

# Views

A view is a document with `$meta.kind: app-view` holding a widget tree. It may
sit anywhere under the app folder; the **file name is its handle** and must be a
slug (lowercase, digits, `-`, `_`), because that handle appears in links.

```yaml
$meta:
  kind: app-view
type: page
title: Invoices
children:
  - type: toolbar
    children:
      - type: button
        label: Reload
        on: { click: reload }
  - type: table
    from: invoices
    columns: [nr, customer, amount]
    on: { rowClick: "navigate:detail" }
```

## The one binding: `from:`

A widget shows **state**; `from: <key>` names it. Not a path, not a table name,
not an expression — a name the program wrote with
`vance.state.set('<key>', value)`.

## Widgets

| Widget | Carries | Notes |
|---|---|---|
| `page` | `title`, `children` | root of a view |
| `toolbar` | `children` | a row of controls |
| `button` | `label`, `on.click` | |
| `text` | `text` **or** `from` | literal, or a state value |
| `markdown` | `text` **or** `from` | rendered, sanitised |
| `table` | `from`, `columns?`, `on.rowClick` | `from` must hold an array of objects |
| `form` | `from`, `fields` | read-only in this build |
| `tabs` | `children` | each child's `label` captions its tab |

`title:` and `label:` are the same field — use whichever reads better.

**Reserved but not rendered:** `if`, `repeat`, `chart`, `dialog`. Using one is
refused with a message saying it arrives later — that is different from an
unknown widget, so do not go looking for a typo.

### `table`

`columns:` fixes the order. Without it, the union of the keys present is used.
A named column the rows do not have shows as empty cells rather than
disappearing, so a typo looks like a typo.

A row's `key` field identifies it; `vance.documents.list` already returns one.

### `form`

`fields:` is the ordinary form-engine field list (the one wizards and setting
forms use). `from:` names the record: an **object** is shown directly, an
**array** is indexed by the record key in the entry handle — which is how
"click a row, see it in a form" works.

Fields carrying `showIf`, `writeIf`, `bindsTo` or `choicesFrom` are **refused**:
those belong to setting forms, the shared parser does not read them here, and a
silently dropped condition would show a field you told it to hide.

## Handlers

```yaml
on:
  click: "main.js:save"
  rowClick: "navigate:detail"
```

| Form | Does |
|---|---|
| `reload` | re-read the view and restart the program |
| `navigate:<handle>` | open another view of this app |
| `<program>:<function>` | call a top-level function in the program |

**The separator is `:` — not `#`.** A `#` is a URI fragment and would be
discarded, taking the function name with it.

`navigate:` is recognised before the generic split, so `navigate:edit` is a
navigation, not a call to a script named "navigate".

A `rowClick` that navigates **carries the clicked row's key**, so the target
view can bind a `form` to that record. This is a convention, not a template:
`navigate:edit/{key}` is not a thing.

## What is not here

No `visibleIf` — a condition nothing evaluates would show exactly what you told
it to hide, so it is refused. No `source:` — it named a folder or a declared
table, and both concepts are gone. No layout properties: a view says *what*,
the renderer decides *how*.

## After editing

`app_rebuild(folder=…)` re-reads every view and names what does not parse.
Nothing else validates a view document.
