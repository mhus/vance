---
name: data
summary: How a custom app reads and writes data — paths, folders as records, parsed content, mounted documents with parameters, and what happens when somebody else wrote first.
triggers: your app needs to show, store or change data, you are writing a list or table, you need to read a mounted (_ext) document, or a write was refused
---

# Data

There is **no table declaration** anywhere — not in the manifest, not on a
widget. The program reads documents and puts the result into state:

```js
async function init() {
  const files = await vance.documents.list('invoices/');
  const rows = [];
  for (const f of files) {
    rows.push({ key: f.key, ...(await vance.documents.read(f.path)) });
  }
  vance.state.set('invoices', rows);
}
```

```yaml
  - type: table
    from: invoices
    columns: [nr, customer, amount]
```

## Paths

Relative to the **app folder**. A leading `/` means the project root.

```js
vance.documents.read('config.yaml')          // inside the app
vance.documents.read('/shared/rates.yaml')   // project root
vance.documents.read('/_ext/library/doc.pdf')// a mounted document
```

**`_ext/…` needs the leading slash.** Without it the path resolves inside the
app folder and the error says the document does not exist — which reads like a
broken mount rather than a relative path. The runtime adds that hint to the
message, but write the slash.

Another project's documents are not reachable.

## A folder of records

One document per record, the file name is the key. That is a habit, not
something the runtime knows — but it is a good one: each record is written,
locked and versioned on its own, so two people editing different records never
collide.

`vance.documents.list(folder)` returns the documents **directly** inside it:

```js
[{ key: '2026-0001', path: '/apps/mine/invoices/2026-0001.yaml', title: '…', mime: '…' }]
```

**`path` comes back with a leading slash**, from the project root — so you hand
it straight to `read` and it means the same document you just listed. `key` is
the file name without its extension, which is the useful half most of the time.

Ordered by path, at most 200 entries. A larger collection needs paging in the
program — say so rather than showing a table that quietly stops.

A query is **refused** on `list`: parameters belong to a document's content, not
to a folder listing.

## Reading content

`vance.documents.read(path)` gives an **object** for YAML and JSON, a **string**
otherwise. The host parses, because it knows the mime type — the program does
not need a parser.

`$`-prefixed keys are dropped from a parsed mapping: `$meta` is document
plumbing, not part of the record.

A read is one round trip per document. Reading a hundred-row folder is a hundred
reads; for a big collection, read what the view shows.

## Mounted documents and parameters

A path under `/_ext/<mount>/…` is a **mounted** file — foreign bytes, streamed
through, read-only. It resolves even if nobody browsed the folder first. For the
namespace itself, `manual_read('mounted-docs')`.

A query on such a read is forwarded to the source as a parameterised view:

```js
const report = await vance.documents.read(
  '/_ext/demo/analysis.yaml?from=2026-02-01&to=2026-03-31'
);
```

The path goes to the lookup, the parameters to the content call. Sending the
whole string as a path would look for a document literally named
`analysis.yaml?from=…` and find nothing — the runtime splits it for you.

## Writing

```js
const inv = await vance.documents.read('invoices/2026-0001.yaml');
inv.status = 'paid';
await vance.documents.write('invoices/2026-0001.yaml', inv);
```

Three calls, and each answers a different question:

| Call | For |
|---|---|
| `write(path, content, opts?)` | store content — creates the document if it is not there |
| `create(path, content)` | the same, but **fails** if something is already there |
| `delete(path)` | remove it (into the trash, like every other delete) |

Reach for `create` when "already there" is a mistake you want to hear about — a
register that must not overwrite yesterday's entry. Otherwise `write`.

An **object** is serialised by the document's own type — YAML for `.yaml`, JSON
for `.json` — so read-then-write round-trips. A **string** is written verbatim,
which is how you keep full control of the bytes (Markdown, a `.js`, a CSV).

There is no separate "make the folder" step: a document's path *is* its
folder.

### Somebody else wrote first

Every read remembers the document's version; every write sends it back. If the
document changed in between, the write is **refused and nothing is stored**:

```js
try {
  await vance.documents.write(path, row);
} catch (e) {
  if (e.message.includes('changed since it was read')) {
    const fresh = await vance.documents.read(path);   // read again, decide
    vance.ui.notify('Somebody else edited this record.', 'warn');
    return;
  }
  throw e;
}
```

`{ force: true }` writes regardless. Use it when your program is the authority
on the content — a log line it appends to, a cache it owns — not to make an
error go away.

The rule this depends on, stated plainly: **only a document this app has read
is guarded.** A write to a path the app never read goes through
unconditionally. So the shape to keep is read → change → write; a write built
from a path in a list, without reading first, is the case that loses somebody's
edit.

Mounted documents (`/_ext/…`) carry no version, so nothing guards them; most
mounts refuse writes outright.

## Documents that are a structure

`records`, `sheet`, `list` and `tree` store their body in their own grammar, not
in YAML. `read` gives you the **structure** anyway — the host decodes by kind:

```js
const t = await vance.documents.read('table.md');
// { kind: 'records', schema: ['customer','amount'], items: [{ values: {...} }] }

t.items.push({ values: { customer: 'Acme', amount: '1250' } });
await vance.documents.write('table.md', t);      // written back in its own grammar
```

This is the point of it: **your app edits the same documents everything else
edits.** The built-in Records editor opens that file, `records_add_row` appends
to it, an `embed` displays it — and your program reads and writes it. No copy,
no export, no second format.

Two things to know:

- **Values are strings** in these formats. A `sheet` cell and a `records` field
  hold text; convert in the program if you need a number.
- **`create` has no codec**, because a kind lives in the document's header and
  there is no document yet. Write the body as a string once — every later
  `read`/`write` goes through the codec.

Chart, diagram, map and slide documents are **not** decoded: a program has
little reason to author one, and `embed` already shows them.

## Showing what you read

Anything a widget shows goes through state (`manual_read('views')`). For values
that are not strings — an object, a list — either format them in the program or
use a `markdown` widget, since a raw object renders as JSON.
