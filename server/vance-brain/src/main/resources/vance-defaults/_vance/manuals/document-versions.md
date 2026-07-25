---
triggers: version, versions, snapshot, revert, restore version, undo document, roll back, alte fassung, frühere version, checkpoint document, save version, wiederherstellen version
summary: Save, list and restore versions (snapshots) of a document — doc_version_snapshot / doc_version_list / doc_version_restore.
---
# Document Versions — snapshot / list / restore

Every meaningful save of a document already leaves an automatic version
behind (throttled by a cooldown). These three tools let you drive that
version history explicitly.

## Tools

- `doc_version_snapshot` — save the current state as a new version now.
  Bypasses the cooldown. **No duplicate**: if nothing changed since the
  last version it returns `created=false, reason=UNCHANGED` — that is
  success, not an error.
- `doc_version_list` — the versions of a document, newest first. Each entry
  carries the `archiveId` you pass to restore, plus `archivedAtMs` + `size`.
- `doc_version_restore` — write a chosen version back onto the live
  document. The current content is archived first, so a restore is itself
  undoable. Needs an `archiveId` from `doc_version_list`.

All three select the document by `path` or `id` (same as `doc_read`).

## When to use

- **Before a risky rewrite** — snapshot first, so you can revert if the
  rewrite goes wrong: `doc_version_snapshot` → edit → (if bad)
  `doc_version_list` → `doc_version_restore`.
- **At a milestone** — snapshot to mark a known-good state the user can
  return to.
- **User asks to go back** — "restore the previous version", "undo my last
  changes": `doc_version_list` to find the version, then
  `doc_version_restore`.

## Not this

- **Trash, not versions** — recovering a *deleted* document is
  `doc_restore` (pulls from `_vance/trash/`). `doc_version_restore` only
  reverts content of a still-living document.
- Snapshotting needs WRITE permission on the document; listing needs READ.
  A permission error means the caller lacks the right — do not retry.
