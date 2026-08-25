---
name: program
summary: The program of a custom app — the four lifecycle functions, what the sandbox allows, and the async rules.
triggers: you are writing or changing main.js of a custom application, wiring a handler, reacting to a document somebody else wrote, or wondering why a function is not called
---

# The program

`main.js` in the app folder, unless the manifest names another file with
`custom.init`.

It is not necessarily *one* file any more: a library (`@require name@version`)
and an app-local script (`@app-script` in its header) load into the same scope
before it. `import` and runtime `require()` still do not work.

**Before writing a loop over documents, look at `core@1`** — the bundled library
has the folder-read, the sort, the filter and the paging that every app was
writing by hand: `manual_read('libraries')`.

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

## The five functions the runtime calls

All optional. The runtime asks once, after loading, which ones exist; a missing
one is simply not called.

| Function | When |
|---|---|
| `onAppInit()` | once, after the program is loaded |
| `onAppShutdown()` | when the app closes — see the warning below |
| `onAppBeforeUnload()` | returns `true` if leaving would lose something |
| `onAppDocumentChanged(paths)` | somebody else wrote a document in the app folder |
| `onAppViewOpened(handle)` | a view was rendered — where runtime view patches go |
| `onAppRefresh()` | every `refresh:` seconds, when the manifest asks for it |

**`onAppInit()` does not render.** The view is drawn first — `onAppInit` is async, and
waiting for it would leave the app blank until the first document read returns.
`onAppInit` fills the state the view reads.

**No handler runs before `onAppInit()` has finished.** Guest calls are serialised, so
a click during startup waits its turn.

**`onAppShutdown()` is not guaranteed.** On a normal close it runs and is awaited
briefly. When the whole page goes away — tab closed, reload, crash — an async
`onAppShutdown` does **not** get to finish; the browser does not wait for promises
there. So never keep the only copy of something until shutdown. Write it when
you have it.

**`onAppBeforeUnload()` is re-asked after every call**, and its answer is cached,
because the browser decides synchronously. Return a plain flag:

```js
function onAppBeforeUnload() { return unsavedChanges; }
```

The browser shows its own generic wording, and skips the prompt entirely if the
reader never interacted with the page. A courtesy, not a safety net.

### `onAppRefresh()` — polling, when the manifest asks

```yaml
# _app.yaml
custom:
  refresh: 30
```

```js
async function onAppRefresh() {
  await vance.state.set('rows', await core.rows('records/'));
}
```

For a screen showing something that changes without the reader doing anything.
**The interval is in the manifest, not in your code** — it is a property of the
app, and a reader looking at the folder can see that it polls. Minimum 5
seconds, refused below that rather than clamped.

It does not fire in a hidden tab (nobody is reading it), never overlaps itself (a
still-running call skips the next tick — a slower rate, not a queue), never runs
before `onAppInit()` finished, and stops when the app closes. Leave the function
out and `refresh:` does nothing. Use it to **read**: a write on a timer nobody
asked for is a surprise.

**`onAppDocumentChanged(paths)` is somebody else's write, never your own.** The
app watches its own folder; when an agent, a colleague or the Cortex editor
writes a document under it, the hook runs with the paths of that batch:

```js
async function onAppDocumentChanged(paths) {
  await load();
}
```

Your own writes do not come back, so reloading in the hook cannot loop.

Three things it is not told about: a document **outside** the app folder, a
change to the app's **own** documents (a view, the manifest, the program — those
reload the whole app, which is why editing a view next door takes effect without
pressing Rebuild), and a **newly added** view (no scan knows it yet — that one
still needs Rebuild).

## What the program knows about itself

`vance.app` carries what cannot change while the app is open. It is there
**before your first line runs**, so it needs no `await`:

```js
// @app-script
const title = 'Rechnungen in ' + vance.app.project;   // works at top level
```

| | |
|---|---|
| `vance.app.folder` | this app's folder, e.g. `apps/invoices` |
| `vance.app.project` | the project it lives in |
| `vance.app.tenant` | the tenant |
| `vance.app.user` | who is looking |
| `vance.app.docPath` / `.docId` | the app manifest |

**You do not need `folder` to read your own files** — a relative path already
resolves against it, so `vance.documents.read('config.yaml')` reads
`<folder>/config.yaml`. It is there for what you cannot compute: a link, a
message, a log line.

**`user` is information, not a permission.** Hiding something from a name is
decoration; what a reader may actually see is decided by the permission system
on every call the host makes for you. Do not build access control on it.

What can change is a call:

```js
const { view, session } = await vance.app.current();
```

`view` is the open view's handle — the same value `onAppViewOpened` gets. `session`
is the id of the chat beside the app, or `null` when there is none, which is the
normal case in a chatless tab.

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
| `vance.state.get(key)` | read it back — including what a reader typed into a `form` or an input |
| `vance.documents.list(path)` | documents directly in a folder |
| `vance.documents.read(path)` | content; a structure for YAML/JSON and for kind documents |
| `vance.documents.write(path, content, opts?)` | store content; refused if it changed since read |
| `vance.documents.create(path, content)` | like `write`, but fails if one is there |
| `vance.documents.delete(path)` | remove it |
| `vance.app.<…>` | facts about this app — read directly, no await (see below) |
| `vance.app.current()` | what can still change: the open view, the chat session |
| `vance.view.patch(id, changes)` | hide/relabel a widget or a field, replace a select's choices |
| `vance.view.reset(id?)` | undo one patch, or all of them |
| `vance.ui.notify(text)` | a message above the page |
| `vance.ui.show(handle)` | switch to another view |
| `vance.rest(method, path, body?)` | any Brain route the reader may use — `manual_read('rest')` |

See `manual_read('data')` for paths, reading and the write rule.

## What it may not do

No `window` of the host, no cookies, no `localStorage`. The program runs in a
sandboxed frame with an opaque origin; `vance.*` is the only way out.

**It never holds a credential**, and that is the point rather than a limitation:
it is why a program written as a document is allowed to run at all. It can still
reach the API — `vance.rest` has the *host* make the call with the reader's
session and hands back the result (`manual_read('rest')`) — so "no token" and
"no server" are different statements, and only the first one is true.

It *does* have a **DOM of its own** — that frame's document. Invisible unless the
view asks for it with `region:`, and then it is a rectangle you render into
yourself: `manual_read('shaping')`.

## Two things that stop a program

A handler that goes silent for five seconds is treated as hung: the frame is
removed and the app says so. Time spent waiting for a `vance.*` call does **not**
count — a slow document read is not a hang.

An error in a handler is reported with the document name and line, because the
program is evaluated with a source URL.
