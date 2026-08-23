---
triggers: digest, morgenlage, briefing, täglich zusammenfassen, feed lesen, was ist passiert, daily digest, feed summary, scheduled digest, feed scheduler
summary: Read a feed with feed_read, and set up the recurring digest (recipe feeds-digest + a scheduler document) that posts it into the inbox.
requires-tools: feed_read
---
# Reading a feed, and the recurring digest

Two things: how to read a feed at all, and how to make that happen every morning without anybody asking.

## Reading

```
feed_read(folder="apps/morning", since="-24h")
feed_read(streams=[{source: "wikipedia-de", selector: "article"}], since="-2h", limit=30)
```

Either an existing feed app **by folder** (uses its stored streams and filter) or an **ad-hoc** set of streams. Source ids come from `feed_sources()` — never guess one.

| What you pass | Notes |
|---|---|
| `since` | Relative (`-24h`, `-7d`, `-30m`) or an ISO instant. Overrides a stored window. |
| `languages` | e.g. `["de","en"]`. Entries whose source declares no language **always pass** — that is deliberate, not a bug. |
| `limit` | Default 20, hard max 50. A page lands in your prompt. |

**There is no cursor and no second page.** The tool answers „the last N since T", which is what a digest needs. If you want more, widen `since` — do not try to paginate.

### Read the answer honestly

- **`unavailable`** lists streams that did not deliver (off, cooling down, failed, too slow). Name them in your output. A summary that silently omits a source reads as „nothing happened there", which is a different statement.
- **An empty `items` is not proof that nothing happened.** It can equally mean the window was too narrow or every entry was filtered out. Say which of the two you can tell apart, and do not report silence as fact.

## The recurring digest

Two pieces: a **recipe** (shipped) and a **scheduler document** (per project — you create it).

The recipe `feeds-digest` reads the feed and posts an `OUTPUT_TEXT` inbox item. It already carries the tools and the instructions; you only supply folder and window.

Create the schedule with `scheduler_set`, or write the document directly:

```yaml
# <project>/_vance/scheduler/morning-feeds.yaml
description: "Posts the overnight feed digest into the inbox."
cron: "0 30 7 * * MON-FRI"      # Quartz — WITH a seconds field
timezone: "Europe/Berlin"
recipe: "feeds-digest"
initialMessage: |
  Digest the feed in folder "apps/morning" for the window since -16h.
runAs: "<user>"                  # who gets the inbox item
overlap: skip
tags: [feeds, digest]
```

Three things go wrong here, so check them:

- **The cron has six fields**, seconds first. `"30 7 * * *"` is a five-field cron and will not load.
- **`initialMessage` carries the parameters.** The recipe does not know which feed you mean; folder and window come from this text.
- **`runAs` decides who receives the item** — indirectly. It sets the *identity of the run*, and the recipe resolves the recipient from it via `whoami()`; `inbox_post` itself has no default recipient. Without `runAs` the scheduler runs as the document's creator — fine when that is the same person, wrong when an admin sets it up for somebody else.

After writing the document, run `scheduler_refresh` — the registration is read at project bootstrap, not per tick, so a new schedule is otherwise invisible until the next restart.

## Sanity check before you promise a daily digest

`feed_read` on the intended folder **once**, by hand. If it comes back empty or with `unavailable`, the schedule would post that same emptiness every morning — fix the feed first. A schedule is only worth creating over a feed that already answers.
