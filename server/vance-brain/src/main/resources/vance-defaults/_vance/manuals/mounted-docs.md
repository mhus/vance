---
triggers: _ext, mounted, mount, external library, book library, pdf library, external files, aus der bibliothek, externe dateien, gemountet, mount_list, mount_search, archive folder, is that file available
summary: Read files that live in an external source (document library, archive, media store) but appear under _ext/<mount>/ in this project. doc_find / doc_grep / memory_search do NOT cover them — use mount_list / mount_search to find them, then the ordinary doc_read.
---
# Mounted documents — files that live somewhere else

Some projects have external sources mounted: a document library, a media
archive, a folder on the server. Their files appear under

```
_ext/<mount>/<path inside the source>
```

and you read them with the **ordinary** document tools — `doc_read`,
`doc_info`, embeds, links. Nothing is copied into Vance; the bytes are
fetched from the source each time.

## The one thing that will trip you up

`doc_find`, `doc_grep`, `memory_search` and `doc_list_in_folder` **do not
see mounted files.** They scan `documents/` by default, and `_ext/` is
deliberately outside it — a foreign library must not turn up in every
search for a note.

So: **never say a file is unavailable, or that the project has no such
document, before calling `mount_list`.** If the user mentions a library, an
archive, a book, a PDF collection or "the external files", that is the
first call.

## The tools

| Tool | Use |
|---|---|
| `mount_list()` | Which sources are mounted, and what they allow. Start here. |
| `mount_list(path='_ext/library/books')` | Browse one folder, one level. Add `refresh=true` to re-read it from the source. |
| `mount_search(query='dune')` | Ask the sources to search their own catalogues. |
| `doc_read(path='_ext/library/books/dune.pdf')` | Read a file once you know its path. |

## Reading the answers honestly

**`mount_search` is not complete by itself.** A source that cannot search
appears in `notSearched`. For those, an empty result means *nobody looked* —
browse with `mount_list` instead of concluding the file does not exist.

**`status` on a mount means it is not answering right now.** It is still
configured. Say so ("the library is not reachable at the moment") rather
than reporting that it holds nothing.

**A missing `itemCount` means unknown, not empty.** Some sources do not
count themselves.

## Writing

Most mounts are read-only, and then `access` says `RO`. A write attempt on
such a file is refused with a clear message — you do not need to guess in
advance, but do not promise the user an edit before you have seen `RW`.

Where a mount *is* writable, `doc_write` works normally and the change goes
to the source, not to a copy here.

## What does not apply to mounted files

- **No summaries and no semantic search.** They are not indexed here. Do
  not offer to "search the library semantically" — ask the library.
- **No versions.** There is no history on our side; the source keeps one or
  it does not.
- **No trash.** Deleting means deleting at the source, or not at all.
- **Colours and notes** are not available on them yet.

If the user wants any of that, the honest answer is to copy the file into
the project first (`doc_write` to a `documents/…` path), which makes it an
ordinary Vance document with all of the above — and a copy, which is worth
saying out loud.
