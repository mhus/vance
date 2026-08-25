---
name: widgets
summary: Each Bistromath widget in detail — table columns, form vs details and editing, repeat scope, embed paths, dialog.
triggers: you are writing a specific widget into a view and need its keys, editing is not working, or you want to show a list, another document or a confirmation
---

# Widgets in detail

The widget list and the rules that apply to all of them — `from:`, `show:`,
handlers — are in `manual_read('views')`. Arranging and showing (`row`,
`column`, `tabs`, `dialog`, `markdown`, `embed`) is
`manual_read('layout')`. This manual is **data and input**.

## `table`

`columns:` fixes the order. Without it, the union of the keys present is used.
A named column the rows do not have shows as empty cells rather than
disappearing, so a typo looks like a typo.

A row's `key` field identifies it; `vance.documents.list` already returns one.

## The four direct inputs

`input`, `number`, `toggle`, `select`. One widget, one state key, **no field
list** — and the value lands in state with its **native type**:

```yaml
- type: row
  children:
    - { type: input,  from: query,  label: Suche }
    - { type: select, from: status, label: Status,
        options: [alle, { value: paid, label: Bezahlt }] }
    - { type: toggle, from: onlyBig, label: "nur > 1000" }
```

```js
const q      = await vance.state.get('query');    // string
const status = await vance.state.get('status');   // string — the VALUE, not the label
const big    = await vance.state.get('onlyBig');  // boolean
const amount = await vance.state.get('newAmount'); // number, or null when empty
```

**Why native and not strings** (the way a `form` works): these write into
*state*, not into a document. A form's values are on their way into a file and
have to round-trip, so they are encoded; these are on their way to your program,
which decides what to do with them. Nothing to preserve, so nothing to encode.

`options` takes either a bare value — which is then also its caption — or
`{value, label}` where the two differ. Not `"paid|Bezahlt"`: a separator inside
a value means everyone has to learn an escaping rule, and YAML can already write
two things.

An **emptied** `number` writes `null`, not `0`. Zero is a number somebody may
have typed on purpose.

All four take `on: { change: "main.js:…" }`, debounced — the running-total and
live-filter case. The handler gets no arguments; it reads the current values
with `vance.state.get`.

**When to use a `form` instead:** you already have a field list — from a kit, a
setting form, or because a record has eight fields and listing them as eight
widgets is noise. The direct inputs are for the two or three controls that
*aren't* a record: a search box, a filter, a mode switch.

## `form` and `details`

Both take `fields:`, the ordinary form-engine field list (the one wizards and
setting forms use), and `from:`, the state key holding the record: an **object**
is shown directly, an **array** is indexed by the record key in the entry
handle — which is how "click a row, see it in a form" works.

The difference is the only thing their names say: **a `form` can be typed into,
a `details` cannot.** Two widgets rather than one with a `readOnly:` flag,
because both defaults for that flag are wrong.

```yaml
  - type: form
    from: draft
    fields:
      - { name: customer, type: string, label: { en: Customer } }
      - { name: amount, type: integer, label: { en: Amount } }
  - type: button
    label: Save
    on: { click: "main.js:save" }
```

**Editing writes state, nothing else.** What the reader types goes straight back
into `draft`; no document is touched until the program says so:

```js
async function save() {
  const draft = await vance.state.get('draft');
  await vance.documents.write(`invoices/${draft.customer}.yaml`, draft);
  vance.ui.notify('Saved.');
}
```

Types survive the trip: an `integer` field hands back a number, a `boolean` a
real boolean. A key the record carries but no field shows is kept, so editing
one field never drops the rest. A field left empty on a record that never had it
stays absent rather than becoming `""`.

`on: { change: "main.js:recalc" }` fires while the reader types — the running
total case. It is debounced, so a word costs one call, not eight; the handler
reads the current values with `vance.state.get`.

A `form` bound to an **array** with no row selected says so instead of showing
empty fields.

Fields carrying `showIf`, `writeIf`, `bindsTo` or `choicesFrom` are **refused**
in both: those belong to setting forms, the shared parser does not read them
here, and a silently dropped condition would show a field you told it to hide.

## `repeat`

Renders its children once per element of a bound list:

```yaml
  - type: repeat
    from: invoices
    children:
      - { type: text, from: customer }
      - { type: text, from: amount }
```

Inside a `repeat`, **`from:` asks the element first** and falls back to the
surrounding state. Two levels, no path syntax — `from: customer` still names a
key, it is just asked of the element.

Use `table` for rows and columns; `repeat` is for anything that is not a table.

