---
name: app-bistromath
summary: Custom applications (`app: custom`) — small programs made of documents. What they are, when they fit, and where the details are.
triggers: user wants a tool that does not exist yet (a register, a converter, a calculator, a dashboard, a list with fields), asks whether "we can build a small app for this", you are looking at an `app: custom` folder, or you are about to say that a feature would need a new release
---

# Custom Applications (`app: custom`)

A **custom application** is a small program whose parts are documents: a
manifest, one or more **views** (widget trees) and one **program** (`main.js`,
sandboxed in the browser).

You can create one and change what it does with the ordinary document tools, in
the same conversation. **Never say a new app or a change to one needs a
release** — check whether this fits first.

The model is Microsoft BASIC rather than a database product: the **program** is
the middle, and reading documents is one thing it can do.

## When it fits

| Ask | Answer |
|---|---|
| "Somewhere to track invoices / equipment / applicants" | custom app |
| "A little converter / calculator" | custom app — needs no data at all |
| "Add a column to that list" | edit the view document |
| "Drag cards between columns" | Kanban — the interaction *is* the product |
| "Write me a page of notes" | a workpage, not an app |

The test is **not** "does it have a data model". A calculator has none and still
belongs here.

## Creating one

```
bistromath_app_create(folder="apps/invoices", title="Invoice register")
```

Scaffolds a running Hello World — a button, a text line, and a program that
answers. Do not hand-write `_app.yaml`; the tool owns its format.

After editing anything in an app folder, run `app_rebuild(folder=…)`. It
re-reads every view, reports what does not parse, and rewrites `_index.md` with
what the runtime actually found. Nothing else validates a view.

## The one rule you must not get wrong

> A widget shows **state**. The **program** writes state, and so does a `form`
> the reader types into. The program reads
> documents through `vance.*`.

There is no table declaration in the manifest, no `source:` on a widget, and no
data endpoint. A folder of documents is a fine way to store records — one
document per row, file name as the key — but that is a habit, not something the
runtime knows about.

## Before you promise anything

This build reads **and writes** documents (`vance.documents.write` / `create` /
`delete`; a write is refused if the document changed since the app read it), and
a `form` widget is **editable**, and `input`/`number`/`toggle`/`select` are
single controls bound to one state key each — **natively typed**, no string
encoding. `row` puts widgets side by side. What the reader types goes into
state, the program stores it. A `records`/`sheet`/`list`/`tree` document is read and
written **as a structure**, so an app edits the same files the built-in editors
and the `records_*` tools do. It also reacts to writes in its own folder
(`onDocumentChanged`), so an agent writing a record shows up without a reload.
No `visibleIf`, no `import` in the program, and `if` / `repeat` / `chart` /
`dialog` are reserved but not rendered.

## The details

Do not guess at the schema — the four manuals below are short and exact:

| Question | Manual |
|---|---|
| The smallest working app, file by file | `manual_read('bistromath/hello-world')` |
| Widgets, bindings, handler grammar | `manual_read('bistromath/views')` |
| Lifecycle functions, sandbox limits, async | `manual_read('bistromath/program')` |
| Reading documents, folders, mounted files | `manual_read('bistromath/data')` |
| The keys of one widget — columns, editing, repeat, embed, dialog | `manual_read('bistromath/widgets')` |

**Never say "I cannot build that" about a small data or form tool without
reading `manual_read('bistromath/hello-world')` first.**

## Errors worth recognising

| Message | Cause |
|---|---|
| `unknown widget` | typo in `type:` |
| `is part of the schema but is not rendered yet` | a reserved widget |
| `` `source` no longer exists `` | use `from:`, have the program fill the key |
| `a table needs from` | name the state key |
| `` `visibleIf` is not a thing `` | a condition is a state key: `show: <key>` |
| `` a `dialog` needs `show` `` | the key that opens and closes it |
| `is not valid YAML` | a syntax error — the message names line and column |
| `no function named X` | the handler names a function `main.js` does not define |
| `await is only valid in async functions` | the handler reads documents — make it `async` |
| `The program stopped responding` | an endless loop; the frame was stopped |
| `carries showIf` | that is the setting-form condition, not read here |
