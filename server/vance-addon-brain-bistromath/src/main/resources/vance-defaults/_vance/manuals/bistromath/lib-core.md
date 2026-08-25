---
name: lib-core
summary: core@1 — the bundled ergonomics library: folder-as-rows, state, filter/sort/paging, formatting, notify.
triggers: you wrote `@require core@1` and need to know what is in it, you are about to hand-write a folder read or a sort with empty cells, or you are looking for a currency or date format
---

# `core@1`

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

## What is deliberately not in it

No currency, no locale, no date pattern, no default page size. A shared **core**
that decides those becomes something to work around. They live in a library you
opt into instead — see `manual_read('bistromath/libraries')` for the catalogue.
