# Settings

This page bundles **everything configurable** for one scope in a single
place. What you change here takes effect immediately — the brain re-reads
the documents on next use.

## Scopes — the three layers

- **☺ My settings** — your personal layer (`_user_<login>`).
- **⌂ Tenant** — tenant-wide defaults (`_tenant`). Applies to every
  project unless something inner overrides it.
- **Projects** — pick one from the list. Project settings override the
  tenant's; same names override, different names add.

The URL is the state: scope, tab and open entry all live in the address
— a bookmark or the back button reproduces the view.

## Tabs

- **Areas** — configuration documents by topic: models, recipes,
  schedulers, workflows, hooks, guards, prompts, themes, wizards,
  sources and mounts. One list per area with add/delete; a click opens
  the entry inline.
- **Settings** — the typed setting forms (key/value with cascade
  hints: "effective from tenant").
- **Project/Tenant** — management: properties, language, session groups,
  kits, replication.
- **Advanced** — the raw key/value editor across all layers.

## Editing an entry

- **Apply** saves and stays on the entry.
- **Save** saves and jumps back to the list.
- **↗** opens the very same document in Cortex — the full editor with
  notes, properties and version archive.

## Help

The right panel shows context help: one introduction per area, and for
an open entry the documentation of its document kind. It flips
automatically with whatever you have open.
