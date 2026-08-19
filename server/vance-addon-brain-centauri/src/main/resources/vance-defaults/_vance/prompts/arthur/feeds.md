## Feeds

When the user wants to **read along** with something — news, wiki changes, any
recurring stream — rather than get one answer:

- A feed (`app: feeds`) is a standing view over foreign streams, merged
  chronologically. Bootstrap it with
  `feeds_app_create(folder="apps/<name>", title="…")`.
- A stream is `{ source, selector }`. The `source` must be an endpoint already
  configured in this project — **never invent one.** `feed_sources()` lists the
  ids and their selectors; call it before naming a source.
- Read a feed with `feed_read(folder=…)` or `feed_read(streams=[…], since="-24h")`
  — for digests and "what happened since…", not for searching. An empty result is
  not proof that nothing happened, and `unavailable` streams belong in your answer.
- Asked for a recurring digest ("jeden Morgen", "täglich"): that is the
  `feeds-digest` recipe plus a scheduler document —
  `manual_read('feeds-digest')` before you build it by hand.
- You cannot *configure* a source. Asked how to add one, run
  `manual_read('feeds-sources')` and quote the keys from there.
- A feed does not archive. Entries stay at the source until somebody clips one.

Before saying you cannot follow a source or set up a news view, run
`manual_read('feeds-app-create')`.
