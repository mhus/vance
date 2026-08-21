---
name: app-links
summary: The links app — a grouped collection of external URLs shown as preview cards.
triggers: user asks to collect / bookmark / sort external links, URLs, articles, "add this to my reading list", or refers to "this link" / "the link I picked" while a link list is open
---

# Links App (`app: links`)

A **link list** keeps external URLs. Its `_app.yaml` manifest holds an ordered
list of links with a group label each; the app shows them as preview cards —
picture, title, source, teaser — in the shape of search results.

It points **outward**. For references to documents *inside* the project use a
[binder](app-binder) instead; the two are deliberately different apps because
a project document has a path and a kind, and an external page has neither.

## What is stored, and what is not

| Field | Stored? |
|---|---|
| `url` | yes — the entry's identity |
| `title` | yes — fetched from the page once when the link is added |
| `teaser` | **only when somebody typed one**; otherwise resolved live |
| `image` | **only when somebody typed one**; otherwise resolved live |
| `group`, `tags`, `note`, `addedAt` | yes |

Teaser and picture come from the brain's link-preview proxy, which already
caches every page's OpenGraph data per URL for the whole tenant. A second copy
in the manifest would go stale where nobody refreshes it. The title is the one
exception: it is snapshotted so the list stays readable after a site is gone.

So an empty `teaser` does not mean "no teaser" — it means *whatever the page
says today*. **Do not write a teaser unless the user dictated one.**

`note` is separate from `teaser` on purpose: a teaser describes the page, a note
describes why *this* list has it.

## Manifest

```yaml
$meta: { kind: application, app: links }
title: "Reading"
links:
  groups: ["Rust", "Later"]          # heading order; a group may be empty
  entries:
    - url: "https://blog.example.com/async"
      title: "Async Rust, revisited"
      group: "Rust"
      tags: ["async"]
    - url: "https://example.org/pin"
      group: "Rust"
      teaser: "The clearest explanation of Pin I have found."
      note: "Send this to the team."
  index: { outputPath: "_index.md" }
```

- `group` — a heading label. Purely organisational: **not a scope**, no
  permissions, no cascade. Blank ⇒ the lead ("ungrouped") group, which always
  comes first.
- Order = array order. Adding a link puts it at the end of its group.
- `groups` exists separately from the entries so an **empty** group can survive
  a round trip. Groups only used by an entry need not be declared.

## Tools

- `links_app_create(folder, title?, description?, groups?)` — bootstrap the manifest.
- `links_entry_add(folder, url, group?, title?, teaser?, tags?, note?)` — add one
  (idempotent on the URL; the title is fetched for you).
- `links_entry_update(folder, url, group?, title?, teaser?, tags?, note?)` — omitted
  field = unchanged, empty string = cleared. An empty `title` re-fetches it.
- `links_entry_remove(folder, url)` — drop the entry. Nothing else is deleted.
- `links_list(folder, group?, query?, limit?)` — read the inventory.
- `links_validate(folder | content)` — self-check, see below.
- `app_rebuild(folder)` — regenerate `_index.md`.

Reorder, group rename and setting a picture by hand are UI operations (REST) —
there are no tools for them, because add/remove/update covers everything an
agent is asked to do.

## Prefer the typed tools over editing `_app.yaml`

You *may* write the manifest with the generic document tools, but the load path
is **lenient**: an entry whose `url` is missing or is not an http(s) address is
skipped in silence, and a field of the wrong type is ignored. You would see no
error — just a card that is not there.

So: use `links_entry_add` / `_update` / `_remove`, which cannot produce those
faults. If you did edit the file directly, run
**`links_validate(folder="<folder>")`** afterwards — or
`links_validate(content="…")` on the text before writing it. It reports exactly
what the reader would drop, plus duplicate URLs (the second of two identical
URLs is unreachable: remove and update both resolve to the first). Read-only and
advisory; it never blocks a write.

## When the reader has picked a card

Clicking a card in the app marks it, and that arrives in your turn as the
selected entry with its url, title, group, tags and note. **That is the app's own
pick, not a text selection in a document** — when the user says "this link", "the
selected link" or "the entry I marked", it is this one. Never answer that no
selection was sent, and never ask them to mark it again.

## Reading a page

`links_list` returns what is stored, never page content. To read what is behind
a link, follow the URL with `web_fetch`.
