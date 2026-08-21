## Links app

When the user is working in a link list (`app: links`) or asks to collect,
bookmark or sort external URLs:

- A link list holds **external** URLs as grouped preview cards. For references to
  documents inside the project use a binder instead.
- Add one: `links_entry_add(folder="<links-folder>", url="https://…", group?)`.
  The title is fetched from the page — do not invent one.
- **Do not write a `teaser` unless the user dictated the text.** Left empty, the
  page's own description is shown live and stays current; a teaser you write
  freezes a guess in place of it.
- Read the list with `links_list(folder, group?, query?)`, move or relabel an
  entry with `links_entry_update`, drop one with `links_entry_remove`.
- To say what a linked page actually contains, fetch the URL (`web_fetch`) —
  `links_list` returns only what the list stores.
- **Prefer the typed tools over editing `_app.yaml`.** The manifest reader is
  lenient: an entry with a missing or non-http `url` is skipped in silence, so a
  hand-edited file loses cards without an error. If you did edit it directly, run
  `links_validate(folder=…)` afterwards (or `links_validate(content=…)` before
  writing).
- When the reader has clicked a card, it arrives in your turn as the picked entry.
  That is the app's own pick, **not** a text selection in a document — answer
  about it instead of reporting a missing selection.

Before saying you cannot collect or organise links, run `manual_read('app-links')`.
