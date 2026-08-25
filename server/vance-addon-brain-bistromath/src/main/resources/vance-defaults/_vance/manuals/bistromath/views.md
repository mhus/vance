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
| `toolbar` | `children` | controls in a line; sizes to content, wraps |
| `row` | `children` | children side by side, sharing the width evenly |
| `column` | `children` | children stacked — a pane of a `tabs`, a cell of a `row` |
| `card` | `title?`, `children` | a titled box around them |
| `button` | `label`, `on.click` | |
| `text` | `text` **or** `from` | literal, or a state value |
| `markdown` | `text` **or** `from` | rendered **the Vance way** — see below |
| `table` | `from`, `columns?`, `on.rowClick` | sortable by header, filter box above 10 rows |
| `input` | `from`, `label?` | one text field, writes a **string** |
| `number` | `from`, `label?` | one number field, writes a **number** or `null` |
| `toggle` | `from`, `label?` | a checkbox, writes a **boolean** |
| `select` | `from`, `options`, `label?` | a choice, writes the chosen **value** |
| `badge` | `text` **or** `from`, `variant?` | a short coloured label |
| `alert` | `text` **or** `from`, `variant?` | a message with a severity |
| `code` | `text` **or** `from`, `language?` | read-only source, highlighted |
| `pagination` | `from` | a page switcher over `{page, pageSize, totalCount}` |
| `file` | `from`, `accept?`, `label?` | pick a **text** file, program gets its content |
| `form` | `from`, `fields`, `on.change?` | a whole field list — **editable** |
| `details` | `from`, `fields` | the same field list, read-only |
| `tabs` | `children` | each child's `label` captions its tab |
| `repeat` | `from`, `children` | children once per element of a list |
| `embed` | `text` **or** `from` | another document, rendered by its kind |
| `dialog` | `show`, `children` | shown over the page while its key is true |

Each one's details — what `columns:` does, how a `form` differs from a
`details`, what a `repeat` scopes — are in three manuals, split by what you are
after: `manual_read('widgets')` for data and input, `manual_read('layout')` for
arranging, `manual_read('content')` for showing.

`title:` and `label:` are the same field — use whichever reads better.

**Reserved but not rendered:** `chart`. Using it is refused with a message
saying it arrives later — that is different from an unknown widget, so do not go
looking for a typo. (For a chart today: put the data in a chart document and
`embed` it.)

## `show:` — one condition, no expression

Any widget takes `show: <state key>`. The **program** computes the boolean; the
widget reads it.

```yaml
  - type: button
    label: Delete…
    show: hasSelection
    on: { click: "main.js:askDelete" }
```

```js
await vance.state.set('hasSelection', Boolean(row));
```

`visibleIf` is **refused** and its message says this. A condition here is never
an expression — that would be a second expression language in the browser, and
there is already exactly one: the sandbox's.

**An unset key means hidden.** A widget whose condition nobody has computed yet
stays away. Briefly missing is a mistake you can see and explain; briefly
showing what the document says to hide is not.

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

Editing an **existing** view takes effect on its own — the app watches its own
folder and reloads when one of its own documents changes.

`app_rebuild(folder=…)` is for the case that watching cannot cover: a
**newly added** view, which no scan knows about yet. It re-reads every view and
names what does not parse.

A view document on its own is also checked by `kind_validate` — the kind
`app-view` is registered, so the parser runs without an app around it. Opening
one in the Cortex shows a **preview** against empty state: the real widgets,
drawn by the real renderer, with no program running.
