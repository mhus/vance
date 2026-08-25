---
name: lib-api
summary: api@1 — the REST layer over vance.rest: project parameter, status branching, tryGet, and named inbox and document routes.
triggers: you wrote `@require api@1`, you are appending projectId by hand, you need to branch on 404 or 409, or you are about to write a wrapper for a Brain route
---

# `api@1`

```js
// @require api@1

const settings = await api.get('settings');
const doc = await api.tryGet('documents/by-path?path=/maybe.yaml');   // null if absent
```

Requires nothing. It sits directly on `vance.rest`
(`manual_read('bistromath/rest')`), which is what decides *whether* a call is
allowed — this library only makes the call easier to write correctly.

| | |
|---|---|
| `api.get/post/put/patch/del(path, body?)` | with `projectId` appended when absent |
| `api.call(method, path, body?)` | the same, any method |
| `api.raw(method, path, body?)` | **without** the project parameter |
| `api.tryGet(path)` | `null` on 404, still throws otherwise |
| `api.status(error)` | the HTTP status as a **number**, or `null` |
| `api.query({…})` / `api.path(base, {…})` | build a query string |
| `api.withProject(path, id?)` | just the project parameter |
| `api.inbox.list/count/get/read/archive` | the reader's threads |
| `api.documents.folder(path, params?)` / `.search(params)` | project-root paths |

## The three things it is for

**The project parameter.** Nearly every route wants one and the program has it
in `vance.app.project`. Written by hand it gets forgotten once, and then the
route answers about the wrong project instead of failing.

**Branching on the status as a number.**

```js
try {
  await api.put('documents/' + id + '/content', body);
} catch (e) {
  if (api.status(e) === 409) { /* somebody else wrote — read again */ }
  else throw e;
}
```

Never match the wording of a message: it will improve one day, and the branch
will stop firing silently.

**`tryGet`, because absence is an answer.** Only 404 becomes `null` — a 403 keeps
throwing. "Not there" and "not yours" need different code, and folding them
together is how a permission problem gets shown as an empty list for a week.

## Named routes: only real ones

`api.inbox.*` goes through `raw`, without a project — an inbox thread has no
project, so adding one would send a parameter nobody reads.

There is **no** `api.inbox.create`: there is no `POST /inbox`. A thread is opened
by an agent or as a discussion on a document. A wrapper for a route that does not
exist is worse than none — it looks plausible while being written and fails at
runtime.

Same reason there is no wrapper per route: a mirror of the whole API goes stale
without anyone noticing, and `api.get` already reaches everything.
