---
triggers: doc_grep_path, doc_count, doc_head_tail, doc_find, expectedContentHash, contentHash, scan budget, maxScannedDocs, search documents, grep documents, count lines, document tools, which doc tool, doc_read_lines paging
summary: The doc_* tool family — read/write with the If-Match contentHash chain, the budgeted multi-document scans (doc_grep_path, doc_count), doc_find filters, and doc_head_tail. What truncated/warning on a scan means and what to do about it.
---
# Document tools — reading, editing, searching

Documents live in project storage (not the file system). The `doc_*` family
addresses them by `path` or `id`; every tool that can change a document
accepts the If-Match guard, every scan is budgeted.

## Read, then edit — chain the hash

`doc_read` and `doc_read_lines` return a `contentHash` (SHA-256 over the
**full** body, never just the served window). `doc_edit`, `doc_append`,
`doc_replace_lines` and `doc_write` accept it back as `expectedContentHash`:

- match → the change applies and returns the **new** `contentHash`, so
  consecutive edits of the same document chain without re-reading,
- mismatch → the change is refused ("document changed since it was read —
  read again"): the user may be editing the same document in the web UI
  right now; re-read and re-apply instead of forcing the write,
- `doc_write` with a hash on a path that no longer exists is refused too —
  no silent re-create. Omit the hash to create deliberately.

Pass the hash whenever you edit something you read earlier in the turn —
especially on documents the user is actively viewing.

## Searching across many documents is budgeted

`doc_grep_path` (content or `files_with_matches`) and `doc_count` scan one
storage fetch per document, so a call is capped: at most 500 documents by
default (raise with `maxScannedDocs`, hard cap 2000), documents over 2 MB
are skipped (counted as `skippedOversized`), plus a total byte budget.

- `truncated: true` + `warning` on a stopped scan means the results are
  **incomplete** — narrow the `pathPrefix` and scan the rest in slices,
  or raise `maxScannedDocs` when the project is known to be small.
- `files_with_matches` first when you are hunting for *where* something
  lives; `content` when you need the lines.
- `'*'` (whole project) excludes mounted files under `_ext/` — use
  `mount_list` / `mount_search` for those (see `manual_read('mounted-docs')`).

## Finding by metadata

`doc_find` is the cheap "I know roughly what it's called": case-insensitive
substring over path / name / title / tags, AND-combined with optional
filters — `pathGlob` (relative to the scope, e.g. `**/*.md`),
`minSizeBytes` / `maxSizeBytes`, `createdAfter` / `createdBefore` (ISO-8601;
documents track no modification time), `sortBy` (`path` / `size` /
`created`). Needs at least `query` or `pathGlob`. Never reads a single
document body.

## Quick slices of one document

- `doc_head_tail` — first and/or last N lines, rows carry 1-based
  `lineNumber` (feed straight into `doc_replace_lines` or `doc_read_lines`).
- `doc_count` on one document (path/id) — lines / chars (+ optional regex
  match count) without paging through the body.
