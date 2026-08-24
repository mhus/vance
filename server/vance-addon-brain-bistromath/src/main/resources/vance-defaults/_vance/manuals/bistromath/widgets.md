---
name: widgets
summary: Each Bistromath widget in detail — table columns, form vs details and editing, repeat scope, embed paths, dialog.
triggers: you are writing a specific widget into a view and need its keys, editing is not working, or you want to show a list, another document or a confirmation
---

# Widgets in detail

The list of widgets and the rules that apply to all of them —
`from:`, `show:`, handlers — are in `manual_read('views')`. This manual is the
per-widget part.

## `table`

`columns:` fixes the order. Without it, the union of the keys present is used.
A named column the rows do not have shows as empty cells rather than
disappearing, so a typo looks like a typo.

A row's `key` field identifies it; `vance.documents.list` already returns one.

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

## `embed`

Another document, rendered by whatever knows its kind — Markdown, an image, a
mindmap, a canvas:

```yaml
  - type: embed
    text: "reports/q3.md"      # or: from: someStateKey
```

The path is the **same grammar as everywhere else in an app**: relative to the
app folder, a leading `/` for the project root.

This is why there is no `chart` and no `image` widget. A chart document and an
image document already have renderers; duplicating them here would mean this
addon shipping a charting library.

## `dialog`

Children shown over the page while its `show:` key is true:

```yaml
  - type: dialog
    title: Delete this invoice?
    show: confirmOpen
    children:
      - { type: text, text: "This cannot be undone." }
      - type: toolbar
        children:
          - { type: button, label: Delete, on: { click: "main.js:doDelete" } }
          - { type: button, label: Cancel, on: { click: "main.js:closeDialog" } }
```

```js
async function askDelete()   { await vance.state.set('confirmOpen', true); }
async function closeDialog() { await vance.state.set('confirmOpen', false); }
```

There is **no** `dialog:` handler and no `vance.ui.closeDialog()`: a dialog is a
widget whose condition happens to be interesting. The ✕ writes the same key back
to false. `show:` is required — without it there would be no way to close it.
