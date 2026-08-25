---
name: libraries
summary: Sharing code between custom apps — the bundled core@1, @require, libraries under _vance/app-libs, app-local scripts, and what happens on a version conflict.
triggers: you are writing a program that reads a folder, sorts, filters or pages, your program is getting long, two apps need the same helper, a require is not found, or the app says a library loads in a version nobody asked for
---

# Libraries and extra files

The guest has **one global scope and no module system**: no `import`, no
`require()` at runtime, no npm. "Loading" means evaluating a list of documents
in order, into that one scope. Everything below is about building that list.

## Two different things

| | What it is | How it gets loaded |
|---|---|---|
| **library** | shared, versioned, lives outside the app | you **ask** for it: `@require db@1` |
| **app script** | belongs to this one app, sits in its folder | it **says so**: `@app-script` in its header |

That split is the whole design. A library is named and versioned because
several apps use it; a file in your own folder has one version and needs no
name.

## Asking for a library

Three places, all equivalent — use whichever is true:

```yaml
# _app.yaml — the app as a whole needs it
custom:
  required: [core@1, db@2]
```

```yaml
# a view — this screen needs it
required: [chart-helpers@1]
type: page
children: [...]
```

```js
// main.js — this file needs it
// @require db@2
```

**The version is required.** `db` alone would have to mean "whatever is
newest", which is a different promise; `db@1` records which API you wrote
against, and that is what lets a conflict be reported instead of guessed.

A library's own header is read too, so a library can require a library. That is
what makes the list transitive, and the order follows: nothing is evaluated
before what it needs.

## Where a library lives

`_vance/app-libs/<name>@<version>.js`, resolved through the usual cascade:
**your project → the tenant → bundled with Vancetope**. So a project can
override a tenant library, and a tenant can override a bundled one, by putting a
document at the same path.

The `Loads` panel in the app toolbar says which layer each one came from — the
difference between "my override works" and "my override is at the wrong path".

## `core@1` — what ships with Vancetope

One library is bundled. Require it and it is there:

```js
// @require core@1
```

Everything in it was written by hand in every app before it existed. It is
**shorter, not more powerful** — a program that never loads it is not missing a
capability.

| | |
|---|---|
| `core.rows(folder)` | a folder of documents as records, `key` mixed in |
| `core.save(folder, record)` | write one back by its `key` (which is dropped from the body) |
| `core.remove(folder, key)` | delete one |
| `core.set({a, b})` | several state keys at once |
| `core.get('a', 'b')` | several back, as one object |
| `core.filter(rows, needle, fields?)` | substring match, any field or the named ones |
| `core.sort(rows, field, descending?)` | numeric when both are numbers, empties last **both ways** |
| `core.paging(rows, size, previous?)` | the object a `pagination` binds to, clamped |
| `core.page(rows, paging)` | the slice it describes |
| `core.num(v, digits?)` | a number, or `''` for a non-number |
| `core.date(v)` | the reader's locale, or the raw value if unparseable |
| `core.say(t)` / `core.warn(t)` | notify, with the severity at the call site |

```js
// @require core@1

async function load() {
  const rows = await core.rows('/invoices/');
  const sorted = core.sort(rows, 'amount', true);
  const paging = core.paging(sorted, 20);
  await core.set({ rows: core.page(sorted, paging), paging: paging });
}
```

**No policy in it.** No currency, no date format, no default page size that
pretends to know your data. `core.num` formats a number; what a number *means*
is your app's business — a shared library deciding that is how a helper becomes
something to work around.

To replace it, put your own document at `_vance/app-libs/core@1.js` in your
project or tenant. The `Loads` panel then says `project` instead of `bundled`.

## Writing a library

```js
// _vance/app-libs/money@1.js
// @require core@1

const money = {
  format: function (n) { return n.toFixed(2) + ' EUR'; },
};
```

Ordinary top-level `const` works, because every file is evaluated **together**
into one scope. Two libraries declaring the same name is a loud error naming it,
not a silent shadow.

## Splitting your own program

Mark the file in its header and it loads before `main.js`:

```js
// helpers.js
// @app-script
// @require money@1

function label(n) { return 'Total: ' + money.format(n); }
```

**Without the marker it is not loaded.** A `.js` document in the app folder that
says nothing stays a note — which is the point: nothing you leave lying around
becomes part of the program.

App scripts load in path order, after every library and before the program.

**Two files declaring the same function silently collide** — a function
declaration is not an error to redeclare, and the one that loads last wins. A
`const` collides *loudly* (a SyntaxError naming it), which is why a library is
better off exposing one object than a handful of loose functions.

## Testing a library

A library that other apps depend on can have real tests — but only if it lives
**in the repository** as a bundled library. `core@1` does, and its tests read
the resource and evaluate it the way the runtime does:

```ts
const { core } = loadLibraries({
  sources: [{ library: 'core@1' }],
  expose: ['core'],
  vance: stubVance({ ui: { notify } }),
});
```

A dependent library is the same call with two entries, dependency first —
`core` is a top-level `const` in another file and is visible, exactly as in the
app.

A library that lives as a **project or tenant document** has no such test: the
test runner reads the repository, not the database, so there is nothing for it
to load. That is the trade-off to make deliberately — a helper that a handful of
apps rely on belongs in the repo as a bundled library (still overridable per
project through the cascade), and a library that only one project will ever use
is fine as a document.

## Version conflicts

If two things ask for different versions of one library, the **highest wins**
and you get a warning naming both versions and who asked for what:

```
`db` is required in 2 versions; loading db@2.
db@1 ← apps/mine/helpers.js; db@2 ← apps/mine/main.js.
Two versions cannot both load — pin the library or fix the caller.
```

This is a guess, not a resolution. Two versions cannot both exist in one
scope — there is no second scope to put one in — so the choice is between
refusing to run and picking one, and a v2 is more likely to still serve a v1
caller than the reverse. **The warning is the fix-me**: either pin the library
(change the `@require`) or update the caller.

## When something is not found

A missing library is reported and the app **still loads**:

```
`ghost@3` was not found. Expected a document at
`_vance/app-libs/ghost@3.js` (this project, the tenant, or bundled).
Asked for by: apps/mine/main.js.
```

A missing library breaks the *program*, not the app — refusing to open would
hide which of the two is wrong. The program will fail at the first call that
needs it.

## Seeing what loads

The **Loads** button in the app toolbar lists the load order, each file's origin
and who asked for it. The same list is written into the generated `_index.md` on
every `app_rebuild`, so an agent can read it too.

Both are worth checking after adding a require, because the list is assembled
from three places and written down in none of them.
