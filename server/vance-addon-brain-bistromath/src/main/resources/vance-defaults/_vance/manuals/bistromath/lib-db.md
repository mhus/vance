---
name: lib-db
summary: db@1 — a folder of documents as a table: get/where/insert/update/upsert/remove, one cached read, generated keys.
triggers: you wrote `@require db@1`, an app needs records it can add to and edit, you are about to write a key generator, or a list re-reads the folder on every question
---

# `db@1`

```js
// @require db@1

const invoices = db.table('/invoices/');

async function init() {
  await vance.state.set('rows', await invoices.all());
}

async function save(form) {
  await invoices.upsert(form);
  await vance.state.set('rows', await invoices.all());
}
```

Requires `core@1` and says so itself — asking for `db@1` is enough.

A record's **`key` is its file name** without the extension, and it is not stored
inside the document. That is `core`'s convention and this keeps it: two copies of
an identity can disagree.

| | |
|---|---|
| `db.table(folder, opts?)` | a handle; reads nothing yet |
| `table.all()` / `reload()` | rows, cached / read again |
| `table.get(key)` | one record, or `null` |
| `table.where(match)` / `first(match)` / `count(match?)` | `{field: value}` or a predicate |
| `table.insert(record)` | refuses an existing key |
| `table.update(key, patch)` | merges; refuses an unknown key |
| `table.upsert(record)` | replaces the whole record, creates if absent |
| `table.remove(key)` | |
| `table.nextKey(rows?)` | highest numeric key + 1, padded |

Options: `extension` (default `.yaml`), `keyPrefix`, `keyWidth`.

## What it buys over `core.rows`

**One read per folder, not per question.** `core.rows` makes one request per
document, every time. A table holds its rows, so `where`, `count` and a paged
view cost nothing more. `reload()` is for a change somebody else made — and the
`documents` channel tells you when that happened.

**Refusals instead of silent overwrites.** `insert` on an existing key throws,
and `update` on an unknown one throws. Both are the difference between a typo
and lost data: an update that quietly creates turns a mistyped key into a second,
nearly identical record.

**`nextKey`, because something has to name a new record.** Highest numeric key
plus one, padded to the width already in use — not the row count, which collides
the moment the last record is deleted. Every app answering this differently is
how one folder ends up holding `1`, `002` and `rec-3`.

## What it is not

Not a query language, no indexes, no joins, no transactions. A folder is not a
database, and `where` is a scan over rows already in memory. For a lot of
records, page in the program and read what you show — or ask whether the data
belongs in an app at all.
