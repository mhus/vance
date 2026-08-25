---
name: libraries
summary: Sharing code between custom apps — @require, libraries under _vance/app-libs, app-local scripts, and what happens on a version conflict.
triggers: your program is getting long, two apps need the same helper, you want to split main.js, a require is not found, or the app says a library loads in a version nobody asked for
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
