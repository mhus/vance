---
name: program
summary: The program of a custom app — the three lifecycle functions, what the sandbox allows, and the async rules.
triggers: you are writing or changing main.js of a custom application, wiring a handler, or wondering why a function is not called
---

# The program

`main.js` in the app folder, unless the manifest names another file with
`custom.init`. It is **one file**: the guest cannot load anything, so `import`
and `require` do not work.

Write **plain top-level functions, no `export`.** The source is evaluated as one
script, so a top-level function is reachable by name — that is what a view's
`main.js:hello` refers to.

## It keeps running

One program per open app, started when the app opens, stopped when it closes.
A variable at the top of the file keeps its value between clicks:

```js
let clicks = 0;
function hello() { clicks++; }
```

Nothing survives to the next opening. Closing the app tab, switching documents
or reloading starts a fresh program. **Whatever must outlive that goes into a
document.**

## The three functions the runtime calls

All optional. The runtime asks once, after loading, which ones exist; a missing
one is simply not called.

| Function | When |
|---|---|
| `init()` | once, after the program is loaded |
| `shutdown()` | when the app closes — see the warning below |
| `onBeforeUnload()` | returns `true` if leaving would lose something |

**`init()` does not render.** The view is drawn first — `init` is async, and
waiting for it would leave the app blank until the first document read returns.
`init` fills the state the view reads.

**No handler runs before `init()` has finished.** Guest calls are serialised, so
a click during startup waits its turn.

**`shutdown()` is not guaranteed.** On a normal close it runs and is awaited
briefly. When the whole page goes away — tab closed, reload, crash — an async
`shutdown` does **not** get to finish; the browser does not wait for promises
there. So never keep the only copy of something until shutdown. Write it when
you have it.

**`onBeforeUnload()` is re-asked after every call**, and its answer is cached,
because the browser decides synchronously. Return a plain flag:

```js
function onBeforeUnload() { return unsavedChanges; }
```

The browser shows its own generic wording, and skips the prompt entirely if the
reader never interacted with the page. A courtesy, not a safety net.

## `await` needs `async`

Every `vance.*` call is asynchronous. A handler that reads must say so:

```js
async function load() {
  const files = await vance.documents.list('records/');
  vance.state.set('count', files.length);
}
```

`await` in a plain function is a syntax error, and it is the mistake made most
often here.

## What the program may do

| Call | Does |
|---|---|
| `vance.state.set(key, value)` | what widgets bound with `from:` show |
| `vance.documents.list(path)` | documents directly in a folder |
| `vance.documents.read(path)` | content; an object for YAML/JSON |
| `vance.documents.write(path, content, opts?)` | store content; refused if it changed since read |
| `vance.documents.create(path, content)` | like `write`, but fails if one is there |
| `vance.documents.delete(path)` | remove it |
| `vance.ui.notify(text)` | a message above the page |
| `vance.ui.show(handle)` | switch to another view |

See `manual_read('data')` for paths, reading and the write rule.

## What it may not do

No DOM, no `window`, no cookies, no `localStorage`, no `fetch`. The program runs
in a sandboxed frame with an opaque origin; `vance.*` is the only way out, and
it never carries the reader's credentials into the program.

## Two things that stop a program

A handler that goes silent for five seconds is treated as hung: the frame is
removed and the app says so. Time spent waiting for a `vance.*` call does **not**
count — a slow document read is not a hang.

An error in a handler is reported with the document name and line, because the
program is evaluated with a source URL.
