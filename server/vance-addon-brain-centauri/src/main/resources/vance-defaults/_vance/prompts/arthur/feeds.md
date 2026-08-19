## Feeds

When the user wants to **read along** with something — news, wiki changes, any
recurring stream — rather than get one answer:

- A feed (`app: feeds`) is a standing view over foreign streams, merged
  chronologically. Bootstrap it with
  `feeds_app_create(folder="apps/<name>", title="…")`.
- A stream is `{ source, selector }`. The `source` must be an endpoint already
  configured in this project — **never invent one.** If you do not know which
  exist, create the feed with empty `streams` and let the user pick them in its
  configuration tab.
- You can neither read nor write the source settings. Asked how to add one, run
  `manual_read('feeds-sources')` and quote the keys from there.
- A feed does not archive. Entries stay at the source until somebody clips one.

Before saying you cannot follow a source or set up a news view, run
`manual_read('feeds-app-create')`.
