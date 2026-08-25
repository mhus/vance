---
name: lib-ui
summary: ui@1 — shaping the rendered view from the program: hide/show, labels, select choices from data, form fields.
triggers: you wrote `@require ui@1`, a select needs choices from documents, fields should appear depending on a value, or you are writing several vance.view.patch calls in a row
---

# `ui@1`

```js
// @require ui@1

async function onCustomerChange(v) {
  await ui.options('contact', await contacts.where({ customer: v.customer }), 'key', 'name');
  await ui.toggle('invoiceBlock', v.customer !== '');
}
```

Requires nothing. It sits on `vance.view.patch`
(`manual_read('bistromath/shaping')`), which is already one call — so this
library is not here to shorten it, but for the shapes that are several calls or
easy to build wrongly.

| | |
|---|---|
| `ui.hide(ids…)` / `ui.show(ids…)` | ids as arguments or one array |
| `ui.toggle(id, visible)` | the branch written once |
| `ui.label(id, text)` / `ui.text(id, value)` | |
| `ui.options(id, rows, valueField?, labelField?)` | a select's choices, from data |
| `ui.fieldOptions(formId, name, rows, …)` | the same for one form field |
| `ui.toOptions(rows, …)` | just the mapping |
| `ui.field(formId, name, patch)` / `ui.fields(formId, patches)` | |
| `ui.hideFields/showFields(formId, names)` | one patch, not one per name |
| `ui.required(formId, names, required?)` | |
| `ui.reset(id?)` | drop patches — one widget's, or all |
| `ui.patch(id, changes)` | the raw call |

## `options` from rows

The case that keeps coming up, and the one I first tried to answer with a schema
key (`optionsFrom:`). That was wrong: a framework cannot know which properties an
author wants to vary. A function in a library nobody has to load is the right
shape for it.

```js
ui.options('status', ['open', 'paid']);                          // strings
ui.options('customer', rows, 'key', 'name');                     // named fields
ui.options('customer', rows);                                    // value/key/id, label/title/name
```

Duplicate values are dropped — a select with two identical values cannot report
which one was picked. A row with no value at all is skipped rather than becoming
an empty choice.

## The boundary: appearance and presence, never behaviour

Nothing here touches `on:` or `from:`. A program that could rewire which function
a button calls, or which key a widget reads, would make the document stop
describing the app. What a patch moves is furniture; the document stays the map.

And nothing here touches the **document** either. `ui.reset()` is always the way
back to exactly what the view says — which is why patches live beside the
fetched tree and not in it.
