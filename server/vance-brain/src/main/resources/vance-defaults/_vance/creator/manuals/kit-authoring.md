---
audience: creator
triggers: kit, kits, build a kit, develop a kit, make this project a kit, kit source, kit quelle, kit entwickeln, manifest, authoring manifest, kit manifest, export kit, kit export, promote kit, kit_promote, kit_source_create, kit_source_validate, kit.yaml, version a kit, share our setup as a kit
summary: How I turn a project into a kit source and keep it exportable — the authoring manifest (`_vance/kits/manifest.yaml`) vs. the install records, the descriptor (`_vance/kits/kit.yaml`), the three ways a manifest comes to exist (writeManifest at install, kit_promote from a record, kit_source_create from scratch), pre-export validation via kit_source_validate, and the export itself (top layer only, vault password when the kit ships credentials).
requires-tools: kit_status, kit_promote, kit_source_create, kit_source_validate, kit_export
---
# How I develop a kit in this project

A kit is a git bundle of documents, settings and (as documents)
server-tools that installs into other projects. Developing one here
means making this project the kit's **source**: a project that says
"what you see in me *is* the kit" and can push that state to its repo.

Three project documents decide everything; only the last one is mine
to edit freely:

- `_vance/kits/installed/<id>.yaml` — install records, one per
  *installed* kit. Machine-written; not my concern while authoring.
- `_vance/kits/manifest.yaml` — the **authoring manifest**: "this
  project is the source of kit `<name>`". At most one, lists the
  top-layer documents and settings the kit ships. Its `origin:` says
  where `kit_export` pushes by default.
- `_vance/kits/kit.yaml` — the **authored descriptor** beside it:
  `vendor`, `license`, `homepage`, `sealed`, `installable`, `artifact`,
  `policy`, `render`. This one is mine to edit (`doc_edit`) — it holds
  the author's decisions, and an export without it silently degrades
  to a generated minimum.

`kit_status` shows both sides: which kits are installed here, and
whether this project is a kit source (`isKitSource`).

## Making this project a source — three paths

1. **The kit to develop is already installed here** → promote it:
   `kit_promote` with the `kit_id` from `kit_status`. The manifest
   grows out of the install record — no re-clone. Every later
   `kit_update` of that kit keeps the manifest in sync.
2. **The kit is new — this project authored its content from the
   start** → `kit_source_create`: name, description, `origin_url`
   (required — a manifest without an origin does not parse), the
   `documents` paths and `settings` keys the kit consists of,
   optionally `inherits` (source urls, `project:<name>` allowed).
   Every listed artefact must exist; I gather the paths with
   `doc_list` / `doc_grep` first. The encrypted-secrets flag is
   computed from the settings' real types — I never set it by hand.
3. **A pre-multi-kit project** still carries the old
   `_vance/kit-manifest.yaml` → migration is a deliberate, explicit
   step the user runs from the admin surface (not one of my tools).
   I point it out and wait; `kit_source_create` is the wrong answer
   there.

Refusing is normal: a project can be the source of **one** kit. To
adjust an existing manifest's artefact lists I edit
`_vance/kits/manifest.yaml` with `doc_edit` — then validate (below).
Never list anything under `_vance/kits/` or `_vance/config/kit-*` —
kit-owned paths are reserved and rejected.

## Validating before the export

`kit_source_validate` is read-only and my pre-flight check. Run it
before every export and after every hand-edit. Errors mean the export
would not do what the manifest says (missing documents/settings, a
dishonest `hasEncryptedSecrets` flag, a broken descriptor); warnings
mean it works but deserves a look (no descriptor yet, name mismatch,
duplicates). Fix errors first — the export does not refuse on all of
them, some it would silently work around.

## Exporting

`kit_export` pushes the top layer — exactly what the manifest lists,
file-per-entity — into the origin repo (or the `url`/`branch`/`path` I
pass). Inherits are *referenced* in `kit.yaml`, never re-emitted as
files: a consumer resolves them from their own sources. When the kit
ships PASSWORD-type settings (`hasEncryptedSecrets`), the export needs
a `vault_password` — the credentials are decrypted with the server key
and immediately re-encrypted under the user's vault passphrase. I ask
the user for that passphrase in chat; I never invent or store one.

After the push I report what was written: kit name, commit, document
and setting counts, and where it went.

## Installing a kit under development elsewhere

Two ways, both fine while iterating: install from the exported git
repo, or — same tenant, no network — install from this project
directly with source url `project:<this-project>`. The second uses
the very same tree writer, so both produce the same kit; a project
never installs its own kit into itself.
