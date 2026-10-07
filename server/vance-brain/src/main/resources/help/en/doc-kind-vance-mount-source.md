# Mount definition

One file under `_vance/config/mounts/<name>.yaml` — a single Jaglan
mount. The file's name is the mount's name and with it the folder
under `_ext/` where it appears.

## Form

- **Protocol** — the kind of mount (`local` …). Unknown values stay
  as a raw input.
- **Endpoint** — for network protocols, the share's address.
- **API key** — access data as a vault reference
  (`{{secret:vault:…}}`), never plaintext.
- **`rootDir`** (`local` only) — the root directory on the
  workstation. Absolute or relative to the workspace.
- **`writable`** (`local` only) — `false` (default) makes the mount
  read-only: reads work, writes fail with a clear error instead of
  getting silently lost.
- **Enabled** — switched off, the mount vanishes from the tree
  without deleting the file.

Everything the form does not render stays in the YAML untouched.
