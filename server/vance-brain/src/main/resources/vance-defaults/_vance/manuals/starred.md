---
triggers: starred, my starred apps, favourites, favorites, pin this, star this, where do I keep my links, which app takes this, send this to my, my start page, tile on the start page, angesternt, favoriten, anpinnen, auf die startseite
summary: The user's per-person, cross-project list of starred documents — tiles on their start page, and the lookup that answers "which document takes a link?". Read it with starred_list, change it with starred_add / starred_remove, repair it with starred_reconcile.
---
# Starred documents

A **starred** document is one the person marked with a star, anywhere in the
tenant. The list is per user, spans projects, and serves two purposes at once:

- **Visual** — the tiles at the top of their start page.
- **Technical** — it answers *which* document is the one for a job. Asked for
  the app type `links`, it returns the link collection this person actually
  uses. That is how a "send this to my links" gets a target without guessing.

The list lives as one document in the person's own hub project. You never need
its path: the tools resolve it.

## Registration vs. visibility

Two independent things, and confusing them produces wrong answers:

| State | On the start page | Returned by a lookup |
|---|---|---|
| normal | yes | yes |
| `hidden` | no | **yes** |
| switched off | no | no |

`hidden` is not "half deleted". It means *registered but out of the way* — a
person may keep their link collection as the target for "send to" without
wanting a tile for it. **Never conclude from "not on the start page" that
something is not starred.** Ask `starred_list`, which includes hidden entries.

## The tools

```
starred_list                      what is starred (hidden entries included)
starred_list(type='links')        only entries of an app type
starred_add(project, path)        star it, or edit an existing entry
starred_remove(project, path)     unstar it
starred_reconcile                 check every entry against the real documents
```

### Reading

`starred_list` is the answer to "which app do I put this in?". Filter by
`type` for an app capability (`links`, `binder`, `workbook`) or by `kind` for a
document form (`workpage`, `text`).

A `type` is only present when the target is an application; a plain Markdown
document has a `kind` and no `type`. Both are fine as targets — a note file is a
perfectly good place to append to.

If a lookup returns nothing, say so and offer to star something. Do not pick an
arbitrary document that looks like it might fit; the star is the person's
statement about where their things go, and inventing one is worse than asking.

### Writing

`starred_add` needs only `project` and `path`. **You cannot pass `kind` or
`type`** — those are read from the document itself. That is deliberate: a
plausible-looking wrong type breaks a "send to" and nothing in the interface
would say so.

Every other field is optional and *tri-state*: leave it out to keep whatever is
there. So `starred_add` on an existing entry is safe — it will not wipe a
description the person typed.

`title` follows the same rule: omit it and an existing label survives, while a
first star takes the document's own title.

`highlight` is **visual only**. It never influences which entry a lookup picks
(that is file order). Do not set it hoping to steer a "send to".

### Removing

`starred_remove` deletes the entry — unless it carries something the person
wrote (description, highlight, hidden), in which case it is only switched off
and re-starring restores those fields. Either way, prefer asking before
unstarring something you did not just star yourself.

## Repair

The list stores `kind`, `title` and `type` rather than looking them up on every
read — that is what makes the start page and a "send to" menu fast. The price
is that a moved, deleted or re-typed target goes unnoticed until something uses
it.

Run `starred_reconcile` when a tile misbehaves, after documents were moved, or
when the person says a favourite is broken. It refreshes drifted kinds and types
and reports targets that are gone or unreadable. It **does not delete** them:
that may be a temporary problem, and dropping a curation is the person's call.
Offer `starred_remove` for what they no longer want.

## What a star is not

A star grants no permission. It is a bookmark with facts attached — opening the
document still goes through the normal access check, and writing into it still
needs write access. If an entry is listed but opening it fails, that is not a
bug in the list.
