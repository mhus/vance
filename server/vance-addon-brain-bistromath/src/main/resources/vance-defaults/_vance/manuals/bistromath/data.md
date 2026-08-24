---
name: data
summary: How a custom app reads data — paths, folders as records, parsed content, and mounted documents with parameters.
triggers: your app needs to show or use data, you are writing a list or table, or you need to read a mounted (_ext) document
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
[{ key: '2026-0001', path: 'invoices/2026-0001.yaml', title: '…', mime: '…' }]
```

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

**Not in this build.** `vance.documents.write` does not exist. An app reads and
computes; it cannot store. Do not promise a save button.

The reason it is missing rather than quick: two browsers writing the same
document need a concurrency rule, and a write without one loses somebody's edit
silently. That rule is the next thing being built.

## Showing what you read

Anything a widget shows goes through state (`manual_read('views')`). For values
that are not strings — an object, a list — either format them in the program or
use a `markdown` widget, since a raw object renders as JSON.
