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

> A widget shows **state**. The **program** writes state. The program reads
> documents through `vance.*`.

There is no table declaration, no `source:` on a widget, and no data endpoint.

## Where to look

| Question | Manual |
|---|---|
| What does the smallest working app look like? | `manual_read('hello-world')` |
| Which widgets are there, how do I wire a button? | `manual_read('views')` |
| Which functions does the runtime call? What can the program do? | `manual_read('program')` |
| How do I read documents, folders, mounted files? | `manual_read('data')` |
| An error message I do not recognise | `manual_read('troubleshooting')` |

Reading these from a general agent (not the app-building recipe) needs the
folder in the name: `manual_read('bistromath/views')`.

## Before you promise anything

This build **reads**; it cannot write documents yet. There is no `visibleIf`,
no `import` in the program, and `if` / `repeat` / `chart` / `dialog` are
reserved but not rendered. Do not write a program that calls
`vance.documents.write` — it does not exist.

Create with `bistromath_app_create(folder=…, title=…)`; it scaffolds a running
Hello World. After editing anything, run `app_rebuild(folder=…)` — nothing else
validates a view.
