---
name: overview
summary: Routing for building custom applications — which manual answers which question.
triggers: you are about to build or change a custom application (`app: custom`) and need to know where the details are
---

# Building custom applications

An app is three documents: a manifest, one or more **views** (widget trees) and
one **program** (`main.js`, sandboxed in the browser). You change what an app
does by editing documents — never say a change needs a release.

The model is Microsoft BASIC, not a database product: the **program** is the
middle, reading documents is one thing it can do.

## One rule, and everything follows from it

> A widget shows **state**. The **program** writes state. So does anything the
> reader types into — a `form`, an `input`, a `select`. The program reads
> documents through `vance.*`.

There is no table declaration, no `source:` on a widget, and no data endpoint.

## Where to look

| Question | Manual |
|---|---|
| What does the smallest working app look like? | `manual_read('hello-world')` |
| Which widgets are there, how do I wire a button? | `manual_read('views')` |
| Data and input — table, the single controls, form, pagination, file | `manual_read('widgets')` |
| Arranging — row, column, card, tabs, dialog | `manual_read('layout')` |
| Showing — markdown, code, badge, alert, embed | `manual_read('content')` |
| Sharing code, `@require`, version conflicts | `manual_read('libraries')` |
| Changing the view from JS, and your own drawing surface | `manual_read('shaping')` |
| Which functions does the runtime call? What can the program do? | `manual_read('program')` |
| How do I read documents, folders, mounted files? | `manual_read('data')` |
| An error message I do not recognise | `manual_read('troubleshooting')` |

Reading these from a general agent (not the app-building recipe) needs the
folder in the name: `manual_read('bistromath/views')`.

A `records`, `sheet`, `list` or `tree` document is one the app reads and writes
**as a structure** — and the `records_*` / `sheet_*` / `list_*` / `tree_*` tool
families edit that same document. Use them when you edit one yourself; writing
their grammar by hand into `doc_write` is how a body stops matching its kind.

## Before you promise anything

This build reads **and writes** documents — including `records`, `sheet`,
`list` and `tree` as structures rather than text — takes input in a `form`, and
reacts to writes in its own folder. It has no `visibleIf` and no `import` in the program (libraries load by
`@require` instead), and `chart` is reserved but not rendered.

Create with `bistromath_app_create(folder=…, title=…)`; it scaffolds a running
Hello World. After editing anything, run `app_rebuild(folder=…)` — nothing else
validates a view.
