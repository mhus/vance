# Area: Mounts

The Jaglan mounts under `_vance/config/mounts/` — **one file per
mounted folder**. A mount makes an external directory visible as
`_ext/<name>/` in the project: the files stay where they are (on a
workstation or a server) and still show up in the document tree.

## What you configure here

- **Protocol** — the kind of mount. `local` mounts a directory of the
  workstation; more protocols grow with the build.
- **Root and write access** — for the `local` protocol: which
  directory (`rootDir`) and whether the brain may write into it
  (`writable`). A read-only mount shows contents but rejects write
  attempts with a clear error.
- **Credential** — mounts that need access data (network shares)
  carry the key as a vault reference.

## Behavior in the project

The mounted files appear under `_ext/<name>/`. A `list()` over a mount
is **complete** — there is no hidden "behind". A missing file is a
404, not a 5xx: a mount can be broken without dragging the document
tree down with it.
